package com.chemrob.medadherence.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Translations. Every text is written in English in the code and looked up here; a missing
 * translation falls back to English. Tables live in assets/i18n/&lt;lang&gt;.json, keyed by the
 * English text, and use %s / %d placeholders in the same order as the English.
 */
public final class I18n {
    private I18n() {}

    /** Supported languages: code and name in its own script. */
    public static final String[][] LANGUAGES = {
            {"en", "English"}, {"hi", "हिन्दी (Hindi)"}, {"bn", "বাংলা (Bengali)"}, {"as", "অসমীয়া (Assamese)"}};

    private static Map<String, String> table = Collections.emptyMap();
    private static String lang = "en";

    public static synchronized void set(String language, Map<String, String> translations) {
        lang = language == null ? "en" : language;
        table = translations == null ? Collections.emptyMap() : translations;
    }

    public static String lang() { return lang; }

    public static Locale locale() { return "en".equals(lang) ? Locale.getDefault() : Locale.forLanguageTag(lang + "-IN"); }

    /** Resolves "system" to a supported language code, from the phone's language. */
    public static String resolve(String setting, String phoneLanguage) {
        if (setting != null && !setting.equals("system")) for (String[] l : LANGUAGES) if (l[0].equals(setting)) return setting;
        for (String[] l : LANGUAGES) if (l[0].equals(phoneLanguage)) return phoneLanguage;
        return "en";
    }

    /** The translation of an English text. */
    public static String t(String english) {
        if (english == null) return null;
        String s = table.get(english);
        return s == null || s.isEmpty() ? english : s;
    }

    /** Translated format string with arguments ("%s missed %s"). */
    public static String tf(String english, Object... args) {
        String pattern = t(english);
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (java.util.IllegalFormatException e) {
            return String.format(Locale.ROOT, english, args); // a broken translation never crashes the app
        }
    }

    public static Map<String, String> parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Map<String, String> m = new HashMap<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.startsWith("_")) m.put(k, o.optString(k, ""));
        }
        return m;
    }
}
