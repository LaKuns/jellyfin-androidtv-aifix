package org.jellyfin.androidtv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline profile for the start-up and first-screen critical paths.
 *
 * The journeys are deliberately small: the profile is only useful when it covers what the
 * application really executes during start-up, on the home screen and when a playback is
 * started, so this test drives exactly those paths and nothing else.
 *
 * Run with `./gradlew :baselineprofile:generateBaselineProfile` while a device is attached.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
	@get:Rule
	val baselineProfileRule = BaselineProfileRule()

	@Test
	fun homeAndPlaybackStart() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
		pressHome()
		startActivityAndWait()

		// The home screen loads its rows and images from the server, let it settle.
		device.wait(Until.hasObject(By.pkg(PACKAGE_NAME)), WAIT_TIMEOUT_MS)
		device.waitForIdle()
		Thread.sleep(4_000)

		// Open the focused item (the home screen focuses the first card on its own).
		device.pressDPadCenter()
		device.waitForIdle()
		Thread.sleep(3_000)

		// Start the playback from the detail page, then let it run for the profile.
		device.pressDPadCenter()
		device.waitForIdle()
		Thread.sleep(8_000)

		// Leave the player and the detail page again so the journey is repeatable.
		device.pressBack()
		Thread.sleep(1_000)
		device.pressBack()
		Thread.sleep(1_000)
	}

	private companion object {
		const val PACKAGE_NAME = "org.jellyfin.androidtv"
		const val WAIT_TIMEOUT_MS = 15_000L
	}
}
