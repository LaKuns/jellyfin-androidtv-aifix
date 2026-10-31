package org.jellyfin.androidtv.ui.settings.composable

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.util.profile.createDeviceProfileReport
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import org.jellyfin.sdk.model.ServerVersion
import org.koin.compose.koinInject

/**
 * Sends the device capabilities report to the server.
 *
 * The report contains the full decoder list and the generated device profile, which is
 * the only reliable way to find out what the client tells the server.
 */
@Composable
fun ReportDeviceProfileButton(modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val api = koinInject<ApiClient>()
	val userPreferences = koinInject<UserPreferences>()
	val serverVersion = koinInject<ServerVersion>()

	SettingsAsyncActionListButton(
		headingContent = { Text(stringResource(R.string.pref_report_device_profile_title)) },
		captionContent = { Text(stringResource(R.string.pref_report_device_profile_summary)) },
		action = {
			val report = createDeviceProfileReport(context, userPreferences, serverVersion)
			val response by api.clientLogApi.logFile(report)
			response
		},
		onSuccess = { result ->
			Toast.makeText(
				context,
				@SuppressLint("LocalContextGetResourceValueCall")
				context.getString(R.string.pref_report_device_profile_success, result.fileName),
				Toast.LENGTH_LONG
			).show()
		},
		onFailure = {
			Toast.makeText(context, R.string.pref_report_device_profile_failure, Toast.LENGTH_LONG).show()
		},
		modifier = modifier.focusKey("report_device_profile"),
	)
}
