package org.jellyfin.androidtv

import android.app.Application
import android.content.Context
import org.jellyfin.androidtv.telemetry.TelemetryService
import org.jellyfin.androidtv.util.StrictModeConfiguration

class JellyfinApplication : Application() {
	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)
		TelemetryService.init(this)
	}

	override fun onCreate() {
		super.onCreate()

		// Debug builds only: catch main-thread disk/network IO on the start-up path.
		StrictModeConfiguration.enableIfDebug(BuildConfig.DEBUG)
	}
}
