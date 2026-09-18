package org.jellyfin.androidtv.ui.browsing;

import static org.koin.java.KoinJavaComponent.inject;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.leanback.app.RowsSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ClassPresenterSelector;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.OnItemViewSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.lifecycle.Lifecycle;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.constant.CustomMessage;
import org.jellyfin.androidtv.constant.Extras;
import org.jellyfin.androidtv.constant.ImageType;
import org.jellyfin.androidtv.constant.LiveTvOption;
import org.jellyfin.androidtv.constant.QueryType;
import org.jellyfin.androidtv.data.model.DataRefreshService;
import org.jellyfin.androidtv.data.querying.GetUserViewsRequest;
import org.jellyfin.androidtv.data.repository.CustomMessageRepository;
import org.jellyfin.androidtv.data.service.BackgroundService;
import org.jellyfin.androidtv.databinding.EnhancedDetailBrowseBinding;
import org.jellyfin.androidtv.ui.GridButton;
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem;
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem;
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem;
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher;
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter;
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapterHelperKt;
import org.jellyfin.androidtv.ui.navigation.Destinations;
import org.jellyfin.androidtv.ui.navigation.NavigationRepository;
import org.jellyfin.androidtv.ui.playback.MediaManager;
import org.jellyfin.androidtv.ui.presentation.CardPresenter;
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter;
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter;
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter;
import org.jellyfin.androidtv.util.CoroutineUtils;
import org.jellyfin.androidtv.util.InfoLayoutHelper;
import org.jellyfin.androidtv.util.KeyProcessor;
import org.jellyfin.androidtv.util.MarkdownRenderer;
import org.jellyfin.androidtv.util.PerformanceProfile;
import org.jellyfin.androidtv.util.sdk.compat.JavaCompat;
import org.jellyfin.sdk.api.client.ApiClient;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.koin.java.KoinJavaComponent;

import java.util.ArrayList;
import java.util.List;

import kotlin.Lazy;
import kotlinx.serialization.json.Json;

public class EnhancedBrowseFragment extends Fragment implements RowLoader, View.OnKeyListener {
    private static final long LOW_PERFORMANCE_SELECTION_DELAY_MS = 180;

    protected TextView mTitle;
    private LinearLayout mInfoRow;
    private TextView mSummary;

    protected static final int BY_LETTER = 0;
    protected static final int GENRES = 1;
    protected static final int RANDOM = 2;
    protected static final int SUGGESTED = 4;
    protected static final int GRID = 6;
    protected static final int ALBUMS = 7;
    protected static final int ARTISTS = 8;
    public static final int FAVSONGS = 9;
    protected static final int SCHEDULE = 10;
    protected static final int SERIES = 11;
    protected static final int ALBUM_ARTISTS = 12;
    protected static final int SHUFFLE_SONGS = 13;
    protected BaseItemDto mFolder;
    protected BaseItemKind itemType;
    protected boolean showViews = true;
    protected boolean justLoaded = true;

    protected RowsSupportFragment mRowsFragment;
    protected CompositeClickedListener mClickedListener = new CompositeClickedListener();
    protected CompositeSelectedListener mSelectedListener = new CompositeSelectedListener();
    protected MutableObjectAdapter<Row> mRowsAdapter;
    protected ArrayList<BrowseRowDef> mRows = new ArrayList<>();
    protected CardPresenter mCardPresenter;
    protected BaseRowItem mCurrentItem;
    protected ListRow mCurrentRow;

    private final Handler selectionHandler = new Handler(Looper.getMainLooper());
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable delayedRefreshRunnable;
    private boolean lowPerformanceDevice;
    private final Runnable applyPendingSelection = () -> {
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
        if (mCurrentItem != null) applySelectionDetails(mCurrentItem);
    };

    private Lazy<BackgroundService> backgroundService = inject(BackgroundService.class);
    private Lazy<MarkdownRenderer> markdownRenderer = inject(MarkdownRenderer.class);
    private final Lazy<CustomMessageRepository> customMessageRepository = inject(CustomMessageRepository.class);
    private final Lazy<NavigationRepository> navigationRepository = inject(NavigationRepository.class);
    private final Lazy<ApiClient> api = inject(ApiClient.class);
    private final Lazy<ItemLauncher> itemLauncher = inject(ItemLauncher.class);
    private final Lazy<KeyProcessor> keyProcessor = inject(KeyProcessor.class);
    private final Lazy<MediaManager> mediaManager = inject(MediaManager.class);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        lowPerformanceDevice = PerformanceProfile.isLowPerformanceDevice(requireContext());

