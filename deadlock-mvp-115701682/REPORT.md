# Why Apollo got MVP over Vindicta in Deadlock match 115701682

**Short answer:** MVP is the highest-scoring player on the **winning** team. Each player's score is a weighted sum of **hero-relative z-scores** on raw match totals, with no per-minute adjustment. Apollo scored **≈3.05** and Vindicta **≈2.16**.

Apollo's lead of 0.89 came mostly from two things:
- **Objective damage** gave him ≈70% of the lead. He was well above Apollo's average; Vindicta was below hers.
- **Deaths** gave him ≈27%. His 1 death is bottom-3% for Apollo.

Neither one alone flips the result. Vindicta's 2 more kills and higher net worth count for little after hero normalization.

Everything below is reproducible from `scripts/`. Run from this folder: `scripts/fetch_all.sh` first, then the analysis scripts.

---

## 1. Data source and build context

| Item | Value |
|---|---|
| Match data | `GET https://api.deadlock-api.com/v1/matches/115701682/metadata`. This is Valve's `CMsgMatchMetaDataContents`, decoded by deadlock-api. Saved in `data/match_115701682_metadata.json`. |
| Start / duration | 2026-10-12 23:49:18 UTC (2026-10-13 in most of Europe and Asia) / 1613 s (26.9 min) |
| Mode | Normal, **Unranked** (`match_mode=1`), `game_mode_version=2`. Winning team: Team 1. |
| Patch | "09-29-2026" (forum changelog posted 2026-10-05). It is the latest patch; no newer notes exist (`/v1/patches`). |
| Game-file evidence | SteamDatabase/GameTracking-Deadlock, HEAD `e6bb7e33` (2026-10-11), plus earlier commits cited in §3 |
| Comparison data | deadlock-api ClickHouse (`/v1/sql`, table `match_player`), queried 2026-10-13 ~00:30–01:00 UTC |

The match was not in the public dataset at first: the Steam fetch returned 503 several times. It became available about 70 minutes after the match, when the replay was published.

## 2. The two players: all raw stats (final stats tick, t = 1613 s)

| Field | **Apollo** (slot 7, MVP rank 1) | **Vindicta** (slot 12, MVP rank 2) |
|---|---:|---:|
| Kills / Deaths / Assists | 10 / 1 / 21 | 12 / 2 / 21 |
| Net worth (souls) | 30,419 | 35,006 |
| player_damage | 29,782 | 33,681 |
| boss_damage (= objective damage) | 7,641 | 3,244 |
| creep_damage / neutral_damage | 34,016 / 7,563 | 14,728 / 5,258 |
| player_healing (= self + teammate) | 10,735 | 2,296 |
| self_healing | 10,735 | 2,296 |
| teammate_healing (ally healing) | 0 | 0 |
| player/teammate barriering | 0 / 0 | 0 / 0 |
| player_damage_taken | 18,913 | 10,840 |
| damage_mitigated | 6,127 | 1,934 |
| heal_prevented / heal_lost | 1,559 / 305 | 339 / 55 |
| last_hits / denies | 121 / 3 | 128 / 1 |
| creep_kills / neutral_kills / possible_creeps | 75 / 26 / 95 | 46 / 13 / 115 |
| level / ability_points | 30 / 26 | 31 / 27 |
| max_health / weapon_power / tech_power | 3171 / 0 / 155 | 2653 / 234 / 101 |
| shots hit / missed | 362 / 241 | 819 / 956 |
| hero_bullets_hit / crit | 92 / 19 | 324 / 45 |
| kills by type: bullet / melee / ability / headshot | 1 / 3 / 6 / 1 | 3 / 1 / 8 / 0 |
| gold: player / lane creep / neutral / boss / treasure / denied / death loss | 8792 / 6878 / 1558 / 3902 / 0 / 179 / 0 | 11476 / 5667 / 2847 / 3897 / 934 / 53 / 50 |

The metadata also stores Valve's own tracked-stat values inside the accolades. For Apollo: `healing` = 10,735, `player_damage` = 29,782, `net_worth` = 30,419, `killstreak_kills` = 8. `player_tracked_stats` is empty in the metadata.

