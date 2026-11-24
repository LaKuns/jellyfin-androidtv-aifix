package org.jellyfin.androidtv.util

import android.os.StrictMode

/**
 * StrictMode is only enabled for debug builds: it exists to catch main-thread disk or network
 * accesses while developing (the start-up path must stay free of them), and must never be
 * active in a release build where the logging penalty only costs performance.
 */
object StrictModeConfiguration {
	fun enableIfDebug(debug: Boolean) {
		if (!debug) return

		StrictMode.setThreadPolicy(
			StrictMode.ThreadPolicy.Builder()
				.detectDiskReads()
				.detectDiskWrites()
				.detectNetwork()
				.detectCustomSlowCalls()
				.penaltyLog()
				.build()
		)

		StrictMode.setVmPolicy(
			StrictMode.VmPolicy.Builder()
				.detectLeakedClosableObjects()
				.detectLeakedRegistrationObjects()
				.penaltyLog()
				.build()
		)
	}
}
