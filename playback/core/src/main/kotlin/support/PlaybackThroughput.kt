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
	private const val KEY_LINK = "link_bps"
	private const val KEY_LINK_LATENCY = "link_latency_ms"

	/** Number of downloads that are averaged. */
	private const val WINDOW_SIZE = 8

	/**
	 * Part of the measured throughput that is requested from the server.
	 *
	 * A measurement is the number of bytes the device pulled during one request cycle,
	 * which is exactly the bitrate it is able to sustain. The measurement contains every
	 * slowdown of a real playback: the time the server needs to hand out a segment, the
	 * round trip time of the connection and the cpu time the decoder takes from the
	 * loader.
	 *
	 * The margin is generous because the speed of a wifi connection drops by more than
	 * half now and then. A stream that is slightly too fast for the connection empties
	 * the buffer, and the player then downloads the stream at the speed it is played
	 * back, so the buffer can never recover.
	 */
	private const val USABLE_FRACTION = 0.75

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

	/**
	 * Downloads slower than this are not a measurement of the connection.
	 *
	 * A file that is read at the speed it is played back shows the bitrate of the file,
	 * not the speed of the connection. Such a sample is useless and would only lower the
	 * requested bitrate for no reason.
	 */
	private const val MIN_SAMPLE_BPS = 2_000_000L

	private val samples = ArrayDeque<Long>()

	/**
	 * Records a single download.
	 *
	 * @param context Used to persist the measurement
	 * @param bytesLoaded Number of bytes of the download
	 * @param loadDurationMs Duration of the download
	 */
	@JvmStatic
	@Synchronized
	fun record(context: Context, bytesLoaded: Long, loadDurationMs: Long) {
		if (bytesLoaded < MIN_SAMPLE_BYTES || bytesLoaded > MAX_SAMPLE_BYTES) return
		if (loadDurationMs <= 0) return

		val bitsPerSecond = bytesLoaded * 8 * 1000 / loadDurationMs
		if (bitsPerSecond < MIN_SAMPLE_BPS) return

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
	 * The average throughput of the media downloads.
	 *
	 * @return The throughput in bits per second or 0 when nothing was measured yet
	 */
	@JvmStatic
	fun getAverageBitrate(context: Context): Long =
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.getLong(KEY_AVERAGE, 0)

	/**
	 * Whether a media download was measured at all.
	 *
	 * This matters because the throughput of the connection alone must never raise
	 * the requested bitrate: a fast connection says nothing about how fast the
	 * device can pull a media stream while it decodes it.
	 */
	@JvmStatic
	fun hasMediaMeasurement(context: Context): Boolean = getAverageBitrate(context) > 0

	/**
	 * The upper bound of the bitrate that may be requested from the server.
	 *
	 * Two independent measurements are combined:
	 *   - the throughput of the media downloads, which is what the device managed
	 *     to pull while it was decoding a stream
	 *   - the throughput of the connection, measured without decoding anything
	 *
	 * The smaller of the two is the limit. When the connection is the bottleneck
	 * the media download cannot be faster than the connection, and when the device
	 * is the bottleneck the connection may be a lot faster than the media download.
	 *
	 * @return The bitrate in bits per second or 0 when nothing is known yet
	 */
	@JvmStatic
	fun getStreamBitrateLimit(context: Context): Int {
		val media = getAverageBitrate(context)
		val link = getLinkBitrate(context)

		val mediaLimit = if (media > 0) {
			(media * USABLE_FRACTION).toInt().coerceAtLeast(MIN_BITRATE)
		} else {
			0
		}
		val linkLimit = if (link > 0) {
			(link * USABLE_FRACTION).toInt().coerceAtLeast(MIN_BITRATE)
		} else {
			0
		}

		return when {
			mediaLimit > 0 && linkLimit > 0 -> minOf(mediaLimit, linkLimit)
			mediaLimit > 0 -> mediaLimit
			else -> linkLimit
		}
	}

	/**
	 * The last measured throughput, for diagnostics.
	 */
	@JvmStatic
	fun getLastMeasuredBitrate(context: Context): Long =
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.getLong(KEY_LAST, 0)

	/**
	 * Stores the throughput of the connection, measured without any decoding.
	 *
	 * A slow connection can lower the requested bitrate, a fast one proves that a
	 * stuttering playback is caused by the device itself.
	 */
	@JvmStatic
	fun recordLinkBitrate(context: Context, bitsPerSecond: Long) {
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.putLong(KEY_LINK, bitsPerSecond)
			.apply()
	}

	/**
	 * Stores the round trip time of the connection, measured without any decoding.
	 *
	 * A high latency makes the player wait between two segments even when the
	 * bandwidth is sufficient.
	 */
	@JvmStatic
	fun recordLinkLatency(context: Context, latencyMs: Long) {
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.putLong(KEY_LINK_LATENCY, latencyMs)
			.apply()
	}

	/**
	 * The round trip time of the connection, for diagnostics.
	 */
	@JvmStatic
	fun getLinkLatency(context: Context): Long =
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.getLong(KEY_LINK_LATENCY, 0)

	/**
	 * The throughput of the connection, measured without any decoding.
	 */
	@JvmStatic
	fun getLinkBitrate(context: Context): Long =
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.getLong(KEY_LINK, 0)
}
