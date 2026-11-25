package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.base.popover.PopoverMenu
import org.jellyfin.androidtv.ui.base.popover.PopoverMenuCheckboxItem
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.itemdetail.EpisodeCatalog
import org.jellyfin.androidtv.ui.playback.EpisodePickerDialog
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.ui.player.base.PlayerSeekbar
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.AudioTrack
import org.jellyfin.playback.core.backend.AudioTrackBackend
import org.jellyfin.playback.core.backend.SubtitleTrackBackend
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The control bar of the video player, laid out like the old player:
 *
 * - first row: playback buttons and the rarely used track buttons,
 * - second row: the progress bar, which owns the focus while the user scrubs,
 * - third row: the episode controls, only for an episode that belongs to a series.
 *
 * A movie or a single video has no third row; pressing down on the progress bar cycles the focus
 * back to the first row instead of moving into an empty area.
 */
@Composable
fun VideoPlayerControls(
	playbackManager: PlaybackManager = koinInject(),
	onMoreOptionsClick: () -> Unit = {},
) {
	val playState by playbackManager.state.playState.collectAsState()
	val entry by playbackManager.queue.entry.collectAsState()
	val item = entry?.baseItem
	val hasEpisodeControls = item?.type == BaseItemKind.EPISODE && item?.seriesId != null

	val firstButtonFocus = remember { FocusRequester() }
	val seekbarFocus = remember { FocusRequester() }
	val firstEpisodeFocus = remember { FocusRequester() }

	Column(
		verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.focusRestorer()
				.focusGroup(),
		) {
			PlayPauseButton(
				playbackManager = playbackManager,
				playState = playState,
				modifier = Modifier.focusRequester(firstButtonFocus),
			)
			RewindButton(playbackManager)
			FastForwardButton(playbackManager)

			Spacer(Modifier.weight(1f))

			AudioTrackButton(playbackManager)
			SubtitleButton(playbackManager)

			MoreSettingsButton(onClick = onMoreOptionsClick)
		}

		PlayerSeekbar(
			playbackManager = playbackManager,
			modifier = Modifier
				.fillMaxWidth()
				.height(8.dp)
				.focusRequester(seekbarFocus)
				.onPreviewKeyEvent { event ->
					if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) {
						return@onPreviewKeyEvent false
					}

					// Without an episode row the focus cycles back to the buttons.
					if (hasEpisodeControls) firstEpisodeFocus.requestFocus() else firstButtonFocus.requestFocus()
					true
				},
		)

		if (hasEpisodeControls) {
			EpisodeRow(
				playbackManager = playbackManager,
				firstButtonFocus = firstButtonFocus,
				firstEpisodeFocus = firstEpisodeFocus,
			)
		}

		Row(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			modifier = Modifier.fillMaxWidth(),
		) {
			Spacer(Modifier.weight(1f))
			PositionText(playbackManager)
		}
	}
}

/**
 * The episode controls below the progress bar: previous episode, next episode and the episode
 * picker. The buttons are icons only, the content description names them for screen readers.
 */
@Composable
private fun EpisodeRow(
	playbackManager: PlaybackManager,
	firstButtonFocus: FocusRequester,
	firstEpisodeFocus: FocusRequester,
) {
	val scope = rememberCoroutineScope()
	val entry by playbackManager.queue.entry.collectAsState()
	var canPrevious by remember { mutableStateOf(false) }
	var canNext by remember { mutableStateOf(false) }

	LaunchedEffect(entry) {
		canPrevious = runCatching { playbackManager.queue.peekPrevious() }.getOrNull() != null
		canNext = runCatching { playbackManager.queue.peekNext() }.getOrNull() != null
	}

	Row(
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.onPreviewKeyEvent { event ->
			if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
				// Cycle back to the first row, the bar has no row below the episode controls.
				firstButtonFocus.requestFocus()
				true
			} else {
				false
			}
		},
	) {
		IconButton(
			onClick = { scope.launch { playbackManager.queue.previous() } },
			enabled = canPrevious,
			// A disabled node can not own the focus, so the picker takes it on the first episode.
			modifier = if (canPrevious) Modifier.focusRequester(firstEpisodeFocus) else Modifier,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_previous_episode),
				contentDescription = stringResource(R.string.lbl_previous_episode),
			)
		}

		SelectEpisodeButton(
			playbackManager = playbackManager,
			modifier = if (canPrevious) Modifier else Modifier.focusRequester(firstEpisodeFocus),
		)

		IconButton(
			onClick = { scope.launch { playbackManager.queue.next() } },
			enabled = canNext,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_next),
				contentDescription = stringResource(R.string.lbl_next_episode),
			)
		}
	}
}

@Composable
private fun SelectEpisodeButton(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
) {
	val entry by playbackManager.queue.entry.collectAsState()
	val current = entry?.baseItem?.takeIf { it.type == BaseItemKind.EPISODE && it.seriesId != null } ?: return
	val context = LocalContext.current
	val owner = LocalLifecycleOwner.current
	val api = koinInject<ApiClient>()
	val preferences = koinInject<UserPreferences>()
	IconButton(
		onClick = {
			EpisodePickerDialog.show(context, owner, api, current) { episode ->
				val items = EpisodeCatalog(api).playbackItems(episode, preferences[UserPreferences.mediaQueuingEnabled])
				playbackManager.state.pause()
				playbackManager.queue.clear()
				playbackManager.queue.addSupplier(RewriteMediaManager.BaseItemQueueSupplier(api, items, false))
				playbackManager.state.play()
				true
			}
		},
		modifier = modifier,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_select_chapter),
			contentDescription = stringResource(R.string.lbl_select_episode),
		)
	}
}

