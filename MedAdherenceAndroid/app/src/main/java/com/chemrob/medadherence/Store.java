package com.chemrob.medadherence;

import android.content.Context;
import android.util.Log;

import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.JsonCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The regimen, dose log and settings, kept as one JSON file in the app's private storage.
 * Activities and receivers run in the same process and share this single instance.
 */
public final class Store {
    private static final String TAG = "MedAdherence";
    private static AppData data;

    private Store() {}

    private static File file(Context ctx) { return new File(ctx.getFilesDir(), "medadherence.json"); }

    public static File evidenceDir(Context ctx, String doseKey) {
        File dir = new File(new File(ctx.getFilesDir(), "evidence"), doseKey.replace('|', '_'));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static synchronized AppData get(Context ctx) {
        if (data != null) return data;
        File f = file(ctx);
        try {
            if (f.exists()) data = JsonCodec.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "Could not read data file; keeping a copy and starting fresh", e);
            //noinspection ResultOfMethodCallIgnored
            f.renameTo(new File(f.getPath() + ".corrupt-" + System.currentTimeMillis()));
        }
        if (data == null) data = new AppData();
        com.chemrob.medadherence.ui.Lang.apply(ctx, data);
        return data;
    }

    /**
     * Replaces everything with a restored backup: moves the staged photos into app storage and
     * installs the restored data file. The JSON is checked before anything is changed.
     */
    public static synchronized void restore(Context ctx, String json, File staging) throws Exception {
        AppData restored = JsonCodec.fromJson(json); // throws if the data is not valid
        move(staging, ctx.getFilesDir());
        deleteTree(staging);
        data = restored;
        save(ctx);
        com.chemrob.medadherence.ui.Lang.apply(ctx, data);
    }

    private static void move(File from, File to) {
        File[] list = from.listFiles();
        if (list == null) return;
        for (File f : list) {
            File dest = new File(to, f.getName());
            if (f.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                dest.mkdirs();
                move(f, dest);
            } else {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                if (!f.renameTo(dest)) Log.w(TAG, "Could not restore " + f);
            }
        }
    }

    public static void deleteTree(File f) {
        File[] list = f.listFiles();
        if (list != null) for (File c : list) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static synchronized void save(Context ctx) {
        if (data == null) return;
        File f = file(ctx);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(JsonCodec.toJson(data).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            Log.e(TAG, "Could not save data", e);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }
}
