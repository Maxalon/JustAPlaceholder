# mtg-judge

A fast, local Magic: The Gathering rules assistant. Card data, rulings and the Comprehensive
Rules live in one SQLite file that is rebuilt daily; questions are answered by a deterministic
rules engine whose every step cites the rule that justifies it. No language-model API in the loop.

```
$ mtg-judge ask "I have Rhystic Study. It's their turn, they cast Sol Ring and Stifle the Rhystic Study trigger."
$ mtg-judge ask "Alice attacks Bob with Hill Giant and Carol with Grizzly Bears. Bob blocks with Wall of Omens. Carol is at 2 life."
$ mtg-judge ask "I have Jace Beleren with 3 loyalty. I activate Jace's +2. Then my opponent attacks Jace with Hill Giant."
$ mtg-judge ask "What order do triggers go on the stack?"   # a question about the rules, with no board at all
$ mtg-judge card "time vault" --set lea     # Oracle text, rulings, and a warning if that printing's text is outdated
$ mtg-judge rule 702.19                     # a rule with its subrules, or `rule trample`, `rule "state-based action"`
$ mtg-judge resolve "rystic studdy"         # how a (misspelled) name resolves
```

## Layout

| path | what |
|---|---|
| `.github/workflows/fetch-data.yml` | daily: fetch Scryfall bulk data, MTGJSON and the rules text, build `judge.db`, publish to the `data` branch |
| `src/main/kotlin/mtg/judge/carddb` | database schema, ingest, name resolution (exact, face, fuzzy) |
| `src/main/kotlin/mtg/judge/cr` | Comprehensive Rules parser and lookups, and the answers to questions about the rules themselves |
| `src/main/kotlin/mtg/judge/oracle` | template-based Oracle text → abilities/effects; unknown text is marked, never guessed |
| `src/main/kotlin/mtg/judge/engine` | the rules engine: stack, triggers, targets, resolution, state-based actions, with a rule-cited trace |
| `src/main/kotlin/mtg/judge/situation` | the situation language (see `docs/situation-language.md`) and the judge that runs it |
| `src/main/kotlin/mtg/judge/nl` | hand-written natural-language front door |

## Getting a database

Either pull the latest build from the `data` branch:

```
git fetch origin data
git show origin/data:judge.db.xz > judge.db.xz && xz -d judge.db.xz     # (concatenate .partNN files if it was split)
```

or build it yourself from the raw sources on that branch:

```
./gradlew installDist
build/install/mtg-judge/bin/mtg-judge build --data path/to/data-branch-checkout --out judge.db
```

Point the CLI at it with `--db judge.db` or `MTG_JUDGE_DB=judge.db`.

## Tests

`./gradlew test` builds a database from small real-data fixtures and exercises ingest, lookups,
the rules parser, the Oracle parser, the engine, the situation judge and the language front door.
With `MTG_JUDGE_DB` set to a full database it also checks every rule number the engine cites
against the real Comprehensive Rules and runs the plain-English scenario bench
(`mtg-judge bench`, which also runs on its own and reports answered / refused / wrong).
`mtg-judge coverage` reports how much of the card pool's rules text the Oracle parser models.
