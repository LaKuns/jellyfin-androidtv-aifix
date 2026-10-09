package org.jellyfin.androidtv.ui.player.photo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.PerformanceProfile
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject

private const val MAX_PHOTO_WIDTH = 1920
private const val MAX_PHOTO_HEIGHT = 1080
private const val MAX_LOW_PERFORMANCE_PHOTO_WIDTH = 1280
private const val MAX_LOW_PERFORMANCE_PHOTO_HEIGHT = 720

@Composable
private fun PhotoImage(
	api: ApiClient,
	item: BaseItemDto?,
	width: Int,
	height: Int,
) {
	val image = item?.itemImages[ImageType.PRIMARY]

	AsyncImage(
		url = image?.getUrl(
			api = api,
			maxWidth = width,
			maxHeight = height,
		),
		blurHash = image?.blurHash,
		aspectRatio = image?.aspectRatio ?: 1f,
		modifier = Modifier
			.fillMaxSize()
	)
}

@Composable
fun PhotoPlayerContent(
	item: BaseItemDto?,
) {
	val api = koinInject<ApiClient>()
	val resources = LocalResources.current
	val lowPerformanceDevice = remember(resources) {
		PerformanceProfile.isLowPerformanceDevice(resources)
	}

	// A photo is displayed fullscreen, so it is decoded at (a bounded) screen
	// resolution and held as a single bitmap. On weak devices this is capped
	// further and the crossfade is dropped, because AnimatedContent would keep
	// two fullscreen bitmaps alive during the transition.
	val width: Int
	val height: Int
	if (lowPerformanceDevice) {
		width = resources.displayMetrics.widthPixels.coerceAtLeast(1).coerceAtMost(MAX_LOW_PERFORMANCE_PHOTO_WIDTH)
		height = resources.displayMetrics.heightPixels.coerceAtLeast(1).coerceAtMost(MAX_LOW_PERFORMANCE_PHOTO_HEIGHT)
	} else {
		width = resources.displayMetrics.widthPixels.coerceAtLeast(1).coerceAtMost(MAX_PHOTO_WIDTH)
		height = resources.displayMetrics.heightPixels.coerceAtLeast(1).coerceAtMost(MAX_PHOTO_HEIGHT)
	}

	if (lowPerformanceDevice) {
		PhotoImage(api, item, width, height)
	} else {
		AnimatedContent(
			targetState = item,
			transitionSpec = {
				fadeIn() togetherWith fadeOut()
			}
		) { target -> PhotoImage(api, target, width, height) }
	}
}
