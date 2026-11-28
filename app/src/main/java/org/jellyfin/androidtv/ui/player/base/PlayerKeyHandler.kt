package org.jellyfin.androidtv.ui.player.base

import timber.log.Timber

/**
 * Keys of the player, delivered by the activity before any focus based handling.
 *
 * The overlay hides its control bar after a few seconds of playback. The Compose focus then
 * belongs to whatever node the tree kept, and with no focusable node left every press of the
 * remote was dropped: the controls could not be brought back and the player could not be paused.
 *
 * The activity therefore asks this handler first. The player registers it while it is on screen
 * and owns the keys that are meant to wake the overlay (any key while it is hidden, back while
 * it is shown, the direct seek of the left and right keys); everything else is left to the
 * normal focus navigation.
 */
object PlayerKeyHandler {
	typealias Handler = (keyCode: Int, repeatCount: Int) -> Boolean

	/** [repeatCount] is the auto-repeat counter of the key event, used to accelerate a seek. */
	@Volatile
	private var handler: Handler? = null

	/** Identifies one registration, so an old player composition can never clear a newer one. */
	class Registration internal constructor(internal val handler: Handler)

	fun register(handler: Handler): Registration {
		this.handler = handler
		Timber.i("Player key handler registered")
		return Registration(handler)
	}

	fun unregister(registration: Registration) {
		// A late dispose of an old player must not remove the handler of the player that replaced
		// it; this is what made the remote unresponsive after leaving and re-entering playback.
		if (handler === registration.handler) {
			handler = null
			Timber.i("Player key handler cleared")
		}
	}

	fun dispatch(keyCode: Int, repeatCount: Int): Boolean = handler?.invoke(keyCode, repeatCount) == true
}
