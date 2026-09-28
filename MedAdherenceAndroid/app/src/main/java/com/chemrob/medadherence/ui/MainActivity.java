package com.chemrob.medadherence.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.alarm.AlarmReceiver;
import com.chemrob.medadherence.alarm.AlarmScheduler;
import com.chemrob.medadherence.core.AdherenceCalculator;
import com.chemrob.medadherence.core.AdherenceStats;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.DoseRecord;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.Inventory;
import com.chemrob.medadherence.core.Medication;
import com.chemrob.medadherence.core.RegimenParser;
import com.chemrob.medadherence.core.ScheduleEngine;
import com.chemrob.medadherence.core.ScheduledDose;
import com.chemrob.medadherence.core.TimeUtil;
import com.chemrob.medadherence.core.Verification;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The app: Today (take / skip doses), Medicines (load the regimen), Adherence (metrics and reports)
 * and Pharmacist (PIN-protected bulk import, evidence review, settings and alarm checks).
 */
public class MainActivity extends Activity {
    private enum Tab { TODAY, MEDICINES, ADHERENCE, PHARMACIST }

    /** A dose may be marked taken up to this long before its scheduled time. */
    private static final int EARLY_WINDOW_MIN = 120;
    private static final int REQ_NOTIFY = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView headerTitle, headerSub;
    private LinearLayout body;
    private ScrollView scroll;
    private final List<Button> navButtons = new ArrayList<>();
    private Tab tab = Tab.TODAY;
    private boolean editing;           // add/edit form is showing
    private boolean pharmacistUnlocked;
    private int adherenceDays = 30;
    private String todaySignature = "";

