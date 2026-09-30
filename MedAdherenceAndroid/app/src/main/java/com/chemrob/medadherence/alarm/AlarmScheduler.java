package com.chemrob.medadherence.alarm;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.Appointment;
import com.chemrob.medadherence.core.Caregiver;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.ScheduleEngine;
import com.chemrob.medadherence.core.ScheduledDose;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps AlarmManager in step with the regimen. {@link #syncAll} is called whenever anything changes,
 * on boot, and every time an alarm fires, so the schedule always rolls forward on its own.
 */
public final class AlarmScheduler {
    static final String PREFS = "alarms";
    static final String K_SCHEDULED = "scheduled";
    static final String K_RINGS = "rings_";

    /** Unanswered doses ring again this often, at most MAX_RINGS times in total. */
    public static final int REPEAT_MINUTES = 10;
    static final String APPT_PREFIX = "APPT|";
    static final String CARE_CHECK = "CARE|check", CARE_SUMMARY = "CARE|summary";
    static final int MAX_RINGS = 4;
    static final int HORIZON_DAYS = 7;

    private AlarmScheduler() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean canScheduleExact(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        return android.os.Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms());
    }

    /** Re-registers an alarm for every unsettled dose from now to the horizon. */
    public static synchronized void syncAll(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        SharedPreferences p = prefs(ctx);
        for (String key : p.getStringSet(K_SCHEDULED, new HashSet<>())) {
            PendingIntent old = fireIntent(ctx, key, PendingIntent.FLAG_NO_CREATE);
            if (old != null) { am.cancel(old); old.cancel(); }
        }

        AppData data = Store.get(ctx);
        LocalDateTime now = LocalDateTime.now();
        Set<String> scheduled = new HashSet<>();
        Set<String> pending = new HashSet<>();
        SharedPreferences.Editor ed = p.edit();
        for (ScheduledDose dose : ScheduleEngine.doses(data, now.minusMinutes(data.settings.graceMinutes), now.plusDays(HORIZON_DAYS))) {
            DoseStatus s = ScheduleEngine.statusOf(data, dose, now);
            if (s != DoseStatus.PENDING && s != DoseStatus.SNOOZED) continue;
            pending.add(dose.key());
            LocalDateTime at = ScheduleEngine.nextRingTime(data, dose);
            if (at.isBefore(now)) {
                // Due but unanswered: ring again shortly, a limited number of times.
                if (p.getInt(K_RINGS + dose.key(), 0) >= MAX_RINGS) continue;
                at = now.plusMinutes(REPEAT_MINUTES);
            }
            long ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            PendingIntent pi = fireIntent(ctx, dose.key(), PendingIntent.FLAG_UPDATE_CURRENT);
            if (canScheduleExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi); // may arrive a few minutes late
            scheduled.add(dose.key());
        }
        // Doctor follow-up reminders (a day before and 2 hours before).
        for (Appointment a : data.appointments) {
            for (LocalDateTime r : a.reminderTimes(now)) {
                if (r.isAfter(now.plusDays(HORIZON_DAYS))) continue;
                String key = APPT_PREFIX + a.id + "|" + r.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
                long ms = r.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                PendingIntent pi = fireIntent(ctx, key, PendingIntent.FLAG_UPDATE_CURRENT);
                if (canScheduleExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
                scheduled.add(key);
            }
        }
        // Caregiver alerts: a check when the next dose could become missed, and the evening summary.
        if (!data.profile.caregiverNumber().isEmpty()) {
            LocalDateTime check = data.settings.caregiverMissedAlerts ? Caregiver.nextCheck(data, now) : null;
            if (check != null) { setLoose(ctx, am, CARE_CHECK, check); scheduled.add(CARE_CHECK); }
            if (data.settings.caregiverDailySummary) {
                setLoose(ctx, am, CARE_SUMMARY, Caregiver.nextSummary(data, now));
                scheduled.add(CARE_SUMMARY);
            }
        }
        // Forget ring counters of doses that are no longer pending.
        for (String k : p.getAll().keySet())
            if (k.startsWith(K_RINGS) && !pending.contains(k.substring(K_RINGS.length()))) ed.remove(k);
        ed.putStringSet(K_SCHEDULED, scheduled).apply();
        com.chemrob.medadherence.ui.NextDoseWidget.updateAll(ctx);
    }

    /** Alarms that may run a few minutes late (no exact-alarm needed). */
    private static void setLoose(Context ctx, AlarmManager am, String key, LocalDateTime at) {
        long ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, fireIntent(ctx, key, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    static void countRing(Context ctx, String key) {
        SharedPreferences p = prefs(ctx);
        p.edit().putInt(K_RINGS + key, p.getInt(K_RINGS + key, 0) + 1).apply();
    }

    /** One-off alarm a minute from now so the patient can hear what a reminder sounds like. */
    public static void scheduleTest(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        long ms = System.currentTimeMillis() + 60_000L;
        PendingIntent pi = fireIntent(ctx, AlarmReceiver.TEST_KEY, PendingIntent.FLAG_UPDATE_CURRENT);
        if (canScheduleExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
    }

    /** Call after a dose is recorded: stops its ringing and re-plans the schedule. */
    public static void onRecorded(Context ctx, String doseKey) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(doseKey, Notifications.ID_DOSE);
        syncAll(ctx);
    }

    static PendingIntent fireIntent(Context ctx, String doseKey, int flags) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction(AlarmReceiver.ACTION_FIRE);
        i.setData(Uri.parse("medadherence://dose/" + Uri.encode(doseKey)));
        i.putExtra(AlarmReceiver.EXTRA_KEY, doseKey);
        return PendingIntent.getBroadcast(ctx, doseKey.hashCode(), i, flags | PendingIntent.FLAG_IMMUTABLE);
    }
}
