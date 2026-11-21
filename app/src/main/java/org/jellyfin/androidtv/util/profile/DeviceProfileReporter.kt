package org.jellyfin.androidtv.util.profile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import org.jellyfin.sdk.model.ServerVersion
import timber.log.Timber

/**
 * Sends the device capabilities report to the server.
 *
 * The report contains the full decoder list and the generated device profile, which is
 * the only reliable way to find out what the client tells the server. Reporting it
 * automatically means the information is available in the server log without having to
 * find the matching entry in the settings.
 */
object DeviceProfileReporter {
	private var reported = false

	suspend fun reportOnce(
		context: Context,
		api: ApiClient,
		userPreferences: UserPreferences,
		serverVersion: ServerVersion,
	) {
		if (reported) return
		reported = true

		runCatching {
			// Building the report probes the decoders, which can take a moment on the first
			// run. It must not block the caller, which is the main thread at app start.
			val report = withContext(Dispatchers.IO) {
				createDeviceProfileReport(context, userPreferences, serverVersion)
			}
			api.clientLogApi.logFile(report)
		}.onSuccess {
			Timber.i("Reported the device capabilities to the server")
		}.onFailure {
			Timber.w(it, "Unable to report the device capabilities to the server")
		}
	}
}
