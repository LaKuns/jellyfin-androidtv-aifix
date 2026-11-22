package org.jellyfin.androidtv.ui.playback;

import static org.koin.java.KoinJavaComponent.get;
import static org.koin.java.KoinJavaComponent.inject;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.data.compat.PlaybackException;
import org.jellyfin.androidtv.data.compat.StreamInfo;
import org.jellyfin.androidtv.data.compat.VideoOptions;
import org.jellyfin.androidtv.data.model.DataRefreshService;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.UserSettingPreferences;
import org.jellyfin.androidtv.preference.constant.NextUpBehavior;
import org.jellyfin.androidtv.preference.constant.PlaybackStrategy;
import org.jellyfin.androidtv.preference.constant.RefreshRateSwitchingBehavior;
import org.jellyfin.androidtv.preference.constant.StillWatchingBehavior;
import org.jellyfin.androidtv.preference.constant.ZoomMode;
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel;
import org.jellyfin.androidtv.ui.livetv.TvManager;
import org.jellyfin.androidtv.util.ConnectionMonitor;
import org.jellyfin.androidtv.util.TimeUtils;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.ReportingHelper;
import org.jellyfin.androidtv.util.apiclient.Response;
import org.jellyfin.androidtv.util.PerformanceProfile;
import org.jellyfin.androidtv.util.profile.DeviceProfileKt;
import org.jellyfin.androidtv.util.sdk.compat.JavaCompat;
import org.jellyfin.sdk.api.client.ApiClient;
import org.jellyfin.sdk.model.ServerVersion;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.jellyfin.sdk.model.api.DeviceProfile;
import org.jellyfin.sdk.model.api.LocationType;
import org.jellyfin.sdk.model.api.MediaSourceInfo;
import org.jellyfin.sdk.model.api.MediaStream;
import org.jellyfin.sdk.model.api.MediaStreamType;
import org.jellyfin.sdk.model.api.PlayMethod;
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod;
import org.jellyfin.sdk.model.serializer.UUIDSerializerKt;
import org.koin.java.KoinJavaComponent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import kotlin.Lazy;
import timber.log.Timber;

public class PlaybackController implements PlaybackControllerNotifiable {
    // Frequency to report playback progress
    private final static long PROGRESS_REPORTING_INTERVAL = TimeUtils.secondsToMillis(3);
    // Frequency to report paused state
    private static final long PROGRESS_REPORTING_PAUSE_INTERVAL = TimeUtils.secondsToMillis(15);
    private static final long INITIAL_SEEK_RETRY_INTERVAL_MS = 100L;
    private static final long INITIAL_SEEK_TIMEOUT_MS = 5_000L;

    private Lazy<PlaybackManager> playbackManager = inject(PlaybackManager.class);
    private Lazy<UserPreferences> userPreferences = inject(UserPreferences.class);
    private Lazy<VideoQueueManager> videoQueueManager = inject(VideoQueueManager.class);
    private Lazy<ApiClient> api = inject(ApiClient.class);
    private Lazy<DataRefreshService> dataRefreshService = inject(DataRefreshService.class);
    private Lazy<ReportingHelper> reportingHelper = inject(ReportingHelper.class);
    private final Lazy<InteractionTrackerViewModel> lazyInteractionTracker = inject(InteractionTrackerViewModel.class);

    List<BaseItemDto> mItems;
    VideoManager mVideoManager;
    int mCurrentIndex;
    protected long mCurrentPosition = 0;
    private PlaybackState mPlaybackState = PlaybackState.IDLE;

    private StreamInfo mCurrentStreamInfo;

    private final InteractionTrackerViewModel interactionTracker;

    @Nullable
    private CustomPlaybackOverlayFragment mFragment;
    private Boolean spinnerOff = false;

    protected VideoOptions mCurrentOptions;
    private int mDefaultAudioIndex = -1;
    protected boolean burningSubs = false;
    /**
     * Set when the previous attempt failed while the server was burning subtitles into the
     * video. The next attempt then ignores the saved subtitle preference so it can actually
     * succeed instead of requesting the same (failing) burn-in transcode again.
     */
    private boolean dropSubtitlesForRetry = false;

    // The server does not update the subtitle delivery method when alwaysBurnInSubtitleWhenTranscoding
    // is set, so we need to assume subs are burned in when transcoding with the option enabled.
    protected boolean shouldBurnInSubtitles(PlayMethod playMethod) {
        return userPreferences.getValue().get(UserPreferences.Companion.getSubtitlesBurnDuringTranscode())
                && playMethod == PlayMethod.TRANSCODE;
    }

    private void updateBurningSubs(StreamInfo response) {
        if (response.getSubtitleDeliveryMethod() == SubtitleDeliveryMethod.DROP) {
            burningSubs = false;
            return;
        }

        burningSubs = response.getSubtitleDeliveryMethod() == SubtitleDeliveryMethod.ENCODE
                || shouldBurnInSubtitles(response.getPlayMethod());
    }
    private float mRequestedPlaybackSpeed = -1.0f;

    private Runnable mReportLoop;
    private Handler mHandler;

    private long mStartPosition = 0;

    // tmp position used when seeking
    private long mSeekPosition = -1;
    private boolean wasSeeking = false;
    private boolean finishedInitialSeek = false;
    private Runnable mInitialSeekRunnable;
    private long mInitialSeekDeadline;

    private LocalDateTime mCurrentProgramEnd = null;
    private LocalDateTime mCurrentProgramStart = null;
    private long mCurrentTranscodeStartTime;
    private boolean isLiveTv = false;
    private boolean directStreamLiveTv;
    private int playbackRetries = 0;
    private long lastPlaybackError = 0;

    private Display.Mode[] mDisplayModes;
    private RefreshRateSwitchingBehavior refreshRateSwitchingBehavior = RefreshRateSwitchingBehavior.DISABLED;

    public PlaybackController(List<BaseItemDto> items, CustomPlaybackOverlayFragment fragment) {
        this(items, fragment, 0);
    }

    public PlaybackController(List<BaseItemDto> items, CustomPlaybackOverlayFragment fragment, int startIndex) {
        mItems = items;
        mCurrentIndex = 0;
        if (items != null && startIndex > 0 && startIndex < items.size()) {
            mCurrentIndex = startIndex;
        }
        mFragment = fragment;
        mHandler = new Handler();

        interactionTracker = lazyInteractionTracker.getValue();

        refreshRateSwitchingBehavior = userPreferences.getValue().get(UserPreferences.Companion.getRefreshRateSwitchingBehavior());
        if (refreshRateSwitchingBehavior != RefreshRateSwitchingBehavior.DISABLED)
            getDisplayModes();

    }

    public boolean hasFragment() {
        return mFragment != null;
    }

    public CustomPlaybackOverlayFragment getFragment() {
        return mFragment;
    }

    public void init(@NonNull VideoManager mgr, @NonNull CustomPlaybackOverlayFragment fragment) {
        mVideoManager = mgr;
        mVideoManager.subscribe(this);
        mVideoManager.setZoom(userPreferences.getValue().get(UserPreferences.Companion.getPlayerZoomMode()));
        mFragment = fragment;
        directStreamLiveTv = userPreferences.getValue().get(UserPreferences.Companion.getLiveTvDirectPlayEnabled());
    }

    public void setItems(List<BaseItemDto> items) {
        mItems = items;
        mCurrentIndex = 0;
    }

    public float getPlaybackSpeed() {
        if (hasInitializedVideoManager()) {
            // Actually poll the video manager, since exoplayer can revert back
            // to 1x if it can't go faster, so we should check directly
            return mVideoManager.getPlaybackSpeed();
        } else {
            return mRequestedPlaybackSpeed;
        }
    }

    public void setPlaybackSpeed(float speed) {
        mRequestedPlaybackSpeed = speed;
        if (hasInitializedVideoManager()) {
            mVideoManager.setPlaybackSpeed(speed);
        }
    }

    public BaseItemDto getCurrentlyPlayingItem() {
        return mItems.size() > mCurrentIndex ? mItems.get(mCurrentIndex) : null;
    }

    public boolean hasInitializedVideoManager() {
        return mVideoManager != null && mVideoManager.isInitialized();
    }

