package com.example.selfpilot.util;

import android.content.Context;
import android.util.Log;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FocusSessionEntity;

/**
 * Single source of truth for focus session state.
 *
 * Focus Mode used to rely on a CountDownTimer inside the service and on the row's
 * status column alone. If that service died (process kill, force stop, reboot before the
 * boot receiver ran, OEM task cleaner) the row stayed "ACTIVE" forever, which meant:
 *   - every non-study app was blocked with no way out,
 *   - notifications kept being suppressed,
 *   - starting a new session did nothing.
 *
 * A session's endTime is authoritative, so state can always be recomputed from the clock.
 */
public class FocusSessionManager {

    private static final String TAG = "SELF_PILOT_FREEZER";

    /** XP for a completed session, reduced by every emergency unlock used inside it. */
    public static int calculateXp(int emergencyUnlocksUsed) {
        return Math.max(0, 20 - (5 * emergencyUnlocksUsed));
    }

    public static FocusSessionEntity getActiveSession(Context context) {
        try {
            return AppDatabase.getDatabase(context.getApplicationContext())
                    .focusSessionDao().getActiveSession();
        } catch (Exception e) {
            Log.w(TAG, "[FOCUS] failed to read active session", e);
            return null;
        }
    }

    /**
     * Completes the running session (if any) regardless of the clock.
     *
     * @return the XP awarded, or 0 when there was no active session.
     */
    public static int completeActiveSession(Context context) {
        FocusSessionEntity active = getActiveSession(context);
        if (active == null) return 0;
        return completeSession(context, active) ? calculateXp(active.emergencyUnlocksUsed) : 0;
    }

    /**
     * Marks one session COMPLETED and recalculates the daily score.
     * Idempotent: an already completed session is left untouched.
     */
    public static boolean completeSession(Context context, FocusSessionEntity session) {
        if (session == null) return false;
        if (!"ACTIVE".equals(session.status)) return false;

        try {
            AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
            session.status = "COMPLETED";
            session.xpEarned = calculateXp(session.emergencyUnlocksUsed);
            db.focusSessionDao().update(session);

            Log.i(TAG, "[FOCUS] Session completed, XP " + session.xpEarned);
            ScoreCalculator.calculateAndSaveDailyScore(context.getApplicationContext());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "[FOCUS] failed to complete session", e);
            return false;
        }
    }

    /**
     * Closes sessions whose end time has passed. Safe to call often and from any thread.
     *
     * @return true when a session was completed.
     */
    public static boolean syncState(Context context) {
        if (context == null) return false;
        FocusSessionEntity active = getActiveSession(context);
        if (active == null) return false;

        if (System.currentTimeMillis() < active.endTime) return false;

        return completeSession(context, active);
    }
}
