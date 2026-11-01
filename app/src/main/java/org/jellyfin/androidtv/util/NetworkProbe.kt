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
 */
object NetworkProbe {
	/** Number of bytes that are downloaded for the measurement. */
	private const val PROBE_SIZE = 8 * 1024 * 1024

	/**
	 * Number of bytes that are downloaded before the measurement starts.
	 *
	 * The first bytes of a connection are slow: the tcp window is still small and the
	 * connection has to warm up. Measuring them would report a connection that is much
	 * slower than it is.
	 */
	private const val WARMUP_SIZE = 2 * 1024 * 1024

	/**
	 * Size of a single read.
	 *
	 * Small reads make the throughput depend on the round trip time, because every read
	 * has to wait for the next chunk of data to arrive.
	 */
	private const val BUFFER_SIZE = 512 * 1024

	private const val TIMEOUT_MS = 20_000

	/** Measurements below this size are not meaningful. */
	private const val MIN_PROBE_BYTES = 1024L * 1024

	/**
	 * A connection that is at least this fast does not need a second attempt.
	 */
	private const val ACCEPTABLE_PROBE_BITRATE = 20_000_000L

	private var probed = false

	/**
	 * Runs the measurement once per app start.
	 *
	 * @return The measurement or null when it failed
	 */
	suspend fun measureOnce(api: ApiClient): LinkMeasurement? {
		if (probed) return null
		probed = true

		return measure(api)
	}

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
		// The server may be busy when the measurement starts, which makes the first
		// attempt slow. The better of two attempts is the better estimate of the
		// connection.
		var best: LinkMeasurement? = null

		for (path in listOf("/Videos/$itemId/stream", "/Audio/$itemId/stream")) {
			val url = buildUrl(api, "$path?static=true")

			for (attempt in 0 until 2) {
				val result = downloadChunk(url, token) ?: continue

				Timber.i("Network probe: %d bps, %d ms latency", result.bitrate, result.latencyMs)

				if (best == null || result.bitrate > best.bitrate) {
					best = result
				}

				if (best.bitrate >= ACCEPTABLE_PROBE_BITRATE) break
			}

			if (best != null) return best
		}

		return null
	}

	private fun downloadChunk(url: String, token: String): LinkMeasurement? {
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.connectTimeout = TIMEOUT_MS
		connection.readTimeout = TIMEOUT_MS
		authorize(connection, token)
		connection.setRequestProperty("Range", "bytes=0-${WARMUP_SIZE + PROBE_SIZE - 1}")

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
				while (read < WARMUP_SIZE + PROBE_SIZE) {
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
