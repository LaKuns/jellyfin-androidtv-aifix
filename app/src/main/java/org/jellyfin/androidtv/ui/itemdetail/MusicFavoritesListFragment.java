package org.jellyfin.androidtv.ui.itemdetail;

import static org.koin.java.KoinJavaComponent.inject;

import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.databinding.FragmentItemListBinding;
import org.jellyfin.androidtv.databinding.ViewRowDetailsBinding;
import org.jellyfin.androidtv.ui.ItemListView;
import org.jellyfin.androidtv.ui.ItemRowView;
import org.jellyfin.androidtv.ui.TextUnderButton;
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem;
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher;
import org.jellyfin.androidtv.ui.playback.AudioEventListener;
import org.jellyfin.androidtv.ui.playback.MediaManager;
import org.jellyfin.androidtv.ui.playback.PlaybackController;
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher;
import org.jellyfin.androidtv.util.PlaybackHelper;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.jellyfin.sdk.model.serializer.UUIDSerializerKt;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import kotlin.Lazy;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import timber.log.Timber;

public class MusicFavoritesListFragment extends Fragment implements View.OnKeyListener {
    private LinearLayout mButtonRow;
    private ItemListView mItemList;
    private ItemRowView mCurrentRow;

    private List<BaseItemDto> mItems = new ArrayList<>();
    private UUID mParentId;
    private int mFavoriteOffset;
    private boolean mFavoritesComplete;
    private boolean mQueueLoading;

    private Lazy<MediaManager> mediaManager = inject(MediaManager.class);
    private final Lazy<ItemLauncher> itemLauncher = inject(ItemLauncher.class);
    private final Lazy<PlaybackHelper> playbackHelper = inject(PlaybackHelper.class);
    private final Lazy<PlaybackLauncher> playbackLauncher = inject(PlaybackLauncher.class);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FragmentItemListBinding binding = FragmentItemListBinding.inflate(getLayoutInflater(), container, false);

        ViewRowDetailsBinding detailsBinding = binding.details.getBinding();
        detailsBinding.fdTitle.setText(getString(R.string.lbl_favorites));
        detailsBinding.mainImage.setImageResource(R.drawable.favorites);
        detailsBinding.fdSummaryText.setText(getString(R.string.desc_automatic_fav_songs));
        mButtonRow = detailsBinding.fdButtonRow;
        mItemList = binding.songs;
        mItemList.setHeaderView(binding.details);
        mItemList.setOnLoadMoreListener(this::loadNextFavoritesPage);

        //Item list listeners
        mItemList.setRowSelectedListener(new ItemRowView.RowSelectedListener() {
            @Override
            public void onRowSelected(ItemRowView row) {
                mCurrentRow = row;
                //Keep selected row in center of screen
                mItemList.centerRow(row);
            }
        });

        mItemList.setRowClickedListener(new ItemRowView.RowClickedListener() {
            @Override
            public void onRowClicked(ItemRowView row) {
                showMenu(row, row.getItem().getType() != BaseItemKind.AUDIO);
            }
        });