    public MediaSourceInfo getCurrentMediaSource() {
        if (mCurrentStreamInfo != null && mCurrentStreamInfo.getMediaSource() != null) {
            return mCurrentStreamInfo.getMediaSource();
        } else {
            BaseItemDto item = getCurrentlyPlayingItem();
            if (item == null) {
                return null;
            }
            List<MediaSourceInfo> mediaSources = item.getMediaSources();

            if (mediaSources == null || mediaSources.isEmpty()) {
                return null;
            } else {
                // Prefer the media source with the same id as the item
                for (MediaSourceInfo mediaSource : mediaSources) {
                    if (item.getId().equals(UUIDSerializerKt.toUUIDOrNull(mediaSource.getId()))) {
                        return mediaSource;
                    }
                }
                // Or fallback to the first media source if none match
                return mediaSources.get(0);
            }
        }
    }

    public StreamInfo getCurrentStreamInfo() {
        return mCurrentStreamInfo;
    }

    public boolean canSeek() {
        return !isLiveTv;
    }

    public boolean isLiveTv() {
        return isLiveTv;
    }

    public int getSubtitleStreamIndex() {
        return (mCurrentOptions != null && mCurrentOptions.getSubtitleStreamIndex() != null) ? mCurrentOptions.getSubtitleStreamIndex() : -1;
    }

    public boolean isTranscoding() {
        // use or here so that true is the default since
        // this method is used to exclude features that may break unless we are sure playback is direct
        return mCurrentStreamInfo == null || mCurrentStreamInfo.getPlayMethod() == PlayMethod.TRANSCODE;
    }

    public boolean hasNextItem() {
        return mItems != null && mCurrentIndex < mItems.size() - 1;
    }

    public BaseItemDto getNextItem() {
        return hasNextItem() ? mItems.get(mCurrentIndex + 1) : null;
    }

    public boolean hasPreviousItem() {
        return mItems != null && mCurrentIndex - 1 >= 0;
    }

    public boolean isPlaying() {
        // since playbackController is so closely tied to videoManager, check if it is playing too since they can fall out of sync
        return mPlaybackState == PlaybackState.PLAYING && hasInitializedVideoManager() && mVideoManager.isPlaying();
    }

    public void playerErrorEncountered() {
        // reset the retry count if it's been more than 30s since previous error
        if (playbackRetries > 0 && Instant.now().toEpochMilli() - lastPlaybackError > 30000) {
            Timber.i("playback stabilized - retry count reset to 0 from %s", playbackRetries);
            playbackRetries = 0;
        }

        playbackRetries++;
        lastPlaybackError = Instant.now().toEpochMilli();

        if (playbackRetries < 3) {
            if (mFragment != null)
                Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.player_error));

            // Burning subtitles in requires a full video transcode, which is slow and fragile on
            // the server: if the encoder dies, the client waits forever for the first segment.
            // Retrying the identical request would just fail again, so drop the subtitles for
            // this attempt - the video can then be direct played and starts right away.
            if (burningSubs) {
                Timber.w("Retry %d: dropping subtitles and disabling burn-in", playbackRetries);
                burningSubs = false;
                dropSubtitlesForRetry = true;
                stop();
                play(mCurrentPosition, -1);
                return;
            }

