package org.jellyfin.playback.core.backend

/**
 * One audio track of the item that is playing, as it is offered to the user.
 */
data class AudioTrack(
	/**
	 * Identifier of the track inside the media that is loaded. It is stable for as long as
	 * the same item is playing and is used to select the track again after a re-selection.
	 */
	val id: String,

	/**
	 * Human readable label, already translated for the device language, for example
	 * "English · E-AC3 · 5.1".
	 */
	val label: String,

	/**
	 * Whether this is the track that is currently playing.
	 */
	val isSelected: Boolean,
)

/**
 * A backend that lets the user pick the audio track of the item that is playing.
 *
 * Backends that do not implement this interface cannot change the audio track while the
 * item is playing, so the UI only offers the choice when the backend supports it.
 */
interface AudioTrackBackend {
	/**
	 * The audio tracks of the current item, in the order they are offered. Empty while the
	 * item has not been prepared yet, or when the item has no audio at all.
	 */
	fun getAudioTracks(): List<AudioTrack>

	/**
	 * Selects the audio track with the given [AudioTrack.id]. Selecting a track that is
	 * already playing does nothing.
	 */
	fun setAudioTrack(id: String)
}
