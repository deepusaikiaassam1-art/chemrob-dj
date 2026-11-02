package com.chemrob.medadherence.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chemrob.medadherence.core.I18n;

/**
 * The loud half of the app's look: saturated gradients, patterned panels, sticker badges and
 * oversized display type.
 *
 * <p>It is used on the screens where being loud costs nothing - the shop, Progress, rewards and the
 * welcome screen. It is deliberately <b>not</b> used on Today, the ringing alarm, the camera-observed
 * dose or anything a patient reads while deciding whether to swallow a tablet: those keep the plain,
 * high-contrast, large-type treatment in {@link Ui}, which is the reason the app is usable by the
 * older patients it was redesigned for in 1.8.
 *
 * <p>Everything here is drawn in code, so it costs no drawables and follows the light/dark palette.
 */
public final class Max {
    private Max() {}

    // Accents used only for decoration. They are picked to stay legible against both palettes.
    public static int violet() { return Ui.dark ? 0xFF8B85FF : 0xFF5B4BE8; }
    public static int pink()   { return Ui.dark ? 0xFFFF7FB0 : 0xFFE8408A; }
    public static int amber()  { return Ui.dark ? 0xFFFFC45C : 0xFFE08A00; }
    public static int teal()   { return Ui.dark ? 0xFF4ED6C0 : 0xFF0E9B8A; }
    public static int lime()   { return Ui.dark ? 0xFFA8E05F : 0xFF5F9E12; }

    /** The decorative accents in a fixed order, so a list of tiles cycles through them. */
    public static int accent(int index) {
        int[] all = { violet(), pink(), amber(), teal(), lime() };
        return all[Math.floorMod(index, all.length)];
    }

