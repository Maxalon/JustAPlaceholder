package mtg.judge.situation

import mtg.judge.carddb.Card
import mtg.judge.carddb.CardRepo
import mtg.judge.carddb.Resolution
import mtg.judge.cr.RulesRepo
import mtg.judge.engine.CardDef
import mtg.judge.engine.Effect
import mtg.judge.engine.Engine
import mtg.judge.engine.GameObject
import mtg.judge.engine.GameState
import mtg.judge.engine.JudgeException
import mtg.judge.engine.Player
import mtg.judge.engine.Ref
import mtg.judge.engine.StackItem
import mtg.judge.engine.StackKind
import mtg.judge.engine.TriggeredAbility
import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.TargetSpec
import mtg.judge.engine.Zone
import mtg.judge.oracle.OracleParser

/** Turns a [Situation] into a game state, runs its events through the [Engine], and renders an [Answer]. */
class Judge(private val cards: CardRepo, private val rules: RulesRepo?) {

    /**
     * Counters other than loyalty, shown so the board the answer reasoned over can be checked against the one
     * that was described. A count read wrong is the failure that looks most like a correct answer.
     */
    private fun countersNote(o: mtg.judge.engine.GameObject): String {
        // +1/+1 and -1/-1 on a creature are already spelled out in its power and toughness.
        val inPt = if (o.def.isCreature && o.isOnBattlefield()) setOf("+1/+1", "-1/-1") else emptySet()
        val shown = o.counters.filterKeys { it != "loyalty" && it !in inPt }.filterValues { it > 0 }
        if (shown.isEmpty()) return ""
        return ", " + shown.entries.joinToString(", ") { (k, n) -> "$n $k counter${if (n == 1) "" else "s"}" }
    }

