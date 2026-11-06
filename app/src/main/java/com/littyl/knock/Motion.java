package com.littyl.knock;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * Knock's motion engine. Small on purpose, so it stays smooth on the weakest phones:
 *  - Only transform and alpha are animated on views (ViewPropertyAnimator → RenderNode properties:
 *    no relayout, no re-recording of the view), and reveals turn off overlapping rendering so a fade
 *    never needs an offscreen buffer.
 *  - Motion is physical: an analytic damped-spring interpolator (no per-frame solver, no allocation),
 *    so presses settle like objects and reveals arrive instead of appearing.
 *  - Custom views animate from one frame clock and stop invalidating as soon as they are still.
 *  - "Remove animations" in system settings turns all of it into instant changes, and haptics follow
 *    the system's touch-feedback setting.
 */
final class Motion {
    private Motion() { }

    static boolean enabled() { return ValueAnimator.areAnimatorsEnabled(); }

    // ---------- curves ----------

    /** Damped spring as an interpolator: x(t) = 1 − e^(−ζωt)(cos ω_d t + ζω/ω_d · sin ω_d t). */
    static final class Spring implements TimeInterpolator {
        final float zeta, omega, omegaD, seconds;
        final long ms;

        /** @param damping ζ (1 = no overshoot, 0.6 = lively) @param response seconds per oscillation */
        Spring(float damping, float response) {
            zeta = damping;
            omega = (float) (2 * Math.PI / response);
            omegaD = omega * (float) Math.sqrt(Math.max(1e-4, 1 - zeta * zeta));
            seconds = (float) (Math.log(400) / (zeta * omega));   // until within 0.25% of rest
            ms = Math.round(seconds * 1000);
        }

        @Override public float getInterpolation(float f) {
            if (f >= 1) return 1;
            float t = f * seconds;
            double decay = Math.exp(-zeta * omega * t);
            return (float) (1 - decay * (Math.cos(omegaD * t) + zeta * omega / omegaD * Math.sin(omegaD * t)));
        }
    }

    /** Presses releasing, switches. Quick with a hint of give. */
    static final Spring SNAP = new Spring(0.72f, 0.30f);
    /** Things arriving on screen. Calm, almost no overshoot. */
    static final Spring SOFT = new Spring(0.88f, 0.46f);
    /** Small things popping in (dots). Lively. */
    static final Spring POP = new Spring(0.52f, 0.34f);
    /** Sheets. */
    static final Spring SHEET = new Spring(0.86f, 0.40f);

    static final TimeInterpolator EASE_OUT = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final TimeInterpolator EASE_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    static final TimeInterpolator EASE = new PathInterpolator(0.3f, 0f, 0.2f, 1f);

    static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    static int blend(int a, int b, float t) {
        t = clamp(t);
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    /** One-shot 0→1 driver for custom views; calls back every frame, ends exactly at 1. */
    static ValueAnimator drive(long ms, TimeInterpolator curve, long delay, ValueAnimator.AnimatorUpdateListener l) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(ms);
        a.setStartDelay(delay);
        a.setInterpolator(curve);
        a.addUpdateListener(l);
        a.start();
        return a;
    }

    // ---------- haptics (system setting decides whether they play) ----------

    static void tick(View v) { if (v != null) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }

    static void tap(View v) { if (v != null) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    static void confirm(View v) { if (v != null) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); }

    static void reject(View v) { if (v != null) v.performHapticFeedback(HapticFeedbackConstants.REJECT); }