The other players' ranks: Rank 3 went to **Celeste** (losing team). No one else got a rank.

### Healing, in detail
- `player_healing == self_healing + teammate_healing` holds exactly in all 316k player rows sampled.
- Valve's tracked stat `healing` (ID 8; accolade "healing") equals `player_healing`. Apollo's value is 10,735. This is the likely scoreboard "Healing" number. That last point is inferred from the accolade, not from the UI.
- Valve also tracks `self_healing` (ID 52), `ally_healing` (ID 53) and **`player_damage_and_ally_healing` (ID 54)**. Stat 54 is the expression `player_damage + ally_healing`, added 2026-03-14, and no accolade uses it.
- The best fit uses stat 54, i.e. ally healing added to player damage. Total healing fits clearly worse: NLL 17,410 vs 13,474 in the 5-stat comparison. Self-healing on its own adds nothing.
- **Apollo's 10.7k healing is all self-healing and does not count.** Both players have 0 ally healing.

## 3. What Valve's code shows (primary evidence)

Sources: GameTracking-Deadlock (game-file dumps) and SteamDatabase/Protobufs.

1. **Score shape.** In server builds from 2026-01-06 (`7b56e242`) to 2026-02-17 (`784da97a`), `server_strings.txt` contained this debug line:
   `MVP Score: Player %d stat %s (%u) has value %d. Compared to mean %.2f, std dev %.2f. Player std dev is %.2f, multiplied by %.2f score is %.2f`
   So score = Σ weight × (value − mean) / std_dev over a list of tracked stats. `value` is an integer raw total, so there is no per-minute rate.
   The string disappeared on 2026-02-19 (`64e3a985`) and a `KDARatio` string appeared. Current builds still contain `CCitadelMVPSchemaGameSystem`, `Starting to track mvp stat %s (%u) for player %d`, and the `mvp_rank` field.
2. **Where the means and SDs come from.** The game coordinator sends them to the game server per lobby: `CServerLobbyData_PlayerStatValues.StatDistribution { tracked_stat_id, tracked_stat_mean, tracked_stat_std_dev }` in `citadel_gcmessages_server.proto`. It is still present.
   They are delivered per player ("PlayerStatValues"), alongside per-player accolade thresholds. So they can be per hero. **The actual numbers, and how the GC computes them, are not public.**
3. **Convars** (`/v1/assets/convars`):
   - `citadel_mvp_enabled`
   - `citadel_mvp_score_kda_ratio` (default false: "Use a KDA ratio for the mvp score instead of the real algorithm.")
   - `citadel_mvp_calculate_now`
   - `citadel_post_game_mvp_screen_enabled`

   There are **no weight convars**. The weights live in an MVP schema that is not in the dumped VPK; it is compiled in or sent by the GC.
4. No Valve patch note or dev post describes the formula. The only official mention is "revamped postgame with an MVP screen" (2026-01-22).
   Community explanations, such as the forum thread "how does the MVP/key players system work" and Statlocker's "valuations", have no traceable source. I did not use them.

## 4. Empirical reconstruction (current patch)

**Training set:** every Normal-mode ranked and unranked match from 2026-10-12 12:00 UTC to 2026-10-13 00:25 UTC with complete data. That is 26,313 matches (315,756 player rows; `scripts/sample_query.sql`).
- Unranked: 18,840 matches. Ranked: 7,473.
- `mvp_rank` is 1/2/3 for exactly three players per match.

### 4a. Who is eligible

| Observation | Result |
|---|---|
| Rank 1 on the winning team | **100%** (26,312 of 26,313; one anomalous match had an LWL pattern) |
| Top-3 win/loss patterns | WWL 21,857; WLW 2,561; WLL 1,894; **never WWW** |

Rule, with no win bonus in the score (`scripts/crossteam.py`; the bonus that fits best is 0):
- **Rank 1** = highest score on the winning team.
- **Ranks 2–3** = next highest scores from either team.
- **At least one loser** always makes the top 3.

