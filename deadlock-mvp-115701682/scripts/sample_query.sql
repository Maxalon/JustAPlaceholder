SELECT match_id, toUnixTimestamp(start_time) st, duration_s, toString(match_mode) mm, average_badge_team0 b0, average_badge_team1 b1,
 toString(team) team, won, hero_id, account_id, player_slot, kills, deaths, assists, net_worth, last_hits, denies, abandon_match_time_s ab,
 final_stats.player_damage pd, final_stats.boss_damage bd, final_stats.creep_damage cd, final_stats.neutral_damage nd,
 final_stats.player_healing ph, final_stats.self_healing sh, final_stats.teammate_healing th, final_stats.teammate_barriering tb, final_stats.player_barriering pb,
 final_stats.damage_mitigated dm, final_stats.player_damage_taken dt, final_stats.absorption_provided ap, final_stats.damage_absorbed da, final_stats.heal_prevented hp,
 final_stats.level lvl, final_stats.creep_kills ck, final_stats.neutral_kills nk, final_stats.self_damage sd, final_stats.gold_player gp,
 mvp_rank, accolades.accolade_id acc, player_tracked_stats pts
FROM match_player
WHERE game_mode='Normal' AND match_mode IN ('Unranked','Ranked') AND start_time >= '2026-10-12 12:00:00' AND match_id % 10 = 3
ORDER BY match_id, player_slot
