package com.example.selfpilot.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.util.Log;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.example.selfpilot.util.FocusSessionManager;
import com.example.selfpilot.util.RestrictionChecker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Suppresses notifications from blocked apps.
 *
 * The exam screen has "Allow calls" / "Allow messages" switches - they were written to
 * SharedPreferences but never read anywhere, so they did nothing. They are honoured here:
 * during Exam Mode the default dialer and SMS app keep their notifications when allowed.
 *
 * Note: a normal app cannot stop an incoming call from ringing on Android; the switches only
 * control whether the call/SMS notification is suppressed.
 */
public class SelfPilotNotificationListener extends NotificationListenerService {

    private static final String TAG = "SELF_PILOT_FREEZER";
    private static final String PREFS = "SelfPilotPrefs";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        final String packageName = sbn.getPackageName();
        if (packageName == null || packageName.equals(getPackageName())) return;
        final String key = sbn.getKey();

        if (executorService.isShutdown()) return;

        // Focus Mode silences every app, not only restricted ones.
        if (FocusSessionManager.isFocusActive()) {
            Log.i(TAG, "[NOTIF] Focus session active - suppressing notification from " + packageName);
            cancelNotification(key);
            return;
        }

        executorService.execute(() -> {
            try {
                RestrictionChecker.BlockDecision decision =
                        RestrictionChecker.evaluate(getApplicationContext(), packageName);
                if (!decision.isBlocked()) return;

                boolean shouldCancel = false;
                switch (decision.reason) {
                    case FROZEN:
                        shouldCancel = true;
                        break;
                    case EXAM_MODE:
                        shouldCancel = !isCommunicationAllowed(packageName);
                        break;
                    case FOCUS_MODE:
                        shouldCancel = true;
                        break;
                    default:
                        RestrictionRuleEntity rule = AppDatabase.getDatabase(getApplicationContext())
                                .restrictionRuleDao().getRuleByPackageName(packageName);
                        shouldCancel = rule != null && rule.suppressNotifications;
                        break;
                }

                if (shouldCancel) {
                    cancelNotification(key);
                }
            } catch (Exception e) {
                Log.w(TAG, "[NOTIF] failed to evaluate notification from " + packageName, e);
            }
        });
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Not used, but required to override
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }

    /**
     * True when the package is the default dialer / SMS app and the user allowed it during
     * Exam Mode. Only ever keeps a notification the app would otherwise suppress.
     */
    private boolean isCommunicationAllowed(String packageName) {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean allowCalls = prefs.getBoolean("exam_allow_calls", true);
        boolean allowMessages = prefs.getBoolean("exam_allow_messages", false);

        if (allowMessages) {
            try {
                String smsPackage = Telephony.Sms.getDefaultSmsPackage(this);
                if (packageName.equals(smsPackage)) return true;
            } catch (Exception ignored) {
            }
        }

        if (allowCalls) {
            try {
                TelecomManager telecomManager =
                        (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null && packageName.equals(telecomManager.getDefaultDialerPackage())) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            // In-call UI of any dialer.
            if (packageName.contains("incall") || packageName.contains("dialer")) return true;
        }

        return false;
    }
}

