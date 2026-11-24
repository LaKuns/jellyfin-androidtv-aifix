package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonColors
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.AudioTrack
import org.jellyfin.playback.core.backend.AudioTrackBackend
import org.jellyfin.playback.core.backend.SubtitleTrackBackend
import org.jellyfin.playback.core.model.RepeatMode
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.math.abs

private val PANEL_WIDTH = 460.dp

private val PLAYBACK_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f)

private val REPEAT_MODES = listOf(
	RepeatMode.NONE to R.string.repeat_mode_none,
	RepeatMode.REPEAT_ENTRY_ONCE to R.string.repeat_mode_once,
	RepeatMode.REPEAT_ENTRY_INFINITE to R.string.repeat_mode_infinite,
)

private val rowColors = ButtonColors(
	containerColor = Color.Transparent,
	contentColor = Color.White,
	focusedContainerColor = Color.White.copy(alpha = 0.22f),
	focusedContentColor = Color.White,
	disabledContainerColor = Color.Transparent,
	disabledContentColor = Color.White.copy(alpha = 0.35f),
)

private val selectedRowColors = rowColors.copy(containerColor = Color.White.copy(alpha = 0.10f))

/**
 * Right-side sheet of the player. Holds the playback info and the settings that are changed
 * rarely, so the control bar only carries the frequently used actions (play/pause, seek,
 * audio track, subtitles, episodes).
 */
@Composable
fun PlayerSettingsPanel(
	playbackManager: PlaybackManager,
	visible: Boolean,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val focusRequester = remember { FocusRequester() }

	LaunchedEffect(visible) {
		if (visible) {
			// Wait for the enter animation to attach the nodes before requesting the focus.
			delay(100)
			runCatching { focusRequester.requestFocus() }
		}
	}

	Box(modifier.fillMaxSize()) {
		AnimatedVisibility(
			visible = visible,
			enter = fadeIn(),
			exit = fadeOut(),
		) {
			Box(
				Modifier
					.fillMaxSize()
					.background(Color.Black.copy(alpha = 0.45f))
					.clickable(
						interactionSource = remember { MutableInteractionSource() },
						indication = null,
					) { onDismiss() }
			)
		}

		AnimatedVisibility(
			visible = visible,
			modifier = Modifier.align(Alignment.CenterEnd),
			enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
			exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
		) {
			Column(
				modifier = Modifier
					.fillMaxHeight()
					.width(PANEL_WIDTH)
					.background(Color(0xF0141414))
					.focusRequester(focusRequester)
					.focusable()
					.onPreviewKeyEvent { event ->
						if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
							onDismiss()
							true
						} else {
							false
						}
					}
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 20.dp, vertical = 16.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					text = stringResource(R.string.lbl_player_settings),
					fontSize = 20.sp,
					fontWeight = FontWeight.SemiBold,
					color = Color.White,
					modifier = Modifier.padding(bottom = 4.dp),
				)

				AudioTracksSection(playbackManager)
				SubtitlesSection(playbackManager)
				EpisodesSection(playbackManager)
				PlaybackOptionsSection(playbackManager)

				SectionDivider()
				PlaybackInfoSection(playbackManager, Modifier.fillMaxWidth())

				Text(
					text = stringResource(R.string.lbl_player_settings_hint),
					fontSize = 12.sp,
					color = Color.White.copy(alpha = 0.55f),
					modifier = Modifier.padding(top = 8.dp),
				)
			}
		}
	}
}

@Composable
private fun SectionDivider() {
	Box(
		Modifier
			.fillMaxWidth()
			.padding(vertical = 8.dp)
			.height(1.dp)
			.background(Color.White.copy(alpha = 0.12f))
	)
}

@Composable
private fun SectionTitle(text: String) {
	Text(
		text = text,
		fontSize = 13.sp,
		fontWeight = FontWeight.Medium,
		color = Color.White.copy(alpha = 0.7f),
		modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
	)
}

