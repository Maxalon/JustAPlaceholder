import json, numpy as np, pandas as pd
R=pd.DataFrame(json.load(open('data/raw_vindicta_apollo.json'))); R['pdh']=R.pd+R.th; R['bd_']=R.bd
me={77:dict(kills=10,deaths=1,assists=21,net_worth=30419,pdh=29782,bd=7641), 3:dict(kills=12,deaths=2,assists=21,net_worth=35006,pdh=33681,bd=3244)}
W=dict(kills=1,assists=1,deaths=-.5,net_worth=.2,pdh=1,bd=.5)
WF=dict(kills=1,assists=8.6991/8.4184,deaths=-4.2161/8.4184,net_worth=1.8655/8.4184,pdh=8.3944/8.4184,bd=4.3781/8.4184)
filters={
 'current patch, all Normal (main)': R.st>0,
 'unranked only (match mode)': R.mm=='Unranked',
 'ranked only': R.mm=='Ranked',
 'last 36h (>=2026-10-11 12:00)': R.st>=pd.Timestamp('2026-10-11 12:00').timestamp(),
 'duration 22-32 min': R.duration_s.between(1320,1920),
 'winners only': R.won,
}
print(f"{'baseline':36s}{'nA':>7s}{'nV':>7s}{'S_Apollo':>9s}{'S_Vind':>8s}{'margin':>8s}  per-metric diff (A-V)")
for name,f in filters.items():
    S={};C={}
    for h in (77,3):
        s=R[f & (R.hero_id==h)]
        C[h]={k:W[k]*(me[h][k]-s[k].mean())/s[k].std() for k in W}; S[h]=sum(C[h].values())
    nA=(f&(R.hero_id==77)).sum(); nV=(f&(R.hero_id==3)).sum()
    print(f"{name:36s}{nA:>7d}{nV:>7d}{S[77]:>9.3f}{S[3]:>8.3f}{S[77]-S[3]:>8.3f}  ", {k:round(C[77][k]-C[3][k],3) for k in W})
# free weights main
S={h:sum(WF[k]*(me[h][k]-R[R.hero_id==h][k].mean())/R[R.hero_id==h][k].std() for k in WF) for h in (77,3)}
print("free-fit weights, main baseline: Apollo %.3f Vindicta %.3f margin %.3f"%(S[77],S[3],S[77]-S[3]))
print("\nEmpirical percentiles within own hero (current patch):")
for h,nm in ((77,'Apollo'),(3,'Vindicta')):
    s=R[R.hero_id==h]
    print(nm, {k:f"{(s[k]<me[h][k]).mean()*100:.1f}-{(s[k]<=me[h][k]).mean()*100:.1f}%" for k in W})
    print('   skew:', {k:round(s[k].skew(),2) for k in W})
