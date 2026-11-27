package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.ButtonColors
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
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
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

private val CompactButtonPadding = PaddingValues(6.dp)

/**
 * An icon button of the control bar.
 *
 * The target of the button is bigger than the icon (44dp, the size a remote needs), and the base
 * button lays its content out from the top left corner. Centering the content here keeps the icon
 * in the middle of the focus circle instead of slightly off to the top left.
 */
@Composable
private fun PlayerIconButton(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	colors: ButtonColors = ButtonDefaults.colors(),
	content: @Composable () -> Unit,
) = IconButton(
	onClick = onClick,
	modifier = modifier,
	enabled = enabled,
	colors = colors,
	contentPadding = CompactButtonPadding,
) {
	Box(
		modifier = Modifier.fillMaxSize(),
		contentAlignment = Alignment.Center,
	) {
		content()
	}
}

/**
 * The control bar of the video player: one row with every action and the progress bar below it.
 *
 * The row is read left to right: playback, the track pickers, the episode controls and the "more"
 * button, then the current position and the duration at the far right. The progress bar spans the
 * whole width underneath. Pressing down on the bar hides the bar, which matches the short jump to
 * the picture the big TV players do.
 */
@Composable
fun VideoPlayerControls(
	playbackManager: PlaybackManager = koinInject(),
	onMoreOptionsClick: () -> Unit = {},
	onHideControls: () -> Unit = {},
) {
	val playState by playbackManager.state.playState.collectAsState()
	val entry by playbackManager.queue.entry.collectAsState()
	val item = entry?.baseItem
	val hasEpisodeControls = item?.type == BaseItemKind.EPISODE && item?.seriesId != null

	val firstButtonFocus = remember { FocusRequester() }
	val seekbarFocus = remember { FocusRequester() }
	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 1.seconds)

	// The time the user is scrubbing to, shown in the right hand time label until the seek is
	// done. The bar itself keeps the target visible, so no extra text is drawn over the picture.
	var scrubPosition by remember { mutableStateOf<Duration?>(null) }
	// The seek that was issued and not reached yet; keeps the right hand time label on the
	// target while the player loads.
	var pendingPosition by remember { mutableStateOf<Duration?>(null) }

	Box(modifier = Modifier.fillMaxWidth()) {
		Column(
			verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
			modifier = Modifier.fillMaxWidth(),
		) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.fillMaxWidth()
				.focusRestorer()
				.focusGroup()
				// DOWN from any button moves to the progress bar; without this the seekbar can
				// not be reached with the remote at all.
				.focusProperties { down = seekbarFocus },
		) {
			PlayPauseButton(
				playbackManager = playbackManager,
				playState = playState,
				modifier = Modifier
					.size(44.dp)
					.focusRequester(firstButtonFocus),
			)

			AudioTrackButton(playbackManager, Modifier.size(44.dp))
			SubtitleButton(playbackManager, Modifier.size(44.dp))

			if (hasEpisodeControls) {
				ControlBarSeparator()
				EpisodeButtons(playbackManager)
			}

			MoreSettingsButton(
				onClick = onMoreOptionsClick,
				modifier = Modifier.size(44.dp),
			)

			Spacer(Modifier.weight(1f))

			TimeLabel(positionInfo, scrubPosition ?: pendingPosition)
		}

		PlayerSeekbar(
			playbackManager = playbackManager,
			modifier = Modifier
				.fillMaxWidth()
				// A 3dp bar gives a 12dp knob in the seekbar component, the size the TV players use.
				.height(3.dp)
				.focusRequester(seekbarFocus)
				.focusProperties { up = firstButtonFocus }
				.onPreviewKeyEvent { event ->
					if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
						onHideControls()
						true
					} else {
						false
					}
				},
			onScrubProgress = { position -> scrubPosition = position },
			onPendingProgress = { position -> pendingPosition = position },
			onScrubbingChange = { scrubbing ->
				playbackManager.state.setScrubbing(scrubbing)
				if (!scrubbing) scrubPosition = null
			},
		)
		}
	}
}

/**
 * The episode controls: previous episode, next episode and the episode picker.
 *
 * The buttons live in the same row as the playback buttons (the row is the only layer above the
 * progress bar) and are separated from them by a thin line so a press does not jump from pause to
 * the previous episode.
 */
