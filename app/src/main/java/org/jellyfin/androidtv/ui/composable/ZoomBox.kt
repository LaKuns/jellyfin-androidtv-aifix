package org.jellyfin.androidtv.ui.composable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun ZoomBox(
	initialValue: Float = 1f,
	targetValue: Float = 2f,
	delayMillis: Int = 0,
	durationMillis: Int = 1_000,
	content: @Composable BoxScope.() -> Unit,
) {
	// The zoom is played once instead of repeating itself forever. An endless
	// animation keeps the frame clock running, which costs the whole television
	// a graphics pass per frame while nothing on the screen changes.
	val scale = remember { Animatable(initialValue) }

	LaunchedEffect(initialValue, targetValue, delayMillis, durationMillis) {
		scale.animateTo(
			targetValue = targetValue,
			animationSpec = tween(
				durationMillis = durationMillis,
				delayMillis = delayMillis,
				easing = LinearEasing,
			),
		)
	}

	Box(
		modifier = Modifier.graphicsLayer {
			scaleX = scale.value
			scaleY = scale.value
		},
		content = content,
	)
}
