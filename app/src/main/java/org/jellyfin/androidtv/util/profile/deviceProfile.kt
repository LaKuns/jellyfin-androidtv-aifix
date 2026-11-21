package org.jellyfin.androidtv.util.profile

import android.content.Context
import android.util.DisplayMetrics
import android.util.Size
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import androidx.preference.PreferenceManager
import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.AudioBehavior
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat
import org.jellyfin.androidtv.preference.constant.BitstreamAudioMode
import org.jellyfin.androidtv.preference.constant.HdrFormat
import org.jellyfin.androidtv.preference.constant.HdrOverrideMode
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.androidtv.util.PerformanceProfile
import org.jellyfin.playback.core.support.PlaybackThroughput
import org.jellyfin.androidtv.util.profile.codec.isPassthroughAudioAvailable
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.deviceprofile.DeviceProfileBuilder
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile
import kotlin.math.roundToInt

private fun UserPreferences.getMaxBitrate(): Int {
	var maxBitrate = this[UserPreferences.maxBitrate].toFloatOrNull()

	// The value "0" was used in an older release, make sure we prevent that from being used to avoid video not playing
	if (maxBitrate == null || maxBitrate < 0.01f) maxBitrate = UserPreferences.maxBitrate.defaultValue.toFloat()

	// Convert megabit to bit
	return (maxBitrate * 1_000_000).roundToInt()
}

private fun UserPreferences.getHdrRangeTypesFor(mode: HdrOverrideMode): Set<VideoRangeType> =
	HdrFormat.entries
		.filter { this[it.preference] == mode }
		.flatMapTo(mutableSetOf()) { it.videoRangeTypes }

/**
 * Whether a format may be handed to the television or the receiver instead of being decoded.
 *
 * A format is passed through when the device reports that its audio output supports it, or when
 * the user asked for it explicitly. The report of the device is the only source for the automatic
 * mode: a box that has no decoder for a format can still pass it through, and that is the normal
 * case for the surround formats on a TV box.
 */
private fun UserPreferences.isBitstreamAudioEnabled(context: Context, format: BitstreamAudioFormat): Boolean =
	when (this[format.preference]) {
		BitstreamAudioMode.AUTO -> isPassthroughAudioAvailable(context, format.mimeType)
		BitstreamAudioMode.ENABLE -> true
		BitstreamAudioMode.DISABLE -> false
	}

/**
 * Whether the user picked an audio behavior themselves.
 *
 * The automatic downmix is only applied while the preference is untouched, an explicit
 * choice of the user always wins.
 */
private fun isAudioBehaviorSet(context: Context): Boolean =
	PreferenceManager.getDefaultSharedPreferences(context).contains(AUDIO_BEHAVIOR_KEY)

private const val AUDIO_BEHAVIOR_KEY = "audio_behavior"