@Composable
private fun EpisodeButtons(playbackManager: PlaybackManager) {
	val scope = rememberCoroutineScope()
	val entry by playbackManager.queue.entry.collectAsState()
	var canPrevious by remember { mutableStateOf(false) }
	var canNext by remember { mutableStateOf(false) }

	val buttonColors = ButtonDefaults.colors(
		containerColor = Color.Transparent,
		contentColor = Color.White,
		focusedContainerColor = Color.White,
		focusedContentColor = Color.Black,
		disabledContainerColor = Color.Transparent,
		disabledContentColor = Color.White.copy(alpha = 0.35f),
	)

	LaunchedEffect(entry) {
		canPrevious = runCatching { playbackManager.queue.peekPrevious() }.getOrNull() != null
		canNext = runCatching { playbackManager.queue.peekNext() }.getOrNull() != null
	}

	PlayerIconButton(
		onClick = { scope.launch { playbackManager.queue.previous() } },
		enabled = canPrevious,
		colors = buttonColors,
		modifier = Modifier.size(44.dp),
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_previous_episode),
			contentDescription = stringResource(R.string.lbl_previous_episode),
		)
	}

	PlayerIconButton(
		onClick = { scope.launch { playbackManager.queue.next() } },
		enabled = canNext,
		colors = buttonColors,
		modifier = Modifier.size(44.dp),
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_next_episode),
			contentDescription = stringResource(R.string.lbl_next_episode),
		)
	}

	SelectEpisodeButton(
		playbackManager = playbackManager,
		colors = buttonColors,
		modifier = Modifier.size(44.dp),
	)
}

/**
 * A thin line between the playback buttons and the episode buttons. It keeps the visual groups
 * apart without adding another row.
 */
@Composable
private fun ControlBarSeparator() = Box(
	modifier = Modifier
		.padding(horizontal = 8.dp)
		.width(1.dp)
		.height(24.dp)
		.background(Color.White.copy(alpha = 0.25f)),
)

@Composable
private fun TimeLabel(positionInfo: PositionInfo, positionOverride: Duration? = null) {
	if (positionInfo.duration == Duration.ZERO) return

	val includeHours = positionInfo.duration.inWholeMinutes >= 60
	val current = positionOverride ?: positionInfo.active
	val text = "${current.formatted(includeHours)} / ${positionInfo.duration.formatted(includeHours)}"

	Text(
		text = text,
		fontSize = 13.sp,
		style = LocalTextStyle.current.copy(
			color = Color.White.copy(alpha = 0.85f),
			// Tabular figures keep the label from jumping while the seconds change.
			fontFeatureSettings = "tnum",
		),
	)
}

@Composable
private fun SelectEpisodeButton(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
	colors: ButtonColors = ButtonDefaults.colors(),
) {
	val entry by playbackManager.queue.entry.collectAsState()
	val current = entry?.baseItem?.takeIf { it.type == BaseItemKind.EPISODE && it.seriesId != null } ?: return
	val context = LocalContext.current
	val owner = LocalLifecycleOwner.current
	val api = koinInject<ApiClient>()
	val preferences = koinInject<UserPreferences>()
	PlayerIconButton(
		onClick = {
			EpisodePickerDialog.show(context, owner, api, current) { episode ->
				// Use the whole season so the previous episode is available after the switch as well.
				val queuingEnabled = preferences[UserPreferences.mediaQueuingEnabled]
				val items = if (queuingEnabled) {
					EpisodeCatalog(api).seasonItems(episode)
				} else {
					listOf(episode)
				}
				val startIndex = items.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
				playbackManager.state.pause()
				playbackManager.queue.clear()
				playbackManager.queue.addSupplier(RewriteMediaManager.BaseItemQueueSupplier(api, items, false))
				if (startIndex > 0) playbackManager.queue.setIndex(startIndex)
				playbackManager.state.play()
				true
			}
		},
		modifier = modifier,
		colors = colors,
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
	PlayerIconButton(
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
private fun MoreSettingsButton(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) = PlayerIconButton(
	onClick = onClick,
	modifier = modifier,
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
private fun SubtitleButton(playbackManager: PlaybackManager, modifier: Modifier = Modifier) {
	val backend = playbackManager.backend as? SubtitleTrackBackend ?: return
	var enabled by remember { mutableStateOf(backend.isSubtitlesEnabled()) }

	PlayerIconButton(
		onClick = {
			enabled = !enabled
			backend.setSubtitlesEnabled(enabled)
		},
		modifier = modifier,
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
private fun AudioTrackButton(playbackManager: PlaybackManager, modifier: Modifier = Modifier) {
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
		PlayerIconButton(
			onClick = {
				tracks = backend.getAudioTracks()
				expanded = true
			},
			modifier = modifier,
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