    private AppData data() { return Store.get(this); }

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.PRIMARY);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = Ui.vbox(this);
        header.setBackgroundColor(Ui.PRIMARY);
        int p = Ui.dp(this, 20);
        header.setPadding(p, p, p, p);
        headerTitle = Ui.text(header, "", 24, Color.WHITE, true);
        headerSub = Ui.text(header, "", 14, Color.parseColor("#BFE3DA"), false);
        root.addView(header);

        scroll = new ScrollView(this);
        body = Ui.vbox(this);
        int bp = Ui.dp(this, 14);
        body.setPadding(bp, 0, bp, Ui.dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = Ui.hbox(this);
        nav.setBackgroundColor(Ui.SURFACE);
        nav.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 8));
        for (Tab t : Tab.values()) {
            String label = t == Tab.TODAY ? "Today" : t == Tab.MEDICINES ? "Medicines" : t == Tab.ADHERENCE ? "Adherence" : "Pharmacist";
            navButtons.add(Ui.button(nav, label, Ui.SURFACE, v -> show(t)));
        }
        root.addView(nav);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlarmScheduler.syncAll(this);
        if (!editing) render();
        handler.postDelayed(ticker, 30_000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    /** Keeps "Upcoming / Due now / Missed" current without resetting the scroll needlessly. */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (tab == Tab.TODAY && !editing && !todaySignature.equals(signature(LocalDateTime.now()))) render();
            handler.postDelayed(this, 30_000);
        }
    };

    @Override
    public void onBackPressed() {
        if (editing) { editing = false; render(); return; }
        if (tab != Tab.TODAY) { show(Tab.TODAY); return; }
        super.onBackPressed();
    }

    private void show(Tab t) {
        tab = t;
        editing = false;
        render();
        scroll.scrollTo(0, 0);
    }

    private void render() {
        for (int i = 0; i < navButtons.size(); i++) {
            boolean on = Tab.values()[i] == tab;
            Button b = navButtons.get(i);
            b.setBackground(Ui.rounded(this, on ? Ui.PRIMARY : Ui.SURFACE, 12));
            b.setTextColor(on ? Color.WHITE : Ui.PRIMARY);
            b.setTextSize(13);
        }
        body.removeAllViews();
        switch (tab) {
            case TODAY: buildToday(); break;
            case MEDICINES: buildMedicines(); break;
            case ADHERENCE: buildAdherence(); break;
            case PHARMACIST: buildPharmacist(); break;
        }
    }

    private void header(String title, String sub) {
        headerTitle.setText(title);
        headerSub.setText(sub);
    }

    private void saveAndSync() {
        Store.save(this);
        AlarmScheduler.syncAll(this);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ================================================================== Today

    private void buildToday() {
        AppData d = data();
        LocalDateTime now = LocalDateTime.now();
        String who = d.settings.patientName.isEmpty() ? "" : ", " + d.settings.patientName;
        String greet = now.getHour() < 12 ? "Good morning" : now.getHour() < 17 ? "Good afternoon" : "Good evening";
        header(greet + who, now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")));

        if (d.medications.isEmpty()) {
            LinearLayout c = Ui.card(body, Ui.SURFACE);
            Ui.heading(c, "No medicines yet");
            Ui.muted(c, "Add the medicines with their times and duration. The phone will ring at every dose time.");
            Ui.button(c, "+ Add medicine", Ui.ACCENT, v -> editMedication(null));
            Ui.button(c, "Pharmacist: load a full regimen", Ui.PRIMARY, v -> show(Tab.PHARMACIST));
            return;
        }

        for (Medication m : d.medications) {
            if (!Inventory.needsRefill(m, now)) continue;
            LinearLayout c = Ui.card(body, Ui.ALERT_BG);
            Ui.text(c, "Refill " + m.name + " soon: " + Inventory.label(m) + ". Contact your pharmacy so you don't run out.", 15, Ui.INK, false);
            Ui.button(c, "I have refilled it", Ui.ACCENT, v -> askRefill(m));
        }

        List<ScheduledDose> doses = ScheduleEngine.doses(d, now.toLocalDate().atStartOfDay(), now.toLocalDate().plusDays(1).atStartOfDay());
        todaySignature = signature(now);
        int taken = 0;
        for (ScheduledDose x : doses) if (ScheduleEngine.statusOf(d, x, now) == DoseStatus.TAKEN) taken++;

        LinearLayout summary = Ui.card(body, Ui.SURFACE);
        Ui.text(summary, taken + " of " + doses.size() + " doses taken today", 19, Ui.INK, true);
        ScheduledDose next = null;
        for (ScheduledDose x : ScheduleEngine.doses(d, now, now.plusDays(7)))
            if (ScheduleEngine.statusOf(d, x, now) == DoseStatus.PENDING) { next = x; break; }
        if (next != null) {
            long mins = ChronoUnit.MINUTES.between(now, next.time);
            String in = mins >= 24 * 60 ? next.time.format(DateTimeFormatter.ofPattern("EEE HH:mm"))
                    : mins >= 60 ? "in " + mins / 60 + "h " + mins % 60 + "m" : "in " + Math.max(0, mins) + " min";
            Ui.muted(summary, "Next: " + next.med.name + " at " + TimeUtil.clock(next.time) + " (" + in + ")");
        }
        Ui.bar(summary, "Last 7 days", AdherenceCalculator.compute(d, now.toLocalDate().minusDays(6).atStartOfDay(), now).overall.takingPercent(), null);

        if (doses.isEmpty()) Ui.muted(body, "\nNo doses scheduled today.");
        for (ScheduledDose dose : doses) doseRow(dose, now);
    }

    private String signature(LocalDateTime now) {
        AppData d = data();
        StringBuilder sb = new StringBuilder();
        for (ScheduledDose x : ScheduleEngine.doses(d, now.toLocalDate().atStartOfDay(), now.toLocalDate().plusDays(1).atStartOfDay()))
            sb.append(ScheduleEngine.statusOf(d, x, now).ordinal())
              .append(ScheduleEngine.isDueNow(d, x, now) ? 'd' : '-')
              .append(!now.isBefore(x.time.minusMinutes(EARLY_WINDOW_MIN)) ? 'e' : '-');
        return sb.toString();
    }

    private void doseRow(ScheduledDose dose, LocalDateTime now) {
        AppData d = data();
        DoseStatus status = ScheduleEngine.statusOf(d, dose, now);
        DoseRecord rec = d.findRecord(dose.key());
        boolean due = ScheduleEngine.isDueNow(d, dose, now);

        LinearLayout card = Ui.card(body, due ? Ui.DUE_BG : Ui.SURFACE);
        LinearLayout top = Ui.hbox(this);
        card.addView(top);
        TextView time = new TextView(this);
        time.setText(TimeUtil.clock(dose.time));
        time.setTextSize(22);
        time.setTextColor(Ui.PRIMARY);
        time.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        top.addView(time, new LinearLayout.LayoutParams(Ui.dp(this, 80), ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.text(top, dose.med.name + (dose.med.dose.isEmpty() ? "" : "\n" + dose.med.dose), 16, Ui.INK, true);

        String badge;
        int color;
        switch (status) {
            case TAKEN:
                boolean late = rec != null && rec.actionTime() != null
                        && Math.abs(ChronoUnit.MINUTES.between(dose.time, rec.actionTime())) > d.settings.onTimeWindowMinutes;
                badge = (late ? "Late " : "Taken ") + (rec != null && rec.actionTime() != null ? TimeUtil.clock(rec.actionTime()) : "");
                color = late ? Ui.WARN : Ui.GOOD;
                break;
            case SKIPPED: badge = "Skipped"; color = Ui.MUTED; break;
            case MISSED: badge = "Missed"; color = Ui.BAD; break;
            case SNOOZED: badge = "Snoozed"; color = Ui.WARN; break;
            default: badge = due ? "Due now" : "Upcoming"; color = due ? Ui.WARN : Ui.ACCENT;
        }
        Ui.badge(top, badge, color);

        if (dose.med.observed || !dose.med.instructions.isEmpty())
            Ui.muted(card, (dose.med.observed ? "Camera-observed dose. " : "") + dose.med.instructions);
        if (rec != null && dose.med.observed && status == DoseStatus.TAKEN)
            Ui.muted(card, "Verification: " + verificationLabel(rec.verification));

        boolean canAct = (status == DoseStatus.PENDING || status == DoseStatus.SNOOZED)
                && !now.isBefore(dose.time.minusMinutes(EARLY_WINDOW_MIN));
        boolean catchUp = status == DoseStatus.MISSED;
        if (!canAct && !catchUp) return;
        LinearLayout actions = Ui.row(card);
        String key = dose.key();
        if (dose.med.observed) Ui.button(actions, "Take on camera", Ui.GOOD, v -> startActivity(ObserveActivity.intent(this, key)));
        else Ui.button(actions, catchUp ? "Taken late" : "Take", Ui.GOOD, v -> recordAction(key, DoseStatus.TAKEN));
        Ui.button(actions, "Skip", Ui.MUTED, v -> recordAction(key, DoseStatus.SKIPPED));
    }

    private static String verificationLabel(Verification v) {
        switch (v) {
            case AUTO_VERIFIED: return "verified on camera";
            case NEEDS_REVIEW: return "waiting for pharmacist review";
            case PHARMACIST_APPROVED: return "approved by pharmacist";
            case PHARMACIST_REJECTED: return "rejected by pharmacist";
            default: return "not required";
        }
    }

    private void recordAction(String key, DoseStatus status) {
        AlarmReceiver.record(this, key, status);
        render();
    }

    // ================================================================== Medicines

    private boolean editingLocked() { return data().settings.lockEditingWithPin && !pharmacistUnlocked; }

    private void withEditPermission(Runnable r) {
        if (!editingLocked()) { r.run(); return; }
        askPin("Pharmacist PIN required to change the medicines", r);
    }

    private void buildMedicines() {
        AppData d = data();
        header("Medicines", d.medications.size() + " in the regimen");
        Ui.button(body, "+ Add medicine", Ui.ACCENT, v -> withEditPermission(() -> editMedication(null)));
        LocalDateTime now = LocalDateTime.now();
        for (Medication m : d.medications) {
            LinearLayout card = Ui.card(body, Ui.SURFACE);
            LinearLayout top = Ui.hbox(this);
            card.addView(top);
            Ui.text(top, m.name + (m.dose.isEmpty() ? "" : "  " + m.dose), 17, Ui.INK, true);
            if (m.observed) Ui.badge(top, "Observed", Ui.PRIMARY);
            if (!m.isActive()) Ui.badge(top, "Paused", Ui.MUTED);

            String course = m.durationDays > 0
                    ? m.durationDays + " days, " + m.startDate + " to " + TimeUtil.date(m.end())
                    : "ongoing from " + m.startDate;
            Ui.muted(card, m.timesLabel() + " " + m.frequencyLabel() + "\n" + course);
            if (!m.instructions.isEmpty()) Ui.muted(card, m.instructions);
            if (m.tracksStock()) {
                boolean low = Inventory.needsRefill(m, now);
                Ui.text(card, (low ? "Refill soon: " : "Stock: ") + Inventory.label(m), 14, low ? Ui.BAD : Ui.MUTED, low);
            }
            if (m.end() != null && m.end().isBefore(LocalDate.now())) Ui.text(card, "Course completed", 14, Ui.GOOD, true);

            LinearLayout a = Ui.row(card);
            Ui.button(a, "Edit", Ui.PRIMARY, v -> withEditPermission(() -> editMedication(m)));
            Ui.button(a, "Refill", Ui.ACCENT, v -> askRefill(m));
            LinearLayout a2 = Ui.row(card);
            Ui.button(a2, m.isActive() ? "Pause" : "Resume", Ui.WARN, v -> withEditPermission(() -> {
                if (m.isActive()) m.pause(LocalDateTime.now()); else m.resume(LocalDateTime.now());
                saveAndSync();
                render();
            }));
            Ui.button(a2, "Delete", Ui.BAD, v -> withEditPermission(() -> confirm(
                    "Delete " + m.name + " and its dose history? To stop reminders but keep the history, use Pause.", () -> {
                        d.medications.remove(m);
                        d.records.removeIf(r -> r.medId.equals(m.id));
                        saveAndSync();
                        render();
                    })));
        }
    }

    /** Add / edit form: pick a frequency, pick a duration, save. */
    private void editMedication(Medication existing) {
        Medication m;
        if (existing != null) m = existing.copy();
        else {
            m = new Medication();
            m.startDate = TimeUtil.date(LocalDate.now());
            m.times = new ArrayList<>(RegimenParser.findPreset("BD").times);
            m.durationDays = 7;
            m.addedBy = pharmacistUnlocked ? "pharmacist" : "patient";
        }
        editing = true;
        buildForm(m, existing == null, null);
    }

    private void buildForm(Medication m, boolean isNew, String error) {
        header(isNew ? "Add medicine" : "Edit medicine", "Name, dose, times and how long to take it");
        body.removeAllViews();
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        EditText name = Ui.textField(c, "Medicine name *", "e.g. Metformin", m.name);
        EditText dose = Ui.textField(c, "Dose", "e.g. 500 mg, 1 tablet", m.dose);
        EditText instr = Ui.textField(c, "Instructions", "e.g. after food", m.instructions);

        final EditText[] f = new EditText[5]; // times, start, days, stock, perDose
        Runnable capture = () -> {
            m.name = name.getText().toString().trim();
            m.dose = dose.getText().toString().trim();
            m.instructions = instr.getText().toString().trim();
            RegimenParser.Times t = RegimenParser.parseTimes(f[0].getText().toString());
            if (t.error == null) { m.times = t.times; if (t.everyNDays > 1) m.everyNDays = t.everyNDays; }
            m.startDate = f[1].getText().toString().trim();
            try { m.durationDays = Math.max(0, Integer.parseInt(f[2].getText().toString().trim())); } catch (NumberFormatException ignored) { }
            String st = f[3].getText().toString().trim();
            Double sv = RegimenParser.parseNumber(st);
            if (st.isEmpty()) m.stock = -1; else if (sv != null && sv >= 0) m.stock = sv;
            Double pv = RegimenParser.parseNumber(f[4].getText().toString());
            if (pv != null && pv > 0) m.unitsPerDose = pv;
        };

        TextView howOften = Ui.text(c, "How often", 13, Ui.MUTED, true);
        ((LinearLayout.LayoutParams) howOften.getLayoutParams()).topMargin = Ui.dp(this, 12);
        LinearLayout r1 = Ui.row(c), r2 = Ui.row(c);
        int i = 0;
        for (RegimenParser.Frequency p : RegimenParser.PRESETS) {
            boolean sel = m.everyNDays == p.everyNDays && m.times.equals(p.times);
            Ui.chip(i++ < 4 ? r1 : r2, p.code, sel, v -> {
                capture.run();
                m.times = new ArrayList<>(p.times);
                m.everyNDays = p.everyNDays;
                buildForm(m, isNew, null);
            });
        }
        f[0] = Ui.textField(c, "Times (24 h, edit freely)", "08:00 20:00", String.join(" ", m.times));
        Ui.muted(c, m.everyNDays == 1 ? "Every day" : "Every " + m.everyNDays + " days");

        f[1] = Ui.textField(c, "Start date (yyyy-MM-dd)", "yyyy-MM-dd", m.startDate);
        LinearLayout sr = Ui.row(c);
        String today = TimeUtil.date(LocalDate.now()), tomorrow = TimeUtil.date(LocalDate.now().plusDays(1));
        Ui.chip(sr, "Today", m.startDate.equals(today), v -> { capture.run(); m.startDate = today; buildForm(m, isNew, null); });
        Ui.chip(sr, "Tomorrow", m.startDate.equals(tomorrow), v -> { capture.run(); m.startDate = tomorrow; buildForm(m, isNew, null); });

        f[2] = Ui.field(c, "Duration in days (0 = ongoing)", "7", String.valueOf(m.durationDays), InputType.TYPE_CLASS_NUMBER);
        LinearLayout dr1 = Ui.row(c), dr2 = Ui.row(c);
        int[] durs = {3, 5, 7, 10, 14, 30, 0};
        for (int k = 0; k < durs.length; k++) {
            int dd = durs[k];
            Ui.chip(k < 4 ? dr1 : dr2, dd == 0 ? "Ongoing" : String.valueOf(dd), m.durationDays == dd, v -> {
                capture.run();
                m.durationDays = dd;
                buildForm(m, isNew, null);
            });
        }

        CheckBox observed = Ui.check(c, "Observed dose: patient takes it in front of the camera", m.observed);
        observed.setOnCheckedChangeListener((btn, on) -> m.observed = on);

        int dec = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        f[3] = Ui.field(c, "Units in stock (blank = don't track)", "e.g. 30", m.tracksStock() ? num(m.stock) : "", dec);
        f[4] = Ui.field(c, "Units per dose", "1", num(m.unitsPerDose), dec);
        Ui.muted(c, "With stock entered, the app counts down each dose taken and warns before it runs out.");

        if (error != null) Ui.text(body, error, 15, Ui.BAD, true);
        LinearLayout a = Ui.row(body);
        Ui.button(a, "Cancel", Ui.MUTED, v -> { editing = false; show(Tab.MEDICINES); });
        Ui.button(a, "Save", Ui.GOOD, v -> {
            capture.run();
            String err = null;
            RegimenParser.Times t = RegimenParser.parseTimes(f[0].getText().toString());
            String st = f[3].getText().toString().trim();
            Double sv = RegimenParser.parseNumber(st), pv = RegimenParser.parseNumber(f[4].getText().toString());
            if (m.name.isEmpty()) err = "Please enter the medicine name.";
            else if (t.error != null) err = t.error + ". Use times like 08:00 20:00.";
            else if (TimeUtil.parseDate(m.startDate) == null) err = "Start date must look like 2026-10-01.";
            else if (!st.isEmpty() && (sv == null || sv < 0)) err = "Stock must be a number of units, e.g. 30, or left blank.";
            else if (pv == null || pv <= 0) err = "Units per dose must be more than 0, e.g. 1 or 0.5.";
            if (err != null) { buildForm(m, isNew, err); return; }
            AppData d = data();
            int idx = -1;
            for (int k = 0; k < d.medications.size(); k++) if (d.medications.get(k).id.equals(m.id)) idx = k;
            if (idx >= 0) d.medications.set(idx, m); else d.medications.add(m);
            saveAndSync();
            editing = false;
            show(Tab.MEDICINES);
        });
        scroll.scrollTo(0, 0);
    }

    private static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ================================================================== Adherence

    private void buildAdherence() {
        AppData d = data();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = adherenceDays > 0 ? now.toLocalDate().minusDays(adherenceDays - 1).atStartOfDay() : earliestStart();
        AdherenceCalculator.Report r = AdherenceCalculator.compute(d, from, now);
        header("Adherence", adherenceDays > 0 ? "Last " + adherenceDays + " days" : "Since the first dose");

        LinearLayout periods = Ui.row(body);
        for (int p : new int[]{7, 30, 90, 0}) {
            Ui.chip(periods, p == 0 ? "All" : p + " days", adherenceDays == p, v -> { adherenceDays = p; render(); });
        }

        AdherenceStats o = r.overall;
        LinearLayout hero = Ui.card(body, Ui.SURFACE);
        TextView big = Ui.text(hero, String.format(Locale.ROOT, "%.0f%%", o.takingPercent()), 52, Ui.colorFor(o.takingPercent()), true);
        big.setGravity(Gravity.CENTER);
        TextView cat = Ui.text(hero, o.category(), 19, Ui.INK, true);
        cat.setGravity(Gravity.CENTER);
        TextView line = Ui.muted(hero, o.taken + " of " + o.due + " doses taken  |  " + o.missed + " missed  |  "
                + o.skipped + " skipped  |  streak " + r.currentStreakDays + " day(s)");
        line.setGravity(Gravity.CENTER);

        LinearLayout bars = Ui.card(body, Ui.SURFACE);
        Ui.bar(bars, "Doses taken", o.takingPercent(), null);
        Ui.bar(bars, "Taken on time (within " + d.settings.onTimeWindowMinutes + " min)", o.timingPercent(), null);
        Ui.bar(bars, "Days fully covered", o.daysCoveredPercent(), "  (" + o.daysCovered + "/" + o.daysElapsed + " days)");
        if (o.observedDue > 0)
            Ui.bar(bars, "Observed doses verified", o.verifiedPercent(), "  (" + o.observedVerified + "/" + o.observedDue + ")");

        LinearLayout hist = Ui.card(body, Ui.SURFACE);
        Ui.heading(hist, "Last 14 days");
        LinearLayout strip = Ui.row(hist);
        for (int i = 13; i >= 0; i--) {
            LocalDate day = now.toLocalDate().minusDays(i);
            LocalDateTime end = i == 0 ? now : day.plusDays(1).atStartOfDay().minusNanos(1);
            AdherenceStats ds = AdherenceCalculator.compute(d, day.atStartOfDay(), end).overall;
            TextView cell = new TextView(this);
            cell.setText(String.valueOf(day.getDayOfMonth()));
            cell.setTextSize(11);
            cell.setGravity(Gravity.CENTER);
            cell.setTextColor(ds.due == 0 ? Ui.MUTED : Color.WHITE);
            cell.setBackground(Ui.rounded(this, ds.due == 0 ? Ui.LINE : Ui.colorFor(ds.takingPercent()), 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1);
            lp.leftMargin = lp.rightMargin = Ui.dp(this, 1.5f);
            strip.addView(cell, lp);
        }
        Ui.muted(hist, "Green = all doses taken, amber = some, red = most missed, grey = nothing due.");

        for (AdherenceStats s : r.perMedication) {
            LinearLayout c = Ui.card(body, Ui.SURFACE);
            Ui.text(c, s.label + "  -  " + s.category(), 16, Ui.INK, true);
            Ui.bar(c, "Doses taken", s.takingPercent(), "  (" + s.taken + "/" + s.due + ")");
            Ui.muted(c, String.format(Locale.ROOT, "On time %.0f%%  |  late %d  |  missed %d  |  skipped %d",
                    s.timingPercent(), s.late, s.missed, s.skipped));
        }

        LinearLayout a = Ui.row(body);
        Ui.button(a, "Share report", Ui.PRIMARY, v -> share("Medication adherence report", AdherenceCalculator.toText(d, r)));
        Ui.button(a, "Share dose log", Ui.ACCENT, v -> share("Dose log (CSV)", AdherenceCalculator.doseLogCsv(d, from, now)));
    }

    private LocalDateTime earliestStart() {
        LocalDate first = LocalDate.now();
        for (Medication m : data().medications) if (m.start().isBefore(first)) first = m.start();
        return first.atStartOfDay();
    }

    private void share(String subject, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, subject));
    }

    // ================================================================== Pharmacist

    private void buildPharmacist() {
        AppData d = data();
        if (!pharmacistUnlocked) {
            header("Pharmacist", "Enter the PIN to continue");
            LinearLayout c = Ui.card(body, Ui.SURFACE);
            Ui.muted(c, "Pharmacist tools: load a full regimen, review observed doses, settings and alarm checks. "
                    + "The default PIN is 0000; change it after first use.");
            EditText pin = Ui.field(c, "PIN", "PIN", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            TextView msg = Ui.text(c, "", 14, Ui.BAD, false);
            Ui.button(c, "Unlock", Ui.PRIMARY, v -> {
                if (pin.getText().toString().equals(d.settings.pharmacistPin)) { pharmacistUnlocked = true; render(); }
                else msg.setText("Wrong PIN");
            });
            return;
        }
        header("Pharmacist", "Regimen, review and settings");
        buildReviewQueue();
        buildImport();
        buildSettings();
        buildReliability();
        Ui.button(body, "Lock pharmacist mode", Ui.MUTED, v -> { pharmacistUnlocked = false; render(); });
    }

    private void buildReviewQueue() {
        AppData d = data();
        List<DoseRecord> queue = new ArrayList<>();
        for (DoseRecord r : d.records)
            if (r.status == DoseStatus.TAKEN && r.verification == Verification.NEEDS_REVIEW) queue.add(r);
        queue.sort((a, b) -> b.scheduled.compareTo(a.scheduled));

        LinearLayout c = Ui.card(body, Ui.SURFACE);
        Ui.heading(c, "Observed doses to review (" + queue.size() + ")");
        if (queue.isEmpty()) Ui.muted(c, "Nothing waiting. Doses that pass every camera check are verified automatically.");
        for (DoseRecord r : queue.subList(0, Math.min(10, queue.size()))) {
            Medication m = d.findMed(r.medId);
            Ui.text(c, (m != null ? m.name : "?") + "  scheduled " + r.scheduled + ", taken " + r.actionAt, 14, Ui.INK, true);
            Ui.muted(c, String.format(Locale.ROOT, "%s  |  movement %.0f%%  |  person in view %.0f%%", r.note, r.livenessScore * 100, r.presenceScore * 100));
            HorizontalScrollView hs = new HorizontalScrollView(this);
            LinearLayout thumbs = Ui.hbox(this);
            hs.addView(thumbs);
            c.addView(hs, Ui.matchWrap(this, 6));
            for (String path : r.evidence) thumbnail(thumbs, path);
            LinearLayout a = Ui.row(c);
            Ui.button(a, "Approve", Ui.GOOD, v -> { r.verification = Verification.PHARMACIST_APPROVED; Store.save(this); render(); });
            Ui.button(a, "Reject", Ui.BAD, v -> { r.verification = Verification.PHARMACIST_REJECTED; Store.save(this); render(); });
        }
    }

    private void thumbnail(LinearLayout parent, String path) {
        File f = new File(path);
        if (!f.exists()) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 4;
        Bitmap bmp = BitmapFactory.decodeFile(path, o);
        if (bmp == null) return;
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 110));
        lp.rightMargin = Ui.dp(this, 6);
        parent.addView(iv, lp);
        iv.setOnClickListener(v -> {
            ImageView full = new ImageView(this);
            full.setImageBitmap(BitmapFactory.decodeFile(path));
            full.setAdjustViewBounds(true);
            new AlertDialog.Builder(this).setView(full).setPositiveButton("Close", null).show();
        });
    }

    private void buildImport() {
        AppData d = data();
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        Ui.heading(c, "Load a full regimen");
        Ui.muted(c, "One medicine per line:\nName | Dose | Times or OD/BD/TDS/QID/HS/Q8H/WEEKLY | Days (0 = ongoing) | Start | Observed yes/no | Instructions | Stock | Units per dose");
        EditText box = Ui.field(c, "Regimen", "Metformin | 500 mg | BD | 30 | today | no | after food | 60 | 1\nRifampicin | 600 mg | 07:00 | 6m | today | yes | empty stomach",
                "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.setMinLines(5);
        box.setGravity(Gravity.TOP);
        CheckBox replace = Ui.check(c, "Replace the current regimen", false);
        TextView msg = Ui.text(c, "", 14, Ui.MUTED, false);
        LinearLayout a = Ui.row(c);
        Ui.button(a, "Import", Ui.GOOD, v -> {
            RegimenParser.ImportResult res = RegimenParser.importText(box.getText().toString(), LocalDate.now(), "pharmacist");
            if (res.medications.isEmpty() && res.errors.isEmpty()) { msg.setText("Type or paste at least one line."); return; }
            if (!res.medications.isEmpty()) {
                if (replace.isChecked()) d.medications.clear();
                d.medications.addAll(res.medications);
                saveAndSync();
            }
            msg.setText("Imported " + res.medications.size() + " medicine(s)." + (res.errors.isEmpty() ? "" : "\n" + String.join("\n", res.errors)));
            msg.setTextColor(res.errors.isEmpty() ? Ui.GOOD : Ui.BAD);
            if (res.errors.isEmpty()) box.setText("");
        });
        Ui.button(a, "Paste", Ui.ACCENT, v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
            if (clip != null && clip.getItemCount() > 0) box.setText(clip.getItemAt(0).coerceToText(this));
        });
        Ui.button(c, "Share regimen (to load on another phone)", Ui.PRIMARY, v -> share("Medication regimen", RegimenParser.export(d.medications)));
    }

    private void buildSettings() {
        com.chemrob.medadherence.core.Settings s = data().settings;
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        Ui.heading(c, "Settings");
        int numType = InputType.TYPE_CLASS_NUMBER;
        EditText name = Ui.textField(c, "Patient name", "optional", s.patientName);
        EditText grace = Ui.field(c, "Minutes before a dose counts as missed", "120", String.valueOf(s.graceMinutes), numType);
        EditText window = Ui.field(c, "On-time window, +/- minutes", "60", String.valueOf(s.onTimeWindowMinutes), numType);
        EditText snooze = Ui.field(c, "Snooze minutes", "10", String.valueOf(s.snoozeMinutes), numType);
        EditText pin = Ui.field(c, "New pharmacist PIN (leave empty to keep)", "", "", numType | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        CheckBox lock = Ui.check(c, "Patient needs the PIN to change medicines", s.lockEditingWithPin);
        Ui.button(c, "Save settings", Ui.GOOD, v -> {
            s.patientName = name.getText().toString().trim();
            s.graceMinutes = clamp(grace, s.graceMinutes, 15, 24 * 60);
            s.onTimeWindowMinutes = clamp(window, s.onTimeWindowMinutes, 5, 12 * 60);
            s.snoozeMinutes = clamp(snooze, s.snoozeMinutes, 1, 120);
            if (pin.getText().length() >= 4) s.pharmacistPin = pin.getText().toString();
            s.lockEditingWithPin = lock.isChecked();
            saveAndSync();
            toast("Settings saved");
            render();
        });
    }

    private static int clamp(EditText e, int fallback, int lo, int hi) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(e.getText().toString().trim()))); }
        catch (NumberFormatException ex) { return fallback; }
    }

    /** Checks that decide whether alarms actually ring, each with a button to fix it. */
    private void buildReliability() {
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        Ui.heading(c, "Alarm reliability");
        boolean notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        check(c, notif, "Notifications allowed", () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY));
        check(c, AlarmScheduler.canScheduleExact(this), "Exact alarm time allowed", () ->
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))));
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean fsi = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent();
        check(c, fsi, "Ring over the lock screen allowed", () ->
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName()))));
        PowerManager pm = getSystemService(PowerManager.class);
        check(c, pm.isIgnoringBatteryOptimizations(getPackageName()), "Battery optimisation off", () ->
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        check(c, checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED, "Camera allowed (observed doses)", () ->
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 2));
        Ui.button(c, "Test: ring in 1 minute", Ui.PRIMARY, v -> {
            AlarmScheduler.scheduleTest(this);
            toast("Lock the phone. It should ring in about a minute.");
        });
    }

    private void check(LinearLayout parent, boolean ok, String label, Runnable fix) {
        LinearLayout r = Ui.row(parent);
        Ui.text(r, (ok ? "OK   " : "!    ") + label, 15, ok ? Ui.GOOD : Ui.BAD, !ok);
        if (!ok) {
            Button b = Ui.button(r, "Fix", Ui.ACCENT, v -> fix.run());
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            lp.weight = 0;
            lp.width = Ui.dp(this, 72);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (tab == Tab.PHARMACIST && !editing) render();
    }

    // ================================================================== dialogs

    private void confirm(String message, Runnable onYes) {
        new AlertDialog.Builder(this).setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Yes", (dlg, w) -> onYes.run()).show();
    }

    private void askPin(String title, Runnable onOk) {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("PIN");
        new AlertDialog.Builder(this).setTitle(title).setView(pin)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (dlg, w) -> {
                    if (pin.getText().toString().equals(data().settings.pharmacistPin)) {
                        pharmacistUnlocked = true;
                        onOk.run();
                    } else toast("Wrong PIN");
                }).show();
    }

    private void askRefill(Medication m) {
        EditText qty = new EditText(this);
        qty.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        qty.setHint("Units added, e.g. 30");
        new AlertDialog.Builder(this)
                .setTitle("Refill " + m.name)
                .setMessage(m.tracksStock() ? "Now: " + Inventory.label(m) : "Stock is not tracked yet. Enter what you have now.")
                .setView(qty)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (dlg, w) -> {
                    Double units = RegimenParser.parseNumber(qty.getText().toString());
                    if (units == null || units <= 0) { toast("Enter how many units were added."); return; }
                    Inventory.refill(m, units);
                    saveAndSync();
                    toast(m.name + ": " + Inventory.label(m));
                    render();
                }).show();
    }
}
