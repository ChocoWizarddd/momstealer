package com.nightlight.app.util;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;

import com.nightlight.app.BuildConfig;
import com.nightlight.app.ui.CrashReportActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Last-resort crash capture. Installs an uncaught-exception handler
 * that persists the full stack trace to a file and shows a report
 * screen, so a crash on a manually installed build can be reported
 * back without adb or logcat.
 *
 * <p>The reporter itself must never throw: every step is wrapped, and
 * a crash inside the reporter falls through to the previous handler
 * (the system default), so the original crash is never masked.
 */
public final class CrashReporter {

    private static final String TAG = "NightLightCrash";
    private static final String FILE_NAME = "nightlight-crash.txt";

    /** Set while the report screen is up: a second crash chains to the
     *  system handler instead of looping the report screen forever. */
    private static volatile boolean reportShowing;

    private CrashReporter() {
    }

    public static void install(final Context app) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (reportShowing) {
                // Already showing a report (or the report screen itself
                // crashed): let the system handle it normally.
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
                return;
            }
            try {
                String trace = render(app, thread, throwable);
                Log.e(TAG, "Uncaught exception on " + (thread != null ? thread.getName() : "?"), throwable);
                persist(app, trace);
                reportShowing = true;
                showReport(app, trace);
            } catch (Throwable ignored) {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
            // Deliberately NOT chaining to the system handler on the
            // main thread: the app stays up just long enough to read
            // and copy the report. Closing the report screen kills the
            // process, so the app never keeps running in a broken
            // state.
        });
    }

    private static String render(Context app, Thread thread, Throwable throwable) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("NightLight crash report\n");
        sb.append("=====================\n");
        sb.append("time:    ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');
        sb.append("app:     NightLight ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("device:  ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" — Android ")
                .append(android.os.Build.VERSION.RELEASE)
                .append(" (api ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("thread:  ").append(thread != null ? thread.getName() : "?").append('\n');
        sb.append("error:   ").append(throwable).append('\n');
        sb.append("\nstack trace:\n");
        sb.append(Log.getStackTraceString(throwable));
        return sb.toString();
    }

    /** Persists the trace where a file manager can reach it, so it
     *  survives even if the report screen cannot be shown. */
    private static void persist(Context app, String trace) {
        File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = app.getFilesDir();
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File file = new File(dir, FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(trace);
        } catch (Throwable ignored) {
        }
    }

    private static void showReport(Context app, String trace) {
        try {
            Intent intent = new Intent(app, CrashReportActivity.class);
            intent.putExtra(CrashReportActivity.EXTRA_TRACE, trace);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            app.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }
}
