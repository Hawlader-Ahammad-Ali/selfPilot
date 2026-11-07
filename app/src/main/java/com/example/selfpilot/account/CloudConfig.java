package com.example.selfpilot.account;

/**
 * Firebase project used for the optional cloud layer.
 *
 * The public web API key is not a secret: it only identifies the Firebase project, access is
 * still controlled by Firestore security rules and Firebase Authentication.
 *
 * The app talks to Firebase over plain REST instead of pulling in the Firebase SDK. That keeps
 * the offline-first promise intact: every call is a normal background HTTP request that can
 * fail without touching the local Room database, the freezer, exam mode or any other feature.
 */
public final class CloudConfig {

    public static final String PROJECT_ID = "selfpilot-2910b";
    public static final String API_KEY = "AIzaSyAvXrkGHum5OXx5hiktSL2zcPvgsTUMMEA";

    public static final String AUTH_BASE =
            "https://identitytoolkit.googleapis.com/v1/accounts:";
    public static final String TOKEN_URL =
            "https://securetoken.googleapis.com/v1/token?key=" + API_KEY;
    public static final String FIRESTORE_BASE =
            "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID
                    + "/databases/(default)/documents";

    /** Realtime Database instance, used for live session state across devices. */
    public static final String RTDB_BASE =
            "https://" + PROJECT_ID + "-default-rtdb.firebaseio.com";

    /** OAuth web client of the Firebase project - used as serverClientId for Google Sign-In. */
    public static final String GOOGLE_WEB_CLIENT_ID =
            "567359393832-bc2nfk2dq3hhvbml2o53jrcu9sqocnrk.apps.googleusercontent.com";

    private CloudConfig() {
    }
}
