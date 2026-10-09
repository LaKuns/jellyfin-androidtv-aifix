package org.jellyfin.androidtv.ui.base.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.interactionTracker
import org.jellyfin.androidtv.ui.composable.MotionDurations

@Composable
fun DialogBase(
	visible: Boolean,
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	scrimColor: Color = JellyfinTheme.colorScheme.scrim,
	durationMillis: Int = MotionDurations.Normal,
	contentAlignment: Alignment = Alignment.Center,
	enterTransition: EnterTransition = fadeIn(tween(durationMillis)),
	exitTransition: ExitTransition = fadeOut(tween(durationMillis)),
	content: @Composable BoxScope.() -> Unit,
) {
	// The scrim and the content must move together. They previously used different
	// durations (1000 ms for the scrim vs 300 ms for the content), which reads as the
	// dialog "catching up" after the background has already settled. Callers supplying a
	// custom enter/exit transition should pass the matching [durationMillis] so the scrim
	// stays in step with it.
	val transition = updateTransition(visible)
	val alpha by transition.animateFloat(
		transitionSpec = { tween(durationMillis) },
		targetValueByState = { visible -> if (visible) 1f else 0f }
	)

	if (transition.currentState || transition.isRunning) {
		val focusRequester = remember { FocusRequester() }
		var contentVisible by remember { mutableStateOf(false) }

		Dialog(
			onDismissRequest = onDismissRequest,
			properties = DialogProperties(
				dismissOnBackPress = true,
				dismissOnClickOutside = true,
				usePlatformDefaultWidth = false,
				decorFitsSystemWindows = false
			),
		) {
			Box(
				modifier = modifier
					.fillMaxSize()
					.background(scrimColor.copy(alpha = scrimColor.alpha * alpha))
					.focusRequester(focusRequester)
					.focusGroup()
					.interactionTracker(),
				contentAlignment = contentAlignment,
			) {
				transition.AnimatedVisibility(
					visible = { it && contentVisible },
					enter = enterTransition,
					exit = exitTransition,
				) {
					content()
					LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
				}
			}
		}

		LaunchedEffect(contentVisible) { contentVisible = true }
	}
}