        mRowsAdapter = new MutableObjectAdapter<Row>(new PositionableListRowPresenter(
                null,
                lowPerformanceDevice ? FocusHighlight.ZOOM_FACTOR_NONE : FocusHighlight.ZOOM_FACTOR_MEDIUM
        ));

        setupViews();
        setupQueries(this);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        EnhancedDetailBrowseBinding binding = EnhancedDetailBrowseBinding.inflate(inflater, container, false);

        mTitle = binding.title;
        mInfoRow = binding.infoRow;
        mSummary = binding.summary;

        // Inject the RowsSupportFragment in the results container
        if (getChildFragmentManager().findFragmentById(R.id.rowsFragment) == null) {
            mRowsFragment = new RowsSupportFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.rowsFragment, mRowsFragment).commit();
        } else {
            mRowsFragment = (RowsSupportFragment) getChildFragmentManager()
                    .findFragmentById(R.id.rowsFragment);
        }

        mRowsFragment.setAdapter(mRowsAdapter);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupEventListeners();
        if (isSeasonFolder()) backgroundService.getValue().setBackground(mFolder);
    }

    @Override
    public void onDestroyView() {
        selectionHandler.removeCallbacks(applyPendingSelection);
        refreshHandler.removeCallbacksAndMessages(null);
        cancelRowRetrievals();
        super.onDestroyView();
        mClickedListener.removeListeners();
        mSelectedListener.removeListeners();
    }

    private void cancelRowRetrievals() {
        if (mRowsAdapter == null) return;

        for (int i = 0; i < mRowsAdapter.size(); i++) {
            Object value = mRowsAdapter.get(i);
            if (!(value instanceof ListRow)) continue;

            Object rowAdapter = ((ListRow) value).getAdapter();
            if (rowAdapter instanceof ItemRowAdapter) {
                ItemRowAdapterHelperKt.cancelRetrieval((ItemRowAdapter) rowAdapter);
            }
        }
    }

    protected void setupQueries(RowLoader rowLoader) {
        rowLoader.loadRows(mRows);
    }

    protected void setupViews() {
        if (!getArguments().containsKey(Extras.Folder)) return;
        mFolder = Json.Default.decodeFromString(BaseItemDto.Companion.serializer(), getArguments().getString(Extras.Folder));
        if (mFolder == null) return;

        if (mFolder.getCollectionType() != null) {
            switch (mFolder.getCollectionType()) {
                case MOVIES:
                    itemType = BaseItemKind.MOVIE;
                    break;
                case TVSHOWS:
                    itemType = BaseItemKind.SERIES;
                    break;
                case MUSIC:
                    itemType = BaseItemKind.MUSIC_ALBUM;
                    break;
                case FOLDERS:
                    showViews = false;
                    break;
                default:
                    showViews = false;
            }
        } else {
            showViews = false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // React to deletion
        DataRefreshService dataRefreshService = KoinJavaComponent.<DataRefreshService>get(DataRefreshService.class);
        if (mCurrentRow != null && mCurrentItem != null && mCurrentItem.getItemId() != null && mCurrentItem.getItemId().equals(dataRefreshService.getLastDeletedItemId())) {
            ((ItemRowAdapter) mCurrentRow.getAdapter()).remove(mCurrentItem);
            dataRefreshService.setLastDeletedItemId(null);
        }

        if (!justLoaded) {
            // Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
            if (mRowsAdapter != null) {
                refreshCurrentItem();
                if (delayedRefreshRunnable != null) refreshHandler.removeCallbacks(delayedRefreshRunnable);
                delayedRefreshRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                            return;

                        for (int i = 0; i < mRowsAdapter.size(); i++) {
                            if (mRowsAdapter.get(i) instanceof ListRow) {
                                if (((ListRow) mRowsAdapter.get(i)).getAdapter() instanceof ItemRowAdapter) {
                                    ((ItemRowAdapter) ((ListRow) mRowsAdapter.get(i)).getAdapter()).ReRetrieveIfNeeded();
                                }
                            }
                        }
                    }
                };
                refreshHandler.postDelayed(delayedRefreshRunnable, 1500);
            }
        } else {
            justLoaded = false;
        }
    }

    public void loadRows(List<BrowseRowDef> rows) {
        cancelRowRetrievals();
        mRowsAdapter = new MutableObjectAdapter<Row>(new PositionableListRowPresenter(
                null,
                lowPerformanceDevice ? FocusHighlight.ZOOM_FACTOR_NONE : FocusHighlight.ZOOM_FACTOR_MEDIUM
        ));
        mCardPresenter = isSeasonFolder()
                ? new CardPresenter(true, ImageType.THUMB, 120)
                : new CardPresenter(false, 140);
        ClassPresenterSelector ps = new ClassPresenterSelector();
        ps.addClassPresenter(GridButtonBaseRowItem.class, new GridButtonPresenter(155, 140));
        ps.addClassPresenter(BaseRowItem.class, mCardPresenter);

        for (BrowseRowDef def : rows) {
            HeaderItem header = new HeaderItem(def.getHeaderText());
            ItemRowAdapter rowAdapter;
            switch (def.getQueryType()) {
                case NextUp:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getNextUpQuery(), true, mCardPresenter, mRowsAdapter);
                    break;
                case LatestItems:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getLatestItemsQuery(), true, mCardPresenter, mRowsAdapter);
                    break;
                case Views:
                    rowAdapter = new ItemRowAdapter(requireContext(), GetUserViewsRequest.INSTANCE, mCardPresenter, mRowsAdapter);
                    break;
                case SimilarSeries:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getSimilarQuery(), QueryType.SimilarSeries, mCardPresenter, mRowsAdapter);
                    break;
                case SimilarMovies:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getSimilarQuery(), QueryType.SimilarMovies, mCardPresenter, mRowsAdapter);
                    break;
                case LiveTvChannel:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getTvChannelQuery(), 40, mCardPresenter, mRowsAdapter);
                    break;
                case LiveTvProgram:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getProgramQuery(), mCardPresenter, mRowsAdapter);
                    break;
                case LiveTvRecording:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getRecordingQuery(), def.getChunkSize(), mCardPresenter, mRowsAdapter);
                    break;
                case Premieres:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getQuery(), def.getChunkSize(), def.getPreferParentThumb(), def.isStaticHeight(), mCardPresenter, mRowsAdapter, def.getQueryType());
                    break;
                case SeriesTimer:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getSeriesTimerQuery(), mCardPresenter, mRowsAdapter);
                    break;
                case Specials:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getSpecialsQuery(), new CardPresenter(true, ImageType.THUMB, 150), mRowsAdapter);
                    break;
                default:
                    rowAdapter = new ItemRowAdapter(requireContext(), def.getQuery(), def.getChunkSize(), def.getPreferParentThumb(), def.isStaticHeight(), ps, mRowsAdapter, def.getQueryType());
                    break;
            }

            rowAdapter.setRetrieveLifecycleOwner(this);
            rowAdapter.setReRetrieveTriggers(def.getChangeTriggers());

            ListRow row = new ListRow(header, rowAdapter);
            mRowsAdapter.add(row);
            rowAdapter.setRow(row);
            rowAdapter.Retrieve();
        }

        addAdditionalRows(mRowsAdapter);

        if (mRowsFragment != null) mRowsFragment.setAdapter(mRowsAdapter);
    }

    protected void addAdditionalRows(MutableObjectAdapter<Row> rowAdapter) {
        if (!showViews || itemType == null) return;

        HeaderItem gridHeader = new HeaderItem(rowAdapter.size(), getString(R.string.lbl_views));
        GridButtonPresenter mGridPresenter = new GridButtonPresenter();
        ArrayObjectAdapter gridRowAdapter = new ArrayObjectAdapter(mGridPresenter);

        switch (itemType) {
            case MOVIE:
                gridRowAdapter.add(new GridButton(SUGGESTED, getString(R.string.lbl_suggested)));
                addStandardViewButtons(gridRowAdapter);
                gridRowAdapter.add(new GridButton(RANDOM, getString(R.string.random)));
                break;

            case MUSIC_ALBUM:
                gridRowAdapter.add(new GridButton(ALBUMS, getString(R.string.lbl_albums)));
                gridRowAdapter.add(new GridButton(ALBUM_ARTISTS, getString(R.string.lbl_album_artists)));
                gridRowAdapter.add(new GridButton(ARTISTS, getString(R.string.lbl_artists)));
                gridRowAdapter.add(new GridButton(GENRES, getString(R.string.lbl_genres)));
                gridRowAdapter.add(new GridButton(RANDOM, getString(R.string.random)));
                gridRowAdapter.add(new GridButton(SHUFFLE_SONGS, getString(R.string.lbl_shuffle_all)));
                break;

            case SERIES:
                addStandardViewButtons(gridRowAdapter);
                gridRowAdapter.add(new GridButton(RANDOM, getString(R.string.random)));
                break;

            default:
                addStandardViewButtons(gridRowAdapter);
                break;
        }

        rowAdapter.add(new ListRow(gridHeader, gridRowAdapter));
    }

    protected void addStandardViewButtons(ArrayObjectAdapter gridRowAdapter) {
        gridRowAdapter.add(new GridButton(GRID, getString(R.string.lbl_all_items)));
        gridRowAdapter.add(new GridButton(BY_LETTER, getString(R.string.lbl_by_letter)));
        gridRowAdapter.add(new GridButton(GENRES, getString(R.string.lbl_genres)));
        // Disabled because the screen doesn't behave properly
        // gridRowAdapter.add(new GridButton(PERSONS, getString(R.string.lbl_performers)));
    }

    protected void setupEventListeners() {
        mRowsFragment.setOnItemViewClickedListener(mClickedListener);
        mClickedListener.registerListener(new ItemViewClickedListener());
        if (showViews) mClickedListener.registerListener(new SpecialViewClickedListener());

        mRowsFragment.setOnItemViewSelectedListener(mSelectedListener);
        mSelectedListener.registerListener(new ItemViewSelectedListener());

        CoroutineUtils.readCustomMessagesOnLifecycle(getLifecycle(), customMessageRepository.getValue(), message -> {
            if (message.equals(CustomMessage.RefreshCurrentItem.INSTANCE)) refreshCurrentItem();
            return null;
        });
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) return false;
        return keyProcessor.getValue().handleKey(keyCode, mCurrentItem, requireActivity());
    }

    private void refreshCurrentItem() {
        if (mCurrentRow == null || mCurrentItem == null) return;
        ItemRowAdapterHelperKt.refreshItem((ItemRowAdapter) mCurrentRow.getAdapter(), api.getValue(), this, mCurrentItem);
    }

    private final class SpecialViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item, RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof GridButtonBaseRowItem) item = ((GridButtonBaseRowItem) item).getGridButton();
            if (item instanceof GridButton) {
                switch (((GridButton) item).getId()) {
                    case GRID:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryBrowser(mFolder, null));
                        break;

                    case ALBUMS:
                        mFolder = JavaCompat.copyWithDisplayPreferencesId(mFolder, mFolder.getId() + "AL");

                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryBrowser(mFolder, null));
                        break;

                    case ALBUM_ARTISTS:
                        mFolder = JavaCompat.copyWithDisplayPreferencesId(mFolder, mFolder.getId() + "AR");

                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryBrowser(mFolder, "AlbumArtist"));
                        break;

                    case ARTISTS:
                        mFolder = JavaCompat.copyWithDisplayPreferencesId(mFolder, mFolder.getId() + "AR");

                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryBrowser(mFolder, "Artist"));
                        break;

                    case BY_LETTER:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryByLetter(mFolder, itemType.getSerialName()));
                        break;

                    case GENRES:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.libraryByGenres(mFolder, itemType.getSerialName()));
                        break;
                    case SHUFFLE_SONGS:
                      BrowsingUtils.getRandomItems(api.getValue(), getViewLifecycleOwner(), mFolder, BaseItemKind.AUDIO, randomItems -> {
                          mediaManager.getValue().clearAudioQueue();
                          mediaManager.getValue().addToAudioQueue(randomItems);
                          navigationRepository.getValue().navigate(Destinations.INSTANCE.getNowPlaying());
                          return null;
                        });
                        break;


                    case RANDOM:
                        BrowsingUtils.getRandomItem(api.getValue(), getViewLifecycleOwner(), mFolder, itemType, randomItem -> {
                            if (randomItem != null) {
                                if (randomItem.getType() == BaseItemKind.MUSIC_ALBUM) {
                                    navigationRepository.getValue().navigate(Destinations.INSTANCE.itemList(randomItem.getId()));
                                } else {
                                    navigationRepository.getValue().navigate(Destinations.INSTANCE.itemDetails(randomItem.getId()));
                                }
                            }

                            return null;
                        });
                        break;

                    case SUGGESTED:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.librarySuggestions(mFolder));
                        break;

                    case FAVSONGS:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.musicFavorites(mFolder.getId()));
                        break;

                    case SERIES:
                    case LiveTvOption.LIVE_TV_SERIES_OPTION_ID:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.getLiveTvSeriesRecordings());
                        break;

                    case SCHEDULE:
                    case LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.getLiveTvSchedule());
                        break;

                    case LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.getLiveTvRecordings());
                        break;

                    case LiveTvOption.LIVE_TV_GUIDE_OPTION_ID:
                        navigationRepository.getValue().navigate(Destinations.INSTANCE.getLiveTvGuide());
                        break;

                    default:
                        Toast.makeText(requireContext(), item.toString() + getString(R.string.msg_not_implemented), Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        }
    }

    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(final Presenter.ViewHolder itemViewHolder, Object item, RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (!(item instanceof BaseRowItem)) return;

            itemLauncher.getValue().launch((BaseRowItem) item, (ItemRowAdapter) ((ListRow) row).getAdapter(), requireContext());
        }
    }

    private final class ItemViewSelectedListener implements OnItemViewSelectedListener {
        @Override
        public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                   RowPresenter.ViewHolder rowViewHolder, Row row) {
            selectionHandler.removeCallbacks(applyPendingSelection);
            if (!(item instanceof BaseRowItem)) {
                mTitle.setText(mFolder != null ? mFolder.getName() : "");
                if (lowPerformanceDevice) {
                    InfoLayoutHelper.addLowPerformanceInfoRow(requireContext(), null, mInfoRow, true);
                } else {
                    InfoLayoutHelper.addInfoRow(requireContext(), null, mInfoRow, true);
                }
                mSummary.setText("");
                mCurrentItem = null;
                mCurrentRow = null;
                // Fill in default background
                if (isSeasonFolder()) backgroundService.getValue().setBackground(mFolder);
                else backgroundService.getValue().clearBackgrounds();
                return;
            }

            BaseRowItem rowItem = (BaseRowItem) item;

            mCurrentItem = rowItem;
            mCurrentRow = (ListRow) row;
            mTitle.setText(getSelectionTitle(rowItem));

            ItemRowAdapter adapter = (ItemRowAdapter) ((ListRow) row).getAdapter();
            adapter.loadMoreItemsIfNeeded(adapter.indexOf(rowItem));

            // Episode browsing and constrained devices commonly receive several focus
            // changes in quick succession. Avoid parsing markdown, rebuilding the info
            // row and starting a backdrop request for selections that are never shown.
            if (lowPerformanceDevice || (mFolder != null && mFolder.getType() == BaseItemKind.SEASON)) {
                selectionHandler.postDelayed(applyPendingSelection, LOW_PERFORMANCE_SELECTION_DELAY_MS);
            } else {
                applySelectionDetails(rowItem);
            }
        }
    }

    private void applySelectionDetails(BaseRowItem rowItem) {
        if (rowItem != mCurrentItem || mInfoRow == null || mSummary == null) return;

        String summary = rowItem.getSummary(requireContext());
        if (summary == null) {
            mSummary.setText(null);
        } else if (lowPerformanceDevice) {
            // Episode summaries are normally plain text. Rendering them as
            // markdown on every focus change is expensive on older CPUs.
            mSummary.setText(summary);
        } else {
            mSummary.setText(markdownRenderer.getValue().toMarkdownSpanned(summary));
        }

        if (lowPerformanceDevice) {
            InfoLayoutHelper.addLowPerformanceInfoRow(requireContext(), rowItem.getBaseItem(), mInfoRow, true);
        } else {
            InfoLayoutHelper.addInfoRow(requireContext(), rowItem.getBaseItem(), mInfoRow, true);
        }
        // Keep one stable series/season backdrop while browsing episodes. Besides
        // reducing visual noise this avoids decoding a new fullscreen image for
        // every D-pad movement.
        if (!isSeasonFolder()) backgroundService.getValue().setSelectionBackground(rowItem.getBaseItem());
    }

    private boolean isSeasonFolder() {
        return mFolder != null && mFolder.getType() == BaseItemKind.SEASON;
    }

    private String getSelectionTitle(BaseRowItem rowItem) {
        if (isSeasonFolder() && rowItem instanceof BaseItemDtoBaseRowItem) {
            String episodeNumber = ((BaseItemDtoBaseRowItem) rowItem).getEpisodeNumberLabel();
            if (episodeNumber != null) return episodeNumber;
        }
        return rowItem.getName(requireContext());
    }
}
