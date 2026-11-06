package com.littyl.knock;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Phase-0 data collection: pick a spot, record ~20 knocks, repeat; files are pulled over adb. */
public class RecorderActivity extends Activity implements SensorEventListener {
    // colours come from the app's light/dark tokens
    int BASE, SURFACE, ACCENT, TEXT, MUTED, REC;
    Ui u;

    static final String[] LABELS = {"left", "right", "near", "far", "desk-life"};
    static final String[] LABEL_TITLES = {"Left", "Right", "Near", "Far", "Desk life"};
    static final String[] HINTS = {
            "Knock ~20 times with a knuckle, about a second apart, on the desk 25 cm to the LEFT of the phone.",
            "Knock ~20 times, about a second apart, 25 cm to the RIGHT of the phone.",
            "Knock ~20 times, about a second apart, right in FRONT of the holder (~10 cm).",
            "Knock ~20 times, about a second apart, FAR in front (~40 cm, or as far as you'd reach).",
            "Don't knock. Just use the desk normally for a few minutes: type, mouse, put a mug down, talk.",
    };

    final Handler ui = new Handler(Looper.getMainLooper());
    SensorManager sensors;
    Sensor accel;
    float gx, gy, gz;
    BufferedWriter accelOut;
    long accelCount;

    Recorder.Probe best;
    String probeReport = "";
    LinearLayout sourceRow;
    final java.util.List<Button> sourceBtns = new java.util.ArrayList<>();
    Recorder recorder;
    File sessionBase;
    String sessionOrient;
    long recStartMs;
    int label = 0;

