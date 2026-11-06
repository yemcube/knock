#!/usr/bin/env python3
"""Run the planned detector end to end over every session: gate → low-band lag → spot → double/triple
command. Reports commands per spot session and false commands in desk life.  usage: simulate.py [--landscape]"""
import sys, glob, numpy as np
sys.path.insert(0, 'analysis'); import analyze as A
R = A.RATE
ACCEL_MIN, LOUD_MIN = 25, 100
NEAR_AT, FAR_BELOW = 22, 14          # lag ≥ 22 near, ≤ 14 far, between = unsure
GAP_MIN, GAP_MAX = 0.12, 0.6         # knocks in one command

def bp(x, lo=100, hi=1500):
    X = np.fft.rfft(x, axis=0); f = np.fft.rfftfreq(len(x), 1 / R); X[(f < lo) | (f > hi)] = 0
    return np.fft.irfft(X, len(x), axis=0)

def xc(l, r, M=40):
    return int(np.argmax([np.dot(l[M:-M], np.roll(r, k)[M:-M]) for k in range(-M, M + 1)]) - M)

def low_lag(a, s):
    seg = a[s - 2400:s + 2400]
    if len(seg) < 4800: return 0
    f = bp(seg)[2400:2400 + 720]
    return xc(f[:, 0], f[:, 1])

def knocks(base):
    m, a, acc = A.load(base)
    idx, _, _ = A.onsets(a)
    rows = A.features(m, a, acc, idx)
    ks = [r for r in rows if r['aratio'] >= ACCEL_MIN and r['loud'] >= LOUD_MIN]
    for k in ks: k['low'] = low_lag(a, k['s'])
    return m, ks, rows, len(a) / R

def commands(ks):
    groups, cur = [], []
    for k in ks:
        if cur and k['s'] / R - cur[-1]['s'] / R > GAP_MAX: groups.append(cur); cur = []
        if cur and k['s'] / R - cur[-1]['s'] / R < GAP_MIN: continue
        cur.append(k)
    if cur: groups.append(cur)
    out = []
    for g in groups:
        spots = ['near' if k['low'] >= NEAR_AT else 'far' if k['low'] <= FAR_BELOW else '?' for k in g]
        spot = spots[0] if len(set(spots)) == 1 and spots[0] != '?' else None
        out.append((g[0]['s'] / R, len(g), spot, [k['low'] for k in g]))
    return out

orient = 'landscape' if '--landscape' in sys.argv else 'portrait'
tot = dict(ok=0, wrong=0, unsure=0, single=0)
for js in sorted(glob.glob('data/raw/*.json')):
    if f'_{orient}_' not in js: continue
    m, ks, rows, dur = knocks(js[:-5])
    cmds = commands(ks)
    name = js.split('/')[-1][:-5]
    if m['label'] == 'desk-life':
        fired = [c for c in cmds if c[1] in (2, 3) and c[2]]
        print(f"\nDESK LIFE {name}: {dur/60:.1f} min, {len(rows)} sounds, {len(ks)} passed knock check, "
              f"{len(cmds)} groups → {len(fired)} commands would fire ({len(fired)/dur*3600:.1f}/hr)")
        for c in cmds: print(f"   t={c[0]:6.1f}s  {c[1]} knock(s)  spot={c[2]}  lags={c[3]}")
        for r in sorted(rows, key=lambda r: -r['aratio'])[:5]:
            print(f"   strongest non-gated: t={r['s']/R:6.1f}s accel x{r['aratio']:.0f} loud {r['loud']:.0f}")
        continue
    if m['label'] not in ('near', 'far'):
        lags = [k['low'] for k in ks]
        print(f"{name:46s} {len(ks):3d} knocks  low-lag median {np.median(lags) if lags else 0:+.0f} (10-90%: {np.percentile(lags,10) if lags else 0:+.0f}..{np.percentile(lags,90) if lags else 0:+.0f})")
        continue
    ok = sum(1 for c in cmds if c[2] == m['label']); wrong = sum(1 for c in cmds if c[2] and c[2] != m['label'])
    uns = sum(1 for c in cmds if not c[2]); singles = sum(1 for c in cmds if c[1] == 1)
    tot['ok'] += ok; tot['wrong'] += wrong; tot['unsure'] += uns
    lags = [k['low'] for k in ks]
    print(f"{name:46s} {len(ks):3d} knocks {len(cmds):3d} groups (by size {dict(zip(*np.unique([c[1] for c in cmds], return_counts=True)))}): "
          f"right spot {ok}, WRONG {wrong}, unsure {uns}   low-lag median {np.median(lags):+.0f}")
    for c in cmds:
        if c[2] != m['label']: print(f"      miss t={c[0]:.1f}s n={c[1]} spot={c[2]} lags={c[3]}")
print(f"\nAll {orient} near/far groups: right {tot['ok']}, WRONG {tot['wrong']}, unsure (ignored) {tot['unsure']}")
