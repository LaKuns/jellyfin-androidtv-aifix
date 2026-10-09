package org.jellyfin.androidtv.util

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager

/**
 * Lightweight device classification used for UI decisions that have a large
 * memory or rendering cost.
 *
 * Android TV devices do not always report [ActivityManager.isLowRamDevice]
 * consistently, so the application heap limit is used as a second signal.
 */
object PerformanceProfile {
	private const val PERFORMANCE_MODE_KEY = "performance_mode"
	private const val PERFORMANCE_MODE_LOW = "LOW"
	private const val PERFORMANCE_MODE_FULL = "FULL"

	/**
	 * Heap limit at or below which a device is treated as low performance.
	 *
	 * Preserve the conservative release threshold until the new UI is measured on
	 * older hardware.
	 */
	private const val LOW_MEMORY_CLASS_MB = 512

	/**
	 * CPU core count at or below which a device is treated as low performance.
	 *
	 * Preserve the existing four-core threshold for this release.
	 */
	private const val LOW_CPU_CORE_COUNT = 4

	private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

	@Volatile
	private var cachedAutomaticProfile: Boolean? = null

	@Volatile
	private var cachedMode: String? = null

	@Volatile
	private var preferenceListenerRegistered = false

	/**
	 * Conservative playback limits for devices which can render the UI but cannot reliably
	 * decode high bitrate or 4K video in real time.
	 */
	const val LOW_PERFORMANCE_MAX_VIDEO_WIDTH = 1920
	const val LOW_PERFORMANCE_MAX_VIDEO_HEIGHT = 1080
	// 18 Mbps keeps most good-quality 1080p sources on direct play while still
	// moving Blu-ray remuxes and other high-load streams to the server.
	const val LOW_PERFORMANCE_MAX_VIDEO_BITRATE = 18_000_000

	@JvmStatic
	fun isLowPerformanceDevice(context: Context): Boolean {
		// Reading the preference first is required so an explicit user choice always wins,
		// but the value is cached because this is called several times per card bind.
		when (readMode(context)) {
			PERFORMANCE_MODE_LOW -> return true
			PERFORMANCE_MODE_FULL -> return false
		}

		cachedAutomaticProfile?.let { return it }

		return synchronized(this) {
			cachedAutomaticProfile ?: detectLowPerformanceDevice(context).also {
				cachedAutomaticProfile = it
			}
		}
	}

	/**
	 * Current user override, cached together with a listener so changing the setting takes
	 * effect without needing the process to be restarted.
	 */
	private fun readMode(context: Context): String? {
		if (preferenceListenerRegistered) return cachedMode

		return synchronized(this) {
			if (!preferenceListenerRegistered) {
				val appContext = context.applicationContext
				val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
				val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
					if (key == PERFORMANCE_MODE_KEY) {
						cachedMode = preferences.getString(PERFORMANCE_MODE_KEY, null)
					}
				}
				preferenceListener = listener
				preferences.registerOnSharedPreferenceChangeListener(listener)
				cachedMode = preferences.getString(PERFORMANCE_MODE_KEY, null)
				preferenceListenerRegistered = true
			}
			cachedMode
		}
	}

	private fun detectLowPerformanceDevice(context: Context): Boolean {
		val appContext = context.applicationContext
		val activityManager = appContext.getSystemService<ActivityManager>() ?: return false
		return activityManager.isLowRamDevice ||
			activityManager.memoryClass <= LOW_MEMORY_CLASS_MB ||
			Runtime.getRuntime().availableProcessors() <= LOW_CPU_CORE_COUNT
	}
}
