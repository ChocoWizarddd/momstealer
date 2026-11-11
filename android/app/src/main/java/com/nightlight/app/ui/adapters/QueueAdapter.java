package com.nightlight.app.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.nightlight.app.R;
import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.List;

public class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.VH> {

    public interface Callbacks {
        void onItemClick(int index);

        void onRemoveClick(int index);
    }

    private final List<Track> items = new ArrayList<>();
    private final Callbacks callbacks;
    private int currentIndex = -1;

    public QueueAdapter(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void submit(List<Track> tracks, int current) {
        items.clear();
        if (tracks != null) {
            items.addAll(tracks);
        }
        currentIndex = current;
        notifyDataSetChanged();
    }

    public void setCurrentIndex(int index) {
        int old = currentIndex;
        currentIndex = index;
        if (old >= 0 && old < items.size()) {
            notifyItemChanged(old);
        }
        if (currentIndex >= 0 && currentIndex < items.size()) {
            notifyItemChanged(currentIndex);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_queue, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Track track = items.get(position);
        holder.name.setText(track.name);
        holder.artist.setText(track.artists.isEmpty() ? "Unknown artist" : track.artists);
        holder.nowPlaying.setVisibility(position == currentIndex ? View.VISIBLE : View.GONE);

        if (track.imageUrl == null || track.imageUrl.isEmpty()) {
            Glide.with(holder.itemView).clear(holder.artwork);
            holder.artwork.setImageResource(R.drawable.bg_artwork_placeholder);
        } else {
            Glide.with(holder.itemView)
                    .load(track.imageUrl)
                    .placeholder(R.drawable.bg_artwork_placeholder)
                    .error(R.drawable.bg_artwork_placeholder)
                    .override(120, 120)
                    .centerCrop()
                    .into(holder.artwork);
        }

        int index = position;
        holder.itemView.setOnClickListener(v -> callbacks.onItemClick(index));
        holder.more.setOnClickListener(v -> callbacks.onRemoveClick(index));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView artwork;
        final TextView name;
        final TextView artist;
        final ImageView nowPlaying;
        final ImageButton more;

        VH(@NonNull View itemView) {
            super(itemView);
            artwork = itemView.findViewById(R.id.queue_artwork);
            name = itemView.findViewById(R.id.queue_name);
            artist = itemView.findViewById(R.id.queue_artist);
            nowPlaying = itemView.findViewById(R.id.queue_now_playing);
            more = itemView.findViewById(R.id.queue_more);
        }
    }
}