@JvmOverloads
fun createDeviceProfile(
	context: Context,
	userPreferences: UserPreferences,
	serverVersion: ServerVersion,
	sourceBitrate: Int? = null,
) = PerformanceProfile.isLowPerformanceDevice(context).let { lowPerformanceDevice ->
	// The device profile answers the question "what may the server send to this
	// device". A format that can only be decoded in software is not playable on
	// a TV box, so the server has to transcode it instead of direct playing it.
	//
	// The preference decides whether software decoders are part of that answer.
	// It is deliberately not coupled to the performance profile: a device that
	// is too slow for the full interface is not a device without a hardware
	// decoder, and a device with a hardware decoder is not necessarily able to
	// decode everything that a software decoder claims.
	val mediaTest = MediaCodecCapabilitiesTest(
		userPreferences[UserPreferences.softwareCodecsEnabled]
	)

	// A device without a hardware decoder for multichannel audio has to decode it in
	// software, which is expensive enough on weak TV chips to starve the network loader.
	// Asking the server to downmix the audio avoids that entirely. An explicit choice of
	// the user is never overridden.
	val hardwareAudioChannels = mediaTest.getMaxHardwareAudioChannels()
	val downMixByDevice = lowPerformanceDevice && hardwareAudioChannels < 3 && !isAudioBehaviorSet(context)

	// The requested bitrate is limited by what the connection is able to carry. A
	// stream that cannot be fetched as fast as it is consumed empties the buffer and
	// makes the playback stutter every few seconds, no matter how fast the server
	// transcodes it.
	//
	// The limit is based on the throughput of the connection, which is measured
	// without decoding anything. The throughput of the media downloads is not used
	// for this: a stream is downloaded at the speed it is played back, so that
	// measurement reports the bitrate of the stream that was requested and never the
	// capability of the connection. It would lower the request of every following
	// stream to the bitrate of the first one.
	//
	// The bitrate of the source file is taken into account as well: the server scales
	// a transcoded stream down when it is asked for less than the bitrate of the
	// source, which costs the resolution of the original.
	val throughputLimit = PlaybackThroughput.getStreamBitrateLimit(context, sourceBitrate)

	// A device that is fast enough plays the resolution of the source: the video pipeline
	// scales a 4K stream down to a 1080p screen for free, while asking the server for a
	// smaller picture costs it a full re-encode. Only a device that is too slow for 4K gets
	// a limit, and that limit is smaller than the display as well.
	val resolutionLimit = getResolutionLimit(context, lowPerformanceDevice)
	val displayHdr = getDisplayHdrCapabilities(context)

	createDeviceProfile(
		mediaTest = mediaTest,
		maxBitrate = userPreferences.getMaxBitrate().let { bitrate ->
			val deviceLimit = if (lowPerformanceDevice) {
				minOf(bitrate, PerformanceProfile.LOW_PERFORMANCE_MAX_VIDEO_BITRATE)
			} else {
				bitrate
			}

			when {
				// The connection was measured, so its throughput is the limit. The limit
				// already covers the bitrate of the source when the connection is able
				// to carry it.
				throughputLimit > 0 -> minOf(deviceLimit, throughputLimit)
				// Nothing was measured at all. The bitrate of the source is requested
				// so that the server does not scale the video down, which is the
				// resolution the file was stored with.
				sourceBitrate != null -> minOf(
					deviceLimit,
					sourceBitrate + PlaybackThroughput.AUDIO_HEADROOM,
				)
				// Without any measurement a conservative value is used, TV boxes are
				// usually limited by their wifi connection.
				lowPerformanceDevice -> minOf(
					deviceLimit,
					PerformanceProfile.LOW_PERFORMANCE_DEFAULT_VIDEO_BITRATE,
				)
				else -> deviceLimit
			}
		},
		isAC3PrefEnabled = userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.AC3),
		isEAC3PrefEnabled = userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.EAC3),
		isDTSPrefEnabled = userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.DTS),
		isTrueHDPrefEnabled = userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.TRUEHD),
		downMixAudio = userPreferences[UserPreferences.audioBehaviour] == AudioBehavior.DOWNMIX_TO_STEREO || downMixByDevice,
		assDirectPlay = userPreferences[UserPreferences.assDirectPlay],
		pgsDirectPlay = userPreferences[UserPreferences.pgsDirectPlay],
		userAVCLevel = userPreferences[UserPreferences.userAVCLevel].level,
		userHEVCLevel = userPreferences[UserPreferences.userHEVCLevel].level,
		forceEnabledHdr = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.ENABLE),
		forceDisabledHdr = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.DISABLE),
		disableHevcDirectPlay = userPreferences[UserPreferences.disableHevcDirectPlay],
		disableHevcMain10DirectPlay = userPreferences[UserPreferences.disableHevcMain10DirectPlay],
		maxVideoWidth = resolutionLimit?.width,
		maxVideoHeight = resolutionLimit?.height,
		displayHdr = displayHdr,
	)
}