    TextView status, orient, hint, timer, log;
    Button recBtn;
    Button[] labelBtns = new Button[LABELS.length];
    LevelBar levelL, levelR;
    final StringBuilder knockLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        u = new Ui(this);
        u.window(this);
        BASE = u.bg; SURFACE = u.dotOff; ACCENT = u.near; TEXT = u.ink; MUTED = u.muted; REC = u.bad;
        sensors = getSystemService(SensorManager.class);
        accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        build();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        else runProbe();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) runProbe();
        else status.setText("Microphone permission is needed.");
    }

    @Override protected void onResume() { super.onResume(); sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST); }

    @Override protected void onPause() {
        super.onPause();
        if (recorder != null) stopRecording();
        sensors.unregisterListener(this);
    }

    void runProbe() {
        status.setText("Checking which mic setup gives real stereo…");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            Recorder.Probe pick = null;
            java.util.List<Recorder.Probe> usable = new java.util.ArrayList<>();
            for (int i = 0; i < Recorder.SOURCES.length; i++) {
                Recorder.Probe p = Recorder.probe(i);
                sb.append(p.summary()).append('\n');
                // Prefer a source that says it's using two physical mics; on the S26 UNPROCESSED
                // lists only the bottom mic even though its channels differ.
                if (p.distinct && p.mics.contains(";") && (pick == null || !pick.mics.contains(";"))) pick = p;
                else if (pick == null && p.distinct) pick = p;
                if (p.distinct) usable.add(p);
            }
            Recorder.Probe chosen = pick;
            String report = sb.toString().trim();
            writeText(new File(dir(), "probe.txt"), report);
            ui.post(() -> {
                best = chosen;
                if (chosen == null) {
                    status.setText("No source gave two separate mics. Recording would be mono, test can't work.\n\n" + report);
                    status.setTextColor(REC);
                } else {
                    probeReport = report;
                    sourceRow.removeAllViews();
                    sourceBtns.clear();
                    for (Recorder.Probe p : usable) {
                        Button sb2 = chipButton(p.name.toLowerCase(Locale.US));
                        sb2.setOnClickListener(v -> selectSource(p));
                        sourceBtns.add(sb2);
                        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, u.dp(40), 1f);
                        lp2.setMargins(sourceRow.getChildCount() == 0 ? 0 : u.dp(6), 0, 0, 0);
                        sourceRow.addView(sb2, lp2);
                    }
                    selectSource(chosen);
                    recBtn.setEnabled(true);
                    recBtn.setAlpha(1f);
                }
            });
        }).start();
    }

    void selectSource(Recorder.Probe p) {
        if (recorder != null) return;
        best = p;
        for (Button b : sourceBtns) {
            boolean on = b.getText().toString().equalsIgnoreCase(p.name);
            chip(b, on);
        }
        status.setText("Mic mode " + p.name + " — two separate mics ✓\n\n" + probeReport);
    }

    File dir() {
        File d = new File(getExternalFilesDir(null), "sessions");
        d.mkdirs();
        return d;
    }

    String orientation() {
        // Phone standing in a holder: gravity along the long axis = portrait, along the short axis = landscape.
        return Math.abs(gx) > Math.abs(gy) ? "landscape" : "portrait";
    }

    void toggle() {
        if (recorder == null) startRecording(); else stopRecording();
    }

    void startRecording() {
        if (best == null) return;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        sessionOrient = orientation();
        sessionBase = new File(dir(), stamp + "_" + sessionOrient + "_" + LABELS[label] + "_" + best.name.toLowerCase(Locale.US));
        knockLog.setLength(0);
        log.setText("");
        try {
            accelOut = new BufferedWriter(new FileWriter(sessionBase.getPath() + ".accel.csv"));
            accelOut.write("t_boot_ns,x,y,z\n");
        } catch (IOException e) { accelOut = null; }
        accelCount = 0;
        recorder = new Recorder(best.source, new File(sessionBase.getPath() + ".wav"), new Recorder.Listener() {
            @Override public void onKnock(int i, int lag, double db, double peak) {
                ui.post(() -> {
                    String side = lag > 0 ? "mic A first" : lag < 0 ? "mic B first" : "same time";
                    knockLog.insert(0, String.format(Locale.US, "#%-3d lag %+3d  (%s)   level %+.1f dB\n", i, lag, side, db));
                    log.setText(knockLog);
                });
            }
            @Override public void onLevel(double l, double r) {
                ui.post(() -> { levelL.set(l); levelR.set(r); });
            }
        });
        if (!recorder.start()) {
            recorder = null;
            status.setText("Could not open the mic.");
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recStartMs = SystemClock.elapsedRealtime();
        recBtn.setText("Stop");
        recBtn.setBackground(u.pressable(u.round(REC, 26)));
        recBtn.setTextColor(0xFFFFFFFF);
        Motion.toggle(recBtn, true);
        for (Button lb : labelBtns) lb.setEnabled(false);
        for (Button sb2 : sourceBtns) sb2.setEnabled(false);
        tick();
    }

    void stopRecording() {
        Recorder r = recorder;
        recorder = null;
        r.stop();
        synchronized (this) {
            try { if (accelOut != null) accelOut.close(); } catch (IOException ignored) { }
            accelOut = null;
        }
        writeText(new File(sessionBase.getPath() + ".json"), String.format(Locale.US,
                "{\"label\":\"%s\",\"orientation\":\"%s\",\"source\":\"%s\",\"rate\":%d,\"frames\":%d,"
                        + "\"knocks_live\":%d,\"accel_samples\":%d,\"ts_start\":[%d,%d],\"ts_end\":[%d,%d],"
                        + "\"mics\":\"%s\",\"model\":\"%s\"}",
                LABELS[label], sessionOrient, best.name, Recorder.RATE, r.frames, r.knocks, accelCount,
                r.startFrame, r.startNanos, r.endFrame, r.endNanos,
                r.activeMics.replace("\"", "'"), android.os.Build.MODEL));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recBtn.setText("Record");
        recBtn.setBackground(u.pressable(u.round(u.ink, 26)));
        recBtn.setTextColor(u.onInk);
        Motion.confirm(recBtn);
        for (Button lb : labelBtns) lb.setEnabled(true);
        for (Button sb2 : sourceBtns) sb2.setEnabled(true);
        timer.setText(String.format(Locale.US, "Saved · %d knocks heard · %d sessions total", r.knocks, countSessions()));
        levelL.set(0); levelR.set(0);
    }

    int countSessions() {
        String[] f = dir().list((d, n) -> n.endsWith(".wav"));
        return f == null ? 0 : f.length;
    }

    void tick() {
        if (recorder == null) return;
        long s = (SystemClock.elapsedRealtime() - recStartMs) / 1000;
        timer.setText(String.format(Locale.US, "Recording %s · %s · %d:%02d · %d knocks",
                LABEL_TITLES[label], sessionOrient, s / 60, s % 60, recorder.knocks));
        ui.postDelayed(this::tick, 250);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        gx = gx * 0.9f + e.values[0] * 0.1f;
        gy = gy * 0.9f + e.values[1] * 0.1f;
        gz = gz * 0.9f + e.values[2] * 0.1f;
        synchronized (this) {
            if (accelOut != null) {
                try {
                    accelOut.write(e.timestamp + "," + e.values[0] + "," + e.values[1] + "," + e.values[2] + "\n");
                    accelCount++;
                } catch (IOException ignored) { }
            }
        }
        if (recorder == null && (accelCount++ & 15) == 0) orient.setText("Phone is " + orientation());
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    static void writeText(File f, String s) {
        try (FileWriter w = new FileWriter(f)) { w.write(s); } catch (IOException ignored) { }
    }

    // ---------- UI ----------

    void build() {
        LinearLayout col = u.page(this);
        LinearLayout bar = u.row();
        bar.setMinimumHeight(u.dp(52));
        bar.addView(u.link("‹ Back", u.muted, v -> finish()));
        bar.addView(u.spacer());
        bar.addView(u.monoText("developer", 11, u.faint));
        col.addView(bar);
        col.addView(u.headline2("Raw data", "for other phones.", 40), u.wide(18));
        col.addView(u.text("Records audio, motion and labelled knocks into files on this phone, to tune Knock for other devices. Files are pulled over USB; nothing is uploaded.", 14, u.muted), u.wide(10));

        status = u.monoText("", 11, u.muted);
        LinearLayout st = u.card();
        st.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(14));
        st.addView(status);
        col.addView(st, u.wide(20));

        col.addView(u.label("Mic mode"), u.wide(22));
        sourceRow = u.row();
        col.addView(sourceRow, u.wide(8));

        col.addView(u.label("Spot"), u.wide(22));
        LinearLayout row = u.row();
        for (int i = 0; i < LABELS.length; i++) {
            final int idx = i;
            Button lb = chipButton(LABEL_TITLES[i]);
            lb.setOnClickListener(v -> selectLabel(idx));
            labelBtns[i] = lb;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, u.dp(40), 1f);
            p.setMargins(i == 0 ? 0 : u.dp(6), 0, 0, 0);
            row.addView(lb, p);
        }
        col.addView(row, u.wide(8));

        orient = u.monoText("phone is …", 11, u.faint);
        col.addView(orient, u.wide(14));
        hint = u.text("", 15, u.ink);
        col.addView(hint, u.wide(8));

        recBtn = u.button("Record", true);
        recBtn.setEnabled(false);
        recBtn.setAlpha(0.35f);
        recBtn.setOnClickListener(v -> toggle());
        col.addView(recBtn, u.wide(22, 54));

        timer = u.monoText("", 11, u.muted);
        col.addView(timer, u.wide(12));

        levelL = new LevelBar("L");
        levelR = new LevelBar("R");
        col.addView(levelL, u.wide(12, 10));
        col.addView(levelR, u.wide(6, 10));

        log = u.monoText("", 11, u.ink);
        col.addView(log, u.wide(14));

        selectLabel(0);
        Motion.reveal(col, 1);
    }

    Button chipButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Ui.sansMedium);
        b.setPadding(0, 0, 0, 0);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        Motion.press(b, 0.95f);
        return b;
    }

    /** Selected chips are ink, the rest outlined. */
    void chip(Button b, boolean on) {
        b.setBackground(u.pressable(on ? u.round(u.ink, 20) : u.outlined(u.surface, u.line, 20)));
        b.setTextColor(on ? u.onInk : u.muted);
    }

    void selectLabel(int i) {
        label = i;
        for (int k = 0; k < labelBtns.length; k++)
            chip(labelBtns[k], k == i);
        hint.setText(HINTS[i]);
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Peak meter for one channel. */
    final class LevelBar extends View {
        final String name;
        double v;
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        LevelBar(String name) { super(RecorderActivity.this); this.name = name; }

        void set(double nv) { v = Math.max(nv, v * 0.85); invalidate(); }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            paint.setColor(SURFACE);
            c.drawRoundRect(0, 0, w, h, r, r, paint);
            double dbv = v <= 0 ? -60 : 20 * Math.log10(v);
            float f = (float) Math.max(0, Math.min(1, (dbv + 60) / 60));
            if (f <= 0) return;
            paint.setColor(f > 0.92f ? REC : ACCENT);
            c.drawRoundRect(0, 0, Math.max(h, w * f), h, r, r, paint);
        }
    }
}
