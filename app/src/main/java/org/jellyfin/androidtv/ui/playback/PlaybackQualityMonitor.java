package org.jellyfin.androidtv.ui.playback;

import android.os.Handler;
import android.os.Looper;

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
 */
@UnstableApi
public final class PlaybackQualityMonitor {
	/** How often the collected numbers are written to the log. */
	private static final long REPORT_INTERVAL_MS = 2_000L;

	private final ExoPlayer player;
	private final boolean enabled;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private int droppedFrames;
	private int audioUnderruns;
	private long frames;
	private long sumAbsOffsetUs;
	private long maxAbsOffsetUs;
	private long lastOffsetUs = Long.MIN_VALUE;
	private long maxOffsetUs = Long.MIN_VALUE;
	private long minOffsetUs = Long.MAX_VALUE;
	private final VideoFrameMetadataListener videoFrameMetadataListener;

	// the player reports these numbers on its own playback thread
	private volatile int pendingDroppedFrames;
	private volatile int pendingAudioUnderruns;

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
				// the audio clock is what the video has to follow, so compare the frame with it
				long offsetUs = presentationTimeUs - player.getCurrentPosition() * 1_000L;
				synchronized (PlaybackQualityMonitor.this) {
					frames++;
					sumAbsOffsetUs += Math.abs(offsetUs);
					maxAbsOffsetUs = Math.max(maxAbsOffsetUs, Math.abs(offsetUs));
					lastOffsetUs = offsetUs;
					maxOffsetUs = Math.max(maxOffsetUs, offsetUs);
					minOffsetUs = Math.min(minOffsetUs, offsetUs);
				}
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

		long averageAbsUs = frames == 0 ? 0 : sumAbsOffsetUs / frames;
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
		sumAbsOffsetUs = 0;
		maxAbsOffsetUs = 0;
		lastOffsetUs = Long.MIN_VALUE;
		maxOffsetUs = Long.MIN_VALUE;
		minOffsetUs = Long.MAX_VALUE;
	}

	public void release() {
		handler.removeCallbacks(report);
		reportNow();
		player.clearVideoFrameMetadataListener(videoFrameMetadataListener);
	}
}
