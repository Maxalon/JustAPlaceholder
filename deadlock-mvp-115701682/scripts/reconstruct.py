import json, sys, numpy as np, pandas as pd
sys.path.insert(0,'scripts'); import final_model as fm
mi=json.load(open('data/match_115701682_metadata.json')); mi=mi.get('match_info',mi)
H={x['id']:x['name'] for x in json.load(open('data/heroes.json'))}
rows=[]
for p in mi['players']:
    f=p['stats'][-1]
    rows.append(dict(slot=p['player_slot'],hero=H[p['hero_id']],hero_id=p['hero_id'],won=p['team']==mi['winning_team'],
        kills=p['kills'],deaths=p['deaths'],assists=p['assists'],net_worth=p['net_worth'],pd=f['player_damage'],th=f['teammate_healing'],bd=f['boss_damage'],mvp_rank=p.get('mvp_rank')))
M=fm.prep(pd.DataFrame(rows))
W_FREE={'kills':8.4184,'assists':8.6991,'deaths':-4.2161,'net_worth':1.8655,'player_damage_and_ally_healing':8.3944,'objective_damage':4.3781}
W_FREE={k:v/8.4184 for k,v in W_FREE.items()}
M['S_round']=fm.score(M); M['S_free']=fm.score(M,W_FREE)
# predicted ranks with the rule
def ranks(M,col):
    w=M[M.won].sort_values(col,ascending=False); l=M[~M.won].sort_values(col,ascending=False)
    rest=pd.concat([w.iloc[1:],l]).sort_values(col,ascending=False)
    third = rest.iloc[1] if (not rest.iloc[0].won or not rest.iloc[1].won) else l.iloc[0]
    return {w.iloc[0].slot:1, rest.iloc[0].slot:2, third.slot:3}
for col in ['S_round','S_free']:
    r=ranks(M,col); M['pred_'+col]=M.slot.map(r)
print(M.sort_values('S_round',ascending=False)[['slot','hero','won','kills','deaths','assists','net_worth','pd','bd','S_round','S_free','mvp_rank','pred_S_round','pred_S_free']].round(3).to_string(index=False))
print()
for slot in [7,12]:
    one=M[M.slot==slot]; comp=fm.components(one)
    print(f"=== {one.hero.iloc[0]} (slot {slot}) ===")
    print(f"{'metric':32s}{'raw':>9s}{'hero mean':>11s}{'hero SD':>10s}{'z':>8s}{'weight':>8s}{'contrib':>9s}")
    for k,c in comp.items():
        print(f"{k:32s}{c['raw'].iloc[0]:>9.0f}{c['mu'].iloc[0]:>11.1f}{c['sd'].iloc[0]:>10.1f}{c['z'].iloc[0]:>8.3f}{fm.W_ROUND[k]:>8.2f}{c['contrib'].iloc[0]:>9.3f}")
    print(f"{'TOTAL':32s}{'':>46s}{one.S_round.iloc[0]:>9.3f}\n")
