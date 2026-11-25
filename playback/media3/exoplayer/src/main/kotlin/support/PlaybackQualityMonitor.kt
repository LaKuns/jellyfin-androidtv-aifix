package org.jellyfin.playback.media3.exoplayer.support

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import timber.log.Timber
import java.util.Locale

/**
 * Measures the playback quality that the user can actually see: the offset between the video frame
 * that is about to be rendered and the audio clock of the player, the frames that the renderer had to
 * drop and the times the audio sink ran dry.
 *
 * This is the port of the old player's `PlaybackQualityMonitor`. The log line is deliberately
 * identical (`Quality: avOffset ... dropped=... underruns=...`), so one gate script can read the
 * numbers of both players.
 *
 * Video follows the audio clock in ExoPlayer, so a positive offset means the video is ahead of the
 * sound and a negative one means it lags. A stable offset of a few dozen milliseconds is normal (the
 * renderer compensates the output latency of the display); a growing offset or a large spread means
 * the two clocks are drifting apart.
 *
 * The player can only be read from the thread that created it, so the frame callback only records
 * the presentation time of the frame and the wall clock of the device. The offset is calculated by
 * the report, which runs on the main thread: it takes the current position of the player and moves it
 * back by the time that passed since the frame was handed to the renderer, which is the media time the
 * audio clock was showing when that frame was rendered.
 */
