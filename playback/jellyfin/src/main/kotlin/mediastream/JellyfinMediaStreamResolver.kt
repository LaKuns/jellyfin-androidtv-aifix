package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.PlaybackDegradation
import org.jellyfin.playback.core.mediastream.playbackDegradation
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackInfoDto

class JellyfinMediaStreamResolver(
	private val api: ApiClient,
	private val deviceProfileBuilder: () -> DeviceProfile,
) : MediaStreamResolver {
	companion object {
		private val supportedMediaTypes = arrayOf(MediaType.VIDEO, MediaType.AUDIO)
	}

	override suspend fun getStream(queueEntry: QueueEntry): PlayableMediaStream? {
		val baseItem = queueEntry.baseItem
		if (baseItem == null || !supportedMediaTypes.contains(baseItem.mediaType)) return null

		// Playback starts as direct play. When the player reported an error the entry is degraded
		// and the server is asked for a stream that is more likely to play (see
		// [PlaybackDegradation]).
		val degradation = queueEntry.playbackDegradation ?: PlaybackDegradation.NONE
		val mediaInfo = getPlaybackInfo(baseItem, queueEntry.mediaSourceId, degradation)
		val source = mediaInfo.mediaSource

		// Direct play video
		if (degradation == PlaybackDegradation.NONE && source.supportsDirectPlay && baseItem.mediaType == MediaType.VIDEO) {
			return mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.None,
				url = api.videosApi.getVideoStreamUrl(
					itemId = baseItem.id,
					container = source.container,
					mediaSourceId = source.id,
					static = true,
					tag = source.eTag,
					liveStreamId = source.liveStreamId,
				)
			)
		}

		// Direct play audio
		if (degradation == PlaybackDegradation.NONE && source.supportsDirectPlay && baseItem.mediaType == MediaType.AUDIO) {
			return mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.None,
				url = api.audioApi.getAudioStreamUrl(
					itemId = baseItem.id,
					container = source.container,
					mediaSourceId = source.id,
					static = true,
					tag = source.eTag,
					liveStreamId = source.liveStreamId,
				)
			)
		}

		// Remux (direct stream)
		if (degradation != PlaybackDegradation.TRANSCODE && source.supportsDirectStream && source.transcodingUrl != null) {
			return mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.Remux,
				url = api.createUrl(requireNotNull(source.transcodingUrl), ignorePathParameters = true)
			)
		}

		// Transcode
		if (source.supportsTranscoding && source.transcodingUrl != null) {
			return mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.Transcode,
				url = api.createUrl(requireNotNull(source.transcodingUrl), ignorePathParameters = true)
			)
		}

		// No compatible stream found
		return null
	}

	private suspend fun getPlaybackInfo(
		item: BaseItemDto,
		mediaSourceId: String? = null,
		degradation: PlaybackDegradation = PlaybackDegradation.NONE,
	): MediaInfo {
		val profile = deviceProfileBuilder()
		val response by api.mediaInfoApi.getPostedPlaybackInfo(
			itemId = item.id,
			data = PlaybackInfoDto(
				mediaSourceId = mediaSourceId,
				deviceProfile = profile,
				enableDirectPlay = degradation == PlaybackDegradation.NONE,
				enableDirectStream = degradation != PlaybackDegradation.TRANSCODE,
				enableTranscoding = true,
				// A remux may copy the streams, a transcode must re-encode them.
				allowVideoStreamCopy = degradation != PlaybackDegradation.TRANSCODE,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = false,
			)
		)

		if (response.errorCode != null) {
			error("Failed to get media info for item ${item.id} source ${mediaSourceId}: ${response.errorCode}")
		}

		val mediaSource = response.mediaSources
			// Filter out invalid streams (like strm files)
			.filter { it.protocol == MediaProtocol.FILE && !it.isRemote }
			// Select first media source
			.firstOrNull { mediaSourceId == null || it.id == mediaSourceId }

		requireNotNull(mediaSource) {
			"Failed to get media info for item ${item.id} source ${mediaSourceId}: media source missing in response"
		}

		return MediaInfo(
			playSessionId = response.playSessionId.orEmpty(),
			mediaSource = mediaSource
		)
	}

	private fun MediaInfo.toStream(
		queueEntry: QueueEntry,
		conversionMethod: MediaConversionMethod,
		url: String,
	) = PlayableMediaStream(
		identifier = playSessionId,
		conversionMethod = conversionMethod,
		container = getMediaStreamContainer(),
		tracks = getTracks(),
		queueEntry = queueEntry,
		url = url,
	)
}
