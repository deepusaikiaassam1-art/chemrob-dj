package com.chemrob.medadherence.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.Inventory;
import com.chemrob.medadherence.core.ScheduledDose;
import com.chemrob.medadherence.core.TimeUtil;
import com.chemrob.medadherence.ui.AlarmActivity;
import com.chemrob.medadherence.ui.ObserveActivity;

import java.time.LocalDateTime;

/** The insistent alarm notification that rings until the patient responds. */
public final class Notifications {
    static final String CHANNEL_ID = "med_alarm_v1";
    public static final int ID_DOSE = 1;
    static final long RING_TIMEOUT_MS = 3 * 60 * 1000L;

    private Notifications() {}

    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Medication alarms", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Rings when it is time to take a medicine");
        ch.setSound(alarmSound(), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 400, 800, 400, 800});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    static Uri alarmSound() {
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        return u != null ? u : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    public static String title(ScheduledDose d) {
        return d.med.name + (d.med.dose.isEmpty() ? "" : " - " + d.med.dose);
    }

    public static String body(AppData data, ScheduledDose d, LocalDateTime now) {
        StringBuilder b = new StringBuilder("Scheduled ").append(TimeUtil.clock(d.time));
        if (!d.med.instructions.isEmpty()) b.append(" | ").append(d.med.instructions);
        if (Inventory.needsRefill(d.med, now)) b.append(" | Refill soon: ").append(Inventory.label(d.med));
        return b.toString();
    }

    static void postDose(Context ctx, AppData data, ScheduledDose d, LocalDateTime now) {
        postRinging(ctx, d.key(), title(d), body(data, d, now), d.med.observed);
    }

    static void postRinging(Context ctx, String key, String title, String body, boolean observed) {
        ensureChannel(ctx);
        int icon = android.R.drawable.ic_lock_idle_alarm;
        PendingIntent ring = AlarmActivity.pendingIntent(ctx, key);
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setTimeoutAfter(RING_TIMEOUT_MS)
                .setContentIntent(ring)
                .setFullScreenIntent(ring, true);
        Icon ic = Icon.createWithResource(ctx, icon);
        if (observed) {
            // An activity PendingIntent: Android 12+ forbids starting activities from a receiver here.
            b.addAction(new Notification.Action.Builder(ic, "Take on camera", ObserveActivity.pendingIntent(ctx, key)).build());
        } else {
            b.addAction(new Notification.Action.Builder(ic, "Taken", action(ctx, AlarmReceiver.ACTION_TAKEN, key)).build());
        }
        b.addAction(new Notification.Action.Builder(ic, "Snooze", action(ctx, AlarmReceiver.ACTION_SNOOZE, key)).build());
        b.addAction(new Notification.Action.Builder(ic, "Skip", action(ctx, AlarmReceiver.ACTION_SKIP, key)).build());
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT; // repeat the alarm sound until the patient responds
        ctx.getSystemService(NotificationManager.class).notify(key, ID_DOSE, n);
    }

    public static void cancel(Context ctx, String key) {
        ctx.getSystemService(NotificationManager.class).cancel(key, ID_DOSE);
    }

    static PendingIntent action(Context ctx, String action, String key) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction(action);
        i.setData(Uri.parse("medadherence://action/" + Uri.encode(key)));
        i.putExtra(AlarmReceiver.EXTRA_KEY, key);
        return PendingIntent.getBroadcast(ctx, (action + key).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
