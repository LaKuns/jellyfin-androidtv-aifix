package org.jellyfin.playback.jellyfin.mediasegment

/** What the player should do when a media segment (intro, outro, ...) starts. */
enum class MediaSegmentSkipAction {
	/** Jump over the segment immediately. */
	SKIP,

	/** Ask the viewer with a small on screen button. */
	ASK_TO_SKIP,

	/** Do nothing for this segment type. */
	DISABLED,
}
