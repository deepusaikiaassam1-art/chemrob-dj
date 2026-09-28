package com.chemrob.medadherence.core;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Backup file: a zip of the data file and every photo (medicine photos, the face scan and
 * observed-dose photos). With a password the zip is encrypted with AES-256-GCM, in 64 KB chunks so
 * large backups never have to fit in memory; the key comes from the password via PBKDF2.
 *
 * Encrypted layout: "MABK1" | salt (16) | nonce prefix (8) | chunks, each: length (4) | ciphertext.
 * Chunk i uses nonce = prefix + i and the associated data byte 1 on the last chunk, 0 otherwise,
 * so a cut-off or reordered file is detected.
 */
public final class Backup {
    private Backup() {}

    public static final String DATA_ENTRY = "medadherence.json", META_ENTRY = "meta.json", FILES = "files/";
    static final byte[] MAGIC = "MABK1".getBytes(StandardCharsets.US_ASCII);
    static final int CHUNK = 64 * 1024, ITERATIONS = 150_000;

    public static class BackupException extends IOException {
        public final boolean wrongPassword;
        public BackupException(String msg, boolean wrongPassword) { super(msg); this.wrongPassword = wrongPassword; }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes a backup of {@code json} and every file under {@code filesDir} except the data file itself.
     * @param password null or empty = not encrypted
     */
    public static void write(OutputStream out, String json, File filesDir, char[] password) throws IOException {
        OutputStream sink = password == null || password.length == 0 ? out : new EncryptingStream(out, password);
        ZipOutputStream zip = new ZipOutputStream(sink);
        try {
            JSONObject meta = new JSONObject();
            meta.put("format", 1);
            meta.put("filesDir", filesDir.getAbsolutePath());
            put(zip, META_ENTRY, meta.toString().getBytes(StandardCharsets.UTF_8));
            put(zip, DATA_ENTRY, json.getBytes(StandardCharsets.UTF_8));
            byte[] buf = new byte[16 * 1024];
            for (File f : files(filesDir)) {
                zip.putNextEntry(new ZipEntry(FILES + relative(filesDir, f)));
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        zip.finish();
        if (sink instanceof EncryptingStream) ((EncryptingStream) sink).finishChunks();
        zip.flush();
    }

    /** Files that go into a backup: everything in app storage except the data file and temp copies. */
    public static List<File> files(File dir) {
        List<File> out = new ArrayList<>();
        collect(dir, dir, out);
        return out;
    }

    private static void collect(File root, File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        Arrays.sort(list);
        for (File f : list) {
            if (f.isDirectory()) {
                if (!f.getName().equals("restore_tmp")) collect(root, f, out);
            } else if (!(dir.equals(root) && f.getName().startsWith("medadherence.json"))) out.add(f);
        }
    }

    private static String relative(File root, File f) {
        return root.toURI().relativize(f.toURI()).getPath();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    // ------------------------------------------------------------------ reading

    /** True if the first bytes say this is an encrypted backup. */
    public static boolean isEncrypted(byte[] head) {
        if (head == null || head.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (head[i] != MAGIC[i]) return false;
        return true;
    }

    /**
     * Reads a backup: files are extracted into {@code staging} (created), and the data file is
     * returned with its paths moved from the backed-up phone's folder to {@code targetDir}.
     * @param in the whole backup, from the first byte
     */
    public static String read(InputStream in, char[] password, File staging, File targetDir) throws IOException {
        InputStream buffered = in.markSupported() ? in : new java.io.BufferedInputStream(in);
        buffered.mark(MAGIC.length);
        byte[] head = new byte[MAGIC.length];
        int got = readFully(buffered, head);
        buffered.reset();
        InputStream plain;
        if (got == MAGIC.length && isEncrypted(head)) {
            if (password == null || password.length == 0) throw new BackupException("This backup has a password.", true);
            plain = new DecryptingStream(buffered, password);
        } else plain = buffered;

        String json = null, oldDir = null;
        //noinspection ResultOfMethodCallIgnored
        staging.mkdirs();
        String stagingPath = staging.getCanonicalPath() + File.separator;
        ZipInputStream zip = new ZipInputStream(plain);
        ZipEntry e;
        byte[] buf = new byte[16 * 1024];
        boolean any = false;
        try {
            while ((e = zip.getNextEntry()) != null) {
                any = true;
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.equals(DATA_ENTRY)) json = new String(readAll(zip), StandardCharsets.UTF_8);
                else if (name.equals(META_ENTRY)) {
                    try { oldDir = new JSONObject(new String(readAll(zip), StandardCharsets.UTF_8)).optString("filesDir", null); }
                    catch (org.json.JSONException ex) { throw new BackupException("The backup file is damaged.", false); }
                } else if (name.startsWith(FILES)) {
                    File out = new File(staging, name.substring(FILES.length()));
                    // Never write outside the staging folder ("zip slip").
                    if (!out.getCanonicalPath().startsWith(stagingPath)) throw new BackupException("The backup file is damaged.", false);
                    //noinspection ResultOfMethodCallIgnored
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zip.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
            }
        } catch (java.util.zip.ZipException ex) {
            throw new BackupException("This is not a MedAdherence backup file.", false);
        }
        if (plain instanceof DecryptingStream) ((DecryptingStream) plain).drain();
        if (!any || json == null) throw new BackupException("This is not a MedAdherence backup file.", false);
        return oldDir == null ? json : rebase(json, oldDir, targetDir.getAbsolutePath());
    }

    /** Points file paths saved on the old phone at the same files on this phone. */
    public static String rebase(String json, String oldDir, String newDir) {
        if (oldDir.equals(newDir)) return json;
        String a = oldDir.endsWith("/") ? oldDir : oldDir + "/", b = newDir.endsWith("/") ? newDir : newDir + "/";
        return json.replace(a, b).replace(a.replace("/", "\\/"), b.replace("/", "\\/"));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    // ------------------------------------------------------------------ encryption

    static SecretKeySpec key(char[] password, byte[] salt) throws GeneralSecurityException {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] k = f.generateSecret(new PBEKeySpec(password, salt, ITERATIONS, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    static byte[] nonce(byte[] prefix, int counter) {
        return ByteBuffer.allocate(12).put(prefix).putInt(counter).array();
    }

    /** Buffers plaintext into chunks and writes each one encrypted. */
    static final class EncryptingStream extends FilterOutputStream {
        private final SecretKeySpec key;
        private final byte[] prefix = new byte[8], buf = new byte[CHUNK];
        private int len, counter;
        private boolean finished;

        EncryptingStream(OutputStream out, char[] password) throws IOException {
            super(out);
            SecureRandom rnd = new SecureRandom();
            byte[] salt = new byte[16];
            rnd.nextBytes(salt);
            rnd.nextBytes(prefix);
            try { key = key(password, salt); } catch (GeneralSecurityException e) { throw new IOException(e); }
            out.write(MAGIC);
            out.write(salt);
            out.write(prefix);
        }

        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

        @Override public void write(byte[] b, int off, int n) throws IOException {
            while (n > 0) {
                if (len == CHUNK) emit(false);
                int k = Math.min(n, CHUNK - len);
                System.arraycopy(b, off, buf, len, k);
                len += k; off += k; n -= k;
            }
        }

        private void emit(boolean last) throws IOException {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce(prefix, counter++)));
                c.updateAAD(new byte[]{(byte) (last ? 1 : 0)});
                byte[] ct = c.doFinal(buf, 0, len);
                out.write(ByteBuffer.allocate(4).putInt(ct.length).array());
                out.write(ct);
                len = 0;
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        /** Writes the final chunk (possibly empty). Nothing may be written afterwards. */
        void finishChunks() throws IOException {
            if (finished) return;
            finished = true;
            emit(true);
            out.flush();
        }

        @Override public void flush() throws IOException { out.flush(); }

        @Override public void close() throws IOException { finishChunks(); out.close(); }
    }

    /** Reads chunks written by {@link EncryptingStream}, checking each one. */
    static final class DecryptingStream extends InputStream {
        private final DataInputStream in;
        private final SecretKeySpec key;
        private final byte[] prefix = new byte[8];
        private byte[] chunk = new byte[0];
        private int pos, counter;
        private boolean last;

        DecryptingStream(InputStream raw, char[] password) throws IOException {
            in = new DataInputStream(raw);
            byte[] magic = new byte[MAGIC.length], salt = new byte[16];
            in.readFully(magic);
            in.readFully(salt);
            in.readFully(prefix);
            try { key = key(password, salt); } catch (GeneralSecurityException e) { throw new IOException(e); }
        }

        private boolean next() throws IOException {
            if (last) return false;
            int n;
            try { n = in.readInt(); } catch (EOFException e) { throw new BackupException("The backup file is incomplete.", false); }
            if (n < 16 || n > CHUNK + 16) throw new BackupException("The backup file is damaged.", false);
            byte[] ct = new byte[n];
            try { in.readFully(ct); } catch (EOFException e) { throw new BackupException("The backup file is incomplete.", false); }
            int idx = counter++;
            for (int flag = 0; flag <= 1; flag++) {
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce(prefix, idx)));
                    c.updateAAD(new byte[]{(byte) flag});
                    chunk = c.doFinal(ct);
                    pos = 0;
                    last = flag == 1;
                    return true;
                } catch (javax.crypto.AEADBadTagException e) {
                    // try the other flag; if neither fits, the password is wrong (or the file was changed)
                } catch (GeneralSecurityException e) {
                    throw new IOException(e);
                }
            }
            throw new BackupException(idx == 0 ? "Wrong password." : "The backup file is damaged.", idx == 0);
        }

        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int n) throws IOException {
            while (pos >= chunk.length) if (!next()) return -1;
            int k = Math.min(n, chunk.length - pos);
            System.arraycopy(chunk, pos, b, off, k);
            pos += k;
            return k;
        }

        /** Reads to the end so a cut-off file is reported even if the zip looked complete. */
        void drain() throws IOException {
            byte[] b = new byte[8192];
            while (read(b, 0, b.length) >= 0) { /* keep going */ }
        }
    }
}
