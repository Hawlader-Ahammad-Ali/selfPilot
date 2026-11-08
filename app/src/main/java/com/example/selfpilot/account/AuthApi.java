package com.example.selfpilot.account;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserProfileChangeRequest;

import org.json.JSONObject;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Authentication facade.
 *
 * Sign-in, sign-up, Google, password reset and account deletion all go through the official
 * Firebase Authentication SDK, which handles provider quirks (audience checks, nonce, Play
 * services versions) far better than a hand-written REST exchange.
 *
 * Firestore and the Realtime Database still use REST; they only need the ID token produced
 * here.
 */
public class AuthApi {

    private static final String TAG = "SELF_PILOT_ACCOUNT";
    private static final long TIMEOUT_SECONDS = 25L;

    public interface Callback {
        void onDone(boolean success, String message);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Context lastContext;

    private AuthApi() {
    }

    // ------------------------------------------------------------------------------------
    // Email
    // ------------------------------------------------------------------------------------

    public static void signIn(Context context, String email, String password, Callback callback) {
        run(context, callback, () -> {
            try {
                FirebaseAuth auth = FirebaseBoot.auth(context);
                if (auth != null) {
                    AuthResult result = Tasks.await(
                            auth.signInWithEmailAndPassword(email, password),
                            TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return Ok.of(saveSession(context, result.getUser(), "password"));
                }
                note("SDK auth unavailable, using REST");
            } catch (Exception sdkError) {
                note("SDK email sign-in failed: " + rootCause(sdkError));
            }
            // Second, independent path: the Identity Toolkit REST endpoint.
            return restSignIn(context, email, password);
        });
    }

    /** Email sign-in through the REST API - used when the SDK path fails. */
    private static Result restSignIn(Context context, String email, String password) {
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);
            body.put("returnSecureToken", true);

            Http.Response response = Http.post(
                    CloudConfig.AUTH_BASE + "signInWithPassword?key=" + CloudConfig.API_KEY,
                    body.toString());
            if (!response.isOk()) {
                String message = restMessage(response.body);
                note("REST email sign-in: " + message);
                return Fail.of(message);
            }
            return Ok.of(sessionFromRest(context, response.body, "password"));
        } catch (Exception e) {
            note("REST email sign-in threw: " + rootCause(e));
            return Fail.of("Network unavailable - check your connection");
        }
    }

