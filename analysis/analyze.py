#!/usr/bin/env python3
"""Phase-0 analysis: can the S26's two mics (+ accelerometer) tell knock spots apart, and how
often does ordinary desk use look like a knock?  numpy only.

usage: analyze.py DATA_DIR
"""
import glob, json, os, sys, wave
import numpy as np

RATE = 48000
MAX_LAG = 48


def load(base):
    meta = json.load(open(base + ".json"))
    with wave.open(base + ".wav") as w:
        a = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").reshape(-1, 2).astype(np.float64)
    acc = np.loadtxt(base + ".accel.csv", delimiter=",", skiprows=1)
    return meta, a, acc


def onsets(a, refractory=0.12):
    """Onset sample indices: sharp energy jumps in the high-passed max of both channels."""
    hp = np.abs(np.diff(a, axis=0, prepend=a[:1])).max(axis=1)
    fr = RATE // 1000
    env = hp[: len(hp) // fr * fr].reshape(-1, fr).max(axis=1)
    floor = np.median(env)
    thr = max(floor * 12, 600)
    out, last = [], -1e9
    for i in np.flatnonzero(env > thr):
        if i - last < refractory * 1000:
            continue
        last = i
        seg = hp[max(0, (i - 2) * fr):(i + 2) * fr]
        j = np.argmax(seg > 0.3 * seg.max())
        out.append(max(0, (i - 2) * fr) + j)
    return np.array(out, dtype=int), thr, floor


def gcc_phat(l, r):
    n = 1 << int(np.ceil(np.log2(len(l) * 2)))
    L, R = np.fft.rfft(l, n), np.fft.rfft(r, n)
    X = L * np.conj(R)
    cc = np.fft.irfft(X / (np.abs(X) + 1e-9), n)
    cc = np.concatenate((cc[-MAX_LAG:], cc[: MAX_LAG + 1]))
    k = int(np.argmax(cc))
    # parabolic sub-sample refinement
    if 0 < k < len(cc) - 1:
        y0, y1, y2 = cc[k - 1], cc[k], cc[k + 1]
        d = 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2 + 1e-12)
    else:
        d = 0
    # + means the left channel leads
    return -(k - MAX_LAG + d), cc.max()


BANDS = [60, 250, 600, 1200, 2500, 5000, 10000, 20000]


def band_db(x):
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    return np.array([10 * np.log10(spec[(f >= lo) & (f < hi)].sum() + 1) for lo, hi in zip(BANDS, BANDS[1:])])


def accel_noise(acc):
    """Resting vibration level for a whole session: median spread over 50-sample blocks."""
    xyz = acc[:, 1:]
    n = len(xyz) // 50 * 50
    return float(np.median(xyz[:n].reshape(-1, 50, 3).std(axis=1).mean(axis=1))) + 1e-4


def accel_feats(acc, t_ns, noise):
    t, xyz = acc[:, 0], acc[:, 1:]
    i = np.searchsorted(t, t_ns)
    pre = xyz[max(0, i - 40):max(1, i - 5)]   # ~70 ms before: gravity baseline, short enough to skip a prior knock's ring
    base = np.median(pre, axis=0) if len(pre) else xyz[i:i + 1].mean(axis=0)
    win = xyz[max(0, i - 10):i + 25] - base
    if len(win) == 0:
        return 0.0, np.zeros(3)
    mag = np.linalg.norm(win, axis=1)
    k = int(np.argmax(mag))
    return mag[k] / noise, win[k] / (mag[k] + 1e-9)


