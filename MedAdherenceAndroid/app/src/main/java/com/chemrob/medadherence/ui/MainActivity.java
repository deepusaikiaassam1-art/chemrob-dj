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
import com.chemrob.medadherence.alarm.Notifications;
import com.chemrob.medadherence.core.AdherenceCalculator;
import com.chemrob.medadherence.core.Backup;
import com.chemrob.medadherence.core.Caregiver;
import com.chemrob.medadherence.core.I18n;
import com.chemrob.medadherence.core.JsonCodec;
import com.chemrob.medadherence.core.AdherenceStats;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.Appointment;
import com.chemrob.medadherence.core.DoseRecord;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.Inventory;
import com.chemrob.medadherence.core.Medication;
import com.chemrob.medadherence.core.Profile;
import com.chemrob.medadherence.core.RegimenParser;
import com.chemrob.medadherence.core.Rewards;
import com.chemrob.medadherence.core.ScheduleEngine;
import com.chemrob.medadherence.core.ScheduledDose;
import com.chemrob.medadherence.core.TimeUtil;
import com.chemrob.medadherence.core.Verification;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.chemrob.medadherence.core.I18n.t;
import static com.chemrob.medadherence.core.I18n.tf;

/**
 * The app. First launch asks for the patient's profile; after that there are four tabs:
 * Today (next dose, progress, take / skip), Medicines (with photos), Adherence (metrics and
 * reports) and Pharmacist (PIN-protected bulk import, evidence review, settings, alarm checks).
 */
public class MainActivity extends Activity {
    private enum Tab { TODAY, MEDICINES, DOCTOR, ADHERENCE, PHARMACIST }
    private enum Mode { TABS, MED_FORM, PROFILE, VISIT_FORM }

    /** A dose may be marked taken up to this long before its scheduled time. */
    private static final int EARLY_WINDOW_MIN = 120;
    private static final int REQ_NOTIFY = 1, REQ_PHOTO = 3, REQ_GALLERY = 4, REQ_SOS = 5, REQ_FACE = 6;
    private static final int REQ_BACKUP = 7, REQ_RESTORE = 8, REQ_SAVE_PDF = 9;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView headerTitle, headerSub;
    private FrameLayout avatar;
    private LinearLayout body, nav;
    private ScrollView scroll;
    private final List<LinearLayout> navItems = new ArrayList<>();
    private Tab tab = Tab.TODAY;
    private Mode mode = Mode.TABS;
    private boolean pharmacistUnlocked;
    private char[] backupPassword;
    private File pendingPdf;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int adherenceDays = 30;
    private String todaySignature = "";

