package org.jellyfin.androidtv.ui.player.video

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.player.base.PlayerKeyHandler
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayLayout
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.playback.jellyfin.mediasegment.MediaSegmentService
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.koin.compose.koinInject
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
fun VideoPlayerOverlay(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	mediaToastRegistry: MediaToastRegistry,
) {
	val playState by playbackManager.state.playState.collectAsState()

	// Entering the player starts silently: the controls are only shown for an explicit action of
	// the viewer. A buffer stall also reports "paused", so the bar must not follow the playback
	// state; only a pause asked for by the viewer keeps the bar open without a timeout.
	var userPaused by remember { mutableStateOf(false) }
	val visibilityState = rememberPlayerOverlayVisibility(timeout = if (userPaused) null else 5.seconds)

	// The player always starts silently; do not depend on the timing of the window focus event.
	LaunchedEffect(Unit) { visibilityState.hide() }

	// The timeout is captured when show() runs; re-show after the remember value changed so a
	// pause by the viewer keeps the bar open instead of hiding after the normal timeout.
	LaunchedEffect(userPaused) {
		if (userPaused) visibilityState.show()
	}

	// The subtitle view is an Android view and draws above the Compose overlay on some devices;
	// hide it while the bar is visible so the bar is always on top (the requirement is that the
	// subtitles are below the controls).
	val controlsVisible = visibilityState.visible
	LaunchedEffect(controlsVisible) {
		playbackManager.backend.setSubtitleVisibility(!controlsVisible)
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
	// The effect below runs on its first composition as well; only react to real changes, so
	// opening the player does not show the control bar.
	var settingsWasOpen by remember { mutableStateOf(false) }
	LaunchedEffect(showSettings) {
		// While the settings sheet is open it owns the focus: hide the control bar, otherwise
		// its buttons keep the focus and steal the key presses of the sheet.
		if (showSettings) {
			settingsWasOpen = true
			visibilityState.hide()
		} else if (settingsWasOpen) {
			settingsWasOpen = false
			visibilityState.show()
		}
	}

	val mediaSegmentService = remember { playbackManager.getService<MediaSegmentService>() }
	val noActiveSegment = remember { MutableStateFlow<MediaSegmentService.ActiveSegment?>(null) }
	val activeSegment by (mediaSegmentService?.activeSegment ?: noActiveSegment).collectAsState()
	val playerPositionInfo by rememberPlayerPositionInfo(playbackManager, 500.milliseconds)
	val currentSegment = activeSegment?.takeIf { segment ->
		playerPositionInfo.active >= segment.start && playerPositionInfo.active < segment.end
	}
	val currentSegmentState = rememberUpdatedState(currentSegment)
	var nextEpisodeName by remember { mutableStateOf<String?>(null) }

	val currentShowSettings = rememberUpdatedState(showSettings)

	// The remote control is handled by the activity while the player is on screen, so it works
	// even when the overlay hid itself and nothing holds the focus (see PlayerKeyHandler).
	val currentVisibilityState = rememberUpdatedState(visibilityState)
	DisposableEffect(Unit) {
		val registration = PlayerKeyHandler.register { keyCode, repeatCount ->
			// While the settings sheet is open it owns the focus. The activity handler must not
			// show the control bar (and steal the focus) on every key; only Back closes the
			// sheet, everything else is left to the focused controls of the sheet.
			if (currentShowSettings.value) {
				if (keyCode == KeyEvent.KEYCODE_BACK) {
					showSettings = false
					true
				} else {
					false
				}
			} else {
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
						seekHint = seek(playbackManager, forward = false, repeatCount = repeatCount)
						seekHintGeneration++
						true
					}

					KeyEvent.KEYCODE_DPAD_RIGHT -> {
						seekHint = seek(playbackManager, forward = true, repeatCount = repeatCount)
						seekHintGeneration++
						true
					}

					KeyEvent.KEYCODE_DPAD_CENTER,
					KeyEvent.KEYCODE_ENTER,
					KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
						if (currentSegmentState.value != null) {
							mediaSegmentService?.skipActiveSegment()
						} else when (playState) {
							PlayState.PLAYING -> {
								playbackManager.state.pause()
								userPaused = true
								// A pause is an explicit action: show the controls with it.
								visibilityState.show()
							}

							PlayState.PAUSED -> {
								userPaused = false
								playbackManager.state.unpause()
							}

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
		}
		onDispose { PlayerKeyHandler.unregister(registration) }
	}

	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value

	LaunchedEffect(entry) {
		nextEpisodeName = runCatching { playbackManager.queue?.peekNext()?.baseItem?.name }.getOrNull()
	}

	Box(modifier = modifier) {
		// The settings sheet is modal: the control bar is not composed while it is open, so the
		// focus can not fall back to a hidden control bar button.
		if (!showSettings) {
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
		}

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

		// The server tells the app where the intro/outro starts; the viewer can skip it with OK.
		currentSegment?.let { segment ->
			val text = when (segment.type) {
				MediaSegmentType.INTRO -> "跳过片头 · 按 OK"
				MediaSegmentType.OUTRO -> "跳过片尾 · 按 OK"
				else -> "跳过 · 按 OK"
			}
			org.jellyfin.androidtv.ui.base.Text(
				text = text,
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(end = 48.dp, bottom = 200.dp)
					.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
					.padding(horizontal = 20.dp, vertical = 10.dp),
				color = Color.White,
			)
		}

		// Next episode hint near the end of an episode with a following entry in the queue.
		val nextName = nextEpisodeName
		val remaining = playerPositionInfo.duration - playerPositionInfo.active
		if (nextName != null && item?.type == BaseItemKind.EPISODE && remaining > Duration.ZERO && remaining <= 20.seconds) {
			Column(
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(end = 48.dp, bottom = 120.dp)
					.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
					.padding(16.dp),
			) {
				org.jellyfin.androidtv.ui.base.Text("即将播放下一集", color = Color.White, fontSize = 14.sp)
				org.jellyfin.androidtv.ui.base.Text(nextName, color = Color.White, fontSize = 18.sp)
				if (remaining <= 5.seconds) {
					org.jellyfin.androidtv.ui.base.Text(
						text = "${remaining.inWholeSeconds} 秒后播放",
						color = Color.White,
						fontSize = 14.sp,
					)
				}
			}
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
