package com.example.selfpilot.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.example.selfpilot.service.FocusTimerService;

import java.util.List;
import java.util.concurrent.Executors;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) {
            
            Executors.newSingleThreadExecutor().execute(() -> {
                AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
                
                // 1. Recover Frozen Apps Enforcement
                List<FrozenAppEntity> frozenApps = db.frozenAppDao().getFrozenApps();
                if (frozenApps != null) {
                    for (FrozenAppEntity frozen : frozenApps) {
                        if (frozen.isFrozen) {
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
            });
        }
    }
}
