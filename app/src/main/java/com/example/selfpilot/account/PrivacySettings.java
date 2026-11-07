package com.example.selfpilot.account;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User-facing privacy switches. Everything defaults to the safest useful behaviour:
 * cloud features are on only while signed in, and the leaderboard never exposes the email.
 *
 * These switches are local: turning sync off cannot be undone by another device, and a
 * signed-out install has no cloud path at all.
 */
public final class PrivacySettings {

    private static final String PREFS = "SelfPilotPrivacy";

    private static final String KEY_AUTO_BACKUP = "auto_backup";
    private static final String KEY_REALTIME = "realtime_sync";
    private static final String KEY_LEADERBOARD = "leaderboard_mode";
    private static final String KEY_ALLOW_METRICS = "share_metrics";

    /** Public display name, anonymous id, or hidden. */
    public static final String BOARD_PUBLIC = "public";
    public static final String BOARD_ANONYMOUS = "anonymous";
    public static final String BOARD_OFF = "off";

    private PrivacySettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean autoBackup(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_BACKUP, true);
    }

    public static void setAutoBackup(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply();
    }

    public static boolean realtimeSync(Context context) {
        return prefs(context).getBoolean(KEY_REALTIME, true);
    }

    public static void setRealtimeSync(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_REALTIME, enabled).apply();
    }

    public static String leaderboardMode(Context context) {
        return prefs(context).getString(KEY_LEADERBOARD, BOARD_PUBLIC);
    }

    public static void setLeaderboardMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_LEADERBOARD, mode).apply();
    }

    /** When false, only aggregate numbers are sent - never app names or usage detail. */
    public static boolean shareMetrics(Context context) {
        return prefs(context).getBoolean(KEY_ALLOW_METRICS, true);
    }

    public static void setShareMetrics(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ALLOW_METRICS, enabled).apply();
    }

    /** True when any cloud upload is allowed right now. */
    public static boolean cloudEnabled(Context context) {
        return AccountSession.isSignedIn(context) && autoBackup(context);
    }

    /** Name shown on the leaderboard, honouring the visibility choice. */
    public static String leaderboardName(Context context, AccountSession session) {
        String mode = leaderboardMode(context);
        if (BOARD_OFF.equals(mode)) return null;
        if (BOARD_ANONYMOUS.equals(mode)) {
            String uid = session == null ? "" : String.valueOf(session.uid);
            String suffix = uid.length() >= 4 ? uid.substring(uid.length() - 4) : uid;
            return "Pilot-" + suffix;
        }
        return session == null ? "Pilot" : session.safeName();
    }
}
