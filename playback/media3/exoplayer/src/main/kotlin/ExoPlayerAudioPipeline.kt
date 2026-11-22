package org.jellyfin.playback.media3.exoplayer

import android.media.audiofx.LoudnessEnhancer
import timber.log.Timber

class ExoPlayerAudioPipeline {
	private var loudnessEnhancer: LoudnessEnhancer? = null
	var normalizationGain: Float? = null
		set(value) {
			Timber.d("Normalization gain changed to $value")
			field = value
			applyGain()
		}

	/**
	 * The gain the user configured for the audio that the device decodes itself.
	 *
	 * A television raises the level of a bitstream that it decodes on its own, so the same track
	 * sounds quieter when the application decodes it. The old player compensated that with an
	 * enhancer, and the new one has to do the same or the level changes with the player.
	 */
	var userGainMillibels: Int = 0
		set(value) {
			if (field == value) return
			Timber.d("User audio gain changed to $value mB")
			field = value
			applyGain()
		}

	fun setAudioSessionId(audioSessionId: Int) {
		Timber.d("Audio session id changed to $audioSessionId")

		// Re-create loudness enhancer for normalization gain
		loudnessEnhancer?.release()
		loudnessEnhancer = runCatching { LoudnessEnhancer(audioSessionId) }
			.onFailure { Timber.w(it, "Failed to create LoudnessEnhancer") }
			.getOrNull()

		// Re-apply current normalization gain
		applyGain()
	}

	private fun applyGain() {
		if (loudnessEnhancer == null) {
			Timber.d("LoudnessEnhancer is not initialized")
			return
		}

		// The normalization gain is a level in decibels, the enhancer takes millibels.
		val normalizationMillibels = normalizationGain?.times(100f)?.toInt() ?: 0
		// A gain of zero means no processing at all: the enhancer is disabled then.
		val targetGain = (normalizationMillibels + userGainMillibels).takeIf { it != 0 }

		Timber.d("Applying gain (targetGain=$targetGain)")
		runCatching {
			loudnessEnhancer?.setTargetGain(targetGain ?: 0)
		}.onSuccess {
			loudnessEnhancer?.setEnabled(targetGain != null)
		}.onFailure { error ->
			Timber.e(error, "Failed to apply gain of $targetGain")
			loudnessEnhancer?.setEnabled(false)
		}
	}
}