@Composable
private fun PlayPauseButton(
	playbackManager: PlaybackManager,
	playState: PlayState,
	modifier: Modifier = Modifier,
) {
	val focusRequester = remember { FocusRequester() }
	IconButton(
		onClick = {
			when (playState) {
				PlayState.STOPPED,
				PlayState.ERROR -> playbackManager.state.play()

				PlayState.PLAYING -> playbackManager.state.pause()
				PlayState.PAUSED -> playbackManager.state.unpause()
			}
		},
		modifier = modifier
			.focusRequester(focusRequester)
			.onVisibilityChanged {
				focusRequester.requestFocus()
			},
	) {
		AnimatedContent(playState) { playState ->
			when (playState) {
				PlayState.PLAYING -> {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_pause),
						contentDescription = stringResource(R.string.lbl_pause),
					)
				}

				PlayState.STOPPED,
				PlayState.PAUSED,
				PlayState.ERROR -> {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_play),
						contentDescription = stringResource(R.string.lbl_play),
					)
				}
			}
		}
	}
}

@Composable
private fun RewindButton(
	playbackManager: PlaybackManager,
) = IconButton(
	onClick = { playbackManager.state.rewind() },
) {
	Icon(
		imageVector = ImageVector.vectorResource(R.drawable.ic_rewind),
		contentDescription = stringResource(R.string.rewind),
	)
}

@Composable
private fun FastForwardButton(
	playbackManager: PlaybackManager,
) = IconButton(
	onClick = { playbackManager.state.fastForward() },
) {
	Icon(
		imageVector = ImageVector.vectorResource(R.drawable.ic_fast_forward),
		contentDescription = stringResource(R.string.fast_forward),
	)
}

@Composable
private fun MoreSettingsButton(
	onClick: () -> Unit,
) = IconButton(
	onClick = onClick,
) {
	Icon(
		imageVector = ImageVector.vectorResource(R.drawable.ic_more),
		contentDescription = stringResource(R.string.lbl_other_options),
	)
}

private fun Duration.formatted(includeHours: Boolean): String {
	val totalSeconds = toInt(DurationUnit.SECONDS)
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60

	return if (includeHours) "%02d:%02d:%02d".format(hours, minutes, seconds)
	else "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun PositionText(
	playbackManager: PlaybackManager,
) {
	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 1.seconds)
	if (positionInfo.duration == Duration.ZERO) return

	val text by remember {
		derivedStateOf {
			val includeHours = positionInfo.duration.inWholeMinutes >= 60
			val activeFormatted = positionInfo.active.formatted(includeHours)
			val durationFormatted = positionInfo.duration.formatted(includeHours)

			"$activeFormatted / $durationFormatted"
		}
	}

	Text(
		text = text,
		style = LocalTextStyle.current.copy(color = Color.White)
	)
}

@Composable
private fun SubtitleButton(playbackManager: PlaybackManager) {
	val backend = playbackManager.backend as? SubtitleTrackBackend ?: return
	var enabled by remember { mutableStateOf(backend.isSubtitlesEnabled()) }

	IconButton(
		onClick = {
			enabled = !enabled
			backend.setSubtitlesEnabled(enabled)
		},
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_subtitles),
			contentDescription = stringResource(R.string.lbl_subtitle_track),
			tint = LocalTextStyle.current.color.copy(alpha = if (enabled) 1f else 0.4f),
		)
	}
}

/**
 * Lets the user pick the audio track of the item that is playing.
 *
 * The list comes from the backend, so it only offers tracks the player can actually play.
 * The button is hidden while the item has fewer than two tracks, there is nothing to pick
 * in that case.
 */
@Composable
private fun AudioTrackButton(playbackManager: PlaybackManager) {
	val backend = playbackManager.backend as? AudioTrackBackend ?: return
	var tracks by remember { mutableStateOf<List<AudioTrack>>(backend.getAudioTracks()) }
	var expanded by remember { mutableStateOf(false) }

	// The tracks are only known after the player prepared the item, and they change when the
	// queue moves on, so the list is refreshed while the controls are on screen.
	LaunchedEffect(backend) {
		while (true) {
			val current = backend.getAudioTracks()
			if (current != tracks) tracks = current
			delay(1_000)
		}
	}

	if (tracks.size < 2) return

	Box {
		IconButton(
			onClick = {
				tracks = backend.getAudioTracks()
				expanded = true
			},
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_audiotrack),
				contentDescription = stringResource(R.string.lbl_audio_track),
			)
		}

		Popover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			alignment = Alignment.TopCenter,
			offset = DpOffset(0.dp, (-5).dp),
		) {
			PopoverMenu {
				tracks.forEach { track ->
					PopoverMenuCheckboxItem(
						selected = track.isSelected,
						onClick = {
							backend.setAudioTrack(track.id)
							tracks = backend.getAudioTracks()
							expanded = false
						},
					) {
						Text(track.label)
					}
				}
			}
		}
	}
}
