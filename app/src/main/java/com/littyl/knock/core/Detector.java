package com.littyl.knock.core;

import java.util.Arrays;

/**
 * Streaming knock detector. Feed interleaved stereo PCM and accelerometer samples; it finds sharp low-band
 * onsets, waits until 50 ms of audio after each is buffered, and measures it into a {@link Knock}.
 * Mirrors analysis/analyze.py (onsets, features, accel_feats) so offline numbers carry over.
 */
public final class Detector {
    public interface Sink { void onKnock(Knock k); }

    static final int RATE = Dsp.RATE;
    static final int RING = RATE * 2;                 // 2 s of stereo history
    static final int FRAME = RATE / 1000;             // 1 ms envelope frames
    static final int REFRACTORY_MS = 120;
    static final int POST = Dsp.LAG_HALF + RATE / 20; // audio needed after an onset before measuring

    private final Sink sink;
    private final short[] ring = new short[RING * 2];
    private long frames;                              // total frames received

    // Onsets are found in the 100–1500 Hz band: a desk knock is 17–35 dB above a reel playing on the
    // phone's own speaker there, while full-band clicks from the speaker are as sharp as any knock.
    private final Biquad[] hpf = {Biquad.highpass(100), Biquad.highpass(100)};
    private final Biquad[] lpf = {Biquad.lowpass(1500), Biquad.lowpass(1500)};
    static final double LOW_MULT = 5, LOW_MIN = 3000;

    // low-band envelope + adaptive floor (median of the last 10 s of 1 ms frames)
    private double frameMax;
    private int frameFill;
    private final double[] envHist = new double[10000];
    private int envCount;
    private double floor = 50;
    private long lastOnsetFrame = Long.MIN_VALUE / 2;
    private long pendingFrameIdx = -1;               // envelope-frame index of a crossing awaiting refinement
    private final long[] queue = new long[64];       // refined onsets waiting for POST audio
    private int qHead, qTail;

    // accelerometer ring, timestamps in boot-time ns
    private final long[] at = new long[8192];
    private final float[][] axyz = new float[8192][3];
    private int aCount;
    private final double[] blockStd = new double[1200];
    private int blockCount;
    private double accelNoise = 0.01;

    // audio frame → boot ns
    private long tsFrame = -1, tsNanos;

    /** Below this envelope level nothing counts (raw PCM units). Teach mode may lower it. */
    public double minThreshold = LOW_MIN;

    public Detector(Sink sink) { this.sink = sink; }

