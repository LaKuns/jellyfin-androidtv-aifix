package org.jellyfin.androidtv.ui.presentation

import android.content.Context
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.AsyncImageView
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * A deliberately small View-based card for devices with a constrained app
 * heap. It avoids a ComposeView, AndroidView, SubcomposeLayout and per-card
 * coroutine/state collection while keeping the primary artwork usable.
 */
@Suppress("MagicNumber")
class LowPerformanceCardPresenter(
	private val showInfo: Boolean = true,
	private val imageType: ImageType = ImageType.POSTER,
	private val staticHeight: Int = 150,
	private val uniformAspect: Boolean = false,
) : Presenter(), KoinComponent {
	private val api by inject<ApiClient>()

	private class CardViewHolder(
		root: LinearLayout,
		val image: AsyncImageView,
		val progress: ProgressBar,
		val title: TextView,
		val subtitle: TextView,
	) : ViewHolder(root)

	private class FocusAwareCardLayout(
		context: Context,
		private val onFocused: () -> Unit,
	) : LinearLayout(context) {
		override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
			super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
			if (gainFocus) onFocused()
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val context = parent.context
		val image = AsyncImageView(context).apply {
			adjustViewBounds = true
			scaleType = ImageView.ScaleType.CENTER_CROP
			crossFadeDuration = Duration.ZERO
			background = ContextCompat.getDrawable(context, R.drawable.shape_card_image_background)
			clipToOutline = true
		}
		val root = FocusAwareCardLayout(context, image::prioritize).apply {
			orientation = LinearLayout.VERTICAL
			isFocusable = true
			isFocusableInTouchMode = true
			background = ContextCompat.getDrawable(context, R.drawable.card_low_performance_focus)
			setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
			setOnLongClickListener {
				context.getActivity()?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)) ?: false
			}
		}
		root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
			max = 100
			progressDrawable = ContextCompat.getDrawable(context, R.drawable.progress_bar)
			visibility = View.INVISIBLE
		}
		root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 4)))

		val title = createMetadataTextView(context, 12f)
		val subtitle = createMetadataTextView(context, 10f).apply {
			alpha = 0.8f
		}
		root.addView(title)
		root.addView(subtitle)

		return CardViewHolder(root, image, progress, title, subtitle)
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		if (viewHolder !is CardViewHolder || item !is BaseRowItem) return

		val context = viewHolder.view.context
		val image = item.getImage(imageType)
		val aspectRatio = defaultAspectRatio(item, image?.aspectRatio?.takeIf { it > 0f })
		val heightDp = if (item.staticHeight) staticHeight else if (aspectRatio > 1f) 130 else 150
		val density = context.resources.displayMetrics.density
		val heightPx = (heightDp * density).roundToInt().coerceAtLeast(1)
		val widthPx = (heightPx * aspectRatio).roundToInt().coerceAtLeast(1)

		viewHolder.image.layoutParams = LinearLayout.LayoutParams(widthPx, heightPx)
		viewHolder.image.scaleType = if (item.baseRowType == BaseRowType.LiveTvChannel) {
			ImageView.ScaleType.FIT_CENTER
		} else {
			ImageView.ScaleType.CENTER_CROP
		}
		val showMetadata = showInfo || item.showCardInfoOverlay || item.baseRowType == BaseRowType.LiveTvProgram || item.baseRowType == BaseRowType.SeriesTimer
		val episodeNumber = (item as? BaseItemDtoBaseRowItem)
			?.takeIf { showInfo && imageType == ImageType.THUMB && it.staticHeight }
			?.getEpisodeNumberLabel()
		viewHolder.title.text = if (showMetadata) episodeNumber ?: item.getCardName(context).orEmpty() else ""
		viewHolder.title.visibility = if (showMetadata && viewHolder.title.text.isNotEmpty()) View.VISIBLE else View.GONE
		val played = item.baseItem?.userData?.played == true
		val subtitle = if (episodeNumber == null) item.getSubText(context).orEmpty() else ""
		viewHolder.subtitle.text = if (showMetadata) {
			if (played) listOf("✓", subtitle).filter { it.isNotEmpty() }.joinToString(" ") else subtitle
		} else ""
		viewHolder.subtitle.visibility = if (showMetadata && viewHolder.subtitle.text.isNotEmpty()) View.VISIBLE else View.GONE

		val playedPercentage = item.baseItem?.userData?.playedPercentage?.toInt()?.coerceIn(0, 100)
		viewHolder.progress.progress = playedPercentage ?: 0
		viewHolder.progress.visibility = if (!played && playedPercentage != null && playedPercentage > 0) View.VISIBLE else View.INVISIBLE

		if (item is GridButtonBaseRowItem && item.gridButton.imageRes != null) {
			viewHolder.image.clear()
			viewHolder.image.setImageResource(item.gridButton.imageRes)
			return
		}

		val placeholder = ContextCompat.getDrawable(context, placeholderResource(item))
		viewHolder.image.load(
			url = image?.getUrl(api, fillWidth = widthPx, fillHeight = heightPx),
			// BlurHash is intentionally omitted in this presenter. It is a CPU-side
			// bitmap decode that is not worth the cost on low-performance devices.
			blurHash = null,
			placeholder = placeholder,
			aspectRatio = aspectRatio.toDouble(),
		)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		if (viewHolder !is CardViewHolder) return
		viewHolder.image.clear()
		viewHolder.progress.progress = 0
		viewHolder.progress.visibility = View.INVISIBLE
	}

	private fun defaultAspectRatio(item: BaseRowItem, imageAspectRatio: Float?): Float {
		val primaryAspectRatio = imageAspectRatio
			?: item.baseItem?.primaryImageAspectRatio?.toFloat()?.takeIf { it > 0f }
			?: 7f / 9f
		val baseAspectRatio = when (imageType) {
			ImageType.BANNER -> 1000f / 185f
			ImageType.THUMB -> 16f / 9f
			else -> primaryAspectRatio
		}

		return when {
			item.baseRowType == BaseRowType.Person -> 7f / 9f
			item.baseRowType == BaseRowType.Chapter -> 16f / 9f
			item.baseRowType == BaseRowType.LiveTvChannel -> if (imageType == ImageType.POSTER) primaryAspectRatio else baseAspectRatio
			item.baseRowType == BaseRowType.LiveTvProgram -> if (imageType == ImageType.POSTER) primaryAspectRatio else baseAspectRatio
			item.baseRowType == BaseRowType.LiveTvRecording -> if (imageType == ImageType.POSTER) primaryAspectRatio else baseAspectRatio
			item.baseRowType == BaseRowType.SeriesTimer -> 16f / 9f
			item.baseRowType == BaseRowType.GridButton -> 7f / 9f
			item.baseItem?.type == BaseItemKind.USER_VIEW || item.baseItem?.type == BaseItemKind.COLLECTION_FOLDER -> 16f / 9f
			item.baseItem?.type == BaseItemKind.EPISODE -> {
				if (item is BaseItemDtoBaseRowItem && item.preferSeriesPoster) 2f / 3f else 16f / 9f
			}
			item.baseItem?.type == BaseItemKind.MOVIE || item.baseItem?.type == BaseItemKind.VIDEO -> if (imageType == ImageType.POSTER) 2f / 3f else baseAspectRatio
			item.baseItem?.type?.let(SQUARE_TYPES::contains) == true -> if (uniformAspect || baseAspectRatio < 0.8f) 1f else baseAspectRatio
			item.baseItem?.type == BaseItemKind.SERIES || item.baseItem?.type == BaseItemKind.SEASON -> if (imageType == ImageType.POSTER) 2f / 3f else baseAspectRatio
			else -> baseAspectRatio
		}
	}

	private fun placeholderResource(item: BaseRowItem): Int = when {
		item.baseRowType == BaseRowType.Person -> R.drawable.tile_port_person
		item.baseRowType == BaseRowType.Chapter -> R.drawable.tile_chapter
		item.baseRowType == BaseRowType.SeriesTimer -> R.drawable.tile_port_series_timer
		item.baseRowType == BaseRowType.GridButton -> R.drawable.tile_port_grid
		else -> when (item.baseItem?.type) {
			BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM -> R.drawable.tile_audio
			BaseItemKind.PERSON, BaseItemKind.MUSIC_ARTIST -> R.drawable.tile_port_person
			BaseItemKind.EPISODE, BaseItemKind.SERIES, BaseItemKind.SEASON -> R.drawable.tile_port_tv
			BaseItemKind.FOLDER, BaseItemKind.GENRE, BaseItemKind.MUSIC_GENRE,
			BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW, BaseItemKind.PHOTO_ALBUM,
			BaseItemKind.PLAYLIST -> R.drawable.tile_port_folder
			BaseItemKind.PHOTO -> R.drawable.tile_land_photo
			BaseItemKind.PROGRAM, BaseItemKind.TV_PROGRAM, BaseItemKind.LIVE_TV_PROGRAM,
			BaseItemKind.RECORDING, BaseItemKind.TV_CHANNEL, BaseItemKind.LIVE_TV_CHANNEL -> R.drawable.tile_land_tv
			else -> R.drawable.tile_port_video
		}
	}

	private fun createMetadataTextView(context: android.content.Context, textSize: Float) = TextView(context).apply {
		layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		gravity = Gravity.CENTER
		setTextColor(ContextCompat.getColor(context, R.color.button_default_normal_text))
		this.textSize = textSize
		maxLines = 1
		ellipsize = TextUtils.TruncateAt.END
		setPadding(dp(context, 4), dp(context, 1), dp(context, 4), dp(context, 1))
	}

	private fun dp(context: android.content.Context, value: Int): Int =
		(value * context.resources.displayMetrics.density).roundToInt()

	private companion object {
		val SQUARE_TYPES = setOf(
			BaseItemKind.AUDIO,
			BaseItemKind.MUSIC_ALBUM,
			BaseItemKind.MUSIC_ARTIST,
			BaseItemKind.PERSON,
		)
	}
}
