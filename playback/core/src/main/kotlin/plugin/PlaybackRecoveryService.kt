package org.jellyfin.playback.core.plugin

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Reports the degradation attempts of the playback so the user interface can tell the user what
 * happens when a video does not start as it is (for example "retrying with transcoding").
 */
class PlaybackRecoveryService : PlayerService() {
	enum class Event {
		/** The next attempt is a remux (video and audio copied, container rebuilt). */
		RETRY_REMUX,

		/** The next attempt is a full transcode. */
		RETRY_TRANSCODE,

		/** No stream could be played at all. */
		FAILED,
	}

	private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)

	val events: SharedFlow<Event> = _events

	internal fun emit(event: Event) {
		_events.tryEmit(event)
	}
}
