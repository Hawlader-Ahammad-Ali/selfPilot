package com.example.selfpilot.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.example.selfpilot.service.FocusTimerService;
import com.example.selfpilot.util.ExamScheduler;
import com.example.selfpilot.util.ExamSessionManager;

import java.util.List;
import java.util.concurrent.Executors;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) {
            com.example.selfpilot.util.FrozenAppsManager.init(context);

            // Bring the always-on guardian back before anything else so the freezer is
            // enforcing again as early as possible after a reboot.
            com.example.selfpilot.service.GuardianService.start(context);
            
            Executors.newSingleThreadExecutor().execute(() -> {
                AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
                
                // 1. Recover Frozen Apps Enforcement
                List<FrozenAppEntity> frozenApps = db.frozenAppDao().getFrozenApps();
                String selfPkg = context.getPackageName();
                if (frozenApps != null) {
                    for (FrozenAppEntity frozen : frozenApps) {
                        if (frozen.isFrozen && !frozen.packageName.equals("com.example.selfpilot") && !frozen.packageName.equals(selfPkg)) {
                            RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(frozen.packageName);
                            if (rule == null) {
                                rule = new RestrictionRuleEntity();
                                rule.packageName = frozen.packageName;
                            }
                            rule.isBlocked = true;
                            db.restrictionRuleDao().insert(rule);

                            AppInfoEntity info = db.appInfoDao().getAppInfo(frozen.packageName);
                            if (info != null) {
                                info.isFrozen = true;
                                info.isBlocked = true;
                                db.appInfoDao().update(info);
                            }
                        }
                    }
                }

                // 2. Recover Active Focus Timer
                if (db.focusSessionDao().getActiveSession() != null) {
                    Intent recoverIntent = new Intent(context, FocusTimerService.class);
                    recoverIntent.setAction(FocusTimerService.ACTION_RECOVER);
                    ContextCompat.startForegroundService(context, recoverIntent);
                }

                // 3. Reconcile Exam Mode with the clock before re-arming any alarm, so an
                //    exam that expired while the device was off is completed instead of
                //    blocking apps forever.
                ExamSessionManager.syncState(context.getApplicationContext());

                List<ExamProfileEntity> pendingExams = db.examProfileDao().getPendingExams();
                if (pendingExams != null) {
                    for (ExamProfileEntity pending : pendingExams) {
                        ExamScheduler.scheduleExam(context.getApplicationContext(), pending);
                    }
                }

                List<ExamProfileEntity> activeExams = db.examProfileDao().getActiveExams();
                if (activeExams != null) {
                    for (ExamProfileEntity active : activeExams) {
                        // Re-arm the end alarm; the start alarm firing immediately is harmless
                        // because state transitions are derived from the dates.
                        ExamScheduler.scheduleExam(context.getApplicationContext(), active);
                    }
                }
            });
        }
    }
}

