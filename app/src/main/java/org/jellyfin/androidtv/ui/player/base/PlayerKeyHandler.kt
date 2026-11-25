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
 * it is shown); everything else is left to the normal focus navigation.
 */
object PlayerKeyHandler {
	@Volatile
	private var handler: ((Int) -> Boolean)? = null

	fun setHandler(handler: ((Int) -> Boolean)?) {
		this.handler = handler
		Timber.i("Player key handler %s", if (handler == null) "cleared" else "registered")
	}

	fun dispatch(keyCode: Int): Boolean = handler?.invoke(keyCode) == true
}
