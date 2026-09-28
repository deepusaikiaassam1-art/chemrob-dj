package com.chemrob.medadherence.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.Appointment;
import com.chemrob.medadherence.core.Caregiver;
import com.chemrob.medadherence.core.I18n;
import com.chemrob.medadherence.core.Inventory;
import com.chemrob.medadherence.core.ScheduledDose;
import com.chemrob.medadherence.core.TimeUtil;
import com.chemrob.medadherence.ui.AlarmActivity;
import com.chemrob.medadherence.ui.ObserveActivity;
import com.chemrob.medadherence.ui.Ui;

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
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, I18n.t("Medication alarms"), NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(I18n.t("Rings when it is time to take a medicine"));
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
        StringBuilder b = new StringBuilder(I18n.tf("Scheduled %s", TimeUtil.clock(d.time)));
        if (!d.med.instructions.isEmpty()) b.append(" | ").append(d.med.instructions);
        if (Inventory.needsRefill(d.med, now)) b.append(" | ").append(I18n.tf("Refill soon: %s", Inventory.label(d.med)));
        return b.toString();
    }

    static void postDose(Context ctx, AppData data, ScheduledDose d, LocalDateTime now) {
        postRinging(ctx, d.key(), title(d), body(data, d, now), d.med.observed, d.med.photo);
    }

    static void postRinging(Context ctx, String key, String title, String body, boolean observed) {
        postRinging(ctx, key, title, body, observed, null);
    }

    static void postRinging(Context ctx, String key, String title, String body, boolean observed, String photo) {
        ensureChannel(ctx);
        int icon = android.R.drawable.ic_lock_idle_alarm;
        PendingIntent ring = AlarmActivity.pendingIntent(ctx, key);
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(body)
                .setColor(0xFF4F46E5)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setTimeoutAfter(RING_TIMEOUT_MS)
                .setContentIntent(ring)
                .setFullScreenIntent(ring, true);
        // Show the drug's photo so the patient can recognise the right medicine at a glance.
        Bitmap pic = Ui.loadBitmap(photo, 720);
        if (pic != null) {
            b.setLargeIcon(pic);
            b.setStyle(new Notification.BigPictureStyle().bigPicture(pic).bigLargeIcon((Icon) null).setSummaryText(body));
        } else {
            b.setStyle(new Notification.BigTextStyle().bigText(body));
        }
        Icon ic = Icon.createWithResource(ctx, icon);
        if (observed) {
            // An activity PendingIntent: Android 12+ forbids starting activities from a receiver here.
            b.addAction(new Notification.Action.Builder(ic, I18n.t("Take on camera"), ObserveActivity.pendingIntent(ctx, key)).build());
        } else {
            b.addAction(new Notification.Action.Builder(ic, I18n.t("Taken"), action(ctx, AlarmReceiver.ACTION_TAKEN, key)).build());
        }
        b.addAction(new Notification.Action.Builder(ic, I18n.t("Snooze"), action(ctx, AlarmReceiver.ACTION_SNOOZE, key)).build());
        b.addAction(new Notification.Action.Builder(ic, I18n.t("Skip"), action(ctx, AlarmReceiver.ACTION_SKIP, key)).build());
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT; // repeat the alarm sound until the patient responds
        ctx.getSystemService(NotificationManager.class).notify(key, ID_DOSE, n);
    }

    static final String VISIT_CHANNEL_ID = "doctor_visits_v1";

    /** Plain (non-ringing) reminder of a doctor follow-up. */
    static void postAppointment(Context ctx, Appointment a) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(VISIT_CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(VISIT_CHANNEL_ID, I18n.t("Doctor follow-ups"), NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(I18n.t("Reminders of doctor appointments"));
            nm.createNotificationChannel(ch);
        }
        LocalDateTime t = a.time();
        String when = t == null ? a.when : t.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", I18n.locale()));
        String body = when + "  \u00b7  " + a.who() + (a.purpose.isEmpty() ? "" : "\n" + a.purpose);
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        Notification.Builder b = new Notification.Builder(ctx, VISIT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setColor(0xFF4F46E5)
                .setContentTitle(I18n.t("Doctor follow-up"))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true);
        if (open != null)
            b.setContentIntent(PendingIntent.getActivity(ctx, ("visit" + a.id).hashCode(), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        nm.notify("visit" + a.id, 2, b.build());
    }

    static final String CARE_CHANNEL_ID = "caregiver_v1";

    /**
     * A message for the caregiver, ready to send: tap WhatsApp or SMS and the text is filled in.
     * (The app has no SMS permission, so it cannot send on its own.)
     */
    public static void postCaregiver(Context ctx, String tag, String title, String message, String number) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CARE_CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CARE_CHANNEL_ID, I18n.t("Caregiver alerts"), NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(I18n.t("Missed doses and daily summaries to send to your caregiver"));
            nm.createNotificationChannel(ch);
        }
        PendingIntent sms = PendingIntent.getActivity(ctx, ("sms" + tag).hashCode(), smsIntent(number, message),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent wa = PendingIntent.getActivity(ctx, ("wa" + tag).hashCode(), whatsappIntent(number, message),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Icon ic = Icon.createWithResource(ctx, android.R.drawable.ic_menu_send);
        Notification.Builder b = new Notification.Builder(ctx, CARE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setColor(0xFF4F46E5)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(wa)
                .addAction(new Notification.Action.Builder(ic, "WhatsApp", wa).build())
                .addAction(new Notification.Action.Builder(ic, "SMS", sms).build());
        nm.notify("care" + tag, 3, b.build());
    }

    public static Intent smsIntent(String number, String message) {
        return new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Caregiver.dialable(number)))
                .putExtra("sms_body", message).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Opens a WhatsApp chat with the message typed in (or the browser, if WhatsApp is missing). */
    public static Intent whatsappIntent(String number, String message) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + Caregiver.whatsappNumber(number)
                + "?text=" + Uri.encode(message))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
