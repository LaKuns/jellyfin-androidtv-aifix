package org.jellyfin.playback.core.support

import android.content.Context
import timber.log.Timber

/**
 * Keeps track of the throughput the device actually achieved while downloading media.
 *
 * The device profile is the only way to tell the server how much bandwidth may be spent on
 * a transcoded stream. A fixed limit either wastes quality on a fast network, or produces a
 * stream that cannot be downloaded as fast as it is consumed. In the second case the buffer
 * runs dry and the playback stutters every few seconds, even though the server transcodes
 * several times faster than real time and the network itself is fine.
 *
 * Measuring the real throughput of previous playbacks keeps the requested bitrate slightly
 * below what the device can actually sustain, which is what makes the buffer grow.
 */
object PlaybackThroughput {
	private const val PREFERENCES_NAME = "playback_throughput"
	private const val KEY_AVERAGE = "average_bps"
	private const val KEY_LAST = "last_bps"

	/** Number of downloads that are averaged. */
	private const val WINDOW_SIZE = 8

	/** Part of the measured throughput that is requested from the server. */
	private const val USABLE_FRACTION = 0.6

	/** Lower bound of the request, playback should never degrade into an unusable bitrate. */
	private const val MIN_BITRATE = 2_000_000

	/**
	 * Downloads below this size are dominated by request overhead and are ignored.
	 */
	private const val MIN_SAMPLE_BYTES = 64L * 1024

	/**
	 * Downloads above this size are not segments.
	 *
	 * A progressive stream is downloaded as one big response and the client only reads it
	 * as fast as it is consumed, so such a download says nothing about the speed of the
	 * connection and would drag the measured throughput down to the bitrate of the stream.
	 */
	private const val MAX_SAMPLE_BYTES = 16L * 1024 * 1024

	private val samples = ArrayDeque<Long>()

	/**
	 * Records a single download.
	 *
	 * @param context Used to persist the measurement
	 * @param bytesLoaded Number of bytes of the download
	 * @param loadDurationMs Duration of the download
	 */
	@Synchronized
	fun record(context: Context, bytesLoaded: Long, loadDurationMs: Long) {
		if (bytesLoaded < MIN_SAMPLE_BYTES || bytesLoaded > MAX_SAMPLE_BYTES) return
		if (loadDurationMs <= 0) return

		val bitsPerSecond = bytesLoaded * 8 * 1000 / loadDurationMs

		samples.addLast(bitsPerSecond)
		while (samples.size > WINDOW_SIZE) samples.removeFirst()

		val average = samples.sum() / samples.size

		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.putLong(KEY_AVERAGE, average)
			.putLong(KEY_LAST, bitsPerSecond)
			.apply()

		Timber.i("Measured download throughput %d bps (average %d bps)", bitsPerSecond, average)
	}

	/**
	 * The bitrate that can be requested from the server without starving the buffer.
	 *
	 * @return The bitrate in bits per second or 0 when nothing was measured yet
	 */
	fun getUsableBitrate(context: Context): Int {
		val average = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.getLong(KEY_AVERAGE, 0)
		if (average <= 0) return 0
		return (average * USABLE_FRACTION).toInt().coerceAtLeast(MIN_BITRATE)
	}

	/**
	 * The last measured throughput, for diagnostics.
	 */
	fun getLastMeasuredBitrate(context: Context): Long =
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.getLong(KEY_LAST, 0)
}
