package org.jellyfin.androidtv.ui.itemdetail

import android.content.ActivityNotFoundException
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.PerformanceProfile
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.getSeriesOverview
import org.jellyfin.androidtv.util.popupMenu
import org.jellyfin.androidtv.util.sdk.TrailerUtils.getExternalTrailerIntent
import org.jellyfin.androidtv.util.sdk.compat.canResume
import org.jellyfin.androidtv.util.sdk.compat.copyWithUserData
import org.jellyfin.androidtv.util.showIfNotEmpty
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUID
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun FullDetailsFragment.deleteItem(
	api: ApiClient,
	item: BaseItemDto,
	dataRefreshService: DataRefreshService,
	navigationRepository: NavigationRepository,
) = lifecycleScope.launch {
	Timber.i("Deleting item ${item.name} (id=${item.id})")

	try {
		withContext(Dispatchers.IO) {
			api.libraryApi.deleteItem(item.id)
		}
	} catch (error: ApiClientException) {
		Timber.e(error, "Failed to delete item ${item.name} (id=${item.id})")
		Toast.makeText(
			context,
			getString(R.string.item_deletion_failed, item.name),
			Toast.LENGTH_LONG
		).show()
		return@launch
	}

	dataRefreshService.lastDeletedItemId = item.id

	if (navigationRepository.canGoBack) navigationRepository.goBack()
	else navigationRepository.navigate(Destinations.home)

	Toast.makeText(context, getString(R.string.item_deleted, item.name), Toast.LENGTH_LONG).show()
}

fun FullDetailsFragment.showDetailsMenu(
	view: View,
	baseItemDto: BaseItemDto,
) = popupMenu(requireContext(), view) {
	// for each button check if it exists (not-null) and is invisible (overflow prevention)
	if (queueButton?.isVisible == false) {
		item(getString(R.string.lbl_add_to_queue)) { addItemToQueue() }
	}

	if (shuffleButton?.isVisible == false) {
		item(getString(R.string.lbl_shuffle_all)) { shufflePlay() }
	}

	if (trailerButton?.isVisible == false) {
		item(getString(R.string.lbl_play_trailers)) { playTrailers() }
	}

	if (favButton?.isVisible == false) {
		val favoriteStringRes = when (baseItemDto.userData?.isFavorite) {
			true -> R.string.lbl_remove_favorite
			else -> R.string.lbl_add_favorite
		}

		item(getString(favoriteStringRes)) { toggleFavorite() }
	}

	if (goToSeriesButton?.isVisible == false) {
		item(getString(R.string.lbl_goto_series)) { gotoSeries() }
	}
}.showIfNotEmpty()

fun FullDetailsFragment.createFakeSeriesTimerBaseItemDto(timer: SeriesTimerInfoDto) = BaseItemDto(
	id = requireNotNull(timer.id).toUUID(),
	type = BaseItemKind.FOLDER,
	mediaType = MediaType.UNKNOWN,
	seriesTimerId = timer.id,
	name = timer.name,
	overview = timer.getSeriesOverview(requireContext()),
)

fun FullDetailsFragment.toggleFavorite() {
	val itemMutationRepository by inject<ItemMutationRepository>()
	val dataRefreshService by inject<DataRefreshService>()

	lifecycleScope.launch {
		val userData = itemMutationRepository.setFavorite(
			item = mBaseItem.id,
			favorite = !(mBaseItem.userData?.isFavorite ?: false)
		)
		mBaseItem = mBaseItem.copyWithUserData(userData)
		favButton.isActivated = userData.isFavorite
		dataRefreshService.lastFavoriteUpdate = Instant.now()
	}
}

