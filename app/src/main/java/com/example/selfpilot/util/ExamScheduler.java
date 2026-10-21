package com.example.selfpilot.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.receiver.ExamAlarmReceiver;

public class ExamScheduler {

    public static void scheduleExam(Context context, ExamProfileEntity exam) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        long now = System.currentTimeMillis();

        if (exam.startDate > now) {
            PendingIntent startIntent = createPendingIntent(context, exam.id, ExamAlarmReceiver.ACTION_EXAM_START);
            setAlarm(alarmManager, exam.startDate, startIntent);
            Log.d("ExamScheduler", "Scheduled START for exam " + exam.id + " at " + exam.startDate);
        }

        if (exam.endDate > now) {
            PendingIntent endIntent = createPendingIntent(context, exam.id, ExamAlarmReceiver.ACTION_EXAM_END);
            setAlarm(alarmManager, exam.endDate, endIntent);
            Log.d("ExamScheduler", "Scheduled END for exam " + exam.id + " at " + exam.endDate);
        }
    }

    public static void cancelExam(Context context, int examId) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        PendingIntent startIntent = createPendingIntent(context, examId, ExamAlarmReceiver.ACTION_EXAM_START);
        alarmManager.cancel(startIntent);
        
        PendingIntent endIntent = createPendingIntent(context, examId, ExamAlarmReceiver.ACTION_EXAM_END);
        alarmManager.cancel(endIntent);
    }

    private static PendingIntent createPendingIntent(Context context, int examId, String action) {
        Intent intent = new Intent(context, ExamAlarmReceiver.class);
        intent.setAction(action);
        intent.putExtra(ExamAlarmReceiver.EXTRA_EXAM_ID, examId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        // Using action + examId for unique RequestCode equivalence to allow distinct pending intents per exam.
        return PendingIntent.getBroadcast(context, examId, intent, flags);
    }

    private static void setAlarm(AlarmManager alarmManager, long timeMs, PendingIntent pendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pendingIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, timeMs, pendingIntent);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, timeMs, pendingIntent);
        }
    }
}