    fun answer(sit: Situation): Answer {
        val understood = mutableListOf<String>()
        val state = GameState(sit.players.map { ps -> Player(ps.id, ps.name, ps.life).also { it.poison = ps.poison ?: 0; it.handSize = ps.handSize; it.librarySize = ps.librarySize; it.commanderDamage.putAll(ps.commanderDamage); it.mana = ps.mana; ps.devotion.forEach { (c, n) -> colourChar(c)?.let { ch -> it.devotion[ch] = n } } } }, LinkedHashMap(), activePlayer = sit.turn.activePlayer, phase = sit.turn.phase, step = sit.turn.step).also { st ->
            st.turnNumber = sit.turn.number
            // "I have cast four spells this turn": storm-style counts start from what the situation said.
            sit.players.forEach { ps -> ps.spellsThisTurn?.let { n -> st.spellsThisTurn[ps.id] = n } }
        }
        val engine = Engine(state)

        for (o in sit.objects) {
            val def = cardDef(o.card, state) ?: continue
            state.add(GameObject(o.id, def, zone(o.zone), o.controller, o.owner ?: o.controller, o.tapped, o.summoningSick, o.counters.toMutableMap(), o.damage, o.token)).also {
                it.timestamp = state.tick(); it.attachedTo = o.attachedTo; it.commander = o.commander; it.commanderCasts = o.commanderCasts; it.chosenName = o.named
                o.keywords.forEach { kw -> it.tempKeywords += kw.lowercase() }
                o.pump?.let { pm -> Regex("""^([+-]?\d+)/([+-]?\d+)$""").matchEntire(pm)?.let { m -> it.pumps += m.groupValues[1].toInt() to m.groupValues[2].toInt() } }
                if (def.isPlaneswalker && it.isOnBattlefield() && !it.counters.containsKey("loyalty") && def.loyalty != null) it.counters["loyalty"] = def.loyalty
            }
        }
        for (s in sit.stack) {
            val kind = when (s.kind.lowercase()) { "triggered" -> StackKind.TRIGGERED; "activated" -> StackKind.ACTIVATED; else -> StackKind.SPELL }
            val source = s.source?.let { state.objects[it] } ?: s.card?.let { ref -> cardDef(ref, state)?.let { def -> state.add(GameObject(s.id ?: freshId(state, def.name), def, Zone.STACK, s.controller)) } }
            if (source == null) { state.unsupported += mtg.judge.engine.Unsupported("stack item", "Stack item ${s.id ?: ""} has neither a known source object nor a card."); continue }
            val effect = when (kind) {
                StackKind.SPELL -> source.def.spellEffect
                StackKind.TRIGGERED -> source.def.abilities.filterIsInstance<TriggeredAbility>().getOrNull(s.abilityIndex ?: 0)?.effect
                StackKind.ACTIVATED -> source.def.abilities.filterIsInstance<ActivatedAbility>().getOrNull(s.abilityIndex ?: 0)?.effect
            }
            val targets = s.targets.map { parseRef(it, state) }
            val item = StackItem(s.id ?: state.newStackId(), kind, s.controller, source, effect, targets, targets.filterIsInstance<Ref.Obj>().associate { it.id to state.obj(it.id).zone } + targets.filterIsInstance<Ref.Stack>().associate { it.id to Zone.STACK }, source.def.oracleText)
            state.stack += item
        }

        understood += "Players: " + state.players.joinToString(", ") { p ->
            val notes = listOfNotNull(p.life?.let { "$it life" }, p.poison.takeIf { it > 0 }?.let { "$it poison" },
                p.handSize?.let { "$it in hand" }, p.librarySize?.let { "$it in library" }, p.mana?.let { "$it mana" },
                state.spellsThisTurn[p.id]?.takeIf { it > 0 }?.let { "$it spell${if (it == 1) "" else "s"} cast this turn" })
            (if (p.you) "you" else p.name) + (if (notes.isEmpty()) "" else " (" + notes.joinToString(", ") + ")")
        } + (state.activePlayer?.let { "; it's ${state.player(it).possessive} turn" } ?: "; whose turn it is wasn't stated")
        state.objects.values.groupBy { it.zone }.forEach { (zone, objs) ->
            understood += "${if (zone == mtg.judge.engine.Zone.COMMAND) "Command zone" else zone.name.lowercase().replaceFirstChar { it.uppercase() }}: " + objs.joinToString(", ") { "${it.name} [${it.id}] (${state.player(it.controller).possessive}${if (it.def.isCreature && it.isOnBattlefield()) ", " + state.describePt(it) else ""}${if (it.tapped == true) ", tapped" else ""}${if (it.damage > 0) ", ${it.damage} damage" else ""}${it.attachedTo?.let { a -> ", attached to ${state.objects[a]?.name ?: a}" } ?: ""}${it.counters["loyalty"]?.let { l -> ", loyalty $l" } ?: ""}${countersNote(it)})" }
        }
        if (state.stack.isNotEmpty()) understood += "Stack (bottom to top): " + state.stack.joinToString(", ") { "${it.describe} [${it.id}]" + (if (it.targets.isNotEmpty()) " targeting " + it.targets.joinToString(" & ") { t -> state.nameOf(t) } else "") }

        // Rules text on the described permanents that the engine can't model is said up front, so a silent "nothing changes" is never a lie.
        for (o in state.objects.values.filter { it.isOnBattlefield() }) {
            val unparsed = o.def.abilities.filterIsInstance<mtg.judge.engine.UnparsedAbility>().map { it.text }.toMutableList()
            // A trigger the parser couldn't read is as invisible as text it couldn't read at all: it can never fire,
            // so the answer would say "nothing changes" while the card sat there doing its thing.
            for (a in o.def.abilities) if (a is mtg.judge.engine.TriggeredAbility && a.trigger is mtg.judge.engine.Trigger.Unknown) unparsed += a.text
            if (unparsed.isNotEmpty()) state.unsupported += mtg.judge.engine.Unsupported(o.name, "Rules text not modeled: " + unparsed.distinct().joinToString(" | "))
        }
        engine.narrateLandTypeSetters()
        engine.narratePainter()
        engine.emptyGraveyardsUnderReplacement()
        // The described state may already call for state-based actions (a 1/1 under an opposing Elesh Norn).
        engine.stateBasedActions()
        var attackBatchEnd = -1
        val deferredAsks = mutableListOf<Pair<Int, EventSpec>>()
        var lastStep: EventSpec? = null
        for ((i, e) in sit.events.withIndex()) {
            if (e.verb == "ask") { deferredAsks += i to e; continue }   // answered once combat damage has been dealt
            if (e.verb == "step") { if (lastStep?.let { it.to == e.to && it.player == e.player } == true) continue; lastStep = e } else if (e.verb != "resolveAll") lastStep = null
            // Consecutive attack events are one declaration: "attacks alone", exalted and "whenever you attack" need the whole set.
            if ((e.verb == "attack" || e.verb == "attackAll") && i > attackBatchEnd) {
                attackBatchEnd = i; while (attackBatchEnd + 1 < sit.events.size && sit.events[attackBatchEnd + 1].verb in setOf("attack", "attackAll") && sit.events[attackBatchEnd + 1].player == e.player) attackBatchEnd++
                engine.beginDeclaringAttackers()
            }
            try {
                understood += "Event ${i + 1}: " + describeEvent(e, state)
                apply(e, state, engine)
                if (i == attackBatchEnd) engine.finishDeclaringAttackers()
            } catch (ex: JudgeException) {
                state.unsupported += mtg.judge.engine.Unsupported("event ${i + 1} (${e.verb})", ex.message ?: "failed")
            }
        }
        if (sit.events.isEmpty() && state.stack.isNotEmpty()) { understood += "No events given; resolving the stack."; engine.resolveAll() }
        if (state.objects.values.any { it.attacking != null } && !state.combatDamageDealt) {
            understood += "Combat damage is dealt after the described actions."
            engine.resolveAll(); engine.combatDamage()
            if (state.stack.isNotEmpty()) engine.resolveAll()   // abilities that triggered on combat damage or deaths
        }
        for ((i, e) in deferredAsks) { try { understood += "Event ${i + 1}: " + describeEvent(e, state); apply(e, state, engine) } catch (ex: JudgeException) { state.unsupported += mtg.judge.engine.Unsupported("event ${i + 1} (ask)", ex.message ?: "failed") } }

        // Life totals that changed, as a single line each (individual damage lines may repeat and collapse).
        for (p in state.players) {
            val start = sit.players.firstOrNull { it.id == p.id }?.life
            if (start != null && p.life != null && p.life != start) state.outcomes += "${p.subject} ${p.v("goes", "go")} from $start to ${p.life} life."
        }
        val cited = state.trace.steps.flatMap { it.rules }.distinct()
        val citations = cited.associateWith { n -> rules?.rule(n)?.text ?: "" }.filterValues { it.isNotEmpty() }
        return Answer(
            outcome = if (state.outcomes.isEmpty()) listOf("Nothing changes.") else state.outcomes.groupingBy { it }.eachCount().let { counts -> state.outcomes.distinct().map { o -> if (counts.getValue(o) > 1) "$o (×${counts.getValue(o)})" else o } },
            trace = state.trace.steps.map { TraceLine(it.text, it.rules) },
            assumptions = state.assumptions.distinct(),
            clarifications = state.clarifications.distinct().map { "${it.about}: ${it.why}" },
            unsupported = state.unsupported.distinct().map { "${it.what}: ${it.detail}" },
            citations = citations,
            understood = understood,
        )
    }

