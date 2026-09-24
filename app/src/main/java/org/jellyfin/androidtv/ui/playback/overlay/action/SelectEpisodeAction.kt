package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.View
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.itemdetail.EpisodeCatalog
import org.jellyfin.androidtv.ui.playback.EpisodePickerDialog
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.java.KoinJavaComponent

class SelectEpisodeAction(context: Context, glue: CustomPlaybackTransportControlGlue) : CustomAction(context, glue) {
    init { initializeWithIcon(R.drawable.ic_select_chapter) }

    override fun handleClickAction(
        playbackController: PlaybackController,
        videoPlayerAdapter: VideoPlayerAdapter,
        context: Context,
        view: View,
    ) {
        val current = playbackController.currentlyPlayingItem ?: return
        val fragment = videoPlayerAdapter.masterOverlayFragment
        val api: ApiClient = KoinJavaComponent.get<ApiClient>(ApiClient::class.java)
        val preferences: UserPreferences = KoinJavaComponent.get<UserPreferences>(UserPreferences::class.java)
        EpisodePickerDialog.show(context, fragment, api, current) { episode ->
            val items = EpisodeCatalog(api).playbackItems(episode, preferences[UserPreferences.mediaQueuingEnabled])
            playbackController.playEpisodeQueue(items)
            videoPlayerAdapter.leanbackOverlayFragment.hideOverlay()
            true
        }
    }
}
