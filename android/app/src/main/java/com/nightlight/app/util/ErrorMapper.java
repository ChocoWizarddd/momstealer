package com.nightlight.app.util;

import android.content.Context;

import com.nightlight.app.R;
import com.nightlight.app.data.api.dto.ApiResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

import retrofit2.HttpException;
import retrofit2.Response;

/** Converts transport + HTTP errors into user-friendly text. */
public final class ErrorMapper {

    private ErrorMapper() {
    }

    public static String toUserMessage(Context context, Throwable error) {
        if (error instanceof SocketTimeoutException) {
            return context.getString(R.string.error_timeout);
        }
        if (error instanceof UnknownHostException
                || error instanceof ConnectException
                || (error instanceof IOException && error.getMessage() != null
                && error.getMessage().contains("Cleartext"))) {
            return context.getString(R.string.error_offline);
        }
        if (error instanceof SSLException) {
            return context.getString(R.string.error_server);
        }
        if (error instanceof HttpException) {
            int code = ((HttpException) error).code();
            return forHttpCode(context, code);
        }
        if (error instanceof com.nightlight.app.data.repo.MusicRepository.HttpStatusException) {
            String code = ((com.nightlight.app.data.repo.MusicRepository.HttpStatusException) error).code;
            if ("SEARCH_UNAVAILABLE".equals(code)) {
                return context.getString(R.string.error_search_unavailable);
            }
            if ("RATE_LIMITED".equals(code)) {
                return context.getString(R.string.error_rate_limited);
            }
            int status = ((com.nightlight.app.data.repo.MusicRepository.HttpStatusException) error).status;
            return forHttpCode(context, status);
        }
        return context.getString(R.string.error_generic);
    }

    public static String forResponse(Context context, Response<?> response) {
        return forHttpCode(context, response.code());
    }

    /** Maps a parsed error body (success:false) into a friendly message. */
    public static String forApiResponse(Context context, ApiResponse<?> body) {
        if (body == null) {
            return context.getString(R.string.error_generic);
        }
        if ("RATE_LIMITED".equals(body.code)) {
            return context.getString(R.string.error_rate_limited);
        }
        if ("UNAUTHORIZED".equals(body.code)) {
            return context.getString(R.string.error_unauthorized);
        }
        if ("EMAIL_NOT_VERIFIED".equals(body.code)) {
            return "Verify your email first, then continue.";
        }
        if ("FIREBASE_TOKEN_INVALID".equals(body.code)) {
            return "Sign-in could not be verified. Please try again.";
        }
        if ("GOOGLE_NOT_CONFIGURED".equals(body.code)) {
            return "Google sign-in is not on the server yet - try again in a minute.";
        }
        if ("TOKEN_AUDIENCE_MISMATCH".equals(body.code)) {
            return "Sign-in config mismatch - update the app from the latest release.";
        }
        int status = body.code != null ? statusForCode(body.code) : 500;
        return forHttpCode(context, status);
    }

    private static String forHttpCode(Context context, int code) {
        switch (code) {
            case 400:
            case 404:
                return context.getString(R.string.error_not_found);
            case 408:
                return context.getString(R.string.error_timeout);
            case 429:
                return context.getString(R.string.error_rate_limited);
            case 500:
            case 502:
            case 503:
                return context.getString(R.string.error_server);
            default:
                return context.getString(R.string.error_generic);
        }
    }

    private static int statusForCode(String code) {
        switch (code) {
            case "BAD_REQUEST":
            case "BAD_ID":
            case "TRACK_ID_MISMATCH":
            case "EMPTY_NAME":
            case "EMPTY_QUERY":
            case "BAD_PREFERENCE":
                return 400;
            case "TRACK_NOT_LIKED":
            case "HISTORY_NOT_FOUND":
            case "PLAYLIST_NOT_FOUND":
            case "NOT_FOUND":
                return 404;
            default:
                return 500;
        }
    }
}