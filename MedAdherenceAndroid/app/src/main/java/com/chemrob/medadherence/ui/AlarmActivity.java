package com.chemrob.medadherence.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.alarm.AlarmReceiver;
import com.chemrob.medadherence.alarm.Notifications;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.ScheduleEngine;
import com.chemrob.medadherence.core.ScheduledDose;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Full-screen "your phone is ringing" screen, shown over the lock screen by the alarm notification.
 * The sound is the insistent alarm notification, so it keeps ringing until the patient chooses.
 */
public class AlarmActivity extends Activity {
    private String key;
    private Vibrator vibrator;

    public static PendingIntent pendingIntent(Context ctx, String key) {
        Intent i = new Intent(ctx, AlarmActivity.class);
        i.setData(Uri.parse("medadherence://ring/" + Uri.encode(key)));
        i.putExtra(AlarmReceiver.EXTRA_KEY, key);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        return PendingIntent.getActivity(ctx, ("ring" + key).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(intent);
    }

    private void show(Intent intent) {
        key = intent.getStringExtra(AlarmReceiver.EXTRA_KEY);
        if (key == null) { finish(); return; }
        String title, body;
        boolean observed = false;
        if (key.startsWith("TEST|")) {
            title = "Test alarm";
            body = "This is how your medicine reminder rings.";
        } else {
            AppData data = Store.get(this);
            ScheduledDose dose = ScheduleEngine.find(data, key);
            if (dose == null || !ScheduleEngine.isDueNow(data, dose, LocalDateTime.now())) { finish(); return; }
            title = Notifications.title(dose);
            body = Notifications.body(data, dose, LocalDateTime.now());
            observed = dose.med.observed;
        }

        LinearLayout root = Ui.vbox(this);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Ui.PRIMARY);
        int p = Ui.dp(this, 24);
        root.setPadding(p, Ui.dp(this, 64), p, p);
        center(Ui.text(root, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), 64, Color.WHITE, true));
        center(Ui.text(root, "Time for your medicine", 20, Color.parseColor("#BFE3DA"), false));
        center(Ui.text(root, "\n" + title, 28, Color.WHITE, true));
        center(Ui.text(root, body, 17, Color.WHITE, false));
        if (observed) center(Ui.text(root, "\nThis dose must be taken in front of the camera.", 16, Color.parseColor("#FFE08A"), false));
        View fill = new View(this);
        root.addView(fill, new LinearLayout.LayoutParams(1, 0, 1));

        final String k = key;
        if (observed) {
            Ui.button(root, "Take on camera", Ui.GOOD, v -> {
                stopVibration();
                Notifications.cancel(this, k);
                startActivity(ObserveActivity.intent(this, k));
                finish();
            });
        } else {
            Ui.button(root, "I have taken it", Ui.GOOD, v -> act(DoseStatus.TAKEN));
        }
        Ui.button(root, "Snooze", Ui.WARN, v -> act(DoseStatus.SNOOZED));
        Ui.button(root, "Skip this dose", Ui.MUTED, v -> act(DoseStatus.SKIPPED));
        setContentView(root);
        startVibration();
    }

    private static void center(TextView t) { t.setGravity(Gravity.CENTER); }

    private void act(DoseStatus status) {
        stopVibration();
        if (key.startsWith("TEST|")) Notifications.cancel(this, key);
        else AlarmReceiver.record(this, key, status);
        finish();
    }

    private void startVibration() {
        vibrator = getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 500}, 0));
        getWindow().getDecorView().postDelayed(this::stopVibration, 3 * 60 * 1000L);
    }

    private void stopVibration() { if (vibrator != null) vibrator.cancel(); }

    @Override
    protected void onDestroy() {
        stopVibration();
        super.onDestroy();
    }

}
