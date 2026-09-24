package org.jellyfin.androidtv.ui.itemdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetSeasonsRequest
import timber.log.Timber
import java.util.UUID

data class EpisodeRange(val startIndex: Int, val endIndex: Int) {
    val label: String get() = "${(startIndex + 1).toString().padStart(2, '0')}–${endIndex.toString().padStart(2, '0')}"
}

data class EpisodePage(
    val items: List<BaseItemDto>,
    val totalCount: Int,
    val startIndex: Int,
    val pageSize: Int,
) {
    val ranges: List<EpisodeRange> get() = (0 until totalCount step pageSize).map { start ->
        EpisodeRange(start, minOf(start + pageSize, totalCount))
    }
}

/** Lists use small, lightweight pages; playback still gets full metadata for its queue. */
class EpisodeCatalog(private val api: ApiClient, lowResource: Boolean = false) {
    val pageSize = if (lowResource) LOW_RESOURCE_PAGE_SIZE else DEFAULT_PAGE_SIZE

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> = withContext(Dispatchers.IO) {
        api.tvShowsApi.getSeasons(GetSeasonsRequest(seriesId = seriesId, fields = ItemRepository.cardFields)).content.items
            .sortedWith(compareBy<BaseItemDto> { it.indexNumber == 0 }.thenBy { it.indexNumber ?: Int.MAX_VALUE })
    }

    suspend fun episodes(seriesId: UUID, seasonId: UUID, startIndex: Int = 0): EpisodePage = withContext(Dispatchers.IO) {
        val offset = startIndex.coerceAtLeast(0)
        val page = try {
            api.tvShowsApi.getEpisodes(
                seriesId = seriesId,
                seasonId = seasonId,
                isMissing = false,
                startIndex = offset,
                limit = pageSize,
                fields = ItemRepository.cardFields,
            ).content
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Could not list episodes from the series endpoint; trying the season directory")
            null
        }
        val result = if (page == null || page.items.isEmpty()) {
            api.itemsApi.getItems(
                parentId = seasonId,
                includeItemTypes = listOf(BaseItemKind.EPISODE),
                isMissing = false,
                startIndex = offset,
                limit = pageSize,
                fields = ItemRepository.cardFields,
            ).content
        } else page
        EpisodePage(result.items, result.totalRecordCount, offset, pageSize)
    }

    suspend fun playbackItems(episode: BaseItemDto, queueFollowing: Boolean): List<BaseItemDto> = withContext(Dispatchers.IO) {
        val seriesId = episode.seriesId
        if (!queueFollowing || seriesId == null) return@withContext listOf(episode)
        try {
            val response = api.tvShowsApi.getEpisodes(
                seriesId = seriesId,
                startItemId = episode.id,
                isMissing = false,
                limit = PLAYBACK_QUEUE_LIMIT,
                fields = ItemRepository.itemFields,
            ).content.items
            response.takeIf { it.firstOrNull()?.id == episode.id } ?: listOf(episode)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Could not queue following episodes; playing the selected episode only")
            listOf(episode)
        }
    }

    companion object {
        private const val LOW_RESOURCE_PAGE_SIZE = 24
        private const val DEFAULT_PAGE_SIZE = 48
        private const val PLAYBACK_QUEUE_LIMIT = 150
    }
}
