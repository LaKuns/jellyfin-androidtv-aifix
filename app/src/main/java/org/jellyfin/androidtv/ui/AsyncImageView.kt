package org.jellyfin.androidtv.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.doOnAttach
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.asImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.BlurHashDecoder
import org.jellyfin.androidtv.util.PerformanceProfile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.round
import kotlin.time.Duration.Companion.milliseconds

/**
 * An extension to the [ImageView] that makes it easy to load images from the network.
 * The [load] function takes a url, blurhash and placeholder to asynchronously load the image
 * using the lifecycle of the current fragment or activity.
 */
class AsyncImageView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr), KoinComponent {
	companion object {
		private const val DEFAULT_CROSSFADE_DURATION_MS = 100
		private val lowPerformanceBackgroundImageSemaphore = Semaphore(1)
		private val lowPerformanceFocusedImageSemaphore = Semaphore(1)
	}

	private val lifeCycleOwner get() = findViewTreeLifecycleOwner()
	private val imageLoader by inject<ImageLoader>()
	private var loadJob: Job? = null
	private var pendingRequest: PendingImageRequest? = null
	private var requestStarted = false
	private val imageStyle = context.obtainStyledAttributes(attrs, R.styleable.AsyncImageView, defStyleAttr, 0).let { attributes ->
		try {
			ImageStyle(
				crossFadeDurationMs = attributes.getInt(
					R.styleable.AsyncImageView_crossfadeDuration,
					DEFAULT_CROSSFADE_DURATION_MS,
				),
				circleCrop = attributes.getBoolean(R.styleable.AsyncImageView_circleCrop, false),
			)
		} finally {
			attributes.recycle()
		}
	}

	private data class ImageStyle(
		val crossFadeDurationMs: Int,
		val circleCrop: Boolean,
	)

	private data class ImageRequestKey(
		val url: String?,
		val blurHash: String?,
		val circleCrop: Boolean,
		val aspectRatio: Double,
		val blurHashResolution: Int,
	)

	private data class PendingImageRequest(
		val key: ImageRequestKey,
		val url: String?,
		val blurHash: String?,
		val placeholder: Drawable?,
		val aspectRatio: Double,
		val blurHashResolution: Int,
	)

	/**
	 * The duration of the crossfade when changing switching the images of the url, blurhash and
	 * placeholder.
	 */
	@Suppress("MagicNumber")
	var crossFadeDuration = imageStyle.crossFadeDurationMs.milliseconds

	/**
	 * Shape the image to a circle and remove all corners.
	 */
	var circleCrop = imageStyle.circleCrop

	/**
	 * Load an image from the network using [url]. When the [url] is null or returns a bad response
	 * the [placeholder] is shown. A [blurHash] is shown while loading the image. An aspect ratio is
	 * required when using a BlurHash or the sizing will be incorrect.
	 */
	fun load(
		url: String? = null,
		blurHash: String? = null,
		placeholder: Drawable? = null,
		aspectRatio: Double = 1.0,
		blurHashResolution: Int = 32,
	) {
		val requestKey = ImageRequestKey(url, blurHash, circleCrop, aspectRatio, blurHashResolution)
		if (requestKey == pendingRequest?.key && loadJob?.isActive == true) return
		val request = PendingImageRequest(requestKey, url, blurHash, placeholder, aspectRatio, blurHashResolution)
		pendingRequest = request
		schedule(request, prioritized = false)
	}

	/** Promote a queued image when its card receives focus. */
	fun prioritize() {
		if (!PerformanceProfile.isLowPerformanceDevice(context) || requestStarted) return
		pendingRequest?.let { schedule(it, prioritized = true) }
	}

	private fun schedule(pending: PendingImageRequest, prioritized: Boolean) {
		doOnAttach {
			// RecyclerView may bind the same view several times before it is
			// attached. Only the most recent request should reach Coil.
			if (pending !== pendingRequest) return@doOnAttach
			// Cancel the previous load if still running
			loadJob?.cancel()
			requestStarted = false

			loadJob = lifeCycleOwner?.lifecycleScope?.launch {
				var placeholderOrBlurHash = pending.placeholder

				// Only show blurhash if an image is going to be loaded from the network
				val isLowPerformanceDevice = PerformanceProfile.isLowPerformanceDevice(context)
				if (pending.url != null && pending.blurHash != null && !isLowPerformanceDevice && pending.aspectRatio > 0) withContext(Dispatchers.Default) {
					val blurHashBitmap = BlurHashDecoder.decode(
						pending.blurHash,
						if (pending.aspectRatio > 1) round(pending.blurHashResolution * pending.aspectRatio).toInt() else pending.blurHashResolution,
						if (pending.aspectRatio >= 1) pending.blurHashResolution else round(pending.blurHashResolution / pending.aspectRatio).toInt(),
					)
					if (blurHashBitmap != null) placeholderOrBlurHash = blurHashBitmap.toDrawable(resources)
				}

				// Start loading image or placeholder
				val imageRequest = if (pending.url == null) {
					ImageRequest.Builder(context).apply {
						target(this@AsyncImageView)
						data(pending.placeholder)
						if (circleCrop) transformations(CircleCropTransformation())
					}.build()
				} else {
					ImageRequest.Builder(context).apply {
						val crossFadeDurationMs = crossFadeDuration.inWholeMilliseconds.toInt()
						if (crossFadeDurationMs > 0) crossfade(crossFadeDurationMs)
						else crossfade(false)

						target(this@AsyncImageView)
						data(pending.url)
						placeholder(placeholderOrBlurHash?.asImage())
						if (circleCrop) transformations(CircleCropTransformation())
						error(pending.placeholder?.asImage())
					}.build()
				}

				if (isLowPerformanceDevice) {
					val semaphore = if (prioritized) {
						lowPerformanceFocusedImageSemaphore
					} else {
						lowPerformanceBackgroundImageSemaphore
					}
					semaphore.withPermit {
						if (pending !== pendingRequest) return@withPermit
						requestStarted = true
						imageLoader.enqueue(imageRequest).job.await()
					}
				} else {
					requestStarted = true
					imageLoader.enqueue(imageRequest).job.await()
				}
			}
		}
	}

	/** Cancel any pending request and release the current drawable. */
	fun clear() {
		loadJob?.cancel()
		loadJob = null
		pendingRequest = null
		requestStarted = false
		setImageDrawable(null)
	}
}
