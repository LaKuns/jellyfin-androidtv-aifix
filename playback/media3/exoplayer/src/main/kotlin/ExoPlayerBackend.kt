package org.jellyfin.playback.media3.exoplayer

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import org.jellyfin.playback.core.audio.AudioTrackCandidate
import org.jellyfin.playback.core.audio.AudioTrackSelector
import org.jellyfin.playback.core.backend.AudioTrack
import org.jellyfin.playback.core.backend.AudioTrackBackend
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.backend.SubtitleTrack
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
import org.jellyfin.playback.media3.exoplayer.support.PlaybackQualityMonitor
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
) : BasePlayerBackend(), AudioTrackBackend, SubtitleTrackBackend {
	companion object {
		/** Text size of the old player that the subtitle size preference is relative to. */
		private const val DEFAULT_SUBTITLE_TEXT_SIZE = 24f

		const val TS_SEARCH_BYTES_LM = TsExtractor.TS_PACKET_SIZE * 1800
		const val TS_SEARCH_BYTES_HM = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
		const val MEDIA_ITEM_COUNT_MAX = 10
	}

	private var currentStream: PlayableMediaStream? = null
	private var subtitleView: SubtitleView? = null
	private var qualityMonitor: PlaybackQualityMonitor? = null
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
	 * The audio track the user picked for the current item, or null while the user has not
	 * picked one and the player decides. The selection is applied again when the tracks of
	 * the item arrive, and forgotten when the item changes.
	 */
	private var selectedAudioTrackId: String? = null

	/**
	 * Language of the audio track the user picked on an earlier item, or null while the user has
	 * never picked one. The next item starts with the same language, which is what the old player
	 * did: a library of Chinese films does not fall back to an English track on every new film.
	 */
	private var lastAudioLanguage: String? = null

	/**
	 * The subtitle track the user picked for the current item, or null while the user has not
	 * picked one and the player decides. Forgotten when the item changes, the same way the
	 * audio pick is forgotten.
	 */
	private var selectedSubtitleTrackId: String? = null

	/**
	 * Language of the subtitle track the user picked on an earlier item. The next item prefers
	 * the same language, like the audio pick does; the device language is the fallback.
	 */
	private var lastSubtitleLanguage: String? = null

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
		// The third argument (max render pixels) must stay 0: the library normalises a cue
		// position by dividing the render-frame pixel position by the video size, so any
		// limit below the video resolution shrinks the subtitle and shifts it towards the
		// upper left corner. On a 3840x1608 film a 1920x1080 limit moved the subtitle to
		// roughly half of its correct position (see VideoManager.java for the same rule).
		AssHandler(AssRenderType.CUES, AssHandlerConfig(2_000, 8, 0))
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
			// The style sanitizer runs before libass: subtitles that were authored with an opaque
			// box behind the text are rendered as outlined text only.
			val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
			val assExtractorsFactory = extractorsFactory.withAssMkvSupport(assSubtitleParserFactory, assHandler)
			DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory).apply {
				// Side loaded ASS/SSA files go through the sanitizer; embedded ASS of a direct
				// played file is hidden in favour of the side loaded WebVTT track (see
				// getSubtitleTracks), so its authored box never reaches the screen either.
				setSubtitleParserFactory(AssStyleSanitizingSubtitleParserFactory(assSubtitleParserFactory))
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

		val targetBufferBytes = PlaybackBufferSizing.targetBufferBytes()
		Timber.i(
			"New player load control: target buffer %d bytes, heap %d bytes",
			targetBufferBytes,
			Runtime.getRuntime().maxMemory(),
		)
		val loadControl = DefaultLoadControl.Builder()
			.setBufferDurationsMs(
				exoPlayerOptions.minBufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
				exoPlayerOptions.maxBufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
				exoPlayerOptions.bufferForPlaybackDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
				exoPlayerOptions.bufferForPlaybackAfterRebufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
			)
			// The byte target must win over the durations, otherwise the loader keeps filling
			// until the heap is exhausted (the media3 default target is about 144 MB, which is
			// more than the 128 MB heap of most TV boxes).
			.setPrioritizeTimeOverSizeThresholds(false)
			.setTargetBufferBytes(targetBufferBytes)
			.build()

		ExoPlayer.Builder(context)
			.setLoadControl(loadControl)
			.setRenderersFactory(renderersFactory)
			.setTrackSelector(DefaultTrackSelector(context).apply {
				setParameters(buildUponParameters().apply {
					setAudioOffloadPreferences(
						TrackSelectionParameters.AudioOffloadPreferences.DEFAULT.buildUpon().apply {
							// A HAL that announces a compressed format it cannot decode makes
							// the direct path report the wrong position, so offload is opt-in.
							setAudioOffloadMode(
								if (exoPlayerOptions.audioOffload) {
									TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
								} else {
									TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
								},
							)
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

				// Report the audio/video offset, the dropped frames and the audio underruns with
				// the same log line the old player uses, so one gate script reads both players.
				qualityMonitor = PlaybackQualityMonitor(player)

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
		listener?.onBufferingChange(playbackState == Player.STATE_BUFFERING)
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

			// The numbers describe one item, a new item starts from a clean slate.
			qualityMonitor?.reset()

			// The subtitle selection belongs to the item, so it is applied again when the
			// item changes. The tracks of the new item are not known yet, the selection is
			// applied when they arrive.
			currentTracks = null
			selectedAudioTrackId = null
			selectedSubtitleTrackId = null
			loggedCues = false
		}

		override fun onTracksChanged(tracks: Tracks) {
			currentTracks = tracks
			applySubtitleSelection(tracks)
			applyAudioSelection(tracks)
			applyPreferredAudioLanguage(tracks)
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
					// set explicitly, otherwise the cues are one pixel high. The size and the
					// position follow the subtitle preferences of the user like in the old player;
					// the background stays transparent so a styled subtitle can not cover the
					// picture with a black box.
					val strokeColor = exoPlayerOptions.subtitleStrokeColor
					val hasStroke = (strokeColor ushr 24) != 0
					setFractionalTextSize(
						exoPlayerOptions.subtitleTextSize / DEFAULT_SUBTITLE_TEXT_SIZE *
							SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
					)
					setBottomPaddingFraction(exoPlayerOptions.subtitleBottomPaddingFraction)
					setStyle(
						CaptionStyleCompat(
							exoPlayerOptions.subtitleTextColor,
							Color.TRANSPARENT,
							Color.TRANSPARENT,
							if (hasStroke) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
							strokeColor,
							null,
						)
					)
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

			// Subtitle files that are not part of the main stream (the server sends the ASS
			// tracks of a transcoded film as WebVTT next to the HLS playlist). The media source
			// factory merges them into the playback, so the tracks show up in the subtitle list
			// and can be toggled like embedded ones.
			if (stream.externalSubtitles.isNotEmpty()) {
				setSubtitleConfigurations(
					stream.externalSubtitles.map { subtitle ->
						MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
							.setMimeType(subtitle.mimeType)
							.setLanguage(subtitle.language)
							.setLabel(subtitle.label)
							.setSelectionFlags(if (subtitle.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
							.build()
					}
				)
			}
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

	/** Whether the format is an ASS/SSA subtitle (the only text format with an authored box). */
	private fun isAssSubtitleFormat(format: Format): Boolean {
		val mimeType = format.sampleMimeType?.lowercase() ?: return false
		return "ssa" in mimeType || "ass" in mimeType
	}

	/**
	 * The text track groups the viewer may pick.
	 *
	 * When the stream carries side loaded WebVTT subtitles the embedded ASS/SSA tracks are hidden:
	 * their authored style can draw a black box over the picture, while the side loaded WebVTT
	 * track contains the same text with a clean style.
	 */
	private fun subtitleGroups(tracks: Tracks): List<Tracks.Group> {
		val hasSideLoadedSubtitles = currentStream?.externalSubtitles?.isNotEmpty() == true
		return tracks.groups
			.filter { group -> group.type == C.TRACK_TYPE_TEXT }
			.filter { group ->
				!hasSideLoadedSubtitles || (0 until group.length).none { index ->
					isAssSubtitleFormat(group.getTrackFormat(index))
				}
			}
	}

	override fun getSubtitleTracks(): List<SubtitleTrack> {
		val tracks = currentTracks ?: readCurrentTracks() ?: return emptyList()

		val subtitleTracks = subtitleGroups(tracks)
			
			.flatMap { group ->
				(0 until group.length)
					.filter(group::isTrackSupported)
					.map { index ->
						val format = group.mediaTrackGroup.getFormat(index)
						SubtitleTrack(
							id = subtitleTrackId(group.mediaTrackGroup, index),
							label = subtitleTrackLabel(format, index),
							language = format.language,
							isSelected = group.isTrackSelected(index),
						)
					}
			}
		Timber.i("Subtitle tracks: %d (%s)", subtitleTracks.size, subtitleTracks.joinToString { it.label })
		return subtitleTracks
	}

	override fun setSubtitleTrack(id: String) {
		val tracks = currentTracks ?: readCurrentTracks() ?: return
		val (group, index) = findSubtitleTrack(tracks, id) ?: run {
			Timber.w("Subtitle track %s does not exist in the current item", id)
			return
		}

		val format = group.getFormat(index)
		selectedSubtitleTrackId = id
		lastSubtitleLanguage = format.language
		subtitlesEnabled = true
		exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
			.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
			.setOverrideForType(TrackSelectionOverride(group, index))
			.build()
		loggedCues = false
		Timber.i("Subtitle track selected: %s", subtitleTrackLabel(format, index))
	}

	override fun getAudioTracks(): List<AudioTrack> {
		val tracks = currentTracks ?: readCurrentTracks() ?: return emptyList()

		val audioTracks = tracks.groups
			.filter { group -> group.type == C.TRACK_TYPE_AUDIO }
			.flatMap { group ->
				(0 until group.length)
					.filter(group::isTrackSupported)
					.map { index ->
						val format = group.mediaTrackGroup.getFormat(index)
						AudioTrack(
							id = audioTrackId(group.mediaTrackGroup, index),
							label = audioTrackLabel(format, index),
							isSelected = group.isTrackSelected(index),
						)
					}
			}
		Timber.i(
			"Audio tracks: %d of %d groups (%s)",
			audioTracks.size,
			tracks.groups.size,
			tracks.groups.joinToString { "%d/%d".format(it.type, it.length) },
		)
		return audioTracks
	}

	override fun setAudioTrack(id: String) {
		val tracks = currentTracks ?: readCurrentTracks() ?: return
		val (group, index) = findAudioTrack(tracks, id) ?: run {
			Timber.w("Audio track %s does not exist in the current item", id)
			return
		}

		selectedAudioTrackId = id
		// Remember the language, not the track: the next item is another file.
		lastAudioLanguage = group.getFormat(index).language
		applyAudioSelection(tracks)
		Timber.i("Audio track selected: %s", audioTrackLabel(group.getFormat(index), index))
	}

	/**
	 * Reads the tracks straight from the player.
	 *
	 * The tracks normally arrive through [PlayerListener.onTracksChanged], but that callback
	 * only fires on a change: an item that was already prepared before the player was built
	 * (a resumed session) never reports its tracks, so they have to be read on demand.
	 */
	private fun readCurrentTracks(): Tracks? {
		val tracks = runCatching { exoPlayer.currentTracks }.getOrNull() ?: return null
		currentTracks = tracks
		return tracks
	}

	/**
	 * Applies the audio track the user picked to the player.
	 *
	 * The pick is remembered so it can be applied again after the tracks are re-read, for
	 * example when the player switches quality or rebuffers. Without a pick the player
	 * keeps its own choice, which prefers the language of the device.
	 */
	private fun applyAudioSelection(tracks: Tracks) {
		val id = selectedAudioTrackId ?: return
		val (group, index) = findAudioTrack(tracks, id) ?: return

		exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
			.setOverrideForType(TrackSelectionOverride(group, index))
			.build()
	}

	/**
	 * Applies the language of the track the user picked on an earlier item.
	 *
	 * Without this the media3 selector decides, and a file whose default track is a format the
	 * box can not play starts silent. The chain is the one of the old player, see
	 * [AudioTrackSelector].
	 */
	private fun applyPreferredAudioLanguage(tracks: Tracks) {
		// A pick of the user for this item always wins.
		if (selectedAudioTrackId != null) return
		val language = lastAudioLanguage ?: return

		val chosen = AudioTrackSelector.choose(
			candidates = audioTrackCandidates(tracks),
			preferredLanguage = language,
			deviceLanguage = Locale.getDefault().language,
		) ?: return

		val (group, index) = findAudioTrack(tracks, chosen.id) ?: return
		exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
			.setOverrideForType(TrackSelectionOverride(group, index))
			.build()
		Timber.i("Preferred audio track: %s", audioTrackLabel(group.getFormat(index), index))
	}

	/** All audio tracks of the loaded media, in file order, for the default selection. */
	private fun audioTrackCandidates(tracks: Tracks): List<AudioTrackCandidate> =
		tracks.groups
			.filter { group -> group.type == C.TRACK_TYPE_AUDIO }
			.flatMap { group ->
				(0 until group.length).map { index ->
					val format = group.getTrackFormat(index)
					AudioTrackCandidate(
						id = audioTrackId(group.mediaTrackGroup, index),
						language = format.language,
						isDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
						isSupported = group.isSupported,
					)
				}
			}

	private fun findAudioTrack(tracks: Tracks, id: String): Pair<TrackGroup, Int>? =
		tracks.groups
			.filter { group -> group.type == C.TRACK_TYPE_AUDIO }
			.flatMap { group -> (0 until group.length).map { index -> group.mediaTrackGroup to index } }
			.firstOrNull { (group, index) -> audioTrackId(group, index) == id }

	/**
	 * Identifies a track inside the media that is loaded. The track group id of ExoPlayer is
	 * stable for the lifetime of the item, so the same id can be used to select the track
	 * again after the selection was lost.
	 */
	private fun audioTrackId(group: TrackGroup, index: Int): String = "${group.id}#$index"

	/**
	 * Builds the label of an audio track the way the user reads it: language, codec and
	 * channel layout, for example "English 路 E-AC3 路 5.1". Only the parts that are known
	 * are shown, and a track without any information falls back to its number.
	 */
	private fun audioTrackLabel(format: Format, index: Int): String {
		val parts = ArrayList<String>(3)

		format.label?.takeIf { label -> label.isNotBlank() }?.let(parts::add)

		format.language?.let { language ->
			val name = Locale(language).getDisplayLanguage(Locale.getDefault())
			if (name.isNotBlank()) parts.add(name)
		}

		val mimeType = format.sampleMimeType
		val codec = when (mimeType?.lowercase(Locale.ROOT)) {
			"audio/ac3" -> "AC-3"
			"audio/eac3", "audio/eac3-joc" -> "E-AC3"
			"audio/vnd.dts" -> "DTS"
			"audio/vnd.dts.hd" -> "DTS-HD"
			"audio/true-hd" -> "TrueHD"
			"audio/mp4a-latm", "audio/aac" -> "AAC"
			"audio/flac" -> "FLAC"
			"audio/opus" -> "Opus"
			"audio/mpeg" -> "MP3"
			"audio/vorbis" -> "Vorbis"
			null -> null
			else -> mimeType.substringAfter('/').uppercase(Locale.ROOT)
		}
		codec?.let(parts::add)

		val channels = when (format.channelCount) {
			1 -> "Mono"
			2 -> "Stereo"
			6 -> "5.1"
			7 -> "6.1"
			8 -> "7.1"
			else -> null
		}
		channels?.let(parts::add)

		return parts.takeIf { it.isNotEmpty() }?.joinToString(" 路 ") ?: "Track ${index + 1}"
	}

	/**
	 * Applies the subtitle selection to the player.
	 *	 * ExoPlayer does not select a text track on its own: a track is only rendered when it
	 * is selected, and the default selection prefers no subtitles. A film that carries a
	 * subtitle is played with its subtitles here, unless the user turned them off.
	 */
	private fun applySubtitleSelection(tracks: Tracks) {
		val builder = exoPlayer.trackSelectionParameters.buildUpon()
			.clearOverridesOfType(C.TRACK_TYPE_TEXT)
			.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)

		if (subtitlesEnabled) {
			Timber.i("Subtitle groups: %s (sideLoaded=%d currentStream=%s)", tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.joinToString { group -> (0 until group.length).joinToString("|") { index -> group.getTrackFormat(index).sampleMimeType ?: "?" } }, currentStream?.externalSubtitles?.size ?: -1, currentStream != null)
			val textGroups = subtitleGroups(tracks).filter { group ->
			(0 until group.length).any(group::isTrackSupported)
		}

			// The pick of the user for this item always wins over the automatic choice.
			val chosen = selectedSubtitleTrackId?.let { id -> findSubtitleTrack(tracks, id) }
				?: chooseTextTrack(textGroups)
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
	private fun findSubtitleTrack(tracks: Tracks, id: String): Pair<TrackGroup, Int>? =
		subtitleGroups(tracks)
			.flatMap { group -> (0 until group.length).map { index -> group.mediaTrackGroup to index } }
			.firstOrNull { (group, index) -> subtitleTrackId(group, index) == id }

	/** Identifies a subtitle track inside the loaded media, see [audioTrackId]. */
	private fun subtitleTrackId(group: TrackGroup, index: Int): String = "${group.id}#$index"

	/**
	 * Builds the label of a subtitle track: language and format, for example
	 * "简体中文 · SSA/ASS". Only the parts that are known are shown.
	 */
	private fun subtitleTrackLabel(format: Format, index: Int): String {
		val parts = ArrayList<String>(2)

		format.label?.takeIf { label -> label.isNotBlank() }?.let(parts::add)

		format.language?.let { language ->
			val name = Locale(language).getDisplayLanguage(Locale.getDefault())
			if (name.isNotBlank()) parts.add(name)
		}

		val formatName = when (format.sampleMimeType?.lowercase(Locale.ROOT)) {
			MimeTypes.TEXT_SSA -> "SSA/ASS"
			MimeTypes.APPLICATION_SUBRIP -> "SRT"
			MimeTypes.TEXT_VTT -> "VTT"
			MimeTypes.APPLICATION_PGS -> "PGS"
			MimeTypes.APPLICATION_VOBSUB -> "VobSub"
			MimeTypes.APPLICATION_TTML -> "TTML"
			else -> null
		}
		formatName?.let(parts::add)

		return parts.distinct().joinToString(" · ").ifBlank { "字幕 ${index + 1}" }
	}

	/**
	 * Picks the text track that is shown.
	 *
	 * The language of the track the user picked on an earlier item wins. After that the
	 * language of the device is preferred; a track without a language is taken next, and the
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

		lastSubtitleLanguage?.let { preferredLanguage ->
			candidates.firstOrNull { (group, index) ->
				group.getFormat(index).language?.equals(preferredLanguage, ignoreCase = true) == true
			}?.let { return it }
		}

		val deviceLanguage = Locale.getDefault().language.lowercase()

		if (deviceLanguage.startsWith("zh")) {
			val simplifiedMarkers = listOf("简体", "简中", "chs", "gb", "sc")
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
