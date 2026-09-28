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
        return data;
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
