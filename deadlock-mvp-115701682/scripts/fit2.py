import sys, numpy as np, pandas as pd
sys.path.insert(0,'scripts'); from load import load
from scipy.optimize import minimize
df=load(); print("rows",len(df),"matches",df.match_id.nunique())
df=df[df.groupby('tk').match_id.transform('size').eq(6)].sort_values(['tk','player_slot'])
F=['kills','deaths','assists','net_worth','pd','bd','ph']
for s in F: df[s+'_pm']=df[s]/df.mins
df['dbin']=pd.cut(df.duration_s,[0,1200,1500,1800,2100,2400,99999],labels=False)
def z(cols, by):
    out=pd.DataFrame(index=df.index)
    for c in cols:
        g=df.groupby(by)[c]; out[c]=(df[c]-g.transform('mean'))/g.transform('std')
    return out
def pct(cols,by):
    return pd.DataFrame({c:df.groupby(by)[c].rank(pct=True) for c in cols})
variants={
 'heroz_raw': z(F,'hero_id'),
 'heroz_permin': z([s+'_pm' for s in F],'hero_id'),
 'heroz_raw_durbin': z(F,['hero_id','dbin']),
 'heroz_permin_durbin': z([s+'_pm' for s in F],['hero_id','dbin']),
 'heropct_raw': pct(F,'hero_id'),
 'heropct_permin': pct([s+'_pm' for s in F],'hero_id'),
}
y=df.groupby('tk',sort=False).team_top.apply(lambda s:int(np.argmax(s.values))).values
def fit(X):
    X=X.values.reshape(-1,6,X.shape[1])
    def f(w):
        s=X@w; s=s-s.max(1,keepdims=True); e=np.exp(s); p=e/e.sum(1,keepdims=True)
        nll=-np.log(p[np.arange(len(y)),y]).sum(); g=(p[:,:,None]*X).sum(1)-X[np.arange(len(y)),y]
        return nll,g.sum(0)
    r=minimize(f,np.zeros(X.shape[2]),jac=True,method='BFGS')
    return r.fun,((X@r.x).argmax(1)==y).mean(),r.x
assert (df.groupby('tk',sort=False).size()==6).all()
for k,X in variants.items():
    nll,acc,w=fit(X.fillna(0))
    print(f"{k:22s} NLL={nll:9.1f} acc={acc:.4f} w/|w|max=",np.round(w/np.abs(w).max(),3))