fun FullDetailsFragment.togglePlayed() {
	val itemMutationRepository by inject<ItemMutationRepository>()
	val dataRefreshService by inject<DataRefreshService>()

	lifecycleScope.launch {
		val userData = itemMutationRepository.setPlayed(
			item = mBaseItem.id,
			played = !(mBaseItem.userData?.played ?: false)
		)
		mBaseItem = mBaseItem.copyWithUserData(userData)
		mWatchedToggleButton.isActivated = userData.played

		// Adjust resume
		mResumeButton?.apply {
			isVisible = mBaseItem.canResume
		}

		// Force lists to re-fetch
		dataRefreshService.lastPlayback = Instant.now()
		when (mBaseItem.type) {
			BaseItemKind.MOVIE -> dataRefreshService.lastMoviePlayback = Instant.now()
			BaseItemKind.EPISODE -> dataRefreshService.lastTvPlayback = Instant.now()
			else -> Unit
		}

		showMoreButtonIfNeeded()
	}
}

fun FullDetailsFragment.playTrailers() {
	val localTrailerCount = mBaseItem.localTrailerCount ?: 0

	// External trailer
	if (localTrailerCount < 1) try {
		val intent = getExternalTrailerIntent(requireContext(), mBaseItem)
		if (intent != null) startActivity(intent)
	} catch (exception: ActivityNotFoundException) {
		Timber.w(exception, "Unable to open external trailer")
		Toast.makeText(
			requireContext(),
			getString(R.string.no_player_message),
			Toast.LENGTH_LONG
		).show()
	} else lifecycleScope.launch {
		val api by inject<ApiClient>()

		try {
			val trailers = withContext(Dispatchers.IO) {
				api.userLibraryApi.getLocalTrailers(mBaseItem.id).content
			}
			play(trailers, 0, false)
		} catch (exception: ApiClientException) {
			Timber.e(exception, "Error retrieving trailers for playback")
			Toast.makeText(
				requireContext(),
				getString(R.string.msg_video_playback_error),
				Toast.LENGTH_LONG
			).show()
		}
	}
}

fun FullDetailsFragment.getItem(id: UUID, callback: (item: BaseItemDto?) -> Unit) {
	val api by inject<ApiClient>()

	lifecycleScope.launch {
		val response = try {
			withContext(Dispatchers.IO) {
				api.userLibraryApi.getItem(id).content
			}
		} catch (err: ApiClientException) {
			Timber.w(err, "Failed to get item $id")
			null
		}

		callback(response)
	}
}

fun FullDetailsFragment.populatePreviousButton() {
	if (mBaseItem.type != BaseItemKind.EPISODE) return

	val api by inject<ApiClient>()

	lifecycleScope.launch {
		val siblings = withContext(Dispatchers.IO) {
			api.tvShowsApi.getEpisodes(
				seriesId = requireNotNull(mBaseItem.seriesId),
				adjacentTo = mBaseItem.id,
			).content
		}

		val previousItem = siblings.items
			.filterNot { it.id == mBaseItem.id }
			.firstOrNull()
			?.id

		mPrevItemId = previousItem
		mPrevButton.isVisible = previousItem != null

		showMoreButtonIfNeeded()
	}
}

fun FullDetailsFragment.getNextUpEpisode(callback: (BaseItemDto?) -> Unit) {
	lifecycleScope.launch {
		val nextUpEpisode = getNextUpEpisode()
		callback(nextUpEpisode)
	}
}

