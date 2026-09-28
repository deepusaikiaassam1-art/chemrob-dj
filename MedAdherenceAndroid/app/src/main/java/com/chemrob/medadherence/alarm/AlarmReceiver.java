package com.chemrob.medadherence.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.Appointment;
import com.chemrob.medadherence.core.Caregiver;
import com.chemrob.medadherence.core.I18n;
import com.chemrob.medadherence.core.TimeUtil;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.ScheduleEngine;
import com.chemrob.medadherence.core.ScheduledDose;

import java.time.LocalDateTime;
import java.util.List;

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
            if (key.startsWith(AlarmScheduler.APPT_PREFIX)) {
                String id = key.substring(AlarmScheduler.APPT_PREFIX.length(), key.lastIndexOf('|'));
                for (Appointment a : Store.get(ctx).appointments)
                    if (a.id.equals(id) && !a.done) Notifications.postAppointment(ctx, a);
                return;
            }
            if (AlarmScheduler.CARE_CHECK.equals(key) || AlarmScheduler.CARE_SUMMARY.equals(key)) {
                caregiver(ctx, AlarmScheduler.CARE_SUMMARY.equals(key));
                AlarmScheduler.syncAll(ctx);
                return;
            }
            if (TEST_KEY.equals(key)) {
                Notifications.postRinging(ctx, key, I18n.t("Test alarm"), I18n.t("This is how your medicine reminder rings."), false);
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

    /** Offers to tell the caregiver about newly missed doses, or sends the evening summary. */
    static void caregiver(Context ctx, boolean summary) {
        AppData d = Store.get(ctx);
        String number = d.profile.caregiverNumber();
        if (number.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        if (summary) {
            if (!d.settings.caregiverDailySummary) return;
            Notifications.postCaregiver(ctx, "summary", I18n.tf("Send today's summary to %s", d.profile.caregiverLabel()),
                    Caregiver.dailySummary(d, now.toLocalDate(), now), number);
            return;
        }
        if (!d.settings.caregiverMissedAlerts) return;
        LocalDateTime since = TimeUtil.parseSecond(d.settings.caregiverLastCheck);
        if (since == null || since.isAfter(now)) since = now.minusHours(1);
        if (since.isBefore(now.minusDays(1))) since = now.minusDays(1); // phone was off: only recent misses
        List<ScheduledDose> missed = Caregiver.newlyMissed(d, since, now);
        d.settings.caregiverLastCheck = TimeUtil.second(now);
        Store.save(ctx);
        if (!missed.isEmpty())
            Notifications.postCaregiver(ctx, "missed", I18n.tf("Missed dose - tell %s?", d.profile.caregiverLabel()),
                    Caregiver.missedMessage(d, missed), number);
    }

    /** Records an action from any surface (notification, ringing screen, app). */
    public static void record(Context ctx, String key, DoseStatus status) {
        AppData data = Store.get(ctx);
        ScheduleEngine.record(data, key, status, LocalDateTime.now());
        Store.save(ctx);
        AlarmScheduler.onRecorded(ctx, key);
    }
}