    /** RBJ-cookbook biquad, Q = 1/√2 (Butterworth). */
    static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double x1, x2, y1, y2;

        Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0; this.a1 = a1 / a0; this.a2 = a2 / a0;
        }

        static Biquad lowpass(double f) {
            double w = 2 * Math.PI * f / RATE, al = Math.sin(w) / Math.sqrt(2), c = Math.cos(w);
            return new Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
        }

        static Biquad highpass(double f) {
            double w = 2 * Math.PI * f / RATE, al = Math.sin(w) / Math.sqrt(2), c = Math.cos(w);
            return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
        }

        double step(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }
    }

    public void setTimestamp(long frame, long bootNanos) { tsFrame = frame; tsNanos = bootNanos; }

    long frameToNanos(long f) {
        if (tsFrame < 0) return Long.MIN_VALUE;
        return tsNanos + (f - tsFrame) * 1_000_000_000L / RATE;
    }

    public synchronized void accel(long tNanos, float x, float y, float z) {
        int i = aCount % at.length;
        at[i] = tNanos;
        axyz[i][0] = x; axyz[i][1] = y; axyz[i][2] = z;
        aCount++;
        if (aCount % 50 == 0 && aCount >= 50) {
            double s = 0;
            for (int c = 0; c < 3; c++) {
                double m = 0, v = 0;
                for (int k = aCount - 50; k < aCount; k++) m += axyz[k % at.length][c];
                m /= 50;
                for (int k = aCount - 50; k < aCount; k++) { double d = axyz[k % at.length][c] - m; v += d * d; }
                s += Math.sqrt(v / 50);
            }
            blockStd[blockCount % blockStd.length] = s / 3;
            blockCount++;
            if (blockCount % 20 == 0) {
                int n = Math.min(blockCount, blockStd.length);
                double[] c = Arrays.copyOf(blockStd, n);
                Arrays.sort(c);
                accelNoise = c[n / 2] + 1e-4;
            }
        }
    }

    public long frames() { return frames; }

    // for the teach screen's live dots: loudest low-band frame since the last call, over the floor
    private volatile double peak;
    public double takePeak() { double p = peak; peak = 0; return p / Math.max(floor, 1); }
    /** The onset threshold as a multiple of the floor. */
    public double thresholdRatio() { return Math.max(floor * LOW_MULT, minThreshold) / Math.max(floor, 1); }


    /** n = number of shorts (2 per frame). */
    public void audio(short[] buf, int n) {
        for (int i = 0; i + 1 < n; i += 2) {
            int l = buf[i], r = buf[i + 1];
            int ri = (int) (frames % RING) * 2;
            ring[ri] = (short) l; ring[ri + 1] = (short) r;
            double bl = Math.abs(lpf[0].step(hpf[0].step(l))), br = Math.abs(lpf[1].step(hpf[1].step(r)));
            frames++;
            double low = Math.max(bl, br);
            if (low > frameMax) frameMax = low;
            if (++frameFill == FRAME) endFrame();
        }
        while (qHead != qTail && frames >= queue[qHead] + POST) {
            measure(queue[qHead]);
            qHead = (qHead + 1) % queue.length;
        }
    }

    private void endFrame() {
        long idx = frames / FRAME - 1;
        double env = frameMax;
        frameMax = 0; frameFill = 0;
        if (env > peak) peak = env;
        envHist[envCount % envHist.length] = env;
        envCount++;
        if (envCount % 1000 == 0) {
            int n = Math.min(envCount, envHist.length);
            double[] c = Arrays.copyOf(envHist, n);
            Arrays.sort(c);
            floor = c[n / 2];
        }
        double thr = Math.max(floor * LOW_MULT, minThreshold);
        if (pendingFrameIdx >= 0 && idx >= pendingFrameIdx + 1) {
            refine(pendingFrameIdx);
            pendingFrameIdx = -1;
        }
        if (pendingFrameIdx < 0 && env > thr && idx - lastOnsetFrame >= REFRACTORY_MS) {
            lastOnsetFrame = idx;
            pendingFrameIdx = idx;
        }
    }

    /** First sample in frames [i−4, i+1) above 30% of that span's full-band peak (analyze.py onsets;
     *  the low-band crossing comes ~1–2 ms after the full-band one). */
    private void refine(long i) {
        long start = Math.max(0, (i - 4) * FRAME), end = (i + 1) * FRAME;
        double peak = 0;
        int[] hp = new int[(int) (end - start)];
        for (long f = start; f < end; f++) {
            int v = hpAt(f);
            hp[(int) (f - start)] = v;
            if (v > peak) peak = v;
        }
        int j = 0;
        while (j < hp.length && hp[j] <= 0.3 * peak) j++;
        long onset = start + Math.min(j, hp.length - 1);
        int next = (qTail + 1) % queue.length;
        if (next != qHead) { queue[qTail] = onset; qTail = next; }
    }

    private int hpAt(long f) {
        if (f <= 0 || frames - f >= RING - 1) return 0;
        int a = (int) (f % RING) * 2, b = (int) ((f - 1) % RING) * 2;
        return Math.max(Math.abs(ring[a] - ring[b]), Math.abs(ring[a + 1] - ring[b + 1]));
    }

    private double[] channel(long from, int len, int ch) {
        double[] out = new double[len];
        for (int i = 0; i < len; i++) {
            long f = from + i;
            out[i] = (f < 0 || frames - f > RING) ? 0 : ring[(int) (f % RING) * 2 + ch];
        }
        return out;
    }

    private void measure(long s) {
        if (s - Dsp.LAG_HALF < 0 || frames - (s - Dsp.LAG_HALF) > RING) return;
        Knock k = new Knock();
        k.frame = s;
        k.timeSec = (double) s / RATE;
        int body = RATE * 30 / 1000;
        double[] bl = channel(s, body, 0), br = channel(s, body, 1);
        double el = 1, er = 1;
        for (int i = 0; i < body; i++) { el += bl[i] * bl[i]; er += br[i] * br[i]; }
        k.lvl = 10 * Math.log10(el / er);
        k.loud = 10 * Math.log10(el + er);
        double[] dl = Dsp.bandDb(bl), dr = Dsp.bandDb(br);
        k.shape = new double[7];
        k.bdiff = new double[7];
        double mx = -Double.MAX_VALUE;
        for (int b = 0; b < 7; b++) { k.shape[b] = (dl[b] + dr[b]) / 2; mx = Math.max(mx, k.shape[b]); }
        for (int b = 0; b < 7; b++) { k.shape[b] -= mx; k.bdiff[b] = dl[b] - dr[b]; }
        k.lag = Dsp.lowBandLag(channel(s - Dsp.LAG_HALF, 2 * Dsp.LAG_HALF, 0), channel(s - Dsp.LAG_HALF, 2 * Dsp.LAG_HALF, 1));
        accelFeatures(k, frameToNanos(s));
        sink.onKnock(k);
    }

    private synchronized void accelFeatures(Knock k, long tNs) {
        k.accelDir = new double[3];
        int n = Math.min(aCount, at.length);
        if (n < 60 || tNs == Long.MIN_VALUE) { k.accelRatio = 0; return; }
        int first = aCount - n;
        // searchsorted
        int lo = first, hi = aCount;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (at[mid % at.length] < tNs) lo = mid + 1; else hi = mid; }
        int i = lo;
        int ps = Math.max(first, i - 40), pe = Math.max(first + 1, i - 5);
        double[] base = new double[3];
        for (int c = 0; c < 3; c++) {
            int len = Math.max(0, Math.min(pe, aCount) - ps);
            if (len == 0) { base[c] = axyz[Math.min(i, aCount - 1) % at.length][c]; continue; }
            double[] v = new double[len];
            for (int q = 0; q < len; q++) v[q] = axyz[(ps + q) % at.length][c];
            Arrays.sort(v);
            base[c] = len % 2 == 1 ? v[len / 2] : (v[len / 2 - 1] + v[len / 2]) / 2;
        }
        double best = -1;
        for (int q = Math.max(first, i - 10); q < Math.min(aCount, i + 25); q++) {
            float[] p = axyz[q % at.length];
            double x = p[0] - base[0], y = p[1] - base[1], z = p[2] - base[2];
            double m = Math.sqrt(x * x + y * y + z * z);
            if (m > best) { best = m; k.accelDir[0] = x; k.accelDir[1] = y; k.accelDir[2] = z; }
        }
        if (best <= 0) { k.accelRatio = 0; return; }
        for (int c = 0; c < 3; c++) k.accelDir[c] /= best + 1e-9;
        k.accelRatio = best / accelNoise;
    }

    /** The gravity vector right now (mean of the last ~0.2 s), for portrait/landscape. */
    public synchronized float[] gravity() {
        float[] g = new float[3];
        int n = Math.min(aCount, 100);
        for (int q = aCount - n; q < aCount; q++) for (int c = 0; c < 3; c++) g[c] += axyz[q % at.length][c] / n;
        return g;
    }
}