    /** A two-colour diagonal gradient, rounded. */
    public static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[] { from, to });
        g.setCornerRadius(Ui.dp(c, radiusDp));
        return g;
    }

    /** A gradient with a bright hairline border, which is what makes a tile read as a sticker. */
    public static GradientDrawable sticker(Context c, int from, int to, float radiusDp) {
        GradientDrawable g = gradient(c, from, to, radiusDp);
        g.setStroke(Ui.dp(c, 2), Color.argb(Ui.dark ? 70 : 45, 255, 255, 255));
        return g;
    }

    /** Lightens a colour towards white, for the second stop of a gradient. */
    public static int lighten(int color, float amount) {
        return Color.argb(255,
                (int) (Color.red(color) + (255 - Color.red(color)) * amount),
                (int) (Color.green(color) + (255 - Color.green(color)) * amount),
                (int) (Color.blue(color) + (255 - Color.blue(color)) * amount));
    }

    /** Darkens a colour towards black. */
    public static int darken(int color, float amount) {
        float k = 1f - amount;
        return Color.argb(255, (int) (Color.red(color) * k),
                (int) (Color.green(color) * k), (int) (Color.blue(color) * k));
    }

    /**
     * A full-bleed hero panel: gradient, diagonal stripes, a scatter of translucent circles, a
     * bright keyline, and whatever is added to the returned column on top. Four layers is the
     * point - this is the loudest thing in the app. Returns the content column, not the panel.
     */
    public static LinearLayout hero(ViewGroup parent, int from, int to) {
        Context c = parent.getContext();
        FrameLayout panel = new FrameLayout(c);
        GradientDrawable base = gradient(c, from, to, 28);
        base.setStroke(Ui.dp(c, 3), Color.argb(80, 255, 255, 255));
        panel.setBackground(base);
        panel.setClipToOutline(true);
        panel.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(c, 28));
            }
        });
        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        panel.addView(new Stripes(c, Color.argb(30, 255, 255, 255)), fill);
        panel.addView(new Confetti(c), new FrameLayout.LayoutParams(fill));

        LinearLayout content = Ui.vbox(c);
        int pad = Ui.dp(c, 20);
        content.setPadding(pad, pad, pad, pad);
        panel.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(panel, Ui.matchWrap(c, 14));
        return content;
    }

    /**
     * A heading that takes the full width as a solid colour banner with a chunky left block, used
     * instead of a plain text heading on the loud screens.
     */
    public static LinearLayout banner(ViewGroup parent, String label, int color) {
        Context c = parent.getContext();
        LinearLayout row = Ui.hbox(c);
        row.setBackground(sticker(c, color, lighten(color, 0.22f), 16));
        row.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));

        View block = new View(c);
        block.setBackground(Ui.rounded(c, Color.argb(90, 255, 255, 255), 8));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 34));
        bl.rightMargin = Ui.dp(c, 12);
        row.addView(block, bl);

        TextView t = new TextView(c);
        t.setText(I18n.t(label));
        t.setTypeface(Ui.medium(), android.graphics.Typeface.BOLD);
        t.setTextSize(20);
        t.setTextColor(Color.WHITE);
        t.setLetterSpacing(0.02f);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        parent.addView(row, Ui.matchWrap(c, 18));
        return row;
    }

    /**
     * A card inside a thick accent frame, with a dotted tint behind the content. Louder than
     * {@link #edgeCard} and used for the shop's product tiles.
     */
    public static LinearLayout frameCard(ViewGroup parent, int accent) {
        Context c = parent.getContext();
        FrameLayout outer = new FrameLayout(c);
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Ui.SURFACE);
        frame.setCornerRadius(Ui.dp(c, 22));
        frame.setStroke(Ui.dp(c, 3), accent);
        outer.setBackground(frame);
        outer.setClipToOutline(true);
        outer.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(c, 22));
            }
        });
        outer.addView(new Dots(c, Color.argb(Ui.dark ? 28 : 22,
                        Color.red(accent), Color.green(accent), Color.blue(accent))),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = Ui.vbox(c);
        int p = Ui.dp(c, 16);
        content.setPadding(p, p, p, p);
        outer.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(outer, Ui.matchWrap(c, 14));
        return content;
    }

    /** A price sticker: solid accent, white text, tilted a few degrees so it reads as stuck on. */
    public static TextView priceTag(ViewGroup parent, String text, int color) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(text);
        t.setTypeface(Ui.medium(), android.graphics.Typeface.BOLD);
        t.setTextSize(17);
        t.setTextColor(Color.WHITE);
        t.setBackground(sticker(c, color, lighten(color, 0.25f), 14));
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 7), Ui.dp(c, 14), Ui.dp(c, 7));
        t.setRotation(-3f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 8);
        t.setLayoutParams(lp);
        parent.addView(t);
        return t;
    }

    /** Oversized display text for a hero panel. */
    public static TextView display(ViewGroup parent, CharSequence s, float sp, int color) {
        TextView t = Ui.text(parent, s, sp, color, true);
        t.setLineSpacing(0, 0.98f);
        t.setLetterSpacing(-0.02f);
        return t;
    }

    /**
     * A small pill-shaped label in a solid accent colour. Used for category names and "sponsored"
     * style notes, never for anything the patient must act on.
     */
    public static TextView tag(ViewGroup parent, String label, int bg, int fg) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(I18n.t(label));
        t.setTypeface(Ui.medium());
        t.setTextSize(13);
        t.setAllCaps(true);
        t.setLetterSpacing(0.10f);
        t.setTextColor(fg);
        t.setBackground(Ui.rounded(c, bg, 20));
        int px = Ui.dp(c, 11), py = Ui.dp(c, 6);
        t.setPadding(px, py, px, py);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(c, 8);
        lp.topMargin = Ui.dp(c, 8);
        t.setLayoutParams(lp);
        parent.addView(t);
        return t;
    }

    /**
     * A card with a thick coloured left edge. Cheaper to read than a full gradient, so it carries
     * the body content under a hero without the screen turning to soup.
     */
    public static LinearLayout edgeCard(ViewGroup parent, int edge) {
        Context c = parent.getContext();
        LinearLayout card = Ui.card(parent, Ui.SURFACE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.SURFACE);
        g.setCornerRadius(Ui.dp(c, 20));
        g.setStroke(Ui.dp(c, 2), Color.argb(Ui.dark ? 40 : 26, Color.red(edge), Color.green(edge), Color.blue(edge)));
        card.setBackground(g);
        View bar = new View(c);
        bar.setBackground(Ui.rounded(c, edge, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 7));
        lp.bottomMargin = Ui.dp(c, 12);
        card.addView(bar, 0, lp);
        return card;
    }

    /** A wide gradient button for a decorative screen. */
    public static TextView bigAction(ViewGroup parent, String label, int from, int to, View.OnClickListener l) {
        Context c = parent.getContext();
        TextView b = new TextView(c);
        b.setText(Ui.twoLine(label, 0.72f));
        b.setTypeface(Ui.medium());
        b.setTextSize(19);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(sticker(c, from, to, 18));
        int py = Ui.dp(c, 15);
        b.setPadding(Ui.dp(c, 18), py, Ui.dp(c, 18), py);
        b.setClickable(true);
        b.setOnClickListener(l);
        parent.addView(b, Ui.matchWrap(c, 12));
        return b;
    }

    /** Diagonal stripes, drawn edge to edge. One of the layers that makes a hero panel busy. */
    public static final class Stripes extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Stripes(Context c, int color) {
            super(c);
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float band = Ui.dp(getContext(), 22);
            // 45-degree bands: step along the x axis and shear each one by the panel height.
            for (float x = -h; x < w + h; x += band * 2) {
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(x, h);
                p.lineTo(x + h, 0);
                p.lineTo(x + h + band, 0);
                p.lineTo(x + band, h);
                p.close();
                canvas.drawPath(p, paint);
            }
        }
    }

    /** A polka-dot tint, used behind the content of a framed card. */
    public static final class Dots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Dots(Context c, int color) {
            super(c);
            paint.setColor(color);
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float step = Ui.dp(getContext(), 18), r = Ui.dp(getContext(), 2.5f);
            for (int row = 0; row * step < h + step; row++) {
                float y = row * step;
                float offset = (row % 2 == 0) ? 0 : step / 2f;   // brick-laid, not a grid
                for (float x = offset; x < w + step; x += step) canvas.drawCircle(x, y, r, paint);
            }
        }
    }

    /**
     * Translucent circles over a hero gradient. Drawn from a fixed pattern rather than at random so
     * the screen looks the same every time it is opened.
     */
    public static final class Confetti extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // x, y and radius as fractions of the panel, so the pattern scales with the panel.
        private static final float[][] DOTS = {
                {0.86f, 0.18f, 0.30f}, {0.12f, 0.80f, 0.22f}, {0.70f, 0.92f, 0.16f},
                {0.96f, 0.62f, 0.12f}, {0.32f, 0.10f, 0.10f}, {0.52f, 0.74f, 0.08f},
        };

        public Confetti(Context c) { super(c); }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float unit = Math.min(w, h);
            for (int i = 0; i < DOTS.length; i++) {
                paint.setColor(Color.argb(i % 2 == 0 ? 38 : 26, 255, 255, 255));
                canvas.drawCircle(DOTS[i][0] * w, DOTS[i][1] * h, DOTS[i][2] * unit, paint);
            }
        }
    }

    /**
     * A horizontal gradient rule, used to break sections on a loud screen.
     */
    public static View rule(ViewGroup parent, int from, int to) {
        Context c = parent.getContext();
        View v = new View(c) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                if (getWidth() == 0) return;
                p.setShader(new LinearGradient(0, 0, getWidth(), 0, from, to, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(0, 0, getWidth(), getHeight(),
                        getHeight() / 2f, getHeight() / 2f, p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 6));
        lp.topMargin = Ui.dp(c, 16);
        lp.bottomMargin = Ui.dp(c, 4);
        parent.addView(v, lp);
        return v;
    }
}
