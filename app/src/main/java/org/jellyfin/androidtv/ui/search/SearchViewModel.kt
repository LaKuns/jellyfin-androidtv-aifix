package org.jellyfin.androidtv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SearchViewModel(
	private val searchRepository: SearchRepository
) : ViewModel() {
	companion object {
		private val debounceDuration = 600.milliseconds
		private const val RESULTS_PER_GROUP = 25

		private val groups = mapOf(
			R.string.lbl_movies to setOf(BaseItemKind.MOVIE),
			R.string.lbl_series to setOf(BaseItemKind.SERIES),
			R.string.lbl_episodes to setOf(BaseItemKind.EPISODE),
			R.string.lbl_videos to setOf(BaseItemKind.VIDEO),
			R.string.lbl_programs to setOf(BaseItemKind.LIVE_TV_PROGRAM),
			R.string.channels to setOf(BaseItemKind.LIVE_TV_CHANNEL),
			R.string.lbl_playlists to setOf(BaseItemKind.PLAYLIST),
			R.string.lbl_artists to setOf(BaseItemKind.MUSIC_ARTIST),
			R.string.lbl_albums to setOf(BaseItemKind.MUSIC_ALBUM),
			R.string.lbl_songs to setOf(BaseItemKind.AUDIO),
			R.string.photo_albums to setOf(BaseItemKind.PHOTO_ALBUM),
			R.string.photos to setOf(BaseItemKind.PHOTO),
			R.string.lbl_collections to setOf(BaseItemKind.BOX_SET),
			R.string.lbl_people to setOf(BaseItemKind.PERSON),
		)

		// Keep the visible result rows unchanged while reducing a search from
		// fourteen server requests to six. VIDEO remains separate because its
		// repository query intentionally excludes movies, episodes and TV.
		private val groupBatches = listOf(
			listOf(R.string.lbl_movies, R.string.lbl_series, R.string.lbl_episodes),
			listOf(R.string.lbl_videos),
			listOf(R.string.lbl_programs, R.string.channels),
			listOf(R.string.lbl_playlists, R.string.lbl_artists, R.string.lbl_albums, R.string.lbl_songs),
			listOf(R.string.photo_albums, R.string.photos),
			listOf(R.string.lbl_collections, R.string.lbl_people),
		)
	}

	private var searchJob: Job? = null
	private val requestLimiter = Semaphore(3)

	private var previousQuery: String? = null

	private val _searchResultsFlow = MutableStateFlow<Collection<SearchResultGroup>>(emptyList())
	val searchResultsFlow = _searchResultsFlow.asStateFlow()

	fun searchImmediately(query: String) = searchDebounced(query, 0.milliseconds)

	fun searchDebounced(query: String, debounce: Duration = debounceDuration): Boolean {
		val trimmed = query.trim()
		if (trimmed == previousQuery) return false
		previousQuery = trimmed

		searchJob?.cancel()

		if (trimmed.isBlank()) {
			_searchResultsFlow.value = emptyList()
			return true
		}

		searchJob = viewModelScope.launch {
			delay(debounce)

			val orderedGroups = groups.entries.toList()
			val groupIndexes = orderedGroups.mapIndexed { index, entry -> entry.key to index }.toMap()
			val results = arrayOfNulls<SearchResultGroup>(orderedGroups.size)
			groupBatches.map { labels ->
				launch {
					val itemKinds = labels.flatMapTo(linkedSetOf()) { groups.getValue(it) }
					// Limit simultaneous batched requests so typing does not create
					// a JSON/image/GC spike on older TVs or on the server.
					val result = requestLimiter.withPermit {
						searchRepository.search(trimmed, itemKinds)
					}
					val items = result.getOrNull().orEmpty()

					for (label in labels) {
						val kinds = groups.getValue(label)
						val index = groupIndexes.getValue(label)
						results[index] = SearchResultGroup(
							label,
							items.asSequence()
								.filter { item -> item.type?.let(kinds::contains) == true }
								.take(RESULTS_PER_GROUP)
								.toList()
						)
					}
					_searchResultsFlow.value = results.filterNotNull()
				}
			}.joinAll()
		}

		return true
	}
}
