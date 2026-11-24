package org.jellyfin.playback.core.mediastream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.plugin.PlaybackRecoveryService
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.timedevent.addTimedEvent
import org.jellyfin.playback.core.timedevent.timedEvents
import timber.log.Timber
import kotlin.time.Duration

internal class MediaStreamService(
	private val mediaStreamResolvers: Collection<MediaStreamResolver>,
	private val preloadDuration: Duration,
) : PlayerService() {
	private companion object {
		private const val TIMED_EVENT_PRELOAD = "MediaStreamServicePreloadNext"
	}

	private var recovery: PlaybackRecoveryService? = null
	private var recoveryJob: Job? = null

	override suspend fun onInitialize() {
		recovery = manager.getService()

		// A player error must not end the playback: reduce the amount of the original streams used
		// for the next attempt (see [PlaybackDegradation]) and start the item again.
		manager.backendService.addListener(object : PlayerBackendEventListener() {
			override fun onPlayStateChange(state: PlayState) {
				if (state != PlayState.ERROR) return
				if (recoveryJob?.isActive == true) return

				recoveryJob = coroutineScope.launch(Dispatchers.Main) {
					recoverFromError()
				}
			}
		})

		manager.queue.entry.onEach { entry ->
			Timber.d("Queue entry changed to $entry")

			if (entry == null) {
				manager.backend?.stop()
			} else {
				playEntry(entry)
				entry.ensurePreloadTimedEvent()
			}
		}.launchIn(coroutineScope + Dispatchers.Main)
	}

	private suspend fun QueueEntry.ensureMediaStream(): Boolean {
		mediaStream = mediaStream ?: mediaStreamResolvers.firstNotNullOfOrNull { resolver ->
			runCatching {
				withContext(Dispatchers.IO) {
					resolver.getStream(this@ensureMediaStream)
				}
			}.onFailure {
				Timber.e(it, "Media stream resolver failed for $this")
			}.getOrNull()
		}

		return mediaStream != null
	}

	private fun QueueEntry.ensurePreloadTimedEvent() {
		if (preloadDuration <= Duration.ZERO) return

		val hasEvent = timedEvents?.any { it.key == TIMED_EVENT_PRELOAD } == true
		if (hasEvent) return

		addTimedEvent(
			TimedEvent.Block(
				key = TIMED_EVENT_PRELOAD,
				start = -preloadDuration,
				end = Duration.INFINITE,
				onActivate = { preloadNextEntry() }
			)
		)
	}

	private suspend fun playEntry(entry: QueueEntry, startPosition: Duration = Duration.ZERO) {
		val backend = requireNotNull(manager.backend)
		val hasMediaStream = entry.ensureMediaStream()

		if (hasMediaStream) {
			backend.playItem(entry)
			if (startPosition > Duration.ZERO) backend.seekTo(startPosition)
		} else if (entry.ensureDegradation()) {
			Timber.w("Unable to resolve a direct stream for $entry, retrying with ${entry.playbackDegradation}")
			playEntry(entry, startPosition)
		} else {
			Timber.e("Unable to resolve stream for entry $entry")

			recovery?.emit(PlaybackRecoveryService.Event.FAILED)

			if (manager.queue.peekNext() != null) {
				manager.queue.next(usePlaybackOrder = true, useRepeatMode = false)
			} else {
				backend.stop()
			}
		}
	}

	/**
	 * The player reported an error. Ask for a less demanding stream and start the same position
	 * again, or give up when the last degradation step was already used.
	 */
	private suspend fun recoverFromError() {
		val entry = manager.queue.entry.value ?: return
		val backend = manager.backend ?: return
		val position = backend.getPositionInfo().position

		if (!entry.ensureDegradation()) {
			Timber.e("Playback failed for $entry at $position, no further degradation possible")
			recovery?.emit(PlaybackRecoveryService.Event.FAILED)
			backend.stop()
			return
		}

		Timber.w("Playback failed for $entry at $position, retrying with ${entry.playbackDegradation}")
		recovery?.emit(
			when (entry.playbackDegradation) {
				PlaybackDegradation.REMUX -> PlaybackRecoveryService.Event.RETRY_REMUX
				else -> PlaybackRecoveryService.Event.RETRY_TRANSCODE
			}
		)

		backend.stop()
		playEntry(entry, position)
	}

	/**
	 * Raise the degradation level of this entry and drop the resolved stream so the next attempt
	 * requests the matching stream from the server.
	 *
	 * @return false when the deepest level ([PlaybackDegradation.TRANSCODE]) was already reached.
	 */
	private fun QueueEntry.ensureDegradation(): Boolean {
		val next = when (playbackDegradation ?: PlaybackDegradation.NONE) {
			PlaybackDegradation.NONE -> PlaybackDegradation.REMUX
			PlaybackDegradation.REMUX -> PlaybackDegradation.TRANSCODE
			PlaybackDegradation.TRANSCODE -> return false
		}

		playbackDegradation = next
		mediaStream = null
		return true
	}

	private fun preloadNextEntry() = coroutineScope.launch(Dispatchers.Main) {
		// Peek into the next item to preload
		val nextItem = manager.queue.peekNext() ?: return@launch

		// Preload media stream information
		val hasMediaStream = nextItem.ensureMediaStream()

		if (hasMediaStream) {
			// Preload media in backend
			val backend = requireNotNull(manager.backend)
			backend.prepareItem(nextItem)
		}
	}
}
