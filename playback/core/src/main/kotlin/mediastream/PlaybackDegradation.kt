package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import org.jellyfin.playback.core.queue.QueueEntry

/**
 * How much of the original media is still used for the playback of a queue entry.
 *
 * Playback starts with [NONE] (direct play). When the player reports an error the level is raised
 * one step at a time, so the next attempt asks the server for a stream the device is more likely to
 * handle:
 *
 * - [REMUX]: the server may copy the video and audio streams but repackages them in another
 *   container. This fixes container and container-level-metadata problems.
 * - [TRANSCODE]: the server re-encodes the media. This is the last resort and fixes codec and
 *   bitstream problems the device can not decode (for example an unsupported H.264 profile).
 *
 * The level is stored on the queue entry so the stream resolver can request the matching stream.
 */
enum class PlaybackDegradation {
	NONE,
	REMUX,
	TRANSCODE,
}

private val playbackDegradationKey = ElementKey<PlaybackDegradation>("PlaybackDegradation")

/**
 * Degradation level of this entry. Null means [PlaybackDegradation.NONE].
 */
var QueueEntry.playbackDegradation by element(playbackDegradationKey)
