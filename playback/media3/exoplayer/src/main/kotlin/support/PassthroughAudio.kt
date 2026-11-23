package org.jellyfin.playback.media3.exoplayer.support

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import timber.log.Timber

/**
 * Compressed audio formats that can travel to the television as a bitstream.
 *
 * The HAL of this class of device declares its pass-through output as an IEC61937 direct
 * output, but the framework does not report those profiles through
 * `AudioManager.getDirectProfilesForAttributes()` (it returns an empty list on Android 14),
 * so media3 believes that only stereo PCM is possible and decodes everything locally.
 *
 * Probing `AudioTrack.isDirectPlaybackSupported()` asks the HAL directly, which is the
 * capability that actually matters for whether a bitstream output can be opened.
 */
@OptIn(UnstableApi::class)
object PassthroughAudioSupport {
	private const val MAX_CHANNEL_COUNT = 8
	private const val SAMPLE_RATE = 48_000

	private val ALLOWED_ENCODINGS = intArrayOf(
		AudioFormat.ENCODING_AC3,
		AudioFormat.ENCODING_E_AC3,
		AudioFormat.ENCODING_DTS,
		AudioFormat.ENCODING_DTS_HD,
		AudioFormat.ENCODING_DOLBY_TRUEHD,
	)

	private val CHANNEL_MASKS = intArrayOf(
		AudioFormat.CHANNEL_OUT_5POINT1,
		AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
	)

	/**
	 * The audio capabilities of this device, extended with the compressed formats its HAL
	 * accepts as a pass-through stream.
	 *
	 * @param allowedEncodings encodings the user wants to pass through; the others are decoded
	 * locally as before. Defaults to every surround format the decoder bundle supports.
	 */
	@JvmStatic
	@JvmOverloads
	fun capabilities(
		context: Context,
		allowedEncodings: IntArray = ALLOWED_ENCODINGS,
	): AudioCapabilities {
		val audioAttributes = AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_MEDIA)
			.setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
			.build()

		val encodings = mutableListOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)
		for (encoding in allowedEncodings) {
			val supported = CHANNEL_MASKS.any { channelMask ->
				AudioTrack.isDirectPlaybackSupported(
					AudioFormat.Builder()
						.setEncoding(encoding)
						.setSampleRate(SAMPLE_RATE)
						.setChannelMask(channelMask)
						.build(),
					audioAttributes,
				)
			}
			Timber.i(
				"Pass-through probe: encoding=0x%x supported=%s",
				encoding,
				supported,
			)
			if (supported) encodings.add(encoding)
		}

		return AudioCapabilities(encodings.toIntArray(), MAX_CHANNEL_COUNT)
	}
}

/**
 * Renderers factory that hands media3 the device capabilities from [PassthroughAudioSupport]
 * instead of the (incomplete) framework report, so that a supported bitstream is sent to the
 * television as is instead of being decoded and downmixed to stereo.
 */
@OptIn(UnstableApi::class)
class PassthroughRenderersFactory @JvmOverloads constructor(
	context: Context,
	private val allowedEncodings: IntArray = intArrayOf(
		AudioFormat.ENCODING_AC3,
		AudioFormat.ENCODING_E_AC3,
		AudioFormat.ENCODING_DTS,
		AudioFormat.ENCODING_DTS_HD,
		AudioFormat.ENCODING_DOLBY_TRUEHD,
	),
) : DefaultRenderersFactory(context) {

	private var cachedCapabilities: AudioCapabilities? = null

	@Suppress("DEPRECATION")
	override fun buildAudioSink(
		context: Context,
		enableFloatOutput: Boolean,
		enableAudioTrackPlaybackParams: Boolean,
	): AudioSink {
		val capabilities = cachedCapabilities
			?: PassthroughAudioSupport.capabilities(context, allowedEncodings).also {
				cachedCapabilities = it
			}

		// `DefaultAudioSink.Builder.setAudioCapabilities` is only honoured while the builder has no
		// Context: with a Context, media3 replaces the capabilities with the ones it probes from the
		// framework, and that probe reports no pass-through profile for a HAL that only advertises a
		// dynamic IEC61937 direct output. The context-less builder therefore keeps the capabilities
		// that `AudioTrack.isDirectPlaybackSupported` reported, which is what decides whether the
		// bitstream can be sent to the television.
		return DefaultAudioSink.Builder()
			.setAudioCapabilities(capabilities)
			.setEnableFloatOutput(enableFloatOutput)
			.setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
			.build()
	}
}
