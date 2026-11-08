package com.example.selfpilot.account;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Optional Google sign-in.
 *
 * Two paths are used, because device coverage differs a lot:
 *   1. Credential Manager (modern, no separate Google activity)
 *   2. Play services GoogleSignIn intent (works on older Play services builds)
 *
 * If the first fails for any reason other than the user cancelling, the second is tried
 * automatically. The resulting Google ID token is exchanged for a Firebase session by
 * {@link AuthApi#signInWithGoogle}. No Firebase SDK is involved.
 */
public class GoogleSignInClient {

    private static final String TAG = "SELF_PILOT_GOOGLE";

    /** Request code for the Play services fallback. */
    public static final int RC_SIGN_IN = 4821;

    public interface Callback {
        void onDone(boolean success, String message);
    }

    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Only one Google flow can be in flight at a time. */
    private static Callback pendingCallback;
    private static Context pendingContext;

    private GoogleSignInClient() {
    }

    // ------------------------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------------------------

    public static void signIn(Activity activity, Callback callback) {
        if (activity == null) {
            finish(callback, false, "Google sign-in needs an active screen");
            return;
        }

        pendingCallback = callback;
        pendingContext = activity.getApplicationContext();

        GetGoogleIdOption option;
        GetCredentialRequest request;
        try {
            option = new GetGoogleIdOption.Builder()
                    .setServerClientId(CloudConfig.GOOGLE_WEB_CLIENT_ID)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .setNonce(UUID.randomUUID().toString())
                    .build();
            request = new GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build();
        } catch (Exception e) {
            Log.w(TAG, "[GOOGLE] could not build request, using fallback", e);
            startPlayServicesSignIn(activity, callback);
            return;
        }

        try {
            CredentialManager manager = CredentialManager.create(activity);
            CancellationSignal signal = new CancellationSignal();

            manager.getCredentialAsync(activity, request, signal, EXECUTOR,
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse result) {
                            handleCredential(activity, result.getCredential(), callback);
                        }

                        @Override
                        public void onError(@NonNull GetCredentialException e) {
                            Log.w(TAG, "[GOOGLE] Credential Manager failed: msg=" + e.getMessage(), e);
                            if (e instanceof GetCredentialCancellationException) {
                                finish(callback, false, "Google sign-in cancelled");
                                return;
                            }
                            // Anything else: the device may simply not support this path.
                            MAIN.post(() -> startPlayServicesSignIn(activity, callback));
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "[GOOGLE] Credential Manager unavailable", e);
            startPlayServicesSignIn(activity, callback);
        }
    }

    // ------------------------------------------------------------------------------------
    // Path 2: Play services
    // ------------------------------------------------------------------------------------

    private static void startPlayServicesSignIn(Activity activity, Callback callback) {
        if (activity == null || activity.isFinishing()) {
            finish(callback, false, "Google sign-in failed - use email instead");
            return;
        }
        try {
            GoogleSignInOptions options = new GoogleSignInOptions.Builder(
                    GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(CloudConfig.GOOGLE_WEB_CLIENT_ID)
                    .requestEmail()
                    .build();

            com.google.android.gms.auth.api.signin.GoogleSignInClient client =
                    GoogleSignIn.getClient(activity, options);
            Intent intent = client.getSignInIntent();
            activity.startActivityForResult(intent, RC_SIGN_IN);
        } catch (Exception e) {
            Log.w(TAG, "[GOOGLE] Play services sign-in unavailable", e);
            finish(callback, false, "Google sign-in is not available on this device");
        }
    }

    /** Must be called from the hosting Activity's onActivityResult. */
    public static void handleSignInResult(Intent data) {
        Callback callback = pendingCallback;
        pendingCallback = null;

        try {
            GoogleSignInAccount account = GoogleSignIn
                    .getSignedInAccountFromIntent(data)
                    .getResult(ApiException.class);

            if (account == null || account.getIdToken() == null) {
                finish(callback, false, "Google did not return an ID token");
                return;
            }
            exchange(pendingContext, account.getIdToken(), callback);
        } catch (ApiException e) {
            Log.w(TAG, "[GOOGLE] Play services sign-in failed, code=" + e.getStatusCode(), e);
            finish(callback, false, describeApiException(e.getStatusCode()));
        } catch (Exception e) {
            Log.w(TAG, "[GOOGLE] Play services sign-in failed", e);
            finish(callback, false, "Google sign-in failed");
        }
    }

    private static String describeApiException(int code) {
        switch (code) {
            case 12501:
                return "Google sign-in cancelled";
            case 12500:
                return "Google sign-in failed (12500) - check Play services";
            case 12502:
                return "Google sign-in already in progress";
            case 10:
                return "Developer error (10) - SHA-1 / client ID mismatch";
            case 7:
                return "Network error - check your connection";
            case 8:
                return "Internal error - try again";
            default:
                return "Google sign-in failed (" + code + ")";
        }
    }

    // ------------------------------------------------------------------------------------
    // Path 1 result handling
    // ------------------------------------------------------------------------------------

    private static void handleCredential(Activity activity, Credential credential,
                                         Callback callback) {
        try {
            if (credential instanceof CustomCredential
                    && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    .equals(credential.getType())) {
                GoogleIdTokenCredential googleCredential =
                        GoogleIdTokenCredential.createFrom(credential.getData());
                String idToken = googleCredential.getIdToken();
                if (idToken == null || idToken.isEmpty()) {
                    finish(callback, false, "Google did not return an ID token");
                    return;
                }
                exchange(activity, idToken, callback);
            } else {
                Log.w(TAG, "[GOOGLE] unexpected credential type: " + credential.getType());
                MAIN.post(() -> startPlayServicesSignIn(activity, callback));
            }
        } catch (Exception e) {
            Log.w(TAG, "[GOOGLE] could not read credential, using fallback", e);
            MAIN.post(() -> startPlayServicesSignIn(activity, callback));
        }
    }

    private static void exchange(Context context, String idToken, Callback callback) {
        AuthApi.signInWithGoogle(context, idToken, (success, message) ->
                finish(callback, success, message));
    }

    // ------------------------------------------------------------------------------------
    // Sign out
    // ------------------------------------------------------------------------------------

    public static void signOut(Context context) {
        try {
            CredentialManager manager = CredentialManager.create(context);
            manager.clearCredentialStateAsync(new ClearCredentialStateRequest(), null,
                    EXECUTOR, new CredentialManagerCallback<Void, ClearCredentialException>() {
                        @Override
                        public void onResult(Void result) {
                        }

                        @Override
                        public void onError(ClearCredentialException e) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private static void finish(Callback callback, boolean success, String message) {
        MAIN.post(() -> {
            if (callback != null) callback.onDone(success, message);
        });
    }
}
