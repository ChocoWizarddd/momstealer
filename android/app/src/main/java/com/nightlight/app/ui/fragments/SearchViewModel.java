package com.nightlight.app.ui.fragments;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.data.repo.MusicRepository;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.util.NetworkMonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Search state machine. A single requestId guards against stale responses:
 * only the newest request may publish results. Debounce is 400 ms.
 */
public class SearchViewModel extends AndroidViewModel {

    public enum Status { IDLE, LOADING, SUCCESS, EMPTY, ERROR, OFFLINE, RATE_LIMITED }

    public static final class UiState {
        public final Status status;
        public final List<Track> tracks;
        public final List<String> recentQueries;
        public final String message;
        public final boolean hasMore;
        public final int nextPage;

        UiState(Status status, List<Track> tracks, List<String> recentQueries, String message,
                boolean hasMore, int nextPage) {
            this.status = status;
            this.tracks = tracks;
            this.recentQueries = recentQueries;
            this.message = message;
            this.hasMore = hasMore;
            this.nextPage = nextPage;
        }

        static UiState idle(List<String> recent) {
            return new UiState(Status.IDLE, new ArrayList<>(), recent, null, false, 0);
        }

        static UiState loading(List<String> recent, List<Track> current) {
            return new UiState(Status.LOADING, new ArrayList<>(current), recent, null, false, 0);
        }
    }

    private static final long DEBOUNCE_MS = 220;

    private final MusicRepository music;
    private final NightLightApp app;
    private final MutableLiveData<UiState> state = new MutableLiveData<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable debounced = this::executeSearch;
    private final LiveData<List<String>> historyLiveData;
    private final Observer<List<String>> historyObserver = this::onHistoryChanged;

    private List<String> recent = new ArrayList<>();
    private int requestId;
    private String pendingQuery = "";
    private String activeQuery = "";
    private int nextPage;
    private boolean hasMore;
    private final List<Track> accumulated = new ArrayList<>();

    public SearchViewModel(@NonNull Application application) {
        super(application);
        this.app = (NightLightApp) application;
        this.music = app.getMusicRepository();
        historyLiveData = app.getLibraryRepository().observeHistoryQueries();
        historyLiveData.observeForever(historyObserver);
        state.setValue(UiState.idle(recent));
    }

    @Override
    protected void onCleared() {
        handler.removeCallbacksAndMessages(null);
        historyLiveData.removeObserver(historyObserver);
        super.onCleared();
    }

    public LiveData<UiState> getState() {
        return state;
    }

    /** Called on every keystroke; debounces and cancels stale work. */
    public void onQueryChanged(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        pendingQuery = query;
        handler.removeCallbacks(debounced);
        if (query.isEmpty()) {
            requestId++;
            music.cancelSearch();
            accumulated.clear();
            state.setValue(UiState.idle(recent));
            return;
        }
        // Keep current results attached so the UI can preserve them until
        // replacements arrive instead of flashing an empty spinner.
        state.setValue(UiState.loading(recent, accumulated));
        handler.postDelayed(debounced, DEBOUNCE_MS);
    }

    public void retry() {
        if (!pendingQuery.isEmpty()) {
            state.setValue(UiState.loading(recent, accumulated));
            handler.removeCallbacks(debounced);
            handler.post(debounced);
        }
    }

    public void loadMore() {
        UiState current = state.getValue();
        if (!hasMore || pendingQuery.isEmpty() || current == null
                || !Status.SUCCESS.equals(current.status)) {
            return;
        }
        executeSearch();
    }

    private void onHistoryChanged(List<String> queries) {
        recent = queries != null ? queries : new ArrayList<>();
        if (pendingQuery.isEmpty()) {
            state.setValue(UiState.idle(recent));
        }
    }

    private void executeSearch() {
        String query = pendingQuery;
        if (query.isEmpty()) {
            return;
        }
        boolean isLoadMore = query.equals(activeQuery);
        final int id = ++requestId;
        final int page = isLoadMore ? nextPage : 0;

        if (!isLoadMore) {
            activeQuery = query;
            accumulated.clear();
            nextPage = 1;
            hasMore = false;
        }

        if (!NetworkMonitor.get(getApplication()).isOnline()) {
            if (id == requestId) {
                state.setValue(new UiState(Status.OFFLINE, accumulated, recent, null, false, page));
            }
            return;
        }

        music.searchSongs(query, page, 20, new MusicRepository.SearchCallback() {
            @Override
            public void onSuccess(List<Track> tracks, int total, int page) {
                if (id != requestId) {
                    return; // stale response — a newer query is in flight
                }
                accumulated.addAll(tracks);
                boolean more = !tracks.isEmpty() && accumulated.size() < total;
                nextPage = page + 1;
                hasMore = more;
                Status status = accumulated.isEmpty() ? Status.EMPTY : Status.SUCCESS;
                state.setValue(new UiState(status, new ArrayList<>(accumulated), recent, null, more, nextPage));
                if (page == 0 && !query.isEmpty()) {
                    app.getLibraryRepository().addSearchHistory(query);
                }
            }

            @Override
            public void onFailure(Throwable error) {
                if (id != requestId) {
                    return;
                }
                Status status = Status.ERROR;
                if (error instanceof MusicRepository.HttpStatusException) {
                    int code = ((MusicRepository.HttpStatusException) error).status;
                    if (code == 429) {
                        status = Status.RATE_LIMITED;
                    }
                }
                state.setValue(new UiState(status, accumulated, recent, null, false, page));
            }
        });
    }
}