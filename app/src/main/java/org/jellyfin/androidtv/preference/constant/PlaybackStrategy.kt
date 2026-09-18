package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

/** Selects how the client balances local decoding against server work. */
enum class PlaybackStrategy(
	override val nameRes: Int,
) : PreferenceEnum {
	AUTO(R.string.playback_strategy_auto),
	PREFER_DIRECT(R.string.playback_strategy_direct),
	PREFER_SERVER_TRANSCODING(R.string.playback_strategy_server),
}
