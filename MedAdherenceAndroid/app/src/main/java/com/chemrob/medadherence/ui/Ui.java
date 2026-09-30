package com.chemrob.medadherence.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chemrob.medadherence.R;
import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.core.I18n;

import java.io.File;

/**
 * The app's look, built in code: a light and a dark palette, large readable type, rounded cards
 * with soft shadows, big buttons, icons and a progress ring. No XML layouts or support libraries.
 * Every label passed to these helpers is translated (see {@link I18n}); names typed by the user
 * have no translation and are shown as they are.
 */
public final class Ui {
    private Ui() {}

    public static boolean dark;

    // Palette (set by apply()).
    public static int BG, SURFACE, SURFACE_VARIANT, PRIMARY, ON_PRIMARY, PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER;
    public static int INK, MUTED, LINE, GOOD, WARN, BAD, ON_STATUS, DUE_BG, ALERT_BG, GOOD_BG;
    /** Light-theme colours for surfaces that stay white in both themes (the camera sheet). */
    public static final int PRIMARY_LIGHT = 0xFF4F46E5, INK_LIGHT = 0xFF1A1C29, MUTED_LIGHT = 0xFF5E6275, GOOD_LIGHT = 0xFF1E9E63;
    /** Kept for older call sites: ACCENT is the primary colour, MUTED buttons render as tonal. */
    public static int ACCENT;

    static { setPalette(false); }

    /** Picks light or dark (patient's choice or the phone's setting). Call before super.onCreate. */
    public static void apply(Activity a) {
        String pref = Store.get(a).settings.theme;
        boolean systemDark = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setPalette("dark".equals(pref) || (!"light".equals(pref) && systemDark));
        a.setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
        fonts(a);
    }

    private static Typeface regularTf, boldTf;

    /**
     * Loads Atkinson Hyperlegible, a typeface made for readers with low vision (clear 1/l/I, 0/O).
     * Screens that do not use the app theme call this themselves. Indian scripts fall back to the
     * phone's own Noto fonts.
     */
    public static void fonts(Context c) {
        if (regularTf != null) return;
        try {
            Typeface family = c.getResources().getFont(R.font.atkinson);
            regularTf = Typeface.create(family, Typeface.NORMAL);
            boldTf = Typeface.create(family, Typeface.BOLD);
        } catch (Exception e) {
            regularTf = Typeface.DEFAULT;
            boldTf = Typeface.create("sans-serif-medium", Typeface.BOLD);
        }
    }

    public static Typeface regular() { return regularTf != null ? regularTf : Typeface.DEFAULT; }

    static void setPalette(boolean isDark) {
        dark = isDark;
        if (!isDark) {
            BG = c("#F4F5FB"); SURFACE = c("#FFFFFF"); SURFACE_VARIANT = c("#ECEEF8");
            PRIMARY = c("#4F46E5"); ON_PRIMARY = Color.WHITE; PRIMARY_CONTAINER = c("#E3E1FF"); ON_PRIMARY_CONTAINER = c("#1E1A6B");
            INK = c("#1A1C29"); MUTED = c("#5E6275"); LINE = c("#DCDEEA");
            GOOD = c("#1E9E63"); WARN = c("#C77C00"); BAD = c("#D93F3F"); ON_STATUS = Color.WHITE;
            DUE_BG = c("#FFF3D6"); ALERT_BG = c("#FDE5E5"); GOOD_BG = c("#DDF5E8");
        } else {
            BG = c("#111320"); SURFACE = c("#1B1E2E"); SURFACE_VARIANT = c("#262A3D");
            PRIMARY = c("#A5A0FF"); ON_PRIMARY = c("#1E1A6B"); PRIMARY_CONTAINER = c("#3A36A0"); ON_PRIMARY_CONTAINER = c("#E3E1FF");
            INK = c("#E7E8F2"); MUTED = c("#A3A7BD"); LINE = c("#33374C");
            GOOD = c("#4CD08E"); WARN = c("#F2B233"); BAD = c("#FF7A7A"); ON_STATUS = c("#111320");
            DUE_BG = c("#3A2F12"); ALERT_BG = c("#3D1F22"); GOOD_BG = c("#173327");
        }
        ACCENT = PRIMARY;
    }

    private static int c(String hex) { return Color.parseColor(hex); }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    private static RippleDrawable pressable(Context c, int color, float radiusDp) {
        int ripple = dark ? Color.argb(60, 255, 255, 255) : Color.argb(40, 0, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(ripple), rounded(c, color, radiusDp), null);
    }

    public static Typeface medium() { return boldTf != null ? boldTf : Typeface.create("sans-serif-medium", Typeface.NORMAL); }

