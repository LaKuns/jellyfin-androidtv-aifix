package org.jellyfin.androidtv.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.preference.constant.BufferLength
import org.jellyfin.androidtv.ui.browsing.MainActivity
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction as AppMediaSegmentAction
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.androidtv.util.PerformanceProfile
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.playback.core.playbackManager
import org.jellyfin.playback.core.support.PlaybackThroughput
import org.jellyfin.playback.jellyfin.jellyfinPlugin
import org.jellyfin.playback.media3.exoplayer.ExoPlayerOptions
import org.jellyfin.playback.media3.exoplayer.exoPlayerPlugin
import org.jellyfin.playback.media3.session.MediaSessionOptions
import org.jellyfin.playback.media3.session.media3SessionPlugin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.jellyfin.androidtv.ui.playback.PlaybackManager as LegacyPlaybackManager
import org.jellyfin.playback.jellyfin.mediasegment.MediaSegmentSkipAction

val playbackModule = module {
	single { LegacyPlaybackManager(get()) }
	single { VideoQueueManager() }
	single<MediaManager> { RewriteMediaManager(get(), get()) }

	single { PlaybackLauncher(get(), get(), get(), get()) }

	single<HttpDataSource.Factory> {
		val okHttpFactory = get<OkHttpFactory>()
		val httpClientOptions = get<HttpClientOptions>().copy(
			// Disable request timeout for media playback as this causes issues with Live TV
			requestTimeout = Duration.ZERO
		)

		OkHttpDataSource.Factory(okHttpFactory.createClient(httpClientOptions))
	}

	single { createPlaybackManager() }
}

fun Scope.createPlaybackManager() = playbackManager(androidContext()) {
	val activityIntent = Intent(get(), MainActivity::class.java)
	val pendingIntent = PendingIntent.getActivity(get(), 0, activityIntent, PendingIntent.FLAG_IMMUTABLE)

	val notificationChannelId = "session"
	if (AndroidVersion.isAtLeastO) {
		val channel = NotificationChannel(
			notificationChannelId,
			notificationChannelId,
			NotificationManager.IMPORTANCE_LOW
		)
		channel.setShowBadge(false)
		NotificationManagerCompat.from(get()).createNotificationChannel(channel)
	}

	val userPreferences = get<UserPreferences>()
	val lowPerformanceDevice = PerformanceProfile.isLowPerformanceDevice(androidContext())
	val bufferLength = userPreferences[UserPreferences.bufferLength]
	// The player starts as soon as it has the start buffer of the stream. A fast
	// connection delivers the first seconds quickly, so the picture appears sooner
	// without risking a stall right at the beginning. A buffer length that the
	// user picked always wins over that.
	val linkBitrate = PlaybackThroughput.getLinkSafeBitrate(androidContext())
	val userStartBuffer = bufferLength.bufferForPlaybackDuration
	// A wireless connection stutters now and then: a retransmission or a busy
	// channel delays a segment for a moment, and a thin buffer runs dry right
	// away when that happens. A cable carries the stream without interruptions,
	// so a thin buffer is enough there. A connection that is unknown is treated
	// like a wireless one, because a buffer that is too generous only costs a
	// moment at the start.
	val activeNetwork = PlaybackThroughput.getActiveNetwork(androidContext())
	val wireless = activeNetwork.isEmpty() || activeNetwork.startsWith("WIFI:")
	val startBufferDuration: Duration? = when {
		userStartBuffer != null -> userStartBuffer
		wireless -> 2.5.seconds
		linkBitrate >= PerformanceProfile.FAST_CONNECTION_BITRATE -> 1.5.seconds
		else -> 2.5.seconds
	}

	// The smaller buffers protect a device that cannot afford a large one. A
	// connection that is fast enough does not need that protection: the buffer
	// exists to survive a stall of the network, and a fast network stalls rarely.
	// Shrinking it would only make the player fill it again more often.
	val conservativeBuffer = lowPerformanceDevice &&
		linkBitrate < PerformanceProfile.FAST_CONNECTION_BITRATE

	val exoPlayerOptions = ExoPlayerOptions(
		preferFfmpeg = userPreferences[UserPreferences.preferExoPlayerFfmpeg],
		enableLibass = userPreferences[UserPreferences.assDirectPlay],
		enableDebugLogging = userPreferences[UserPreferences.debuggingEnabled],
		// The direct audio path of this device's HAL reports the wrong position, so the
		// player decodes compressed audio instead of handing it to the HAL as a bitstream.
		audioOffload = userPreferences[UserPreferences.audioOffload],
		// Keep the decoder fallback enabled: it is the only way some TV chips can play
		// streams their hardware decoder rejects (e.g. multichannel AAC). Disabling it
		// turns a playable stream into a hard playback error.
		enableDecoderFallback = true,
		baseDataSourceFactory = get<HttpDataSource.Factory>(),
		minBufferDuration = if (conservativeBuffer) 15.seconds else bufferLength.minBufferDuration,
		maxBufferDuration = if (conservativeBuffer) 30.seconds else bufferLength.maxBufferDuration,
		bufferForPlaybackDuration = startBufferDuration,
		bufferForPlaybackAfterRebufferDuration = if (conservativeBuffer) 5.seconds else bufferLength.bufferForPlaybackAfterRebufferDuration,
		// The gain is read when a playback starts, the same way the old player reads it.
		audioGain = { userPreferences[UserPreferences.audioBoost].millibels },
		// The same subtitle style the old player applies. The background is left transparent:
		// the styles of the library put a black box behind some subtitles, which hides the
		// picture; the user only wants the text itself.
		subtitleTextColor = userPreferences[UserPreferences.subtitlesTextColor].toInt(),
		subtitleTextSize = userPreferences[UserPreferences.subtitlesTextSize],
		subtitleBottomPaddingFraction = userPreferences[UserPreferences.subtitlesOffsetPosition],
		subtitleStrokeColor = userPreferences[UserPreferences.subtitleTextStrokeColor].toInt(),
	)
	install(exoPlayerPlugin(get(), exoPlayerOptions))

	val mediaSessionOptions = MediaSessionOptions(
		channelId = notificationChannelId,
		notificationId = 1,
		iconSmall = R.drawable.app_icon_foreground,
		openIntent = pendingIntent,
	)
	install(media3SessionPlugin(get(), mediaSessionOptions))

	val deviceProfileBuilder = { forceHevcTranscode: Boolean -> createDeviceProfile(androidContext(), userPreferences, get(), forceHevcTranscode = forceHevcTranscode) }
	val mediaSegmentActionProvider: (MediaSegmentType) -> MediaSegmentSkipAction = { type ->
		when (get<MediaSegmentRepository>().getDefaultSegmentTypeAction(type)) {
			AppMediaSegmentAction.SKIP -> MediaSegmentSkipAction.SKIP
			AppMediaSegmentAction.NOTHING -> MediaSegmentSkipAction.DISABLED
			AppMediaSegmentAction.ASK_TO_SKIP -> MediaSegmentSkipAction.ASK_TO_SKIP
		}
	}
	install(
		jellyfinPlugin(
			get(),
			deviceProfileBuilder,
			setOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO),
			mediaSegmentActionProvider,
			ProcessLifecycleOwner.get().lifecycle,
		)
	)

	// Options
	val userSettingPreferences = get<UserSettingPreferences>()
	defaultRewindAmount = { userSettingPreferences[UserSettingPreferences.skipBackLength].milliseconds }
	defaultFastForwardAmount = { userSettingPreferences[UserSettingPreferences.skipForwardLength].milliseconds }
}
