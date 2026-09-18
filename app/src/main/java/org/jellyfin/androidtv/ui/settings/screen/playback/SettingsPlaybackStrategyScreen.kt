package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.PlaybackStrategy
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsPlaybackStrategyScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	var playbackStrategy by rememberPreference(userPreferences, UserPreferences.playbackStrategy)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback_advanced).uppercase()) },
				headingContent = { Text(stringResource(R.string.playback_strategy_title)) },
				captionContent = { Text(stringResource(R.string.playback_strategy_description)) },
			)
		}

		items(PlaybackStrategy.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				trailingContent = { RadioButton(checked = playbackStrategy == entry) },
				onClick = {
					playbackStrategy = entry
					router.back()
				},
				modifier = Modifier.focusKey("playback_strategy_${entry.name}", initialFocus = playbackStrategy == entry),
			)
		}
	}
}
