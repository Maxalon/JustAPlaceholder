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
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.StaticEffect
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
        val state = GameState(sit.players.map { ps -> Player(ps.id, ps.name, ps.life).also { it.poison = ps.poison ?: 0; it.handSize = ps.handSize; it.librarySize = ps.librarySize; it.graveyardSize = ps.graveyardSize; it.commanderDamage.putAll(ps.commanderDamage); it.mana = ps.mana; ps.devotion.forEach { (c, n) -> colourChar(c)?.let { ch -> it.devotion[ch] = n } } } }, LinkedHashMap(), activePlayer = sit.turn.activePlayer, phase = sit.turn.phase, step = sit.turn.step, activePlayerStated = sit.turn.activePlayer != null).also { st ->
            st.turnNumber = sit.turn.number
            // "I have cast four spells this turn": storm-style counts start from what the situation said.
            sit.players.forEach { ps -> ps.spellsThisTurn?.let { n -> st.spellsThisTurn[ps.id] = n } }
        }
        val engine = Engine(state)

        for (o in sit.objects) {
            val def = cardDef(o.card, state) ?: continue
            state.add(GameObject(o.id, def, zone(o.zone), o.controller, o.owner ?: o.controller, o.tapped, o.summoningSick, o.counters.toMutableMap(), o.damage, o.token)).also { it.assumed = o.assumed
                it.timestamp = state.tick(); it.attachedTo = o.attachedTo; it.commander = o.commander; it.commanderCasts = o.commanderCasts; it.chosenName = o.named
                o.keywords.forEach { kw -> it.tempKeywords += kw.lowercase() }
                o.pump?.let { pm -> Regex("""^([+-]?\d+)/([+-]?\d+)$""").matchEntire(pm)?.let { m -> it.pumps += m.groupValues[1].toInt() to m.groupValues[2].toInt() } }
                if (def.isPlaneswalker && it.isOnBattlefield() && !it.counters.containsKey("loyalty") && def.loyalty != null) it.counters["loyalty"] = def.loyalty
                // A permanent the situation says is already on the battlefield entered at some point, so a flat
                // "enters with N counters" (modular, Hangarback) has already happened. Without this an Arcbound
                // Worker described as controlled was a 0/0 and died to state-based actions on the spot.
                if (it.isOnBattlefield() && o.counters.isEmpty()) def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }
                    .filterIsInstance<StaticEffect.EntersWithCounters>().firstOrNull { e -> e.count != null && e.per == null && !e.onlyIfKicked }?.let { e ->
                        val n = e.count!!
                        it.counters[e.kind] = n
                        state.assumptions += "${def.name} entered with $n ${e.kind} counter${if (n == 1) "" else "s"} on it, as its own text says; say the counters outright if it has a different number now."
                    }
                // Walking Ballista, Hangarback Walker: "enters with X counters" and nothing said how many. A 0/0
                // with none would have died already, so it has at least one; one it is, said out loud.
                if (it.isOnBattlefield() && o.counters.isEmpty() && def.power == 0 && def.toughness == 0) def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }
                    .filterIsInstance<StaticEffect.EntersWithCounters>().firstOrNull { e -> e.count == null && e.per == null && !e.onlyIfKicked }?.let { e ->
                        it.counters[e.kind] = 1
                        state.assumptions += "${def.name}'s ${e.kind} counters weren't stated; assuming 1 (it's a 0/0 without any). Say the number for a precise answer."
                    }
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
                p.handSize?.let { "$it in hand" }, p.librarySize?.let { "$it in library" }, p.graveyardSize?.let { "$it in graveyard" }, p.mana?.let { "$it mana" },
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
        // "I cast Reanimate on my Grizzly Bears": nobody reanimates a creature that is already on the battlefield,
        // so the creature the question names is read as being in the graveyard the spell takes it from. Left where
        // it was, the spell found nothing to put and the answer said nothing changed.
        val fromGraveyard = Regex("""from (?:a|your|an opponent's|target player's) graveyard (?:onto|to) the battlefield""", RegexOption.IGNORE_CASE)
        for (e in sit.events) {
            if (e.verb != "cast" || e.targets.isEmpty()) continue
            val def = e.card?.let { cardDef(it, state) } ?: continue
            if (!fromGraveyard.containsMatchIn(def.oracleText)) continue
            for (t in e.targets) state.objects[t]?.takeIf { it.zone == mtg.judge.engine.Zone.BATTLEFIELD }?.let { o ->
                o.zone = mtg.judge.engine.Zone.GRAVEYARD
                state.assumptions += "${o.name} is read as being in the graveyard: ${def.name} puts a card from a graveyard onto the battlefield, so a creature already there is not what it means."
            }
        }
        var attackBatchEnd = -1
        val deferredAsks = mutableListOf<Pair<Int, EventSpec>>()
        var lastStep: EventSpec? = null
        curEvents = sit.events
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

    /** The situation's events, for a step that needs to look back at what was said before it. */
    private var curEvents: List<EventSpec> = emptyList()

    private fun apply(e: EventSpec, state: GameState, engine: Engine) {
        val targets = e.targets.map { parseRef(it, state) }
        when (e.verb.lowercase()) {
            // "Can I stop it?": the spell on the stack, answered with what the asker has.
            "stop" -> {
                val player = e.player ?: "me"; val p = state.player(player)
                val spell = state.stack.lastOrNull { it.kind == StackKind.SPELL && it.controller != player } ?: run { state.outcomes += "There is no spell of ${state.player(state.players.first { it.id != player }.id).possessive} on the stack to stop."; return }
                val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == player }
                val crypt = mine.firstNotNullOfOrNull { o -> engine.activatedAbilitiesOf(o).withIndex().firstOrNull { (_, a) -> a.effect is Effect.ExileGraveyard && (a.effect as Effect.ExileGraveyard).who == mtg.judge.engine.Who.TARGET_PLAYER }?.let { (i, a) -> Triple(o, i, a) } }
                val counter = mine.firstNotNullOfOrNull { o -> engine.activatedAbilitiesOf(o).withIndex().firstOrNull { (_, a) -> a.effect is Effect.Counter }?.let { (i, a) -> Triple(o, i, a) } }
                val heldCounter = state.objects.values.firstOrNull { it.zone == Zone.HAND && it.controller == player && it.def.isInstantOrSorcery && it.def.spellEffect.let { ef -> ef is Effect.Counter || (ef is Effect.Seq && ef.effects.any { x -> x is Effect.Counter }) } }
                when {
                    crypt != null && spell.source.def.oracleText.contains("graveyard", true) -> {
                        state.trace.step("\"Can ${p.subject.lowercase()} stop it?\": ${crypt.first.name} has \"${crypt.third.text.replace("~", crypt.first.name)}\", and ${spell.describe} works with a graveyard, so it's activated in response targeting ${state.player(spell.controller).subject.lowercase()}.", "602.2")
                        engine.activate(player, crypt.first.id, crypt.second, listOf(Ref.Player(spell.controller)))
                    }
                    counter != null -> { state.trace.step("\"Can ${p.subject.lowercase()} stop it?\": ${counter.first.name} has an ability that counters, so it's activated targeting ${spell.describe}.", "602.2"); engine.activate(player, counter.first.id, counter.second, listOf(Ref.Stack(spell.id))) }
                    heldCounter != null -> { state.trace.step("\"Can ${p.subject.lowercase()} stop it?\": ${heldCounter.name} in hand counters, so it's cast targeting ${spell.describe}.", "601.2"); engine.cast(player, heldCounter.def, listOf(Ref.Stack(spell.id)), heldCounter.id) }
                    else -> state.outcomes += "No: nothing described that ${p.subject.lowercase()} ${p.v("controls", "control")} or ${p.v("holds", "hold")} counters ${spell.describe} or answers what it does."
                }
            }
            "cast" -> {
                val player = e.player ?: state.players.first().id
                // "Can I counter it?" with Glen Elendra Archmage out: the permanent's own counter ability, not a spell nobody named.
                if (e.card?.name == "a counterspell" && e.obj == null) {
                    val spellRef = e.targets.firstOrNull()?.let { parseRef(it, state) }
                    val spellItem = (spellRef as? Ref.Stack)?.let { r -> state.stack.firstOrNull { it.id == r.id } }
                    val found = state.objects.values.filter { it.isOnBattlefield() && it.controller == player }.firstNotNullOfOrNull { o ->
                        o.def.abilities.filterIsInstance<ActivatedAbility>().withIndex().firstOrNull { (_, a) -> (a.effect as? Effect.Counter)?.let { c -> spellItem == null || !(mtg.judge.engine.Kind.CREATURE in c.target.filter.notKinds && spellItem.source.def.isCreature) && (mtg.judge.engine.Kind.CREATURE !in c.target.filter.kinds || spellItem.source.def.isCreature) } == true }?.let { (i, _) -> o to i }
                    }
                    if (found != null && spellRef != null) {
                        state.trace.step("\"Can ${state.player(player).subject.lowercase()} counter it?\": ${found.first.name} has an ability that counters, so it's activated rather than a counterspell nobody named.", "602.2")
                        engine.activate(player, found.first.id, found.second, listOf(spellRef)); return
                    }
                }
                // A commander cast without saying which object it is: the card sitting in the command zone is it,
                // which is what carries the commander tax and what Drannith Magistrate is looking at.
                val existing = e.obj?.let { state.objects[it] }
                    // "flashback it": the card is the one in the caster's graveyard (with the flashback Snapcaster gave it).
                    ?: e.card?.takeIf { e.to == "flashback" }?.let { c -> state.objects.values.lastOrNull { o -> o.zone == Zone.GRAVEYARD && o.owner == player &&
                        (c.oracleId?.let { it == o.def.oracleId } ?: o.def.name.equals(c.name ?: "", true)) } }
                    ?: e.card?.let { c -> state.objects.values.firstOrNull { o -> o.zone == Zone.COMMAND && o.controller == player &&
                        (c.oracleId?.let { it == o.def.oracleId } ?: o.def.name.equals(c.name ?: "", true)) } }
                val def = existing?.def ?: cardDef(e.card ?: throw JudgeException("cast needs a card"), state) ?: return
                // A rider sentence in front of the modes makes the spell a Seq around its Modal.
                val modalEffect = def.spellEffect as? Effect.Modal
                    ?: (def.spellEffect as? Effect.Seq)?.effects?.firstNotNullOfOrNull { it as? Effect.Modal }
                val modes = if (e.modes.isEmpty() && e.to?.startsWith("mode:") == true) {
                    val words = e.to.removePrefix("mode:").lowercase().split("|").filter { it.isNotEmpty() }
                    val texts = modalEffect?.modeTexts ?: emptyList()
                    words.mapNotNull { w -> matchMode(w, texts, def.name)?.plus(1) }
                } else e.modes.filter { i -> modalEffect == null || i <= modalEffect.modes.size }
                // A modal spell's targets belong to the modes chosen (700.2c), so those are what a stated target
                // has to fit; without this the target was dropped and the mode resolved with none.
                val needed = modalEffect?.takeIf { modes.isNotEmpty() }?.let { mo -> modes.mapNotNull { i -> mo.modes.getOrNull(i - 1) }.flatMap { it.targets() } }
                    ?: def.spellEffect?.targets() ?: emptyList()
                // "I Stifle Wasteland's ability" with no activation described: the ability is activated first, so there
                // is something on the stack to aim at, and the answer says so.
                for (t in e.targets) if (t.endsWith(":ability") || t.endsWith(":activated")) {
                    val srcId = t.substringBefore(':')
                    val src = state.objects[srcId] ?: continue
                    if (state.stack.none { it.source.id == srcId && it.kind == StackKind.ACTIVATED } && src.def.abilities.any { it is ActivatedAbility }) {
                        state.assumptions += "${src.name}'s ability wasn't said to be activated; assuming ${state.player(src.controller).subject.lowercase()} activated it, since that is what ${def.name} is aimed at."
                        engine.activate(src.controller, srcId, null, emptyList())
                    }
                }
                // "They block with a 2/2. I Giant Growth after damage.": a pump with no target named, cast by the
                // player whose creature is attacking, is aimed at that attacker.
                val castTargets = if (e.targets.isEmpty() && needed.size == 1 && def.isInstantOrSorcery && def.spellEffect.let { it is Effect.Pump || (it is Effect.Seq && it.effects.firstOrNull() is Effect.Pump) })
                    (state.objects.values.filter { it.isOnBattlefield() && it.controller == player && it.attacking != null }.takeIf { it.size == 1 }
                        // "… after damage": the attacker may already be dead; the spell was still meant for it.
                        ?: curEvents.lastOrNull { it.verb == "attack" && it.player == player && it.obj != null }?.obj?.let { state.objects[it] }?.let { listOf(it) }
                        ?: curEvents.lastOrNull { it.verb == "attackAll" && it.player == player }?.let { state.objects.values.filter { it.owner == player && it.def.isCreature && it.zone == Zone.GRAVEYARD }.takeIf { it.size == 1 } })
                        ?.map { a -> state.assumptions += "${def.name}'s target wasn't stated; it's read as ${a.name}, the creature ${state.player(player).subject.lowercase()} attacked with."; Ref.Obj(a.id) as Ref } ?: emptyList()
                    else disambiguate(e.targets, needed, player, state, engine)
                // "I cast Unearth on my Kitchen Finks": a spell that targets a card in a graveyard says where the card is.
                castTargets.forEachIndexed { i, ref -> val spec = needed.getOrNull(i); if (spec?.filter?.inGraveyard == true && ref is Ref.Obj) state.objects[ref.id]?.takeIf { it.assumed && it.isOnBattlefield() }?.let { o ->
                    o.zone = Zone.GRAVEYARD; o.assumed = false
                    state.assumptions += "${o.name} is read as a card in ${state.player(o.owner).possessive} graveyard, since ${def.name} targets one there and nothing said where it was." } }
                engine.cast(player, def, castTargets, existing?.id, modes, overload = e.to == "overload", x = e.amount, kicked = e.to == "kicked", evoked = e.to == "evoke", flashback = e.to == "flashback", alternative = e.to == "altcost", choice = e.to?.takeIf { it.startsWith("copy:") || it == "revolt" } ?: e.to?.takeIf { it.startsWith("copytarget:") }?.removePrefix("copytarget:") ?: e.to?.takeIf { it.startsWith("name:") }?.removePrefix("name:") ?: e.to?.takeIf { it == "revolt" || it == "spellmastery" } ?: e.to?.takeIf { it.startsWith("put:") }?.removePrefix("put:"), payLife = e.payLife)
            }
            "draw" -> engine.draw(e.player ?: throw JudgeException("draw needs a player"), e.amount ?: 1)
            // "Grizzly Bears fights Hill Giant": the fight itself, with no card making it happen (701.14a).
            "fight" -> engine.fight(state.objects[e.obj ?: throw JudgeException("fight needs an object")], state.objects[e.targets.firstOrNull() ?: throw JudgeException("fight needs something to fight")])
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
            "counters" -> engine.putCounters(e.obj ?: throw JudgeException("counters needs an object"), e.amount ?: 1, e.to ?: "+1/+1")
            "tap" -> engine.tapObject(e.obj ?: throw JudgeException("tap needs an object"))
            "untap" -> engine.untapObject(e.obj ?: throw JudgeException("untap needs an object"))
            "mill" -> engine.millCards(e.player ?: throw JudgeException("mill needs a player"), e.amount ?: 1)
            "proliferate" -> engine.proliferate(e.player ?: state.players.first().id)
            // "I cast Angel's Grace and go to 0 life": a life total said after something happened is where that
            // player ends up, not where they started — set at setup the game would have ended before the spell.
            "setlife" -> {
                val p = state.player(e.player ?: throw JudgeException("setLife needs a player"))
                val to = e.amount ?: throw JudgeException("setLife needs a life total")
                val from = p.life
                p.life = to
                state.trace.step("${p.subject} ${p.v("is", "are")} at $to life" + (from?.let { " (from $it)" } ?: "") + ".", "118.5")
                engine.stateBasedActions()
            }
            // "my opponent is the monarch": the monarch draws at their end step and loses it to combat damage (725).
            "monarch" -> {
                val p = state.player(e.player ?: throw JudgeException("monarch needs a player"))
                state.monarch = p.id
                state.trace.step("${p.subject} ${p.v("is", "are")} the monarch: at the beginning of ${p.possessive} end step ${p.subject.lowercase()} ${p.v("draws", "draw")} a card, and whoever deals combat damage to ${p.subject.lowercase()} becomes the monarch instead.", "725.1", "725.2")
            }
            // "my Grizzly Bears becomes a 4/4 until end of turn": a base size set outright (layer 7b), so counters
            // and +N/+N effects still apply on top of it.
            "setpt" -> {
                val o = state.obj(e.obj ?: throw JudgeException("setPt needs an object"))
                val pt = Regex("""^(\d+)/(\d+)$""").find(e.to ?: "") ?: throw JudgeException("setPt needs a size like \"4/4\"")
                o.basePt = pt.groupValues[1].toInt() to pt.groupValues[2].toInt()
                state.trace.step("${o.name} has base power and toughness ${pt.groupValues[1]}/${pt.groupValues[2]}. That's a layer 7b effect, so counters and +N/+N effects still apply on top of it; it is now ${o.power}/${o.toughness}.", "613.4b")
                state.outcomes += "${o.name} is ${o.power}/${o.toughness}."
            }
            // "my opponent scoops": conceding is a special action that player may take any time they have priority.
            "concede" -> {
                val p = state.player(e.player ?: throw JudgeException("concede needs a player"))
                if (!p.lost) {
                    p.lost = true
                    state.trace.step("${p.subject} ${p.v("concedes", "concede")} and leaves the game. A player who concedes loses the game immediately; it isn't a state-based action and can't be responded to.", "104.3a", "800.4a")
                    state.outcomes += "${p.subject} ${p.v("concedes", "concede")} and ${p.v("loses", "lose")} the game."
                }
            }
            "discardcount" -> engine.discardCount(e.player ?: throw JudgeException("discard needs a player"), e.amount ?: 1)
            // "I create a Treasure": tokens the situation makes, through the doublers.
            "token" -> engine.createTokens(e.player ?: "me", e.card?.name ?: throw JudgeException("token needs a description"), e.amount ?: 1)
            // "I have 2 lands untapped" said after a cast: the mana available from here on, whatever was spent before.
            "mananow" -> { val p = state.player(e.player ?: throw JudgeException("manaNow needs a player")); p.mana = e.amount; p.manaSpent = 0 }
            "poison" -> engine.addPoison(e.player ?: throw JudgeException("poison needs a player"), e.amount ?: 1)
            "gainlife" -> engine.gainLifeEvent(e.player ?: throw JudgeException("gainLife needs a player"), e.amount ?: 1)
            "loselife" -> engine.loseLifeEvent(e.player ?: throw JudgeException("loseLife needs a player"), e.amount ?: 1)
            "blink" -> { val o = state.obj(e.obj ?: throw JudgeException("blink needs an object")); engine.blinkObject(o, e.player ?: o.controller) }
            "reanimate" -> { val o = state.obj(e.obj ?: throw JudgeException("reanimate needs an object")); engine.reanimateObject(o, e.player ?: o.owner) }
            "regenerate" -> { val o = state.obj(e.obj ?: throw JudgeException("regenerate needs an object")); state.shields += mtg.judge.engine.Shield(mtg.judge.engine.Replacement.Regenerate, o.id, null, 1, "a regeneration effect") }
            // "Can I kill my opponent at 40?" / "can I kill it?": what the asker controls or holds that deals damage is aimed at it.
            "kill" -> {
                val player = e.player ?: state.players.first().id
                val victim = targets.firstOrNull() ?: throw JudgeException("kill needs a target")
                fun damage(eff: Effect?): Boolean = when (eff) { is Effect.Damage -> true; is Effect.DamageDivided -> true; is Effect.Seq -> eff.effects.any { damage(it) }; is Effect.May -> damage(eff.effect); is Effect.Modal -> eff.modes.any { damage(it) }; else -> false }
                val onBoard = state.objects.values.filter { it.isOnBattlefield() && it.controller == player }.firstNotNullOfOrNull { o ->
                    o.def.abilities.filterIsInstance<ActivatedAbility>().withIndex().firstOrNull { (_, a) -> damage(a.effect) && a.effect.targets().isNotEmpty() }?.let { (i, a) -> Triple(o, i, a) }
                }
                if (onBoard != null) {
                    val (o, idx, a) = onBoard
                    state.trace.step("\"Can ${state.player(player).subject.lowercase()} kill ${state.nameOf(victim)}?\": ${o.name} has \"${a.text.replace("~", o.name)}\", which deals damage, so it's activated targeting ${state.nameOf(victim)}.", "602.2")
                    engine.activate(player, o.id, idx, listOf(victim)); return
                }
                val inHand = state.objects.values.firstOrNull { it.zone == Zone.HAND && it.controller == player && it.def.isInstantOrSorcery && damage(it.def.spellEffect) }
                if (inHand != null) { state.trace.step("\"Can ${state.player(player).subject.lowercase()} kill ${state.nameOf(victim)}?\": ${inHand.name} in hand deals damage, so it's cast targeting ${state.nameOf(victim)}.", "601.2"); engine.cast(player, inHand.def, listOf(victim), objectId = inHand.id); return }
                state.clarifications += mtg.judge.engine.Clarification("killing ${state.nameOf(victim)}", "nothing described that ${state.player(player).subject.lowercase()} ${state.player(player).v("controls", "control")} or ${state.player(player).v("holds", "hold")} deals damage to it. Say what you have for an answer.")
            }
            // "Can I save it?": whatever the player controls or holds that would protect the creature is used on it, in
            // response to what threatens it — Mother of Runes, a regeneration ability, a Blossoming Defense in hand.
            "save" -> {
                val player = e.player ?: state.players.first().id
                val target = state.obj(e.obj ?: throw JudgeException("save needs an object"))
                fun protective(eff: Effect?): Boolean = when (eff) {
                    is Effect.GainKeywords -> eff.keywords.any { k -> k.startsWith("protection") || k in setOf("hexproof", "shroud", "indestructible") }
                    is Effect.PumpAll -> eff.keywords.any { k -> k in setOf("hexproof", "shroud", "indestructible") } && eff.filter.controller == mtg.judge.engine.Who.YOU
                    is Effect.Regenerate, is Effect.CreateShield -> true
                    is Effect.Seq -> eff.effects.any { protective(it) }
                    is Effect.Modal -> eff.modes.any { protective(it) }
                    is Effect.May -> protective(eff.effect)
                    else -> false
                }
                fun targeted(eff: Effect?): Boolean = when (eff) {
                    is Effect.GainKeywords -> true; is Effect.Regenerate -> eff.target != null; is Effect.CreateShield -> eff.target != null
                    is Effect.Seq -> eff.effects.any { targeted(it) }; is Effect.May -> targeted(eff.effect); is Effect.Modal -> eff.modes.any { targeted(it) }; else -> false
                }
                val onBoard = state.objects.values.filter { it.isOnBattlefield() && it.controller == player }.firstNotNullOfOrNull { o ->
                    o.def.abilities.withIndex().filter { it.value is ActivatedAbility }.map { it.index to it.value as ActivatedAbility }
                        .firstOrNull { (_, a) -> protective(a.effect) && (targeted(a.effect) || o.id == target.id || a.effect is Effect.PumpAll) }?.let { (_, a) -> o to a }
                }
                if (onBoard != null) {
                    val (o, a) = onBoard
                    val idx = o.def.abilities.filterIsInstance<ActivatedAbility>().indexOf(a)
                    // Mother of Runes: the colour chosen is the colour of the spell aimed at the creature.
                    val threat = state.stack.lastOrNull { s -> s.targets.any { t -> t is Ref.Obj && t.id == target.id } }
                    val colour = threat?.source?.def?.colors?.singleOrNull()?.let { c -> mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")[c] }
                    val choosesColour = a.text.contains("color of your choice", true) || a.text.contains("colour of your choice", true)
                    state.trace.step("\"Can ${state.player(player).subject.lowercase()} save ${target.name}?\": ${o.name} has \"${a.text.replace("~", o.name)}\", which would protect it, so it's activated${if (targeted(a.effect)) " targeting ${target.name}" else ""} in response${if (choosesColour && colour != null) ", choosing $colour (${threat.source.name}'s colour)" else ""}.", "117.3c", "602.2")
                    engine.activate(player, o.id, idx, if (targeted(a.effect)) listOf(Ref.Obj(target.id)) else emptyList(), choice = if (choosesColour) colour else null)
                    return
                }
                val inHand = state.objects.values.firstOrNull { it.zone == Zone.HAND && it.controller == player && it.def.isInstantOrSorcery && protective(it.def.spellEffect) }
                if (inHand != null) {
                    val spell = inHand.def.spellEffect
                    val modeIdx = (spell as? Effect.Modal)?.modes?.indexOfFirst { protective(it) }?.takeIf { it >= 0 }
                    val chosen = modeIdx?.let { (spell as Effect.Modal).modes[it] } ?: spell
                    val aimed = targeted(chosen)
                    state.trace.step("\"Can ${state.player(player).subject.lowercase()} save ${target.name}?\": ${inHand.name} in hand would protect it, so it's cast in response${if (aimed) " targeting ${target.name}" else ""}${modeIdx?.let { ", choosing its mode \"${(chosen as? Effect.PumpAll)?.let { pa -> "${pa.filter.raw} gain ${pa.keywords.joinToString(" and ")} until end of turn" } ?: "the one that protects it"}\"" } ?: ""}.", "117.3c")
                    engine.cast(player, inHand.def, if (aimed) listOf(Ref.Obj(target.id)) else emptyList(), objectId = inHand.id, modes = modeIdx?.let { listOf(it + 1) } ?: emptyList())
                    return
                }
                if (e.to == "quiet") return
                state.clarifications += mtg.judge.engine.Clarification("saving ${target.name}", "nothing described that ${state.player(player).subject.lowercase()} ${state.player(player).v("controls", "control")} or ${state.player(player).v("holds", "hold")} would protect ${target.name} (an ability that grants protection, hexproof, shroud, indestructible or regeneration, or such an instant in hand). Say what you have for an answer.")
            }
            "pay" -> {
                val who = e.player ?: throw JudgeException("pay needs a player")
                if (e.to == "no") { state.willPay.remove(who); state.wontPay += who } else { state.wontPay.remove(who); state.willPay += who }
            }
            "activate" -> {
                val objId = e.obj ?: throw JudgeException("activate needs an object")
                val obj = state.obj(objId)
                // "I cast Walking Ballista for X=3 and ping their 1/1": the Ballista has to resolve and be on the
                // battlefield before its ability can be activated; the pings come after, not in response to itself.
                if (obj.zone == mtg.judge.engine.Zone.STACK && state.stack.any { it.source === obj }) { state.trace.step("${obj.name} is still a spell on the stack; a permanent's activated ability can be activated only once it's on the battlefield, so the spell resolves first.", "113.6", "601.2a"); engine.resolveAll() }
                // "sacrifice a Forest to Gitrog": nothing to activate on it, so it's just a sacrifice (its triggers still see it).
                e.to?.takeIf { it.startsWith("sacrifice:") }?.removePrefix("sacrifice:")?.let { sacId ->
                    if (obj.def.abilities.filterIsInstance<ActivatedAbility>().none { a -> a.cost.contains("sacrifice", true) }) { engine.sacrifice(e.player ?: state.obj(sacId).controller, sacId); return }
                }
                // "+1" / "-3" names a loyalty ability by its cost.
                val idx = e.abilityIndex
                    // "activate Nykthos for green": a colour was chosen, so the ability that uses one is the one
                    // meant — not Nykthos's plain "{T}: Add {C}", which was picked first and ignored the devotion.
                    ?: e.to?.takeIf { it.startsWith("color:") }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.effect is Effect.AddManaDevotion || (a.effect as? Effect.Seq)?.effects?.any { it is Effect.AddManaDevotion } == true }.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "mana" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.effect is Effect.AddMana || a.effect is Effect.AddManaPer || (a.effect as? Effect.Seq)?.effects?.any { it is Effect.AddMana || it is Effect.AddManaPer } == true }.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "levelup" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.cost.startsWith("Level up", true) }.takeIf { it >= 0 } }
                    ?: e.to?.takeIf { it == "saddle" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.effect is Effect.SaddleSelf }.takeIf { it >= 0 } }
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
                if (e.to == "untapLands") {
                    val p = state.player(e.player ?: "me")
                    val did = state.trace.steps.any { st -> st.text.contains("untap", true) && st.text.contains("land", true) && st.text.contains(p.subject) }
                    val sword = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.def.oracleText.contains("deals combat damage to a player", true) && o.def.oracleText.contains("untap all lands", true) }
                    state.outcomes += if (did) "Yes: ${p.possessive} lands untap." else "No: nothing untapped ${p.possessive} lands." + (sword?.let { " ${it.name} untaps them only when the equipped creature deals combat damage to a player; a blocked creature deals its damage to the blocker, not the player, so the trigger never happens (510.1c)." } ?: "")
                    return
                }
                if (e.to == "countered") {
                    val name = e.card?.name ?: throw JudgeException("ask needs a card")
                    val by = state.trace.steps.firstOrNull { it.text.contains("$name is countered") || (it.text.contains("counter") && it.text.contains(name) && it.text.contains("resolves")) }
                    state.outcomes += when {
                        state.outcomes.any { it == "$name is countered." } -> "Yes: $name is countered" + (by?.let { st -> Regex("""^(.+?)(?:'s (?:triggered |activated )?ability)? (?:resolves|triggers)""").find(st.text)?.groupValues?.get(1)?.takeIf { src -> src != name && !src.startsWith("All players") }?.let { src -> " (by $src)" } } ?: "") + "."
                        state.outcomes.any { it.startsWith("$name isn't countered") || it.startsWith("$name can't be countered") } -> "No: $name isn't countered (it can't be)."
                        state.outcomes.any { it.startsWith("$name can't be cast") } -> "It's never cast: see above."
                        else -> "No: $name isn't countered; it resolves."
                    }
                    return
                }
                if (e.to == "manaNextTurn") {
                    // "If they Mana Drain it, how much mana do they get next turn?": the delayed trigger's mana.
                    val p = state.player(e.player ?: "me")
                    val drained = state.trace.steps.mapNotNull { st -> Regex("""That spell was (.+?), mana value (\d+)""").find(st.text) }.lastOrNull()
                    state.outcomes += if (drained != null) "${p.subject} ${p.v("adds", "add")} ${drained.groupValues[2]} colorless mana ({C} × ${drained.groupValues[2]}, ${drained.groupValues[1]}'s mana value) at the beginning of ${p.possessive.lowercase()} next main phase, from Mana Drain's delayed trigger; it's added then, not now, and lasts until that phase ends."
                        else engine.manaAvailable(p.id)
                    return
                }
                if (e.to == "manaAvailable") {
                    // "I activate Nykthos for green. How much mana do I get?" — the question is about the ability
                    // just used, not about what is left untapped afterwards, which is usually nothing.
                    val made = state.outcomes.lastOrNull { it.contains("mana ability: add ") }
                    if (made != null) return
                    val p = state.player(e.player ?: throw JudgeException("ask needs a player"))
                    // After a Mana Drain the mana asked about is the delayed trigger's, added next main phase.
                    val drained = state.trace.steps.mapNotNull { st -> Regex("""That spell was (.+?), mana value (\d+)""").find(st.text) }.lastOrNull()
                    if (drained != null && state.objects.values.none { it.isOnBattlefield() && it.controller == p.id && it.tapped != true && engine.activatedAbilitiesOf(it).any { a -> engine.isManaEffect(a.effect) } }) {
                        state.outcomes += "${p.subject} ${p.v("adds", "add")} ${drained.groupValues[2]} colorless mana ({C} × ${drained.groupValues[2]}, ${drained.groupValues[1]}'s mana value) at the beginning of ${p.possessive.lowercase()} next main phase, from Mana Drain's delayed trigger; it's added then, not now, and lasts until that phase ends."; return
                    }
                    state.outcomes += engine.manaAvailable(p.id); return
                }
                if (e.to == "spellCost") { state.outcomes += engine.spellCost(e.obj ?: e.card?.name?.let { n -> state.objects.values.lastOrNull { it.def.name.equals(n, true) }?.id } ?: throw JudgeException("ask needs an object")); return }
                if (e.to == "identity") { val o = state.obj(e.obj ?: throw JudgeException("ask needs an object")); val where = o.zone.name.lowercase().replace('_', ' '); state.outcomes += "It's ${o.def.name} in ${if (o.zone == Zone.HAND) "${state.player(o.controller).possessive} hand" else where}${if (state.trace.steps.any { it.text.contains("stops being a copy") || it.text.contains("was a copy of") }) ": a copy effect lasts only while the permanent is on the battlefield (400.7)" else ""}."; return }
                if (e.to?.startsWith("text:") == true) { state.outcomes += e.to.removePrefix("text:"); return }
                if (e.to == "playerGain" || e.to == "playerLost") {
                    val p = state.player(e.player ?: "me"); val subj = Regex.escape(p.subject)
                    val gain = e.to == "playerGain"
                    val total = state.trace.steps.sumOf { st ->
                        (if (gain) Regex("""(?i)(?:^|\b)$subj (?:gains?|gain) (\d+) life\b""") else Regex("""(?i)(?:^|\b)$subj,? (?:who )?(?:loses?|lose) (\d+) life\b""")).findAll(st.text).sumOf { it.groupValues[1].toInt() }
                    }
                    val asked = e.amount
                    state.outcomes += when {
                        total == 0 -> "No: ${p.subject.lowercase()} ${if (gain) p.v("gains", "gain") else p.v("loses", "lose")} no life here."
                        asked != null && asked != total -> "No: ${p.subject.lowercase()} ${if (gain) p.v("gains", "gain") else p.v("loses", "lose")} $total life in all, not $asked."
                        asked != null -> "Yes: ${p.subject.lowercase()} ${if (gain) p.v("gains", "gain") else p.v("loses", "lose")} $total life."
                        else -> "${p.subject} ${if (gain) p.v("gains", "gain") else p.v("loses", "lose")} $total life in all."
                    }
                    return
                }
                if (e.to == "tokenMade") {
                    val p = state.player(e.player ?: "me")
                    val name = e.card?.name ?: run {
                        // "Do I get a token?" with none named: whatever tokens that player ended up with.
                        val any = state.objects.values.filter { it.token && it.isOnBattlefield() && it.controller == p.id }
                        state.outcomes += if (any.isNotEmpty()) "Yes: ${p.subject.lowercase()} ${p.v("has", "have")} ${any.groupBy { it.def.name }.entries.joinToString(", ") { (n, l) -> "${if (l.size == 1) "a" else l.size.toString()} $n token${if (l.size == 1) "" else "s"}" }}."
                            else "No: no token was created by what was described."
                        return
                    }
                    val tok = state.objects.values.filter { it.token && it.isOnBattlefield() && it.controller == p.id && it.def.name.equals(name, true) }
                    state.outcomes += if (tok.isNotEmpty()) "Yes: ${p.subject.lowercase()} ${p.v("has", "have")} ${if (tok.size == 1) "a" else tok.size.toString()} $name token${if (tok.size == 1) "" else "s"}${tok.first().let { t -> if (t.def.isCreature) " (${state.describePt(t)})" else "" }}."
                        else "No: no $name token was created."
                    return
                }
                if (e.to == "hitsOwn") {
                    val name = e.card?.name ?: throw JudgeException("ask needs a card")
                    val o = state.objects.values.lastOrNull { it.def.name.equals(name, true) } ?: throw JudgeException("$name wasn't cast")
                    val p = state.player(e.player ?: o.controller)
                    fun filters(x: Effect?): List<mtg.judge.engine.ObjFilter> = when (x) {
                        null -> emptyList(); is Effect.ForAll -> listOf(x.filter); is Effect.Destroy -> listOf(x.target.filter); is Effect.Exile -> listOf(x.target.filter)
                        is Effect.Bounce -> listOfNotNull(x.target?.filter); is Effect.Damage -> listOf(x.target.filter); is Effect.Tap -> listOf(x.target.filter); is Effect.Seq -> x.effects.flatMap { filters(it) }; else -> emptyList()
                    }
                    val fs = filters(o.def.spellEffect)
                    val hit = state.objects.values.filter { m -> m.isOnBattlefield() && m.controller == p.id && m !== o && fs.any { f -> state.matches(f, m, o.controller) } }
                    state.outcomes += when {
                        fs.isEmpty() -> "${o.name}'s effect isn't modeled closely enough to say what it touches."
                        fs.all { it.controller == mtg.judge.engine.Who.OPPONENT } -> "No: ${o.name} affects only what its controller doesn't control (\"${fs.first().raw}\"); ${p.possessive} own permanents are untouched."
                        hit.isNotEmpty() -> "Yes: ${o.name} affects ${p.possessive} own ${hit.joinToString(", ") { it.name }} too (\"${fs.first().raw}\")."
                        fs.any { it.controller == null } -> "Yes: ${o.name}'s text says \"${fs.first().raw}\" with no controller named, so ${p.possessive} own permanents that match are affected too."
                        else -> "No: ${o.name} doesn't affect ${p.possessive} own permanents."
                    }
                    return
                }
                if (e.to?.startsWith("count:") == true) {
                    val what = e.to.removePrefix("count:"); val p = state.player(e.player ?: "me")
                    val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id }
                    val n = when (what) {
                        "creatures" -> mine.count { it.def.isCreature || it.animatedAs != null }
                        "permanents" -> mine.size
                        "lands" -> mine.count { "Land" in it.def.types }
                        "artifacts" -> mine.count { "Artifact" in it.def.types }
                        "tokens" -> mine.count { it.token }
                        "cards", "cards in hand" -> p.handSize ?: state.objects.values.count { it.zone == Zone.HAND && it.controller == p.id }
                        // "how many Goblins do I have?": creatures of that type, by subtype or by the generic name.
                        else -> mine.count { o -> o.def.subtypes.any { st -> st.equals(what.removeSuffix("s"), true) } || o.def.name.lowercase().contains(" ${what.removeSuffix("s")}") }
                    }
                    val noun = if (what.startsWith("cards")) "card${if (n == 1) "" else "s"} in hand" else if (n == 1) what.removeSuffix("s") else what
                    state.outcomes += "${p.subject} ${p.v("has", "have")} $n $noun${if (n == 0 && !what.startsWith("cards")) " left" else ""}."
                    return
                }
                if (e.to == "canCounter") {
                    val p = state.player(e.player ?: "opp")
                    val teferi = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != p.id && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { s -> s is StaticEffect.OpponentsSorcerySpeed } }
                    val ss = state.objects.values.lastOrNull { it.def.has("split second") && it.zone != Zone.HAND && it.zone != Zone.LIBRARY }
                    state.outcomes += when {
                        teferi != null -> "No: ${teferi.name} lets ${p.subject.lowercase()} cast spells only when ${p.subject.lowercase()} could cast a sorcery (${p.possessive} own main phase, empty stack), so ${p.subject.lowercase()} can't cast a counterspell while a spell is on the stack. Activated abilities that counter still work."
                        ss != null -> "No: ${ss.name} has split second, so no spells can be cast while it's on the stack (702.61a)."
                        else -> "Yes, with a counterspell in hand and the mana for it: ${p.subject.lowercase()} ${p.v("gets", "get")} priority after the spell is cast (117.3c)."
                    }
                    return
                }
                if (e.to == "respond") {
                    val ss = state.objects.values.lastOrNull { it.def.has("split second") && it.zone != Zone.HAND && it.zone != Zone.LIBRARY }
                    val last = state.objects.values.lastOrNull { it.def.isInstantOrSorcery && it.zone == Zone.GRAVEYARD }
                    state.outcomes += if (ss != null) "No: ${ss.name} has split second, so while it's on the stack players can't cast spells or activate abilities that aren't mana abilities (702.61a). Triggered abilities still trigger, and special actions like turning a morph face up are still allowed."
                        else "Yes: after ${last?.name ?: "a spell"} is cast its controller gets priority, then each player does in turn; instants can be cast and abilities activated before it resolves (117.3c, 117.4)."
                    return
                }
                if (e.to == "gotLand") {
                    val p = state.player(e.player ?: "me")
                    val searched = state.trace.steps.any { Regex("""(?i)^${Regex.escape(p.subject)} (?:may )?(?:choose(?:s)? to )?search(?:es)? (?:your|their|${Regex.escape(p.possessive)}) library for a (?:basic )?land""").containsMatchIn(it.text) } ||
                        state.outcomes.any { Regex("""(?i)^${Regex.escape(p.subject)} choose(?:s)? to search""").containsMatchIn(it) && it.contains("land") }
                    state.outcomes += if (searched) "Yes: the search is ${if (p.you) "yours" else p.name + "'s"}. The card gives it to the exiled creature's controller, which is ${p.subject.lowercase()}; ${p.subject.lowercase()} may search for a basic land card and put it onto the battlefield tapped."
                        else "No: nothing here has ${p.subject.lowercase()} search for a land."
                    return
                }
                if (e.to == "monarch") { state.outcomes += (state.monarch?.let { mid -> val p = state.player(mid); "${p.subject} ${p.v("is", "are")} the monarch." } ?: "Nobody is the monarch."); return }
                if (e.to == "playerSurvive" || e.to == "playerDie" || e.to == "playerWin") { state.outcomes += playerAnswer(e.to, state.player(e.player ?: throw JudgeException("ask needs a player")), state); return }
                // "do I draw?" / "how many cards do I draw?": every card that player drew while this played out.
                if (e.to == "playerDraw") {
                    val p = state.player(e.player ?: throw JudgeException("ask needs a player"))
                    state.outcomes += if (p.drew > 0) "Yes: ${p.subject} ${p.v("draws", "draw")} ${p.drew} card${if (p.drew == 1) "" else "s"} in all."
                        else "No: ${p.subject} ${p.v("doesn't", "don't")} draw${if (p.drewFromEmpty) " (the library is empty)" else ""}."
                    return
                }
                // "what's their life total?": the total once everything is done, said plainly.
                if (e.to == "playerLife") {
                    val p = state.player(e.player ?: throw JudgeException("ask needs a player"))
                    state.outcomes += p.life?.let { "${p.subject} ${p.v("is", "are")} at $it life." }
                        ?: "${p.subject} ${p.v("has", "have")} no life total in this situation; say what ${p.subject.lowercase()} started at for a number."
                    return
                }
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
                    "tookDamage" -> state.outcomes += run {
                        // Damage stays marked until cleanup, and two creatures can share a description, so the object's own count decides.
                        if (o.damage > 0) "Yes: ${o.name} was dealt ${o.damage} damage." else if (!o.isOnBattlefield() && state.trace.steps.any { st -> st.text.contains("damage to ${o.name}") && st.text.contains("deals") }) "Yes: ${o.name} was dealt damage (it's no longer on the battlefield)." else "No: ${o.name} wasn't dealt any damage."
                    }
                    "stillResolves" -> state.outcomes += when {
                        state.trace.steps.any { it.text.startsWith("${o.name} is no longer on the battlefield, but its ability was already on the stack") } ->
                            "Yes: ${o.name}'s ability was already on the stack, and an ability on the stack exists independently of its source. Removing ${o.name} doesn't counter it; it resolves using ${o.name}'s last known information (113.7a)."
                        state.trace.steps.any { it.text.contains("${o.name}'s ability") && it.text.contains("resolves") } || state.trace.steps.any { it.text.startsWith("${o.name}'s ability triggers") } -> "Yes: ${o.name}'s ability resolves as normal."
                        else -> "No: ${o.name}'s ability didn't resolve (see the outcome above)."
                    }
                    "trigger" -> {
                        if (!o.isOnBattlefield() && state.outcomes.any { it == "${o.name} is countered." }) { state.outcomes += "No: ${o.name} was countered, so it never entered the battlefield. Its enters-the-battlefield ability triggers only on entering, and a countered spell goes to the graveyard instead (701.5a, 603.6a)."; return }
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
                        e.to == "attack" && engine.bridgeStops(o) != null -> "No: ${o.name} can't attack (${engine.bridgeStops(o)}, 508.1c)."
                        e.to == "block" && o.tapped == true && o.blocking == null -> "No: ${o.name} is tapped, and a tapped creature can't be declared as a blocker (509.1a)."
                        e.to == "block" && o.attacking != null -> "No: ${o.name} is attacking, so it isn't there to block (509.1a)."
                        // "which of my creatures can block it?": the attacker's evasion decides it, and answering
                        // yes for a ground creature against a flyer is the wrong answer, not a missing one.
                        e.to == "block" && attackerFacing(o, state) != null ->
                            attackerFacing(o, state)!!.let { att ->
                                engine.cantBlockWhy(att, o)?.let { (why, rules, out) -> state.trace.step(why, *rules.toTypedArray()); "No: $out" }
                                    ?: "Yes: ${o.name} can block ${att.name}."
                            }
                        // "Can it block?" with nothing attacking: the one creature across the table is what it would block.
                        e.to == "block" && state.objects.values.count { it.isOnBattlefield() && it.controller != o.controller && (it.def.isCreature || it.animatedAs != null) } == 1 ->
                            state.objects.values.first { it.isOnBattlefield() && it.controller != o.controller && (it.def.isCreature || it.animatedAs != null) }.let { att ->
                                (if (state.hasKeyword(att, "menace") && state.objects.values.count { b -> b.isOnBattlefield() && b.controller == o.controller && (b.def.isCreature || b.animatedAs != null) && b.tapped != true } < 2) "No: ${att.name} has menace and can't be blocked except by two or more creatures, and ${o.name} is the only creature that could block it (702.110b)." else null)
                                    ?: engine.cantBlockWhy(att, o)?.let { (why, rules, out) -> state.trace.step(why, *rules.toTypedArray()); "No: $out" }
                                    ?: engine.cantWhy(o.id, e.to)?.let { why -> "No: ${o.name} can't block (${if (why == o.name) "its own ability" else why} says so)." }
                                    ?: "Yes: ${o.name} can block ${att.name} if it attacks."
                            }
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
                        o.def.isCreature || o.animatedAs != null -> "${if (o.printedDef.name != o.def.name && !o.token) "${o.printedDef.name} (copying ${o.def.name})" else o.name} is ${state.describePt(o)}."
                        else -> "${o.name} isn't a creature."
                    }
                    "counters" -> state.outcomes += o.counters.filterValues { it > 0 }.let { cs ->
                        if (cs.isEmpty()) "${o.name} has no counters on it."
                        else "${o.name} has " + cs.entries.joinToString(" and ") { (k, n) -> "$n $k counter${if (n == 1) "" else "s"}" } + "."
                    }
                    "isCreature" -> state.outcomes += state.notACreatureBecause(o)?.let { why -> "No: ${o.name} isn't a creature — ${state.player(o.controller).possessive} $why. It's still an enchantment on the battlefield, it keeps its other abilities, and it can't attack, block, or be targeted by anything that needs a creature." }
                        ?: if (!o.isOnBattlefield()) "${o.name} isn't on the battlefield." else if (o.def.isCreature || o.animatedAs != null) "Yes: ${o.name} is a creature (${state.describePt(o)})${if (o.animatedAs != null) " until end of turn" else ""}." else "No: ${o.name} isn't a creature; it's ${o.def.types.joinToString(" ").lowercase()}."
                    "castNow" -> { val p = state.player(e.player ?: o.owner); val fb = "flashback" in o.tempKeywords; state.outcomes += when {
                        o.zone == Zone.GRAVEYARD && (fb || o.def.has("flashback")) -> "Yes: ${o.name} has flashback${if (fb) " until end of turn" else ""}, so ${p.subject.lowercase()} can cast it from ${p.possessive} graveyard for its flashback cost${if (fb) " (its mana cost)" else ""} with the usual timing for its type. It's exiled as it resolves or is countered (702.34a)."
                        o.zone == Zone.GRAVEYARD -> "No: ${o.name} is in ${p.possessive} graveyard, and nothing here lets it be cast from there."
                        o.zone == Zone.EXILE -> "No: ${o.name} is in exile, and nothing here lets it be cast from there."
                        o.zone == Zone.HAND -> "Yes: ${o.name} is in ${p.possessive} hand and can be cast with the usual timing for its type, mana permitting."
                        else -> "${o.name} is ${if (o.isOnBattlefield()) "on the battlefield" else "in ${o.zone.name.lowercase()}"}, not somewhere it can be cast from."
                    } }
                    "controller" -> { val p = state.player(o.controller); state.outcomes += "${o.name} is under ${p.possessive} control${if (!o.isOnBattlefield()) " (it's in ${o.zone.name.lowercase().replace('_', ' ')})" else ""}.${o.controlRevertsTo?.let { " That lasts until end of turn, then it goes back to ${state.player(it).possessive} control." } ?: ""}" }
                    "control" -> {
                        // "Do I get it back?": the turn is played out to its cleanup step first, once combat is done.
                        if (o.controlRevertsTo != null) engine.beginStep("cleanup", e.targets.firstOrNull()?.takeIf { t -> state.players.any { it.id == t } } ?: state.activePlayer ?: o.controller)
                        val p = state.player(e.player ?: "me"); state.outcomes += if (o.controller == p.id) "Yes: ${o.name} is under ${p.possessive} control." else "No: ${o.name} is under ${state.player(o.controller).possessive} control${o.controlRevertsTo?.let { r -> " until end of turn (it goes back to ${state.player(r).possessive} at cleanup)" } ?: ""}." }
                    "mana" -> state.outcomes += engine.manaOptions(o.id)
                    "activate" -> {
                        val tapAbilities = engine.activatedAbilitiesOf(o).filter { it.cost.contains("{T}") }
                        val any = engine.activatedAbilitiesOf(o)
                        val lock = engine.activationLock(o)
                        state.outcomes += when {
                            !o.isOnBattlefield() -> "No: ${o.name} isn\'t on the battlefield, so its abilities can\'t be activated."
                            lock != null && any.isNotEmpty() -> "No: ${lock.name} says ${o.name}\'s activated abilities can\'t be activated${if (lock.def.oracleText.contains("mana abilit", true)) " (mana abilities excepted, as it says)" else ""}."
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
                        state.outcomes += if (o.phasedOut) "No: ${o.name} is phased out — treated as though it doesn't exist until it phases in at its controller's next untap step, so nothing happened to it." else if (e.to == "die") { if (o.isOnBattlefield()) "No: ${o.name} is still on the battlefield." else if (o.zone == mtg.judge.engine.Zone.GRAVEYARD) "Yes: ${o.name} died (it's in the graveyard)." else "No: ${o.name} didn't die, but it left the battlefield; it's in $where." }
                        else { if (o.isOnBattlefield()) "Yes: ${o.name} is still on the battlefield." else "No: ${o.name} is in $where." }
                        if (o.zone == mtg.judge.engine.Zone.EXILE && (o.def.has("persist") || o.def.has("undying"))) { val kw = if (o.def.has("persist")) "persist" else "undying"; state.outcomes += "${o.name}'s $kw doesn't return it: it was exiled instead of going to the graveyard, and $kw returns it only from the graveyard (${if (kw == "persist") "702.79a" else "702.93a"})." }
                    }
                    else -> {}
                }
            }
            "pass" -> engine.resolveTop()
            "enter" -> engine.enter(e.obj ?: throw JudgeException("enter needs an object"), e.to)
            // "My opponent gains control of my creature": a control change with no card behind it.
            "gaincontrol" -> engine.gainControl(e.player ?: throw JudgeException("gainControl needs a player"), e.obj ?: throw JudgeException("gainControl needs an object"), e.to == "eot")
            // "I lose the flip": the coin flip a card asks for, said rather than randomised (705.2).
            "flip" -> state.coinFlips += (e.to ?: "lose").lowercase()
            // "I play a land": counted against the one land a player may play each turn (305.2).
            "playland" -> { val objId = e.obj ?: throw JudgeException("playLand needs an object"); engine.playLand(e.player ?: state.obj(objId).controller, objId) }
            "leave" -> if (e.targets.isEmpty()) engine.leave(e.obj ?: throw JudgeException("leave needs an object"), zone(e.to ?: "graveyard"))
                       else engine.leaveTogether(listOf(e.obj ?: throw JudgeException("leave needs an object")) + e.targets, zone(e.to ?: "graveyard"))
            "damage" -> {
                val srcName = e.source?.let { state.objects[it]?.name } ?: e.source ?: "A source"
                // Damage from a source nobody named still has a source, and "whenever ~ is dealt damage" triggers
                // watch for one; without a stand-in object the event was never raised and Boros Reckoner sat quiet.
                if (state.objects.values.none { it.name == srcName }) state.add(GameObject(freshId(state, srcName), OracleParser.parse("generic-damage-source", srcName, "Instant", null, 0.0, "", null, null, emptyList(), ""), Zone.EXILE, e.player ?: state.players.first().id))
                engine.dealDamage(srcName, targets.firstOrNull() ?: throw JudgeException("damage needs a target"), e.amount ?: throw JudgeException("damage needs an amount"))
            }
            "statecheck" -> engine.stateBasedActions()
            "attack" -> { val objId = e.obj ?: throw JudgeException("attack needs an object"); engine.declareAttacker(e.player ?: state.obj(objId).controller, objId, targets.firstOrNull() ?: Ref.Player(state.opponentsOf(state.obj(objId).controller).firstOrNull()?.id ?: throw JudgeException("no defending player"))) }
            "block" -> { val objId = e.obj ?: throw JudgeException("block needs an object"); val att = (targets.firstOrNull() as? Ref.Obj)?.id ?: state.objects.values.lastOrNull { it.attacking != null }?.id ?: throw JudgeException("block needs the attacker"); engine.declareBlocker(e.player ?: state.obj(objId).controller, objId, att) }
            // The stack empties before attackers are declared. Without this a creature cast in the same breath
            // ("I cast Grizzly Bears and attack") was still on the stack, and the answer was that no creatures
            // of yours were described rather than that the one you cast is summoning sick.
            "attackall" -> { val who = e.player ?: state.players.first().id; engine.emptyStackFirst("declaring attackers"); val def = targets.firstOrNull() ?: Ref.Player(state.opponentsOf(who).firstOrNull()?.id ?: throw JudgeException("no defending player")); val cs = state.objects.values.filter { it.controller == who && it.isOnBattlefield() && it.def.isCreature }; if (cs.isEmpty()) state.unsupported += mtg.judge.engine.Unsupported("attack", "${state.player(who).subject} said to attack with everything, but no creatures of ${if (state.player(who).you) "yours" else state.player(who).possessive} were described."); cs.forEach { engine.declareAttacker(who, it.id, def) } }
            "combatdamage" -> engine.combatDamage()
            "step", "beginstep" -> engine.beginStep((e.to ?: "upkeep").lowercase(), e.player ?: state.activePlayer ?: state.players.first().id)
            else -> throw JudgeException("Unknown event verb '${e.verb}'")
        }
    }

    /** The attacker a creature would be blocking: one attacking its controller, or a planeswalker they control. */
    private fun attackerFacing(o: mtg.judge.engine.GameObject, state: GameState): mtg.judge.engine.GameObject? =
        state.objects.values.lastOrNull { a -> a.isOnBattlefield() && a.attacking != null && when (val d = a.attacking) {
            is mtg.judge.engine.Ref.Player -> d.id == o.controller
            is mtg.judge.engine.Ref.Obj -> state.objects[d.id]?.controller == o.controller
            else -> false
        } }

    private fun describeEvent(e: EventSpec, state: GameState): String {
        val who = e.player?.let { state.players.firstOrNull { p -> p.id == it }?.let { p -> if (p.you) "you" else p.name } ?: it }
        val tg = if (e.targets.isEmpty()) "" else " targeting " + e.targets.joinToString(" and ") { runCatching { state.nameOf(parseRef(it, state)) }.getOrDefault(it) }
        return when (e.verb.lowercase()) {
            "cast" -> { val land = e.card?.let { c -> runCatching { cardDef(c, state) }.getOrNull() }?.let { "Land" in it.types && !it.isInstantOrSorcery } == true
                "${who ?: "you"} ${if (land) (if (who == null || who == "you") "play" else "plays") else (if (who == null || who == "you") "cast" else "casts")} ${e.card ?: state.objects[e.obj]?.name ?: e.obj}${if (e.to == "overload") " overloaded" else if (e.to == "kicked") " kicked" else if (e.to == "evoke") " for its evoke cost" else ""}$tg" }
            "activate" -> "${who ?: "controller"} ${if (who == "you") "activate" else "activates"} ${state.objects[e.obj]?.name ?: e.obj}${e.to?.takeIf { e.abilityIndex == null && (it == "ultimate" || Regex("""^[+\u2212-]?\d+$""").matches(it)) }?.let { " ($it)" } ?: ""}$tg"
            "discard" -> "${who ?: "you"} ${if (who == null || who == "you") "discard" else "discards"} ${state.objects[e.obj]?.name ?: e.obj}"
            "counters" -> "${e.amount ?: 1} ${e.to ?: "+1/+1"} counter(s) are put on ${state.objects[e.obj]?.name ?: e.obj}"
            "tap" -> "${state.objects[e.obj]?.name ?: e.obj} becomes tapped"
            "untap" -> "${state.objects[e.obj]?.name ?: e.obj} untaps"
            "mill" -> "${who ?: "the player"} ${if (who == "you") "mill" else "mills"} ${e.amount ?: 1} card${if ((e.amount ?: 1) == 1) "" else "s"}"
            "proliferate" -> "${who ?: "the player"} ${if (who == "you") "proliferate" else "proliferates"}"
            "setlife" -> "${who ?: "the player"} ${if (who == "you") "go" else "goes"} to ${e.amount ?: "?"} life"
            "monarch" -> "${who ?: "the player"} ${if (who == "you") "are" else "is"} the monarch"
            "setpt" -> "${state.objects[e.obj]?.name ?: e.obj} becomes ${e.to ?: "?"}"
            "concede" -> "${who ?: "the player"} ${if (who == "you") "concede" else "concedes"}"
            "discardcount" -> "${who ?: "the player"} ${if (who == "you") "discard" else "discards"} ${e.amount ?: 1} card${if ((e.amount ?: 1) == 1) "" else "s"}"
            "mananow" -> "${who ?: "the player"} ${if (who == "you") "have" else "has"} ${e.amount} mana available from here"
            "token" -> "${who ?: "you"} ${if (who == null || who == "you") "create" else "creates"} ${e.amount ?: 1} ${e.card?.name}${if ((e.amount ?: 1) == 1) "" else "s"}"
            "poison" -> "${who ?: "the player"} ${if (who == "you") "get" else "gets"} ${e.amount ?: 1} poison counter${if ((e.amount ?: 1) == 1) "" else "s"}"
            "trigger" -> "${state.objects[e.obj]?.name ?: e.obj}'s ability triggers$tg"
            "choose" -> "${who ?: "controller"} ${if (who == "you") "choose" else "chooses"} ${e.to?.substringAfter(':')?.let { state.objects[it]?.name ?: it } ?: "?"} for ${state.objects[e.obj]?.name ?: e.obj}'s ability"
            "blink" -> "${state.objects[e.obj]?.name ?: e.obj} is exiled and returned to the battlefield"
            "reanimate" -> "${state.objects[e.obj]?.name ?: e.obj} is put from the graveyard onto the battlefield"
            "regenerate" -> "${state.objects[e.obj]?.name ?: e.obj} has a regeneration shield"
            "save" -> "${who ?: "you"} ${if (who == null || who == "you") "try" else "tries"} to save ${state.objects[e.obj]?.name ?: e.obj}"
            "kill" -> "${who ?: "you"} ${if (who == null || who == "you") "try" else "tries"} to kill ${e.targets.firstOrNull()?.let { t -> state.objects[t]?.name ?: state.players.firstOrNull { p -> p.id == t }?.name ?: t } ?: "?"}"
            "sacrifice" -> "${who ?: "controller"} ${if (who == "you") "sacrifice" else "sacrifices"} ${state.objects[e.obj]?.name ?: e.obj}"
            "fight" -> "${state.objects[e.obj]?.name ?: e.obj} fights ${e.targets.firstOrNull()?.let { state.objects[it]?.name ?: it } ?: "?"}"
            "gainlife" -> "${who ?: "the player"} ${if (who == "you") "gain" else "gains"} ${e.amount ?: 1} life"
            "loselife" -> "${who ?: "the player"} ${if (who == "you") "lose" else "loses"} ${e.amount ?: 1} life"
            "draw" -> "${who ?: "the player"} ${if (who == "you") "draw" else "draws"} ${e.amount ?: 1} card${if ((e.amount ?: 1) > 1) "s" else ""}"
            "pay" -> "${who ?: "the player"} ${if (e.to == "no") "${if (who == "you") "don't" else "doesn't"} pay" else "${if (who == "you") "pay" else "pays"}"}"
            "resolve", "pass" -> "the top of the stack resolves"
            "resolveall" -> "everything on the stack resolves"
            "stop" -> "${who ?: "you"} ${if (who == null || who == "you") "try" else "tries"} to stop the spell on the stack"
            "ask" -> if (e.to?.startsWith("text:") == true) "question: answered in the outcome" else if (e.to == "manaAvailable" || e.to == "manaNextTurn") "question: how much mana ${if (who == null || who == "you") "do you" else "does $who"} ${if (e.to == "manaNextTurn") "get next turn" else "have"}?" else if (e.to == "stillResolves") "question: does ${state.objects[e.obj]?.name ?: e.obj}'s ability still resolve?" else if (e.to == "tokenMade") "question: ${if (who == null || who == "you") "do you" else "does $who"} get ${e.card?.name?.let { "a $it token" } ?: "a token"}?" else if (e.to == "spellCost") "question: how much does ${state.objects[e.obj]?.name ?: e.card?.name ?: "the spell"} cost?" else if (e.to == "countered") "question: is ${e.card?.name ?: "the spell"} countered?" else if (e.to == "untapLands") "question: ${if (who == null || who == "you") "do your" else "do $who's"} lands untap?" else if (e.to == "respond") "question: can ${if (who == null || who == "you") "you" else who} respond?" else if (e.to == "gotLand") "question: ${if (who == null || who == "you") "do you" else "does $who"} get a land?" else if (e.to == "tokenMade") "question: ${if (who == null || who == "you") "do you" else "does $who"} get ${e.card?.name}?" else if (e.to == "hitsOwn") "question: does ${e.card?.name} hit ${if (who == null || who == "you") "your" else "$who's"} own permanents?" else if (e.to?.startsWith("count:") == true) "question: how many ${e.to.removePrefix("count:")} ${if (who == null || who == "you") "do you" else "does $who"} have?" else if (e.to == "canCounter") "question: can ${if (who == null || who == "you") "you" else who} counter it?" else if (e.to == "playerGain" || e.to == "playerLost") "question: how much life ${if (who == null || who == "you") "do you" else "does $who"} ${if (e.to == "playerGain") "gain" else "lose"}?" else if (e.to == "playerDraw") "question: ${if (who == "you") "do you" else "does $who"} draw?" else if (e.to == "playerLife") "question: what ${if (who == "you") "is your" else "is $who's"} life total?" else if (e.to == "playerSurvive") "question: ${if (who == "you") "do you" else "does $who"} survive?" else if (e.to == "playerDie") "question: ${if (who == "you") "do you" else "does $who"} lose?" else if (e.to == "playerWin") "question: ${if (who == "you") "do you" else "does $who"} win?" else if (e.to == "playerDamage") "question: ${if (who == "you") "do you" else "does $who"} take damage?" else if (e.to == "controller") "question: who controls ${state.objects[e.obj]?.name ?: e.obj}?" else if (e.to == "identity") "question: what is ${state.objects[e.obj]?.name ?: e.obj} now?" else if (e.to == "castNow") "question: can ${if (who == null || who == "you") "you" else who} cast ${state.objects[e.obj]?.name ?: e.obj} now?" else "question: ${if (e.to == "block" || e.to == "attack") "can" else "does"} ${state.objects[e.obj]?.name ?: e.obj} ${if (e.to == "damage") "deal damage to ${e.targets.firstOrNull()?.let { t -> state.players.firstOrNull { it.id == t }?.let { if (it.you) "you" else it.name } } ?: "the player"}" else e.to}?"
            "enter" -> "${state.objects[e.obj]?.name ?: e.obj} enters the battlefield"
            "playland" -> "${who ?: "you"} play${if (who == null || who == "you") "" else "s"} ${state.objects[e.obj]?.name ?: e.obj}"
            "flip" -> "${who ?: "you"} ${e.to ?: "lose"} the coin flip"
            "gaincontrol" -> "${who ?: "you"} gain${if (who == null || who == "you") "" else "s"} control of ${state.objects[e.obj]?.name ?: e.obj}"
            "leave" -> if (e.targets.isEmpty()) "${state.objects[e.obj]?.name ?: e.obj} goes to ${e.to}" else "${(listOf(e.obj) + e.targets).joinToString(" and ") { state.objects[it]?.name ?: it ?: "?" }} go to ${e.to} at the same time"
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
        // Player shorthand for a mode: "bounce" is "return … to its owner's hand", "shatter" is "destroy … artifact".
        val shorthand = mapOf("bounce" to "return owner hand", "shatter" to "destroy artifact", "counterspell" to "counter spell", "fog" to "prevent all damage",
            "tuck" to "bottom library", "wrath" to "destroy all creatures", "naturalize" to "destroy artifact enchantment", "disenchant" to "destroy artifact enchantment")
        val want = words(shorthand[phrase.trim()] ?: phrase)
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
        // "my 3/3 is blocked by a 2/2 and I cast Giant Growth on it": after a block "it" has two readings, and the
        // parser hands both over, the speaker's own side first. disambiguate() picks between them where it knows
        // the target's filter; everywhere else, take the first that names something rather than dropping the event.
        if ('|' in s) for (part in s.split('|')) if (part.isNotEmpty()) {
            try { return parseRef(part, state) } catch (e: JudgeException) { }
        }
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
            "playerDie" -> if (p.lost) "Yes: ${p.subject} ${p.v("has", "have")} lost the game." else "No: $alive" + (if (p.librarySize == 0) " An empty library isn't a loss by itself: ${p.subject.lowercase()} ${p.v("loses", "lose")} only when ${p.subject.lowercase()} would draw from it, at the next state-based check (704.5b)." else "")
            "playerWin" -> (if (won) "Yes: ${p.subject} ${p.v("has", "have")} won the game." else if (p.lost) "No: ${p.subject} ${p.v("has", "have")} lost the game." else "No: ${p.subject} ${p.v("hasn't", "haven't")} won; ${alive.replaceFirstChar { it.lowercase() }}") + (state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && it.def.abilities.filterIsInstance<mtg.judge.engine.TriggeredAbility>().any { a -> a.trigger == mtg.judge.engine.Trigger.ThisEnters && a.text.contains("win the game", true) } }.takeIf { !won }?.firstOrNull()?.let { o -> " ${o.name}'s ability triggers only as it enters the battlefield; it is already there, so nothing checks now. Say you cast it if that is what you mean." } ?: "")
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
