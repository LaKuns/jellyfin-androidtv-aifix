package org.jellyfin.androidtv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "org.jellyfin.androidtv"
private const val WAIT_TIMEOUT_MS = 15_000L

private fun device(): UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

/**
 * Generates the baseline profile of the start-up paths: the home screen first, then the
 * playback start of the first card (which is what the P2 work targets).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
	@get:Rule
	val baselineProfileRule = BaselineProfileRule()

	@Test
	fun homeAndPlaybackStart() = baselineProfileRule.collect(packageName = PACKAGE_NAME) {
		pressHome()
		startActivityAndWait()

		val ui = device()
		ui.wait(Until.hasObject(By.pkg(PACKAGE_NAME)), WAIT_TIMEOUT_MS)
		ui.waitForIdle()

		// Let the home rows settle and their artwork load.
		Thread.sleep(4_000)

		// User picker (first start only) then the first card, then the playback start.
		ui.pressDPadCenter()
		Thread.sleep(3_000)
		ui.pressDPadCenter()
		Thread.sleep(8_000)

		ui.pressBack()
		Thread.sleep(1_000)
		ui.pressBack()
		Thread.sleep(1_000)
	}

	/**
	 * Startup profile rules: only the code that runs before the first frame. Generating these
	 * separately keeps the ART start-up compilation small and focused.
	 */
	@Test
	fun startupProfile() = baselineProfileRule.collect(
		packageName = PACKAGE_NAME,
		includeInStartupProfile = true,
	) {
		pressHome()
		startActivityAndWait()

		val ui = device()
		ui.wait(Until.hasObject(By.pkg(PACKAGE_NAME)), WAIT_TIMEOUT_MS)
		ui.waitForIdle()
		Thread.sleep(4_000)
	}
}
