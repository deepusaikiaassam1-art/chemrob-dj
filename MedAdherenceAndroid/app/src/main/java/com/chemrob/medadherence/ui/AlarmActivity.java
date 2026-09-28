package com.chemrob.medadherence.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chemrob.medadherence.R;
import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.alarm.AlarmReceiver;
import com.chemrob.medadherence.alarm.Notifications;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.Inventory;
import com.chemrob.medadherence.core.ScheduleEngine;
import com.chemrob.medadherence.core.ScheduledDose;
import com.chemrob.medadherence.core.TimeUtil;

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
        Ui.apply(this);
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
        String title, body, instructions = "", photo = null;
        boolean observed = false;
        if (key.startsWith("TEST|")) {
            title = "Test alarm";
            body = "This is how your medicine reminder rings.";
        } else {
            AppData data = Store.get(this);
            ScheduledDose dose = ScheduleEngine.find(data, key);
            if (dose == null || !ScheduleEngine.isDueNow(data, dose, LocalDateTime.now())) { finish(); return; }
            title = dose.med.name;
            body = (dose.med.dose.isEmpty() ? "" : dose.med.dose + "  \u00b7  ") + "due " + TimeUtil.clock(dose.time);
            instructions = dose.med.instructions;
            if (Inventory.needsRefill(dose.med, LocalDateTime.now()))
                instructions += (instructions.isEmpty() ? "" : "\n") + "Refill soon: " + Inventory.label(dose.med);
            observed = dose.med.observed;
            photo = dose.med.photo;
        }

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 22);
        root.setPadding(p, Ui.dp(this, 36), p, p);

        LinearLayout top = Ui.hbox(this);
        top.setGravity(Gravity.CENTER);
        top.addView(Ui.icon(this, R.drawable.ic_alarm, Ui.PRIMARY, 26));
        TextView clock = Ui.text(top, "  " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), 30, Ui.INK, true);
        clock.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(top, Ui.matchWrap(this, 0));
        center(Ui.text(root, "Time for your medicine", 18, Ui.MUTED, false));

        // Big photo of the drug so the patient takes the right one.
        android.graphics.Bitmap pic = Ui.loadBitmap(photo, Ui.dp(this, 260));
        if (pic != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(pic);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(AlarmActivity.this, 28));
                }
            });
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            lp.topMargin = Ui.dp(this, 18);
            root.addView(iv, lp);
        } else {
            FrameLayoutHolder.addCentered(root, Ui.iconCircle(this, R.drawable.ic_pill, Ui.PRIMARY_CONTAINER, Ui.ON_PRIMARY_CONTAINER, 120));
        }

        LinearLayout info = Ui.card(root, Ui.SURFACE);
        center(Ui.text(info, title, 30, Ui.INK, true));
        center(Ui.text(info, body, 18, Ui.MUTED, false));
        if (!instructions.isEmpty()) center(Ui.text(info, instructions, 17, Ui.INK, false));
        if (observed) center(Ui.text(info, "Take this dose in front of the camera.", 16, Ui.WARN, true));

        final String k = key;
        if (observed) {
            Ui.button(root, "Take on camera", Ui.GOOD, v -> {
                stopVibration();
                Notifications.cancel(this, k);
                startActivity(ObserveActivity.intent(this, k));
                finish();
            }).getLayoutParams().height = Ui.dp(this, 72);
        } else {
            Ui.button(root, "I took it", Ui.GOOD, v -> act(DoseStatus.TAKEN)).getLayoutParams().height = Ui.dp(this, 72);
        }
        LinearLayout row = Ui.row(root);
        Ui.button(row, "Snooze", Ui.WARN, v -> act(DoseStatus.SNOOZED));
        Ui.button(row, "Skip", Ui.SURFACE_VARIANT, v -> act(DoseStatus.SKIPPED));
        setContentView(root);
        startVibration();
    }

    /** Adds a view centred horizontally with some space above and below. */
    private static final class FrameLayoutHolder {
        static void addCentered(LinearLayout parent, View v) {
            LinearLayout wrap = Ui.hbox(parent.getContext());
            wrap.setGravity(Gravity.CENTER);
            wrap.addView(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            parent.addView(wrap, lp);
        }
    }

    private static void center(TextView t) { t.setGravity(Gravity.CENTER); }

    private void act(DoseStatus status) {
        stopVibration();
        if (status == DoseStatus.TAKEN && !key.startsWith("TEST|")) {
            ScheduledDose d = ScheduleEngine.find(Store.get(this), key);
            if (d != null) Voice.say(this, Voice.takePhrase(d.med));
        } else if (status == DoseStatus.SNOOZED) Voice.say(this, "Okay, I will remind you again soon.");
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
