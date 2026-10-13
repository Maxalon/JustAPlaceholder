import json, glob, pandas as pd, numpy as np
def load():
    rows=[]
    for f in sorted(glob.glob('data/slice*.json')): rows+=json.load(open(f))
    df=pd.DataFrame(rows).drop(columns=['acc','pts'])
    df['mins']=df.duration_s/60
    g=df.groupby('match_id')
    df=df[g.match_id.transform('size').eq(12) & g.mvp_rank.transform('count').eq(3)].copy()
    # team-top label: best (lowest) mvp_rank within team
    df['r']=df.mvp_rank.fillna(99)
    df['team_top']=df.r.eq(df.groupby(['match_id','team']).r.transform('min')) & df.r.lt(99)
    df['tk']=df.match_id.astype(str)+df.team
    return df
