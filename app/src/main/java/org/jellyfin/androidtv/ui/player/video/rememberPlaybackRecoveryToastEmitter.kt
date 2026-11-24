package org.jellyfin.androidtv.ui.player.video

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.plugin.PlaybackRecoveryService

/**
 * Tell the user when the playback has to fall back to a less demanding stream, or when it failed
 * completely. The retry itself is handled by the playback services.
 */
@Composable
fun rememberPlaybackRecoveryToastEmitter(playbackManager: PlaybackManager) {
	val context = LocalContext.current
	val retryRemux = stringResource(R.string.playback_retry_remux)
	val retryTranscode = stringResource(R.string.playback_retry_transcode)
	val failed = stringResource(R.string.playback_failed)

	LaunchedEffect(playbackManager, retryRemux, retryTranscode, failed) {
		val recovery = playbackManager.getService<PlaybackRecoveryService>() ?: return@LaunchedEffect

		recovery.events.collect { event ->
			val message = when (event) {
				PlaybackRecoveryService.Event.RETRY_REMUX -> retryRemux
				PlaybackRecoveryService.Event.RETRY_TRANSCODE -> retryTranscode
				PlaybackRecoveryService.Event.FAILED -> failed
			}

			Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
		}
	}
}
