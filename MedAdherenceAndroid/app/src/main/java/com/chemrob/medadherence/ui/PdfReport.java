package com.chemrob.medadherence.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.chemrob.medadherence.core.AdherenceCalculator;
import com.chemrob.medadherence.core.AdherenceStats;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.Appointment;
import com.chemrob.medadherence.core.DoseRecord;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.Medication;
import com.chemrob.medadherence.core.Profile;
import com.chemrob.medadherence.core.ScheduleEngine;
import com.chemrob.medadherence.core.ScheduledDose;
import com.chemrob.medadherence.core.TimeUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.chemrob.medadherence.core.I18n.t;
import static com.chemrob.medadherence.core.I18n.tf;

/**
 * A printable A4 adherence report for the doctor or pharmacist: patient details, summary figures,
 * a daily chart, a table per medicine, missed and late doses, and the next visit. Drawn with
 * Android's own PDF support; nothing is uploaded.
 */
public final class PdfReport {
    private static final int W = 595, H = 842, M = 40; // A4 in points, margin
    private static final int INK = Color.rgb(26, 28, 41), MUTED = Color.rgb(94, 98, 117), LINE = Color.rgb(220, 222, 234);
    private static final int PRIMARY = Color.rgb(79, 70, 229), TINT = Color.rgb(236, 238, 248);
    private static final int GOOD = Color.rgb(30, 158, 99), WARN = Color.rgb(199, 124, 0), BAD = Color.rgb(217, 63, 63);

    private final PdfDocument doc = new PdfDocument();
    private PdfDocument.Page page;
    private Canvas c;
    private float y;
    private int pageNo;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String footer;

    private PdfReport(String footer) { this.footer = footer; }

    private static int color(double pct) { return pct >= 80 ? GOOD : pct >= 50 ? WARN : BAD; }