This rule reproduces 97.7% of the win/loss patterns.

### 4b. Which stats, and which normalization
I fitted a conditional logit that predicts each team's top-ranked player from candidate features (`scripts/fit2.py`, `fit6.py`, `fit7.py`, `fit_windows.py`). Lower NLL means a better fit.

| Normalization (same 7 stats) | NLL | Top-player accuracy |
|---|---:|---:|
| **Hero z-score, raw totals** | **11,783** | **92.1%** |
| Hero z-score, per minute | 13,736 | 90.7% |
| Hero z-score, by duration bin | 16,038 | 88.9% |
| Ratio to hero mean | 13,708 | 90.7% |
| Hero percentile | 22,412 | 83.1% |
| Raw values (no hero baseline) | — | 82.9% |
| Per-minute (no hero baseline) | — | 83.0% |
| Within-match z / share of match | — | 78.7–80.5% |

The last three rows come from the first 2,691-match pilot fit with 16 stats; the gap to hero z was the same (hero z: 90.7%). The first rows use baselines estimated from the training sample. Replacing them with the full current-patch hero baselines improves the fit further (§4c).

Conclusions:
- Scores are **hero-relative z-scores on raw match totals**.
- **Duration is not normalized.** Both the debug string and the fit agree.
- A pooled SD fits worse than a per-hero SD.

| Stat set (Valve tracked-stat names) | NLL | Accuracy |
|---|---:|---:|
| kills, assists, deaths, net_worth, **player_damage_and_ally_healing**, objective_damage | **7,713** | **95.3%** |
| Same, with player_damage instead | 9,436 | 94.1% |
| kills_and_assists (combined) instead of kills + assists | 13,474 | 90.3% |
| Plus last hits, denies, damage mitigated, damage taken | no gain (weights ≈ 0) | |
| Plus barriers, creep/neutral damage, level, self-healing, etc. | no meaningful gain | |
| KDA only | 44,594 | 67.3% |

### 4c. Which baselines

Same model, with hero means and SDs taken from different time windows. Evaluated only on teams whose heroes exist in every window; three heroes (Baba, Rat King, Solomon) are new this patch and make up 15.7% of picks.

| Baseline window | Player rows | NLL | Accuracy |
|---|---:|---:|---:|
| **2026-10-06 → 10-13, ranked + unranked (current patch)** | 5.80M | **3,137** | **93.8%** |
| 2026-09-17 → 09-29 (previous patch) | 6.49M | 3,582 | 92.1% |
| 2026-07-01 → 08-12 | 23.2M | 4,263 | 90.2% |
| 2026-08-23 → 09-16 | 13.9M | 4,659 | 89.7% |
| Current patch, ranked only | 1.28M | 5,117 | 87.7% |
| 2026-01-01 → 07-01 | 112M | 6,148 | 85.9% |

**Valve's distributions appear to be fresh (current patch) and pooled across ranked and unranked.** Rank-bracket baselines fit worse.

### 4d. Weights (best-supported)

| Tracked stat (ID) | Free fit (kills = 1) | Rounded (used below) |
|---|---:|---:|
| kills (1) | 1.000 | **1** |
| assists (2) | 1.033 | **1** |
| deaths (5) | −0.501 | **−0.5** |
| net_worth (4) | 0.222 | **0.2** |
| player_damage_and_ally_healing (54) | 0.997 | **1** |
| objective_damage (7) = `boss_damage` | 0.520 | **0.5** |

The rounded weights lose almost nothing: NLL 7,786 vs 7,713.

Accuracy of the rounded model on all 26,313 matches:
- Exact MVP: **95.5%**
- Exact top-3 order: 84.0%
- Same top-3 set: 88.7%

## 5. Reconstructed scores

Baseline: current patch, Normal mode, ranked + unranked, 2026-10-06 00:00 → 2026-10-13 00:25 UTC. Raw sample sets: **Apollo n = 129,770**, **Vindicta n = 159,492** (`data/raw_vindicta_apollo.json` via `fetch_all.sh`).