    static void toggle(View v, boolean on) {
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= 34) v.performHapticFeedback(on ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF);
        else v.performHapticFeedback(on ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK);
    }

    static void gesture(View v) { if (v != null) v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END); }

    // ---------- touch ----------

    /**
     * Press feel: sinks to {@code scale} while held (after a short delay, so starting a scroll
     * doesn't flinch the item), springs back on release, and ticks once on a real tap.
     * Never consumes the event, so clicks and scrolling work as usual.
     */
    static void press(View v, float scale) {
        final int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        v.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0;
            boolean live;
            final Runnable sink = () -> {
                if (live) v.animate().scaleX(scale).scaleY(scale).setDuration(140).setInterpolator(EASE_OUT).setStartDelay(0).start();
            };

            @Override public boolean onTouch(View view, MotionEvent e) {
                if (!view.isEnabled()) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getX(); y0 = e.getY(); live = true;
                        view.postDelayed(sink, 45);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (live && (Math.abs(e.getX() - x0) > slop || Math.abs(e.getY() - y0) > slop)) release(view);
                        break;
                    case MotionEvent.ACTION_UP:
                        if (live) {
                            tap(view);
                            // a tap shorter than the sink delay still gets a visible press
                            view.removeCallbacks(sink);
                            view.animate().scaleX(scale).scaleY(scale).setDuration(70).setInterpolator(EASE_OUT)
                                    .withEndAction(() -> settle(view)).start();
                            live = false;
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        release(view);
                        break;
                }
                return false;
            }

            void release(View view) {
                live = false;
                view.removeCallbacks(sink);
                settle(view);
            }
        });
    }

    private static void settle(View v) {
        v.animate().scaleX(1f).scaleY(1f).setDuration(SNAP.ms).setInterpolator(SNAP).setStartDelay(0).withEndAction(null).start();
    }

    // ---------- reveals ----------

    /** Marks a group whose children reveal one by one (e.g. the two lines of a headline). */
    static final Object STAGGER = new Object();

    static final int MAX_STAGGERED = 10;

    /**
     * Bring a freshly built screen in: each child rises a little and fades in, one after another.
     * Only the first {@link #MAX_STAGGERED} move; the rest are below the fold and just appear.
     * @param dir  +1 going deeper, −1 coming back, 0 no sideways drift
     */
    static void reveal(ViewGroup col, int dir) {
        if (!enabled()) return;
        int dy = Math.round(18 * col.getResources().getDisplayMetrics().density);
        int dx = Math.round(14 * col.getResources().getDisplayMetrics().density) * dir;
        int n = 0;
        for (int i = 0; i < col.getChildCount() && n < MAX_STAGGERED; i++) {
            View child = col.getChildAt(i);
            if (child.getTag() == STAGGER && child instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) child;
                for (int j = 0; j < g.getChildCount() && n < MAX_STAGGERED; j++) rise(g.getChildAt(j), n++, dx, dy);
            } else {
                rise(child, n++, dx, dy);
            }
        }
    }

    static void rise(View v, int order, int dx, int dy) {
        if (!enabled()) return;
        float target = v.getAlpha();             // keep e.g. a disabled button's dimming
        v.forceHasOverlappingRendering(false);   // fade without an offscreen layer
        v.setAlpha(0f);
        v.setTranslationY(dy);
        v.setTranslationX(dx);
        long delay = 30L + order * 38L;
        // one animator for all three: the spring's fast start makes the fade read as quick
        v.animate().translationY(0f).translationX(0f).alpha(target)
                .setDuration(SOFT.ms).setStartDelay(delay).setInterpolator(SOFT)
                .withEndAction(() -> v.forceHasOverlappingRendering(true)).start();
    }

    /** Replace-in-place emphasis: a quick fade-and-settle for content that changed. */
    static void refresh(View v) {
        if (!enabled()) return;
        v.setAlpha(0f);
        v.setTranslationY(v.getResources().getDisplayMetrics().density * 8);
        v.animate().alpha(1f).translationY(0f).setDuration(SOFT.ms).setInterpolator(SOFT).setStartDelay(0).start();
    }

    /** Gentle bump, e.g. a button that just became available. */
    static void bump(View v) {
        if (!enabled()) return;
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(POP.ms).setInterpolator(POP).setStartDelay(0).start();
    }
}
