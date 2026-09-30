package com.chemrob.medadherence.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.chemrob.medadherence.R;
import com.chemrob.medadherence.core.I18n;

/**
 * "I took it" as a press-and-hold button: the patient keeps a finger on it for under a second
 * while it fills up, so a stray tap in a pocket or by a grandchild never records a dose. A quick
 * tap explains what to do. Screen readers (TalkBack double-tap) and keyboards act at once, since
 * holding is not possible there.
 */
@SuppressLint("ViewConstructor")
public final class HoldButton extends LinearLayout {
    private static final long HOLD_MS = 800;

    private final Runnable action;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final float radius;
    private float progress;
    private ValueAnimator anim;
    private long downAt;
    private boolean fired;

    public HoldButton(Context c, String label, int bg, int fg, Runnable action) {
        super(c);
        this.action = action;
        radius = Ui.dp(c, 20);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(Ui.rounded(c, bg, 20));
        int p = Ui.dp(c, 16);
        setPadding(Ui.dp(c, 18), p, Ui.dp(c, 18), p);
        setMinimumHeight(Ui.dp(c, 84));
        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        setElevation(Ui.dp(c, 2));
        fill.setColor(Color.argb(70, Color.red(fg), Color.green(fg), Color.blue(fg)));

        FrameLayout ring = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(Ui.dp(c, 3), Color.argb(217, Color.red(fg), Color.green(fg), Color.blue(fg)));
        ring.setBackground(g);
        ImageView check = new ImageView(c);
        check.setImageResource(R.drawable.ic_check);
        check.setImageTintList(ColorStateList.valueOf(fg));
        ring.addView(check, new FrameLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), Gravity.CENTER));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54));
        rl.rightMargin = Ui.dp(c, 14);
        addView(ring, rl);

        LinearLayout texts = Ui.vbox(c);
        addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView main = Ui.text(texts, label, 24, fg, true);
        ((LinearLayout.LayoutParams) main.getLayoutParams()).topMargin = 0;
        String second = I18n.t2(label);
        if (second != null && !second.equals(I18n.t(label))) {
            TextView s = Ui.text(texts, "", 17, fg, false);
            s.setText(second);
            ((LinearLayout.LayoutParams) s.getLayoutParams()).topMargin = 0;
        }

        TextView hint = new TextView(c);
        hint.setText(I18n.t("Press and hold"));
        hint.setTextSize(14);
        hint.setTextColor(fg);
        hint.setTypeface(Ui.regular());
        hint.setGravity(Gravity.END);
        hint.setMaxWidth(Ui.dp(c, 110));
        addView(hint);

        setContentDescription(I18n.t(label));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downAt = System.currentTimeMillis();
                fired = false;
                setPressed(true);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                start();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (e.getX() < 0 || e.getY() < 0 || e.getX() > getWidth() || e.getY() > getHeight()) release(false);
                return true;
            case MotionEvent.ACTION_UP:
                release(System.currentTimeMillis() - downAt < 300);
                return true;
            case MotionEvent.ACTION_CANCEL:
                release(false);
                return true;
            default:
                return true;
        }
    }

    private void start() {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(progress, 1f);
        anim.setDuration((long) (HOLD_MS * (1 - progress)));
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
            if (progress >= 1f && !fired) fire();
        });
        anim.start();
    }

    private void release(boolean quickTap) {
        setPressed(false);
        if (fired) return;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(progress, 0f);
        anim.setDuration(180);
        anim.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
        if (quickTap) Toast.makeText(getContext(), I18n.t("Keep your finger on the button until it fills up."), Toast.LENGTH_SHORT).show();
    }

    private void fire() {
        fired = true;
        setPressed(false);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        progress = 0;
        invalidate();
        action.run();
    }

    /** Screen readers and keyboards: act straight away. */
    @Override
    public boolean performClick() {
        super.performClick();
        if (isEnabled()) action.run();
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (progress > 0) {
            box.set(0, 0, getWidth(), getHeight());
            clip.reset();
            clip.addRoundRect(box, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawRect(0, 0, getWidth() * progress, getHeight(), fill);
            canvas.restore();
        }
        super.dispatchDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    /** Adds a hold button to a vertical parent. */
    public static HoldButton add(LinearLayout parent, String label, int bg, int fg, Runnable action) {
        HoldButton b = new HoldButton(parent.getContext(), label, bg, fg, action);
        parent.addView(b, Ui.matchWrap(parent.getContext(), 14));
        return b;
    }
}
