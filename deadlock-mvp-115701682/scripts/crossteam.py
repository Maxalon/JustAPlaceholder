import sys, json, numpy as np, pandas as pd, collections
sys.path.insert(0,'scripts'); from load import load
from scipy.optimize import minimize_scalar
DF=load(); a=pd.DataFrame(json.load(open('data/agg_cur.json'))).set_index('hero_id')
W=dict(kills=7.1408,deaths=-3.5804,assists=7.4241,net_worth=1.6496,pd=6.7521,bd=3.6518,th=0.2837)
DF['S']=sum(w*((DF[c]-DF.hero_id.map(a[c+'_mu']))/DF.hero_id.map(a[c+'_sd'])).fillna(0) for c,w in W.items())/7.1408  # kills-z units
recs=[]
for mid,g in DF.groupby('match_id'):
    w=g[g.won].sort_values('S',ascending=False); l=g[~g.won].sort_values('S',ascending=False)
    pat=''.join('W' if x else 'L' for x in g.dropna(subset=['mvp_rank']).sort_values('mvp_rank').won)
    recs.append(dict(pat=pat, W1top=w.iloc[0].mvp_rank==1, W2=w.S.iloc[1], L1=l.S.iloc[0], L2=l.S.iloc[1],
        rank2_is_bestS_nonW1 = True))
R=pd.DataFrame(recs); print(R.pat.value_counts())
print("predicted W1 is rank1:", R.W1top.mean().round(4))
# Hypothesis: slots 2-3 = top two of remaining by (S + delta*loser), forced >=1 loser.
def pred(delta):
    L1=R.L1+delta; L2=R.L2+delta
    p=np.where(R.W2>L1,'WWL', np.where(L2>R.W2,'WLL','WLW')); return p
for d in [-2,-1,-.5,0,.5,1,2]:
    print(f"delta={d:+.1f} pattern acc={(pred(d)==R.pat).mean():.4f}")
top=DF.loc[DF.groupby('match_id').S.idxmax()]
print("matches where top-S overall is a loser:", (~top.won).mean().round(4), "; of those, rank1 still a winner:", (DF[DF.mvp_rank==1].won.mean()))
