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

    private AuthApi() {
    }

    // ------------------------------------------------------------------------------------
    // Email
    // ------------------------------------------------------------------------------------

    public static void signIn(Context context, String email, String password, Callback callback) {
        run(context, callback, () -> {
            FirebaseAuth auth = FirebaseBoot.auth(context);
            if (auth == null) return Fail.of("Authentication is unavailable on this device");

            AuthResult result = Tasks.await(
                    auth.signInWithEmailAndPassword(email, password),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Ok.of(saveSession(context, result.getUser(), "password"));
        });
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
            FirebaseAuth auth = FirebaseBoot.auth(context);
            if (auth == null) return Fail.of("Authentication is unavailable on this device");
            if (googleIdToken == null || googleIdToken.isEmpty()) {
                return Fail.of("Google did not return an ID token");
            }

            AuthCredential credential = GoogleAuthProvider.getCredential(googleIdToken, null);
            AuthResult result = Tasks.await(auth.signInWithCredential(credential),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS);

            FirebaseUser user = result.getUser();
            return Ok.of(saveSession(context, user, "google.com"));
        });
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
