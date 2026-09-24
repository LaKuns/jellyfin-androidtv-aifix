package org.jellyfin.androidtv.ui.playback

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.itemdetail.EpisodeCatalog
import org.jellyfin.androidtv.ui.itemdetail.EpisodeRange
import org.jellyfin.androidtv.util.PerformanceProfile
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber

/** A paged, remote-friendly picker shared by both internal video players. */
@Suppress("MagicNumber")
object EpisodePickerDialog {
    @Suppress("LongMethod", "ComplexMethod", "NestedBlockDepth")
    fun show(
        context: Context,
        owner: LifecycleOwner,
        api: ApiClient,
        current: BaseItemDto,
        onEpisodeSelected: suspend (BaseItemDto) -> Boolean,
    ) {
        val seriesId = current.seriesId ?: return
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val metrics = context.resources.displayMetrics
        val screenWidth = (metrics.widthPixels / density).toInt()
        val screenHeight = (metrics.heightPixels / density).toInt()
        val dialogWidth = minOf(620, (screenWidth - 48).coerceAtLeast(240))
        val gridHeight = minOf(360, (screenHeight * 0.42f).toInt().coerceAtLeast(180))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val seasonBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(seasonBar)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rangeAdapter = RangeAdapter(context) { /* set once the active season is known */ }
        val rangeList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = rangeAdapter
            itemAnimator = null
            visibility = View.GONE
        }
        root.addView(rangeList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val status = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(34)
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val retryButton = Button(context).apply {
            text = context.getString(R.string.lbl_retry_episode_selection)
            visibility = View.GONE
        }
        root.addView(retryButton)

        val episodeAdapter = EpisodeAdapter(context, current.id) { /* set below */ }
        val grid = RecyclerView(context).apply {
            val gridLayoutManager = GridLayoutManager(context, 2)
            layoutManager = gridLayoutManager
            adapter = episodeAdapter
            itemAnimator = null
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                // Dialog themes add their own insets; only the measured grid width is reliable.
                view.post {
                    val availableWidth = view.width - view.paddingLeft - view.paddingRight
                    val columns = (availableWidth / dp(88)).coerceIn(2, 6)
                    if (gridLayoutManager.spanCount != columns) gridLayoutManager.spanCount = columns
                }
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(gridHeight)))

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.lbl_select_episode)
            .setView(root)
            .setNegativeButton(R.string.lbl_cancel, null)
            .create()
        val catalog = EpisodeCatalog(api, PerformanceProfile.isLowPerformanceDevice(context))
        var loadJob: Job? = null
        var seasonsJob: Job? = null
        var selectionJob: Job? = null
        var activeSeason: BaseItemDto? = null
        var requestedStart = -1
        var retryAction: (() -> Unit)? = null

        fun showStatus(message: String, retry: (() -> Unit)? = null) {
            status.text = message
            retryAction = retry
            retryButton.visibility = if (retry == null) View.GONE else View.VISIBLE
        }
        fun showEpisodes() = showStatus("")

        fun playSelected(episode: BaseItemDto) {
            if (selectionJob?.isActive == true) return
            showStatus(context.getString(R.string.lbl_loading_elipses))
            selectionJob = owner.lifecycleScope.launch {
                try {
                    if (onEpisodeSelected(episode)) dialog.dismiss()
                    else {
                        showStatus(context.getString(R.string.msg_episode_selection_play_error)) { playSelected(episode) }
                        retryButton.requestFocus()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(error, "Failed to play selected episode")
                    if (dialog.isShowing) {
                        showStatus(context.getString(R.string.msg_episode_selection_play_error)) { playSelected(episode) }
                        retryButton.requestFocus()
                    }
                }
            }
        }
        episodeAdapter.onClick = ::playSelected

        fun loadPage(season: BaseItemDto, startIndex: Int) {
            loadJob?.cancel()
            activeSeason = season
            requestedStart = startIndex
            showStatus(context.getString(R.string.lbl_loading_elipses)) { loadPage(season, startIndex) }
            retryButton.visibility = View.GONE
            if (rangeAdapter.seasonId != season.id) {
                rangeAdapter.update(season.id, emptyList(), -1)
                rangeList.visibility = View.GONE
            }
            episodeAdapter.update(emptyList())
            for (index in 0 until seasonBar.childCount) {
                val button = seasonBar.getChildAt(index) as Button
                button.setTypeface(null, if (button.tag == season.id) Typeface.BOLD else Typeface.NORMAL)
            }
            loadJob = owner.lifecycleScope.launch {
                try {
                    var page = catalog.episodes(seriesId, season.id, startIndex)
                    if (page.items.isEmpty() && page.totalCount > 0 && startIndex > 0) {
                        page = catalog.episodes(seriesId, season.id, 0)
                    }
                    if (!dialog.isShowing || activeSeason?.id != season.id || requestedStart != startIndex) return@launch
                    if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                    rangeAdapter.update(season.id, page.ranges, page.startIndex)
                    rangeList.visibility = if (page.ranges.size > 1) View.VISIBLE else View.GONE
                    episodeAdapter.update(page.items)
                    if (page.items.isEmpty()) {
                        showStatus(context.getString(R.string.lbl_no_items))
                    } else {
                        showEpisodes()
                        val focusIndex = page.items.indexOfFirst { it.id == current.id }.takeIf { it >= 0 } ?: 0
                        grid.scrollToPosition(focusIndex)
                        grid.post {
                            grid.findViewHolderForAdapterPosition(focusIndex)?.itemView?.requestFocus()
                                ?: grid.requestFocus()
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(error, "Failed to load episode page")
                    if (dialog.isShowing) {
                        showStatus(context.getString(R.string.msg_episode_selection_load_error)) { loadPage(season, startIndex) }
                        retryButton.requestFocus()
                    }
                }
            }
        }
        rangeAdapter.onClick = { range -> activeSeason?.let { loadPage(it, range.startIndex) } }
        retryButton.setOnClickListener { retryAction?.invoke() }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        owner.lifecycle.addObserver(lifecycleObserver)
        dialog.setOnDismissListener {
            loadJob?.cancel()
            seasonsJob?.cancel()
            selectionJob?.cancel()
            owner.lifecycle.removeObserver(lifecycleObserver)
        }
        dialog.show()
        dialog.window?.setLayout(dp(dialogWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
        fun loadSeasons() {
            seasonsJob?.cancel()
            seasonBar.removeAllViews()
            showStatus(context.getString(R.string.lbl_loading_elipses))
            seasonsJob = owner.lifecycleScope.launch {
                try {
                    val seasons = catalog.seasons(seriesId)
                    if (!dialog.isShowing || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                    seasons.forEach { season ->
                        seasonBar.addView(Button(context).apply {
                            text = season.name.orEmpty()
                            tag = season.id
                            setOnClickListener { loadPage(season, 0) }
                        })
                    }
                    val season = seasons.firstOrNull { it.id == current.seasonId } ?: seasons.firstOrNull()
                    if (season == null) showStatus(context.getString(R.string.lbl_no_items))
                    else {
                        val number = (current.indexNumber ?: 1).coerceAtLeast(1)
                        val start = ((number - 1) / catalog.pageSize) * catalog.pageSize
                        loadPage(season, if (season.id == current.seasonId) start else 0)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(error, "Failed to load seasons for picker")
                    if (dialog.isShowing) {
                        showStatus(context.getString(R.string.msg_episode_selection_load_error)) { loadSeasons() }
                        retryButton.requestFocus()
                    }
                }
            }
        }
        loadSeasons()
    }

    private class ButtonHolder(val button: Button) : RecyclerView.ViewHolder(button)

    private class RangeAdapter(
        private val context: Context,
        var onClick: (EpisodeRange) -> Unit,
    ) : RecyclerView.Adapter<ButtonHolder>() {
        var seasonId: java.util.UUID? = null
            private set
        private var ranges: List<EpisodeRange> = emptyList()
        private var selectedStart = -1

        fun update(seasonId: java.util.UUID, ranges: List<EpisodeRange>, selectedStart: Int) {
            this.seasonId = seasonId
            this.ranges = ranges
            this.selectedStart = selectedStart
            notifyDataSetChanged()
        }

        override fun getItemCount() = ranges.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ButtonHolder {
            val density = context.resources.displayMetrics.density
            return ButtonHolder(Button(context).apply {
                layoutParams = RecyclerView.LayoutParams((112 * density).toInt(), (48 * density).toInt())
                textSize = 16f
            })
        }

        override fun onBindViewHolder(holder: ButtonHolder, position: Int) {
            val range = ranges[position]
            holder.button.apply {
                text = range.label
                isActivated = range.startIndex == selectedStart
                setTypeface(null, if (isActivated) Typeface.BOLD else Typeface.NORMAL)
                contentDescription = context.getString(R.string.lbl_episode_range_selection, range.startIndex + 1, range.endIndex)
                setOnClickListener { onClick(range) }
            }
        }
    }

    private class EpisodeAdapter(
        private val context: Context,
        private val currentId: java.util.UUID,
        var onClick: (BaseItemDto) -> Unit,
    ) : RecyclerView.Adapter<ButtonHolder>() {
        private var episodes: List<BaseItemDto> = emptyList()

        fun update(episodes: List<BaseItemDto>) {
            this.episodes = episodes
            notifyDataSetChanged()
        }

        override fun getItemCount() = episodes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ButtonHolder {
            val density = context.resources.displayMetrics.density
            return ButtonHolder(Button(context).apply {
                val gap = (2 * density).toInt()
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (54 * density).toInt()).apply {
                    setMargins(gap, gap, gap, gap)
                }
                textSize = 18f
                minWidth = 0
                setPadding(0, 0, 0, 0)
                setBackgroundResource(R.drawable.episode_number_back)
                backgroundTintList = null
                setTextColor(resources.getColorStateList(R.drawable.button_default_text, context.theme))
            })
        }

        override fun onBindViewHolder(holder: ButtonHolder, position: Int) {
            val episode = episodes[position]
            val number = episode.indexNumber?.toString()?.padStart(2, '0') ?: episode.name.orEmpty()
            val end = episode.indexNumberEnd
            val label = if (end != null && end != episode.indexNumber) "$number–${end.toString().padStart(2, '0')}" else number
            holder.button.apply {
                isActivated = episode.id == currentId
                text = if (isActivated) "▶$label" else label
                setTypeface(null, if (isActivated) Typeface.BOLD else Typeface.NORMAL)
                contentDescription = episode.name?.takeIf { it.isNotBlank() } ?: label
                setOnClickListener { onClick(episode) }
            }
        }
    }
}
