package com.chemrob.medalarm;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Native side of the medication reminder. Unity calls the public static methods through
 * AndroidJavaClass("com.chemrob.medalarm.MedAlarmPlugin").
 *
 * Every scheduled dose is kept in SharedPreferences so it survives reboots (BootReceiver) and so
 * actions taken from the notification / ringing screen while Unity is not running are queued as
 * events that Unity drains the next time it is in the foreground.
 */
public final class MedAlarmPlugin {
    static final String PREFS = "medalarm";
    static final String K_ALARMS = "alarms";       // JSONObject: doseKey -> alarm
    static final String K_EVENTS = "events";       // JSONArray of {key, action, at}
    static final String K_RINGING = "ringing";     // JSONArray of doseKeys currently ringing
    static final String K_LAUNCH = "launch_dose";  // dose the user asked to verify with the camera

    static final String CHANNEL_ID = "med_alarm_v1";
    static final String INFO_CHANNEL_ID = "med_info_v1";
    static final String ACTION_FIRE = "com.chemrob.medalarm.FIRE";
    static final String ACTION_TAKEN = "com.chemrob.medalarm.TAKEN";
    static final String ACTION_SNOOZE = "com.chemrob.medalarm.SNOOZE";
    static final String ACTION_SKIP = "com.chemrob.medalarm.SKIP";

    static final String EXTRA_KEY = "dose_key";
    static final String EXTRA_REPEAT = "repeat";

    /** A dose that nobody reacts to rings again this many times, this many minutes apart. */
    static final int MAX_REPEATS = 3;
    static final int REPEAT_MINUTES = 10;
    static final int DEFAULT_SNOOZE_MINUTES = 10;
    /** Stop ringing a single alarm after this long; the repeat alarm rings again later. */
    static final long RING_TIMEOUT_MS = 3 * 60 * 1000L;

    private MedAlarmPlugin() {}

    // ------------------------------------------------------------------ Unity API

    /** Schedules (or replaces) the alarm for one dose. */
    public static synchronized void schedule(Context ctx, String doseKey, long triggerAtMillis,
                                             String title, String body, boolean observed) {
        try {
            JSONObject a = new JSONObject();
            a.put("key", doseKey);
            a.put("at", triggerAtMillis);
            a.put("title", title);
            a.put("body", body);
            a.put("observed", observed);
            JSONObject all = alarms(ctx);
            all.put(doseKey, a);
            save(ctx, K_ALARMS, all.toString());
            setAlarm(ctx, doseKey, 0, triggerAtMillis);
        } catch (JSONException ignored) {
        }
    }

    /**
     * Replaces every stored alarm with the given set in one step (one preferences write).
     * json: {"items":[{"key":..., "at": epochMillis, "title":..., "body":..., "observed": bool}, ...]}
     */
    public static synchronized void scheduleAll(Context ctx, String json) {
        cancelAll(ctx);
        try {
            JSONArray in = new JSONObject(json).getJSONArray("items");
            JSONObject all = new JSONObject();
            for (int i = 0; i < in.length(); i++) {
                JSONObject a = in.getJSONObject(i);
                String key = a.getString("key");
                all.put(key, a);
                setAlarm(ctx, key, 0, a.getLong("at"));
            }
            save(ctx, K_ALARMS, all.toString());
        } catch (JSONException ignored) {
        }
    }

    /** Cancels a dose's alarm, its repeats, its notification and its ringing state. */
    public static synchronized void cancel(Context ctx, String doseKey) {
        for (int r = 0; r <= MAX_REPEATS; r++) cancelAlarm(ctx, doseKey, r);
        JSONObject all = alarms(ctx);
        all.remove(doseKey);
        save(ctx, K_ALARMS, all.toString());
        notifications(ctx).cancel(doseKey, 0);
        setRinging(ctx, doseKey, false);
    }

