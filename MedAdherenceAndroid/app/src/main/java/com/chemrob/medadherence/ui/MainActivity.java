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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.chemrob.medadherence.R;
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
import com.chemrob.medadherence.core.Profile;
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
 * The app. First launch asks for the patient's profile; after that there are four tabs:
 * Today (next dose, progress, take / skip), Medicines (with photos), Adherence (metrics and
 * reports) and Pharmacist (PIN-protected bulk import, evidence review, settings, alarm checks).
 */
public class MainActivity extends Activity {
    private enum Tab { TODAY, MEDICINES, ADHERENCE, PHARMACIST }
    private enum Mode { TABS, MED_FORM, PROFILE }

    /** A dose may be marked taken up to this long before its scheduled time. */
    private static final int EARLY_WINDOW_MIN = 120;
    private static final int REQ_NOTIFY = 1, REQ_PHOTO = 3, REQ_GALLERY = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView headerTitle, headerSub;
    private FrameLayout avatar;
    private LinearLayout body, nav;
    private ScrollView scroll;
    private final List<LinearLayout> navItems = new ArrayList<>();
    private Tab tab = Tab.TODAY;
    private Mode mode = Mode.TABS;
    private boolean pharmacistUnlocked;
    private int adherenceDays = 30;
    private String todaySignature = "";

    // Medicine form state (kept across the camera / gallery round trip).
    private Medication formMed;
    private boolean formIsNew;
    private Runnable formCapture;

    private AppData data() { return Store.get(this); }

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = Ui.hbox(this);
        int p = Ui.dp(this, 20);
        header.setPadding(p, Ui.dp(this, 18), p, Ui.dp(this, 6));
        LinearLayout titles = Ui.vbox(this);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        headerTitle = Ui.text(titles, "", 26, Ui.INK, true);
        headerSub = Ui.text(titles, "", 16, Ui.MUTED, false);
        avatar = new FrameLayout(this);
        header.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        avatar.setOnClickListener(v -> showProfileForm(false));
        root.addView(header);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        body = Ui.vbox(this);
        int bp = Ui.dp(this, 16);
        body.setPadding(bp, 0, bp, Ui.dp(this, 28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        nav = Ui.hbox(this);
        nav.setBackgroundColor(Ui.SURFACE);
        nav.setElevation(Ui.dp(this, 8));
        nav.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 10));
        String[] labels = {"Today", "Medicines", "Adherence", "Pharmacist"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_pill, R.drawable.ic_chart, R.drawable.ic_pharmacy};
        for (int i = 0; i < labels.length; i++) navItems.add(navItem(nav, labels[i], icons[i], Tab.values()[i]));
        root.addView(nav);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    private LinearLayout navItem(LinearLayout parent, String label, int icon, Tab t) {
        LinearLayout item = Ui.vbox(this);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout pill = new FrameLayout(this);
        ImageView iv = Ui.icon(this, icon, Ui.MUTED, 24);
        pill.addView(iv, new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
        item.addView(pill, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 32)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(this, 4);
        item.addView(tv, tl);
        item.setOnClickListener(v -> show(t));
        parent.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return item;
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlarmScheduler.syncAll(this);
        if (!data().profile.isComplete()) showProfileForm(true);
        else if (mode == Mode.TABS) render();
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
            if (tab == Tab.TODAY && mode == Mode.TABS && !todaySignature.equals(signature(LocalDateTime.now()))) render();
            handler.postDelayed(this, 30_000);
        }
    };

    @Override
    public void onBackPressed() {
        if (mode == Mode.PROFILE && !data().profile.isComplete()) { super.onBackPressed(); return; }
        if (mode != Mode.TABS) { mode = Mode.TABS; render(); return; }
        if (tab != Tab.TODAY) { show(Tab.TODAY); return; }
        super.onBackPressed();
    }

    private void show(Tab t) {
        tab = t;
        mode = Mode.TABS;
        render();
        scroll.scrollTo(0, 0);
    }