    /**
     * A label with the second language underneath ("I took it" / "মই খালোঁ"), for the main buttons.
     * Just the label when no second language is set.
     */
    public static CharSequence twoLine(String english, float secondScale) {
        String main = I18n.t(english), second = I18n.t2(english);
        if (second == null || second.equals(main)) return main;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(main).append('\n');
        int start = sb.length();
        sb.append(second);
        sb.setSpan(new android.text.style.RelativeSizeSpan(secondScale), start, sb.length(), 0);
        sb.setSpan(new FontSpan(regular()), start, sb.length(), 0);
        return sb;
    }

    /** A text already in the main language, with the second-language version of another label under it. */
    public static CharSequence twoLine(String mainText, String secondKey, float secondScale) {
        String second = I18n.t2(secondKey);
        if (second == null || second.equals(mainText)) return mainText;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(mainText).append('\n');
        int start = sb.length();
        sb.append(second);
        sb.setSpan(new android.text.style.RelativeSizeSpan(secondScale), start, sb.length(), 0);
        sb.setSpan(new FontSpan(regular()), start, sb.length(), 0);
        return sb;
    }

    public static boolean hasSecond(String english) {
        String second = I18n.t2(english);
        return second != null && !second.equals(I18n.t(english));
    }

    /** Draws a span in a given typeface (TypefaceSpan(Typeface) needs Android 9). */
    static final class FontSpan extends android.text.style.MetricAffectingSpan {
        private final Typeface tf;
        FontSpan(Typeface tf) { this.tf = tf; }
        @Override public void updateDrawState(android.text.TextPaint p) { p.setTypeface(tf); p.setFakeBoldText(false); }
        @Override public void updateMeasureState(android.text.TextPaint p) { p.setTypeface(tf); p.setFakeBoldText(false); }
    }

    /**
     * A main action: a big button with the second language underneath. Taller than an ordinary
     * button so both lines stay large.
     */
    public static Button mainButton(ViewGroup parent, String label, int color, View.OnClickListener l) {
        Button b = button(parent, label, color, l);
        b.setText(twoLine(label, 0.72f));
        if (hasSecond(label)) {
            b.getLayoutParams().height += dp(parent.getContext(), 18);
            b.setLineSpacing(0, 1.0f);
        }
        return b;
    }

    public static LinearLayout.LayoutParams matchWrap(Context c, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private static boolean isRow(ViewGroup p) {
        return p instanceof LinearLayout && ((LinearLayout) p).getOrientation() == LinearLayout.HORIZONTAL;
    }

    /** A rounded card with a soft shadow that stacks its children. */
    public static LinearLayout card(ViewGroup parent, int color) {
        Context c = parent.getContext();
        LinearLayout card = vbox(c);
        card.setBackground(rounded(c, color, 22));
        card.setElevation(dark ? 0 : dp(c, 2));
        int p = dp(c, 18);
        card.setPadding(p, p, p, p);
        parent.addView(card, matchWrap(c, 14));
        return card;
    }

    public static TextView text(ViewGroup parent, CharSequence s, float sp, int color, boolean bold) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(s instanceof String ? I18n.t((String) s) : s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.12f);
        t.setTypeface(bold ? medium() : regular(), bold ? Typeface.BOLD : Typeface.NORMAL);
        if (isRow(parent)) parent.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        else parent.addView(t, matchWrap(c, 4));
        return t;
    }

    public static TextView heading(ViewGroup parent, String s) { return text(parent, s, 21, INK, true); }

    public static TextView muted(ViewGroup parent, CharSequence s) { return text(parent, s, 16, MUTED, false); }

    /** Section label above a group of cards. */
    public static TextView section(ViewGroup parent, String s) {
        TextView t = text(parent, I18n.t(s).toUpperCase(java.util.Locale.ROOT), 13, MUTED, true);
        t.setLetterSpacing(0.08f);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = dp(parent.getContext(), 22);
        return t;
    }

