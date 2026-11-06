package com.littyl.knock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.littyl.knock.core.Detector;
import com.littyl.knock.core.Grouper;
import com.littyl.knock.core.Knock;
import com.littyl.knock.core.Model;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Owns the microphone while Knock is on: runs the detector, and either collects knocks for Teach
 * or turns them into commands and performs the mapped actions.
 */
public class KnockService extends Service implements SensorEventListener {
    static final String CHANNEL = "listening";
    static final String STOP = "com.littyl.knock.STOP";
    static final String CAPTURE_STOP = "com.littyl.knock.CAPTURE_STOP";
    static final int CAPTURE_SECONDS = 180;
    static final double TEACH_ACCEL = 12;   // looser gate while teaching: only you are knocking

    /** UI hooks; everything is called on the main thread. */
    interface Listener {
        void onState();
        void onTeachKnock(int count);
        void onEvent(Event e);
    }

    /** One line of the activity log. spot/count are -1 for notes. */
    static final class Event {
        final long time; final int spot, count; final boolean command; final String what, result;
        Event(int spot, int count, boolean command, String what, String result) {
            this.time = System.currentTimeMillis(); this.spot = spot; this.count = count;
            this.command = command; this.what = what; this.result = result;
        }
        boolean ok() { return command && "done".equals(result); }
    }


    static volatile KnockService running;
    static Listener listener;
    static final List<Event> recent = new ArrayList<>();

    final Handler main = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    AudioManager audio;
    SensorManager sensors;
    HandlerThread sensorThread;
    Thread audioThread;
    volatile boolean alive;
    Detector detector;
    Grouper grouper;
    Model portrait, landscape;
    String micStatus = "";

    // Teach mode: knocks collected for the spot being taught
    volatile boolean teaching;
    final List<Knock> teachKnocks = new ArrayList<>();
    double lastTeachTime = -1;

