package com.chemrob.medadherence.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;

import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.core.Medication;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Spoken guidance using the phone's own text-to-speech, in the phone's language. Used once the
 * patient accepts a dose: it reads out what to take and, in observed mode, each camera step.
 * Can be switched off under Settings.
 */
public final class Voice {
    private Voice() {}

    private static TextToSpeech tts;
    private static boolean ready;
    private static final List<String> pending = new ArrayList<>();

    public static boolean enabled(Context c) { return Store.get(c).settings.voiceGuidance; }

    /** Speaks now, replacing anything still being said. */
    public static void say(Context c, String text) { speak(c, text, true); }

    /** Speaks after whatever is already queued. */
    public static void then(Context c, String text) { speak(c, text, false); }

    private static synchronized void speak(Context c, String text, boolean flush) {
        if (text == null || text.isEmpty() || !enabled(c)) return;
        if (tts == null) {
            tts = new TextToSpeech(c.getApplicationContext(), status -> {
                synchronized (Voice.class) {
                    ready = status == TextToSpeech.SUCCESS;
                    if (ready) {
                        tts.setLanguage(Locale.getDefault());
                        tts.setSpeechRate(0.9f); // a little slower: easier for older patients
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                        for (String p : pending) tts.speak(p, TextToSpeech.QUEUE_ADD, null, "ma" + p.hashCode());
                    }
                    pending.clear();
                }
            });
        }
        if (!ready) {
            if (flush) pending.clear();
            pending.add(text);
            return;
        }
        tts.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "ma" + System.nanoTime());
    }

    public static synchronized void stop() {
        pending.clear();
        if (tts != null && ready) tts.stop();
    }

    /** What to say when the patient taps "I took it" / "Take". */
    public static String takePhrase(Medication m) {
        StringBuilder sb = new StringBuilder("Please take ");
        sb.append(m.dose.isEmpty() ? "your " + m.name : m.dose + " of " + m.name).append(" now, with a glass of water. ");
        if (!m.instructions.isEmpty()) sb.append(m.instructions).append(". ");
        sb.append("Your dose has been recorded. Well done.");
        return sb.toString();
    }
}
