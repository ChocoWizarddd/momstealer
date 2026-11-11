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
import com.nightlight.app.domain.model.Playlist;

public class PlaylistAdapter extends ListAdapter<Playlist, PlaylistAdapter.VH> {

    public interface Callbacks {
        void onPlaylistClick(Playlist playlist);

        void onMoreClick(Playlist playlist);
    }

    private static final DiffUtil.ItemCallback<Playlist> DIFF = new DiffUtil.ItemCallback<Playlist>() {
        @Override
        public boolean areItemsTheSame(@NonNull Playlist oldItem, @NonNull Playlist newItem) {
            return oldItem.id.equals(newItem.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Playlist oldItem, @NonNull Playlist newItem) {
            return oldItem.name.equals(newItem.name) && oldItem.trackCount == newItem.trackCount;
        }
    };

    private final Callbacks callbacks;

    public PlaylistAdapter(Callbacks callbacks) {
        super(DIFF);
        this.callbacks = callbacks;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Playlist playlist = getItem(position);
        holder.name.setText(playlist.name);
        holder.count.setText(holder.itemView.getContext()
                .getString(R.string.playlist_tracks_count, playlist.trackCount));

        if (playlist.artworkUrl == null || playlist.artworkUrl.isEmpty()) {
            Glide.with(holder.itemView).clear(holder.artwork);
            holder.artwork.setImageResource(R.drawable.bg_artwork_placeholder);
        } else {
            Glide.with(holder.itemView)
                    .load(playlist.artworkUrl)
                    .placeholder(R.drawable.bg_artwork_placeholder)
                    .error(R.drawable.bg_artwork_placeholder)
                    .override(160, 160)
                    .centerCrop()
                    .into(holder.artwork);
        }

        holder.itemView.setOnClickListener(v -> callbacks.onPlaylistClick(playlist));
        holder.more.setOnClickListener(v -> callbacks.onMoreClick(playlist));
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView artwork;
        final TextView name;
        final TextView count;
        final ImageButton more;

        VH(@NonNull View itemView) {
            super(itemView);
            artwork = itemView.findViewById(R.id.playlist_artwork);
            name = itemView.findViewById(R.id.playlist_name);
            count = itemView.findViewById(R.id.playlist_count);
            more = itemView.findViewById(R.id.playlist_more);
        }
    }
}