    // Test recording: what the service hears (audio, accel, every onset), for tuning on real use
    volatile boolean wantCapture;
    final Object capLock = new Object();
    java.io.RandomAccessFile capWav;
    java.io.BufferedWriter capAccel, capKnocks;
    File capBase;
    long capFrames, capAccelCount, capTsFrame = -1, capTsNanos = -1, capStartAbs;
    String capOrient;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, KnockService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, KnockService.class));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CAPTURE_STOP.equals(intent.getAction())) {
            wantCapture = false;
            return START_STICKY;
        }
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (alive) return START_STICKY;
        prefs = Prefs.get(this);
        audio = getSystemService(AudioManager.class);
        startForeground(1, notification(listeningText()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        reloadModels();
        alive = true;
        running = this;
        detector = new Detector(this::onKnock);
        grouper = new Grouper(new Grouper.Sink() {
            @Override public void onCommand(int spot, int count, List<Knock> ks) { command(spot, count, ks); }
            @Override public void onRejected(int count, String why) { event(new Event(-1, count, false, "Ignored", count == 1 ? "one knock" : why)); }
        });
        sensors = getSystemService(SensorManager.class);
        sensorThread = new HandlerThread("knock-accel");
        sensorThread.start();
        Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_FASTEST, new Handler(sensorThread.getLooper()));
        audioThread = new Thread(this::audioLoop, "knock-audio");
        audioThread.start();
        notifyState();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = false;
        running = null;
        if (sensors != null) sensors.unregisterListener(this);
        if (sensorThread != null) sensorThread.quitSafely();
        if (audioThread != null) try { audioThread.join(1500); } catch (InterruptedException ignored) { }
        notifyState();
        super.onDestroy();
    }

    void reloadModels() {
        String id = Prefs.active(prefs);
        portrait = Model.parse(prefs.getString(Prefs.modelKey(id, "portrait"), ""));
        landscape = Model.parse(prefs.getString(Prefs.modelKey(id, "landscape"), ""));
    }

    String orientation() {
        if (detector == null) return "portrait";
        float[] g = detector.gravity();
        return Math.abs(g[0]) > Math.abs(g[1]) ? "landscape" : "portrait";
    }

    Model currentModel() { return orientation().equals("landscape") ? landscape : portrait; }

    private void audioLoop() {
        int source = Prefs.source(prefs);
        AudioRecord r = Recorder.open(source);
        if (r == null) {
            micStatus = "Microphone unavailable";
            main.post(this::stopSelf);
            return;
        }
        try {
            r.startRecording();
            short[] buf = new short[960];
            AudioTimestamp ts = new AudioTimestamp();
            long lastTs = -1;
            byte[] bytes = new byte[buf.length * 2];
            while (alive) {
                int n = r.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (wantCapture && capWav == null) openCapture(r);
                if (capWav != null) {
                    if (!wantCapture || capFrames >= (long) CAPTURE_SECONDS * Recorder.RATE) closeCapture(r);
                    else {
                        for (int i = 0; i < n; i++) { bytes[2 * i] = (byte) buf[i]; bytes[2 * i + 1] = (byte) (buf[i] >> 8); }
                        try { capWav.write(bytes, 0, n * 2); } catch (IOException e) { wantCapture = false; }
                        if (capTsFrame < 0 && r.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                            capTsFrame = ts.framePosition - capStartAbs; capTsNanos = ts.nanoTime;   // relative to the WAV
                        }
                        capFrames += n / 2;
                    }
                }
                long f = detector.frames();
                if (f - lastTs > Recorder.RATE && r.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    detector.setTimestamp(ts.framePosition, ts.nanoTime);
                    lastTs = f;
                }
                detector.audio(buf, n);
                grouper.tick((double) detector.frames() / Recorder.RATE);
            }
            if (capWav != null) closeCapture(r);
            r.stop();
        } catch (IllegalStateException ignored) {
        } finally {
            r.release();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        detector.accel(e.timestamp, e.values[0], e.values[1], e.values[2]);
        if (capAccel != null) synchronized (capLock) {
            if (capAccel != null) try {
                capAccel.write(e.timestamp + "," + e.values[0] + "," + e.values[1] + "," + e.values[2] + "\n");
                capAccelCount++;
            } catch (IOException ignored) { }
        }
    }

    // ---------- test recording ----------

    void startCapture() { wantCapture = true; }

    boolean capturing() { return capWav != null || wantCapture; }

    /** Audio thread. Same file layout as RecorderActivity sessions, labelled "live". */
    private void openCapture(AudioRecord r) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dir = new File(getExternalFilesDir(null), "sessions");
        dir.mkdirs();
        capOrient = orientation();
        capBase = new File(dir, stamp + "_" + capOrient + "_live_" + sourceName(Prefs.source(prefs)));
        try {
            java.io.RandomAccessFile w = new java.io.RandomAccessFile(capBase.getPath() + ".wav", "rw");
            w.setLength(0);
            w.write(new byte[44]);
            synchronized (capLock) {
                capAccel = new java.io.BufferedWriter(new FileWriter(capBase.getPath() + ".accel.csv"));
                capAccel.write("t_boot_ns,x,y,z\n");
                capAccelCount = 0;
            }
            capKnocks = new java.io.BufferedWriter(new FileWriter(capBase.getPath() + ".knocks.csv"));
            capKnocks.write("t,label,lag,accel,loud,like,media\n");
            capFrames = 0;
            capTsFrame = -1;
            capStartAbs = detector.frames();
            capWav = w;
        } catch (IOException e) {
            wantCapture = false;
            return;
        }
        main.post(() -> {
            getSystemService(NotificationManager.class).notify(1, notification("Recording a test — " + CAPTURE_SECONDS / 60 + " min"));
            notifyState();
        });
    }

    private void closeCapture(AudioRecord r) {
        wantCapture = false;
        long endF = -1, endN = -1;
        AudioTimestamp ts = new AudioTimestamp();
        if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) { endF = ts.framePosition - capStartAbs; endN = ts.nanoTime; }
        try {
            long data = capFrames * 4;
            capWav.seek(0);
            java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt((int) (36 + data)).put("WAVE".getBytes())
                    .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2)
                    .putInt(Recorder.RATE).putInt(Recorder.RATE * 4).putShort((short) 4).putShort((short) 16)
                    .put("data".getBytes()).putInt((int) data);
            capWav.write(h.array());
            capWav.close();
        } catch (IOException ignored) { }
        capWav = null;
        long accelN;
        synchronized (capLock) {
            try { capAccel.close(); } catch (IOException ignored) { }
            capAccel = null;
            accelN = capAccelCount;
        }
        try { capKnocks.close(); } catch (IOException ignored) { }
        capKnocks = null;
        RecorderActivity.writeText(new File(capBase.getPath() + ".json"), String.format(Locale.US,
                "{\"label\":\"live\",\"orientation\":\"%s\",\"source\":\"%s\",\"rate\":%d,\"frames\":%d,"
                        + "\"knocks_live\":0,\"accel_samples\":%d,\"ts_start\":[%d,%d],\"ts_end\":[%d,%d],"
                        + "\"mics\":\"\",\"model\":\"%s\"}",
                capOrient, sourceName(Prefs.source(prefs)), Recorder.RATE, capFrames, accelN,
                capTsFrame, capTsNanos, endF, endN, android.os.Build.MODEL));
        event(new Event(-1, 0, false, "Test recording saved", ""));
        main.post(() -> {
            getSystemService(NotificationManager.class).notify(1, notification(listeningText()));
            notifyState();
        });
    }

    static String sourceName(int source) {
        for (int i = 0; i < Recorder.SOURCES.length; i++)
            if (Recorder.SOURCES[i] == source) return Recorder.SOURCE_NAMES[i].toLowerCase(Locale.US);
        return "src" + source;
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    // ---------- knocks ----------

    private void onKnock(Knock k) {
        if (teaching) {
            if (k.accelRatio < TEACH_ACCEL) return;
            if (lastTeachTime >= 0 && k.timeSec - lastTeachTime < 0.3) return;   // only single knocks while teaching
            lastTeachTime = k.timeSec;
            int count;
            synchronized (teachKnocks) { teachKnocks.add(k); count = teachKnocks.size(); }
            teachKnockSeen = true;
            vibrate(VibrationEffect.EFFECT_TICK);
            main.post(() -> { if (listener != null) listener.onTeachKnock(count); });
            return;
        }
        Model m = currentModel();
        if (m == null) return;
        k.media = audio.isMusicActive();
        int label = m.classify(k);
        if (capKnocks != null) try {
            capKnocks.write(String.format(Locale.US, "%.4f,%d,%d,%.1f,%.1f,%.2f,%b\n", k.timeSec, label, k.lag,
                    k.accelRatio, k.loud, m.like(k), k.media));
        } catch (IOException ignored) { }
        if (label != Model.NOT_KNOCK) grouper.add(k, label);
    }

    /** Teach screen's live dots. */
    double takePeak() { Detector d = detector; return d == null ? 0 : d.takePeak(); }
    double thresholdRatio() { Detector d = detector; return d == null ? 5 : d.thresholdRatio(); }
    /** True once after each knock heard while teaching. */
    boolean takeTeachKnock() { boolean k = teachKnockSeen; teachKnockSeen = false; return k; }
    private volatile boolean teachKnockSeen;

    int teachCount() { synchronized (teachKnocks) { return teachKnocks.size(); } }

    void beginTeach() {
        synchronized (teachKnocks) { teachKnocks.clear(); }
        lastTeachTime = -1;
        teaching = true;
    }

    List<Knock> endTeach() {
        teaching = false;
        synchronized (teachKnocks) { return new ArrayList<>(teachKnocks); }
    }

    void saveModel(String orient, Model m) {
        prefs.edit().putString(Prefs.modelKey(Prefs.active(prefs), orient), m.serialize()).apply();
        reloadModels();
    }

    // ---------- commands ----------

    private void command(int spot, int count, List<Knock> ks) {
        // Called on the audio thread: adapt the model here (it is only read on this thread),
        // then run the action on the main thread.
        String orient = orientation();
        Model m = currentModel();
        if (m != null) {
            m.adapt(spot, ks);
            prefs.edit().putString(Prefs.modelKey(Prefs.active(prefs), orient), m.serialize()).apply();
        }
        main.post(() -> perform(orient, spot, count, ks));
    }

    private void perform(String orient, int spot, int count, List<Knock> ks) {
        String slot = Prefs.slot(spot, count);
        String action = Prefs.action(prefs, Prefs.active(prefs), slot);
        String label = Prefs.commandName(orient, spot, count);
        AudioManager am = getSystemService(AudioManager.class);
        int mode = am.getMode();
        boolean inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE;
        boolean practice = prefs.getBoolean(Prefs.PRACTICE, false);
        String result;
        if (inCall) result = "skipped during a call";
        else if (practice) result = "practice mode, not run";
        else {
            String err = Actions.run(this, action);
            result = err == null ? "done" : err;
        }
        vibrate(count == 2 ? VibrationEffect.EFFECT_DOUBLE_CLICK : VibrationEffect.EFFECT_HEAVY_CLICK);
        StringBuilder lags = new StringBuilder();
        for (Knock k : ks) lags.append(lags.length() == 0 ? "" : " ").append(k.lag);
        logCsv(orient, slot, action, result, lags.toString(), ks);
        event(new Event(spot, count, true, Actions.name(this, action), result));
    }

    private void vibrate(int effect) {
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null && prefs.getBoolean(Prefs.HAPTICS, true)) v.vibrate(VibrationEffect.createPredefined(effect));
    }

    private void event(Event e) {
        if (!e.command) logCsv(orientation(), "-", "-", e.what + (e.result.isEmpty() ? "" : " — " + e.result), "", null);
        main.post(() -> {
            synchronized (recent) {
                recent.add(0, e);
                while (recent.size() > 40) recent.remove(recent.size() - 1);
            }
            if (listener != null) listener.onEvent(e);
        });
    }

    /** Phase-2 log: every command and every rejected group, for counting real false triggers. */
    private void logCsv(String orient, String slot, String action, String result, String lags, List<Knock> ks) {
        File f = new File(getExternalFilesDir(null), "commands.csv");
        boolean header = !f.exists();
        try (FileWriter w = new FileWriter(f, true)) {
            if (header) w.write("time,orientation,slot,action,result,lags,accel,loud\n");
            StringBuilder ac = new StringBuilder(), ld = new StringBuilder();
            if (ks != null) for (Knock k : ks) {
                ac.append(ac.length() == 0 ? "" : " ").append(Math.round(k.accelRatio));
                ld.append(ld.length() == 0 ? "" : " ").append(Math.round(k.loud));
            }
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "," + orient + "," + slot + ","
                    + action + ",\"" + result.replace("\"", "'") + "\"," + lags + "," + ac + "," + ld + "\n");
        } catch (IOException ignored) { }
    }

    // ---------- notification ----------

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, KnockService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = new Notification.Builder(this, CHANNEL);
        if (capturing()) nb.addAction(new Notification.Action.Builder(null, "Stop recording",
                PendingIntent.getService(this, 2, new Intent(this, KnockService.class).setAction(CAPTURE_STOP), PendingIntent.FLAG_IMMUTABLE)).build());
        return nb
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFF5B5BF6)
                .setContentTitle(text)
                .setContentText(capturing() ? "Recording a test" : "Knock twice or three times on the desk")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
                .build();
    }

    String listeningText() { return "Listening · " + Prefs.activeName(prefs); }

    /** Switch tables: reload the models and update the notification. */
    void profileChanged() {
        reloadModels();
        getSystemService(NotificationManager.class).notify(1, notification(listeningText()));
    }

    private void notifyState() {
        main.post(() -> { if (listener != null) listener.onState(); });
    }
}