def features(meta, a, acc, idx):
    f0, n0 = meta["ts_start"]
    noise = accel_noise(acc)
    rows = []
    for s in idx:
        if s - 2 * RATE // 1000 - MAX_LAG < 0 or s + RATE // 20 >= len(a):
            continue
        w = a[s - RATE // 1000: s + 4 * RATE // 1000]   # direct-path window
        lag, peakcc = gcc_phat(w[:, 0], w[:, 1])
        body = a[s: s + 30 * RATE // 1000]
        el, er = (body ** 2).sum(axis=0) + 1
        lvl = 10 * np.log10(el / er)
        e5l, e5r = (w ** 2).sum(axis=0) + 1
        lvl5 = 10 * np.log10(e5l / e5r)
        bl, br = band_db(body[:, 0]), band_db(body[:, 1])
        mid = (bl + br) / 2
        shape = mid - mid.max()
        loud = 10 * np.log10(el + er)
        t_ns = n0 + (s - f0) / RATE * 1e9
        aratio, adir = accel_feats(acc, t_ns, noise)
        rows.append(dict(s=s, lag=lag, cc=peakcc, lvl=lvl, lvl5=lvl5, shape=shape, bdiff=bl - br,
                         loud=loud, aratio=aratio, adir=adir))
    return rows


def vec(r, kind):
    v = [r["lag"]]
    if kind in ("lag+level", "all"):
        v += [r["lvl"], r["lvl5"]]
    if kind == "all":
        v += list(r["shape"]) + list(r["bdiff"]) + list(r["adir"] * 3)
    return np.array(v)


def knn_eval(X, y, k=3, train_mask=None):
    """Leave-one-out (or train/test split) kNN on standardized features; returns predictions."""
    mu, sd = X.mean(0), X.std(0) + 1e-9
    Z = (X - mu) / sd
    D = ((Z[:, None, :] - Z[None, :, :]) ** 2).sum(-1)
    pred = []
    for i in range(len(y)):
        d = D[i].copy()
        d[i] = np.inf
        if train_mask is not None:
            d[~train_mask] = np.inf
        nn = np.argsort(d)[:k]
        vals, cnt = np.unique(y[nn], return_counts=True)
        pred.append(vals[np.argmax(cnt)])
    return np.array(pred)


def main(d):
    sessions = {}
    for js in sorted(glob.glob(os.path.join(d, "*.json"))):
        base = js[:-5]
        meta, a, acc = load(base)
        idx, thr, floor = onsets(a)
        rows = features(meta, a, acc, idx)
        sessions.setdefault((meta["orientation"], meta["label"]), []).append((meta, rows, len(a) / RATE))
        print(f"{os.path.basename(base):40s} {meta['source']:10s} {len(a)/RATE:5.1f}s  onsets {len(rows):3d}"
              f"  (floor {floor:.0f}, thr {thr:.0f})")

    for orient in sorted({o for o, _ in sessions}):
        labels = [l for o, l in sessions if o == orient and l != "desk-life"]
        X, y, rows_all = [], [], []
        print(f"\n===== {orient} =====")
        for l in labels:
            rows = [r for _, rs, _ in sessions[(orient, l)] for r in rs]
            # Keep the 20 strongest events per spot session: a stray noise onset shouldn't count as a knock.
            rows = sorted(sorted(rows, key=lambda r: -r["loud"])[:25], key=lambda r: r["s"])
            lags = np.array([r["lag"] for r in rows])
            lv = np.array([r["lvl"] for r in rows])
            ar = np.array([r["aratio"] for r in rows])
            print(f"{l:6s} n={len(rows):2d}  lag {np.median(lags):+6.2f} ±{lags.std():4.2f} (min {lags.min():+.1f} max {lags.max():+.1f})"
                  f"   level {np.median(lv):+5.1f} ±{lv.std():3.1f} dB   accel x{np.median(ar):5.1f} (min {ar.min():4.1f})")
            rows_all += rows
            y += [l] * len(rows)
        y = np.array(y)
        knock_accel = np.array([r["aratio"] for r in rows_all])

        for kind in ("lag", "lag+level", "all"):
            X = np.array([vec(r, kind) for r in rows_all])
            p = knn_eval(X, y)
            # honest split: train on first half of each session, test on second half
            order = np.zeros(len(y), bool)
            for l in labels:
                ii = np.flatnonzero(y == l)
                order[ii[: len(ii) // 2]] = True
            p2 = knn_eval(X, y, train_mask=order)
            acc_split = (p2[~order] == y[~order]).mean()
            print(f"\n[{kind}] 4-spot accuracy: leave-one-out {np.mean(p == y)*100:5.1f}%   first-half→second-half {acc_split*100:5.1f}%")
            print("   confusion (rows=true):  " + " ".join(f"{l:>6s}" for l in labels))
            for l in labels:
                print(f"   {l:>6s}                  " + " ".join(f"{np.sum((y == l) & (p == m)):6d}" for m in labels))

        print("\nPairs (all features, first-half→second-half):")
        X = np.array([vec(r, "all") for r in rows_all])
        res = []
        for i, a_ in enumerate(labels):
            for b_ in labels[i + 1:]:
                m = (y == a_) | (y == b_)
                Xm, ym = X[m], y[m]
                tr = np.zeros(len(ym), bool)
                for l in (a_, b_):
                    ii = np.flatnonzero(ym == l)
                    tr[ii[: len(ii) // 2]] = True
                p2 = knn_eval(Xm, ym, train_mask=tr)
                loo = knn_eval(Xm, ym)
                res.append((np.mean(loo == ym), (p2[~tr] == ym[~tr]).mean(), a_, b_))
        for loo, sp, a_, b_ in sorted(res, reverse=True):
            print(f"   {a_:>6s} vs {b_:<6s}  loo {loo*100:5.1f}%   split {sp*100:5.1f}%")

        # ---- false triggers in desk life ----
        if (orient, "desk-life") in sessions:
            for meta, rows, dur in sessions[(orient, "desk-life")]:
                gate = np.percentile(knock_accel, 2) * 0.7
                loud_gate = np.percentile([r["loud"] for r in rows_all], 2) - 6
                ev = [r for r in rows if r["aratio"] >= gate and r["loud"] >= loud_gate]
                t = np.array([r["s"] / RATE for r in ev])
                ar = np.array([r["aratio"] for r in rows])
                print(f"\nDesk life {dur:.0f}s: {len(rows)} sound onsets; accel ratios median x{np.median(ar) if len(ar) else 0:.1f}"
                      f"; {len(ev)} pass accel gate (≥x{gate:.1f}) + loudness gate")
                # doubles: 2 gated events 0.12–0.6 s apart; triples: 3 within 1.2 s
                dbl = sum(1 for i in range(len(t) - 1) if 0.12 <= t[i + 1] - t[i] <= 0.6)
                tri = sum(1 for i in range(len(t) - 2) if t[i + 2] - t[i] <= 1.2 and t[i + 1] - t[i] >= 0.12 and t[i + 2] - t[i + 1] >= 0.12)
                print(f"   would-be double knocks: {dbl}   triple knocks: {tri}"
                      f"   → {dbl / dur * 3600:.0f}/hr doubles if nothing else filtered")
                for r in ev[:30]:
                    print(f"   t={r['s']/RATE:6.2f}s lag {r['lag']:+5.1f} lvl {r['lvl']:+5.1f} loud {r['loud']:5.1f} accel x{r['aratio']:.1f}")


if __name__ == "__main__":
    main(sys.argv[1])
