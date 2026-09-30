package com.chemrob.medadherence.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;

import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.core.I18n;
import com.chemrob.medadherence.core.Medication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Spoken guidance using the phone's own text-to-speech, in the app's language. Used once the
 * patient accepts a dose: it reads out what to take and, in observed mode, each camera step.
 * If the phone has no voice for the chosen language it falls back (Assamese to Bengali, then
 * Hindi; any language to English) and speaks the text in the language it can pronounce.
 * Can be switched off under Settings.
 */
public final class Voice {
    private Voice() {}

    private static TextToSpeech tts;
    private static Context app;
    private static boolean ready;
    private static volatile boolean languageSet; // volatile, not locked: Store may clear it while holding its own lock
    private static Map<String, String> table = Collections.emptyMap();
    private static final List<Object[]> pending = new ArrayList<>(); // {template, args}

    public static boolean enabled(Context c) { return Store.get(c).settings.voiceGuidance; }

    /** Speaks now, replacing anything still being said. The template is English; it is translated. */
    public static void say(Context c, String template, Object... args) { speak(c, template, args, true); }

    /** Speaks after whatever is already queued. */
    public static void then(Context c, String template, Object... args) { speak(c, template, args, false); }

    /** Speaks text as it is (a name or instructions the user typed). */
    public static void sayRaw(Context c, String text) { speak(c, "%s", new Object[]{text}, false); }

    /** The app's language changed: pick the voice again before the next sentence. */
    static void languageChanged() { languageSet = false; }

    /** Translation into the language actually being spoken. */
    static synchronized String phrase(String template, Object... args) {
        String p = table.get(template);
        if (p == null || p.isEmpty()) p = template;
        Object[] a = args.clone();
        for (int i = 0; i < a.length; i++)
            if (a[i] instanceof Label) { String k = ((Label) a[i]).english; String v = table.get(k); a[i] = v == null || v.isEmpty() ? k : v; }
        try { return String.format(Locale.ROOT, p, a); }
        catch (java.util.IllegalFormatException e) { return String.format(Locale.ROOT, template, a); }
    }

    /** An English label passed as an argument, translated along with the sentence. */
    public static final class Label {
        final String english;
        public Label(String english) { this.english = english; }
        @Override public String toString() { return english; }
    }

    /** "a, b, c" with each English label translated. */
    public static String list(List<String> englishLabels) {
        List<String> out = new ArrayList<>();
        for (String s : englishLabels) out.add(phrase("%s", new Label(s)));
        return String.join(", ", out);
    }

    private static synchronized void chooseLanguage() {
        if (languageSet || tts == null || !ready) return;
        String want = I18n.lang();
        String[] chain = want.equals("as") ? new String[]{"as", "bn", "hi", "en"} : want.equals("en") ? new String[]{"en"} : new String[]{want, "en"};
        for (String code : chain) {
            Locale loc = code.equals("en") ? new Locale("en", "IN") : Locale.forLanguageTag(code + "-IN");
            int ok = tts.isLanguageAvailable(loc);
            if (ok >= TextToSpeech.LANG_AVAILABLE || code.equals("en")) {
                if (ok < TextToSpeech.LANG_AVAILABLE) loc = Locale.ENGLISH;
                tts.setLanguage(loc);
                table = Lang.table(app, code);
                break;
            }
        }
        languageSet = true;
    }

    private static synchronized void speak(Context c, String template, Object[] args, boolean flush) {
        if (template == null || template.isEmpty() || !enabled(c)) return;
        if (tts == null) {
            app = c.getApplicationContext();
            tts = new TextToSpeech(app, status -> {
                synchronized (Voice.class) {
                    ready = status == TextToSpeech.SUCCESS;
                    if (ready) {
                        chooseLanguage();
                        tts.setSpeechRate(0.9f); // a little slower: easier for older patients
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                        for (Object[] p : pending) {
                            String text = phrase((String) p[0], (Object[]) p[1]);
                            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "ma" + text.hashCode());
                        }
                    }
                    pending.clear();
                }
            });
        }
        if (!ready) {
            if (flush) pending.clear();
            pending.add(new Object[]{template, args});
            return;
        }
        chooseLanguage();
        tts.speak(phrase(template, args), flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "ma" + System.nanoTime());
    }

    public static synchronized void stop() {
        pending.clear();
        if (tts != null && ready) tts.stop();
    }

    /** What to say when the patient taps "I took it" / "Take". */
    public static void sayTake(Context c, Medication m) {
        com.chemrob.medadherence.core.DoseForm f = m.doseForm();
        if (f == com.chemrob.medadherence.core.DoseForm.TABLET || f == com.chemrob.medadherence.core.DoseForm.LIQUID) {
            if (m.dose.isEmpty()) say(c, "Please take your %s now, with a glass of water.", m.name);
            else say(c, "Please take %s of %s now, with a glass of water.", m.dose, m.name);
        } else {
            say(c, "Time to use your %s.", m.name);
            // Inhalers and drops are easy to get wrong: read the steps out.
            if (f == com.chemrob.medadherence.core.DoseForm.INHALER || f.hasSide())
                for (String step : f.howTo) then(c, step);
        }
        if (!m.instructions.isEmpty()) sayRaw(c, m.instructions);
        then(c, "Your dose has been recorded. Well done.");
    }
}