fun createDeviceProfile(
	mediaTest: MediaCodecCapabilitiesTest,
	maxBitrate: Int,
	isAC3PrefEnabled: Boolean,
	isEAC3PrefEnabled: Boolean,
	isDTSPrefEnabled: Boolean,
	isTrueHDPrefEnabled: Boolean,
	downMixAudio: Boolean,
	assDirectPlay: Boolean,
	pgsDirectPlay: Boolean,
	userAVCLevel: Int?,
	userHEVCLevel: Int?,
	forceEnabledHdr: Set<VideoRangeType>,
	forceDisabledHdr: Set<VideoRangeType>,
	disableHevcDirectPlay: Boolean = false,
	disableHevcMain10DirectPlay: Boolean = false,
	maxVideoWidth: Int? = null,
	maxVideoHeight: Int? = null,
	displayHdr: DisplayHdrCapabilities = DisplayHdrCapabilities(),
) = buildDeviceProfile {
	// The formats that are handed to the television or the receiver are the only ones that need
	// a decision: the user may enable or disable them, and in the automatic mode the device is
	// asked whether its audio output supports them. A device without a decoder is not excluded,
	// because a track does not have to be decoded to be played - passing it through is the
	// normal way to play surround audio on a TV box.
	val allowedAudioCodecs = when {
		downMixAudio -> downmixSupportedAudioCodecs
		else -> supportedAudioCodecs
	}.filter { supportedPassthroughAudioCodecs ->
		when (supportedPassthroughAudioCodecs) {
			Codec.Audio.AC3 -> isAC3PrefEnabled
			Codec.Audio.EAC3 -> isEAC3PrefEnabled
			Codec.Audio.TRUEHD -> isTrueHDPrefEnabled
			Codec.Audio.DTS -> isDTSPrefEnabled
			else -> true
		}
	}.toTypedArray()

	// HEVC direct play can be disabled by the user. A device that reports HEVC support but can
	// only decode it in software stalls the playback completely, so it is safer to let the
	// server transcode those files to H.264 at the original resolution instead.
	val hevcDirectPlayEnabled = !disableHevcDirectPlay
	val hevcMain10DirectPlayEnabled = hevcDirectPlayEnabled && !disableHevcMain10DirectPlay

	val supportsHevc = mediaTest.supportsHevc() && hevcDirectPlayEnabled
	val supportsHevcMain10 = mediaTest.supportsHevcMain10() && hevcMain10DirectPlayEnabled
	val hevcMainLevel = userHEVCLevel ?: mediaTest.getHevcMainLevel()
	val hevcMain10Level = userHEVCLevel ?: mediaTest.getHevcMain10Level()
	val supportsAVC = mediaTest.supportsAVC()
	val supportsAVCHigh10 = mediaTest.supportsAVCHigh10()
	val avcMainLevel = userAVCLevel ?: mediaTest.getAVCMainLevel()
	val avcHigh10Level = userAVCLevel ?: mediaTest.getAVCHigh10Level()
	val supportsAV1 = mediaTest.supportsAV1()
	val supportsAV1Main10 = mediaTest.supportsAV1Main10()
	val supportsVC1 = mediaTest.supportsVc1()
	// VP8, VP9 and MPEG-4 part 2 are decoded in hardware by only a few of the
	// TV chips. A stream that the device can only decode in software is played
	// by the server instead, because a software decoder is much too slow for the
	// picture on a box with four small cores.
	val supportsVP8 = mediaTest.supportsVP8()
	val supportsVP9 = mediaTest.supportsVP9()
	val supportsMPEG4 = mediaTest.supportsMPEG4()
	val maxResolutionAVC = mediaTest.getMaxResolution(MimeTypes.VIDEO_H264).limitTo(maxVideoWidth, maxVideoHeight)
	val maxResolutionHevc = mediaTest.getMaxResolution(MimeTypes.VIDEO_H265).limitTo(maxVideoWidth, maxVideoHeight)
	val maxResolutionAV1 = mediaTest.getMaxResolution(MimeTypes.VIDEO_AV1).limitTo(maxVideoWidth, maxVideoHeight)
	val maxResolutionVC1 = mediaTest.getMaxResolution(MimeTypes.VIDEO_VC1).limitTo(maxVideoWidth, maxVideoHeight)

	/// HDR capabilities

	// Codecs
	// AV1
	val supportsAV1DolbyVision = mediaTest.supportsAV1DolbyVision()
	val supportsAV1HDR10 = mediaTest.supportsAV1HDR10()
	val supportsAV1HDR10Plus = mediaTest.supportsAV1HDR10Plus()

	// HEVC
	val supportsHevcDolbyVision = mediaTest.supportsHevcDolbyVision()
	val supportsHevcDolbyVisionEL = mediaTest.supportsHevcDolbyVisionEL()
	val supportsHevcHDR10 = mediaTest.supportsHevcHDR10()
	val supportsHevcHDR10Plus = mediaTest.supportsHevcHDR10Plus()

	name = "AndroidTV-Default"

	/// Bitrate
	maxStaticBitrate = maxBitrate
	maxStreamingBitrate = maxBitrate

	/// Transcoding profiles
	// Video
	// HEVC is only offered as a transcode target while the HEVC decoder is trusted. The server
	// keeps the profile of the source, so a Main 10 source would be transcoded to Main 10
	// HEVC again, which is exactly the format the device cannot decode.
	val hlsVideoCodecs = listOfNotNull(
		if (supportsHevc && hevcMain10DirectPlayEnabled) Codec.Video.HEVC else null,
		Codec.Video.H264
	).toTypedArray()

	transcodingProfile {
		type = DlnaProfileType.VIDEO
		context = EncodingContext.STREAMING

		container = Codec.Container.TS
		protocol = MediaStreamProtocol.HLS

		videoCodec(*hlsVideoCodecs)
		audioCodec(*hlsMpegTsAudioCodecs.filter(allowedAudioCodecs::contains).toTypedArray())

		copyTimestamps = false
		enableSubtitlesInManifest = true
	}

	transcodingProfile {
		type = DlnaProfileType.VIDEO
		context = EncodingContext.STREAMING

		container = Codec.Container.MP4
		protocol = MediaStreamProtocol.HLS

		videoCodec(*hlsVideoCodecs)
		audioCodec(*hlsFmp4AudioCodecs.filter(allowedAudioCodecs::contains).toTypedArray())

		copyTimestamps = false
		enableSubtitlesInManifest = true
	}

	// Audio
	transcodingProfile {
		type = DlnaProfileType.AUDIO
		context = EncodingContext.STREAMING

		container = Codec.Container.TS
		protocol = MediaStreamProtocol.HLS

		audioCodec(Codec.Audio.AAC)
	}

	/// Direct play profiles
	// Video
	directPlayProfile {
		type = DlnaProfileType.VIDEO

		container(
			Codec.Container.ASF,
			Codec.Container.HLS,
			Codec.Container.M4V,
			Codec.Container.MKV,
			Codec.Container.MOV,
			Codec.Container.MP4,
			Codec.Container.OGM,
			Codec.Container.OGV,
			Codec.Container.TS,
			Codec.Container.VOB,
			Codec.Container.WEBM,
			Codec.Container.WMV,
			Codec.Container.XVID,
		)

		// A codec that the device can only decode in software is not offered for
		// direct play: the server prepares such a stream instead of letting the
		// device spend its few cores on it.
		videoCodec(
			Codec.Video.AV1,
			Codec.Video.H264,
			Codec.Video.HEVC,
			Codec.Video.MPEG2VIDEO,
			Codec.Video.VC1,
		)
		if (supportsMPEG4) videoCodec(Codec.Video.MPEG)
		if (supportsVP8) videoCodec(Codec.Video.VP8)
		if (supportsVP9) videoCodec(Codec.Video.VP9)

		audioCodec(*allowedAudioCodecs)
	}

	// Audio
	directPlayProfile {
		type = DlnaProfileType.AUDIO

		audioCodec(*allowedAudioCodecs)
	}

	/// Codec profiles
	// H264 profile
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.H264

		conditions {
			when {
				!supportsAVC -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE inCollection listOfNotNull(
					"high",
					"main",
					"baseline",
					"constrained baseline",
					if (supportsAVCHigh10) "high 10" else null
				)
			}
		}
	}
	if (supportsAVC) {
		codecProfile {
			type = CodecType.VIDEO
			codec = Codec.Video.H264

			conditions {
				ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals avcMainLevel
			}

			applyConditions {
				ProfileConditionValue.VIDEO_PROFILE inCollection listOf(
					"high",
					"main",
					"baseline",
					"constrained baseline"
				)
			}
		}
	}
	if (supportsAVCHigh10) {
		codecProfile {
			type = CodecType.VIDEO
			codec = Codec.Video.H264

			conditions {
				ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals avcHigh10Level
			}

			applyConditions {
				ProfileConditionValue.VIDEO_PROFILE equals "high 10"
			}
		}
	}

	// H264 ref frames profile
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.H264

		conditions {
			ProfileConditionValue.REF_FRAMES lowerThanOrEquals 12
		}

		applyConditions {
			ProfileConditionValue.WIDTH greaterThanOrEquals 1200
		}
	}

	// H264 ref frames profile
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.H264

		conditions {
			ProfileConditionValue.REF_FRAMES lowerThanOrEquals 4
		}

		applyConditions {
			ProfileConditionValue.WIDTH greaterThanOrEquals 1900
		}
	}

	// HEVC profiles
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.HEVC

		conditions {
			when {
				!supportsHevc -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE inCollection listOfNotNull(
					"main",
					if (supportsHevcMain10) "main 10" else null
				)
			}
		}
	}
	if (supportsHevc) {
		codecProfile {
			type = CodecType.VIDEO
			codec = Codec.Video.HEVC

			conditions {
				ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals hevcMainLevel
			}

			applyConditions {
				ProfileConditionValue.VIDEO_PROFILE equals "main"
			}
		}
	}
	if (supportsHevcMain10) {
		codecProfile {
			type = CodecType.VIDEO
			codec = Codec.Video.HEVC

			conditions {
				ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals hevcMain10Level
			}

			applyConditions {
				ProfileConditionValue.VIDEO_PROFILE equals "main 10"
			}
		}
	}

	// AV1 profile
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.AV1

		conditions {
			when {
				!supportsAV1 -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				!supportsAV1Main10 -> ProfileConditionValue.VIDEO_PROFILE notEquals "main 10"
				else -> ProfileConditionValue.VIDEO_PROFILE notEquals "none"
			}
		}
	}

	// VC1 profile
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.VC1

		conditions {
			when {
				!supportsVC1 -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE notEquals "none"
			}
		}
	}

	// VP8, VP9 and MPEG-4 part 2 profiles
	//
	// A profile that no stream can match keeps the server from direct playing a
	// format that the device would have to decode in software.
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.VP8

		conditions {
			when {
				!supportsVP8 -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE notEquals "none"
			}
		}
	}

	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.VP9

		conditions {
			when {
				!supportsVP9 -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE notEquals "none"
			}
		}
	}

	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.MPEG

		conditions {
			when {
				!supportsMPEG4 -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE notEquals "none"
			}
		}
	}

	// A device that is too slow for 4K is asked for a smaller picture, because its decoder
	// cannot follow a 4K stream in real time. Every other device is limited by its decoder
	// only: a 4K stream on a 1080p screen is scaled down by the video pipeline, which is free,
	// while a server transcode would re-encode the whole film.
	if (maxVideoWidth != null && maxVideoHeight != null) {
		arrayOf(
			Codec.Video.AV1,
			Codec.Video.H264,
			Codec.Video.HEVC,
			Codec.Video.MPEG,
			Codec.Video.MPEG2VIDEO,
			Codec.Video.VP8,
			Codec.Video.VP9,
			Codec.Video.VC1,
		).forEach { videoCodec ->
			codecProfile {
				type = CodecType.VIDEO
				codec = videoCodec

				conditions {
					ProfileConditionValue.WIDTH lowerThanOrEquals maxVideoWidth
					ProfileConditionValue.HEIGHT lowerThanOrEquals maxVideoHeight
				}
			}
		}
	}

	// Get max resolutions for common codecs
	// AVC
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.H264

		conditions {
			ProfileConditionValue.WIDTH lowerThanOrEquals maxResolutionAVC.width
			ProfileConditionValue.HEIGHT lowerThanOrEquals maxResolutionAVC.height
		}
	}

	// HEVC
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.HEVC

		conditions {
			ProfileConditionValue.WIDTH lowerThanOrEquals maxResolutionHevc.width
			ProfileConditionValue.HEIGHT lowerThanOrEquals maxResolutionHevc.height
		}
	}

	// AV1
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.AV1

		conditions {
			ProfileConditionValue.WIDTH lowerThanOrEquals maxResolutionAV1.width
			ProfileConditionValue.HEIGHT lowerThanOrEquals maxResolutionAV1.height
		}
	}

	// VC1
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.VC1

		conditions {
			ProfileConditionValue.WIDTH lowerThanOrEquals maxResolutionVC1.width
			ProfileConditionValue.HEIGHT lowerThanOrEquals maxResolutionVC1.height
		}
	}

	/// HDR exclude list

	// HDR10+ is carried by 10 bit AV1 or HEVC. The decoder of a television rarely
	// reports the HDR10+ profile, but a television that can show the format
	// handles it. The server tone maps such a stream away otherwise, which costs
	// the server a lot of work and loses the quality of the picture.
	val av1Hdr10Plus = supportsAV1Main10 && (supportsAV1HDR10Plus || displayHdr.supportsHdr10Plus)
	val hevcHdr10Plus = supportsHevcMain10 && (supportsHevcHDR10Plus || displayHdr.supportsHdr10Plus)

	// HLG is a property of the television as much as of the decoder.
	val hevcHlg = supportsHevcMain10 && displayHdr.supportsHlg

	val unsupportedRangeTypesAv1 = buildSet {
		add(VideoRangeType.DOVI_INVALID)

		if (!supportsAV1DolbyVision) {
			add(VideoRangeType.DOVI)
			if (!supportsAV1HDR10) add(VideoRangeType.DOVI_WITH_HDR10)
			if (!supportsAV1HDR10Plus) add(VideoRangeType.DOVI_WITH_HDR10_PLUS)
		}

		if (!av1Hdr10Plus) {
			add(VideoRangeType.HDR10_PLUS)

			if (!mediaTest.supportsAV1HDR10()) add(VideoRangeType.HDR10)
		}
	} - forceEnabledHdr + forceDisabledHdr

	val unsupportedRangeTypesHevc = buildSet {
		add(VideoRangeType.DOVI_INVALID)

		if (!supportsHevcDolbyVisionEL) {
			if (
				!KnownDefects.unreportedDoviProfile7Support ||
				!supportsHevcDolbyVision ||
				!supportsHevcMain10 ||
				!supportsHevcHDR10
			) {
				add(VideoRangeType.DOVI_WITH_EL)

				if (!supportsHevcHDR10Plus && !KnownDefects.hevcDoviHdr10PlusBug) {
					add(VideoRangeType.DOVI_WITH_ELHDR10_PLUS)
				}
			}

			if (!supportsHevcDolbyVision) {
				add(VideoRangeType.DOVI)
				if (!supportsHevcHDR10) add(VideoRangeType.DOVI_WITH_HDR10)
				if (!supportsHevcHDR10Plus && !KnownDefects.hevcDoviHdr10PlusBug) add(VideoRangeType.DOVI_WITH_HDR10_PLUS)
			}
		}

		if (!hevcHdr10Plus) {
			add(VideoRangeType.HDR10_PLUS)
			if (!supportsHevcHDR10) add(VideoRangeType.HDR10)
		}

		if (!hevcHlg) add(VideoRangeType.HLG)

		if (KnownDefects.hevcDoviHdr10PlusBug) {
			add(VideoRangeType.DOVI_WITH_HDR10_PLUS)
			add(VideoRangeType.DOVI_WITH_ELHDR10_PLUS)
		}
	} - forceEnabledHdr + forceDisabledHdr

	// Note: The codec profiles use a workaround to create correct behavior
	// The notEquals condition will always fail the ConditionProcessor test in the server so we use applyConditions to only have the codec
	// profile be active when the media in question uses one of the unsupported range types. The server will then use the value of the
	// notEquals in the StreamBuilder to create a correct transcode pipeline

	// Codecs
	// AV1
	if (unsupportedRangeTypesAv1.isNotEmpty()) codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.AV1

		conditions {
			ProfileConditionValue.VIDEO_RANGE_TYPE notEquals unsupportedRangeTypesAv1.joinToString("|") { it.serialName }
		}

		applyConditions {
			ProfileConditionValue.VIDEO_RANGE_TYPE inCollection unsupportedRangeTypesAv1.map { it.serialName }
		}
	}

	// HEVC
	if (unsupportedRangeTypesHevc.isNotEmpty()) codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.HEVC

		conditions {
			ProfileConditionValue.VIDEO_RANGE_TYPE notEquals unsupportedRangeTypesHevc.joinToString("|") { it.serialName }
		}

		applyConditions {
			ProfileConditionValue.VIDEO_RANGE_TYPE inCollection unsupportedRangeTypesHevc.map { it.serialName }
		}
	}

	// Audio channel profile
	// The real limit of the device is reported so the server downmixes the audio to a
	// channel layout the TV can decode. TV chips commonly only decode stereo AAC and a
	// multichannel stream that cannot be decoded aborts the playback completely.
	val maxAudioChannels = when {
		downMixAudio -> 2
		else -> mediaTest.getMaxAudioChannels().takeIf { it > 0 }?.coerceAtMost(8) ?: 8
	}

	codecProfile {
		type = CodecType.VIDEO_AUDIO

		conditions {
			ProfileConditionValue.AUDIO_CHANNELS lowerThanOrEquals maxAudioChannels
		}
	}

	/// Subtitle profiles
	// Jellyfin server only supports WebVTT subtitles in HLS, other text subtitles will be converted to WebVTT
	// which we do not want so only allow delivery over HLS for WebVTT subtitles
	subtitleProfile(Codec.Subtitle.VTT, embedded = true, hls = true, external = true)
	subtitleProfile(Codec.Subtitle.WEBVTT, embedded = true, hls = true, external = true)

	subtitleProfile(Codec.Subtitle.SRT, embedded = true, external = true)
	subtitleProfile(Codec.Subtitle.SUBRIP, embedded = true, external = true)
	subtitleProfile(Codec.Subtitle.TTML, embedded = true, external = true)

	// Not all subtitles can be loaded standalone by the player
	subtitleProfile(Codec.Subtitle.DVBSUB, embedded = true, encode = true)
	subtitleProfile(Codec.Subtitle.DVDSUB, embedded = true, encode = true)
	subtitleProfile(Codec.Subtitle.IDX, embedded = true, encode = true)
	subtitleProfile(Codec.Subtitle.PGS, embedded = pgsDirectPlay, encode = true)
	subtitleProfile(Codec.Subtitle.PGSSUB, embedded = pgsDirectPlay, encode = true)

	// ASS/SSA is supported via libass extension
	subtitleProfile(Codec.Subtitle.ASS, encode = true, embedded = assDirectPlay, external = assDirectPlay)
	subtitleProfile(Codec.Subtitle.SSA, encode = true, embedded = assDirectPlay, external = assDirectPlay)
}