        TextUnderButton play = TextUnderButton.create(requireContext(), R.drawable.ic_play, Utils.convertDpToPixel(requireContext(), 35), 2, getString(R.string.lbl_play_all), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playCompleteFavorites(0, false);
            }
        });
        play.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) mItemList.scrollToStart();
        });
        mButtonRow.addView(play);
        play.requestFocus();

        TextUnderButton shuffle = TextUnderButton.create(requireContext(), R.drawable.ic_shuffle, Utils.convertDpToPixel(requireContext(), 35), 2, getString(R.string.lbl_shuffle_all), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playCompleteFavorites(0, true);
            }
        });
        mButtonRow.addView(shuffle);
        shuffle.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) mItemList.scrollToStart();
        });

        return binding.getRoot();
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) return false;

        if (mediaManager.getValue().isPlayingAudio()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    mediaManager.getValue().togglePlayPause();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    mediaManager.getValue().nextAudioItem();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    mediaManager.getValue().prevAudioItem();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    showMenu(mCurrentRow, false);
                    return true;
            }
        } else if (mCurrentRow != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MENU:
                    showMenu(mCurrentRow, false);
                    return true;
            }
        }

        return false;
    }

    @Override
    public void onResume() {
        super.onResume();

        mediaManager.getValue().addAudioEventListener(mAudioEventListener);
        // and fire it to be sure we're updated
        mAudioEventListener.onPlaybackStateChange(mediaManager.getValue().isPlayingAudio() ? PlaybackController.PlaybackState.PLAYING : PlaybackController.PlaybackState.IDLE, mediaManager.getValue().getCurrentAudioItem());

        mParentId = UUIDSerializerKt.toUUIDOrNull(getArguments().getString("ParentId"));
        ItemListFragmentHelperKt.getFavoritePlaylist(this, mParentId, itemResponse);
    }

    @Override
    public void onPause() {
        super.onPause();
        mediaManager.getValue().removeAudioEventListener(mAudioEventListener);
    }

    @Override
    public void onDestroyView() {
        mQueueLoading = false;
        super.onDestroyView();
    }

    private AudioEventListener mAudioEventListener = new AudioEventListener() {
        @Override
        public void onPlaybackStateChange(@NonNull PlaybackController.PlaybackState newState, @Nullable BaseItemDto currentItem) {
            Timber.i("Got playback state change event %s for item %s", newState.toString(), currentItem != null ? currentItem.getName() : "<unknown>");

            if (newState != PlaybackController.PlaybackState.PLAYING || currentItem == null) {
                mItemList.updateCurrentTime(-1);
                mItemList.updatePlaying(null);
            } else {
                mItemList.updatePlaying(currentItem.getId());
            }
        }

        @Override
        public void onProgress(long pos, long duration) {
            mItemList.updateCurrentTime(pos);
        }

        @Override
        public void onQueueStatusChanged(boolean hasQueue) {
        }

        @Override
        public void onQueueReplaced() {
        }
    };

    private void showMenu(final ItemRowView row, boolean showOpen) {
        PopupMenu menu = new PopupMenu(requireContext(), row != null ? row : requireActivity().getCurrentFocus(), Gravity.END);
        int order = 0;
        if (showOpen) {
            MenuItem open = menu.getMenu().add(0, 0, order++, R.string.lbl_open);
            open.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    itemLauncher.getValue().launch(new BaseItemDtoBaseRowItem(row.getItem()), null, requireContext());
                    return true;
                }
            });

        }
        MenuItem playFromHere = menu.getMenu().add(0, 0, order++, R.string.lbl_play_from_here);
        playFromHere.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                playCompleteFavorites(row.getIndex(), false);
                return true;
            }
        });
        MenuItem play = menu.getMenu().add(0, 1, order++, R.string.lbl_play);
        play.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                play(mItems.subList(row.getIndex(), row.getIndex() + 1), 0, false);
                return true;
            }
        });
        if (row.getItem().getType() == BaseItemKind.AUDIO) {
            MenuItem queue = menu.getMenu().add(0, 2, order++, R.string.lbl_add_to_queue);
            queue.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    mediaManager.getValue().queueAudioItem(row.getItem());
                    return true;
                }
            });

            MenuItem mix = menu.getMenu().add(0, 1, order++, R.string.lbl_instant_mix);
            mix.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    playbackHelper.getValue().playInstantMix(requireContext(), row.getItem());
                    return true;
                }
            });

        }

        menu.show();
    }

    private Function1<PlaylistPage, Unit> itemResponse = (PlaylistPage page) -> {
        if (page == null) {
            Utils.showToast(requireContext(), R.string.msg_playlist_load_error);
            return null;
        }
        mFavoriteOffset = page.getItems().size();
        mFavoritesComplete = !page.getHasMore();
        mItemList.setItems(page.getItems());
        mItems = mItemList.getItems();
        if (!page.getItems().isEmpty()) {
            if (mediaManager.getValue().isPlayingAudio()) {
                //update our status
                mAudioEventListener.onPlaybackStateChange(PlaybackController.PlaybackState.PLAYING, mediaManager.getValue().getCurrentAudioItem());
            }
        }
        return null;
    };

    private void loadNextFavoritesPage() {
        if (mFavoritesComplete) return;
        mItemList.setLoadingMore(true);
        ItemListFragmentHelperKt.getFavoritePage(this, mParentId, mFavoriteOffset, page -> {
            mItemList.setLoadingMore(false);
            if (page == null) {
                Utils.showToast(requireContext(), R.string.msg_playlist_load_error);
                return null;
            }
            mFavoritesComplete = !page.getHasMore();
            mFavoriteOffset += page.getItems().size();
            mItemList.appendItems(page.getItems());
            return null;
        });
    }

    private void playCompleteFavorites(int startIndex, boolean shuffle) {
        if (mQueueLoading) return;
        mQueueLoading = true;
        ItemListFragmentHelperKt.getAllFavoriteItems(this, mParentId, items -> {
            mQueueLoading = false;
            if (items == null) Utils.showToast(requireContext(), R.string.msg_playlist_load_error);
            else if (items.isEmpty()) Utils.showToast(requireContext(), R.string.msg_no_playable_items);
            else play(items, Math.min(startIndex, items.size() - 1), shuffle);
            return null;
        });
    }

    private void play(List<BaseItemDto> items, int ndx, boolean shuffle) {
        Timber.i("play items: %d, ndx: %d, shuffle: %b", items.size(), ndx, shuffle);

        playbackLauncher.getValue().launch(requireContext(), items, 0, false, ndx, shuffle);
    }
}
