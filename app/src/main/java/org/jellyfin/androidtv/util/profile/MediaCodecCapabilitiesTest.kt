package org.jellyfin.androidtv.util.profile

import android.media.MediaCodecList
import android.util.Size
import androidx.media3.common.MimeTypes
import org.jellyfin.androidtv.util.profile.codec.Av1CodecCapabilities
import org.jellyfin.androidtv.util.profile.codec.AvcCodecCapabilities
import org.jellyfin.androidtv.util.profile.codec.HevcCodecCapabilities
import org.jellyfin.androidtv.util.profile.codec.MediaCodecQuery

class MediaCodecCapabilitiesTest(
	private val softwareCodecsEnabled: Boolean,
	/**
	 * Whether a hardware decoder that does not list Main 10 is probed for it. The profile
	 * list of a decoder is not always complete, see [HevcMain10Probe].
	 */
	private val probeHevcMain10: Boolean = false,
) {
	private val mediaCodecList by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }
	private val codecQuery by lazy { MediaCodecQuery(mediaCodecList, softwareCodecsEnabled) }
	private val avc by lazy { AvcCodecCapabilities(codecQuery) }
	private val hevc by lazy { HevcCodecCapabilities(codecQuery, probeHevcMain10) }
	private val av1 by lazy { Av1CodecCapabilities(codecQuery) }

	fun supportsAV1(): Boolean = av1.supportsAv1()

	fun supportsAV1Main10(): Boolean = av1.supportsAv1Main10()

	fun supportsAV1DolbyVision(): Boolean = av1.supportsAv1DolbyVision()

	fun supportsAV1HDR10(): Boolean = av1.supportsAv1HDR10()

	fun supportsAV1HDR10Plus(): Boolean = av1.supportsAv1HDR10Plus()

	fun supportsAVC(): Boolean = avc.supportsAvc()

	fun supportsAVCHigh10(): Boolean = avc.supportsAvcHigh10()

	fun getAVCMainLevel(): Int = avc.getMainLevel()

	fun getAVCHigh10Level(): Int = avc.getHigh10Level()

	fun supportsHevc(): Boolean = hevc.supportsHevc()

	fun supportsHevcMain10(): Boolean = hevc.supportsHevcMain10()

	fun supportsHevcDolbyVision(): Boolean = hevc.supportsHevcDolbyVision()

	fun supportsHevcDolbyVisionEL(): Boolean = hevc.supportsHevcDolbyVisionEL()

	fun supportsHevcHDR10(): Boolean = hevc.supportsHevcHDR10()

	fun supportsHevcHDR10Plus(): Boolean = hevc.supportsHevcHDR10Plus()

	fun getHevcMainLevel(): Int = hevc.getMainLevel()

	fun getHevcMain10Level(): Int = hevc.getMain10Level()

	fun supportsVc1(): Boolean = codecQuery.hasCodecForMime(MimeTypes.VIDEO_VC1)

	/**
	 * VP8 is decoded in hardware by only a few of the TV chips, but the format is small enough
	 * for a software decoder to follow, so a decoder of any kind counts.
	 */
	fun supportsVP8(): Boolean = codecQuery.hasAnyDecoderForMime(MimeTypes.VIDEO_VP8)

	/**
	 * VP9 is decoded in hardware by only a few of the TV chips. A software decoder follows a
	 * 1080p stream, which is the resolution that the format is used at.
	 */
	fun supportsVP9(): Boolean = codecQuery.hasAnyDecoderForMime(MimeTypes.VIDEO_VP9)

	/**
	 * MPEG-1 and MPEG-2 video are old enough that no decoder reports them in hardware, but
	 * every player decodes them in software.
	 */
	fun supportsMpeg2(): Boolean = codecQuery.hasAnyDecoderForMime(MimeTypes.VIDEO_MPEG2)

	fun getMaxResolution(mime: String): Size = codecQuery.getMaxResolution(mime)

	/**
	 * Whether the device is able to decode the audio format itself.
	 *
	 * Software decoders count as well: the audio of a TV box is decoded on the cpu for the
	 * most part, and a format without any decoder cannot be played at all.
	 */
	fun supportsAudioCodec(mime: String): Boolean = codecQuery.hasAnyDecoderForMime(mime)

	fun getMaxAudioChannels(): Int = codecQuery.getMaxAudioChannels(MimeTypes.AUDIO_AAC)

	/**
	 * The highest number of channels a hardware decoder can handle. Multichannel audio that
	 * has to be decoded in software is expensive enough on weak TV chips to starve the
	 * network loader and make the playback stutter.
	 */
	fun getMaxHardwareAudioChannels(): Int = codecQuery.getMaxHardwareAudioChannels(MimeTypes.AUDIO_AAC)
}
