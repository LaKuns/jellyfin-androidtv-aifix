package org.jellyfin.playback.core.backend

/**
 * A backend that renders text subtitles and lets the user turn them on and off.
 *
 * Backends that do not implement this interface show no subtitles at all, so the UI only
 * offers the control when the backend supports it.
 */
interface SubtitleTrackBackend {
	/**
	 * Whether the subtitles of the current item are turned on.
	 */
	fun isSubtitlesEnabled(): Boolean

	/**
	 * Turns the subtitles of the current item on or off.
	 */
	fun setSubtitlesEnabled(enabled: Boolean)
}
