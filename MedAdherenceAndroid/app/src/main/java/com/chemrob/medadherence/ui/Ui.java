package com.chemrob.medadherence.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for building the UI in code (no XML layouts, no support libraries). */
public final class Ui {
    private Ui() {}

    public static final int BG = Color.parseColor("#F2F5F3");
    public static final int SURFACE = Color.WHITE;
    public static final int PRIMARY = Color.parseColor("#0E6B5C");
    public static final int ACCENT = Color.parseColor("#1F8FB3");
    public static final int GOOD = Color.parseColor("#2F8F4E");
    public static final int WARN = Color.parseColor("#C9821A");
    public static final int BAD = Color.parseColor("#C2443A");
    public static final int MUTED = Color.parseColor("#66756F");
    public static final int INK = Color.parseColor("#1B2A2F");
    public static final int LINE = Color.parseColor("#DCE4E0");
    public static final int DUE_BG = Color.parseColor("#FFF4DE");
    public static final int ALERT_BG = Color.parseColor("#FDE7E5");

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
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

    /** A white rounded card that stacks its children. */
    public static LinearLayout card(ViewGroup parent, int color) {
        Context c = parent.getContext();
        LinearLayout card = vbox(c);
        card.setBackground(rounded(c, color, 14));
        int p = dp(c, 16);
        card.setPadding(p, p, p, p);
        parent.addView(card, matchWrap(c, 12));
        return card;
    }

    public static TextView text(ViewGroup parent, CharSequence s, float sp, int color, boolean bold) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        if (parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL)
            parent.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        else parent.addView(t, matchWrap(c, 4));
        return t;
    }

    public static TextView heading(ViewGroup parent, String s) { return text(parent, s, 19, INK, true); }

    public static TextView muted(ViewGroup parent, CharSequence s) { return text(parent, s, 14, MUTED, false); }

    public static Button button(ViewGroup parent, String label, int color, View.OnClickListener l) {
        Context c = parent.getContext();
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rounded(c, color, 12));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        boolean row = parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams lp = row
                ? new LinearLayout.LayoutParams(0, dp(c, 48), 1)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 52));
        if (row) { lp.leftMargin = dp(c, 4); lp.rightMargin = dp(c, 4); }
        lp.topMargin = dp(c, 8);
        parent.addView(b, lp);
        return b;
    }

    /** Small selectable pill used for presets. */
    public static Button chip(ViewGroup row, String label, boolean selected, View.OnClickListener l) {
        Context c = row.getContext();
        Button b = button(row, label, selected ? PRIMARY : LINE, l);
        b.setTextColor(selected ? Color.WHITE : INK);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(0, 0, 0, 0);
        b.getLayoutParams().height = dp(c, 40);
        return b;
    }

    public static LinearLayout row(ViewGroup parent) {
        LinearLayout r = hbox(parent.getContext());
        parent.addView(r, matchWrap(parent.getContext(), 0));
        return r;
    }

    public static EditText field(ViewGroup parent, String label, String hint, String value, int inputType) {
        Context c = parent.getContext();
        TextView l = text(parent, label, 13, MUTED, true);
        ((LinearLayout.LayoutParams) l.getLayoutParams()).topMargin = dp(c, 12);
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(inputType);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        e.setBackground(rounded(c, BG, 10));
        int p = dp(c, 12);
        e.setPadding(p, p, p, p);
        parent.addView(e, matchWrap(c, 4));
        return e;
    }

    public static EditText textField(ViewGroup parent, String label, String hint, String value) {
        return field(parent, label, hint, value, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    }

    public static CheckBox check(ViewGroup parent, String label, boolean value) {
        CheckBox cb = new CheckBox(parent.getContext());
        cb.setText(label);
        cb.setTextColor(INK);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cb.setChecked(value);
        parent.addView(cb, matchWrap(parent.getContext(), 8));
        return cb;
    }

    public static TextView badge(ViewGroup parent, String s, int color) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackground(rounded(c, color, 10));
        int p = dp(c, 8);
        t.setPadding(p, dp(c, 4), p, dp(c, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 6);
        parent.addView(t, lp);
        return t;
    }

    public static int colorFor(double percent) { return percent >= 80 ? GOOD : percent >= 50 ? WARN : BAD; }

    /** Labelled horizontal bar, coloured by the 80/50 adherence thresholds. */
    public static void bar(ViewGroup parent, String label, double percent, String suffix) {
        Context c = parent.getContext();
        text(parent, String.format(java.util.Locale.ROOT, "%s  %.0f%%%s", label, percent, suffix == null ? "" : suffix), 14, INK, false);
        FrameLayout track = new FrameLayout(c);
        track.setBackground(rounded(c, LINE, 5));
        parent.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 10)));
        View fill = new View(c);
        fill.setBackground(rounded(c, colorFor(percent), 5));
        track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        track.post(() -> {
            fill.getLayoutParams().width = (int) (track.getWidth() * Math.max(0, Math.min(1, percent / 100)));
            fill.requestLayout();
        });
    }
}
