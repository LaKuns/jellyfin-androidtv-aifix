package org.jellyfin.androidtv.ui.playback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.common.util.UnstableApi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import timber.log.Timber;

/**
 * Temporary tracing wrapper: logs every HTTP media request of the player with its duration and
 * transfer size, so a stall can be attributed to a specific request.
 */
@UnstableApi
public final class TracingHttpDataSourceFactory implements HttpDataSource.Factory {
	private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
	private static final List<TransferListener> LISTENERS = Collections.synchronizedList(new ArrayList<>());

	private final HttpDataSource.Factory delegate;
	@Nullable
	private final String tag;

	public TracingHttpDataSourceFactory(HttpDataSource.Factory delegate, @Nullable String tag) {
		this.delegate = delegate;
		this.tag = tag;
	}

	@NonNull
	@Override
	public HttpDataSource createDataSource() {
		int id = NEXT_ID.getAndIncrement();
		return new TracingHttpDataSource(delegate.createDataSource(), id, tag);
	}

	@NonNull
	@Override
	public TracingHttpDataSourceFactory setDefaultRequestProperties(@NonNull Map<String, String> defaultRequestProperties) {
		delegate.setDefaultRequestProperties(defaultRequestProperties);
		return this;
	}

	private static final class TracingHttpDataSource implements HttpDataSource {
		private final HttpDataSource delegate;
		private final int id;
		@Nullable
		private final String tag;
		private long openedAtMs;
		private long readBytes;
		@Nullable
		private String openedUri;

		TracingHttpDataSource(HttpDataSource delegate, int id, @Nullable String tag) {
			this.delegate = delegate;
			this.id = id;
			this.tag = tag;
		}

		@Override
		public long open(@NonNull DataSpec dataSpec) throws IOException {
			openedAtMs = android.os.SystemClock.elapsedRealtime();
			openedUri = dataSpec.uri.toString();
			readBytes = 0;
			Timber.i("HTTP[%d] open %s (pos=%d, len=%d) %s", id, openedUri, dataSpec.position, dataSpec.length, tag);
			try {
				long length = delegate.open(dataSpec);
				Timber.i("HTTP[%d] opened in %d ms, length=%d", id, android.os.SystemClock.elapsedRealtime() - openedAtMs, length);
				return length;
			} catch (IOException e) {
				Timber.w(e, "HTTP[%d] open failed after %d ms: %s", id, android.os.SystemClock.elapsedRealtime() - openedAtMs, openedUri);
				throw e;
			}
		}

		@Override
		public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
			long startedAt = android.os.SystemClock.elapsedRealtime();
			try {
				int read = delegate.read(buffer, offset, length);
				readBytes += Math.max(read, 0);
				long took = android.os.SystemClock.elapsedRealtime() - startedAt;
				if (took > 2_000) {
					Timber.w("HTTP[%d] slow read %d bytes took %d ms (total %d)", id, read, took, readBytes);
				}
				return read;
			} catch (IOException e) {
				Timber.w(e, "HTTP[%d] read failed after %d ms (total %d)", id, android.os.SystemClock.elapsedRealtime() - startedAt, readBytes);
				throw e;
			}
		}

		@Override
		public void close() throws IOException {
			Timber.i("HTTP[%d] close after %d ms, %d bytes: %s", id, android.os.SystemClock.elapsedRealtime() - openedAtMs, readBytes, openedUri);
			delegate.close();
		}

		@NonNull
		@Override
		public Map<String, List<String>> getResponseHeaders() {
			return delegate.getResponseHeaders();
		}

		@Nullable
		@Override
		public android.net.Uri getUri() {
			return delegate.getUri();
		}

		@Override
		public int getResponseCode() throws IOException {
			return delegate.getResponseCode();
		}

		@Override
		public void setRequestProperty(@NonNull String name, @NonNull String value) {
			delegate.setRequestProperty(name, value);
		}

		@Override
		public void clearRequestProperty(@NonNull String name) {
			delegate.clearRequestProperty(name);
		}

		@Override
		public void clearAllRequestProperties() {
			delegate.clearAllRequestProperties();
		}

		@Override
		public void addTransferListener(@NonNull TransferListener transferListener) {
			delegate.addTransferListener(transferListener);
			LISTENERS.add(transferListener);
		}
	}
}
