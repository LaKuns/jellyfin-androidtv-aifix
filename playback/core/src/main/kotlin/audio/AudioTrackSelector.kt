package org.jellyfin.playback.core.audio

/**
 * One audio track of the media that is loaded, reduced to what the default selection needs to
 * know about it.
 */
data class AudioTrackCandidate(
	/** Stable identifier of the track inside the loaded media. */
	val id: String,
	/** ISO language of the track, for example `zho`. Null when the media does not carry one. */
	val language: String?,
	/** Whether the server marks the track as the default of the file. */
	val isDefault: Boolean,
	/** Whether the player has a renderer for this track. */
	val isSupported: Boolean,
)

/**
 * Picks the audio track that is played while the user has not picked one.
 *
 * The old player had this chain and the new player did not, which left the choice to the media3
 * track selector: a file whose first default track is a format the box can not play started
 * silent, and the user had to walk through the tracks until one worked.
 *
 * The chain is, in order:
 *
 * 1. the language the user picked for an earlier item (their intent outranks the file),
 * 2. Chinese when the device is set to Chinese, because this library is mostly Chinese media,
 * 3. the track the server marks as the default of the file,
 * 4. the first track the player can actually render,
 * 5. the server default, then the first track - only reached when nothing is supported and the
 *    player is expected to fail anyway.
 */
object AudioTrackSelector {
	/**
	 * The codes a Chinese track may carry. The names follow the ISO 639-2/B codes of the server
	 * (`zho` for Chinese, `yue` for Cantonese) plus the codes some files use directly.
	 */
	private val CHINESE_LANGUAGES = setOf("zho", "chi", "cmn", "zh", "chs", "cht", "yue")

	/**
	 * @param candidates the audio tracks of the loaded media, in file order.
	 * @param preferredLanguage language the user picked for an earlier item.
	 * @param deviceLanguage language of the device, for example `zh_CN`.
	 */
	fun choose(
		candidates: List<AudioTrackCandidate>,
		preferredLanguage: String? = null,
		deviceLanguage: String? = null,
	): AudioTrackCandidate? {
		if (candidates.isEmpty()) return null

		preferredLanguage
			?.takeIf { it.isNotBlank() }
			?.let { language ->
				candidates.firstOrNull { track -> track.language.equals(language, ignoreCase = true) }
					?.let { track -> return track }
			}

		if (deviceLanguage?.lowercase()?.startsWith("zh") == true) {
			candidates.firstOrNull { track -> track.language?.lowercase() in CHINESE_LANGUAGES && track.isSupported }
				?.let { track -> return track }
		}

		candidates.firstOrNull { track -> track.isDefault && track.isSupported }
			?.let { track -> return track }

		candidates.firstOrNull { track -> track.isSupported }
			?.let { track -> return track }

		return candidates.firstOrNull { track -> track.isDefault } ?: candidates.first()
	}
}