**Apollo, MVP**

| Metric | Raw | Hero mean | Hero SD | z | Weight | Contribution |
|---|---:|---:|---:|---:|---:|---:|
| kills | 10 | 6.9 | 4.4 | +0.709 | 1 | +0.709 |
| assists | 21 | 12.2 | 6.5 | +1.359 | 1 | +1.359 |
| deaths | 1 | 7.2 | 3.6 | −1.718 | −0.5 | +0.859 |
| net_worth | 30,419 | 38,777 | 12,599 | −0.663 | 0.2 | −0.133 |
| player_damage + ally_healing | 29,782 + 0 | 31,358 | 15,348 | −0.103 | 1 | −0.103 |
| objective_damage | 7,641 | 4,830 | 3,939 | +0.714 | 0.5 | +0.357 |
| **Total** | | | | | | **3.049** |

**Vindicta, rank 2 (Key Player)**

| Metric | Raw | Hero mean | Hero SD | z | Weight | Contribution |
|---|---:|---:|---:|---:|---:|---:|
| kills | 12 | 8.6 | 5.6 | +0.613 | 1 | +0.613 |
| assists | 21 | 11.8 | 6.1 | +1.517 | 1 | +1.517 |
| deaths | 2 | 6.1 | 3.3 | −1.237 | −0.5 | +0.619 |
| net_worth | 35,006 | 39,551 | 12,890 | −0.353 | 0.2 | −0.071 |
| player_damage + ally_healing | 33,681 + 0 | 38,651 | 19,692 | −0.252 | 1 | −0.252 |
| objective_damage | 3,244 | 6,604 | 6,352 | −0.529 | 0.5 | −0.264 |
| **Total** | | | | | | **2.161** |

**All 12 players.** The model reproduces the observed ranks (`scripts/reconstruct.py`):

| Player | Team | Score | Observed rank | Predicted rank |
|---|---|---:|---:|---:|
| Apollo | Win | 3.05 | 1 | 1 |
| Vindicta | Win | 2.16 | 2 | 2 |
| Rat King | Win | 2.16 | — | — |
| Paradox | Win | 0.73 | | |
| Mina | Win | −0.07 | | |
| Silver | Win | −0.32 | | |
| Celeste | Loss | −2.89 | 3 | 3 (best loser) |
| Losers 2–6 | Loss | −4.2 to −5.2 | | |

Vindicta vs Rat King for rank 2 is a coin flip (2.161 vs 2.156). With the free-fit weights Rat King edges ahead.

## 6. Which metrics put Apollo above Vindicta (margin 0.886)

| Metric | Apollo − Vindicta contribution | Share of margin |
|---|---:|---:|
| **objective_damage** | **+0.621** | 70% |
| **deaths** | **+0.240** | 27% |
| player_damage (+ ally healing) | +0.150 | 17% |
| kills | +0.096 | 11% |
| net_worth | −0.062 | −7% |
| assists | −0.158 | −18% |

Why each metric came out this way:
- **Objective damage.** Apollo's 7,641 is about the 79th percentile for Apollo. Vindicta's 3,244 is about the 40th percentile for her; her hero average is 6,604.
- **Deaths.** Apollo's 1 death is around the 1st–4th percentile for Apollo, so it is genuinely exceptional. Vindicta's 2 deaths are around the 7th–14th percentile.
- **Player damage.** Vindicta's 33.7k is below the Vindicta average of 38.7k. Apollo's 29.8k is near the Apollo average.
- **Kills.** Vindicta had 2 more kills, but Apollo is still ahead here because Vindicta's kill baseline is higher (8.6 vs 6.9).
- **Assists.** Apollo's lower assist baseline helps him less than Vindicta's equally high assists help her.

Removing one metric at a time:

| Metric removed | Margin | Winner |
|---|---:|---|
| Objective damage | 0.265 | Apollo |
| Deaths | 0.646 | Apollo |
| Both | 0.025 | Apollo (≈ tie) |

**So the low deaths alone do not explain it.** The decision rests mainly on objective damage, then deaths, with hero normalization of damage and kills adding to it.

