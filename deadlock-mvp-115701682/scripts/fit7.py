import sys, numpy as np, pandas as pd
sys.path.insert(0,'scripts'); from load import load; from clogit import clogit; import final_model as fm
DF=fm.prep(load()); DF=DF[DF.groupby('tk').match_id.transform('size').eq(6)].sort_values(['tk','player_slot'])
comp=fm.components(DF)
r=clogit(DF,pd.DataFrame({k:v['z'] for k,v in comp.items()})); print('free 6:',r['nll'],r['acc'],r['w'])
for nw in [0.15,0.2,0.25]:
  for d in [-0.4,-0.5,-0.6]:
    W=dict(fm.W_ROUND,net_worth=nw,deaths=d); S=fm.score(DF,W).fillna(0)
    rr=clogit(DF,pd.DataFrame({'S':S})); print(f"rounded NW={nw} D={d}: nll={rr['nll']} acc={rr['acc']} temp={rr['w']['S']}")
# cross-team rule with rounded score
DF['S']=fm.score(DF).fillna(0); res=[]
for mid,g in DF.groupby('match_id'):
    if len(g)!=12: continue
    obs=''.join('W' if x else 'L' for x in g.dropna(subset=['mvp_rank']).sort_values('mvp_rank').won)
    obs_ids=list(g.dropna(subset=['mvp_rank']).sort_values('mvp_rank').account_id)
    w=g[g.won].sort_values('S',ascending=False); l=g[~g.won].sort_values('S',ascending=False)
    rest=pd.concat([w.iloc[1:],l]).sort_values('S',ascending=False)
    top=[w.iloc[0]]+[rest.iloc[0]]
    third = rest.iloc[1] if (rest.iloc[0].won==False or rest.iloc[1].won==False) else l.iloc[0]
    pred=[x.account_id for x in top+[third]]
    res.append(dict(r1=pred[0]==obs_ids[0], top3_exact=pred==obs_ids, top3_set=set(pred)==set(obs_ids)))
R=pd.DataFrame(res); print('matches',len(R)); print(R.mean().round(4))
