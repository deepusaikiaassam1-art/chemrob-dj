package com.chemrob.medadherence.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Typeface;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
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
        String title, body, instructions = "", photo = null, advice = "";
        Course course = null;
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
            body = (dose.med.dose.isEmpty() ? "" : dose.med.dose + " \u00b7 ") + tf("due %s", TimeUtil.clock(dose.time));
            instructions = dose.med.instructions;
            if (Inventory.needsRefill(dose.med, LocalDateTime.now()))
                instructions += (instructions.isEmpty() ? "" : "\n") + tf("Refill soon: %s", Inventory.label(dose.med));
            observed = dose.med.observed;
            photo = dose.med.photo;
            med = dose.med;
            if (med.doseForm().hasSide() && !med.side.isEmpty())
                body += "  \u00b7  " + t(med.doseForm() == DoseForm.EYE ? "Eye: " + med.side : "Ear: " + med.side);
            course = Course.of(data, med, LocalDateTime.now());
            advice = DrugInfo.advice(med.name);
        }

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.SURFACE);

        // Top: the pack photo full width (or the form's icon), with "Time for your medicine".
        FrameLayout top = new FrameLayout(this);
        top.setBackgroundColor(Ui.PRIMARY_CONTAINER);
        android.graphics.Bitmap pic = Ui.loadBitmap(photo, Ui.dp(this, 360));
        if (pic != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(pic);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setContentDescription(t("Medicine photo"));
            top.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            ImageView iv = Ui.icon(this, med == null ? R.drawable.ic_pill : Ui.formIcon(med.doseForm()), Ui.PRIMARY, 120);
            top.addView(iv, new FrameLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120), Gravity.CENTER));
        }
        TextView clock = new TextView(this);
        clock.setText(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        clock.setTypeface(Ui.medium(), Typeface.BOLD);
        clock.setTextColor(Ui.INK);
        clock.setBackground(Ui.rounded(this, Ui.SURFACE, 16));
        clock.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cl.setMargins(0, Ui.dp(this, 36), Ui.dp(this, 20), 0);
        top.addView(clock, cl);
        LinearLayout pill = Ui.hbox(this);
        pill.setBackground(Ui.rounded(this, Ui.PRIMARY, 26));
        pill.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        pill.addView(Ui.icon(this, R.drawable.ic_alarm, Ui.ON_PRIMARY, 24));
        TextView pl = Ui.text(pill, "Time for your medicine", 18, Ui.ON_PRIMARY, true);
        pl.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pl.setPadding(Ui.dp(this, 10), 0, 0, 0);
        FrameLayout.LayoutParams pll = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        pll.setMargins(Ui.dp(this, 20), 0, 0, Ui.dp(this, 16));
        top.addView(pill, pll);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, pic != null ? 300 : 240)));

        // Middle: what to take, and where the course is up to.
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout info = Ui.vbox(this);
        int p = Ui.dp(this, 24);
        info.setPadding(p, Ui.dp(this, 18), p, Ui.dp(this, 8));
        sv.addView(info);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView name = Ui.text(info, title, 34, Ui.INK, true);
        ((LinearLayout.LayoutParams) name.getLayoutParams()).topMargin = 0;
        Ui.text(info, body, 21, Ui.INK, false);
        if (!instructions.isEmpty()) Ui.text(info, instructions, 18, Ui.MUTED, false);
        if (course != null && !course.finished && course.total > 0) courseBox(info, course, med);
        if (!advice.isEmpty()) Ui.text(info, advice, 15, Ui.WARN, false);
        if (observed) Ui.text(info, "Take this dose in front of the camera.", 16, Ui.WARN, true);
        if (med != null && med.doseForm() != DoseForm.TABLET) {
            final DoseForm form = med.doseForm();
            Ui.button(info, "How to use", Ui.SURFACE_VARIANT, v -> {
                android.widget.ScrollView hsv = new android.widget.ScrollView(this);
                LinearLayout box = Ui.vbox(this);
                int pad = Ui.dp(this, 16);
                box.setPadding(pad, 0, pad, pad);
                hsv.addView(box);
                Ui.howTo(box, form);
                new android.app.AlertDialog.Builder(this).setView(hsv).setPositiveButton(t("OK"), null).show();
            });
        }

        // Bottom: the big "Taken" (press and hold), then Snooze and Skip.
        LinearLayout actions = Ui.vbox(this);
        actions.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 22));
        root.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final String k = key;
        if (observed) {
            Button cam = Ui.mainButton(actions, "Take on camera", Ui.GOOD, v -> {
                stopVibration();
                Notifications.cancel(this, k);
                startActivity(ObserveActivity.intent(this, k));
                finish();
            });
            cam.getLayoutParams().height = Ui.dp(this, Ui.hasSecond("Take on camera") ? 88 : 76);
            cam.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        } else {
            HoldButton.add(actions, med == null || med.doseForm().observable ? "Taken" : "Done", Ui.GOOD, Ui.ON_STATUS, () -> act(DoseStatus.TAKEN));
        }
        LinearLayout row = Ui.row(actions);
        ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = Ui.dp(this, 2);
        int snooze = Store.get(this).settings.snoozeMinutes;
        Button sn = Ui.button(row, "", Ui.SURFACE_VARIANT, v -> act(DoseStatus.SNOOZED));
        sn.setText(Ui.twoLine(tf("Snooze %d min", snooze), "Later", 0.78f));
        Button skip = Ui.mainButton(row, "Skip", Ui.SURFACE_VARIANT, null);
        if (Ui.hasSecond("Later") || Ui.hasSecond("Skip")) {
            sn.getLayoutParams().height = Ui.dp(this, 68);
            skip.getLayoutParams().height = Ui.dp(this, 68);
        }
        final boolean antimicrobial = med != null && DrugInfo.isAntimicrobial(med.name);
        skip.setOnClickListener(v -> {
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

    /** "Day 3 of 5 · 9 doses left" with one bar per day of the course. */
    private void courseBox(LinearLayout parent, Course c, Medication med) {
        LinearLayout box = Ui.vbox(this);
        box.setBackground(Ui.rounded(this, Ui.PRIMARY_CONTAINER, 16));
        int p = Ui.dp(this, 14);
        box.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        parent.addView(box, Ui.matchWrap(this, 16));
        LinearLayout head = Ui.row(box);
        TextView day = Ui.text(head, tf("Day %d of %d", c.day, c.days), 17, Ui.ON_PRIMARY_CONTAINER, true);
        ((LinearLayout.LayoutParams) day.getLayoutParams()).topMargin = 0;
        TextView left = Ui.text(head, c.left == 1 ? t("1 dose left") : tf("%d doses left", c.left), 17, Ui.ON_PRIMARY_CONTAINER, true);
        left.setGravity(Gravity.END);
        ((LinearLayout.LayoutParams) left.getLayoutParams()).topMargin = 0;
        if (c.days <= 31) {
            LinearLayout bars = Ui.row(box);
            ((LinearLayout.LayoutParams) bars.getLayoutParams()).topMargin = Ui.dp(this, 10);
            for (int i = 0; i < c.days; i++) {
                View seg = new View(this);
                seg.setBackground(Ui.rounded(this, i < c.day ? Ui.PRIMARY : Ui.SURFACE, 4));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 8), 1);
                lp.leftMargin = lp.rightMargin = Ui.dp(this, c.days > 14 ? 1 : 3);
                bars.addView(seg, lp);
            }
        }
        if (med != null && DrugInfo.isAntimicrobial(med.name))
            Ui.text(box, "Finish the whole course, even if you feel better.", 15, Ui.ON_PRIMARY_CONTAINER, false);
    }


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
