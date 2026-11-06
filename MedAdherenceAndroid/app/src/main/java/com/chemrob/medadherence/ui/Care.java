package com.chemrob.medadherence.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chemrob.medadherence.core.I18n;

/**
 * The components of the "Clinical Clarity & Care" design system, on top of {@link Ui}'s palette.
 *
 * <p>It replaces the maximalist layer this app carried in 1.9. The reason is the patients: the app
 * was redesigned in 1.8 for older people reading a dose at a bedside, and gradients, stripes and
 * sticker tiles fought that. This system is built the other way round - tonal layering, a strict
 * type floor, oversized targets and a three-colour triage set that never depends on hue alone.
 *
 * <p>Two rules it keeps that the system itself does not state:
 * <ul>
 *   <li><b>Atkinson Hyperlegible stays.</b> The system standardises on Plus Jakarta Sans, which is
 *       a handsome geometric face but does not disambiguate 1/l/I and 0/O. For an app whose whole
 *       job is showing dosages to people with low vision, that property outranks the brand.</li>
 *   <li><b>State is never colour alone.</b> Every status chip carries a word as well as a colour,
 *       and the dose strip's three states differ in lightness, not just hue.</li>
 * </ul>
 */
public final class Care {
    private Care() {}

    /** The three states a dose can be in, as the system's triage set. */
    public enum State {
        TAKEN, DUE, UPCOMING, MISSED;

        /** The strip and chip colour. */
        int color() {
            switch (this) {
                case TAKEN: return Ui.GOOD;
                case DUE: return Ui.WARN;
                case MISSED: return Ui.BAD;
                default: return Ui.LINE;
            }
        }

        /** The chip's low-saturation background. */
        int tint() {
            switch (this) {
                case TAKEN: return Ui.GOOD_BG;
                case DUE: return Ui.DUE_BG;
                case MISSED: return Ui.ALERT_BG;
                default: return Ui.SURFACE_VARIANT;
            }
        }

        /** The chip's deeply saturated text, dark enough to pass on its own tint. */
        int ink() {
            if (Ui.dark) return color();
            switch (this) {
                case TAKEN: return Color.parseColor("#065F46");
                case DUE: return Color.parseColor("#92400E");
                case MISSED: return Color.parseColor("#9F1239");
                default: return Ui.MUTED;
            }
        }
    }

    /**
     * A dose card: a white surface with a 4px state strip down its left edge, so the state reads
     * from across the room before any of the words do.
     */
    public static LinearLayout doseCard(ViewGroup parent, State state) {
        Context c = parent.getContext();
        LinearLayout row = Ui.hbox(c);
        row.setGravity(Gravity.TOP);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.SURFACE);
        bg.setCornerRadius(Ui.dp(c, 16));
        bg.setStroke(Math.max(1, Ui.dp(c, 1)), Ui.dark
                ? Color.argb(40, 148, 163, 184) : Color.argb(153, 203, 213, 225));
        row.setBackground(bg);
        row.setElevation(Ui.dark ? 0 : Ui.dp(c, 2));

        View strip = new View(c);
        GradientDrawable s = new GradientDrawable();
        s.setColor(state.color());
        float r = Ui.dp(c, 16);
        // Rounded on the left only, so the strip follows the card's corner.
        s.setCornerRadii(new float[] { r, r, 0, 0, 0, 0, r, r });
        strip.setBackground(s);
        row.addView(strip, new LinearLayout.LayoutParams(Ui.dp(c, 5), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = Ui.vbox(c);
        int p = Ui.dp(c, 16);
        content.setPadding(p, p, p, p);
        row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        parent.addView(row, Ui.matchWrap(c, 12));
        return content;
    }

    /**
     * A status chip: pill-shaped, low-saturation tint behind deeply saturated text. The label is
     * always a word, never a bare colour.
     */
    public static TextView chip(ViewGroup parent, String label, State state) {
        return chip(parent, label, state.tint(), state.ink());
    }

    public static TextView chip(ViewGroup parent, String label, int tint, int ink) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(I18n.t(label));
        t.setTypeface(Ui.medium(), Typeface.BOLD);
        t.setTextSize(13);                       // the system's label floor
        t.setTextColor(ink);
        t.setBackground(Ui.rounded(c, tint, 999));
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(c, 8);
        lp.topMargin = Ui.dp(c, 8);
        t.setLayoutParams(lp);
        parent.addView(t);
        return t;
    }

    /**
     * A section heading: small, wide-tracked and quiet, so the cards under it carry the weight.
     * The system's "hierarchy restraint" - the heading is not competing with the content.
     */
    public static TextView section(ViewGroup parent, String label) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(I18n.t(label));
        t.setTypeface(Ui.medium(), Typeface.BOLD);
        t.setTextSize(13);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setTextColor(Ui.MUTED);
        LinearLayout.LayoutParams lp = Ui.matchWrap(c, 20);
        lp.bottomMargin = Ui.dp(c, 2);
        parent.addView(t, lp);
        return t;
    }

    /**
     * A plain card with the system's ghost border, for a block that is not a dose. Returns the
     * content column.
     */
    public static LinearLayout panel(ViewGroup parent) {
        return Ui.card(parent, Ui.SURFACE);
    }

    /**
     * The teal wash used for a secondary action or a supporting note: the primary colour at about
     * a tenth, flattened against the current surface so it stays opaque on any background.
     */
    public static int wash() {
        return blend(Ui.PRIMARY, Ui.SURFACE, Ui.dark ? 0.82f : 0.90f);
    }

    /** Mixes {@code towards} into {@code color} by {@code amount} (0 = color, 1 = towards). */
    public static int blend(int color, int towards, float amount) {
        float k = Math.max(0f, Math.min(1f, amount));
        return Color.argb(255,
                Math.round(Color.red(color) + (Color.red(towards) - Color.red(color)) * k),
                Math.round(Color.green(color) + (Color.green(towards) - Color.green(color)) * k),
                Math.round(Color.blue(color) + (Color.blue(towards) - Color.blue(color)) * k));
    }

    /**
     * The adherence dial's track colour, so the ring reads on the canvas rather than disappearing
     * into it.
     */
    public static int track() {
        return Ui.dark ? Ui.SURFACE_VARIANT : Color.parseColor("#E2E8F0");
    }
}
