package com.chemrob.medadherence.ui;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.chemrob.medadherence.core.FaceCrop;
import com.chemrob.medadherence.core.FaceMatch;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Face recognition on the phone: the SFace model (assets/face_sface_int8.tflite) turns an aligned
 * face into a 128-number "face fingerprint". Two fingerprints of the same person are close
 * (see {@link FaceMatch}). Runs on a background thread; nothing leaves the phone.
 */
public final class FaceRecognizer {
    public interface Callback { void onEmbedding(float[] embedding); }

    private static final String TAG = "MedAdherence", MODEL = "face_sface_int8.tflite";
    private static FaceRecognizer instance;
    private static boolean failed;

    private final Interpreter interpreter;
    private final ByteBuffer input;
    private final float[][] output = new float[1][128];
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    private FaceRecognizer(Interpreter interpreter) {
        this.interpreter = interpreter;
        input = ByteBuffer.allocateDirect(FaceMatch.SIZE * FaceMatch.SIZE * 3 * 4).order(ByteOrder.nativeOrder());
    }

    /** The shared recognizer, or null if the model cannot run on this phone. */
    public static synchronized FaceRecognizer get(Context c) {
        if (instance != null || failed) return instance;
        try (AssetFileDescriptor fd = c.getApplicationContext().getAssets().openFd(MODEL);
             FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            MappedByteBuffer model = in.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
            Interpreter.Options o = new Interpreter.Options();
            o.setNumThreads(2);
            instance = new FaceRecognizer(new Interpreter(model, o));
        } catch (Throwable e) {
            Log.w(TAG, "Face recognition model unavailable", e);
            failed = true;
        }
        return instance;
    }

    public static boolean available(Context c) { return get(c) != null; }

    /** True while a face is being processed; skip new requests meanwhile. */
    public boolean busy() { return busy; }

    /**
     * Fingerprint of the face at the given landmarks, delivered on the main thread (null on failure).
     * @param points five landmarks in upright-image coordinates, template order (see {@link FaceMatch#templateOrder})
     * @param rotation degrees the camera frame is turned clockwise to be upright
     */
    public void embedAsync(byte[] nv21, int w, int h, int rotation, double[] points, Callback cb) {
        double[] t = FaceMatch.alignTransform(points);
        if (t == null) { main.post(() -> cb.onEmbedding(null)); return; }
        busy = true;
        worker.execute(() -> {
            float[] e = null;
            try { e = embed(nv21, w, h, rotation, t); }
            catch (Throwable ex) { Log.w(TAG, "Face recognition failed", ex); }
            busy = false;
            final float[] res = e;
            main.post(() -> cb.onEmbedding(res));
        });
    }

    private synchronized float[] embed(byte[] nv21, int w, int h, int rotation, double[] t) {
        float[] rgb = FaceCrop.alignedRgb(nv21, w, h, rotation, t);
        if (rgb == null) return null;
        input.rewind();
        for (float v : rgb) input.putFloat(v);
        input.rewind();
        interpreter.run(input, output);
        return FaceMatch.normalize(output[0]);
    }
}