    public static void signUp(Context context, String email, String password,
                              String displayName, Callback callback) {
        run(context, callback, () -> {
            FirebaseAuth auth = FirebaseBoot.auth(context);
            if (auth == null) return Fail.of("Authentication is unavailable on this device");

            AuthResult result = Tasks.await(
                    auth.createUserWithEmailAndPassword(email, password),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS);

            FirebaseUser user = result.getUser();
            if (user != null && displayName != null && !displayName.trim().isEmpty()) {
                try {
                    Tasks.await(user.updateProfile(new UserProfileChangeRequest.Builder()
                            .setDisplayName(displayName)
                            .build()), TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    Tasks.await(user.reload(), 10, TimeUnit.SECONDS);
                    user = auth.getCurrentUser();
                } catch (Exception ignored) {
                }
            }
            return Ok.of(saveSession(context, user, "password"));
        });
    }

    public static void sendPasswordReset(Context context, String email, Callback callback) {
        run(context, callback, () -> {
            FirebaseAuth auth = FirebaseBoot.auth(context);
            if (auth == null) return Fail.of("Authentication is unavailable on this device");
            Tasks.await(auth.sendPasswordResetEmail(email), TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Ok.plain("Password reset email sent");
        });
    }

    // ------------------------------------------------------------------------------------
    // Google
    // ------------------------------------------------------------------------------------

    public static void signInWithGoogle(Context context, String googleIdToken, Callback callback) {
        run(context, callback, () -> {
            if (googleIdToken == null || googleIdToken.isEmpty()) {
                return Fail.of("Google did not return an ID token");
            }

            try {
                FirebaseAuth auth = FirebaseBoot.auth(context);
                if (auth != null) {
                    AuthCredential credential = GoogleAuthProvider.getCredential(googleIdToken, null);
                    AuthResult result = Tasks.await(auth.signInWithCredential(credential),
                            TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return Ok.of(saveSession(context, result.getUser(), "google.com"));
                }
                note("SDK auth unavailable, using REST");
            } catch (Exception sdkError) {
                note("SDK Google sign-in failed: " + rootCause(sdkError));
            }
            return restGoogle(context, googleIdToken);
        });
    }

    /** Google sign-in through the REST API - the second, independent path. */
    private static Result restGoogle(Context context, String googleIdToken) {
        try {
            JSONObject body = new JSONObject();
            body.put("postBody", "id_token=" + googleIdToken + "&providerId=google.com");
            body.put("requestUri", "http://localhost");
            body.put("returnSecureToken", true);
            body.put("returnIdpCredential", true);

            Http.Response response = Http.post(
                    CloudConfig.AUTH_BASE + "signInWithIdp?key=" + CloudConfig.API_KEY,
                    body.toString());
            if (!response.isOk()) {
                String message = restMessage(response.body);
                note("REST Google sign-in: " + message);
                return Fail.of(message);
            }
            return Ok.of(sessionFromRest(context, response.body, "google.com"));
        } catch (Exception e) {
            note("REST Google sign-in threw: " + rootCause(e));
            return Fail.of("Network unavailable - check your connection");
        }
    }

    // ------------------------------------------------------------------------------------
    // Account deletion
    // ------------------------------------------------------------------------------------

    /** Blocking variant used by the deletion flow, which already owns a worker thread. */
    static boolean deleteAccountBlocking(Context context) {
        try {
            FirebaseAuth auth = FirebaseBoot.auth(context);
            FirebaseUser user = auth == null ? null : auth.getCurrentUser();
            if (user == null) return false;
            Tasks.await(user.delete(), TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "[AUTH] delete failed", e);
            return false;
        }
    }

    public static void deleteAccount(Context context, Callback callback) {
        run(context, callback, () -> deleteAccountBlocking(context)
                ? Ok.plain("Account deleted")
                : Fail.of("Account deletion failed - sign in again and retry"));
    }

    // ------------------------------------------------------------------------------------
    // Tokens for the REST data layer
    // ------------------------------------------------------------------------------------

    /**
     * Returns a valid Firebase ID token. Blocking - callers are already on a worker thread.
     * Uses the SDK session when available and falls back to the stored REST refresh token so
     * sessions created by older builds keep working.
     */
    static String validToken(Context context) {
        AccountSession session = AccountSession.load(context);
        if (!session.isSignedIn()) return null;

        // 1. SDK session
        try {
            FirebaseAuth auth = FirebaseBoot.auth(context);
            FirebaseUser user = auth == null ? null : auth.getCurrentUser();
            if (user != null) {
                GetTokenResult result = Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS);
                String token = result == null ? null : result.getToken();
                if (token != null && !token.isEmpty()) {
                    session.idToken = token;
                    session.expiresAt = System.currentTimeMillis() + 50 * 60 * 1000L;
                    session.save(context);
                    return token;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "[AUTH] SDK token unavailable", e);
        }

        // 2. Legacy REST session
        long now = System.currentTimeMillis();
        if (session.idToken != null && !session.idToken.isEmpty()
                && session.expiresAt - now > 5 * 60 * 1000L) {
            return session.idToken;
        }
        if (session.refreshToken == null || session.refreshToken.isEmpty()) {
            return session.idToken;
        }

        try {
            JSONObject body = new JSONObject();
            body.put("grant_type", "refresh_token");
            body.put("refresh_token", session.refreshToken);

            Http.Response response = Http.post(CloudConfig.TOKEN_URL, body.toString());
            if (!response.isOk()) return session.idToken;

            JSONObject json = new JSONObject(response.body);
            session.idToken = json.optString("id_token", session.idToken);
            session.refreshToken = json.optString("refresh_token", session.refreshToken);
            long expiresIn = 3600L;
            try {
                expiresIn = Long.parseLong(json.optString("expires_in", "3600"));
            } catch (Exception ignored) {
            }
            session.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
            session.save(context);
            return session.idToken;
        } catch (Exception e) {
            return session.idToken;
        }
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    /** Builds and persists a session from an Identity Toolkit REST response. */
    private static AccountSession sessionFromRest(Context context, String json, String provider)
            throws Exception {
        JSONObject o = new JSONObject(json);
        AccountSession session = AccountSession.load(context);
        session.uid = o.optString("localId", null);
        session.email = o.optString("email", null);
        session.idToken = o.optString("idToken", null);
        session.refreshToken = o.optString("refreshToken", null);

        String name = o.optString("displayName", null);
        if (name == null || name.trim().isEmpty()) {
            String email = session.email == null ? "" : session.email;
            name = email.contains("@") ? email.substring(0, email.indexOf('@')) : null;
        }
        session.displayName = name;
        session.provider = provider;

        long expiresIn = 3600L;
        try {
            expiresIn = Long.parseLong(o.optString("expiresIn", "3600"));
        } catch (Exception ignored) {
        }
        session.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;

        if (session.uid == null || session.idToken == null) {
            throw new Exception("Incomplete session response");
        }
        session.save(context);
        return session;
    }

    private static String restMessage(String body) {
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if (error != null) return describeCode(error.optString("message", "Sign-in failed"));
        } catch (Exception ignored) {
        }
        return "Sign-in failed";
    }

    private static String describeCode(String code) {
        if (code == null) return "Sign-in failed";
        if (code.contains("INVALID_LOGIN_CREDENTIALS") || code.contains("INVALID_PASSWORD")
                || code.contains("EMAIL_NOT_FOUND")) {
            return "Wrong email or password";
        }
        if (code.contains("EMAIL_EXISTS")) return "This email is already registered";
        if (code.contains("WEAK_PASSWORD")) return "Password must be at least 6 characters";
        if (code.contains("INVALID_EMAIL")) return "Enter a valid email address";
        if (code.contains("OPERATION_NOT_ALLOWED")) return "This sign-in method is disabled for the project";
        if (code.contains("TOO_MANY_ATTEMPTS")) return "Too many attempts - try again later";
        if (code.contains("INVALID_IDP_RESPONSE")) return "Google rejected this install - signature not registered";
        return "Sign-in failed (" + code + ")";
    }

    private static String rootCause(Throwable t) {
        if (t == null) return "unknown";
        Throwable cause = t;
        int guard = 0;
        while (cause.getCause() != null && guard++ < 5) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // ------------------------------------------------------------------------------------
    // Diagnostics - surfaced on the account screen so a failure is never silent
    // ------------------------------------------------------------------------------------

    private static final String DIAG_PREFS = "SelfPilotAuthDiag";
    private static final String KEY_LAST_ERROR = "last_error";

    private static void note(String message) {
        try {
            Context ctx = lastContext;
            if (ctx == null) return;
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_LAST_ERROR, new java.text.SimpleDateFormat(
                            "HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                            + " " + message)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** Last recorded auth problem for this install, or null when nothing failed. */
    public static String lastError(Context context) {
        if (context == null) return null;
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_ERROR, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static AccountSession saveSession(Context context, FirebaseUser user,
                                              String provider) {
        AccountSession session = AccountSession.load(context);
        if (user != null) {
            session.uid = user.getUid();
            session.email = user.getEmail();
            session.displayName = user.getDisplayName();
            if (session.displayName == null || session.displayName.trim().isEmpty()) {
                String email = session.email == null ? "" : session.email;
                session.displayName = email.contains("@")
                        ? email.substring(0, email.indexOf('@')) : null;
            }
        }
        session.provider = provider;
        session.expiresAt = System.currentTimeMillis() + 50 * 60 * 1000L;

        // Keep a fresh token so the REST layer can start working immediately.
        try {
            if (user != null) {
                GetTokenResult result = Tasks.await(user.getIdToken(false), 10, TimeUnit.SECONDS);
                if (result != null) session.idToken = result.getToken();
            }
        } catch (Exception ignored) {
        }

        session.save(context);
        return session;
    }

    private static String describeAuthError(Exception e) {
        if (e instanceof ExecutionException && e.getCause() instanceof FirebaseAuthException) {
            FirebaseAuthException auth = (FirebaseAuthException) e.getCause();
            String code = auth.getErrorCode();
            if (code == null) return "Sign-in failed";
            if (code.contains("INVALID_CREDENTIAL") || code.contains("INVALID_LOGIN_CREDENTIALS")
                    || code.contains("WRONG_PASSWORD") || code.contains("USER_NOT_FOUND")) {
                return "Wrong email or password";
            }
            if (code.contains("EMAIL_ALREADY_IN_USE")) return "This email is already registered";
            if (code.contains("WEAK_PASSWORD")) return "Password must be at least 6 characters";
            if (code.contains("INVALID_EMAIL")) return "Enter a valid email address";
            if (code.contains("OPERATION_NOT_ALLOWED")) {
                return "This sign-in method is disabled for the project";
            }
            if (code.contains("TOO_MANY_REQUESTS")) return "Too many attempts - try again later";
            if (code.contains("NETWORK")) return "Network unavailable - check your connection";
            if (code.contains("INVALID_IDP_RESPONSE") || code.contains("INVALID_CREDENTIAL")) {
                return "Google rejected this install - signature not registered";
            }
            if (code.contains("USER_DISABLED")) return "This account has been disabled";
            return "Sign-in failed (" + code.replace("ERROR_", "") + ")";
        }
        if (e instanceof java.util.concurrent.TimeoutException) {
            return "Timed out - check your connection";
        }
        return "Network unavailable - try again";
    }

    // ------------------------------------------------------------------------------------
    // Async plumbing
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
        final Context app = context == null ? null : context.getApplicationContext();
        if (app != null) lastContext = app;
        if (app == null) {
            MAIN.post(() -> {
                if (callback != null) callback.onDone(false, "Authentication unavailable");
            });
            return;
        }
        IO.execute(() -> {
            Result result;
            try {
                result = task.call();
            } catch (Exception e) {
                Log.w(TAG, "[AUTH] request failed", e);
                result = Fail.of(describeAuthError(e));
            }
            final Result finalResult = result;
            MAIN.post(() -> {
                if (callback != null) callback.onDone(finalResult.success, finalResult.message);
            });
        });
    }
}