What Vindicta would have needed to tie:
- About **+11.3k more objective damage** (≈14.5k total).
- 0 deaths would not be enough: it gains only +0.30.

**Robustness** (`scripts/sensitivity.py`): the margin stays positive and objective damage stays the largest factor under every baseline tried.

| Baseline | Margin |
|---|---:|
| Unranked only | 0.920 |
| Ranked only | 0.742 |
| Last 36 h | 0.909 |
| 22–32 min games only | 0.964 |
| Winners only | 0.842 |
| Free-fit weights | 0.899 |

Under the fitted model, the probability that Valve's true ordering of these two matches ours is above 99.9%.

## 7. Confidence

| Claim | Confidence | Basis |
|---|---|---|
| Apollo is MVP, Vindicta rank 2, both on the winning team | Certain | Valve metadata |
| Score = Σ w·(x − μ)/σ over tracked stats, raw totals (no duration normalization) | High | Debug string (Jan–Feb 2026) and best empirical fit. The string is gone, but the GC mean/SD protocol remains, and the fit still prefers this form today. |
| μ/σ are per hero | High | Hero z beats every non-hero normalization by a wide margin |
| Exact μ/σ Valve used | **Not available** | They are GC-supplied and not exported anywhere public. Mine are current-patch public estimates. The model still misses about 4.5% of MVPs, which is consistent with Valve's baselines differing slightly from mine (window, population or mode filters). |
| Baseline population ≈ current patch, ranked + unranked pooled | Medium | Best fit among windows tested; a finer rolling window can't be ruled out |
| Stat list: K, A, D, NW, player_damage_and_ally_healing, objective_damage | Medium-high | Best fit; all are Valve tracked stats; other stats add nothing |
| Weights 1 / 1 / −0.5 / 0.2 / 1 / 0.5 | Medium | Fitted; Valve's exact constants are unpublished. Net worth (0.15–0.25) is the least certain. |
| Healing: only ally healing counts (via stat 54); self-healing does not | Medium-high | Stat 54 definition plus fit comparison. Irrelevant here: both players have 0 ally healing. |
| Rank 1 restricted to winners; ≥1 loser in top 3 | High (empirical) | 26,312 of 26,313 matches; never WWW |
| Apollo > Vindicta driven mainly by objective damage, then deaths | High | Holds under every baseline variant and weight set tried |
| Vindicta vs Rat King for rank 2 | Low | Within model noise |

## 8. Reproduce

```bash
cd deadlock-mvp-115701682
scripts/fetch_all.sh                # ~10 min (SQL endpoint: 2 req/min; dsql.py waits on 429)
python3 scripts/explore2.py data/slice3.json   # win/loss pattern of ranks 1-3
python3 scripts/crossteam.py        # rank-1 = best winner; ranks 2-3 rule
python3 scripts/fit2.py             # normalization comparison
python3 scripts/fit_windows.py cur cur_ranked prev aug jul h1   # baseline window comparison
python3 scripts/fit6.py             # stat-set comparison (incl. combined Valve stats)
python3 scripts/fit7.py             # rounded weights + full-match accuracy
python3 scripts/reconstruct.py      # all 12 players, component tables
python3 scripts/sensitivity.py      # baseline sensitivity + percentiles
```

Key endpoints:
- `https://api.deadlock-api.com/v1/matches/115701682/metadata`
- `https://api.deadlock-api.com/v1/sql?query=...` (tables `match_player`, `player_match_stats`; columns `mvp_rank`, `final_stats.*`)
- `https://api.deadlock-api.com/v1/assets/convars`
- `https://api.deadlock-api.com/v1/assets/accolades`
- `https://github.com/SteamDatabase/GameTracking-Deadlock`: `game/citadel/bin/win64/server_strings.txt` at `7b56e242`, `game/citadel/pak01_dir/stats/tracked_stats_player.vdata`, `Protobufs/citadel_gcmessages_server.proto`

Python needs `pandas`, `numpy` and `scipy`.
