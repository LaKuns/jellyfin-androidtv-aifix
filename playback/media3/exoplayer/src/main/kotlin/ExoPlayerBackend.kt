package org.jellyfin.playback.media3.exoplayer

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.backend.SubtitleTrackBackend
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediatype.MediaType
import org.jellyfin.playback.core.mediastream.mediatype.mediaType
import org.jellyfin.playback.core.mediastream.normalizationGain
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.support.PlaybackBufferSizing
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.jellyfin.playback.media3.exoplayer.support.getPlaySupportReport
import org.jellyfin.playback.media3.exoplayer.support.toFormats
import timber.log.Timber
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
class ExoPlayerBackend(
	private val context: Context,
	private val exoPlayerOptions: ExoPlayerOptions,
) : BasePlayerBackend(), SubtitleTrackBackend {
	companion object {
		const val TS_SEARCH_BYTES_LM = TsExtractor.TS_PACKET_SIZE * 1800
		const val TS_SEARCH_BYTES_HM = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
		const val MEDIA_ITEM_COUNT_MAX = 10
	}

	private var currentStream: PlayableMediaStream? = null
	private var subtitleView: SubtitleView? = null
	private val audioPipeline = ExoPlayerAudioPipeline()
	private val audioAttributeState = AudioAttributeState()
	private val timedEventState = TimedEventState()
	private var lastKnownDuration: Duration? = null

	/**
	 * The tracks of the item that is loaded, kept so the subtitle selection can be applied
	 * again when the user turns the subtitles on or off.
	 */
	private var currentTracks: Tracks? = null

	/**
	 * Whether the subtitles of the current item are turned on. They are on by default: a
	 * film that carries a text track is meant to show it, unlike the old player the user
	 * had to pick a track for.
	 */
	private var subtitlesEnabled = true

	/**
	 * The moment the playback of the current item was requested, used to report how long
	 * the first frame took. The old player reports the same measurement, so both players
	 * can be compared.
	 */
	private var playbackRequestedAtMs: Long? = null

	/** Whether the first drawn subtitle cue of the current item was logged. */
	private var loggedCues = false


	/**
	 * Renders the ASS subtitles into text cues instead of an overlay surface. The overlay
	 * draws a full frame bitmap for every displayed frame, which filled the heap on a 4K
	 * film within three minutes on the box this app is built for, and the overlay surface is
	 * not visible above the video surface in this player. The cues are drawn by the
	 * subtitle view the player attaches, the same way the old player draws them.
	 */
	private val assHandler by lazy {
		AssHandler(AssRenderType.CUES, AssHandlerConfig(10_000, 128, 0))
	}

	private val exoPlayer by lazy {
		val dataSourceFactory = DefaultDataSource.Factory(
			context,
			exoPlayerOptions.baseDataSourceFactory,
		)
		val extractorsFactory = DefaultExtractorsFactory().apply {
			val isLowRamDevice = context.getSystemService<ActivityManager>()?.isLowRamDevice == true
			setTsExtractorTimestampSearchBytes(
				when (isLowRamDevice) {
					true -> TS_SEARCH_BYTES_LM
					false -> TS_SEARCH_BYTES_HM
				}
			)
			setConstantBitrateSeekingEnabled(true)
			setConstantBitrateSeekingAlwaysEnabled(true)
		}

		val mediaSourceFactory = if (exoPlayerOptions.enableLibass) {
			val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
			val assExtractorsFactory = extractorsFactory.withAssMkvSupport(assSubtitleParserFactory, assHandler)
			DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory).apply {
				setSubtitleParserFactory(assSubtitleParserFactory)
			}
		} else DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

		val renderersFactory = DefaultRenderersFactory(context).apply {
			// Falling back to a software decoder hides the real problem: the device advertises
			// hardware support for a format it can only decode in software and the playback
			// silently stalls instead of failing. Devices that are known to be slow should get
			// an error instead of a frozen picture.
			setEnableDecoderFallback(exoPlayerOptions.enableDecoderFallback)
			setExtensionRendererMode(
				when (exoPlayerOptions.preferFfmpeg) {
					true -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
					false -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
				}
			)
		}.let { renderersFactory ->
			if (exoPlayerOptions.enableLibass) AssRenderersFactory(assHandler, renderersFactory)
			else renderersFactory
		}

		val loadControl = DefaultLoadControl.Builder()
			.setBufferDurationsMs(
				exoPlayerOptions.minBufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
				exoPlayerOptions.maxBufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
				exoPlayerOptions.bufferForPlaybackDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
				exoPlayerOptions.bufferForPlaybackAfterRebufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
			)
			// Without this the load control targets a byte count first and only uses the
			// durations above as a fallback, so bufferForPlaybackDuration was not actually
			// deciding how long playback waits before showing a frame after a seek.
			.setPrioritizeTimeOverSizeThresholds(true)
			.setTargetBufferBytes(PlaybackBufferSizing.targetBufferBytes())
			.build()

		ExoPlayer.Builder(context)
			.setLoadControl(loadControl)
			.setRenderersFactory(renderersFactory)
			.setTrackSelector(DefaultTrackSelector(context).apply {
				setParameters(buildUponParameters().apply {
					setAudioOffloadPreferences(
						TrackSelectionParameters.AudioOffloadPreferences.DEFAULT.buildUpon().apply {
							setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
						}.build()
					)
					setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
				})
			})
			.setMediaSourceFactory(mediaSourceFactory)
			.setPauseAtEndOfMediaItems(true)
			.build()
			.also { player ->
				player.addListener(PlayerListener())

				// Measure how fast this device is actually able to download media. The
				// measurement is used to request a bitrate the device can sustain.
				player.addAnalyticsListener(ThroughputAnalyticsListener(context))

				if (exoPlayerOptions.enableDebugLogging) {
					player.addAnalyticsListener(EventLogger())
				}

				if (exoPlayerOptions.enableLibass) {
					assHandler.init(player)
				}
			}
	}

	inner class PlayerListener : Player.Listener {
		override fun onIsPlayingChanged(isPlaying: Boolean) {
			val state = when {
				isPlaying -> PlayState.PLAYING
				exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED -> PlayState.STOPPED
				else -> PlayState.PAUSED
			}
			listener?.onPlayStateChange(state)
		}

		override fun onPlayerError(error: PlaybackException) {
			listener?.onPlayStateChange(PlayState.ERROR)
		}

		override fun onVideoSizeChanged(size: VideoSize) {
			if (size != VideoSize.UNKNOWN) {
				listener?.onVideoSizeChange(size.width, size.height)
			}
		}

		override fun onCues(cueGroup: CueGroup) {
			val cues = cueGroup.cues
			if (cues.isNotEmpty() && !loggedCues) {
				loggedCues = true
				Timber.i("Subtitle cues drawn in %dx%d", subtitleView?.width ?: 0, subtitleView?.height ?: 0)
			}
			subtitleView?.setCues(cues)
		}

		override fun onPlaybackStateChanged(playbackState: Int) {
			onIsPlayingChanged(exoPlayer.isPlaying)
		}

		override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
			if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
				listener?.onMediaStreamEnd(requireNotNull(currentStream))
			}
		}

		override fun onAudioSessionIdChanged(audioSessionId: Int) {
			// The session is created once per playback, so the gain of the user is read again
			// here: a change of the preference applies to the next playback without a restart.
			audioPipeline.userGainMillibels = exoPlayerOptions.audioGain()
			audioPipeline.setAudioSessionId(audioSessionId)
		}

		override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
			val queueEntry = mediaItem?.localConfiguration?.tag as? QueueEntry
			audioPipeline.normalizationGain = queueEntry?.normalizationGain

			// The subtitle selection belongs to the item, so it is applied again when the
			// item changes. The tracks of the new item are not known yet, the selection is
			// applied when they arrive.
			currentTracks = null
			loggedCues = false
		}

		override fun onTracksChanged(tracks: Tracks) {
			currentTracks = tracks
			applySubtitleSelection(tracks)
		}

		override fun onRenderedFirstFrame() {
			val requestedAt = playbackRequestedAtMs ?: return
			playbackRequestedAtMs = null

			val elapsed = SystemClock.elapsedRealtime() - requestedAt
			val method = when {
				// An HLS playlist means the server had to rebuild at least part of the file,
				// the plain file url means the file was sent as it is.
				currentStream?.url?.contains(".m3u8") != false -> "HLS"
				else -> "Direct"
			}
			Timber.i("Playback rendered first frame using %s in %d ms", method, elapsed)
		}

		override fun onTimelineChanged(timeline: Timeline, reason: Int) {
			val duration = exoPlayer.duration.takeUnless { it == C.TIME_UNSET }?.milliseconds
			if (duration == lastKnownDuration) return
			timedEventState.onDurationChange(exoPlayer, duration)
			lastKnownDuration = duration
		}

		override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
			timedEventState.onSeek(oldPosition.positionMs.milliseconds, newPosition.positionMs.milliseconds, lastKnownDuration ?: Duration.ZERO)
		}
	}

	override fun supportsStream(
		stream: MediaStream
	): PlaySupportReport = exoPlayer.getPlaySupportReport(stream.toFormats())

	override fun setSurfaceView(surfaceView: PlayerSurfaceView?) {
		exoPlayer.setVideoSurfaceView(surfaceView?.surface)
	}

	override fun setSubtitleView(surfaceView: PlayerSubtitleView?) {
		if (surfaceView != null) {
			if (subtitleView == null) {
				subtitleView = SubtitleView(surfaceView.context).apply {
					// The view is created here and not by a PlayerView, so the text size has to be
					// set explicitly, otherwise the cues are one pixel high.
					setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
					if (exoPlayerOptions.enableLibass && assHandler.renderType != AssRenderType.CUES) {
						addView(AssSubtitleView(surfaceView.context, assHandler))
					}
				}
			}

			surfaceView.addView(subtitleView)
			surfaceView.post {
				Timber.i("Subtitle view attached: %dx%d", subtitleView?.width ?: 0, subtitleView?.height ?: 0)
			}
		} else {
			(subtitleView?.parent as? ViewGroup)?.removeView(subtitleView)
			subtitleView = null
		}
	}

	override fun prepareItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream)
		val mediaItem = MediaItem.Builder().apply {
			setTag(item)
			setMediaId(stream.hashCode().toString())
			setUri(stream.url)
		}.build()

		// Remove any excessive items from the start
		while (exoPlayer.mediaItemCount > MEDIA_ITEM_COUNT_MAX - 1) exoPlayer.removeMediaItem(0)


		// Add new item to the end of the media item list
		exoPlayer.addMediaItem(mediaItem)

		// Instruct exoplayer to prepare
		exoPlayer.prepare()
	}

	override fun playItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream)
		if (currentStream == stream) return

		currentStream = stream

		var preparedItemIndex = (0 until exoPlayer.mediaItemCount).firstOrNull { index ->
			exoPlayer.getMediaItemAt(index).mediaId == stream.hashCode().toString()
		}

		// Prepare the item now if it doesn't exist yet
		if (preparedItemIndex == null) {
			prepareItem(item)
			preparedItemIndex = exoPlayer.mediaItemCount - 1
		}

		// Seek to prepared media item
		when (preparedItemIndex) {
			exoPlayer.currentMediaItemIndex - 1 -> exoPlayer.seekToPreviousMediaItem()
			exoPlayer.currentMediaItemIndex + 1 -> exoPlayer.seekToNextMediaItem()
			exoPlayer.currentMediaItemIndex -> Unit
			else -> exoPlayer.seekTo(preparedItemIndex, 0)
		}

		// Update audio attributes
		val contentType = when (item.mediaType) {
			MediaType.Video -> C.AUDIO_CONTENT_TYPE_MOVIE
			MediaType.Audio -> C.AUDIO_CONTENT_TYPE_MUSIC
			MediaType.Unknown -> C.AUDIO_CONTENT_TYPE_UNKNOWN
		}

		audioAttributeState.updateAudioAttributes(
			builder = {
				setContentType(contentType)
				setUsage(C.USAGE_MEDIA)
			},
			onChange = { audioAttributes ->
				exoPlayer.setAudioAttributes(audioAttributes, true)
			}
		)

		// Enjoy!
		Timber.i("Playing ${item.mediaStream?.url}")
		playbackRequestedAtMs = SystemClock.elapsedRealtime()
		exoPlayer.play()
	}

	override fun play() {
		// If the item has ended, revert first so the item will start over again
		if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0)
		exoPlayer.play()
	}

	override fun pause() {
		exoPlayer.pause()
	}

	override fun stop() {
		exoPlayer.stop()
		currentStream = null
	}

	override fun seekTo(position: Duration) {
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !exoPlayer.isCurrentMediaItemSeekable) {
			Timber.w("Trying to seek but ExoPlayer doesn't support it for the current item")
		}

		exoPlayer.seekTo(position.inWholeMilliseconds)
	}

	override fun setScrubbing(scrubbing: Boolean) {
		exoPlayer.isScrubbingModeEnabled = scrubbing
	}

	override fun setSpeed(speed: Float) {
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
			Timber.w("Trying to change speed but ExoPlayer doesn't support it for the current item")
		}

		exoPlayer.setPlaybackSpeed(speed)
	}

	override fun getPositionInfo(): PositionInfo = PositionInfo(
		active = exoPlayer.currentPosition.milliseconds,
		buffer = exoPlayer.bufferedPosition.milliseconds,
		duration = lastKnownDuration ?: Duration.ZERO,
	)

	override fun setTimedEvents(timedEvents: List<TimedEvent>) {
		timedEventState.setTimedEvents(exoPlayer, timedEvents)
	}

	override fun isSubtitlesEnabled(): Boolean = subtitlesEnabled

	override fun setSubtitlesEnabled(enabled: Boolean) {
		if (subtitlesEnabled == enabled) return
		subtitlesEnabled = enabled
		Timber.i("Subtitles enabled: %s", enabled)
		loggedCues = false
		currentTracks?.let(::applySubtitleSelection)
	}

	/**
	 * Applies the subtitle selection to the player.
	 *
	 * ExoPlayer does not select a text track on its own: a track is only rendered when it
	 * is selected, and the default selection prefers no subtitles. A film that carries a
	 * subtitle is played with its subtitles here, unless the user turned them off.
	 */
	private fun applySubtitleSelection(tracks: Tracks) {
		val builder = exoPlayer.trackSelectionParameters.buildUpon()
			.clearOverridesOfType(C.TRACK_TYPE_TEXT)
			.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)

		if (subtitlesEnabled) {
			val textGroups = tracks.groups.filter { group ->
				group.type == C.TRACK_TYPE_TEXT &&
					(0 until group.length).any(group::isTrackSupported)
			}

			val chosen = chooseTextTrack(textGroups)
			Timber.i("Subtitle selection: %d text groups, chosen %s", textGroups.size, chosen?.second ?: "none")
			chosen?.let { (group, index) ->
				builder.setOverrideForType(TrackSelectionOverride(group, index))
			}
		}

		exoPlayer.trackSelectionParameters = builder.build()
	}

	/**
	 * Picks the text track that is shown.
	 *
	 * The language of the device wins. A track without a language is taken next, and the
	 * first track that the device can render is the last choice. A device that is set to
	 * Chinese prefers the track that is labelled as simplified Chinese: films often carry
	 * both variants, and the wrong one is worse than none.
	 */
	private fun chooseTextTrack(groups: List<Tracks.Group>): Pair<TrackGroup, Int>? {
		val candidates = groups.flatMap { group ->
			(0 until group.length)
				.filter(group::isTrackSupported)
				.map { index -> group.mediaTrackGroup to index }
		}
		if (candidates.isEmpty()) return null

		val deviceLanguage = Locale.getDefault().language.lowercase()

		if (deviceLanguage.startsWith("zh")) {
			val simplifiedMarkers = listOf("绠€", "gb", "chs")
			candidates.firstOrNull { (group, index) ->
				val label = group.getFormat(index).label.orEmpty().lowercase()
				simplifiedMarkers.any(label::contains)
			}?.let { return it }
		}

		candidates.firstOrNull { (group, index) ->
			group.getFormat(index).language?.lowercase() == deviceLanguage
		}?.let { return it }

		candidates.firstOrNull { (group, index) ->
			group.getFormat(index).language == null
		}?.let { return it }

		return candidates.first()
	}
}
