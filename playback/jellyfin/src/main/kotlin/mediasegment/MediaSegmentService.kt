package org.jellyfin.playback.jellyfin.mediasegment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.timedevent.BlockActivation
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.timedevent.timedEvents
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MediaSegmentService(
	private val api: ApiClient,
	private val segmentTypes: Set<MediaSegmentType>,
	private val actionProvider: (MediaSegmentType) -> MediaSegmentSkipAction = { MediaSegmentSkipAction.ASK_TO_SKIP },
) : PlayerService() {
	companion object {
		const val TIMED_EVENT_PREFIX = "MediaSegment:"

		private const val ACTIVE_SEGMENT_END_EVENT = "MediaSegmentActiveEnd"
	}

	/** A segment that is currently on screen and may be skipped by the viewer. */
	data class ActiveSegment(
		val type: MediaSegmentType,
		val start: Duration,
		val end: Duration,
	)

	private val _activeSegment = MutableStateFlow<ActiveSegment?>(null)
	val activeSegment: StateFlow<ActiveSegment?> = _activeSegment.asStateFlow()

	override suspend fun onInitialize() {
		// Load media segments for an item as soon as it becomes the currently playing item
		manager.queue.entry
			.filterNotNull()
			.onEach { entry ->
				_activeSegment.value = null
				fetchMediaSegments(entry)
			}
			.onEach { entry -> createTimedEvents(entry) }
			.launchIn(coroutineScope)
	}

	/** Seek past the segment that is currently on screen. */
	fun skipActiveSegment() {
		val segment = _activeSegment.value ?: return
		Timber.i("Skipping %s segment at %s", segment.type, segment.start)
		_activeSegment.value = null
		manager.state.seek(segment.end)
	}

	private suspend fun fetchMediaSegments(entry: QueueEntry) {
		// Already has media segments!
		if (entry.mediaSegments != null) return

		// BaseItem doesn't exist
		val baseItem = entry.baseItem ?: return

		// Get via API
		val mediaSegments by api.mediaSegmentsApi.getItemSegments(baseItem.id)
		entry.mediaSegments = mediaSegments.items
	}

	private fun createTimedEvents(entry: QueueEntry) {
		val events = entry.timedEvents
			?.toMutableList()
			?: mutableListOf()

		// Remove existing media segments
		events.removeAll { event -> event.key?.startsWith(TIMED_EVENT_PREFIX) == true }
		events.removeAll { event -> event.key == ACTIVE_SEGMENT_END_EVENT }

		// Add current media segments
		val mediaSegments = entry.mediaSegments.orEmpty()
		for (mediaSegment in mediaSegments) {
			for (timedEvents in mediaSegment.asTimedEvents()) {
				events.add(timedEvents)
			}
		}

		// Set new timed events
		entry.timedEvents = events.ifEmpty { null }
	}

	private fun MediaSegmentDto.asTimedEvents(): List<TimedEvent> {
		if (!segmentTypes.contains(type)) return emptyList()

		val action = actionProvider(type)
		if (action == MediaSegmentSkipAction.DISABLED) return emptyList()

		val events = mutableListOf(
			TimedEvent.Block(
				key = "$TIMED_EVENT_PREFIX$id",
				start = startTicks.ticks,
				end = endTicks.ticks,
				onActivate = { metadata ->
					if (metadata !is BlockActivation.Natural) return@Block

					coroutineScope.launch(Dispatchers.Main) {
						when (action) {
							MediaSegmentSkipAction.SKIP -> manager.state.seek(endTicks.ticks)
							MediaSegmentSkipAction.ASK_TO_SKIP -> _activeSegment.value = ActiveSegment(
								type = type,
								start = startTicks.ticks,
								end = endTicks.ticks,
							)

							MediaSegmentSkipAction.DISABLED -> Unit
						}
					}
				},
			)
		)

		if (action == MediaSegmentSkipAction.ASK_TO_SKIP) {
			// Clear the prompt when the playback leaves the segment without using the button.
			events += TimedEvent.Block(
				key = "$TIMED_EVENT_PREFIX$id:end",
				start = endTicks.ticks,
				end = endTicks.ticks + 1.milliseconds,
				onActivate = {
					_activeSegment.value = null
				},
			)
		}

		return events
	}
}
