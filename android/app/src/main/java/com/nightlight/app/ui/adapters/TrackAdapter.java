package com.nightlight.app.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.nightlight.app.R;
import com.nightlight.app.domain.model.Track;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Row adapter with DiffUtil content updates and precise like-state rebinds.
 * No network or database work happens in onBindViewHolder.
 */
public class TrackAdapter extends ListAdapter<Track, TrackAdapter.VH> {

    public interface Callbacks {
        void onTrackClick(Track track);

        void onLikeClick(Track track);

        void onMoreClick(Track track);
    }

    private static final DiffUtil.ItemCallback<Track> DIFF = new DiffUtil.ItemCallback<Track>() {
        @Override
        public boolean areItemsTheSame(@NonNull Track oldItem, @NonNull Track newItem) {
            return oldItem.id.equals(newItem.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Track oldItem, @NonNull Track newItem) {
            return oldItem.name.equals(newItem.name)
                    && oldItem.artists.equals(newItem.artists)
                    && oldItem.imageUrl.equals(newItem.imageUrl)
                    && oldItem.durationMs == newItem.durationMs;
        }
    };

    private final Callbacks callbacks;
    private final Set<String> likedIds = new HashSet<>();

    public TrackAdapter(Callbacks callbacks) {
        super(DIFF);
        this.callbacks = callbacks;
    }

    /** Rebind visible rows only when like state changes. */
    public void setLikedIds(Set<String> ids) {
        if (ids == null) {
            return;
        }
        List<Track> current = getCurrentList();
        boolean changed = false;
        for (int i = 0; i < current.size(); i++) {
            boolean wasLiked = likedIds.contains(current.get(i).id);
            boolean isLiked = ids.contains(current.get(i).id);
            if (wasLiked != isLiked) {
                changed = true;
            }
        }
        likedIds.clear();
        likedIds.addAll(ids);
        if (changed) {
            notifyItemRangeChanged(0, getCurrentList().size());
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_track, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Track track = getItem(position);
        holder.name.setText(track.name);
        holder.artists.setText(track.artists.isEmpty() ? "Unknown artist" : track.artists);
        holder.duration.setText(Track.formatDuration(track.durationMs));

        boolean liked = likedIds.contains(track.id);
        holder.like.setImageResource(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        holder.like.setContentDescription(holder.itemView.getContext()
                .getString(liked ? R.string.action_unlike : R.string.action_like));

        if (track.imageUrl == null || track.imageUrl.isEmpty()) {
            Glide.with(holder.itemView)
                    .clear(holder.artwork);
            holder.artwork.setImageResource(R.drawable.bg_artwork_placeholder);
        } else {
            Glide.with(holder.itemView)
                    .load(track.imageUrl)
                    .placeholder(R.drawable.bg_artwork_placeholder)
                    .error(R.drawable.bg_artwork_placeholder)
                    .override(160, 160)
                    .centerCrop()
                    .into(holder.artwork);
        }

        holder.itemView.setOnClickListener(v -> callbacks.onTrackClick(track));
        holder.like.setOnClickListener(v -> callbacks.onLikeClick(track));
        holder.more.setOnClickListener(v -> callbacks.onMoreClick(track));
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView artwork;
        final TextView name;
        final TextView artists;
        final TextView duration;
        final ImageButton like;
        final ImageButton more;

        VH(@NonNull View itemView) {
            super(itemView);
            artwork = itemView.findViewById(R.id.track_artwork);
            name = itemView.findViewById(R.id.track_name);
            artists = itemView.findViewById(R.id.track_artists);
            duration = itemView.findViewById(R.id.track_duration);
            like = itemView.findViewById(R.id.track_like);
            more = itemView.findViewById(R.id.track_more);
        }
    }
}