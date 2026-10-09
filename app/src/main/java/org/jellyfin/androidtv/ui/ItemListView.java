package org.jellyfin.androidtv.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ConcatAdapter;

import org.jellyfin.sdk.model.api.BaseItemDto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A scrolling list of item rows.
 *
 * <p>This used to be a {@link FrameLayout} wrapping a vertical {@code LinearLayout} that
 * inflated one {@link ItemRowView} per item. Long playlists and favourites therefore created
 * a view for every single entry, which is a significant allocation on devices with a small
 * heap. It is now a {@link RecyclerView} so rows are recycled and only the visible ones are
 * bound.
 */
public class ItemListView extends RecyclerView {
    private final ItemAdapter mAdapter;
    private final LinearLayoutManager mLayoutManager;

    /**
     * How close to the end of the list the user has to scroll before the next page is
     * requested. Prefetching slightly early keeps scrolling continuous on slow networks.
     */
    private static final int LOAD_MORE_THRESHOLD = 10;

    @Nullable
    private OnLoadMoreListener mLoadMoreListener;
    private boolean mLoadingMore;

    public interface OnLoadMoreListener {
        void onLoadMore();
    }

    public ItemListView(Context context) {
        this(context, null);
    }

    public ItemListView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);

        mLayoutManager = new LinearLayoutManager(context);
        setLayoutManager(mLayoutManager);
        setItemAnimator(null);

        mAdapter = new ItemAdapter();
        setAdapter(mAdapter);

        addOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                maybeLoadMore();
            }
        });
    }

    public void setRowSelectedListener(ItemRowView.RowSelectedListener listener) {
        mAdapter.setRowSelectedListener(listener);
    }

    /** Keep the details header in the same scrolling container as the recycled rows. */
    public void setHeaderView(View header) {
        ViewGroup parent = (ViewGroup) header.getParent();
        if (parent != null) parent.removeView(header);
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Math.round(10 * getResources().getDisplayMetrics().density);
        header.setLayoutParams(params);
        setAdapter(new ConcatAdapter(new HeaderAdapter(header), mAdapter));
    }

    public void setRowClickedListener(ItemRowView.RowClickedListener listener) {
        mAdapter.setRowClickedListener(listener);
    }

    public void setOnLoadMoreListener(OnLoadMoreListener listener) {
        mLoadMoreListener = listener;
    }

    public void setLoadingMore(boolean loadingMore) {
        mLoadingMore = loadingMore;
    }

    /** Replace every row with [items]. */
    public void setItems(List<BaseItemDto> items) {
        mAdapter.setItems(items);
    }

    /** Append the next page of [items]. */
    public void appendItems(List<BaseItemDto> items) {
        mAdapter.appendItems(items);
    }

    public List<BaseItemDto> getItems() {
        return mAdapter.getItems();
    }

    public void clear() {
        mAdapter.setItems(new ArrayList<>());
    }

    /** Ids of the currently listed items, in display order. */
    public List<UUID> getItemIds() {
        List<BaseItemDto> items = mAdapter.getItems();
        List<UUID> ids = new ArrayList<>(items.size());
        for (BaseItemDto item : items) {
            ids.add(item.getId());
        }
        return ids;
    }

    /**
     * Replace the row at [position] with an updated copy of [item]. This is used by the
     * playback-progress refresh, which must not disturb the rest of the list.
     */
    public void updateItem(int position, BaseItemDto item) {
        if (position < 0 || position >= mAdapter.getItemCount()) return;
        mAdapter.updateItem(position, item);
    }

    /**
     * Mark [id] as the currently playing track. Passing {@code null} clears the marker.
     */
    public void updatePlaying(UUID id) {
        mAdapter.setPlayingItemId(id);
    }

    /**
     * Update the runtime label of the playing row. A negative [pos] restores the total
     * runtime instead of showing a position.
     */
    public void updateCurrentTime(long pos) {
        mAdapter.updateCurrentTime(pos);
        UUID playingId = mAdapter.mPlayingItemId;
        if (playingId == null) return;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child instanceof ItemRowView) {
                ItemRowView row = (ItemRowView) child;
                if (row.getItem() != null && playingId.equals(row.getItem().getId())) {
                    row.updateCurrentTime(pos);
                    break;
                }
            }
        }
    }

    /**
     * Keep the focused row visually centred. The previous implementation used
     * {@code ScrollView.smoothScrollBy} against absolute screen coordinates, which a
     * RecyclerView cannot provide; a smooth scroller that centres its target replaces it.
     */
    public void centerRow(ItemRowView row) {
        int position = row == null ? RecyclerView.NO_POSITION : getChildAdapterPosition(row);
        if (position == RecyclerView.NO_POSITION) return;

        LinearSmoothScroller scroller = new LinearSmoothScroller(getContext()) {
            @Override
            public int calculateDyToMakeVisible(View view, int snapPref) {
                // Centre the row instead of only making it visible: move it so its middle
                // sits on the middle of the viewport.
                int desiredTop = (getHeight() / 2) - (view.getHeight() / 2);
                return view.getTop() - desiredTop;
            }
        };
        scroller.setTargetPosition(position);
        startSmoothScroll(scroller);
    }

    /** Scroll back to the first row, replacing the old "scroll the page to the top" call. */
    public void scrollToStart() {
        smoothScrollToPosition(0);
    }

    private void maybeLoadMore() {
        if (mLoadMoreListener == null || mLoadingMore) return;

        int lastVisible = mLayoutManager.findLastVisibleItemPosition();
        int total = mAdapter.getItemCount();
        if (total > 0 && lastVisible >= total - LOAD_MORE_THRESHOLD) {
            mLoadMoreListener.onLoadMore();
        }
    }

    private static class ItemRowHolder extends RecyclerView.ViewHolder {
        final ItemRowView row;

        ItemRowHolder(ItemRowView row) {
            super(row);
            this.row = row;
        }
    }

    private static class HeaderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View header;

        HeaderAdapter(View header) {
            this.header = header;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(header) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}

        @Override
        public int getItemCount() { return 1; }
    }

    private static class ItemAdapter extends RecyclerView.Adapter<ItemRowHolder> {
        private final List<BaseItemDto> mItems = new ArrayList<>();
        private ItemRowView.RowSelectedListener mRowSelectedListener;
        private ItemRowView.RowClickedListener mRowClickedListener;
        private UUID mPlayingItemId;
        private long mPlayingPosition = -1;

        void setRowSelectedListener(ItemRowView.RowSelectedListener listener) {
            mRowSelectedListener = listener;
        }

        void setRowClickedListener(ItemRowView.RowClickedListener listener) {
            mRowClickedListener = listener;
        }

        List<BaseItemDto> getItems() {
            return mItems;
        }

        void setItems(List<BaseItemDto> items) {
            mItems.clear();
            mItems.addAll(items);
            notifyDataSetChanged();
        }

        void appendItems(List<BaseItemDto> items) {
            if (items.isEmpty()) return;
            int start = mItems.size();
            mItems.addAll(items);
            notifyItemRangeInserted(start, items.size());
        }

        void updateItem(int position, BaseItemDto item) {
            mItems.set(position, item);
            notifyItemChanged(position);
        }

        void setPlayingItemId(UUID id) {
            if (java.util.Objects.equals(mPlayingItemId, id)) return;

            Integer previous = positionOf(mPlayingItemId);
            mPlayingItemId = id;
            mPlayingPosition = -1;

            if (previous != null) notifyItemChanged(previous);
            Integer current = positionOf(mPlayingItemId);
            if (current != null) notifyItemChanged(current);
        }

        void updateCurrentTime(long pos) {
            mPlayingPosition = pos;
        }

        @Nullable
        private Integer positionOf(UUID id) {
            if (id == null) return null;
            for (int i = 0; i < mItems.size(); i++) {
                UUID candidate = mItems.get(i).getId();
                if (id.equals(candidate)) return i;
            }
            return null;
        }

        @NonNull
        @Override
        public ItemRowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemRowView row = new ItemRowView(parent.getContext());
            row.setRowSelectedListener(mRowSelectedListener);
            row.setRowClickedListener(mRowClickedListener);
            return new ItemRowHolder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull ItemRowHolder holder, int position) {
            BaseItemDto item = mItems.get(position);
            ItemRowView row = holder.row;
            row.setRowSelectedListener(mRowSelectedListener);
            row.setRowClickedListener(mRowClickedListener);
            row.setItem(item, position);

            boolean isPlaying = mPlayingItemId != null && mPlayingItemId.equals(item.getId());
            if (isPlaying) {
                row.setPlaying(true);
                row.updateCurrentTime(mPlayingPosition);
            }
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }
}
