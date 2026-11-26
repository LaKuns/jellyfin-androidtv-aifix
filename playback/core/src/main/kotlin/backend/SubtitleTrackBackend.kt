package org.jellyfin.playback.core.backend

/**
 * One subtitle track of the item that is playing, as it is offered to the user.
 */
data class SubtitleTrack(
	/**
	 * Identifier of the track inside the media that is loaded. It is stable for as long as
	 * the same item is playing and is used to select the track again after a re-selection.
	 */
	val id: String,

	/**
	 * Human readable label, already translated for the device language, for example
	 * "English · SSA" or "简体中文 · SSA".
	 */
	val label: String,

	/**
	 * ISO language of the track, for example `zho`. Null when the media does not carry one.
	 */
	val language: String?,

	/**
	 * Whether this is the track that is currently shown.
	 */
	val isSelected: Boolean,
)

/**
 * A backend that renders text subtitles and lets the user pick the track and turn the subtitles
 * on and off.
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

	/**
	 * The subtitle tracks of the current item, in the order they are offered. Empty while the
	 * item has not been prepared yet, or when the item has no text subtitles at all.
	 */
	fun getSubtitleTracks(): List<SubtitleTrack>

	/**
	 * Selects the subtitle track with the given [SubtitleTrack.id] and turns the subtitles on.
	 */
	fun setSubtitleTrack(id: String)
}
