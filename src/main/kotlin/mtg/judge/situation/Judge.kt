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

    fun answer(sit: Situation): Answer {
        val understood = mutableListOf<String>()
        val state = GameState(sit.players.map { Player(it.id, it.name, it.life) }, LinkedHashMap(), activePlayer = sit.turn.activePlayer, phase = sit.turn.phase, step = sit.turn.step)
        val engine = Engine(state)

        for (o in sit.objects) {
            val def = cardDef(o.card, state) ?: continue
            state.add(GameObject(o.id, def, zone(o.zone), o.controller, o.owner ?: o.controller, o.tapped, o.summoningSick, o.counters.toMutableMap(), o.damage, o.token)).also {
                it.timestamp = state.tick(); it.attachedTo = o.attachedTo
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

        understood += "Players: " + state.players.joinToString(", ") { (if (it.you) "you" else it.name) + (it.life?.let { l -> " ($l life)" } ?: "") } + (state.activePlayer?.let { "; it's ${state.player(it).possessive} turn" } ?: "; whose turn it is wasn't stated")
        state.objects.values.groupBy { it.zone }.forEach { (zone, objs) ->
            understood += "${zone.name.lowercase().replaceFirstChar { it.uppercase() }}: " + objs.joinToString(", ") { "${it.name} [${it.id}] (${state.player(it.controller).possessive}${if (it.def.isCreature && it.isOnBattlefield()) ", " + state.describePt(it) else ""}${if (it.tapped == true) ", tapped" else ""}${if (it.damage > 0) ", ${it.damage} damage" else ""}${it.attachedTo?.let { a -> ", attached to ${state.objects[a]?.name ?: a}" } ?: ""}${it.counters["loyalty"]?.let { l -> ", loyalty $l" } ?: ""})" }
        }
        if (state.stack.isNotEmpty()) understood += "Stack (bottom to top): " + state.stack.joinToString(", ") { "${it.describe} [${it.id}]" + (if (it.targets.isNotEmpty()) " targeting " + it.targets.joinToString(" & ") { t -> state.nameOf(t) } else "") }

        // Rules text on the described permanents that the engine can't model is said up front, so a silent "nothing changes" is never a lie.
        for (o in state.objects.values.filter { it.isOnBattlefield() }) {
            val unparsed = o.def.abilities.filterIsInstance<mtg.judge.engine.UnparsedAbility>().map { it.text }
            if (unparsed.isNotEmpty()) state.unsupported += mtg.judge.engine.Unsupported(o.name, "Rules text not modeled: " + unparsed.joinToString(" | "))
        }
        // The described state may already call for state-based actions (a 1/1 under an opposing Elesh Norn).
        engine.stateBasedActions()
        for ((i, e) in sit.events.withIndex()) {
            try {
                understood += "Event ${i + 1}: " + describeEvent(e, state)
                apply(e, state, engine)
            } catch (ex: JudgeException) {
                state.unsupported += mtg.judge.engine.Unsupported("event ${i + 1} (${e.verb})", ex.message ?: "failed")
            }
        }
        if (sit.events.isEmpty() && state.stack.isNotEmpty()) { understood += "No events given; resolving the stack."; engine.resolveAll() }
        if (state.objects.values.any { it.attacking != null } && !state.combatDamageDealt) {
            understood += "Combat damage is dealt after the described actions."
            engine.resolveAll(); engine.combatDamage()
        }

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
                engine.cast(player, def, disambiguate(e.targets, def.spellEffect?.targets() ?: emptyList(), player, state, engine), existing?.id, e.modes, overload = e.to == "overload")
            }
            "draw" -> engine.draw(e.player ?: throw JudgeException("draw needs a player"), e.amount ?: 1)
            "sacrifice" -> { val objId = e.obj ?: throw JudgeException("sacrifice needs an object"); engine.sacrifice(e.player ?: state.obj(objId).controller, objId) }
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
                // "+1" / "-3" names a loyalty ability by its cost.
                val idx = e.abilityIndex
                    ?: e.to?.takeIf { it == "mana" }?.let { obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { a -> a.effect is Effect.AddMana || (a.effect as? Effect.Seq)?.effects?.firstOrNull() is Effect.AddMana }.takeIf { it >= 0 } }
                    ?: e.to?.let { cost -> obj.def.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { it.cost.replace('\u2212', '-') == cost.replace('\u2212', '-') }.takeIf { it >= 0 } }
                engine.activate(e.player ?: obj.controller, objId, idx, targets)
            }
            "trigger" -> engine.assertTrigger(e.obj ?: throw JudgeException("trigger needs an object"), e.abilityIndex, targets)
            "resolve" -> engine.resolveTop()
            "resolveall" -> engine.resolveAll()
            "pass" -> engine.resolveTop()
            "enter" -> engine.enter(e.obj ?: throw JudgeException("enter needs an object"))
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
                "${who ?: "you"} ${if (land) (if (who == null || who == "you") "play" else "plays") else (if (who == null || who == "you") "cast" else "casts")} ${e.card ?: e.obj}${if (e.to == "overload") " overloaded" else ""}$tg" }
            "activate" -> "${who ?: "controller"} ${if (who == "you") "activate" else "activates"} ${state.objects[e.obj]?.name ?: e.obj}${e.to?.takeIf { e.abilityIndex == null && Regex("""^[+\u2212-]?\d+$""").matches(it) }?.let { " ($it)" } ?: ""}$tg"
            "trigger" -> "${state.objects[e.obj]?.name ?: e.obj}'s ability triggers$tg"
            "regenerate" -> "${state.objects[e.obj]?.name ?: e.obj} has a regeneration shield"
            "sacrifice" -> "${who ?: "controller"} ${if (who == "you") "sacrifice" else "sacrifices"} ${state.objects[e.obj]?.name ?: e.obj}"
            "gainlife" -> "${who ?: "the player"} ${if (who == "you") "gain" else "gains"} ${e.amount ?: 1} life"
            "loselife" -> "${who ?: "the player"} ${if (who == "you") "lose" else "loses"} ${e.amount ?: 1} life"
            "draw" -> "${who ?: "the player"} ${if (who == "you") "draw" else "draws"} ${e.amount ?: 1} card${if ((e.amount ?: 1) > 1) "s" else ""}"
            "pay" -> "${who ?: "the player"} ${if (e.to == "no") "${if (who == "you") "don't" else "doesn't"} pay" else "${if (who == "you") "pay" else "pays"}"}"
            "resolve", "pass" -> "the top of the stack resolves"
            "resolveall" -> "everything on the stack resolves"
            "enter" -> "${state.objects[e.obj]?.name ?: e.obj} enters the battlefield"
            "leave" -> "${state.objects[e.obj]?.name ?: e.obj} goes to ${e.to}"
            "damage" -> "${e.source} deals ${e.amount} damage$tg"
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

    companion object {
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
