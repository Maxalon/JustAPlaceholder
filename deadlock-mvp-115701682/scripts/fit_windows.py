import sys, json, numpy as np, pandas as pd
sys.path.insert(0,'scripts'); from load import load; from clogit import clogit
DF=load(); DF=DF[DF.groupby('tk').match_id.transform('size').eq(6)].sort_values(['tk','player_slot'])
C=['kills','deaths','assists','net_worth','pd','bd','th']
for name in sys.argv[1:]:
    a=pd.DataFrame(json.load(open(f'data/agg_{name}.json'))).set_index('hero_id')
    X=pd.DataFrame({c:(DF[c]-DF.hero_id.map(a[c+'_mu']))/DF.hero_id.map(a[c+'_sd']) for c in C})
    r=clogit(DF,X); print(f"{name:12s} rows={a.n.sum():8d} heroes={len(a)}", {k:r[k] for k in ['nll','acc','ok']}, r['w'])
    for mm in ['Unranked','Ranked']:
        d=(DF.mm==mm).values; rr=clogit(DF[d],X[d]); print('    ',mm, {k:rr[k] for k in ['nll','acc','n','ok']})
print("\n=== restricted to teams whose 6 heroes exist in ALL given windows ===")
common=set.intersection(*[set(pd.DataFrame(json.load(open(f'data/agg_{n}.json'))).hero_id) for n in sys.argv[1:]])
ok=DF.groupby('tk').hero_id.transform(lambda s:s.isin(common).all())
D2=DF[ok]
for name in sys.argv[1:]:
    a=pd.DataFrame(json.load(open(f'data/agg_{name}.json'))).set_index('hero_id')
    X=pd.DataFrame({c:(D2[c]-D2.hero_id.map(a[c+'_mu']))/D2.hero_id.map(a[c+'_sd']) for c in C})
    r=clogit(D2,X); print(f"{name:12s}", {k:r[k] for k in ['nll','acc','n','ok']})
