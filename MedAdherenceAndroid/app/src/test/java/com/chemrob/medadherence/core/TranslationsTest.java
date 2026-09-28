package com.chemrob.medadherence.core;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Checks the translation files: every text the code translates explicitly with t("...") or
 * tf("...") has a translation in each language, and every translation keeps the placeholders
 * (%s, %d, ...) of the English text in the same order, so tf() never breaks.
 */
public class TranslationsTest {
    private static final File ROOT = new File(System.getProperty("user.dir"));
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[-#+0,(]*\\d*(?:\\.\\d+)?[sdfx%]");

    private static File find(String rel) {
        for (File base = ROOT; base != null; base = base.getParentFile()) {
            for (String prefix : new String[]{"", "app/", "MedAdherenceAndroid/app/"}) {
                File f = new File(base, prefix + rel);
                if (f.exists()) return f;
            }
        }
        throw new AssertionError("not found: " + rel);
    }

    private static Map<String, String> table(String code) throws Exception {
        return I18n.parse(new String(Files.readAllBytes(find("src/main/assets/i18n/" + code + ".json").toPath()), StandardCharsets.UTF_8));
    }

    /**
     * Placeholders as "argument number: conversion", sorted by argument, so a translation may
     * reorder them with %2$s / %1$d as long as each argument keeps its type.
     */
    static List<String> placeholders(String s) {
        java.util.TreeMap<Integer, String> byArg = new java.util.TreeMap<>();
        Matcher m = PLACEHOLDER.matcher(s);
        int next = 1;
        while (m.find()) {
            String g = m.group();
            if (g.equals("%%")) continue;
            Matcher idx = Pattern.compile("^%(\\d+)\\$").matcher(g);
            int arg = idx.find() ? Integer.parseInt(idx.group(1)) : next++;
            byArg.put(arg, g.replaceFirst("^%\\d+\\$", "%"));
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : byArg.entrySet()) out.add(e.getKey() + ":" + e.getValue());
        return out;
    }

    /** Literal arguments of t("...") / tf("...") calls in the app's code ("a" + "b" joined). */
    static List<String> explicitKeys() throws IOException {
        List<String> keys = new ArrayList<>();
        Pattern call = Pattern.compile("\\b(?:I18n\\.)?tf?\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\"(?:\\s*\\+\\s*\"(?:[^\"\\\\]|\\\\.)*\")*)\\s*[,)]");
        List<File> files = new ArrayList<>();
        collect(find("src/main/java"), files);
        for (File f : files) {
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = call.matcher(src);
            while (m.find()) {
                StringBuilder sb = new StringBuilder();
                Matcher lit = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(m.group(1));
                while (lit.find()) sb.append(lit.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\u00b7", "·"));
                if (!keys.contains(sb.toString())) keys.add(sb.toString());
            }
        }
        return keys;
    }

    private static void collect(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    @Test public void everyLanguageIsCompleteAndSafe() throws Exception {
        List<String> keys = explicitKeys();
        assertTrue("found the translated texts in the code", keys.size() > 100);
        for (String[] lang : I18n.LANGUAGES) {
            if (lang[0].equals("en")) continue;
            Map<String, String> t = table(lang[0]);
            List<String> missing = new ArrayList<>();
            for (String k : keys) if (!t.containsKey(k) && !k.isEmpty()) missing.add(k);
            assertTrue(lang[0] + " is missing: " + missing, missing.isEmpty());
            for (Map.Entry<String, String> e : t.entrySet()) {
                assertFalse(lang[0] + ": empty translation for " + e.getKey(), e.getValue().trim().isEmpty());
                assertEquals(lang[0] + ": placeholders differ in \"" + e.getKey() + "\"",
                        placeholders(e.getKey()), placeholders(e.getValue()));
            }
        }
    }
}
