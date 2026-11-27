package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.queue.QueueEntry

interface MediaStream {
	val identifier: String
	val conversionMethod: MediaConversionMethod
	val container: MediaStreamContainer
	val tracks: Collection<MediaStreamTrack>
}

data class BasicMediaStream(
	override val identifier: String,
	override val conversionMethod: MediaConversionMethod,
	override val container: MediaStreamContainer,
	override val tracks: Collection<MediaStreamTrack>,
) : MediaStream {
	fun toPlayableMediaStream(
		queueEntry: QueueEntry,
		url: String,
	) = PlayableMediaStream(
		identifier = identifier,
		conversionMethod = conversionMethod,
		container = container,
		tracks = tracks,
		queueEntry = queueEntry,
		url = url,
	)
}

data class PlayableMediaStream(
	override val identifier: String,
	override val conversionMethod: MediaConversionMethod,
	override val container: MediaStreamContainer,
	override val tracks: Collection<MediaStreamTrack>,
	val queueEntry: QueueEntry,
	val url: String,
	/**
	 * Subtitle tracks that are delivered next to the main stream instead of being embedded in it.
	 *
	 * A server side HLS stream can not carry ASS subtitles; those are offered as side loaded
	 * tracks so the player still lists and shows them.
	 */
	val externalSubtitles: List<ExternalSubtitle> = emptyList(),
) : MediaStream

/** A subtitle file the player loads next to the main media stream. */
data class ExternalSubtitle(
	val url: String,
	val mimeType: String,
	val language: String?,
	val label: String?,
	val isDefault: Boolean = false,
)

data class MediaStreamContainer(
	val format: String,
)

sealed interface MediaStreamTrack {
	val codec: String
}

data class MediaStreamAudioTrack(
	override val codec: String,
	val bitrate: Int,
	val channels: Int,
	val sampleRate: Int,
) : MediaStreamTrack

data class MediaStreamVideoTrack(
	override val codec: String,
	val bitrate: Int,
	val width: Int,
	val height: Int,
	val videoRange: String?,
) : MediaStreamTrack

// TODO: Add subtitle track
