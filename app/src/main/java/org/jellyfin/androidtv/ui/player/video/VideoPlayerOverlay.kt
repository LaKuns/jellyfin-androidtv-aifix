package org.jellyfin.androidtv.ui.player.video

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.player.base.PlayerKeyHandler
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayLayout
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.koin.compose.koinInject
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Composable
fun VideoPlayerOverlay(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	mediaToastRegistry: MediaToastRegistry,
) {
	val playState by playbackManager.state.playState.collectAsState()
	val visibilityState = rememberPlayerOverlayVisibility(timeout = if (playState == PlayState.PAUSED) null else 5.seconds)

	// Entering the player starts silently: the controls are only shown after an explicit action
	// of the viewer. A seek makes the player report a short paused/playing change; that change
	// must not open the bar, otherwise a held direction key loses its target (the focus is
	// pulled into the controls) and the picture keeps the bar on top.
	var lastSeekAtMs by remember { mutableStateOf(0L) }
	LaunchedEffect(playState) {
		if (playState == PlayState.PAUSED) {
			delay(400)
			if (SystemClock.elapsedRealtime() - lastSeekAtMs > 800) visibilityState.show()
		}
	}

	// Feedback for the direct seek of the hidden overlay: one short line instead of the full bar.
	var seekHint by remember { mutableStateOf<String?>(null) }
	var seekHintGeneration by remember { mutableStateOf(0) }
	LaunchedEffect(seekHintGeneration) {
		if (seekHint != null) {
			delay(1_200)
			seekHint = null
		}
	}

	var showSettings by remember { mutableStateOf(false) }
	LaunchedEffect(showSettings) {
		// While the settings sheet is open it owns the focus: hide the control bar, otherwise
		// its buttons keep the focus and steal the key presses of the sheet.
		if (showSettings) visibilityState.hide() else visibilityState.show()
	}

	// The remote control is handled by the activity while the player is on screen, so it works
	// even when the overlay hid itself and nothing holds the focus (see PlayerKeyHandler).
	val currentVisibilityState = rememberUpdatedState(visibilityState)
	DisposableEffect(Unit) {
		PlayerKeyHandler.setHandler { keyCode, repeatCount ->
			val state = currentVisibilityState.value
			when {
				// Back closes the controls first and leaves the player on the second press.
				keyCode == KeyEvent.KEYCODE_BACK -> if (state.visible) {
					state.hide()
					true
				} else {
					false
				}

				!state.visible -> when (keyCode) {
					// Seek without opening the bar, the viewer sees what was skipped in one line.
					KeyEvent.KEYCODE_DPAD_LEFT -> {
						lastSeekAtMs = SystemClock.elapsedRealtime()
						seekHint = seek(playbackManager, forward = false, repeatCount = repeatCount)
						seekHintGeneration++
						true
					}

					KeyEvent.KEYCODE_DPAD_RIGHT -> {
						lastSeekAtMs = SystemClock.elapsedRealtime()
						seekHint = seek(playbackManager, forward = true, repeatCount = repeatCount)
						seekHintGeneration++
						true
					}

					KeyEvent.KEYCODE_DPAD_CENTER,
					KeyEvent.KEYCODE_ENTER,
					KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
						when (playState) {
							PlayState.PLAYING -> {
								playbackManager.state.pause()
								// A pause is an explicit action: show the controls with it.
								visibilityState.show()
							}

							PlayState.PAUSED -> playbackManager.state.unpause()
							else -> playbackManager.state.play()
						}
						true
					}

					// Every other key only wakes the bar; the next press already moves the focus.
					else -> {
						state.show()
						true
					}
				}

				else -> false
			}
		}
		onDispose { PlayerKeyHandler.setHandler(null) }
	}

	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value

	Box(modifier = modifier) {
		PlayerOverlayLayout(
			visibilityState = visibilityState,
			header = {
				Column {
					VideoPlayerHeader(
						item = item,
					)
				}
			},
			controls = {
				VideoPlayerControls(
					playbackManager = playbackManager,
					onMoreOptionsClick = {
						showSettings = true
					},
					onHideControls = {
						visibilityState.hide()
					},
				)
			},
		)

		seekHint?.let { hint ->
			org.jellyfin.androidtv.ui.base.Text(
				text = hint,
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(bottom = 160.dp)
					.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
					.padding(horizontal = 20.dp, vertical = 10.dp),
				color = Color.White,
			)
		}

		// Right-side sheet with the playback info and the rarely changed settings.
		PlayerSettingsPanel(
			playbackManager = playbackManager,
			visible = showSettings,
			onDismiss = { showSettings = false },
		)

		MediaToasts(mediaToastRegistry)
	}
}

/**
 * Seeks the running item and returns the line that is shown to the viewer.
 *
 * A held key accelerates the seek, see [seekAcceleration]: the repeat counter grows while the
 * key is down, so the step grows from the configured amount to three and finally six times that
 * amount. Without the acceleration a two minute skip would take twelve presses on a ten second
 * step.
 */
private fun seek(playbackManager: PlaybackManager, forward: Boolean, repeatCount: Int): String {
	val baseAmount = if (forward) {
		playbackManager.options.defaultFastForwardAmount()
	} else {
		playbackManager.options.defaultRewindAmount()
	}
	val amount = seekAcceleration(baseAmount, repeatCount)

	val positionInfo = playbackManager.state.positionInfo
	val current = positionInfo.active
	val duration = positionInfo.duration
	val target = if (forward) current + amount else current - amount
	val clampedTarget = if (duration > Duration.ZERO) {
		target.coerceIn(Duration.ZERO, duration)
	} else {
		target.coerceAtLeast(Duration.ZERO)
	}

	if (forward) {
		playbackManager.state.fastForward(amount)
	} else {
		playbackManager.state.rewind(amount)
	}
	Timber.i("Player direct seek: %s %s -> %s", if (forward) "forward" else "rewind", amount, clampedTarget)

	val arrow = if (forward) "⏩" else "⏪"
	val clamp = if (duration > Duration.ZERO) " / ${formatDuration(duration)}" else ""
	return "$arrow ${amount.inWholeSeconds}秒   ${formatDuration(clampedTarget)}$clamp"
}

private fun formatDuration(duration: Duration): String {
	val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0)
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60
	return if (hours > 0) {
		"%d:%02d:%02d".format(hours, minutes, seconds)
	} else {
		"%d:%02d".format(minutes, seconds)
	}
}
