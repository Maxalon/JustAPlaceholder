#!/usr/bin/env bash
# Re-download everything. Run from deadlock-mvp-115701682/. SQL endpoint is limited to 2 req/min; dsql.py waits automatically.
set -e
curl -sS https://api.deadlock-api.com/v1/matches/115701682/metadata -o data/match_115701682_metadata.json
curl -sS "https://api.deadlock-api.com/v1/assets/heroes?only_active=true" -o data/heroes.json
curl -sS https://api.deadlock-api.com/v1/assets/accolades -o data/accolades.json
# 1) training set: every Normal (ranked+unranked) match since 2026-10-12 12:00 UTC, in 10 slices
for k in 0 1 2 3 4 5 6 7 8 9; do
  python3 scripts/dsql.py "$(sed "s/match_id % 10 = 3/match_id % 10 = $k/" scripts/sample_query.sql) LIMIT 100000" data/slice$k.json
done
# 2) per-hero mean/SD baselines for several windows
scripts/agg.sh 2026-10-06 2026-10-14 data/agg_cur.json
scripts/agg.sh 2026-09-17 2026-09-29 data/agg_prev.json
scripts/agg.sh 2026-08-23 2026-09-16 data/agg_aug.json
scripts/agg.sh 2026-07-01 2026-08-12 data/agg_jul.json
scripts/agg.sh 2026-01-01 2026-07-01 data/agg_h1.json
scripts/agg.sh 2026-10-06 2026-10-14 data/agg_cur_ranked.json "AND match_mode='Ranked'"
python3 scripts/dsql.py "SELECT hero_id, count() n, avg(kills+assists) ka_mu, stddevSamp(kills+assists) ka_sd, avg(final_stats.player_damage+final_stats.teammate_healing) pdh_mu, stddevSamp(final_stats.player_damage+final_stats.teammate_healing) pdh_sd, avg(final_stats.player_damage+final_stats.player_healing) pdph_mu, stddevSamp(final_stats.player_damage+final_stats.player_healing) pdph_sd, avg(last_hits) lh_mu, stddevSamp(last_hits) lh_sd, avg(denies) dn_mu, stddevSamp(denies) dn_sd, avg(final_stats.damage_mitigated) dm_mu, stddevSamp(final_stats.damage_mitigated) dm_sd, avg(final_stats.player_damage_taken) dt_mu, stddevSamp(final_stats.player_damage_taken) dt_sd FROM match_player WHERE game_mode='Normal' AND match_mode IN ('Unranked','Ranked') AND start_time >= '2026-10-06' GROUP BY hero_id ORDER BY hero_id" data/agg_cur2.json
# 3) raw per-game sample sets for Vindicta (3) and Apollo (77), current patch
python3 scripts/dsql.py "SELECT hero_id, match_id, toUnixTimestamp(start_time) st, duration_s, toString(match_mode) mm, average_badge_team0 b0, average_badge_team1 b1, won, kills, deaths, assists, net_worth, final_stats.player_damage pd, final_stats.teammate_healing th, final_stats.boss_damage bd, final_stats.player_healing ph, mvp_rank FROM match_player WHERE game_mode='Normal' AND match_mode IN ('Unranked','Ranked') AND start_time >= '2026-10-06' AND hero_id IN (3,77)" data/raw_vindicta_apollo.json
