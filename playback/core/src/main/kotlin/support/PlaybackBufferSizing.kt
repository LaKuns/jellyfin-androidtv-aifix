package org.jellyfin.playback.core.support

/**
 * Sizes the buffer of the player so it fits inside the heap of the device.
 *
 * media3 derives its default target buffer from the selected track types: a single video
 * track asks for 2000 segments and a segment is 64 KiB, so the player is allowed to keep
 * roughly 128 MiB of compressed video in memory. A TV box has a heap of 128-256 MiB, so the
 * player fills the heap with several minutes of the stream and the app dies with an
 * OutOfMemoryError.
 *
 * A quarter of the heap is enough for about 30-50 seconds of a normal 3-8 Mbit/s stream and
 * leaves the rest of the heap to the rest of the app.
 */
object PlaybackBufferSizing {
	private const val MINIMUM_BYTES = 16L * 1024 * 1024
	private const val MAXIMUM_BYTES = 48L * 1024 * 1024
	private const val HEAP_FRACTION = 4

	/** Returns the amount of media data the player is allowed to hold in memory. */
	@JvmStatic
	fun targetBufferBytes(): Int {
		val target = Runtime.getRuntime().maxMemory() / HEAP_FRACTION
		return target.coerceIn(MINIMUM_BYTES, MAXIMUM_BYTES).toInt()
	}
}