package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter

interface HomeFragmentRow {
	fun addToRowsAdapter(context: Context, cardPresenter: Presenter, rowsAdapter: MutableObjectAdapter<Row>)
}
