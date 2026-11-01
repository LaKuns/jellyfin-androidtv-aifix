package org.jellyfin.playback.media3.exoplayer;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;

import org.jellyfin.playback.core.support.PlaybackThroughput;

/**
 * Measures how fast the device is actually able to download media.
 *
 * <p>The measurement is used to request a bitrate the device can sustain. A stream that
 * cannot be downloaded as fast as it is consumed empties the buffer and makes the
 * playback stutter every few seconds, even though the server transcodes much faster than
 * real time.
 */
public class ThroughputAnalyticsListener implements AnalyticsListener {
	private final Context context;

	public ThroughputAnalyticsListener(Context context) {
		this.context = context;
	}

	@Override
	public void onLoadCompleted(
		@NonNull EventTime eventTime,
		@NonNull LoadEventInfo loadEventInfo,
		@NonNull MediaLoadData mediaLoadData
	) {
		// Only media downloads say something about the speed of the connection, a
		// manifest or a subtitle is far too small to measure anything.
		if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return;

		PlaybackThroughput.record(context, loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs);
	}
}
