# Situation language

The contract between the fuzzy front door (natural language in) and the exact
core (rules engine). Everything the engine reasons about is expressed in this
structure. The natural-language parser produces it, the engine consumes it, the
answer echoes it back so the user can see what was understood.

Design rules:

1. **Card names resolve before anything else.** A situation refers to cards by
   Oracle ID, never by free text. Name resolution is deterministic and happens
   against the card database. A parser cannot invent a card.
2. **Unknown is a first-class value.** Anything the user did not say is
   `null` (unknown), not a default. The engine asks about an unknown only when
   the answer depends on it.
3. **Small vocabulary.** A fixed list of event verbs and object attributes. If
   something cannot be expressed here, the system says so instead of guessing.
4. **Serializable and diffable.** Plain JSON. A situation plus the engine's
   answer is a test case.

## Top level

```json
{
  "players": [ Player ],
  "turn":    Turn,
  "objects": [ GameObject ],
  "stack":   [ StackItem ],
  "events":  [ Event ],
  "question": Question
}
```

## Player

```json
{ "id": "me", "name": "me", "life": 40, "poison": 0, "handSize": 3, "librarySize": 0, "commanderDamage": { "atraxa": 18 } }
```

`id` is a short handle used everywhere else (`me`, `opp`, `opp2`, or a name). `poison`, `handSize` and
`librarySize` are optional and only matter when a card asks (Ensnaring Bridge counts the hand; drawing from
an empty library loses the game, 704.5b).
`life` may be `null` for unknown. The first player listed is the person asking
unless stated otherwise.

## Turn

```json
{ "activePlayer": "opp", "phase": "combat", "step": "declare_blockers" }
```

Phases: `beginning`, `precombat_main`, `combat`, `postcombat_main`, `ending`.
Steps: `untap`, `upkeep`, `draw`, `beginning_of_combat`, `declare_attackers`,
`declare_blockers`, `combat_damage`, `end_of_combat`, `end`, `cleanup`.
Any field may be `null` (unknown).

## GameObject

A card or token in some zone.

```json
{
  "id": "rhystic",
  "card": { "oracleId": "…", "name": "Rhystic Study" },
  "zone": "battlefield",
  "controller": "me",
  "owner": "me",
  "tapped": false,
  "summoningSick": "unknown",
  "counters": { "+1/+1": 2 },
  "attachedTo": null,
  "faceDown": false,
  "token": false,
  "copyOf": null,
  "notes": []
}
```

Zones: `battlefield`, `hand`, `graveyard`, `library`, `exile`, `stack`,
`command`. Cards in hidden zones are listed only when the user mentioned them.
Every boolean may be `null` (unknown). `card` may be a plain name string or
`{ "name", "oracleId" }`.

`id` is a handle the user's text can refer back to ("the second Bear" becomes
`bear2`). The parser assigns them; the echo shows them.

## StackItem

```json
{
  "id": "s1",
  "kind": "spell",              // spell | activated | triggered
  "source": "rhystic",          // object id whose ability this is (abilities)
  "card": "Stifle",             // the card, for a spell
  "controller": "opp",
  "targets": [ "s0" ],          // references, see below
  "abilityIndex": 0             // which ability on the card, when it has several
}
```

The stack is listed bottom to top. Order matters and is part of the question.

### References

Wherever a target or object is named, a reference is one of:

| form | means |
|---|---|
| `bears` | the object with that id |
| `opp` | the player with that id |
| `s1` | the stack item with that id |
| `rhystic:trigger` | the topmost triggered ability on the stack whose source is object `rhystic` |
| `bolt:spell` | the spell on the stack whose card is object `bolt` |
| `Grizzly Bears` | a card name, when exactly one object in the situation has it |

References in events are resolved when the event is applied, so `rhystic:trigger`
in the second event means "the trigger that is on the stack at that moment".

## Event

What happens next, in order. The engine applies these to the described state.

