package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

/**
 * Controls the amount of work used by browsing screens and card presenters.
 * AUTO keeps the device based heuristic, while LOW is useful for older TVs
 * whose CPU/GPU is weak but whose reported memory looks normal.
 */
enum class PerformanceMode(
	override val nameRes: Int,
) : PreferenceEnum {
	AUTO(R.string.performance_mode_auto),
	LOW(R.string.performance_mode_low),
	FULL(R.string.performance_mode_full),
}
