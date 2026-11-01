package org.jellyfin.androidtv.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.playback.core.support.PlaybackThroughput
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber

/**
 * Keeps the measurements of the connection up to date.
 *
 * The speed and the latency of the connection belong to the connection they
 * were measured on. A device that is moved from wifi to a cable keeps the
 * measurements of the wifi connection otherwise, and the requested bitrate
 * stays at the value of the slower connection. The server scales the video
 * down as soon as the request is below the bitrate of the source, so an
 * outdated measurement costs the resolution of the film.
 *
 * The monitor lives in the process and not in an activity: the connection can
 * change while the application is in the background, and the measurements have
 * to be there when the application is used again.
 */
object ConnectionMonitor {
	/**
	 * Time the connection is given to settle before it is measured.
	 *
	 * A connection that has just become available is not usable yet: the address
	 * is assigned a moment later and a request would fail.
	 */
	private const val SETTLE_DELAY_MS = 3_000L

	/** Number of attempts to measure a connection. */
	private const val ATTEMPTS = 2

	/** Delay between two attempts. */
	private const val RETRY_DELAY_MS = 2_500L

	/**
	 * Age after which the measurement of a connection is repeated.
	 *
	 * A measurement of the same connection is not repeated before that time: the
	 * measurement downloads several megabytes, and the speed of a connection does
	 * not change from one moment to the next.
	 */
	private const val MAX_AGE_MS = 10 * 60 * 1000L

	/**
	 * Scope of the monitor.
	 *
	 * The monitor belongs to the process, so the measurements outlive the
	 * activity that started them.
	 */
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	/**
	 * Lock of the measurement.
	 *
	 * The connection is measured when the application starts and again when it
	 * changes. Both measurements would download the same data at the same time,
	 * so only one of them runs.
	 */
	private val measurementLock = Mutex()

	private var started = false

	/**
	 * Starts the monitor and measures the connection that is in use.
	 *
	 * @param context Used to read and store the measurements
	 * @param api Used to download the data of the measurement
	 * @return The measurement of the current connection
	 */
	fun start(context: Context, api: ApiClient): Job {
		val appContext = context.applicationContext
		val measurement = scope.launch { measure(appContext, api) }

		if (started) return measurement
		started = true

		val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java) ?: return measurement

		val callback = object : ConnectivityManager.NetworkCallback() {
			override fun onAvailable(network: Network) {
				Timber.i("Connection changed, measuring it again")

				scope.launch {
					// The connection needs a moment before it can carry a request.
					delay(SETTLE_DELAY_MS)

					updateActiveNetwork(appContext)
					measure(appContext, api)
				}
			}
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			connectivityManager.registerDefaultNetworkCallback(callback)
		} else {
			val request = NetworkRequest.Builder()
				.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
				.build()
			connectivityManager.registerNetworkCallback(request, callback)
		}

		return measurement
	}

	/**
	 * Stores the identity of the connection that is currently used.
	 *
	 * The measurements of another connection are worthless as soon as the device
	 * uses a different one, so they are ignored from this moment on.
	 */
	private fun updateActiveNetwork(context: Context) {
		val identity = NetworkIdentity.current(context)
		if (identity.isEmpty()) return

		PlaybackThroughput.setActiveNetwork(context, identity)
	}

	/**
	 * Measures the connection, unless it was measured already.
	 *
	 * A failed measurement is not fatal: the connection may not be usable yet.
	 * The measurements of the previous connection are not used in that case
	 * either, because they describe a connection that is not in use anymore.
	 */
	private suspend fun measure(context: Context, api: ApiClient) = measurementLock.withLock {
		val identity = NetworkIdentity.current(context)
		if (identity.isEmpty()) return@withLock

		// The connection was measured already and the measurement is still valid.
		if (isMeasurementValid(context, identity)) return@withLock

		updateActiveNetwork(context)

		for (attempt in 1..ATTEMPTS) {
			val measurement = NetworkProbe.measure(api)
			if (measurement != null) {
				PlaybackThroughput.recordLinkBitrate(context, measurement.bitrate, identity)
				PlaybackThroughput.recordLinkLatency(context, measurement.latencyMs)
				return@withLock
			}

			Timber.w("Network probe failed (attempt %d of %d)", attempt, ATTEMPTS)

			if (attempt < ATTEMPTS) delay(RETRY_DELAY_MS)
		}
	}

	/**
	 * Checks whether the connection was measured recently.
	 *
	 * A measurement of the same connection is only repeated when it is old. The
	 * speed of a wireless connection drops now and then, and a measurement that
	 * is hours old does not describe it anymore.
	 */
	private fun isMeasurementValid(context: Context, identity: String): Boolean {
		if (PlaybackThroughput.getLinkNetwork(context) != identity) return false

		val measuredAt = PlaybackThroughput.getLinkTime(context)
		if (measuredAt <= 0) return false

		return System.currentTimeMillis() - measuredAt < MAX_AGE_MS
	}
}
