package com.littyl.knock.core;

/** Small numeric helpers: radix-2 FFT, band-limited lag between the two mics, band energies. */
public final class Dsp {
    private Dsp() { }

    public static final int RATE = 48000;
    static final int LAG_HALF = 2400;           // 50 ms either side of the onset for the band-pass
    static final int LAG_LEN = 720;             // 15 ms of direct sound after the onset
    static final int MAX_LAG = 40;
    static final int[] BANDS = {60, 250, 600, 1200, 2500, 5000, 10000, 20000};

    /** In-place complex FFT; n must be a power of two. inverse=true divides by n. */
    static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
        if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    /** Brick-wall band-pass of a real signal (zero outside [lo, hi] Hz), same length out. */
    static double[] bandpass(double[] x, double lo, double hi) {
        int n = Integer.highestOneBit(x.length - 1) << 1;
        double[] re = new double[n], im = new double[n];
        System.arraycopy(x, 0, re, 0, x.length);
        fft(re, im, false);
        for (int k = 0; k <= n / 2; k++) {
            double f = (double) k * RATE / n;
            if (f < lo || f > hi) {
                re[k] = im[k] = 0;
                if (k > 0 && k < n / 2) { re[n - k] = im[n - k] = 0; }
            }
        }
        fft(re, im, true);
        double[] out = new double[x.length];
        System.arraycopy(re, 0, out, 0, x.length);
        return out;
    }

    /**
     * Lag (samples) between the mics in the 100–1500 Hz band over the 15 ms after the onset.
     * l and r are 2*LAG_HALF samples centred on the onset. Matches analysis/simulate.py low_lag.
     */
    static int lowBandLag(double[] l, double[] r) {
        double[] fl = bandpass(l, 100, 1500), fr = bandpass(r, 100, 1500);
        int n = LAG_LEN, m = MAX_LAG;
        double best = -Double.MAX_VALUE;
        int bestK = 0;
        for (int k = -m; k <= m; k++) {
            double s = 0;
            for (int i = m; i < n - m; i++) {
                int j = i - k;           // np.roll(r, k)[i] == r[i - k] (wrapping inside the window)
                if (j < 0) j += n; else if (j >= n) j -= n;
                s += fl[LAG_HALF + i] * fr[LAG_HALF + j];
            }
            if (s > best) { best = s; bestK = k; }
        }
        return bestK;
    }

    /** Hann-windowed band energies in dB (BANDS edges). */
    static double[] bandDb(double[] x) {
        int len = x.length;
        int n = Integer.highestOneBit(len - 1) << 1;
        // numpy rfft(x * hanning(len)) uses n = len; we zero-pad to a power of two and scale bins
        // back to len-point frequencies, which keeps band sums comparable.
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < len; i++) re[i] = x[i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (len - 1)));
        fft(re, im, false);
        double[] out = new double[BANDS.length - 1];
        double scale = (double) len / n;
        for (int k = 0; k <= n / 2; k++) {
            double f = (double) k * RATE / n;
            double p = (re[k] * re[k] + im[k] * im[k]) * scale;
            for (int b = 0; b < out.length; b++) if (f >= BANDS[b] && f < BANDS[b + 1]) { out[b] += p; break; }
        }
        for (int b = 0; b < out.length; b++) out[b] = 10 * Math.log10(out[b] + 1);
        return out;
    }
}
