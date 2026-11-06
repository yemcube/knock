package com.littyl.knock;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.littyl.knock.core.Knock;
import com.littyl.knock.core.Model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Home (setup, on/off, the four commands, activity), the Teach flow, and the pickers. */
public class MainActivity extends Activity implements KnockService.Listener {
    static final int TEACH_COUNT = 8;

    final Handler ui = new Handler(Looper.getMainLooper());
    Ui u;
    SharedPreferences prefs;
    boolean probing, resumed;

    // live views on the current screen
    LinearLayout feed;

    // teach state
    boolean inTeach;
    boolean teachMore;             // add to the profile's existing teaching instead of replacing it
    int teachStep;                 // 0 intro, 1 spot A, 2 spot B, 3 result
    String teachOrient;
    List<Knock> spotA, spotB;
    Ui.Progress progress;
    TextView teachHint;
    Button teachNext;

    // motion state
    int enter = 2;              // next screen build: 2 reveal in place, 1 forward, −1 back, 0 re-render quietly
    boolean powerFlip;          // the last build follows the On/Off switch: sweep the signature, refresh the headline
    boolean powerPending;       // switch is animating: the service's own state callback must not redraw underneath it
    Ui.Signature sig;
    View headlineView;
    long lastFeedTop;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        u = new Ui(this);
        u.window(this);
        prefs = Prefs.get(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        KnockService.listener = this;
        if (!inTeach) home();
        if (hasMic() && !prefs.contains(Prefs.SOURCE)) probe();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (KnockService.listener == this) KnockService.listener = null;
    }

