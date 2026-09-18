package org.jellyfin.androidtv.util

import android.app.ActivityManager
import android.content.Context
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
	private const val LOW_MEMORY_CLASS_MB = 512
	private const val LOW_CPU_CORE_COUNT = 4
	@Volatile
	private var cachedAutomaticProfile: Boolean? = null

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
		val mode = PreferenceManager.getDefaultSharedPreferences(context)
			.getString(PERFORMANCE_MODE_KEY, null)
		when (mode) {
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

	private fun detectLowPerformanceDevice(context: Context): Boolean {
		val activityManager = context.applicationContext.getSystemService<ActivityManager>() ?: return false
		return activityManager.isLowRamDevice ||
			activityManager.memoryClass <= LOW_MEMORY_CLASS_MB ||
			Runtime.getRuntime().availableProcessors() <= LOW_CPU_CORE_COUNT
	}
}
