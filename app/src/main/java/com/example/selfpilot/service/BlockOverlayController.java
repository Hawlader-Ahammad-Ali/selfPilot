package com.example.selfpilot.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full screen block surface drawn as an accessibility overlay.
 *
 * Used for every kind of block: frozen apps, daily limit reached, Focus Mode, Exam Mode,
 * schedules and manual rules.
 *
 * Why an overlay instead of an activity:
 *  - Starting an activity from the background can be refused by the OS (background activity
 *    launch restrictions), which silently let blocked apps through.
 *  - An accessibility overlay needs no extra permission, is added synchronously (no launch
 *    latency, no race with the blocked activity finishing its resume) and always sits above
 *    every application window, so the blocked app can never be seen or touched.
 *
 * The overlay is focusable on purpose: it swallows touches and the back key. Home still
 * works, so the user is never trapped.
 */
public class BlockOverlayController {

    private static final String TAG = "SELF_PILOT_FREEZER";

    private static final int COLOR_BACKGROUND = 0xFF07070C;
    private static final int COLOR_SURFACE = 0xFF15131F;
    private static final int COLOR_ACCENT = 0xFFB39DDB;
    private static final int COLOR_TEXT = 0xFFF3F0FA;
    private static final int COLOR_MUTED = 0xFF9A94AD;
    private static final int COLOR_PRIMARY = 0xFF6C4FD8;

    private static final long CONTENT_FADE_MS = 180L;

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View overlayView;
    private LinearLayout overlayContent;
    private String currentKey;
    private boolean exiting = false;
    private ExitHandler exitHandler;

    /** Implemented by the accessibility service, which owns the foreground detection used to
     *  decide when it is safe to actually detach the block surface. */
    public interface ExitHandler {
        void onGoHome();
        void onOpenSelfPilot();
    }

    public void setExitHandler(ExitHandler handler) {
        this.exitHandler = handler;
    }

    /** Immutable description of what to show. */
    public static class BlockSpec {
        public final String packageName;
        public final String badge;
        public final String title;
        public final String message;
        public final boolean allowEmergencyUnlock;
        public final int unlocksLeft;
        public final Runnable onEmergencyUnlock;

        public BlockSpec(String packageName, String badge, String title, String message,
                         boolean allowEmergencyUnlock, int unlocksLeft, Runnable onEmergencyUnlock) {
            this.packageName = packageName;
            this.badge = badge;
            this.title = title;
            this.message = message;
            this.allowEmergencyUnlock = allowEmergencyUnlock;
            this.unlocksLeft = unlocksLeft;
            this.onEmergencyUnlock = onEmergencyUnlock;
        }

        String key() {
            return packageName + "|" + badge + "|" + message + "|" + allowEmergencyUnlock + "|" + unlocksLeft;
        }
    }

    public BlockOverlayController(AccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return overlayView != null;
    }

    public String getTargetPackage() {
        if (overlayView == null) return null;
        String key = currentKey;
        if (key == null) return null;
        int separator = key.indexOf('|');
        return separator > 0 ? key.substring(0, separator) : key;
    }

