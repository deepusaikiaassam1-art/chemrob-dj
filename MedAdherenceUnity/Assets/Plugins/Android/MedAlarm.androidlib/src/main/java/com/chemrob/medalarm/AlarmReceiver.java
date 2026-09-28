package com.chemrob.medalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Receives the AlarmManager wake-up for a dose, and the Taken / Snooze / Skip notification buttons. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        String doseKey = intent.getStringExtra(MedAlarmPlugin.EXTRA_KEY);
        if (action == null || doseKey == null) return;

        if (MedAlarmPlugin.ACTION_FIRE.equals(action)) {
            JSONObject a = MedAlarmPlugin.alarm(ctx, doseKey);
            if (a == null) return; // dose was already taken / cancelled
            if (a.optBoolean("info", false)) {
                MedAlarmPlugin.postInfoNotification(ctx, doseKey, a);
                return;
            }
            int repeat = intent.getIntExtra(MedAlarmPlugin.EXTRA_REPEAT, 0);
            MedAlarmPlugin.setRinging(ctx, doseKey, true);
            MedAlarmPlugin.postRingingNotification(ctx, doseKey, a);
            if (repeat < MedAlarmPlugin.MAX_REPEATS) {
                long next = System.currentTimeMillis() + MedAlarmPlugin.REPEAT_MINUTES * 60_000L;
                MedAlarmPlugin.setAlarm(ctx, doseKey, repeat + 1, next);
            }
        } else {
            MedAlarmPlugin.handleAction(ctx, action, doseKey);
        }
    }
}
