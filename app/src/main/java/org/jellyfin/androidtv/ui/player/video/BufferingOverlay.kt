package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.mediaStream

/**
 * A small spinner for a buffering player.
 *
 * The spinner appears only after a short delay, so a normal segment change does not flash it. A
 * forced video re-encode restarts the server side transcode on a seek, which can take a while;
 * the message tells the viewer that the player is not stuck.
 */
@Composable
fun BufferingOverlay(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
) {
	val buffering by playbackManager.state.buffering.collectAsState()
	var visible by remember { mutableStateOf(false) }
	LaunchedEffect(buffering) {
		if (!buffering) {
			visible = false
			return@LaunchedEffect
		}

		delay(500)
		if (buffering) visible = true
	}

	if (!visible) return

	val entry by rememberQueueEntry(playbackManager)
	val isTranscode = entry?.mediaStream?.conversionMethod == MediaConversionMethod.Transcode

	Box(
		modifier = modifier
			.fillMaxSize()
			.background(Color.Black.copy(alpha = 0.35f)),
		contentAlignment = Alignment.Center,
	) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			CircularProgressIndicator(
				modifier = Modifier.size(48.dp),
				color = Color.White,
			)
			Text(
				text = if (isTranscode) "正在重新转码，请稍候…" else "正在加载…",
				color = Color.White,
				fontSize = 16.sp,
			)
		}
	}
}
