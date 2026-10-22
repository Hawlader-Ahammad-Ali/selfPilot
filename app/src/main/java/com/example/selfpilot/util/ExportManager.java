package com.example.selfpilot.util;

import android.content.Context;
import android.net.Uri;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.ExportHistoryEntity;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.data.HonorScoreEntity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExportManager {

    public interface ExportCallback {
        void onSuccess(String msg);
        void onError(String err);
    }

    public static void exportDataToUri(Context context, Uri uri, int daysToExport, ExportCallback callback) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(context);
                
                long now = System.currentTimeMillis();
                long startMillis = now - (daysToExport * 86400000L);

                List<FocusSessionEntity> sessions = db.focusSessionDao().getSessionsByDate(startMillis);
                List<HonorScoreEntity> scores = db.honorScoreDao().getRecentScores(); // Assume we query 30
                
                JSONObject root = new JSONObject();
                root.put("exportDate", now);
                root.put("daysRange", daysToExport);

                JSONArray sessionArray = new JSONArray();
                if (sessions != null) {
                    for (FocusSessionEntity s : sessions) {
                        JSONObject sObj = new JSONObject();
                        sObj.put("id", s.id);
                        sObj.put("startTime", s.startTime);
                        sObj.put("durationMinutes", s.durationMinutes);
                        sObj.put("xpEarned", s.xpEarned);
                        sObj.put("status", s.status);
                        sessionArray.put(sObj);
                    }
                }
                root.put("focusSessions", sessionArray);

                JSONArray scoreArray = new JSONArray();
                if (scores != null) {
                    for (HonorScoreEntity hs : scores) {
                        JSONObject hsObj = new JSONObject();
                        hsObj.put("date", hs.getDate());
                        hsObj.put("disciplineScore", hs.getDisciplineScore());
                        hsObj.put("studyPoints", hs.getStudyPoints());
                        scoreArray.put(hsObj);
                    }
                }
                root.put("honorScores", scoreArray);

                String jsonOutput = root.toString(4);

                try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(jsonOutput.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } else {
                        throw new Exception("Unable to open output stream.");
                    }
                }

                // Log Export History
                ExportHistoryEntity history = new ExportHistoryEntity(
                        now,
                        uri.getLastPathSegment(),
                        "JSON",
                        "Last " + daysToExport + " days"
                );
                db.exportHistoryDao().insert(history);

                if (callback != null) {
                    callback.onSuccess("Successfully exported " + jsonOutput.length() + " bytes to " + uri.getLastPathSegment());
                }

            } catch (Exception e) {
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            } finally {
                executor.shutdown();
            }
        });
    }
}
