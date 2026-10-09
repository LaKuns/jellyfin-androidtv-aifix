package org.jellyfin.androidtv.ui.itemdetail

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemFields
import org.koin.android.ext.android.inject
import java.util.UUID

fun ItemListFragment.loadItem(itemId: UUID) {
	val api by inject<ApiClient>()

	viewLifecycleOwner.lifecycleScope.launch {
		val item = withContext(Dispatchers.IO) {
			api.userLibraryApi.getItem(itemId).content
		}
		if (isActive) setBaseItem(item)
	}
}

/** How many rows are requested per page when paging through a playlist or album. */
private const val PLAYLIST_PAGE_SIZE = 60

/**
 * One page of playlist/album rows.
 *
 * The rows only render name, series name, artist, runtime and the watched marker, so the
 * request asks for [ItemRepository.cardFields] instead of the full item payload. Asking for
 * media sources, streams, chapters and trickplay data here produced a very large JSON body
 * for lists that never display any of it.
 */
data class PlaylistPage(
	val items: List<BaseItemDto>,
	val totalCount: Int,
	val startIndex: Int,
) {
	val hasMore: Boolean get() = startIndex + items.size < totalCount
}

fun MusicFavoritesListFragment.getFavoritePlaylist(
	parentId: UUID?,
	callback: (page: PlaylistPage?) -> Unit
) = getFavoritePage(parentId, 0, callback)

fun MusicFavoritesListFragment.getFavoritePage(
	parentId: UUID?,
	startIndex: Int,
	callback: (page: PlaylistPage?) -> Unit,
) {
	val api by inject<ApiClient>()

	viewLifecycleOwner.lifecycleScope.launch {
		val page = runCatching { withContext(Dispatchers.IO) { requestFavoritePage(api, parentId, startIndex) } }.getOrNull()
		if (isActive) callback(page)
	}
}

private suspend fun requestFavoritePage(
	api: ApiClient, parentId: UUID?, startIndex: Int,
	fields: Set<ItemFields> = ItemRepository.cardFields,
): PlaylistPage {
	val result = api.itemsApi.getItems(
		parentId = parentId,
		includeItemTypes = setOf(BaseItemKind.AUDIO),
		recursive = true,
		filters = setOf(ItemFilter.IS_FAVORITE_OR_LIKES),
		// Pagination needs a stable order; shuffle happens when the queue is launched.
		sortBy = setOf(ItemSortBy.SORT_NAME),
		startIndex = startIndex,
		limit = PLAYLIST_PAGE_SIZE,
		fields = fields,
	).content
	return PlaylistPage(result.items, result.totalRecordCount, startIndex)
}

fun MusicFavoritesListFragment.getAllFavoriteItems(parentId: UUID?, callback: (List<BaseItemDto>?) -> Unit) {
	val api by inject<ApiClient>()
	viewLifecycleOwner.lifecycleScope.launch {
		val items = runCatching {
			withContext(Dispatchers.IO) { collectPages { offset -> requestFavoritePage(api, parentId, offset) } }
		}.getOrNull()
		if (isActive) callback(items)
	}
}

fun ItemListFragment.getPlaylist(
	item: BaseItemDto,
	callback: (page: PlaylistPage?) -> Unit
) = getPlaylistPage(item, 0, callback)

fun ItemListFragment.getPlaylistPage(
	item: BaseItemDto,
	startIndex: Int,
	callback: (page: PlaylistPage?) -> Unit
) {
	val api by inject<ApiClient>()

	viewLifecycleOwner.lifecycleScope.launch {
		val page = runCatching { withContext(Dispatchers.IO) { requestPlaylistPage(api, item, startIndex) } }.getOrNull()
		if (isActive) callback(page)
	}
}

private suspend fun requestPlaylistPage(
	api: ApiClient, item: BaseItemDto, startIndex: Int,
	fields: Set<ItemFields> = ItemRepository.cardFields,
): PlaylistPage {
	val result = when {
		item.type == BaseItemKind.PLAYLIST -> api.playlistsApi.getPlaylistItems(
					playlistId = item.id,
					startIndex = startIndex,
					limit = PLAYLIST_PAGE_SIZE,
					fields = fields,
				).content

		else -> api.itemsApi.getItems(
					parentId = item.id,
					includeItemTypes = setOf(BaseItemKind.AUDIO),
					recursive = true,
					sortBy = setOf(ItemSortBy.SORT_NAME),
					startIndex = startIndex,
					limit = PLAYLIST_PAGE_SIZE,
					fields = fields,
				).content
	}
	return PlaylistPage(result.items, result.totalRecordCount, startIndex)
}

fun ItemListFragment.getAllPlaylistItems(item: BaseItemDto, callback: (List<BaseItemDto>?) -> Unit) {
	val api by inject<ApiClient>()
	viewLifecycleOwner.lifecycleScope.launch {
		val items = runCatching {
			val fields = if (item.type == BaseItemKind.PLAYLIST) ItemRepository.itemFields else ItemRepository.cardFields
			withContext(Dispatchers.IO) { collectPages { offset -> requestPlaylistPage(api, item, offset, fields) } }
		}.getOrNull()
		if (isActive) callback(items)
	}
}

private suspend fun collectPages(request: suspend (Int) -> PlaylistPage): List<BaseItemDto> {
	val items = mutableListOf<BaseItemDto>()
	while (true) {
		val page = request(items.size)
		items.addAll(page.items)
		if (!page.hasMore || page.items.isEmpty()) return items
	}
}

fun ItemListFragment.toggleFavorite(item: BaseItemDto, callback: (item: BaseItemDto) -> Unit) {
	val itemMutationRepository by inject<ItemMutationRepository>()

	viewLifecycleOwner.lifecycleScope.launch {
		val userData = itemMutationRepository.setFavorite(
			item = item.id,
			favorite = !(item.userData?.isFavorite ?: false)
		)
		callback(item.copy(userData = userData))
	}
}