    private fun apply(e: EventSpec, state: GameState, engine: Engine) {
        val targets = e.targets.map { parseRef(it, state) }
        when (e.verb.lowercase()) {
            "cast" -> {
                val player = e.player ?: state.players.first().id
                val existing = e.obj?.let { state.objects[it] }
                val def = existing?.def ?: cardDef(e.card ?: throw JudgeException("cast needs a card"), state) ?: return
                val modes = if (e.modes.isEmpty() && e.to?.startsWith("mode:") == true) {
                    val words = e.to.removePrefix("mode:").lowercase().split("|").filter { it.isNotEmpty() }
                    val texts = (def.spellEffect as? Effect.Modal)?.modeTexts ?: emptyList()
                    words.mapNotNull { w -> matchMode(w, texts, def.name)?.plus(1) }
                } else e.modes
                engine.cast(player, def, disambiguate(e.targets, def.spellEffect?.targets() ?: emptyList(), player, state, engine), existing?.id, modes, overload = e.to == "overload", x = e.amount, kicked = e.to == "kicked", evoked = e.to == "evoke", flashback = e.to == "flashback", choice = e.to?.takeIf { it.startsWith("copy:") } ?: e.to?.takeIf { it.startsWith("copytarget:") }?.removePrefix("copytarget:") ?: e.to?.takeIf { it.startsWith("name:") }?.removePrefix("name:") ?: e.to?.takeIf { it == "revolt" || it == "spellmastery" })
            }
            "draw" -> engine.draw(e.player ?: throw JudgeException("draw needs a player"), e.amount ?: 1)
            "sacrifice" -> {
                val objId = e.obj ?: throw JudgeException("sacrifice needs an object"); val o = state.obj(objId)
                // "I sacrifice Sakura-Tribe Elder": sacrificing a permanent that has a "Sacrifice this: …" ability means activating it.
                val sacAbility = o.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> Regex("""(?i)\bsacrifice (?:~|this\b|${Regex.escape(o.name)}\b)""").containsMatchIn(a.cost) }
                val gone = engine.printedAbilitiesGone(o)
                if (sacAbility >= 0 && o.isOnBattlefield() && gone == null) engine.activate(e.player ?: o.controller, objId, sacAbility, targets)
                else {
                    if (sacAbility >= 0 && gone != null) state.trace.step("${o.name} has no ability of its own under $gone, so sacrificing it is just that: it goes to the graveyard and nothing else happens.", "613.1f", "701.21a")
                    engine.sacrifice(e.player ?: o.controller, objId)
                }
            }
            "discard" -> { val objId = e.obj ?: throw JudgeException("discard needs an object"); engine.discard(e.player ?: state.obj(objId).owner, objId) }
            "untap" -> engine.untapObject(e.obj ?: throw JudgeException("untap needs an object"))
            "mill" -> engine.millCards(e.player ?: throw JudgeException("mill needs a player"), e.amount ?: 1)
            "discardcount" -> engine.discardCount(e.player ?: throw JudgeException("discard needs a player"), e.amount ?: 1)
            "poison" -> engine.addPoison(e.player ?: throw JudgeException("poison needs a player"), e.amount ?: 1)
            "gainlife" -> engine.gainLifeEvent(e.player ?: throw JudgeException("gainLife needs a player"), e.amount ?: 1)
            "loselife" -> engine.loseLifeEvent(e.player ?: throw JudgeException("loseLife needs a player"), e.amount ?: 1)
            "regenerate" -> { val o = state.obj(e.obj ?: throw JudgeException("regenerate needs an object")); state.shields += mtg.judge.engine.Shield(mtg.judge.engine.Replacement.Regenerate, o.id, null, 1, "a regeneration effect") }
            "pay" -> {
                val who = e.player ?: throw JudgeException("pay needs a player")
                if (e.to == "no") { state.willPay.remove(who); state.wontPay += who } else { state.wontPay.remove(who); state.willPay += who }
            }
            "activate" -> {
                val objId = e.obj ?: throw JudgeException("activate needs an object")
                val obj = state.obj(objId)
                // "sacrifice a Forest to Gitrog": nothing to activate on it, so it's just a sacrifice (its triggers still see it).
                e.to?.takeIf { it.startsWith("sacrifice:") }?.removePrefix("sacrifice:")?.let { sacId ->
                    if (obj.def.abilities.filterIsInstance<ActivatedAbility>().none { a -> a.cost.contains("sacrifice", true) }) { engine.sacrifice(e.player ?: state.obj(sacId).controller, sacId); return }
                }
                // "+1" / "-3" names a loyalty ability by its cost.
                val idx = e.abilityIndex
                    ?: e.to?.takeIf { it == "mana" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.effect is Effect.AddMana || a.effect is Effect.AddManaPer || (a.effect as? Effect.Seq)?.effects?.any { it is Effect.AddMana || it is Effect.AddManaPer } == true }.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "ultimate" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().withIndex().filter { (_, a) -> Regex("""^[\u2212-]\d+$""").matches(a.cost) }.minByOrNull { (_, a) -> a.cost.replace('\u2212', '-').toInt() }?.index }
                    ?: e.to?.let { cost -> obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { it.cost.replace('\u2212', '-') == cost.replace('\u2212', '-') }.takeIf { it >= 0 } }
                engine.activate(e.player ?: obj.controller, objId, idx, targets, choice = e.to?.takeIf { it.startsWith("color:") || it.startsWith("put:") }?.substringAfter(':') ?: e.to?.takeIf { it.startsWith("sacrifice:") }, x = e.amount)
            }
            "trigger" -> engine.assertTrigger(e.obj ?: throw JudgeException("trigger needs an object"), e.abilityIndex, targets)
            "choose" -> { val objId = e.obj ?: throw JudgeException("choose needs an object"); state.pendingChoices[objId] = e.to?.substringAfter(':') ?: throw JudgeException("choose needs a choice") }
            "resolve" -> engine.resolveTop()
            "resolveall" -> engine.resolveAll()
            "ask" -> {
                if (e.to == "playerDamage") { val p = state.player(e.player ?: throw JudgeException("ask needs a player")); val name = if (p.you) "you" else p.name; val total = state.trace.steps.sumOf { st -> Regex("""deals (\d+) (?:combat )?damage to ${Regex.escape(name)}\b""").findAll(st.text).sumOf { it.groupValues[1].toInt() } }; val prevented = state.trace.steps.any { it.text.contains("to $name") && it.text.contains("prevented") }; state.outcomes += if (total > 0) "Yes: $name ${p.v("takes", "take")} $total damage in all." else "No: $name ${p.v("takes", "take")} no damage${if (prevented) " (it's prevented)" else ""}."; return }
                if (e.to == "manaAvailable") { state.outcomes += engine.manaAvailable(e.player ?: throw JudgeException("ask needs a player")); return }
                if (e.to == "spellCost") { state.outcomes += engine.spellCost(e.obj ?: throw JudgeException("ask needs an object")); return }
                if (e.to == "playerSurvive" || e.to == "playerDie" || e.to == "playerWin") { state.outcomes += playerAnswer(e.to, state.player(e.player ?: throw JudgeException("ask needs a player")), state); return }
                val o = generateSequence(state.obj(e.obj ?: throw JudgeException("ask needs an object"))) { it.successor?.let { id -> state.objects[id] } }.last()
                // "does my Serra Angel still have flying?": whether it has that keyword once everything is done,
                // and if it doesn't, what took it away.
                e.to?.removePrefix("keyword:")?.takeIf { e.to!!.startsWith("keyword:") }?.let { kw ->
                    val has = if (kw.startsWith("protection from ")) kw.removePrefix("protection from ") in state.protections(o) else state.hasKeyword(o, kw)
                    val stripped = engine.printedAbilitiesGone(o)
                    state.outcomes += when {
                        !o.isOnBattlefield() -> "${o.name} isn't on the battlefield."
                        has -> "Yes: ${o.name} has $kw."
                        stripped != null -> "No: ${o.name} has no abilities under $stripped, so it doesn't have $kw."
                        else -> "No: ${o.name} doesn't have $kw."
                    }
                    return
                }
                when (e.to) {
                    "trigger" -> {
                        val muted = state.trace.steps.firstOrNull { it.text.contains("doesn't cause any abilities to trigger") }?.text?.substringBefore(" is on the battlefield")
                        val stripped = engine.printedAbilitiesGone(o)
                        state.outcomes += when {
                            state.trace.steps.any { it.text.startsWith("${o.name}'s ability triggers") || it.text.startsWith("${o.name}'s evoke ability triggers") } -> "Yes: ${o.name}'s ability triggered."
                            muted != null -> "No: $muted stops it. ${o.name} entering doesn't cause any ability to trigger, its own included."
                            stripped != null -> "No: ${o.name} has no abilities under $stripped, so there is nothing to trigger."
                            else -> "No: ${o.name}'s ability didn't trigger (nothing that happened matched its trigger condition)."
                        }
                    }
                    "block", "attack" -> state.outcomes += when {
                        // Defender and being tapped stop an attack just as surely as a "can't attack" effect does;
                        // without these "which of my creatures can attack?" answered yes for a Wall of Omens.
                        e.to == "attack" && state.hasKeyword(o, "defender") -> "No: ${o.name} has defender, so it can't attack (702.3b)."
                        // Attacking taps it, so a creature that is already attacking is tapped and could attack.
                        e.to == "attack" && o.tapped == true && o.attacking == null -> "No: ${o.name} is tapped, so it can't be declared as an attacker (508.1a)."
                        else -> engine.cantWhy(o.id, e.to)?.let { why -> "No: ${o.name} can't ${e.to} (${if (why == o.name) "its own ability" else why} says so)." }
                            ?: if (o.isOnBattlefield()) "Yes: ${o.name} can ${e.to}${if (e.to == "attack" && o.summoningSick == true && !o.has("haste")) ", but not this turn: it's summoning sick (302.6)" else ""}." else "No: ${o.name} isn't on the battlefield."
                    }
                    "damage" -> {
                        val victim = e.targets.firstOrNull()?.let { parseRef(it, state) as? mtg.judge.engine.Ref.Player }?.let { state.player(it.id) }
                        val who = victim?.let { if (it.you) "you" else it.name } ?: "opponent"
                        val hit = state.trace.steps.any { Regex("""^${Regex.escape(o.name)} deals \d+ (?:combat )?damage to ${Regex.escape(who)}\b""").containsMatchIn(it.text) }
                        val blocked = state.trace.steps.any { it.text.contains("blocks ${o.name}") }
                        state.outcomes += if (hit) "Yes: ${o.name} dealt damage to $who." else "No: ${o.name} dealt no damage to $who${if (blocked) " (it was blocked, and a blocked creature stays blocked even if its blocker leaves combat; without trample it assigns no damage to the player, 509.1h)" else ""}."
                    }
                    "playerSurvive", "playerDie", "playerWin" -> state.outcomes += playerAnswer(e.to, state.player(e.player ?: o.controller), state)
                    "pt" -> state.outcomes += when {
                        !o.isOnBattlefield() -> "${o.name} isn't on the battlefield."
                        state.notACreatureBecause(o) != null -> "${o.name} isn't a creature right now (${state.player(o.controller).possessive} ${state.notACreatureBecause(o)}), so it has no power or toughness. It's still an enchantment on the battlefield and keeps its other abilities."
                        o.def.isCreature || o.animatedAs != null -> "${o.name} is ${state.describePt(o)}."
                        else -> "${o.name} isn't a creature."
                    }
                    "isCreature" -> state.outcomes += state.notACreatureBecause(o)?.let { why -> "No: ${o.name} isn't a creature — ${state.player(o.controller).possessive} $why. It's still an enchantment on the battlefield, it keeps its other abilities, and it can't attack, block, or be targeted by anything that needs a creature." }
                        ?: if (!o.isOnBattlefield()) "${o.name} isn't on the battlefield." else if (o.def.isCreature || o.animatedAs != null) "Yes: ${o.name} is a creature (${state.describePt(o)})${if (o.animatedAs != null) " until end of turn" else ""}." else "No: ${o.name} isn't a creature; it's ${o.def.types.joinToString(" ").lowercase()}."
                    "control" -> {
                        // "Do I get it back?": the turn is played out to its cleanup step first, once combat is done.
                        if (o.controlRevertsTo != null) engine.beginStep("cleanup", e.targets.firstOrNull()?.takeIf { t -> state.players.any { it.id == t } } ?: state.activePlayer ?: o.controller)
                        val p = state.player(e.player ?: "me"); state.outcomes += if (o.controller == p.id) "Yes: ${o.name} is under ${p.possessive} control." else "No: ${o.name} is under ${state.player(o.controller).possessive} control${o.controlRevertsTo?.let { r -> " until end of turn (it goes back to ${state.player(r).possessive} at cleanup)" } ?: ""}." }
                    "mana" -> state.outcomes += engine.manaOptions(o.id)
                    "activate" -> {
                        val tapAbilities = o.def.abilities.filterIsInstance<mtg.judge.engine.ActivatedAbility>().filter { it.cost.contains("{T}") }
                        val any = o.def.abilities.filterIsInstance<mtg.judge.engine.ActivatedAbility>()
                        state.outcomes += when {
                            !o.isOnBattlefield() -> "No: ${o.name} isn\'t on the battlefield, so its abilities can\'t be activated."
                            any.isEmpty() -> "${o.name} has no activated abilities."
                            o.tapped == true && tapAbilities.isNotEmpty() && tapAbilities.size == any.size -> "No: ${o.name} is already tapped, and every one of its abilities costs {T}."
                            tapAbilities.isNotEmpty() && o.def.isCreature && o.summoningSick == true && !state.hasKeyword(o, "haste") ->
                                "No: ${o.name} came under ${state.player(o.controller).possessive} control this turn and doesn\'t have haste, so its {T} ability can\'t be activated yet (302.6).${if (tapAbilities.size < any.size) " Its abilities without {T} in the cost still can be." else ""}"
                            tapAbilities.isNotEmpty() && o.def.isCreature && o.summoningSick == true ->
                                "Yes: ${o.name} has haste, so it can use its {T} ability the turn it came under ${state.player(o.controller).possessive} control (702.10b)."
                            tapAbilities.isNotEmpty() && o.def.isCreature && o.summoningSick == null ->
                                "Yes, if it has been under ${state.player(o.controller).possessive} control since the turn began; a {T} ability of a creature needs that or haste (302.6)."
                            else -> "Yes: ${o.name} can activate ${if (any.size == 1) "its ability" else "its abilities"}, mana permitting."
                        }
                    }
                    "blocked" -> state.outcomes += run {
                        val blockers = state.objects.values.filter { it.blocking == o.id && it.isOnBattlefield() }
                        when {
                            o.attacking == null && !o.wasBlocked -> "${o.name} isn't attacking, so nothing is blocking it."
                            blockers.isNotEmpty() -> "Yes: ${o.name} is blocked by ${blockers.joinToString(" and ") { it.name }}."
                            o.wasBlocked -> "Yes: ${o.name} is blocked, even though nothing is blocking it now — a creature stays blocked once blockers are declared, so without trample it assigns no combat damage at all (509.1h)."
                            else -> "No: ${o.name} is unblocked."
                        }
                    }
                    "targetable" -> state.outcomes += run {
                        val prots = state.protections(o)
                        val ward = state.wardCost(o)
                        when {
                            !o.isOnBattlefield() -> "${o.name} isn't on the battlefield."
                            state.hasKeyword(o, "shroud") -> "No: ${o.name} has shroud, so it can't be the target of any spell or ability, its controller's included (702.18a)."
                            state.hasKeyword(o, "hexproof") -> "Not by an opponent: ${o.name} has hexproof, so its controller's opponents can't target it; ${state.player(o.controller).possessive} own spells and abilities still can (702.11b)."
                            prots.isNotEmpty() -> "Only by something it isn't protected from: ${o.name} has protection from ${prots.joinToString(" and ")} (702.16b)."
                            ward != null -> "Yes, but at a price: ${o.name} has ward, so targeting it by an opponent triggers \"counter that spell or ability unless its controller pays $ward\" (702.21a)."
                            else -> "Yes: nothing stops ${o.name} being targeted."
                        }
                    }
                    "summoningSick" -> state.outcomes += run {
                        val who = state.player(o.controller).possessive
                        when {
                            !o.isOnBattlefield() -> "${o.name} isn't on the battlefield."
                            !o.def.isCreature && o.animatedAs == null -> "${o.name} isn't a creature, so summoning sickness doesn't affect it (302.6 is about creatures)."
                            o.summoningSick == true && state.hasKeyword(o, "haste") -> "Yes, but it doesn't matter: ${o.name} came under $who control this turn and has haste, so it can attack and use its {T} abilities anyway (702.10b)."
                            o.summoningSick == true -> "Yes: ${o.name} came under $who control this turn and doesn't have haste, so it can't attack or use a {T} ability (302.6)."
                            o.summoningSick == false -> "No: ${o.name} has been under $who control since the turn began."
                            else -> "It wasn't said when ${o.name} came under $who control. If it was this turn it's summoning sick and can't attack or use a {T} ability without haste (302.6); if it was earlier it isn't."
                        }
                    }
                    "tapped" -> state.outcomes += if (o.tapped == true) "${o.name} is tapped." else "${o.name} is untapped${if (state.hasKeyword(o, "vigilance") && state.trace.steps.any { it.text.startsWith("${o.name} attacks") || it.text.contains("attack with ${o.name}") }) " (vigilance: attacking didn't tap it)" else ""}."
                    "survive", "die" -> {
                        val where = when (o.zone) { mtg.judge.engine.Zone.GRAVEYARD -> "the graveyard"; mtg.judge.engine.Zone.EXILE -> "exile"; mtg.judge.engine.Zone.HAND -> "its owner's hand"; mtg.judge.engine.Zone.LIBRARY -> "its owner's library"; mtg.judge.engine.Zone.COMMAND -> "the command zone"; else -> o.zone.name.lowercase() }
                        state.outcomes += if (e.to == "die") { if (o.isOnBattlefield()) "No: ${o.name} is still on the battlefield." else if (o.zone == mtg.judge.engine.Zone.GRAVEYARD) "Yes: ${o.name} died (it's in the graveyard)." else "No: ${o.name} didn't die, but it left the battlefield; it's in $where." }
                        else { if (o.isOnBattlefield()) "Yes: ${o.name} is still on the battlefield." else "No: ${o.name} is in $where." }
                    }
                    else -> {}
                }
            }
            "pass" -> engine.resolveTop()
            "enter" -> engine.enter(e.obj ?: throw JudgeException("enter needs an object"), e.to)
            "leave" -> engine.leave(e.obj ?: throw JudgeException("leave needs an object"), zone(e.to ?: "graveyard"))
            "damage" -> engine.dealDamage(e.source?.let { state.objects[it]?.name } ?: e.source ?: "A source", targets.firstOrNull() ?: throw JudgeException("damage needs a target"), e.amount ?: throw JudgeException("damage needs an amount"))
            "statecheck" -> engine.stateBasedActions()
            "attack" -> { val objId = e.obj ?: throw JudgeException("attack needs an object"); engine.declareAttacker(e.player ?: state.obj(objId).controller, objId, targets.firstOrNull() ?: Ref.Player(state.opponentsOf(state.obj(objId).controller).firstOrNull()?.id ?: throw JudgeException("no defending player"))) }
            "block" -> { val objId = e.obj ?: throw JudgeException("block needs an object"); val att = (targets.firstOrNull() as? Ref.Obj)?.id ?: state.objects.values.lastOrNull { it.attacking != null }?.id ?: throw JudgeException("block needs the attacker"); engine.declareBlocker(e.player ?: state.obj(objId).controller, objId, att) }
            "attackall" -> { val who = e.player ?: state.players.first().id; val def = targets.firstOrNull() ?: Ref.Player(state.opponentsOf(who).firstOrNull()?.id ?: throw JudgeException("no defending player")); val cs = state.objects.values.filter { it.controller == who && it.isOnBattlefield() && it.def.isCreature }; if (cs.isEmpty()) state.unsupported += mtg.judge.engine.Unsupported("attack", "No creatures of ${state.player(who).possessive} were described."); cs.forEach { engine.declareAttacker(who, it.id, def) } }
            "combatdamage" -> engine.combatDamage()
            "step", "beginstep" -> engine.beginStep((e.to ?: "upkeep").lowercase(), e.player ?: state.activePlayer ?: state.players.first().id)
            else -> throw JudgeException("Unknown event verb '${e.verb}'")
        }
    }

    private fun describeEvent(e: EventSpec, state: GameState): String {
        val who = e.player?.let { state.players.firstOrNull { p -> p.id == it }?.let { p -> if (p.you) "you" else p.name } ?: it }
        val tg = if (e.targets.isEmpty()) "" else " targeting " + e.targets.joinToString(" and ") { runCatching { state.nameOf(parseRef(it, state)) }.getOrDefault(it) }
        return when (e.verb.lowercase()) {
            "cast" -> { val land = e.card?.let { c -> runCatching { cardDef(c, state) }.getOrNull() }?.let { "Land" in it.types && !it.isInstantOrSorcery } == true
                "${who ?: "you"} ${if (land) (if (who == null || who == "you") "play" else "plays") else (if (who == null || who == "you") "cast" else "casts")} ${e.card ?: state.objects[e.obj]?.name ?: e.obj}${if (e.to == "overload") " overloaded" else if (e.to == "kicked") " kicked" else if (e.to == "evoke") " for its evoke cost" else ""}$tg" }
            "activate" -> "${who ?: "controller"} ${if (who == "you") "activate" else "activates"} ${state.objects[e.obj]?.name ?: e.obj}${e.to?.takeIf { e.abilityIndex == null && (it == "ultimate" || Regex("""^[+\u2212-]?\d+$""").matches(it)) }?.let { " ($it)" } ?: ""}$tg"
            "discard" -> "${who ?: "you"} ${if (who == null || who == "you") "discard" else "discards"} ${state.objects[e.obj]?.name ?: e.obj}"
            "untap" -> "${state.objects[e.obj]?.name ?: e.obj} untaps"
            "mill" -> "${who ?: "the player"} ${if (who == "you") "mill" else "mills"} ${e.amount ?: 1} card${if ((e.amount ?: 1) == 1) "" else "s"}"
            "discardcount" -> "${who ?: "the player"} ${if (who == "you") "discard" else "discards"} ${e.amount ?: 1} card${if ((e.amount ?: 1) == 1) "" else "s"}"
            "poison" -> "${who ?: "the player"} ${if (who == "you") "get" else "gets"} ${e.amount ?: 1} poison counter${if ((e.amount ?: 1) == 1) "" else "s"}"
            "trigger" -> "${state.objects[e.obj]?.name ?: e.obj}'s ability triggers$tg"
            "choose" -> "${who ?: "controller"} ${if (who == "you") "choose" else "chooses"} ${e.to?.substringAfter(':')?.let { state.objects[it]?.name ?: it } ?: "?"} for ${state.objects[e.obj]?.name ?: e.obj}'s ability"
            "regenerate" -> "${state.objects[e.obj]?.name ?: e.obj} has a regeneration shield"
            "sacrifice" -> "${who ?: "controller"} ${if (who == "you") "sacrifice" else "sacrifices"} ${state.objects[e.obj]?.name ?: e.obj}"
            "gainlife" -> "${who ?: "the player"} ${if (who == "you") "gain" else "gains"} ${e.amount ?: 1} life"
            "loselife" -> "${who ?: "the player"} ${if (who == "you") "lose" else "loses"} ${e.amount ?: 1} life"
            "draw" -> "${who ?: "the player"} ${if (who == "you") "draw" else "draws"} ${e.amount ?: 1} card${if ((e.amount ?: 1) > 1) "s" else ""}"
            "pay" -> "${who ?: "the player"} ${if (e.to == "no") "${if (who == "you") "don't" else "doesn't"} pay" else "${if (who == "you") "pay" else "pays"}"}"
            "resolve", "pass" -> "the top of the stack resolves"
            "resolveall" -> "everything on the stack resolves"
            "ask" -> if (e.to == "playerSurvive") "question: ${if (who == "you") "do you" else "does $who"} survive?" else if (e.to == "playerDie") "question: ${if (who == "you") "do you" else "does $who"} lose?" else if (e.to == "playerWin") "question: ${if (who == "you") "do you" else "does $who"} win?" else if (e.to == "playerDamage") "question: ${if (who == "you") "do you" else "does $who"} take damage?" else "question: ${if (e.to == "block" || e.to == "attack") "can" else "does"} ${state.objects[e.obj]?.name ?: e.obj} ${if (e.to == "damage") "deal damage to ${e.targets.firstOrNull()?.let { t -> state.players.firstOrNull { it.id == t }?.let { if (it.you) "you" else it.name } } ?: "the player"}" else e.to}?"
            "enter" -> "${state.objects[e.obj]?.name ?: e.obj} enters the battlefield"
            "leave" -> "${state.objects[e.obj]?.name ?: e.obj} goes to ${e.to}"
            "damage" -> "${e.source} deals ${e.amount} damage${tg.replace(" targeting ", " to ")}"
            "attack" -> "${who ?: "you"} attack${if (who == null || who == "you") "" else "s"} with ${state.objects[e.obj]?.name ?: e.obj}$tg"
            "attackall" -> "${who ?: "you"} attack${if (who == null || who == "you") "" else "s"} with every creature$tg"
            "block" -> "${who ?: "opponent"} block${if (who == null || who == "you") "" else "s"} with ${state.objects[e.obj]?.name ?: e.obj}$tg"
            "combatdamage" -> "combat damage is dealt"
            "step", "beginstep" -> "${when (who) { null -> "the active player's"; "you" -> "your"; else -> "$who's" }} ${when (val st = (e.to ?: "upkeep").lowercase()) { "end" -> "end step"; "draw" -> "draw step"; "cleanup" -> "cleanup step"; "combat" -> "combat phase"; "untap" -> "untap step"; else -> st.replace('_', ' ') }} begins"
            else -> e.verb
        }
    }

    /** `rhystic` (object), `opp` (player), `s1` (stack), `rhystic:trigger` / `bolt:spell` (stack item by source). */
    /**
     * A target written as "a|b" is ambiguous ("it" after two things were mentioned): take the first candidate the
     * spell could legally target, else the first candidate.
     */
    /**
     * Match a mode the asker spelled out ("deal 2 damage to any target") against the card's own mode lines.
     * Oracle text names the card where a player says "deal", and writes "deals" where they write "deal", so a
     * plain substring check misses; the match is on shared content words instead, and stays unmatched rather
     * than guessing when nothing clearly fits.
     */
    private fun matchMode(phrase: String, texts: List<String>, cardName: String): Int? {
        fun words(t: String) = Regex("""[a-z0-9]+""").findAll(t.lowercase().replace(cardName.lowercase(), " "))
            .map { it.value.removeSuffix("s") }.filter { it.length > 1 && it !in setOf("the", "a", "an", "to", "of", "it", "that", "this", "your", "you", "their", "from", "on", "in", "any") }.toList()
        val want = words(phrase)
        if (want.isEmpty()) return null
        texts.indexOfFirst { it.lowercase().contains(phrase) }.takeIf { it >= 0 }?.let { return it }
        val scored = texts.mapIndexed { i, t -> i to words(t).toSet().let { have -> want.count { w -> w in have }.toDouble() / want.size } }
        val best = scored.maxByOrNull { it.second } ?: return null
        // A clear winner only: a tie means the asker's words fit two modes equally well, and guessing would be worse than asking.
        if (best.second < 0.6 || scored.count { it.second == best.second } > 1) return null
        return best.first
    }

    private fun disambiguate(targets: List<String>, specs: List<TargetSpec>, controller: String, state: GameState, engine: Engine): List<Ref> =
        targets.mapIndexed { i, t ->
            if ('|' !in t) parseRef(t, state)
            else {
                val candidates = t.split('|').mapNotNull { c -> runCatching { parseRef(c, state) }.getOrNull() }
                val spec = specs.getOrNull(i)
                val pick = candidates.firstOrNull { spec == null || engine.filterMatches(spec.filter, it, controller) } ?: candidates.first()
                if (candidates.size > 1) state.assumptions += "\"It\" was read as ${state.nameOf(pick)} (the first of ${candidates.joinToString(", ") { state.nameOf(it) }} that fits \"${spec?.raw ?: "the target"}\")."
                pick
            }
        }

    private fun parseRef(s: String, state: GameState): Ref {
        if (state.objects.containsKey(s)) return Ref.Obj(s)
        if (state.players.any { it.id == s }) return Ref.Player(s)
        if (state.stackItem(s) != null) return Ref.Stack(s)
        if (':' in s) {
            val (objId, kind) = s.split(':', limit = 2)
            val wanted = when (kind.lowercase()) { "trigger", "triggered" -> StackKind.TRIGGERED; "ability", "activated" -> StackKind.ACTIVATED; else -> StackKind.SPELL }
            val item = state.stack.lastOrNull { it.source.id == objId && it.kind == wanted } ?: throw JudgeException("No ${kind} from '$objId' is on the stack")
            return Ref.Stack(item.id)
        }
        // A card name: is that card on the stack or battlefield?
        val byName = state.objects.values.filter { it.name.equals(s, ignoreCase = true) }
        if (byName.size == 1) return Ref.Obj(byName.first().id)
        throw JudgeException("Can't tell what '$s' refers to (not an object id, player id, stack id, or id:trigger)")
    }

    private fun cardDef(ref: CardRef, state: GameState): CardDef? {
        ref.name?.let { n -> if (ref.oracleId == null) (mtg.judge.engine.Generic.token(n) ?: mtg.judge.engine.Generic.spell(n))?.let { return it } }
        val card: Card? = ref.oracleId?.let { cards.byOracleId(it) } ?: ref.name?.let { n ->
            val r = cards.resolve(n)
            val m = r.best
            if (m != null && m.how == Resolution.How.FUZZY) state.assumptions += "\"$n\" taken to mean ${m.card.name}."
            if (m != null && r.ambiguous) state.clarifications += mtg.judge.engine.Clarification("card name", "\"$n\" could be ${r.matches.map { it.card.name }.distinct().joinToString(" or ")}; using ${m.card.name}.")
            m?.card
        }
        if (card == null) { state.unsupported += mtg.judge.engine.Unsupported("card", "Unknown card: $ref"); return null }
        return toDef(card)
    }

    private fun zone(s: String) = runCatching { Zone.valueOf(s.uppercase()) }.getOrElse { throw JudgeException("Unknown zone '$s'") }
    private fun freshId(state: GameState, name: String): String { val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var id = base; var i = 2; while (state.objects.containsKey(id)) id = "${base}_${i++}"; return id }

    /** The yes/no for "do they survive / die / win?", phrased the way it was asked. */
    private fun playerAnswer(to: String, p: mtg.judge.engine.Player, state: GameState): String {
        val won = !p.lost && state.players.all { it.id == p.id || it.lost }
        val alive = "${p.subject} ${p.v("is", "are")} still in the game${p.life?.let { " at $it life" } ?: ""}."
        return when (to) {
            "playerDie" -> if (p.lost) "Yes: ${p.subject} ${p.v("has", "have")} lost the game." else "No: $alive"
            "playerWin" -> if (won) "Yes: ${p.subject} ${p.v("has", "have")} won the game." else if (p.lost) "No: ${p.subject} ${p.v("has", "have")} lost the game." else "No: ${p.subject} ${p.v("hasn't", "haven't")} won; ${alive.replaceFirstChar { it.lowercase() }}"
            else -> if (p.lost) "No: ${p.subject} ${p.v("has", "have")} lost the game." else "Yes: $alive"
        }
    }

    companion object {
        /** A colour name or mana letter as its mana symbol letter: "blue" is U, not B (black). */
        fun colourChar(name: String): Char? = mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')[name.trim().lowercase()] ?: name.trim().singleOrNull()?.uppercaseChar()?.takeIf { it in "WUBRG" }

        fun toDef(card: Card): CardDef {
            val keywords = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(card.keywords) }.getOrDefault(emptyList())
            return OracleParser.parse(card.oracleId, card.name, card.typeLine, card.manaCost, card.manaValue, card.colors, card.power, card.toughness, keywords, card.oracleText, card.loyalty)
        }
    }
}

/** Plain-text rendering of an [Answer]. */
object AnswerRenderer {
    fun render(a: Answer, withCitations: Boolean = true): String = buildString {
        appendLine("Understood:"); a.understood.forEach { appendLine("  $it") }
        appendLine(); appendLine("What happens:")
        a.trace.forEachIndexed { i, t -> appendLine("  ${i + 1}. ${t.text}" + (if (t.rules.isNotEmpty()) "  [${t.rules.joinToString(", ")}]" else "")) }
        appendLine(); appendLine("Outcome:"); a.outcome.forEach { appendLine("  • $it") }
        if (a.assumptions.isNotEmpty()) { appendLine(); appendLine("Assumed:"); a.assumptions.forEach { appendLine("  • $it") } }
        if (a.clarifications.isNotEmpty()) { appendLine(); appendLine("Need to know:"); a.clarifications.forEach { appendLine("  ? $it") } }
        if (a.unsupported.isNotEmpty()) { appendLine(); appendLine("Not modeled (answer may be incomplete):"); a.unsupported.forEach { appendLine("  ! $it") } }
        if (withCitations && a.citations.isNotEmpty()) { appendLine(); appendLine("Rules cited:"); a.citations.toSortedMap(compareBy<String> { it.substringBefore('.').toIntOrNull() ?: 0 }.thenBy { it }).forEach { (n, t) -> appendLine("  $n  ${t.replace('\n', ' ')}") } }
    }
}
