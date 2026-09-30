package com.chemrob.medadherence.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.core.Emergency;
import com.chemrob.medadherence.core.Profile;

import java.util.Locale;

import static com.chemrob.medadherence.core.I18n.t;
import static com.chemrob.medadherence.core.I18n.tf;

/**
 * SOS: after a short countdown (so an accidental tap can be cancelled) it calls the emergency
 * contact directly, plays an automated voice message through the loudspeaker so it can be heard on
 * the call, and then opens the messaging app with the patient's details and location ready to send.
 *
 * Android does not let ordinary apps inject audio into a phone call, so the voice is played on the
 * speaker for the call's microphone to pick up. On most phones the other person hears it, but some
 * phones filter it out; the SMS carries the full message.
 */
public class EmergencyActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final String TAG = "MedAdherence";
    private static final int REQ_PERMS = 21;
    private static final long VOICE_DELAY_MS = 9000;   // give the call time to be answered
    private static final int VOICE_REPEATS = 4;

    private static final int RED = Color.parseColor("#D0342F");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView big, status;
    private int left = Emergency.COUNTDOWN_SECONDS;
    private boolean cancelled, sent;
    private TextToSpeech tts;
    private boolean ttsReady;
    private Profile profile;
    private LinearLayout root;
    private android.widget.Button cancelButton;
    private String smsText;
    private boolean voiceEnglish = true;
    private boolean callStarted, smsOpened;

    public static Intent intent(Context c) { return new Intent(c, EmergencyActivity.class); }

    /** Permissions the SOS button needs; asked for up front so an emergency is not delayed by dialogs. */
    public static String[] permissions() {
        return new String[]{Manifest.permission.CALL_PHONE,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        profile = Store.get(this).profile;

        root = Ui.vbox(this);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(RED);
        int p = Ui.dp(this, 24);
        root.setPadding(p, Ui.dp(this, 48), p, p);
        TextView title = Ui.text(root, "Emergency", 18, Color.WHITE, true);
        title.setText(title.getText().toString().toUpperCase(com.chemrob.medadherence.core.I18n.locale()));
        title.setLetterSpacing(0.12f);
        title.setGravity(Gravity.CENTER);
        String number = Emergency.number(profile);
        String who = profile.emergencyName.trim().isEmpty() ? (number == null ? "" : number) : profile.emergencyName.trim();
        status = Ui.text(root, number == null ? t("No emergency contact is set.") : tf("Calling %s in", who), 30, Color.WHITE, true);
        status.setGravity(Gravity.CENTER);

        // The countdown inside a ring.
        android.widget.FrameLayout ring = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable rg = new android.graphics.drawable.GradientDrawable();
        rg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        rg.setStroke(Ui.dp(this, 10), Color.argb(90, 255, 255, 255));
        ring.setBackground(rg);
        big = new TextView(this);
        big.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 110);
        big.setTextColor(Color.WHITE);
        big.setTypeface(Ui.medium(), Typeface.BOLD);
        big.setGravity(Gravity.CENTER);
        big.setIncludeFontPadding(false);
        ring.addView(big, new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Ui.dp(this, 220), Ui.dp(this, 220));
        rl.topMargin = Ui.dp(this, 28);
        root.addView(ring, rl);
        if (number != null) {
            TextView who2 = Ui.text(root, "", 18, Color.WHITE, false);
            who2.setText(tf("%s · %s. The speaker will play a message asking for help.", who, number));
            who2.setGravity(Gravity.CENTER);
            ((LinearLayout.LayoutParams) who2.getLayoutParams()).topMargin = Ui.dp(this, 24);
        }
        root.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1));
        cancelButton = Ui.mainButton(root, "Cancel", Color.WHITE, v -> cancel());
        cancelButton.setTextColor(RED);
        cancelButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
        cancelButton.setBackground(Ui.rounded(this, Color.WHITE, 22));
        cancelButton.getLayoutParams().height = Ui.dp(this, Ui.hasSecond("Cancel") ? 92 : 80);
        if (number != null) {
            TextView after = Ui.text(root, "After the call, a message with your location opens for you to send.", 15, Color.WHITE, false);
            after.setGravity(Gravity.CENTER);
        }
        setContentView(root);

        if (number == null) {
            big.setText("!");
            status.setText(t("Add an emergency contact in your profile first."));
            return;
        }
        tts = new TextToSpeech(this, this);
        if (!hasAll()) requestPermissions(permissions(), REQ_PERMS);
        else tick();
    }

    private boolean hasAll() {
        return checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_PERMS && !cancelled) tick(); // go ahead with whatever was granted
    }

    private void tick() {
        if (cancelled || sent) return;
        if (left <= 0) { send(); return; }
        big.setText(String.valueOf(left));
        left--;
        handler.postDelayed(this::tick, 1000);
    }

    private void cancel() {
        cancelled = true;
        handler.removeCallbacksAndMessages(null);
        if (tts != null) tts.stop();
        finish();
    }

    /**
     * Calls straight away (no tap needed, so it works even if the patient cannot press anything),
     * with the voice message on the loudspeaker. When the call ends and the patient is back here,
     * the messaging app opens with the emergency text and location filled in; they tap Send.
     * The app does not send SMS itself: that permission makes Play Protect block sideloaded apps.
     */
    private void send() {
        sent = true;
        String number = Emergency.number(profile);
        big.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 60);
        big.setText("SOS");
        Location loc = lastLocation();
        smsText = Emergency.smsText(profile, loc == null ? null : loc.getLatitude(), loc == null ? null : loc.getLongitude());

        boolean canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        status.setText(t(canCall ? "Calling now..." : "Tap the call button to ring them."));
        Intent call = new Intent(canCall ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + number));
        try {
            startActivity(call);
            callStarted = true;
        } catch (Exception e) {
            Log.e(TAG, "Call failed", e);
            openSms();
        }
        showAfterButtons(number);
        handler.postDelayed(this::speak, VOICE_DELAY_MS);
    }

    /** Opens the messaging app with the emergency text and location filled in. */
    private void openSms() {
        if (smsText == null) return;
        smsOpened = true;
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Emergency.number(profile))).putExtra("sms_body", smsText);
        try { startActivity(i); } catch (Exception e) { Log.e(TAG, "No messaging app", e); }
    }

    private void showAfterButtons(String number) {
        cancelButton.setVisibility(android.view.View.GONE);
        Ui.button(root, "Send location by SMS", Color.WHITE, v -> openSms()).setTextColor(RED);
        Ui.button(root, "Call again", Color.WHITE, v -> {
            Intent call = new Intent(checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                    ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            try { startActivity(call); } catch (Exception ignored) { }
        }).setTextColor(RED);
        Ui.button(root, "Close", Color.parseColor("#8C1D18"), v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the call: offer the SMS with the location straight away (once).
        if (sent && callStarted && !smsOpened) {
            handler.removeCallbacksAndMessages(null);
            if (tts != null) tts.stop();
            status.setText(t("Now send your location: tap Send in the message."));
            openSms();
        }
    }

    @Override
    public void onInit(int st) {
        ttsReady = st == TextToSpeech.SUCCESS;
        if (ttsReady) {
            // The app's language if the phone can speak it, otherwise English.
            Locale want = com.chemrob.medadherence.core.I18n.locale();
            voiceEnglish = com.chemrob.medadherence.core.I18n.lang().equals("en") || tts.isLanguageAvailable(want) < TextToSpeech.LANG_AVAILABLE;
            tts.setLanguage(voiceEnglish ? (com.chemrob.medadherence.core.I18n.lang().equals("en") ? Locale.getDefault() : Locale.ENGLISH) : want);
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        }
    }

    /** Speaks the help message loudly through the speaker so the call's microphone carries it. */
    @SuppressWarnings("deprecation")
    private void speak() {
        if (!ttsReady || cancelled) return;
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null) {
            try {
                am.setSpeakerphoneOn(true);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            } catch (Exception e) {
                Log.w(TAG, "Could not switch to speaker", e);
            }
        }
        String text = Emergency.voiceText(profile, voiceEnglish);
        for (int i = 0; i < VOICE_REPEATS; i++) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "sos" + i);
            tts.playSilentUtterance(1500, TextToSpeech.QUEUE_ADD, "gap" + i);
        }
    }

    @SuppressWarnings("MissingPermission")
    private Location lastLocation() {
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return null;
        LocationManager lm = getSystemService(LocationManager.class);
        if (lm == null) return null;
        Location best = null;
        for (String provider : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) { }
        }
        return best;
    }

    @Override
    public void onBackPressed() { if (!sent) cancel(); else super.onBackPressed(); }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
