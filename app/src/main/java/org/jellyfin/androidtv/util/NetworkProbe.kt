package org.jellyfin.androidtv.util

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.support.PlaybackThroughput
import org.jellyfin.sdk.api.client.ApiClient
import org.json.JSONArray
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Measures the speed and the latency of the connection to the server without decoding
 * anything.
 *
 * The download speed of a media stream cannot be used to tell a slow network apart from
 * a busy device: while a video is decoded the loader thread of the player competes with
 * the decoders for cpu time, so the transfer slows down even when the connection itself
 * is fast. Downloading a plain file therefore measures the connection on its own.
 *
 * The latency matters as much as the speed. A weak wifi connection on a TV box is often
 * fast enough to carry the stream but adds a second of round trip time to every request,
 * and that delay is what makes the player wait between two segments.
 *
 * Three things have to be kept apart from the measurement:
 *
 * - The time the server needs to find a file on its disk is not the round trip time of
 *   the network. The file is therefore read once before the measurement, so that the
 *   server has it in its cache. The time until the first byte arrives is not part of the
 *   download time either: it is reported as the latency and the transfer is measured from
 *   the moment the data actually flows.
 * - A sample that is downloaded faster than the window of the tcp connection can fill does
 *   not describe the connection, it describes a burst. Such a measurement is repeated with
 *   a sample that is large enough to measure the rate the connection sustains.
 * - A first byte that takes more than a second is the disk of the server and not the
 *   connection. The measurement is repeated once and the answer with the smaller delay
 *   is used.
 */
object NetworkProbe {
	/**
	 * Number of bytes that are downloaded for the measurement of a connection.
	 *
	 * The measurement runs while the application starts and while the connection
	 * changes, and it shares the bandwidth with the first playback. A large
	 * sample measures the connection more precisely, but it also keeps the
	 * connection busy for a long time, so the sample is kept small.
	 */
	private const val PROBE_SIZE = 2 * 1024 * 1024

	/**
	 * Number of bytes that are downloaded for the measurement of a fast connection.
	 *
	 * A sample that is larger than the window of the tcp connection measures the
	 * rate that the connection sustains instead of the rate of the first burst.
	 */
	private const val PROBE_SIZE_FAST = 16 * 1024 * 1024

	/**
	 * Transfer time from which on the small sample describes the connection.
	 *
	 * When two megabytes are downloaded faster than this, the data arrived as one burst:
	 * the window of the tcp connection was not filled yet, so the rate is the burst rate of
	 * the wifi link and not what it sustains. The same download then reports a connection
	 * that is much faster than it is, and a stream is requested that the buffer cannot
	 * keep up with.
	 */
	private const val FAST_SAMPLE_MS = 500L

	/**
	 * Delay before the first byte that is not the round trip time of the connection.
	 *
	 * The server needs about that long to open a file on a mechanical disk while it is
	 * transcoding for another device. That time is not the network, so the measurement is
	 * repeated and the better answer is kept.
	 */
	private val SLOW_LATENCY_MS = PlaybackThroughput.MAX_LINK_LATENCY_MS

	/**
	 * Number of bytes that are downloaded before the measurement starts.
	 *
	 * The first bytes of a connection are slow: the tcp window is still small and the
	 * connection has to warm up. Measuring them would report a connection that is much
	 * slower than it is.
	 *
	 * The same amount is read before the measurement as well, to let the server
	 * cache the file it has to open.
	 */
	private const val WARMUP_SIZE = 512 * 1024

	/**
	 * Size of a single read.
	 *
	 * Small reads make the throughput depend on the round trip time, because every read
	 * has to wait for the next chunk of data to arrive.
	 */
	private const val BUFFER_SIZE = 512 * 1024

	/**
	 * Timeout of the requests of the measurement.
	 *
	 * The timeout applies to a single read and not to the whole transfer: a slow
	 * connection still delivers its data, it only needs more time for it.
	 */
	private const val TIMEOUT_MS = 10_000

	/** Measurements below this size are not meaningful. */
	private const val MIN_PROBE_BYTES = 1024L * 1024

	/**
	 * Runs the measurement.
	 *
	 * @return The measurement or null when it failed
	 */
	suspend fun measure(api: ApiClient): LinkMeasurement? {
		val token = api.accessToken ?: return null

		return withContext(Dispatchers.IO) {
			runCatching {
				val itemId = findItem(api, token) ?: return@runCatching null
				download(api, token, itemId)
			}.onFailure {
				Timber.w(it, "Network probe failed")
			}.getOrNull()
		}
	}

	/**
	 * Builds an url of the server.
	 */
	private fun buildUrl(api: ApiClient, path: String): String =
		api.createUrl(path, ignorePathParameters = true)

	/**
	 * The server only accepts the access token in the authorization header.
	 */
	private fun authorize(connection: HttpURLConnection, token: String) {
		connection.setRequestProperty("Authorization", "MediaBrowser Token=\"$token\"")
	}

	/**
	 * The id of any item of the library, its bytes are used for the measurement.
	 */
	private fun findItem(api: ApiClient, token: String): String? {
		val url = buildUrl(api, "/Items/Latest?limit=1")
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.connectTimeout = TIMEOUT_MS
		connection.readTimeout = TIMEOUT_MS
		authorize(connection, token)

		return try {
			val text = connection.inputStream.bufferedReader().use { it.readText() }
			val items = JSONArray(text)
			if (items.length() == 0) {
				null
			} else {
				items.getJSONObject(0).optString("Id").takeIf { it.isNotEmpty() }
			}
		} catch (_: Exception) {
			null
		} finally {
			connection.disconnect()
		}
	}