/** Put a compact season/episode selector directly beneath the overview actions. */
fun FullDetailsFragment.loadEpisodeSelection() {
	val item = mBaseItem ?: return
	val seriesId = if (item.type == BaseItemKind.SERIES) item.id else item.seriesId ?: return
	val api by inject<ApiClient>()
	val adapter = mRowsAdapter ?: return
	val catalog = EpisodeCatalog(api, PerformanceProfile.isLowPerformanceDevice(requireContext()))
	mEpisodeCatalog = catalog
	mEpisodeSelectionJob?.cancel()
	clearEpisodeSelectionPage()
	showEpisodeMessage(getString(R.string.lbl_loading_elipses))
	mEpisodeSelectionJob = lifecycleScope.launch {
		try {
			val seasons = catalog.seasons(seriesId)
			if (adapter !== mRowsAdapter) return@launch
			mEpisodeSeasons = seasons
			// An unplayed series already requests Next Up for its main button. Start at season one
			// here instead of duplicating that request on older devices.
			val started = item.userData?.playedPercentage?.let { it > 0 } == true
			val nextUp = if (item.type == BaseItemKind.SERIES && started) {
				try {
					getNextUpEpisode()
				} catch (error: CancellationException) {
					throw error
				} catch (error: Exception) {
					Timber.w(error, "Failed to resolve next up episode for selector")
					null
				}
			} else null
			val selected = seasons.firstOrNull { it.id == (item.seasonId ?: nextUp?.seasonId) }
				?: seasons.firstOrNull()
			if (selected == null) {
				showEpisodeMessage(getString(R.string.lbl_no_items))
				return@launch
			}
			if (adapter !== mRowsAdapter) return@launch
			mSelectedSeasonId = selected.id
			mHighlightedEpisodeId = if (item.type == BaseItemKind.EPISODE) item.id else nextUp?.id
			showEpisodeSeasons(seasons, selected.id)
			val highlightedNumber = (if (item.type == BaseItemKind.EPISODE) item.indexNumber else nextUp?.indexNumber) ?: 1
			val start = ((highlightedNumber - 1).coerceAtLeast(0) / catalog.pageSize) * catalog.pageSize
			mEpisodePageStart = start
			loadEpisodePage(catalog, adapter, seriesId, selected.id, start)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.w(error, "Failed to load episode selector")
			if (adapter === mRowsAdapter) showEpisodeMessage(getString(R.string.msg_episode_selection_load_error))
			if (adapter === mRowsAdapter) context?.let {
				Toast.makeText(it, R.string.msg_episode_selection_load_error, Toast.LENGTH_SHORT).show()
			}
		}
	}
}

fun FullDetailsFragment.selectEpisodeSeason(season: BaseItemDto) {
	val item = mBaseItem ?: return
	if (item.type != BaseItemKind.SERIES && item.seriesId == null) return
	mSelectedSeasonId = season.id
	mHighlightedEpisodeId = item.id.takeIf { item.type == BaseItemKind.EPISODE && item.seasonId == season.id }
	mEpisodePageStart = 0
	clearEpisodeSelectionPage()
	mEpisodeSeasons?.let { showEpisodeSeasons(it, season.id) }
	retryEpisodePage()
}

fun FullDetailsFragment.selectEpisodeRange(range: EpisodeRange) {
	mEpisodePageStart = range.startIndex
	retryEpisodePage()
}

fun FullDetailsFragment.retryEpisodePage() {
	val item = mBaseItem ?: return
	val seriesId = if (item.type == BaseItemKind.SERIES) item.id else item.seriesId ?: return
	val seasonId = mSelectedSeasonId
	val catalog = mEpisodeCatalog
	if (seasonId == null || catalog == null) {
		loadEpisodeSelection()
		return
	}
	val adapter = mRowsAdapter ?: return
	val startIndex = mEpisodePageStart
	mEpisodeSelectionJob?.cancel()
	showEpisodeMessage(getString(R.string.lbl_loading_elipses))
	mEpisodeSelectionJob = lifecycleScope.launch {
		try {
			loadEpisodePage(catalog, adapter, seriesId, seasonId, startIndex)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.w(error, "Failed to load episode page")
			if (adapter === mRowsAdapter) showEpisodeMessage(getString(R.string.msg_episode_selection_load_error))
			if (adapter === mRowsAdapter) context?.let {
				Toast.makeText(it, R.string.msg_episode_selection_load_error, Toast.LENGTH_SHORT).show()
			}
		}
	}
}

private suspend fun FullDetailsFragment.loadEpisodePage(
	catalog: EpisodeCatalog,
	adapter: MutableObjectAdapter<Row>,
	seriesId: UUID,
	seasonId: UUID,
	startIndex: Int,
) {
	var page = catalog.episodes(seriesId, seasonId, startIndex)
	if (page.items.isEmpty() && page.totalCount > 0 && startIndex > 0) {
		page = catalog.episodes(seriesId, seasonId, 0)
	}
	if (adapter !== mRowsAdapter || mSelectedSeasonId != seasonId || mEpisodePageStart != startIndex) return
	mEpisodePageStart = page.startIndex
	showEpisodeRanges(page.ranges, page.startIndex)
	showEpisodeNumbers(page.items, mHighlightedEpisodeId)
}

