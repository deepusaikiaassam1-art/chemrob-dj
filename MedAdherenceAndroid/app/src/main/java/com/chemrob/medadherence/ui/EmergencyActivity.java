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
        root.setBackgroundColor(Color.parseColor("#B3261E"));
        int p = Ui.dp(this, 24);
        root.setPadding(p, Ui.dp(this, 56), p, p);
        TextView title = Ui.text(root, "Emergency", 34, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        String number = Emergency.number(profile);
        String who = profile.emergencyName.trim().isEmpty() ? (number == null ? "" : number) : profile.emergencyName.trim();
        status = Ui.text(root, number == null ? "No emergency contact is set." : "Calling " + who + " in", 20, Color.WHITE, false);
        status.setGravity(Gravity.CENTER);
        big = Ui.text(root, "", 120, Color.WHITE, true);
        big.setGravity(Gravity.CENTER);
        big.setTypeface(Typeface.create(Ui.medium(), Typeface.BOLD));
        root.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1));
        cancelButton = Ui.button(root, "Cancel", Color.WHITE, v -> cancel());
        cancelButton.setTextColor(Color.parseColor("#B3261E"));
        setContentView(root);

        if (number == null) {
            big.setText("!");
            status.setText("Add an emergency contact in your profile first.");
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
        big.setText("SOS");
        Location loc = lastLocation();
        smsText = Emergency.smsText(profile, loc == null ? null : loc.getLatitude(), loc == null ? null : loc.getLongitude());

        boolean canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        status.setText(canCall ? "Calling now..." : "Tap the call button to ring them.");
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
        Ui.button(root, "Send location by SMS", Color.WHITE, v -> openSms()).setTextColor(Color.parseColor("#B3261E"));
        Ui.button(root, "Call again", Color.WHITE, v -> {
            Intent call = new Intent(checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                    ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            try { startActivity(call); } catch (Exception ignored) { }
        }).setTextColor(Color.parseColor("#B3261E"));
        Ui.button(root, "Close", Color.parseColor("#8C1D18"), v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the call: offer the SMS with the location straight away (once).
        if (sent && callStarted && !smsOpened) {
            handler.removeCallbacksAndMessages(null);
            if (tts != null) tts.stop();
            status.setText("Now send your location: tap Send in the message.");
            openSms();
        }
    }

    @Override
    public void onInit(int st) {
        ttsReady = st == TextToSpeech.SUCCESS;
        if (ttsReady) {
            tts.setLanguage(Locale.getDefault());
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
        String text = Emergency.voiceText(profile);
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
