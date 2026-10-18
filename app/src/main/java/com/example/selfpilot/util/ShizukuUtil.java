package com.example.selfpilot.util;

import android.content.pm.PackageManager;
import android.util.Log;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuSystemProperties;

public class ShizukuUtil {

    private static final String TAG = "ShizukuUtil";

    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean hasShizukuPermission() {
        try {
            if (!isShizukuAvailable()) return false;
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                return false;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void requestShizukuPermission(int requestCode) {
        try {
            if (isShizukuAvailable() && !hasShizukuPermission()) {
                Shizuku.requestPermission(requestCode);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to request Shizuku permission", e);
        }
    }

    public static boolean freezeApp(String packageName) {
        if (!hasShizukuPermission()) return false;

        try {
            // Using Shizuku shell directly through standard shizuku executable wrapper
            // Note: Since Shizuku is a privileged provider, executing via its Process API or runtime exec is commonly supported if integrated.
            // On modern Android versions, `pm disable-user --user 0 <pkg>` effectively acts as a deep OEM freeze.
            // It completely suspends background processes, removes launcher icons, and prevents notifications.
            
            // Due to deprecation of Shizuku.newProcess, we wrap standard Runtime.exec with "shizuku -c" 
            // as officially advised in some Shizuku deployment wrappers when the Shizuku API doesn't expose it directly.
            Process p = Runtime.getRuntime().exec(new String[]{"shizuku", "-c", "pm disable-user --user 0 " + packageName});
            p.waitFor();
            int exitValue = p.exitValue();
            Log.i(TAG, "Shizuku freezeApp (" + packageName + ") exit value: " + exitValue);
            return exitValue == 0;
        } catch (Exception e) {
            Log.e(TAG, "Error executing Shizuku pm disable-user", e);
            return false;
        }
    }

    public static boolean unfreezeApp(String packageName) {
        if (!hasShizukuPermission()) return false;

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"shizuku", "-c", "pm enable " + packageName});
            p.waitFor();
            int exitValue = p.exitValue();
            Log.i(TAG, "Shizuku unfreezeApp (" + packageName + ") exit value: " + exitValue);
            return exitValue == 0;
        } catch (Exception e) {
            Log.e(TAG, "Error executing Shizuku pm enable", e);
            return false;
        }
    }
}