@Composable
private fun PanelRow(
	selected: Boolean? = null,
	enabled: Boolean = true,
	onClick: () -> Unit,
	content: @Composable RowScope.() -> Unit,
) {
	Button(
		onClick = onClick,
		enabled = enabled,
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(4.dp),
		colors = if (selected == true) selectedRowColors else rowColors,
		contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
	) {
		if (selected != null) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_check),
				contentDescription = null,
				tint = if (selected) Color.White else Color.Transparent,
				modifier = Modifier.size(16.dp),
			)
			Spacer(Modifier.width(12.dp))
		}
		content()
	}
}

@Composable
private fun AudioTracksSection(playbackManager: PlaybackManager) {
	val backend = playbackManager.backend as? AudioTrackBackend ?: return
	var tracks by remember(backend) { mutableStateOf<List<AudioTrack>>(backend.getAudioTracks()) }

	LaunchedEffect(backend) {
		while (true) {
			tracks = backend.getAudioTracks()
			delay(1_000)
		}
	}

	if (tracks.size < 2) return

	SectionTitle(stringResource(R.string.lbl_audio_track))
	tracks.forEach { track ->
		PanelRow(
			selected = track.isSelected,
			onClick = {
				backend.setAudioTrack(track.id)
				tracks = backend.getAudioTracks()
			},
		) {
			Text(track.label)
		}
	}
}

@Composable
private fun SubtitlesSection(playbackManager: PlaybackManager) {
	val backend = playbackManager.backend as? SubtitleTrackBackend ?: return
	var enabled by remember(backend) { mutableStateOf(backend.isSubtitlesEnabled()) }

	SectionTitle(stringResource(R.string.lbl_subtitle_track))
	PanelRow(
		selected = enabled,
		onClick = {
			enabled = !enabled
			backend.setSubtitlesEnabled(enabled)
		},
	) {
		Text(stringResource(if (enabled) R.string.lbl_subtitles_on else R.string.lbl_subtitles_off))
	}
}

@Composable
private fun EpisodesSection(playbackManager: PlaybackManager) {
	val entry by playbackManager.queue.entry.collectAsState()
	val baseItem = entry?.baseItem ?: return
	if (baseItem.type != BaseItemKind.EPISODE) return

	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val queueSize = playbackManager.queue.estimatedSize
	val coroutineScope = rememberCoroutineScope()

	SectionTitle(stringResource(R.string.lbl_select_episode))
	Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		Button(
			onClick = { coroutineScope.launch { playbackManager.queue.previous() } },
			enabled = entryIndex > 0,
			shape = RoundedCornerShape(4.dp),
			colors = rowColors,
		) {
			Text(stringResource(R.string.lbl_prev_item))
		}
		Button(
			onClick = { coroutineScope.launch { playbackManager.queue.next() } },
			enabled = entryIndex < queueSize - 1,
			shape = RoundedCornerShape(4.dp),
			colors = rowColors,
		) {
			Text(stringResource(R.string.lbl_next_item))
		}
	}
}

@Composable
private fun PlaybackOptionsSection(playbackManager: PlaybackManager) {
	val state = playbackManager.state
	val speed by state.speed.collectAsState()
	val repeatMode by state.repeatMode.collectAsState()

	SectionTitle(stringResource(R.string.lbl_playback_speed))
	PLAYBACK_SPEEDS.forEach { option ->
		PanelRow(
			selected = abs(speed - option) < 0.01f,
			onClick = { state.setSpeed(option) },
		) {
			Text(stringResource(R.string.speed_multiplier, formatSpeed(option)))
		}
	}

	SectionTitle(stringResource(R.string.lbl_repeat))
	REPEAT_MODES.forEach { (mode, label) ->
		PanelRow(
			selected = repeatMode == mode,
			onClick = { state.setRepeatMode(mode) },
		) {
			Text(stringResource(label))
		}
	}
}

private fun formatSpeed(speed: Float): String {
	val text = speed.toString()
	return if (text.endsWith(".0")) text.dropLast(2) else text
}
