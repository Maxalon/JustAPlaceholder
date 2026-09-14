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
   `unknown`, not a default. The engine asks about an unknown only when the
   answer depends on it.
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
{ "id": "me", "name": "me", "life": 40, "isActive": "unknown" }
```

`id` is a short handle used everywhere else (`me`, `opp`, `opp2`, or a name).
`life` may be `null` for unknown. The first player listed is the person asking
unless stated otherwise.

## Turn

```json
{ "activePlayer": "opp", "phase": "combat", "step": "declare_blockers" }
```

Phases: `beginning`, `precombat_main`, `combat`, `postcombat_main`, `ending`.
Steps: `untap`, `upkeep`, `draw`, `beginning_of_combat`, `declare_attackers`,
`declare_blockers`, `combat_damage`, `end_of_combat`, `end`, `cleanup`.
Any field may be `"unknown"`.

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
Every boolean may be `"unknown"`.

`id` is a handle the user's text can refer back to ("the second Bear" becomes
`bear2`). The parser assigns them; the echo shows them.

## StackItem

```json
{
  "id": "s1",
  "kind": "spell",              // spell | activated | triggered
  "source": "rhystic",          // object id, or card ref for a spell being cast
  "card": { "oracleId": "…", "name": "Stifle" },
  "controller": "opp",
  "targets": [ "s0" ],          // object ids, stack ids, or player ids
  "abilityIndex": 0,            // which ability on the card, when it has several
  "modes": [], "x": null
}
```

The stack is listed bottom to top. Order matters and is part of the question.

## Event

What happens next, in order. The engine applies these to the described state.

| verb | fields |
|---|---|
| `cast` | `player`, `card`, `targets`, `modes`, `x`, `alternativeCost` |
| `activate` | `player`, `object`, `abilityIndex`, `targets` |
| `trigger` | `object`, `abilityIndex` (used when the user asserts a trigger happened) |
| `resolve` | (resolves the top of the stack) |
| `resolveAll` | (everyone passes until the stack is empty) |
| `pass` | `player` |
| `attack` | `player`, `attackers`: [{ `object`, `defending`: player or planeswalker id }] |
| `block` | `player`, `blocks`: [{ `blocker`, `attacker` }] |
| `damage` | `source`, `target`, `amount` (used when the user describes damage as a given) |
| `enter` | `object` (a permanent enters, source unspecified) |
| `leave` | `object`, `to`: zone |
| `stateCheck` | (explicitly ask for state-based actions to be performed) |

Any event may carry `"inResponse": true` to mean "before the previous item
resolves", which is the default anyway when the stack is non-empty. Unknown
ordering between two events is an ambiguity the engine reports.

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
  "understood": Situation,          // the echo
  "outcome": "Nobody draws a card.",
  "trace": [
    { "step": "Stifle resolves.", "rules": ["608.2"] },
    { "step": "Rhystic Study's triggered ability is countered and removed from the stack.", "rules": ["603.3", "701.5a"] }
  ],
  "clarifications": [
    { "path": "objects[1].tapped", "why": "Rule 302.6: a tapped creature can't be declared as a blocker." }
  ],
  "unsupported": [],                 // things the engine could not model, with the card/rule involved
  "citations": { "603.3": "Once an ability has triggered, …" }
}
```

`clarifications` is how the engine asks. `unsupported` is how it refuses. A
wrong answer is the one outcome the design is built to avoid: when in doubt, the
engine says what it cannot decide and cites what it can.
