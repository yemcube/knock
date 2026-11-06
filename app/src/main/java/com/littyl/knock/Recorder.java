package com.littyl.knock;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.media.MicrophoneInfo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * Two-channel capture for the phase-0 knock test: probes which audio source really gives two
 * different mics, records 48 kHz stereo to WAV, and runs a crude live onset + lag detector so
 * the person knocking can see the mics disagreeing.
 */
final class Recorder {
    static final int RATE = 48000;
    static final int MAX_LAG = 48; // ±1 ms; 15 cm of air is ~21 samples, leave room for the desk path

    interface Listener {
        void onKnock(int index, int lag, double levelDb, double peak);
        void onLevel(double left, double right);
    }

    static final class Probe {
        final int source;
        final String name;
        final boolean ok;
        final boolean distinct;
        final double corr, rmsL, rmsR;
        final String mics;

        Probe(int source, String name, boolean ok, boolean distinct, double corr, double rmsL, double rmsR, String mics) {
            this.source = source; this.name = name; this.ok = ok; this.distinct = distinct;
            this.corr = corr; this.rmsL = rmsL; this.rmsR = rmsR; this.mics = mics;
        }

        String summary() {
            if (!ok) return name + ": unavailable";
            return String.format("%s: %s  corr %.3f  rms %.0f / %.0f%s", name,
                    distinct ? "2 mics" : "COPY (mono)", corr, rmsL, rmsR, mics.isEmpty() ? "" : "\n    " + mics);
        }
    }

    static final int[] SOURCES = {
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
    };
    static final String[] SOURCE_NAMES = {"UNPROCESSED", "CAMCORDER", "MIC", "VOICE_RECOGNITION"};

