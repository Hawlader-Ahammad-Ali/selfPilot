package com.example.selfpilot.account;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;

/**
 * Initialises the Firebase SDK from constants instead of a google-services.json file, so the
 * project keeps building without the Gradle google-services plugin.
 *
 * Only Authentication uses the SDK. Firestore and the Realtime Database stay on their REST
 * endpoints - they only need a valid ID token, which AuthApi hands out either way.
 */
public final class FirebaseBoot {

    private static final String TAG = "SELF_PILOT_FIREBASE";

    /** mobilesdk_app_id of the Android app registered in the Firebase project. */
    private static final String APPLICATION_ID =
            "1:567359393832:android:e0a3e0d84a9d65c830e51d";

    private static volatile boolean initialised = false;

    private FirebaseBoot() {
    }

    /** Idempotent. Returns false when the SDK could not be initialised at all. */
    public static synchronized boolean ensure(Context context) {
        if (initialised) return true;
        if (context == null) return false;

        Context app = context.getApplicationContext();
        try {
            if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setApiKey(CloudConfig.API_KEY)
                        .setApplicationId(APPLICATION_ID)
                        .setProjectId(CloudConfig.PROJECT_ID)
                        .setStorageBucket(CloudConfig.PROJECT_ID + ".firebasestorage.app")
                        .build();
                FirebaseApp.initializeApp(app, options);
            }
            initialised = true;
        } catch (Exception e) {
            Log.e(TAG, "[FIREBASE] initialisation failed", e);
            initialised = false;
        }
        return initialised;
    }

    public static FirebaseAuth auth(Context context) {
        if (!ensure(context)) return null;
        try {
            return FirebaseAuth.getInstance();
        } catch (Exception e) {
            return null;
        }
    }

    /** Signs the SDK session out; the REST session is cleared separately. */
    public static void signOutQuietly() {
        try {
            FirebaseAuth.getInstance().signOut();
        } catch (Exception ignored) {
            // Not initialised (or already signed out) - nothing to do.
        }
    }
}
