package org.jellyfin.androidtv.ui.presentation

import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R

/** Keeps the episode row visible while its page is loading or unavailable. */
class EpisodeMessagePresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = ViewHolder(TextView(parent.context).apply {
        val density = resources.displayMetrics.density
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (58 * density).toInt())
        minWidth = (240 * density).toInt()
        setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        gravity = Gravity.CENTER
        textSize = 18f
        setTextColor(resources.getColorStateList(R.drawable.button_default_text, context.theme))
        setBackgroundResource(R.drawable.episode_number_back)
        isFocusable = true
    })

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        (viewHolder.view as TextView).text = item as? String ?: ""
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