// Little helper function to more easily define subtitle profiles
private fun DeviceProfileBuilder.subtitleProfile(
	format: String,
	embedded: Boolean = false,
	external: Boolean = false,
	hls: Boolean = false,
	encode: Boolean = false,
) {
	if (embedded) subtitleProfile(format, SubtitleDeliveryMethod.EMBED)
	if (external) subtitleProfile(format, SubtitleDeliveryMethod.EXTERNAL)
	if (hls) subtitleProfile(format, SubtitleDeliveryMethod.HLS)
	if (encode) subtitleProfile(format, SubtitleDeliveryMethod.ENCODE)
}

/**
 * The HDR formats that the television can show.
 *
 * A stream in a format that the television cannot show is tone mapped by the
 * server, which costs the server a lot of work and loses the quality of the
 * picture. The decoder alone does not tell whether the television can show it:
 * HDR10+ and HLG are properties of the display.
 */
data class DisplayHdrCapabilities(
	val supportsHdr10: Boolean = false,
	val supportsHdr10Plus: Boolean = false,
	val supportsHlg: Boolean = false,
)

private fun getDisplayHdrCapabilities(context: Context): DisplayHdrCapabilities {
	if (!AndroidVersion.isAtLeastN) return DisplayHdrCapabilities()

	val display = ContextCompat.getDisplayOrDefault(context)
	val types = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()

	return DisplayHdrCapabilities(
		supportsHdr10 = types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10),
		supportsHdr10Plus = types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS),
		supportsHlg = types.contains(Display.HdrCapabilities.HDR_TYPE_HLG),
	)
}

