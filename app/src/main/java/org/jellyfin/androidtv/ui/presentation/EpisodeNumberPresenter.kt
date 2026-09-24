package org.jellyfin.androidtv.ui.presentation

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemDto

/** Compact, remote-focusable episode numbers for a Leanback row. */
@Suppress("MagicNumber")
class EpisodeNumberPresenter(
    var selectedId: java.util.UUID? = null,
    private val seasons: Boolean = false,
) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = ViewHolder(TextView(parent.context).apply {
        val density = resources.displayMetrics.density
        layoutParams = ViewGroup.MarginLayoutParams(
            ((if (seasons) 116 else 72) * density).toInt(),
            (58 * density).toInt(),
        ).apply { marginEnd = (8 * density).toInt() }
        gravity = Gravity.CENTER
        textSize = 22f
        setTextColor(resources.getColorStateList(R.drawable.button_default_text, context.theme))
        setBackgroundResource(R.drawable.episode_number_back)
        isFocusable = true
    })

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val episode = item as? BaseItemDto ?: return
        (viewHolder.view as TextView).apply {
            val number = episode.indexNumber?.toString()?.padStart(2, '0') ?: episode.name.orEmpty()
            val end = episode.indexNumberEnd
            val label = when {
                seasons -> episode.name.orEmpty()
                end != null && end != episode.indexNumber -> "$number–${end.toString().padStart(2, '0')}"
                else -> number
            }
            isActivated = episode.id == selectedId
            text = if (!seasons && isActivated) "▶$label" else label
            setTypeface(null, if (isActivated) Typeface.BOLD else Typeface.NORMAL)
            alpha = if (!seasons && !isActivated && episode.userData?.played == true) 0.72f else 1f
            contentDescription = if (seasons) episode.name.orEmpty() else episode.indexNumber?.let { index ->
                "${episode.name.orEmpty()}, ${context.getString(R.string.lbl_episode_number, index)}"
            } ?: episode.name.orEmpty()
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
