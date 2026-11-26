package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.itemdetail.EpisodeCatalog
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

class VideoPlayerFragment : Fragment() {
	companion object {
		const val EXTRA_POSITION: String = "position"
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val playbackManager by inject<PlaybackManager>()
	private val api by inject<ApiClient>()
	private val userPreferences by inject<UserPreferences>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Hold the picture until the queue is in place: the activity resumes the player and the
		// queue switch below must not start the first entry of the season before that.
		playbackManager.state.pause()

		lifecycleScope.launch {
			val queueItems = videoQueueManager.getCurrentVideoQueue()
			val startIndex = videoQueueManager.getCurrentMediaPosition().coerceAtLeast(0)
			val (items, index) = buildQueueItems(queueItems, startIndex)

			val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(api, items, false)
			Timber.i("Created a queue with ${queueSupplier.items.size} items, starting at $index")
			playbackManager.queue.clear()
			playbackManager.queue.addSupplier(queueSupplier)
			if (index > 0) playbackManager.queue.setIndex(index)

			// Set position
			arguments?.getInt(EXTRA_POSITION)?.milliseconds?.let {
				playbackManager.state.seek(it)
			}
		}
	}

	/**
	 * Returns the items for the queue and the index of the entry that should start playing.
	 *
	 * For an episode the queue only held the current and the following episodes, so the previous
	 * episode was never available. The whole season is loaded instead (the previous episodes are
	 * prepended) and the current episode is played from its place in that list. Episodes of the
	 * following seasons from the original queue are kept behind it so a run through the whole
	 * show is not cut at the season end.
	 */
	private suspend fun buildQueueItems(
		queueItems: List<org.jellyfin.sdk.model.api.BaseItemDto>,
		startIndex: Int,
	): Pair<List<org.jellyfin.sdk.model.api.BaseItemDto>, Int> {
		val anchor = queueItems.getOrNull(startIndex) ?: queueItems.firstOrNull()
			?: return queueItems to 0

		val canExpand = anchor.type == BaseItemKind.EPISODE &&
			startIndex == 0 &&
			userPreferences[UserPreferences.mediaQueuingEnabled] &&
			anchor.seasonId != null
		if (!canExpand) return queueItems to startIndex

		val seasonItems = runCatching { EpisodeCatalog(api).seasonItems(anchor) }.getOrNull()
			?: return queueItems to startIndex
		val anchorIndex = seasonItems.indexOfFirst { it.id == anchor.id }
		if (anchorIndex < 0) return queueItems to startIndex

		val seasonIds = seasonItems.mapTo(HashSet()) { it.id }
		val trailingItems = queueItems.filter { it.id !in seasonIds }
		Timber.i("Expanded the episode queue to its season: $anchorIndex previous item(s), ${trailingItems.size} trailing item(s)")
		return (seasonItems + trailingItems) to anchorIndex
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		BaseScreen {
			VideoPlayerScreen()
		}
	}

	override fun onPause() {
		super.onPause()

		playbackManager.state.pause()
	}

	override fun onResume() {
		super.onResume()

		playbackManager.state.unpause()
	}

	override fun onStop() {
		super.onStop()

		playbackManager.state.stop()
	}
}
