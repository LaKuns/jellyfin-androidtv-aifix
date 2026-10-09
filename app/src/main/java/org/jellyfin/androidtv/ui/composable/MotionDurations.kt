package org.jellyfin.androidtv.ui.composable

/**
 * Shared animation durations.
 *
 * Durations used to be scattered across screens (100/150/300/400/1000/1500 ms), which made
 * it easy for two parts of the same transition to drift apart. Values below are the ones
 * that already read well; new animations should pick one of these instead of a literal.
 *
 * None of these bypass the system animation scale setting. Animations whose *duration is the
 * meaning* (playback progress, countdowns) must use [FixedMotionDurationScale] instead of
 * picking a value from here.
 */
object MotionDurations {
	/** Focus highlights and other small, frequent state changes. */
	const val Fast: Int = 150

	/** Default enter/exit for surfaces: dialogs, popovers, screens. */
	const val Normal: Int = 300

	/** Navigation between sibling screens, where the direction of travel is the point. */
	const val Slow: Int = 400

	/** Fullscreen fades such as the in-app screensaver. */
	const val VerySlow: Int = 1000
}