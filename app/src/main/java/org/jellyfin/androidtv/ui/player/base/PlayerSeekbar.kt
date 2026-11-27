package org.jellyfin.androidtv.ui.player.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jellyfin.androidtv.ui.base.Seekbar
import org.jellyfin.androidtv.ui.base.SeekbarColors
import org.jellyfin.androidtv.ui.base.SeekbarDefaults
import org.jellyfin.androidtv.ui.composable.rememberPlayerProgress
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.times

@Composable
fun PlayerSeekbar(
	modifier: Modifier = Modifier,
	colors: SeekbarColors = SeekbarDefaults.colors(),
	playbackManager: PlaybackManager = koinInject<PlaybackManager>(),
	/** Called with the position the user is currently scrubbing to, while the bar has the focus. */
	onScrubProgress: ((Duration) -> Unit)? = null,
	/** Called when a seek was issued and the bar keeps showing the target until it is reached. */
	onPendingProgress: ((Duration?) -> Unit)? = null,
	/** Called when a scrub starts (true) and when the seek has been issued (false). */
	onScrubbingChange: ((Boolean) -> Unit)? = null,
) {
	val playState by playbackManager.state.playState.collectAsState()
	val positionInfo = playbackManager.state.positionInfo
	val progress by rememberPlayerProgress(
		playing = playState == PlayState.PLAYING,
		active = positionInfo.active,
		duration = positionInfo.duration,
	)
	val seekForwardAmount = remember { playbackManager.options.defaultFastForwardAmount() }
	val seekRewindAmount = remember { playbackManager.options.defaultRewindAmount() }

	Seekbar(
		progress = progress.toDouble() * positionInfo.duration,
		buffer = positionInfo.buffer,
		duration = positionInfo.duration,
		seekForwardAmount = seekForwardAmount,
		seekRewindAmount = seekRewindAmount,
		onScrubbing = { scrubbing ->
			playbackManager.state.setScrubbing(scrubbing)
			onScrubbingChange?.invoke(scrubbing)
		},
		onScrubProgress = onScrubProgress,
		onPendingProgress = onPendingProgress,
		onSeek = { progress -> playbackManager.state.seek(progress) },
		modifier = modifier,
		colors = colors,
		enabled = positionInfo.duration > Duration.ZERO,
	)
}
