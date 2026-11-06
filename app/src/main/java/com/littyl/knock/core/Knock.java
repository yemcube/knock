package com.littyl.knock.core;

/** One detected knock and everything measured about it. */
public final class Knock {
    public long frame;          // audio frame of the onset
    public double timeSec;      // frame / RATE
    public int lag;             // 100–1500 Hz lag between the mics (samples)
    public double loud;         // dB (arbitrary reference), both channels, 30 ms
    public double lvl;          // L/R level difference, dB
    public double[] shape;      // 7 band levels of the mid signal relative to its loudest band
    public double[] bdiff;      // 7 per-band L−R level differences
    public double accelRatio;   // accelerometer peak / resting noise
    public double[] accelDir;   // unit vector of that peak
    public boolean media;       // the phone itself was playing sound (set by the caller)

    /** Rebuild a taught knock from what a profile stores (its vector, lag and levels). */
    public static Knock fromVector(double[] v, int lag, double accelRatio, double loud) {
        Knock k = new Knock();
        k.shape = java.util.Arrays.copyOfRange(v, 0, 7);
        k.bdiff = java.util.Arrays.copyOfRange(v, 7, 14);
        k.accelDir = new double[]{v[14] / 3, v[15] / 3, v[16] / 3};
        k.lvl = v[17];
        k.lag = lag;
        k.accelRatio = accelRatio;
        k.loud = loud;
        return k;
    }

    /** Features for the "is this the knock you taught" check (matches analysis/teach.py fv). */
    public double[] vector() {
        double[] v = new double[18];
        System.arraycopy(shape, 0, v, 0, 7);
        System.arraycopy(bdiff, 0, v, 7, 7);
        for (int i = 0; i < 3; i++) v[14 + i] = Math.abs(accelDir[i]) * 3;
        v[17] = lvl;
        return v;
    }
}
