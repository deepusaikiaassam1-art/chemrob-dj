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
import com.chemrob.medadherence.core.Course;
import com.chemrob.medadherence.core.DoseForm;
import com.chemrob.medadherence.core.DrugInfo;
import com.chemrob.medadherence.core.Medication;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.Inventory;
import com.chemrob.medadherence.core.ScheduleEngine;
import com.chemrob.medadherence.core.ScheduledDose;
import com.chemrob.medadherence.core.TimeUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static com.chemrob.medadherence.core.I18n.t;
import static com.chemrob.medadherence.core.I18n.tf;

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
        String title, body, instructions = "", photo = null, courseText = "", advice = "";
        boolean observed = false;
        Medication med = null;
        if (key.startsWith("TEST|")) {
            title = "Test alarm";
            body = "This is how your medicine reminder rings.";
        } else {
            AppData data = Store.get(this);
            ScheduledDose dose = ScheduleEngine.find(data, key);
            if (dose == null || !ScheduleEngine.isDueNow(data, dose, LocalDateTime.now())) { finish(); return; }
            title = dose.med.name;
            body = (dose.med.dose.isEmpty() ? "" : dose.med.dose + "  \u00b7  ") + tf("due %s", TimeUtil.clock(dose.time));
            instructions = dose.med.instructions;
            if (Inventory.needsRefill(dose.med, LocalDateTime.now()))
                instructions += (instructions.isEmpty() ? "" : "\n") + tf("Refill soon: %s", Inventory.label(dose.med));
            observed = dose.med.observed;
            photo = dose.med.photo;
            med = dose.med;
            if (med.doseForm().hasSide() && !med.side.isEmpty())
                body += "  \u00b7  " + t(med.doseForm() == DoseForm.EYE ? "Eye: " + med.side : "Ear: " + med.side);
            Course c = Course.of(data, med, LocalDateTime.now());
            if (c != null && !c.finished) courseText = tf("Day %d of %d  ·  %d doses left", c.day, c.days, c.left);
            advice = DrugInfo.advice(med.name);
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
            FrameLayoutHolder.addCentered(root, Ui.iconCircle(this, med == null ? R.drawable.ic_pill : Ui.formIcon(med.doseForm()),
                    Ui.PRIMARY_CONTAINER, Ui.ON_PRIMARY_CONTAINER, 120));
        }

        LinearLayout info = Ui.card(root, Ui.SURFACE);
        center(Ui.text(info, title, 30, Ui.INK, true));
        center(Ui.text(info, body, 18, Ui.MUTED, false));
        if (!instructions.isEmpty()) center(Ui.text(info, instructions, 17, Ui.INK, false));
        if (!courseText.isEmpty()) center(Ui.text(info, courseText, 16, Ui.PRIMARY, true));
        if (!advice.isEmpty()) center(Ui.text(info, advice, 15, Ui.WARN, false));
        if (observed) center(Ui.text(info, "Take this dose in front of the camera.", 16, Ui.WARN, true));
        if (med != null && med.doseForm() != DoseForm.TABLET) {
            final DoseForm form = med.doseForm();
            Ui.button(info, "How to use", Ui.SURFACE_VARIANT, v -> {
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                LinearLayout box = Ui.vbox(this);
                int pad = Ui.dp(this, 16);
                box.setPadding(pad, 0, pad, pad);
                sv.addView(box);
                Ui.howTo(box, form);
                new android.app.AlertDialog.Builder(this).setView(sv).setPositiveButton(t("OK"), null).show();
            });
        }

        final String k = key;
        if (observed) {
            Ui.button(root, "Take on camera", Ui.GOOD, v -> {
                stopVibration();
                Notifications.cancel(this, k);
                startActivity(ObserveActivity.intent(this, k));
                finish();
            }).getLayoutParams().height = Ui.dp(this, 72);
        } else {
            Ui.button(root, med == null || med.doseForm().observable ? "I took it" : "Done", Ui.GOOD, v -> act(DoseStatus.TAKEN))
                    .getLayoutParams().height = Ui.dp(this, 72);
        }
        LinearLayout row = Ui.row(root);
        Ui.button(row, "Snooze", Ui.WARN, v -> act(DoseStatus.SNOOZED));
        final boolean antimicrobial = med != null && DrugInfo.isAntimicrobial(med.name);
        Ui.button(row, "Skip", Ui.SURFACE_VARIANT, v -> {
            if (!antimicrobial) { act(DoseStatus.SKIPPED); return; }
            new android.app.AlertDialog.Builder(this)
                    .setTitle(t("Finish the full course"))
                    .setMessage(t("Skipping antibiotic doses can let the infection come back and helps germs become resistant. Take it unless your doctor told you to stop."))
                    .setPositiveButton(t("I took it"), (dlg, w) -> act(DoseStatus.TAKEN))
                    .setNeutralButton(t("Skip anyway"), (dlg, w) -> act(DoseStatus.SKIPPED))
                    .setNegativeButton(t("Cancel"), null)
                    .show();
        });
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
            if (d != null) Voice.sayTake(this, d.med);
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
