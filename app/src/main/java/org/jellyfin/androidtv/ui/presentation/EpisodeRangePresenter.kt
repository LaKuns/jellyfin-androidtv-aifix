package org.jellyfin.androidtv.ui.presentation

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.itemdetail.EpisodeRange

/** Remote-focusable page ranges; Leanback only creates views for visible ranges. */
@Suppress("MagicNumber")
class EpisodeRangePresenter(var selectedStart: Int) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = ViewHolder(TextView(parent.context).apply {
        val density = resources.displayMetrics.density
        layoutParams = ViewGroup.MarginLayoutParams((112 * density).toInt(), (48 * density).toInt()).apply {
            marginEnd = (8 * density).toInt()
        }
        gravity = Gravity.CENTER
        textSize = 18f
        setTextColor(resources.getColorStateList(R.drawable.button_default_text, context.theme))
        setBackgroundResource(R.drawable.episode_number_back)
        isFocusable = true
    })

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val range = item as? EpisodeRange ?: return
        (viewHolder.view as TextView).apply {
            text = range.label
            contentDescription = context.getString(R.string.lbl_episode_range_selection, range.startIndex + 1, range.endIndex)
            isActivated = range.startIndex == selectedStart
            setTypeface(null, if (isActivated) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