            Timber.i("Player error encountered - retrying");
            stop();
            play(mCurrentPosition);
        } else {
            mPlaybackState = PlaybackState.ERROR;
            if (mFragment != null) {
                Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.too_many_errors));
                mFragment.closePlayer();
            }
        }
    }

    private void getDisplayModes() {
        if (mFragment == null)
            return;
        Display display = mFragment.requireActivity().getWindowManager().getDefaultDisplay();
        mDisplayModes = display.getSupportedModes();
        Timber.i("** Available display refresh rates:");
        for (Display.Mode mDisplayMode : mDisplayModes) {
            Timber.i("display mode %s - %dx%d@%f", mDisplayMode.getModeId(), mDisplayMode.getPhysicalWidth(), mDisplayMode.getPhysicalHeight(), mDisplayMode.getRefreshRate());
        }
    }

    @Nullable
    private Display.Mode findBestDisplayMode(@Nullable MediaStream videoStream) {
        if (mFragment == null || mDisplayModes == null || videoStream == null || videoStream.getRealFrameRate() == null || videoStream.getWidth() == null || videoStream.getHeight() == null)
            return null;

        int curWeight = 0;
        Display.Mode bestMode = null;
        int sourceRate = Math.round(videoStream.getRealFrameRate() * 100);

        Display.Mode defaultMode = mFragment.requireActivity().getWindowManager().getDefaultDisplay().getMode();

        Timber.d("trying to find display mode for video: %dx%d@%f", videoStream.getWidth(), videoStream.getHeight(), videoStream.getRealFrameRate());
        for (Display.Mode mode : mDisplayModes) {
            Timber.d("considering display mode: %s - %dx%d@%f", mode.getModeId(), mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate());

            // Skip unwanted display modes
            if (mode.getPhysicalWidth() < 1280 || mode.getPhysicalHeight() < 720)  // Skip non-HD
                continue;

            if (mode.getPhysicalWidth() < videoStream.getWidth() || mode.getPhysicalHeight() < videoStream.getHeight())  // Disallow resolution downgrade
                continue;

            int rate = Math.round(mode.getRefreshRate() * 100);
            if (rate != sourceRate && rate != sourceRate * 2 && rate != Math.round(sourceRate * 2.5)) // Skip inappropriate rates
                continue;

            Timber.i("qualifying display mode: %s - %dx%d@%f", mode.getModeId(), mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate());

            // if scaling on-device, keep native resolution modes at diff 0 (best score)
            // for other resolutions when scaling on device, or if scaling on tv, score based on distance from media resolution

            // use -1 as the default so, with SCALE_ON_DEVICE, a mode at native resolution will rank higher than
            // a mode with equal refresh rate and the same resolution as the media
            int resolutionDifference = -1;
            if ((refreshRateSwitchingBehavior == RefreshRateSwitchingBehavior.SCALE_ON_DEVICE &&
                    !(mode.getPhysicalWidth() == defaultMode.getPhysicalWidth() && mode.getPhysicalHeight() == defaultMode.getPhysicalHeight())) ||

                    refreshRateSwitchingBehavior == RefreshRateSwitchingBehavior.SCALE_ON_TV) {

                resolutionDifference = Math.abs(mode.getPhysicalWidth() - videoStream.getWidth());
            }
            int refreshRateDifference = rate - sourceRate;

            // use 100,000 to account for refresh rates 120Hz+ (at 120Hz rate == 12,000)
            int weight = 100000 - refreshRateDifference + 100000 - resolutionDifference;

            if (weight > curWeight) {
                Timber.d("preferring mode: %s - %dx%d@%f", mode.getModeId(), mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate());
                curWeight = weight;
                bestMode = mode;
            }
        }

        return bestMode;
    }

    private void setRefreshRate(MediaStream videoStream) {
        if (videoStream == null || mFragment == null) {
            Timber.e("Null video stream attempting to set refresh rate");
            return;
        }

        Display.Mode current = mFragment.requireActivity().getWindowManager().getDefaultDisplay().getMode();
        Display.Mode best = findBestDisplayMode(videoStream);
        if (best != null) {
            Timber.i("*** Best refresh mode is: %s - %dx%d/%f",
                    best.getModeId(), best.getPhysicalWidth(), best.getPhysicalHeight(), best.getRefreshRate());
            if (current.getModeId() != best.getModeId()) {
                Timber.i("*** Attempting to change refresh rate from: %s - %dx%d@%f", current.getModeId(), current.getPhysicalWidth(),
                        current.getPhysicalHeight(), current.getRefreshRate());
                WindowManager.LayoutParams params = mFragment.requireActivity().getWindow().getAttributes();
                params.preferredDisplayModeId = best.getModeId();
                mFragment.requireActivity().getWindow().setAttributes(params);
            } else {
                Timber.i("Display is already in best mode");
            }
        } else {
            Timber.i("*** Unable to find display mode for refresh rate: %f", videoStream.getRealFrameRate());
        }
    }

    /**
     * Restores the refresh rate of the display.
     *
     * The refresh rate is changed to match the frame rate of the video, but a
     * display that stays at 24 Hz makes the whole interface feel slow once the
     * playback has ended.
     */
    private void resetRefreshRate() {
        if (mFragment == null) return;

        WindowManager.LayoutParams params = mFragment.requireActivity().getWindow().getAttributes();
        if (params.preferredDisplayModeId == 0) return;

        Timber.i("Restoring the default refresh rate");
        params.preferredDisplayModeId = 0;
        mFragment.requireActivity().getWindow().setAttributes(params);
    }

    // central place to update mCurrentPosition
    private void refreshCurrentPosition() {
        long newPos = -1;

        if (isLiveTv && mCurrentProgramStart != null) {
            newPos = getRealTimeProgress();
            // live tv
        } else if (hasInitializedVideoManager()) {
            if (currentSkipPos != 0 || (!isPlaying() && mSeekPosition != -1)) {
                newPos = mSeekPosition;
                // use seekPosition until playback starts
            } else if (isPlaying()) {
                if (finishedInitialSeek) {
                    // playback has started following initial seek for direct play and hls
                    // get current position and reset seekPosition
                    newPos = mVideoManager.getCurrentPosition();
                    mSeekPosition = -1;
                } else if (wasSeeking) {
                    // the initial seek for direct play and hls completed
                    finishedInitialSeek = true;
                } else if (mSeekPosition != -1) {
                    // the initial seek for direct play and hls hasn't happened yet
                    newPos = mSeekPosition;
                }
                wasSeeking = false;
            }
        }
        // use original value if new one isn't available
        mCurrentPosition = newPos != -1 ? newPos : mCurrentPosition;
    }

    public void play(long position) {
        play(position, null);
    }

    protected void play(long position, @Nullable Integer forcedSubtitleIndex) {
        String forcedAudioLanguage = videoQueueManager.getValue().getLastPlayedAudioLanguageIsoCode();
        Timber.i("Play called from state: %s with pos: %d, sub index: %d and forced audio: %s", mPlaybackState, position, forcedSubtitleIndex, forcedAudioLanguage);

        if (mFragment == null) {
            Timber.w("mFragment is null, returning");
            return;
        }

        if (position < 0) {
            Timber.i("Negative start requested - adjusting to zero");
            position = 0;
        }

        switch (mPlaybackState) {
            case PLAYING:
                // do nothing
                break;
            case PAUSED:
                if (!hasInitializedVideoManager()) {
                    return;
                }
                // just resume
                mVideoManager.play();
                mPlaybackState = PlaybackState.PLAYING; // won't get another onPrepared call
                mFragment.setFadingEnabled(true);
                startReportLoop();
                break;
            case BUFFERING:
                // onPrepared should take care of it
                break;
            case IDLE:
                // start new playback

                // set mSeekPosition so the seekbar will not default to 0:00
                mSeekPosition = position;
                mCurrentPosition = 0;

                mFragment.setFadingEnabled(false);

                BaseItemDto item = getCurrentlyPlayingItem();

                if (item == null) {
                    Timber.w("item is null - aborting play");
                    Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_cannot_play));
                    mFragment.closePlayer();
                    return;
                }

                // make sure item isn't missing
                if (item.getLocationType() == LocationType.VIRTUAL) {
                    if (hasNextItem()) {
                        new AlertDialog.Builder(mFragment.getContext())
                                .setTitle(R.string.episode_missing)
                                .setMessage(R.string.episode_missing_message)
                                .setPositiveButton(R.string.lbl_yes, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        next();
                                    }
                                })
                                .setNegativeButton(R.string.lbl_no, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        mFragment.closePlayer();
                                    }
                                })
                                .create()
                                .show();
                    } else {
                        new AlertDialog.Builder(mFragment.getContext())
                                .setTitle(R.string.episode_missing)
                                .setMessage(R.string.episode_missing_message_2)
                                .setPositiveButton(R.string.lbl_ok, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        mFragment.closePlayer();
                                    }
                                })
                                .create()
                                .show();
                    }
                    return;
                }

                isLiveTv = item.getType() == BaseItemKind.TV_CHANNEL;
                startSpinner();

                // undo setting mSeekPosition for liveTV
                if (isLiveTv) mSeekPosition = -1;

                VideoOptions internalOptions = buildExoPlayerOptions(forcedSubtitleIndex, forcedAudioLanguage, item);

                playInternal(getCurrentlyPlayingItem(), position, internalOptions);
                mPlaybackState = PlaybackState.BUFFERING;
                mFragment.setPlayPauseActionState(0);
                mFragment.setCurrentTime(position);

                long duration = getCurrentlyPlayingItem().getRunTimeTicks() != null ? getCurrentlyPlayingItem().getRunTimeTicks() / 10000 : -1;
                if (mVideoManager != null)
                    mVideoManager.setMetaDuration(duration);

                break;
        }
    }

    @NonNull
    private VideoOptions buildExoPlayerOptions(
            @Nullable Integer forcedSubtitleIndex,
            @Nullable String forcedAudioLanguage,
            BaseItemDto item) {
        VideoOptions internalOptions = new VideoOptions();
        internalOptions.setItemId(item.getId());
        internalOptions.setMediaSources(item.getMediaSources());
        internalOptions.setAlwaysBurnInSubtitleWhenTranscoding(userPreferences.getValue().get(UserPreferences.Companion.getSubtitlesBurnDuringTranscode()));
        if (playbackRetries > 0 || (isLiveTv && !directStreamLiveTv)) internalOptions.setEnableDirectPlay(false);
        if (playbackRetries > 1) internalOptions.setEnableDirectStream(false);
        if (mCurrentOptions != null) {
            internalOptions.setSubtitleStreamIndex(mCurrentOptions.getSubtitleStreamIndex());
            internalOptions.setAudioStreamIndex(mCurrentOptions.getAudioStreamIndex());
        }
        if (forcedSubtitleIndex != null) {
            internalOptions.setSubtitleStreamIndex(forcedSubtitleIndex);
        }
        MediaSourceInfo currentMediaSource = getCurrentMediaSource();
        if (forcedAudioLanguage != null && currentMediaSource != null && currentMediaSource.getMediaStreams() != null) {
            // find the first audio stream with the requested language
            for (MediaStream stream : currentMediaSource.getMediaStreams()) {
                if (stream.getType() == MediaStreamType.AUDIO && forcedAudioLanguage.equals(stream.getLanguage())) {
                    internalOptions.setAudioStreamIndex(stream.getIndex());
                    break;
                }
            }
        }
        if (!isLiveTv && currentMediaSource != null) {
            internalOptions.setMediaSourceId(currentMediaSource.getId());
        }

        // Keep high-load media away from the TV decoder on low-end devices. A direct stream
        // still carries the original video bitrate, so only a real server transcode helps when
        // the source is 4K or too expensive for the local decoder.
        if (shouldPreferServerTranscoding(currentMediaSource)) {
            Timber.i("Low-performance device: requesting server transcode for high-load media");
            internalOptions.setEnableDirectPlay(false);
            internalOptions.setEnableDirectStream(false);
        }

        DeviceProfile internalProfile = DeviceProfileKt.createDeviceProfile(
                mFragment.getContext(),
                userPreferences.getValue(),
                get(ServerVersion.class),
                getSourceVideoBitrate(currentMediaSource),
                getContainerBitrate(currentMediaSource)
        );
        internalOptions.setProfile(internalProfile);
        return internalOptions;
    }

    /**
     * The bitrate of the video stream of the source file.
     *
     * The server scales a transcoded stream down when it is asked for a bitrate below
     * the bitrate of the source file, so the bitrate of the source is needed to keep
     * the resolution of the original.
     */
    @Nullable
    private Integer getSourceVideoBitrate(@Nullable MediaSourceInfo mediaSource) {
        if (mediaSource == null || mediaSource.getMediaStreams() == null) return null;

        for (MediaStream stream : mediaSource.getMediaStreams()) {
            if (stream.getType() == MediaStreamType.VIDEO && stream.getBitRate() != null) {
                return stream.getBitRate();
            }
        }

        return null;
    }

    /**
     * The bitrate of the whole source file, every track included.
     *
     * The server compares the requested bitrate with the bitrate of the container and not
     * with the bitrate of the video stream, so a file that carries several audio tracks is
     * transcoded when the request only covers the video and one audio track. The bitrate of
     * the container is therefore part of the request as well.
     */
    @Nullable
    private Integer getContainerBitrate(@Nullable MediaSourceInfo mediaSource) {
        if (mediaSource == null || mediaSource.getBitrate() == null) return null;

        long bitrate = mediaSource.getBitrate();
        if (bitrate <= 0 || bitrate > Integer.MAX_VALUE) return null;

        return (int) bitrate;
    }

    private boolean shouldPreferServerTranscoding(@Nullable MediaSourceInfo mediaSource) {
        if (mediaSource == null || mFragment == null
                || !Boolean.TRUE.equals(mediaSource.getSupportsTranscoding())) {
            return false;
        }

        PlaybackStrategy strategy = userPreferences.getValue().get(UserPreferences.Companion.getPlaybackStrategy());
        if (strategy == PlaybackStrategy.PREFER_DIRECT) return false;
        if (strategy == PlaybackStrategy.PREFER_SERVER_TRANSCODING) {
            return mediaSource.getMediaStreams() != null
                    && mediaSource.getMediaStreams().stream()
                    .anyMatch(stream -> stream.getType() == MediaStreamType.VIDEO);
        }
        if (!PerformanceProfile.isLowPerformanceDevice(mFragment.requireContext())) return false;

        Integer sourceBitrate = mediaSource.getBitrate();
        if (sourceBitrate != null && sourceBitrate > PerformanceProfile.LOW_PERFORMANCE_MAX_VIDEO_BITRATE) {
            return true;
        }
        if (mediaSource.getMediaStreams() == null) return false;

        for (MediaStream stream : mediaSource.getMediaStreams()) {
            if (stream.getType() != MediaStreamType.VIDEO) continue;

            Integer width = stream.getWidth();
            Integer height = stream.getHeight();
            Integer bitrate = stream.getBitRate();
            String codec = stream.getCodec() == null ? "" : stream.getCodec().toLowerCase(Locale.ROOT);
            boolean codecIsExpensiveForLegacyTv = codec.contains("av1") || codec.contains("vp9");
            boolean highFrameRate = stream.getRealFrameRate() != null && stream.getRealFrameRate() > 30.0;
            return codecIsExpensiveForLegacyTv
                    || (width != null && width > PerformanceProfile.LOW_PERFORMANCE_MAX_VIDEO_WIDTH)
                    || (height != null && height > PerformanceProfile.LOW_PERFORMANCE_MAX_VIDEO_HEIGHT)
                    || (bitrate != null && bitrate > PerformanceProfile.LOW_PERFORMANCE_MAX_VIDEO_BITRATE)
                    || (highFrameRate && width != null && width >= 1920);
        }

        return false;
    }

    private void playInternal(final BaseItemDto item, final Long position, final VideoOptions internalOptions) {
        final long playbackInfoRequestStartedAt = SystemClock.elapsedRealtime();
        if (isLiveTv) {
            updateTvProgramInfo();
            TvManager.setLastLiveTvChannel(item.getId());
            //internal/exo player
            Timber.i("Using internal player for Live TV");
            playbackManager.getValue().getVideoStreamInfo(mFragment, internalOptions, position * 10000, new Response<StreamInfo>(mFragment.getLifecycle()) {
                @Override
                public void onResponse(StreamInfo response) {
                    if (!isActive()) return;
                    Timber.i("Playback info resolved in %d ms using %s",
                            SystemClock.elapsedRealtime() - playbackInfoRequestStartedAt, response.getPlayMethod());
                    if (mVideoManager == null)
                        return;
                    mCurrentOptions = internalOptions;
                    startItem(item, position, response);
                }

                @Override
                public void onError(Exception exception) {
                    if (!isActive()) return;
                    Timber.e(exception, "Playback info failed after %d ms",
                            SystemClock.elapsedRealtime() - playbackInfoRequestStartedAt);
                    handlePlaybackInfoError(exception);
                }
            });
        } else {
            playbackManager.getValue().getVideoStreamInfo(mFragment, internalOptions, position * 10000, new Response<StreamInfo>(mFragment.getLifecycle()) {
                @Override
                public void onResponse(StreamInfo internalResponse) {
                    if (!isActive()) return;
                    Timber.i("Playback info resolved in %d ms using %s",
                            SystemClock.elapsedRealtime() - playbackInfoRequestStartedAt, internalResponse.getPlayMethod());
                    Timber.i("Internal player would %s", internalResponse.getPlayMethod().equals(PlayMethod.TRANSCODE) ? "transcode" : "direct stream");
                    if (mVideoManager == null)
                        return;
                    mCurrentOptions = internalOptions;
                    updateBurningSubs(internalResponse);
                    startItem(item, position, internalResponse);
                }

                @Override
                public void onError(Exception exception) {
                    if (!isActive()) return;
                    Timber.e(exception, "Unable to get stream info for internal player after %d ms",
                            SystemClock.elapsedRealtime() - playbackInfoRequestStartedAt);
                    // A direct-play request can fail before ExoPlayer is created, so the
                    // normal player-error callback is never emitted. Retry through direct
                    // stream and then full transcode instead of leaving the spinner running.
                    if (mPlaybackState == PlaybackState.BUFFERING) {
                        playerErrorEncountered();
                    } else {
                        handlePlaybackInfoError(exception);
                    }
                }
            });
        }
    }

    private void handlePlaybackInfoError(Exception exception) {
        Timber.e(exception, "Error getting playback stream info");
        if (mFragment == null) return;
        if (exception instanceof PlaybackException) {
            PlaybackException ex = (PlaybackException) exception;
            switch (ex.getErrorCode()) {
                case NOT_ALLOWED:
                    Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_playback_not_allowed));
                    break;
                case NO_COMPATIBLE_STREAM:
                    Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_playback_incompatible));
                    break;
                case RATE_LIMIT_EXCEEDED:
                    Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_playback_restricted));
                    break;
            }
        } else {
            Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_cannot_play));
        }
        if (mFragment != null) mFragment.closePlayer();
    }

    private void startItem(BaseItemDto item, long position, StreamInfo response) {
        if (!hasInitializedVideoManager() || !hasFragment()) {
            Timber.w("Error - attempting to play without:%s%s", hasInitializedVideoManager() ? "" : " [videoManager]", hasFragment() ? "" : " [overlay fragment]");
            return;
        }

        mCurrentOptions.setAudioStreamIndex(null); // reset audio stream index to allow auto selection on new item

        mStartPosition = position;
        mCurrentStreamInfo = response;
        MediaSourceInfo startMediaSource = response.getMediaSource();
        if (startMediaSource == null) {
            // A media source is required to describe the later playback. Without it the
            // playback would fail at the first access to the streams, so it is reported here.
            Timber.w("Playback info has no media source - aborting playback");
            handlePlaybackInfoError(null);
            return;
        }
        mCurrentOptions.setMediaSourceId(startMediaSource.getId());

        if (response.getMediaUrl() == null) {
            // If baking subtitles doesn't work (e.g. no permissions to transcode), disable them
            if (response.getSubtitleDeliveryMethod() == SubtitleDeliveryMethod.ENCODE && (startMediaSource.getDefaultSubtitleStreamIndex() == null || startMediaSource.getDefaultSubtitleStreamIndex() != -1)) {
                burningSubs = false;
                stop();
                play(position, -1);
            } else {
                handlePlaybackInfoError(null);
            }
            return;
        }

        // get subtitle info - prefer saved language preference over server default
        String lastSubtitleLanguage = videoQueueManager.getValue().getLastPlayedSubtitleLanguageIsoCode();
        if (lastSubtitleLanguage != null && lastSubtitleLanguage.isEmpty()) {
            // User explicitly disabled subtitles
            mCurrentOptions.setSubtitleStreamIndex(null);
        } else {
            Integer matchingIndex = null;

            // The language of the last playback comes first: the user picked it. The
            // requested stream is kept when it matches that language, so a restart that
            // switches between a baked and a plain subtitle does not select the baked one
            // again.
            if (lastSubtitleLanguage != null && response.getMediaSource().getMediaStreams() != null) {
                Integer requestedIndex = mCurrentOptions.getSubtitleStreamIndex();
                for (MediaStream stream : response.getMediaSource().getMediaStreams()) {
                    if (stream.getType() == MediaStreamType.SUBTITLE && lastSubtitleLanguage.equals(stream.getLanguage())) {
                        if (matchingIndex == null) matchingIndex = stream.getIndex();
                        if (requestedIndex != null && requestedIndex == stream.getIndex()) {
                            matchingIndex = requestedIndex;
                            break;
                        }
                    }
                }
            }

            // A subtitle in chinese comes next. The server usually has no default
            // subtitle and the preference of the user only remembers the language
            // of the last playback, so nothing was selected before and the
            // subtitles had to be picked by hand every time.
            if (matchingIndex == null) {
                matchingIndex = findPreferredSubtitle(response.getMediaSource().getMediaStreams());
            }

            // The default of the server comes next.
            if (matchingIndex == null) {
                Integer defaultIndex = response.getMediaSource().getDefaultSubtitleStreamIndex();
                if (defaultIndex != null && defaultIndex >= 0) matchingIndex = defaultIndex;
            }

            // Any text subtitle the device is able to show comes last, if the user
            // asked for that.
            if (matchingIndex == null && userPreferences.getValue().get(UserPreferences.Companion.getSubtitlesAutoSelect())) {
                matchingIndex = findTextSubtitle(response.getMediaSource().getMediaStreams());
            }

            mCurrentOptions.setSubtitleStreamIndex(matchingIndex);
        }

        // The previous attempt failed while the server was burning subtitles into the video, so
        // this attempt was started without them. Make sure the preference resolution above does
        // not silently select the stream again and re-trigger the failing burn-in.
        if (dropSubtitlesForRetry) {
            dropSubtitlesForRetry = false;
            mCurrentOptions.setSubtitleStreamIndex(null);
            Timber.i("Subtitle selection skipped for this attempt to avoid burn-in");
        }

        setDefaultAudioIndex(response);
        Timber.i("default audio index set to %s remote default %s", mDefaultAudioIndex, response.getMediaSource().getDefaultAudioStreamIndex());
        Timber.i("default sub index set to %s remote default %s", mCurrentOptions.getSubtitleStreamIndex(), response.getMediaSource().getDefaultSubtitleStreamIndex());

        Long mbPos = position * 10000;

        // set refresh rate
        if (refreshRateSwitchingBehavior != RefreshRateSwitchingBehavior.DISABLED) {
            setRefreshRate(JavaCompat.getVideoStream(response.getMediaSource()));
        }

        // set playback speed to user selection, or 1 if we're watching live-tv
        if (mVideoManager != null)
            mVideoManager.setPlaybackSpeed(isLiveTv() ? 1.0f : mRequestedPlaybackSpeed);

        if (mFragment != null) mFragment.updateDisplay();

        if (mVideoManager != null) {
            mVideoManager.setMediaStreamInfo(api.getValue(), response);
        }

        // Start playback right away. The intro/outro segment lookup below is a network request and
        // used to gate start(), which kept the screen black until that request finished.
        startVideoPlayback(item, response, mbPos);

        // Media segments are only used for the "skip intro" overlay, so resolve them in parallel
        // with playback instead of blocking the first frame on them.
        PlaybackControllerHelperKt.applyMediaSegments(this, item, () -> null);
    }

    private void startVideoPlayback(BaseItemDto item, StreamInfo response, Long mbPos) {
        // Set video start delay
        long videoStartDelay = userPreferences.getValue().get(UserPreferences.Companion.getVideoStartDelay());
        if (videoStartDelay > 0) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mVideoManager != null) {
                        mVideoManager.start();
                    }
                }
            }, videoStartDelay);
        } else if (mVideoManager != null) {
            mVideoManager.start();
        }

        dataRefreshService.getValue().setLastPlayedItem(item);
        reportingHelper.getValue().reportStart(mFragment, PlaybackController.this, item, response, mbPos, false);
    }

    public void startSpinner() {
        spinnerOff = false;
        // The server needs time to prepare the stream, and the video surface is
        // plain black during that time. The indicator tells the user that the
        // playback is starting.
        if (mFragment != null) mFragment.setBufferingVisible(true);
    }

    public void stopSpinner() {
        spinnerOff = true;
        if (mFragment != null) mFragment.setBufferingVisible(false);
    }

    public int getAudioStreamIndex() {
        int currIndex = -1;

        // Use stream index from mCurrentOptions if it's set.
        // This should be null until the player has been queried at least once after playback starts
        //
        // Use DefaultAudioStreamIndex for transcoding since they are encoded with only one stream
        //
        // Otherwise, query the players
        if (mCurrentOptions.getAudioStreamIndex() != null) {
            currIndex = mCurrentOptions.getAudioStreamIndex();
        } else if (isTranscoding() && getCurrentMediaSource() != null && getCurrentMediaSource().getDefaultAudioStreamIndex() != null) {
            currIndex = getCurrentMediaSource().getDefaultAudioStreamIndex();
        } else if (hasInitializedVideoManager() && !isTranscoding()) {
            BaseItemDto item = getCurrentlyPlayingItem();
            if (item == null)
                return -1;
            currIndex = mVideoManager.getExoPlayerTrack(MediaStreamType.AUDIO, item.getMediaStreams());
        }
        return currIndex;
    }

    private Integer bestGuessAudioTrack(MediaSourceInfo info) {
        if (info == null || info.getMediaStreams() == null)
            return null;

        boolean videoFound = false;
        for (MediaStream track : info.getMediaStreams()) {
            if (track.getType() == MediaStreamType.VIDEO) {
                videoFound = true;
            } else {
                if (videoFound && track.getType() == MediaStreamType.AUDIO)
                    return track.getIndex();
            }
        }
        return null;
    }

    private Integer lastChosenLanguageAudioTrack(MediaSourceInfo info) {
        if (info == null || info.getMediaStreams() == null)
            return null;

        boolean videoFound = false;
        for (MediaStream track : info.getMediaStreams()) {
            if (track.getType() == MediaStreamType.VIDEO) {
                videoFound = true;
            } else {
                if (videoFound
                    && track.getType() == MediaStreamType.AUDIO
                    && (track.getLanguage() != null && track.getLanguage().equals(videoQueueManager.getValue().getLastPlayedAudioLanguageIsoCode()))
                )
                    return track.getIndex();
            }
        }
        return null;
    }

    private void setDefaultAudioIndex(StreamInfo info) {
        if (mDefaultAudioIndex != -1)
            return;

        MediaSourceInfo source = info.getMediaSource();
        if (source == null)
            return;

        Integer lastChosenLanguage = lastChosenLanguageAudioTrack(source);
        Integer remoteDefault = source.getDefaultAudioStreamIndex();
        Integer bestGuess = bestGuessAudioTrack(source);

        if (lastChosenLanguage != null)
            mDefaultAudioIndex = lastChosenLanguage;
        else if (remoteDefault != null)
            mDefaultAudioIndex = remoteDefault;
        else if (bestGuess != null)
            mDefaultAudioIndex = bestGuess;
        Timber.d("default audio index set to %s", mDefaultAudioIndex);
    }

    public void switchAudioStream(int index) {
        if (!(isPlaying() || isPaused()) || index < 0)
            return;

        MediaSourceInfo currentMediaSource = getCurrentMediaSource();
        if (currentMediaSource == null
                || currentMediaSource.getMediaStreams() == null
                || index >= currentMediaSource.getMediaStreams().size()) {
            return;
        }

        String lastAudioIsoCode = videoQueueManager.getValue().getLastPlayedAudioLanguageIsoCode();
        String currentAudioIsoCode = currentMediaSource.getMediaStreams().get(index).getLanguage();

        if (currentAudioIsoCode != null
                && (lastAudioIsoCode == null || !lastAudioIsoCode.equals(currentAudioIsoCode))) {
            videoQueueManager.getValue().setLastPlayedAudioLanguageIsoCode(
                    currentAudioIsoCode
            );
        }

        int currAudioIndex = getAudioStreamIndex();
        Timber.i("trying to switch audio stream from %s to %s", currAudioIndex, index);
        if (currAudioIndex == index) {
            Timber.d("skipping setting audio stream, already set to requested index %s", index);
            if (mCurrentOptions.getAudioStreamIndex() == null || mCurrentOptions.getAudioStreamIndex() != index) {
                Timber.i("setting mCurrentOptions audio stream index from %s to %s", mCurrentOptions.getAudioStreamIndex(), index);
                mCurrentOptions.setAudioStreamIndex(index);
            }
            return;
        }

        // get current timestamp first
        refreshCurrentPosition();

        if (!isTranscoding() && mVideoManager.setExoPlayerTrack(index, MediaStreamType.AUDIO, currentMediaSource.getMediaStreams())) {
            mCurrentOptions.setMediaSourceId(currentMediaSource.getId());
            mCurrentOptions.setAudioStreamIndex(index);
        } else {
            startSpinner();
            mCurrentOptions.setMediaSourceId(currentMediaSource.getId());
            mCurrentOptions.setAudioStreamIndex(index);
            stop();
            playInternal(getCurrentlyPlayingItem(), mCurrentPosition, mCurrentOptions);
            mPlaybackState = PlaybackState.BUFFERING;
        }
    }

    public void pause() {
        Timber.i("pause called at %s", mCurrentPosition);
        // if playback is paused and the seekbar is scrubbed, it will call pause even if already paused
        if (mPlaybackState == PlaybackState.PAUSED) {
            Timber.d("already paused, ignoring");
            return;
        }
        mPlaybackState = PlaybackState.PAUSED;
        if (hasInitializedVideoManager()) mVideoManager.pause();
        if (mFragment != null) {
            mFragment.setFadingEnabled(false);
            mFragment.setPlayPauseActionState(0);
        }

        stopReportLoop();
        // start a slower report for pause state to keep session alive
        startPauseReportLoop();

    }

    public void playPause() {
        switch (mPlaybackState) {
            case PLAYING:
                pause();
                break;
            case PAUSED:
            case IDLE:
                stopReportLoop();
                play(getCurrentPosition());
                break;
        }
    }

    public void stop() {
        // The connection can be measured again now that no stream uses it.
        ConnectionMonitor.INSTANCE.setPlaybackActive(false);

        refreshCurrentPosition();
        Timber.i("stop called at %s", mCurrentPosition);
        cancelInitialSeek();
        stopReportLoop();
        if (mPlaybackState != PlaybackState.IDLE && mPlaybackState != PlaybackState.UNDEFINED) {
            mPlaybackState = PlaybackState.IDLE;

            if (mVideoManager != null && mVideoManager.isInitialized()) mVideoManager.stopPlayback();
            if (getCurrentlyPlayingItem() != null && mCurrentStreamInfo != null) {
                Long mbPos = mCurrentPosition * 10000;
                reportingHelper.getValue().reportStopped(mFragment, getCurrentlyPlayingItem(), mCurrentStreamInfo, mbPos);
            }
            clearPlaybackSessionOptions();
        }
    }

    public void refreshStream() {
        // get current timestamp first
        refreshCurrentPosition();

        stop();
        play(mCurrentPosition);
    }

    public void endPlayback(Boolean closeActivity) {
        if (closeActivity && mFragment != null) {
            mFragment.closePlayer();
        }
        stop();
        resetRefreshRate();
        if (mVideoManager != null)
            mVideoManager.destroy();
        mFragment = null;
        mVideoManager = null;
        resetPlayerErrors();
    }

    public void endPlayback() {
        endPlayback(false);
    }

    private void resetPlayerErrors() {
        playbackRetries = 0;
    }

    private void clearPlaybackSessionOptions() {
        cancelInitialSeek();
        mDefaultAudioIndex = -1;
        mSeekPosition = -1;
        finishedInitialSeek = false;
        wasSeeking = false;
        burningSubs = false;
        mCurrentStreamInfo = null;
    }

    public void next() {
        Timber.d("Next called.");
        if (mCurrentIndex < mItems.size() - 1) {
            stop();
            resetPlayerErrors();
            mCurrentIndex++;
            videoQueueManager.getValue().setCurrentMediaPosition(mCurrentIndex);
            Timber.i("Moving to index: %d out of %d total items.", mCurrentIndex, mItems.size());
            spinnerOff = false;
            play(0);
        }
    }

    public void prev() {
        Timber.d("Prev called.");
        if (mCurrentIndex > 0 && mItems.size() > 0) {
            stop();
            resetPlayerErrors();
            mCurrentIndex--;
            videoQueueManager.getValue().setCurrentMediaPosition(mCurrentIndex);
            Timber.i("Moving to index: %d out of %d total items.", mCurrentIndex, mItems.size());
            spinnerOff = false;
            play(0);
        }
    }

    /** Jump to an episode selected outside the currently loaded queue. */
    public void playEpisodeQueue(List<BaseItemDto> items) {
        if (items == null || items.isEmpty()) return;
        stop();
        resetPlayerErrors();
        mItems = items;
        mCurrentIndex = 0;
        videoQueueManager.getValue().setCurrentVideoQueue(items);
        spinnerOff = false;
        play(0);
    }

    public void fastForward() {
        UserSettingPreferences prefs = KoinJavaComponent.<UserSettingPreferences>get(UserSettingPreferences.class);
        skip(prefs.get(UserSettingPreferences.Companion.getSkipForwardLength()));
    }

    public void rewind() {
        UserSettingPreferences prefs = KoinJavaComponent.<UserSettingPreferences>get(UserSettingPreferences.class);
        skip(-prefs.get(UserSettingPreferences.Companion.getSkipBackLength()));
    }

    public void seek(long pos) {
        seek(pos, false);
    }

    public void seek(long pos, boolean skipToNext) {
        if (pos <= 0) pos = 0;

        Timber.i("Trying to seek from %s to %d", mCurrentPosition, pos);
        Timber.d("Container: %s", mCurrentStreamInfo == null ? "unknown" : mCurrentStreamInfo.getContainer());

        if (!hasInitializedVideoManager()) {
            return;
        }

        if (wasSeeking) {
            Timber.d("Previous seek has not finished - cancelling seek from %s to %d", mCurrentPosition, pos);
            if (isPaused()) {
                refreshCurrentPosition();
                play(mCurrentPosition);
            }
            return;
        }
        wasSeeking = true;

        // Stop playback when the requested seek position is at the end of the video
        if (skipToNext && pos >= (getDuration() - 100)) {
            // Since we've skipped ahead, set the current position so the PlaybackStopInfo will report the correct end time
            mCurrentPosition = getDuration();
            // Make sure we also set the seek positions so mCurrentPosition won't get overwritten in refreshCurrentPosition()
            currentSkipPos = mCurrentPosition;
            mSeekPosition = mCurrentPosition;
            // Finalize item playback
            itemComplete();
            return;
        }

        if (pos >= getDuration()) pos = getDuration();

        // set seekPosition so real position isn't used until playback starts again
        mSeekPosition = pos;

        if (mCurrentStreamInfo == null) return;

        // rebuild the stream
        // if an older device uses exoplayer to play a transcoded stream but falls back to the generic http stream instead of hls, rebuild the stream
        if (!mVideoManager.isSeekable()) {
            Timber.d("Seek method - rebuilding the stream");
            //mkv transcodes require re-start of stream for seek
            mVideoManager.stopPlayback();
            mPlaybackState = PlaybackState.BUFFERING;

            playbackManager.getValue().changeVideoStream(mFragment, mCurrentStreamInfo, mCurrentOptions, pos * 10000, new Response<StreamInfo>(mFragment.getLifecycle()) {
                @Override
                public void onResponse(StreamInfo response) {
                    if (!isActive()) return;
                    mCurrentStreamInfo = response;

                    updateBurningSubs(response);

                    if (mVideoManager != null) {
                        mVideoManager.setMediaStreamInfo(api.getValue(), response);
                        mVideoManager.start();
                    }
                }

                @Override
                public void onError(Exception exception) {
                    if (!isActive()) return;
                    if (mFragment != null)
                        Utils.showToast(mFragment.getContext(), R.string.msg_video_playback_error);
                    Timber.e(exception, "Error trying to seek transcoded stream");
                    // call stop so playback can be retried by the user
                    stop();
                }
            });
        } else {
            // use the same approach to directplay seeking as setOnProgressListener
            // set state to SEEKING
            // if seek succeeds call play and mirror the logic in play() for unpausing. if fails call pause()
            // stopProgressLoop() being called at the beginning of startProgressLoop keeps this from breaking. otherwise it would start twice
            // if seek() is called from skip()
            mPlaybackState = PlaybackState.SEEKING;
            if (mVideoManager.seekTo(pos) < 0) {
                if (mFragment != null)
                    Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.seek_error));
                pause();
            } else {
                mVideoManager.play();
                mPlaybackState = PlaybackState.PLAYING;
                if (mFragment != null) mFragment.setFadingEnabled(true);
                startReportLoop();
            }
        }
    }

    private long currentSkipPos = 0;
    private final Runnable skipRunnable = () -> {
        if (!(isPlaying() || isPaused())) return; // in case we completed since this was requested

        seek(currentSkipPos);
        currentSkipPos = 0;
    };

    private void skip(int msec) {
        if (hasInitializedVideoManager() && (isPlaying() || isPaused()) && spinnerOff && mVideoManager.getCurrentPosition() > 0) { //guard against skipping before playback has truly begun
            mHandler.removeCallbacks(skipRunnable);
            refreshCurrentPosition();
            currentSkipPos = Utils.getSafeSeekPosition((currentSkipPos == 0 ? mCurrentPosition : currentSkipPos) + msec, getDuration());

            Timber.i("Skip amount requested was %s. Calculated position is %s", msec, currentSkipPos);
            Timber.i("Duration reported as: %s current pos: %s", getDuration(), mCurrentPosition);

            mSeekPosition = currentSkipPos;
            mHandler.postDelayed(skipRunnable, 800);
        }
    }

    public void updateTvProgramInfo() {
        // Get the current program info when playing a live TV channel
        final BaseItemDto channel = getCurrentlyPlayingItem();
        if (channel.getType() == BaseItemKind.TV_CHANNEL) {
            PlaybackControllerHelperKt.getLiveTvChannel(this, channel.getId(), updatedChannel -> {
                BaseItemDto program = updatedChannel.getCurrentProgram();
                if (program != null) {
                    mCurrentProgramEnd = program.getEndDate();
                    mCurrentProgramStart = program.getStartDate();
                    if (mFragment != null) mFragment.updateDisplay();
                }
                return null;
            });
        }
    }

    private long getRealTimeProgress() {
        if (mCurrentProgramStart != null) {
            return Duration.between(mCurrentProgramStart, LocalDateTime.now()).toMillis();
        }
        return 0;
    }

    private long getTimeShiftedProgress() {
        refreshCurrentPosition();
        return !directStreamLiveTv ? mCurrentPosition + (mCurrentTranscodeStartTime - (mCurrentProgramStart == null ? 0 : mCurrentProgramStart.toInstant(ZoneOffset.UTC).toEpochMilli())) : getRealTimeProgress();
    }

    private void startReportLoop() {
        if (mCurrentStreamInfo == null) return;

        stopReportLoop();
        reportingHelper.getValue().reportProgress(mFragment, this, getCurrentlyPlayingItem(), getCurrentStreamInfo(), mCurrentPosition * 10000, false);
        mReportLoop = new Runnable() {
            @Override
            public void run() {
                if (isPlaying()) {
                    refreshCurrentPosition();
                    long currentTime = isLiveTv ? getTimeShiftedProgress() : mCurrentPosition;

                    reportingHelper.getValue().reportProgress(mFragment, PlaybackController.this, getCurrentlyPlayingItem(), getCurrentStreamInfo(), currentTime * 10000, false);
                }
                if (mPlaybackState != PlaybackState.UNDEFINED && mPlaybackState != PlaybackState.IDLE) {
                    mHandler.postDelayed(this, PROGRESS_REPORTING_INTERVAL);
                }
            }
        };
        mHandler.postDelayed(mReportLoop, PROGRESS_REPORTING_INTERVAL);
    }

    private void startPauseReportLoop() {
        stopReportLoop();
        if (mCurrentStreamInfo == null) return;
        reportingHelper.getValue().reportProgress(mFragment, this, getCurrentlyPlayingItem(), mCurrentStreamInfo, mCurrentPosition * 10000, true);
        mReportLoop = new Runnable() {
            @Override
            public void run() {
                BaseItemDto currentItem = getCurrentlyPlayingItem();
                if (currentItem == null) {
                    // Loop was called while nothing was playing!
                    stopReportLoop();
                    return;
                }

                if (mPlaybackState != PlaybackState.PAUSED) {
                    // Playback is not paused anymore, stop reporting
                    return;
                }
                refreshCurrentPosition();
                long currentTime = isLiveTv ? getTimeShiftedProgress() : mCurrentPosition;
                if (isLiveTv && !directStreamLiveTv && mFragment != null) {
                    mFragment.setSecondaryTime(getRealTimeProgress());
                }

                reportingHelper.getValue().reportProgress(mFragment, PlaybackController.this, currentItem, getCurrentStreamInfo(), currentTime * 10000, true);
                mHandler.postDelayed(this, PROGRESS_REPORTING_PAUSE_INTERVAL);
            }
        };
        mHandler.postDelayed(mReportLoop, PROGRESS_REPORTING_PAUSE_INTERVAL);
    }

    private void stopReportLoop() {
        if (mHandler != null && mReportLoop != null) {
            mHandler.removeCallbacks(mReportLoop);
        }
    }

    private void initialSeek(final long position) {
        cancelInitialSeek();
        mInitialSeekDeadline = SystemClock.uptimeMillis() + INITIAL_SEEK_TIMEOUT_MS;
        mInitialSeekRunnable = new Runnable() {
            @Override
            public void run() {
                if (mVideoManager == null) {
                    mInitialSeekRunnable = null;
                    return;
                }
                if (mVideoManager.getDuration() <= 0) {
                    // Use the player timeline when it becomes available, but
                    // never keep a 25ms polling loop alive indefinitely.
                    if (SystemClock.uptimeMillis() >= mInitialSeekDeadline) {
                        Timber.w("Initial seek timed out while duration was unavailable");
                        mInitialSeekRunnable = null;
                        finishedInitialSeek = true;
                        stopSpinner();
                        return;
                    }
                    mHandler.postDelayed(this, INITIAL_SEEK_RETRY_INTERVAL_MS);
                } else if (mVideoManager.isSeekable()) {
                    mInitialSeekRunnable = null;
                    seek(position);
                } else {
                    mInitialSeekRunnable = null;
                    finishedInitialSeek = true;
                }
            }
        };
        mHandler.post(mInitialSeekRunnable);
    }

    private void cancelInitialSeek() {
        if (mHandler != null && mInitialSeekRunnable != null) {
            mHandler.removeCallbacks(mInitialSeekRunnable);
            mInitialSeekRunnable = null;
        }
    }

    private void itemComplete() {
        interactionTracker.onEpisodeWatched();
        stop();
        resetPlayerErrors();

        BaseItemDto nextItem = getNextItem();
        BaseItemDto curItem = getCurrentlyPlayingItem();
        if (nextItem == null || curItem == null) {
            endPlayback(true);
            return;
        }

        Timber.i("Moving to next queue item. Index: %s", (mCurrentIndex + 1));
        boolean stillWatchingEnabled = userPreferences.getValue().get(UserPreferences.Companion.getStillWatchingBehavior()) != StillWatchingBehavior.DISABLED;
        boolean nextUpEnabled = userPreferences.getValue().get(UserPreferences.Companion.getNextUpBehavior()) != NextUpBehavior.DISABLED;
        if ((stillWatchingEnabled || nextUpEnabled) && curItem.getType() != BaseItemKind.TRAILER) {
            mCurrentIndex++;
            videoQueueManager.getValue().setCurrentMediaPosition(mCurrentIndex);
            spinnerOff = false;

            if (mFragment != null) {
                if (stillWatchingEnabled && interactionTracker.getShowStillWatching()) {
                    // Show "Still Watching" fragment
                    mFragment.showStillWatching(nextItem.getId());
                } else if (nextUpEnabled) {
                    // Show "Next Up" fragment
                    mFragment.showNextUp(nextItem.getId());
                }
            }
            endPlayback();
        } else {
            next();
        }
    }

    @Override
    public void onPlaybackSpeedChange(float newSpeed) {
        // TODO, implement speed change handling
    }

    @Override
    public void onPrepared() {
        // The connection is not measured while a playback is running, see ConnectionMonitor.
        ConnectionMonitor.INSTANCE.setPlaybackActive(true);

        if (mPlaybackState == PlaybackState.BUFFERING) {
            if (mFragment != null) {
                mFragment.setFadingEnabled(true);
                mFragment.leanbackOverlayFragment.setShouldShowOverlay(false);
            }

            mPlaybackState = PlaybackState.PLAYING;
            interactionTracker.notifyStart(getCurrentlyPlayingItem());
            mCurrentTranscodeStartTime = mCurrentStreamInfo.getPlayMethod() == PlayMethod.TRANSCODE ? Instant.now().toEpochMilli() : 0;
            startReportLoop();
        }

        Timber.i("Play method: %s", mCurrentStreamInfo.getPlayMethod() == PlayMethod.TRANSCODE ? "Trans" : "Direct");

        if (mPlaybackState == PlaybackState.PAUSED) {
            mPlaybackState = PlaybackState.PLAYING;
        } else {
            if (!burningSubs) {
                // Make sure the requested subtitles are enabled when external/embedded
                Integer currentSubtitleIndex = mCurrentOptions.getSubtitleStreamIndex();
                if (currentSubtitleIndex == null) currentSubtitleIndex = -1;
                PlaybackControllerHelperKt.setSubtitleIndex(this, currentSubtitleIndex, true);
            } else {
                PlaybackControllerHelperKt.disableDefaultSubtitles(this);
            }

            // select an audio track
            int eligibleAudioTrack = mDefaultAudioIndex;

            // if track switching is done without rebuilding the stream, mCurrentOptions is updated
            // otherwise, use the server default
            MediaSourceInfo currentSource = getCurrentMediaSource();
            if (mCurrentOptions.getAudioStreamIndex() != null) {
                eligibleAudioTrack = mCurrentOptions.getAudioStreamIndex();
            } else if (currentSource != null && currentSource.getDefaultAudioStreamIndex() != null) {
                eligibleAudioTrack = currentSource.getDefaultAudioStreamIndex();
            }
            switchAudioStream(eligibleAudioTrack);
        }
    }

    @Override
    public void onError() {
        // A playback that fails does not measure the connection either.
        ConnectionMonitor.INSTANCE.setPlaybackActive(false);

        if (mFragment == null) {
            playerErrorEncountered();
            return;
        }

        if (isLiveTv && directStreamLiveTv) {
            Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_error_live_stream));
            directStreamLiveTv = false;
        } else {
            String msg = mFragment.getString(R.string.video_error_unknown_error);
            Timber.e("Playback error - %s", msg);
        }
        playerErrorEncountered();
    }

    @Override
    public void onCompletion() {
        Timber.i("On Completion fired");
        itemComplete();
    }

    @Override
    public void onProgress() {
        refreshCurrentPosition();
        if (isPlaying()) {
            if (!spinnerOff) {
                if (mStartPosition > 0) {
                    initialSeek(mStartPosition);
                    mStartPosition = 0;
                } else {
                    finishedInitialSeek = true;
                    stopSpinner();
                }
            }
        }
        if (mFragment != null)
            mFragment.setCurrentTime(mCurrentPosition);
    }

    public long getDuration() {
        long duration = 0;

        if (hasInitializedVideoManager()) {
            duration = mVideoManager.getDuration();
        } else if (getCurrentMediaSource() != null && getCurrentMediaSource().getRunTimeTicks() != null) {
            duration = getCurrentMediaSource().getRunTimeTicks() / 10000;
        } else if (getCurrentlyPlayingItem() != null && getCurrentlyPlayingItem().getRunTimeTicks() != null) {
            duration = getCurrentlyPlayingItem().getRunTimeTicks() / 10000;
        }
        return duration > 0 ? duration : 0;
    }

    public long getBufferedPosition() {
        long bufferedPosition = -1;

        if (hasInitializedVideoManager())
            bufferedPosition = mVideoManager.getBufferedPosition();

        if (bufferedPosition < 0)
            bufferedPosition = getDuration();

        return bufferedPosition;
    }

    public long getCurrentPosition() {
        // don't report the real position if seeking
        return !isPlaying() && mSeekPosition != -1 ? mSeekPosition : mCurrentPosition;
    }

    public boolean isPaused() {
        return mPlaybackState == PlaybackState.PAUSED;
    }

    public @NonNull ZoomMode getZoomMode() {
        return hasInitializedVideoManager() ? mVideoManager.getZoomMode() : ZoomMode.FIT;
    }

    public void setZoom(@NonNull ZoomMode mode) {
        if (hasInitializedVideoManager())
            mVideoManager.setZoom(mode);
    }

    /**
     * List of various states that we can be in
     */
    public enum PlaybackState {
        PLAYING,
        PAUSED,
        BUFFERING,
        IDLE,
        SEEKING,
        UNDEFINED,
        ERROR
    }

    /**
     * The subtitle stream that is used when the user did not choose one.
     *
     * A subtitle in simplified chinese is preferred, because most of the films in
     * this library only carry that one and it has to be picked by hand otherwise.
     * A subtitle in any other chinese comes next, and a subtitle in another
     * language is not selected at all: the user asks for the subtitles of their
     * own language, not for any subtitle.
     *
     * @param streams The streams of the media source
     * @return The index of the stream or null when there is no chinese subtitle
     */
    @Nullable
    private static Integer findPreferredSubtitle(@Nullable List<MediaStream> streams) {
        if (streams == null) return null;

        Integer chinese = null;

        for (MediaStream stream : streams) {
            if (stream.getType() != MediaStreamType.SUBTITLE) continue;
            if (!isTextSubtitle(stream)) continue;

            String language = stream.getLanguage();
            String title = stream.getTitle() != null ? stream.getTitle() : "";

            if (isSimplifiedChinese(language, title)) return stream.getIndex();
            if (chinese == null && isChinese(language, title)) chinese = stream.getIndex();
        }

        return chinese;
    }

    /**
     * The first subtitle stream that the device is able to show.
     *
     * @param streams The streams of the media source
     * @return The index of the stream or null when there is no text subtitle
     */
    @Nullable
    private static Integer findTextSubtitle(@Nullable List<MediaStream> streams) {
        if (streams == null) return null;

        for (MediaStream stream : streams) {
            if (stream.getType() == MediaStreamType.SUBTITLE && isTextSubtitle(stream)) {
                return stream.getIndex();
            }
        }

        return null;
    }

    /**
     * Whether the subtitle is a text subtitle that the player is able to show.
     */
    private static boolean isTextSubtitle(MediaStream stream) {
        if (stream.getCodec() == null) return false;

        return java.util.Arrays.asList("srt", "subrip", "ass", "ssa", "webvtt", "vtt", "ttml", "mov_text")
                .contains(stream.getCodec().toLowerCase(Locale.ROOT));
    }

    /**
     * The marks of a simplified chinese subtitle in its title.
     *
     * A plain "GB" or "CHS" is matched only as a word of its own: the letters appear in the
     * names of many other releases as well, and a subtitle that is in another language must
     * not be chosen because of them.
     */
    private static final java.util.regex.Pattern SIMPLIFIED_CHINESE_TITLE = java.util.regex.Pattern.compile(
            "简体|简中|GBK|(?<![A-Za-z])GB(?![A-Za-z])|(?<![A-Za-z])CHS(?![A-Za-z])",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Whether the subtitle is in simplified chinese.
     *
     * The language of a subtitle stream is an ISO 639-2 code and the code of
     * chinese differs between the libraries: chi, zho, cmn and zh are all used.
     * The title of the stream names the language as well and is used when the
     * code does not tell it.
     */
    private static boolean isSimplifiedChinese(@Nullable String language, String title) {
        if (language != null && !language.isEmpty()) {
            String code = language.toLowerCase(Locale.ROOT);

            if (code.equals("chi") || code.equals("hans") || code.equals("cmn")
                    || code.equals("zh") || code.startsWith("zh-cn") || code.startsWith("zh-hans")
                    || code.equals("zh-sg")) {
                return true;
            }

            // A subtitle that is named as traditional chinese is not simplified, even when
            // the title of the release mentions the simplified variant.
            if (code.equals("hant") || code.equals("cht") || code.equals("yue")
                    || code.startsWith("zh-tw") || code.startsWith("zh-hk") || code.startsWith("zh-hant")) {
                return false;
            }
        }

        return title != null && SIMPLIFIED_CHINESE_TITLE.matcher(title).find();
    }

    /**
     * Whether the subtitle is in chinese.
     */
    private static boolean isChinese(@Nullable String language, String title) {
        if (language != null) {
            String code = language.toLowerCase(Locale.ROOT);
            if (code.equals("zho") || code.equals("cmn") || code.equals("hant") || code.equals("yue")
                    || code.startsWith("zh-tw") || code.startsWith("zh-hk") || code.startsWith("zh-hant")) {
                return true;
            }
        }

        return title.contains("中文") || title.contains("繁體") || title.contains("繁体")
                || title.toLowerCase(Locale.ROOT).contains("chinese");
    }
}