    public static synchronized void cancelAll(Context ctx) {
        JSONObject all = alarms(ctx);
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String key = it.next();
            for (int r = 0; r <= MAX_REPEATS; r++) cancelAlarm(ctx, key, r);
        }
        save(ctx, K_ALARMS, "{}");
    }

    /** Returns and clears the queue of actions taken outside Unity, as a JSON array string. */
    public static synchronized String drainEvents(Context ctx) {
        String s = prefs(ctx).getString(K_EVENTS, "[]");
        save(ctx, K_EVENTS, "[]");
        return s;
    }

    /** Dose keys whose alarm has fired and has not been acted on, as a JSON array string. */
    public static synchronized String getRinging(Context ctx) {
        return prefs(ctx).getString(K_RINGING, "[]");
    }

    /** Returns and clears the dose the user chose to verify on camera from the ringing screen. */
    public static synchronized String consumeLaunchDose(Context ctx) {
        String s = prefs(ctx).getString(K_LAUNCH, "");
        save(ctx, K_LAUNCH, "");
        return s;
    }

    /** Silences the ringing notification without cancelling the dose (e.g. camera screen opened). */
    public static void silence(Context ctx, String doseKey) {
        notifications(ctx).cancel(doseKey, 0);
    }

    public static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    public static void openExactAlarmSettings(Activity activity) {
        if (Build.VERSION.SDK_INT < 31) return;
        Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(i);
    }

    public static boolean isIgnoringBatteryOptimizations(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    public static void openBatterySettings(Activity activity) {
        activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    public static boolean canUseFullScreenIntent(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return true;
        return notifications(ctx).canUseFullScreenIntent();
    }

    public static void openFullScreenIntentSettings(Activity activity) {
        if (Build.VERSION.SDK_INT < 34) return;
        activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:" + activity.getPackageName())));
    }

    /** Lets the Unity activity appear over the lock screen while a dose is being verified. */
    public static void setShowOverLockScreen(final Activity activity, final boolean show) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (Build.VERSION.SDK_INT >= 27) {
                    activity.setShowWhenLocked(show);
                    activity.setTurnScreenOn(show);
                    if (show) {
                        KeyguardManager km = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
                        if (km != null) km.requestDismissKeyguard(activity, null);
                    }
                } else {
                    int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
                    if (show) activity.getWindow().addFlags(flags);
                    else activity.getWindow().clearFlags(flags);
                }
                if (show) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    /** Opens the Android share sheet (WhatsApp, e-mail, SMS...) with a text report. */
    public static void shareText(Activity activity, String subject, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.putExtra(Intent.EXTRA_TEXT, text);
        activity.startActivity(Intent.createChooser(send, subject));
    }

    // ------------------------------------------------------------------ internals

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void save(Context ctx, String key, String value) {
        prefs(ctx).edit().putString(key, value).apply();
    }

    static JSONObject alarms(Context ctx) {
        try {
            return new JSONObject(prefs(ctx).getString(K_ALARMS, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static JSONObject alarm(Context ctx, String doseKey) {
        return alarms(ctx).optJSONObject(doseKey);
    }

    static NotificationManager notifications(Context ctx) {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static synchronized void addEvent(Context ctx, String doseKey, String action) {
        try {
            JSONArray ev = new JSONArray(prefs(ctx).getString(K_EVENTS, "[]"));
            JSONObject e = new JSONObject();
            e.put("key", doseKey);
            e.put("action", action);
            e.put("at", System.currentTimeMillis());
            ev.put(e);
            save(ctx, K_EVENTS, ev.toString());
        } catch (JSONException ignored) {
        }
    }

    static synchronized void setRinging(Context ctx, String doseKey, boolean ringing) {
        try {
            JSONArray in = new JSONArray(prefs(ctx).getString(K_RINGING, "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++)
                if (!doseKey.equals(in.optString(i))) out.put(in.optString(i));
            if (ringing) out.put(doseKey);
            save(ctx, K_RINGING, out.toString());
        } catch (JSONException ignored) {
        }
    }

    static void setLaunchDose(Context ctx, String doseKey) {
        save(ctx, K_LAUNCH, doseKey);
    }

    private static PendingIntent firePendingIntent(Context ctx, String doseKey, int repeat, int flags) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction(ACTION_FIRE);
        // The data URI makes each (dose, repeat) a distinct PendingIntent.
        i.setData(Uri.parse("medalarm://dose/" + Uri.encode(doseKey) + "/" + repeat));
        i.putExtra(EXTRA_KEY, doseKey);
        i.putExtra(EXTRA_REPEAT, repeat);
        return PendingIntent.getBroadcast(ctx, (doseKey + "#" + repeat).hashCode(), i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    static void setAlarm(Context ctx, String doseKey, int repeat, long at) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = firePendingIntent(ctx, doseKey, repeat, PendingIntent.FLAG_UPDATE_CURRENT);
        if (canScheduleExact(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            // Without the exact-alarm grant Android may deliver this a few minutes late.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelAlarm(Context ctx, String doseKey, int repeat) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = firePendingIntent(ctx, doseKey, repeat, PendingIntent.FLAG_NO_CREATE);
        if (am != null && pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = notifications(ctx);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Medication alarms", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Rings when it is time to take a medicine");
        ch.setSound(alarmSound(), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 400, 800, 400, 800});
        ch.enableLights(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    static Uri alarmSound() {
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return u;
    }

    static PendingIntent actionIntent(Context ctx, String action, String doseKey) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction(action);
        i.setData(Uri.parse("medalarm://action/" + Uri.encode(doseKey)));
        i.putExtra(EXTRA_KEY, doseKey);
        return PendingIntent.getBroadcast(ctx, (action + doseKey).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent ringScreenIntent(Context ctx, String doseKey) {
        Intent i = new Intent(ctx, AlarmActivity.class);
        i.setData(Uri.parse("medalarm://ring/" + Uri.encode(doseKey)));
        i.putExtra(EXTRA_KEY, doseKey);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        return PendingIntent.getActivity(ctx, ("ring" + doseKey).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent verifyIntent(Context ctx, String doseKey) {
        Intent i = new Intent(ctx, AlarmActivity.class);
        i.setData(Uri.parse("medalarm://verify/" + Uri.encode(doseKey)));
        i.putExtra(EXTRA_KEY, doseKey);
        i.putExtra(AlarmActivity.EXTRA_VERIFY_NOW, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, ("verify" + doseKey).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Opens the Unity app so the dose can be taken in front of the camera. Call from an activity. */
    static void launchUnityForVerification(Activity from, String doseKey) {
        notifications(from).cancel(doseKey, 0); // stop the sound; repeats stay armed until recorded
        setLaunchDose(from, doseKey);
        Intent launch = from.getPackageManager().getLaunchIntentForPackage(from.getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launch.putExtra(EXTRA_KEY, doseKey);
            from.startActivity(launch);
        }
    }

    /**
     * Plain (non-ringing) notification that opens the app, used for the "open the app to keep
     * reminders going" notice scheduled near the end of the alarm horizon.
     */
    static void postInfoNotification(Context ctx, String key, JSONObject a) {
        NotificationManager nm = notifications(ctx);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(INFO_CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(INFO_CHANNEL_ID, "Reminder status",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        PendingIntent open = launch == null ? null : PendingIntent.getActivity(ctx, key.hashCode(), launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, INFO_CHANNEL_ID)
                : new Notification.Builder(ctx);
        String body = a.optString("body", "");
        b.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(a.optString("title", ""))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true);
        if (open != null) b.setContentIntent(open);
        nm.notify(key, 1, b.build());
    }

    /** Posts the insistent (keeps ringing) alarm notification with a full-screen ringing screen. */
    static void postRingingNotification(Context ctx, String doseKey, JSONObject a) {
        ensureChannel(ctx);
        String title = a.optString("title", "Time for your medicine");
        String body = a.optString("body", "");
        boolean observed = a.optBoolean("observed", false);
        int icon = android.R.drawable.ic_lock_idle_alarm;

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : legacyBuilder(ctx);
        b.setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(ringScreenIntent(ctx, doseKey))
                .setFullScreenIntent(ringScreenIntent(ctx, doseKey), true);
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(RING_TIMEOUT_MS);

        if (observed) {
            // Must be an activity PendingIntent: Android 12+ blocks starting activities from a
            // notification action's broadcast receiver ("notification trampoline").
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, icon),
                    "Take on camera", verifyIntent(ctx, doseKey)).build());
        } else {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, icon),
                    "Taken", actionIntent(ctx, ACTION_TAKEN, doseKey)).build());
        }
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, icon),
                "Snooze", actionIntent(ctx, ACTION_SNOOZE, doseKey)).build());
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, icon),
                "Skip", actionIntent(ctx, ACTION_SKIP, doseKey)).build());

        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT; // repeat the alarm sound until the user responds
        notifications(ctx).notify(doseKey, 0, n);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder legacyBuilder(Context ctx) {
        Notification.Builder b = new Notification.Builder(ctx);
        b.setPriority(Notification.PRIORITY_MAX);
        b.setSound(alarmSound(), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        b.setVibrate(new long[]{0, 800, 400, 800, 400, 800});
        return b;
    }

    /** Handles Taken / Snooze / Skip from the notification or the ringing screen. */
    static void handleAction(Context ctx, String action, String doseKey) {
        if (doseKey == null) return;
        if (ACTION_SNOOZE.equals(action)) {
            JSONObject a = alarm(ctx, doseKey);
            for (int r = 0; r <= MAX_REPEATS; r++) cancelAlarm(ctx, doseKey, r);
            notifications(ctx).cancel(doseKey, 0);
            setRinging(ctx, doseKey, false);
            addEvent(ctx, doseKey, "snooze");
            if (a != null) setAlarm(ctx, doseKey, 0, System.currentTimeMillis() + DEFAULT_SNOOZE_MINUTES * 60_000L);
        } else {
            addEvent(ctx, doseKey, ACTION_TAKEN.equals(action) ? "taken" : "skip");
            cancel(ctx, doseKey);
        }
    }
}
