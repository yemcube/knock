#!/usr/bin/env python3
"""Train on round 1, test on round 2 (re-mounted): per knock, and per knock group (double/triple) with majority vote."""
import sys, glob, numpy as np
sys.path.insert(0, 'analysis'); import analyze as A
R2 = '20261027-0950'
GATE = float(sys.argv[1]) if len(sys.argv) > 1 else 20
labels = ['left', 'right', 'near', 'far']
data = {1: [], 2: []}
for js in sorted(glob.glob('data/raw/*.json')):
    m, a, acc = A.load(js[:-5])
    if m['label'] == 'desk-life': continue
    rnd = 2 if js.split('/')[-1] >= R2 else 1
    idx, _, _ = A.onsets(a)
    allrows = A.features(m, a, acc, idx)
    rows = [r for r in allrows if r['aratio'] >= GATE and r['loud'] >= 100]
    print(f'   dropped by accel gate: {len(allrows)-len(rows)}', end='  ')
    for r in rows: r['label'] = m['label']; r['sess'] = js
    data[rnd] += rows
    print(f"round {rnd} {js.split('/')[-1][:-5]:36s} onsets {len(rows)}  accel min x{min(r['aratio'] for r in rows):.1f}")

def groups(rows):
    """Split a session's onsets into bursts: knocks <0.6 s apart belong to one group."""
    out, cur = [], []
    for r in rows:
        if cur and (r['s'] - cur[-1]['s']) / A.RATE > 0.6 or (cur and r['sess'] != cur[-1]['sess']):
            out.append(cur); cur = []
        cur.append(r)
    if cur: out.append(cur)
    return out

feats = {
    'mic band diff': lambda r: r['bdiff'],
    'band diff + accel dir': lambda r: np.r_[r['bdiff'], r['adir'] * 3],
    'all': lambda r: A.vec(r, 'all'),
}
def classify(train, test, f, k=5):
    X = np.array([f(r) for r in train]); Y = np.array([r['label'] for r in train])
    T = np.array([f(r) for r in test])
    mu, sd = X.mean(0), X.std(0) + 1e-9
    Z, ZT = (X - mu) / sd, (T - mu) / sd
    D = ((ZT[:, None] - Z[None]) ** 2).sum(-1)
    pred, conf = [], []
    for d in D:
        nn = np.argsort(d)[:k]; v, c = np.unique(Y[nn], return_counts=True)
        pred.append(v[np.argmax(c)]); conf.append(c.max() / k)
    return np.array(pred), np.array(conf)

g2 = groups(data[2])
sizes = np.bincount([len(g) for g in g2])
print('\nround-2 groups by size (1=single,2=double,3=triple..):', {i: int(n) for i, n in enumerate(sizes) if n})
for sets, title in ((labels, '4 spots'), (['near', 'far'], 'near vs far'), (['right', 'near'], 'right vs near'), (['left', 'near'], 'left vs near'), (['left', 'right'], 'left vs right')):
    tr = [r for r in data[1] if r['label'] in sets]; te = [r for r in data[2] if r['label'] in sets]
    print(f'\n--- {title}: train round1 ({len(tr)}) → test round2 ({len(te)}) ---')
    for name, f in feats.items():
        p, c = classify(tr, te, f); y = np.array([r['label'] for r in te])
        # same, but also training on round 2's own first third (= what a recalibration would give)
        acc1 = np.mean(p == y)
        sure = c >= 0.8
        # group vote
        gs = [g for g in groups(te)]
        off = 0; gok = 0
        for g in gs:
            pp = p[off:off + len(g)]; off += len(g)
            v, cn = np.unique(pp, return_counts=True); gok += v[np.argmax(cn)] == g[0]['label']
        print(f'  {name:22s} per-knock {acc1*100:5.1f}%   confident-only {np.mean(p[sure]==y[sure])*100:5.1f}% (keeps {sure.mean()*100:.0f}%)   per-group vote {gok}/{len(gs)}')
    if title == '4 spots':
        p, c = classify(tr, te, feats['all']); y = np.array([r['label'] for r in te])
        print('  confusion (rows=true):', ' '.join(f'{l:>6s}' for l in labels))
        for l in labels: print(f'  {l:>6s}               ', ' '.join(f'{np.sum((y==l)&(p==m)):6d}' for m in labels))
# recalibrated: train on round1 + first 15 of each round-2 spot, test on the rest of round 2
print('\n--- recalibrated (round1 + first 15 knocks/spot of round2) → rest of round2 ---')
cal, rest = [], []
for l in labels:
    rr = [r for r in data[2] if r['label'] == l]; cal += rr[:15]; rest += rr[15:]
for sets, title in ((labels, '4 spots'), (['near', 'far'], 'near vs far'), (['left', 'right'], 'left vs right')):
    tr = [r for r in data[1] + cal if r['label'] in sets]; te = [r for r in rest if r['label'] in sets]
    p, c = classify(tr, te, feats['all']); y = np.array([r['label'] for r in te])
    print(f'  {title:14s} per-knock {np.mean(p==y)*100:5.1f}%  ({len(te)} knocks)')
