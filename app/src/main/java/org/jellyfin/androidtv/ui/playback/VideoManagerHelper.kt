package org.jellyfin.androidtv.ui.playback

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.core.net.toUri
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.playback.media3.exoplayer.mapping.getFfmpegSubtitleMimeType
import org.jellyfin.sdk.model.api.MediaStream
import timber.log.Timber

/**
 * Return the media type for the codec found in this media stream. First tries to infer the media type from the streams delivery URL and
 * falls back to the original stream codec.
 */
fun getSubtitleMediaStreamCodec(stream: MediaStream): String {
	val codec = requireNotNull(stream.codec)
	val codecMediaType = getFfmpegSubtitleMimeType(codec, "").ifBlank { null }

	val urlSubtitleExtension = stream.deliveryUrl?.toUri()?.lastPathSegment?.split('.')?.last()
	val urlExtensionMediaType = urlSubtitleExtension?.let { getFfmpegSubtitleMimeType(it, "") }?.ifBlank { null }

	return urlExtensionMediaType ?: codecMediaType ?: urlSubtitleExtension ?: codec
}

private var audioEffect: AudioEffect? = null

/**
 * Raises the level of the audio the device decodes itself.
 *
 * The level was measured on the material this is meant for: the film "给阿嬷的情书" has an
 * integrated loudness of -23.1 LUFS and a true peak of -3.9 dBFS. At six decibels the loudest
 * moment of the film moves two decibels over full scale, which the enhancer limits without ever
 * clipping a sample; every step below that is free of any processing.
 *
 * @param audioSessionId The audio session of the player
 * @param millibels The gain in millibels, zero disables the effect
 */
private var audioBoost: LoudnessEnhancer? = null

fun applyAudioBoost(audioSessionId: Int, millibels: Int) {
	Timber.i("Audio boost for session %s: %s mB", audioSessionId, millibels)

	audioBoost?.release()
	audioBoost = null

	if (millibels <= 0) return

	audioBoost = runCatching {
		LoudnessEnhancer(audioSessionId).apply {
			setTargetGain(millibels)
			setEnabled(true)
		}
	}.onFailure {
		Timber.w(it, "Unable to enable the audio boost")
	}.getOrNull()
}

fun applyAudioNightmode(audioSessionId: Int) {
	Timber.i("Enabling audio night mode for session $audioSessionId")

	audioEffect?.release()

	audioEffect = when {
		// Use dynamics processinc on Android 9 (API 28) and newer
		AndroidVersion.isAtLeastP -> DynamicsProcessing(0, audioSessionId, null).apply {
			setLimiterAllChannelsTo(
				DynamicsProcessing.Limiter(
					true,
					true,
					1,
					30f,
					300f,
					10f,
					-24f,
					3f
				)
			)

			setPreEqAllChannelsTo(DynamicsProcessing.Eq(true, true, 5).apply {
				getBand(0).gain = 0f
				getBand(1).gain = 0.02f
				getBand(2).gain = 0.03f
				getBand(3).gain = 0.02f
				getBand(4).gain = 0f
			})

			enabled = true
		}

		// Use more simple equalizer implementation on older versions
		else -> Equalizer(0, audioSessionId).apply {
			setBandLevel(0, 0)
			setBandLevel(1, 2)
			setBandLevel(2, 3)
			setBandLevel(3, 2)
			setBandLevel(4, 0)
			enabled = true
		}
	}
}
