package com.example.selfpilot.account;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Firebase Authentication over REST (email + password).
 *
 * Nothing here is allowed to throw into the UI: every failure is translated into a short,
 * human readable message and handed back on the main thread.
 */
public class AuthApi {

    private static final String TAG = "SELF_PILOT_ACCOUNT";

    public interface Callback {
        void onDone(boolean success, String message);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AuthApi() {
    }

    // ------------------------------------------------------------------------------------
    // Public calls
    // ------------------------------------------------------------------------------------

    public static void signIn(Context context, String email, String password, Callback callback) {
        run(context, callback, () -> {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);
            body.put("returnSecureToken", true);

            Http.Response response = Http.post(CloudConfig.AUTH_BASE
                    + "signInWithPassword?key=" + CloudConfig.API_KEY, body.toString());
            if (!response.isOk()) {
                return Fail.of(describe(response.body));
            }
            AccountSession session = readSession(new JSONObject(response.body));
            session.save(context);
            return Ok.of(session);
        });
    }

    public static void signUp(Context context, String email, String password,
                              String displayName, Callback callback) {
        run(context, callback, () -> {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);
            body.put("returnSecureToken", true);

            Http.Response response = Http.post(CloudConfig.AUTH_BASE
                    + "signUp?key=" + CloudConfig.API_KEY, body.toString());
            if (!response.isOk()) {
                return Fail.of(describe(response.body));
            }

            JSONObject json = new JSONObject(response.body);
            AccountSession session = readSession(json);
            session.displayName = displayName;

            // Store the display name on the account too, so the leaderboard and a fresh
            // install both show the same name.
            try {
                JSONObject profile = new JSONObject();
                profile.put("displayName", displayName);
                Http.postBearer(CloudConfig.AUTH_BASE + "update?key=" + CloudConfig.API_KEY,
                        profile.toString(), session.idToken);
            } catch (Exception ignored) {
            }

            session.save(context);
            return Ok.of(session);
        });
    }

    /**
     * Exchanges a Google ID token (from Credential Manager) for a Firebase session, so Google
     * and email users share exactly the same account model.
     */
    public static void signInWithGoogle(Context context, String googleIdToken, Callback callback) {
        run(context, callback, () -> {
            JSONObject body = new JSONObject();
            body.put("postBody", "id_token=" + googleIdToken + "&providerId=google.com");
            body.put("requestUri", "http://localhost");
            body.put("returnSecureToken", true);

            Http.Response response = Http.post(CloudConfig.AUTH_BASE
                    + "signInWithIdp?key=" + CloudConfig.API_KEY, body.toString());
            if (!response.isOk()) {
                return Fail.of(describe(response.body));
            }

            JSONObject json = new JSONObject(response.body);
            AccountSession session = readSession(json);
            if (session.displayName == null) session.displayName = json.optString("displayName", null);
            if (session.email == null) session.email = json.optString("email", null);
            session.provider = "google.com";
            session.save(context);
            return Ok.of(session);
        });
    }

    /** Blocking variant used by the deletion flow, which already owns a worker thread. */
    static boolean deleteAccountBlocking(Context context) {
        try {
            AccountSession session = AccountSession.load(context);
            if (!session.isSignedIn()) return false;
            String token = validToken(context);
            if (token == null) return false;

            JSONObject body = new JSONObject();
            body.put("idToken", token);
            Http.Response response = Http.post(CloudConfig.AUTH_BASE
                    + "delete?key=" + CloudConfig.API_KEY, body.toString());
            return response.isOk();
        } catch (Exception e) {
            return false;
        }
    }

    /** Permanently deletes the Firebase Auth user. Cloud documents are removed separately. */
    public static void deleteAccount(Context context, Callback callback) {
        run(context, callback, () -> {
            AccountSession session = AccountSession.load(context);
            if (!session.isSignedIn()) return Fail.of("Not signed in");

            String token = validToken(context);
            if (token == null) return Fail.of("Cloud unreachable - try again");

            JSONObject body = new JSONObject();
            body.put("idToken", token);
            Http.Response response = Http.post(CloudConfig.AUTH_BASE
                    + "delete?key=" + CloudConfig.API_KEY, body.toString());
            return response.isOk()
                    ? Ok.plain("Account deleted")
                    : Fail.of(describe(response.body));
        });
    }

    public static void sendPasswordReset(Context context, String email, Callback callback) {
        run(context, callback, () -> {
            JSONObject body = new JSONObject();
            body.put("requestType", "PASSWORD_RESET");
            body.put("email", email);
            Http.Response response = Http.post(CloudConfig.AUTH_BASE
                    + "sendOobCode?key=" + CloudConfig.API_KEY, body.toString());
            return response.isOk()
                    ? Ok.plain("Password reset email sent")
                    : Fail.of(describe(response.body));
        });
    }

    // ------------------------------------------------------------------------------------
    // Token handling
    // ------------------------------------------------------------------------------------

    /**
     * Returns a valid ID token, refreshing it when it is close to expiring.
     * Blocking - callers must already be on a worker thread.
     */
    static String validToken(Context context) throws Exception {
        AccountSession session = AccountSession.load(context);
        if (!session.isSignedIn()) return null;

        long now = System.currentTimeMillis();
        if (session.idToken != null && !session.idToken.isEmpty()
                && session.expiresAt - now > 5 * 60 * 1000L) {
            return session.idToken;
        }

        JSONObject body = new JSONObject();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", session.refreshToken);

        Http.Response response = Http.post(CloudConfig.TOKEN_URL, body.toString());
        if (!response.isOk()) {
            Log.w(TAG, "[AUTH] token refresh failed: " + response.code);
            // Keep the session but report the failure: the app stays fully usable offline.
            return session.idToken;
        }

        JSONObject json = new JSONObject(response.body);
        session.idToken = json.optString("id_token", session.idToken);
        session.refreshToken = json.optString("refresh_token", session.refreshToken);
        long expiresIn = 0L;
        try {
            expiresIn = Long.parseLong(json.optString("expires_in", "3600"));
        } catch (Exception ignored) {
        }
        session.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        session.save(context);
        return session.idToken;
    }

    // ------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------

    private static AccountSession readSession(JSONObject json) {
        AccountSession session = new AccountSession();
        session.uid = json.optString("localId", null);
        session.email = json.optString("email", null);
        session.displayName = json.optString("displayName", null);
        session.idToken = json.optString("idToken", null);
        session.refreshToken = json.optString("refreshToken", null);
        long expiresIn = 3600L;
        try {
            expiresIn = Long.parseLong(json.optString("expiresIn", "3600"));
        } catch (Exception ignored) {
        }
        session.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        session.provider = "password";
        if (session.displayName == null || session.displayName.isEmpty()) {
            session.displayName = null;
        }
        return session;
    }

    private static String describe(String body) {
        String code = "";
        try {
            JSONObject json = new JSONObject(body == null ? "{}" : body);
            code = json.optJSONObject("error") != null
                    ? json.getJSONObject("error").optString("message", "")
                    : json.optString("error", "");
        } catch (Exception ignored) {
        }
        if (code.contains("INVALID_LOGIN_CREDENTIALS") || code.contains("EMAIL_NOT_FOUND")
                || code.contains("INVALID_PASSWORD")) {
            return "Wrong email or password";
        }
        if (code.contains("EMAIL_EXISTS")) return "This email is already registered";
        if (code.contains("WEAK_PASSWORD")) return "Password must be at least 6 characters";
        if (code.contains("INVALID_EMAIL")) return "Enter a valid email address";
        if (code.contains("MISSING_PASSWORD")) return "Enter a password";
        if (code.contains("TOO_MANY_ATTEMPTS")) return "Too many attempts - try again later";
        if (code.contains("OPERATION_NOT_ALLOWED")) {
            return "Email sign-in is disabled for this project";
        }
        if (code.contains("CONFIGURATION_NOT_FOUND")) return "Auth is not configured in the cloud";
        if (code.contains("INVALID_IDP_RESPONSE")) {
            return "Google rejected this install - the app signature is not registered";
        }
        if (code.contains("FEDERATED_USER_ID_ALREADY_LINKED")) {
            return "This Google account is already linked";
        }
        if (code.contains("API_KEY_INVALID") || code.contains("API key not valid")) {
            return "Cloud API key rejected - contact support";
        }
        if (code.isEmpty()) return "Network unavailable - try again";
        return "Sign-in failed: " + code;
    }

    // ------------------------------------------------------------------------------------
    // Simple async plumbing
    // ------------------------------------------------------------------------------------

    private interface Task {
        Result call() throws Exception;
    }

    private static final class Result {
        final boolean success;
        final String message;
        final AccountSession session;

        private Result(boolean success, String message, AccountSession session) {
            this.success = success;
            this.message = message;
            this.session = session;
        }
    }

    private static final class Ok {
        static Result of(AccountSession session) {
            return new Result(true, "Signed in", session);
        }

        static Result plain(String message) {
            return new Result(true, message, null);
        }
    }

    private static final class Fail {
        static Result of(String message) {
            return new Result(false, message, null);
        }
    }

    private static void run(Context context, Callback callback, Task task) {
        Context app = context == null ? null : context.getApplicationContext();
        IO.execute(() -> {
            Result result;
            try {
                result = task.call();
            } catch (Exception e) {
                Log.w(TAG, "[AUTH] request failed", e);
                result = Fail.of("Network unavailable - try again");
            }
            final Result finalResult = result;
            MAIN.post(() -> {
                if (callback != null) callback.onDone(finalResult.success, finalResult.message);
            });
        });
    }
}
