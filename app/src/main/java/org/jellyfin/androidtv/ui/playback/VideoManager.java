package org.jellyfin.androidtv.ui.playback;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.DynamicsProcessing.Limiter;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.graphics.TypefaceCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.data.compat.StreamInfo;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.constant.BufferLength;
import org.jellyfin.androidtv.preference.constant.ZoomMode;
import org.jellyfin.androidtv.util.PerformanceProfile;
import org.jellyfin.playback.core.support.PlaybackThroughput;
import org.jellyfin.sdk.api.client.ApiClient;
import org.jellyfin.sdk.model.api.MediaStream;
import org.jellyfin.sdk.model.api.MediaStreamType;
import org.jellyfin.sdk.model.api.PlayMethod;
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod;
import org.koin.java.KoinJavaComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.github.peerless2012.ass.media.AssHandler;
import io.github.peerless2012.ass.media.AssHandlerConfig;
import io.github.peerless2012.ass.media.factory.AssRenderersFactory;
import io.github.peerless2012.ass.media.kt.AssPlayerKt;
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory;
import io.github.peerless2012.ass.media.type.AssRenderType;
import io.github.peerless2012.ass.media.widget.AssSubtitleView;
import timber.log.Timber;

@OptIn(markerClass = UnstableApi.class)
public class VideoManager {
    private static final long PROGRESS_LOOP_INTERVAL_MS = 500L;
    private static final long LOW_PERFORMANCE_PROGRESS_LOOP_INTERVAL_MS = 750L;
    private static final long DIRECT_PLAY_STARTUP_TIMEOUT_MS = 20_000L;
    private static final long DIRECT_STREAM_STARTUP_TIMEOUT_MS = 30_000L;
    private static final long TRANSCODE_STARTUP_TIMEOUT_MS = 75_000L;

    /**
     * The subtitle size that corresponds to the default size of the player.
     *
     * The preference for the subtitle size uses the same value as its default.
     */
    private static final float DEFAULT_SUBTITLE_TEXT_SIZE = 24f;

    private ZoomMode mZoomMode;
    private Activity mActivity;
    private Equalizer mEqualizer;
    private DynamicsProcessing mDynamicsProcessing;
    private Limiter mLimiter;
    private PlaybackControllerNotifiable mPlaybackControllerNotifiable;
    private PlaybackOverlayFragmentHelper _helper;
    public ExoPlayer mExoPlayer;
    private PlayerView mExoPlayerView;
    /** Indeterminate spinner drawn over the video surface while nothing can be shown yet. */
    private View bufferingView;
    private Handler mHandler = new Handler();

    private long mMetaDuration = -1;
    private long lastExoPlayerPosition = -1;
    private boolean nightModeEnabled;
    private PlayMethod currentPlayMethod = PlayMethod.DIRECT_PLAY;
    private boolean playbackReady;
    private Runnable startupBufferingTimeout;
    private long mediaPrepareStartedAtMs = -1L;
    private long firstBufferingStartedAtMs = -1L;

    public boolean isContracted = false;

    private final UserPreferences userPreferences = KoinJavaComponent.get(UserPreferences.class);
    private final HttpDataSource.Factory exoPlayerHttpDataSourceFactory = KoinJavaComponent.get(HttpDataSource.Factory.class);

    /** Number of players that have been created and not released yet, used to find leaks. */
    private static final java.util.concurrent.atomic.AtomicInteger LIVE_PLAYERS = new java.util.concurrent.atomic.AtomicInteger();

