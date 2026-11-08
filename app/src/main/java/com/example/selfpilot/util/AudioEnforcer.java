package com.example.selfpilot.util;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.selfpilot.service.SelfPilotNotificationListener;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Stops audio that a blocked app keeps playing.
 *
 * Blocking the UI is not enough: a music player that already owns an audio focus and a media
 * session keeps singing from the background after its limit is gone. SelfPilot already holds
 * notification-listener access, and that is exactly the grant needed to reach other apps'
 * MediaSessions - so the restriction can be enforced on the audio side too.
 *
 * Strategy, strongest first:
 *   1. pause()/stop() on every active MediaSession of the blocked package.
 *   2. a short audio-focus request, which makes well-behaved players pause themselves.
 *   3. killBackgroundProcesses() to reclaim the cached process.
 *
 * None of this needs root, Shizuku or a device-owner profile.
 */
public final class AudioEnforcer {

    private static final String TAG = "SELF_PILOT_AUDIO";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AudioEnforcer() {
    }

    /** Packages with an active media session that is playing or buffering right now. */
    public static List<String> playingPackages(Context context) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (MediaController controller : activeSessions(context)) {
            try {
                PlaybackState state = controller.getPlaybackState();
                if (state == null) continue;
                int code = state.getState();
                if (code == PlaybackState.STATE_PLAYING
                        || code == PlaybackState.STATE_BUFFERING
                        || code == PlaybackState.STATE_FAST_FORWARDING) {
                    String pkg = controller.getPackageName();
                    if (pkg != null) result.add(pkg);
                }
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(result);
    }

    public static boolean isPlaying(Context context, String packageName) {
        if (packageName == null) return false;
        for (MediaController controller : activeSessions(context)) {
            try {
                if (!packageName.equals(controller.getPackageName())) continue;
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * Silences {@code packageName} if it is producing audio. Safe to call for any package:
     * it does nothing when the app has no media session.
     */
    public static boolean stopPlayback(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        Context app = context.getApplicationContext();

        // The overlay path can call this several times a second; once every few seconds is
        // plenty to keep a player silent without hammering the media session service.
        if (!claimAttempt(packageName)) return false;

        boolean touched = false;
        for (MediaController controller : activeSessions(app)) {
            try {
                if (!packageName.equals(controller.getPackageName())) continue;
                MediaController.TransportControls controls = controller.getTransportControls();
                if (controls == null) continue;
                controls.pause();
                controls.stop();
                touched = true;
                Log.i(TAG, "[AUDIO] Stopped media session of " + packageName);
            } catch (Exception e) {
                Log.w(TAG, "[AUDIO] Could not stop session of " + packageName, e);
            }
        }

        // Players that ignore transport commands still respect audio focus.
        if (touched) nudgeAudioFocus(app);

        killBackground(app, packageName);
        return touched;
    }

    // ------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------

    private static final long MIN_RETRY_MS = 6_000L;
    private static final java.util.Map<String, Long> LAST_ATTEMPT = new java.util.HashMap<>();

    private static boolean claimAttempt(String packageName) {
        synchronized (LAST_ATTEMPT) {
            long now = android.os.SystemClock.elapsedRealtime();
            Long last = LAST_ATTEMPT.get(packageName);
            if (last != null && now - last < MIN_RETRY_MS) return false;
            LAST_ATTEMPT.put(packageName, now);
            return true;
        }
    }

    private static List<MediaController> activeSessions(Context context) {
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return new ArrayList<>();

            // A notification listener is enough to read other apps' sessions; without it the
            // call throws SecurityException, which simply means "nothing to enforce".
            ComponentName listener =
                    new ComponentName(context, SelfPilotNotificationListener.class);
            List<MediaController> sessions = manager.getActiveSessions(listener);
            return sessions == null ? new ArrayList<>() : sessions;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void nudgeAudioFocus(Context context) {
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest request = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setWillPauseWhenDucked(true)
                        .build();
                audio.requestAudioFocus(request);
                MAIN.postDelayed(() -> {
                    try {
                        audio.abandonAudioFocusRequest(request);
                    } catch (Exception ignored) {
                    }
                }, 600L);
            } else {
                audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                MAIN.postDelayed(() -> {
                    try {
                        audio.abandonAudioFocus(null);
                    } catch (Exception ignored) {
                    }
                }, 600L);
            }
        } catch (Exception e) {
            Log.w(TAG, "[AUDIO] audio focus nudge failed", e);
        }
    }

    private static void killBackground(Context context, String packageName) {
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) manager.killBackgroundProcesses(packageName);
        } catch (Exception ignored) {
            // Modern Android only lets apps reclaim cached processes; the media stop above is
            // what actually silences the player.
        }
    }
}
