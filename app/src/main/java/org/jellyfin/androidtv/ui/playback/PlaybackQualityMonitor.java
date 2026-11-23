package org.jellyfin.androidtv.ui.playback;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;

import java.util.Locale;

import timber.log.Timber;

/**
 * Measures the playback quality that the user can actually see: the offset between the video frame
 * that is about to be rendered and the audio clock of the player, the frames that the renderer had to
 * drop and the times the audio sink ran dry.
 *
 * <p>Video follows the audio clock in ExoPlayer, so a positive offset means the video is ahead of the
 * sound and a negative one means it lags. A stable offset of a few dozen milliseconds is normal (the
 * renderer compensates the output latency of the display); a growing offset or a large spread means
 * the two clocks are drifting apart.</p>
 *
 * <p>The player can only be read from the thread that created it, so the frame callback only records
 * the presentation time of the frame and the wall clock of the device. The offset is calculated by
 * the report, which runs on the main thread: it takes the current position of the player and moves it
 * back by the time that passed since the frame was handed to the renderer, which is the media time the
 * audio clock was showing when that frame was rendered.</p>
 */
@UnstableApi
public final class PlaybackQualityMonitor {
	/** How often the collected numbers are written to the log. */
	private static final long REPORT_INTERVAL_MS = 2_000L;

	/**
	 * The first frames of a stream are handed to the renderer before the audio clock is stable, which
	 * produces one large offset that never repeats. The offset is therefore only collected after this
	 * startup window, so that the numbers describe the steady state that the viewer sees.
	 */
	private static final long STARTUP_SETTLE_MS = 4_000L;

	private final ExoPlayer player;
	private final boolean enabled;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private int droppedFrames;
	private int audioUnderruns;
	private long frames;
	private long samples;
	private long sumAbsOffsetUs;
	private long maxAbsOffsetUs;
	private long maxOffsetUs = Long.MIN_VALUE;
	private long minOffsetUs = Long.MAX_VALUE;
	private final VideoFrameMetadataListener videoFrameMetadataListener;

	// the player reports these numbers on its own playback thread
	private volatile int pendingDroppedFrames;
	private volatile int pendingAudioUnderruns;
	// written by the frame callback of the renderer thread, read by the report on the main thread
	private volatile long lastPresentationTimeUs = -1;
	private volatile long lastFrameWallClockMs = -1;
	private volatile long firstFrameWallClockMs = -1;

	private final Runnable report = new Runnable() {
		@Override
		public void run() {
			reportNow();
			if (enabled) handler.postDelayed(this, REPORT_INTERVAL_MS);
		}
	};

	public PlaybackQualityMonitor(@NonNull ExoPlayer player, boolean enabled) {
		this.player = player;
		this.enabled = enabled;

		player.addAnalyticsListener(new AnalyticsListener() {
			@Override
			public void onDroppedVideoFrames(@NonNull EventTime eventTime, int droppedFrames, long elapsedMs) {
				pendingDroppedFrames += droppedFrames;
			}

			@Override
			public void onAudioUnderrun(@NonNull EventTime eventTime, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
				pendingAudioUnderruns++;
			}
		});

		videoFrameMetadataListener = new VideoFrameMetadataListener() {
			@Override
			public void onVideoFrameAboutToBeRendered(long presentationTimeUs, long releaseTimeNs, @NonNull Format format, @Nullable android.media.MediaFormat mediaFormat) {
				// never read the player here: it belongs to the thread that created it
				frames++;
				lastPresentationTimeUs = presentationTimeUs;
				lastFrameWallClockMs = SystemClock.elapsedRealtime();
				if (firstFrameWallClockMs < 0) firstFrameWallClockMs = lastFrameWallClockMs;
			}
		};
		player.setVideoFrameMetadataListener(videoFrameMetadataListener);

		if (enabled) handler.postDelayed(report, REPORT_INTERVAL_MS);
	}

	/** Writes the numbers collected so far. */
	public synchronized void reportNow() {
		droppedFrames += pendingDroppedFrames;
		audioUnderruns += pendingAudioUnderruns;
		pendingDroppedFrames = 0;
		pendingAudioUnderruns = 0;

		long presentationTimeUs = lastPresentationTimeUs;
		long frameWallClockMs = lastFrameWallClockMs;
		if (presentationTimeUs >= 0 && frameWallClockMs >= 0
				&& firstFrameWallClockMs >= 0
				&& SystemClock.elapsedRealtime() - firstFrameWallClockMs >= STARTUP_SETTLE_MS
				&& player.getPlaybackState() == androidx.media3.common.Player.STATE_READY) {
			long nowMs = SystemClock.elapsedRealtime();
			long positionNowMs = player.getCurrentPosition();
			long audioClockAtFrameMs = positionNowMs - (nowMs - frameWallClockMs);
			long offsetUs = presentationTimeUs - audioClockAtFrameMs * 1_000L;

			samples++;
			sumAbsOffsetUs += Math.abs(offsetUs);
			maxAbsOffsetUs = Math.max(maxAbsOffsetUs, Math.abs(offsetUs));
			maxOffsetUs = Math.max(maxOffsetUs, offsetUs);
			minOffsetUs = Math.min(minOffsetUs, offsetUs);
		}

		long averageAbsUs = samples == 0 ? 0 : sumAbsOffsetUs / samples;
		Timber.i(String.format(Locale.ROOT,
				"Quality: avOffset avg=%dms max=%dms range=[%dms,%dms] frames=%d dropped=%d underruns=%d",
				averageAbsUs / 1_000, maxAbsOffsetUs / 1_000,
				minOffsetUs == Long.MAX_VALUE ? 0 : minOffsetUs / 1_000,
				maxOffsetUs == Long.MIN_VALUE ? 0 : maxOffsetUs / 1_000,
				frames, droppedFrames, audioUnderruns));
	}

	public synchronized void reset() {
		droppedFrames = 0;
		audioUnderruns = 0;
		pendingDroppedFrames = 0;
		pendingAudioUnderruns = 0;
		frames = 0;
		samples = 0;
		sumAbsOffsetUs = 0;
		maxAbsOffsetUs = 0;
		maxOffsetUs = Long.MIN_VALUE;
		minOffsetUs = Long.MAX_VALUE;
		lastPresentationTimeUs = -1;
		lastFrameWallClockMs = -1;
		firstFrameWallClockMs = -1;
	}

	public void release() {
		handler.removeCallbacks(report);
		reportNow();
		player.clearVideoFrameMetadataListener(videoFrameMetadataListener);
	}
}
