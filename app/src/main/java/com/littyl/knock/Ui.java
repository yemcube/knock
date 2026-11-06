package com.littyl.knock;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The Dot-matrix design system: paper/ink tokens for light and dark, three typefaces,
 * and the few pieces every screen is built from (tiles, rows, pills, dot glyphs, the live matrix, sheets).
 */
final class Ui {
    // tokens, set from the phone's light/dark mode
    final int bg, surface, line, ink, muted, faint, dotOff, dotOn, near, far, good, bad, red, onInk, press;
    final boolean dark;

    static Typeface serif, serifItalic, sans, sansMedium, sansSemi, mono;

    final Context c;

    Ui(Context c) {
        this.c = c;
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            bg = 0xFF0E0E0D; surface = 0xFF181817; line = 0xFF2A2A27; ink = 0xFFEDEDE8; muted = 0xFF9C9C95; faint = 0xFF6F6F69;
            dotOff = 0xFF272724; dotOn = 0xFF5C5C57; near = 0xFF8C8CFF; far = 0xFFFF7D4D; good = 0xFF3DD68C; bad = 0xFFFF6B66; red = 0xFFFF4B3E;
            onInk = 0xFF0E0E0D; press = 0x1FFFFFFF;
        } else {
            bg = 0xFFF7F6F2; surface = 0xFFFFFFFF; line = 0xFFE7E6DF; ink = 0xFF111111; muted = 0xFF6B6B66; faint = 0xFF8A8A84;
            dotOff = 0xFFE6E5DE; dotOn = 0xFFBDBDB5; near = 0xFF5B5BF6; far = 0xFFE0592A; good = 0xFF1FA463; bad = 0xFFD64541; red = 0xFFE5392C;
            onInk = 0xFFF7F6F2; press = 0x14000000;
        }
        if (serif == null) {
            serif = Typeface.createFromAsset(c.getAssets(), "fonts/InstrumentSerif-Regular.ttf");
            serifItalic = Typeface.createFromAsset(c.getAssets(), "fonts/InstrumentSerif-Italic.ttf");
            sans = variable("fonts/Geist.ttf", 400);
            sansMedium = variable("fonts/Geist.ttf", 500);
            sansSemi = variable("fonts/Geist.ttf", 600);
            mono = variable("fonts/GeistMono.ttf", 450);
        }
    }

    private Typeface variable(String path, int weight) {
        try {
            return new Typeface.Builder(c.getAssets(), path).setFontVariationSettings("'wght' " + weight).build();
        } catch (Exception e) {
            return Typeface.create("sans-serif", Typeface.NORMAL);
        }
    }

    int spotColor(int spot) { return spot == 0 ? near : spot == 1 ? far : faint; }

    int dp(float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** Window chrome: background behind the bars and dark or light bar icons. */
    void window(Activity a) {
        Window w = a.getWindow();
        w.getDecorView().setBackgroundColor(bg);
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        WindowInsetsController ic = w.getInsetsController();
        if (ic != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ic.setSystemBarsAppearance(dark ? 0 : light, light);
        }
    }

    // ---------- text ----------

    TextView text(CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(sans);
        t.setLineSpacing(0, 1.2f);
        t.setIncludeFontPadding(false);
        return t;
    }

    TextView medium(CharSequence s, float sp, int color) { TextView t = text(s, sp, color); t.setTypeface(sansMedium); return t; }

    /** Instrument Serif headline. */
    TextView headline(CharSequence s, float sp) {
        TextView t = text(s, sp, ink);
        t.setTypeface(serif);
        t.setLineSpacing(0, 0.9f);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    /** Two-line headline (second line muted); the lines reveal one after the other. */
    LinearLayout headline2(String first, String second, float sp) {
        LinearLayout g = column();
        g.setTag(Motion.STAGGER);
        g.addView(headline(first, sp));
        if (second != null) {
            TextView t = headline(second, sp);
            t.setTextColor(faint);
            g.addView(t);
        }
        return g;
    }

    TextView monoText(CharSequence s, float sp, int color) {
        TextView t = text(s, sp, color);
        t.setTypeface(mono);
        return t;
    }

    /** Small grey label above a section. */
    TextView label(CharSequence s) {
        TextView t = monoText(s.toString().toLowerCase(java.util.Locale.US), 11, faint);
        t.setLetterSpacing(0.04f);
        return t;
    }

    // ---------- shapes ----------

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    GradientDrawable outlined(int fill, int stroke, float radiusDp) {
        GradientDrawable g = round(fill, radiusDp);
        g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    RippleDrawable pressable(GradientDrawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(press), shape, null);
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** White (or raised) card with a hairline border. */
    LinearLayout card() {
        LinearLayout l = column();
        l.setBackground(outlined(surface, line, 22));
        l.setPadding(dp(18), dp(18), dp(18), dp(18));
        return l;
    }

    // ---------- controls ----------

    /** Full-width pill: primary is ink on paper, secondary is outlined. */
    Button button(CharSequence s, boolean primary) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(sansMedium);
        b.setStateListAnimator(null);
        b.setTextColor(primary ? onInk : ink);
        b.setBackground(pressable(primary ? round(ink, 26) : outlined(0x00000000, line, 26)));
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        Motion.press(b, 0.965f);
        return b;
    }

    /** Quiet text button. */
    TextView link(CharSequence s, int color, View.OnClickListener click) {
        TextView t = medium(s, 14, color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(44));
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setOnClickListener(click);
        t.setBackground(pressable(round(0x00000000, 10)));
        Motion.press(t, 0.94f);
        return t;
    }

    /** Profile button: name + chevron in an outlined pill with room to breathe. */
    LinearLayout pill(CharSequence s, View.OnClickListener click) {
        LinearLayout r = row();
        r.setPadding(dp(18), 0, dp(16), 0);
        r.setMinimumHeight(dp(44));
        r.setBackground(pressable(outlined(surface, line, 22)));
        r.setOnClickListener(click);
        TextView t = medium(s, 14, ink);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(190));
        r.addView(t);
        Chevron ch = new Chevron();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(12), dp(12));
        lp.leftMargin = dp(10);
        r.addView(ch, lp);
        Motion.press(r, 0.96f);
        return r;
    }

    /** A settings-style row: title + optional sub on the left, a trailing view on the right. */
    LinearLayout listRow(CharSequence title, CharSequence sub, View trailing, View.OnClickListener click) {
        return listRow(null, title, sub, trailing, click);
    }

    /** Same, with an icon (or any view) in front. */
    LinearLayout listRow(View leading, CharSequence title, CharSequence sub, View trailing, View.OnClickListener click) {
        LinearLayout r = row();
        if (leading != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.rightMargin = dp(14);
            r.addView(leading, lp);
        }
        r.setMinimumHeight(dp(56));
        r.setPadding(0, dp(10), 0, dp(10));
        LinearLayout t = column();
        t.addView(text(title, 15, ink));
        if (sub != null) t.addView(text(sub, 13, muted), wide(3));
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) {
            ViewGroup.LayoutParams own = trailing.getLayoutParams();
            LinearLayout.LayoutParams p = own != null ? new LinearLayout.LayoutParams(own.width, own.height)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.leftMargin = dp(12);
            r.addView(trailing, p);
        }
        if (click != null) {
            r.setOnClickListener(click);
            r.setBackground(pressable(round(0x00000000, 12)));
            Motion.press(r, 0.985f);
        }
        return r;
    }

    View divider() {
        View v = new View(c);
        v.setBackgroundColor(line);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.75f))));
        return v;
    }

    View spacer() {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        return v;
    }

    /** Small switch for settings rows (the row handles the tap). */
    Toggle toggle(boolean on) {
        Toggle t = new Toggle(on, false);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(26)));
        return t;
    }

    /**
     * Animated switch. The knob travels on a spring and stretches while it moves; the track and
     * knob colours cross-fade; the labelled version (the main On/Off) swaps ON/OFF as it goes.
     */
    final class Toggle extends View {
        final boolean labeled;
        boolean on;
        float t;            // 0 off … 1 on (may overshoot slightly on the spring)
        float motion;       // 0 still … 1 mid-travel, for the stretch
        ValueAnimator anim;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF rect = new android.graphics.RectF();

        Toggle(boolean on, boolean labeled) {
            super(c);
            this.on = on; this.labeled = labeled; t = on ? 1 : 0;
            if (labeled) {
                setContentDescription("Knock");
                setAccessibilityDelegate(new AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(android.widget.Switch.class.getName());
                        info.setCheckable(true);
                        info.setChecked(Toggle.this.on);
                    }
                });
            }
        }

        /** Flip with motion and a haptic. */
        void set(boolean value) {
            if (value == on) return;
            on = value;
            Motion.toggle(this, on);
            if (anim != null) anim.cancel();
            float from = t, to = on ? 1 : 0;
            anim = Motion.drive(Motion.SNAP.ms, Motion.SNAP, 0, a -> {
                float f = (float) a.getAnimatedValue();
                t = from + (to - from) * f;
                motion = (float) Math.sin(Math.PI * Motion.clamp(f * 1.15f));
                invalidate();
            });
            if (!Motion.enabled()) { t = to; motion = 0; invalidate(); }
        }

        @Override protected void onMeasure(int w, int h) {
            if (labeled) setMeasuredDimension(dp(74), dp(32));
            else setMeasuredDimension(dp(46), dp(26));
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2f, pad = labeled ? dp(3.5f) : dp(3), knob = r - pad;
            float k = Motion.clamp(t);
            // track
            p.setStyle(Paint.Style.FILL);
            p.setColor(Motion.blend(dotOff, ink, k));
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, p);
            if (k < 1) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1, dp(1)));
                p.setColor(line);
                p.setAlpha(Math.round(255 * (1 - k)));
                rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
                cv.drawRoundRect(rect, r, r, p);
                p.setAlpha(255);
                p.setStyle(Paint.Style.FILL);
            }
            // labels
            if (labeled) {
                p.setTypeface(mono);
                p.setTextSize(dp(10.5f));
                p.setLetterSpacing(0.06f);
                Paint.FontMetrics fm = p.getFontMetrics();
                float base = r - (fm.ascent + fm.descent) / 2f, area = w - knob * 2 - pad;
                float twOn = p.measureText("ON"), twOff = p.measureText("OFF");
                p.setColor(onInk);
                p.setAlpha(Math.round(255 * k));
                cv.drawText("ON", (area - twOn) / 2f + pad / 2f - (1 - k) * dp(6), base, p);
                p.setColor(muted);
                p.setAlpha(Math.round(255 * (1 - k)));
                cv.drawText("OFF", knob * 2 + pad + (area - twOff) / 2f + k * dp(6), base, p);
                p.setAlpha(255);
            }
            // knob: travels, stretches mid-way
            float x0 = pad + knob, x1 = w - pad - knob;
            float cx = x0 + (x1 - x0) * t;
            float stretch = motion * dp(5);
            p.setColor(Motion.blend(dark ? dotOn : surface, labeled ? near : onInk, k));
            rect.set(cx - knob - stretch / 2, r - knob, cx + knob + stretch / 2, r + knob);
            cv.drawRoundRect(rect, knob, knob, p);
            if (k < 1 && !dark) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1, dp(1)));
                p.setColor(line);
                p.setAlpha(Math.round(255 * (1 - k)));
                cv.drawRoundRect(rect, knob, knob, p);
                p.setAlpha(255);
                p.setStyle(Paint.Style.FILL);
            }
        }
    }

    // ---------- icons ----------

    /** Line icon for an action id, drawn from paths on a 24-unit grid (crisp at any size, no assets). */
    final class Icon extends View {
        final android.graphics.Path path = new android.graphics.Path(), fill = new android.graphics.Path();
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Matrix m = new android.graphics.Matrix();
        final android.graphics.Path scaled = new android.graphics.Path(), scaledFill = new android.graphics.Path();
        int color;
        boolean boxed;

        Icon(String id, int color, boolean boxed) {
            super(c);
            this.color = color; this.boxed = boxed;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            build(id == null ? "" : id.startsWith(Actions.OPEN_APP) ? "app" : id);
        }

        private void line(float... xy) { path.moveTo(xy[0], xy[1]); for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]); }

        private void build(String id) {
            switch (id) {
                case Actions.SCROLL_DOWN: line(12, 4, 12, 19); line(6, 13, 12, 19, 18, 13); break;
                case Actions.SCROLL_UP: line(12, 20, 12, 5); line(6, 11, 12, 5, 18, 11); break;
                case Actions.SWIPE_LEFT: line(20, 12, 5, 12); line(11, 6, 5, 12, 11, 18); break;
                case Actions.SWIPE_RIGHT: line(4, 12, 19, 12); line(13, 6, 19, 12, 13, 18); break;
                case Actions.BACK:
                    line(9, 6, 4, 11, 9, 16);
                    path.moveTo(4, 11); path.lineTo(14, 11); path.cubicTo(17.5f, 11, 20, 13.5f, 20, 16.5f); path.lineTo(20, 19);
                    break;
                case Actions.HOME: line(3.5f, 11.5f, 12, 4, 20.5f, 11.5f); line(6.5f, 9.5f, 6.5f, 20, 17.5f, 20, 17.5f, 9.5f); line(10, 20, 10, 15, 14, 15, 14, 20); break;
                case Actions.RECENTS:
                    path.addRoundRect(4.5f, 8, 15.5f, 20, 2, 2, android.graphics.Path.Direction.CW);
                    line(8.5f, 5.5f, 8.5f, 4, 19.5f, 4, 19.5f, 16, 18, 16);
                    break;
                case Actions.NOTIFICATIONS:
                    path.moveTo(6.5f, 16.5f); path.lineTo(6.5f, 11); path.cubicTo(6.5f, 7.6f, 9, 5, 12, 5); path.cubicTo(15, 5, 17.5f, 7.6f, 17.5f, 11); path.lineTo(17.5f, 16.5f);
                    line(4.5f, 16.5f, 19.5f, 16.5f); line(10, 20, 14, 20);
                    break;
                case Actions.QUICK_SETTINGS:
                    line(4, 8, 20, 8); line(4, 16, 20, 16);
                    fill.addCircle(9, 8, 2.6f, android.graphics.Path.Direction.CW); fill.addCircle(15, 16, 2.6f, android.graphics.Path.Direction.CW);
                    break;
                case Actions.PLAY_PAUSE: line(4.5f, 6, 4.5f, 18, 12.5f, 12); path.close(); line(16, 6.5f, 16, 17.5f); line(20, 6.5f, 20, 17.5f); break;
                case Actions.NEXT_TRACK: line(5, 6, 5, 18, 14, 12); path.close(); line(18.5f, 6, 18.5f, 18); break;
                case Actions.PREV_TRACK: line(19, 6, 19, 18, 10, 12); path.close(); line(5.5f, 6, 5.5f, 18); break;
                case Actions.ASSISTANT:
                    path.moveTo(12, 3); path.quadTo(13, 11, 21, 12); path.quadTo(13, 13, 12, 21); path.quadTo(11, 13, 3, 12); path.quadTo(11, 11, 12, 3); path.close();
                    break;
                case Actions.SCREENSHOT: line(4, 9, 4, 4, 9, 4); line(15, 4, 20, 4, 20, 9); line(20, 15, 20, 20, 15, 20); line(9, 20, 4, 20, 4, 15); break;
                case Actions.LOCK:
                    path.addRoundRect(5.5f, 11, 18.5f, 20.5f, 2.5f, 2.5f, android.graphics.Path.Direction.CW);
                    path.moveTo(8.5f, 11); path.lineTo(8.5f, 8); path.arcTo(8.5f, 4.5f, 15.5f, 11.5f, 180, 180, false); path.lineTo(15.5f, 11);
                    break;
                case Actions.TORCH: line(8, 3.5f, 16, 3.5f, 16, 7, 14, 10, 14, 20.5f, 10, 20.5f, 10, 10, 8, 7); path.close(); line(12, 13, 12, 15.5f); break;
                case Actions.NONE: path.addCircle(12, 12, 8, android.graphics.Path.Direction.CW); line(6.5f, 17.5f, 17.5f, 6.5f); break;
                case "app":
                    path.addRoundRect(4.5f, 4.5f, 10.5f, 10.5f, 1.8f, 1.8f, android.graphics.Path.Direction.CW);
                    path.addRoundRect(13.5f, 4.5f, 19.5f, 10.5f, 1.8f, 1.8f, android.graphics.Path.Direction.CW);
                    path.addRoundRect(4.5f, 13.5f, 10.5f, 19.5f, 1.8f, 1.8f, android.graphics.Path.Direction.CW);
                    path.addRoundRect(13.5f, 13.5f, 19.5f, 19.5f, 1.8f, 1.8f, android.graphics.Path.Direction.CW);
                    break;
                case "record": path.addCircle(12, 12, 8, android.graphics.Path.Direction.CW); fill.addCircle(12, 12, 3.6f, android.graphics.Path.Direction.CW); break;
                case "data": line(5, 10, 5, 14); line(9, 6, 9, 18); line(13, 8.5f, 13, 15.5f); line(17, 4.5f, 17, 19.5f); line(21, 10.5f, 21, 13.5f); break;
                default: path.addCircle(12, 12, 3, android.graphics.Path.Direction.CW);
            }
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float inset = boxed ? Math.min(w, h) * 0.22f : 0, size = Math.min(w, h) - inset * 2;
            m.reset();
            m.setScale(size / 24f, size / 24f);
            m.postTranslate((w - size) / 2f, (h - size) / 2f);
            path.transform(m, scaled);
            fill.transform(m, scaledFill);
            p.setStrokeWidth(Math.max(1.4f, size / 24f * 1.7f));
        }

        @Override protected void onDraw(Canvas cv) {
            if (boxed) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(dotOff);
                float r = Math.min(getWidth(), getHeight()) * 0.3f;
                cv.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, p);
            }
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            cv.drawPath(scaled, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawPath(scaledFill, p);
        }
    }

    /** Icon for an action: an app's own icon for "open app", otherwise the line icon. */
    View actionIcon(String id, boolean boxed) {
        if (id != null && id.startsWith(Actions.OPEN_APP)) {
            try {
                android.graphics.drawable.Drawable d = c.getPackageManager().getApplicationIcon(id.substring(Actions.OPEN_APP.length()));
                android.widget.ImageView iv = new android.widget.ImageView(c);
                iv.setImageDrawable(d);
                iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                return iv;
            } catch (Exception ignored) { }
        }
        return new Icon(id, ink, boxed);
    }

    // ---------- dot pieces ----------

    /** Row of knock dots: filled for near, rings for far, grey for unknown. */
    View glyph(int spot, int count, float sizeDp) {
        return new Glyph(spot, count, sizeDp);
    }

    final class Glyph extends View {
        final int spot, count; final float size;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(int spot, int count, float sizeDp) {
            super(c);
            this.spot = spot; this.count = count; this.size = dp(sizeDp);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) (count * size + (count - 1) * size * 0.7f) + 2, (int) size + 2);
        }
        @Override protected void onDraw(Canvas cv) {
            int col = spotColor(spot);
            p.setColor(col);
            float r = size / 2f;
            for (int i = 0; i < count; i++) {
                float cx = 1 + r + i * size * 1.7f, cy = 1 + r;
                if (spot == 1) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(size * 0.24f); cv.drawCircle(cx, cy, r - size * 0.12f, p); }
                else { p.setStyle(Paint.Style.FILL); cv.drawCircle(cx, cy, r, p); }
            }
        }
    }

    /**
     * The double-knock signature: a static dot matrix of a quiet room with two knocks in it.
     * Not a live meter. It animates only once — rising in on arrival, and sweeping colour when
     * Knock is switched on or off — then sits still and costs nothing.
     */
    final class Signature extends View {
        static final int COLS = 24, ROWS = 8;
        // quiet-room floor, then the two knocks ~0.4 s apart with their ring-down
        final int[] level = {1, 1, 2, 1, 1, 1, 2, 1, 2, 8, 5, 3, 2, 1, 8, 6, 3, 2, 1, 1, 2, 1, 1, 1};
        final int[] knock = {0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 3, 0, 1, 2, 3, 3, 0, 0, 0, 0, 0, 0};
        int color, from;       // 0 = greyed out (Knock off)
        float rise = 1, sweep = 1;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Signature(int color) {
            super(c);
            this.color = color; this.from = color;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /** Columns grow up from the floor, left to right; the knocks land last with a little bounce. */
        void rise(long delay) {
            if (!Motion.enabled()) return;
            rise = 0;
            Motion.drive(1100, null, delay, a -> { rise = (float) a.getAnimatedValue(); invalidate(); });
        }

        /** Re-colour with a left-to-right wave (Knock switched on/off). */
        void sweepTo(int to) {
            from = color; color = to;
            if (!Motion.enabled()) { invalidate(); return; }
            sweep = 0;
            Motion.drive(620, Motion.EASE, 0, a -> { sweep = (float) a.getAnimatedValue(); invalidate(); });
        }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, Math.round(width / (float) COLS * ROWS * 0.86f));
        }

        @Override protected void onDraw(Canvas cv) {
            float pitchX = getWidth() / (float) COLS, pitchY = getHeight() / (float) ROWS;
            float big = Math.min(pitchX, pitchY) * 0.44f, small = big * 0.4f;
            for (int i = 0; i < COLS; i++) {
                float pos = i / (float) (COLS - 1);
                // this column's own rise progress, staggered across the row
                float local = Motion.clamp((rise - pos * 0.45f) / 0.55f);
                float shown = level[i] * Motion.POP.getInterpolation(local);
                // colour wave: columns left of the front have the new colour, with a bright crest
                float front = sweep * 1.3f - 0.15f;
                boolean newColor = pos <= front;
                float crest = Math.max(0, 1 - Math.abs(pos - front) * 9) * (sweep < 1 ? 1 : 0);
                int tint = newColor ? color : from;
                for (int r = 0; r < ROWS; r++) {
                    float cx = pitchX * (i + 0.5f), cy = getHeight() - pitchY * (r + 0.5f);
                    float fill = Motion.clamp(shown - r);   // 0 empty … 1 full, fractional at the top
                    if (fill > 0.02f) {
                        int k = knock[i];
                        p.setColor(k == 0 || tint == 0 ? dotOn : tint);
                        int a = k == 1 ? 255 : k == 2 ? 200 - r * 12 : k == 3 ? 130 : 230 - r * 30;
                        p.setAlpha(Math.max(60, Math.min(255, a)));
                        float rad = k == 1 ? big : k == 0 ? big * 0.78f : big * 0.88f;
                        rad = small + (rad - small) * fill;
                        cv.drawCircle(cx, cy, rad * (1 + crest * 0.22f), p);
                    } else {
                        p.setColor(dotOff);
                        p.setAlpha(255);
                        cv.drawCircle(cx, cy, small, p);
                    }
                }
            }
        }
    }

    /**
     * Live dot matrix for teaching: columns scroll smoothly right-to-left, rows are loudness, and a
     * knock lights its column in the spot's colour with a pop. Runs only while it is on screen.
     */
    final class Matrix extends View {
        static final int COLS = 24, ROWS = 8;
        static final long STEP = 75;   // ms per column
        interface Source { double peak(); double threshold(); boolean knocked(); }

        final Source src;
        final int tint;
        final float[] level = new float[COLS + 1];
        final boolean[] hit = new boolean[COLS + 1];
        final long[] hitAt = new long[COLS + 1];
        int head;            // index of the oldest column
        long last;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Matrix(Source src, int tint) {
            super(c);
            this.src = src; this.tint = tint;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private void push(long now) {
            double ratio = src.peak(), thr = src.threshold();
            boolean k = src.knocked();
            float lv = ratio <= 1 ? 0.6f : (float) (0.6 + Math.log(ratio) / Math.log(Math.max(thr, 2) * 4) * (ROWS - 0.6));
            if (k) lv = Math.max(lv, ROWS);
            level[head] = Math.min(ROWS, lv);
            hit[head] = k;
            hitAt[head] = now;
            head = (head + 1) % level.length;
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); last = 0; postInvalidateOnAnimation(); }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, Math.round(width / (float) COLS * ROWS * 0.86f));
        }

        @Override protected void onDraw(Canvas cv) {
            long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            if (last == 0) last = now;
            int guard = 0;
            while (now - last >= STEP && guard++ < 6) { push(now); last += STEP; }
            if (now - last > STEP * 6) last = now;
            float frac = Motion.enabled() ? Motion.clamp((now - last) / (float) STEP) : 0;
            float pitchX = getWidth() / (float) COLS, pitchY = getHeight() / (float) ROWS;
            float big = Math.min(pitchX, pitchY) * 0.46f, small = big * 0.42f;
            int n = level.length;
            for (int i = 0; i < n; i++) {
                int idx = (head + i) % n;
                float cx = pitchX * (i + 0.5f - frac);
                if (cx < -pitchX || cx > getWidth() + pitchX) continue;
                // fade at both edges so columns slide in and out instead of popping
                float edge = Math.min(Motion.clamp((cx + pitchX * 0.5f) / (pitchX * 2)), Motion.clamp((getWidth() - cx + pitchX * 0.5f) / (pitchX * 2)));
                float age = (now - hitAt[idx]) / 420f;
                float pop = hit[idx] && age < 1 ? 1 + 0.35f * (1 - Motion.POP.getInterpolation(age)) : 1;
                for (int r = 0; r < ROWS; r++) {
                    float cy = getHeight() - pitchY * (r + 0.5f);
                    if (r < level[idx]) {
                        p.setColor(hit[idx] ? tint : dotOn);
                        int a = hit[idx] ? 255 : 255 - r * 16;
                        p.setAlpha(Math.round(Math.max(40, a) * edge));
                        cv.drawCircle(cx, cy, big * (hit[idx] ? pop : 0.88f), p);
                    } else {
                        p.setColor(dotOff);
                        p.setAlpha(Math.round(255 * edge));
                        cv.drawCircle(cx, cy, small, p);
                    }
                }
            }
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
        }
    }

    /** Chevron-down for the profile button. */
    final class Chevron extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path path = new android.graphics.Path();
        Chevron() { super(c); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.6f)); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(dp(12), dp(12)); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            path.reset();
            path.moveTo(w * 0.15f, h * 0.35f); path.lineTo(w * 0.5f, h * 0.7f); path.lineTo(w * 0.85f, h * 0.35f);
        }
        @Override protected void onDraw(Canvas cv) { p.setColor(muted); cv.drawPath(path, p); }
    }

    /** Teach progress: filled up to n (each new dot pops in), the next one ringed and breathing. */
    final class Progress extends View {
        final int total; int n; int color;
        final long[] popAt;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Progress(int total, int color) { super(c); this.total = total; this.color = color; popAt = new long[total]; }
        void set(int count) {
            long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            for (int i = n; i < Math.min(count, total); i++) popAt[i] = now;
            n = count;
            setContentDescription(Math.min(n, total) + " of " + total + " knocks");
            invalidate();
        }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), dp(32)); }
        @Override protected void onDraw(Canvas cv) {
            long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            boolean moving = false;
            float pitch = getWidth() / (float) total, r = Math.min(pitch * 0.32f, dp(11));
            for (int i = 0; i < total; i++) {
                float cx = pitch * (i + 0.5f), cy = getHeight() / 2f;
                if (i < n) {
                    float age = (now - popAt[i]) / (float) Motion.POP.ms;
                    float s = age >= 1 || !Motion.enabled() ? 1 : 0.35f + 0.65f * Motion.POP.getInterpolation(age);
                    if (age < 1) moving = true;
                    p.setStyle(Paint.Style.FILL); p.setColor(color);
                    cv.drawCircle(cx, cy, r * s, p);
                } else if (i == n) {
                    float breathe = Motion.enabled() ? 1 + 0.07f * (float) Math.sin(now / 260.0) : 1;
                    moving = true;
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.5f)); p.setColor(color);
                    cv.drawCircle(cx, cy, (r - dp(1)) * breathe, p);
                } else {
                    p.setStyle(Paint.Style.FILL); p.setColor(dotOff);
                    cv.drawCircle(cx, cy, r * 0.45f, p);
                }
            }
            if (moving && Motion.enabled()) postInvalidateOnAnimation();
        }
    }

    /**
     * Taught knocks along the near↔far axis (their mic-to-mic lag), one dot each,
     * stacked where they pile up — shows how far apart the two spots sound. Dots drop in on arrival.
     */
    final class Strip extends View {
        final int[] a, b;
        long start;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] stackA = new int[0], stackB = new int[0];
        Strip(int[] a, int[] b) { super(c); this.a = a; this.b = b; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); start = 0; }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), dp(84)); }
        @Override protected void onDraw(Canvas cv) {
            long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            if (start == 0) start = now + 260;
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int x : a) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
            for (int x : b) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
            if (lo > hi) return;
            int span = Math.max(4, hi - lo);
            lo -= span / 6 + 1; hi += span / 6 + 1;
            int bins = 28;
            float pitch = getWidth() / (float) bins, r = pitch * 0.3f;
            if (stackA.length != bins) { stackA = new int[bins]; stackB = new int[bins]; }
            java.util.Arrays.fill(stackA, 0); java.util.Arrays.fill(stackB, 0);
            p.setStyle(Paint.Style.FILL);
            p.setColor(dotOff);
            p.setAlpha(255);
            for (int i = 0; i < bins; i++) cv.drawCircle(pitch * (i + 0.5f), getHeight() - r, r * 0.45f, p);
            boolean moving = draw(cv, a, lo, hi, bins, pitch, r, stackA, near, false, now, 0);
            moving |= draw(cv, b, lo, hi, bins, pitch, r, stackB, far, true, now, a.length);
            if (moving) postInvalidateOnAnimation();
        }
        boolean draw(Canvas cv, int[] xs, int lo, int hi, int bins, float pitch, float r, int[] stack, int color, boolean ring, long now, int offset) {
            boolean moving = false;
            p.setStyle(ring ? Paint.Style.STROKE : Paint.Style.FILL);
            p.setStrokeWidth(r * 0.45f);
            for (int k = 0; k < xs.length; k++) {
                int x = xs[k];
                int bin = Math.min(bins - 1, Math.max(0, Math.round((x - lo) / (float) (hi - lo) * (bins - 1))));
                int level = stack[bin]++;
                float cy = getHeight() - r - (level + 1) * pitch * 0.8f;
                if (cy < r) continue;
                float age = Motion.enabled() ? (now - start - (offset + k) * 22) / (float) Motion.POP.ms : 1;
                if (age <= 0) { moving = true; continue; }
                if (age < 1) moving = true;
                float e = Motion.POP.getInterpolation(Math.min(1, age));
                p.setColor(color);
                p.setAlpha(Math.round(255 * Motion.clamp(age * 3)));
                cv.drawCircle(pitch * (bin + 0.5f), cy - (1 - e) * dp(14), ring ? r - r * 0.22f : r, p);
            }
            return moving;
        }
    }

    // ---------- pages & sheets ----------

    /** Scrollable page that respects the system bars. Returns the column to fill. */
    LinearLayout page(Activity a) {
        View old = a.findViewById(android.R.id.content);
        int keepY = 0;
        if (old instanceof ViewGroup && ((ViewGroup) old).getChildCount() > 0 && ((ViewGroup) old).getChildAt(0) instanceof ScrollView)
            keepY = ((ViewGroup) old).getChildAt(0).getScrollY();
        LinearLayout col = column();
        col.setPadding(dp(22), dp(12), dp(22), dp(24));
        ScrollView sv = new ScrollView(c);
        sv.setBackgroundColor(bg);
        sv.setFillViewport(true);
        sv.setClipToPadding(true);
        sv.setVerticalScrollBarEnabled(false);
        final int y = keepY;
        sv.post(() -> sv.scrollTo(0, y));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets s = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return in;
        });
        a.setContentView(sv);
        return col;
    }

    /** Bottom sheet in the app's style. Fill the returned column; call sheet.show(). */
    Sheet sheet(CharSequence title, CharSequence sub) { return new Sheet(title, sub); }

    /**
     * Bottom sheet: full-window dialog with its own scrim, so the scrim fades while the sheet rides
     * up on a spring. Drag the top of the sheet down (or tap outside, or Back) to send it away.
     */
    final class Sheet {
        final Dialog d;
        final LinearLayout body, sheet;
        final View scrim;
        boolean leaving;

        Sheet(CharSequence title, CharSequence sub) {
            d = new Dialog(c, R.style.Knock_Sheet);
            android.widget.FrameLayout frame = new android.widget.FrameLayout(c);
            scrim = new View(c);
            scrim.setBackgroundColor(dark ? 0x99000000 : 0x52000000);
            scrim.setOnClickListener(v -> dismiss(null));
            frame.addView(scrim, new android.widget.FrameLayout.LayoutParams(-1, -1));

            sheet = column();
            GradientDrawable bgd = round(surface, 0);
            float r = dp(28);
            bgd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            sheet.setBackground(bgd);
            sheet.setClickable(true);   // taps on the sheet don't reach the scrim

            LinearLayout head = column();
            head.setPadding(dp(24), dp(10), dp(24), 0);
            View grab = new View(c);
            grab.setBackground(round(line, 3));
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(36), dp(4));
            gp.gravity = Gravity.CENTER_HORIZONTAL;
            gp.bottomMargin = dp(18);
            head.addView(grab, gp);
            if (title != null) head.addView(headline(title, 28));
            if (sub != null) head.addView(text(sub, 13, muted), wide(5));
            sheet.addView(head);
            dragToDismiss(head);

            body = column();
            body.setPadding(dp(24), 0, dp(24), 0);
            ScrollView sv = new ScrollView(c);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(body);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = dp(12);
            sheet.addView(sv, sp);

            android.widget.FrameLayout.LayoutParams fp = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            frame.addView(sheet, fp);
            d.setContentView(frame);

            int maxSv = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.82f) - dp(130);
            sv.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (sv.getHeight() > maxSv) {
                    ViewGroup.LayoutParams lp = sv.getLayoutParams();
                    if (lp.height != maxSv) { lp.height = maxSv; sv.setLayoutParams(lp); }
                }
            });

            Window w = d.getWindow();
            if (w != null) {
                w.setLayout(-1, -1);
                w.setBackgroundDrawable(new ColorDrawable(0));
                w.setWindowAnimations(0);
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDecorFitsSystemWindows(false);
                w.setStatusBarColor(0);
                w.setNavigationBarColor(0);
                WindowInsetsController ic = w.getInsetsController();
                if (ic != null) {
                    int light = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ic.setSystemBarsAppearance(dark ? 0 : light, light);
                }
            }
            sheet.setOnApplyWindowInsetsListener((v, in) -> {
                int bottom = Math.max(in.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom,
                        in.getInsets(android.view.WindowInsets.Type.ime()).bottom);
                v.setPadding(0, 0, 0, dp(20) + bottom);
                return in;
            });
            d.setOnKeyListener((dlg, key, e) -> {
                if (key == android.view.KeyEvent.KEYCODE_BACK && e.getAction() == android.view.KeyEvent.ACTION_UP) { dismiss(null); return true; }
                return key == android.view.KeyEvent.KEYCODE_BACK;
            });
        }

        private void dragToDismiss(View handle) {
            handle.setOnTouchListener(new View.OnTouchListener() {
                float y0; android.view.VelocityTracker vt;
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            y0 = e.getRawY(); vt = android.view.VelocityTracker.obtain(); vt.addMovement(e);
                            sheet.animate().cancel();
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE: {
                            if (vt != null) vt.addMovement(e);
                            float dy = e.getRawY() - y0;
                            // pull down freely, resist pulling up
                            sheet.setTranslationY(dy > 0 ? dy : dy * 0.15f);
                            scrim.setAlpha(1 - Motion.clamp(dy / Math.max(1, sheet.getHeight())));
                            return true;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL: {
                            float vy = 0;
                            if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                            float dy = sheet.getTranslationY();
                            if (dy > sheet.getHeight() * 0.28f || vy > dp(900)) { Motion.gesture(sheet); dismiss(null); }
                            else {
                                sheet.animate().translationY(0).setDuration(Motion.SNAP.ms).setInterpolator(Motion.SNAP).start();
                                scrim.animate().alpha(1).setDuration(160).start();
                            }
                            return true;
                        }
                    }
                    return false;
                }
            });
        }

        /** A choice row; selected rows get a dot on the right. */
        Sheet option(CharSequence title, CharSequence sub, boolean selected, Runnable pick) {
            return option(null, title, sub, selected, pick);
        }

        Sheet option(View leading, CharSequence title, CharSequence sub, boolean selected, Runnable pick) {
            View mark = null;
            if (selected) {
                View dot = new View(c);
                dot.setBackground(round(near, 5));
                dot.setLayoutParams(new LinearLayout.LayoutParams(dp(10), dp(10)));
                mark = dot;
            }
            if (body.getChildCount() > 0) body.addView(divider());
            LinearLayout r = listRow(leading, title, sub, mark, v -> dismiss(pick));
            if (selected) r.setContentDescription(title + ", selected");
            body.addView(r);
            return this;
        }

        Sheet view(View v, int topDp) { body.addView(v, wide(topDp)); return this; }

        /** Put a view in the sheet's header, under the title (e.g. a search field). */
        Sheet header(View v, int topDp) {
            LinearLayout head = (LinearLayout) sheet.getChildAt(0);
            head.addView(v, wide(topDp));
            return this;
        }

        /**
         * Tall sheet whose content is {@code v} filling the rest of the height (a virtualised list).
         * The keyboard shrinks the list instead of pushing the sheet off screen.
         */
        Sheet fill(View v) {
            View sv = sheet.getChildAt(1);
            sheet.removeView(sv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
            lp.topMargin = dp(8);
            sheet.addView(v, lp);
            ViewGroup.LayoutParams sp = sheet.getLayoutParams();
            sp.height = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.86f);
            sheet.setLayoutParams(sp);
            return this;
        }

        Sheet action(CharSequence s, boolean primary, Runnable run) {
            Button b = button(s, primary);
            b.setOnClickListener(v -> dismiss(run));
            body.addView(b, wide(body.getChildCount() == 0 ? 4 : 10, 52));
            return this;
        }

        void show() {
            d.show();
            if (!Motion.enabled()) return;
            scrim.setAlpha(0);
            sheet.setVisibility(View.INVISIBLE);
            sheet.post(() -> {
                sheet.setTranslationY(sheet.getHeight());
                sheet.setVisibility(View.VISIBLE);
                scrim.animate().alpha(1).setDuration(220).setInterpolator(Motion.EASE_OUT).start();
                sheet.animate().translationY(0).setDuration(Motion.SHEET.ms).setInterpolator(Motion.SHEET).start();
                // rows follow the sheet up, one after another
                int n = 0;
                for (int i = 0; i < body.getChildCount() && n < 9; i++) {
                    View ch = body.getChildAt(i);
                    if (ch.getHeight() <= 2) continue;   // dividers come along with their row
                    ch.setAlpha(0);
                    ch.setTranslationY(dp(14));
                    ch.animate().alpha(1).translationY(0).setStartDelay(70 + n * 28L)
                            .setDuration(Motion.SOFT.ms).setInterpolator(Motion.SOFT).start();
                    n++;
                }
            });
        }

        /** Slide away, then run {@code after} (if any) once the sheet has mostly left. */
        void dismiss(Runnable after) {
            if (leaving) return;
            leaving = true;
            if (!Motion.enabled()) { d.dismiss(); if (after != null) after.run(); return; }
            scrim.animate().alpha(0).setDuration(200).setInterpolator(Motion.EASE).start();
            sheet.animate().translationY(sheet.getHeight() + dp(24)).setDuration(220).setInterpolator(Motion.EASE_IN)
                    .withEndAction(d::dismiss).start();
            if (after != null) sheet.postDelayed(after, 120);
        }
    }

    LinearLayout.LayoutParams wide(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    LinearLayout.LayoutParams wide(int topDp, int heightDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        p.topMargin = dp(topDp);
        return p;
    }

    LinearLayout.LayoutParams weight(int leftDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.leftMargin = dp(leftDp);
        return p;
    }
}