    private void render() {
        nav.setVisibility(View.VISIBLE);
        for (int i = 0; i < navItems.size(); i++) {
            boolean on = Tab.values()[i] == tab;
            LinearLayout item = navItems.get(i);
            FrameLayout pill = (FrameLayout) item.getChildAt(0);
            pill.setBackground(on ? Ui.rounded(this, Ui.PRIMARY_CONTAINER, 16) : null);
            ((ImageView) pill.getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(on ? Ui.ON_PRIMARY_CONTAINER : Ui.MUTED));
            TextView tv = (TextView) item.getChildAt(1);
            tv.setTextColor(on ? Ui.INK : Ui.MUTED);
            tv.setTypeface(on ? Ui.medium() : Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        drawAvatar();
        body.removeAllViews();
        switch (tab) {
            case TODAY: buildToday(); break;
            case MEDICINES: buildMedicines(); break;
            case ADHERENCE: buildAdherence(); break;
            case PHARMACIST: buildPharmacist(); break;
        }
    }

    private void drawAvatar() {
        avatar.removeAllViews();
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.PRIMARY_CONTAINER);
        avatar.setBackground(g);
        String n = data().profile.name.trim();
        if (n.isEmpty()) {
            ImageView iv = Ui.icon(this, R.drawable.ic_person, Ui.ON_PRIMARY_CONTAINER, 26);
            avatar.addView(iv, new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.CENTER));
            return;
        }
        String[] parts = n.split("\\s+");
        String initials = (parts[0].substring(0, 1) + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "")).toUpperCase(Locale.ROOT);
        TextView t = new TextView(this);
        t.setText(initials);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Ui.medium(), Typeface.BOLD);
        t.setTextColor(Ui.ON_PRIMARY_CONTAINER);
        t.setGravity(Gravity.CENTER);
        avatar.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void header(String title, String sub) {
        headerTitle.setText(title);
        headerSub.setText(sub);
        headerSub.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void saveAndSync() {
        Store.save(this);
        AlarmScheduler.syncAll(this);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, Ui.dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    // ================================================================== Profile

    /** The patient's profile. On first launch this is the only screen until a name is saved. */
    private void showProfileForm(boolean onboarding) {
        mode = Mode.PROFILE;
        nav.setVisibility(onboarding ? View.GONE : View.VISIBLE);
        drawAvatar();
        body.removeAllViews();
        Profile p = data().profile.copy();
        LocalDate today = LocalDate.now();

        if (onboarding) {
            header("Welcome", "Let's set up your profile first");
            LinearLayout hero = Ui.card(body, Ui.PRIMARY_CONTAINER);
            LinearLayout r = Ui.row(hero);
            r.addView(Ui.iconCircle(this, R.drawable.ic_person, Ui.PRIMARY, Ui.ON_PRIMARY, 56));
            Ui.text(r, "Your profile helps your pharmacist and doctor read your adherence reports, and keeps "
                    + "allergy and emergency details in one place.", 16, Ui.ON_PRIMARY_CONTAINER, false);
        } else {
            header("Profile", p.isComplete() ? p.summary(today) : "");
        }

        Ui.section(body, "About you");
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        EditText name = Ui.field(c, "Full name *", "e.g. Asha Devi", p.name,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText dob = Ui.textField(c, "Date of birth (yyyy-MM-dd)", "e.g. 1962-10-01", p.dateOfBirth);
        dob.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        TextView sexLabel = Ui.text(c, "Sex", 14, Ui.MUTED, true);
        ((LinearLayout.LayoutParams) sexLabel.getLayoutParams()).topMargin = Ui.dp(this, 14);
        LinearLayout sexRow = Ui.row(c);
        final String[] sex = {p.sex};
        List<Button> sexChips = new ArrayList<>();
        for (String s : new String[]{"Female", "Male", "Other"}) {
            Button chip = Ui.chip(sexRow, s, s.equals(p.sex), null);
            sexChips.add(chip);
            chip.setOnClickListener(v -> {
                sex[0] = sex[0].equals(s) ? "" : s;
                for (Button x : sexChips) restyleChip(x, x.getText().toString().equals(sex[0]));
            });
        }
        EditText phone = Ui.field(c, "Phone", "e.g. +91 98765 43210", p.phone, InputType.TYPE_CLASS_PHONE);

        Ui.section(body, "Health");
        LinearLayout h = Ui.card(body, Ui.SURFACE);
        EditText cond = Ui.textField(h, "Conditions", "e.g. Type 2 diabetes, high blood pressure", p.conditions);
        EditText allergy = Ui.textField(h, "Allergies", "e.g. Penicillin (leave empty if none)", p.allergies);
        EditText doctor = Ui.textField(h, "Doctor or pharmacy", "e.g. Dr Sharma, City Pharmacy", p.doctor);

        Ui.section(body, "Emergency contact");
        LinearLayout e = Ui.card(body, Ui.SURFACE);
        EditText eName = Ui.field(e, "Name", "e.g. Ravi (son)", p.emergencyName,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText ePhone = Ui.field(e, "Phone", "e.g. +91 91234 56789", p.emergencyPhone, InputType.TYPE_CLASS_PHONE);

        TextView err = Ui.text(body, "", 16, Ui.BAD, true);
        err.setVisibility(View.GONE);
        Ui.button(body, onboarding ? "Create profile" : "Save profile", Ui.PRIMARY, v -> {
            p.name = name.getText().toString().trim();
            p.dateOfBirth = dob.getText().toString().trim();
            p.sex = sex[0];
            p.phone = phone.getText().toString().trim();
            p.conditions = cond.getText().toString().trim();
            p.allergies = allergy.getText().toString().trim();
            p.doctor = doctor.getText().toString().trim();
            p.emergencyName = eName.getText().toString().trim();
            p.emergencyPhone = ePhone.getText().toString().trim();
            String problem = p.validate(LocalDate.now());
            if (problem != null) {
                err.setText(problem);
                err.setVisibility(View.VISIBLE);
                scroll.post(() -> scroll.smoothScrollTo(0, err.getTop()));
                return;
            }
            data().profile = p;
            Store.save(this);
            toast(onboarding ? "Welcome, " + p.firstName() + "!" : "Profile saved");
            show(onboarding && data().medications.isEmpty() ? Tab.MEDICINES : Tab.TODAY);
        }).getLayoutParams().height = Ui.dp(this, 64);
        if (!onboarding) Ui.button(body, "Cancel", Ui.SURFACE_VARIANT, v -> show(tab));
        scroll.scrollTo(0, 0);
    }

    private static void restyleChip(Button b, boolean selected) {
        b.setBackground(Ui.rounded(b.getContext(), selected ? Ui.PRIMARY : Ui.SURFACE_VARIANT, 18));
        b.setTextColor(selected ? Ui.ON_PRIMARY : Ui.INK);
    }

    // ================================================================== Today

    private void buildToday() {
        AppData d = data();
        LocalDateTime now = LocalDateTime.now();
        String greet = now.getHour() < 12 ? "Good morning" : now.getHour() < 17 ? "Good afternoon" : "Good evening";
        header(greet + ", " + d.profile.firstName(), now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")));

        if (d.medications.isEmpty()) {
            LinearLayout c = Ui.card(body, Ui.PRIMARY_CONTAINER);
            LinearLayout r = Ui.row(c);
            r.addView(Ui.iconCircle(this, R.drawable.ic_pill, Ui.PRIMARY, Ui.ON_PRIMARY, 56));
            Ui.text(r, "No medicines yet", 22, Ui.ON_PRIMARY_CONTAINER, true);
            Ui.text(c, "Add your medicines with their times and how long to take them. "
                    + "Your phone will ring at every dose.", 17, Ui.ON_PRIMARY_CONTAINER, false);
            Ui.button(c, "Add a medicine", Ui.PRIMARY, v -> editMedication(null));
            Ui.button(c, "Pharmacist: load a full prescription", Ui.SURFACE, v -> show(Tab.PHARMACIST));
            return;
        }

        for (Medication m : d.medications) {
            if (!Inventory.needsRefill(m, now)) continue;
            LinearLayout c = Ui.card(body, Ui.ALERT_BG);
            Ui.text(c, "Refill " + m.name + " soon", 18, Ui.INK, true);
            Ui.text(c, Inventory.label(m) + ". Contact your pharmacy so you don't run out.", 16, Ui.INK, false);
            Ui.button(c, "I have refilled it", Ui.PRIMARY, v -> askRefill(m));
        }

        List<ScheduledDose> doses = ScheduleEngine.doses(d, now.toLocalDate().atStartOfDay(), now.toLocalDate().plusDays(1).atStartOfDay());
        todaySignature = signature(now);

        // Hero: the dose that needs attention now, or the next one.
        ScheduledDose due = null, next = null;
        for (ScheduledDose x : ScheduleEngine.doses(d, now.minusMinutes(d.settings.graceMinutes), now.plusNanos(1)))
            if (ScheduleEngine.isDueNow(d, x, now)) { due = x; break; }
        for (ScheduledDose x : ScheduleEngine.doses(d, now, now.plusDays(7)))
            if (ScheduleEngine.statusOf(d, x, now) == DoseStatus.PENDING) { next = x; break; }
        if (due != null) heroDose(due, true, now);
        else if (next != null) heroDose(next, false, now);

        // Progress ring.
        int taken = 0, settled = 0;
        for (ScheduledDose x : doses) {
            DoseStatus s = ScheduleEngine.statusOf(d, x, now);
            if (s == DoseStatus.TAKEN) taken++;
            if (s != DoseStatus.PENDING && s != DoseStatus.SNOOZED) settled++;
        }
        LinearLayout prog = Ui.card(body, Ui.SURFACE);
        LinearLayout pr = Ui.row(prog);
        Ui.Ring ring = new Ui.Ring(this);
        ring.set(doses.isEmpty() ? 0 : (float) taken / doses.size(), taken + "/" + doses.size(), "today", Ui.GOOD);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 104));
        rl.rightMargin = Ui.dp(this, 18);
        pr.addView(ring, rl);
        LinearLayout pt = Ui.vbox(this);
        pr.addView(pt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Ui.text(pt, doses.isEmpty() ? "Nothing scheduled today"
                : taken == doses.size() ? "All done for today" : taken + " of " + doses.size() + " doses taken", 19, Ui.INK, true);
        double week = AdherenceCalculator.compute(d, now.toLocalDate().minusDays(6).atStartOfDay(), now).overall.takingPercent();
        TextView wk = Ui.text(pt, String.format(Locale.ROOT, "Last 7 days: %.0f%%", week), 16, Ui.colorFor(week), true);
        if (settled > taken) Ui.text(pt, (settled - taken) + " missed or skipped today", 15, Ui.MUTED, false);
        wk.setOnClickListener(v -> show(Tab.ADHERENCE));

        if (!doses.isEmpty()) Ui.section(body, "Today's schedule");
        for (ScheduledDose dose : doses) doseRow(dose, now);
    }

    private void heroDose(ScheduledDose dose, boolean due, LocalDateTime now) {
        LinearLayout c = Ui.card(body, due ? Ui.DUE_BG : Ui.PRIMARY_CONTAINER);
        int fg = due ? Ui.INK : Ui.ON_PRIMARY_CONTAINER;
        String when;
        if (due) when = "Due now  ·  " + TimeUtil.clock(dose.time);
        else {
            long mins = ChronoUnit.MINUTES.between(now, dose.time);
            when = "Next  ·  " + (mins >= 24 * 60 ? dose.time.format(DateTimeFormatter.ofPattern("EEE HH:mm"))
                    : TimeUtil.clock(dose.time) + (mins >= 60 ? "  (in " + mins / 60 + " h " + mins % 60 + " min)" : "  (in " + Math.max(0, mins) + " min)"));
        }
        TextView label = Ui.text(c, when.toUpperCase(Locale.ROOT), 14, due ? Ui.WARN : fg, true);
        label.setLetterSpacing(0.06f);
        LinearLayout r = Ui.row(c);
        ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = Ui.dp(this, 10);
        r.addView(Ui.drugImage(this, dose.med.photo, 88));
        LinearLayout t = Ui.vbox(this);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Ui.text(t, dose.med.name, 26, fg, true);
        if (!dose.med.dose.isEmpty()) Ui.text(t, dose.med.dose, 18, fg, false);
        if (!dose.med.instructions.isEmpty()) Ui.text(t, dose.med.instructions, 16, fg, false);
        if (!due) return;
        String key = dose.key();
        Button main = dose.med.observed
                ? Ui.button(c, "Take on camera", Ui.GOOD, v -> startActivity(ObserveActivity.intent(this, key)))
                : Ui.button(c, "I took it", Ui.GOOD, v -> recordAction(key, DoseStatus.TAKEN));
        main.getLayoutParams().height = Ui.dp(this, 68);
        main.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        LinearLayout a = Ui.row(c);
        Ui.button(a, "Snooze", Ui.SURFACE, v -> recordAction(key, DoseStatus.SNOOZED));
        Ui.button(a, "Skip", Ui.SURFACE, v -> recordAction(key, DoseStatus.SKIPPED));
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

        LinearLayout card = Ui.card(body, Ui.SURFACE);
        ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin = Ui.dp(this, 10);
        LinearLayout top = Ui.row(card);
        top.addView(Ui.drugImage(this, dose.med.photo, 56));
        LinearLayout t = Ui.vbox(this);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView time = Ui.text(t, TimeUtil.clock(dose.time), 15, Ui.PRIMARY, true);
        time.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        Ui.text(t, dose.med.name, 19, Ui.INK, true);
        if (!dose.med.dose.isEmpty()) Ui.text(t, dose.med.dose, 15, Ui.MUTED, false);

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
            default: badge = due ? "Due now" : "Upcoming"; color = due ? Ui.WARN : Ui.PRIMARY;
        }
        Ui.badge(top, badge, color);

        if (dose.med.observed) Ui.text(card, "Take this dose in front of the camera", 14, Ui.MUTED, false);
        if (rec != null && dose.med.observed && status == DoseStatus.TAKEN)
            Ui.text(card, "Verification: " + verificationLabel(rec.verification), 14, Ui.MUTED, false);

        boolean canAct = (status == DoseStatus.PENDING || status == DoseStatus.SNOOZED)
                && !now.isBefore(dose.time.minusMinutes(EARLY_WINDOW_MIN));
        boolean catchUp = status == DoseStatus.MISSED;
        if (!canAct && !catchUp) return;
        LinearLayout actions = Ui.row(card);
        String key = dose.key();
        if (dose.med.observed) Ui.button(actions, "Take on camera", Ui.GOOD, v -> startActivity(ObserveActivity.intent(this, key)));
        else Ui.button(actions, catchUp ? "Taken late" : "Take", Ui.GOOD, v -> recordAction(key, DoseStatus.TAKEN));
        Ui.button(actions, "Skip", Ui.SURFACE_VARIANT, v -> recordAction(key, DoseStatus.SKIPPED));
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
        if (status == DoseStatus.TAKEN) toast("Well done!");
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
        header("Medicines", d.medications.size() + (d.medications.size() == 1 ? " medicine" : " medicines"));
        Ui.button(body, "+  Add a medicine", Ui.PRIMARY, v -> withEditPermission(() -> editMedication(null)));
        LocalDateTime now = LocalDateTime.now();
        for (Medication m : d.medications) {
            LinearLayout card = Ui.card(body, Ui.SURFACE);
            LinearLayout top = Ui.row(card);
            top.addView(Ui.drugImage(this, m.photo, 72));
            LinearLayout t = Ui.vbox(this);
            top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Ui.text(t, m.name, 20, Ui.INK, true);
            if (!m.dose.isEmpty()) Ui.text(t, m.dose, 16, Ui.MUTED, false);
            LinearLayout badges = Ui.row(t);
            ((LinearLayout.LayoutParams) badges.getLayoutParams()).topMargin = Ui.dp(this, 6);
            badges.setGravity(Gravity.START);
            if (m.observed) Ui.badge(badges, "Observed", Ui.PRIMARY);
            if (!m.isActive()) Ui.badge(badges, "Paused", Ui.MUTED);
            if (m.end() != null && m.end().isBefore(LocalDate.now())) Ui.badge(badges, "Completed", Ui.GOOD);
            if (badges.getChildCount() > 0) ((LinearLayout.LayoutParams) badges.getChildAt(0).getLayoutParams()).leftMargin = 0;

            String course = m.durationDays > 0
                    ? m.durationDays + " days  ·  " + m.startDate + " to " + TimeUtil.date(m.end())
                    : "Ongoing since " + m.startDate;
            Ui.text(card, m.timesLabel() + "  ·  " + m.frequencyLabel(), 16, Ui.INK, false);
            Ui.text(card, course, 15, Ui.MUTED, false);
            if (!m.instructions.isEmpty()) Ui.text(card, m.instructions, 15, Ui.MUTED, false);
            if (m.tracksStock()) {
                boolean low = Inventory.needsRefill(m, now);
                Ui.text(card, (low ? "Refill soon: " : "Stock: ") + Inventory.label(m), 15, low ? Ui.BAD : Ui.MUTED, low);
            }

            LinearLayout a = Ui.row(card);
            Ui.button(a, "Edit", Ui.PRIMARY_CONTAINER, v -> withEditPermission(() -> editMedication(m)));
            Ui.button(a, "Refill", Ui.SURFACE_VARIANT, v -> askRefill(m));
            LinearLayout a2 = Ui.row(card);
            Ui.button(a2, m.isActive() ? "Pause" : "Resume", Ui.SURFACE_VARIANT, v -> withEditPermission(() -> {
                if (m.isActive()) m.pause(LocalDateTime.now()); else m.resume(LocalDateTime.now());
                saveAndSync();
                render();
            }));
            Ui.button(a2, "Delete", Ui.SURFACE_VARIANT, v -> withEditPermission(() -> confirm(
                    "Delete " + m.name + " and its dose history? To stop reminders but keep the history, use Pause.", () -> {
                        d.medications.remove(m);
                        d.records.removeIf(r -> r.medId.equals(m.id));
                        deletePhoto(m.photo);
                        saveAndSync();
                        render();
                    }))).setTextColor(Ui.BAD);
        }
    }

    private static void deletePhoto(String path) {
        if (path != null && !path.isEmpty()) //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
    }

    /** Add / edit form: photo, name, pick a frequency, pick a duration, save. */
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
        formMed = m;
        formIsNew = existing == null;
        buildForm(null);
    }

    private void buildForm(String error) {
        Medication m = formMed;
        boolean isNew = formIsNew;
        mode = Mode.MED_FORM;
        header(isNew ? "Add a medicine" : "Edit medicine", "Photo, name, times and how long to take it");
        body.removeAllViews();

        // Photo
        LinearLayout pc = Ui.card(body, Ui.SURFACE);
        LinearLayout pr = Ui.row(pc);
        pr.addView(Ui.drugImage(this, m.photo, 96));
        LinearLayout pt = Ui.vbox(this);
        pr.addView(pt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Ui.text(pt, "Medicine photo", 18, Ui.INK, true);
        Ui.text(pt, "Shown on the alarm so the right tablet is easy to recognise.", 15, Ui.MUTED, false);
        LinearLayout pa = Ui.row(pc);

        final EditText[] f = new EditText[8]; // name, dose, instr, times, start, days, stock, perDose
        formCapture = () -> {
            m.name = f[0].getText().toString().trim();
            m.dose = f[1].getText().toString().trim();
            m.instructions = f[2].getText().toString().trim();
            RegimenParser.Times t = RegimenParser.parseTimes(f[3].getText().toString());
            if (t.error == null) { m.times = t.times; if (t.everyNDays > 1) m.everyNDays = t.everyNDays; }
            m.startDate = f[4].getText().toString().trim();
            try { m.durationDays = Math.max(0, Integer.parseInt(f[5].getText().toString().trim())); } catch (NumberFormatException ignored) { }
            String st = f[6].getText().toString().trim();
            Double sv = RegimenParser.parseNumber(st);
            if (st.isEmpty()) m.stock = -1; else if (sv != null && sv >= 0) m.stock = sv;
            Double pv = RegimenParser.parseNumber(f[7].getText().toString());
            if (pv != null && pv > 0) m.unitsPerDose = pv;
        };
        Ui.button(pa, "Camera", Ui.PRIMARY_CONTAINER, v -> { formCapture.run(); startActivityForResult(PhotoActivity.intent(this), REQ_PHOTO); });
        Ui.button(pa, "Gallery", Ui.SURFACE_VARIANT, v -> {
            formCapture.run();
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_GALLERY);
        });
        if (!m.photo.isEmpty()) Ui.button(pa, "Remove", Ui.SURFACE_VARIANT, v -> { formCapture.run(); m.photo = ""; buildForm(null); });

        // Details
        Ui.section(body, "Medicine");
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        f[0] = Ui.textField(c, "Medicine name *", "e.g. Metformin", m.name);
        f[1] = Ui.textField(c, "Dose", "e.g. 500 mg, 1 tablet", m.dose);
        f[2] = Ui.textField(c, "Instructions", "e.g. after food", m.instructions);
        CheckBox observed = Ui.check(c, "Observed dose: take it in front of the camera", m.observed);
        observed.setOnCheckedChangeListener((btn, on) -> m.observed = on);

        Ui.section(body, "How often");
        LinearLayout o = Ui.card(body, Ui.SURFACE);
        LinearLayout r1 = Ui.row(o), r2 = Ui.row(o);
        ((LinearLayout.LayoutParams) r1.getLayoutParams()).topMargin = Ui.dp(this, -6);
        int i = 0;
        for (RegimenParser.Frequency p : RegimenParser.PRESETS) {
            boolean sel = m.everyNDays == p.everyNDays && m.times.equals(p.times);
            Ui.chip(i++ < 4 ? r1 : r2, p.code, sel, v -> {
                formCapture.run();
                m.times = new ArrayList<>(p.times);
                m.everyNDays = p.everyNDays;
                buildForm(null);
            });
        }
        f[3] = Ui.textField(o, "Times (24 h, edit freely)", "08:00 20:00", String.join(" ", m.times));
        Ui.text(o, m.everyNDays == 1 ? "Every day" : "Every " + m.everyNDays + " days", 15, Ui.MUTED, false);

        Ui.section(body, "How long");
        LinearLayout hl = Ui.card(body, Ui.SURFACE);
        f[4] = Ui.textField(hl, "Start date (yyyy-MM-dd)", "yyyy-MM-dd", m.startDate);
        LinearLayout sr = Ui.row(hl);
        String today = TimeUtil.date(LocalDate.now()), tomorrow = TimeUtil.date(LocalDate.now().plusDays(1));
        Ui.chip(sr, "Today", m.startDate.equals(today), v -> { formCapture.run(); m.startDate = today; buildForm(null); });
        Ui.chip(sr, "Tomorrow", m.startDate.equals(tomorrow), v -> { formCapture.run(); m.startDate = tomorrow; buildForm(null); });
        f[5] = Ui.field(hl, "Number of days (0 = ongoing)", "7", String.valueOf(m.durationDays), InputType.TYPE_CLASS_NUMBER);
        LinearLayout dr1 = Ui.row(hl), dr2 = Ui.row(hl);
        int[] durs = {3, 5, 7, 10, 14, 30, 0};
        for (int k = 0; k < durs.length; k++) {
            int dd = durs[k];
            Ui.chip(k < 4 ? dr1 : dr2, dd == 0 ? "Ongoing" : dd + " d", m.durationDays == dd, v -> {
                formCapture.run();
                m.durationDays = dd;
                buildForm(null);
            });
        }

        Ui.section(body, "Stock (optional)");
        LinearLayout sc = Ui.card(body, Ui.SURFACE);
        int dec = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        LinearLayout srow = Ui.row(sc);
        LinearLayout s1 = Ui.vbox(this), s2 = Ui.vbox(this);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        half.rightMargin = Ui.dp(this, 10);
        srow.addView(s1, half);
        srow.addView(s2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        f[6] = Ui.field(s1, "Units in stock", "e.g. 30", m.tracksStock() ? num(m.stock) : "", dec);
        f[7] = Ui.field(s2, "Units per dose", "1", num(m.unitsPerDose), dec);
        Ui.text(sc, "The app counts down each dose taken and warns before you run out.", 15, Ui.MUTED, false);

        if (error != null) Ui.text(body, error, 16, Ui.BAD, true);
        LinearLayout a = Ui.row(body);
        ((LinearLayout.LayoutParams) a.getLayoutParams()).topMargin = Ui.dp(this, 12);
        Ui.button(a, "Cancel", Ui.SURFACE_VARIANT, v -> show(Tab.MEDICINES));
        Ui.button(a, "Save", Ui.PRIMARY, v -> {
            formCapture.run();
            String err = null;
            RegimenParser.Times t = RegimenParser.parseTimes(f[3].getText().toString());
            String st = f[6].getText().toString().trim();
            Double sv = RegimenParser.parseNumber(st), pv = RegimenParser.parseNumber(f[7].getText().toString());
            if (m.name.isEmpty()) err = "Please enter the medicine name.";
            else if (t.error != null) err = t.error + ". Use times like 08:00 20:00.";
            else if (TimeUtil.parseDate(m.startDate) == null) err = "Start date must look like 2026-10-01.";
            else if (!st.isEmpty() && (sv == null || sv < 0)) err = "Stock must be a number of units, e.g. 30, or left blank.";
            else if (pv == null || pv <= 0) err = "Units per dose must be more than 0, e.g. 1 or 0.5.";
            if (err != null) { buildForm(err); return; }
            AppData d = data();
            int idx = -1;
            for (int k = 0; k < d.medications.size(); k++) if (d.medications.get(k).id.equals(m.id)) idx = k;
            if (idx >= 0) {
                String old = d.medications.get(idx).photo;
                if (!old.equals(m.photo)) deletePhoto(old);
                d.medications.set(idx, m);
            } else d.medications.add(m);
            saveAndSync();
            toast("Saved " + m.name);
            show(Tab.MEDICINES);
        });
        if (error != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        else scroll.scrollTo(0, 0);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent intent) {
        super.onActivityResult(req, result, intent);
        if (formMed == null || result != RESULT_OK || intent == null) return;
        String path = null;
        if (req == REQ_PHOTO) path = intent.getStringExtra(PhotoActivity.EXTRA_PATH);
        else if (req == REQ_GALLERY && intent.getData() != null) path = PhotoActivity.importImage(this, intent.getData());
        if (path == null) { if (req == REQ_GALLERY) toast("Could not use that picture."); return; }
        formMed.photo = path;
        buildForm(null);
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
        LinearLayout hr = Ui.row(hero);
        Ui.Ring ring = new Ui.Ring(this);
        ring.set((float) (o.takingPercent() / 100), String.format(Locale.ROOT, "%.0f%%", o.takingPercent()), "taken", Ui.colorFor(o.takingPercent()));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120));
        rl.rightMargin = Ui.dp(this, 18);
        hr.addView(ring, rl);
        LinearLayout ht = Ui.vbox(this);
        hr.addView(ht, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Ui.text(ht, o.category(), 22, Ui.INK, true);
        Ui.text(ht, o.taken + " of " + o.due + " doses taken", 16, Ui.MUTED, false);
        Ui.text(ht, o.missed + " missed  ·  " + o.skipped + " skipped", 16, Ui.MUTED, false);
        Ui.text(ht, "Streak: " + r.currentStreakDays + (r.currentStreakDays == 1 ? " day" : " days"), 16, Ui.PRIMARY, true);

        LinearLayout bars = Ui.card(body, Ui.SURFACE);
        Ui.bar(bars, "Doses taken", o.takingPercent(), null);
        Ui.bar(bars, "On time (±" + d.settings.onTimeWindowMinutes + " min)", o.timingPercent(), null);
        Ui.bar(bars, "Days fully covered", o.daysCoveredPercent(), "  (" + o.daysCovered + "/" + o.daysElapsed + ")");
        if (o.observedDue > 0)
            Ui.bar(bars, "Observed doses verified", o.verifiedPercent(), "  (" + o.observedVerified + "/" + o.observedDue + ")");

        Ui.section(body, "Last 14 days");
        LinearLayout hist = Ui.card(body, Ui.SURFACE);
        LinearLayout strip = Ui.row(hist);
        for (int i = 13; i >= 0; i--) {
            LocalDate day = now.toLocalDate().minusDays(i);
            LocalDateTime end = i == 0 ? now : day.plusDays(1).atStartOfDay().minusNanos(1);
            AdherenceStats ds = AdherenceCalculator.compute(d, day.atStartOfDay(), end).overall;
            TextView cell = new TextView(this);
            cell.setText(String.valueOf(day.getDayOfMonth()));
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            cell.setTypeface(Ui.medium());
            cell.setGravity(Gravity.CENTER);
            cell.setTextColor(ds.due == 0 ? Ui.MUTED : Ui.ON_STATUS);
            cell.setBackground(Ui.rounded(this, ds.due == 0 ? Ui.SURFACE_VARIANT : Ui.colorFor(ds.takingPercent()), 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1);
            lp.leftMargin = lp.rightMargin = Ui.dp(this, 1.5f);
            strip.addView(cell, lp);
        }
        Ui.text(hist, "Green: all taken  ·  Amber: some  ·  Red: most missed  ·  Grey: none due", 14, Ui.MUTED, false);

        if (!r.perMedication.isEmpty()) Ui.section(body, "By medicine");
        for (int k = 0; k < r.perMedication.size(); k++) {
            AdherenceStats s = r.perMedication.get(k);
            Medication m = d.medications.get(k);
            LinearLayout c = Ui.card(body, Ui.SURFACE);
            LinearLayout top = Ui.row(c);
            top.addView(Ui.drugImage(this, m.photo, 48));
            LinearLayout t = Ui.vbox(this);
            top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Ui.text(t, s.label, 18, Ui.INK, true);
            Ui.text(t, s.category(), 15, Ui.colorFor(s.takingPercent()), true);
            Ui.bar(c, "Doses taken", s.takingPercent(), "  (" + s.taken + "/" + s.due + ")");
            Ui.text(c, String.format(Locale.ROOT, "On time %.0f%%  ·  late %d  ·  missed %d  ·  skipped %d",
                    s.timingPercent(), s.late, s.missed, s.skipped), 14, Ui.MUTED, false);
        }

        LinearLayout a = Ui.row(body);
        ((LinearLayout.LayoutParams) a.getLayoutParams()).topMargin = Ui.dp(this, 8);
        Ui.button(a, "Share report", Ui.PRIMARY, v -> share("Medication adherence report", AdherenceCalculator.toText(d, r)));
        Ui.button(a, "Share dose log", Ui.SURFACE_VARIANT, v -> share("Dose log (CSV)", AdherenceCalculator.doseLogCsv(d, from, now)));
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
            LinearLayout r = Ui.row(c);
            r.addView(Ui.iconCircle(this, R.drawable.ic_pharmacy, Ui.PRIMARY_CONTAINER, Ui.ON_PRIMARY_CONTAINER, 56));
            Ui.text(r, "Load a full prescription, review observed doses, and change settings.", 16, Ui.INK, false);
            EditText pin = Ui.field(c, "PIN", "Default PIN is 0000", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            TextView msg = Ui.text(c, "", 15, Ui.BAD, true);
            Ui.button(c, "Unlock", Ui.PRIMARY, v -> {
                if (pin.getText().toString().equals(d.settings.pharmacistPin)) { pharmacistUnlocked = true; render(); }
                else msg.setText("Wrong PIN");
            });
            return;
        }
        header("Pharmacist", "Prescription, review and settings");
        buildReviewQueue();
        buildImport();
        buildSettings();
        buildReliability();
        Ui.button(body, "Lock pharmacist mode", Ui.SURFACE_VARIANT, v -> { pharmacistUnlocked = false; render(); });
    }

    private void buildReviewQueue() {
        AppData d = data();
        List<DoseRecord> queue = new ArrayList<>();
        for (DoseRecord r : d.records)
            if (r.status == DoseStatus.TAKEN && r.verification == Verification.NEEDS_REVIEW) queue.add(r);
        queue.sort((a, b) -> b.scheduled.compareTo(a.scheduled));

        Ui.section(body, "Observed doses to review (" + queue.size() + ")");
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        if (queue.isEmpty()) Ui.text(c, "Nothing waiting. Doses that pass every camera check are verified automatically.", 16, Ui.MUTED, false);
        for (DoseRecord r : queue.subList(0, Math.min(10, queue.size()))) {
            Medication m = d.findMed(r.medId);
            Ui.text(c, (m != null ? m.name : "?") + "  ·  scheduled " + r.scheduled, 17, Ui.INK, true);
            Ui.text(c, String.format(Locale.ROOT, "Taken %s  ·  %s  ·  movement %.0f%%  ·  person in view %.0f%%",
                    r.actionAt, r.note, r.livenessScore * 100, r.presenceScore * 100), 14, Ui.MUTED, false);
            HorizontalScrollView hs = new HorizontalScrollView(this);
            LinearLayout thumbs = Ui.hbox(this);
            hs.addView(thumbs);
            c.addView(hs, Ui.matchWrap(this, 8));
            for (String path : r.evidence) thumbnail(thumbs, path);
            LinearLayout a = Ui.row(c);
            Ui.button(a, "Approve", Ui.GOOD, v -> { r.verification = Verification.PHARMACIST_APPROVED; Store.save(this); render(); });
            Ui.button(a, "Reject", Ui.BAD, v -> { r.verification = Verification.PHARMACIST_REJECTED; Store.save(this); render(); });
        }
    }

    private void thumbnail(LinearLayout parent, String path) {
        Bitmap bmp = Ui.loadBitmap(path, Ui.dp(this, 110));
        if (bmp == null) return;
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 110));
        lp.rightMargin = Ui.dp(this, 8);
        parent.addView(iv, lp);
        iv.setOnClickListener(v -> {
            ImageView full = new ImageView(this);
            full.setImageBitmap(BitmapFactory.decodeFile(path));
            full.setAdjustViewBounds(true);
            dialog().setView(full).setPositiveButton("Close", null).show();
        });
    }

    private void buildImport() {
        AppData d = data();
        Ui.section(body, "Load a full prescription");
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        Ui.text(c, "One medicine per line:\nName | Dose | Times or OD/BD/TDS/QID/HS/Q8H/WEEKLY | Days (0 = ongoing) | Start | Observed yes/no | Instructions | Stock | Units per dose", 14, Ui.MUTED, false);
        EditText box = Ui.field(c, "Prescription", "Metformin | 500 mg | BD | 30 | today | no | after food | 60 | 1\nRifampicin | 600 mg | 07:00 | 6m | today | yes | empty stomach",
                "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.setMinLines(5);
        box.setGravity(Gravity.TOP);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        CheckBox replace = Ui.check(c, "Replace the current medicines", false);
        TextView msg = Ui.text(c, "", 15, Ui.MUTED, false);
        LinearLayout a = Ui.row(c);
        Ui.button(a, "Import", Ui.PRIMARY, v -> {
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
        Ui.button(a, "Paste", Ui.SURFACE_VARIANT, v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
            if (clip != null && clip.getItemCount() > 0) box.setText(clip.getItemAt(0).coerceToText(this));
        });
        Ui.button(c, "Share prescription (to load on another phone)", Ui.SURFACE_VARIANT, v -> share("Medication regimen", RegimenParser.export(d.medications)));
    }

    private void buildSettings() {
        com.chemrob.medadherence.core.Settings s = data().settings;
        Ui.section(body, "Settings");
        LinearLayout c = Ui.card(body, Ui.SURFACE);

        TextView themeLabel = Ui.text(c, "Appearance", 14, Ui.MUTED, true);
        ((LinearLayout.LayoutParams) themeLabel.getLayoutParams()).topMargin = 0;
        LinearLayout tr = Ui.row(c);
        String[][] themes = {{"system", "Phone setting"}, {"light", "Light"}, {"dark", "Dark"}};
        for (String[] t : themes) {
            Ui.chip(tr, t[1], s.theme.equals(t[0]), v -> {
                if (s.theme.equals(t[0])) return;
                s.theme = t[0];
                Store.save(this);
                recreate();
            });
        }

        int numType = InputType.TYPE_CLASS_NUMBER;
        EditText grace = Ui.field(c, "Minutes before a dose counts as missed", "120", String.valueOf(s.graceMinutes), numType);
        EditText window = Ui.field(c, "On-time window, ± minutes", "60", String.valueOf(s.onTimeWindowMinutes), numType);
        EditText snooze = Ui.field(c, "Snooze minutes", "10", String.valueOf(s.snoozeMinutes), numType);
        EditText pin = Ui.field(c, "New pharmacist PIN (leave empty to keep)", "", "", numType | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        CheckBox lock = Ui.check(c, "Patient needs the PIN to change medicines", s.lockEditingWithPin);
        Ui.button(c, "Save settings", Ui.PRIMARY, v -> {
            s.graceMinutes = clamp(grace, s.graceMinutes, 15, 24 * 60);
            s.onTimeWindowMinutes = clamp(window, s.onTimeWindowMinutes, 5, 12 * 60);
            s.snoozeMinutes = clamp(snooze, s.snoozeMinutes, 1, 120);
            if (pin.getText().length() >= 4) s.pharmacistPin = pin.getText().toString();
            s.lockEditingWithPin = lock.isChecked();
            saveAndSync();
            toast("Settings saved");
            render();
        });
        Ui.button(c, "Edit patient profile", Ui.SURFACE_VARIANT, v -> showProfileForm(false));
    }

    private static int clamp(EditText e, int fallback, int lo, int hi) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(e.getText().toString().trim()))); }
        catch (NumberFormatException ex) { return fallback; }
    }

    /** Checks that decide whether alarms actually ring, each with a button to fix it. */
    private void buildReliability() {
        Ui.section(body, "Alarm reliability");
        LinearLayout c = Ui.card(body, Ui.SURFACE);
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
        check(c, checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED, "Camera allowed", () ->
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 2));
        Ui.button(c, "Test: ring in 1 minute", Ui.PRIMARY, v -> {
            AlarmScheduler.scheduleTest(this);
            toast("Lock the phone. It should ring in about a minute.");
        });
    }

    private void check(LinearLayout parent, boolean ok, String label, Runnable fix) {
        LinearLayout r = Ui.row(parent);
        ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = Ui.dp(this, 6);
        ImageView iv = ok ? Ui.icon(this, R.drawable.ic_check, Ui.GOOD, 22) : Ui.icon(this, R.drawable.ic_alarm, Ui.BAD, 22);
        ((LinearLayout.LayoutParams) iv.getLayoutParams()).rightMargin = Ui.dp(this, 12);
        r.addView(iv);
        Ui.text(r, label, 16, ok ? Ui.INK : Ui.BAD, !ok);
        if (!ok) {
            Button b = Ui.button(r, "Fix", Ui.PRIMARY_CONTAINER, v -> fix.run());
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            lp.weight = 0;
            lp.width = Ui.dp(this, 76);
            lp.height = Ui.dp(this, 44);
            lp.topMargin = 0;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (tab == Tab.PHARMACIST && mode == Mode.TABS) render();
    }

    // ================================================================== dialogs

    private void confirm(String message, Runnable onYes) {
        dialog().setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dlg, w) -> onYes.run()).show();
    }

    private void askPin(String title, Runnable onOk) {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("PIN");
        dialog().setTitle(title).setView(pin)
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
        dialog().setTitle("Refill " + m.name)
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