    // Profile form state (kept across the face-scan round trip).
    private Profile profileDraft;
    private boolean profileOnboarding;
    private Runnable profileCapture;

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
        TextView sos = new TextView(this);
        sos.setText(t("SOS"));
        sos.setTextColor(android.graphics.Color.WHITE);
        sos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sos.setTypeface(Ui.medium(), Typeface.BOLD);
        sos.setGravity(Gravity.CENTER);
        sos.setBackground(Ui.rounded(this, android.graphics.Color.parseColor("#D93F3F"), 24));
        sos.setElevation(Ui.dp(this, 3));
        sos.setContentDescription("Emergency: call and text your emergency contact");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 48));
        sl.rightMargin = Ui.dp(this, 10);
        header.addView(sos, sl);
        sos.setOnClickListener(v -> startSos());
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
        String[] labels = {"Today", "Medicines", "Doctor", "Adherence", "Pharmacy"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_pill, R.drawable.ic_calendar, R.drawable.ic_chart, R.drawable.ic_pharmacy};
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
        item.addView(pill, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 32)));
        TextView tv = new TextView(this);
        tv.setText(t(label));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
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
            case DOCTOR: buildDoctor(); break;
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
        headerTitle.setText(t(title));
        headerSub.setText(sub == null ? null : t(sub));
        headerSub.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void saveAndSync() {
        Store.save(this);
        AlarmScheduler.syncAll(this);
    }

    private void toast(String s) { Toast.makeText(this, t(s), Toast.LENGTH_SHORT).show(); }

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
        if (profileDraft == null) profileDraft = data().profile.copy();
        Profile p = profileDraft;
        profileOnboarding = onboarding;
        LocalDate today = LocalDate.now();

        if (onboarding) {
            header("Welcome", "Let's set up your profile first");
            LinearLayout hero = Ui.card(body, Ui.PRIMARY_CONTAINER);
            LinearLayout r = Ui.row(hero);
            r.addView(Ui.iconCircle(this, R.drawable.ic_person, Ui.PRIMARY, Ui.ON_PRIMARY, 56));
            Ui.text(r, "Your profile helps your pharmacist and doctor read your adherence reports, and keeps "
                    + "allergy and emergency details in one place.", 16, Ui.ON_PRIMARY_CONTAINER, false);
            languagePicker(hero);
        } else {
            header("Profile", p.isComplete() ? p.summary(today) : "");
        }

        Ui.section(body, "Face scan");
        LinearLayout fc = Ui.card(body, Ui.SURFACE);
        LinearLayout fr = Ui.row(fc);
        Bitmap face = Ui.loadBitmap(p.facePhoto, Ui.dp(this, 84));
        if (face != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(face);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84));
            il.rightMargin = Ui.dp(this, 14);
            fr.addView(iv, il);
        } else fr.addView(Ui.iconCircle(this, R.drawable.ic_person, Ui.PRIMARY_CONTAINER, Ui.ON_PRIMARY_CONTAINER, 84));
        LinearLayout ft = Ui.vbox(this);
        fr.addView(ft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        boolean recog = p.hasFaceRecognition();
        Ui.text(ft, recog ? "Face recognition on" : p.hasFace() ? "Face saved - scan again" : "Face scan needed", 18,
                recog ? Ui.GOOD : Ui.INK, true);
        Ui.text(ft, recog ? tf("Learned from %d views. The app recognises you during camera-observed doses, "
                        + "like a phone's face unlock. It stays on this phone.", p.faceEmbeddings.size())
                : "A short guided scan (look straight, turn a little each way, blink) so the app can recognise "
                        + "you during camera-observed doses. It stays on this phone.", 15, Ui.MUTED, false);
        Ui.button(fc, recog ? "Scan again" : "Scan my face", recog ? Ui.SURFACE_VARIANT : Ui.PRIMARY, v -> {
            if (profileCapture != null) profileCapture.run();
            startActivityForResult(FaceEnrollActivity.intent(this), REQ_FACE);
        });

        Ui.section(body, "About you");
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        EditText name = Ui.field(c, "Full name *", "e.g. Asha Devi", p.name,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final String[] dob = {p.dateOfBirth};
        birthDateField(c, dob);
        TextView sexLabel = Ui.text(c, "Sex", 14, Ui.MUTED, true);
        ((LinearLayout.LayoutParams) sexLabel.getLayoutParams()).topMargin = Ui.dp(this, 14);
        LinearLayout sexRow = Ui.row(c);
        final String[] sex = {p.sex};
        List<Button> sexChips = new ArrayList<>();
        for (String s : new String[]{"Female", "Male", "Other"}) {
            Button chip = Ui.chip(sexRow, s, s.equals(p.sex), null);
            chip.setTag(s); // the label is translated; the stored value stays English
            sexChips.add(chip);
            chip.setOnClickListener(v -> {
                sex[0] = sex[0].equals(s) ? "" : s;
                for (Button x : sexChips) restyleChip(x, x.getTag().equals(sex[0]));
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

        Ui.section(body, "Caregiver");
        LinearLayout cg = Ui.card(body, Ui.SURFACE);
        Ui.text(cg, "A family member or nurse who is told when a dose is missed. Leave empty to use the emergency contact.", 15, Ui.MUTED, false);
        EditText cName = Ui.field(cg, "Name", "e.g. Priya (daughter)", p.caregiverName,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText cPhone = Ui.field(cg, "Phone (WhatsApp or SMS)", "e.g. +91 90000 12345", p.caregiverPhone, InputType.TYPE_CLASS_PHONE);

        profileCapture = () -> {
            p.name = name.getText().toString().trim();
            p.dateOfBirth = dob[0];
            p.sex = sex[0];
            p.phone = phone.getText().toString().trim();
            p.conditions = cond.getText().toString().trim();
            p.allergies = allergy.getText().toString().trim();
            p.doctor = doctor.getText().toString().trim();
            p.emergencyName = eName.getText().toString().trim();
            p.emergencyPhone = ePhone.getText().toString().trim();
            p.caregiverName = cName.getText().toString().trim();
            p.caregiverPhone = cPhone.getText().toString().trim();
        };
        TextView err = Ui.text(body, "", 16, Ui.BAD, true);
        err.setVisibility(View.GONE);
        Ui.button(body, onboarding ? "Create profile" : "Save profile", Ui.PRIMARY, v -> {
            p.name = name.getText().toString().trim();
            p.dateOfBirth = dob[0];
            p.sex = sex[0];
            p.phone = phone.getText().toString().trim();
            p.conditions = cond.getText().toString().trim();
            p.allergies = allergy.getText().toString().trim();
            p.doctor = doctor.getText().toString().trim();
            p.emergencyName = eName.getText().toString().trim();
            p.emergencyPhone = ePhone.getText().toString().trim();
            p.caregiverName = cName.getText().toString().trim();
            p.caregiverPhone = cPhone.getText().toString().trim();
            String problem = p.validate(LocalDate.now());
            if (problem == null && onboarding && !p.hasFace() && FaceEnrollActivity.hasFrontCamera())
                problem = "Please scan your face (at the top) to finish your profile.";
            if (problem != null) {
                err.setText(t(problem));
                err.setVisibility(View.VISIBLE);
                scroll.post(() -> scroll.smoothScrollTo(0, err.getTop()));
                return;
            }
            data().profile = p;
            profileDraft = null;
            saveAndSync();
            if (!p.emergencyPhone.isEmpty()) askSosPermissions();
            toast(onboarding ? tf("Welcome, %s!", p.firstName()) : t("Profile saved"));
            show(onboarding && data().medications.isEmpty() ? Tab.MEDICINES : Tab.TODAY);
        }).getLayoutParams().height = Ui.dp(this, 64);
        if (!onboarding) Ui.button(body, "Cancel", Ui.SURFACE_VARIANT, v -> { profileDraft = null; show(tab); });
        scroll.scrollTo(0, 0);
    }

    /** Date of birth: a tap opens day / month / year wheels (no typing, no date format to learn). */
    private void birthDateField(LinearLayout parent, String[] value) {
        TextView label = Ui.text(parent, "Date of birth", 14, Ui.MUTED, true);
        ((LinearLayout.LayoutParams) label.getLayoutParams()).topMargin = Ui.dp(this, 14);
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setBackground(Ui.rounded(this, Ui.SURFACE_VARIANT, 14));
        b.setStateListAnimator(null);
        int pad = Ui.dp(this, 14);
        b.setPadding(pad, pad, pad, pad);
        b.setCompoundDrawablePadding(Ui.dp(this, 12));
        android.graphics.drawable.Drawable cal = getDrawable(R.drawable.ic_calendar).mutate();
        cal.setTint(Ui.PRIMARY);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(cal, null, null, null);
        Runnable show = () -> {
            LocalDate d = TimeUtil.parseDate(value[0]);
            if (d == null) {
                b.setText(t("Tap to choose"));
                b.setTextColor(Ui.MUTED);
            } else {
                String when = d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", I18n.locale()));
                Integer age = ageOn(d, LocalDate.now());
                b.setText(age == null ? when : tf("%s (%d years)", when, age));
                b.setTextColor(Ui.INK);
            }
        };
        show.run();
        b.setOnClickListener(v -> pickBirthDate(value[0], picked -> { value[0] = picked; show.run(); }));
        LinearLayout.LayoutParams lp = Ui.matchWrap(this, 6);
        parent.addView(b, lp);
    }

    private static Integer ageOn(LocalDate dob, LocalDate today) {
        return dob.isAfter(today) ? null : java.time.Period.between(dob, today).getYears();
    }

    private void pickBirthDate(String current, java.util.function.Consumer<String> done) {
        LocalDate today = LocalDate.now();
        LocalDate init = TimeUtil.parseDate(current);
        if (init == null || init.isAfter(today)) init = LocalDate.of(today.getYear() - 60, 1, 1);

        LinearLayout box = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        LinearLayout wheels = Ui.hbox(this);
        wheels.setGravity(Gravity.CENTER);
        box.addView(wheels);
        android.widget.NumberPicker day = wheel(wheels, "Day", 1, 31, init.getDayOfMonth());
        android.widget.NumberPicker month = wheel(wheels, "Month", 1, 12, init.getMonthValue());
        String[] months = new String[12];
        for (int i = 0; i < 12; i++)
            months[i] = java.time.Month.of(i + 1).getDisplayName(java.time.format.TextStyle.SHORT, I18n.locale());
        month.setDisplayedValues(months);
        android.widget.NumberPicker year = wheel(wheels, "Year", today.getYear() - 120, today.getYear(), init.getYear());
        TextView age = Ui.text(box, "", 18, Ui.PRIMARY, true);
        age.setGravity(Gravity.CENTER);
        Runnable update = () -> {
            int len = java.time.YearMonth.of(year.getValue(), month.getValue()).lengthOfMonth();
            day.setMaxValue(len);
            LocalDate d = LocalDate.of(year.getValue(), month.getValue(), Math.min(day.getValue(), len));
            Integer a = ageOn(d, today);
            age.setText(a == null ? t("Date of birth can't be in the future.") : tf("Age: %d years", a));
            age.setTextColor(a == null ? Ui.BAD : Ui.PRIMARY);
        };
        android.widget.NumberPicker.OnValueChangeListener l = (w, o, n) -> update.run();
        day.setOnValueChangedListener(l);
        month.setOnValueChangedListener(l);
        year.setOnValueChangedListener(l);
        update.run();

        new AlertDialog.Builder(this)
                .setTitle(t("Date of birth"))
                .setView(box)
                .setPositiveButton(t("Done"), (dlg, w) -> {
                    LocalDate d = LocalDate.of(year.getValue(), month.getValue(), day.getValue());
                    if (d.isAfter(today)) { toast("Date of birth can't be in the future."); return; }
                    done.accept(TimeUtil.date(d));
                })
                .setNeutralButton(t("Clear"), (dlg, w) -> done.accept(""))
                .setNegativeButton(t("Cancel"), null)
                .show();
    }

    private android.widget.NumberPicker wheel(LinearLayout parent, String label, int min, int max, int value) {
        LinearLayout col = Ui.vbox(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        parent.addView(col, lp);
        Ui.text(col, label, 14, Ui.MUTED, true).setGravity(Gravity.CENTER);
        android.widget.NumberPicker w = new android.widget.NumberPicker(this);
        w.setMinValue(min);
        w.setMaxValue(max);
        w.setValue(value);
        w.setWrapSelectorWheel(!"Year".equals(label));
        w.setDescendantFocusability(android.widget.NumberPicker.FOCUS_BLOCK_DESCENDANTS); // no keyboard pops up
        if (Build.VERSION.SDK_INT >= 29) w.setTextSize(Ui.dp(this, 24));
        col.addView(w, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return w;
    }

    private static void restyleChip(Button b, boolean selected) {
        b.setBackground(Ui.rounded(b.getContext(), selected ? Ui.PRIMARY : Ui.SURFACE_VARIANT, 18));
        b.setTextColor(selected ? Ui.ON_PRIMARY : Ui.INK);
    }

    // ================================================================== Today

    private static boolean hasObservedMed(AppData d) {
        for (Medication m : d.medications) if (m.observed) return true;
        return false;
    }

    private void buildToday() {
        AppData d = data();
        LocalDateTime now = LocalDateTime.now();
        String greet = now.getHour() < 12 ? "Good morning, %s" : now.getHour() < 17 ? "Good afternoon, %s" : "Good evening, %s";
        header(tf(greet, d.profile.firstName()), now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", I18n.locale())));

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

        if (!d.profile.hasFaceRecognition() && FaceEnrollActivity.hasFrontCamera() && hasObservedMed(d)) {
            LinearLayout c = Ui.card(body, Ui.PRIMARY_CONTAINER);
            Ui.text(c, "New: face recognition", 18, Ui.ON_PRIMARY_CONTAINER, true);
            Ui.text(c, "Scan your face once more so the app can recognise you, not just see a face, "
                    + "during camera-observed doses.", 16, Ui.ON_PRIMARY_CONTAINER, false);
            Ui.button(c, "Scan my face", Ui.PRIMARY, v -> showProfileForm(false));
        }

        for (Medication m : d.medications) {
            if (!Inventory.needsRefill(m, now)) continue;
            LinearLayout c = Ui.card(body, Ui.ALERT_BG);
            Ui.text(c, tf("Refill %s soon", m.name), 18, Ui.INK, true);
            Ui.text(c, tf("%s. Contact your pharmacy so you don't run out.", Inventory.label(m)), 16, Ui.INK, false);
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
        Appointment visit = Appointment.next(d.appointments, now);
        if (visit != null && visit.time().isBefore(now.plusDays(14))) visitCard(body, visit, now, false);

        rewardsCard(d, now);

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
        ring.set(doses.isEmpty() ? 0 : (float) taken / doses.size(), taken + "/" + doses.size(), t("today"), Ui.GOOD);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 104));
        rl.rightMargin = Ui.dp(this, 18);
        pr.addView(ring, rl);
        LinearLayout pt = Ui.vbox(this);
        pr.addView(pt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Ui.text(pt, doses.isEmpty() ? "Nothing scheduled today"
                : taken == doses.size() ? t("All done for today") : tf("%d of %d doses taken", taken, doses.size()), 19, Ui.INK, true);
        double week = AdherenceCalculator.compute(d, now.toLocalDate().minusDays(6).atStartOfDay(), now).overall.takingPercent();
        TextView wk = Ui.text(pt, tf("Last 7 days: %.0f%%", week), 16, Ui.colorFor(week), true);
        if (settled > taken) Ui.text(pt, tf("%d missed or skipped today", settled - taken), 15, Ui.MUTED, false);
        wk.setOnClickListener(v -> show(Tab.ADHERENCE));

        if (!doses.isEmpty()) Ui.section(body, "Today's schedule");
        for (ScheduledDose dose : doses) doseRow(dose, now);
    }

    private void heroDose(ScheduledDose dose, boolean due, LocalDateTime now) {
        LinearLayout c = Ui.card(body, due ? Ui.DUE_BG : Ui.PRIMARY_CONTAINER);
        int fg = due ? Ui.INK : Ui.ON_PRIMARY_CONTAINER;
        String when;
        if (due) when = t("Due now") + "  ·  " + TimeUtil.clock(dose.time);
        else {
            long mins = ChronoUnit.MINUTES.between(now, dose.time);
            when = t("Next") + "  ·  " + (mins >= 24 * 60 ? dose.time.format(DateTimeFormatter.ofPattern("EEE HH:mm", I18n.locale()))
                    : TimeUtil.clock(dose.time) + "  " + (mins >= 60 ? tf("(in %d h %d min)", mins / 60, mins % 60) : tf("(in %d min)", Math.max(0, mins))));
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
                String at = rec != null && rec.actionTime() != null ? TimeUtil.clock(rec.actionTime()) : "";
                badge = tf(late ? "Late %s" : "Taken %s", at).trim();
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
            Ui.text(card, tf("Verification: %s", t(verificationLabel(rec.verification))), 14, Ui.MUTED, false);

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
        Rewards.State before = status == DoseStatus.TAKEN ? Rewards.compute(data(), LocalDateTime.now()) : null;
        if (status == DoseStatus.TAKEN) {
            ScheduledDose d = ScheduleEngine.find(data(), key);
            if (d != null) Voice.sayTake(this, d.med);
        }
        AlarmReceiver.record(this, key, status);
        render();
        if (before != null) celebrate(before, Rewards.compute(data(), LocalDateTime.now()));
    }

    // ================================================================== Rewards (the game layer)

    /** Level, points, streak and the next badge, on Today. Tapping it opens the badges. */
    private void rewardsCard(AppData d, LocalDateTime now) {
        Rewards.State s = Rewards.compute(d, now);
        LinearLayout c = Ui.card(body, Ui.PRIMARY);
        c.setOnClickListener(v -> show(Tab.ADHERENCE));
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.iconCircle(this, R.drawable.ic_trophy, Ui.ON_PRIMARY, Ui.PRIMARY, 56));
        LinearLayout t = Ui.vbox(this);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Ui.text(t, tf("Level %d · %s", s.level, t(s.levelName)), 21, Ui.ON_PRIMARY, true);
        Ui.text(t, tf("%d points", s.points), 16, Ui.ON_PRIMARY, false);
        LinearLayout streak = Ui.hbox(this);
        streak.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 10);
        streak.setPadding(pad, Ui.dp(this, 6), pad + Ui.dp(this, 4), Ui.dp(this, 6));
        streak.setBackground(Ui.rounded(this, translucent(Ui.ON_PRIMARY, 40), 20));
        streak.addView(Ui.icon(this, R.drawable.ic_flame, s.currentStreak > 0 ? 0xFFFFB020 : Ui.ON_PRIMARY, 24));
        TextView n = new TextView(this);
        n.setText(String.valueOf(s.currentStreak));
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        n.setTypeface(Ui.medium(), Typeface.BOLD);
        n.setTextColor(Ui.ON_PRIMARY);
        n.setPadding(Ui.dp(this, 4), 0, 0, 0);
        streak.addView(n);
        streak.setContentDescription(tf("%d-day streak", s.currentStreak));
        top.addView(streak);

        xpBar(c, s.levelProgress(), Ui.ON_PRIMARY, translucent(Ui.ON_PRIMARY, 50));
        Ui.text(c, s.nextLevelAt < 0 ? t("Top level reached") : tf("%d points to Level %d", s.nextLevelAt - s.points, s.level + 1),
                14, Ui.ON_PRIMARY, false);
        Rewards.Badge next = s.nextBadge();
        if (next != null) Ui.text(c, tf("Next badge: %s (%d/%d)", t(next.title), next.progress, next.target), 15, Ui.ON_PRIMARY, true);
    }

    private static int translucent(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private void xpBar(LinearLayout parent, double fraction, int fillColor, int trackColor) {
        FrameLayout track = new FrameLayout(this);
        track.setBackground(Ui.rounded(this, trackColor, 7));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 14));
        tl.topMargin = Ui.dp(this, 14);
        parent.addView(track, tl);
        View fill = new View(this);
        fill.setBackground(Ui.rounded(this, fillColor, 7));
        track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        double f = Math.max(0, Math.min(1, fraction));
        track.post(() -> {
            fill.getLayoutParams().width = (int) (track.getWidth() * f);
            fill.requestLayout();
        });
    }

    /** Badge collection on the Adherence tab: earned ones in colour, the rest with their progress. */
    private void badges(Rewards.State s) {
        Ui.section(body, tf("Badges (%d of %d)", s.earnedCount(), s.badges.size()));
        LinearLayout row = null;
        for (int i = 0; i < s.badges.size(); i++) {
            Rewards.Badge b = s.badges.get(i);
            if (i % 2 == 0) {
                row = Ui.row(body);
                ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = Ui.dp(this, 10);
            }
            LinearLayout cell = Ui.vbox(this);
            cell.setBackground(Ui.rounded(this, b.earned() ? Ui.GOOD_BG : Ui.SURFACE, 20));
            int p = Ui.dp(this, 14);
            cell.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i % 2 == 0) lp.rightMargin = Ui.dp(this, 5); else lp.leftMargin = Ui.dp(this, 5);
            row.addView(cell, lp);
            cell.addView(Ui.iconCircle(this, b.earned() ? R.drawable.ic_trophy : R.drawable.ic_lock,
                    b.earned() ? Ui.GOOD : Ui.SURFACE_VARIANT, b.earned() ? Ui.ON_STATUS : Ui.MUTED, 44));
            TextView title = Ui.text(cell, b.title, 17, Ui.INK, true);
            ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = Ui.dp(this, 8);
            Ui.text(cell, b.description, 14, Ui.MUTED, false);
            if (b.earned()) Ui.text(cell, "Earned", 14, Ui.GOOD, true);
            else {
                xpBar(cell, (double) b.progress / b.target, Ui.PRIMARY, Ui.SURFACE_VARIANT);
                Ui.text(cell, b.progress + " / " + b.target, 14, Ui.MUTED, false);
            }
        }
        if (row != null && row.getChildCount() == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    /** Points earned, a level-up and new badges, shown after "I took it". */
    private void celebrate(Rewards.State before, Rewards.State after) {
        int gained = after.points - before.points;
        if (gained <= 0) return;
        LinearLayout v = Ui.vbox(this);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(this, 24);
        v.setPadding(p, p, p, Ui.dp(this, 8));
        FrameLayout star = Ui.iconCircle(this, R.drawable.ic_trophy, Ui.GOOD, Ui.ON_STATUS, 88);
        ((LinearLayout.LayoutParams) star.getLayoutParams()).rightMargin = 0;
        v.addView(star);
        TextView pts = Ui.text(v, tf("+%d points", gained), 34, Ui.PRIMARY, true);
        pts.setGravity(Gravity.CENTER);
        Ui.text(v, "Well done!", 20, Ui.INK, true).setGravity(Gravity.CENTER);
        if (after.currentStreak > 0)
            Ui.text(v, tf("%d-day streak", after.currentStreak), 17, Ui.WARN, true).setGravity(Gravity.CENTER);
        if (after.level > before.level)
            Ui.text(v, tf("Level up! You are now Level %d · %s", after.level, t(after.levelName)), 18, Ui.GOOD, true).setGravity(Gravity.CENTER);
        for (Rewards.Badge b : Rewards.newlyEarned(before, after))
            Ui.text(v, tf("New badge: %s", t(b.title)), 18, Ui.GOOD, true).setGravity(Gravity.CENTER);
        AlertDialog dlg = new AlertDialog.Builder(this).setView(v).setPositiveButton(t("Great!"), null).show();
        // A small pop so the moment feels rewarding.
        star.setScaleX(0.3f);
        star.setScaleY(0.3f);
        star.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start();
        pts.setAlpha(0f);
        pts.setTranslationY(Ui.dp(this, 16));
        pts.animate().alpha(1f).translationY(0).setStartDelay(200).setDuration(400).start();
        scroll.postDelayed(() -> { if (dlg.isShowing() && !isFinishing()) dlg.dismiss(); }, 6000);
    }

    // ================================================================== Medicines

    private boolean editingLocked() { return data().settings.lockEditingWithPin && !pharmacistUnlocked; }

    private void withEditPermission(Runnable r) {
        if (!editingLocked()) { r.run(); return; }
        askPin("Pharmacist PIN required to change the medicines", r);
    }

    private void buildMedicines() {
        AppData d = data();
        header("Medicines", d.medications.size() == 1 ? t("1 medicine") : tf("%d medicines", d.medications.size()));
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
                    ? tf("%d days", m.durationDays) + "  ·  " + tf("%s to %s", m.startDate, TimeUtil.date(m.end()))
                    : tf("Ongoing since %s", m.startDate);
            Ui.text(card, m.timesLabel() + "  ·  " + (m.everyNDays == 1 ? t("daily") : m.everyNDays == 7 ? t("weekly")
                    : tf("every %d days", m.everyNDays)), 16, Ui.INK, false);
            Ui.text(card, course, 15, Ui.MUTED, false);
            if (!m.instructions.isEmpty()) Ui.text(card, m.instructions, 15, Ui.MUTED, false);
            if (m.tracksStock()) {
                boolean low = Inventory.needsRefill(m, now);
                Ui.text(card, tf(low ? "Refill soon: %s" : "Stock: %s", Inventory.label(m)), 15, low ? Ui.BAD : Ui.MUTED, low);
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
                    tf("Delete %s and its dose history? To stop reminders but keep the history, use Pause.", m.name), () -> {
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
        Ui.text(o, m.everyNDays == 1 ? t("Every day") : tf("Every %d days", m.everyNDays), 15, Ui.MUTED, false);

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
            Ui.chip(k < 4 ? dr1 : dr2, dd == 0 ? t("Ongoing") : tf("%d d", dd), m.durationDays == dd, v -> {
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
            else if (t.error != null) err = tf("%s. Use times like 08:00 20:00.", t(t.error));
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
            toast(tf("Saved %s", m.name));
            show(Tab.MEDICINES);
        });
        if (error != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        else scroll.scrollTo(0, 0);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent intent) {
        super.onActivityResult(req, result, intent);
        if (req == REQ_FACE) {
            if (result == RESULT_OK && intent != null && profileDraft != null) {
                profileDraft.facePhoto = intent.getStringExtra(FaceEnrollActivity.EXTRA_PATH);
                profileDraft.faceSignature = intent.getDoubleArrayExtra(FaceEnrollActivity.EXTRA_SIGNATURE);
                profileDraft.faceEmbeddings = FaceEnrollActivity.embeddings(intent);
                toast(profileDraft.hasFaceRecognition() ? "Face learned" : "Face saved");
            }
            if (profileDraft != null) showProfileForm(profileOnboarding);
            return;
        }
        if (req == REQ_BACKUP || req == REQ_RESTORE || req == REQ_SAVE_PDF) {
            if (result == RESULT_OK && intent != null && intent.getData() != null) {
                if (req == REQ_BACKUP) doBackup(intent.getData());
                else if (req == REQ_RESTORE) confirmRestore(intent.getData());
                else savePdf(intent.getData());
            }
            return;
        }
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

    // ================================================================== Emergency

    private void startSos() {
        Profile p = data().profile;
        if (com.chemrob.medadherence.core.Emergency.number(p) == null) {
            dialog().setTitle(t("Add an emergency contact"))
                    .setMessage(t("The SOS button calls and texts your emergency contact. Add their name and phone number in your profile."))
                    .setNegativeButton(t("Not now"), null)
                    .setPositiveButton(t("Open profile"), (dlg, w) -> showProfileForm(false)).show();
            return;
        }
        startActivity(EmergencyActivity.intent(this));
    }

    private void askSosPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : EmergencyActivity.permissions())
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) missing.add(perm);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_SOS);
    }

    // ================================================================== Doctor follow-ups

    private void buildDoctor() {
        AppData d = data();
        LocalDateTime now = LocalDateTime.now();
        header("Doctor follow-ups", d.profile.doctor.isEmpty() ? "Visits and check-ups" : d.profile.doctor);
        Ui.button(body, "+  Add a follow-up", Ui.PRIMARY, v -> editVisit(null));

        List<Appointment> upcoming = new ArrayList<>(), past = new ArrayList<>();
        for (Appointment a : d.appointments) (a.isUpcoming(now) ? upcoming : past).add(a);
        upcoming.sort((x, y) -> x.when.compareTo(y.when));
        past.sort((x, y) -> y.when.compareTo(x.when));

        if (upcoming.isEmpty()) {
            LinearLayout c = Ui.card(body, Ui.SURFACE);
            LinearLayout r = Ui.row(c);
            r.addView(Ui.iconCircle(this, R.drawable.ic_calendar, Ui.PRIMARY_CONTAINER, Ui.ON_PRIMARY_CONTAINER, 52));
            Ui.text(r, "No upcoming visits. Add the date your doctor asked you to come back, and the phone "
                    + "will remind you the day before and 2 hours before.", 16, Ui.MUTED, false);
        } else Ui.section(body, "Upcoming");
        for (Appointment a : upcoming) visitCard(body, a, now, true);
        if (!past.isEmpty()) Ui.section(body, "Past");
        for (Appointment a : past.subList(0, Math.min(10, past.size()))) visitCard(body, a, now, true);
    }

    private void visitCard(LinearLayout parent, Appointment a, LocalDateTime now, boolean actions) {
        boolean upcoming = a.isUpcoming(now);
        LinearLayout c = Ui.card(parent, upcoming && !actions ? Ui.PRIMARY_CONTAINER : Ui.SURFACE);
        int fg = upcoming && !actions ? Ui.ON_PRIMARY_CONTAINER : Ui.INK;
        LinearLayout r = Ui.row(c);
        r.addView(Ui.iconCircle(this, R.drawable.ic_calendar, upcoming ? Ui.PRIMARY : Ui.SURFACE_VARIANT,
                upcoming ? Ui.ON_PRIMARY : Ui.MUTED, 52));
        LinearLayout t = Ui.vbox(this);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LocalDateTime when = a.time();
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), when.toLocalDate());
        String rel = !upcoming ? t(a.done ? "Done" : "Past") : days == 0 ? t("Today") : days == 1 ? t("Tomorrow") : tf("In %d days", days);
        TextView label = Ui.text(t, (actions ? "" : t("Doctor follow-up") + "  \u00b7  ") + rel, 14, upcoming ? Ui.PRIMARY : Ui.MUTED, true);
        if (!actions) label.setTextColor(fg);
        Ui.text(t, when.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", I18n.locale())), 19, fg, true);
        Ui.text(t, a.who(), 16, fg, false);
        if (!a.purpose.isEmpty()) Ui.text(t, a.purpose, 15, actions ? Ui.MUTED : fg, false);
        if (!actions) {
            c.setOnClickListener(v -> show(Tab.DOCTOR));
            return;
        }
        LinearLayout b = Ui.row(c);
        Ui.button(b, "Edit", Ui.PRIMARY_CONTAINER, v -> editVisit(a));
        if (upcoming) Ui.button(b, "Mark done", Ui.SURFACE_VARIANT, v -> { a.done = true; saveAndSync(); render(); });
        Ui.button(b, "Delete", Ui.SURFACE_VARIANT, v -> dialog().setMessage(t("Delete this follow-up?"))
                .setNegativeButton(t("Cancel"), null)
                .setPositiveButton(t("Delete"), (dlg, w) -> { data().appointments.remove(a); saveAndSync(); render(); })
                .show()).setTextColor(Ui.BAD);
    }

    private void editVisit(Appointment existing) {
        Appointment a = existing != null ? existing.copy() : new Appointment();
        if (existing == null) {
            a.when = TimeUtil.minute(LocalDate.now().plusDays(7).atTime(10, 0));
            a.doctor = data().profile.doctor;
        }
        buildVisitForm(a, existing, null);
    }

    private void buildVisitForm(Appointment a, Appointment existing, String error) {
        mode = Mode.VISIT_FORM;
        header(existing == null ? "Add a follow-up" : "Edit follow-up", "Date, time and doctor");
        body.removeAllViews();
        LocalDateTime cur = a.time() != null ? a.time() : LocalDate.now().plusDays(7).atTime(10, 0);

        Ui.section(body, "When");
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        EditText date = Ui.textField(c, "Date (yyyy-MM-dd)", "e.g. 2026-10-05", TimeUtil.date(cur.toLocalDate()));
        date.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        LinearLayout dr = Ui.row(c);
        int[][] quick = {{7, 0}, {14, 0}, {30, 0}, {90, 0}};
        String[] ql = {"1 week", "2 weeks", "1 month", "3 months"};
        EditText[] timeRef = new EditText[1];
        for (int i = 0; i < quick.length; i++) {
            int days = quick[i][0];
            Ui.chip(dr, ql[i], false, v -> {
                date.setText(TimeUtil.date(LocalDate.now().plusDays(days)));
            });
        }
        timeRef[0] = Ui.textField(c, "Time (24 h)", "e.g. 10:30", TimeUtil.clock(cur));
        timeRef[0].setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);

        Ui.section(body, "Doctor");
        LinearLayout dc = Ui.card(body, Ui.SURFACE);
        EditText doctor = Ui.textField(dc, "Doctor", "e.g. Dr Sharma", a.doctor);
        EditText place = Ui.textField(dc, "Hospital / clinic", "e.g. City Hospital, OPD 3", a.place);
        EditText purpose = Ui.textField(dc, "Purpose / what to bring", "e.g. Diabetes review, bring blood report", a.purpose);

        if (error != null) Ui.text(body, error, 16, Ui.BAD, true);
        LinearLayout r = Ui.row(body);
        ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = Ui.dp(this, 12);
        Ui.button(r, "Cancel", Ui.SURFACE_VARIANT, v -> show(Tab.DOCTOR));
        Ui.button(r, "Save", Ui.PRIMARY, v -> {
            String clock = TimeUtil.normalizeClock(timeRef[0].getText().toString());
            a.when = date.getText().toString().trim() + " " + (clock == null ? timeRef[0].getText().toString().trim() : clock);
            a.doctor = doctor.getText().toString().trim();
            a.place = place.getText().toString().trim();
            a.purpose = purpose.getText().toString().trim();
            String err = a.validate(LocalDateTime.now());
            if (err != null) { buildVisitForm(a, existing, err); return; }
            List<Appointment> list = data().appointments;
            if (existing != null) list.set(list.indexOf(existing), a); else list.add(a);
            saveAndSync();
            toast("Follow-up saved. You'll be reminded the day before and 2 hours before.");
            show(Tab.DOCTOR);
        });
        if (error != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        else scroll.scrollTo(0, 0);
    }

    // ================================================================== Adherence

    private void buildAdherence() {
        AppData d = data();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = adherenceDays > 0 ? now.toLocalDate().minusDays(adherenceDays - 1).atStartOfDay() : earliestStart();
        AdherenceCalculator.Report r = AdherenceCalculator.compute(d, from, now);
        header("Adherence", adherenceDays > 0 ? tf("Last %d days", adherenceDays) : t("Since the first dose"));

        LinearLayout periods = Ui.row(body);
        for (int p : new int[]{7, 30, 90, 0}) {
            Ui.chip(periods, p == 0 ? t("All") : tf("%d days", p), adherenceDays == p, v -> { adherenceDays = p; render(); });
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
        Ui.text(ht, tf("%d of %d doses taken", o.taken, o.due), 16, Ui.MUTED, false);
        Ui.text(ht, tf("%d missed", o.missed) + "  ·  " + tf("%d skipped", o.skipped), 16, Ui.MUTED, false);
        Ui.text(ht, r.currentStreakDays == 1 ? t("Streak: 1 day") : tf("Streak: %d days", r.currentStreakDays), 16, Ui.PRIMARY, true);

        LinearLayout bars = Ui.card(body, Ui.SURFACE);
        Ui.bar(bars, "Doses taken", o.takingPercent(), null);
        Ui.bar(bars, tf("On time (±%d min)", d.settings.onTimeWindowMinutes), o.timingPercent(), null);
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
            Ui.text(c, tf("On time %.0f%%  ·  late %d  ·  missed %d  ·  skipped %d",
                    s.timingPercent(), s.late, s.missed, s.skipped), 14, Ui.MUTED, false);
        }

        badges(Rewards.compute(d, now));

        LinearLayout a = Ui.row(body);
        ((LinearLayout.LayoutParams) a.getLayoutParams()).topMargin = Ui.dp(this, 8);
        Ui.button(a, "PDF report", Ui.PRIMARY, v -> pdfReport(from, LocalDateTime.now()));
        Ui.button(a, "Share as text", Ui.SURFACE_VARIANT, v -> share("Medication adherence report", AdherenceCalculator.toText(d, r)));
        LinearLayout a2 = Ui.row(body);
        Ui.button(a2, "Share dose log", Ui.SURFACE_VARIANT, v -> share("Dose log (CSV)", AdherenceCalculator.doseLogCsv(d, from, now)));
        if (!d.profile.caregiverNumber().isEmpty())
            Ui.button(a2, "Send to caregiver", Ui.SURFACE_VARIANT, v -> sendToCaregiver(
                    Caregiver.dailySummary(d, LocalDate.now(), LocalDateTime.now())));
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

    /** WhatsApp or SMS to the caregiver, with the message filled in (the patient taps Send). */
    private void sendToCaregiver(String message) {
        String number = data().profile.caregiverNumber();
        new AlertDialog.Builder(this)
                .setTitle(tf("Send to %s", data().profile.caregiverLabel()))
                .setMessage(message)
                .setPositiveButton(t("WhatsApp"), (dlg, w) -> open(Notifications.whatsappIntent(number, message)))
                .setNeutralButton(t("SMS"), (dlg, w) -> open(Notifications.smsIntent(number, message)))
                .setNegativeButton(t("Cancel"), null).show();
    }

    private void open(Intent i) {
        try { startActivity(i); } catch (android.content.ActivityNotFoundException e) { toast(t("No app can open this.")); }
    }

    // ------------------------------------------------------------------ background work

    private interface Job { Object run() throws Exception; }
    private interface Done { void done(Object result, Exception error); }

    /** Runs slow work (files, encryption) off the main thread behind a small "please wait" dialog. */
    private void background(String message, Job job, Done done) {
        AlertDialog wait = new AlertDialog.Builder(this).setMessage(message).setCancelable(false).show();
        io.execute(() -> {
            Object r = null;
            Exception err = null;
            try { r = job.run(); } catch (Exception e) { err = e; }
            final Object fr = r;
            final Exception fe = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                wait.dismiss();
                done.done(fr, fe);
            });
        });
    }

    private void alert(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(t("OK"), null).show();
    }

    // ------------------------------------------------------------------ PDF report

    private void pdfReport(LocalDateTime from, LocalDateTime now) {
        AppData d = data();
        background(t("Creating the PDF report..."), () -> PdfReport.write(this, d, from, now, ShareProvider.dir(this)), (r, e) -> {
            if (e != null) {
                android.util.Log.e("MedAdherence", "PDF failed", e);
                alert(t("Could not create the report"), String.valueOf(e.getMessage()));
                return;
            }
            File f = (File) r;
            pendingPdf = f;
            new AlertDialog.Builder(this)
                    .setTitle(t("PDF report ready"))
                    .setMessage(t("Share it with the doctor or pharmacist (WhatsApp, e-mail, print), or save it on the phone."))
                    .setPositiveButton(t("Share"), (x, w) -> shareFile(f, "application/pdf", t("PDF report")))
                    .setNeutralButton(t("Save to phone"), (x, w) -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
                            .putExtra(Intent.EXTRA_TITLE, f.getName()), REQ_SAVE_PDF))
                    .setNegativeButton(t("Close"), null).show();
        });
    }

    private void shareFile(File f, String type, String title) {
        Uri u = ShareProvider.uri(f);
        Intent send = new Intent(Intent.ACTION_SEND).setType(type)
                .putExtra(Intent.EXTRA_STREAM, u)
                .putExtra(Intent.EXTRA_SUBJECT, f.getName())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(f.getName(), u));
        startActivity(Intent.createChooser(send, title));
    }

    private void savePdf(Uri dest) {
        File f = pendingPdf;
        if (f == null || !f.exists()) return;
        background(t("Saving..."), () -> {
            try (InputStream in = new FileInputStream(f); OutputStream out = getContentResolver().openOutputStream(dest)) {
                if (out == null) throw new IOException("Cannot write there");
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return null;
        }, (r, e) -> toast(e == null ? t("Report saved") : t("Could not save the report")));
    }

    // ------------------------------------------------------------------ backup

    private void buildBackup() {
        Ui.section(body, "Backup and restore");
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        Ui.text(c, "Save the medicines, dose history, profile and photos to one file (for example on Google Drive "
                + "or a memory card), and bring everything back on a new phone.", 15, Ui.MUTED, false);
        EditText pw = Ui.field(c, "Password (recommended)", "You will need it to restore", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Ui.button(c, "Back up to a file", Ui.PRIMARY, v -> {
            backupPassword = pw.getText().toString().toCharArray();
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, "MedAdherence-backup-" + TimeUtil.date(LocalDate.now()) + ".mabackup"), REQ_BACKUP);
        });
        Ui.button(c, "Restore from a file", Ui.SURFACE_VARIANT, v -> {
            backupPassword = pw.getText().toString().toCharArray();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_RESTORE);
        });
    }

    private void doBackup(Uri uri) {
        char[] pw = backupPassword == null ? new char[0] : backupPassword;
        backupPassword = null;
        String json = JsonCodec.toJson(data());
        background(t("Backing up..."), () -> {
            try (OutputStream raw = getContentResolver().openOutputStream(uri)) {
                if (raw == null) throw new IOException("Cannot write there");
                BufferedOutputStream out = new BufferedOutputStream(raw);
                Backup.write(out, json, getFilesDir(), pw);
                out.flush();
            }
            return null;
        }, (r, e) -> {
            if (e != null) {
                android.util.Log.e("MedAdherence", "Backup failed", e);
                alert(t("Backup failed"), String.valueOf(e.getMessage()));
            } else alert(t("Backup saved"), pw.length > 0
                    ? t("Keep the password safe: the backup cannot be opened without it.")
                    : t("This backup has no password: anyone with the file can read the patient's data. Keep it private."));
        });
    }

    private void confirmRestore(Uri uri) {
        char[] pw = backupPassword;
        backupPassword = null;
        new AlertDialog.Builder(this)
                .setTitle(t("Restore this backup?"))
                .setMessage(t("Everything on this phone (medicines, history, profile and photos) will be replaced by the backup."))
                .setPositiveButton(t("Restore"), (dlg, w) -> doRestore(uri, pw))
                .setNegativeButton(t("Cancel"), null).show();
    }

    private void doRestore(Uri uri, char[] pw) {
        File staging = new File(getFilesDir(), "restore_tmp");
        Store.deleteTree(staging);
        background(t("Restoring..."), () -> {
            String json;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot read the file");
                json = Backup.read(new BufferedInputStream(in), pw, staging, getFilesDir());
            }
            Store.restore(this, json, staging);
            return null;
        }, (r, e) -> {
            if (e != null) Store.deleteTree(staging);
            if (e instanceof Backup.BackupException && ((Backup.BackupException) e).wrongPassword) {
                EditText field = new EditText(this);
                field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                field.setHint(t("Backup password"));
                new AlertDialog.Builder(this)
                        .setTitle(pw == null || pw.length == 0 ? t("This backup has a password") : t("Wrong password"))
                        .setView(field)
                        .setPositiveButton(t("Restore"), (dlg, w) -> doRestore(uri, field.getText().toString().toCharArray()))
                        .setNegativeButton(t("Cancel"), null).show();
                return;
            }
            if (e != null) {
                android.util.Log.e("MedAdherence", "Restore failed", e);
                alert(t("Could not restore"), e instanceof Backup.BackupException ? t(e.getMessage()) : String.valueOf(e.getMessage()));
                return;
            }
            AlarmScheduler.syncAll(this);
            toast(t("Backup restored"));
            recreate();
        });
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
                else msg.setText(t("Wrong PIN"));
            });
            return;
        }
        header("Pharmacist", "Prescription, review and settings");
        buildReviewQueue();
        buildImport();
        buildSettings();
        buildBackup();
        buildReliability();
        Ui.button(body, "Lock pharmacist mode", Ui.SURFACE_VARIANT, v -> { pharmacistUnlocked = false; render(); });
    }

    private void buildReviewQueue() {
        AppData d = data();
        List<DoseRecord> queue = new ArrayList<>();
        for (DoseRecord r : d.records)
            if (r.status == DoseStatus.TAKEN && r.verification == Verification.NEEDS_REVIEW) queue.add(r);
        queue.sort((a, b) -> b.scheduled.compareTo(a.scheduled));

        Ui.section(body, tf("Observed doses to review (%d)", queue.size()));
        LinearLayout c = Ui.card(body, Ui.SURFACE);
        if (queue.isEmpty()) Ui.text(c, "Nothing waiting. Doses that pass every camera check are verified automatically.", 16, Ui.MUTED, false);
        for (DoseRecord r : queue.subList(0, Math.min(10, queue.size()))) {
            Medication m = d.findMed(r.medId);
            Ui.text(c, (m != null ? m.name : "?") + "  ·  " + tf("scheduled %s", r.scheduled), 17, Ui.INK, true);
            Ui.text(c, tf("Taken %s", r.actionAt) + "  \u00b7  " + r.note, 14, Ui.MUTED, false);
            if (!d.profile.facePhoto.isEmpty()) Ui.text(c, "First photo (blue frame) is the enrolled face.", 13, Ui.MUTED, false);
            HorizontalScrollView hs = new HorizontalScrollView(this);
            LinearLayout thumbs = Ui.hbox(this);
            hs.addView(thumbs);
            c.addView(hs, Ui.matchWrap(this, 8));
            if (!d.profile.facePhoto.isEmpty()) {
                thumbnail(thumbs, d.profile.facePhoto);
                if (thumbs.getChildCount() > 0) {
                    View enrolled = thumbs.getChildAt(0);
                    enrolled.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
                    enrolled.setBackground(Ui.rounded(this, Ui.PRIMARY, 6));
                    enrolled.setContentDescription("Enrolled face");
                }
            }
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
            dialog().setView(full).setPositiveButton(t("Close"), null).show();
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
            if (res.medications.isEmpty() && res.errors.isEmpty()) { msg.setText(t("Type or paste at least one line.")); return; }
            if (!res.medications.isEmpty()) {
                if (replace.isChecked()) d.medications.clear();
                d.medications.addAll(res.medications);
                saveAndSync();
            }
            msg.setText(tf("Imported %d medicine(s).", res.medications.size()) + (res.errors.isEmpty() ? "" : "\n" + String.join("\n", res.errors)));
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

        languagePicker(c);

        CheckBox voice = Ui.check(c, "Voice guidance: speak instructions when taking a dose", s.voiceGuidance);
        voice.setOnCheckedChangeListener((btn, on) -> {
            s.voiceGuidance = on;
            Store.save(this);
            if (on) Voice.say(this, "Voice guidance is on.");
        });

        TextView cgLabel = Ui.text(c, "Caregiver alerts", 14, Ui.MUTED, true);
        ((LinearLayout.LayoutParams) cgLabel.getLayoutParams()).topMargin = Ui.dp(this, 16);
        String who = data().profile.caregiverLabel();
        if (data().profile.caregiverNumber().isEmpty())
            Ui.text(c, "Add a caregiver or emergency contact in the patient profile to use these.", 15, Ui.MUTED, false);
        CheckBox missedAlert = Ui.check(c, who.isEmpty() ? t("Offer to tell the caregiver when a dose is missed")
                : tf("Offer to tell %s when a dose is missed", who), s.caregiverMissedAlerts);
        missedAlert.setOnCheckedChangeListener((btn, on) -> {
            s.caregiverMissedAlerts = on;
            s.caregiverLastCheck = TimeUtil.second(LocalDateTime.now()); // only doses missed from now on
            saveAndSync();
        });
        CheckBox daily = Ui.check(c, tf("Evening summary for the caregiver at %02d:00", s.summaryHour), s.caregiverDailySummary);
        daily.setOnCheckedChangeListener((btn, on) -> { s.caregiverDailySummary = on; saveAndSync(); });

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

    /** Language chips (English, Hindi, Bengali, Assamese, or the phone's language). */
    private void languagePicker(LinearLayout c) {
        com.chemrob.medadherence.core.Settings s = data().settings;
        TextView label = Ui.text(c, "Language / भाषा / ভাষা", 14, Ui.MUTED, true);
        ((LinearLayout.LayoutParams) label.getLayoutParams()).topMargin = Ui.dp(this, 16);
        List<String[]> options = new ArrayList<>();
        options.add(new String[]{"system", t("Phone setting")});
        for (String[] l : I18n.LANGUAGES) options.add(l);
        LinearLayout row = null;
        for (int i = 0; i < options.size(); i++) {
            if (i % 2 == 0) row = Ui.row(c);
            String[] o = options.get(i);
            Button chip = Ui.chip(row, o[1], s.language.equals(o[0]), v -> {
                if (s.language.equals(o[0])) return;
                s.language = o[0];
                Store.save(this);
                Lang.apply(this, data());
                saveAndSync(); // notification texts follow the new language
                recreate();
            });
            chip.setText(o[1]); // names are shown in their own script
        }
        if (row != null && row.getChildCount() == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (!"en".equals(I18n.lang()))
            Ui.text(c, "Translations are new: please tell your pharmacist if a word is wrong.", 13, Ui.MUTED, false);
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
                .setNegativeButton(t("Cancel"), null)
                .setPositiveButton(t("Delete"), (dlg, w) -> onYes.run()).show();
    }

    private void askPin(String title, Runnable onOk) {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint(t("PIN"));
        dialog().setTitle(title).setView(pin)
                .setNegativeButton(t("Cancel"), null)
                .setPositiveButton(t("OK"), (dlg, w) -> {
                    if (pin.getText().toString().equals(data().settings.pharmacistPin)) {
                        pharmacistUnlocked = true;
                        onOk.run();
                    } else toast("Wrong PIN");
                }).show();
    }

    private void askRefill(Medication m) {
        EditText qty = new EditText(this);
        qty.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        qty.setHint(t("Units added, e.g. 30"));
        dialog().setTitle(tf("Refill %s", m.name))
                .setMessage(m.tracksStock() ? tf("Now: %s", Inventory.label(m)) : t("Stock is not tracked yet. Enter what you have now."))
                .setView(qty)
                .setNegativeButton(t("Cancel"), null)
                .setPositiveButton(t("Add"), (dlg, w) -> {
                    Double units = RegimenParser.parseNumber(qty.getText().toString());
                    if (units == null || units <= 0) { toast("Enter how many units were added."); return; }
                    Inventory.refill(m, units);
                    saveAndSync();
                    toast(m.name + ": " + Inventory.label(m));
                    render();
                }).show();
    }
}
