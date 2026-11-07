package com.example.selfpilot.account;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Automatic cloud backup in the background.
 *
 * The worker is deliberately allowed to do nothing: no session, sync disabled, no network or
 * any failure simply returns success without touching local data. It never touches focus,
 * freezer or exam state.
 */
public class CloudBackupWorker extends Worker {

    private static final String WORK_NAME = "selfpilot-auto-backup";

    public CloudBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context context = getApplicationContext();
            if (!AccountSession.isSignedIn(context)) return Result.success();
            if (!PrivacySettings.autoBackup(context)) return Result.success();
            CloudSyncManager.syncBlocking(context);
        } catch (Exception ignored) {
            // Retrying is pointless when the cause is a configuration problem; the next
            // schedule will try again anyway.
        }
        return Result.success();
    }

    /** Registers the periodic job. Safe to call on every app start. */
    public static void schedule(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    CloudBackupWorker.class, 6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build();

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Exception ignored) {
        }
    }

    public static void cancel(Context context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        } catch (Exception ignored) {
        }
    }
}
