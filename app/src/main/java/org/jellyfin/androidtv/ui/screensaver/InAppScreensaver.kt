package org.jellyfin.androidtv.ui.screensaver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import org.jellyfin.androidtv.integration.dream.composable.DreamHost
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.androidtv.ui.composable.MotionDurations
import org.jellyfin.androidtv.util.isMediaSessionKeyEvent
import org.koin.androidx.compose.koinViewModel

@Composable
fun InAppScreensaver() {
	val interactionTrackerViewModel = koinViewModel<InteractionTrackerViewModel>()
	val visible by interactionTrackerViewModel.visible.collectAsState()

	DialogBase(
		visible = visible,
		onDismissRequest = {
			interactionTrackerViewModel.notifyInteraction(canCancel = true, userInitiated = false)
		},
		scrimColor = Color.Black,
		// A slow fade is the point of a screensaver; the scrim must use the same clock
		// as the content instead of finishing at its own pace.
		durationMillis = MotionDurations.VerySlow,
		modifier = Modifier
			.fillMaxSize()
			.clickable(
				interactionSource = remember { MutableInteractionSource() },
				indication = null,
			) {
				interactionTrackerViewModel.notifyInteraction(canCancel = true, userInitiated = false)
			}
			.onKeyEvent { event ->
				if (!event.nativeKeyEvent.isMediaSessionKeyEvent()) {
					interactionTrackerViewModel.notifyInteraction(
						canCancel = event.type == KeyEventType.KeyUp,
						userInitiated = true,
					)
				}
				false
			}
	) {
		DreamHost()
	}
}
