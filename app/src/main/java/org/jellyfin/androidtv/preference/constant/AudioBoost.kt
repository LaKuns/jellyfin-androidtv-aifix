package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

/**
 * How much the audio that the device decodes itself is raised.
 *
 * A track that the server transcodes is decoded to the level of its source, while a television
 * that decodes the same track from a bitstream applies its own level on top of it, which makes
 * the decoded audio sound quieter at the same volume position. The steps are offered so the
 * level can be matched to the television instead of being fixed by the application.
 *
 * @param millibels The gain in millibels, zero for no gain at all
 */
enum class AudioBoost(
	override val nameRes: Int,
	val millibels: Int,
) : PreferenceEnum {
	/** No gain, the decoded audio keeps the level of its source. */
	OFF(R.string.pref_audio_boost_off, 0),

	/** Two decibels, a subtle correction. */
	PLUS_2(R.string.pref_audio_boost_2, 2_000),

	/** Four decibels, enough for most televisions and free of any processing. */
	PLUS_4(R.string.pref_audio_boost_4, 4_000),

	/** Six decibels, the loudest moments are limited. */
	PLUS_6(R.string.pref_audio_boost_6, 6_000),

	/** Eight decibels, for a television that applies a lot of its own level. */
	PLUS_8(R.string.pref_audio_boost_8, 8_000),
}
