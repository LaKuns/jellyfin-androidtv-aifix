package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.flow.map
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.ScreensaverLock
import org.jellyfin.androidtv.ui.player.base.PlayerSubtitles
import org.jellyfin.androidtv.ui.player.base.PlayerSurface
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.video.toast.rememberPlaybackManagerMediaToastEmitter
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.koin.compose.koinInject

private const val DefaultVideoAspectRatio = 16f / 9f

@Composable
fun VideoPlayerScreen() {
	val playbackManager = koinInject<PlaybackManager>()

	val backgroundService = koinInject<BackgroundService>()
	LaunchedEffect(backgroundService) {
		backgroundService.clearBackgrounds()
	}

	val playing by remember {
		playbackManager.state.playState.map { it == PlayState.PLAYING }
	}.collectAsState(false)
	ScreensaverLock(
		enabled = playing,
	)

	val videoSize by playbackManager.state.videoSize.collectAsState()
	val aspectRatio = videoSize.aspectRatio.takeIf { !it.isNaN() && it > 0f } ?: DefaultVideoAspectRatio

	val coroutineScope = rememberCoroutineScope()
	val mediaToastRegistry = remember { MediaToastRegistry(coroutineScope) }
	rememberPlaybackManagerMediaToastEmitter(playbackManager, mediaToastRegistry)
	rememberPlaybackRecoveryToastEmitter(playbackManager)

	Box(
		modifier = Modifier
			.background(Color.Black)
			.fillMaxSize()
	) {
		PlayerSurface(
			playbackManager = playbackManager,
			modifier = Modifier
				.aspectRatio(aspectRatio, videoSize.height < videoSize.width)
				.fillMaxSize()
				.align(Alignment.Center)
		)

		// Subtitles are a layer below the controls: the control bar must cover them, never the
		// other way around.
		PlayerSubtitles(
			playbackManager = playbackManager,
			modifier = Modifier
				.aspectRatio(aspectRatio, videoSize.height < videoSize.width)
				.fillMaxSize()
				.align(Alignment.Center)
				.zIndex(0f),
		)

		// The Compose overlay (controls, hints, settings, buffering spinner) is hosted in its own
		// Android view. Compose AndroidViews are painted in their own layer; hosting the overlay
		// in an Android view that is added after the subtitle view makes the controls cover the
		// subtitles (and not the other way around) on every content aspect ratio.
		AndroidView(
			factory = { context ->
				ComposeView(context).apply {
					// Compose AndroidViews share one hosting layer; a higher Z than the subtitle view
					// makes the controls draw on top of the subtitles in that layer as well.
					translationZ = 10f
					setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
					setContent {
						VideoPlayerOverlay(
							playbackManager = playbackManager,
							mediaToastRegistry = mediaToastRegistry,
							modifier = Modifier.fillMaxSize(),
						)
						BufferingOverlay(
							playbackManager = playbackManager,
							modifier = Modifier.fillMaxSize(),
						)
					}
				}
			},
			modifier = Modifier
				.fillMaxSize()
				.zIndex(1f),
		)
	}
}
