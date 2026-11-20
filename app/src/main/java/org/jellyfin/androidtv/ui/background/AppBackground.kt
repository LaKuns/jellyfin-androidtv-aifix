package org.jellyfin.androidtv.ui.background

import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.util.PerformanceProfile
import org.koin.compose.koinInject
import kotlin.math.max

/** Radius of the blur that is applied to the background. */
private val BLUR_RADIUS = 10.dp

@Composable
private fun AppThemeBackground() {
	val context = LocalContext.current
	val themeBackground = remember(context.theme) {
		val attributes = context.theme.obtainStyledAttributes(intArrayOf(R.attr.defaultBackground))
		val drawable = attributes.getDrawable(0)
		attributes.recycle()

		if (drawable is ColorDrawable) drawable.toBitmap(1, 1).asImageBitmap()
		else drawable?.toBitmap()?.asImageBitmap()
	}

	if (themeBackground != null) {
		Image(
			bitmap = themeBackground,
			contentDescription = null,
			alignment = Alignment.Center,
			contentScale = ContentScale.Crop,
			modifier = Modifier.fillMaxSize()
		)
	} else {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(Color.Black)
		)
	}
}

@Composable
fun AppBackground() {
	val context = LocalContext.current
	val backgroundService = koinInject<BackgroundService>()
	val currentBackground by backgroundService.currentBackground.collectAsState()
	val blurBackground by backgroundService.blurBackground.collectAsState()
	val enabled by backgroundService.enabled.collectAsState()
	val lowPerformanceDevice = remember(context) {
		PerformanceProfile.isLowPerformanceDevice(context)
	}

	if (enabled) {
		if (lowPerformanceDevice) {
			// Avoid keeping two fullscreen bitmaps during AnimatedContent's
			// transition. BackgroundService also disables blur on these devices.
			BackgroundImage(currentBackground, blur = false)
		} else {
			AnimatedContent(
				targetState = currentBackground,
				transitionSpec = {
					val duration = (BackgroundService.TRANSITION_DURATION.inWholeMilliseconds / 2).toInt()
					fadeIn(tween(durationMillis = duration)) togetherWith fadeOut(snap(delayMillis = duration))
				},
				label = "BackgroundTransition",
			) { background ->
				BackgroundImage(background, blur = blurBackground)
			}
		}
	}
}

@Composable
private fun BackgroundImage(
	background: ImageBitmap?,
	blur: Boolean,
) {
	if (background == null) {
		AppThemeBackground()
		return
	}

	val tint = colorResource(R.color.background_filter)
	val colorFilter = remember(tint) { ColorFilter.tint(tint, BlendMode.SrcAtop) }

	if (!blur) {
		BackgroundBitmap(background, colorFilter, Modifier.fillMaxSize())
		return
	}

	// The blur is applied to a layer that has the resolution of the image and the
	// result is scaled up to the screen afterwards. A blur of the fullscreen
	// picture runs with the resolution of the television, which is expensive on the
	// weak GPUs that are found in TV boxes.
	//
	// The scaling factor is the one that ContentScale.Crop would use, so the
	// picture itself stays exactly the same.
	val density = LocalDensity.current.density
	val configuration = LocalConfiguration.current
	val screenWidth = configuration.screenWidthDp * density
	val screenHeight = configuration.screenHeightDp * density
	val imageWidth = background.width
	val imageHeight = background.height

	val imageIsUsable = imageWidth > 0 && imageHeight > 0
	val screenIsUsable = screenWidth > 0 && screenHeight > 0
	if (!imageIsUsable || !screenIsUsable) {
		BackgroundBitmap(background, colorFilter, Modifier.fillMaxSize())
		return
	}

	val scale = max(screenWidth / imageWidth, screenHeight / imageHeight)

	Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Box(
			modifier = Modifier
				.size(
					(imageWidth / density).dp,
					(imageHeight / density).dp,
				)
				.blur(BLUR_RADIUS)
				.graphicsLayer {
					scaleX = scale
					scaleY = scale
				},
		) {
			BackgroundBitmap(background, colorFilter, Modifier.fillMaxSize())
		}
	}
}

@Composable
private fun BackgroundBitmap(
	background: ImageBitmap,
	colorFilter: ColorFilter,
	modifier: Modifier,
) {
	Image(
		bitmap = background,
		contentDescription = null,
		alignment = Alignment.Center,
		contentScale = ContentScale.Crop,
		colorFilter = colorFilter,
		modifier = modifier,
	)
}
