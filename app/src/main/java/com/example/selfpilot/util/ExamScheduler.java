package com.example.selfpilot.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.receiver.ExamAlarmReceiver;

public class ExamScheduler {

    public static void scheduleExam(Context context, ExamProfileEntity exam) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        // Schedule Start Alarm
        Intent startIntent = new Intent(context, ExamAlarmReceiver.class);
        startIntent.setAction(ExamAlarmReceiver.ACTION_EXAM_START);
        startIntent.putExtra(ExamAlarmReceiver.EXTRA_EXAM_ID, exam.id);
        PendingIntent startPending = PendingIntent.getBroadcast(context, exam.id * 2, startIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, exam.startDate, startPending);
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, exam.startDate, startPending);
        }

        // Schedule End Alarm
        Intent endIntent = new Intent(context, ExamAlarmReceiver.class);
        endIntent.setAction(ExamAlarmReceiver.ACTION_EXAM_END);
        endIntent.putExtra(ExamAlarmReceiver.EXTRA_EXAM_ID, exam.id);
        PendingIntent endPending = PendingIntent.getBroadcast(context, exam.id * 2 + 1, endIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, exam.endDate, endPending);
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, exam.endDate, endPending);
        }
    }

    public static void cancelExamAlarms(Context context, int examId) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent startIntent = new Intent(context, ExamAlarmReceiver.class);
        startIntent.setAction(ExamAlarmReceiver.ACTION_EXAM_START);
        PendingIntent startPending = PendingIntent.getBroadcast(context, examId * 2, startIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.cancel(startPending);

        Intent endIntent = new Intent(context, ExamAlarmReceiver.class);
        endIntent.setAction(ExamAlarmReceiver.ACTION_EXAM_END);
        PendingIntent endPending = PendingIntent.getBroadcast(context, examId * 2 + 1, endIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.cancel(endPending);
    }
}
