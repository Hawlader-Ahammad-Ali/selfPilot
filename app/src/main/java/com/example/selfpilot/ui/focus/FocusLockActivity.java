package com.example.selfpilot.ui.focus;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.util.Log;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.service.FocusTimerService;
import com.example.selfpilot.util.SystemBarInsets;

import java.util.Locale;

/**
 * Dedicated countdown screen shown while a focus session runs. It is a separate interface on
 * purpose: there is no bottom navigation and no back navigation here, so the session cannot be
 * abandoned half way. The screen closes itself the moment the timer ends.
 */
public class FocusLockActivity extends AppCompatActivity {

    private static final String TAG = "SELF_PILOT_FOCUS";
    private static final long TICK_MS = 250L;

    public static volatile boolean VISIBLE = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvTimer;
    private long sessionEndTime = 0L;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long left = timeLeft();
            if (left <= 0 && !FocusTimerService.isRunning()) {
                closeLock("session finished");
                return;
            }
            if (tvTimer != null) {
                tvTimer.setText(formatHms(left));
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    public static void start(Context context) {
        if (context == null || VISIBLE) return;
        try {
            Intent intent = new Intent(context, FocusLockActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "[LOCK] Unable to open focus lock screen", t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_focus_lock);
        SystemBarInsets.apply(findViewById(R.id.focus_lock_root));

        // Full immersion: no status bar, no navigation bar, no notification shade on top.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();

        tvTimer = findViewById(R.id.tvLockTimer);

        // No way out while the session is running.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Log.i(TAG, "[LOCK] Back blocked - focus session still running");
            }
        });

        loadSession();
    }

    @Override
    protected void onStart() {
        super.onStart();
        VISIBLE = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        VISIBLE = false;
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** Immersive sticky: the bars may slide in transiently but never stay. */
    private void hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void loadSession() {
        new Thread(() -> {
            long endTime = 0L;
            try {
                FocusSessionEntity session = AppDatabase.getDatabase(getApplicationContext())
                        .focusSessionDao().getActiveSession();
                if (session != null) {
                    endTime = session.endTime;
                }
            } catch (Exception e) {
                Log.w(TAG, "[LOCK] Unable to read active session", e);
            }

            final long resolved = endTime;
            handler.postDelayed(() -> {
                sessionEndTime = resolved;
                if (resolved <= 0L && !FocusTimerService.isRunning()) {
                    // Nothing to lock: never trap the user on an empty screen.
                    closeLock("no active session");
                }
            }, 400L);
        }).start();
    }

    /** Service clock first (it ticks live), database end time as the fallback. */
    private long timeLeft() {
        if (FocusTimerService.isRunning()) {
            long left = FocusTimerService.getTimeLeftMs();
            if (left > 0) return left;
        }
        if (sessionEndTime > 0L) {
            return Math.max(0L, sessionEndTime - System.currentTimeMillis());
        }
        return 0L;
    }

    private void closeLock(String reason) {
        Log.i(TAG, "[LOCK] Closing focus lock screen (" + reason + ")");
        handler.removeCallbacks(tick);
        VISIBLE = false;
        if (!isFinishing() && !isDestroyed()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private static String formatHms(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }
}

