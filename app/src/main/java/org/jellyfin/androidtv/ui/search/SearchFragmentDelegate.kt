package org.jellyfin.androidtv.ui.search

import android.content.Context
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.PerformanceProfile
import org.jellyfin.sdk.model.api.BaseItemDto

class SearchFragmentDelegate(
	private val context: Context,
	private val backgroundService: BackgroundService,
	private val itemLauncher: ItemLauncher,
) {
	private val rowsByLabel = mutableMapOf<Int, ListRow>()
	private val itemsByLabel = mutableMapOf<Int, List<BaseItemDto>>()

	val rowsAdapter = MutableObjectAdapter<Row>(CustomListRowPresenter(
		focusZoomFactor = if (PerformanceProfile.isLowPerformanceDevice(context)) {
			FocusHighlight.ZOOM_FACTOR_NONE
		} else {
			FocusHighlight.ZOOM_FACTOR_MEDIUM
		}
	))

	fun showResults(searchResultGroups: Collection<SearchResultGroup>) {
		val labels = searchResultGroups.mapTo(mutableSetOf()) { it.labelRes }
		rowsByLabel.keys.retainAll(labels)
		itemsByLabel.keys.retainAll(labels)

		val desiredRows = buildList<Row> {
			for ((labelRes, baseItems) in searchResultGroups) {
				val items = baseItems.toList()
				if (items.isEmpty()) {
					rowsByLabel.remove(labelRes)
					itemsByLabel.remove(labelRes)
					continue
				}

				val existingRow = rowsByLabel[labelRes]
				val row = if (existingRow != null && itemsByLabel[labelRes] == items) {
					existingRow
				} else {
					val adapter = ItemRowAdapter(
						context,
						items,
						CardPresenter(),
						null,
						QueryType.Search,
					)
					val newRow = ListRow(HeaderItem(labelRes.toLong(), context.getString(labelRes)), adapter)
					adapter.setRow(newRow)
					adapter.Retrieve()
					itemsByLabel[labelRes] = items
					rowsByLabel[labelRes] = newRow
					newRow
				}
				add(row)
			}
		}

		// Search batches finish independently. Reconcile only rows that changed
		// instead of clearing every row and throwing away the current focus.
		rowsAdapter.replaceAll(
			desiredRows,
			areItemsTheSame = { old, new -> old.headerItem?.id == new.headerItem?.id },
			areContentsTheSame = { old, new -> old === new },
		)
	}

	val onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
		if (item !is BaseRowItem) return@OnItemViewClickedListener
		row as ListRow
		val adapter = row.adapter as ItemRowAdapter
		itemLauncher.launch(item as BaseRowItem?, adapter, context)
	}

	val onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
		val baseItem = item?.let { (item as BaseRowItem).baseItem }
		if (baseItem != null) {
			backgroundService.setSelectionBackground(baseItem)
		} else {
			backgroundService.clearBackgrounds()
		}
	}
}
