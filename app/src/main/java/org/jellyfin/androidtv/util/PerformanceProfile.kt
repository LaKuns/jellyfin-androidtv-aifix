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
 * The decision is based on the hardware of the device: the configuration of the
 * system, the number of cores and the physical memory. An explicit choice of the
 * user always wins over the automatic detection.
 */
object PerformanceProfile {
	private const val PERFORMANCE_MODE_KEY = "performance_mode"
	private const val PERFORMANCE_MODE_LOW = "LOW"
	private const val PERFORMANCE_MODE_FULL = "FULL"

	/**
	 * Physical memory of the device at or below which it is treated as low performance.
	 *
	 * The heap limit of a single application is deliberately not used for this: it is
	 * read from the system property `dalvik.vm.heapgrowthlimit`, which is 512 MB on
	 * most devices, so a comparison against that value would classify every device
	 * as a low performance one.
	 */
	private const val LOW_TOTAL_MEMORY_MB = 2048

	/**
	 * CPU core count at or below which a device is treated as low performance.
	 *
	 * A modern quad core chip renders the interface without trouble, and the number of
	 * cores says nothing about the speed of a single core.
	 */
	private const val LOW_CPU_CORE_COUNT = 2

	/** Number of bytes in a megabyte, used to report the memory of the device. */
	private const val BYTES_PER_MB = 1024L * 1024L

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
	// 12 Mbps is what a weak TV box can actually sustain while it decodes. A higher
	// limit only helps on a fast wired connection, where the measured throughput is
	// the binding constraint anyway.
	const val LOW_PERFORMANCE_MAX_VIDEO_BITRATE = 12_000_000

	/**
	 * Bitrate requested from the server before the real throughput of the device is known.
	 *
	 * TV boxes are usually connected by wifi, which is often the limiting factor of the
	 * whole playback. A stream that cannot be downloaded as fast as it is consumed empties
	 * the buffer and makes the playback stutter every few seconds, so the requested bitrate
	 * starts well below the measured throughput of such devices and is corrected afterwards.
	 *
	 * The value is deliberately low: on a weak wifi connection a higher bitrate makes the
	 * player fall into a state where the download is paced by the playback and the buffer
	 * can never recover, which shows up as a stutter every few seconds.
	 */
	const val LOW_PERFORMANCE_DEFAULT_VIDEO_BITRATE = 5_000_000

	/**
	 * Bitrate the connection has to be able to carry before the player starts with a
	 * short buffer.
	 *
	 * The player waits for a couple of seconds of the stream before it shows the
	 * first frame. A fast connection delivers them quickly, a slow one has to wait
	 * for them, so the start is quicker when the connection is fast.
	 */
	const val FAST_CONNECTION_BITRATE = 12_000_000L

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
			cachedAutomaticProfile ?: detectLowPerformanceDevice(context).isNotEmpty().also {
				cachedAutomaticProfile = it
			}
		}
	}

	/**
	 * Why the device is treated as a low performance device.
	 *
	 * The reason is reported instead of a plain flag, because the automatic
	 * detection depends on the hardware of the device and a wrong result is hard
	 * to explain without knowing which of the criteria was the deciding one.
	 *
	 * @return The reason, or an empty string when the device is not a low
	 * performance device
	 */
	@JvmStatic
	fun lowPerformanceReason(context: Context): String {
		when (readMode(context)) {
			PERFORMANCE_MODE_LOW -> return "the setting forces the low performance mode"
			PERFORMANCE_MODE_FULL -> return ""
		}

		return detectLowPerformanceDevice(context)
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

	/**
	 * Detects whether the hardware of the device is too weak for the full interface.
	 *
	 * @return The criterion that makes the device a low performance one, or an empty
	 * string when the device is able to run the full interface
	 */
	private fun detectLowPerformanceDevice(context: Context): String {
		val appContext = context.applicationContext
		val activityManager = appContext.getSystemService<ActivityManager>() ?: return ""

		// The configuration of the device decides whether the system itself considers
		// it to be short on memory.
		if (activityManager.isLowRamDevice) return "the system reports a low RAM device"

		// Only a device with very few cores is treated as low performance.
		val cores = Runtime.getRuntime().availableProcessors()
		if (cores <= LOW_CPU_CORE_COUNT) return "only $cores CPU cores"

		// The physical memory of the device is the relevant value here.
		val memoryInfo = ActivityManager.MemoryInfo()
		activityManager.getMemoryInfo(memoryInfo)
		val totalMemoryMb = memoryInfo.totalMem / BYTES_PER_MB
		if (totalMemoryMb <= LOW_TOTAL_MEMORY_MB) return "only $totalMemoryMb MB of memory"

		return ""
	}
}
