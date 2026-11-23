package org.jellyfin.playback.media3.exoplayer.support

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import timber.log.Timber

/**
 * A [DefaultLoadControl] whose byte budget is fixed and which never lets that budget starve the
 * loader.
 *
 * [DefaultLoadControl] targets roughly 144 MB for a normal video stream (2000 buffer segments of
 * 64 KiB for the video track alone), more than the 128 MB heap of most TV boxes, so the budget has
 * to be reduced. A pure byte target can however deadlock the player: when the budget is reached
 * while the remaining buffer is nearly empty the loader stops, the buffer drains and media3 aborts
 * the playback with "Player stuck buffering and not loading".
 *
 * This control therefore keeps loading while less than [minimumBufferedDurationUs] of media is
 * buffered, even if the budget is already used.
 */
@OptIn(UnstableApi::class)
class CappedLoadControl @JvmOverloads constructor(
	private val targetBufferBytes: Int,
	private val minimumBufferedDurationUs: Long = DEFAULT_MINIMUM_BUFFERED_DURATION_US,
) : DefaultLoadControl() {
	private var lastForcedLoadLogAtMs: Long = 0L

	override fun calculateTargetBufferBytes(
		parameters: LoadControl.Parameters,
		selectedTracks: Array<ExoTrackSelection>,
	): Int = targetBufferBytes

	override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
		if (super.shouldContinueLoading(parameters)) return true

		// The byte budget is reached. Keep loading when the buffer is about to run dry, otherwise
		// the player cannot recover from the stall.
		if (parameters.bufferedDurationUs >= minimumBufferedDurationUs) return false

		val now = SystemClock.elapsedRealtime()
		if (now - lastForcedLoadLogAtMs >= FORCED_LOAD_LOG_INTERVAL_MS) {
			lastForcedLoadLogAtMs = now
			Timber.i(
				"Load control: loading past the byte budget, %d ms buffered",
				parameters.bufferedDurationUs / 1000,
			)
		}
		return true
	}

	companion object {
		private const val DEFAULT_MINIMUM_BUFFERED_DURATION_US = 3_000_000L
		private const val FORCED_LOAD_LOG_INTERVAL_MS = 2_000L
	}
}
