package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest

class CollectionFragment : EnhancedBrowseFragment() {
	override fun setupQueries(rowLoader: RowLoader) {
		val movies = GetItemsRequest(
			fields = ItemRepository.cardFields,
			parentId = mFolder.id,
			includeItemTypes = setOf(BaseItemKind.MOVIE),
		)
		mRows.add(BrowseRowDef(getString(R.string.lbl_movies), movies, 32))

		val series = GetItemsRequest(
			fields = ItemRepository.cardFields,
			parentId = mFolder.id,
			includeItemTypes = setOf(BaseItemKind.SERIES),
		)
		mRows.add(BrowseRowDef(getString(R.string.lbl_tv_series), series, 32))

		val others = GetItemsRequest(
			fields = ItemRepository.cardFields,
			parentId = mFolder.id,
			excludeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
		)
		mRows.add(BrowseRowDef(getString(R.string.lbl_other), others, 32))

		rowLoader.loadRows(mRows)
	}
}
