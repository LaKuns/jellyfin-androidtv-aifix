package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.ExternalSubtitle
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
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import timber.log.Timber

class JellyfinMediaStreamResolver(
	private val api: ApiClient,
	private val deviceProfileBuilder: (forceHevcTranscode: Boolean) -> DeviceProfile,
) : MediaStreamResolver {
	companion object {
		private val supportedMediaTypes = arrayOf(MediaType.VIDEO, MediaType.AUDIO)

		/** Text subtitle formats the server can convert to WebVTT for a transcode or remux stream. */
		private val TEXT_SUBTITLE_CODECS = setOf("ass", "ssa", "srt", "subrip", "vtt", "webvtt", "txt", "ttml", "mov_text")

		/** Codec block size of HEVC; a picture with another alignment can not be copied into TS safely. */
		private const val VIDEO_BLOCK_ALIGNMENT = 16
	}

	override suspend fun getStream(queueEntry: QueueEntry): PlayableMediaStream? {
		val baseItem = queueEntry.baseItem
		if (baseItem == null || !supportedMediaTypes.contains(baseItem.mediaType)) return null

		// Playback starts as direct play. When the player reported an error the entry is degraded
		// and the server is asked for a stream that is more likely to play (see
		// [PlaybackDegradation]).
		val degradation = queueEntry.playbackDegradation ?: PlaybackDegradation.NONE
		var mediaInfo = getPlaybackInfo(baseItem, queueEntry.mediaSourceId, degradation)
		var source = mediaInfo.mediaSource

		// A few HEVC films have a picture height that is not a multiple of the codec block size
		// (for example 3840x1608). When only the audio needs a transcode the server copies the video
		// into an HLS/TS stream, and the hardware decoder of the box draws green blocks for a few
		// scenes of those files. Re-encoding the video avoids the broken remux path.
		if (
			degradation != PlaybackDegradation.TRANSCODE &&
			source.transcodingUrl != null &&
			needsVideoReencode(source)
		) {
			Timber.i("Video remux of an unaligned HEVC picture, asking the server for a re-encode instead")
			mediaInfo = getPlaybackInfo(baseItem, queueEntry.mediaSourceId, degradation, forceHevcTranscode = true)
			source = mediaInfo.mediaSource
		}

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
				),
				// Text subtitles are offered as side loaded WebVTT even for a direct play: the
				// authored ASS styles (including an opaque box behind the text) are stripped by
				// the server conversion, and the player renders one consistent text style.
				externalSubtitles = mediaInfo.externalSubtitles(baseItem, source),
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
				url = api.createUrl(requireNotNull(source.transcodingUrl), ignorePathParameters = true),
				externalSubtitles = mediaInfo.externalSubtitles(baseItem, source)
			)
		}

		// Transcode
		if (source.supportsTranscoding && source.transcodingUrl != null) {
			return mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.Transcode,
				url = api.createUrl(requireNotNull(source.transcodingUrl), ignorePathParameters = true),
				externalSubtitles = mediaInfo.externalSubtitles(baseItem, source)
			)
		}

		// No compatible stream found
		return null
	}

	/**
	 * Whether the video has to be re-encoded instead of copied into the HLS stream.
	 *
	 * The hardware decoder of the box draws green blocks for HEVC pictures whose size is not
	 * aligned to the 16 pixel block of the codec when the picture is copied into MPEG-TS (the
	 * library contains a 3840x1608 film). A re-encode delivers a clean H.264 stream instead.
	 */
	private fun needsVideoReencode(source: MediaSourceInfo): Boolean {
		val video = source.mediaStreams?.firstOrNull { stream -> stream.type == MediaStreamType.VIDEO } ?: run {
			Timber.i("No video stream information, keeping the server decision")
			return false
		}
		if (!video.codec.equals("hevc", ignoreCase = true)) return false
		val width = video.width ?: return false
		val height = video.height ?: return false
		return width % VIDEO_BLOCK_ALIGNMENT != 0 || height % VIDEO_BLOCK_ALIGNMENT != 0
	}

	private suspend fun getPlaybackInfo(
		item: BaseItemDto,
		mediaSourceId: String? = null,
		degradation: PlaybackDegradation = PlaybackDegradation.NONE,
		forceHevcTranscode: Boolean = false,
	): MediaInfo {
		val profile = deviceProfileBuilder(forceHevcTranscode)
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

	/**
	 * Subtitle tracks that have to be loaded next to the HLS stream.
	 *
	 * HLS can only carry WebVTT subtitles, so the ASS/SSA tracks of a film are not part of a
	 * transcoded or remuxed stream. The server offers every text subtitle as WebVTT as well;
	 * those are handed to the player as side loaded tracks, which keeps the subtitle list and
	 * the toggle of the player working for transcoded playback too.
	 */
	private fun MediaInfo.externalSubtitles(
		item: BaseItemDto,
		source: MediaSourceInfo,
	): List<ExternalSubtitle> = source.mediaStreams
		.orEmpty()
		.filter { stream -> stream.type == MediaStreamType.SUBTITLE }
		.filter { stream -> stream.index != null && stream.codec?.lowercase() in TEXT_SUBTITLE_CODECS }
		.sortedWith(compareByDescending<org.jellyfin.sdk.model.api.MediaStream> { it.isDefault }.thenBy { it.index })
		.mapNotNull { stream ->
			val index = stream.index ?: return@mapNotNull null
			val url = runCatching {
				api.subtitleApi.getSubtitleUrl(
					routeItemId = item.id,
					routeMediaSourceId = source.id.toString(),
					routeIndex = index,
					routeFormat = "vtt",
				)
			}.getOrNull() ?: return@mapNotNull null

			ExternalSubtitle(
				url = url,
				mimeType = "text/vtt",
				language = stream.language,
				label = stream.displayTitle ?: stream.title,
				isDefault = stream.isDefault == true,
			)
		}

	private fun MediaInfo.toStream(
		queueEntry: QueueEntry,
		conversionMethod: MediaConversionMethod,
		url: String,
		externalSubtitles: List<ExternalSubtitle> = emptyList(),
	) = PlayableMediaStream(
		identifier = playSessionId,
		conversionMethod = conversionMethod,
		container = getMediaStreamContainer(),
		tracks = getTracks(),
		queueEntry = queueEntry,
		url = url,
		externalSubtitles = externalSubtitles,
	)
}