    /** Light/dark switched: rebuild with the new tokens, keeping where we are. */
    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        u = new Ui(this);
        u.window(this);
        enter = 0;
        if (!inTeach) { home(); return; }
        teachScreen();
        KnockService s = KnockService.running;
        if (s != null && (teachStep == 1 || teachStep == 2)) onTeachKnock(s.teachCount());
    }

    @Override
    public void onBackPressed() {
        if (inTeach) { cancelTeach(); return; }
        super.onBackPressed();
    }

    boolean hasMic() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

    boolean hasNotif() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasAccess() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName cn = new ComponentName(this, KnockAccess.class);
        return s != null && (s.contains(cn.flattenToString()) || s.contains(cn.flattenToShortString()));
    }

    Model model(String orient) { return model(Prefs.active(prefs), orient); }

    Model model(String profile, String orient) { return Model.parse(prefs.getString(Prefs.modelKey(profile, orient), "")); }

    boolean taught(String orient) { return model(orient) != null; }

    String currentOrient() {
        KnockService s = KnockService.running;
        if (s != null) return s.orientation();
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait";
    }

    static String shortName(String action) {
        int p = action.indexOf(" (");
        return p > 0 ? action.substring(0, p) : action;
    }

    /** Teach only: the live dot matrix, knocks lit in the spot's colour. */
    View liveCard(int color, String right) {
        LinearLayout card = u.card();
        card.setPadding(u.dp(18), u.dp(20), u.dp(18), u.dp(6));
        card.addView(u.new Matrix(new Ui.Matrix.Source() {
            @Override public double peak() { KnockService s = KnockService.running; return s == null ? 0 : s.takePeak(); }
            @Override public double threshold() { KnockService s = KnockService.running; return s == null ? 5 : s.thresholdRatio(); }
            @Override public boolean knocked() { KnockService s = KnockService.running; return s != null && s.takeTeachKnock(); }
        }, color));
        LinearLayout cap = u.row();
        cap.setMinimumHeight(u.dp(40));
        cap.addView(u.monoText("listening", 11, u.faint), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) cap.addView(u.monoText(right, 11, color));
        card.addView(cap, u.wide(10));
        return card;
    }

    /** The static double-knock signature with a caption under it. */
    View signatureCard(int color, String left, String right, int rightColor) {
        LinearLayout card = u.card();
        card.setPadding(u.dp(18), u.dp(20), u.dp(18), u.dp(6));
        sig = u.new Signature(color);
        card.addView(sig);
        LinearLayout cap = u.row();
        cap.setMinimumHeight(u.dp(40));
        cap.addView(u.monoText(left, 11, u.faint), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) cap.addView(u.monoText(right, 11, rightColor));
        card.addView(cap, u.wide(10));
        return card;
    }

    // ---------------- home ----------------

    void home() {
        inTeach = false;
        progress = null;
        sig = null; headlineView = null;
        LinearLayout col = u.page(this);
        homeInto(col);
        settle(col);
    }

    /** After a screen is built: reveal it if we navigated here, otherwise animate only what changed. */
    void settle(LinearLayout col) {
        int dir = enter;
        enter = 0;
        if (dir != 0) {
            Motion.reveal(col, dir == 2 ? 0 : dir);
            if (sig != null) sig.rise(dir == 2 ? 160 : 220);
        } else if (powerFlip && sig != null) {
            sig.sweepTo(KnockService.running != null ? u.near : 0);
            if (headlineView != null) Motion.reveal((LinearLayout) headlineView, 0);
        }
        powerFlip = false;
    }

    void homeInto(LinearLayout col) {

        boolean on = KnockService.running != null;
        boolean anyModel = taught("portrait") || taught("landscape");
        String orient = currentOrient();

        // header: name and the on/off switch
        boolean ready = hasMic() && hasNotif() && hasAccess() && anyModel && !probing;
        LinearLayout head = u.row();
        head.setMinimumHeight(u.dp(56));
        TextView brand = u.headline("Knock", 30);
        brand.setOnLongClickListener(v -> { Motion.confirm(v); developer(); return true; });
        head.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (ready) {
            Ui.Toggle power = u.new Toggle(on, true);
            power.setOnClickListener(v -> togglePower(power));
            head.addView(power);
        }
        col.addView(head);

        if (!ready) { setup(col, anyModel); return; }

        // profile
        LinearLayout profRow = u.row();
        View prof = u.pill(Prefs.activeName(prefs), v -> pickProfile());
        prof.setContentDescription("Profile " + Prefs.activeName(prefs) + ". Switch or add a table.");
        profRow.addView(prof);
        profRow.addView(u.spacer());
        profRow.addView(u.monoText(on ? orient : "", 11, u.faint));
        col.addView(profRow, u.wide(10));

        // signature: what a command looks like
        Model sm = model(orient);
        String a2 = shortName(Actions.name(this, Prefs.action(prefs, Prefs.active(prefs), "A2")));
        boolean oneSpot = sm != null && sm.spots == 1;
        col.addView(signatureCard(powerFlip ? (on ? 0 : u.near) : on ? u.near : 0, "knock · knock",
                (oneSpot ? "twice" : Prefs.suggestedSpots(orient)[0].toLowerCase(Locale.US)) + " → " + a2.toLowerCase(Locale.US),
                on ? u.near : u.faint), u.wide(16));

        String[] hl = headline(on, orient);
        headlineView = u.headline2(hl[0], hl[1], 36);
        col.addView(headlineView, u.wide(22));

        // commands
        Model m = model(orient);
        if (m == null) m = model(orient.equals("portrait") ? "landscape" : "portrait");
        int spots = m == null ? 2 : m.spots;
        String[] spotNames = Prefs.suggestedSpots(orient);
        LinearLayout grid = u.column();
        LinearLayout gridRow = null;
        int n = 0;
        for (String slot : Prefs.SLOTS) {
            int spot = slot.charAt(0) == 'A' ? 0 : 1, count = slot.charAt(1) - '0';
            if (spot >= spots) continue;
            if (n % 2 == 0) { gridRow = u.row(); gridRow.setBaselineAligned(false); grid.addView(gridRow, u.wide(n == 0 ? 0 : 10)); }
            String action = Prefs.action(prefs, Prefs.active(prefs), slot);
            String where = spots == 1 ? (count == 2 ? "twice" : "3 times") : spotNames[spot].toLowerCase(Locale.US);
            boolean warn = Actions.needsAccessibility(action) && !hasAccess();
            gridRow.addView(tile(spots == 1 ? 0 : spot, count, where, action, shortName(Actions.name(this, action)), warn, v -> pickAction(slot)), u.weight(n % 2 == 0 ? 0 : 10));
            n++;
        }
        col.addView(grid, u.wide(22));

        // activity
        col.addView(u.label("Recent"), u.wide(30));
        feed = u.column();
        col.addView(feed, u.wide(6));
        renderFeed();

        // settings
        col.addView(u.label("Settings"), u.wide(30));
        LinearLayout set = u.column();
        set.addView(toggleRow("Practice mode", "Recognise knocks without running actions", Prefs.PRACTICE, false));
        set.addView(u.divider());
        set.addView(toggleRow("Vibrate on each command", null, Prefs.HAPTICS, true));
        set.addView(u.divider());
        set.addView(u.listRow("Portrait", teachLine("portrait"), null, null));
        set.addView(u.divider());
        set.addView(u.listRow("Landscape", teachLine("landscape"), null, null));
        if (taught(orient)) {
            set.addView(u.divider());
            set.addView(u.listRow("Start " + orient + " over", "Forget this profile's " + orient + " knocks and teach fresh",
                    chevron(), v -> startTeach(false)));
        }
        col.addView(set, u.wide(4));

        // teach more
        boolean has = taught(orient);
        col.addView(u.text(has ? "Knocks sometimes ignored? Teach more — it adds to what " + Prefs.activeName(prefs)
                + " knows. New table? Add a profile instead." : "Rotate the phone in its holder first to teach the other way round.", 13, u.muted), u.wide(28));
        Button t = u.button(has ? "Teach more knocks" : "Teach " + orient, true);
        t.setOnClickListener(v -> startTeach(has));
        col.addView(t, u.wide(14, 54));
    }

    /** The switch moves at once; the screen follows when the service has actually changed state. */
    String[] headline(boolean on, String orient) {
        if (!on) return new String[]{"Knock is off.", "Switch it on to listen."};
        if (!taught(orient)) return new String[]{"Not taught for " + orient + ".", "Teach this way round to use it."};
        if (prefs.getBoolean(Prefs.PRACTICE, false)) return new String[]{"Practice mode.", "Knocks show here, nothing runs."};
        return new String[]{"Knock on the desk.", "Your phone does the rest."};
    }

    /** Swap the headline's words without rebuilding the screen: old lines lift out, new ones settle in. */
    void updateHeadline() {
        if (!(headlineView instanceof LinearLayout)) return;
        LinearLayout g = (LinearLayout) headlineView;
        String[] hl = headline(KnockService.running != null, currentOrient());
        for (int i = 0; i < g.getChildCount() && i < 2; i++) {
            TextView t = (TextView) g.getChildAt(i);
            String next = hl[i];
            if (next.contentEquals(t.getText())) continue;
            if (!Motion.enabled()) { t.setText(next); continue; }
            long delay = i * 50L;
            t.animate().alpha(0).translationY(-u.dp(6)).setStartDelay(delay).setDuration(120).setInterpolator(Motion.EASE_IN)
                    .withEndAction(() -> {
                        t.setText(next);
                        t.setTranslationY(u.dp(10));
                        t.animate().alpha(1).translationY(0).setStartDelay(0).setDuration(Motion.SOFT.ms).setInterpolator(Motion.SOFT).withEndAction(null).start();
                    }).start();
        }
    }

    /**
     * Prominent disclosure required by Google Play before the user is sent to enable an
     * accessibility service: what the AccessibilityService API is used for, what is not collected,
     * and an explicit choice. Nothing happens without the affirmative button.
     */
    void accessDisclosure() {
        Ui.Sheet sh = u.sheet("Let Knock act for you", "Knock uses Android's AccessibilityService API.");
        TextView body = u.text("When you knock a command you set up, Knock uses the AccessibilityService API to carry it out: "
                + "scroll, swipe, go back, go home, open recent apps, notifications or quick settings, take a screenshot or lock the screen.\n\n"
                + "• It acts only right after a knock you taught it, and only with the action you picked.\n"
                + "• It does not read, collect or store anything on your screen.\n"
                + "• Nothing leaves your phone. Knock has no internet access.\n"
                + "• You can turn it off any time in Settings › Accessibility.", 15, u.ink);
        body.setLineSpacing(0, 1.3f);
        sh.view(body, 4);
        sh.action("Turn on in Accessibility", true, () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        sh.action("Not now", false, () -> { });
        sh.show();
    }

    /** Hidden tools for tuning Knock (long-press the title): test capture and raw recording. */
    void developer() {
        Ui.Sheet sh = u.sheet("Developer", "Tools for tuning Knock. Files stay on the phone and are pulled over USB.");
        KnockService svc = KnockService.running;
        if (svc != null) {
            boolean cap = svc.capturing();
            sh.option(u.new Icon("record", u.ink, true), cap ? "Stop test recording" : "Record a test",
                    cap ? "Recording what Knock hears" : "Saves " + KnockService.CAPTURE_SECONDS / 60 + " min of what Knock hears while you use the phone",
                    false, () -> { if (svc.capturing()) svc.wantCapture = false; else svc.startCapture(); });
        }
        sh.option(u.new Icon("data", u.ink, true), "Record raw data", "Labelled knocks for tuning other phones", false, () -> {
            if (KnockService.running != null) KnockService.stop(this);
            startActivity(new Intent(this, RecorderActivity.class));
        });
        sh.show();
    }

    void togglePower(Ui.Toggle power) {
        if (powerPending) return;
        boolean want = KnockService.running == null;
        powerPending = true;
        power.set(want);
        if (want) KnockService.start(this); else KnockService.stop(this);
        long t0 = System.currentTimeMillis();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                boolean done = (KnockService.running != null) == want;
                if (!done && System.currentTimeMillis() - t0 < 2000) { ui.postDelayed(this, 80); return; }
                powerPending = false;
                if (inTeach) return;
                powerFlip = true;
                home();
            }
        }, Motion.SNAP.ms);
    }

    String teachLine(String orient) {
        Model pm = model(orient);
        if (pm == null) return "Not taught";
        return "Taught " + (pm.sessions == 1 ? "once" : pm.sessions + " times") + " · " + pm.knockCount() + " knocks"
                + (pm.spots == 1 ? " · one spot" : "");
    }

    TextView chevron() { return u.text("›", 20, u.faint); }

    View tile(int spot, int count, String where, String actionId, String action, boolean warn, View.OnClickListener click) {
        LinearLayout t = u.column();
        t.setPadding(u.dp(14), u.dp(14), u.dp(14), u.dp(14));
        t.setMinimumHeight(u.dp(104));
        t.setBackground(u.pressable(u.outlined(u.surface, u.line, 18)));
        LinearLayout top = u.row();
        top.addView(u.glyph(spot, count, 7));
        top.addView(u.spacer());
        top.addView(u.actionIcon(actionId, false), new LinearLayout.LayoutParams(u.dp(22), u.dp(22)));
        t.addView(top);
        View gap = new View(this);
        t.addView(gap, new LinearLayout.LayoutParams(1, 0, 1f));
        t.addView(u.text(warn ? where + " · needs Accessibility" : where, 11, warn ? u.bad : u.muted));
        TextView a = u.medium(action, 15, u.ink);
        a.setMaxLines(2);
        t.addView(a, u.wide(3));
        t.setOnClickListener(click);
        Motion.press(t, 0.96f);
        t.setContentDescription("Knock " + (count == 2 ? "twice" : "three times") + " " + where + ": " + action + ". Double-tap to change.");
        return t;
    }

    View toggleRow(String title, String sub, String key, boolean def) {
        boolean on = prefs.getBoolean(key, def);
        Ui.Toggle tg = u.toggle(on);
        LinearLayout r = u.listRow(title, sub, tg, null);
        r.setOnClickListener(v -> {
            boolean now = !tg.on;
            tg.set(now);
            prefs.edit().putBoolean(key, now).apply();
            v.setContentDescription(title + ", " + (now ? "on" : "off"));
            if (key.equals(Prefs.PRACTICE)) updateHeadline();
        });
        r.setBackground(u.pressable(u.round(0x00000000, 12)));
        Motion.press(r, 0.985f);
        r.setContentDescription(title + ", " + (on ? "on" : "off"));
        return r;
    }

    void renderFeed() {
        if (feed == null) return;
        feed.removeAllViews();
        List<KnockService.Event> list;
        synchronized (KnockService.recent) { list = new ArrayList<>(KnockService.recent.subList(0, Math.min(5, KnockService.recent.size()))); }
        if (list.isEmpty()) {
            LinearLayout r = u.row();
            r.setMinimumHeight(u.dp(52));
            r.addView(u.text("Nothing yet. Your knocks show up here.", 14, u.faint));
            feed.addView(r);
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
        boolean fresh = lastFeedTop != 0 && list.get(0).time != lastFeedTop;
        lastFeedTop = list.get(0).time;
        boolean first = true;
        for (KnockService.Event e : list) {
            if (!first) feed.addView(u.divider());
            first = false;
            LinearLayout r = u.row();
            r.setMinimumHeight(u.dp(48));
            TextView time = u.monoText(f.format(new Date(e.time)), 11, u.faint);
            r.addView(time, new LinearLayout.LayoutParams(u.dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout g = u.row();
            g.addView(e.command ? u.glyph(e.spot, e.count, 6) : u.glyph(-1, Math.max(1, Math.min(3, e.count)), 6));
            r.addView(g, new LinearLayout.LayoutParams(u.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
            String what = e.command ? shortName(e.what) : e.what + (e.result.isEmpty() ? "" : " — " + e.result);
            TextView w = u.text(what, 15, e.command ? u.ink : u.faint);
            w.setSingleLine();
            w.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(w, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (e.command && !e.ok()) r.addView(u.monoText(e.result.startsWith("practice") ? "practice" : e.result, 11, e.result.startsWith("practice") || e.result.startsWith("skipped") ? u.faint : u.bad));
            feed.addView(r);
            if (fresh && feed.getChildCount() == 1) Motion.refresh(r);
        }
    }

    // ---------------- setup ----------------

    void setup(LinearLayout col, boolean anyModel) {
        col.addView(signatureCard(0, probing ? "checking microphones…" : "knock · knock", null, 0), u.wide(14));
        col.addView(u.headline2("Four steps,", "then just knock.", 40), u.wide(22));
        col.addView(u.text("Knock hears taps on your desk through the phone's microphones and turns them into commands.", 15, u.muted), u.wide(10));

        boolean mic = hasMic(), notif = hasNotif(), access = hasAccess();
        boolean current = false;   // the first unfinished step gets the button
        LinearLayout list = u.column();
        String[][] steps = {
                {"Microphone", "Listens for knocks. Audio stays on the phone and is never saved.", "Allow"},
                {"Notifications", "Shows when Knock is listening, with a quick off switch.", "Allow"},
                {"Accessibility", "Lets Knock scroll, go back and open apps for you. It doesn't read your screen. Find “Knock” under Installed apps.", "Continue"},
                {"Teach your desk", "Knock 8 times on two spots so Knock learns how your desk sounds. About a minute.", "Teach"},
        };
        boolean[] done = {mic, notif, access, anyModel};
        for (int i = 0; i < steps.length; i++) {
            boolean isCurrent = !done[i] && !current && !(i == 3 && (probing || !mic));
            if (isCurrent) current = true;
            if (i > 0) list.addView(u.divider());
            list.addView(step(i, steps[i], done[i], isCurrent));
        }
        col.addView(list, u.wide(22));
    }

    View step(int i, String[] s, boolean done, boolean current) {
        LinearLayout r = u.row();
        r.setGravity(Gravity.TOP);
        r.setPadding(0, u.dp(16), 0, u.dp(16));
        // marker: number, or a filled dot when done
        TextView num = u.monoText(done ? "●" : String.format(Locale.US, "%02d", i + 1), 12, done ? u.good : current ? u.ink : u.faint);
        r.addView(num, new LinearLayout.LayoutParams(u.dp(34), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout body = u.column();
        TextView title = u.medium(s[0], 16, done ? u.faint : u.ink);
        body.addView(title);
        if (!done) body.addView(u.text(s[1], 13, u.muted), u.wide(4));
        if (current) {
            Button b = u.button(s[2], true);
            b.setOnClickListener(v -> {
                switch (i) {
                    case 0: requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); break;
                    case 1: requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2); break;
                    case 2: accessDisclosure(); break;
                    default: startTeach(false);
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, u.dp(44));
            p.topMargin = u.dp(12);
            body.addView(b, p);
        }
        r.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setContentDescription("Step " + (i + 1) + ", " + s[0] + (done ? ", done" : ""));
        return r;
    }

    // ---------------- pickers ----------------

    void pickProfile() {
        List<String[]> list = Prefs.profiles(prefs);
        String active = Prefs.active(prefs);
        Ui.Sheet sh = u.sheet("Profiles", "One per table or setup. Each learns its own knocks.");
        for (String[] pr : list) {
            Model p = model(pr[0], "portrait"), l = model(pr[0], "landscape");
            int knocks = (p == null ? 0 : p.knockCount()) + (l == null ? 0 : l.knockCount());
            String sub = p == null && l == null ? "Not taught yet" : knocks + " knocks taught"
                    + (p != null && l != null ? " · both ways round" : p != null ? " · portrait" : " · landscape");
            sh.option(pr[1], sub, pr[0].equals(active), () -> switchProfile(pr[0]));
        }
        sh.action("New profile", true, () -> askName("New profile", "Office desk", "Then teach it — about a minute.",
                name -> { switchProfile(Prefs.newProfile(prefs, name)); startTeach(false); }));
        if (!active.equals(Prefs.DEFAULT)) {
            String name = Prefs.activeName(prefs);
            LinearLayout r = u.row();
            r.setGravity(Gravity.CENTER);
            r.addView(u.link("Rename " + name, u.ink, v -> { sh.d.dismiss(); askName("Rename", name, null, nn -> { Prefs.renameProfile(prefs, active, nn); profileChanged(); }); }));
            View sp = new View(this);
            r.addView(sp, new LinearLayout.LayoutParams(u.dp(18), 1));
            r.addView(u.link("Delete", u.bad, v -> { sh.d.dismiss(); confirmDelete(active, name); }));
            sh.view(r, 6);
        }
        sh.show();
    }

    void confirmDelete(String id, String name) {
        u.sheet("Delete " + name + "?", "Its teaching and commands are removed. Default stays.")
                .action("Delete", true, () -> { Prefs.deleteProfile(prefs, id); profileChanged(); })
                .action("Cancel", false, () -> { })
                .show();
    }

    void switchProfile(String id) {
        prefs.edit().putString(Prefs.ACTIVE, id).apply();
        profileChanged();
    }

    void profileChanged() {
        KnockService s = KnockService.running;
        if (s != null) s.profileChanged();
        enter = 2;
        home();
    }

    void askName(String title, String hint, String sub, java.util.function.Consumer<String> done) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine();
        e.setTextSize(17);
        e.setTypeface(Ui.sans);
        e.setTextColor(u.ink);
        e.setHintTextColor(u.faint);
        e.setBackground(u.outlined(u.bg, u.line, 14));
        e.setPadding(u.dp(16), 0, u.dp(16), 0);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (title.equals("Rename")) { e.setText(hint); e.selectAll(); }
        Ui.Sheet sh = u.sheet(title, sub);
        Runnable save = () -> {
            String n = e.getText().toString().trim();
            done.accept(n.isEmpty() ? hint : n);
        };
        sh.view(e, 4);
        ((LinearLayout.LayoutParams) e.getLayoutParams()).height = u.dp(52);
        sh.action("Save", true, save);
        e.setOnEditorActionListener((v, a, ev) -> { sh.d.dismiss(); save.run(); return true; });
        sh.d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sh.show();
        e.requestFocus();
    }

    void pickAction(String slot) {
        int spot = slot.charAt(0) == 'A' ? 0 : 1, count = slot.charAt(1) - '0';
        String orient = currentOrient();
        Model m = model(orient);
        boolean one = m != null && m.spots == 1;
        String title = one ? "Knock " + (count == 2 ? "twice" : "3 times") : Prefs.commandName(orient, spot, count);
        String cur = Prefs.action(prefs, Prefs.active(prefs), slot);
        Ui.Sheet sh = u.sheet(title, "What happens · " + Prefs.activeName(prefs));
        for (int i = 0; i < Actions.IDS.length; i++) {
            String id = Actions.IDS[i];
            sh.option(u.new Icon(id, u.ink, true), Actions.NAMES[i], Actions.needsAccessibility(id) && !hasAccess() ? "Needs Accessibility" : null, id.equals(cur),
                    () -> { Prefs.setAction(prefs, Prefs.active(prefs), slot, id); home(); });
        }
        boolean app = cur.startsWith(Actions.OPEN_APP);
        sh.option(app ? u.actionIcon(cur, true) : u.new Icon("app", u.ink, true), "Open an app…", app ? Actions.name(this, cur) : null, app, () -> pickApp(slot));
        sh.show();
    }

    /** All launchable apps with search; icons load in the background and are cached. */
    void pickApp(String slot) {
        PackageManager pm = getPackageManager();
        String cur = Prefs.action(prefs, Prefs.active(prefs), slot);
        Ui.Sheet sh = u.sheet("Open which app?", null);

        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine();
        search.setTextSize(16);
        search.setTypeface(Ui.sans);
        search.setTextColor(u.ink);
        search.setHintTextColor(u.faint);
        search.setBackground(u.round(u.dotOff, 14));
        search.setPadding(u.dp(16), 0, u.dp(16), 0);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setCompoundDrawablePadding(u.dp(10));
        sh.header(search, 14);
        ((LinearLayout.LayoutParams) search.getLayoutParams()).height = u.dp(48);

        AppList list = new AppList(slot, cur, sh);
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setDivider(null);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
        lv.setVerticalScrollBarEnabled(false);
        lv.setClipToPadding(false);
        lv.setPadding(u.dp(16), 0, u.dp(16), u.dp(8));
        lv.setAdapter(list);
        sh.fill(lv);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence q, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence q, int a, int b, int c) { list.filter(q.toString()); }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });
        search.setOnEditorActionListener((v, a, e) -> {
            if (list.getCount() > 0) list.pick(0);
            return true;
        });
        sh.show();

        new Thread(() -> {
            List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            List<String[]> all = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ResolveInfo r : apps) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                all.add(new String[]{r.loadLabel(pm).toString(), pkg});
            }
            Collections.sort(all, (a, b) -> a[0].compareToIgnoreCase(b[0]));
            ui.post(() -> list.set(all));
        }, "knock-apps").start();
    }

    /** Adapter for the app picker: recycled rows, search filter, async cached icons. */
    final class AppList extends android.widget.BaseAdapter {
        final String slot, cur;
        final Ui.Sheet sheet;
        List<String[]> all = new ArrayList<>(), shown = new ArrayList<>();
        String query = "";
        final android.util.LruCache<String, android.graphics.drawable.Drawable> icons = new android.util.LruCache<>(160);
        final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor();

        AppList(String slot, String cur, Ui.Sheet sheet) {
            this.slot = slot; this.cur = cur; this.sheet = sheet;
            sheet.d.setOnDismissListener(d -> loader.shutdownNow());
        }

        void set(List<String[]> apps) { all = apps; filter(query); }

        void filter(String q) {
            query = q.trim().toLowerCase(Locale.getDefault());
            List<String[]> starts = new ArrayList<>(), contains = new ArrayList<>();
            for (String[] a : all) {
                String name = a[0].toLowerCase(Locale.getDefault());
                if (query.isEmpty() || name.startsWith(query)) starts.add(a);
                else if (name.contains(query)) contains.add(a);   // names only: package ids give odd matches
            }
            starts.addAll(contains);   // names that start with the query first
            shown = starts;
            notifyDataSetChanged();
        }

        void pick(int i) {
            String[] a = shown.get(i);
            sheet.dismiss(() -> { Prefs.setAction(prefs, Prefs.active(prefs), slot, Actions.OPEN_APP + a[1]); home(); });
        }

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout r;
            android.widget.ImageView icon;
            TextView name;
            View mark;
            if (convert == null) {
                r = u.row();
                r.setMinimumHeight(u.dp(60));
                r.setPadding(u.dp(8), u.dp(8), u.dp(8), u.dp(8));
                r.setBackground(u.pressable(u.round(0x00000000, 14)));
                icon = new android.widget.ImageView(MainActivity.this);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(u.dp(40), u.dp(40));
                ip.rightMargin = u.dp(14);
                r.addView(icon, ip);
                name = u.text("", 16, u.ink);
                name.setSingleLine();
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                mark = new View(MainActivity.this);
                mark.setBackground(u.round(u.near, 5));
                r.addView(mark, new LinearLayout.LayoutParams(u.dp(10), u.dp(10)));
                Motion.press(r, 0.98f);
                r.setTag(new Object[]{icon, name, mark});
            } else {
                r = (LinearLayout) convert;
            }
            Object[] h = (Object[]) r.getTag();
            icon = (android.widget.ImageView) h[0]; name = (TextView) h[1]; mark = (View) h[2];
            String[] a = shown.get(i);
            name.setText(a[0]);
            boolean sel = cur.equals(Actions.OPEN_APP + a[1]);
            mark.setVisibility(sel ? View.VISIBLE : View.GONE);
            r.setContentDescription(a[0] + (sel ? ", selected" : ""));
            r.setOnClickListener(v -> pick(i));
            String pkg = a[1];
            final android.widget.ImageView iconView = icon;
            icon.setTag(pkg);
            android.graphics.drawable.Drawable d = icons.get(pkg);
            if (d != null) { icon.setImageDrawable(d); icon.setAlpha(1f); }
            else {
                icon.setImageDrawable(u.round(u.dotOff, 12));
                if (!loader.isShutdown()) loader.execute(() -> {
                    android.graphics.drawable.Drawable got;
                    try { got = getPackageManager().getApplicationIcon(pkg); } catch (Exception e) { return; }
                    ui.post(() -> {
                        icons.put(pkg, got);
                        if (pkg.equals(iconView.getTag())) {
                            iconView.setImageDrawable(got);
                            if (Motion.enabled()) { iconView.setAlpha(0f); iconView.animate().alpha(1f).setDuration(160).start(); }
                        }
                    });
                });
            }
            return r;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        enter = 2;
        if (code == 1 && hasMic() && !prefs.contains(Prefs.SOURCE)) probe();
        else home();
    }

    /** Find a source with two separate mics; CAMCORDER is what was validated on the S26. */
    void probe() {
        if (probing) return;
        probing = true;
        home();
        new Thread(() -> {
            Recorder.Probe pick = null;
            List<Recorder.Probe> all = new ArrayList<>();
            for (int i = 0; i < Recorder.SOURCES.length; i++) all.add(Recorder.probe(i));
            for (Recorder.Probe p : all) if (p.distinct && p.source == MediaRecorder.AudioSource.CAMCORDER) pick = p;
            if (pick == null) for (Recorder.Probe p : all) if (p.distinct && p.mics.contains(";")) { pick = p; break; }
            if (pick == null) for (Recorder.Probe p : all) if (p.distinct) { pick = p; break; }
            boolean stereo = pick != null;
            int source = stereo ? pick.source : MediaRecorder.AudioSource.MIC;
            prefs.edit().putInt(Prefs.SOURCE, source).putBoolean(Prefs.STEREO, stereo).apply();
            ui.post(() -> { probing = false; if (!inTeach) home(); });
        }).start();
    }

    // ---------------- teach ----------------

    void startTeach(boolean more) {
        teachMore = more;
        if (!hasMic()) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return; }
        if (KnockService.running == null) KnockService.start(this);
        inTeach = true;
        teachStep = 0;
        spotA = spotB = null;
        enter = 1;
        teachScreen();
    }

    void cancelTeach() {
        KnockService s = KnockService.running;
        if (s != null) s.endTeach();
        inTeach = false;
        enter = -1;
        home();
    }

    /** Top bar for teach screens: Cancel on the left, step on the right. */
    void teachBar(LinearLayout col, String step) {
        LinearLayout bar = u.row();
        bar.setMinimumHeight(u.dp(52));
        TextView cancel = u.link("‹ Cancel", u.muted, v -> cancelTeach());
        bar.addView(cancel);
        bar.addView(u.spacer());
        bar.addView(u.monoText(step, 11, u.faint));
        col.addView(bar);
    }

    void teachScreen() {
        LinearLayout col = u.page(this);
        feed = null; progress = null; sig = null; headlineView = null;
        teachInto(col);
        settle(col);
    }

    void teachInto(LinearLayout col) {
        KnockService s = KnockService.running;
        teachOrient = s != null ? s.orientation() : currentOrient();
        boolean stereo = prefs.getBoolean(Prefs.STEREO, true);
        boolean portrait = teachOrient.equals("portrait");
        int total = stereo ? 2 : 1;

        if (teachStep == 0) {
            teachBar(col, "teach · " + teachOrient);
            col.addView(u.headline2(teachMore ? "Teach more" : "Teach Knock", teachMore ? "on " + Prefs.activeName(prefs) + "." : "your desk.", 40), u.wide(18));
            col.addView(u.text(stereo
                    ? "Put the phone in its holder exactly where it will stay. You'll knock 8 times on two spots so Knock learns how each one sounds."
                    : "Your phone gives apps only one microphone, so Knock can't tell spots apart. You'll teach one spot and get two commands: knock twice and knock 3 times.",
                    15, u.muted), u.wide(12));
            LinearLayout spots = u.card();
            spots.setPadding(u.dp(18), u.dp(6), u.dp(18), u.dp(6));
            if (stereo) {
                spots.addView(spotRow(0, "Spot 1 · " + (portrait ? "near" : "left"), portrait ? "Right in front of the holder, about 10 cm" : "About 25 cm to the left of the phone"));
                spots.addView(u.divider());
                spots.addView(spotRow(1, "Spot 2 · " + (portrait ? "far" : "right"), portrait ? "Further in front, 30–40 cm away" : "About 25 cm to the right"));
            } else {
                spots.addView(spotRow(0, "One spot", "Wherever you'll knock"));
            }
            col.addView(spots, u.wide(22));
            col.addView(u.label("Tips"), u.wide(24));
            col.addView(u.text("Knock with a knuckle, about once a second — the way you'll knock later. Keep the room fairly quiet.", 14, u.muted), u.wide(6));
            col.addView(u.spacer());
            teachNext = u.button("Start", true);
            teachNext.setOnClickListener(v -> beginSpot(1));
            col.addView(teachNext, u.wide(28, 54));
            return;
        }

        if (teachStep == 1 || teachStep == 2) {
            int spot = teachStep - 1;
            int color = u.spotColor(spot);
            teachBar(col, "spot " + teachStep + " of " + total);
            String where = !stereo ? "on the desk."
                    : spot == 0 ? (portrait ? "near the phone." : "left of the phone.")
                    : (portrait ? "far from the phone." : "right of the phone.");
            col.addView(u.headline2("Knock 8 times", where, 40), u.wide(18));
            teachHint = u.text("One knock about every second. Each one buzzes.", 15, u.muted);
            col.addView(teachHint, u.wide(12));
            col.addView(liveCard(color, stereo ? (spot == 0 ? "● " : "○ ") + (portrait ? (spot == 0 ? "near" : "far") : (spot == 0 ? "left" : "right")) : null), u.wide(22));
            progress = u.new Progress(TEACH_COUNT, color);
            progress.set(0);
            col.addView(progress, u.wide(22));
            col.addView(u.spacer());
            teachNext = u.button("Next", true);
            teachNext.setEnabled(false);
            teachNext.setAlpha(0.35f);
            teachNext.setOnClickListener(v -> finishSpot());
            col.addView(teachNext, u.wide(28, 54));
            Button redo = u.button("Start this spot again", false);
            redo.setOnClickListener(v -> beginSpot(teachStep));
            col.addView(redo, u.wide(10, 52));
            if (teachStep == 2) {
                TextView one = u.link("Use one spot only (2 commands)", u.muted, v -> {
                    KnockService ks = KnockService.running; if (ks != null) ks.endTeach();
                    spotB = null; teachStep = 3; enter = 1; teachScreen();
                });
                one.setGravity(Gravity.CENTER);
                col.addView(one, u.wide(6));
            }
            return;
        }

        // result
        Model fresh = Model.teach(spotA, spotB);
        Model merged = teachMore ? Model.teachMore(model(teachOrient), spotA, spotB) : null;
        Model m = merged != null ? merged : fresh;
        boolean two = m.spots == 2;
        String verdict, detail;
        int color;
        boolean ok = true;
        if (!two) { verdict = "Ready."; detail = "Knock twice or 3 times on that spot."; color = u.good; }
        else if (m.quality >= 3) { verdict = "Clearly different."; detail = "Knock can tell your two spots apart."; color = u.good; }
        else if (m.quality >= 2) { verdict = "Good enough."; detail = "It will work. Spots further apart would make it more reliable."; color = u.ink; }
        else {
            verdict = "Too alike.";
            detail = "From where the phone sits, the two spots sound almost the same, so Knock could mix them up. Try spots further apart"
                    + (portrait ? " (one right by the holder, one far away)" : " (well to each side)") + ", rotate the phone, or use one spot.";
            color = u.bad;
            ok = false;
        }
        teachBar(col, "result");
        boolean good = ok;
        col.postDelayed(() -> { if (good) Motion.confirm(col); else Motion.reject(col); }, 260);
        TextView vt = u.headline(verdict, 44);
        vt.setTextColor(color);
        col.addView(vt, u.wide(18));
        col.addView(u.text(detail, 15, u.muted), u.wide(10));

        if (two) {
            LinearLayout card = u.card();
            card.addView(u.new Strip(lags(m.taught.get(0)), lags(m.taught.get(1))));
            LinearLayout cap = u.row();
            cap.addView(u.monoText(portrait ? "● near" : "● left", 11, u.near));
            cap.addView(u.spacer());
            cap.addView(u.monoText(String.format(Locale.US, "gap %.1f×", m.quality), 11, u.muted));
            cap.addView(u.spacer());
            cap.addView(u.monoText(portrait ? "far ○" : "right ○", 11, u.far));
            card.addView(cap, u.wide(12));
            card.setContentDescription(String.format(Locale.US, "Your two spots are %.1f times further apart than they vary.", m.quality));
            col.addView(card, u.wide(22));
        }
        col.addView(u.monoText((merged != null ? Prefs.activeName(prefs) + " · taught " + m.sessions + "× · " + m.knockCount() + " knocks"
                : Prefs.activeName(prefs) + " · " + teachOrient + " · " + m.knockCount() + " knocks").toLowerCase(Locale.US), 11, u.faint), u.wide(12));

        if (merged != null && fresh.spots == 2 && fresh.quality >= 3 && merged.quality < 2) {
            // the old teaching no longer fits (phone moved?): offer the new knocks alone
            col.addView(u.text("Only today's knocks tell the spots apart well — the phone may have moved since you first taught it.", 14, u.ink), u.wide(18));
        }
        col.addView(u.spacer());
        if (ok) {
            Button save = u.button("Save and start", true);
            save.setOnClickListener(v -> {
                KnockService ks = KnockService.running;
                if (ks != null) ks.saveModel(teachOrient, m);
                else prefs.edit().putString(Prefs.modelKey(Prefs.active(prefs), teachOrient), m.serialize()).apply();
                if (ks == null) KnockService.start(this);
                inTeach = false;
                Motion.confirm(v);
                enter = -1;
                home();
            });
            col.addView(save, u.wide(28, 54));
        }
        if (merged != null && fresh.spots == 2 && fresh.quality >= 3 && merged.quality < 2) {
            Button only = u.button("Replace with today's knocks", !ok);
            only.setOnClickListener(v -> { teachMore = false; enter = 1; teachScreen(); });
            col.addView(only, u.wide(ok ? 10 : 28, 52));
        }
        Button again = u.button("Teach again", !ok);
        again.setOnClickListener(v -> { teachStep = 0; enter = -1; teachScreen(); });
        col.addView(again, u.wide(10, 52));
        if (!ok) {
            TextView one = u.link("Use spot 1 only (2 commands)", u.muted, v -> { spotB = null; teachStep = 3; enter = 1; teachScreen(); });
            one.setGravity(Gravity.CENTER);
            col.addView(one, u.wide(6));
        }
    }

    static int[] lags(List<Knock> ks) {
        int[] out = new int[ks.size()];
        for (int i = 0; i < out.length; i++) out[i] = ks.get(i).lag;
        return out;
    }

    View spotRow(int spot, String title, String sub) {
        LinearLayout r = u.row();
        r.setPadding(0, u.dp(14), 0, u.dp(14));
        View g = u.glyph(spot, 1, 12);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.rightMargin = u.dp(16);
        r.addView(g, gp);
        LinearLayout t = u.column();
        t.addView(u.medium(title, 15, u.ink));
        t.addView(u.text(sub, 13, u.muted), u.wide(3));
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    void beginSpot(int step) {
        KnockService s = KnockService.running;
        if (s == null) { KnockService.start(this); ui.postDelayed(() -> beginSpot(step), 400); return; }
        if (teachStep != step || step == 1) enter = 1;
        teachStep = step;
        teachScreen();
        s.beginTeach();
    }

    void finishSpot() {
        KnockService s = KnockService.running;
        List<Knock> ks = s == null ? new ArrayList<>() : s.endTeach();
        if (ks.size() > TEACH_COUNT) ks = new ArrayList<>(ks.subList(ks.size() - TEACH_COUNT, ks.size()));
        if (teachStep == 1) {
            spotA = ks;
            if (prefs.getBoolean(Prefs.STEREO, true)) beginSpot(2);
            else { spotB = null; teachStep = 3; enter = 1; teachScreen(); }
        } else {
            spotB = ks;
            teachStep = 3;
            enter = 1;
            teachScreen();
        }
    }

    // ---------------- service callbacks ----------------

    @Override public void onState() { if (!inTeach && !powerPending) home(); }

    @Override
    public void onTeachKnock(int count) {
        if (!inTeach || progress == null) return;
        progress.set(count);
        if (count >= TEACH_COUNT) {
            teachHint.setText(count > TEACH_COUNT ? "Got it — the last " + TEACH_COUNT + " knocks are used." : "Got it.");
            teachHint.setTextColor(u.good);
            if (!teachNext.isEnabled()) {
                teachNext.setEnabled(true);
                Motion.bump(teachNext);
                Motion.confirm(teachNext);
                Motion.refresh(teachHint);
            }
            teachNext.setAlpha(1f);
        }
    }

    @Override public void onEvent(KnockService.Event e) { if (!inTeach) renderFeed(); }
}
