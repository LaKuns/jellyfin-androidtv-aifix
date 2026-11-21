package org.jellyfin.androidtv.util

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Two things have to be kept apart from the measurement:
 *
 * - The time the server needs to find a file on its disk is not the round trip time of
 *   the network. The file is therefore read once before the measurement, so that the
 *   server has it in its cache and only the network is left to measure.
 * - A small sample measures the burst rate of a fast connection: the window of the tcp
 *   connection is larger than the sample and the data arrives in one go. A fast
 *   connection is therefore measured again with a large sample, which reports the rate
 *   that the connection sustains.
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
	 * Rate from which on the small sample is not trusted.
	 *
	 * Above that rate the sample of two megabytes is smaller than the window of the
	 * tcp connection, so the measurement describes a burst and not the connection.
	 */
	private const val FAST_LINK_BITRATE = 50_000_000L

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

			// The server opens the file when the request arrives. The time it needs
			// for that is spent on its disk and not on the network, so the file is
			// read once before the measurement starts.
			warmUp(url, token)

			val result = downloadChunk(url, token, PROBE_SIZE) ?: continue

			Timber.i("Network probe: %d bps, %d ms latency", result.bitrate, result.latencyMs)

			// A fast connection is measured again with a large sample: the small one
			// is smaller than the window of the tcp connection and describes the
			// burst that arrives first instead of the rate the connection sustains.
			if (result.bitrate >= FAST_LINK_BITRATE) {
				val sustained = downloadChunk(url, token, PROBE_SIZE_FAST)
				if (sustained != null) {
					Timber.i("Network probe (sustained): %d bps, %d ms latency", sustained.bitrate, sustained.latencyMs)
					return sustained
				}
			}

			return result
		}

		return null
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
		var elapsed = 0L
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

				val start = SystemClock.elapsedRealtime()
				val measured = read
				while (read < WARMUP_SIZE + probeSize) {
					val count = stream.read(buffer)
					if (count < 0) break
					read += count
				}
				elapsed = SystemClock.elapsedRealtime() - start
				if (read <= measured) return null
			}
		} catch (_: Exception) {
			return null
		} finally {
			connection.disconnect()
		}

		val bytes = read - WARMUP_SIZE
		if (bytes < MIN_PROBE_BYTES || elapsed <= 0) return null

		return LinkMeasurement(
			bitrate = bytes * 8 * 1000 / elapsed,
			latencyMs = latencyMs,
		)
	}
}

/**
 * The result of a connection measurement.
 *
 * @param bitrate The measured throughput in bits per second
 * @param latencyMs The time the connection needed to deliver the first chunk of data
 */
data class LinkMeasurement(
	val bitrate: Long,
	val latencyMs: Long,
)