private fun Size.limitTo(maxWidth: Int?, maxHeight: Int?): Size = Size(
	width.coerceAtMost(maxWidth ?: width),
	height.coerceAtMost(maxHeight ?: height),
)

/**
 * The largest resolution that the server may send to a device that is too slow for 4K.
 *
 * The limit is deliberately not the size of the display. A device whose decoder handles 4K
 * plays a 4K stream on a 1080p screen without the server doing anything: the video pipeline
 * scales the picture down, which costs nothing but a little decoding power, while a server
 * transcode costs the server a full re-encode and the picture a generation of quality. The
 * bitrate limit still applies, so a stream that the connection cannot carry is scaled down by
 * the server on its own.
 *
 * A device that is too slow for the full interface is the exception: its decoder cannot
 * sustain 4K in real time and a stream that the decoder cannot follow stutters, so the server
 * is asked for a smaller picture in that case.
 *
 * @return The limit or null when the decoder is the only limit
 */
private fun getResolutionLimit(context: Context, lowPerformanceDevice: Boolean): Size? {
	if (!lowPerformanceDevice) return null

	val display = ContextCompat.getDisplayOrDefault(context)
	val metrics = DisplayMetrics()
	display.getRealMetrics(metrics)

	val displayWidth = metrics.widthPixels
	val displayHeight = metrics.heightPixels
	if (displayWidth <= 0 || displayHeight <= 0) return null

	return Size(
		minOf(PerformanceProfile.LOW_PERFORMANCE_MAX_VIDEO_WIDTH, displayWidth),
		minOf(PerformanceProfile.LOW_PERFORMANCE_MAX_VIDEO_HEIGHT, displayHeight),
	)
}
