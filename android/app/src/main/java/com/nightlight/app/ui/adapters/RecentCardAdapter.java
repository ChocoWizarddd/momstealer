package com.nightlight.app.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.nightlight.app.R;
import com.nightlight.app.domain.model.Track;

public class RecentCardAdapter extends ListAdapter<Track, RecentCardAdapter.VH> {

    public interface Callbacks {
        void onTrackClick(Track track);
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
                    && oldItem.imageUrl.equals(newItem.imageUrl);
        }
    };

    private final Callbacks callbacks;
    private final int layoutRes;

    public RecentCardAdapter(Callbacks callbacks) {
        this(callbacks, R.layout.item_recent_card);
    }

    public RecentCardAdapter(Callbacks callbacks, int layoutRes) {
        super(DIFF);
        this.callbacks = callbacks;
        this.layoutRes = layoutRes;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(layoutRes, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Track track = getItem(position);
        holder.name.setText(track.name);
        holder.artist.setText(track.artists.isEmpty() ? "Unknown artist" : track.artists);

        if (track.imageUrl == null || track.imageUrl.isEmpty()) {
            Glide.with(holder.itemView).clear(holder.artwork);
            holder.artwork.setImageResource(R.drawable.bg_artwork_placeholder);
        } else {
            Glide.with(holder.itemView)
                    .load(track.imageUrl)
                    .placeholder(R.drawable.bg_artwork_placeholder)
                    .error(R.drawable.bg_artwork_placeholder)
                    .override(240, 240)
                    .centerCrop()
                    .into(holder.artwork);
        }

        holder.itemView.setOnClickListener(v -> callbacks.onTrackClick(track));
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView artwork;
        final TextView name;
        final TextView artist;

        VH(@NonNull View itemView) {
            super(itemView);
            artwork = itemView.findViewById(R.id.recent_artwork);
            name = itemView.findViewById(R.id.recent_name);
            artist = itemView.findViewById(R.id.recent_artist);
        }
    }
}