    /**
     * Writes the report for [from, now] to a file in {@code dir} and returns it.
     */
    public static File write(Context ctx, AppData d, LocalDateTime from, LocalDateTime now, File dir) throws IOException {
        AdherenceCalculator.Report r = AdherenceCalculator.compute(d, from, now);
        PdfReport p = new PdfReport(tf("MedAdherence report for %s, created %s on the patient's phone.",
                d.profile.name, TimeUtil.minute(now)));
        p.newPage();
        p.header(d.profile, from, now);
        p.summary(d, r);
        p.chart(d, from, now);
        p.medicines(d, r);
        p.problems(d, from, now);
        p.sideEffects(d, from, now);
        p.visit(d, now);
        p.finishPage();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String safe = d.profile.name.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        File out = new File(dir, "MedAdherence-report-" + (safe.isEmpty() ? "" : safe + "-") + TimeUtil.date(now.toLocalDate()) + ".pdf");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            p.doc.writeTo(fos);
        } finally {
            p.doc.close();
        }
        return out;
    }

    // ------------------------------------------------------------------ pages

    private void newPage() {
        pageNo++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
        c = page.getCanvas();
        y = M;
    }

    private void finishPage() {
        text.setTextSize(8);
        text.setColor(MUTED);
        text.setTypeface(Typeface.DEFAULT);
        c.drawText(footer, M, H - 22, text);
        String n = tf("Page %d", pageNo);
        c.drawText(n, W - M - text.measureText(n), H - 22, text);
        doc.finishPage(page);
    }

    /** Starts a new page unless {@code h} more points fit. */
    private void need(float h) {
        if (y + h > H - 44) { finishPage(); newPage(); }
    }

    private float paragraph(String s, float x, float width, float size, int color, boolean bold, boolean draw) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), text, (int) width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.1f).build();
        if (draw) {
            c.save();
            c.translate(x, y);
            l.draw(c);
            c.restore();
        }
        return l.getHeight();
    }

    private void line(String s, float size, int color, boolean bold) {
        float h = paragraph(s, M, W - 2 * M, size, color, bold, false);
        need(h);
        paragraph(s, M, W - 2 * M, size, color, bold, true);
        y += h;
    }

    private void section(String title) {
        need(60);
        y += 14;
        line(title, 13, PRIMARY, true);
        fill.setColor(LINE);
        c.drawRect(M, y + 2, W - M, y + 3, fill);
        y += 8;
    }

    // ------------------------------------------------------------------ content

    private void header(Profile p, LocalDateTime from, LocalDateTime now) {
        fill.setColor(PRIMARY);
        c.drawRect(0, 0, W, 76, fill);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(20);
        c.drawText(t("Medication adherence report"), M, 36, text);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(10);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        c.drawText(tf("Period: %s to %s", from.toLocalDate().format(f), now.toLocalDate().format(f)), M, 56, text);
        y = 96;

        float top = y, photo = 64, x = M;
        Bitmap face = Ui.loadBitmap(p.facePhoto, 160);
        if (face != null) {
            Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
            BitmapShader sh = new BitmapShader(face, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float s = photo / Math.min(face.getWidth(), face.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate(M - (face.getWidth() * s - photo) / 2, top - (face.getHeight() * s - photo) / 2);
            sh.setLocalMatrix(m);
            pp.setShader(sh);
            c.drawOval(new RectF(M, top, M + photo, top + photo), pp);
            x = M + photo + 16;
        }
        List<String> rows = new ArrayList<>();
        rows.add(p.summary(LocalDate.now()));
        if (!p.phone.isEmpty()) rows.add(t("Phone") + ": " + p.phone);
        if (!p.conditions.isEmpty()) rows.add(t("Conditions") + ": " + p.conditions);
        rows.add(t("Allergies") + ": " + (p.allergies.isEmpty() ? t("none recorded") : p.allergies));
        if (!p.doctor.isEmpty()) rows.add(t("Doctor or pharmacy") + ": " + p.doctor);
        for (int i = 0; i < rows.size(); i++) {
            float h = paragraph(rows.get(i), x, W - M - x, i == 0 ? 15 : 10.5f, i == 0 ? INK : MUTED, i == 0, true);
            y += h + 2;
        }
        y = Math.max(y, top + (face != null ? photo : 0)) + 6;
    }

    private void summary(AppData d, AdherenceCalculator.Report r) {
        section(t("Summary"));
        AdherenceStats o = r.overall;
        need(120);
        float top = y;
        // Big figure.
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(34);
        text.setColor(color(o.takingPercent()));
        c.drawText(String.format(Locale.ROOT, "%.0f%%", o.takingPercent()), M, top + 34, text);
        text.setTextSize(12);
        c.drawText(t(o.category()), M, top + 54, text);
        text.setTypeface(Typeface.DEFAULT);
        text.setColor(MUTED);
        text.setTextSize(10);
        c.drawText(tf("%d of %d doses taken", o.taken, o.due), M, top + 70, text);
        c.drawText(tf("Missed %d, skipped %d, late %d", o.missed, o.skipped, o.late), M, top + 84, text);
        c.drawText(tf("Current streak: %d days", r.currentStreakDays), M, top + 98, text);

        float bx = M + 190, bw = W - M - bx;
        y = top;
        bar(bx, bw, t("Doses taken"), o.takingPercent());
        bar(bx, bw, tf("On time (within %d min)", d.settings.onTimeWindowMinutes), o.timingPercent());
        bar(bx, bw, t("Days fully covered"), o.daysCoveredPercent());
        if (o.observedDue > 0) bar(bx, bw, t("Observed doses verified"), o.verifiedPercent());
        y = Math.max(y, top + 104);
        y += 4;
        text.setTextSize(8.5f);
        paragraph(t("Adherent = at least 80% of due doses taken; partially adherent = 50-79%; non-adherent = below 50%."),
                M, W - 2 * M, 8.5f, MUTED, false, true);
        y += 14;
    }

    private void bar(float x, float w, String label, double pct) {
        text.setTextSize(10);
        text.setColor(INK);
        text.setTypeface(Typeface.DEFAULT);
        c.drawText(label, x, y + 10, text);
        String v = String.format(Locale.ROOT, "%.0f%%", pct);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText(v, x + w - text.measureText(v), y + 10, text);
        fill.setColor(TINT);
        c.drawRoundRect(new RectF(x, y + 15, x + w, y + 22), 3.5f, 3.5f, fill);
        fill.setColor(color(pct));
        c.drawRoundRect(new RectF(x, y + 15, x + (float) (w * Math.max(0, Math.min(100, pct)) / 100), y + 22), 3.5f, 3.5f, fill);
        y += 28;
    }

    /** One bar per day (up to the last 30 days): share of that day's due doses taken. */
    private void chart(AppData d, LocalDateTime from, LocalDateTime now) {
        LocalDate last = now.toLocalDate(), first = from.toLocalDate();
        if (first.isBefore(last.minusDays(29))) first = last.minusDays(29);
        int days = (int) (last.toEpochDay() - first.toEpochDay()) + 1;
        if (days < 2) return;
        section(tf("Daily doses taken (last %d days)", days));
        need(120);
        float top = y, h = 80, left = M + 26, width = W - M - left, slot = width / days;
        text.setTextSize(8);
        text.setColor(MUTED);
        text.setTypeface(Typeface.DEFAULT);
        fill.setColor(LINE);
        for (int pct : new int[]{0, 50, 100}) {
            float gy = top + h - h * pct / 100f;
            c.drawRect(left, gy, W - M, gy + 0.5f, fill);
            c.drawText(pct + "%", M, gy + 3, text);
        }
        for (int i = 0; i < days; i++) {
            LocalDate day = first.plusDays(i);
            LocalDateTime end = day.equals(last) ? now : day.plusDays(1).atStartOfDay().minusNanos(1);
            AdherenceStats s = AdherenceCalculator.compute(d, day.atStartOfDay(), end).overall;
            float x = left + i * slot + slot * 0.15f, bw = slot * 0.7f;
            if (s.due == 0) {
                fill.setColor(TINT);
                c.drawRect(x, top + h - 2, x + bw, top + h, fill);
            } else {
                double pct = s.takingPercent();
                fill.setColor(color(pct));
                c.drawRect(x, top + h - (float) (h * Math.max(pct, 3) / 100), x + bw, top + h, fill);
            }
            if (days <= 14 || i % 5 == 0 || i == days - 1) {
                String lbl = String.valueOf(day.getDayOfMonth());
                c.drawText(lbl, x + bw / 2 - text.measureText(lbl) / 2, top + h + 11, text);
            }
        }
        y = top + h + 18;
    }

    private void medicines(AppData d, AdherenceCalculator.Report r) {
        if (d.medications.isEmpty()) return;
        section(t("Medicines"));
        float[] cols = {M, M + 150, M + 250, M + 330, M + 380, M + 425, M + 470};
        String[] head = {t("Medicine"), t("Times"), t("Duration"), t("Taken"), t("On time"), t("Missed"), t("Skipped")};
        need(40);
        fill.setColor(TINT);
        c.drawRect(M, y, W - M, y + 18, fill);
        text.setTextSize(9);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setColor(INK);
        for (int i = 0; i < head.length; i++) c.drawText(head[i], cols[i] + 4, y + 12.5f, text);
        y += 22;
        for (int k = 0; k < d.medications.size() && k < r.perMedication.size(); k++) {
            Medication m = d.medications.get(k);
            AdherenceStats s = r.perMedication.get(k);
            String name = m.name + (m.dose.isEmpty() ? "" : " " + m.dose)
                    + (m.doseForm() == com.chemrob.medadherence.core.DoseForm.TABLET ? "" : " (" + t(m.doseForm().label) + ")")
                    + (m.observed ? " *" : "") + (m.isActive() ? "" : " (" + t("paused") + ")");
            float h = Math.max(paragraph(name, cols[0] + 4, cols[1] - cols[0] - 8, 9.5f, INK, true, false),
                    paragraph(m.timesLabel(), cols[1] + 4, cols[2] - cols[1] - 8, 9, INK, false, false)) + 6;
            need(h);
            paragraph(name, cols[0] + 4, cols[1] - cols[0] - 8, 9.5f, INK, true, true);
            paragraph(m.timesLabel(), cols[1] + 4, cols[2] - cols[1] - 8, 9, INK, false, true);
            String dur = m.durationDays > 0 ? tf("%d days from %s", m.durationDays, m.startDate) : tf("ongoing from %s", m.startDate);
            paragraph(dur, cols[2] + 4, cols[3] - cols[2] - 8, 8.5f, MUTED, false, true);
            text.setTextSize(9.5f);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setColor(s.due == 0 ? MUTED : color(s.takingPercent()));
            c.drawText(s.due == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", s.takingPercent()), cols[3] + 4, y + 10, text);
            text.setTypeface(Typeface.DEFAULT);
            text.setColor(INK);
            c.drawText(s.due == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", s.timingPercent()), cols[4] + 4, y + 10, text);
            c.drawText(String.valueOf(s.missed), cols[5] + 4, y + 10, text);
            c.drawText(String.valueOf(s.skipped), cols[6] + 4, y + 10, text);
            text.setTextSize(8);
            text.setColor(MUTED);
            c.drawText(s.taken + "/" + s.due, cols[3] + 4, y + 21, text);
            y += Math.max(h, 24);
            fill.setColor(LINE);
            c.drawRect(M, y - 2, W - M, y - 1.5f, fill);
        }
        boolean anyObserved = false;
        for (Medication m : d.medications) anyObserved |= m.observed;
        if (anyObserved) line("* " + t("taken in front of the camera, checked by on-device AI"), 8.5f, MUTED, false);
    }

    /** Most recent missed, skipped and late doses. */
    private void problems(AppData d, LocalDateTime from, LocalDateTime now) {
        List<String[]> rows = new ArrayList<>();
        List<ScheduledDose> all = ScheduleEngine.doses(d, from, now.plusNanos(1));
        for (int i = all.size() - 1; i >= 0 && rows.size() < 25; i--) {
            ScheduledDose x = all.get(i);
            DoseStatus s = ScheduleEngine.statusOf(d, x, now);
            String what = null;
            if (s == DoseStatus.MISSED) what = t("Missed");
            else if (s == DoseStatus.SKIPPED) what = t("Skipped");
            else if (s == DoseStatus.TAKEN) {
                DoseRecord rec = d.findRecord(x.key());
                LocalDateTime at = rec == null ? null : TimeUtil.parseSecond(rec.actionAt);
                if (at != null && Math.abs(java.time.Duration.between(x.time, at).toMinutes()) > d.settings.onTimeWindowMinutes)
                    what = tf("Late (taken %s)", TimeUtil.clock(at));
            }
            if (what != null) rows.add(new String[]{TimeUtil.minute(x.time), x.med.name + (x.med.dose.isEmpty() ? "" : " " + x.med.dose), what});
        }
        section(t("Missed, skipped and late doses"));
        if (rows.isEmpty()) { line(t("None in this period."), 10, MUTED, false); return; }
        for (String[] row : rows) {
            need(16);
            text.setTextSize(9.5f);
            text.setTypeface(Typeface.DEFAULT);
            text.setColor(INK);
            c.drawText(row[0], M + 4, y + 10, text);
            c.drawText(row[1], M + 110, y + 10, text);
            text.setColor(row[2].equals(t("Missed")) ? BAD : WARN);
            c.drawText(row[2], M + 330, y + 10, text);
            y += 15;
        }
        if (rows.size() == 25) line(t("Only the 25 most recent are listed."), 8.5f, MUTED, false);
    }

    /** Side effects reported in the period, and tablets left over at the end of courses. */
    private void sideEffects(AppData d, LocalDateTime from, LocalDateTime now) {
        java.util.List<String[]> rows = new ArrayList<>();
        for (int i = d.sideEffects.size() - 1; i >= 0 && rows.size() < 20; i--) {
            com.chemrob.medadherence.core.SideEffect e = d.sideEffects.get(i);
            LocalDateTime at = TimeUtil.parseMinute(e.at);
            if (at == null || at.isBefore(from)) continue;
            rows.add(new String[]{e.at, t(e.symptom.label) + (e.symptom.serious ? "  (" + t("serious") + ")" : ""), e.medicines});
        }
        java.util.List<String> leftovers = new ArrayList<>();
        for (Medication m : d.medications)
            if (m.leftover > 0) leftovers.add(m.name + ": " + (m.leftover == Math.floor(m.leftover) ? String.valueOf((long) m.leftover) : String.valueOf(m.leftover))
                    + (m.doseForm().unit.isEmpty() ? "" : " " + t(m.doseForm().unit)));
        if (rows.isEmpty() && leftovers.isEmpty()) return;
        section(t("Side effects and leftovers"));
        for (String[] row : rows) {
            line(row[0] + "   " + row[1] + (row[2].isEmpty() ? "" : "   (" + row[2] + ")"), 10, row[1].contains(t("serious")) ? BAD : INK, false);
            y += 4;
        }
        if (!leftovers.isEmpty()) line(t("Left over at the end of the course:") + " " + String.join("; ", leftovers), 10, WARN, true);
    }

    private void visit(AppData d, LocalDateTime now) {
        Appointment a = Appointment.next(d.appointments, now);
        if (a == null) return;
        section(t("Next doctor visit"));
        line(TimeUtil.minute(a.time()) + "  -  " + a.who() + (a.purpose.isEmpty() ? "" : "  -  " + a.purpose), 11, INK, false);
    }
}
