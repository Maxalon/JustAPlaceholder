import sys, json, numpy as np, pandas as pd
sys.path.insert(0,'scripts'); from load import load; from clogit import clogit
DF=load(); DF=DF[DF.groupby('tk').match_id.transform('size').eq(6)].sort_values(['tk','player_slot'])
a=pd.DataFrame(json.load(open('data/agg_cur.json'))).set_index('hero_id').join(pd.DataFrame(json.load(open('data/agg_cur2.json'))).set_index('hero_id').drop(columns='n'))
DF['ka']=DF.kills+DF.assists; DF['pdh']=DF.pd+DF.th; DF['pdph']=DF.pd+DF.ph; DF['lh']=DF.last_hits; DF['dn']=DF.denies
Z=lambda c:(DF[c]-DF.hero_id.map(a[c+'_mu']))/DF.hero_id.map(a[c+'_sd'])
def show(name,cols):
    r=clogit(DF,pd.DataFrame({c:Z(c) for c in cols})); w=r['w']; m=max(abs(v) for v in w.values())
    print(f"{name:34s} k={len(cols)} NLL={r['nll']:8.1f} acc={r['acc']:.4f} ok={r['ok']}  rel w:", {k:round(v/m,3) for k,v in w.items()})
show('7 separate', ['kills','deaths','assists','net_worth','pd','bd','th'])
show('Valve-stats: KA,D,NW,PD+AH,OBJ', ['ka','deaths','net_worth','pdh','bd'])
show('KA,D,NW,PD,OBJ', ['ka','deaths','net_worth','pd','bd'])
show('KA,D,NW,PD+heal(total),OBJ', ['ka','deaths','net_worth','pdph','bd'])
show('KA,D,NW,PD+AH,OBJ,+K', ['ka','kills','deaths','net_worth','pdh','bd'])
show('KA,D,NW,PD+AH,OBJ,+PD', ['ka','deaths','net_worth','pdh','pd','bd'])
show('KA,D,PD+AH,OBJ (no NW)', ['ka','deaths','pdh','bd'])
show('+lh,dn,dm,dt', ['ka','deaths','net_worth','pdh','bd','lh','dn','dm','dt'])
print('----')
show('K,A,D,NW,PDH,OBJ', ['kills','assists','deaths','net_worth','pdh','bd'])
show('K,A,KA,D,NW,PDH,OBJ', ['kills','assists','ka','deaths','net_worth','pdh','bd'])
show('K,KA,D,NW,PDH,OBJ', ['kills','ka','deaths','net_worth','pdh','bd'])
show('K,KA,D,NW,PD,TH,OBJ', ['kills','ka','deaths','net_worth','pd','th','bd'])
show('K,KA,D,NW,PDH,PD,OBJ', ['kills','ka','deaths','net_worth','pdh','pd','bd'])
show('K,A,D,NW,PD,OBJ', ['kills','assists','deaths','net_worth','pd','bd'])