| verb | fields |
|---|---|
| `cast` | `player`, `card` (or `object` for a card already in the situation), `targets`, `amount` (the value of X), `modes`, `payLife` (life the caster said they paid; paid only if the card's own cost doesn't already take it) |
| `activate` | `player`, `object`, `abilityIndex`, `targets`, `amount` (the value of X) |
| `trigger` | `object`, `abilityIndex`, `targets` (used when the user asserts a trigger happened) |
| `resolve` | (resolves the top of the stack) |
| `resolveAll` | (everyone passes until the stack is empty) |
| `pass` | (same as `resolve`) |
| `attack` | `player`, `object` (the attacker), `targets`: [player, planeswalker or battle id] |
| `attackAll` | `player`, `targets`: [defender] (every creature the player controls attacks) |
| `block` | `player`, `object` (the blocker), `targets`: [attacker id] |
| `choose` | `object`, `to` = `put:<objectId>` | Announces the card a permanent's next triggered ability will put onto the battlefield (Kaalia, Aether Vial as a trigger). |
| (player field) | `spellsThisTurn` on a player: spells they have already cast this turn, for storm-style counts ("I have cast four spells this turn"). |
| `ask` | `object`, `to` = `trigger`, `survive`, `die`, `tapped`, `pt`, `mana`, `activate`, `block`, `attack`, `damage`, `isCreature`, `spellCost` or `control` (with `player`); or `player`, `to` = `playerSurvive`, `playerDie`, `playerWin`, `playerDamage`, `playerLife`, `playerDraw` or `manaAvailable` | A question about that permanent or player, answered in the outcome once everything has resolved. `activate` says whether the permanent can use its abilities right now (summoning sickness, already tapped); `manaAvailable` lists every untapped mana source that player controls and the total; `playerLife` gives that player's life total once everything has resolved; `playerDraw` says how many cards that player drew; `isCreature` says whether the permanent is a creature at the moment (the Theros gods turn that off below their devotion threshold); `spellCost` gives a card's total cost with every tax and reduction on the battlefield applied. |
| `combatDamage` | (deals combat damage now; otherwise it's dealt after the last event) |
| `step` | `player` (the active player), `to`: `upkeep` / `draw` / `precombat_main` / `combat` / `end` (a step begins; its triggers fire) |
| `pay` | `player`, `to`: `yes` / `no` (whether the player pays the next "unless … pays" cost asked of them) |
| `draw` | `player`, `amount` (a player draws outside any effect; draw triggers see it) |
| `regenerate` | `object` (the permanent has a regeneration shield this turn) |
| `sacrifice` | `player`, `object` |
| `fight` | `object`, `targets`: [the other creature] (each deals damage equal to its power to the other, 701.14a) |

`activate` also accepts `to: "mana"` (the object's mana ability) or a loyalty cost such as `"+1"`.
An object's `card` may name a generic token: `"5/5 Zombie token"`, `"Treasure token"`, `"1/1 white Soldier creature token"`.

`cast` also accepts `to: "overload"` (cast for the overload cost, every "target" read as "each") and a
generic `card` such as `"a spell"`, `"a creature spell"` or `"an instant"` for spells whose identity
doesn't matter.
| `damage` | `source`, `target`, `amount` (used when the user describes damage as a given) |
| `enter` | `object` (a permanent enters, source unspecified) |
| `leave` | `object`, `to`: zone |
| `stateCheck` | (explicitly ask for state-based actions to be performed) |

Events are applied in order. A spell cast while something is on the stack is,
by construction, cast "in response". A target written as `a|b` is ambiguous
(the parser emits this for "it"); the judge takes the first candidate the spell
can legally target and says so in the assumptions.

## Question

```json
{ "kind": "whatHappens" }
{ "kind": "canDo",      "event": Event }
{ "kind": "doesTrigger", "object": "rhystic", "abilityIndex": 0 }
{ "kind": "characteristic", "object": "bear1", "which": "power" }
{ "kind": "legalTargets", "event": Event }
{ "kind": "whoControls", "object": "bear1" }
```

`whatHappens` is the default and the most common. The others let the parser
narrow a question so the answer stays short.

## Answer

The engine's output. Every step carries the rule that justified it.

```json
{
  "understood": [ "Players: me (40 life), opponent…", "Battlefield: …", "Event 1: …" ],
  "outcome": [ "me draws 1 card.", "Rhystic Study's triggered ability is countered." ],
  "trace": [
    { "text": "Stifle (top of the stack) starts to resolve.", "rules": ["117.4", "608.1"] },
    { "text": "Rhystic Study's triggered ability is countered: …", "rules": ["701.6a"] }
  ],
  "assumptions": [ "opponent does not pay {1} for Rhystic Study's triggered ability." ],
  "clarifications": [ "active player: … whose turn it is decides which resolves first (603.3b)." ],
  "unsupported": [ "Rampant Growth: Part of the spell's effect is not modeled: Search your library…" ],
  "citations": { "603.3": "Once an ability has triggered, …" }
}
```

`assumptions` lists the player choices the engine made to reach an outcome (it
always picks the option the text offers and says so). `clarifications` is how
the engine asks. `unsupported` is how it refuses. A wrong answer is the one
outcome the design is built to avoid: when in doubt, the engine says what it
cannot decide and cites what it can.

Run one with `mtg-judge judge situation.json`.
