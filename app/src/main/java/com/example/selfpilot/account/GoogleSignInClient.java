package com.example.selfpilot.account;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import android.util.Log;

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

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Optional Google sign-in through Credential Manager (Google Play services).
 *
 * Returns a Google ID token, which is then exchanged for a Firebase session by
 * {@link AuthApi#signInWithGoogle}. No Firebase SDK is required for the picker, which keeps
 * the dependency footprint small.
 */
public class GoogleSignInClient {

    private static final String TAG = "SELF_PILOT_GOOGLE";

    public interface Callback {
        void onDone(boolean success, String message);
    }

    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();

    private GoogleSignInClient() {
    }

    public static void signIn(Activity activity, Callback callback) {
        if (activity == null) {
            finish(callback, false, "Google sign-in needs an active screen");
            return;
        }

        GetGoogleIdOption option;
        GetCredentialRequest request;
        try {
            option = new GetGoogleIdOption.Builder()
                    .setServerClientId(CloudConfig.GOOGLE_WEB_CLIENT_ID)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build();
            request = new GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build();
        } catch (Exception e) {
            Log.w(TAG, "[GOOGLE] could not build request", e);
            finish(callback, false, "Google sign-in is not available on this device");
            return;
        }

        CredentialManager manager = CredentialManager.create(activity);
        CancellationSignal signal = new CancellationSignal();

        manager.getCredentialAsync(activity, request, signal, EXECUTOR,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleCredential(activity, result.getCredential(), callback);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        Log.w(TAG, "[GOOGLE] getCredential failed", e);
                        String type = e.getType();
                        if (e instanceof GetCredentialCancellationException
                                || type.contains("CANCELED")) {
                            finish(callback, false, "Google sign-in cancelled");
                        } else if (e instanceof NoCredentialException
                                || type.contains("NO_CREDENTIAL")) {
                            finish(callback, false,
                                    "No Google account found - try email sign-in");
                        } else {
                            finish(callback, false, "Google sign-in failed - use email instead");
                        }
                    }
                });
    }

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
                AuthApi.signInWithGoogle(activity, idToken,
                        (success, message) -> finish(callback, success, message));
            } else {
                finish(callback, false, "Unsupported Google credential");
            }
        } catch (Exception e) {
            Log.w(TAG, "[GOOGLE] unexpected failure", e);
            finish(callback, false, "Google sign-in failed");
        }
    }

    /** Clears the cached Google credential state on sign-out. */
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
        if (callback != null) callback.onDone(success, message);
    }
}
