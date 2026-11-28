package org.jellyfin.playback.jellyfin

import androidx.lifecycle.Lifecycle
import org.jellyfin.playback.core.plugin.playbackPlugin
import org.jellyfin.playback.jellyfin.lyrics.LyricsPlayerService
import org.jellyfin.playback.jellyfin.mediasegment.MediaSegmentService
import org.jellyfin.playback.jellyfin.mediasegment.MediaSegmentSkipAction
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamResolver
import org.jellyfin.playback.jellyfin.playsession.PlaySessionService
import org.jellyfin.playback.jellyfin.playsession.PlaySessionSocketService
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaSegmentType

fun jellyfinPlugin(
	api: ApiClient,
	deviceProfileBuilder: (forceHevcTranscode: Boolean) -> DeviceProfile,
	mediaSegmentSkipTypes: Set<MediaSegmentType> = emptySet(),
	mediaSegmentActionProvider: (MediaSegmentType) -> MediaSegmentSkipAction = { MediaSegmentSkipAction.ASK_TO_SKIP },
	lifecycle: Lifecycle? = null,
) = playbackPlugin {
	provide(JellyfinMediaStreamResolver(api, deviceProfileBuilder))

	val playSessionService = PlaySessionService(api)
	provide(playSessionService)
	provide(PlaySessionSocketService(api, playSessionService, lifecycle))

	provide(LyricsPlayerService(api))

	if (mediaSegmentSkipTypes.isNotEmpty()) provide(MediaSegmentService(api, mediaSegmentSkipTypes, mediaSegmentActionProvider))
}