suspend fun FullDetailsFragment.getNextUpEpisode(): BaseItemDto? {
	val api by inject<ApiClient>()

	try {
		val episodes = withContext(Dispatchers.IO) {
			api.tvShowsApi.getNextUp(
				seriesId = mBaseItem.seriesId ?: mBaseItem.id,
				fields = ItemRepository.itemFields,
				limit = 1,
			).content
		}
		return episodes.items.firstOrNull()
	} catch (err: ApiClientException) {
		Timber.w(err, "Failed to get next up items")
		return null
	}
}

fun FullDetailsFragment.resumePlayback(v: View) {
	if (mBaseItem.type != BaseItemKind.SERIES) {
		val pos = (mBaseItem.userData?.playbackPositionTicks?.ticks
			?: Duration.ZERO) - resumePreroll.milliseconds
		play(mBaseItem, pos.inWholeMilliseconds.toInt(), false)
		return
	}

	lifecycleScope.launch {
		val nextUpEpisode = getNextUpEpisode()
		if (nextUpEpisode == null) {
			Toast.makeText(
				requireContext(),
				getString(R.string.msg_video_playback_error),
				Toast.LENGTH_LONG
			).show()
		} else if (nextUpEpisode.userData?.playbackPositionTicks == 0L) {
			play(nextUpEpisode, 0, false)
		} else {
			showResumeMenu(v, nextUpEpisode)
		}
	}
}

fun FullDetailsFragment.showResumeMenu(
	view: View,
	nextUpEpisode: BaseItemDto
) = popupMenu(requireContext(), view) {
	val pos = (nextUpEpisode.userData?.playbackPositionTicks?.ticks
		?: Duration.ZERO) - resumePreroll.milliseconds
	item(
		getString(
			R.string.lbl_resume_from,
			TimeUtils.formatMillis(pos.inWholeMilliseconds)
		)
	) {
		play(nextUpEpisode, pos.inWholeMilliseconds.toInt(), false)
	}
	item(getString(R.string.lbl_from_beginning)) {
		play(nextUpEpisode, 0, false)
	}
}.showIfNotEmpty()

fun FullDetailsFragment.getLiveTvSeriesTimer(
	id: String,
	callback: (timer: SeriesTimerInfoDto) -> Unit,
) {
	val api by inject<ApiClient>()

	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getSeriesTimer(id).content
			}
		}.onSuccess { timer ->
			callback(timer)
		}
	}
}

fun FullDetailsFragment.getLiveTvProgram(
	id: UUID,
	callback: (program: BaseItemDto) -> Unit,
) {
	val api by inject<ApiClient>()

	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getProgram(id.toString()).content
			}
		}.onSuccess { program ->
			callback(program)
		}
	}
}

fun FullDetailsFragment.createLiveTvSeriesTimer(
	seriesTimer: SeriesTimerInfoDto,
	callback: () -> Unit,
) {
	val api by inject<ApiClient>()

	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.createSeriesTimer(seriesTimer)
			}
		}.onSuccess {
			callback()
		}
	}
}

fun FullDetailsFragment.getLiveTvDefaultTimer(
	id: UUID,
	callback: (seriesTimer: SeriesTimerInfoDto) -> Unit,
) {
	val api by inject<ApiClient>()

	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getDefaultTimer(id.toString()).content
			}
		}.onSuccess { seriesTimer ->
			callback(seriesTimer)
		}
	}
}

fun FullDetailsFragment.cancelLiveTvSeriesTimer(
	timerId: String,
	callback: () -> Unit,
) {
	val api by inject<ApiClient>()

	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.cancelTimer(timerId)
			}
		}.onSuccess {
			callback()
		}
	}
}

fun FullDetailsFragment.getLiveTvChannel(
	id: UUID,
	callback: (channel: BaseItemDto) -> Unit,
) {
	val api by inject<ApiClient>()

	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getChannel(id).content
			}
		}.onSuccess { channel ->
			callback(channel)
		}
	}
}
