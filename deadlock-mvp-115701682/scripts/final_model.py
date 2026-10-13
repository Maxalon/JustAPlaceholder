"""Best-supported reconstruction: S = sum_w w * (x - mu_hero)/sd_hero over Valve tracked stats; baselines = current patch, Normal mode, ranked+unranked."""
import json, numpy as np, pandas as pd
A=pd.DataFrame(json.load(open('data/agg_cur.json'))).set_index('hero_id').join(pd.DataFrame(json.load(open('data/agg_cur2.json'))).set_index('hero_id').drop(columns='n'))
STAT={'kills':'kills','assists':'assists','deaths':'deaths','net_worth':'net_worth','player_damage_and_ally_healing':'pdh','objective_damage':'bd'}
W_ROUND={'kills':1,'assists':1,'deaths':-0.5,'net_worth':0.2,'player_damage_and_ally_healing':1,'objective_damage':0.5}
def prep(df):
    df=df.copy(); df['pdh']=df.pd+df.th; return df
def components(df, W=W_ROUND):
    out={}
    for name,col in STAT.items():
        mu=df.hero_id.map(A[col+'_mu']); sd=df.hero_id.map(A[col+'_sd']); z=(df[col]-mu)/sd
        out[name]=dict(raw=df[col],mu=mu,sd=sd,z=z,contrib=W[name]*z)
    return out
def score(df,W=W_ROUND):
    return sum(c['contrib'] for c in components(df,W).values())
