package com.littyl.knock.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What Teach learns: how strong your knocks are, what they sound like, and where each spot's
 * mic lag falls. Ported from analysis/teach.py (N=8, like×2, pad×2), which on recorded data gave
 * 0–1 wrong commands and 0 desk-life false triggers when taught on one mounting and used on others.
 */
public final class Model {
    public static final int SPOT_A = 0, SPOT_B = 1, UNSURE = 2, NOT_KNOCK = 3;
    static final double[] SD_FLOOR = {1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 0.08, 0.08, 0.08, 0.5};
    static final double LIKE_MULT = 2.0, PAD_MULT = 2.0, MIN_PAD = 4;
    static final int MAX_ADAPT = 24;
    /** Teaching again adds to what a profile knows; keep the newest this many knocks per spot. */
    public static final int MAX_TAUGHT = 40;
    /** While the phone plays sound its speaker fills the upper bands and skews the level difference, so
     *  the knock check uses only what a knock dominates: band shape and L−R below 1.2 kHz, and accel direction. */
    static final int[] MEDIA_DIMS = {0, 1, 2, 7, 8, 9, 14, 15, 16};
    /** Reels at normal volume shook the phone up to 23× resting noise; knocks under them were 38×+. */
    static final double MEDIA_ACCEL_MIN = 30;
    static final int[] ALL_DIMS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};

    public int spots;                  // 1 or 2
    public double accelMin, loudMin, likeMax, likeMaxMedia;
    public double[] mu, sd;
    public double[][] z;               // taught knocks, standardized
    public double[][] window = new double[2][2];  // per spot [lo, hi] lag window
    public double quality;             // separation of the two spots (median gap / summed spread)
    public double[] center = new double[2];
    final List<List<Integer>> lags = new ArrayList<>();   // taught + adapted lags per spot
    public final List<List<Knock>> taught = new ArrayList<>();   // the knocks it was taught, per spot
    public int sessions = 1;          // how many times this was taught

    /** Build from knocks at spot A (and B, or null for single-spot mode). */
    public static Model teach(List<Knock> a, List<Knock> b) {
        Model m = new Model();
        m.spots = b == null || b.isEmpty() ? 1 : 2;
        List<Knock> all = new ArrayList<>(a);
        if (m.spots == 2) all.addAll(b);
        int n = all.size(), d = 18;
        double[][] x = new double[n][];
        for (int i = 0; i < n; i++) x[i] = all.get(i).vector();
        m.mu = new double[d];
        m.sd = new double[d];
        for (double[] v : x) for (int j = 0; j < d; j++) m.mu[j] += v[j] / n;
        for (double[] v : x) for (int j = 0; j < d; j++) m.sd[j] += (v[j] - m.mu[j]) * (v[j] - m.mu[j]) / n;
        for (int j = 0; j < d; j++) m.sd[j] = Math.max(Math.sqrt(m.sd[j]), SD_FLOOR[j]);
        m.z = new double[n][];
        for (int i = 0; i < n; i++) m.z[i] = m.standardize(x[i]);
        m.likeMax = m.looLike(ALL_DIMS);
        m.likeMaxMedia = m.looLike(MEDIA_DIMS);
        double amin = Double.MAX_VALUE, lmin = Double.MAX_VALUE;
        for (Knock k : all) { amin = Math.min(amin, k.accelRatio); lmin = Math.min(lmin, k.loud); }
        m.accelMin = amin * 0.5;
        m.loudMin = lmin - 6;
        m.lags.add(lagsOf(a));
        m.lags.add(m.spots == 2 ? lagsOf(b) : new ArrayList<>());
        m.taught.add(new ArrayList<>(a));
        m.taught.add(m.spots == 2 ? new ArrayList<>(b) : new ArrayList<>());
        m.fitWindows();
        return m;
    }

    /** Teach again on top of an existing model: its taught knocks plus the new ones. Null if the
     *  spot count differs (one spot vs two), which can't be merged. */
    public static Model teachMore(Model old, List<Knock> a, List<Knock> b) {
        int spots = b == null || b.isEmpty() ? 1 : 2;
        if (old == null || old.spots != spots) return null;
        Model m = teach(newest(old.taught.get(0), a), spots == 2 ? newest(old.taught.get(1), b) : null);
        m.sessions = old.sessions + 1;
        return m;
    }

    static List<Knock> newest(List<Knock> old, List<Knock> add) {
        List<Knock> all = new ArrayList<>(old);
        all.addAll(add);
        return all.size() > MAX_TAUGHT ? new ArrayList<>(all.subList(all.size() - MAX_TAUGHT, all.size())) : all;
    }

    public int knockCount() { return taught.get(0).size() + taught.get(1).size(); }

    static List<Integer> lagsOf(List<Knock> ks) {
        List<Integer> out = new ArrayList<>();
        for (Knock k : ks) out.add(k.lag);
        return out;
    }

    void fitWindows() {
        if (spots == 1) {
            window[0][0] = -1e9; window[0][1] = 1e9;   // one spot: lag isn't used
            center[0] = median(lags.get(0));
            quality = Double.POSITIVE_INFINITY;
            return;
        }
        int hiSpot = median(lags.get(0)) >= median(lags.get(1)) ? 0 : 1, loSpot = 1 - hiSpot;
        double[] h = stats(lags.get(hiSpot)), l = stats(lags.get(loSpot)); // min, max, std, median
        double pa = Math.max(MIN_PAD, PAD_MULT * h[2]), pb = Math.max(MIN_PAD, PAD_MULT * l[2]);
        double[] hw = {h[0] - pa, h[1] + 2 * pa}, lw = {l[0] - 2 * pb, l[1] + pb};
        if (hw[0] - lw[1] < 4) {
            double mid = (h[3] + l[3]) / 2;
            hw[0] = mid + 2; lw[1] = mid - 2;
        }
        window[hiSpot] = hw;
        window[loSpot] = lw;
        center[hiSpot] = h[3];
        center[loSpot] = l[3];
        quality = (h[3] - l[3]) / (h[2] + l[2] + 1e-9);
    }

    static double median(List<Integer> v) {
        if (v.isEmpty()) return 0;
        int[] a = new int[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2.0;
    }

    static double[] stats(List<Integer> v) {
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE, s = 0, s2 = 0;
        for (int x : v) { mn = Math.min(mn, x); mx = Math.max(mx, x); s += x; s2 += (double) x * x; }
        double mean = s / v.size();
        return new double[]{mn, mx, Math.sqrt(Math.max(0, s2 / v.size() - mean * mean)), median(v)};
    }

    double[] standardize(double[] v) {
        double[] o = new double[v.length];
        for (int j = 0; j < v.length; j++) o[j] = (v[j] - mu[j]) / sd[j];
        return o;
    }

    /** Like threshold: LIKE_MULT × the worst leave-one-out 3rd-nearest distance among taught knocks. */
    double looLike(int[] dims) {
        double worst = 0;
        for (int i = 0; i < z.length; i++) worst = Math.max(worst, thirdNearest(z[i], i, dims));
        return worst * LIKE_MULT;
    }

    double thirdNearest(double[] q, int skip) { return thirdNearest(q, skip, ALL_DIMS); }

    double thirdNearest(double[] q, int skip, int[] dims) {
        double a = Double.MAX_VALUE, b = Double.MAX_VALUE, c = Double.MAX_VALUE;
        for (int i = 0; i < z.length; i++) {
            if (i == skip) continue;
            double s = 0;
            for (int j : dims) { double d = z[i][j] - q[j]; s += d * d; }
            if (s < a) { c = b; b = a; a = s; } else if (s < b) { c = b; b = s; } else if (s < c) c = s;
        }
        return Math.sqrt(c);
    }

    /** Distance to the 3rd-nearest taught knock (knock-likeness; ≤ likeMax passes). */
    public double like(Knock k) { return thirdNearest(standardize(k.vector()), -1, k.media ? MEDIA_DIMS : ALL_DIMS); }

    /** Where (if anywhere) this sound was a knock. */
    public int classify(Knock k) {
        if (k.accelRatio < (k.media ? Math.max(accelMin, MEDIA_ACCEL_MIN) : accelMin) || k.loud < loudMin) return NOT_KNOCK;
        if (like(k) > (k.media ? likeMaxMedia : likeMax)) return UNSURE;
        for (int s = 0; s < spots; s++) if (k.lag >= window[s][0] && k.lag <= window[s][1]) return s;
        return UNSURE;
    }

    /** A confidently recognised command: let the lag windows follow slow drift of the mounting. */
    public void adapt(int spot, List<Knock> ks) {
        if (spots < 2) return;
        List<Integer> l = lags.get(spot);
        for (Knock k : ks) {
            l.add(k.lag);
            // keep the taught lags forever, recycle the adapted ones
            int keep = taught.get(spot).size();
            if (l.size() > keep + MAX_ADAPT) l.remove(keep);
        }
        fitWindows();
    }

    public String describe() {
        if (spots == 1) return "One spot";
        return String.format(Locale.US, "Spot A lag %.0f, spot B lag %.0f · separation %.1f", center[0], center[1], quality);
    }

    // ---- persistence: one line per field, numbers comma-separated ----

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("v2\n").append(spots).append('\n')
          .append(accelMin).append(',').append(loudMin).append(',').append(likeMax).append('\n')
          .append(join(mu)).append('\n').append(join(sd)).append('\n')
          .append(z.length).append('\n');
        for (double[] r : z) sb.append(join(r)).append('\n');
        for (int s = 0; s < 2; s++) {
            StringBuilder q = new StringBuilder();
            for (int x : lags.get(s)) { if (q.length() > 0) q.append(','); q.append(x); }
            sb.append(q).append('\n');
        }
        sb.append(sessions).append('\n');
        for (int s = 0; s < 2; s++)
            for (Knock k : taught.get(s))
                sb.append(s).append(',').append(k.lag).append(',').append(k.accelRatio).append(',').append(k.loud)
                  .append(',').append(join(k.vector())).append('\n');
        return sb.toString();
    }

    public static Model parse(String s) {
        try {
            String[] ln = s.split("\n", -1);
            boolean v2 = ln[0].equals("v2");
            if (!v2 && !ln[0].equals("v1")) return null;
            Model m = new Model();
            m.spots = Integer.parseInt(ln[1]);
            double[] g = nums(ln[2]);
            m.accelMin = g[0]; m.loudMin = g[1]; m.likeMax = g[2];
            m.mu = nums(ln[3]); m.sd = nums(ln[4]);
            int n = Integer.parseInt(ln[5]);
            m.z = new double[n][];
            for (int i = 0; i < n; i++) m.z[i] = nums(ln[6 + i]);
            m.likeMaxMedia = m.looLike(MEDIA_DIMS);
            for (int sp = 0; sp < 2; sp++) {
                List<Integer> l = new ArrayList<>();
                for (double v : nums(ln[6 + n + sp])) l.add((int) v);
                m.lags.add(l);
            }
            m.taught.add(new ArrayList<>());
            m.taught.add(new ArrayList<>());
            if (v2) {
                int at = 8 + n;
                m.sessions = Integer.parseInt(ln[at++]);
                for (; at < ln.length && !ln[at].isEmpty(); at++) {
                    double[] r = nums(ln[at]);
                    m.taught.get((int) r[0]).add(Knock.fromVector(Arrays.copyOfRange(r, 4, 22), (int) r[1], r[2], r[3]));
                }
            } else {
                // v1 kept no raw knocks: rebuild them from the standardized ones (8 per spot, A first)
                for (int i = 0; i < n; i++) {
                    int sp = i < 8 ? 0 : 1;
                    double[] v = new double[m.mu.length];
                    for (int j = 0; j < v.length; j++) v[j] = m.z[i][j] * m.sd[j] + m.mu[j];
                    m.taught.get(sp).add(Knock.fromVector(v, m.lags.get(sp).get(i % 8), m.accelMin * 2, m.loudMin + 6));
                }
            }
            m.fitWindows();
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String join(double[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.toString();
    }

    static double[] nums(String s) {
        if (s.isEmpty()) return new double[0];
        String[] p = s.split(",");
        double[] v = new double[p.length];
        for (int i = 0; i < p.length; i++) v[i] = Double.parseDouble(p[i]);
        return v;
    }
}
