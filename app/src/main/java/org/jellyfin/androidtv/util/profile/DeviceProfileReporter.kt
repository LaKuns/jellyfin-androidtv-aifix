package org.jellyfin.androidtv.util.profile

import android.content.Context
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
			val report = createDeviceProfileReport(context, userPreferences, serverVersion)
			api.clientLogApi.logFile(report)
		}.onSuccess {
			Timber.i("Reported the device capabilities to the server")
		}.onFailure {
			Timber.w(it, "Unable to report the device capabilities to the server")
		}
	}
}
