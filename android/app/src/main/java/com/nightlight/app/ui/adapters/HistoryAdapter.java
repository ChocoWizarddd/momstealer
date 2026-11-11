package com.nightlight.app.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nightlight.app.R;

import java.util.ArrayList;
import java.util.List;

/** Simple vertical list of recent search queries with tap + delete actions. */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface Callbacks {
        void onQueryClick(String query);

        void onQueryDelete(String query);
    }

    private final List<String> queries = new ArrayList<>();
    private final Callbacks callbacks;

    public HistoryAdapter(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void submit(List<String> items) {
        queries.clear();
        if (items != null) {
            queries.addAll(items);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String query = queries.get(position);
        holder.text.setText(query);
        holder.itemView.setOnClickListener(v -> callbacks.onQueryClick(query));
        holder.delete.setOnClickListener(v -> callbacks.onQueryDelete(query));
    }

    @Override
    public int getItemCount() {
        return queries.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView text;
        final View delete;

        VH(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.history_text);
            delete = itemView.findViewById(R.id.history_delete);
        }
    }
}