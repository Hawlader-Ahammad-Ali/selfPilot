package com.example.selfpilot.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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
 * Why an overlay instead of an activity:
 *  - Starting an activity from the background can be refused by the OS
 *    (background activity launch restrictions), which silently let frozen apps through.
 *  - An accessibility overlay needs no extra permission, is added synchronously (no launch
 *    latency, no race with the frozen activity finishing its resume) and always sits above
 *    every application window, so a frozen app can never be seen or touched.
 *
 * The overlay is focusable on purpose: it swallows touches and the back key. Home still
 * works, so the user is never trapped.
 */
public class FreezerOverlayController {

    private static final String TAG = "SELF_PILOT_FREEZER";

    private static final int COLOR_BACKGROUND = 0xFF07070C;
    private static final int COLOR_SURFACE = 0xFF15131F;
    private static final int COLOR_ACCENT = 0xFFB39DDB;
    private static final int COLOR_TEXT = 0xFFF3F0FA;
    private static final int COLOR_MUTED = 0xFF9A94AD;
    private static final int COLOR_BUTTON = 0xFF6C4FD8;

    private final AccessibilityService service;
    private final WindowManager windowManager;

    private View overlayView;
    private String targetPackage;

    public FreezerOverlayController(AccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return overlayView != null;
    }

    public String getTargetPackage() {
        return targetPackage;
    }

    /**
     * Shows (or keeps) the block surface for the given package.
     * Safe to call repeatedly: no-op while the same package is already covered.
     */
    public void show(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        if (windowManager == null) return;
        if (overlayView != null && packageName.equals(targetPackage)) return;
        if (overlayView != null) hide();

        try {
            View view = buildView(packageName);
            windowManager.addView(view, buildParams());
            overlayView = view;
            targetPackage = packageName;
            Log.i(TAG, "[OVERLAY] Block surface shown for " + packageName);
        } catch (Throwable t) {
            Log.e(TAG, "[OVERLAY] Failed to show overlay for " + packageName, t);
        }
    }

    public void hide() {
        if (overlayView == null) return;
        try {
            windowManager.removeView(overlayView);
        } catch (Throwable ignored) {
        }
        overlayView = null;
        targetPackage = null;
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
        params.setTitle("SelfPilotFreezerBlock");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
    }

    private View buildView(String packageName) {
        FrameLayout root = new FrameLayout(service);
        root.setBackgroundColor(COLOR_BACKGROUND);
        // Focusable + clickable so the frozen app underneath receives no touch or key events.
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setClickable(true);
        root.setOnKeyListener((v, keyCode, event) -> keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN);

        LinearLayout column = new LinearLayout(service);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(28), dp(28), dp(28), dp(28));

        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        columnParams.gravity = Gravity.CENTER;

        TextView badge = new TextView(service);
        badge.setText("FROZEN");
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
        title.setText(appLabel(packageName));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        title.setTextColor(COLOR_TEXT);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, 0);
        column.addView(title);

        TextView message = new TextView(service);
        message.setText("This app is frozen by SelfPilot.\nIt stays blocked until you unfreeze it - "
                + "launcher, recents and notifications included.");
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        message.setTextColor(COLOR_MUTED);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(12), 0, dp(28));
        column.addView(message);

        TextView goHome = actionButton("Go to Home screen", false);
        goHome.setOnClickListener(v -> {
            hide();
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        });
        column.addView(goHome, buttonParams());

        TextView openSelfPilot = actionButton("Open SelfPilot to unfreeze", true);
        openSelfPilot.setOnClickListener(v -> {
            hide();
            openSelfPilot();
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
            background.setColor(COLOR_BUTTON);
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

    private String appLabel(String packageName) {
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
