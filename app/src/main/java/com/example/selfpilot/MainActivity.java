package com.example.selfpilot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.service.FocusTimerService;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final long LOCK_TICK_MS = 250L;
    private static final int LOCK_BACKGROUND = 0xFF0B0B12;
    private static final int LOCK_ACCENT = 0xFFB39DDB;

    private View focusLockView;
    private TextView focusLockTimer;
    private long focusLockEndTime = 0L;
    private OnBackPressedCallback focusLockBackCallback;
    private final Handler lockHandler = new Handler(Looper.getMainLooper());

    private final Runnable lockTick = new Runnable() {
        @Override
        public void run() {
            if (focusLockView == null) return;

            boolean serviceRunning = FocusTimerService.isRunning();
            long left = serviceRunning
                    ? FocusTimerService.getTimeLeftMs()
                    : Math.max(0L, focusLockEndTime - System.currentTimeMillis());

            if (!serviceRunning && left <= 0L) {
                removeFocusLock();
                return;
            }

            if (focusLockTimer != null) {
                focusLockTimer.setText(formatHms(left));
            }
            lockHandler.postDelayed(this, LOCK_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.example.selfpilot.util.FrozenAppsManager.init(this);
        ensureGuardianRunning();
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
            NavigationUI.setupWithNavController(bottomNav, navController);
        }

        // Swallow the back gesture/key while a focus session is locked.
        focusLockBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                // Intentional no-op: a running session cannot be left.
            }
        };
        getOnBackPressedDispatcher().addCallback(this, focusLockBackCallback);

        handleNavigationIntent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        syncFocusLock();
    }

    @Override
    protected void onStop() {
        super.onStop();
        lockHandler.removeCallbacks(lockTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNavigationIntent();
    }

    /** Called by the focus screen whenever a session starts or finishes. */
    public void onFocusSessionStateChanged() {
        syncFocusLock();
    }

    /**
     * SelfPilot must always keep running in the background: the freezer is only as reliable
     * as the process that enforces it.
     */
    private void ensureGuardianRunning() {
        requestNotificationPermission();
        com.example.selfpilot.service.GuardianService.start(this);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(
                this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
    }

    private void handleNavigationIntent() {
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("NAVIGATE_TO")) {
            int navId = intent.getIntExtra("NAVIGATE_TO", R.id.navigation_home);
            BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
            if (bottomNav != null) {
                bottomNav.setSelectedItemId(navId);
            }
            intent.removeExtra("NAVIGATE_TO"); // prevent re-triggering on rotation
        }
    }

    // ------------------------------------------------------------------------------------
    // Focus lock
    // ------------------------------------------------------------------------------------

    private void syncFocusLock() {
        if (FocusTimerService.isRunning()) {
            showFocusLock(0L);
            return;
        }

        // The service may have been killed while the session is still alive in the database.
        new Thread(() -> {
            long endTime = 0L;
            try {
                FocusSessionEntity active = AppDatabase.getDatabase(getApplicationContext())
                        .focusSessionDao().getActiveSession();
                if (active != null && active.endTime > System.currentTimeMillis()) {
                    endTime = active.endTime;
                }
            } catch (Exception ignored) {
            }

            final long resolvedEndTime = endTime;
            runOnUiThread(() -> {
                if (resolvedEndTime > 0L) {
                    showFocusLock(resolvedEndTime);
                } else {
                    removeFocusLock();
                }
            });
        }).start();
    }

    /**
     * Covers the whole screen with the countdown. While it is attached the bottom navigation,
     * every tab and the back gesture are unreachable, so a running session cannot be left.
     */
    private void showFocusLock(long endTime) {
        if (focusLockView != null) {
            if (endTime > 0L) focusLockEndTime = endTime;
            return;
        }

        ViewGroup root = findViewById(R.id.main);
        if (root == null) return;

        FrameLayout lock = new FrameLayout(this);
        lock.setBackgroundColor(LOCK_BACKGROUND);
        lock.setClickable(true);
        lock.setFocusable(true);
        lock.setOnKeyListener((v, keyCode, event) -> true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView badge = new TextView(this);
        badge.setText("FOCUS MODE");
        badge.setTextColor(LOCK_ACCENT);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        badge.setLetterSpacing(0.28f);
        badge.setGravity(Gravity.CENTER);
        column.addView(badge);

        focusLockTimer = new TextView(this);
        focusLockTimer.setText(formatHms(
                FocusTimerService.isRunning()
                        ? FocusTimerService.getTimeLeftMs()
                        : Math.max(0L, focusLockEndTime - System.currentTimeMillis())));
        focusLockTimer.setTextColor(Color.WHITE);
        focusLockTimer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f);
        focusLockTimer.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        focusLockTimer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams timerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timerParams.topMargin = dp(20);
        column.addView(focusLockTimer, timerParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("Stay focused. This session cannot be left until the timer ends.");
        subtitle.setTextColor(0xFF9E9E9E);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(32), dp(14), dp(32), 0);
        column.addView(subtitle);

        lock.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        root.addView(lock, lp);

        focusLockView = lock;
        if (focusLockBackCallback != null) focusLockBackCallback.setEnabled(true);

        lockHandler.removeCallbacks(lockTick);
        lockHandler.post(lockTick);
    }

    private void removeFocusLock() {
        lockHandler.removeCallbacks(lockTick);

        if (focusLockView != null) {
            ViewGroup parent = (ViewGroup) focusLockView.getParent();
            if (parent != null) parent.removeView(focusLockView);
            focusLockView = null;
        }
        focusLockTimer = null;
        focusLockEndTime = 0L;

        if (focusLockBackCallback != null) focusLockBackCallback.setEnabled(false);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private static String formatHms(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
    }
}
