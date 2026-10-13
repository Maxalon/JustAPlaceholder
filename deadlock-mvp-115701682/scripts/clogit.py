import numpy as np
from scipy.optimize import minimize
def team_labels(df):
    return df.groupby('tk',sort=False).team_top.apply(lambda s:int(np.argmax(s.values))).values
def clogit(df, X, k=6):
    """Conditional logit, one choice per group of k consecutive rows. Returns nll, acc, weights (per original feature unit), success."""
    y=team_labels(df); names=list(X.columns)
    A=X.replace([np.inf,-np.inf],np.nan).fillna(0).values.astype(float)
    sd=A.std(0); sd[sd==0]=1; A=(A/sd).reshape(-1,k,A.shape[1])
    def f(w):
        s=A@w; s=s-s.max(1,keepdims=True); e=np.exp(s); p=e/e.sum(1,keepdims=True)
        return -np.log(p[np.arange(len(y)),y]+1e-300).sum(),((p[:,:,None]*A).sum(1)-A[np.arange(len(y)),y]).sum(0)
    r=minimize(f,np.zeros(A.shape[2]),jac=True,method='L-BFGS-B',options={'maxiter':5000})
    w=r.x/sd
    return dict(nll=round(r.fun,1), acc=round(((A@r.x).argmax(1)==y).mean(),4), n=len(y), ok=r.success, w=dict(zip(names,np.round(w,4))))