    /**
     * Large rounded button. Filled with {@code color}; MUTED, SURFACE_VARIANT and LINE render as a
     * quiet "tonal" button with dark text.
     */
    public static Button button(ViewGroup parent, String label, int color, View.OnClickListener l) {
        Context c = parent.getContext();
        Button b = new Button(c);
        b.setText(I18n.t(label));
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(medium());
        boolean tonal = color == MUTED || color == SURFACE_VARIANT || color == LINE;
        int bg = tonal ? SURFACE_VARIANT : color;
        int fg = tonal ? INK : color == PRIMARY ? ON_PRIMARY : color == PRIMARY_CONTAINER ? ON_PRIMARY_CONTAINER
                : color == SURFACE ? PRIMARY : ON_STATUS;
        b.setTextColor(fg);
        b.setBackground(pressable(c, bg, 18));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = isRow(parent)
                ? new LinearLayout.LayoutParams(0, dp(c, 58), 1)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 60));
        if (isRow(parent)) { lp.leftMargin = dp(c, 5); lp.rightMargin = dp(c, 5); }
        lp.topMargin = dp(c, 10);
        parent.addView(b, lp);
        return b;
    }

    /** Selectable pill for presets (frequency, duration, period). */
    public static Button chip(ViewGroup row, String label, boolean selected, View.OnClickListener l) {
        Context c = row.getContext();
        Button b = button(row, label, selected ? PRIMARY : SURFACE_VARIANT, l);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(0, 0, 0, 0);
        b.getLayoutParams().height = dp(c, 46);
        return b;
    }

    public static LinearLayout row(ViewGroup parent) {
        LinearLayout r = hbox(parent.getContext());
        parent.addView(r, matchWrap(parent.getContext(), 0));
        return r;
    }

    public static EditText field(ViewGroup parent, String label, String hint, String value, int inputType) {
        Context c = parent.getContext();
        TextView l = text(parent, label, 14, MUTED, true);
        ((LinearLayout.LayoutParams) l.getLayoutParams()).topMargin = dp(c, 14);
        EditText e = new EditText(c);
        e.setHint(I18n.t(hint));
        e.setText(value);
        e.setInputType(inputType);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setTextColor(INK);
        e.setTypeface(regular());
        e.setHintTextColor(MUTED);
        // The field being typed in turns white with a coloured outline, so it is easy to see.
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        GradientDrawable focused = rounded(c, SURFACE, 14);
        focused.setStroke(dp(c, 2), PRIMARY);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, rounded(c, SURFACE_VARIANT, 14));
        e.setBackground(bg);
        int p = dp(c, 14);
        e.setPadding(p, p, p, p);
        parent.addView(e, matchWrap(c, 6));
        return e;
    }

    public static EditText textField(ViewGroup parent, String label, String hint, String value) {
        return field(parent, label, hint, value, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    }

    public static CheckBox check(ViewGroup parent, String label, boolean value) {
        CheckBox cb = new CheckBox(parent.getContext());
        cb.setText(I18n.t(label));
        cb.setTextColor(INK);
        cb.setTypeface(regular());
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        cb.setButtonTintList(ColorStateList.valueOf(PRIMARY));
        cb.setChecked(value);
        parent.addView(cb, matchWrap(parent.getContext(), 10));
        return cb;
    }

    public static TextView badge(ViewGroup parent, String s, int color) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(I18n.t(s));
        t.setTextColor(color == PRIMARY ? ON_PRIMARY : color == MUTED ? SURFACE : ON_STATUS);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(medium());
        t.setBackground(rounded(c, color, 12));
        int p = dp(c, 10);
        t.setPadding(p, dp(c, 5), p, dp(c, 5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 8);
        parent.addView(t, lp);
        return t;
    }

    public static int colorFor(double percent) { return percent >= 80 ? GOOD : percent >= 50 ? WARN : BAD; }

    /** Labelled rounded progress bar, coloured by the 80/50 adherence thresholds. */
    public static void bar(ViewGroup parent, String label, double percent, String suffix) {
        Context c = parent.getContext();
        LinearLayout head = row(parent);
        ((LinearLayout.LayoutParams) head.getLayoutParams()).topMargin = dp(c, 12);
        text(head, I18n.t(label) + (suffix == null ? "" : suffix), 15, INK, false);
        TextView pct = new TextView(c);
        pct.setText(String.format(java.util.Locale.ROOT, "%.0f%%", percent));
        pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        pct.setTypeface(medium(), Typeface.BOLD);
        pct.setTextColor(colorFor(percent));
        head.addView(pct);
        FrameLayout track = new FrameLayout(c);
        track.setBackground(rounded(c, SURFACE_VARIANT, 6));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 12));
        tl.topMargin = dp(c, 6);
        parent.addView(track, tl);
        View fill = new View(c);
        fill.setBackground(rounded(c, colorFor(percent), 6));
        track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        track.post(() -> {
            fill.getLayoutParams().width = (int) (track.getWidth() * Math.max(0, Math.min(1, percent / 100)));
            fill.requestLayout();
        });
    }

    /** Tinted vector icon. */
    public static ImageView icon(Context c, int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    /** Round badge holding an icon, e.g. the pill in front of a dose. */
    public static FrameLayout iconCircle(Context c, int res, int bg, int tint, int sizeDp) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(bg);
        f.setBackground(g);
        ImageView iv = icon(c, res, tint, sizeDp / 2);
        f.addView(iv, new FrameLayout.LayoutParams(dp(c, sizeDp / 2f), dp(c, sizeDp / 2f), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        lp.rightMargin = dp(c, 14);
        f.setLayoutParams(lp);
        return f;
    }

    /** The drug's photo cropped to a rounded square, or a pill icon when there is none. */
    public static View drugImage(Context c, String photoPath, int sizeDp) {
        return drugImage(c, photoPath, com.chemrob.medadherence.core.DoseForm.TABLET, sizeDp);
    }

    /** The medicine's photo, or the icon of its form (pill, drop, inhaler...) when it has none. */
    public static View drugImage(Context c, com.chemrob.medadherence.core.Medication m, int sizeDp) {
        return drugImage(c, m.photo, m.doseForm(), sizeDp);
    }

    public static int formIcon(com.chemrob.medadherence.core.DoseForm f) {
        switch (f) {
            case LIQUID: return R.drawable.ic_drop;
            case INJECTION: return R.drawable.ic_syringe;
            case INHALER: return R.drawable.ic_inhaler;
            case EYE: return R.drawable.ic_eye;
            case EAR: return R.drawable.ic_ear;
            case SKIN: return R.drawable.ic_lotion;
            default: return R.drawable.ic_pill;
        }
    }

    /** "How to use": the form's steps, numbered, in a tinted box. */
    public static LinearLayout howTo(ViewGroup parent, com.chemrob.medadherence.core.DoseForm f) {
        Context c = parent.getContext();
        LinearLayout box = vbox(c);
        box.setBackground(rounded(c, PRIMARY_CONTAINER, 16));
        int p = dp(c, 14);
        box.setPadding(p, p, p, p);
        parent.addView(box, matchWrap(c, 12));
        LinearLayout head = hbox(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(icon(c, formIcon(f), ON_PRIMARY_CONTAINER, 22));
        TextView h = text(head, I18n.t("How to use") + "  ·  " + I18n.t(f.label), 16, ON_PRIMARY_CONTAINER, true);
        h.setPadding(dp(c, 8), 0, 0, 0);
        box.addView(head);
        for (int i = 0; i < f.howTo.length; i++) {
            LinearLayout row = hbox(c);
            row.setPadding(0, dp(c, 6), 0, 0);
            TextView n = new TextView(c);
            n.setText(String.valueOf(i + 1));
            n.setGravity(Gravity.CENTER);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            n.setTypeface(medium(), Typeface.BOLD);
            n.setTextColor(ON_PRIMARY);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(PRIMARY);
            n.setBackground(g);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
            nl.rightMargin = dp(c, 10);
            row.addView(n, nl);
            text(row, f.howTo[i], 15, ON_PRIMARY_CONTAINER, false);
            box.addView(row);
        }
        return box;
    }

    private static View drugImage(Context c, String photoPath, com.chemrob.medadherence.core.DoseForm form, int sizeDp) {
        Bitmap bmp = loadBitmap(photoPath, dp(c, sizeDp));
        if (bmp == null) return iconCircle(c, formIcon(form), PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER, sizeDp);
        ImageView iv = new ImageView(c);
        iv.setImageBitmap(bmp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float radius = dp(c, 16);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        iv.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        lp.rightMargin = dp(c, 14);
        iv.setLayoutParams(lp);
        return iv;
    }

    /** Decodes a photo scaled down to roughly {@code targetPx} on its short side; null if missing. */
    public static Bitmap loadBitmap(String path, int targetPx) {
        if (path == null || path.isEmpty() || !new File(path).exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int shortSide = Math.min(o.outWidth, o.outHeight), sample = 1;
        while (shortSide / (sample * 2) >= targetPx) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, o);
    }

    /** Circular progress ring with a big number in the middle ("3/4"). */
    public static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float fraction;
        private String label = "", caption = "";

        public Ring(Context c) {
            super(c);
            float stroke = dp(c, 11);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(SURFACE_VARIANT);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            big.setTextAlign(Paint.Align.CENTER);
            big.setColor(INK);
            big.setTypeface(Typeface.create(medium(), Typeface.BOLD));
            big.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26, c.getResources().getDisplayMetrics()));
            small.setTextAlign(Paint.Align.CENTER);
            small.setColor(MUTED);
            small.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, c.getResources().getDisplayMetrics()));
        }

        public void set(float fraction, String label, String caption, int color) {
            this.fraction = Math.max(0, Math.min(1, fraction));
            this.label = label;
            this.caption = caption;
            arc.setColor(color);
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float pad = track.getStrokeWidth() / 2 + 2;
            box.set(pad, pad, getWidth() - pad, getHeight() - pad);
            cv.drawArc(box, 0, 360, false, track);
            if (fraction > 0) cv.drawArc(box, -90, 360 * fraction, false, arc);
            float cy = getHeight() / 2f;
            cv.drawText(label, getWidth() / 2f, cy + big.getTextSize() * 0.25f, big);
            cv.drawText(caption, getWidth() / 2f, cy + big.getTextSize() * 0.25f + small.getTextSize() * 1.5f, small);
        }
    }
}
