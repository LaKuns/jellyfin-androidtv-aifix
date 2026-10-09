package org.jellyfin.androidtv.ui.settings.composable

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.jellyfin.androidtv.ui.composable.MotionDurations
import org.jellyfin.androidtv.ui.navigation.RouterContent
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.util.PerformanceProfile
import org.koin.compose.koinInject

/**
 * Wrapper for the [RouterContent] composable that sets the settings slide in/out transitions.
 */
@Composable
fun SettingsRouterContent() {
	val context = LocalContext.current
	val preferences = koinInject<UserPreferences>()
	val performanceMode by rememberPreference(preferences, UserPreferences.performanceMode)
	val lowPerformanceDevice = remember(context, performanceMode) {
		PerformanceProfile.isLowPerformanceDevice(context)
	}

	if (lowPerformanceDevice) {
		// An animated transition keeps the outgoing and the incoming settings screen
		// composed at the same time for the whole duration. Devices that cannot afford that
		// extra composition work get an instant swap instead.
		val instant = EnterTransition.None togetherWith ExitTransition.None
		RouterContent(
			transitionSpec = { instant },
			popTransitionSpec = { instant },
		)
		return
	}

	val duration = MotionDurations.Slow // milliseconds

	val transitionIn = slideInHorizontally(
		initialOffsetX = { it },
		animationSpec = tween(duration, easing = FastOutSlowInEasing)
	) + fadeIn(
		animationSpec = tween(duration)
	)
	val transitionOut = slideOutHorizontally(
		targetOffsetX = { -it / 3 },
		animationSpec = tween(duration, easing = FastOutSlowInEasing)
	) + fadeOut(
		animationSpec = tween(duration)
	)

	val popIn = slideInHorizontally(
		initialOffsetX = { -it / 3 },
		animationSpec = tween(duration, easing = FastOutSlowInEasing)
	) + fadeIn(
		animationSpec = tween(duration)
	)

	val popOut = slideOutHorizontally(
		targetOffsetX = { it },
		animationSpec = tween(duration, easing = FastOutSlowInEasing)
	) + fadeOut(
		animationSpec = tween(duration)
	)

	RouterContent(
		transitionSpec = { transitionIn togetherWith transitionOut },
		popTransitionSpec = { popIn togetherWith popOut },
	)
}
