package mtg.judge.situation

import mtg.judge.carddb.Card
import mtg.judge.carddb.CardRepo
import mtg.judge.carddb.Resolution
import mtg.judge.cr.RulesRepo
import mtg.judge.engine.CardDef
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
            state.objects[o.id] = GameObject(o.id, def, zone(o.zone), o.controller, o.owner ?: o.controller, o.tapped, o.summoningSick, o.counters.toMutableMap(), o.damage, o.token).also { it.timestamp = state.tick() }
        }
        for (s in sit.stack) {
            val kind = when (s.kind.lowercase()) { "triggered" -> StackKind.TRIGGERED; "activated" -> StackKind.ACTIVATED; else -> StackKind.SPELL }
            val source = s.source?.let { state.objects[it] } ?: s.card?.let { ref -> cardDef(ref, state)?.let { def -> GameObject(s.id ?: freshId(state, def.name), def, Zone.STACK, s.controller).also { state.objects[it.id] = it } } }
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
            understood += "${zone.name.lowercase().replaceFirstChar { it.uppercase() }}: " + objs.joinToString(", ") { "${it.name} [${it.id}] (${state.player(it.controller).possessive}${if (it.tapped == true) ", tapped" else ""}${if (it.damage > 0) ", ${it.damage} damage" else ""})" }
        }
        if (state.stack.isNotEmpty()) understood += "Stack (bottom to top): " + state.stack.joinToString(", ") { "${it.describe} [${it.id}]" + (if (it.targets.isNotEmpty()) " targeting " + it.targets.joinToString(" & ") { t -> state.nameOf(t) } else "") }

        for ((i, e) in sit.events.withIndex()) {
            try {
                understood += "Event ${i + 1}: " + describeEvent(e, state)
                apply(e, state, engine)
            } catch (ex: JudgeException) {
                state.unsupported += mtg.judge.engine.Unsupported("event ${i + 1} (${e.verb})", ex.message ?: "failed")
            }
        }
        if (sit.events.isEmpty() && state.stack.isNotEmpty()) { understood += "No events given; resolving the stack."; engine.resolveAll() }

        val cited = state.trace.steps.flatMap { it.rules }.distinct()
        val citations = cited.associateWith { n -> rules?.rule(n)?.text ?: "" }.filterValues { it.isNotEmpty() }
        return Answer(
            outcome = if (state.outcomes.isEmpty()) listOf("Nothing changes.") else state.outcomes.distinct(),
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
                engine.cast(player, def, targets, existing?.id)
            }
            "activate" -> { val objId = e.obj ?: throw JudgeException("activate needs an object"); engine.activate(e.player ?: state.obj(objId).controller, objId, e.abilityIndex, targets) }
            "trigger" -> engine.assertTrigger(e.obj ?: throw JudgeException("trigger needs an object"), e.abilityIndex, targets)
            "resolve" -> engine.resolveTop()
            "resolveall" -> engine.resolveAll()
            "pass" -> engine.resolveTop()
            "enter" -> engine.enter(e.obj ?: throw JudgeException("enter needs an object"))
            "leave" -> engine.leave(e.obj ?: throw JudgeException("leave needs an object"), zone(e.to ?: "graveyard"))
            "damage" -> engine.dealDamage(e.source?.let { state.objects[it]?.name } ?: e.source ?: "A source", targets.firstOrNull() ?: throw JudgeException("damage needs a target"), e.amount ?: throw JudgeException("damage needs an amount"))
            "statecheck" -> engine.stateBasedActions()
            "attack", "block" -> state.unsupported += mtg.judge.engine.Unsupported("combat", "Attacking and blocking aren't modeled yet (${state.objects[e.obj]?.name ?: e.obj} ${e.verb}s); rules 506–511 apply.")
            else -> throw JudgeException("Unknown event verb '${e.verb}'")
        }
    }

    private fun describeEvent(e: EventSpec, state: GameState): String {
        val who = e.player?.let { state.players.firstOrNull { p -> p.id == it }?.let { p -> if (p.you) "you" else p.name } ?: it }
        val tg = if (e.targets.isEmpty()) "" else " targeting " + e.targets.joinToString(" and ") { runCatching { state.nameOf(parseRef(it, state)) }.getOrDefault(it) }
        return when (e.verb.lowercase()) {
            "cast" -> "${who ?: "you"} ${if (who == null || who == "you") "cast" else "casts"} ${e.card ?: e.obj}$tg"
            "activate" -> "${who ?: "controller"} activates ${state.objects[e.obj]?.name ?: e.obj}$tg"
            "trigger" -> "${state.objects[e.obj]?.name ?: e.obj}'s ability triggers$tg"
            "resolve", "pass" -> "the top of the stack resolves"
            "resolveall" -> "everything on the stack resolves"
            "enter" -> "${state.objects[e.obj]?.name ?: e.obj} enters the battlefield"
            "leave" -> "${state.objects[e.obj]?.name ?: e.obj} goes to ${e.to}"
            "damage" -> "${e.source} deals ${e.amount} damage$tg"
            "attack" -> "${who ?: "you"} attack${if (who == null || who == "you") "" else "s"} with ${state.objects[e.obj]?.name ?: e.obj}$tg"
            "block" -> "${who ?: "opponent"} block${if (who == null || who == "you") "" else "s"} with ${state.objects[e.obj]?.name ?: e.obj}"
            else -> e.verb
        }
    }

    /** `rhystic` (object), `opp` (player), `s1` (stack), `rhystic:trigger` / `bolt:spell` (stack item by source). */
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
            return OracleParser.parse(card.oracleId, card.name, card.typeLine, card.manaCost, card.manaValue, card.colors, card.power, card.toughness, keywords, card.oracleText)
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
