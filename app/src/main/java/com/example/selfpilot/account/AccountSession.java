package com.example.selfpilot.account;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The signed-in session. Everything here is a network credential / profile value; the user's
 * actual productivity data always lives in the Room database and is never removed by signing
 * out.
 */
public class AccountSession {

    private static final String PREFS = "SelfPilotAccount";
    private static final String KEY_UID = "uid";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_NAME = "display_name";
    private static final String KEY_TOKEN = "id_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_PROVIDER = "provider";

    public String uid;
    public String email;
    public String displayName;
    public String idToken;
    public String refreshToken;
    /** "password" or "google.com". */
    public String provider;
    /** Epoch millis at which {@link #idToken} stops being valid. */
    public long expiresAt;

    public boolean isSignedIn() {
        return uid != null && !uid.isEmpty() && refreshToken != null && !refreshToken.isEmpty();
    }

    public String safeName() {
        if (displayName != null && !displayName.trim().isEmpty()) return displayName.trim();
        if (email != null && email.contains("@")) return email.substring(0, email.indexOf('@'));
        return "You";
    }

    // ------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------

    public static AccountSession load(Context context) {
        AccountSession session = new AccountSession();
        if (context == null) return session;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        session.uid = prefs.getString(KEY_UID, null);
        session.email = prefs.getString(KEY_EMAIL, null);
        session.displayName = prefs.getString(KEY_NAME, null);
        session.idToken = prefs.getString(KEY_TOKEN, null);
        session.refreshToken = prefs.getString(KEY_REFRESH, null);
        session.expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L);
        session.provider = prefs.getString(KEY_PROVIDER, null);
        return session;
    }

    public void save(Context context) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_UID, uid)
                .putString(KEY_EMAIL, email)
                .putString(KEY_NAME, displayName)
                .putString(KEY_TOKEN, idToken)
                .putString(KEY_REFRESH, refreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .putString(KEY_PROVIDER, provider)
                .apply();
    }

    /**
     * Signing out forgets the credentials only. Local Room data stays exactly as it was, which
     * is why the app keeps working offline afterwards as a guest.
     */
    public static void clear(Context context) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_UID)
                .remove(KEY_EMAIL)
                .remove(KEY_NAME)
                .remove(KEY_TOKEN)
                .remove(KEY_REFRESH)
                .remove(KEY_EXPIRES_AT)
                .remove(KEY_PROVIDER)
                .apply();
    }

    public static boolean isSignedIn(Context context) {
        return load(context).isSignedIn();
    }
}
