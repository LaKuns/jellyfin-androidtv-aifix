package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType

interface UserViewsRepository {
	val views: Flow<Collection<BaseItemDto>>

	fun isSupported(collectionType: CollectionType?): Boolean
	fun allowViewSelection(collectionType: CollectionType?): Boolean
	fun allowGridView(collectionType: CollectionType?): Boolean
}

class UserViewsRepositoryImpl(
	private val api: ApiClient,
) : UserViewsRepository {
	// The library list barely changes, but it is needed by the home rows, by the browse grid
	// and by several settings screens. As a cold flow every one of those collectors issued
	// its own request. Sharing concurrent collectors collapses duplicate calls;
	// the replay is cleared as soon as no one is collecting to avoid cross-user data.
	//
	// shareIn rather than stateIn on purpose: a StateFlow needs an initial value, and an
	// empty list would be handed straight to callers using first(), which would silently
	// build the home rows with zero libraries. shareIn has no value until the upstream
	// actually emits, so first() still waits for real data while later collectors are
	// served from the replay cache.
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	override val views = flow {
		val views by api.userViewsApi.getUserViews()
		val filteredViews = views.items
			.filter { isSupported(it.collectionType) }
		emit(filteredViews)
	}
		.flowOn(Dispatchers.IO)
		.shareIn(
			scope = scope,
			started = SharingStarted.WhileSubscribed(
				stopTimeoutMillis = 0L,
				replayExpirationMillis = 0L,
			),
			replay = 1,
		)

	override fun isSupported(collectionType: CollectionType?) = collectionType !in unsupportedCollectionTypes
	override fun allowViewSelection(collectionType: CollectionType?) = collectionType !in disallowViewSelectionCollectionTypes
	override fun allowGridView(collectionType: CollectionType?) = collectionType !in disallowGridViewCollectionTypes

	private companion object {
		private val unsupportedCollectionTypes = arrayOf(
			CollectionType.BOOKS,
			CollectionType.FOLDERS
		)

		private val disallowViewSelectionCollectionTypes = arrayOf(
			CollectionType.LIVETV,
			CollectionType.MUSIC,
			CollectionType.PHOTOS,
		)

		private val disallowGridViewCollectionTypes = arrayOf(
			CollectionType.LIVETV,
			CollectionType.MUSIC
		)
	}
}
