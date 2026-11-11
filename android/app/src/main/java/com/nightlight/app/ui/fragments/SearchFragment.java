package com.nightlight.app.ui.fragments;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.ui.adapters.HistoryAdapter;
import com.nightlight.app.ui.adapters.TrackAdapter;
import com.nightlight.app.ui.common.PlaylistDialogs;
import com.nightlight.app.ui.common.TrackPlayer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SearchFragment extends Fragment {

    private SearchViewModel viewModel;
    private NightLightApp app;
    private LibraryRepository library;

    private EditText input;
    private RecyclerView resultsList;
    private RecyclerView recentList;
    private View browseView;
    private TextView recentTitle;
    private View searchHeading;
    private TextView cancelSearch;
    private View stateView;
    private ProgressBar stateProgress;
    private ImageView stateIcon;
    private TextView stateText;
    private MaterialButton stateRetry;

    private TrackAdapter resultsAdapter;
    private HistoryAdapter historyAdapter;

    private LiveData<List<Track>> likesLiveData;
    private final Observer<List<Track>> likesObserver = this::onLikesChanged;
    private Set<String> likedIds = new HashSet<>();
    private final Observer<SearchViewModel.UiState> stateObserver = this::render;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = (NightLightApp) requireActivity().getApplication();
        library = app.getLibraryRepository();
        viewModel = new androidx.lifecycle.ViewModelProvider(this).get(SearchViewModel.class);

        input = view.findViewById(R.id.search_input);
        resultsList = view.findViewById(R.id.search_results);
        recentList = view.findViewById(R.id.search_recent);
        browseView = view.findViewById(R.id.search_browse);
        recentTitle = view.findViewById(R.id.search_recent_title);
        searchHeading = view.findViewById(R.id.search_heading);
        cancelSearch = view.findViewById(R.id.search_cancel);
        view.setFocusableInTouchMode(true);
        view.requestFocus();
        stateView = view.findViewById(R.id.search_state);
        stateProgress = view.findViewById(R.id.search_state_progress);
        stateIcon = view.findViewById(R.id.search_state_icon);
        stateText = view.findViewById(R.id.search_state_text);
        stateRetry = view.findViewById(R.id.search_state_retry);

        resultsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        resultsAdapter = new TrackAdapter(new TrackAdapter.Callbacks() {
            @Override
            public void onTrackClick(Track track) {
                // SEARCH is not RECOMMENDATION (§3): in Smart mode the tap
                // starts a fresh recommendation session around the tapped
                // song — the search list itself is never queued. Shuffle OFF
                // / NORMAL keep the raw search order (variant browsing).
                List<Track> queue = new java.util.ArrayList<>(resultsAdapter.getCurrentList());
                int index = indexOf(queue, track);
                if (com.nightlight.app.util.ShufflePrefs.isSmart(requireContext())) {
                    TrackPlayer.playSmartSession(requireContext(), queue, index);
                } else {
                    TrackPlayer.play(requireContext(), queue, index);
                }
                hideKeyboard();
            }

            @Override
            public void onLikeClick(Track track) {
                // Read the like state from the observed set; a direct
                // Room read here would crash (main-thread database access).
                boolean like = !likedIds.contains(track.id);
                if (like) {
                    likedIds.add(track.id);
                } else {
                    likedIds.remove(track.id);
                }
                library.toggleLike(track, like, null);
            }

            @Override
            public void onMoreClick(Track track) {
                showTrackSheet(track);
            }
        });
        resultsList.setAdapter(resultsAdapter);
        resultsList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!recyclerView.canScrollVertically(1)) {
                    viewModel.loadMore();
                }
            }
        });

        recentList.setLayoutManager(new LinearLayoutManager(requireContext()));
        historyAdapter = new HistoryAdapter(new HistoryAdapter.Callbacks() {
            @Override
            public void onQueryClick(String query) {
                input.setText(query);
                input.setSelection(query.length());
            }

            @Override
            public void onQueryDelete(String query) {
                library.deleteSearchHistoryQuery(query);
            }
        });
        recentList.setAdapter(historyAdapter);

        stateRetry.setOnClickListener(v -> viewModel.retry());

        bindGenreSearch(view, R.id.search_category_pop, "pop music");
        bindGenreSearch(view, R.id.search_category_indie, "indie music");
        bindGenreSearch(view, R.id.search_category_hiphop, "hip hop music");
        bindGenreSearch(view, R.id.search_category_rock, "rock music");
        bindGenreSearch(view, R.id.search_category_jazz, "jazz music");
        bindGenreSearch(view, R.id.search_category_electronic, "electronic music");

        input.setOnFocusChangeListener((v, hasFocus) -> {
            searchHeading.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
            cancelSearch.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
            SearchViewModel.UiState currentState = viewModel.getState().getValue();
            if (currentState != null && currentState.status == SearchViewModel.Status.IDLE) {
                render(currentState);
            }
        });
        cancelSearch.setOnClickListener(v -> {
            input.setText("");
            hideKeyboard();
            SearchViewModel.UiState currentState = viewModel.getState().getValue();
            if (currentState != null) render(currentState);
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.onQueryChanged(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    private void showTrackSheet(Track track) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(track.name)
                .setItems(new CharSequence[]{
                        requireContext().getString(R.string.action_add_to_playlist),
                        "Play next",
                        "Add to queue"
                }, (dialog, which) -> {
                    if (which == 0) {
                        PlaylistDialogs.showAddToPlaylistSheet(requireActivity(), track);
                    } else if (which == 1) {
                        PlaybackManager.get(requireContext()).playNext(track);
                    } else {
                        PlaybackManager.get(requireContext())
                                .addToQueue(Collections.singletonList(track));
                    }
                })
                .show();
    }

    private void render(SearchViewModel.UiState state) {
        if (state == null) {
            return;
        }
        switch (state.status) {
            case IDLE:
                resultsList.setVisibility(View.GONE);
                boolean showRecent = input.hasFocus();
                browseView.setVisibility(showRecent ? View.GONE : View.VISIBLE);
                recentList.setVisibility(showRecent ? View.VISIBLE : View.GONE);
                recentTitle.setVisibility(showRecent && !state.recentQueries.isEmpty()
                        ? View.VISIBLE : View.GONE);
                stateView.setVisibility(View.GONE);
                historyAdapter.submit(state.recentQueries);
                break;
            case LOADING:
                browseView.setVisibility(View.GONE);
                recentTitle.setVisibility(View.GONE);
                // Preserve existing results while refining: only show the
                // full spinner state when there is nothing to keep on screen.
                if (resultsAdapter.getCurrentList() == null || resultsAdapter.getCurrentList().isEmpty()) {
                    resultsList.setVisibility(View.GONE);
                    recentList.setVisibility(View.GONE);
                    stateView.setVisibility(View.VISIBLE);
                    stateProgress.setVisibility(View.VISIBLE);
                    stateIcon.setVisibility(View.GONE);
                    stateRetry.setVisibility(View.GONE);
                    stateText.setText(R.string.search_state_loading);
                } else {
                    resultsList.setVisibility(View.VISIBLE);
                    recentList.setVisibility(View.GONE);
                    stateView.setVisibility(View.GONE);
                }
                break;
            case SUCCESS:
                browseView.setVisibility(View.GONE);
                recentTitle.setVisibility(View.GONE);
                resultsList.setVisibility(View.VISIBLE);
                recentList.setVisibility(View.GONE);
                stateView.setVisibility(View.GONE);
                resultsAdapter.submitList(state.tracks);
                break;
            case EMPTY:
                browseView.setVisibility(View.GONE);
                recentTitle.setVisibility(View.GONE);
                resultsList.setVisibility(View.GONE);
                recentList.setVisibility(View.GONE);
                stateView.setVisibility(View.VISIBLE);
                stateProgress.setVisibility(View.GONE);
                stateIcon.setVisibility(View.VISIBLE);
                stateIcon.setImageResource(R.drawable.ic_music_note);
                stateRetry.setVisibility(View.GONE);
                String query = input.getText() != null ? input.getText().toString().trim() : "";
                stateText.setText(getString(R.string.empty_results, query));
                break;
            case OFFLINE:
                showErrorState(R.string.search_state_offline, R.drawable.ic_offline);
                break;
            case RATE_LIMITED:
                showErrorState(R.string.search_state_rate_limited, R.drawable.ic_error_outline);
                break;
            case ERROR:
            default:
                showErrorState(R.string.search_state_error, R.drawable.ic_error_outline);
                break;
        }
    }

    private void showErrorState(int textRes, int iconRes) {
        browseView.setVisibility(View.GONE);
        recentTitle.setVisibility(View.GONE);
        resultsList.setVisibility(View.GONE);
        recentList.setVisibility(View.GONE);
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateIcon.setVisibility(View.VISIBLE);
        stateIcon.setImageResource(iconRes);
        stateRetry.setVisibility(View.VISIBLE);
        stateText.setText(textRes);
    }

    private void onLikesChanged(List<Track> likes) {
        Set<String> ids = new HashSet<>();
        for (Track t : likes) {
            ids.add(t.id);
        }
        likedIds = ids;
        resultsAdapter.setLikedIds(ids);
    }

    private static int indexOf(List<Track> tracks, Track track) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id.equals(track.id)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Focus + open the keyboard on the actual search field. Called when the
     * user taps the Search tab; defers via post() until the view is attached
     * to the window so getWindowToken() is valid and showSoftInput succeeds.
     */
    public void focusSearchInput() {
        if (input == null || !isAdded()) {
            return;
        }
        input.post(() -> {
            if (!isAdded() || input == null) {
                return;
            }
            input.setFocusableInTouchMode(true);
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null && input.getWindowToken() != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null && input != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        input.clearFocus();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    public void onSearchTabTapped() {
        if (input != null && input.length() == 0) {
            input.clearFocus();
            searchHeading.setVisibility(View.VISIBLE);
            cancelSearch.setVisibility(View.GONE);
            SearchViewModel.UiState currentState = viewModel.getState().getValue();
            if (currentState != null) render(currentState);
        }
    }

    private void bindGenreSearch(View root, int viewId, String query) {
        View tile = root.findViewById(viewId);
        tile.setOnClickListener(v -> {
            input.setText(query);
            input.setSelection(input.length());
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        likesLiveData = library.observeLikes();
        likesLiveData.observe(this, likesObserver);
        viewModel.getState().observe(this, stateObserver);
    }

    @Override
    public void onStop() {
        super.onStop();
        viewModel.getState().removeObserver(stateObserver);
        if (likesLiveData != null) {
            likesLiveData.removeObserver(likesObserver);
        }
    }
}
