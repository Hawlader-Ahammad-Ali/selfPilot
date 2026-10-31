package com.example.selfpilot.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.selfpilot.service.GuardianService;

/**
 * Resurrects {@link GuardianService} after the process was killed (OEM task cleaners,
 * swiping SelfPilot away from recents, low memory). Without this, the freezer would
 * silently stop enforcing anything until the user opened the app again.
 */
public class GuardianRestartReceiver extends BroadcastReceiver {

    private static final String TAG = "SELF_PILOT_FREEZER";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "[GUARDIAN] Restart alarm fired -> starting guardian service");
        GuardianService.start(context);
    }
}
