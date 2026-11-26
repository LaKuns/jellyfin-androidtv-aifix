package org.jellyfin.androidtv.ui.player.video.toast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState

/**
 * Shows the play/pause icon in the middle of the screen when the state of the playback changes.
 *
 * The state is not stable while the user seeks: the player briefly reports "paused" or "playing"
 * around every seek, and the toast of that short state change is what made a pause icon flash in
 * the middle of the picture while the progress bar was being dragged. Every state change is
 * therefore held for a moment before it becomes a toast, and nothing is shown while the user is
 * scrubbing at all.
 */
@Composable
fun rememberPlaybackManagerMediaToastEmitter(
	playbackManager: PlaybackManager,
	mediaToastRegistry: MediaToastRegistry,
) {
	LaunchedEffect(playbackManager) {
		var active = false

		playbackManager.state.playState
			.filter { playState ->
				// Wait until we received our first "playing" state before emitting play/pause events
				// this avoids an immediate toast from showing when the video player is opened
				if (!active) {
					active = playState == PlayState.PLAYING
					false
				} else {
					active
				}
			}
			.collectLatest { playState ->
				// A state that only lasts for a moment is a seek, not a command of the viewer.
				delay(STATE_SETTLE_DELAY_MS)
				// The scrubbing state is the reliable signal for a seek; a seek can report a
				// paused or playing state after the bar already stopped moving.
				if (playbackManager.state.scrubbing.value) return@collectLatest

				when (playState) {
					PlayState.PLAYING -> mediaToastRegistry.emit(R.drawable.ic_play)
					PlayState.PAUSED -> mediaToastRegistry.emit(R.drawable.ic_pause)
					else -> Unit
				}
			}
	}
}

private const val STATE_SETTLE_DELAY_MS = 350L

