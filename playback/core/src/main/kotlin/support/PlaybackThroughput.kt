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
	private const val KEY_CAPABILITY = "capability_bps"
	private const val KEY_LINK = "link_bps"
	private const val KEY_LINK_LATENCY = "link_latency_ms"
	private const val KEY_LINK_NETWORK = "link_network"
	private const val KEY_LINK_TIME = "link_time"
	private const val KEY_ACTIVE_NETWORK = "active_network"

	/** Length of a HLS segment in seconds. */
	private const val SEGMENT_SECONDS = 3.0

	/** Safety factor of the bitrate the connection is able to carry. */
	private const val LINK_SAFETY_FACTOR = 0.9

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

	/**
	 * Bitrate that is reserved for the audio that is transcoded along with the video.
	 *
	 * The server takes the requested bitrate, subtracts the bitrate of the audio it
	 * chose and compares the remainder with the bitrate of the video stream of the
	 * source. A request that does not carry the audio therefore ends up below the
	 * bitrate of the source and the server scales the video down to the next step of
	 * its resolution ladder.
	 *
	 * The server picks the audio bitrate itself. The largest value it was observed to
	 * choose is 640000, so that amount has to be reserved to be sure that the video
	 * keeps the resolution of the source. A little more is added on top of it, so
	 * that the request also survives a server that picks a larger audio bitrate
	 * than the one that was observed.
	 */
	const val AUDIO_HEADROOM = 768_000

	/**
	 * Fraction of the measured connection that a request may use when it keeps the
	 * resolution of the source.
	 *
	 * The normal limit of a request is lower than the connection, because the
	 * margin is needed for the traffic that competes with the stream and for the
	 * time between two segments. A request that keeps the resolution of the source
	 * is higher than that limit, but the connection is still able to carry it: the
	 * surplus of the connection over the stream fills the buffer, and a stream that
	 * is scaled down by the server stays that way for the whole film.
	 *
	 * When the connection is not able to carry the bitrate of the source at all,
	 * the request falls back to the normal limit and the server picks the
	 * resolution that fits. A stuttering picture is worse than a smaller one.
	 */
	private const val RESOLUTION_FRACTION = 0.9

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

		val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
		val capability = maxOf(preferences.getLong(KEY_CAPABILITY, 0), bitsPerSecond)

		preferences.edit()
			.putLong(KEY_AVERAGE, average)
			.putLong(KEY_LAST, bitsPerSecond)
			.putLong(KEY_CAPABILITY, capability)
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
	 * The highest download rate the device has achieved so far.
	 *
	 * The average of the downloads is not a good measure of the capability of the
	 * device: when the server delivers the stream slower than the device can read
	 * it, the download rate is the bitrate of the stream and not the capability of
	 * the device. The highest rate that was ever reached is the capability.
	 */
	@JvmStatic
	fun getMediaCapability(context: Context): Long =
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.getLong(KEY_CAPABILITY, 0)

	/**
	 * The bitrate the connection is able to carry without the buffer running dry.
	 *
	 * The player downloads the segments of a stream one after the other, so a segment
	 * has to be downloaded faster than it is played back. The time that is left for
	 * the download is the length of a segment minus the round trip time of the
	 * connection.
	 *
	 * @return The bitrate in bits per second or 0 when the connection is unknown
	 */
	@JvmStatic
	fun getLinkSafeBitrate(context: Context): Long {
		val link = getLinkBitrate(context)
		if (link <= 0) return 0

		val latencySeconds = getLinkLatency(context) / 1000.0
		val usableFraction = ((SEGMENT_SECONDS - latencySeconds) / SEGMENT_SECONDS).coerceIn(0.1, 1.0)

		return (link * usableFraction * LINK_SAFETY_FACTOR).toLong()
	}

	/**
	 * The bitrate a request may use when it keeps the resolution of the source.
	 *
	 * @return The bitrate in bits per second or 0 when the connection is unknown
	 */
	@JvmStatic
	fun getResolutionAllowance(context: Context): Long {
		val link = getLinkBitrate(context)
		if (link <= 0) return 0

		return (link * RESOLUTION_FRACTION).toLong()
	}

	/**
	 * The upper bound of the bitrate that may be requested from the server.
	 *
	 * A transcoded stream is scaled down by the server when the requested bitrate is
	 * below the bitrate of the source file, so a limit below the bitrate of the
	 * source costs the resolution of the original. The bitrate of the source is
	 * therefore requested when the device kept up with everything it downloaded so
	 * far and the connection is able to carry it.
	 *
	 * @param context Used to read the measurements
	 * @param sourceBitrate The bitrate of the video stream of the source file in bits per second
	 * @return The bitrate in bits per second
	 */
	@JvmStatic
	fun getStreamBitrateLimit(context: Context, sourceBitrate: Int?): Int {
		val ceiling = getStreamBitrateLimit(context)
		if (sourceBitrate == null || sourceBitrate <= 0) return ceiling

		// The server compares the requested video bitrate with the bitrate of the
		// video stream of the source. The request also has to carry the audio that
		// is transcoded along with the video, so the bitrate of the audio is added
		// to the bitrate of the source. Without it the video bitrate that reaches
		// the server stays below the bitrate of the source and the server scales
		// the video down to the next step of its resolution ladder.
		val required = sourceBitrate + AUDIO_HEADROOM

		// The connection carries the bitrate of the source, so the resolution of
		// the source is requested.
		if (ceiling >= required) return required

		// The connection is slower than the bitrate of the source. The resolution
		// is still kept when the connection comes close enough to it: the surplus
		// of the connection over the stream fills the buffer, and a stream that
		// the server scaled down stays that way for the whole film.
		val link = getLinkBitrate(context)
		if (link > 0) {
			val allowance = (link * RESOLUTION_FRACTION).toInt()
			if (required <= allowance) return required
		}

		// The connection cannot carry the bitrate of the source at all. The limit
		// of the connection is requested and the server scales the video down to
		// the resolution that fits. A stuttering picture is worse than a smaller
		// one.
		return ceiling
	}

	/**
	 * The upper bound of the bitrate that may be requested from the server.
	 *
	 * The measurement of the connection is the limit. The throughput of the media
	 * downloads cannot be used for this: a stream is downloaded at the speed it is
	 * played back, so the measurement reports the bitrate of the stream that was
	 * requested and never the capability of the device. Using it as the limit
	 * would keep every following request at the bitrate of the first stream and
	 * the server would scale every following stream down.
	 *
	 * The connection is measured without decoding anything, so it reports what the
	 * network is able to deliver. The margin compensates for the traffic that
	 * competes with the stream and for the time between two segments.
	 *
	 * @return The bitrate in bits per second or 0 when nothing is known yet
	 */
	@JvmStatic
	fun getStreamBitrateLimit(context: Context): Int {
		val link = getLinkBitrate(context)
		if (link > 0) {
			return (link * USABLE_FRACTION).toInt().coerceAtLeast(MIN_BITRATE)
		}

		// The connection is unknown: it was never measured, or it was measured on
		// another connection. A limit that is based on the media downloads would be
		// wrong in both cases, so the request is not limited by the throughput.
		return 0
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
	 *
	 * The measurement belongs to the connection it was taken on. The identity of
	 * that connection is stored with it, so that the measurement can be ignored
	 * as soon as the device uses another connection.
	 *
	 * @param context Used to persist the measurement
	 * @param bitsPerSecond The measured throughput in bits per second
	 * @param networkId The identity of the connection the measurement was taken on
	 */
	@JvmStatic
	fun recordLinkBitrate(context: Context, bitsPerSecond: Long, networkId: String) {
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.putLong(KEY_LINK, bitsPerSecond)
			.putString(KEY_LINK_NETWORK, networkId)
			.putLong(KEY_LINK_TIME, System.currentTimeMillis())
			.apply()
	}

	/**
	 * The time of the last measurement of the connection.
	 *
	 * @return The time in milliseconds since the epoch or 0 when nothing was
	 *         measured yet
	 */
	@JvmStatic
	fun getLinkTime(context: Context): Long =
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.getLong(KEY_LINK_TIME, 0)

	/**
	 * The identity of the connection the measurements belong to.
	 *
	 * @return The identity or an empty string when nothing was measured yet
	 */
	@JvmStatic
	fun getLinkNetwork(context: Context): String =
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.getString(KEY_LINK_NETWORK, "") ?: ""

	/**
	 * The identity of the connection that is currently used.
	 *
	 * @return The identity or an empty string when it is unknown
	 */
	@JvmStatic
	fun getActiveNetwork(context: Context): String =
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.getString(KEY_ACTIVE_NETWORK, "") ?: ""

	/**
	 * Stores the identity of the connection that is currently used.
	 *
	 * The measurements of another connection are worthless as soon as the device
	 * uses a different one, so the connection that is in use is stored with them.
	 *
	 * @param context Used to persist the identity
	 * @param networkId The identity of the connection that is currently used
	 */
	@JvmStatic
	fun setActiveNetwork(context: Context, networkId: String) {
		context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.putString(KEY_ACTIVE_NETWORK, networkId)
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
	 *
	 * The measurement is only valid for the connection it was taken on. A device
	 * that switches from wifi to a cable keeps the value of the old connection
	 * otherwise, and the request would be based on a connection that is not in
	 * use anymore. A connection that is fast enough to carry the stream would
	 * then be treated as a slow one and the server would scale the video down.
	 *
	 * @return The bitrate in bits per second or 0 when the connection is unknown
	 *         or was measured on another connection
	 */
	@JvmStatic
	fun getLinkBitrate(context: Context): Long {
		val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
		val measured = preferences.getLong(KEY_LINK, 0)
		if (measured <= 0) return 0

		val measuredOn = preferences.getString(KEY_LINK_NETWORK, "") ?: ""
		val active = preferences.getString(KEY_ACTIVE_NETWORK, "") ?: ""

		// A measurement without an identity and a connection without a known
		// identity cannot be compared, so neither of them is used.
		if (measuredOn.isEmpty() || active.isEmpty()) return 0

		return if (measuredOn == active) measured else 0
	}
}
