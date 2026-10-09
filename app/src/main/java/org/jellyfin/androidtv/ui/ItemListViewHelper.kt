package org.jellyfin.androidtv.ui

import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.koin.java.KoinJavaComponent

/**
 * Re-fetch the currently listed rows and patch them in place.
 *
 * Rows are recycled now, so the refreshed values are written back by adapter position
 * instead of by walking the child views.
 */
fun ItemListView.refresh() {
	val api by KoinJavaComponent.inject<ApiClient>(ApiClient::class.java)

	findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
		val ids = getItemIds()
		if (ids.isEmpty()) return@launch

		val updated = withContext(Dispatchers.IO) {
			ids.chunked(60).flatMap { batch ->
				api.itemsApi.getItems(ids = batch, fields = ItemRepository.cardFields).content.items
			}.associateBy { it.id }
		}
		val current = getItems()
		for (index in current.indices) {
			val replacement = updated[current[index].id] ?: continue
			updateItem(index, replacement)
		}
	}
}
