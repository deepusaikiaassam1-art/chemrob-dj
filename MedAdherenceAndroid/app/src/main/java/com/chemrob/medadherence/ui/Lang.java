package com.chemrob.medadherence.ui;

import android.content.Context;
import android.util.Log;

import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.I18n;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Loads the translation tables from assets/i18n and switches the app's language. */
public final class Lang {
    private Lang() {}

    private static final Map<String, Map<String, String>> cache = new HashMap<>();

    /** The table for a language code; empty for English or if the file is missing. */
    public static synchronized Map<String, String> table(Context c, String code) {
        if ("en".equals(code)) return Collections.emptyMap();
        Map<String, String> t = cache.get(code);
        if (t != null) return t;
        try (InputStream in = c.getApplicationContext().getAssets().open("i18n/" + code + ".json")) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            t = I18n.parse(new String(o.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w("MedAdherence", "No translations for " + code, e);
            t = Collections.emptyMap();
        }
        cache.put(code, t);
        return t;
    }

    /** Applies the language chosen in settings ("system" = the phone's language, if supported). */
    public static void apply(Context c, AppData d) {
        String code = I18n.resolve(d.settings.language, Locale.getDefault().getLanguage());
        String second = d.settings.secondLanguage == null ? "" : d.settings.secondLanguage;
        if (!second.equals(I18n.secondLang())) I18n.setSecond(second, second.isEmpty() ? null : table(c, second));
        if (code.equals(I18n.lang()) && (code.equals("en") || cache.containsKey(code))) return;
        I18n.set(code, table(c, code));
        Voice.languageChanged();
    }
}