@UnstableApi
class PlaybackQualityMonitor(
	private val player: ExoPlayer,
) {
	private companion object {
		/** How often the collected numbers are written to the log. */
		const val REPORT_INTERVAL_MS = 2_000L

		/**
		 * The first frames of a stream are handed to the renderer before the audio clock is stable,
		 * which produces one large offset that never repeats. The offset is therefore only collected
		 * after this startup window, so that the numbers describe the steady state the viewer sees.
		 */
		const val STARTUP_SETTLE_MS = 4_000L
	}

	private val handler = Handler(Looper.getMainLooper())

	// The offset is also reported for the last report interval, which shows a drift that a
	// cumulative average hides.
	private var windowSamples = 0L
	private var windowSumOffsetUs = 0L

	private var droppedFrames = 0
	private var audioUnderruns = 0
	private var frames = 0L
	private var samples = 0L
	private var sumAbsOffsetUs = 0L
	private var maxAbsOffsetUs = 0L
	private var maxOffsetUs = Long.MIN_VALUE
	private var minOffsetUs = Long.MAX_VALUE

	// The player reports these numbers on its own playback thread.
	@Volatile
	private var pendingDroppedFrames = 0

	@Volatile
	private var pendingAudioUnderruns = 0

	// Written by the frame callback of the renderer thread, read by the report on the main thread.
	@Volatile
	private var lastPresentationTimeUs = -1L

	@Volatile
	private var lastFrameWallClockMs = -1L

	@Volatile
	private var firstFrameWallClockMs = -1L

	/** Wall clock (elapsed realtime) at which the renderer says the frame will be released to the display. */
	@Volatile
	private var lastReleaseWallClockMs = -1L

	private val analyticsListener = object : AnalyticsListener {
		override fun onDroppedVideoFrames(
			eventTime: AnalyticsListener.EventTime,
			droppedFrames: Int,
			elapsedMs: Long,
		) {
			pendingDroppedFrames += droppedFrames
		}

		override fun onAudioUnderrun(
			eventTime: AnalyticsListener.EventTime,
			bufferSize: Int,
			bufferSizeMs: Long,
			elapsedSinceLastFeedMs: Long,
		) {
			pendingAudioUnderruns++
		}
	}

	private val videoFrameMetadataListener = VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _: Format, _ ->
		// Never read the player here: it belongs to the thread that created it.
		frames++
		val nowMs = SystemClock.elapsedRealtime()
		val nowNs = System.nanoTime()
		lastPresentationTimeUs = presentationTimeUs
		lastFrameWallClockMs = nowMs
		// releaseTimeNs is in the nanoTime domain; convert it to the elapsed realtime domain so
		// that the report can compare the frame against the audio clock at the moment the display
		// receives it.
		lastReleaseWallClockMs = nowMs + (releaseTimeNs - nowNs) / 1_000_000L
		if (firstFrameWallClockMs < 0) firstFrameWallClockMs = nowMs
	}

	private val report = object : Runnable {
		override fun run() {
			reportNow()
			handler.postDelayed(this, REPORT_INTERVAL_MS)
		}
	}

	init {
		player.addAnalyticsListener(analyticsListener)
		player.setVideoFrameMetadataListener(videoFrameMetadataListener)
		handler.postDelayed(report, REPORT_INTERVAL_MS)
	}

	/** Writes the numbers collected so far. */
	@Synchronized
	fun reportNow() {
		droppedFrames += pendingDroppedFrames
		audioUnderruns += pendingAudioUnderruns
		pendingDroppedFrames = 0
		pendingAudioUnderruns = 0

		val presentationTimeUs = lastPresentationTimeUs
		val frameWallClockMs = lastFrameWallClockMs
		// The display receives the frame at the release time, not at the callback time.
		val referenceWallClockMs = if (lastReleaseWallClockMs > 0) lastReleaseWallClockMs else frameWallClockMs
		if (presentationTimeUs >= 0 && referenceWallClockMs >= 0 && firstFrameWallClockMs >= 0 &&
			SystemClock.elapsedRealtime() - firstFrameWallClockMs >= STARTUP_SETTLE_MS &&
			player.playbackState == Player.STATE_READY
		) {
			val nowMs = SystemClock.elapsedRealtime()
			val positionNowMs = player.currentPosition
			val audioClockAtFrameMs = positionNowMs - (nowMs - referenceWallClockMs)
			val offsetUs = presentationTimeUs - audioClockAtFrameMs * 1_000L

			samples++
			sumAbsOffsetUs += kotlin.math.abs(offsetUs)
			maxAbsOffsetUs = maxOf(maxAbsOffsetUs, kotlin.math.abs(offsetUs))
			maxOffsetUs = maxOf(maxOffsetUs, offsetUs)
			minOffsetUs = minOf(minOffsetUs, offsetUs)

			windowSamples++
			windowSumOffsetUs += offsetUs
		}

		val averageAbsUs = if (samples == 0L) 0L else sumAbsOffsetUs / samples
		val windowAverageUs = if (windowSamples == 0L) 0L else windowSumOffsetUs / windowSamples
		windowSamples = 0
		windowSumOffsetUs = 0
		Timber.i(
			String.format(
				Locale.ROOT,
				"Quality: avOffset avg=%dms win=%dms max=%dms range=[%dms,%dms] frames=%d dropped=%d underruns=%d",
				averageAbsUs / 1_000,
				windowAverageUs / 1_000,
				maxAbsOffsetUs / 1_000,
				if (minOffsetUs == Long.MAX_VALUE) 0 else minOffsetUs / 1_000,
				if (maxOffsetUs == Long.MIN_VALUE) 0 else maxOffsetUs / 1_000,
				frames,
				droppedFrames,
				audioUnderruns,
			),
		)
	}

	/** Forgets everything collected so far, called when another item starts. */
	@Synchronized
	fun reset() {
		droppedFrames = 0
		audioUnderruns = 0
		pendingDroppedFrames = 0
		pendingAudioUnderruns = 0
		frames = 0
		samples = 0
		sumAbsOffsetUs = 0
		maxAbsOffsetUs = 0
		maxOffsetUs = Long.MIN_VALUE
		minOffsetUs = Long.MAX_VALUE
		lastPresentationTimeUs = -1
		lastFrameWallClockMs = -1
		lastReleaseWallClockMs = -1
		firstFrameWallClockMs = -1
		windowSamples = 0
		windowSumOffsetUs = 0
	}

	fun release() {
		handler.removeCallbacks(report)
		reportNow()
		player.clearVideoFrameMetadataListener(videoFrameMetadataListener)
		player.removeAnalyticsListener(analyticsListener)
	}
}
