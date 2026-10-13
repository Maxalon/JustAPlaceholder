import json, sys, collections
d = json.load(open(sys.argv[1]))
m = collections.defaultdict(list)
for r in d: m[r['match_id']].append(r)
c = collections.Counter()
for v in m.values():
    if len(v)!=12 or sum(1 for r in v if r['mvp_rank'])!=3: continue
    pat = ''.join('W' if r['won'] else 'L' for r in sorted([r for r in v if r['mvp_rank']], key=lambda r:r['mvp_rank']))
    c[pat]+=1
print(c)
