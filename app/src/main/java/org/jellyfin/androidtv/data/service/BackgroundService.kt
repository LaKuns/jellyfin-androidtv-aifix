package org.jellyfin.androidtv.data.service

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.BackdropBehavior
import org.jellyfin.androidtv.util.PerformanceProfile
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackgroundService(
	private val context: Context,
	private val jellyfin: Jellyfin,
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val imageLoader: ImageLoader,
) {
	companion object {
		private const val MAX_BACKGROUND_WIDTH = 1920
		private const val MAX_BACKGROUND_HEIGHT = 1080
		private const val MAX_LOW_PERFORMANCE_BACKGROUND_WIDTH = 1280
		private const val MAX_LOW_PERFORMANCE_BACKGROUND_HEIGHT = 720
		private const val MAX_BACKGROUND_COUNT = 2
		private const val MAX_LOW_PERFORMANCE_BACKGROUND_COUNT = 1

		val BACKGROUND_SELECTION_DELAY = 250.milliseconds
		val SLIDESHOW_DURATION = 30.seconds
		val TRANSITION_DURATION = 800.milliseconds
	}

	// Async
	private val scope = MainScope()
	private val lowPerformanceDevice = PerformanceProfile.isLowPerformanceDevice(context)
	private val maxBackgroundCount = if (lowPerformanceDevice) MAX_LOW_PERFORMANCE_BACKGROUND_COUNT else MAX_BACKGROUND_COUNT
	private val backgroundSize by lazy {
		val metrics = context.resources.displayMetrics
		val maxWidth = if (lowPerformanceDevice) MAX_LOW_PERFORMANCE_BACKGROUND_WIDTH else MAX_BACKGROUND_WIDTH
		val maxHeight = if (lowPerformanceDevice) MAX_LOW_PERFORMANCE_BACKGROUND_HEIGHT else MAX_BACKGROUND_HEIGHT
		val width = metrics.widthPixels.coerceAtLeast(1).coerceAtMost(maxWidth)
		val height = metrics.heightPixels.coerceAtLeast(1).coerceAtMost(maxHeight)
		width to height
	}
	private var pendingBackgroundJob: Job? = null
	private var loadBackgroundsJob: Job? = null
	private var updateBackgroundTimerJob: Job? = null
	private var lastBackgroundTimerUpdate = 0L
	private var requestedBackgroundUrls: Set<String>? = null

	// Current background data
	private var _backgrounds = emptyList<ImageBitmap>()
	private var _currentIndex = 0
	private var _currentBackground = MutableStateFlow<ImageBitmap?>(null)
	private var _blurBackground = MutableStateFlow(false)
	private var _enabled = MutableStateFlow(true)
	val currentBackground get() = _currentBackground.asStateFlow()
	val blurBackground get() = _blurBackground.asStateFlow()
	val enabled get() = _enabled.asStateFlow()

	/**
	 * Use all available backdrops from [baseItem] as background.
	 */
	fun setBackground(baseItem: BaseItemDto?) {
		val backdropBehavior = userPreferences[UserPreferences.backdropBehavior]

		// Check if item is set and backgrounds are enabled
		if (baseItem == null || backdropBehavior == BackdropBehavior.DISABLED)
			return clearBackgrounds()

		// Enable blur for backdrops
		_blurBackground.value = backdropBehavior == BackdropBehavior.BACKDROP_WITH_BLUR && !lowPerformanceDevice

		// Ask the server for screen-sized images. Loading original/4K backdrops here
		// is particularly expensive because they are kept as ImageBitmaps.
		val (width, height) = backgroundSize
		val backdropUrls = (baseItem.itemBackdropImages + baseItem.parentBackdropImages)
			.asSequence()
			.map { it.getUrl(api, fillWidth = width, fillHeight = height) }
			.distinct()
			.take(maxBackgroundCount)
			.toSet()

		if (backdropUrls.isEmpty()) clearBackgrounds()
		else requestBackgrounds(backdropUrls)
	}

	/**
	 * Update a backdrop in response to focus movement. Fullscreen image decoding is
	 * intentionally disabled for this high-frequency path on constrained devices;
	 * explicit screen and detail backdrops can still use [setBackground].
	 */
	fun setSelectionBackground(baseItem: BaseItemDto?) {
		if (lowPerformanceDevice) {
			// Remove a backdrop left by the previous screen once, then keep focus
			// movement allocation-free while browsing.
			if (requestedBackgroundUrls != null || _currentBackground.value != null) clearBackgrounds()
			return
		}
		setBackground(baseItem)
	}

	/**
	 * Use splashscreen from [server] as background.
	 */
	fun setBackground(server: Server) {
		// Check if item is set and backgrounds are enabled
		if (userPreferences[UserPreferences.backdropBehavior] == BackdropBehavior.DISABLED)
			return clearBackgrounds()

		// Check if splashscreen is enabled in (cached) branding options
		if (!server.splashscreenEnabled)
			return clearBackgrounds()

		// Disable blur on splashscreen
		_blurBackground.value = false

		// Manually grab the backdrop URL
		val api = jellyfin.createApi(baseUrl = server.address)
		val splashscreenUrl = api.imageApi.getSplashscreenUrl()

		requestBackgrounds(setOf(splashscreenUrl), delayed = false)
	}

	private fun requestBackgrounds(backdropUrls: Set<String>, delayed: Boolean = true) {
		if (backdropUrls.isEmpty()) return clearBackgrounds()
		if (backdropUrls == requestedBackgroundUrls) return
		requestedBackgroundUrls = backdropUrls

		pendingBackgroundJob?.cancel()
		// Stop decoding the previous selection immediately; the new selection
		// will only be started after the focus settles.
		loadBackgroundsJob?.cancel()
		if (!delayed) {
			loadBackgrounds(backdropUrls)
			return
		}

		pendingBackgroundJob = scope.launch {
			delay(BACKGROUND_SELECTION_DELAY)
			loadBackgrounds(backdropUrls)
		}
	}

	private fun loadBackgrounds(backdropUrls: Set<String>) {
		if (backdropUrls.isEmpty()) return clearBackgrounds()

		// Re-enable backgrounds if disabled
		_enabled.value = true

		// Cancel current loading and slideshow jobs before releasing the old
		// bitmaps. This prevents rapid focus changes from retaining several
		// generations of fullscreen images at once.
		loadBackgroundsJob?.cancel()
		updateBackgroundTimerJob?.cancel()
		_backgrounds = emptyList()
		_currentBackground.value = null

		val (width, height) = backgroundSize
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			val coroutineContext = currentCoroutineContext()
			val loadedBackgrounds = backdropUrls.mapNotNull { url ->
				coroutineContext.ensureActive()
				imageLoader.execute(
					request = ImageRequest.Builder(context)
						.data(url)
						.size(width, height)
						.build()
				).image?.toBitmap()?.asImageBitmap()
			}

			withContext(Dispatchers.Main.immediate) {
				if (!currentCoroutineContext().isActive) return@withContext

				_backgrounds = loadedBackgrounds

				// Go to first background
				_currentIndex = 0
				update()
			}
		}
	}

	fun clearBackgrounds() {
		pendingBackgroundJob?.cancel()
		pendingBackgroundJob = null
		requestedBackgroundUrls = null
		loadBackgroundsJob?.cancel()
		loadBackgroundsJob = null
		updateBackgroundTimerJob?.cancel()

		// Re-enable backgrounds if disabled
		_enabled.value = true

		_backgrounds = emptyList()
		_currentIndex = 0
		_currentBackground.value = null
	}

	/**
	 * Disable the showing of backgrounds until any function manipulating the backgrounds is called.
	 */
	fun disable() {
		_enabled.value = false
	}

	internal fun update() {
		val now = Instant.now().toEpochMilli()
		if (lastBackgroundTimerUpdate > now - TRANSITION_DURATION.inWholeMilliseconds)
			return setTimer((lastBackgroundTimerUpdate - now).milliseconds + TRANSITION_DURATION, false)

		lastBackgroundTimerUpdate = now

		// Get next background to show
		if (_currentIndex >= _backgrounds.size) _currentIndex = 0

		// Set background
		_currentBackground.value = _backgrounds.getOrNull(_currentIndex)

		// Set timer for next background
		if (_backgrounds.size > 1) setTimer()
		else updateBackgroundTimerJob?.cancel()
	}

	private fun setTimer(updateDelay: Duration = SLIDESHOW_DURATION, increaseIndex: Boolean = true) {
		updateBackgroundTimerJob?.cancel()
		updateBackgroundTimerJob = scope.launch {
			delay(updateDelay)

			if (increaseIndex) _currentIndex++

			update()
		}
	}
}
