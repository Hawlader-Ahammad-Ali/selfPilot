package com.example.selfpilot.ui.account;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.example.selfpilot.R;
import com.example.selfpilot.account.AccountDataManager;
import com.example.selfpilot.account.AccountSession;
import com.example.selfpilot.account.AuthApi;
import com.example.selfpilot.account.CloudBackupWorker;
import com.example.selfpilot.account.CloudSyncManager;
import com.example.selfpilot.account.GoogleSignInClient;
import com.example.selfpilot.account.LeaderboardRepository;
import com.example.selfpilot.account.PrivacySettings;
import com.example.selfpilot.account.ProfileStats;
import com.example.selfpilot.account.SessionSyncManager;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.util.SystemBarInsets;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Profile dashboard + account controls.
 *
 * Guest mode is a first-class state: the dashboard renders from the local database, and the
 * only difference is that nothing is uploaded. Every cloud action is optional and failure of
 * any of them leaves the local features untouched.
 */
public class AccountActivity extends AppCompatActivity {

    public static void start(Context context) {
        if (context == null) return;
        try {
            context.startActivity(new Intent(context, AccountActivity.class));
        } catch (Exception ignored) {
        }
    }

    private TextView tvAvatar, tvName, tvEmail, tvMode;
    private TextView tvStatPoints, tvStatFocusMinutes, tvStatSessions, tvStatStreak, tvStatExams;
    private TextView tvSyncStatus, tvRemoteSession, tvDeviceList, tvLeaderboardEmpty, tvLeaderboardMode;
    private LinearLayout containerLeaderboard;
    private View layoutGuestActions, layoutMemberActions;
    private MaterialSwitch switchAutoBackup, switchRealtime;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable remoteRefresh = this::refreshRemoteCard;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);
        SystemBarInsets.apply(findViewById(R.id.accountRoot));

        bindViews();

        findViewById(R.id.btnAccountBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnGoogleSignIn).setOnClickListener(v -> startGoogleSignIn());
        findViewById(R.id.btnSignIn).setOnClickListener(v -> showAuthDialog(false));
        findViewById(R.id.btnCreateAccount).setOnClickListener(v -> showAuthDialog(true));
        findViewById(R.id.btnSyncNow).setOnClickListener(v -> runSync());
        findViewById(R.id.btnRestore).setOnClickListener(v -> confirmRestore());
        findViewById(R.id.btnSignOut).setOnClickListener(v -> confirmSignOut());
        findViewById(R.id.btnRefreshLeaderboard).setOnClickListener(v -> loadLeaderboard());

        findViewById(R.id.btnExportData).setOnClickListener(v -> exportData());
        findViewById(R.id.btnDeleteAccount).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.rowLeaderboardMode).setOnClickListener(v -> chooseLeaderboardMode());

        switchAutoBackup.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                PrivacySettings.setAutoBackup(this, isChecked);
                if (isChecked) {
                    CloudBackupWorker.schedule(this);
                    if (AccountSession.isSignedIn(this)) CloudSyncManager.syncIfDue(this, 0L);
                } else {
                    CloudBackupWorker.cancel(this);
                }
                Toast.makeText(this, isChecked ? "Automatic backup on" : "Automatic backup off",
                        Toast.LENGTH_SHORT).show();
            }
        });

        switchRealtime.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                PrivacySettings.setRealtimeSync(this, isChecked);
                if (isChecked) {
                    SessionSyncManager.startListening(this);
                    SessionSyncManager.publish(this);
                } else {
                    SessionSyncManager.stopListening();
                }
                refreshRemoteCard();
            }
        });

        SessionSyncManager.RemoteListenerRegistry.add(remoteRefresh);
        refreshUi();
    }

    private void bindViews() {
        tvAvatar = findViewById(R.id.tvAccountAvatar);
        tvName = findViewById(R.id.tvAccountName);
        tvEmail = findViewById(R.id.tvAccountEmail);
        tvMode = findViewById(R.id.tvAccountMode);
        tvStatPoints = findViewById(R.id.tvStatPoints);
        tvStatFocusMinutes = findViewById(R.id.tvStatFocusMinutes);
        tvStatSessions = findViewById(R.id.tvStatSessions);
        tvStatStreak = findViewById(R.id.tvStatStreak);
        tvStatExams = findViewById(R.id.tvStatExams);
        tvSyncStatus = findViewById(R.id.tvSyncStatus);
        tvRemoteSession = findViewById(R.id.tvRemoteSession);
        tvDeviceList = findViewById(R.id.tvDeviceList);
        tvLeaderboardEmpty = findViewById(R.id.tvLeaderboardEmpty);
        tvLeaderboardMode = findViewById(R.id.tvLeaderboardMode);
        containerLeaderboard = findViewById(R.id.containerLeaderboard);
        layoutGuestActions = findViewById(R.id.layoutGuestActions);
        layoutMemberActions = findViewById(R.id.layoutMemberActions);
        switchAutoBackup = findViewById(R.id.switchAutoBackup);
        switchRealtime = findViewById(R.id.switchRealtime);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SessionSyncManager.RemoteListenerRegistry.remove(remoteRefresh);
        io.shutdown();
    }

    // ------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------

    private void refreshUi() {
        AccountSession session = AccountSession.load(this);
        boolean signedIn = session.isSignedIn();

        if (signedIn) {
            tvName.setText(session.safeName());
            tvEmail.setText(session.email == null ? "Signed in" : session.email);
            String provider = "google.com".equals(session.provider) ? "Google" : "Email";
            tvMode.setText("Signed in with " + provider + " • cloud backup on");
            tvAvatar.setText(initialOf(session.safeName()));
            layoutGuestActions.setVisibility(View.GONE);
            layoutMemberActions.setVisibility(View.VISIBLE);
        } else {
            tvName.setText("Guest");
            tvEmail.setText("Using SelfPilot without an account");
            tvMode.setText("Local only • every feature unlocked");
            tvAvatar.setText("G");
            layoutGuestActions.setVisibility(View.VISIBLE);
            layoutMemberActions.setVisibility(View.GONE);
        }

        switchAutoBackup.setChecked(PrivacySettings.autoBackup(this));
        switchRealtime.setChecked(PrivacySettings.realtimeSync(this));
        tvLeaderboardMode.setText(leaderboardModeLabel(PrivacySettings.leaderboardMode(this)));
        tvSyncStatus.setText(CloudSyncManager.statusLine(this));

        loadStats();
        loadLeaderboard();
        refreshRemoteCard();
    }

    private String leaderboardModeLabel(String mode) {
        if (PrivacySettings.BOARD_ANONYMOUS.equals(mode)) return "Anonymous";
        if (PrivacySettings.BOARD_OFF.equals(mode)) return "Hidden";
        return "Public name";
    }

    private void loadStats() {
        try {
            io.execute(() -> {
                final ProfileStats stats = ProfileStats.load(this);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    tvStatPoints.setText(String.valueOf(stats.studyPoints));
                    tvStatFocusMinutes.setText(stats.focusMinutes + "m");
                    tvStatSessions.setText(String.valueOf(stats.focusSessions));
                    tvStatStreak.setText(stats.streak + "d");
                    tvStatExams.setText(String.valueOf(stats.exams));
                });
            });
        } catch (Exception ignored) {
        }
    }

    private void refreshRemoteCard() {
        String summary = SessionSyncManager.remoteSummary(this);
        tvRemoteSession.setText(summary == null
                ? "No live session on another device"
                : summary);

        SessionSyncManager.listDevices(this, devices -> runOnUiThread(() -> {
            if (isFinishing()) return;
            if (devices == null || devices.isEmpty()) {
                tvDeviceList.setText(AccountSession.isSignedIn(this)
                        ? "Only this device has synced so far"
                        : "");
            } else {
                tvDeviceList.setText("Devices: " + devices);
            }
        }));
    }

    private static String initialOf(String name) {
        if (name == null || name.trim().isEmpty()) return "S";
        return name.trim().substring(0, 1).toUpperCase(Locale.getDefault());
    }

    // ------------------------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------------------------

    private void startGoogleSignIn() {
        GoogleSignInClient.signIn(this, (success, message) -> {
            if (isFinishing()) return;
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            if (success) onAuthenticated();
        });
    }

    private void showAuthDialog(final boolean createMode) {
        View form = getLayoutInflater().inflate(R.layout.dialog_auth, null);
        final EditText etName = form.findViewById(R.id.etAuthName);
        final EditText etEmail = form.findViewById(R.id.etAuthEmail);
        final EditText etPassword = form.findViewById(R.id.etAuthPassword);
        etName.setVisibility(createMode ? View.VISIBLE : View.GONE);

        final AlertDialog dialog;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(createMode ? "Create account" : "Sign in")
                .setView(form)
                .setPositiveButton(createMode ? "Create" : "Sign in", null)
                .setNegativeButton("Cancel", null);
        if (!createMode) builder.setNeutralButton("Reset password", null);
        dialog = builder.create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String email = etEmail.getText().toString().trim();
                String password = etPassword.getText().toString();
                String name = etName.getText().toString().trim();

                if (TextUtils.isEmpty(email) || !email.contains("@")) {
                    Toast.makeText(this, "Enter a valid email", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (password.length() < 6) {
                    Toast.makeText(this, "Password must be at least 6 characters",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Please wait...");

                AuthApi.Callback callback = (success, message) -> {
                    if (isFinishing()) return;
                    if (success) {
                        dialog.dismiss();
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                        onAuthenticated();
                    } else {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setText(createMode ? "Create" : "Sign in");
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    }
                };

                if (createMode) AuthApi.signUp(this, email, password, name, callback);
                else AuthApi.signIn(this, email, password, callback);
            });

            if (!createMode) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    String email = etEmail.getText().toString().trim();
                    if (TextUtils.isEmpty(email) || !email.contains("@")) {
                        Toast.makeText(this, "Enter your email first", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AuthApi.sendPasswordReset(this, email, (success, message) ->
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show());
                });
            }
        });

        dialog.show();
        if (!createMode) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(
                    getResources().getColor(R.color.electric_blue, getTheme()));
        }
    }

    /**
     * Guest-to-account migration. When this phone already holds data, the user is told what
     * will happen before anything is uploaded; the merge is additive in both directions.
     */
    private void onAuthenticated() {
        boolean hasLocalData;
        try {
            AppDatabase db = AppDatabase.getDatabase(this);
            hasLocalData = db.honorScoreDao().getTotalStudyPoints() > 0
                    || !db.restrictionRuleDao().getAllRules().isEmpty()
                    || db.frozenAppDao().getFrozenCount() > 0;
        } catch (Exception e) {
            hasLocalData = false;
        }

        CloudBackupWorker.schedule(this);
        SessionSyncManager.startListening(this);

        if (!hasLocalData) {
            runMigration();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Link this device's data?")
                .setMessage("Your local progress stays on this phone and is added to your account. "
                        + "If the account already has a backup, both are merged - nothing is "
                        + "overwritten.")
                .setPositiveButton("Link data", (d, w) -> runMigration())
                .setNegativeButton("Not now", (d, w) -> {
                    refreshUi();
                    Toast.makeText(this, "Signed in - local data kept on this device",
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void runMigration() {
        tvSyncStatus.setText("Linking local data...");
        CloudSyncManager.migrateThenSync(this, (success, message) -> {
            if (isFinishing()) return;
            refreshUi();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    // ------------------------------------------------------------------------------------
    // Sync / restore / sign out
    // ------------------------------------------------------------------------------------

    private void runSync() {
        if (!AccountSession.isSignedIn(this)) {
            showAuthDialog(false);
            return;
        }
        tvSyncStatus.setText("Backing up...");
        CloudSyncManager.syncNow(this, (success, message) -> {
            if (isFinishing()) return;
            tvSyncStatus.setText(CloudSyncManager.statusLine(this));
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            loadLeaderboard();
        });
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this)
                .setTitle("Restore from cloud")
                .setMessage("Your cloud backup will be merged into this device. "
                        + "Anything already here is kept.")
                .setPositiveButton("Restore", (d, w) -> {
                    tvSyncStatus.setText("Restoring...");
                    CloudSyncManager.restore(this, (success, message) -> {
                        if (isFinishing()) return;
                        refreshUi();
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
                .setTitle("Sign out")
                .setMessage("Sign out of this device?\n\nYour local data stays on the phone and "
                        + "every feature keeps working. Nothing is deleted.")
                .setPositiveButton("Sign out", (d, w) -> {
                    SessionSyncManager.clearRemoteNode(this);
                    SessionSyncManager.stopListening();
                    GoogleSignInClient.signOut(this);
                    CloudBackupWorker.cancel(this);
                    AccountSession.clear(this);
                    Toast.makeText(this, "Signed out - local data kept",
                            Toast.LENGTH_SHORT).show();
                    refreshUi();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------------------------
    // Privacy
    // ------------------------------------------------------------------------------------

    private void chooseLeaderboardMode() {
        final String[] labels = {"Public name", "Anonymous", "Hidden"};
        final String[] values = {PrivacySettings.BOARD_PUBLIC,
                PrivacySettings.BOARD_ANONYMOUS, PrivacySettings.BOARD_OFF};
        int checked = 0;
        String current = PrivacySettings.leaderboardMode(this);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Leaderboard visibility")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    PrivacySettings.setLeaderboardMode(this, values[which]);
                    tvLeaderboardMode.setText(labels[which]);
                    dialog.dismiss();
                    if (AccountSession.isSignedIn(this)) {
                        CloudSyncManager.syncNow(this, (success, message) -> {
                            if (!isFinishing()) loadLeaderboard();
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------------------------
    // Data export / account deletion
    // ------------------------------------------------------------------------------------

    private void exportData() {
        Toast.makeText(this, "Preparing export...", Toast.LENGTH_SHORT).show();
        AccountDataManager.exportJson(this, (success, file, message) -> {
            if (isFinishing()) return;
            if (!success || file == null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                return;
            }
            shareFile(file);
        });
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/json");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Save or share your data"));
        } catch (Exception e) {
            Toast.makeText(this, "Saved to " + file.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete() {
        if (!AccountSession.isSignedIn(this)) {
            Toast.makeText(this, "No account is signed in", Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean[] wipeLocal = {false};
        new AlertDialog.Builder(this)
                .setTitle("Delete account")
                .setMessage("This removes your cloud backup, leaderboard entry and account. "
                        + "It cannot be undone.")
                .setMultiChoiceItems(new String[]{"Also delete local data on this phone"},
                        new boolean[]{false},
                        (dialog, which, isChecked) -> wipeLocal[0] = isChecked)
                .setPositiveButton("Delete", (d, w) -> {
                    tvSyncStatus.setText("Deleting account...");
                    AccountDataManager.deleteAccount(this, wipeLocal[0], (success, message) -> {
                        if (isFinishing()) return;
                        refreshUi();
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------------------------
    // Leaderboard
    // ------------------------------------------------------------------------------------

    private void loadLeaderboard() {
        if (!AccountSession.isSignedIn(this)) {
            containerLeaderboard.removeAllViews();
            tvLeaderboardEmpty.setVisibility(View.VISIBLE);
            tvLeaderboardEmpty.setText("Sign in to see how you compare. "
                    + "The leaderboard is optional - no feature needs it.");
            return;
        }
        if (PrivacySettings.BOARD_OFF.equals(PrivacySettings.leaderboardMode(this))) {
            containerLeaderboard.removeAllViews();
            tvLeaderboardEmpty.setVisibility(View.VISIBLE);
            tvLeaderboardEmpty.setText("Hidden mode - your row is not published. "
                    + "You can still see the board.");
        }

        LeaderboardRepository.fetch(this, 20, (success, rows, message) -> {
            if (isFinishing()) return;
            containerLeaderboard.removeAllViews();

            if (rows == null || rows.isEmpty()) {
                tvLeaderboardEmpty.setVisibility(View.VISIBLE);
                tvLeaderboardEmpty.setText(message == null || message.isEmpty()
                        ? "No entries yet - be the first" : message);
                return;
            }

            tvLeaderboardEmpty.setVisibility(View.GONE);
            for (LeaderboardRepository.Row row : rows) {
                View item = getLayoutInflater().inflate(
                        R.layout.item_leaderboard, containerLeaderboard, false);
                TextView rank = item.findViewById(R.id.tvLeaderboardRank);
                TextView name = item.findViewById(R.id.tvLeaderboardName);
                TextView detail = item.findViewById(R.id.tvLeaderboardDetail);
                TextView points = item.findViewById(R.id.tvLeaderboardPoints);

                rank.setText(String.valueOf(row.rank));
                name.setText(row.isMe ? row.name + " (you)" : row.name);
                detail.setText(row.focusMinutes + " min focus • discipline " + row.discipline);
                points.setText(row.points + " pts");

                if (row.isMe) {
                    name.setTextColor(getResources().getColor(R.color.purple_accent, getTheme()));
                }
                containerLeaderboard.addView(item);
            }
        });
    }
}
