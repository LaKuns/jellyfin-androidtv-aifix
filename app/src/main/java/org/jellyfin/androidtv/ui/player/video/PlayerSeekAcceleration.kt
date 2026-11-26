package org.jellyfin.androidtv.ui.player.video

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How far a left or right key press jumps when the control bar is hidden.
 *
 * The first presses use the amount from the settings, so a short press moves exactly what the
 * user configured. Holding the key repeats the press, and the step grows with the repeat
 * counter ([android.view.KeyEvent.getRepeatCount]): from the fourth repeat it triples and from
 * the ninth it grows to six times the configured amount, so a long seek does not need dozens of
 * presses. One press never skips more than a minute (unless the configured amount is larger).
 */
internal fun seekAcceleration(baseAmount: Duration, repeatCount: Int): Duration {
	val multiplier = when {
		repeatCount < 4 -> 1
		repeatCount < 9 -> 3
		else -> 6
	}

	return (baseAmount * multiplier).coerceAtMost(maxOf(60.seconds, baseAmount))
}
