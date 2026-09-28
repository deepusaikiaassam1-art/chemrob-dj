package com.chemrob.medalarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/**
 * Full-screen "your phone is ringing" screen for a due dose. Shown over the lock screen by the
 * notification's full-screen intent. The ringtone itself is the insistent alarm notification, so
 * it keeps sounding until the patient chooses Taken / Snooze / Skip (or it times out and repeats).
 */
public class AlarmActivity extends Activity {
    static final String EXTRA_VERIFY_NOW = "verify_now";

    private String doseKey;
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverLockScreen();
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        doseKey = intent.getStringExtra(MedAlarmPlugin.EXTRA_KEY);
        JSONObject a = doseKey == null ? null : MedAlarmPlugin.alarm(this, doseKey);
        if (a == null) { finish(); return; } // already handled

        if (intent.getBooleanExtra(EXTRA_VERIFY_NOW, false)) {
            MedAlarmPlugin.launchUnityForVerification(this, doseKey);
            finish();
            return;
        }
        buildUi(a);
        startVibration();
    }

    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void buildUi(JSONObject a) {
        final boolean observed = a.optBoolean("observed", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(13, 71, 97));
        int pad = dp(24);
        root.setPadding(pad, dp(64), pad, pad);

        root.addView(text(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()), 56, true));
        root.addView(text("Time for your medicine", 20, false));
        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, dp(32)));
        root.addView(text(a.optString("title", ""), 30, true));
        root.addView(text(a.optString("body", ""), 18, false));
        if (observed) root.addView(text("\nThis dose must be taken in front of the camera.", 16, false));

        View fill = new View(this);
        root.addView(fill, new LinearLayout.LayoutParams(1, 0, 1f));

        if (observed) {
            root.addView(button("Take on camera", Color.rgb(46, 160, 67), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    stopVibration();
                    MedAlarmPlugin.launchUnityForVerification(AlarmActivity.this, doseKey);
                    finish();
                }
            }));
        } else {
            root.addView(button("I have taken it", Color.rgb(46, 160, 67), actionListener(MedAlarmPlugin.ACTION_TAKEN)));
        }
        root.addView(button("Snooze " + MedAlarmPlugin.DEFAULT_SNOOZE_MINUTES + " min", Color.rgb(240, 160, 30),
                actionListener(MedAlarmPlugin.ACTION_SNOOZE)));
        root.addView(button("Skip this dose", Color.rgb(120, 130, 140), actionListener(MedAlarmPlugin.ACTION_SKIP)));

        setContentView(root);
    }

    private View.OnClickListener actionListener(final String action) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopVibration();
                MedAlarmPlugin.handleAction(AlarmActivity.this, action, doseKey);
                finish();
            }
        };
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private void startVibration() {
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = {0, 700, 500};
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        else vibrator.vibrate(pattern, 0);
        // Never vibrate forever if nobody is there.
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override public void run() { stopVibration(); }
        }, MedAlarmPlugin.RING_TIMEOUT_MS);
    }

    private void stopVibration() {
        if (vibrator != null) vibrator.cancel();
    }

    @Override
    protected void onDestroy() {
        stopVibration();
        super.onDestroy();
    }
}
