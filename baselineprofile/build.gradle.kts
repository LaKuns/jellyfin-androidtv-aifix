plugins {
	// AGP is already on the build classpath through the root project, so the plugin is
	// applied without a version here.
	id("com.android.test")
	alias(libs.plugins.androidx.baselineprofile)
}

android {
	namespace = "org.jellyfin.androidtv.baselineprofile"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		// Profile generation needs a device with API 28+ (the boxes run Android 14).
		minSdk = 28
		targetSdk = libs.versions.android.targetSdk.get().toInt()
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	// The module tests the real application, so it must target the app project.
	targetProjectPath = ":app"

	// The profile generator instruments itself and drives the target application.
	experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
	// Generate the profile on the device that is attached over adb instead of a managed
	// emulator: the boxes used for this fork are physical devices.
	useConnectedDevices = true
	saveInSrc = true
}

dependencies {
	implementation(libs.androidx.benchmark.macro)
	implementation(libs.androidx.test.junit)
	implementation(libs.androidx.test.runner)
	implementation(libs.androidx.test.uiautomator)
}