    /**
     * Shows (or refreshes) the block surface. Safe to call on every watchdog tick:
     * the view is only rebuilt when the described content actually changed.
     */
    public void show(final BlockSpec spec) {
        if (spec == null || spec.packageName == null || spec.packageName.isEmpty()) return;
        if (windowManager == null) return;

        Runnable apply = () -> applySpec(spec);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run();
        } else {
            mainHandler.post(apply);
        }
    }

    /**
     * Leaves the block surface without flashing the blocked app.
     *
     * The old implementation removed the window first and only then pressed Home, so for a
     * few frames the frozen app was visible again - the "blink" the user saw. Now the opaque
     * backdrop stays in place while the content fades out and the destination screen is being
     * drawn; the window is only detached once the launcher is actually in front (detected by
     * the watchdog) or by the exit timeout, whichever comes first.
     */
    public void animateOut() {
        if (overlayView == null || exiting) return;
        exiting = true;

        if (overlayContent != null) {
            overlayContent.animate()
                    .alpha(0f)
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(CONTENT_FADE_MS)
                    .start();
        }
    }

    /**
     * Reverts {@link #animateOut()} when the destination screen never came up, so the user is
     * not left looking at a blank surface.
     */
    public void cancelExit() {
        exiting = false;
        if (overlayContent != null) {
            overlayContent.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .start();
        }
    }

    public boolean isExiting() {
        return exiting;
    }

    private void applySpec(BlockSpec spec) {
        String key = spec.key();
        if (overlayView != null && key.equals(currentKey)) return;
        hide();

        try {
            View view = buildView(spec);
            windowManager.addView(view, buildParams());
            overlayView = view;
            currentKey = key;
            exiting = false;
            Log.i(TAG, "[OVERLAY] Block surface shown (" + spec.badge + ") for " + spec.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "[OVERLAY] Failed to show overlay for " + spec.packageName, t);
        }
    }

    public void hide() {
        if (overlayView == null) {
            currentKey = null;
            overlayContent = null;
            exiting = false;
            return;
        }
        View view = overlayView;
        overlayView = null;
        overlayContent = null;
        currentKey = null;
        exiting = false;

        Runnable remove = () -> {
            try {
                windowManager.removeView(view);
            } catch (Throwable ignored) {
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove.run();
        } else {
            mainHandler.post(remove);
        }
    }

    // ------------------------------------------------------------------------------------
    // View construction
    // ------------------------------------------------------------------------------------

    private WindowManager.LayoutParams buildParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("SelfPilotBlock");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
    }

    private View buildView(BlockSpec spec) {
        FrameLayout root = new FrameLayout(service);
        root.setBackgroundColor(COLOR_BACKGROUND);
        // Focusable + clickable so the blocked app underneath receives no touch or key events.
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setClickable(true);
        root.setOnKeyListener((v, keyCode, event) -> keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN);

        LinearLayout column = new LinearLayout(service);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(28), dp(28), dp(28), dp(28));
        overlayContent = column;

        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        columnParams.gravity = Gravity.CENTER;

        TextView badge = new TextView(service);
        badge.setText(spec.badge);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        badge.setTextColor(COLOR_ACCENT);
        badge.setLetterSpacing(0.25f);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable badgeBackground = new GradientDrawable();
        badgeBackground.setColor(COLOR_SURFACE);
        badgeBackground.setCornerRadius(dp(20));
        badgeBackground.setStroke(dp(1), COLOR_ACCENT);
        badge.setBackground(badgeBackground);
        column.addView(badge);

        TextView title = new TextView(service);
        title.setText(spec.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        title.setTextColor(COLOR_TEXT);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, 0);
        column.addView(title);

        TextView message = new TextView(service);
        message.setText(spec.message);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        message.setTextColor(COLOR_MUTED);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(12), 0, dp(24));
        column.addView(message);

        TextView goHome = actionButton("Go to Home screen", false);
        goHome.setOnClickListener(v -> {
            animateOut();
            if (exitHandler != null) {
                exitHandler.onGoHome();
            } else {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                hide();
            }
        });
        column.addView(goHome, buttonParams());

        if (spec.allowEmergencyUnlock && spec.unlocksLeft > 0) {
            TextView unlock = actionButton("Emergency break (" + spec.unlocksLeft + " left today)", false);
            unlock.setOnClickListener(v -> {
                if (spec.onEmergencyUnlock != null) {
                    spec.onEmergencyUnlock.run();
                }
            });
            column.addView(unlock, buttonParams());
        }

        TextView openSelfPilot = actionButton("Open SelfPilot", true);
        openSelfPilot.setOnClickListener(v -> {
            animateOut();
            if (exitHandler != null) {
                exitHandler.onOpenSelfPilot();
            } else {
                openSelfPilot();
                hide();
            }
        });
        column.addView(openSelfPilot, buttonParams());

        root.addView(column, columnParams);
        return root;
    }

    private TextView actionButton(String label, boolean primary) {
        TextView button = new TextView(service);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(24), dp(15), dp(24), dp(15));
        button.setTextColor(primary ? 0xFFFFFFFF : COLOR_TEXT);
        button.setClickable(true);
        button.setFocusable(true);

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14));
        if (primary) {
            background.setColor(COLOR_PRIMARY);
        } else {
            background.setColor(COLOR_SURFACE);
            background.setStroke(dp(1), 0xFF322C45);
        }
        button.setBackground(background);
        return button;
    }

    private FrameLayout.LayoutParams buttonParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        return params;
    }

    private void openSelfPilot() {
        try {
            Intent intent = new Intent(service, com.example.selfpilot.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("NAVIGATE_TO", com.example.selfpilot.R.id.navigation_home);
            service.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "[OVERLAY] Failed to open SelfPilot", t);
        }
    }

    public String appLabel(String packageName) {
        try {
            PackageManager pm = service.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Throwable t) {
            return packageName;
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, service.getResources().getDisplayMetrics()));
    }
}
