package org.jellyfin.androidtv.util

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRowView
import org.jellyfin.androidtv.util.sdk.getSeasonEpisodeName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.util.Locale

object InfoLayoutHelper {
	@Suppress("MagicNumber")
	private class LowPerformanceInfoRowView(context: Context) : AppCompatTextView(context) {
		init {
			val textColor = Utils.getThemeColor(context, android.R.attr.textColorPrimary)
			setTextColor(if (textColor == Color.TRANSPARENT) Color.WHITE else textColor)
			textSize = 16f
			maxLines = 1
			ellipsize = TextUtils.TruncateAt.END
			isFocusable = false
		}

		fun bind(item: BaseItemDto?, mediaSource: MediaSourceInfo?, includeRuntime: Boolean) {
			if (item == null) {
				text = null
				return
			}

			val source = mediaSource ?: item.mediaSources?.firstOrNull()
			val video = source?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
			val audio = source?.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO }
			val videoWidth = video?.width
			val videoHeight = video?.height
			val details = buildList {
				item.getSeasonEpisodeName(context).takeIf(String::isNotBlank)?.let(::add)
				item.productionYear?.toString()?.let(::add)
				if (includeRuntime) item.runTimeTicks?.let { ticks ->
					add(TimeUtils.formatMillis(ticks / TICKS_PER_MILLISECOND))
				}
				item.officialRating?.takeIf(String::isNotBlank)?.let(::add)
				item.communityRating?.let { rating ->
					add("★ ${String.format(Locale.getDefault(), "%.1f", rating)}")
				}
				if (videoWidth != null && videoHeight != null && video != null) {
					add(resolutionName(videoWidth, videoHeight, video.isInterlaced))
				}
				video?.videoRangeType?.name
					?.takeUnless { it == "SDR" }
					?.replace('_', ' ')
					?.let(::add)
				audio?.codec?.uppercase(Locale.ROOT)?.let(::add)
				audio?.channelLayout?.uppercase(Locale.ROOT)?.let(::add)
				if (source?.mediaStreams?.any { it.type == MediaStreamType.SUBTITLE } == true) {
					add(context.getString(R.string.indicator_subtitles))
				}
			}.distinct()

			text = details.joinToString("  •  ")
		}
	}

	private const val TICKS_PER_MILLISECOND = 10_000L

	@Suppress("MagicNumber")
	private fun resolutionName(width: Int, height: Int, interlaced: Boolean): String {
		val suffix = if (interlaced) "i" else "p"
		return when {
			width >= 7600 || height >= 4300 -> "8K"
			width >= 3800 || height >= 2000 -> "4K"
			width >= 2500 || height >= 1400 -> "1440$suffix"
			width >= 1800 || height >= 1000 -> "1080$suffix"
			width >= 1200 || height >= 700 -> "720$suffix"
			width >= 600 || height >= 400 -> "480$suffix"
			else -> "SD"
		}
	}

	@JvmStatic
	fun addInfoRow(
		context: Context,
		item: BaseItemDto?,
		mediaSource: MediaSourceInfo?,
		layout: LinearLayout,
		includeRuntime: Boolean
	) {
		// Find existing BaseItemInfoRowView or create a new one
		var baseItemInfoRowView: BaseItemInfoRowView? = null

		for (i in 0 until layout.childCount) {
			val child = layout.getChildAt(i)

			if (child is BaseItemInfoRowView) {
				baseItemInfoRowView = child
				break
			}
		}

		if (baseItemInfoRowView == null) {
			baseItemInfoRowView = BaseItemInfoRowView(context)
			layout.addView(baseItemInfoRowView)
		}

		// Update item info
		baseItemInfoRowView.item = item
		baseItemInfoRowView.mediaSource = mediaSource ?: item?.mediaSources?.firstOrNull()
		baseItemInfoRowView.includeRuntime = includeRuntime
	}

	@JvmStatic
	fun addInfoRow(
		context: Context,
		item: BaseItemDto?,
		layout: LinearLayout,
		includeRuntime: Boolean
	) = addInfoRow(context, item, null, layout, includeRuntime)

	/** Lightweight, allocation-stable info row for constrained devices. */
	@JvmStatic
	fun addLowPerformanceInfoRow(
		context: Context,
		item: BaseItemDto?,
		layout: LinearLayout,
		includeRuntime: Boolean,
	) {
		var existing: LowPerformanceInfoRowView? = null
		for (index in 0 until layout.childCount) {
			val child = layout.getChildAt(index)
			if (child is LowPerformanceInfoRowView) {
				existing = child
				break
			}
		}
		val view = existing ?: LowPerformanceInfoRowView(context).also {
			// This replacement happens once per screen, not on every focus move.
			layout.removeAllViews()
			layout.addView(it)
		}
		view.bind(item, item?.mediaSources?.firstOrNull(), includeRuntime)
	}
}
