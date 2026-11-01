package com.example.selfpilot.util;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Pushes a screen root below the status bar and above the navigation bar.
 *
 * With edge-to-edge enforced from Android 15 on, every screen that does not consume insets
 * draws its header underneath the status bar (the freezer title collided with the clock).
 */
public final class SystemBarInsets {

    private SystemBarInsets() {
    }

    public static void apply(final View root) {
        if (root == null) return;

        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    baseLeft + bars.left,
                    baseTop + bars.top,
                    baseRight + bars.right,
                    baseBottom + bars.bottom);
            return windowInsets;
        });

        ViewCompat.requestApplyInsets(root);
    }
}
