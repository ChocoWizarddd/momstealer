package com.nightlight.app.data.api;

import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.LyricsDtos;
import com.nightlight.app.data.api.dto.SongDtos;
import com.nightlight.app.data.api.dto.TrendingDtos;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Retrofit contract for the JioSaavn music-proxy endpoints (matches Flutter reference). */
public interface MusicApi {

    @GET("search/songs")
    Call<ApiResponse<SongDtos.SearchSongsDto>> searchSongs(
            @Query("query") String query,
            @Query("page") int page,
            @Query("limit") int limit);

    @GET("songs")
    Call<ApiResponse<List<SongDtos.SongDto>>> getSongs(@Query("ids") String ids);

    /** JioSaavn's own similar songs for a track — this is what makes the queue feel like a real streaming service. */
    @GET("songs/{id}/suggestions")
    Call<ApiResponse<List<SongDtos.SongDto>>> getSuggestions(
            @Path("id") String id,
            @Query("limit") int limit);

    /** Trending/top-chart songs for the home screen. */
    @GET("search/trending")
    Call<ApiResponse<TrendingDtos.TrendingDto>> getTrending();

    /** Radio/related songs for a track — seeds the smart shuffle queue. */
    @GET("search/radio")
    Call<ApiResponse<SongDtos.SearchSongsDto>> getRadio(
            @Query("seedId") String seedId,
            @Query("seedName") String seedName,
            @Query("artists") String artists,
            @Query("album") String album,
            @Query("limit") int limit);

    /** Synchronized lyrics for a track (LRCLIB backend). */
    @GET("lyrics")
    Call<ApiResponse<LyricsDtos.LyricsDto>> getLyrics(
            @Query("track_name") String trackName,
            @Query("artist_name") String artistName,
            @Query("album") String album,
            @Query("duration") long durationMs);
}