    static AudioRecord open(int source) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        try {
            AudioRecord r = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 4 / 5));
            if (r.getState() != AudioRecord.STATE_INITIALIZED || r.getChannelCount() != 2) { r.release(); return null; }
            return r;
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Record ~0.8 s from one source and check whether L and R are really different signals. */
    static Probe probe(int idx) {
        int source = SOURCES[idx];
        AudioRecord r = open(source);
        if (r == null) return new Probe(source, SOURCE_NAMES[idx], false, false, 0, 0, 0, "");
        short[] buf = new short[RATE * 2];
        int got = 0;
        String mics = "";
        try {
            r.startRecording();
            long end = System.currentTimeMillis() + 900;
            while (got < buf.length && System.currentTimeMillis() < end) {
                int n = r.read(buf, got, Math.min(4096, buf.length - got));
                if (n <= 0) break;
                got += n;
            }
            mics = describe(r);
            r.stop();
        } catch (Exception e) {
            // fall through with what we have
        } finally {
            r.release();
        }
        // Skip the first 100 ms: some HALs ramp in.
        int start = Math.min(got, RATE / 10 * 2) & ~1;
        double sl = 0, sr = 0, slr = 0;
        long identical = 0, frames = 0;
        for (int i = start; i + 1 < got; i += 2) {
            double l = buf[i], rr = buf[i + 1];
            sl += l * l; sr += rr * rr; slr += l * rr;
            if (buf[i] == buf[i + 1]) identical++;
            frames++;
        }
        if (frames == 0) return new Probe(source, SOURCE_NAMES[idx], false, false, 0, 0, 0, mics);
        double corr = (sl > 0 && sr > 0) ? slr / Math.sqrt(sl * sr) : 1;
        boolean distinct = identical < frames * 0.9 && corr < 0.9999 && sl > 0 && sr > 0;
        return new Probe(source, SOURCE_NAMES[idx], true, distinct, corr,
                Math.sqrt(sl / frames), Math.sqrt(sr / frames), mics);
    }

    static String describe(AudioRecord r) {
        StringBuilder sb = new StringBuilder();
        try {
            List<MicrophoneInfo> act = r.getActiveMicrophones();
            for (MicrophoneInfo m : act) {
                MicrophoneInfo.Coordinate3F p = m.getPosition();
                if (sb.length() > 0) sb.append("; ");
                sb.append(m.getDescription()).append(" loc=").append(m.getLocation());
                if (p != MicrophoneInfo.POSITION_UNKNOWN)
                    sb.append(String.format(" @(%.3f,%.3f,%.3f)", p.x, p.y, p.z));
            }
        } catch (IOException ignored) { }
        return sb.toString();
    }

    private final int source;
    private final File wav;
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    String activeMics = "";
    long startFrame = -1, startNanos = -1, endFrame = -1, endNanos = -1;
    long frames;
    int knocks;

    Recorder(int source, File wav, Listener listener) {
        this.source = source; this.wav = wav; this.listener = listener;
    }

    boolean start() {
        AudioRecord r = open(source);
        if (r == null) return false;
        running = true;
        thread = new Thread(() -> loop(r), "knock-rec");
        thread.start();
        return true;
    }

    void stop() {
        running = false;
        if (thread != null) try { thread.join(2000); } catch (InterruptedException ignored) { }
    }

    private void loop(AudioRecord r) {
        try (RandomAccessFile out = new RandomAccessFile(wav, "rw")) {
            out.setLength(0);
            out.write(new byte[44]);
            r.startRecording();
            activeMics = describe(r);
            short[] buf = new short[RATE / 50 * 2]; // 20 ms
            byte[] bytes = new byte[buf.length * 2];
            // Ring of recent audio so the detector can look a little before and after an onset.
            short[] ring = new short[RATE * 2]; // 1 s stereo
            long ringPos = 0; // frames written into ring
            double noise = 200;
            long refractoryUntil = 0, pendingAt = -1;
            AudioTimestamp ts = new AudioTimestamp();
            while (running) {
                int n = r.read(buf, 0, buf.length);
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) {
                    bytes[2 * i] = (byte) buf[i];
                    bytes[2 * i + 1] = (byte) (buf[i] >> 8);
                }
                out.write(bytes, 0, n * 2);
                if (startFrame < 0 && r.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    startFrame = ts.framePosition; startNanos = ts.nanoTime;
                }
                double pl = 0, pr = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    long f = ringPos + i / 2;
                    int ri = (int) ((f % RATE) * 2);
                    ring[ri] = buf[i]; ring[ri + 1] = buf[i + 1];
                    int a = Math.abs(buf[i]), b = Math.abs(buf[i + 1]);
                    pl = Math.max(pl, a); pr = Math.max(pr, b);
                    double m = Math.max(a, b);
                    if (pendingAt < 0 && f >= refractoryUntil && m > Math.max(1500, noise * 10)) {
                        pendingAt = f;
                        refractoryUntil = f + RATE / 12; // ~80 ms
                    }
                    noise = noise * 0.9995 + Math.min(m, noise * 4) * 0.0005;
                }
                ringPos += n / 2;
                // Analyse once 15 ms after the onset is in the ring.
                if (pendingAt >= 0 && ringPos >= pendingAt + RATE * 15 / 1000) {
                    analyse(ring, pendingAt);
                    pendingAt = -1;
                }
                listener.onLevel(pl / 32768.0, pr / 32768.0);
            }
            if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                endFrame = ts.framePosition; endNanos = ts.nanoTime;
            }
            r.stop();
            frames = ringPos;
            writeHeader(out, ringPos);
        } catch (IOException e) {
            running = false;
        } finally {
            r.release();
        }
    }

    /** Lag (samples, + means left channel hears it first) by cross-correlation over the first ~6 ms. */
    private void analyse(short[] ring, long onset) {
        int pre = RATE / 1000, len = RATE * 6 / 1000;
        int total = pre + len + 2 * MAX_LAG;
        double[] l = new double[total], r = new double[total];
        long base = onset - pre - MAX_LAG;
        double el = 0, er = 0, peak = 0;
        for (int i = 0; i < total; i++) {
            long f = base + i;
            int ri = (int) ((((f % RATE) + RATE) % RATE) * 2);
            l[i] = ring[ri]; r[i] = ring[ri + 1];
        }
        for (int i = MAX_LAG; i < MAX_LAG + pre + len; i++) {
            el += l[i] * l[i]; er += r[i] * r[i];
            peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        }
        int best = 0;
        double bestV = -Double.MAX_VALUE;
        for (int lag = -MAX_LAG; lag <= MAX_LAG; lag++) {
            double s = 0;
            for (int i = MAX_LAG; i < MAX_LAG + pre + len; i++) s += l[i] * r[i + lag];
            if (s > bestV) { bestV = s; best = lag; }
        }
        double db = 10 * Math.log10((el + 1) / (er + 1));
        knocks++;
        listener.onKnock(knocks, best, db, peak / 32768.0);
    }

    private static void writeHeader(RandomAccessFile out, long frames) throws IOException {
        long data = frames * 4;
        out.seek(0);
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt((int) (36 + data)).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2)
                .putInt(RATE).putInt(RATE * 4).putShort((short) 4).putShort((short) 16)
                .put("data".getBytes()).putInt((int) data);
        out.write(h.array());
    }
}
