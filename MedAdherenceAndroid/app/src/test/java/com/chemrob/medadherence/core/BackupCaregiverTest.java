package com.chemrob.medadherence.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

public class BackupCaregiverTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File phone() throws Exception {
        File dir = tmp.newFolder("files");
        Files.write(new File(dir, "medadherence.json").toPath(), "old".getBytes());
        Files.write(new File(dir, "med_1.jpg").toPath(), "photo".getBytes());
        File ev = new File(dir, "evidence/m1_20260928");
        assertTrue(ev.mkdirs());
        byte[] big = new byte[200_000]; // spans several encrypted chunks
        new Random(1).nextBytes(big);
        Files.write(new File(ev, "step1.jpg").toPath(), big);
        return dir;
    }

    private String json(File dir) {
        AppData d = new AppData();
        d.profile.name = "Asha";
        d.profile.facePhoto = new File(dir, "med_1.jpg").getAbsolutePath();
        return JsonCodec.toJson(d);
    }

    @Test public void plainBackupRoundTripMovesPaths() throws Exception {
        File dir = phone();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Backup.write(out, json(dir), dir, null);
        assertFalse(Backup.isEncrypted(out.toByteArray()));

        File newDir = tmp.newFolder("other", "files");
        File staging = new File(newDir, "restore_tmp");
        String restored = Backup.read(new ByteArrayInputStream(out.toByteArray()), null, staging, newDir);
        AppData back = JsonCodec.fromJson(restored);
        assertEquals("Asha", back.profile.name);
        assertEquals(new File(newDir, "med_1.jpg").getAbsolutePath(), back.profile.facePhoto);
        assertEquals("photo", new String(Files.readAllBytes(new File(staging, "med_1.jpg").toPath())));
        assertEquals(200_000, new File(staging, "evidence/m1_20260928/step1.jpg").length());
        assertFalse("data file is not copied as a photo", new File(staging, "medadherence.json").exists());
    }

    @Test public void encryptedBackup() throws Exception {
        File dir = phone();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Backup.write(out, json(dir), dir, "secret 123".toCharArray());
        byte[] b = out.toByteArray();
        assertTrue(Backup.isEncrypted(b));
        assertFalse("contents are not readable", new String(b, StandardCharsets.ISO_8859_1).contains("Asha"));

        File staging = tmp.newFolder("st");
        String restored = Backup.read(new ByteArrayInputStream(b), "secret 123".toCharArray(), staging, dir);
        assertEquals("Asha", JsonCodec.fromJson(restored).profile.name);
        assertArrayEquals(Files.readAllBytes(new File(dir, "evidence/m1_20260928/step1.jpg").toPath()),
                Files.readAllBytes(new File(staging, "evidence/m1_20260928/step1.jpg").toPath()));

        try {
            Backup.read(new ByteArrayInputStream(b), "wrong".toCharArray(), tmp.newFolder("st2"), dir);
            fail();
        } catch (Backup.BackupException e) { assertTrue(e.wrongPassword); }
        try {
            Backup.read(new ByteArrayInputStream(b), null, tmp.newFolder("st3"), dir);
            fail();
        } catch (Backup.BackupException e) { assertTrue(e.wrongPassword); }

        // A cut-off file is detected.
        byte[] cut = Arrays.copyOf(b, b.length - 100);
        try {
            Backup.read(new ByteArrayInputStream(cut), "secret 123".toCharArray(), tmp.newFolder("st4"), dir);
            fail();
        } catch (Backup.BackupException e) { assertFalse(e.wrongPassword); }
    }

    @Test public void rejectsOtherFiles() throws Exception {
        try {
            Backup.read(new ByteArrayInputStream("hello, not a zip".getBytes()), null, tmp.newFolder("x"), tmp.getRoot());
            fail();
        } catch (Backup.BackupException e) { assertFalse(e.wrongPassword); }
        assertEquals("{\"p\":\"/new/files/a.jpg\"}", Backup.rebase("{\"p\":\"/old/files/a.jpg\"}", "/old/files", "/new/files"));
        assertEquals("{\"p\":\"\\/new\\/files\\/a.jpg\"}", Backup.rebase("{\"p\":\"\\/old\\/files\\/a.jpg\"}", "/old/files", "/new/files"));
    }

    @Test public void caregiverAlerts() throws Exception {
        AppData d = new AppData();
        d.profile.name = "Asha Devi";
        Medication m = CoreTest.med("08:00, 20:00", 0, "2026-09-01", 1);
        m.name = "Metformin"; m.dose = "500 mg";
        d.medications.add(m);
        d.settings.graceMinutes = 120;
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 10, 30);

        // 08:00 became missed at 10:00.
        List<ScheduledDose> missed = Caregiver.newlyMissed(d, now.minusHours(1), now);
        assertEquals(1, missed.size());
        assertEquals(LocalDateTime.of(2026, 9, 28, 8, 0), missed.get(0).time);
        assertTrue(Caregiver.missedMessage(d, missed).contains("Asha has missed Metformin 500 mg (08:00)"));
        // Already reported: nothing new.
        assertTrue(Caregiver.newlyMissed(d, now, now.plusMinutes(5)).isEmpty());
        // Taken doses are not reported.
        ScheduleEngine.record(d, missed.get(0).key(), DoseStatus.TAKEN, LocalDateTime.of(2026, 9, 28, 8, 5));
        assertTrue(Caregiver.newlyMissed(d, now.minusHours(1), now).isEmpty());

        assertEquals(LocalDateTime.of(2026, 9, 28, 22, 1), Caregiver.nextCheck(d, now));
        assertEquals(LocalDateTime.of(2026, 9, 28, 21, 0), Caregiver.nextSummary(d, now));
        assertEquals(LocalDateTime.of(2026, 9, 29, 21, 0), Caregiver.nextSummary(d, now.withHour(22)));

        String sum = Caregiver.dailySummary(d, LocalDate.of(2026, 9, 27), now);
        assertTrue(sum, sum.contains("0 of 2 doses taken"));
        assertTrue(sum, sum.contains("Missed: Metformin 08:00, Metformin 20:00"));

        assertEquals("919876543210", Caregiver.whatsappNumber("98765 43210"));
        assertEquals("919876543210", Caregiver.whatsappNumber("+91 98765-43210"));
        assertEquals("919876543210", Caregiver.whatsappNumber("09876543210"));
        assertEquals("+919876543210", Caregiver.dialable("+91 (98765) 43210"));

        d.profile.emergencyPhone = "111111";
        assertEquals("111111", d.profile.caregiverNumber());
        d.profile.caregiverPhone = "222222";
        assertEquals("222222", d.profile.caregiverNumber());
        AppData back = JsonCodec.fromJson(JsonCodec.toJson(d));
        assertEquals("222222", back.profile.caregiverPhone);
        assertTrue(back.settings.caregiverMissedAlerts);
    }

    @Test public void translations() throws Exception {
        Map<String, String> hi = new HashMap<>();
        hi.put("Missed:", "छूटी:");
        hi.put("%d of %d doses taken.", "%d में से %d खुराक ली गईं।");
        hi.put("Broken %s", "टूटा %d");
        try {
            I18n.set("hi", hi);
            assertEquals("छूटी:", I18n.t("Missed:"));
            assertEquals("Untranslated", I18n.t("Untranslated"));
            assertEquals("3 में से 2 खुराक ली गईं।", I18n.tf("%d of %d doses taken.", 3, 2));
            assertEquals("Broken x", I18n.tf("Broken %s", "x")); // bad translation falls back to English
        } finally {
            I18n.set("en", null);
        }
        assertEquals("hi", I18n.resolve("system", "hi"));
        assertEquals("en", I18n.resolve("system", "fr"));
        assertEquals("as", I18n.resolve("as", "en"));
        assertEquals("{a=b}", I18n.parse("{\"a\":\"b\",\"_note\":\"x\"}").toString());
    }
}
