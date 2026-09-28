package com.chemrob.medadherence.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.ScheduleEngine;
import com.chemrob.medadherence.core.ScheduledDose;

import java.time.LocalDateTime;

/** The AlarmManager wake-up for a dose, and the Taken / Snooze / Skip notification buttons. */
public class AlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_FIRE = "com.chemrob.medadherence.FIRE";
    public static final String ACTION_TAKEN = "com.chemrob.medadherence.TAKEN";
    public static final String ACTION_SNOOZE = "com.chemrob.medadherence.SNOOZE";
    public static final String ACTION_SKIP = "com.chemrob.medadherence.SKIP";
    public static final String EXTRA_KEY = "dose_key";
    static final String TEST_KEY = "TEST|000000000000";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String key = intent.getStringExtra(EXTRA_KEY);
        String action = intent.getAction();
        if (key == null || action == null) return;

        if (ACTION_FIRE.equals(action)) {
            if (TEST_KEY.equals(key)) {
                Notifications.postRinging(ctx, key, "Test alarm", "This is how your medicine reminder rings.", false);
                return;
            }
            AppData data = Store.get(ctx);
            ScheduledDose dose = ScheduleEngine.find(data, key);
            LocalDateTime now = LocalDateTime.now();
            if (dose != null && ScheduleEngine.isDueNow(data, dose, now)) {
                AlarmScheduler.countRing(ctx, key);
                Notifications.postDose(ctx, data, dose, now);
            }
            AlarmScheduler.syncAll(ctx); // plans the repeat and rolls the horizon forward
            return;
        }
        if (TEST_KEY.equals(key)) {
            Notifications.cancel(ctx, key);
            return;
        }
        DoseStatus status = ACTION_TAKEN.equals(action) ? DoseStatus.TAKEN
                : ACTION_SNOOZE.equals(action) ? DoseStatus.SNOOZED : DoseStatus.SKIPPED;
        record(ctx, key, status);
    }

    /** Records an action from any surface (notification, ringing screen, app). */
    public static void record(Context ctx, String key, DoseStatus status) {
        AppData data = Store.get(ctx);
        ScheduleEngine.record(data, key, status, LocalDateTime.now());
        Store.save(ctx);
        AlarmScheduler.onRecorded(ctx, key);
    }
}
