package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayLayout
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

@Composable
fun VideoPlayerOverlay(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	mediaToastRegistry: MediaToastRegistry,
) {
	val playState by playbackManager.state.playState.collectAsState()
	val visibilityState = rememberPlayerOverlayVisibility(timeout = if (playState == PlayState.PAUSED) null else 5.seconds)
	LaunchedEffect(playState) {
		if (playState == PlayState.PAUSED || playState == PlayState.PLAYING) visibilityState.show()
	}
	var showSettings by remember { mutableStateOf(false) }
	LaunchedEffect(showSettings) {
		// While the settings sheet is open it owns the focus: hide the control bar, otherwise
		// its buttons keep the focus and steal the key presses of the sheet.
		if (showSettings) visibilityState.hide() else visibilityState.show()
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
				)
			},
		)

		// Right-side sheet with the playback info and the rarely changed settings.
		PlayerSettingsPanel(
			playbackManager = playbackManager,
			visible = showSettings,
			onDismiss = { showSettings = false },
		)

		MediaToasts(mediaToastRegistry)
	}
}
