package com.nightlight.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.nightlight.app.R;

/**
 * Shown by {@link com.nightlight.app.util.CrashReporter} when the app
 * crashes. Displays the full stack trace with a copy button so a
 * crash on a manually installed build can be reported back without
 * adb or logcat.
 *
 * <p>Leaving the screen (Close or Back) terminates the process: the
 * crashed app must not keep running in a broken state.
 */
public class CrashReportActivity extends AppCompatActivity {

    public static final String EXTRA_TRACE = "crash_trace";

    private String trace = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash_report);
        String fromIntent = getIntent() != null ? getIntent().getStringExtra(EXTRA_TRACE) : null;
        render(fromIntent != null ? fromIntent : "(no trace captured)");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String fromIntent = intent != null ? intent.getStringExtra(EXTRA_TRACE) : null;
        if (fromIntent != null) {
            render(fromIntent);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        terminate();
    }

    private void render(String traceText) {
        this.trace = traceText;
        TextView view = findViewById(R.id.crash_trace);
        if (view != null) {
            view.setText(traceText);
        }
        MaterialButton copy = findViewById(R.id.crash_copy);
        if (copy != null) {
            copy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("NightLight crash report", trace));
                    copy.setText(R.string.crash_copied);
                }
            });
        }
        MaterialButton close = findViewById(R.id.crash_close);
        if (close != null) {
            close.setOnClickListener(v -> terminate());
        }
    }

    /** The app already crashed once — leave nothing half-alive. */
    private void terminate() {
        finish();
        Process.killProcess(Process.myPid());
    }
}
