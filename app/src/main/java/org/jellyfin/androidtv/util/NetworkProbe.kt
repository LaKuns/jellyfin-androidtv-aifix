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
 * Measures the speed of the connection to the server without decoding anything.
 *
 * The download speed of a media stream cannot be used to tell a slow network apart from
 * a busy device: while a video is decoded the loader thread of the player competes with
 * the decoders for cpu time, so the transfer slows down even when the connection itself
 * is fast. Downloading a plain file therefore measures the connection on its own.
 *
 * The result is only used for diagnostics, it shows whether the connection is the reason
 * for a stuttering playback or the device itself is too slow.
 */
object NetworkProbe {
	/** Number of bytes that are downloaded for the measurement. */
	private const val PROBE_SIZE = 8 * 1024 * 1024

	private const val TIMEOUT_MS = 20_000

	/** Measurements below this size are not meaningful. */
	private const val MIN_PROBE_BYTES = 1024L * 1024

	private var probed = false

	/**
	 * Runs the measurement once per app start.
	 *
	 * @return The measured throughput in bits per second or null when it failed
	 */
	suspend fun measureOnce(api: ApiClient): Long? {
		if (probed) return null
		probed = true

		return withContext(Dispatchers.IO) {
			runCatching {
				val itemId = findItem(api) ?: return@runCatching null
				download(api, itemId)
			}.onFailure {
				Timber.w(it, "Network probe failed")
			}.getOrNull()
		}
	}

	/**
	 * Builds an url of the server with the access token of this device.
	 */
	private fun buildUrl(api: ApiClient, path: String): String {
		val separator = if (path.contains('?')) "&" else "?"
		return api.createUrl(path, ignorePathParameters = true) +
			separator + "api_key=" + api.deviceInfo.accessToken
	}

	/**
	 * The id of any item of the library, its bytes are used for the measurement.
	 */
	private fun findItem(api: ApiClient): String? {
		val url = buildUrl(api, "/Items/Latest?limit=1")
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.connectTimeout = TIMEOUT_MS
		connection.readTimeout = TIMEOUT_MS

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
	private fun download(api: ApiClient, itemId: String): Long? {
		for (path in listOf("/Videos/$itemId/stream", "/Audio/$itemId/stream")) {
			val url = buildUrl(api, "$path?static=true")
			val result = downloadChunk(url) ?: continue

			Timber.i("Network probe: %d bps", result)
			return result
		}

		return null
	}

	private fun downloadChunk(url: String): Long? {
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.connectTimeout = TIMEOUT_MS
		connection.readTimeout = TIMEOUT_MS
		connection.setRequestProperty("Range", "bytes=0-${PROBE_SIZE - 1}")

		var read = 0L
		val start = SystemClock.elapsedRealtime()

		try {
			connection.inputStream.use { stream ->
				val buffer = ByteArray(64 * 1024)
				while (read < PROBE_SIZE) {
					val count = stream.read(buffer)
					if (count < 0) break
					read += count
				}
			}
		} catch (_: Exception) {
			return null
		} finally {
			connection.disconnect()
		}

		val elapsed = SystemClock.elapsedRealtime() - start
		if (read < MIN_PROBE_BYTES || elapsed <= 0) return null

		return read * 8 * 1000 / elapsed
	}
}
