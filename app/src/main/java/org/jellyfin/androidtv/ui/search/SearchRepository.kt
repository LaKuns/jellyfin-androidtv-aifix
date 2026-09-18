package org.jellyfin.androidtv.ui.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber

interface SearchRepository {
	suspend fun search(
		searchTerm: String,
		itemTypes: Collection<BaseItemKind>,
	): Result<List<BaseItemDto>>
}

class SearchRepositoryImpl(
	private val apiClient: ApiClient
) : SearchRepository {
	companion object {
		private const val QUERY_LIMIT = 25
		private const val MAX_BATCH_QUERY_LIMIT = 100
	}

	override suspend fun search(
		searchTerm: String,
		itemTypes: Collection<BaseItemKind>,
	): Result<List<BaseItemDto>> = try {
		var request = GetItemsRequest(
			searchTerm = searchTerm,
			// Batched searches still reserve roughly one row of results per type.
			// The client splits the response back into the original UI groups.
			limit = (QUERY_LIMIT * itemTypes.size).coerceAtMost(MAX_BATCH_QUERY_LIMIT),
			imageTypeLimit = 1,
			includeItemTypes = itemTypes,
			fields = ItemRepository.cardFields,
			recursive = true,
			enableTotalRecordCount = false,
		)

		// Special case for video row
		if (itemTypes.size == 1 && itemTypes.first() == BaseItemKind.VIDEO) {
			request = request.copy(
				mediaTypes = setOf(MediaType.VIDEO),
				includeItemTypes = null,
				excludeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.TV_CHANNEL)
			)
		}

		val result = withContext(Dispatchers.IO) {
			apiClient.itemsApi.getItems(request).content
		}

		Result.success(result.items)
	} catch (e: ApiClientException) {
		Timber.e(e, "Failed to search for items")
		Result.failure(e)
	}
}