    public VideoManager(@NonNull Activity activity, @NonNull View view, @NonNull PlaybackOverlayFragmentHelper helper) {
        mActivity = activity;
        _helper = helper;
        nightModeEnabled = userPreferences.get(UserPreferences.Companion.getAudioNightMode());

        boolean assDirectPlay = userPreferences.get(UserPreferences.Companion.getAssDirectPlay());
        // The overlay mode paints a new picture of the whole frame for every displayed frame,
        // twenty-four times a second. A picture is a large object, so it is only reclaimed by a
        // full garbage collection, and the heap of a TV box is far too small for that stream of
        // allocations: at 4K the heap of this device (128 MB) was full five seconds after the
        // playback started and the process was killed by the runtime a minute later.
        //
        // The cue mode renders every subtitle once, when it is parsed, and the player draws the
        // resulting picture while the subtitle is on screen. The memory it needs follows the
        // number of subtitles and not the number of frames, which is what a television needs.
        //
        // No limit is put on the pixels of that picture: the parser divides the position of a
        // cue by the size of the render frame, while the frame that the handler renders in is
        // the video size, so a limit smaller than the video shrinks the subtitles and moves
        // them towards the upper left corner.
        AssHandler assHandler = assDirectPlay
                ? new AssHandler(AssRenderType.CUES, new AssHandlerConfig(2_000, 8, 1920 * 1080))
                : null;

        mExoPlayer = configureExoplayerBuilder(activity, assHandler).build();
        Timber.i("Player created, live players: %d", LIVE_PLAYERS.incrementAndGet());

        if (userPreferences.get(UserPreferences.Companion.getDebuggingEnabled())) {
            mExoPlayer.addAnalyticsListener(new EventLogger());
        }

        // Measure how fast this device is actually able to download media. The measurement is
        // used to request a bitrate the device can sustain, otherwise the buffer runs dry and
        // the playback stutters every few seconds.
        mExoPlayer.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onLoadCompleted(@NonNull EventTime eventTime, @NonNull LoadEventInfo loadEventInfo, @NonNull MediaLoadData mediaLoadData) {
                if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return;

                PlaybackThroughput.record(mActivity, loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs);
            }
        });

        // Volume normalisation (audio night mode) and the level of the audio that is decoded here
        // instead of being passed through to the television.
        int audioBoost = userPreferences.get(UserPreferences.Companion.getAudioBoost()).getMillibels();
        if (nightModeEnabled || audioBoost > 0) {
            mExoPlayer.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onAudioSessionIdChanged(AnalyticsListener.EventTime eventTime, int audioSessionId) {
                    if (nightModeEnabled) VideoManagerHelperKt.applyAudioNightmode(audioSessionId);
                    VideoManagerHelperKt.applyAudioBoost(audioSessionId, audioBoost);
                }
            });
        }

        mExoPlayerView = view.findViewById(R.id.exoPlayerView);
        mExoPlayerView.setPlayer(mExoPlayer);
        bufferingView = view.findViewById(R.id.playback_buffering);
        int strokeColor = userPreferences.get(UserPreferences.Companion.getSubtitleTextStrokeColor()).intValue();
        int textWeight = userPreferences.get(UserPreferences.Companion.getSubtitlesTextWeight());
        CaptionStyleCompat subtitleStyle = new CaptionStyleCompat(
                userPreferences.get(UserPreferences.Companion.getSubtitlesTextColor()).intValue(),
                userPreferences.get(UserPreferences.Companion.getSubtitlesBackgroundColor()).intValue(),
                Color.TRANSPARENT,
                Color.alpha(strokeColor) == 0 ? CaptionStyleCompat.EDGE_TYPE_NONE : CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                strokeColor,
                TypefaceCompat.create(activity, Typeface.DEFAULT, textWeight, false)
        );
        // The size of the subtitles is configured in dp, which does not scale with
        // the resolution of the television: the same subtitles are a fraction of
        // the size on a 4K television as they are on a 1080p one. The size is
        // therefore applied as a fraction of the height of the video, with the
        // configured size used as the scale around the default of the player.
        float subtitleTextSize = userPreferences.get(UserPreferences.Companion.getSubtitlesTextSize());
        float subtitleTextSizeFraction = subtitleTextSize / DEFAULT_SUBTITLE_TEXT_SIZE * SubtitleView.DEFAULT_TEXT_SIZE_FRACTION;
        mExoPlayerView.getSubtitleView().setFractionalTextSize(subtitleTextSizeFraction);
        mExoPlayerView.getSubtitleView().setBottomPaddingFraction(userPreferences.get(UserPreferences.Companion.getSubtitlesOffsetPosition()));
        mExoPlayerView.getSubtitleView().setStyle(subtitleStyle);

        if (assHandler != null) {
            assHandler.init(mExoPlayer);
            // The overlay modes draw on a view of their own. A cue is drawn by the subtitle view
            // of the player, so the extra view would only cover the picture with an empty layer.
            if (assHandler.getRenderType() != AssRenderType.CUES) {
                mExoPlayerView.getSubtitleView().addView(new AssSubtitleView(mActivity, assHandler));
            }
        }

        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Timber.e(error, "Player failed during %s startup after %d ms (buffered=%d ms)",
                        currentPlayMethod, startupElapsedMs(), mExoPlayer.getBufferedPosition());
                cancelStartupBufferingTimeout();
                setBufferingVisible(false);
                if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onError();
                stopProgressLoop();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onPrepared();
                    startProgressLoop();
                    _helper.setScreensaverLock(true);
                } else {
                    stopProgressLoop();
                    _helper.setScreensaverLock(false);
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    if (!playbackReady && firstBufferingStartedAtMs < 0) {
                        firstBufferingStartedAtMs = SystemClock.elapsedRealtime();
                    }
                    Timber.d("Player is buffering using %s after %d ms (buffered=%d ms)",
                            currentPlayMethod, startupElapsedMs(), mExoPlayer.getBufferedPosition());
                    setBufferingVisible(true);
                    scheduleStartupBufferingTimeout();
                } else if (playbackState == Player.STATE_READY) {
                    playbackReady = true;
                    setBufferingVisible(false);
                    Timber.i("Playback startup ready using %s in %d ms (initial buffering=%d ms)",
                            currentPlayMethod,
                            startupElapsedMs(),
                            firstBufferingStartedAtMs < 0 ? 0L : SystemClock.elapsedRealtime() - firstBufferingStartedAtMs);
                    cancelStartupBufferingTimeout();
                } else if (playbackState == Player.STATE_IDLE) {
                    setBufferingVisible(false);
                }

                if (playbackState == Player.STATE_ENDED) {
                    setBufferingVisible(false);
                    if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onCompletion();
                    stopProgressLoop();
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                Timber.i("Playback rendered first frame using %s in %d ms", currentPlayMethod, startupElapsedMs());
            }

            @Override
            public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
                if (mPlaybackControllerNotifiable != null) {
                    mPlaybackControllerNotifiable.onPlaybackSpeedChange(playbackParameters.speed);
                }
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                // discontinuity for reason internal usually indicates an error, and that the player will reset to its default timestamp
                if (reason == Player.DISCONTINUITY_REASON_INTERNAL) {
                    Timber.i("Caught player discontinuity (reason internal) - oldPos: %s newPos: %s", oldPosition.positionMs, newPosition.positionMs);
                }
            }

            @Override
            public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
                Timber.d("Caught player timeline change - reason: %s", reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED ? "PLAYLIST_CHANGED" : "SOURCE_UPDATE");
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                Timber.d("Tracks changed");
            }
        });
    }

    public void subscribe(@NonNull PlaybackControllerNotifiable notifier) {
        mPlaybackControllerNotifiable = notifier;
    }

    private int determineExoPlayerExtensionRendererMode() {
        if (userPreferences.get(UserPreferences.Companion.getPreferExoPlayerFfmpeg())) {
            return DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
        }

        return DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
    }

    /**
     * Configures Exoplayer for video playback. Initially we try with core decoders, but allow
     * ExoPlayer to silently fallback to software renderers.
     *
     * @param context The associated context
     * @return A configured builder for Exoplayer
     */
    private ExoPlayer.Builder configureExoplayerBuilder(Context context, AssHandler assHandler) {
        ExoPlayer.Builder exoPlayerBuilder = new ExoPlayer.Builder(context);
        boolean lowPerformanceDevice = PerformanceProfile.isLowPerformanceDevice(context);
        DefaultRenderersFactory defaultRendererFactory = new DefaultRenderersFactory(context);
        // Software decoder fallback is useful on desktop-like devices, but on old TVs it
        // usually turns a media incompatibility into permanent dropped frames. The device
        // profile already asks the server to transcode those streams instead.
        defaultRendererFactory.setEnableDecoderFallback(!lowPerformanceDevice);
        defaultRendererFactory.setExtensionRendererMode(determineExoPlayerExtensionRendererMode());

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setAudioOffloadPreferences(new TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                        .build()
                )
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                .build()
        );
        exoPlayerBuilder.setTrackSelector(trackSelector);

        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3);
        extractorsFactory.setConstantBitrateSeekingEnabled(true);
        extractorsFactory.setConstantBitrateSeekingAlwaysEnabled(true);
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, exoPlayerHttpDataSourceFactory);
        if (assHandler != null) {
            AssSubtitleParserFactory assSubtitleParserFactory = new AssSubtitleParserFactory(assHandler);
            ExtractorsFactory assExtractorsFactory = AssPlayerKt.withAssMkvSupport(extractorsFactory, assSubtitleParserFactory, assHandler);
            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory);
            mediaSourceFactory.setSubtitleParserFactory(assSubtitleParserFactory);
            exoPlayerBuilder.setMediaSourceFactory(mediaSourceFactory);
            exoPlayerBuilder.setRenderersFactory(new AssRenderersFactory(assHandler, defaultRendererFactory));
        } else {
            exoPlayerBuilder.setRenderersFactory(defaultRendererFactory);
            exoPlayerBuilder.setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory));
        }

        BufferLength bufferLength = userPreferences.get(UserPreferences.Companion.getBufferLength());
        // media3 sizes its default target buffer from the selected track types: a video track
        // alone asks for 2000 segments (a segment is 64 KiB), which is roughly 128 MiB - far more
        // than the heap of a TV box (128-256 MiB). The player would then keep several minutes of
        // the stream in memory and the app is killed by an OutOfMemoryError. Cap the target buffer
        // by the heap size instead, so the player holds a few dozen seconds of video and stops
        // loading before the heap is full.
        int targetBufferBytes = org.jellyfin.playback.core.support.PlaybackBufferSizing.targetBufferBytes();
        Timber.i("Load control: target buffer %d bytes, heap %d bytes", targetBufferBytes, Runtime.getRuntime().maxMemory());
        androidx.media3.exoplayer.LoadControl loadControl;
        if (lowPerformanceDevice) {
            // Keep the allocator small enough for low-memory TV boxes. The server-side
            // profile limits expensive media, so a short buffer is enough to absorb normal
            // network jitter without holding tens of megabytes of compressed video.
            // The rebuffer threshold is generous so a single hiccup does not show the
            // buffering spinner.
            // The player starts as soon as it has the start buffer of the stream. A fast
            // connection delivers the first seconds quickly, so the picture appears
            // sooner without risking a stall right at the beginning.
            long linkBitrate = PlaybackThroughput.getLinkSafeBitrate(context);
            // A wireless connection stutters now and then: a retransmission or a
            // busy channel delays a segment for a moment, and a thin buffer runs
            // dry right away when that happens. A cable carries the stream without
            // interruptions, so a thin buffer is enough there. A connection that
            // is unknown is treated like a wireless one.
            String activeNetwork = PlaybackThroughput.getActiveNetwork(context);
            boolean wireless = activeNetwork.isEmpty() || activeNetwork.startsWith("WIFI:");
            int startBufferMs = (!wireless && linkBitrate >= PerformanceProfile.FAST_CONNECTION_BITRATE) ? 1_500 : 2_500;
            loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 30_000, startBufferMs, 5_000)
                    .setBackBuffer(0, false)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .setTargetBufferBytes(targetBufferBytes)
                    .build();
        } else if (bufferLength == BufferLength.LARGE) {
            loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(50_000, 120_000, 2_500, 5_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .setTargetBufferBytes(targetBufferBytes)
                    .build();
        } else if (bufferLength == BufferLength.EXTRA_LARGE) {
            loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(80_000, 240_000, 5_000, 10_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .setTargetBufferBytes(targetBufferBytes)
                    .build();
        } else {
            // A byte budget alone can deadlock the player when it is reached while the buffer is
            // nearly empty: the loader stops, the buffer drains and media3 aborts the playback.
            // This control keeps the budget but forces loading below a few seconds of buffered
            // media so a stall can recover.
            loadControl = new org.jellyfin.playback.media3.exoplayer.support.CappedLoadControl(targetBufferBytes);
        }
        exoPlayerBuilder.setLoadControl(loadControl);

        exoPlayerBuilder.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);

        return exoPlayerBuilder;
    }


    public boolean isInitialized() {
        return mExoPlayer != null;
    }

    public @NonNull ZoomMode getZoomMode() {
        return mZoomMode;
    }

    public void setZoom(@NonNull ZoomMode mode) {
        mZoomMode = mode;
        switch (mode) {
            case FIT:
                mExoPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case AUTO_CROP:
                mExoPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
            case STRETCH:
                mExoPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
        }
    }

    public void setMetaDuration(long duration) {
        mMetaDuration = duration;
    }

    public long getDuration() {
        return isInitialized() && mExoPlayer.getDuration() > 0 ? mExoPlayer.getDuration() : mMetaDuration;
    }

    public long getBufferedPosition() {
        if (!isInitialized())
            return -1;

        long bufferedPosition = mExoPlayer.getBufferedPosition();

        if (bufferedPosition > -1 && bufferedPosition < getDuration()) {
            return bufferedPosition;
        }
        return -1;
    }

    public long getCurrentPosition() {
        if (mExoPlayer == null || !isPlaying()) {
            return lastExoPlayerPosition == -1 ? 0 : lastExoPlayerPosition;
        } else {
            long mExoPlayerCurrentPosition = mExoPlayer.getCurrentPosition();
            lastExoPlayerPosition = mExoPlayerCurrentPosition;
            return mExoPlayerCurrentPosition;
        }
    }

    public boolean isPlaying() {
        return mExoPlayer.isPlaying();
    }

    public void start() {
        if (mExoPlayer == null) {
            Timber.e("mExoPlayer should not be null!!");
            _helper.getFragment().closePlayer();
            return;
        }
        mExoPlayer.setPlayWhenReady(true);
        scheduleStartupBufferingTimeout();
        normalWidth = mExoPlayerView.getLayoutParams().width;
        normalHeight = mExoPlayerView.getLayoutParams().height;
    }

    public void play() {
        mExoPlayer.setPlayWhenReady(true);
        scheduleStartupBufferingTimeout();
    }

    public void pause() {
        mExoPlayer.setPlayWhenReady(false);
        cancelStartupBufferingTimeout();
    }

    public void stopPlayback() {
        cancelStartupBufferingTimeout();
        playbackReady = false;
        setBufferingVisible(false);
        if (mExoPlayer != null) {
            mExoPlayer.stop();

            mExoPlayer.setTrackSelectionParameters(mExoPlayer.getTrackSelectionParameters()
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .build());
        }

        stopProgressLoop();
    }

    public boolean isSeekable() {
        if (!isInitialized())
            return false;

        boolean canSeek = mExoPlayer.isCurrentMediaItemSeekable();
        Timber.d("current media item is%s seekable", canSeek ? "" : " not");
        return canSeek;
    }

    public long seekTo(long pos) {
        if (!isInitialized())
            return -1;

        Timber.i("Exo length in seek is: %d", getDuration());
        mExoPlayer.seekTo(pos);
        return pos;
    }

    /**
     * Subtitle codecs that are rendered by libass on the client.
     */
    private static boolean isLibassSubtitleCodec(@Nullable String codec) {
        return "ass".equalsIgnoreCase(codec) || "ssa".equalsIgnoreCase(codec);
    }

    /**
     * Rewrites a subtitle delivery url to the webvtt variant, keeping the query string.
     */
    private static String toVttSubtitleUrl(@Nullable String url) {
        if (url == null) return null;

        int queryAt = url.indexOf('?');
        String base = queryAt >= 0 ? url.substring(0, queryAt) : url;
        String query = queryAt >= 0 ? url.substring(queryAt) : "";

        int extensionAt = base.lastIndexOf('.');
        if (extensionAt < 0) return url;

        return base.substring(0, extensionAt) + ".vtt" + query;
    }
    private int getSubtitleSelectionFlags(MediaStream mediaStream) {
        int flags = 0;
        if (mediaStream.isDefault()) flags &= C.SELECTION_FLAG_DEFAULT;
        if (mediaStream.isForced()) flags &= C.SELECTION_FLAG_FORCED;
        return flags;
    }

    public void setMediaStreamInfo(ApiClient api, StreamInfo streamInfo) {
        String path = streamInfo.getMediaUrl();
        if (path == null) {
            Timber.w("Video path is null cannot continue");
            return;
        }
        Timber.i("Video path set to: %s", path);
        currentPlayMethod = streamInfo.getPlayMethod();
        playbackReady = false;
        mediaPrepareStartedAtMs = SystemClock.elapsedRealtime();
        firstBufferingStartedAtMs = -1L;
        cancelStartupBufferingTimeout();
        // The video surface renders black until the first frame arrives. Show the spinner now,
        // before the player even reports STATE_BUFFERING, so the user always gets feedback.
        setBufferingVisible(true);

        try {
            // Add external subtitles
            List<MediaItem.SubtitleConfiguration> subtitleConfigurations = new ArrayList<>();
            List<MediaStream> mediaStreams = streamInfo.getMediaSource() == null ? null : streamInfo.getMediaSource().getMediaStreams();
            if (mediaStreams == null) {
                Timber.w("Playback info has no media streams - the subtitles cannot be added");
                mediaStreams = Collections.emptyList();
            }
            for (MediaStream mediaStream : mediaStreams) {
                if (mediaStream.getType() != MediaStreamType.SUBTITLE) continue;

                if (mediaStream.getDeliveryMethod() == SubtitleDeliveryMethod.EXTERNAL) {
                    String subtitleUrl = mediaStream.getDeliveryUrl();
                    String subtitleMimeType = VideoManagerHelperKt.getSubtitleMediaStreamCodec(mediaStream);
                    // libass renders every cue up to the seek position on the loading thread, which stalls
                    // playback for minutes on slower boxes. The server can convert the text format for us.
                    if (isLibassSubtitleCodec(mediaStream.getCodec())) {
                        Timber.i("Subtitle %d is served as vtt instead of %s to avoid the libass startup cost", mediaStream.getIndex(), mediaStream.getCodec());
                        subtitleUrl = toVttSubtitleUrl(subtitleUrl);
                        subtitleMimeType = "text/vtt";
                    }
                    Uri subtitleUri = Uri.parse(api.createUrl(subtitleUrl, Collections.emptyMap(), Collections.emptyMap(), true));
                    MediaItem.SubtitleConfiguration subtitleConfiguration = new MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                            .setId("JF_EXTERNAL:" + String.valueOf(mediaStream.getIndex()))
                            .setMimeType(subtitleMimeType)
                            .setLanguage(mediaStream.getLanguage())
                            .setLabel(mediaStream.getDisplayTitle())
                            .setSelectionFlags(getSubtitleSelectionFlags(mediaStream))
                            .build();
                    Timber.i("Adding subtitle track %s of type %s", subtitleConfiguration.uri, subtitleConfiguration.mimeType);
                    subtitleConfigurations.add(subtitleConfiguration);
                }
            }

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(Uri.parse(path))
                    .setSubtitleConfigurations(subtitleConfigurations)
                    .build();

            mExoPlayer.setMediaItem(mediaItem);
            mExoPlayer.prepare();
        } catch (IllegalStateException e) {
            Timber.e(e, "Unable to set video path.  Probably backing out.");
        }
    }

    private int offsetStreamIndex(int index, boolean adjustByAdding, @Nullable List<org.jellyfin.sdk.model.api.MediaStream> allStreams) {
        if (index < 0 || allStreams == null)
            return -1;

        // translate player track index to/from Jellyfin MediaStream index to account for external tracks
        // being in the MediaStream tracks list but not in a player's track list
        //
        // use adjustByAdding=true to translate player-id -> MediaStream-id, false for the other direction
        //
        // use indexStartsAtOne=true when the player's tracks list uses indexes/IDs starting at 1
        // MediaStream indexes/IDs start at 0

        for (org.jellyfin.sdk.model.api.MediaStream stream : allStreams) {
            if (!stream.isExternal())
                break;
            index += adjustByAdding ? 1 : -1;
        }

        return index < 0 || index > allStreams.size() ? -1 : index;
    }

    public int getExoPlayerTrack(@Nullable org.jellyfin.sdk.model.api.MediaStreamType streamType, @Nullable List<org.jellyfin.sdk.model.api.MediaStream> allStreams) {
        if (!isInitialized() || streamType == null || allStreams == null)
            return -1;
        if (streamType != org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE && streamType != org.jellyfin.sdk.model.api.MediaStreamType.AUDIO)
            return -1;

        int chosenTrackType = streamType == org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_AUDIO;

        int matchedIndex = -2;
        Tracks exoTracks = mExoPlayer.getCurrentTracks();
        for (Tracks.Group groupInfo : exoTracks.getGroups()) {
            if (matchedIndex > -2)
                break;
            // Group level information.
            @C.TrackType int trackType = groupInfo.getType();
            TrackGroup group = groupInfo.getMediaTrackGroup();
            for (int i = 0; i < group.length; i++) {
                if (trackType == chosenTrackType) {
                    if (groupInfo.isTrackSelected(i)) {
                        // we found the track, set to -1 first to handle failed int parsing
                        matchedIndex = -1;
                        int id;
                        try {
                            if (group.id.contains(":")) {
                                id = Integer.parseInt(group.id.split(":")[1]);
                            } else {
                                id = Integer.parseInt(group.id);
                            }
                        } catch (NumberFormatException e) {
                            Timber.w("failed to parse group ID [%s]", group.id);
                            break;
                        }
                        matchedIndex = id;
                        break;
                    }
                }
            }
        }

        // offset the stream index to account for external streams
        int exoTrackID = offsetStreamIndex(matchedIndex, true, allStreams);
        if (exoTrackID < 0)
            return -1;

        Timber.d("re-retrieved exoplayer track index %s", exoTrackID);
        return exoTrackID;
    }

    public boolean setExoPlayerTrack(int index, @Nullable org.jellyfin.sdk.model.api.MediaStreamType streamType, @Nullable List<org.jellyfin.sdk.model.api.MediaStream> allStreams) {
        if (!isInitialized() || allStreams == null || allStreams.isEmpty() || streamType != org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE && streamType != org.jellyfin.sdk.model.api.MediaStreamType.AUDIO)
            return false;

        int chosenTrackType = streamType == org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_AUDIO;

        // Make sure the index is present
        Optional<MediaStream> candidateOptional = allStreams.stream().filter(stream -> stream.getIndex() == index && !stream.isExternal() && stream.getType() == streamType).findFirst();
        if (!candidateOptional.isPresent()) return false;

        int exoTrackID = offsetStreamIndex(index, false, allStreams);
        if (exoTrackID < 0)
            return false;

        // print the streams for debugging
        for (MediaStream stream : allStreams) {
            Timber.d("MediaStream track %s type %s label %s codec %s isExternal %s", stream.getIndex(), stream.getType(), stream.getTitle(), stream.getCodec(), stream.isExternal());
        }

        // design choices for exoplayer track selection overrides:
        // * build upon the prior parameters so we can mix overrides of different track types without erasing priors
        //
        // * for subtitles (not currently used) - use setDisabledTrackTypes to disable or enable exoplayer handing subtitles
        //   if we want most formats to be handled by the external subtitle handler (which has adjustable size, background), we leave sub track selection disabled
        //   if we decide to use exoplayer to render a specific subtitle format, allow subtitle track selection and restrict selection to the chosen group

        Tracks exoTracks = mExoPlayer.getCurrentTracks();
        TrackGroup matchedGroup = null;
        for (Tracks.Group groupInfo : exoTracks.getGroups()) {
            // Group level information.
            @C.TrackType int trackType = groupInfo.getType();
            TrackGroup group = groupInfo.getMediaTrackGroup();
            for (int i = 0; i < group.length; i++) {
                // Individual track information.
                boolean isSupported = groupInfo.isTrackSupported(i);
                boolean isSelected = groupInfo.isTrackSelected(i);
                Format trackFormat = group.getFormat(i);

                Timber.i("track %s group %s/%s trackType %s label %s mime %s isSelected %s isSupported %s",
                        trackFormat.id, i + 1, group.length, trackType, trackFormat.label, trackFormat.sampleMimeType, isSelected, isSupported);

                if (trackType != chosenTrackType)
                    continue;

                int id;
                try {
                    if (group.id.contains(":")) {
                        id = Integer.parseInt(group.id.split(":")[1]);
                    } else {
                        id = Integer.parseInt(group.id);
                    }
                    if (id != exoTrackID)
                        continue;
                } catch (NumberFormatException e) {
                    Timber.w("failed to parse group ID [%s]", group.id);
                    continue;
                }

                if (!groupInfo.isTrackSupported(i)) {
                    Timber.d("track is not compatible");
                    return false;
                }

                if (groupInfo.isTrackSelected(i)) {
                    Timber.d("track is already selected");
                    return true;
                }

                Timber.i("matched exoplayer track %s to mediaStream track %s", trackFormat.id, index);
                matchedGroup = group;
            }
        }

        if (matchedGroup == null)
            return false;

        try {
            TrackSelectionParameters.Builder mExoPlayerSelectionParams = mExoPlayer.getTrackSelectionParameters().buildUpon();
            mExoPlayerSelectionParams.setOverrideForType(new TrackSelectionOverride(matchedGroup, 0));
            mExoPlayer.setTrackSelectionParameters(mExoPlayerSelectionParams.build());
        } catch (Exception e) {
            Timber.w("Error setting track selection");
            return false;
        }
        return true;
    }

    public float getPlaybackSpeed() {
        if (!isInitialized()) {
            return 1.0f;
        } else {
            return mExoPlayer.getPlaybackParameters().speed;
        }
    }

    public void setPlaybackSpeed(float speed) {
        if (speed < 0.25) {
            Timber.w("Invalid playback speed requested: %f", speed);
            return;
        }
        Timber.d("Setting playback speed: %f", speed);

        mExoPlayer.setPlaybackParameters(new PlaybackParameters(speed));
    }

    public void destroy() {
        mPlaybackControllerNotifiable = null;
        stopPlayback();
        releasePlayer();
        VideoManagerHelperKt.releaseAudioBoost();
    }

    private void releasePlayer() {
        cancelStartupBufferingTimeout();
        _helper.setScreensaverLock(false);
        if (mExoPlayer != null) {
            mExoPlayerView.setPlayer(null);
            mExoPlayer.release();
            mExoPlayer = null;
            Timber.i("Player released, live players: %d", LIVE_PLAYERS.decrementAndGet());
        }
    }

    /**
     * Current buffer state of the player, logged to find why the memory of the player grows.
     */
    public String describeBufferState() {
        if (mExoPlayer == null) return "no player";

        return "pos=" + mExoPlayer.getCurrentPosition()
                + " buffered=" + mExoPlayer.getBufferedPosition()
                + " totalBuffered=" + mExoPlayer.getTotalBufferedDuration();
    }

    int normalWidth;
    int normalHeight;

    public void contractVideo(int height) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mExoPlayerView.getLayoutParams();
        if (isContracted) return;

        int sw = mActivity.getWindow().getDecorView().getWidth();
        int sh = mActivity.getWindow().getDecorView().getHeight();
        float ar = (float) sw / sh;
        lp.height = height;
        lp.width = (int) Math.ceil(height * ar);
        lp.rightMargin = ((lp.width - normalWidth) / 2) - 110;
        lp.bottomMargin = ((lp.height - normalHeight) / 2) - 50;

        mExoPlayerView.setLayoutParams(lp);
        mExoPlayerView.invalidate();

        isContracted = true;
    }

    public void setVideoFullSize(boolean force) {
        if (normalHeight == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mExoPlayerView.getLayoutParams();
        if (force) {
            lp.height = -1;
            lp.width = -1;
        } else {
            lp.height = normalHeight;
            lp.width = normalWidth;
        }

        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        mExoPlayerView.setLayoutParams(lp);
        mExoPlayerView.invalidate();

        isContracted = false;
    }

    private Runnable progressLoop;

    private void startProgressLoop() {
        stopProgressLoop();
        final long interval = PerformanceProfile.isLowPerformanceDevice(mActivity)
                ? LOW_PERFORMANCE_PROGRESS_LOOP_INTERVAL_MS
                : PROGRESS_LOOP_INTERVAL_MS;
        progressLoop = new Runnable() {
            @Override
            public void run() {
                if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onProgress();
                mHandler.postDelayed(this, interval);
            }
        };
        mHandler.post(progressLoop);
    }

    private void stopProgressLoop() {
        if (progressLoop != null) {
            mHandler.removeCallbacks(progressLoop);
        }
    }

    private void scheduleStartupBufferingTimeout() {
        if (mExoPlayer == null || playbackReady || !mExoPlayer.getPlayWhenReady()
                || startupBufferingTimeout != null) {
            return;
        }

        long timeout = currentPlayMethod == PlayMethod.DIRECT_PLAY
                ? DIRECT_PLAY_STARTUP_TIMEOUT_MS
                : currentPlayMethod == PlayMethod.DIRECT_STREAM
                ? DIRECT_STREAM_STARTUP_TIMEOUT_MS
                : TRANSCODE_STARTUP_TIMEOUT_MS;

        startupBufferingTimeout = () -> {
            startupBufferingTimeout = null;
            if (mExoPlayer == null || playbackReady || !mExoPlayer.getPlayWhenReady()
                    || mExoPlayer.getPlaybackState() != Player.STATE_BUFFERING) {
                return;
            }

            Timber.w("Playback startup timed out after %d ms using %s (buffered=%d ms, position=%d ms); retrying with a safer stream",
                    startupElapsedMs(), currentPlayMethod, mExoPlayer.getBufferedPosition(), mExoPlayer.getCurrentPosition());
            stopProgressLoop();
            if (mPlaybackControllerNotifiable != null) mPlaybackControllerNotifiable.onError();
        };
        mHandler.postDelayed(startupBufferingTimeout, timeout);
    }

    private void cancelStartupBufferingTimeout() {
        if (startupBufferingTimeout != null) {
            mHandler.removeCallbacks(startupBufferingTimeout);
            startupBufferingTimeout = null;
        }
    }

    /**
     * Show or hide the buffering spinner over the video surface.
     */
    private void setBufferingVisible(boolean visible) {
        if (bufferingView == null) return;

        int target = visible ? View.VISIBLE : View.GONE;
        if (bufferingView.getVisibility() == target) return;

        bufferingView.setVisibility(target);
    }

    private long startupElapsedMs() {
        return mediaPrepareStartedAtMs < 0 ? 0L : SystemClock.elapsedRealtime() - mediaPrepareStartedAtMs;
    }
}