	/**
	 * Downloads a chunk of the item and measures how long it took.
	 */
	private fun download(api: ApiClient, token: String, itemId: String): LinkMeasurement? {
		for (path in listOf("/Videos/$itemId/stream", "/Audio/$itemId/stream")) {
			val url = buildUrl(api, "$path?static=true")

			val result = measure(url, token) ?: continue

			Timber.i(
				"Network probe: %d bps, %d ms latency, %d ms transfer",
				result.bitrate,
				result.latencyMs,
				result.transferMs,
			)

			return result
		}

		return null
	}

	/**
	 * Measures the connection, and measures it again when the first byte was late.
	 *
	 * A delay before the first byte belongs to the disk of the server as often as to the
	 * connection, and the two cannot be told apart. A second measurement costs two
	 * megabytes and gives the connection another chance: when the file is in the cache of
	 * the server by then, the delay of the second answer is the delay of the network.
	 */
	private fun measure(url: String, token: String): LinkMeasurement? {
		val first = measureOnce(url, token) ?: return null
		if (first.latencyMs <= SLOW_LATENCY_MS) return first

		val repeated = measureOnce(url, token) ?: return first
		Timber.i(
			"Network probe (repeated): %d bps, %d ms latency",
			repeated.bitrate,
			repeated.latencyMs,
		)

		return if (repeated.latencyMs < first.latencyMs) repeated else first
	}

	/**
	 * One measurement.
	 *
	 * The small sample is downloaded first. When it was delivered as a burst, the rate the
	 * connection sustains is measured with a large sample instead.
	 */
	private fun measureOnce(url: String, token: String): LinkMeasurement? {
		// The server opens the file when the request arrives. The time it needs for that
		// is spent on its disk and not on the network, so the file is read once before the
		// measurement starts.
		warmUp(url, token)

		val small = downloadChunk(url, token, PROBE_SIZE) ?: return null
		Timber.i(
			"Network probe (small): %d bps, %d ms latency, %d ms transfer",
			small.bitrate,
			small.latencyMs,
			small.transferMs,
		)

		// The sample was delivered faster than the window of the tcp connection can fill:
		// the rate is a burst and not what the connection sustains. The large sample takes
		// the place of the small one.
		if (small.transferMs >= FAST_SAMPLE_MS) return small

		val sustained = downloadChunk(url, token, PROBE_SIZE_FAST) ?: return small
		Timber.i(
			"Network probe (sustained): %d bps, %d ms latency, %d ms transfer",
			sustained.bitrate,
			sustained.latencyMs,
			sustained.transferMs,
		)

		return sustained
	}

	/**
	 * Reads a small part of the file so that the server has it in its cache.
	 *
	 * The time the server needs to find a file on its disk happens before the first
	 * byte of the answer arrives. That time is not the round trip time of the
	 * network, and the playback does not wait for it either: the segments of a
	 * stream are read from the cache of the transcoder.
	 */
	private fun warmUp(url: String, token: String) {
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.connectTimeout = TIMEOUT_MS
		connection.readTimeout = TIMEOUT_MS
		authorize(connection, token)
		connection.setRequestProperty("Range", "bytes=0-${WARMUP_SIZE - 1}")

		try {
			connection.inputStream.use { stream ->
				val buffer = ByteArray(BUFFER_SIZE)
				var read = 0
				while (read < WARMUP_SIZE) {
					val count = stream.read(buffer)
					if (count < 0) break
					read += count
				}
			}
		} catch (_: Exception) {
			// The warm up is an optimisation, a failure of it does not matter.
		} finally {
			connection.disconnect()
		}
	}

	private fun downloadChunk(url: String, token: String, probeSize: Int): LinkMeasurement? {
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.connectTimeout = TIMEOUT_MS
		connection.readTimeout = TIMEOUT_MS
		authorize(connection, token)
		connection.setRequestProperty("Range", "bytes=0-${WARMUP_SIZE + probeSize - 1}")

		val buffer = ByteArray(BUFFER_SIZE)
		var read = 0L
		var latencyMs = 0L
		var transferMs = 0L
		val requestStart = SystemClock.elapsedRealtime()

		try {
			connection.inputStream.use { stream ->
				// The round trip time of the connection: the time until the first
				// byte of the answer arrives. Everything the player has to wait for
				// before a segment starts is this delay.
				val first = stream.read(buffer)
				if (first <= 0) return null
				latencyMs = SystemClock.elapsedRealtime() - requestStart
				read += first

				// Warm up: the first bytes are slow and would distort the measurement.
				while (read < WARMUP_SIZE) {
					val count = stream.read(buffer)
					if (count < 0) break
					read += count
				}

				if (read < WARMUP_SIZE) return null

				// The transfer is measured from here on. The time before the first byte
				// is the latency of the answer and does not describe the speed of the
				// connection, so it is not part of the transfer.
				val start = SystemClock.elapsedRealtime()
				val measured = read
				while (read < WARMUP_SIZE + probeSize) {
					val count = stream.read(buffer)
					if (count < 0) break
					read += count
				}
				transferMs = SystemClock.elapsedRealtime() - start
				if (read <= measured) return null
			}
		} catch (_: Exception) {
			return null
		} finally {
			connection.disconnect()
		}

		val bytes = read - WARMUP_SIZE
		if (bytes < MIN_PROBE_BYTES || transferMs <= 0) return null

		return LinkMeasurement(
			bitrate = bytes * 8 * 1000 / transferMs,
			latencyMs = latencyMs,
			transferMs = transferMs,
		)
	}
}

/**
 * The result of a connection measurement.
 *
 * @param bitrate The measured throughput in bits per second
 * @param latencyMs The time the connection needed to deliver the first chunk of data
 * @param transferMs The time the measured sample took to arrive, without the latency
 */
data class LinkMeasurement(
	val bitrate: Long,
	val latencyMs: Long,
	val transferMs: Long = 0,
)
