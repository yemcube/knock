#!/usr/bin/env python3
"""Prototype of the in-app Teach step: learn gates, lag windows and a knock template from N knocks per spot
of ONE session, then run on every other session + desk life. This is what a new phone/user would get."""
import sys, glob, numpy as np
sys.argv = sys.argv[:1]; sys.path.insert(0, 'analysis'); import analyze as A
exec(open('analysis/simulate.py').read().split("orient = ")[0].replace("ACCEL_MIN, LOUD_MIN = 25, 100", "ACCEL_MIN, LOUD_MIN = 12, 94"))
import os
N = int(os.environ.get('N', 8)); LIKEM = float(os.environ.get('LIKEM', 1.5)); PADF = float(os.environ.get('PADF', 1))
SD_FLOOR = np.r_[[1.5] * 7, [1.5] * 7, [0.08] * 3, [0.5]]

def fv(k): return np.r_[k['shape'], k['bdiff'], np.abs(k['adir']) * 3, k['lvl']]

def teach(a_ks, b_ks):
    cal = a_ks + b_ks
    X = np.array([fv(k) for k in cal]); mu = X.mean(0); sd = np.maximum(X.std(0), SD_FLOOR)
    Z = (X - mu) / sd
    D = np.sqrt(((Z[:, None] - Z[None]) ** 2).sum(-1)); np.fill_diagonal(D, np.inf)
    loo = np.sort(D, 1)[:, 2]
    like = loo.max() * LIKEM
    la, lb = np.array([k['low'] for k in a_ks]), np.array([k['low'] for k in b_ks])
    if np.median(la) < np.median(lb): la, lb, swap = lb, la, True
    else: swap = False
    pa = max(4, PADF * la.std()); pb = max(4, PADF * lb.std())
    hi = (la.min() - pa, la.max() + 2 * pa); lo = (lb.min() - 2 * pb, lb.max() + pb)
    if os.environ.get('MID'):
        mid = (np.median(la) + np.median(lb)) / 2; g = max(2, 0.1 * (np.median(la) - np.median(lb)))
        W = float(os.environ['MID'])
        hi = (mid + g, la.max() + max(W, 2 * la.std())); lo = (lb.min() - max(W, 2 * lb.std()), mid - g)
    elif hi[0] - lo[1] < 4:   # overlap: split at the midpoint with a 4-sample reject gap
        mid = (np.median(la) + np.median(lb)) / 2; hi = (mid + 2, hi[1]); lo = (lo[0], mid - 2)
    q = (np.median(la) - np.median(lb)) / (la.std() + lb.std() + 1e-9)
    return dict(mu=mu, sd=sd, Z=Z, like=like, hi=hi, lo=lo, swap=swap, q=q,
                accel=min(k['aratio'] for k in cal) * 0.5, loud=min(k['loud'] for k in cal) - 6)

def label(model, k, names):
    if k['aratio'] < model['accel'] or k['loud'] < model['loud']: return None   # not a knock
    z = (fv(k) - model['mu']) / model['sd']; d = np.sort(np.sqrt(((model['Z'] - z) ** 2).sum(1)))[2]
    if d > model['like']: return '?'
    hi_name, lo_name = (names[1], names[0]) if model['swap'] else names
    if model['hi'][0] <= k['low'] <= model['hi'][1]: return hi_name
    if model['lo'][0] <= k['low'] <= model['lo'][1]: return lo_name
    return '?'

def run_cmds(model, ks, names):
    lab = [(k, label(model, k, names)) for k in ks]
    lab = [(k, l) for k, l in lab if l is not None]
    groups, cur = [], []
    for k, l in lab:
        if cur and k['s'] / R - cur[-1][0]['s'] / R > GAP_MAX: groups.append(cur); cur = []
        if cur and k['s'] / R - cur[-1][0]['s'] / R < GAP_MIN: continue
        cur.append((k, l))
    if cur: groups.append(cur)
    out = []
    for g in groups:
        ls = [l for _, l in g]
        if os.environ.get('LOOSE'):
            dec = set(l for l in ls if l != '?')
            spot = dec.pop() if len(dec) == 1 and sum(l != '?' for l in ls) * 2 >= len(ls) else '?'
        else:
            s = set(ls); spot = s.pop() if len(s) == 1 and '?' not in s else '?'
        out.append((len(g), spot))
    return out

orient = sys.argv[1] if len(sys.argv) > 1 else 'portrait'
import pickle
CACHE = 'data/teach_cache.pkl'
S = pickle.load(open(CACHE, 'rb')) if os.path.exists(CACHE) else {}
for js in sorted(glob.glob(f'data/raw/*_portrait_*.json')) + sorted(glob.glob(f'data/raw/*_landscape_*.json')):
    if js.split('/')[-1][:-5] in S: continue
    m, _, rows, dur = knocks(js[:-5])
    for r in rows: r['low'] = low_lag(A.load(js[:-5])[1], r['s']) if r['aratio'] >= 10 else 0
    S[js.split('/')[-1][:-5]] = (m, rows, dur)

pickle.dump(S, open(CACHE, 'wb'))
def strong(rows): return [r for r in rows if r['aratio'] >= 25 and r['loud'] >= 100]
pairs = [('092343_portrait_near', '092405_portrait_far'), ('095901_portrait_near', '103730_portrait_far'),
         ('133747_portrait_near_mic', '133829_portrait_far_mic'), ('134058_portrait_near_camcorder', '134137_portrait_far_camcorder')]
def find(sub): return next(k for k in S if sub in k)
for a, b in pairs:
    A_, B_ = find(a), find(b)
    model = teach(strong(S[A_][1])[:N], strong(S[B_][1])[:N])
    tot = dict(right=0, wrong=0, unsure=0); false = 0; desk_min = 0
    for name, (m, rows, dur) in S.items():
        if '_portrait_' not in name: continue
        cmds = run_cmds(model, rows, ('near', 'far'))
        if m['label'] == 'desk-life':
            false += sum(1 for n, l in cmds if n in (2, 3) and l != '?'); desk_min += dur / 60
        elif m['label'] in ('near', 'far'):
            skip = N if name in (A_, B_) else 0   # don't score the teach knocks themselves
            for n, l in cmds[skip:]:
                tot['right' if l == m['label'] else 'unsure' if l == '?' else 'wrong'] += 1
    print(f"teach on {a[:6]} ({N}+{N} knocks): sep quality {model['q']:.1f} windows hi {np.round(model['hi'])} lo {np.round(model['lo'])} like≤{model['like']:.1f}"
          f" gates accel≥{model['accel']:.0f} loud≥{model['loud']:.0f}\n    → other knocks {tot}   desk-life false commands {false} in {desk_min:.1f} min")
# landscape: teach left/right on the one session's first N, test on the rest
la, ra = find('landscape_left'), find('landscape_right')
model = teach(strong(S[la][1])[:N], strong(S[ra][1])[:N])
tot = dict(right=0, wrong=0, unsure=0)
for name in (la, ra):
    for n, l in run_cmds(model, S[name][1], ('left', 'right'))[N:]:
        tot['right' if l == S[name][0]['label'] else 'unsure' if l == '?' else 'wrong'] += 1
false = sum(1 for n, l in run_cmds(model, S[find('desk-life_camcorder')][1], ('left', 'right')) if n in (2, 3) and l != '?')
print(f"landscape teach left/right: sep {model['q']:.1f} → rest {tot}; portrait desk-life false (wrong orientation, just a stress test) {false}")
