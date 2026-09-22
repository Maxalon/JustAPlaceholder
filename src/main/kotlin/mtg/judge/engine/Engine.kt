package mtg.judge.engine

/**
 * Applies events to a [GameState] and records a rule-cited trace of everything that happens.
 *
 * Scope of this first version: casting spells and activating abilities, triggered abilities
 * going on the stack (including APNAP ordering), resolution with target legality, countering,
 * simple effects (damage, draw, destroy, exile, tap, pump, life), permanents entering and
 * leaving the battlefield, and the state-based actions those produce. Anything the Oracle
 * parser could not model is reported as unsupported rather than guessed.
 */
class Engine(val state: GameState) {
    private val trace get() = state.trace

    // ---- events ----------------------------------------------------------------------------

    fun cast(playerId: String, card: CardDef, targets: List<Ref>, objectId: String? = null, modes: List<Int> = emptyList(), overload: Boolean = false, x: Int? = null, kicked: Boolean = false, evoked: Boolean = false, flashback: Boolean = false, choice: String? = null, payLife: Int? = null): StackItem? {
        val player = state.player(playerId)
        val obj = objectId?.let { state.objects[it] } ?: state.add(GameObject(objectId ?: freshObjectId(card.name), card, Zone.HAND, playerId))
        state.stack.firstOrNull { it.kind == StackKind.SPELL && it.source.def.has("split second") }?.let { ss ->
            trace.step("${ss.source.name} has split second and is on the stack, so players can't cast spells or activate abilities that aren't mana abilities. ${card.name} can't be cast now.", "702.61a")
            state.outcomes += "${card.name} can't be cast while ${ss.source.name} is on the stack (split second)."; return null
        }
        fun castLockApplies(e: StaticEffect.CantCastFiltered, lock: GameObject): Boolean =
            spellMatches(e.filter, card) && (e.minManaValue == null || card.manaValue.toInt() >= e.minManaValue) && (!e.xInCost || (card.manaCost ?: "").contains("{X}")) &&
                (!e.chosenNumber || lock.chosenName?.toIntOrNull() == card.manaValue.toInt())
        state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { e -> e is StaticEffect.CantCastFiltered && castLockApplies(e, o) } }?.let { lock ->
            val e = lock.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.filterIsInstance<StaticEffect.CantCastFiltered>().first { castLockApplies(it, lock) }
            trace.step("${lock.name} says ${e.filter.raw}s${e.minManaValue?.let { " with mana value $it or greater" } ?: ""}${if (e.chosenNumber) " with mana value ${lock.chosenName}" else ""}${if (e.xInCost) " with {X} in their mana costs" else ""} can't be cast, and ${card.name} is one (mana value ${card.manaValue.toInt()}), so it can't be cast at all.", "604.2", "101.2")
            state.outcomes += "${card.name} can't be cast (${lock.name})."; return null
        }
        // Silence: a turn-long stop on casting, which ends in the cleanup step.
        if (playerId in state.cantCastThisTurn) {
            val p = state.player(playerId)
            trace.step("${p.subject} can't cast spells this turn, so ${card.name} can't be cast.", "601.3", "611.2a")
            state.outcomes += "${card.name} can't be cast (${p.subject.lowercase()} can't cast spells this turn)."; return null
        }
        // Rule of Law, Ethersworn Canonist: a spell too many this turn.
        for (src in state.objects.values.filter { it.isOnBattlefield() }) {
            for (e in src.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.filterIsInstance<StaticEffect.SpellsPerTurn>()) {
                if (e.filter != null && !spellMatches(e.filter, card)) continue
                val already = if (e.filter == null) (state.spellsThisTurn[playerId] ?: 0) else (state.matchingSpellsThisTurn[playerId] ?: emptyList()).count { spellMatches(e.filter, it) }
                if (already >= e.count) {
                    val what = e.filter?.raw ?: "spell"
                    trace.step("${src.name} says each player can't cast more than ${e.count} $what${if (e.count == 1) "" else "s"} each turn, and ${state.player(playerId).subject.lowercase()} ${state.player(playerId).v("has", "have")} already cast $already this turn, so ${card.name} can't be cast.", "604.2", "101.2")
                    state.outcomes += "${card.name} can't be cast (${src.name})."; return null
                }
            }
        }
        state.objects.values.firstOrNull { it.isOnBattlefield() && it.chosenName?.equals(card.name, true) == true && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { s -> s is StaticEffect.CantCastNamed } }?.let { mage ->
            trace.step("${mage.name} names ${card.name}, and spells with the chosen name can't be cast, so ${card.name} can't be cast at all while ${mage.name} is on the battlefield.", "604.2", "101.2")
            state.outcomes += "${card.name} can't be cast (${mage.name} names it)."; return null
        }
        state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { s -> s is StaticEffect.OwnTurnOnly } }?.let { dosan ->
            val active = state.activePlayer
            if (active != null && active != playerId) {
                trace.step("${dosan.name} says players can cast spells only during their own turns, and it is ${state.player(active).possessive} turn, so ${state.player(playerId).subject.lowercase()} can't cast ${card.name} at all.", "307.1", "117.1a")
                state.outcomes += "${card.name} can't be cast (${dosan.name}: only on its caster's own turn)."; return null
            }
            if (active == null) state.clarifications += Clarification("whose turn it is", "${dosan.name} lets a player cast spells only during their own turn; whose turn is it?")
        }
        state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != playerId && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { s -> s is StaticEffect.OpponentsSorcerySpeed } }?.let { teferi ->
            val offTiming = state.stack.isNotEmpty() || state.phase == "combat" || (state.activePlayer != null && state.activePlayer != playerId) || state.step in setOf("upkeep", "draw", "end", "cleanup", "untap")
            if (offTiming) {
                trace.step("${teferi.name} says ${state.player(playerId).subject.lowercase()} can cast spells only any time ${state.player(playerId).subject.lowercase()} could cast a sorcery: during ${state.player(playerId).possessive} own main phase with an empty stack. ${card.name} can't be cast now${if (state.stack.isNotEmpty()) " (something is on the stack)" else ""}.", "307.1", "117.1a")
                state.outcomes += "${card.name} can't be cast (${teferi.name}: sorcery speed only)."; return null
            }
            // Whose turn it is decides this, and the question may not have said. Left silent, the answer read as
            // if Teferi weren't there at all.
            if (state.activePlayer == null) state.clarifications += Clarification("whose turn it is",
                "${teferi.name} lets ${state.player(playerId).subject.lowercase()} cast spells only at sorcery speed — during ${state.player(playerId).possessive} own main phase with an empty stack. Whose turn is it? ${card.name} is taken as cast at a legal time.")
        }
        state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != playerId && it.controller == state.activePlayer && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { s -> s is StaticEffect.OpponentsLockedOnYourTurn } }?.let { ab ->
            trace.step("It's ${state.player(ab.controller).possessive} turn and ${ab.name} says ${state.player(ab.controller).possessive} opponents can't cast spells during it. ${card.name} can't be cast now; it could be cast on ${state.player(playerId).possessive} own turn (or another opponent's).", "101.2")
            state.outcomes += "${card.name} can't be cast (${ab.name}: not during ${state.player(ab.controller).possessive} turn)."; return null
        }
        // Drannith Magistrate: a spell cast from anywhere but its controller's hand. Only a zone the situation
        // actually gave is checked; a card nobody placed is taken to be in hand, as it usually is.
        val fromZone = if (flashback) Zone.GRAVEYARD else obj.zone
        if (fromZone in setOf(Zone.GRAVEYARD, Zone.EXILE, Zone.COMMAND, Zone.LIBRARY)) {
            state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }
                .any { e -> e is StaticEffect.CantCastFromZone && fromZone.name.lowercase() in e.zones && (!e.opponentsOnly || o.controller != playerId) } }?.let { lock ->
                trace.step("${lock.name} says ${if (lock.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.filterIsInstance<StaticEffect.CantCastFromZone>().first().opponentsOnly) "${state.player(lock.controller).possessive} opponents can't" else "players can't"} cast spells from ${zoneName(fromZone, obj)}, so ${card.name} can't be cast at all.", "601.2", "113.6c")
                state.outcomes += "${card.name} can't be cast from ${zoneName(fromZone, obj)} (${lock.name})."
                return null
            }
        }
        // Cost taxes (Thalia, the commander tax) against the mana the situation said is available.
        run {
            val taxes = state.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CostTax>().filter { t -> (t.whose == null || (t.whose == Who.YOU) == (o.controller == playerId)) && spellMatches(t.filter, card) }.map { o to it } }
            val commanderTax = if (obj.commander && obj.commanderCasts > 0) 2 * obj.commanderCasts else 0
            if (commanderTax > 0) trace.step("${card.name} is ${player.possessive} commander and has been cast from the command zone ${obj.commanderCasts} time${if (obj.commanderCasts == 1) "" else "s"} before, so it costs an additional {${commanderTax}} this time (the \"commander tax\").", "903.8")
            val tax = taxes.sumOf { it.second.amount } + commanderTax
            val cost = card.manaValue.toInt() + (x ?: 0) * maxOf(0, Regex("""\{X\}""").findAll(card.manaCost ?: "").count() - 1) + tax
            if (taxes.isNotEmpty() || commanderTax > 0) {
                val change = when { tax > 0 -> "plus {$tax}"; tax < 0 -> "less {${-tax}}"; else -> "unchanged" }
                trace.step("${(taxes.map { "${it.first.name} makes ${card.name} cost {${kotlin.math.abs(it.second.amount)}} ${if (it.second.amount < 0) "less" else "more"}" } + (if (commanderTax > 0) listOf("the commander tax adds {$commanderTax}") else emptyList())).joinToString(" and ")}: its total cost is ${card.manaCost ?: "?"} $change (${cost} mana in all).", "601.2f", "118.7")
            }
            player.mana?.let { avail -> if (cost > avail) { trace.step("${player.subject} ${player.v("has", "have")} only $avail mana available and ${card.name} costs $cost, so it can't be cast: the total cost can't be paid.", "601.2h", "601.2f"); state.outcomes += "${card.name} can't be cast (costs $cost, only $avail mana available)."; return null } else if (taxes.isNotEmpty() || commanderTax > 0) trace.step("${player.subject} ${player.v("has", "have")} $avail mana available, enough for the $cost.", "601.2h") }
        }
        card.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CantCastBeforeTurn>().firstOrNull()?.let { c ->
            val t = state.turnNumber
            if (t != null && t < c.turn) { trace.step("${card.name} can't be cast during ${player.possessive} first ${c.turn - 1} turns of the game, and it's only turn $t. It can't be cast yet.", "101.2"); state.outcomes += "${card.name} can't be cast yet (turn $t; not before ${player.possessive} turn ${c.turn})."; return null }
            else if (t == null) state.assumptions += "${card.name} can't be cast during ${player.possessive} first ${c.turn - 1} turns; assuming the game is past that (the turn number wasn't stated)."
        }
        if (overload) return castOverloaded(playerId, card, obj)
        if ("Land" in card.types && !card.isInstantOrSorcery) {
            // Lands aren't cast: playing one is a special action that doesn't use the stack.
            emptyStackFirst("playing a land")
            trace.step("${player.subject} ${player.v("plays", "play")} ${card.name}. Playing a land is a special action: it doesn't use the stack, can't be responded to, and is only possible during ${player.possessive} own main phase with an empty stack, once per turn unless an effect allows more.", "305.1", "116.2a", "305.2")
            // enter() already says it entered; saying it again here printed the line twice.
            obj.controller = playerId; enter(obj.id); stateBasedActions()
            if ("Basic" !in card.supertypes) narrateLandTypeSetters(land = obj)
            return null
        }
        var effect = card.spellEffect ?: card.enchant?.takeIf { card.isAura }?.let { Effect.Attach(TargetSpec(it, "enchant ${it.raw}")) }
        val needed = effect?.targets() ?: emptyList()
        var asked = false
        var noLegalTarget = false
        var targetsUnknown = false
        (effect as? Effect.Destroy)?.let { d -> if (choice == "revolt" && card.has("revolt") && d.target.filter.maxManaValue != null) {
            trace.step("Revolt: a permanent left the battlefield under ${player.possessive} control this turn, so ${card.name} can destroy a creature with mana value 4 or less instead of 2 or less.", "207.2c")
            effect = d.copy(target = d.target.copy(filter = d.target.filter.copy(maxManaValue = 4, raw = "creature with mana value 4 or less"), raw = "creature with mana value 4 or less"))
        } }
        var targets = if (targets.isEmpty() && needed.size == 1) inferTarget(card.name, needed[0], playerId, harmful = isHarmful(effect), source = obj, beneficial = isBeneficial(effect)).also { asked = it == null && state.clarifications.any { c -> c.about == "${card.name}'s target" }; noLegalTarget = it != null && it.isEmpty() } ?: targets else targets
        // Two targets and only one named ("Rabid Bite on my Bears"): the named one takes the spec it fits, the other is inferred.
        if (targets.size == 1 && needed.size == 2) {
            val given = targets[0]
            val fit = needed.indexOfFirst { filterMatches(it.filter, given, playerId) }
            if (fit >= 0) {
                val other = needed[1 - fit]
                val inferred = inferTarget(card.name, other, playerId, harmful = other.filter.controller != Who.YOU, source = obj, beneficial = other.filter.controller == Who.YOU)
                if (inferred != null && inferred.size == 1) targets = if (fit == 0) listOf(given, inferred[0]) else listOf(inferred[0], given)
            }
        }
        // Two targets and none named ("target creature you control fights target creature you don't control"): each one inferred on its own.
        if (targets.isEmpty() && needed.size == 2) {
            val each = needed.map { spec -> inferTarget(card.name, spec, playerId, harmful = isHarmful(effect) && spec.filter.controller != Who.YOU, source = obj, beneficial = spec.filter.controller == Who.YOU) }
            if (each.all { it != null && it.size == 1 }) targets = each.map { it!!.single() }
        }
        if (noLegalTarget) { trace.step("${card.name} needs a target (${needed[0].raw}) and nothing can legally be chosen, so it can't be cast.", "601.2c", "115.1"); state.outcomes += "${card.name} can't be cast: no legal target."; return null }
        if (!card.isInstantOrSorcery && card.abilities.none { it is TriggeredAbility || it is ActivatedAbility || it is StaticAbility } && card.abilities.isNotEmpty()) {
            state.unsupported += Unsupported(card.name, "Rules text not modeled: " + card.abilities.filterIsInstance<UnparsedAbility>().joinToString(" | ") { it.text })
        }
        // "Ravenous Chupacabra targeting their Bears": the creature spell targets nothing; its enters-the-battlefield trigger will.
        targets = if (needed.isEmpty() && targets.isNotEmpty() && card.abilities.any { it is TriggeredAbility && it.trigger == Trigger.ThisEnters && (it.effect.targets().isNotEmpty() || Regex("""\btarget\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.text)) }) {
            obj.etbTargets = targets
            trace.step("${card.name} itself doesn't target anything as a spell; ${describeTargets(targets).removePrefix(" targeting ")} will be the target of its enters-the-battlefield trigger when it's put on the stack.", "603.3d", "601.2c")
            emptyList()
        } else targets
        // The spell's own text names a target the engine couldn't model ("target creature loses all abilities …"): the target is kept, not queried.
        val unmodeledTarget = needed.isEmpty() && targets.isNotEmpty() && effect != null && effect.hasUnparsed() && !targetsAPlayer(effect) && Regex("""(?i)\btarget\b""").containsMatchIn(card.oracleText ?: "")
        if (unmodeledTarget) trace.step("${card.name}'s text names a target the engine can't model, so ${describeTargets(targets).removePrefix(" targeting ")} is kept as its target and what the spell does to it is reported as unsupported.", "601.2c")
        // "I Stifle the mana ability": an activated mana ability never goes on the stack, so there is nothing to
        // target. Before this the answer was a clarification asking which ability was meant.
        if (needed.any { Regex("""(?i)\bability\b""").containsMatchIn(it.raw) } && targets.isEmpty() &&
            state.stack.none { it.kind == StackKind.ACTIVATED || it.kind == StackKind.TRIGGERED } &&
            trace.steps.any { it.text.contains("It's a mana ability, so it doesn't use the stack") }) {
            trace.step("${card.name} targets an activated or triggered ability on the stack, and an activated mana ability never goes on the stack, so there is nothing for it to target.", "605.3b", "601.2c")
            state.outcomes += "${card.name} can't target a mana ability: a mana ability doesn't use the stack, so it can't be targeted, countered or responded to."
            return null
        }
        // Damage divided as you choose takes any number of targets within its cap, so one spec is not one target.
        val divided = effect as? Effect.DamageDivided ?: (effect as? Effect.Seq)?.effects?.filterIsInstance<Effect.DamageDivided>()?.firstOrNull()
        if (divided != null && targets.isNotEmpty() && (divided.maxTargets == null || targets.size <= divided.maxTargets)) Unit
        // A modal spell's targets belong to the mode, which isn't known here — and a rider sentence in front of the
        // modes ("If you control a commander …, you may choose both instead") makes the spell a Seq, not a Modal.
        // "A source of your choice" (Deflecting Palm) is chosen as the spell resolves, not targeted; a source named
        // with the spell is that choice, not a target the spell doesn't have.
        else if (needed.size != targets.size && !unmodeledTarget && !isModal(effect) && !(needed.isEmpty() && targets.size == 1 && targets[0] is Ref.Player && effect != null && targetsAPlayer(effect))
                 && !(needed.isEmpty() && targets.size == 1 && card.oracleText.contains("source of your choice", true))) {
            if (!asked) state.clarifications += Clarification("${card.name}'s target${if (needed.size == 1) "" else "s"}",
                "${card.name} needs ${needed.size} target${if (needed.size == 1) "" else "s"} (${needed.joinToString("; ") { it.raw }}) but ${targets.size} ${if (targets.size == 1) "was" else "were"} given (601.2c).")
            if (needed.size > targets.size && (card.isInstantOrSorcery || obj.zone == Zone.HAND)) { targetsUnknown = true; trace.step("${card.name} needs a target that wasn't stated; it's put on the stack anyway so responses to it can be shown, but what it does to its target can't be.", "601.2c") }
            else if (needed.size > targets.size) return null
        }
        for (ref in targets) targetingProblem(obj, playerId, ref)?.let { (why, rule) ->
            trace.step("${card.name} can't be cast targeting ${state.nameOf(ref)}: $why.", rule, "601.2c")
            state.outcomes += "${card.name} can't target ${state.nameOf(ref)}."
            return null
        }
        for ((i, ref) in targets.withIndex()) { val spec = needed.getOrNull(i) ?: continue; if (ref is Ref.Obj && spec.filter.verifiable && state.objects[ref.id]?.isOnBattlefield() == true && !filterMatches(spec.filter, ref, playerId)) {
            trace.step("${state.nameOf(ref)} isn't a legal target for ${card.name}: it needs \"${spec.raw}\"${if (spec.filter.controller == Who.OPPONENT) ", and ${state.nameOf(ref)} is ${state.player(playerId).possessive} own" else ""}. A spell can't be cast without a legal target for each of its targets.", "601.2c", "115.1a")
            state.outcomes += "${card.name} can't target ${state.nameOf(ref)} (not ${withArticle(spec.raw)})."; return null
        } }
        obj.zone = Zone.STACK
        obj.x = x
        obj.wasKicked = kicked
        state.spellsCast[card.name] = (state.spellsCast[card.name] ?: 0) + 1
        state.spellsThisTurn[playerId] = (state.spellsThisTurn[playerId] ?: 0) + 1
        state.matchingSpellsThisTurn.getOrPut(playerId) { mutableListOf() } += card
        if ("Instant" !in card.types) {
            val offTiming = state.phase == "combat" || state.stack.isNotEmpty() || (state.activePlayer != null && state.activePlayer != playerId)
            val kind = card.types.firstOrNull { it in setOf("Creature", "Sorcery", "Enchantment", "Artifact", "Planeswalker", "Battle") } ?: "permanent"
            val timingRule = mapOf("Creature" to "302.1", "Sorcery" to "307.1", "Enchantment" to "303.1", "Artifact" to "301.1", "Planeswalker" to "306.1", "Battle" to "310.1")[kind]
            if (offTiming && card.has("flash")) trace.step("${card.name} has flash, so it can be cast any time its controller could cast an instant, including now.", "702.8a")
            else if (offTiming && timingRule != null) {
                // Something on the battlefield may already allow it, in which case there is nothing to assume.
                val granter = state.objects.values.firstOrNull { o ->
                    o.isOnBattlefield() && o.controller == playerId && o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }
                        .any { e -> e is StaticEffect.CastAsThoughFlash && (e.filter == null || spellMatches(e.filter, card)) }
                }
                if (granter != null) trace.step("${granter.name} lets ${state.player(playerId).possessive} spells be cast as though they had flash, so ${card.name} can be cast now even though ${withArticle(kind.lowercase())} spell normally couldn't be.", "702.8a", timingRule)
                else if (state.activePlayerStated && state.activePlayer != null && state.activePlayer != playerId) {
                    val active = state.player(state.activePlayer!!)
                    trace.step("${withArticle(kind.lowercase()).replaceFirstChar { c -> c.uppercase() }} spell can be cast only during its controller's own main phase, and it's ${active.possessive} turn; ${card.name} doesn't have flash, so it can't be cast now.", timingRule, "117.1a")
                    state.outcomes += "${card.name} can't be cast on ${active.possessive} turn (no flash)."
                    return null
                }
                else { trace.step("${withArticle(kind.lowercase()).replaceFirstChar { c -> c.uppercase() }} spell can normally be cast only during its controller's main phase with an empty stack; ${card.name} doesn't have flash. Assuming an effect allows it, as described.", timingRule); state.assumptions += "${card.name} is cast at a time ${withArticle(kind.lowercase())} spell normally can't be (no flash); assuming something allows it." }
            }
        }
        // A modal spell's targets belong to the chosen mode (700.2c): validate against that mode's needs.
        // A rider sentence in front of the modes ("If you control a commander …, you may choose both instead")
        // makes the spell a Seq around its Modal; the modes and their targets are still the Modal's.
        val modal = effect as? Effect.Modal ?: (effect as? Effect.Seq)?.effects?.firstNotNullOfOrNull { it as? Effect.Modal }
        // A modal spell with a target but no mode named: the mode whose target the given target fits ("Red Elemental Blast on Counterspell").
        val modes = if (modal != null && modes.isEmpty() && targets.size == 1) {
            val fits = modal.modes.withIndex().filter { (_, m) -> m.targets().size == 1 && m.targets()[0].filter.let { f -> when (val t = targets[0]) { is Ref.Stack -> Kind.SPELL in f.kinds || Kind.ABILITY in f.kinds; is Ref.Obj -> Kind.SPELL !in f.kinds && (!f.verifiable || filterMatches(f, t, playerId)); is Ref.Player -> Kind.PLAYER in f.kinds } } }
            if (fits.size == 1) { state.assumptions += "${card.name}'s mode: \"${modal.modeTexts.getOrNull(fits[0].index)?.replace("~", card.name) ?: "?"}\" (the one the target fits)."; listOf(fits[0].index + 1) }
            else if (fits.isEmpty()) {
                trace.step("${state.nameOf(targets[0])} doesn't fit any of ${card.name}'s modes (${modal.modeTexts.joinToString("; ")}), so there is nothing ${card.name} could legally target here and it can't be cast.", "601.2c", "700.2a")
                state.outcomes += "${card.name} can't target ${state.nameOf(targets[0])} (no mode fits it)."
                return null
            }
            else modes
        } else modes
        val modeEffect = modal?.let { m -> modes.mapNotNull { i -> m.modes.getOrNull(i - 1) }.let { if (it.isEmpty()) null else Effect.Seq(it) } }
        // A chosen mode needs a target nobody named: the one legal target is taken, as for any other spell.
        if (modeEffect != null && targets.isEmpty() && modeEffect.targets().size == 1) {
            inferTarget(card.name, modeEffect.targets()[0], playerId, harmful = isHarmful(modeEffect), source = obj, beneficial = isBeneficial(modeEffect))?.takeIf { it.isNotEmpty() }?.let { targets = it }
        }
        val item = StackItem(state.newStackId(), StackKind.SPELL, playerId, obj, effect, targets, zonesOf(targets), card.oracleText, modes, x = x, kicked = kicked, evoked = evoked && card.has("evoke"), flashback = flashback && card.has("flashback"), choice = choice, targetsUnknown = targetsUnknown)
        state.stack += item
        if (flashback && !card.has("flashback")) state.assumptions += "${card.name} doesn't have flashback, so something else must be allowing it to be cast from a graveyard (escape, for instance); it is shown going to the graveyard afterwards as usual."
        if (item.flashback) { obj.zone = Zone.STACK; trace.step("${card.name} is cast from ${player.possessive} graveyard for its flashback cost, an alternative cost paid instead of its mana cost.", "702.34a", "601.2b") }
        if (evoked && !card.has("evoke")) { state.clarifications += Clarification("${card.name}'s evoke", "${card.name} doesn't have evoke, so it can't be cast for an evoke cost; treating it as cast normally.") }
        if (item.evoked) trace.step("${card.name} is cast for its evoke cost, an alternative cost paid instead of its mana cost. It's still a creature spell and resolves normally; its evoke trigger will sacrifice it once it has entered.", "702.74a", "601.2b")
        if (kicked) trace.step("${card.name} is kicked: its controller paid the kicker cost as an additional cost, so its \"if this spell was kicked\" parts apply.", "702.33a", "702.33d")
        if (x != null) trace.step("X is $x, chosen as ${card.name} is cast; the mana cost includes X.", "107.3a", "601.2b")
        else if (effect != null && usesX(effect)) state.clarifications += Clarification("${card.name}'s X", "${card.name} has X in its text; what was X? (assuming 0)")
        trace.step("${player.subject} ${player.v("casts", "cast")} ${card.name}${if (modes.isNotEmpty() && modal != null) " choosing " + modes.joinToString(" and ") { "\"${modal.modeTexts.getOrNull(it - 1)?.replace("~", card.name) ?: "?"}\"" } else ""}${describeTargets(targets)}. It goes on top of the stack.", "601.2a", "405.2", *(if (modal != null) arrayOf("601.2b", "700.2a") else emptyArray()))
        val playerTargetMode = targets.isNotEmpty() && targets.all { it is Ref.Player } && modes.any { modal?.modeTexts?.getOrNull(it - 1)?.lowercase()?.contains("target player") == true }
        if (modeEffect != null && modeEffect.targets().size != targets.size && !playerTargetMode) state.clarifications += Clarification("${card.name}'s target", "The chosen mode needs ${modeEffect.targets().size} target(s) (${modeEffect.targets().joinToString("; ") { it.raw }}) but ${targets.size} given.")
        var lifeCostPaid = false
        card.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CostText>().forEach {
            val life = Regex("""(?i)\bpay (X|\d+) life\b""").find(it.text)?.groupValues?.get(1)?.let { n -> if (n.equals("X", true)) x else n.toIntOrNull() }
            if (life != null && life > 0) {
                lifeCostPaid = true
                player.life = player.life?.minus(life)
                trace.step("${player.subject} ${player.v("pays", "pay")} $life life as an additional cost of ${card.name}${player.life?.let { l -> " ($l)" } ?: ""}. It's a cost, so it's paid as the spell is cast and can't be responded to.", "601.2b", "601.2h", "119.4")
                state.outcomes += "${player.subject} ${player.v("pays", "pay")} $life life."
            } else trace.step("Cost note for ${card.name}: \"${it.text.replace("~", card.name)}\" (the total cost is determined and paid as part of casting).", "601.2b", "601.2f", "601.2h")
        }
        // "I cast it paying 3 life" on a card whose own cost doesn't take life: the life was still paid, so say so.
        // When the card does have a "pay X life" cost the line above already paid it, and paying again halved the
        // life total for no reason.
        if (!lifeCostPaid && payLife != null && payLife > 0) {
            player.life = player.life?.minus(payLife)
            trace.step("${player.subject} ${player.v("pays", "pay")} $payLife life while casting ${card.name}${player.life?.let { l -> " ($l)" } ?: ""}.", "119.4")
            state.outcomes += "${player.subject} ${player.v("pays", "pay")} $payLife life."
        }
        card.abilities.filterIsInstance<StaticAbility>().filter { it.keyword in castingKeywordRules }.forEach { k ->
            trace.step("${card.name} has ${k.text.trimEnd('.')}: ${castingKeywordNotes[k.keyword]}.", castingKeywordRules.getValue(k.keyword!!))
        }
        if (card.isAura) trace.step("${card.name} is an Aura spell, so it targets what it will enchant.", "303.4a", "702.5a")
        checkTargetsAtCast(item)
        afterCast(item, card)
        return item
    }

    private fun afterCast(item: StackItem, card: CardDef) {
        val player = state.player(item.controller)
        wardTriggers(item)
        item.targets.forEach { ref -> objOf(ref)?.let { state.targetedThisTurn[it.id] = (state.targetedThisTurn[it.id] ?: 0) + 1; onEvent(GameEvent.BecomesTarget(it, item.controller, item.id)) } }
        if (item.effect?.hasUnparsed() == true) state.unsupported += Unsupported(card.name, "Part of the spell's effect is not modeled: " + unparsedText(item.effect))
        onEvent(GameEvent.SpellCast(item))
        trace.step("${player.subject} ${player.v("receives", "receive")} priority again after casting.", "117.3c")
    }

    /** Proliferate: one more of each kind of counter already there, on whatever its controller would pick (701.34a). */
    fun proliferate(playerId: String) {
        val you = state.player(playerId)
        val objs = state.objects.values.filter { it.isOnBattlefield() && it.counters.values.any { n -> n > 0 } && it.controller == playerId }
        val players = state.players.filter { it.poison > 0 && it.id != playerId }
        if (objs.isEmpty() && players.isEmpty()) { trace.step("Nothing ${you.subject.lowercase()} would want to proliferate has a counter.", "701.34a"); state.outcomes += "Proliferate does nothing: nothing ${you.subject.lowercase()} would choose has a counter on it." }
        for (o in objs) { o.counters.keys.toList().forEach { k -> o.counters[k] = o.counters.getValue(k) + 1 }; trace.step("${you.subject} ${you.v("proliferates", "proliferate")} ${o.name}: one more of each kind of counter it has (${o.counters.entries.joinToString(", ") { "${it.value} ${it.key}" }}).", "701.34a"); state.outcomes += "${o.name} has ${o.counters.entries.joinToString(", ") { "${it.value} ${it.key}" }} counters." }
        for (p in players) { p.poison += 1; trace.step("${p.subject} ${p.v("gets", "get")} another poison counter (${p.poison}).", "701.34a"); state.outcomes += "${p.subject} ${p.v("has", "have")} ${p.poison} poison counters." }
        if (objs.isNotEmpty() || players.isNotEmpty()) state.assumptions += "Proliferate: ${you.subject.lowercase()} ${you.v("chooses", "choose")} all ${you.possessive} own permanents with counters${if (players.isNotEmpty()) " and each opponent with poison counters" else ""} (701.34a lets ${you.subject.lowercase()} choose any number)."
        stateBasedActions()
    }

    /** Two creatures fight: each deals damage equal to its power to the other, at the same time (701.14a). */
    fun fight(a: GameObject?, b: GameObject?) {
        if (a == null || b == null || !a.isOnBattlefield() || !b.isOnBattlefield()) {
            trace.step("${listOfNotNull(a?.takeIf { !it.isOnBattlefield() }?.name, b?.takeIf { !it.isOnBattlefield() }?.name).joinToString(" and ").ifEmpty { "One of the creatures" }} is no longer on the battlefield, so neither creature fights and neither deals damage.", "701.14b")
            return
        }
        trace.step("${a.name} (${state.describePt(a)}) and ${b.name} (${state.describePt(b)}) fight: each deals damage equal to its power to the other, at the same time.", "701.14a")
        val pa = a.power ?: 0; val pb = b.power ?: 0
        applyDamage(a.name, Ref.Obj(b.id), pa, a); applyDamage(b.name, Ref.Obj(a.id), pb, b)
        stateBasedActions()
    }

    /** "I sacrifice X": its controller moves it from the battlefield to its owner's graveyard (701.21a). */
    fun sacrifice(playerId: String, objectId: String) {
        val o = state.obj(objectId); val p = state.player(playerId)
        if (!o.isOnBattlefield()) { trace.step("${o.name} isn't on the battlefield, so it can't be sacrificed.", "701.21a"); state.outcomes += "${o.name} can't be sacrificed (it isn't on the battlefield)."; return }
        if (o.controller != playerId) { trace.step("${p.subject} ${p.v("doesn't", "don't")} control ${o.name}, so ${p.subject.lowercase()} can't sacrifice it.", "701.21a"); state.outcomes += "${o.name} can't be sacrificed by ${p.subject.lowercase()} (${p.subject.lowercase()} ${p.v("doesn't", "don't")} control it)."; return }
        o.lkiPower = o.power; state.lastSacrificed = o
        move(o, Zone.GRAVEYARD, "${p.subject} ${p.v("sacrifices", "sacrifice")} ${o.name}: it goes from the battlefield to its owner's graveyard. Sacrificing isn't destroying, so indestructible and regeneration don't help.", "701.21a")
        stateBasedActions()
    }

    /** "I gain 5 life (from lifelink)": life gain as a given, with its triggers. */
    fun gainLifeEvent(playerId: String, amount: Int) { gainLife(state.player(playerId), amount); stateBasedActions() }
    fun loseLifeEvent(playerId: String, amount: Int) { val p = state.player(playerId); p.life = p.life?.minus(amount); trace.step("${p.subject} ${p.v("loses", "lose")} $amount life${p.life?.let { " ($it)" } ?: ""}.", "119.3"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} $amount life."; stateBasedActions() }

    /** A player draws cards outside any effect ("my opponent draws a card"): each draw is an event triggers can see. */
    /**
     * Put a card from a graveyard onto the battlefield without casting it: what "I reanimate my Grizzly Bears"
     * says with no card behind it. It is never cast, so nothing that watches for a spell being cast sees it, and
     * the effects that stop an uncast permanent entering (Containment Priest, Grafdigger's Cage) still apply.
     */
    fun reanimateObject(o: GameObject, controllerId: String) {
        val p = state.player(controllerId)
        if (o.zone != Zone.GRAVEYARD) { trace.step("${o.name} isn't in a graveyard, so there's nothing to put onto the battlefield.", "608.2b"); return }
        uncastEntryBlocked(o, Zone.GRAVEYARD)?.let { (by, how) ->
            if (how == "cant") { trace.step("$by stops cards in a graveyard from entering the battlefield, so ${o.name} stays there. Nothing enters, so no enters-the-battlefield ability triggers.", "614.1", "616.1"); state.outcomes += "${o.name} can't enter the battlefield ($by)." }
            else { trace.step("${o.name} would enter the battlefield without having been cast, so $by exiles it instead.", "614.1a", "614.6"); moveRaw(o, Zone.EXILE); state.outcomes += "${o.name}: graveyard → exile (replaced by $by)." }
            return
        }
        o.controller = controllerId
        trace.step("${p.subject} ${p.v("puts", "put")} ${o.name} from the graveyard onto the battlefield. It's put there directly rather than cast, so it never was a spell: it can't be countered and \"whenever you cast\" abilities don't trigger.", "608.2c", "400.7")
        enter(o.id); stateBasedActions()
    }

    /**
     * Exile a permanent and return it at once (701.13a): a new object, with none of the old one's counters,
     * damage, attachments or tapped state, and summoning sick again. "I flicker my Wall of Omens" says exactly
     * this with no card behind it, and Cloudshift and its kin do it through [Effect.Blink].
     */
    fun blinkObject(o: GameObject, newController: String) {
        if (!o.isOnBattlefield()) { trace.step("${o.name} isn't on the battlefield, so there's nothing to exile and return.", "608.2b"); return }
        val hadCounters = o.counters.filterValues { it > 0 }; val wasAttached = state.objects.values.filter { it.isOnBattlefield() && it.attachedTo == o.id }.map { it.name }
        move(o, Zone.EXILE, "${o.name} is exiled.", "701.13a")
        val back = state.add(GameObject(freshObjectId(o.def.name), o.def, Zone.BATTLEFIELD, newController, o.owner)); back.timestamp = state.tick(); back.summoningSick = o.def.isCreature; o.successor = back.id
        trace.step("${o.def.name} returns to the battlefield at once, under ${state.player(back.controller).possessive} control, as a new object with no memory of its previous existence: untapped, with no damage, no counters${if (hadCounters.isNotEmpty()) " (the ${hadCounters.entries.joinToString(", ") { (k, n) -> "$n $k" }} are gone)" else ""}${if (wasAttached.isNotEmpty()) ", and ${wasAttached.joinToString(", ")} no longer attached to it" else ""}${if (o.def.isCreature) ", and summoning sick again" else ""}. Anything that was targeting the old object no longer has a legal target.", "400.7", "701.13a", "302.6")
        // "Does it keep the counter?" is the usual question, so the outcome says it, not only the trace.
        state.outcomes += "${o.def.name} is exiled and returns as a new object (${state.player(back.controller).possessive} control)" +
            (if (hadCounters.isEmpty()) "." else ", with no ${hadCounters.keys.joinToString(" or ")} counters on it.")
        applyEntersReplacements(back); onEvent(GameEvent.EntersBattlefield(back)); stateBasedActions()
    }

    fun draw(playerId: String, count: Int) { drawCards(state.player(playerId), count); stateBasedActions() }

    /** Draws, tracking the library size when it's known; drawing from an empty library flags the player for 704.5b. */
    /** A Narset-style limit on how many cards [who] may draw this turn, with the permanent imposing it. */
    private fun drawLimit(who: Player): Pair<Int, GameObject>? = state.objects.values.filter { it.isOnBattlefield() }
        .firstNotNullOfOrNull { src ->
            src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CantDrawMoreThan>()
                .firstOrNull { e -> e.who == Who.EACH_PLAYER || src.controller != who.id }?.let { it.count to src }
        }

    /** What stops a card entering the battlefield without being cast, and how: ("Containment Priest", "exile") or ("Grafdigger's Cage", "cant"). */
    private fun uncastEntryBlocked(o: GameObject, from: Zone): Pair<String, String>? {
        val zone = when (from) { Zone.GRAVEYARD -> "graveyard"; Zone.LIBRARY -> "library"; Zone.HAND -> "hand"; Zone.EXILE -> "exile"; else -> "" }
        for (src in state.objects.values) {
            if (!src.isOnBattlefield()) continue
            for (e in src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }) {
                if (e is StaticEffect.CantEnterFrom && zone in e.zones && state.matches(e.filter, o, src.controller, src, anyZone = true)) return src.name to "cant"
                if (e is StaticEffect.ExileIfEntersUncast && !o.token && state.matches(e.filter, o, src.controller, src, anyZone = true)) return src.name to "exile"
            }
        }
        return null
    }

    private fun drawCards(who: Player, count0: Int) {
        var count = count0
        var trimmed = false
        // Notion Thief: a draw an opponent would make is the Thief's controller's instead (614.1a). Only the
        // first card of that player's own draw step is theirs to keep. Without this Brainstorm under a Thief
        // drew its caster three cards and the Thief's controller none.
        state.objects.values.filter { it.isOnBattlefield() && it.controller != who.id }
            .firstNotNullOfOrNull { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.OpponentsDrawsRedirected>().firstOrNull()?.let { src to it } }
            ?.let { (thief, e) ->
                val exempt = if (e.exceptFirstInDrawStep && state.step == "draw" && state.activePlayer == who.id && who.drewThisTurn == 0) minOf(1, count) else 0
                val redirected = count - exempt
                if (redirected > 0) {
                    val taker = state.player(thief.controller)
                    trace.step("${thief.name} says that whenever ${who.subject.lowercase()} would draw a card${if (exempt > 0) " other than the first in ${who.possessive} draw step" else ""}, ${taker.subject.lowercase()} ${taker.v("draws", "draw")} a card instead and ${who.subject.lowercase()} ${who.v("skips", "skip")} that draw: $redirected of the $count draw${if (count == 1) "" else "s"} ${if (redirected == 1) "is" else "are"} ${if (taker.you) "yours" else taker.possessive}.", "614.1a", "121.1")
                    state.outcomes += "${who.subject} ${who.v("skips", "skip")} $redirected draw${if (redirected == 1) "" else "s"} (${thief.name}); ${taker.subject.lowercase()} ${taker.v("draws", "draw")} instead."
                    count = exempt
                    if (count > 0) drawCards(who, count)
                    drawCards(taker, redirected)
                    return
                }
            }
        drawLimit(who)?.let { (limit, src) ->
            val left = (limit - who.drewThisTurn).coerceAtLeast(0)
            if (count > left) {
                trace.step("${src.name} says ${if (who.you) "you" else who.name} can't draw more than $limit card${if (limit == 1) "" else "s"} each turn, and ${who.subject.lowercase()} ${who.v("has", "have")} already drawn ${who.drewThisTurn} this turn, so only $left of the $count ${if (left == 1) "is" else "are"} drawn; the rest simply don't happen.", "614.1", "121.3")
                state.outcomes += "${who.subject} ${who.v("draws", "draw")} only $left card${if (left == 1) "" else "s"} of the $count (${src.name})."
                count = left; trimmed = true
            }
        }
        val lib = who.librarySize
        if (lib != null && lib < count) {
            if (lib > 0) { trace.step("${who.subject} ${who.v("draws", "draw")} $lib card${if (lib > 1) "s" else ""}, emptying ${who.possessive} library.", "121.1"); repeat(lib) { who.drew += 1; who.drewThisTurn += 1; onEvent(GameEvent.Drew(who.id)) } }
            who.librarySize = 0; who.drewFromEmpty = true
            trace.step("${who.subject} ${who.v("attempts", "attempt")} to draw ${count - lib} card${if (count - lib > 1) "s" else ""} from a library with no cards in it. No card is drawn, and ${who.subject.lowercase()} will lose the game the next time a player would receive priority.", "121.4", "704.5b")
            state.outcomes += if (lib == 0) "${who.subject} can't draw: ${who.possessive} library is empty." else "${who.subject} ${who.v("draws", "draw")} $lib card${if (lib == 1) "" else "s"} and can't draw the rest (empty library)."
            return
        }
        if (count <= 0) { trace.step("${who.subject} ${who.v("draws", "draw")} no cards.", "121.1"); return }
        trace.step("${who.subject} ${who.v("draws", "draw")} $count card${if (count > 1) "s" else ""}${if (count > 1) " (one at a time)" else ""}${if (lib != null) "; ${lib - count} left in ${who.possessive} library" else ""}.", "121.1", *(if (count > 1) arrayOf("121.2") else emptyArray()))
        if (!trimmed) state.outcomes += "${who.subject} ${who.v("draws", "draw")} $count card${if (count > 1) "s" else ""}."
        if (lib != null) who.librarySize = lib - count
        repeat(count) { who.drew += 1; who.drewThisTurn += 1; onEvent(GameEvent.Drew(who.id)) }
    }

    /** What strips a permanent of the abilities printed on it right now (Blood Moon, Humility), or null. */
    fun printedAbilitiesGone(obj: GameObject): String? {
        if (!obj.isOnBattlefield()) return null
        if ("Land" in obj.def.types && "Basic" !in obj.def.supertypes)
            state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.NonbasicLandsAreMountains } }?.let { return it.name }
        return state.abilitiesLostOn(obj)?.first?.name
    }

    /** Whether an ability's effect adds mana, so it's a mana ability that doesn't use the stack (605.1a). */
    private fun isManaEffect(e: Effect?): Boolean = e is Effect.AddMana || e is Effect.AddManaPer || e is Effect.AddManaDevotion ||
        ((e as? Effect.Seq)?.effects?.firstOrNull()?.let { it is Effect.AddMana || it is Effect.AddManaPer || it is Effect.AddManaDevotion } == true)

    /** A player's devotion to a colour: the colour's symbols in the mana costs of the permanents they control (700.5). */
    fun devotionOf(playerId: String, colour: Char): Int = state.devotion(playerId, colour)

    /**
     * Abilities that trigger while an activation is under way (a creature sacrificed to pay the cost, say) don't go
     * on the stack there and then: they wait until a player would next receive priority, which is after the ability
     * being activated is already on the stack (603.3, 117.5). So they end up above it and resolve first. While this
     * holds a trigger, putTriggerOnStack queues it here instead of stacking it.
     */
    private var triggerHold: MutableList<() -> Unit>? = null

    fun activate(playerId: String, objectId: String, abilityIndex: Int?, targets: List<Ref>, choice: String? = null, x: Int? = null): StackItem? {
        val hold = mutableListOf<() -> Unit>()
        val outer = triggerHold
        triggerHold = hold
        try { return activate0(playerId, objectId, abilityIndex, targets, choice, x) }
        finally { triggerHold = outer; hold.forEach { it() } }
    }

    private fun activate0(playerId: String, objectId: String, abilityIndex: Int?, targets: List<Ref>, choice: String? = null, x: Int? = null): StackItem? {
        val obj = state.obj(objectId)
        val abilities = obj.def.abilities.filterIsInstance<ActivatedAbility>()
        if ("Land" in obj.def.types && "Basic" !in obj.def.supertypes) state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.NonbasicLandsAreMountains } }?.let { moon ->
            val p = state.player(playerId)
            if (state.trace.steps.none { it.text.startsWith("${moon.name} makes ${obj.name} a Mountain") })
                trace.step("${moon.name} makes ${obj.name} a Mountain: it loses its other land types and all its printed abilities and has only \"{T}: Add {R}\" (a type-changing effect, layer 4).", "613.1d", "305.7")
            // Asked for one of the land's own abilities: under the moon there is no such ability any more.
            val asked = abilityIndex?.let { abilities.getOrNull(it) }
            if (asked != null && !(isManaEffect(asked.effect))) {
                trace.step("${obj.name} no longer has \"${asked.text.replace("~", obj.name)}\", so it can't be activated.", "613.1d", "305.7")
                state.outcomes += "${obj.name}'s own ability is gone under ${moon.name}, so it can't be activated."; return null
            }
            if (obj.tapped == true) { trace.step("${obj.name} is already tapped, so it can't be tapped for mana.", "118.3", "701.26a"); state.outcomes += "${obj.name} can't be tapped (already tapped)."; return null }
            tap(obj); trace.step("${p.subject} ${p.v("taps", "tap")} ${obj.name} for {R}; that's the only mana it can make under ${moon.name}.", "605.1a", "605.3b"); state.outcomes += "${obj.name} adds {R} (only), because of ${moon.name}."; return null
        }
        val isManaAbility: (ActivatedAbility) -> Boolean = { a -> isManaEffect(a.effect) }
        val lockApplies: (GameObject, StaticEffect.CantActivate) -> Boolean = { lock, e ->
            (!e.opponentsOnly || lock.controller != playerId) &&
                (if (e.named) lock.chosenName?.equals(obj.name, true) == true else state.matches(e.filter, obj, lock.controller, lock)) &&
                !(e.exceptMana && abilities.getOrNull(abilityIndex ?: 0)?.let(isManaAbility) == true)
        }
        state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { e -> e is StaticEffect.CantActivate && lockApplies(it, e) } }?.let { lock ->
            val e = lock.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.filterIsInstance<StaticEffect.CantActivate>().first { lockApplies(lock, it) }
            if (e.named) { trace.step("${lock.name} names ${obj.name}, and activated abilities of sources with the chosen name can't be activated${if (e.exceptMana) " unless they're mana abilities, which this isn't" else ""}, so ${state.player(playerId).subject.lowercase()} can't begin to activate it.", "602.5", "604.2", "101.2"); state.outcomes += "${obj.name}'s ability can't be activated (${lock.name} names it)."; return null }
            trace.step("${lock.name} says activated abilities of ${e.filter.raw} can't be activated, and ${obj.name} is ${withArticle(e.filter.raw)}, so ${state.player(playerId).subject.lowercase()} can't begin to activate its ability${if (abilities.any { a -> isManaEffect(a.effect) }) " (mana abilities included: they are activated abilities too)" else ""}.", "602.5", "604.2", "101.2")
            state.outcomes += "${obj.name}'s ability can't be activated (${lock.name})."; return null
        }
        if (obj.isOnBattlefield() && obj.controller != playerId) {
            val p = state.player(playerId); val owner = state.player(obj.controller)
            trace.step("${obj.name} is under ${owner.possessive} control, and only a permanent's controller may activate its abilities, so ${p.subject.lowercase()} can't activate it.", "602.1a", "601.2")
            state.outcomes += "${p.subject} can't activate ${obj.name} (${owner.subject.lowercase()} ${owner.v("controls", "control")} it)."; return null
        }
        if (abilities.isEmpty()) {
            // "I tap my Grizzly Bears": a permanent with no activated ability can still be turned sideways, and
            // that is the only thing the words can mean. Saying it is untapped afterwards contradicted the asker.
            if (obj.def.abilities.none { it is ActivatedAbility }) { tapObject(obj.id); return null }
            state.unsupported += Unsupported(obj.name, "No activated ability was recognised on ${obj.name}."); return null
        }
        val pickedIndex = abilityIndex ?: if (abilities.size > 1) {
            // Nothing said which: take the first that isn't a mana ability, and say so. A target was named, so
            // prefer one that takes a target — "I activate Walking Ballista targeting their 1/1" is the ability
            // that deals the damage, not the one that only puts a counter on the Ballista itself.
            val nonMana = abilities.withIndex().filter { !(isManaEffect(it.value.effect)) }
            val first = (if (targets.isEmpty()) null else nonMana.firstOrNull { it.value.effect.targets().isNotEmpty() })?.index
                ?: nonMana.firstOrNull()?.index ?: 0
            state.assumptions += "${obj.name} has ${abilities.size} activated abilities and none was named; assuming \"${abilities[first].text.replace("~", obj.name)}\"${abilities.withIndex().filter { it.index != first }.joinToString("") { " (not \"${it.value.text.replace("~", obj.name)}\")" }}."
            first
        } else 0
        val ability = abilities[pickedIndex]
        // A {T} cost is a {T} cost whether or not the ability makes mana: an already-tapped or summoning-sick
        // creature can't pay it (302.6, 118.3). These used to sit below the mana-ability branch, which returns
        // first, so Llanowar Elves made mana the turn it came down.
        if (ability.cost.contains("{T}") && obj.tapped == true) { trace.step("${obj.name} is already tapped, so its {T} ability can't be activated.", "118.3", "701.26a"); state.outcomes += "${obj.name}'s {T} ability can't be activated (already tapped)."; return null }
        if (ability.cost.contains("{T}") && obj.def.isCreature && obj.summoningSick == true && !obj.has("haste")) { trace.step("${obj.name} hasn't been under ${state.player(playerId).possessive} control since the turn began and doesn't have haste, so its {T} ability can't be activated.", "302.6"); state.outcomes += "${obj.name}'s {T} ability can't be activated (summoning sickness)."; return null }
        if (!paySacrificeCosts(playerId, obj, ability, choice)) return null
        val isMana = isManaEffect(ability.effect)
        if (!isMana) state.stack.firstOrNull { it.kind == StackKind.SPELL && it.source.def.has("split second") }?.let { ss ->
            trace.step("${ss.source.name} has split second and is on the stack, so abilities that aren't mana abilities can't be activated. ${obj.name}'s ability can't be activated now.", "702.61a")
            state.outcomes += "${obj.name}'s ability can't be activated while ${ss.source.name} is on the stack (split second)."; return null
        }
        if (isManaEffect(ability.effect)) {
            val p = state.player(playerId)
            val made = manaMade(ability.effect, playerId, choice)
            trace.step("${p.subject} ${p.v("activates", "activate")} ${obj.name}'s mana ability (${ability.cost}). It's a mana ability, so it doesn't use the stack and resolves immediately: ${made ?: describeManaEffect(ability.effect)}.", "605.1a", "605.3b")
            if (ability.cost.contains("{T}")) tap(obj)
            state.outcomes += "${obj.name}'s mana ability: ${made ?: describeManaEffect(ability.effect)}."
            // A mana ability that needs working out (devotion, "for each") explains itself in the trace.
            if (ability.effect is Effect.AddManaDevotion) applyEffect(ability.effect, StackItem(state.newStackId(), StackKind.ACTIVATED, playerId, obj, ability.effect, emptyList(), emptyMap(), ability.text, choice = choice))
            // "Add {C}{C}. Ancient Tomb deals 2 damage to you." — the rest of a mana ability happens too, right away.
            (ability.effect as? Effect.Seq)?.effects?.drop(1)?.takeIf { it.isNotEmpty() }?.let { rest ->
                val item = StackItem(state.newStackId(), StackKind.ACTIVATED, playerId, obj, Effect.Seq(rest), emptyList(), emptyMap(), ability.text)
                rest.forEach { applyEffect(it, item) }
                stateBasedActions()
            }
            return null
        }
        Regex("""Pay (\d+) life""", RegexOption.IGNORE_CASE).find(ability.cost)?.let { m ->
            val n = m.groupValues[1].toInt(); val p = state.player(playerId)
            if (p.life != null && p.life!! < n) { trace.step("${p.subject} ${p.v("has", "have")} ${p.life} life and can't pay $n life, so the ability can't be activated.", "118.3", "119.4"); state.outcomes += "${obj.name}'s ability can't be activated (not enough life)."; return null }
            p.life = p.life?.minus(n); trace.step("${p.subject} ${p.v("pays", "pay")} $n life${p.life?.let { " ($it)" } ?: ""} as part of the cost.", "119.4", "602.2b"); state.outcomes += "${p.subject} ${p.v("pays", "pay")} $n life."
        }
        ability.loyaltyCost?.let { lc ->
            val have = obj.counters["loyalty"] ?: 0
            if (lc < 0 && have < -lc) { trace.step("${obj.name} has $have loyalty and can't pay the ${lc} loyalty cost.", "606.6"); state.outcomes += "${obj.name}'s $lc ability can't be activated (not enough loyalty)."; return null }
            obj.counters["loyalty"] = have + lc
            trace.step("${state.player(playerId).subject} ${state.player(playerId).v("activates", "activate")} ${obj.name}'s ${ability.cost} loyalty ability, ${if (lc >= 0) "putting $lc loyalty counter${if (lc == 1) "" else "s"} on it" else "removing ${-lc} loyalty counter${if (lc == -1) "" else "s"} from it"} (now ${obj.counters["loyalty"]}). Loyalty abilities can be activated only at sorcery speed and once per turn per permanent.", "606.4", "606.3")
        }
        if (ability.cost.contains("{T}") && obj.tapped == true) { trace.step("${obj.name} is already tapped, so its {T} ability can't be activated.", "602.2b", "701.26a"); state.outcomes += "${obj.name}'s {T} ability can't be activated (it's already tapped)."; return null }
        if (ability.cost.contains("{T}") && obj.def.isCreature && obj.summoningSick == true && !obj.has("haste")) { trace.step("${obj.name} hasn't been under ${state.player(playerId).possessive} control since the turn began and doesn't have haste, so its {T} ability can't be activated.", "302.6"); state.outcomes += "${obj.name}'s {T} ability can't be activated (summoning sickness)."; return null }
        if (ability.cost.contains("{T}")) tap(obj)
        if (ability.cost.contains("discard this card", true)) onEvent(GameEvent.Cycled(obj))
        val needed = ability.effect.targets()
        // "I activate Spellskite" with nothing named: the one legal target, as for spells.
        val targets = if (targets.isEmpty() && needed.size == 1) inferTarget("${obj.name}'s ability", needed[0], playerId, harmful = isHarmful(ability.effect), source = obj, beneficial = isBeneficial(ability.effect))?.takeIf { it.isNotEmpty() } ?: targets else targets
        // The ability's text names a target the parser couldn't model ("~ becomes a copy of target land"): keeping
        // the target and saying the text isn't modeled beats "the ability needs 0 targets but 1 given".
        val unmodeledTarget = needed.isEmpty() && targets.isNotEmpty() && ability.effect.hasUnparsed() && Regex("""(?i)\btarget\b""").containsMatchIn(ability.text)
        // Damage divided as you choose takes any number of targets within its cap, so one spec is not one target.
        val divided = ability.effect as? Effect.DamageDivided ?: (ability.effect as? Effect.Seq)?.effects?.filterIsInstance<Effect.DamageDivided>()?.firstOrNull()
        if (unmodeledTarget) trace.step("${obj.name}'s ability names a target the engine can't model, so ${describeTargets(targets).removePrefix(" targeting ")} is kept as its target and what the ability does to it is reported as unsupported.", "601.2c")
        else if (divided != null && targets.isNotEmpty() && (divided.maxTargets == null || targets.size <= divided.maxTargets)) Unit
        else if (needed.size != targets.size && !(needed.isEmpty() && targets.size == 1 && targets[0] is Ref.Player && targetsAPlayer(ability.effect))) {
            state.clarifications += Clarification("${obj.name}'s ability target", "The ability needs ${needed.size} target(s) (${needed.joinToString("; ") { it.raw }}) but ${targets.size} given (602.2b, 601.2c).")
            if (needed.size > targets.size) return null
        }
        for (ref in targets) targetingProblem(obj, playerId, ref)?.let { (why, rule) ->
            trace.step("${obj.name}'s ability can't target ${state.nameOf(ref)}: $why.", rule, "602.2b", "601.2c"); state.outcomes += "${obj.name}'s ability can't target ${state.nameOf(ref)}."; return null
        }
        ability.restriction?.let { trace.step("${obj.name}'s ability says \"$it\"; assuming that timing is satisfied.", "602.5", "602.2") }
        val item = StackItem(state.newStackId(), StackKind.ACTIVATED, playerId, obj, ability.effect, targets, zonesOf(targets), ability.text, choice = choice, x = x)
        if (x != null) trace.step("X is $x, chosen as the ability is activated; its cost includes X.", "107.3a", "602.2b")
        state.stack += item
        wardTriggers(item)
        targets.forEach { ref -> objOf(ref)?.let { state.targetedThisTurn[it.id] = (state.targetedThisTurn[it.id] ?: 0) + 1; onEvent(GameEvent.BecomesTarget(it, playerId, item.id)) } }
        state.player(playerId).let { p -> trace.step("${p.subject} ${p.v("activates", "activate")} ${obj.name}'s ability (${ability.cost})${describeTargets(targets)}. It goes on top of the stack.", "602.2a", "405.2") }
        if (ability.effect.hasUnparsed()) state.unsupported += Unsupported(obj.name, "Part of the ability's effect is not modeled: " + unparsedText(ability.effect))
        return item
    }

    /** The user asserts that an object's triggered ability has triggered; put it on the stack. */
    fun assertTrigger(objectId: String, abilityIndex: Int?, targets: List<Ref>): StackItem? {
        val obj = state.obj(objectId)
        val abilities = obj.def.abilities.filterIsInstance<TriggeredAbility>()
        if (abilities.isEmpty()) { state.unsupported += Unsupported(obj.name, "No triggered ability was recognised on ${obj.name}."); return null }
        val ability = abilities[abilityIndex ?: 0]
        return putTriggerOnStack(obj, ability, targets)
    }

    /** How many lands [playerId] may play this turn: one, plus whatever effects add (305.2). */
    fun landPlaysAllowed(playerId: String): Int = 1 + (state.extraLandsThisTurn[playerId] ?: 0) +
        state.objects.values.filter { it.isOnBattlefield() }.sumOf { src ->
            src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.ExtraLandPlays>()
                .filter { it.who != Who.YOU || src.controller == playerId }.sumOf { it.count }
        }

    /**
     * "I play a land": a land play is not a spell and uses the stack for nothing (305.1), but a player may only
     * make one each turn unless an effect says otherwise (305.2). Without the count a second land went down
     * without a word, and "can I play another land?" answered yes.
     */
    fun playLand(playerId: String, objectId: String) {
        val p = state.player(playerId)
        state.activePlayer?.takeIf { state.activePlayerStated && it != playerId }?.let { active ->
            trace.step("A land can only be played during a main phase of its controller's own turn, and it's ${state.player(active).possessive} turn.", "305.1", "116.2a")
            state.outcomes += "${p.subject} can't play a land on ${state.player(active).possessive} turn."; return
        }
        val allowed = landPlaysAllowed(playerId)
        val already = state.landsPlayed[playerId] ?: 0
        if (already >= allowed) {
            val extra = allowed - 1
            trace.step("${p.subject} ${p.v("has", "have")} already played $already land${if (already == 1) "" else "s"} this turn and may play $allowed${if (extra > 0) " (one, plus $extra from an effect)" else ""}, so ${p.subject.lowercase()} can't play another.", "305.2a", "116.2a")
            state.outcomes += "${p.subject} can't play another land this turn."
            return
        }
        state.landsPlayed[playerId] = already + 1
        trace.step("${p.subject} ${p.v("plays", "play")} a land. Playing a land is a special action: it uses no stack and can't be responded to.", "305.1", "116.2a")
        enter(objectId)
    }

    fun enter(objectId: String, choice: String? = null) {
        val obj = state.obj(objectId)
        obj.zone = Zone.BATTLEFIELD; obj.tapped = false; obj.timestamp = state.tick()
        // It has just come under its controller's control, so it is summoning sick — which matters for a creature
        // land played this turn (Dryad Arbor attacked the turn it was played) as much as for a creature.
        obj.summoningSick = true
        applyEntersReplacements(obj, choice)
        // "a creature enters with two +1/+1 counters": stated by the asker rather than printed on a card, but the
        // counters are still put on as it enters, so doublers and Hardened Scales apply (614.1c).
        choice?.takeIf { it.startsWith("counters:") }?.split(":")?.takeIf { it.size == 3 }?.let { (_, n, kind) ->
            val asked = n.toIntOrNull() ?: 1
            val placed = countersPlaced(obj, asked, kind)
            if (placed > 0) obj.counters[kind] = (obj.counters[kind] ?: 0) + placed
            trace.step("${obj.name} enters with $placed $kind counter${if (placed == 1) "" else "s"} on it${if (placed != asked) " (the situation said $asked)" else ""}.", "614.1c", "122.6")
        }
        trace.step("${obj.name} enters the battlefield under ${state.player(obj.controller).possessive} control${if (obj.tapped == true) " tapped" else ""}.", "110.5b")
        state.outcomes += "${obj.name} enters the battlefield${obj.counters.entries.filter { it.value > 0 }.takeIf { it.isNotEmpty() }?.joinToString(", ", " with ") { (k, n) -> "$n $k counter${if (n == 1) "" else "s"}" } ?: ""}."
        onEvent(GameEvent.EntersBattlefield(obj))
    }

    /**
     * "My opponent gains control of my creature": a control change the situation states outright, with no card
     * behind it. Layer 2; the permanent doesn't change zones, so it is summoning sick for its new controller.
     */
    fun gainControl(playerId: String, objectId: String, untilEndOfTurn: Boolean) {
        val o = state.obj(objectId); val p = state.player(playerId); val was = state.player(o.controller)
        if (o.controller == playerId) { trace.step("${o.name} is already under ${p.possessive} control.", "613.1b"); return }
        if (untilEndOfTurn && o.controlRevertsTo == null) o.controlRevertsTo = o.controller
        o.controller = playerId
        if (o.def.isCreature) o.summoningSick = true
        trace.step("${p.subject} ${p.v("gains", "gain")} control of ${o.name}${if (untilEndOfTurn) " until end of turn" else ""} (it was ${was.possessive}). A control-changing effect applies in layer 2; the permanent doesn't change zones, so it isn't summoning sick only if it has haste or has been under its new controller's control since the turn began.", "613.1b", "611.2a", "302.6")
        state.outcomes += "${p.subject} ${p.v("controls", "control")} ${o.name}${if (untilEndOfTurn) " until end of turn" else ""}."
    }

    /** "I discard Vengevine": a named card goes from its owner's hand to their graveyard (701.9a). */
    fun discard(playerId: String, objectId: String) {
        val obj = state.obj(objectId)
        val p = state.player(playerId)
        if (obj.zone != Zone.HAND) { trace.step("${obj.name} isn't in ${p.possessive} hand, so it can't be discarded.", "701.9a"); state.outcomes += "${obj.name} can't be discarded (it isn't in ${p.possessive} hand)."; return }
        move(obj, Zone.GRAVEYARD, "${p.subject} ${p.v("discards", "discard")} ${obj.name}: it goes from ${p.possessive} hand to ${p.possessive} graveyard.", "701.9a")
        p.handSize = p.handSize?.minus(1)?.coerceAtLeast(0)
        obj.def.abilities.filterIsInstance<StaticAbility>().firstOrNull { it.keyword == "madness" }?.let {
            trace.step("${obj.name} has madness, so it is still discarded, but it is exiled instead of going to the graveyard; its owner may then cast it for the madness cost, and if they don't, it goes to the graveyard after all — which is where this answer leaves it.", "702.35a")
            state.assumptions += "${obj.name}'s madness cost was not paid, so it ends up in the graveyard (702.35a)."
        }
    }

    /** "Their Grizzly Bears untaps": the asker states it, rather than it happening in an untap step (701.26b). */
    /** "I put a +1/+1 counter on my Bears": counters placed by an effect, so doublers and Solemnity apply (614.1a). */
    fun putCounters(objectId: String, count: Int, kind: String) {
        val obj = state.obj(objectId)
        val placed = countersPlaced(obj, count, kind)
        if (placed <= 0) { state.outcomes += "No $kind counters are put on ${obj.name}."; return }
        obj.counters[kind] = (obj.counters[kind] ?: 0) + placed
        trace.step("$placed $kind counter${if (placed == 1) "" else "s"} ${if (placed == 1) "is" else "are"} put on ${obj.name}${if (obj.def.isCreature) "; it is now ${state.describePt(obj)}" else ""}.", "122.1", "614.1a")
        state.outcomes += "${obj.name} has ${obj.counters[kind]} $kind counter${if (obj.counters[kind] == 1) "" else "s"}."
        onEvent(GameEvent.CountersPut(obj, kind, placed))
        stateBasedActions()
    }

    /** "they tap my Grizzly Bears down": tapping a permanent, which is not the same as using a {T} ability. */
    fun tapObject(objectId: String) {
        val obj = state.obj(objectId)
        if (obj.tapped == true) { trace.step("${obj.name} is already tapped.", "701.26a"); return }
        tap(obj)
        trace.step("${obj.name} becomes tapped.", "701.26a")
        state.outcomes += "${obj.name} is tapped."
    }

    fun untapObject(objectId: String) {
        val obj = state.obj(objectId)
        if (obj.tapped != true) { trace.step("${obj.name} isn't tapped, so untapping it does nothing.", "701.26b"); return }
        obj.tapped = false
        trace.step("${obj.name} becomes untapped.", "701.26b")
        state.outcomes += "${obj.name} is untapped."
    }

    /** "I mill three cards": the top cards of that player's library go to their graveyard (701.13a). */
    fun millCards(playerId: String, count: Int) {
        val p = state.player(playerId)
        val lib = p.librarySize
        val n = if (lib != null && lib < count) lib else count
        if (lib != null && lib < count) trace.step("${p.subject} ${p.v("has", "have")} only $lib card${if (lib == 1) "" else "s"} in ${p.possessive} library, so ${p.subject.lowercase()} ${p.v("mills", "mill")} only that many.", "701.13a")
        trace.step("${p.subject} ${p.v("mills", "mill")} $n card${if (n == 1) "" else "s"}: the top $n of ${p.possessive} library ${if (n == 1) "goes" else "go"} into ${p.possessive} graveyard.", "701.13a")
        if (lib != null) p.librarySize = lib - n
        state.outcomes += "${p.subject} ${p.v("mills", "mill")} $n card${if (n == 1) "" else "s"}${if (lib != null) " (${lib - n} left in library)" else ""}."
    }

    /** "I discard a card" with no card named: the hand shrinks and the answer says which card isn't known. */
    fun discardCount(playerId: String, count: Int) {
        val p = state.player(playerId)
        val hand = p.handSize
        val n = if (hand != null && hand < count) hand else count
        if (hand == null) { trace.step("${p.subject} ${p.v("discards", "discard")} $n card${if (n == 1) "" else "s"}; which card isn't known, so nothing that depends on it is shown.", "701.9a"); state.outcomes += "${p.subject} ${p.v("discards", "discard")} $n card${if (n == 1) "" else "s"}." }
        else { p.handSize = hand - n; trace.step("${p.subject} ${p.v("discards", "discard")} $n card${if (n == 1) "" else "s"}, leaving ${p.handSize} in hand; which card isn't known.", "701.9a"); state.outcomes += "${p.subject} ${p.v("discards", "discard")} $n card${if (n == 1) "" else "s"} ($hand → ${p.handSize} in hand)." }
    }

    /** "I get a poison counter": ten or more and that player loses (704.5c). */
    fun addPoison(playerId: String, count: Int) {
        val p = state.player(playerId)
        p.poison = (p.poison ?: 0) + count
        trace.step("${p.subject} ${p.v("gets", "get")} $count poison counter${if (count == 1) "" else "s"} (${p.poison} in all).", "122.1a", "704.5c")
        state.outcomes += "${p.subject} ${p.v("has", "have")} ${p.poison} poison counter${if (p.poison == 1) "" else "s"}."
        stateBasedActions()
    }

    fun leave(objectId: String, to: Zone) {
        val obj = state.obj(objectId)
        move(obj, to, "${obj.name} is put into ${zoneName(to, obj)}.", "400.7")
        // An Aura that was on it is now attached to nothing, and dies to state-based actions (704.5m).
        stateBasedActions()
    }

    /** Damage the user states as a given (e.g. "Bolt already dealt 3 to it"). */
    fun dealDamage(sourceName: String, target: Ref, amount: Int) {
        applyDamage(sourceName, target, amount)
        stateBasedActions()
    }

    /** A step or phase begins (603.2b): "at the beginning of" abilities trigger. */
    /** Overload: cast for the overload cost with every "target" read as "each" (702.96a). */
    private fun castOverloaded(playerId: String, card: CardDef, obj: GameObject): StackItem? {
        val player = state.player(playerId)
        val overloadCost = card.abilities.filterIsInstance<StaticAbility>().firstOrNull { it.keyword == "overload" }?.text?.removePrefix("Overload ")?.trimEnd('.')
        if (overloadCost == null) { state.unsupported += Unsupported(card.name, "${card.name} doesn't have overload."); return null }
        fun each(e: Effect): Effect = when (e) {
            is Effect.Destroy -> Effect.ForAll(e.target.filter, "destroy", noRegen = e.noRegen)
            is Effect.Exile -> Effect.ForAll(e.target.filter, "exile")
            is Effect.Tap -> Effect.ForAll(e.target.filter, "tap")
            is Effect.Bounce -> if (e.target != null) Effect.ForAll(e.target.filter, "bounce") else e
            is Effect.Damage -> Effect.ForAll(e.target.filter, "damage", e.amount)
            is Effect.Seq -> Effect.Seq(e.effects.map { each(it) })
            else -> e
        }
        val effect = card.spellEffect?.let { each(it) }
        if (effect == null || effect.targets().isNotEmpty()) { state.unsupported += Unsupported(card.name, "Couldn't rewrite ${card.name}'s text from \"target\" to \"each\" for overload."); return null }
        obj.zone = Zone.STACK
        val item = StackItem(state.newStackId(), StackKind.SPELL, playerId, obj, effect, emptyList(), emptyMap(), card.oracleText, emptyList())
        state.stack += item
        trace.step("${player.subject} ${player.v("casts", "cast")} ${card.name} for its overload cost $overloadCost instead of its mana cost. Every \"target\" in its text becomes \"each\", so it targets nothing and affects everything it describes. It goes on top of the stack.", "702.96a", "601.2b", "405.2")
        afterCast(item, card)
        return item
    }

    /** Drawing cards, gaining life: a "target player" effect its caster would aim at themselves. */
    private fun helpsThePlayer(e: Effect?): Boolean = when (e) { is Effect.Draw -> true; is Effect.GainLife -> true; is Effect.Seq -> e.effects.firstOrNull { it !is Effect.Narrated }?.let { helpsThePlayer(it) } == true; is Effect.May -> helpsThePlayer(e.effect); else -> false }
    /** Pumps, keyword grants, shields, +1/+1 counters: aimed at your own things when an opponent's also qualify. */
    private fun isBeneficial(e: Effect?): Boolean = when (e) { is Effect.Pump, is Effect.GainKeywords, is Effect.CreateShield, is Effect.Regenerate, is Effect.Untap -> true; is Effect.PutCounters -> e.kind.let { it == "+1/+1" || it.startsWith("+") }; is Effect.Seq -> e.effects.isNotEmpty() && e.effects.all { isBeneficial(it) || it is Effect.Narrated }; is Effect.May -> isBeneficial(e.effect); else -> false }
    private fun isHarmful(e: Effect?): Boolean = when (e) { is Effect.Destroy, is Effect.Exile, is Effect.Damage, is Effect.Bounce, is Effect.Tap, is Effect.Counter, is Effect.ShuffleIntoLibrary, is Effect.GainControl -> true; is Effect.Seq -> e.effects.any { isHarmful(it) }; is Effect.May -> isHarmful(e.effect); is Effect.UnlessPays -> isHarmful(e.effect); else -> false }
    /** Effects phrased "target player …" whose player is carried by a [Who] rather than a TargetSpec. */
    /** Whether the spell's targets are a mode's rather than its own — a Modal, possibly behind a rider sentence. */
    private fun isModal(e: Effect?): Boolean = e is Effect.Modal || (e is Effect.Seq && e.effects.any { isModal(it) })

    private fun targetsAPlayer(e: Effect): Boolean = when (e) {
        is Effect.Draw -> e.who == Who.TARGET_PLAYER; is Effect.GainLife -> e.who == Who.TARGET_PLAYER; is Effect.LoseLife -> e.who == Who.TARGET_PLAYER; is Effect.DamagePlayer -> e.who == Who.TARGET_PLAYER; is Effect.NarratedTargeted -> e.text.startsWith("target opponent", ignoreCase = true) || Kind.PLAYER in e.target.filter.kinds
        is Effect.CantCastThisTurn -> e.who == Who.TARGET_PLAYER; is Effect.CreateToken -> e.who == Who.TARGET_PLAYER; is Effect.Discard -> e.who == Who.TARGET_PLAYER; is Effect.DiscardChosen -> e.who == Who.TARGET_PLAYER; is Effect.DiscardNamed -> e.who == Who.TARGET_PLAYER; is Effect.Mill -> e.who == Who.TARGET_PLAYER; is Effect.ExileGraveyard -> e.who == Who.TARGET_PLAYER; is Effect.SacrificeEach -> e.who == Who.TARGET_PLAYER; is Effect.LoseLifeThatMuch -> e.who == Who.TARGET_PLAYER
        is Effect.Seq -> e.effects.any { targetsAPlayer(it) }; is Effect.May -> targetsAPlayer(e.effect); is Effect.UnlessPays -> targetsAPlayer(e.effect); is Effect.Modal -> e.modes.any { targetsAPlayer(it) }
        is Effect.Narrated -> e.text.startsWith("target player", ignoreCase = true); else -> false
    }

    /** Sacrifice costs of an activated ability ("Sacrifice ~:", "Sacrifice an artifact:"), paid as it's activated. False if they can't be paid. */
    /** Why a creature can't attack or block right now (the permanent forbidding it), or null if it can. */
    fun cantWhy(objectId: String, what: String): String? {
        val o = state.obj(objectId)
        if (what == "attack" || what == "block") state.notACreatureBecause(o)?.let { why -> return "${o.name} isn't a creature right now — ${state.player(o.controller).possessive} $why" }
        if (what == "attack") attackConditionUnmet(o, null)?.let { cond -> return "its own \"can't attack unless $cond\"" }
        if (!cant(o, what)) return null
        return cantSource(o, what) ?: o.name
    }

    private fun paySacrificeCosts(playerId: String, obj: GameObject, ability: ActivatedAbility, choice: String?): Boolean {
        Regex("""(?i)\bsacrifice (?:an?|another|two|three) (.+?)(?::|$)""").find(ability.cost)?.takeIf { !Regex("""(?i)\bsacrifice (?:~|this\b|${Regex.escape(obj.name)}\b)""").containsMatchIn(ability.cost) }?.let { sc ->
            val what = sc.groupValues[1].trim()
            val chosen = choice?.takeIf { it.startsWith("sacrifice:") }?.removePrefix("sacrifice:")?.let { state.objects[it] }
                ?: state.objects.values.filter { it.isOnBattlefield() && it.controller == playerId && it !== obj && state.matches(mtg.judge.oracle.OracleParser.parseFilter(what, Kind.PERMANENT), it, playerId) }.minByOrNull { it.def.manaValue }?.also { state.assumptions += "${state.player(playerId).subject} ${state.player(playerId).v("sacrifices", "sacrifice")} ${it.name} to ${obj.name} (no ${what} was named; assuming the cheapest)." }
            if (chosen == null) { trace.step("${obj.name}'s ability costs \"Sacrifice ${sc.groupValues[0].removePrefix("Sacrifice ").removeSuffix(":")}\" and ${state.player(playerId).subject.lowercase()} ${state.player(playerId).v("controls", "control")} no such permanent to sacrifice, so it can't be activated.", "602.2b", "701.21a"); state.outcomes += "${obj.name}'s ability can't be activated (nothing to sacrifice)."; return false }
            if (!chosen.isOnBattlefield() || chosen.controller != playerId) { trace.step("${chosen.name} isn't a permanent ${state.player(playerId).subject.lowercase()} ${state.player(playerId).v("controls", "control")}, so it can't be sacrificed to ${obj.name}.", "701.21a"); return false }
            chosen.lkiPower = chosen.power; state.lastSacrificed = chosen
            move(chosen, Zone.GRAVEYARD, "${state.player(playerId).subject} ${state.player(playerId).v("sacrifices", "sacrifice")} ${chosen.name} as the cost of ${obj.name}'s ability. Costs are paid as the ability is activated, so the sacrifice can't be responded to.", "701.21a", "602.2b", "601.2h")
        }
        if (Regex("""(?i)\bsacrifice (?:~|this\b|${Regex.escape(obj.name)}\b)""").containsMatchIn(ability.cost)) {
            if (!obj.isOnBattlefield()) { trace.step("${obj.name} isn't on the battlefield, so it can't be sacrificed to pay the cost.", "602.2b", "701.21a"); return false }
            obj.lkiPower = obj.power; state.lastSacrificed = obj
            move(obj, Zone.GRAVEYARD, "${state.player(playerId).subject} ${state.player(playerId).v("sacrifices", "sacrifice")} ${obj.name} as the cost. Costs are paid as the ability is activated, so once it's on the stack the ability resolves even if something is done in response: the sacrifice can't be responded to.", "701.21a", "602.2b", "601.2h", "113.7a")
        }
        return true
    }

    /** Whether a spell being cast matches a spell filter (for cost taxes and "whenever you cast" checks). */
    private fun spellMatches(f: ObjFilter, card: CardDef): Boolean {
        val typeOk = f.kinds.any { k -> when (k) { Kind.SPELL -> true; Kind.CREATURE -> card.isCreature; Kind.ARTIFACT -> "Artifact" in card.types; Kind.ENCHANTMENT -> "Enchantment" in card.types; Kind.PLANESWALKER -> card.isPlaneswalker; else -> false } }
        val notOk = f.notKinds.none { k -> when (k) { Kind.CREATURE -> card.isCreature; Kind.ARTIFACT -> "Artifact" in card.types; Kind.ENCHANTMENT -> "Enchantment" in card.types; Kind.PLANESWALKER -> card.isPlaneswalker; Kind.LAND -> "Land" in card.types; else -> false } }
        val colourOk = f.colors.all { it in card.colors } && f.notColors.none { it in card.colors }
        val mvOk = (f.maxManaValue == null || card.manaValue.toInt() <= f.maxManaValue) && (f.minManaValue == null || card.manaValue.toInt() >= f.minManaValue)
        return typeOk && notOk && colourOk && mvOk
    }
    private fun usesX(e: Effect): Boolean = when (e) { is Effect.Damage -> e.x; is Effect.Draw -> e.x; is Effect.LoseLife -> e.x; is Effect.Discard -> e.x; is Effect.PumpAll -> e.x; is Effect.SetBasePtAll -> e.x; is Effect.PutCounters -> e.x; is Effect.CreateToken -> e.x; is Effect.Repeat -> e.x || usesX(e.body); is Effect.Seq -> e.effects.any { usesX(it) }; is Effect.May -> usesX(e.effect); is Effect.Modal -> e.modes.any { usesX(it) }; else -> false }

    /** Steps and combat can't begin while something is on the stack: everything pending resolves first (500.2). */
    fun emptyStackFirst(what: String) {
        if (state.stack.isEmpty()) return
        trace.step("The stack isn't empty, and $what can't happen until it is and all players pass, so everything on the stack resolves first.", "500.2", "117.4")
        state.assumptions += "Everything on the stack resolved before $what (a step can't end with objects on the stack, 500.2)."
        resolveAll()
    }

    fun beginStep(step: String, activePlayer: String) {
        emptyStackFirst("the ${step.replace('_', ' ')} step")
        if (step == "untap") {
            state.activePlayer = activePlayer; state.step = step; state.phase = "beginning"
            val p = state.player(activePlayer)
            val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == activePlayer }
            // Meekstone and its cousins: some permanents don't untap at all (302.6 doesn't apply to them).
            val held = mine.filter { o -> state.objects.values.any { src -> src.isOnBattlefield() &&
                src.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.filterIsInstance<StaticEffect.DontUntap>()
                    .any { e -> state.matches(e.filter, o, src.controller, src) } } }
            for (o in held) state.objects.values.firstOrNull { src -> src.isOnBattlefield() &&
                src.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.filterIsInstance<StaticEffect.DontUntap>()
                    .any { e -> state.matches(e.filter, o, src.controller, src) } }?.let { src ->
                trace.step("${o.name} doesn't untap during its controller's untap step (${src.name}).", "502.3", "614.1")
                state.outcomes += "${o.name} doesn't untap (${src.name})."
            }
            val wereTapped = (mine - held.toSet()).filter { it.tapped == true }
            (mine - held.toSet()).forEach { it.tapped = false; it.summoningSick = false; it.attacking = null; it.blocking = null }
            for (o in wereTapped) state.outcomes += "${o.name} untaps."
            // Seedborn Muse: another player's permanents untap in this player's untap step too.
            for (src in state.objects.values.filter { it.isOnBattlefield() && it.controller != activePlayer }) {
                val e = src.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.filterIsInstance<StaticEffect.UntapInOthersUntapStep>().firstOrNull() ?: continue
                val theirs = state.objects.values.filter { it.isOnBattlefield() && it.controller == src.controller && it.tapped == true && state.matches(e.filter, it, src.controller, src) }
                if (theirs.isEmpty()) { trace.step("${src.name} would untap ${state.player(src.controller).possessive} ${e.filter.raw ?: "permanents"} in this untap step, but none of them are tapped.", "502.3"); continue }
                trace.step("${src.name} untaps ${state.player(src.controller).possessive} ${e.filter.raw ?: "permanents"} during this player's untap step as well.", "502.3", "614.1")
                theirs.forEach { it.tapped = false; state.outcomes += "${it.name} untaps (${src.name})." }
            }
            held.forEach { it.summoningSick = false; it.attacking = null; it.blocking = null }
            // A new turn, so the land plays and the extra ones an effect gave start over (305.2).
            state.landsPlayed.clear(); state.extraLandsThisTurn.clear()
            trace.step("${p.possessive.replaceFirstChar { it.uppercase() }} turn begins: ${p.subject.lowercase()} ${p.v("untaps", "untap")} all ${p.possessive} permanents, and everything ${p.subject.lowercase()} ${p.v("has", "have")} controlled since the turn began can attack and use {T} abilities.", "502.3", "302.6")
            return
        }
        if (step == "cleanup") {
            state.activePlayer = activePlayer; state.step = step; state.phase = "ending"
            val noMax = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.controller == activePlayer &&
                o.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { e -> e is StaticEffect.NoMaximumHandSize } }
            state.player(activePlayer).let { ap -> ap.handSize?.let { hand ->
                if (noMax != null) trace.step("${noMax.name} gives ${ap.subject.lowercase()} no maximum hand size, so ${ap.subject.lowercase()} ${ap.v("keeps", "keep")} all $hand card${if (hand == 1) "" else "s"}.", "514.1", "402.2")
                else if (hand > 7) { trace.step("${ap.subject} ${ap.v("has", "have")} $hand cards in hand and a maximum hand size of seven, so ${ap.subject.lowercase()} ${ap.v("discards", "discard")} ${hand - 7} card${if (hand - 7 == 1) "" else "s"} of ${ap.possessive} choice first.", "514.1", "402.2"); ap.handSize = 7; state.outcomes += "${ap.subject} ${ap.v("discards", "discard")} ${hand - 7} card${if (hand - 7 == 1) "" else "s"} to hand size." }
                else trace.step("${ap.subject} ${ap.v("has", "have")} $hand card${if (hand == 1) "" else "s"} in hand, no more than the maximum hand size of seven, so nothing is discarded.", "514.1", "402.2")
            } }
            val affected = state.objects.values.filter { it.isOnBattlefield() && (it.pumps.isNotEmpty() || it.tempKeywords.isNotEmpty() || it.damage > 0 || it.basePt != null) }
            trace.step("The cleanup step: all damage marked on permanents is removed and all \"until end of turn\" effects end, simultaneously.", "514.2")
            for (o in state.objects.values.filter { it.isOnBattlefield() && it.controlRevertsTo != null }) {
                val back = state.player(o.controlRevertsTo!!); o.controller = back.id; o.controlRevertsTo = null
                trace.step("The \"until end of turn\" control change on ${o.name} ends: ${back.subject} ${back.v("controls", "control")} it again (it doesn't leave the battlefield or untap).", "514.2", "611.2a")
                state.outcomes += "${o.name} is back under ${back.possessive} control."
            }
            state.cantLoseThisTurn.clear()
            state.cantCastThisTurn.clear()
            state.damageLifeFloor.clear()
            state.landsPlayed.clear(); state.extraLandsThisTurn.clear()
            for (o in affected) { o.pumps.clear(); o.tempKeywords.clear(); o.basePt = null; o.animatedAs = null; o.saddled = false; o.saddledThisTurn = 0; o.damage = 0; trace.step("${o.name} is back to ${if (o.def.isCreature) state.describePt(o) else "normal"} with no damage.", "514.2"); state.outcomes += "${o.name}'s until-end-of-turn effects and damage are gone (cleanup)." }
            state.shields.clear(); state.objects.values.forEach { it.exileOnDeath = null }
            return
        }
        state.activePlayer = activePlayer
        state.step = step
        state.phase = when (step) { "untap", "upkeep", "draw" -> "beginning"; "precombat_main" -> "precombat_main"; "postcombat_main" -> "postcombat_main"; "end", "cleanup" -> "ending"; else -> "combat" }
        // "end" and "upkeep" are steps; "main" is a phase. Say which, so the line reads as Magic does.
        val stepName = step.replace('_', ' ').let { n -> if (n.endsWith(" step") || n.endsWith(" phase") || n == "combat") n else if (n == "main") "$n phase" else "$n step" }
        trace.step("${state.player(activePlayer).possessive.replaceFirstChar { it.uppercase() }} $stepName begins.", when (step) { "upkeep" -> "503.1"; "end" -> "513.1"; "draw" -> "504.1"; else -> "500.1" })
        // 504.1: the active player draws a card as a turn-based action. Triggers on the step happen after it.
        if (step == "draw") {
            trace.step("${state.player(activePlayer).subject} ${state.player(activePlayer).v("draws", "draw")} a card for the turn. It is a turn-based action, so it happens before anyone gets priority and before any \"at the beginning of the draw step\" trigger resolves.", "504.1", "117.3a")
            draw(activePlayer, 1)
            state.assumptions += "This isn't the first turn of the game, so the active player draws for the turn (the starting player skips that draw, 103.7a)."
        }
        if (step == "end" && state.objects.values.any { it.isOnBattlefield() && (it.pumps.isNotEmpty() || it.tempKeywords.isNotEmpty()) }) {
            trace.step("\"Until end of turn\" effects don't end in the end step: \"at the beginning of the end step\" abilities trigger now, and the effects last until the cleanup step that follows.", "513.1", "514.2")
            state.outcomes += "Until-end-of-turn effects still apply during the end step; they end in the cleanup step."
        }
        // The discard to hand size is a turn-based action of the cleanup step (514.1), not the end step. Asked
        // "I have nine cards in hand at end of turn, what happens?" the answer was "nothing changes", which is
        // true of the end step and not of what was asked.
        if (step == "end") state.player(activePlayer).let { p -> p.handSize?.let { hand ->
            val noMax = state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.controller == activePlayer &&
                o.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { e -> e is StaticEffect.NoMaximumHandSize } }
            if (noMax != null && hand > 7) {
                trace.step("${p.subject} ${p.v("has", "have")} $hand cards in hand, but ${noMax.name} gives ${p.subject.lowercase()} no maximum hand size, so nothing is discarded in the cleanup step either.", "402.2", "514.1")
                state.outcomes += "${p.subject} ${p.v("discards", "discard")} nothing to hand size (${noMax.name})."
            } else if (hand > 7) {
                val n = hand - 7
                trace.step("${p.subject} ${p.v("has", "have")} $hand cards in hand, over the maximum hand size of seven, but nothing is discarded during the end step: the discard is a turn-based action of the cleanup step that follows.", "513.1", "514.1", "402.2")
                state.outcomes += "${p.subject} ${p.v("discards", "discard")} $n card${if (n == 1) "" else "s"} to hand size in the cleanup step that follows, not during the end step."
            }
        } }
        onEvent(GameEvent.StepBegins(step, activePlayer))
        // 725.2: the monarch's end-step draw is an inherent ability with no source, so it isn't on any permanent.
        if (step == "end") state.monarch?.let { mid ->
            if (mid == activePlayer) {
                val p = state.player(mid)
                trace.step("${p.subject} ${p.v("is", "are")} the monarch, and the monarch draws a card at the beginning of their end step. This ability has no source — it comes with the crown.", "725.2")
                draw(mid, 1)
            }
        }
    }

    /** 725.2: combat damage to the monarch hands the crown to the damaging creature's controller. */
    private fun monarchCombatDamage(source: GameObject, target: Ref) {
        val mid = state.monarch ?: return
        if ((target as? Ref.Player)?.id != mid || source.controller == mid) return
        val taker = state.player(source.controller); val lost = state.player(mid)
        state.monarch = source.controller
        trace.step("${source.name} dealt combat damage to the monarch, so ${taker.subject.lowercase()} ${taker.v("becomes", "become")} the monarch and ${lost.subject.lowercase()} ${lost.v("stops", "stop")} being it.", "725.2", "725.3")
        state.outcomes += "${taker.subject} ${taker.v("is", "are")} the monarch."
    }

    fun resolveTop() {
        val item = state.stack.removeLastOrNull() ?: run { trace.step("The stack is empty; nothing resolves."); return }
        trace.step("All players pass priority; ${item.describe} (top of the stack) starts to resolve.", "117.4", "608.1")
        // 608.2b target legality
        if (item.targets.isNotEmpty()) {
            val legal = item.targets.map { it to isTargetLegal(item, it) }
            val illegalCount = legal.count { !it.second }
            if (illegalCount == item.targets.size) {
                trace.step("${item.describe}'s target${if (item.targets.size > 1) "s are" else " is"} no longer legal (${legal.joinToString("; ") { whyIllegal(item, it.first) }}), so it doesn't resolve and is removed from the stack" +
                    (if (item.kind == StackKind.SPELL) " and put into its owner's graveyard" else "") + ".", "608.2b")
                state.outcomes += "${item.describe} doesn't resolve (all targets illegal)."
                if (item.kind == StackKind.SPELL) item.source.zone = Zone.GRAVEYARD
                if (item.kind == StackKind.SPELL && item.source.def.abilities.any { it is TriggeredAbility && (it.trigger is Trigger.ThisDies || it.trigger is Trigger.ThisLeavesBattlefield) }) trace.step("${item.source.name} goes to the graveyard from the stack, not from the battlefield, so its \"${item.source.def.abilities.filterIsInstance<TriggeredAbility>().first { it.trigger is Trigger.ThisDies || it.trigger is Trigger.ThisLeavesBattlefield }.text.replace("~", item.source.name)}\" ability doesn't trigger.", "603.6c", "608.2b")
                afterResolution(); return
            } else if (illegalCount > 0) {
                trace.step("Some targets of ${item.describe} are illegal (${legal.filter { !it.second }.joinToString("; ") { whyIllegal(item, it.first) }}); it resolves but won't affect them.", "608.2b")
            }
        }
        when (item.kind) {
            StackKind.SPELL -> {
                val def = item.source.def
                if (def.isInstantOrSorcery) {
                    if (item.targetsUnknown) trace.step("${def.name} resolves, but its target was never stated, so what it does to it isn't shown.", "608.2c")
                    else item.effect?.let { applyEffect(it, item) } ?: if (!Generic.isGeneric(def)) state.unsupported.add(Unsupported(def.name, "The spell has no modeled effect.")) else Unit
                    item.source.zone = if (item.flashback) Zone.EXILE else Zone.GRAVEYARD
                    if (item.source.token) trace.step("The copy of ${def.name} finishes resolving; a copy of a spell ceases to exist once it leaves the stack.", "608.2c", "707.10a")
                    else if (item.flashback) { trace.step("${def.name} was cast with flashback, so it is exiled instead of going to its owner's graveyard; it can't be cast again.", "702.34a", "608.2n"); state.outcomes += "${def.name} is exiled (flashback)." }
                    else trace.step("${def.name} finishes resolving and is put into its owner's graveyard.", "608.2c", "608.2n")
                } else {
                    item.source.zone = Zone.BATTLEFIELD; item.source.tapped = false; item.source.summoningSick = def.isCreature; item.source.timestamp = state.tick()
                    if (def.isAura) {
                        val t = item.targets.firstOrNull()
                        val tid = (t as? Ref.Obj)?.id
                        item.source.attachedTo = tid; applyControlEnchanted(item.source)
                        trace.step("${def.name} enters the battlefield attached to ${t?.let { state.nameOf(it) } ?: "nothing"}.", "608.3b", "303.4")
                    }
                    applyEntersReplacements(item.source, item.choice)
                    // What it is now: the same card, unless it entered as a copy of something else.
                    val now = item.source.def
                    val before = state.objects.values.filter { it.isOnBattlefield() && it.def.isCreature && it !== item.source }.associate { it.id to (it.power to it.toughness) }
                    trace.step("${def.name} resolves and enters the battlefield under ${state.player(item.controller).possessive} control${if (now !== def) " as a copy of ${now.name}${if (now.isCreature) ", a ${state.describePt(item.source)}" else ""}" else if (now.isCreature) " as a ${state.describePt(item.source)}" else ""}${if (item.source.tapped == true) ", tapped" else ""}.", "608.3a")
                    // "Enters tapped", "enters with counters" and "enters as a copy" have already happened; they aren't
                    // abilities that go on applying, so they don't get this line.
                    val entersOnly = setOf(StaticEffect.EntersTapped::class, StaticEffect.EntersWithCounters::class, StaticEffect.EntersAsCopy::class)
                    if (now.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it::class !in entersOnly }) trace.step("${now.name}'s static ability starts applying to the permanents it describes.", "604.2", "613.1")
                    now.abilities.filterIsInstance<UnparsedAbility>().takeIf { it.isNotEmpty() }?.let { un -> if (state.unsupported.none { it.what == now.name }) state.unsupported += Unsupported(now.name, "Rules text not modeled: " + un.joinToString(" | ") { it.text }) }
                    state.outcomes += "${if (now !== def) "${def.name}, a copy of ${now.name}," else now.name} enters the battlefield."
                    narrateLandTypeSetters(item.source)
                    onEvent(GameEvent.EntersBattlefield(item.source))
                    if (item.evoked) onEvokeEntered(item.source)
                }
            }
            StackKind.TRIGGERED, StackKind.ACTIVATED -> {
                item.effect?.let { applyEffect(it, item) }
                trace.step("${item.describe} finishes resolving and ceases to exist.", "608.2c", "608.2n")
            }
        }
        afterResolution()
    }

    fun resolveAll() {
        var guard = 0
        while (state.stack.isNotEmpty() && guard++ < 50) resolveTop()
    }

    fun stateBasedActions() {
        var changed = true; var rounds = 0
        while (changed && rounds++ < 10) {
            changed = false
            // 704.5j, the "legend rule": one legendary permanent with a given name per player.
            state.objects.values.filter { it.isOnBattlefield() && "Legendary" in it.def.supertypes }.groupBy { it.controller to it.name }.values.filter { it.size > 1 }.forEach { group ->
                val keep = group.maxByOrNull { it.timestamp }!!
                val p = state.player(keep.controller)
                for (o in group) if (o !== keep) { move(o, Zone.GRAVEYARD, "${p.subject} ${p.v("controls", "control")} two legendary permanents named ${o.name}; ${p.subject.lowercase()} ${p.v("chooses", "choose")} one and the other is put into its owner's graveyard (the \"legend rule\", a state-based action).", "704.3", "704.5j"); changed = true }
                state.assumptions += "${p.subject} ${p.v("keeps", "keep")} the newer ${keep.name} (704.5j lets ${p.subject.lowercase()} choose which)."
            }
            // +1/+1 and -1/-1 counters cancel out before anything is checked for dying (704.5q): a 2/2 with one
            // of each is a 2/2 with no counters, not a 2/2 carrying both.
            for (obj in state.objects.values.toList()) {
                if (!obj.isOnBattlefield()) continue
                val plus = obj.counters["+1/+1"] ?: 0; val minus = obj.counters["-1/-1"] ?: 0
                val n = minOf(plus, minus)
                if (n <= 0) continue
                obj.counters["+1/+1"] = plus - n; obj.counters["-1/-1"] = minus - n
                if (obj.counters["+1/+1"] == 0) obj.counters.remove("+1/+1")
                if (obj.counters["-1/-1"] == 0) obj.counters.remove("-1/-1")
                trace.step("${obj.name} has both +1/+1 and -1/-1 counters, so $n of each are removed (state-based action); it's now ${obj.power}/${obj.toughness}.", "704.3", "704.5q")
                // Said in the trace only, a board that was nothing but a creature carrying both kinds of counter
                // answered "nothing changes" — when removing them is the whole of what happens.
                state.outcomes += "${obj.name}: $n +1/+1 and $n -1/-1 counter${if (n == 1) "" else "s"} are removed (704.5q); it's ${obj.power}/${obj.toughness}."
                changed = true
            }
            // Creatures dying to the same state-based check die at once: each sees the others go (603.10a).
            val dying = state.objects.values.filter { o -> o.isOnBattlefield() && o.def.isCreature && o.toughness?.let { t -> t <= 0 || (!o.has("indestructible") && ((o.damage >= t && o.damage > 0) || (o.dealtDeathtouchDamage && o.damage > 0))) } == true }
            if (dying.size > 1) { leavingTogether = dying.map { it.id }.toSet(); trace.step("${dying.joinToString(", ") { it.name }} are all put into their owners' graveyards by the same state-based check, simultaneously; abilities that trigger on a creature dying see all of them go, including a creature's own leaving alongside the others.", "704.3", "603.10a") }
            try { for (obj in state.objects.values.toList()) {
                if (!obj.isOnBattlefield()) continue
                if (obj.def.isCreature) {
                    val t = obj.toughness
                    if (t != null && t <= 0) { move(obj, Zone.GRAVEYARD, "${obj.name} has toughness $t and is put into its owner's graveyard (state-based action).", "704.3", "704.5f"); changed = true; continue }
                    val lethal = t != null && obj.damage >= t && obj.damage > 0
                    if ((lethal || obj.dealtDeathtouchDamage) && obj.has("indestructible")) {
                        trace.step("${obj.name} has lethal damage but is indestructible, so it isn't destroyed.", "702.12b"); obj.dealtDeathtouchDamage = false; continue
                    }
                    if (lethal) { destroy(obj, "${obj.name} has ${obj.damage} damage marked and toughness $t, so it's destroyed (state-based action).", "704.3", "704.5g"); changed = true; continue }
                    if (obj.dealtDeathtouchDamage && obj.damage > 0) { destroy(obj, "${obj.name} was dealt damage by a source with deathtouch, so it's destroyed (state-based action).", "704.3", "704.5h", "702.2b"); changed = true; continue }
                }
            } } finally { if (dying.size > 1) leavingTogether = emptySet() }
            for (obj in state.objects.values.toList()) {
                if (obj.isOnBattlefield() && obj.def.isPlaneswalker && (obj.counters["loyalty"] ?: 0) <= 0) { move(obj, Zone.GRAVEYARD, "${obj.name} has 0 loyalty and is put into its owner's graveyard (state-based action).", "704.3", "704.5i", "306.9"); changed = true }
            }
            for (obj in state.objects.values.toList()) {
                // "I bounce my own token, what happens?": the answer is that it stops existing, so the outcome says
                // it. (And the name may already end in "token", which read "a 1/1 token token".)
                if (obj.token && !obj.isOnBattlefield() && obj.zone != Zone.STACK) {
                    state.objects.remove(obj.id)
                    val what = if (obj.name.endsWith("token", true)) obj.name else "${obj.name} token"
                    trace.step("$what ceases to exist (a token that isn't on the battlefield stops existing the next time state-based actions are checked).", "704.5d", "111.7")
                    state.outcomes += "$what ceases to exist; it doesn't stay in the zone it went to."
                    changed = true
                }
            }
            for (obj in state.objects.values.toList()) {
                if (!obj.isOnBattlefield()) continue
                val host = obj.attachedTo?.let { state.objects[it] }
                if (obj.def.isAura) {
                    val enchant = obj.def.enchant
                    val legal = host != null && host.isOnBattlefield() && (enchant == null || state.matches(enchant, host, obj.controller, obj))
                    if (!legal) { move(obj, Zone.GRAVEYARD, "${obj.name} is ${if (host == null || !host.isOnBattlefield()) "no longer attached to anything" else "attached to something it can't enchant"}, so it's put into its owner's graveyard (state-based action).", "704.3", "704.5m"); changed = true }
                } else if (obj.def.isEquipment && obj.attachedTo != null) {
                    if (host == null || !host.isOnBattlefield() || !host.def.isCreature) { obj.attachedTo = null; trace.step("${obj.name} is no longer attached to a creature, so it becomes unattached and stays on the battlefield (state-based action).", "704.3", "704.5n"); state.outcomes += "${obj.name} stays on the battlefield, unattached."; changed = true }
                }
            }
            for (p in state.players) {
                p.commanderDamage.entries.firstOrNull { it.value >= 21 }?.let { (cid, dmg) -> if (!p.lost) { p.lost = true; trace.step("${p.subject} ${p.v("has", "have")} been dealt $dmg combat damage by ${state.objects[cid]?.name ?: "a commander"} and ${p.v("loses", "lose")} the game (state-based action).", "704.6c", "903.10a"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} the game (commander damage)."; changed = true } }
                if (p.poison >= 10 && !p.lost) { p.lost = true; trace.step("${p.subject} ${p.v("has", "have")} ${p.poison} poison counters and ${p.v("loses", "lose")} the game (state-based action).", "704.3", "704.5c"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} the game (poison)."; changed = true }
                val life = p.life
                val cantLose = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == p.id && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.CantLose } }
                // Angel's Grace: the same thing for a turn, from a spell that has already resolved.
                if (cantLose == null && p.id in state.cantLoseThisTurn && ((life != null && life <= 0) || p.poison >= 10 || p.drewFromEmpty || p.commanderDamage.values.any { it >= 21 })) {
                    if (state.outcomes.none { it.contains("can't lose the game this turn") }) { trace.step("${p.subject} would lose the game, but ${p.subject.lowercase()} can't lose this turn, so the state-based action doesn't apply.", "704.5a", "104.3b"); state.outcomes += "${p.subject} ${p.v("stays", "stay")} in the game: ${p.subject.lowercase()} can't lose the game this turn." }
                    continue
                }
                if (cantLose != null && ((life != null && life <= 0) || p.poison >= 10 || p.drewFromEmpty || p.commanderDamage.values.any { it >= 21 })) { if (state.outcomes.none { it.contains("${cantLose.name} keeps") }) { trace.step("${p.subject} would lose the game, but ${cantLose.name} says ${p.subject.lowercase()} can't lose, so the state-based action doesn't apply while it's on the battlefield.", "704.5a", "104.3b"); state.outcomes += "${p.subject} ${p.v("stays", "stay")} in the game: ${cantLose.name} keeps ${p.subject.lowercase()} from losing." }; continue }
                if (p.drewFromEmpty && !p.lost) { p.lost = true; p.drewFromEmpty = false; trace.step("${p.subject} attempted to draw from an empty library since state-based actions were last checked, so ${p.subject.lowercase()} ${p.v("loses", "lose")} the game (state-based action).", "704.5b", "121.4"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} the game (drew from an empty library)."; changed = true }
                if (life != null && life <= 0 && !p.lost) { p.lost = true; trace.step("${p.subject} ${p.v("has", "have")} $life life and ${p.v("loses", "lose")} the game (state-based action).", "704.3", "704.5a"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} the game."; changed = true }
            }
        }
    }


    // ---- combat ----------------------------------------------------------------------------

    /** Declare one attacker (508.1). `defender` is a player, or a planeswalker/battle object. */
    fun declareAttacker(playerId: String, attackerId: String, defender: Ref) {
        emptyStackFirst("declaring attackers")
        val a = state.obj(attackerId)
        val p = state.player(playerId)
        state.phase = "combat"; state.step = "declare_attackers"
        if ((!a.def.isCreature && a.animatedAs == null) || !a.isOnBattlefield()) { trace.step("${a.name} isn't a creature on the battlefield, so it can't attack.", "506.3"); state.outcomes += "${a.name} can't attack."; return }
        state.notACreatureBecause(a)?.let { why -> trace.step("${a.name} isn't a creature right now — ${p.possessive} $why — so it can't be declared as an attacker. It's still an enchantment on the battlefield.", "506.3", "508.1a"); state.outcomes += "${a.name} can't attack (${p.possessive} $why)."; return }
        if (a.controller != playerId) { trace.step("${a.name} isn't controlled by ${p.subject.lowercase()}, so ${p.subject.lowercase()} can't attack with it.", "508.1a"); state.outcomes += "${p.subject} can't attack with ${a.name} (${p.subject.lowercase()} ${p.v("doesn't", "don't")} control it)."; return }
        state.activePlayer?.takeIf { it != playerId }?.let { active ->
            // Nobody said whose turn it is: a player declaring attackers is what says it, so the turn is theirs.
            if (!state.activePlayerStated) { state.activePlayer = playerId; return@let }
            trace.step("It's ${state.player(active).possessive} turn, and only the active player declares attackers, so ${p.subject.lowercase()} can't attack now.", "508.1", "506.2")
            state.outcomes += "${p.subject} can't attack on ${state.player(active).possessive} turn."; return
        }
        if (a.has("defender")) { trace.step("${a.name} has defender and can't attack.", "702.3b"); state.outcomes += "${a.name} can't attack (defender)."; return }
        if (cant(a, "attack")) { trace.step("${a.name} can't attack (a rules text says so${cantSource(a, "attack")?.let { ": $it" } ?: ""}).", "508.1c"); state.outcomes += "${a.name} can't attack."; return }
        if (a.tapped == true) { trace.step("${a.name} is tapped, so it can't be declared as an attacker.", "508.1a"); state.outcomes += "${a.name} can't attack (tapped)."; return }
        if (a.summoningSick == true && !a.has("haste")) { trace.step("${a.name} came under ${p.possessive} control this turn and doesn't have haste, so it can't attack (\"summoning sickness\").", "508.1a", "302.6"); state.outcomes += "${a.name} can't attack (summoning sick)."; return }
        (defender as? Ref.Obj)?.let { d -> val o = state.objects[d.id]; if (o == null || !o.isOnBattlefield() || !(o.def.isPlaneswalker || "Battle" in o.def.types)) { trace.step("${state.nameOf(defender)} isn't a player, planeswalker or battle, so it can't be attacked.", "506.3"); return } else if (o.controller == playerId) { trace.step("${o.name} is ${p.possessive} own permanent; only an opponent's planeswalker or a battle can be attacked.", "506.2", "508.1b"); return } }
        // Propaganda / Ghostly Prison: attacking that player costs mana per attacker.
        val defendingPlayer = when (defender) { is Ref.Player -> defender.id; is Ref.Obj -> state.objects[defender.id]?.controller; else -> null }
        // Two taxes are one cost to pay: paying the first and then failing the second spent mana for nothing.
        run {
            val all = state.objects.values.filter { it.isOnBattlefield() && it.controller == defendingPlayer }
                .flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.AttackTax>().map { o to it } }
            val total = all.sumOf { (_, t) -> Regex("""\{(\d+)\}""").find(t.cost)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            if (all.size > 1 && p.mana != null && p.mana!! < total) {
                trace.step("${all.joinToString(" and ") { (o, _) -> o.name }} each say creatures can't attack ${state.nameOf(Ref.Player(defendingPlayer!!))} unless their controller pays, so attacking with ${a.name} costs {$total} in all; ${p.subject.lowercase()} ${p.v("has", "have")} only ${p.mana}, so it can't attack.", "508.1c")
                state.outcomes += "${a.name} can't attack (attacking costs {$total} in all: ${all.joinToString(" and ") { (o, t) -> "${t.cost} for ${o.name}" }})."
                return
            }
        }
        state.objects.values.filter { it.isOnBattlefield() && it.controller == defendingPlayer }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.AttackTax>().map { o to it } }.forEach { (src, tax) ->
            when {
                state.wontPay.remove(playerId) -> { trace.step("${src.name} says creatures can't attack ${state.nameOf(Ref.Player(defendingPlayer!!))} unless their controller pays ${tax.cost} for each; ${p.subject.lowercase()} ${p.v("doesn't", "don't")} pay, so ${a.name} can't attack.", "508.1c"); state.outcomes += "${a.name} can't attack (${src.name}'s cost not paid)."; return }
                state.willPay.remove(playerId) -> trace.step("${p.subject} ${p.v("pays", "pay")} ${tax.cost} for ${src.name} so that ${a.name} can attack.", "508.1c")
                p.mana != null -> {
                    val n = Regex("""\{(\d+)\}""").find(tax.cost)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (p.mana!! >= n) { p.mana = p.mana!! - n; trace.step("${src.name} says creatures can't attack ${state.nameOf(Ref.Player(defendingPlayer!!))} unless their controller pays ${tax.cost} for each. ${p.subject} ${p.v("has", "have")} the mana, so ${p.subject.lowercase()} ${p.v("pays", "pay")} ${tax.cost} for ${src.name} and ${a.name} attacks (${p.mana} mana left).", "508.1c"); state.outcomes += "${p.subject} ${p.v("pays", "pay")} ${tax.cost} for ${src.name} (${a.name} attacks)." }
                    else { trace.step("${src.name} says creatures can't attack ${state.nameOf(Ref.Player(defendingPlayer!!))} unless their controller pays ${tax.cost} for each; ${p.subject.lowercase()} ${p.v("has", "have")} only ${p.mana} mana left, so ${a.name} can't attack.", "508.1c"); state.outcomes += "${a.name} can't attack (can't pay ${tax.cost} for ${src.name})."; return }
                }
                else -> {
                    trace.step("${src.name} says creatures can't attack ${state.nameOf(Ref.Player(defendingPlayer!!))} unless their controller pays ${tax.cost} for each; since ${a.name} attacks, ${p.subject.lowercase()} must be paying.", "508.1c")
                    state.assumptions += "${p.subject} ${p.v("pays", "pay")} ${tax.cost} for ${src.name} for each attacker (otherwise they couldn't attack)."
                    noteAttackTax(playerId, src.name, tax.cost, a.name)
                }
            }
        }
        if (a.summoningSick == null && !a.has("haste")) state.assumptions += "${a.name} has been under ${p.possessive} control since the turn began (otherwise it couldn't attack, 508.1a)."
        if (a.summoningSick == true && a.has("haste")) trace.step("${a.name} has haste, so it can attack the turn it came under ${p.possessive} control.", "702.10b")
        val firstAttacker = state.objects.values.none { it.attacking != null }
        // Ensnaring Bridge: creatures with power greater than the number of cards in the Bridge controller's hand can't attack.
        state.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.Cant>().filter { it.what == "attack" && it.powerAboveHand }.map { o to it } }.forEach { (src, _) ->
            val hand = state.player(src.controller).handSize
            if (hand == null) state.assumptions += "${src.name}: ${a.name} can attack only if its power (${a.power}) isn't greater than the number of cards in ${state.player(src.controller).possessive} hand, which wasn't stated; assuming it may attack."
            else if ((a.power ?: 0) > hand) { trace.step("${src.name}: ${state.player(src.controller).subject} ${state.player(src.controller).v("has", "have")} $hand card${if (hand == 1) "" else "s"} in hand and ${a.name} has power ${a.power}, so it can't attack.", "508.1c"); state.outcomes += "${a.name} can't attack (${src.name})."; return }
            else trace.step("${src.name} allows ${a.name} to attack: its power (${a.power}) isn't greater than the $hand card${if (hand == 1) "" else "s"} in ${state.player(src.controller).possessive} hand.", "508.1c")
        }
        attackConditionUnmet(a, defendingPlayer)?.let { cond ->
            trace.step("${a.name} can't attack unless $cond, and that isn't so, so it can't be declared as an attacker.", "508.1c")
            state.outcomes += "${a.name} can't attack (it can't attack unless $cond)."
            return
        }
        a.attacking = defender
        if (a.has("vigilance")) trace.step("${p.subject} ${p.v("attacks", "attack")} with ${a.name} (${state.describePt(a)}), attacking ${state.nameOf(defender)}. It has vigilance, so it doesn't tap.", "508.1a", "702.20b")
        else { trace.step("${p.subject} ${p.v("attacks", "attack")} with ${a.name} (${state.describePt(a)}), attacking ${state.nameOf(defender)}. It becomes tapped.", "508.1a", "508.1f"); tap(a) }
        if (a.def.abilities.any { it is StaticAbility && it.effects.contains(StaticEffect.MustAttack) }) trace.step("${a.name} attacks each combat if able, so it had to be declared as an attacker.", "508.1d")
        if (declaringAttackers) pendingAttackers += a
        else { onEvent(GameEvent.Attacks(a)); if (firstAttacker) onEvent(GameEvent.PlayerAttacks(playerId)) }
    }

    private var declaringAttackers = false
    private val pendingAttackers = mutableListOf<GameObject>()

    /** Attackers described together are declared as one action (508.1); their triggers fire once all are declared. */
    fun beginDeclaringAttackers() { declaringAttackers = true; pendingAttackers.clear() }
    fun finishDeclaringAttackers() {
        declaringAttackers = false
        val declared = pendingAttackers.toList(); pendingAttackers.clear()
        if (declared.isEmpty()) return
        val byPlayer = declared.groupBy { it.controller }
        for (a in declared) onEvent(GameEvent.Attacks(a))
        for ((pid, list) in byPlayer) { onEvent(GameEvent.PlayerAttacks(pid)); if (list.size == 1 && state.objects.values.count { it.attacking != null && it.controller == pid } == 1) onEvent(GameEvent.AttacksAlone(list[0])) }
    }

    /** Declare one blocker for one attacker (509.1). Legality of evasion abilities is checked here; menace is re-checked when damage is dealt. */
    /** Why [b] can't block [a] — the trace line, the rules behind it, and the one-line outcome — or null when it can. */
    fun cantBlockWhy(a: GameObject, b: GameObject): Triple<String, List<String>, String>? {
        if (cant(a, "be blocked")) return Triple("${a.name} can't be blocked.", listOf("509.1b"), "${b.name} can't block ${a.name}.")
        cantBeBlockedBy(a, b)?.let { r -> return Triple("${a.name} can't be blocked by ${r.by!!.raw}, and ${b.name} is one, so it can't block ${a.name}.", listOf("509.1b"), "${b.name} can't block ${a.name}.") }
        state.protections(a).takeIf { it.isNotEmpty() }?.let { prots ->
            val bq = qualitiesOf(b.def)
            prots.firstOrNull { it == "everything" || it in bq }?.let { q -> return Triple("${a.name} has protection from $q, so ${b.name} can't block it.", listOf("702.16f"), "${b.name} can't block ${a.name} (protection).") }
        }
        if (a.has("fear") && !(b.has("fear") || "Artifact" in b.def.types || 'B' in b.def.colors)) return Triple("${a.name} has fear and can't be blocked except by artifact creatures and/or black creatures.", listOf("702.36b"), "${b.name} can't block ${a.name} (fear).")
        if (a.has("intimidate") && !("Artifact" in b.def.types || b.def.colors.intersect(a.def.colors).isNotEmpty())) return Triple("${a.name} has intimidate and can't be blocked except by artifact creatures and/or creatures that share a color with it.", listOf("702.13b"), "${b.name} can't block ${a.name} (intimidate).")
        if (a.has("horsemanship") && !b.has("horsemanship")) return Triple("${a.name} has horsemanship and can't be blocked except by creatures with horsemanship.", listOf("702.31b"), "${b.name} can't block ${a.name} (horsemanship).")
        if (a.has("shadow") != b.has("shadow")) return Triple("${a.name} ${if (a.has("shadow")) "has" else "doesn't have"} shadow and ${b.name} ${if (b.has("shadow")) "has" else "doesn't have"}; creatures with shadow can only block and be blocked by creatures with shadow.", listOf("702.28b"), "${b.name} can't block ${a.name} (shadow).")
        if (a.has("skulk") && (b.power ?: 0) > (a.power ?: 0)) return Triple("${a.name} has skulk and can't be blocked by creatures with greater power.", listOf("702.118b"), "${b.name} can't block ${a.name} (skulk).")
        (a.def.keywords.firstOrNull { it.endsWith("walk") && it != "landwalk" } ?: a.def.abilities.filterIsInstance<StaticAbility>().map { it.text.trimEnd('.').lowercase() }.firstOrNull { it.endsWith("walk") && !it.contains(' ') })?.let { walk ->
            val landType = walk.removeSuffix("walk").replaceFirstChar { it.uppercase() }
            val defender = state.player(b.controller)
            if (state.objects.values.any { it.isOnBattlefield() && it.controller == defender.id && "Land" in it.def.types && (it.def.subtypes.any { st -> st.equals(landType, true) } || it.def.name.equals(landType, true)) })
                return Triple("${a.name} has $walk and ${defender.subject.lowercase()} ${defender.v("controls", "control")} a $landType, so it can't be blocked.", listOf("702.14c"), "${b.name} can't block ${a.name} ($walk).")
        }
        b.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.BlockOnly>().firstOrNull { !state.matches(it.filter, a, b.controller, b) }?.let { r ->
            return Triple("${b.name} can block only ${r.filter.raw}, and ${a.name} isn't one, so it can't block ${a.name}.", listOf("509.1b"), "${b.name} can't block ${a.name} (it can block only ${r.filter.raw}).")
        }
        if (a.has("flying") && !(b.has("flying") || b.has("reach"))) return Triple("${a.name} has flying and ${b.name} has neither flying nor reach, so ${b.name} can't block it.", listOf("702.9b"), "${b.name} can't block ${a.name} (flying).")
        return null
    }

    fun declareBlocker(playerId: String, blockerId: String, attackerId: String) {
        emptyStackFirst("declaring blockers")
        val b = state.obj(blockerId); val a = state.obj(attackerId); val p = state.player(playerId)
        state.step = "declare_blockers"
        if ((!b.def.isCreature && b.animatedAs == null) || !b.isOnBattlefield()) { trace.step("${b.name} isn't a creature on the battlefield, so it can't block.", "506.3"); state.outcomes += "${b.name} can't block (it isn't a creature on the battlefield)."; return }
        state.notACreatureBecause(b)?.let { why -> trace.step("${b.name} isn't a creature right now — ${state.player(b.controller).possessive} $why — so it can't block.", "506.3", "509.1a"); state.outcomes += "${b.name} can't block (${state.player(b.controller).possessive} $why)."; return }
        if (a.attacking == null) { trace.step("${a.name} isn't attacking, so ${b.name} can't block it.", "509.1a"); state.outcomes += "${b.name} can't block ${a.name} (${a.name} isn't attacking)."; return }
        // A creature may only block an attacker that is attacking its controller, a planeswalker they control, or a battle they protect.
        val defendsAgainst = when (val d = a.attacking) { is Ref.Player -> d.id == playerId; is Ref.Obj -> state.objects[d.id]?.controller == playerId; else -> true }
        if (!defendsAgainst) { trace.step("${a.name} is attacking ${state.nameOf(a.attacking!!)}, not ${p.subject.lowercase()}${if (p.you) "" else " or a planeswalker ${p.subject} controls"}, so ${b.name} can't block it.", "509.1a"); state.outcomes += "${b.name} can't block ${a.name} (it isn't attacking ${p.subject.lowercase()})."; return }
        if (b.tapped == true) { trace.step("${b.name} is tapped, so it can't block.", "509.1a"); state.outcomes += "${b.name} can't block (tapped)."; return }
        if (cant(b, "block")) { trace.step("${b.name} can't block (a rules text says so${cantSource(b, "block")?.let { ": $it" } ?: ""}).", "509.1b"); state.outcomes += "${b.name} can't block."; return }
        cantBlockWhy(a, b)?.let { (why, rules, out) -> trace.step(why, *rules.toTypedArray()); state.outcomes += out; return }
        val firstBlocker = blockersOf(a).isEmpty()
        b.blocking = a.id; a.wasBlocked = true
        if (firstBlocker) onEvent(GameEvent.BecomesBlocked(a))
        onEvent(GameEvent.BecomesBlockedBy(a, b))
        onEvent(GameEvent.Blocks(b))
        trace.step("${p.subject} ${p.v("blocks", "block")} ${a.name} with ${b.name} (${state.describePt(b)}). ${a.name} is now a blocked creature and stays blocked even if ${b.name} leaves combat.", "509.1a", "509.1g", "509.1h")
    }

    /** The combat damage step (510), including a first-strike step when needed (510.4). */
    fun combatDamage() {
        state.step = "combat_damage"
        // Fog Bank and the like: their own combat damage, given and taken, is prevented.
        for (o in state.objects.values.filter { it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { e -> e is StaticEffect.PreventOwnCombatDamage } }) {
            if (state.combatDamageMuted.add(o.id)) trace.step("${o.name} prevents all combat damage that would be dealt to it and by it, so it neither deals nor takes combat damage.", "604.2", "615.1")
        }
        val attackers = state.objects.values.filter { it.attacking != null && it.isOnBattlefield() }
        if (attackers.isEmpty()) { trace.step("No creatures are attacking, so there is no combat damage step.", "506.1"); return }
        // Menace: a single blocker is not a legal block.
        for (a in attackers) if (a.has("menace")) {
            val bs = blockersOf(a)
            if (bs.size == 1) { trace.step("${a.name} has menace and can't be blocked except by two or more creatures; blocking it with only ${bs[0].name} isn't a legal block, so ${a.name} is unblocked.", "702.111b", "509.1a"); state.outcomes += "${bs[0].name} can't block ${a.name} on its own (menace)." + (state.objects.values.count { it.isOnBattlefield() && it.controller == bs[0].controller && it.def.isCreature && it.tapped != true && it.id != bs[0].id }.takeIf { it > 0 }?.let { " Another creature blocking alongside it would be a legal block." } ?: ""); bs[0].blocking = null; a.wasBlocked = false }
        }
        // "Whenever ~ attacks and isn't blocked": the condition is checked once blockers are declared, so the
        // trigger goes on the stack then and resolves before any combat damage.
        val stackBefore = state.stack.size
        for (a in attackers) if (a.wasBlocked != true && state.unblockedTriggered.add(a.id)) onEvent(GameEvent.BecomesUnblocked(a))
        if (state.stack.size > stackBefore) {
            trace.step("Triggers that check for an unblocked attacker go on the stack in the declare blockers step, so they resolve before the combat damage step.", "509.1h", "117.4")
            resolveAll()
        }
        val strikers = (attackers + attackers.flatMap { blockersOf(it) }).filter { it.has("first strike") || it.has("double strike") }
        if (strikers.isNotEmpty()) {
            trace.step("At least one creature has first strike or double strike, so there is an extra combat damage step in which only those creatures deal damage.", "510.4", "702.7b")
            dealCombatDamage(attackers) { it.has("first strike") || it.has("double strike") }
            trace.step("Then the regular combat damage step: creatures without first strike, plus any with double strike, deal damage.", "510.4", "702.4b")
            dealCombatDamage(attackers.filter { it.isOnBattlefield() && it.attacking != null }) { !it.has("first strike") || it.has("double strike") }
        } else {
            dealCombatDamage(attackers) { true }
        }
        state.combatDamageDealt = true
        trace.step("The active player receives priority.", "510.3")
    }

    private fun blockersOf(a: GameObject) = state.objects.values.filter { it.blocking == a.id && it.isOnBattlefield() }

    private fun dealCombatDamage(attackers: List<GameObject>, deals: (GameObject) -> Boolean) {
        data class Hit(val source: GameObject, val target: Ref, val amount: Int) { var dealt = 0 }
        val hits = mutableListOf<Hit>()
        for (a in attackers) {
            if (!a.isOnBattlefield() || a.attacking == null) continue
            val blockers = blockersOf(a)
            if (deals(a)) {
                val power = a.power ?: 0
                if (power <= 0) trace.step("${a.name} has power $power and assigns no combat damage.", "510.1a")
                else if (blockers.isEmpty() && a.wasBlocked) {
                    if (a.has("trample")) { trace.step("${a.name} was blocked but its blocker is gone; it has trample, so it assigns all $power damage to ${state.nameOf(a.attacking!!)}.", "702.19d"); hits += Hit(a, a.attacking!!, power) }
                    else trace.step("${a.name} was blocked and its blocker has left combat. A blocked creature stays blocked, and without trample it assigns no combat damage at all.", "509.1h", "510.1c")
                }
                else if (blockers.isEmpty()) { trace.step("${a.name} is unblocked and assigns $power damage to ${state.nameOf(a.attacking!!)}.", "510.1b"); hits += Hit(a, a.attacking!!, power) }
                else if (blockers.size == 1) {
                    val b = blockers[0]
                    val lethal = if (a.has("deathtouch")) 1 else maxOf(0, (b.toughness ?: 0) - b.damage)
                    if (a.has("trample") && power > lethal) {
                        trace.step("${a.name} has trample: it assigns lethal damage ($lethal${if (a.has("deathtouch")) ", any amount is lethal with deathtouch" else ""}) to ${b.name} and the remaining ${power - lethal} to ${state.nameOf(a.attacking!!)}.", "510.1c", "702.19b", *(if (a.has("deathtouch")) arrayOf("702.2c") else emptyArray()))
                        hits += Hit(a, Ref.Obj(b.id), lethal); hits += Hit(a, a.attacking!!, power - lethal)
                    } else { trace.step("${a.name} is blocked by ${b.name} and assigns all $power damage to it.", "510.1c"); hits += Hit(a, Ref.Obj(b.id), power) }
                } else {
                    // Divided as the attacker's controller chooses (510.1c): assume lethal to each in order, remainder to the last (or over with trample).
                    var left = power
                    val parts = mutableListOf<String>()
                    for ((i, b) in blockers.withIndex()) {
                        val lethal = if (a.has("deathtouch")) 1 else maxOf(0, (b.toughness ?: 0) - b.damage)
                        val give = if (i == blockers.lastIndex && !a.has("trample")) left else minOf(left, lethal)
                        if (give > 0) { hits += Hit(a, Ref.Obj(b.id), give); parts += "$give to ${b.name}"; left -= give }
                    }
                    if (left > 0 && a.has("trample")) { hits += Hit(a, a.attacking!!, left); parts += "$left to ${state.nameOf(a.attacking!!)} (trample)" }
                    trace.step("${a.name} is blocked by ${blockers.joinToString(" and ") { it.name }}; its controller divides its $power damage among them as they choose. Assuming ${parts.joinToString(", ")}.", "510.1c", *(if (a.has("trample")) arrayOf("702.19b") else emptyArray()))
                    state.assumptions += "${a.name}'s damage is divided as: ${parts.joinToString(", ")} (510.1c lets its controller choose)."
                }
            }
            for (b in blockers) if (deals(b)) {
                val bp = b.power ?: 0
                if (bp <= 0) trace.step("${b.name} has power $bp and assigns no combat damage.", "510.1a")
                else { trace.step("${b.name} assigns $bp damage to ${a.name}.", "510.1d"); hits += Hit(b, Ref.Obj(a.id), bp) }
            }
        }
        if (hits.isEmpty()) return
        trace.step("All that combat damage is dealt simultaneously.", "510.2")
        inCombatDamage = true
        for (h in hits) {
            h.dealt = applyDamage(h.source.name, h.target, h.amount, h.source)
            if (h.source.has("deathtouch")) (h.target as? Ref.Obj)?.let { state.objects[it.id]?.dealtDeathtouchDamage = true }
            if (h.source.has("lifelink") && h.dealt > 0) { val c = state.player(h.source.controller); trace.step("${h.source.name} has lifelink, so its controller gains life equal to the damage dealt.", "702.15b"); gainLife(c, h.dealt) }
        }
        inCombatDamage = false
        hits.filter { it.target is Ref.Player }.map { it.source.controller }.distinct().forEach { onEvent(GameEvent.CreaturesDealtCombatDamageToPlayer(it)) }
        stateBasedActions()
    }

    // ---- triggers --------------------------------------------------------------------------

    sealed interface GameEvent {
        data class SpellCast(val item: StackItem) : GameEvent
        data class EntersBattlefield(val obj: GameObject) : GameEvent
        data class Dies(val obj: GameObject) : GameEvent
        data class LeavesBattlefield(val obj: GameObject) : GameEvent
        data class Attacks(val obj: GameObject) : GameEvent
        data class BecomesSaddled(val obj: GameObject) : GameEvent
        data class CountersPut(val obj: GameObject, val kind: String, val count: Int) : GameEvent
        data class PlayerAttacks(val playerId: String) : GameEvent
        data class AttacksAlone(val obj: GameObject) : GameEvent
        data class StepBegins(val step: String, val activePlayer: String) : GameEvent
        data class DamageDealt(val source: GameObject, val target: Ref, val amount: Int, val combat: Boolean) : GameEvent
        data class LifeGained(val playerId: String, val amount: Int) : GameEvent
        data class BecomesBlocked(val obj: GameObject) : GameEvent
        /** Fired once per blocker, unlike BecomesBlocked which fires only when the attacker first becomes blocked. */
        data class BecomesBlockedBy(val obj: GameObject, val blocker: GameObject) : GameEvent
        data class Blocks(val obj: GameObject) : GameEvent
        data class BecomesUnblocked(val obj: GameObject) : GameEvent
        data class BecomesTarget(val obj: GameObject, val by: String, val sourceId: String) : GameEvent
        data class BecomesTapped(val obj: GameObject) : GameEvent
        data class BecomesMonstrous(val obj: GameObject) : GameEvent
        /** A copy of a spell was put on the stack; a copy isn't cast, so only magecraft-style triggers see it. */
        data class SpellCopied(val item: StackItem) : GameEvent
        data class Cycled(val obj: GameObject) : GameEvent
        data class Drew(val playerId: String) : GameEvent
        data class CreaturesDealtCombatDamageToPlayer(val playerId: String) : GameEvent
    }

    /** Ids of permanents leaving the battlefield in one event (mass removal), for leaves-the-battlefield look-back. */
    private var leavingTogether: Set<String> = emptySet()

    private fun onEvent(event: GameEvent) {
        // Torpor Orb / Hushbringer: creatures entering (or dying) don't cause abilities to trigger.
        val hush = state.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.NoEtbTriggers>().map { o to it } }
        val muted = hush.firstOrNull { (_, e) -> (event is GameEvent.EntersBattlefield && event.obj.def.isCreature) || (e.alsoDies && event is GameEvent.Dies && event.obj.def.isCreature) }
        if (muted != null) {
            val what = if (event is GameEvent.EntersBattlefield) "${event.obj.name} entering the battlefield" else "${(event as GameEvent.Dies).obj.name} dying"
            trace.step("${muted.first.name} is on the battlefield, so $what doesn't cause any abilities to trigger (its own \"when this enters\" abilities included).", "603.2", "603.6")
            return
        }
        val triggered = mutableListOf<Pair<GameObject, TriggeredAbility>>()
        for (obj in state.objects.values) {
            if (obj.isOnBattlefield() && state.abilitiesLostOn(obj) != null) continue
            val jailer = if (obj.zone == Zone.GRAVEYARD) graveyardAbilitiesGone() else null
            for (ability in obj.def.abilities.filterIsInstance<TriggeredAbility>()) {
                val fromGy = obj.zone == Zone.GRAVEYARD && functionsFromGraveyard(ability)
                if (fromGy && jailer != null) {
                    if (state.trace.steps.none { it.text.startsWith("${jailer.name} takes the abilities away") })
                        trace.step("${jailer.name} takes the abilities away from every card in every graveyard, so ${obj.name} has no ability to trigger while it's there.", "604.2", "613.1f")
                    continue
                }
                if (matches(obj, ability.trigger, event, fromGy)) triggered += obj to ability
            }
        }
        // Elesh Norn: a permanent entering causes no abilities of her controller's opponents to trigger.
        if (event is GameEvent.EntersBattlefield) {
            val norn = state.objects.values.firstOrNull { p -> p.isOnBattlefield() && p.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.NoEtbTriggersForOpponents } }
            if (norn != null) {
                val muted = triggered.filter { it.first.controller != norn.controller }
                if (muted.isNotEmpty()) {
                    fun side(p: String) = if (state.player(p).you) "yours" else "${state.player(p).name}'s"
                    trace.step("${norn.name} is ${side(norn.controller)} and says permanents entering don't cause abilities of permanents its controller's opponents control to trigger. ${muted.joinToString(" and ") { it.first.name }} ${if (muted.size == 1) "is" else "are"} ${side(muted.first().first.controller)}, so ${muted.joinToString(" and ") { it.first.name + "'s ability" }} doesn't trigger at all.", "603.2", "604.2")
                    triggered.removeAll(muted)
                }
            }
        }
        // Panharmonicon and Elesh Norn: a permanent entering makes its controller's triggers trigger an additional time.
        if (event is GameEvent.EntersBattlefield) {
            fun doublerFor(owner: String) = state.objects.values.firstOrNull { p ->
                p.isOnBattlefield() && p.controller == owner && p.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }
                    .any { it is StaticEffect.ExtraEtbTrigger && (it.anyPermanent || event.obj.def.isCreature || "Artifact" in event.obj.def.types) }
            }
            val extra = triggered.filter { (obj, _) -> doublerFor(obj.controller) != null }
            if (extra.isNotEmpty()) {
                val src = doublerFor(extra[0].first.controller)!!
                trace.step("${src.name} makes ${extra.joinToString(" and ") { it.first.name + "'s ability" }} trigger an additional time.", "603.2")
                triggered += extra
            }
        }
        if (triggered.isEmpty()) return
        // 603.3b: APNAP order; the active player's triggers go on the stack first (so they resolve last).
        val order = state.players.map { it.id }
        val ap = state.activePlayer
        val controllers = triggered.map { it.first.controller }.distinct()
        val ordered = if (ap != null) {
            val rotated = order.dropWhile { it != ap } + order.takeWhile { it != ap }
            triggered.sortedBy { rotated.indexOf(it.first.controller) }
        } else {
            if (controllers.size > 1) state.clarifications += Clarification("active player", "Abilities controlled by ${controllers.joinToString(" and ") { if (state.player(it).you) "you" else state.player(it).name }} triggered at the same time; they go on the stack in APNAP order, so whose turn it is decides which resolves first (603.3b). Assuming ${if (state.player(order.first()).you) "you are" else state.player(order.first()).name + " is"} the active player.")
            triggered.sortedBy { order.indexOf(it.first.controller) }
        }
        for ((obj, ability) in ordered) {
            val cause = when (event) {
                is GameEvent.SpellCast -> "${if (state.player(event.item.controller).you) "you" else state.player(event.item.controller).name} casting ${event.item.source.name}"
                is GameEvent.EntersBattlefield -> "${event.obj.name} entering the battlefield"
                is GameEvent.Dies -> "${event.obj.name} dying"
                is GameEvent.LeavesBattlefield -> "${event.obj.name} leaving the battlefield"
                is GameEvent.Attacks -> "${event.obj.name} attacking"
                is GameEvent.BecomesSaddled -> "${event.obj.name} becoming saddled"
                is GameEvent.CountersPut -> "${event.count} ${event.kind} counter${if (event.count == 1) "" else "s"} being put on ${event.obj.name}"
                is GameEvent.PlayerAttacks -> "${state.player(event.playerId).subject.lowercase()} attacking"
                is GameEvent.AttacksAlone -> "${event.obj.name} attacking alone"
                is GameEvent.StepBegins -> "the beginning of ${state.player(event.activePlayer).possessive} ${event.step.replace('_', ' ')}"
                is GameEvent.DamageDealt -> "${event.source.name} dealing ${event.amount} damage to ${state.nameOf(event.target)}"
                is GameEvent.LifeGained -> "${state.player(event.playerId).subject.lowercase()} gaining life"
                is GameEvent.BecomesBlocked -> "${event.obj.name} becoming blocked"
                is GameEvent.BecomesBlockedBy -> "${event.obj.name} becoming blocked by ${event.blocker.name}"
                is GameEvent.Blocks -> "${event.obj.name} blocking"
                is GameEvent.BecomesUnblocked -> "${event.obj.name} attacking and not being blocked"
                is GameEvent.BecomesMonstrous -> "${event.obj.name} becoming monstrous"
                is GameEvent.SpellCopied -> "a copy of ${event.item.source.name} being put on the stack"
                is GameEvent.BecomesTarget -> "${event.obj.name} becoming the target of a spell or ability"
                is GameEvent.BecomesTapped -> "${event.obj.name} becoming tapped"
                is GameEvent.Cycled -> "${event.obj.name} being cycled"
                is GameEvent.Drew -> "${state.player(event.playerId).subject.lowercase()} drawing a card"
                is GameEvent.CreaturesDealtCombatDamageToPlayer -> "${state.player(event.playerId).possessive} creatures dealing combat damage to a player"
            }
            val extra = mutableListOf<String>()
            if (event is GameEvent.EntersBattlefield) extra += "603.6a"
            if (event is GameEvent.StepBegins) extra += "603.2b"
            if (event is GameEvent.Dies || event is GameEvent.LeavesBattlefield) extra += "603.10a"
            keywordTriggerRules.entries.firstOrNull { ability.text.startsWith(it.key, true) }?.let { extra += it.value }
            trace.step("${obj.name}'s ability triggers on $cause.", "603.2", *extra.toTypedArray())
            val causedBy = when (event) {
                is GameEvent.SpellCast -> event.item.controller; is GameEvent.Drew -> event.playerId; is GameEvent.LifeGained -> event.playerId
                is GameEvent.PlayerAttacks -> event.playerId; is GameEvent.Attacks -> event.obj.controller; is GameEvent.StepBegins -> event.activePlayer
                is GameEvent.EntersBattlefield -> event.obj.controller; is GameEvent.Dies -> event.obj.controller; is GameEvent.LeavesBattlefield -> event.obj.controller
                is GameEvent.CreaturesDealtCombatDamageToPlayer -> event.playerId; is GameEvent.DamageDealt -> if (ability.trigger == Trigger.ThisIsDealtDamage) event.source.controller else (event.target as? Ref.Player)?.id ?: event.source.controller; else -> null
            }
            val causedAmount = when (event) { is GameEvent.LifeGained -> event.amount; is GameEvent.DamageDealt -> event.amount; else -> null }
            val causedObject = when (event) { is GameEvent.AttacksAlone -> event.obj.id; is GameEvent.Attacks -> event.obj.id; is GameEvent.EntersBattlefield -> event.obj.id; is GameEvent.Dies -> event.obj.id; is GameEvent.BecomesTarget -> event.sourceId; is GameEvent.SpellCast -> event.item.id; is GameEvent.BecomesBlockedBy -> event.blocker.id; else -> null }
            putTriggerOnStack(obj, ability, emptyList(), causedBy, causedAmount, causedObject)
        }
        if (ordered.size > 1) trace.step("Multiple abilities triggered at once; they are put on the stack in APNAP order, each player choosing the order among their own.", "603.3b")
    }

    private fun matches(obj: GameObject, trigger: Trigger, event: GameEvent, fromGraveyard: Boolean = false): Boolean {
        // A trigger that functions from a graveyard (603.6e, Bloodghast) is live there; every other one needs its source on the battlefield.
        fun onBf() = obj.isOnBattlefield() || (fromGraveyard && obj.zone == Zone.GRAVEYARD)
        return when (trigger) {
        is Trigger.SpellCast -> (event as? GameEvent.SpellCast)?.item.let { cast ->
            val item = cast ?: (event as? GameEvent.SpellCopied)?.takeIf { trigger.orCopied }?.item
            item != null && onBf() && when (trigger.who) {
                Who.YOU -> item.controller == obj.controller
                Who.OPPONENT -> item.controller != obj.controller
                else -> true
            } && (trigger.spellFilter == null || filterMatchesSpell(trigger.spellFilter, item, obj.controller))
        }
        // 603.2: it triggers on exactly that spell, so the count as it was cast has to be the Nth.
        is Trigger.NthSpellEachTurn -> event is GameEvent.SpellCast && onBf() && when (trigger.who) {
            Who.YOU -> event.item.controller == obj.controller
            Who.OPPONENT -> event.item.controller != obj.controller
            else -> true
        } && (trigger.spellFilter == null || filterMatchesSpell(trigger.spellFilter, event.item, obj.controller)) &&
            // Esper Sentinel's "first noncreature spell each turn" counts only the spells of that kind, so a
            // creature spell cast earlier in the turn doesn't use the trigger up.
            (if (trigger.spellFilter == null) (state.spellsThisTurn[event.item.controller] ?: 0) else {
                val cast = state.matchingSpellsThisTurn[event.item.controller] ?: emptyList()
                // Spells the question only counted ("their second noncreature spell") have no card to match, so
                // they are taken to be of the kind the asker was counting.
                cast.count { spellMatches(trigger.spellFilter, it) } + maxOf(0, (state.spellsThisTurn[event.item.controller] ?: 0) - cast.size)
            }) == trigger.n
        is Trigger.ThisAndNOthersAttack -> event is GameEvent.PlayerAttacks && onBf() && event.playerId == obj.controller && obj.attacking != null &&
            state.objects.values.count { it.attacking != null && it.controller == obj.controller && it !== obj } >= trigger.others
        is Trigger.AttackWithNOrMore -> event is GameEvent.PlayerAttacks && onBf() && event.playerId == obj.controller &&
            state.objects.values.count { it.attacking != null && it.controller == event.playerId && (trigger.filter == null || state.matches(trigger.filter, it, obj.controller, obj)) } >= trigger.n
        is Trigger.SpellCastMvEqualsCounters -> event is GameEvent.SpellCast && onBf() &&
            event.item.source !== obj && event.item.source.def.manaValue.toInt() == (obj.counters[trigger.counter] ?: 0)
        Trigger.ThisEnters -> event is GameEvent.EntersBattlefield && event.obj === obj
        Trigger.ThisDies -> event is GameEvent.Dies && event.obj === obj
        Trigger.ThisLeavesBattlefield -> (event is GameEvent.LeavesBattlefield || event is GameEvent.Dies) && (event as? GameEvent.LeavesBattlefield)?.obj === obj || (event as? GameEvent.Dies)?.obj === obj
        Trigger.ThisAttacks -> event is GameEvent.Attacks && event.obj === obj
        Trigger.ThisAttacksSaddled -> event is GameEvent.Attacks && event.obj === obj && obj.saddled
        is Trigger.CountersPutOnThis -> event is GameEvent.CountersPut && event.obj === obj && onBf() &&
            event.kind.equals(trigger.kind, true) && event.count >= trigger.atLeast
        is Trigger.ThisBecomesSaddled -> event is GameEvent.BecomesSaddled && event.obj === obj && (!trigger.firstEachTurn || obj.saddledThisTurn == 1)
        Trigger.ThisCast -> event is GameEvent.SpellCast && event.item.source === obj
        is Trigger.BeginningOfStep -> event is GameEvent.StepBegins && event.step == trigger.step && onBf() && when (trigger.whose) {
            Who.YOU -> event.activePlayer == obj.controller; Who.OPPONENT -> event.activePlayer != obj.controller; else -> true }
        is Trigger.ThisDealsDamage -> event is GameEvent.DamageDealt && event.source === obj && (!trigger.combatOnly || event.combat) &&
            (trigger.toPlayer == null || trigger.toPlayer == (event.target is Ref.Player))
        is Trigger.PermanentEnters -> event is GameEvent.EntersBattlefield && onBf() && !(trigger.other && event.obj === obj) && state.matches(trigger.filter, event.obj, obj.controller)
        is Trigger.PermanentDies -> event is GameEvent.Dies && (onBf() || event.obj === obj || obj.id in leavingTogether) && !(trigger.other && event.obj === obj) && matchesLki(trigger.filter, event.obj, obj.controller, obj)
        Trigger.YouAttack -> event is GameEvent.PlayerAttacks && event.playerId == obj.controller && onBf()
        Trigger.CreatureAttacksAlone -> event is GameEvent.AttacksAlone && event.obj.controller == obj.controller && onBf()
        Trigger.YouGainLife -> event is GameEvent.LifeGained && event.playerId == obj.controller && onBf()
        Trigger.YouDraw -> event is GameEvent.Drew && event.playerId == obj.controller && onBf()
        is Trigger.CardsToYourGraveyard -> event is GameEvent.Dies && onBf() && event.obj.owner == obj.controller && matchesLki(trigger.filter, event.obj, obj.controller)
        is Trigger.PlayerDraws -> event is GameEvent.Drew && onBf() && when (trigger.who) { Who.YOU -> event.playerId == obj.controller; Who.OPPONENT -> event.playerId != obj.controller; else -> true }
        is Trigger.YouDrawNth -> event is GameEvent.Drew && event.playerId == obj.controller && onBf() && state.player(event.playerId).drew == trigger.n
        Trigger.ThisIsDealtDamage -> event is GameEvent.DamageDealt && (event.target as? Ref.Obj)?.id == obj.id
        Trigger.ThisBecomesBlocked -> event is GameEvent.BecomesBlocked && event.obj === obj
        Trigger.ThisBecomesBlockedByCreature -> event is GameEvent.BecomesBlockedBy && event.obj === obj
        Trigger.ThisBlocks -> event is GameEvent.Blocks && event.obj === obj
        Trigger.ThisAttacksUnblocked -> event is GameEvent.BecomesUnblocked && event.obj === obj
        Trigger.ThisBecomesMonstrous -> event is GameEvent.BecomesMonstrous && event.obj === obj
        is Trigger.ThisBecomesTarget -> event is GameEvent.BecomesTarget && event.obj === obj &&
            (!trigger.opponentsOnly || event.by != obj.controller) &&
            // "for the first time each turn": only the first one counts, and the count is kept per permanent.
            (!trigger.firstEachTurn || (state.targetedThisTurn[obj.id] ?: 0) <= 1)
        Trigger.ThisBecomesTapped -> event is GameEvent.BecomesTapped && event.obj === obj
        Trigger.ThisCycled -> event is GameEvent.Cycled && event.obj === obj
        is Trigger.PermanentAttacks -> event is GameEvent.Attacks && onBf() && state.matches(trigger.filter, event.obj, obj.controller, obj)
        is Trigger.PermanentDealsCombatDamageToPlayer -> event is GameEvent.DamageDealt && event.combat && event.target is Ref.Player && onBf() && state.matches(trigger.filter, event.source, obj.controller, obj)
        is Trigger.PermanentDealsCombatDamage -> event is GameEvent.DamageDealt && event.combat && onBf() && state.matches(trigger.filter, event.source, obj.controller, obj)
        Trigger.YourCreaturesDealCombatDamageToPlayer -> event is GameEvent.CreaturesDealtCombatDamageToPlayer && event.playerId == obj.controller && onBf()
        is Trigger.Unknown -> false
        }
    }

    /** Whether a triggered ability works while its card sits in a graveyard (603.6e): the ones that talk about returning it from there. */
    private fun functionsFromGraveyard(ability: TriggeredAbility): Boolean =
        ability.text.contains("from your graveyard", true) || ability.text.contains("from their graveyard", true) ||
            hasReturnSelf(ability.effect)

    private fun hasReturnSelf(e: Effect): Boolean = when (e) {
        is Effect.ReturnSelfFromGraveyard -> true
        is Effect.May -> hasReturnSelf(e.effect); is Effect.Seq -> e.effects.any { hasReturnSelf(it) }
        is Effect.IfYouDo -> hasReturnSelf(e.choice) || hasReturnSelf(e.then); is Effect.IfCondition -> hasReturnSelf(e.then); is Effect.Modal -> e.modes.any { hasReturnSelf(it) }
        else -> false
    }

    /** Yixlid Jailer: the permanent taking abilities away from cards in graveyards, if one is out. */
    private fun graveyardAbilitiesGone(): GameObject? = state.objects.values.firstOrNull { o ->
        o.isOnBattlefield() && o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.GraveyardCardsLoseAbilities }
    }

    /** Filter match for something that just left the battlefield (last known information, 603.10a). */
    private fun matchesLki(f: ObjFilter, o: GameObject, controller: String, source: GameObject? = null): Boolean {
        val z = o.zone; o.zone = Zone.BATTLEFIELD
        try { return state.matches(f, o, controller, source) } finally { o.zone = z }
    }

    /** Undying (702.92a) and persist (702.79a): a creature that died comes back with a counter, once. */
    private fun undyingOrPersist(obj: GameObject, undying: Boolean, persist: Boolean) {
        if (!undying && !persist) return
        if (obj.zone != Zone.GRAVEYARD) return
        val kw = if (undying) "undying" else "persist"
        val kind = if (undying) "+1/+1" else "-1/-1"
        val had = obj.counters[kind] ?: 0
        if (had > 0) {
            trace.step("${obj.name} has $kw, but it had ${had} $kind counter${if (had == 1) "" else "s"} on it when it died, so the ability does nothing.", if (undying) "702.93a" else "702.79a")
            return
        }
        val def = obj.def
        val back = state.add(GameObject(freshObjectId(def.name), def, Zone.BATTLEFIELD, obj.owner, obj.owner))
        back.timestamp = state.tick(); back.summoningSick = def.isCreature
        obj.successor = back.id
        applyEntersReplacements(back)
        val n = countersPlaced(back, 1, kind)
        if (n > 0) back.counters[kind] = (back.counters[kind] ?: 0) + n
        trace.step("${obj.name} had $kw, so it returns to the battlefield under its owner's control with ${if (n == 0) "no" else "a"} $kind counter on it. It comes back as a new object with no memory of the old one.", if (undying) "702.93a" else "702.79a", "400.7")
        state.outcomes += "${def.name} comes back with ${if (n == 0) "no" else "a"} $kind counter ($kw); it's now ${back.power}/${back.toughness}."
        onEvent(GameEvent.EntersBattlefield(back))
        stateBasedActions()
    }

    /** The evoke trigger (702.74a): "When this permanent enters, if its evoke cost was paid, its controller sacrifices it." It's an enters-the-battlefield trigger like any other, so Torpor Orb stops it. */
    private fun onEvokeEntered(obj: GameObject) {
        val ability = TriggeredAbility(Trigger.ThisEnters, Effect.SacrificeSource, "When this permanent enters, if its evoke cost was paid, its controller sacrifices it.")
        val hush = state.objects.values.filter { it.isOnBattlefield() }.any { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.NoEtbTriggers } }
        if (hush) { trace.step("${obj.name}'s evoke sacrifice trigger is an enters-the-battlefield trigger too, so it doesn't trigger either: ${obj.name} stays on the battlefield.", "702.74a", "603.2"); state.outcomes += "${obj.name} stays on the battlefield (its evoke trigger never triggered)."; return }
        trace.step("${obj.name}'s evoke ability triggers: its evoke cost was paid, so its controller will sacrifice it. This goes on the stack above the other enters-the-battlefield triggers of ${obj.name} only if it triggered later; abilities that triggered at the same time are put on the stack in the order their controller chooses.", "702.74a", "603.3b")
        putTriggerOnStack(obj, ability, emptyList())
    }

    private fun putTriggerOnStack(obj: GameObject, ability: TriggeredAbility, targets: List<Ref>, causedBy: String? = null, causedAmount: Int? = null, causedObject: String? = null): StackItem? {
        triggerHold?.let { hold ->
            hold += { putTriggerOnStack(obj, ability, targets, causedBy, causedAmount, causedObject) }
            return null
        }
        val needed = ability.effect.targets()
        var targets = targets
        if (targets.isEmpty() && needed.isNotEmpty() && ability.trigger == Trigger.ThisEnters && obj.etbTargets != null) { targets = obj.etbTargets!!; obj.etbTargets = null; trace.step("${obj.name}'s trigger targets ${targets.joinToString(" and ") { state.nameOf(it) }}, as named when it was cast.", "603.3d") }
        if (needed.size == 1 && targets.isEmpty()) {
            // A trigger nobody named a target for: the only legal target, or for "target player"/"target opponent" the one opponent.
            val spec = needed[0]
            // "Whenever … deals combat damage to a player, … deals 2 damage to any target": the player it just hit is the natural target.
            if (causedBy != null && causedBy != obj.controller && Kind.PLAYER in spec.filter.kinds && isHarmful(ability.effect) && targetingProblem(obj, obj.controller, Ref.Player(causedBy)) == null) {
                state.assumptions += "${obj.name}'s triggered ability targets ${state.nameOf(Ref.Player(causedBy))} (\"${spec.raw}\"; assuming the player it just hit)."
                return StackItem(state.newStackId(), StackKind.TRIGGERED, obj.controller, obj, ability.effect, listOf(Ref.Player(causedBy)), zonesOf(listOf(Ref.Player(causedBy))), ability.text, causedBy = causedBy, causedAmount = causedAmount, causedObject = causedObject).also { state.stack += it; state.player(obj.controller).let { p -> trace.step("${p.subject} ${p.v("puts", "put")} ${obj.name}'s triggered ability on the stack targeting ${state.nameOf(Ref.Player(causedBy))}.", "603.3", "603.3d") } }
            }
            val inferred = inferTarget("${obj.name}'s triggered ability", spec, obj.controller, harmful = isHarmful(ability.effect), source = obj)
                ?: if ((spec.filter.kinds == setOf(Kind.PLAYER) || Regex("""(?i)\bplayer\b""").containsMatchIn(spec.raw)) && state.opponentsOf(obj.controller).size == 1) {
                    state.clarifications.removeAll { it.about == "${obj.name}'s triggered ability's target" }
                    val opp = state.opponentsOf(obj.controller).single()
                    state.assumptions += "${obj.name}'s triggered ability targets ${state.nameOf(Ref.Player(opp.id))} (\"${spec.raw}\"; assuming its controller chose an opponent rather than themselves)."
                    listOf(Ref.Player(opp.id))
                } else null
            if (inferred != null && inferred.isEmpty() && targets.isEmpty()) {
                trace.step("${obj.name}'s triggered ability has no legal target, so it's removed from the stack and does nothing.", "603.3d"); state.outcomes += "${obj.name}'s triggered ability has no legal target and is removed from the stack."; return null
            }
            // Several players could be the target and nothing says which: the part of the ability that needs one is
            // skipped, and saying so is the difference between an incomplete answer and a wrong one. With one
            // opponent it is assumed above; with two it is a real choice.
            if (inferred == null && targets.isEmpty() && state.clarifications.none { it.about == "${obj.name}'s triggered ability's target" }) {
                state.clarifications += Clarification("${obj.name}'s triggered ability's target",
                    "${obj.name}'s triggered ability needs a target (${spec.raw}) and the situation doesn't say which${if (state.players.size > 2) " (${state.opponentsOf(obj.controller).joinToString(" or ") { if (it.you) "you" else it.name }})" else ""}; what the ability does to that target is left out. Say who it targets for a complete answer.")
                trace.step("${obj.name}'s triggered ability needs a target (${spec.raw}) that the situation doesn't name, so what it does to that target isn't shown.", "603.3d", "601.2c")
            }
            if (inferred != null) targets = inferred
        }
        if (needed.isEmpty() && targets.isEmpty() && targetsAPlayer(ability.effect)) {
            // "Exile target player's graveyard": the player is carried by a Who, so there is no TargetSpec to infer from.
            val opps = state.opponentsOf(obj.controller)
            if (opps.size == 1) {
                targets = listOf(Ref.Player(opps.single().id))
                val word = if (Regex("""(?i)\btarget opponent\b""").containsMatchIn(ability.text)) "target opponent" else "target player"
                state.assumptions += "${obj.name}'s triggered ability targets ${state.nameOf(targets[0])} (\"$word\"; assuming its controller chose an opponent rather than themselves)."
            }
        }
        if (needed.size > targets.size) {
            if (state.clarifications.none { it.about == "${obj.name}'s triggered ability's target" }) state.clarifications += Clarification("${obj.name}'s trigger target", "${obj.name}'s triggered ability needs a target (${needed.joinToString("; ") { it.raw }}); which? (603.3d)")
            return null
        }
        val item = StackItem(state.newStackId(), StackKind.TRIGGERED, obj.controller, obj, ability.effect, targets, zonesOf(targets), ability.text, causedBy = causedBy, causedAmount = causedAmount, causedObject = causedObject)
        state.stack += item
        state.player(obj.controller).let { p -> trace.step("${p.subject} ${p.v("puts", "put")} ${obj.name}'s triggered ability on the stack${if (targets.isNotEmpty()) ", choosing its target${if (targets.size == 1) "" else "s"} now: ${targets.joinToString(" and ") { state.nameOf(it) }}" else ""}${if (state.stack.size > 1) ", above ${state.stack[state.stack.size - 2].describe}" else ""}.", "603.3", "603.3a", *(if (targets.isNotEmpty()) arrayOf("603.3d") else emptyArray())) }
        if (ability.effect.hasUnparsed()) state.unsupported += Unsupported(obj.name, "Part of the triggered ability is not modeled: " + unparsedText(ability.effect))
        return item
    }

    // ---- effects ---------------------------------------------------------------------------

    /** Where each creature's "has N damage marked" outcome line sits, so a second hit updates it instead of adding another. */
    private val damageOutcome = mutableMapOf<String, Int>()
    /** Attack taxes add up across attackers; the running total replaces its own outcome line rather than repeating. */
    private val attackTaxTotal = mutableMapOf<String, Pair<Int, Int>>()   // "player|source" -> (attackers, total mana)

    private fun noteAttackTax(playerId: String, srcName: String, cost: String, attackerName: String) {
        val per = Regex("""\{(\d+)\}""").find(cost)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val p = state.player(playerId)
        val key = "$playerId|$srcName"
        val (count, _) = attackTaxTotal[key]?.let { (c, _) -> c to 0 } ?: (0 to 0)
        val n = count + 1
        val line = if (n == 1) "${p.subject} ${p.v("pays", "pay")} $cost for $srcName ($attackerName attacks)."
                   else "${p.subject} ${p.v("pays", "pay")} {${per * n}} for $srcName in all — $cost for each of $n attackers."
        val slot = attackTaxTotal[key]?.second
        if (slot != null && slot < state.outcomes.size) { state.outcomes[slot] = line; attackTaxTotal[key] = n to slot }
        else { attackTaxTotal[key] = n to state.outcomes.size; state.outcomes += line }
    }

    private fun applyEffect(effect: Effect, item: StackItem) {
        val you = state.player(item.controller)
        when (effect) {
            is Effect.Seq -> effect.effects.forEach { applyEffect(it, item) }
            is Effect.CantCastThisTurn -> {
                val who = when (effect.who) {
                    Who.EACH_PLAYER -> state.players.map { it.id }
                    Who.TARGET_PLAYER, Who.THAT_PLAYER -> listOfNotNull((item.targets.firstOrNull() as? Ref.Player)?.id)
                    else -> state.opponentsOf(item.controller).map { it.id }
                }
                state.cantCastThisTurn += who
                for (id in who) {
                    val p = state.player(id)
                    trace.step("${p.subject} can't cast spells for the rest of this turn.", "601.3", "611.2a")
                    state.outcomes += "${p.subject} can't cast spells this turn."
                }
            }
            is Effect.CoinFlip -> {
                val flipper = you
                val win = effect.onWin?.let { describe(it, item) }
                val lose = effect.onLose?.let { describe(it, item) }
                trace.step("${flipper.subject} ${flipper.v("flips", "flip")} a coin and ${flipper.v("calls", "call")} it" +
                    (win?.let { "; if ${flipper.subject.lowercase()} ${flipper.v("wins", "win")} the flip, $it" } ?: "") +
                    (lose?.let { "; if ${flipper.subject.lowercase()} ${flipper.v("loses", "lose")} the flip, $it" } ?: "") + ".", "705.1", "705.2")
                // Said which way it went, that branch is the answer.
                state.coinFlips.removeFirstOrNull()?.let { said ->
                    val won = said == "win"
                    trace.step("The situation says ${flipper.subject.lowercase()} ${if (won) flipper.v("won", "win") else flipper.v("lost", "lose")} the flip.", "705.2")
                    val branch = if (won) effect.onWin else effect.onLose
                    if (branch != null) applyEffect(branch, item) else trace.step("Nothing happens on that result.", "705.2")
                    return
                }
                // The flip is random, so neither branch is the answer: both are given, and the situation can say
                // which it was ("I lose the flip") to get one of them applied.
                state.clarifications += Clarification("the coin flip",
                    "${item.describe} flips a coin: " + listOfNotNull(win?.let { "win → $it" }, lose?.let { "lose → $it" }).joinToString("; ") +
                    ". Say which way it went for a single answer (705.2).")
                state.outcomes += "Coin flip: " + listOfNotNull(win?.let { "if ${flipper.subject.lowercase()} ${flipper.v("wins", "win")}, $it" }, lose?.let { "if ${flipper.subject.lowercase()} ${flipper.v("loses", "lose")}, $it" }).joinToString("; ") + "."
            }
            is Effect.May -> {
                val chooser = (if (effect.who == Who.YOU) you else resolveWho(effect.who, item)) ?: you
                val what = describe(effect.effect, item).let { d ->
                    if (chooser.you) d.replace("their library", "your library").replace("their hand", "your hand").replace("their graveyard", "your graveyard")
                    else d.replace("your library", "their library").replace("your hand", "their hand").replace("your graveyard", "their graveyard")
                }
                trace.step("${chooser.subject} may choose to $what.", "608.2d")
                state.assumptions += "${chooser.subject} ${chooser.v("chooses", "choose")} to $what (${item.describe} says \"${if (effect.who == Who.YOU) "you may" else "may"}\")."
                applyEffect(effect.effect, item)
            }
            is Effect.UnlessPays -> {
                val payer = resolveWho(effect.payer, item)
                trace.step("${payer?.subject ?: "The named player"} may pay ${effect.cost}. If ${if (payer?.you == true) "you do" else "they do"}, nothing more happens; if not: ${describe(effect.effect, item)}.", "608.2g", "117.3d")
                if (payer != null && state.willPay.remove(payer.id)) {
                    trace.step("${payer.subject} ${payer.v("pays", "pay")} ${effect.cost}, so ${item.describe} does nothing more.", "608.2g")
                    state.outcomes += "${payer.subject} ${payer.v("pays", "pay")} ${effect.cost}; ${item.describe} has no further effect."
                } else {
                    if (payer != null && payer.id in state.wontPay) trace.step("${payer.subject} ${payer.v("declines", "decline")} to pay ${effect.cost}.", "608.2g")
                    else state.assumptions += "${payer?.subject ?: "The player"} ${payer?.v("does", "do") ?: "does"} not pay ${effect.cost} for ${item.describe}."
                    applyEffect(effect.effect, item)
                }
            }
            is Effect.Draw -> {
                val players = resolvePlayers(effect.who, item)
                if (players.isEmpty()) { state.unsupported += Unsupported(item.describe, "Couldn't work out who draws."); return }
                val n = if (effect.x) (item.x ?: 0) else effect.count
                if (effect.x) trace.step("X is ${item.x ?: 0}, so ${describe(effect, item).replace("X", (item.x ?: 0).toString())}.", "107.3a")
                for (who in players) drawCards(who, n)
            }
            is Effect.Damage -> {
                val sacAmount = if (effect.sacrificedPower) {
                    val s = state.lastSacrificed
                    if (s == null) { state.clarifications += Clarification("${item.source.name}'s sacrifice", "${item.source.name} deals damage equal to the sacrificed creature's power, but no creature was sacrificed for it; what was sacrificed? (assuming 0)"); 0 }
                    else { val pw = s.lkiPower ?: s.def.power ?: 0; trace.step("The sacrificed creature was ${s.name}; its last known power was $pw, so ${item.source.name} deals $pw damage.", "608.2h"); pw }
                } else null
                // Spell mastery: two or more instants and sorceries in the caster's graveyard (or the situation said so) raise the damage.
                val mastery = effect.masteryAmount?.takeIf { _ ->
                    val gy = state.objects.values.count { it.zone == Zone.GRAVEYARD && it.owner == item.controller && it.def.isInstantOrSorcery }
                    val on = item.choice == "spellmastery" || gy >= 2
                    if (on) trace.step("Spell mastery: ${if (item.choice == "spellmastery") "the situation says its condition is met" else "$gy instant and sorcery cards are in ${state.player(item.controller).possessive} graveyard"}, so ${item.source.name} deals ${effect.masteryAmount} damage instead of ${effect.amount}.", "608.2c")
                    else trace.step("Spell mastery isn't on: only $gy instant or sorcery card${if (gy == 1) "" else "s"} ${if (gy == 1) "is" else "are"} in ${state.player(item.controller).possessive} graveyard (the situation didn't say otherwise), so ${item.source.name} deals its usual ${effect.amount}.", "608.2c")
                    on
                }
                forEachLegalTarget(item, effect.target) { applyDamage(item.source.name, it, sacAmount ?: if (effect.x) (item.x ?: 0) else if (item.kicked && effect.kickedAmount != null) effect.kickedAmount else mastery ?: effect.amount) }
            }
            is Effect.PumpSelfCount -> {
                val o = item.source
                val x = when (val c = effect.count) {
                    is CountExpr.Permanents -> state.objects.values.count { state.matches(c.filter, it, item.controller, o) }
                    is CountExpr.CardTypesInGraveyards -> state.cardTypesInGraveyards().size
                    is CountExpr.YourLifeTotal -> state.player(item.controller).life
                    is CountExpr.Unknown -> null
                }
                if (x == null) { state.unsupported += Unsupported(item.describe, "Couldn't count what the bonus is for each of."); return }
                trace.step("X is $x, counted as the ability resolves.", "608.2h")
                if (!o.isOnBattlefield()) trace.step("${o.name} isn't on the battlefield, so nothing gets the bonus.", "611.2c")
                else {
                    o.pumps += (effect.power * x) to (effect.toughness * x)
                    trace.step("${o.name} gets ${signed(effect.power * x)}/${signed(effect.toughness * x)} until end of turn; it's now ${state.describePt(o)}.", "611.2a")
                    state.outcomes += "${o.name} is ${o.power}/${o.toughness} until end of turn."
                }
            }
            is Effect.BecomeMonarch -> {
                val who = resolvePlayers(effect.who, item).firstOrNull() ?: state.player(item.controller)
                val old = state.monarch
                if (old == who.id) trace.step("${who.subject} ${who.v("is", "are")} already the monarch, so nothing changes.", "725.3")
                else {
                    state.monarch = who.id
                    trace.step("${who.subject} ${who.v("becomes", "become")} the monarch" +
                        (if (old != null) ", and ${state.player(old).subject.lowercase()} ${state.player(old).v("stops", "stop")} being it — only one player is the monarch at a time" else "") +
                        ". The monarch draws a card at the beginning of their end step, and a creature dealing combat damage to the monarch takes the crown for its controller.", "725.1", "725.2", "725.3")
                    state.outcomes += "${who.subject} ${who.v("is", "are")} the monarch."
                }
            }
            is Effect.TapAttached -> {
                val t = state.objects.values.firstOrNull { it.id == item.source.attachedTo }
                if (t == null) trace.step("${item.source.name} isn't attached to anything, so there is nothing to tap.", "608.2b")
                else if (t.tapped == true) trace.step("${t.name} is already tapped.", "701.21a")
                else { tap(t); trace.step("${t.name} becomes tapped.", "701.26a"); state.outcomes += "${t.name} is tapped." }
            }
            is Effect.AnimateSelf -> {
                val o = item.source
                if (!o.isOnBattlefield()) trace.step("${o.name} isn't on the battlefield, so there is nothing to animate.", "608.2b")
                else {
                    val stillLand = effect.stillALand || "Land" in o.def.types
                    o.animatedAs = Animation(effect.power, effect.toughness, effect.subtypes, effect.colors, effect.allCreatureTypes, stillLand, o.name)
                    if (effect.keywords.isNotEmpty()) o.tempKeywords += effect.keywords
                    val what = (if (effect.allCreatureTypes) "creature with every creature type" else (effect.subtypes.joinToString(" ").ifEmpty { "" } + " creature").trim()) +
                        (if (effect.keywords.isEmpty()) "" else " with " + effect.keywords.joinToString(" and "))
                    trace.step("${o.name} becomes a ${effect.power}/${effect.toughness} $what until end of turn" +
                        (if (stillLand) ", and it is still a land — animating it doesn't remove its land types or its mana ability" else "") +
                        ". It has been on the battlefield, so summoning sickness only matters if it came under ${state.player(o.controller).possessive} control this turn.", "613.1d", "613.1f", "302.6")
                    state.outcomes += "${o.name} is a ${effect.power}/${effect.toughness} creature until end of turn."
                }
            }
            is Effect.SaddleSelf -> {
                val o = item.source
                if (!o.isOnBattlefield()) trace.step("${o.name} isn't on the battlefield, so it can't be saddled.", "608.2b")
                else {
                    o.saddled = true; o.saddledThisTurn += 1
                    trace.step("${o.name} becomes saddled until end of turn. Saddling doesn't tap it and doesn't make it attack; it only turns on what its own text does while saddled.", "702.166a")
                    state.outcomes += "${o.name} is saddled until end of turn."
                    onEvent(GameEvent.BecomesSaddled(o))
                }
            }
            is Effect.ReturnSelfFromGraveyard -> {
                val o = item.source; val p = state.player(item.controller)
                if (o.zone != Zone.GRAVEYARD) trace.step("${o.name} is no longer in ${p.possessive} graveyard, so there is nothing to return.", "400.7", "608.2b")
                else {
                    move(o, Zone.BATTLEFIELD, "${o.name} returns from ${p.possessive} graveyard to the battlefield${if (effect.tapped) " tapped" else ""}. It is a new object with no memory of its previous existence.", "400.7", "603.6e")
                    o.timestamp = state.tick(); o.summoningSick = o.def.isCreature; if (effect.tapped) o.tapped = true
                    onEvent(GameEvent.EntersBattlefield(o))
                }
            }
            is Effect.DamageThatMuch -> {
                val n = item.causedAmount ?: run { state.unsupported += Unsupported(item.describe, "\"That much\" refers to an amount the engine didn't record."); return }
                trace.step("\"That much\" is $n \u2014 the damage the trigger was about.", "608.2h")
                forEachLegalTarget(item, effect.target) { applyDamage(item.source.name, it, n) }
            }
            is Effect.Proliferate -> proliferate(item.controller)
            is Effect.ForAllTargeted -> forEachLegalTarget(item, effect.target) { ref ->
                val pid = (ref as? Ref.Player)?.id ?: return@forEachLegalTarget
                val affected = state.objects.values.filter { it.controller == pid && state.matches(effect.filter, it, pid) }
                item.lastCount = affected.size
                if (affected.isEmpty()) trace.step("${state.nameOf(ref)} ${if (state.player(pid).you) "control" else "controls"} no ${effect.filter.raw}, so nothing happens.")
                if (affected.size > 1 && effect.action in setOf("destroy", "exile", "bounce")) leavingTogether = affected.map { it.id }.toSet()
                try { for (o in affected) when (effect.action) {
                    "destroy" -> destroy(o, "${o.name} is destroyed.", "701.8a")
                    "exile" -> move(o, Zone.EXILE, "${o.name} is exiled.", "701.13a")
                    "bounce" -> move(o, Zone.HAND, "${o.name} is returned to its owner's hand.", "400.7")
                    "tuck" -> move(o, Zone.LIBRARY, "${o.name} is put on the bottom of its owner's library. It isn't destroyed, so indestructible doesn't help, and it isn't a death, so \"when this dies\" abilities don't trigger.", "400.7")
                    "tap" -> { o.tapped = true; trace.step("${o.name} becomes tapped.", "701.26a"); state.outcomes += "${o.name} is tapped." }
                    else -> state.unsupported += Unsupported(item.describe, "Unknown action ${effect.action}")
                } } finally { leavingTogether = emptySet() }
            }
            is Effect.PreventCombatToAndBy -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { o -> state.combatDamageMuted += o.id; trace.step("A prevention effect applies to ${o.name} for the rest of the turn: all combat damage that would be dealt to it or by it is prevented. It stays attacking (it was untapped, not removed from combat), so it's still blocked or unblocked as before, but no combat damage happens either way.", "615.1", "506.4"); state.outcomes += "${o.name}'s combat damage this turn (dealt and received) is prevented." } }
            is Effect.WinIfCastBefore -> {
                val you = state.player(item.controller); val times = state.spellsCast[item.source.name] ?: 1
                if (times >= 2) {
                    trace.step("${item.source.name} was cast from ${you.possessive} hand and another spell with that name was cast earlier this game (${times - 1} before), so ${you.subject.lowercase()} ${you.v("wins", "win")} the game.", "104.2b")
                    state.players.filter { it.id != you.id }.forEach { it.lost = true }; state.outcomes += "${you.subject} ${you.v("wins", "win")} the game."
                    state.assumptions += "${item.source.name} was cast from ${you.possessive} hand both times (a copy or a cast from elsewhere wouldn't count)."
                } else {
                    trace.step("This is the first ${item.source.name} ${you.subject.lowercase()} ${you.v("has", "have")} cast this game, so instead of going to the graveyard it's put into ${you.possessive} library seventh from the top, and ${you.subject.lowercase()} ${you.v("gains", "gain")} ${effect.life} life.", "608.2c", "119.3")
                    item.source.zone = Zone.LIBRARY; you.librarySize?.let { you.librarySize = it + 1 }
                    gainLife(you, effect.life)
                    state.outcomes += "${item.source.name} goes into ${you.possessive} library seventh from the top; ${you.subject.lowercase()} ${you.v("gains", "gain")} ${effect.life} life."
                }
            }
            is Effect.CopySpell -> forEachLegalTarget(item, effect.target) { ref ->
                val target = (ref as? Ref.Stack)?.let { state.stackItem(it.id) } ?: (ref as? Ref.Obj)?.let { r -> state.stack.firstOrNull { it.source.id == r.id } }
                if (target == null) { trace.step("${state.nameOf(ref)} is no longer on the stack, so there's nothing to copy.", "707.10"); return@forEachLegalTarget }
                val you = state.player(item.controller)
                val copyObj = state.add(GameObject(freshObjectId(target.source.name + " copy"), target.source.def, Zone.STACK, item.controller, token = true))
                // 707.10c: new targets may be chosen. Assume they stay unless the copy would hit its new controller, who then aims it at the opponent.
                var targets = target.targets
                val named = item.choice?.split('|')?.mapNotNull { c -> state.objects[c]?.let { Ref.Obj(it.id) } ?: state.players.firstOrNull { it.id == c }?.let { Ref.Player(it.id) } } ?: emptyList()
                if (effect.newTargets && named.isNotEmpty() && named.size == targets.size) { targets = named; trace.step("${you.subject} ${you.v("chooses", "choose")} new targets for the copy: ${named.joinToString(" and ") { state.nameOf(it) }}.", "707.10c") }
                else if (effect.newTargets) {
                    val opp = state.opponentsOf(item.controller).singleOrNull()
                    val retargeted = targets.map { t -> if (t is Ref.Player && t.id == item.controller && opp != null) Ref.Player(opp.id) else if (t is Ref.Obj && state.objects[t.id]?.controller == item.controller && isHarmful(target.effect) && opp != null) (state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == opp.id && it.def.isCreature }?.let { Ref.Obj(it.id) } ?: Ref.Player(opp.id)) else t }
                    if (retargeted != targets) { targets = retargeted; state.assumptions += "${you.subject} ${you.v("chooses", "choose")} new targets for the copy of ${target.source.name}: ${targets.joinToString(" and ") { state.nameOf(it) }} (707.10c; the original aimed at ${you.subject.lowercase()})." }
                    else state.assumptions += "${you.subject} ${you.v("keeps", "keep")} the copy's targets as they were (707.10c allows new ones)."
                }
                val copy = StackItem(state.newStackId(), StackKind.SPELL, item.controller, copyObj, target.effect, targets, zonesOf(targets), target.text, target.modes, x = target.x, kicked = target.kicked)
                state.stack += copy
                trace.step("${you.subject} ${you.v("puts", "put")} a copy of ${target.source.name} on the stack, above ${item.describe}. The copy isn't cast (so \"when you cast\" abilities don't trigger and it can't be countered by \"counter target spell\" only while it's a spell on the stack, which it is), and it copies every choice made for the original: modes, targets, X${if (targets != target.targets) ", except the targets changed" else ""}.", "707.10", "707.10c")
                state.outcomes += "A copy of ${target.source.name} is put on the stack${describeTargets(targets)}."
                onEvent(GameEvent.SpellCopied(copy))
            }
            is Effect.StormCopy -> {
                val you = state.player(item.controller)
                val spell = state.stack.firstOrNull { it.source === item.source }
                val prior = state.spellsThisTurn.values.sum() - 1
                when {
                    spell == null -> trace.step("${item.source.name} isn't on the stack any more (it was countered or otherwise left), so the storm trigger copies nothing. The trigger is independent of the spell and still resolves.", "702.40a", "608.2b")
                    prior <= 0 -> {
                        trace.step("Storm counts every spell cast before ${item.source.name} this turn, by any player, whether or not it resolved. None were, so the trigger makes no copies.", "702.40a")
                        state.outcomes += "Storm makes no copies (no spell was cast before ${item.source.name} this turn)."
                    }
                    else -> {
                        val copies = (1..prior).map {
                            val copyObj = state.add(GameObject(freshObjectId(spell.source.name + " copy"), spell.source.def, Zone.STACK, item.controller, token = true))
                            StackItem(state.newStackId(), StackKind.SPELL, item.controller, copyObj, spell.effect, spell.targets, zonesOf(spell.targets), spell.text, spell.modes, x = spell.x, kicked = spell.kicked)
                        }
                        state.stack += copies
                        trace.step("$prior spell${if (prior == 1) " was" else "s were"} cast before ${item.source.name} this turn, so storm puts $prior cop${if (prior == 1) "y" else "ies"} of it on the stack. The copies aren't cast, so they don't trigger \"when you cast\" abilities and they don't add to the storm count themselves; each copies every choice made for the original, and ${you.subject.lowercase()} may choose new targets for them.", "702.40a", "707.10", "707.10c")
                        if (spell.targets.isNotEmpty()) state.assumptions += "The storm copies keep the original's targets (707.10c allows new ones)."
                        state.outcomes += "Storm puts $prior cop${if (prior == 1) "y" else "ies"} of ${spell.source.name} on the stack, above the original."
                        copies.forEach { onEvent(GameEvent.SpellCopied(it)) }
                    }
                }
            }
            is Effect.DamageDivided -> {
                val legal = item.targets.filter { isTargetLegal(item, it) }
                when {
                    item.targets.isEmpty() -> state.unsupported += Unsupported(item.describe, "No targets were given to divide ${effect.amount} damage among (${effect.target.raw}).")
                    legal.isEmpty() -> trace.step("Every target is illegal now, so ${item.describe} is removed from the stack and none of the damage is dealt.", "608.2b")
                    else -> {
                        val n = legal.size
                        val base = effect.amount / n; val extra = effect.amount % n
                        if (n > effect.amount) {
                            state.clarifications += Clarification("${item.describe}'s division", "${effect.amount} damage can't be divided among $n targets: each one has to be assigned at least 1 damage as the spell is cast (601.2d).")
                            trace.step("The division is chosen as ${item.describe} is cast, and each target must be assigned at least 1 damage, so $n targets can't share ${effect.amount} damage.", "601.2d")
                        } else {
                            trace.step("${item.source.name} deals ${effect.amount} damage divided among ${legal.joinToString(" and ") { state.nameOf(it) }}. The division was chosen as it was cast, each target assigned at least 1, and it can't be changed now.", "601.2d", "119.4")
                            if (n > 1) state.assumptions += "${item.describe}'s ${effect.amount} damage is split as evenly as it goes among the $n targets named (${legal.joinToString(" and ") { state.nameOf(it) }}); the caster could have divided it any other way as it was cast."
                            legal.forEachIndexed { i, ref -> applyDamage(item.source.name, ref, base + if (i < extra) 1 else 0, item.source) }
                            stateBasedActions()
                        }
                    }
                }
            }
            is Effect.ReflectPrevented -> {
                val sh = state.shields.lastOrNull { it.sourceName == item.describe }
                if (sh == null) trace.step("${item.describe} made no prevention shield, so there is nothing for the damage to be sent back from.")
                else {
                    val r = sh.replacement as Replacement.PreventDamage
                    sh.replacement = r.copy(reflectToCreature = r.reflectToCreature || effect.toCreature, reflectToController = r.reflectToController || effect.toController)
                    trace.step("If damage${if (effect.toCreature) " from a creature source" else if (r.reflectToCreature) " from a noncreature source" else ""} is prevented this way, ${item.source.name} deals that much damage to ${if (effect.toCreature) "that creature" else "the source's controller"}. That damage comes from ${item.source.name}, not from the original source, so it isn't combat damage and the original source's abilities don't apply to it.", "615.7")
                }
            }
            is Effect.Monstrosity -> {
                val o = item.source
                if (!o.isOnBattlefield()) trace.step("${o.name} isn't on the battlefield, so monstrosity does nothing.", "608.2b")
                else if (o.monstrous) {
                    trace.step("${o.name} is already monstrous, so monstrosity ${effect.amount} does nothing at all — no counters, and no \"becomes monstrous\" trigger.", "701.31b")
                    state.outcomes += "${o.name} is already monstrous, so nothing happens."
                } else {
                    trace.step("${o.name} isn't monstrous, so it gets ${effect.amount} +1/+1 counters and becomes monstrous. Being monstrous isn't a counter or an ability; it just stays true while it's on the battlefield.", "701.31a", "701.31b")
                    putCounters(o.id, effect.amount, "+1/+1")
                    o.monstrous = true
                    state.outcomes += "${o.name} is monstrous."
                    onEvent(GameEvent.BecomesMonstrous(o))
                }
            }
            is Effect.ChangeTarget -> {
                val ref = item.targets.getOrNull(0)
                val spell = (ref as? Ref.Stack)?.let { state.stackItem(it.id) } ?: (ref as? Ref.Obj)?.let { r -> state.stack.firstOrNull { it.source.id == r.id } }
                val you = state.player(item.controller)
                when {
                    spell == null -> trace.step("${state.nameOf(ref ?: Ref.Player(item.controller))} isn't on the stack any more, so there is nothing to retarget.", "608.2b")
                    spell.targets.isEmpty() -> trace.step("${spell.source.name} has no targets, so there is nothing to change.", "115.7")
                    effect.singleOnly && spell.targets.size > 1 -> trace.step("${spell.source.name} has ${spell.targets.size} targets, and ${item.describe} can only change the target of a spell with a single target.", "115.7")
                    else -> {
                        // The asker may name the new target after the spell; otherwise the obvious choice is to
                        // turn a spell aimed at this player or their permanent back on the player who cast it.
                        val named = item.targets.drop(1).takeIf { it.isNotEmpty() }
                        val flipped = spell.targets.map { t ->
                            when {
                                t is Ref.Player && t.id == item.controller -> Ref.Player(spell.controller)
                                t is Ref.Obj && state.objects[t.id]?.controller == item.controller ->
                                    state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == spell.controller && it.def.isCreature }?.let { Ref.Obj(it.id) } ?: Ref.Player(spell.controller)
                                else -> t
                            }
                        }
                        val newTargets = named ?: flipped
                        val illegal = newTargets.filter { !isTargetLegal(spell, it) }
                        when {
                            newTargets == spell.targets -> {
                                state.clarifications += Clarification("${item.describe}'s new target", "${item.describe} changes ${spell.source.name}'s target, but the situation doesn't say what to; it was aimed at ${spell.targets.joinToString(" and ") { state.nameOf(it) }}. Name the new target for a complete answer.")
                                trace.step("${item.describe} may change ${spell.source.name}'s target, but nothing says what to and there is no obvious swap, so its target is left as it was.", "115.7")
                            }
                            illegal.isNotEmpty() -> trace.step("${illegal.joinToString(" and ") { state.nameOf(it) }} can't be chosen as ${spell.source.name}'s new target, and a target can only be changed to a legal one, so it stays as it was.", "115.7b")
                            else -> {
                                spell.targets = newTargets
                                if (named == null) state.assumptions += "${item.describe} turns ${spell.source.name} back on ${state.player(spell.controller).name} (${newTargets.joinToString(" and ") { state.nameOf(it) }}); any other legal target could have been chosen."
                                trace.step("${you.subject} ${you.v("changes", "change")} ${spell.source.name}'s target to ${newTargets.joinToString(" and ") { state.nameOf(it) }}. The spell isn't recast and nothing about it changes but the target.", "115.7", "115.7b")
                                state.outcomes += "${spell.source.name} now targets ${newTargets.joinToString(" and ") { state.nameOf(it) }}."
                            }
                        }
                    }
                }
            }
            is Effect.MoveSourceCounters -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { t ->
                val n = item.source.counters[effect.kind] ?: 0
                if (n <= 0) trace.step("${item.source.name} had no ${effect.kind} counters when it left the battlefield, so none are moved.", "608.2h", "121.6")
                else {
                    trace.step("${item.source.name}'s $n ${effect.kind} counter${if (n == 1) "" else "s"} ${if (n == 1) "is" else "are"} put on ${t.name}. Counters aren't moved by any rule of their own; the ability puts that many new ones on.", "702.43b", "121.6")
                    putCounters(t.id, n, effect.kind)
                    item.source.counters.remove(effect.kind)
                }
            } }
            is Effect.Evolve -> {
                val o = item.causedObject?.let { state.objects[it] }
                val src = item.source
                when {
                    !src.isOnBattlefield() -> trace.step("${src.name} isn't on the battlefield any more, so evolve does nothing.", "608.2b")
                    o == null -> trace.step("The creature that caused the trigger isn't there to compare, so evolve does nothing.", "702.100a")
                    (o.power ?: 0) > (src.power ?: 0) || (o.toughness ?: 0) > (src.toughness ?: 0) -> {
                        trace.step("${o.name} is ${o.power}/${o.toughness} and ${src.name} is ${src.power}/${src.toughness}, so ${o.name} is greater in ${if ((o.power ?: 0) > (src.power ?: 0) && (o.toughness ?: 0) > (src.toughness ?: 0)) "both power and toughness" else if ((o.power ?: 0) > (src.power ?: 0)) "power" else "toughness"} and evolve puts a +1/+1 counter on ${src.name}.", "702.100a")
                        putCounters(src.id, 1, "+1/+1")
                    }
                    else -> {
                        trace.step("${o.name} is ${o.power}/${o.toughness} and ${src.name} is ${src.power}/${src.toughness}, so it isn't greater in power or in toughness and evolve does nothing.", "702.100a")
                        state.outcomes += "Evolve doesn't trigger a counter: ${o.name} isn't bigger than ${src.name} in either direction."
                    }
                }
            }
            is Effect.Fight -> fight(item.targets.getOrNull(0)?.let { objOf(it) }, item.targets.getOrNull(1)?.let { objOf(it) })
            is Effect.DealsPowerTo -> {
                val a = item.targets.getOrNull(0)?.let { objOf(it) }; val b = item.targets.getOrNull(1)?.let { objOf(it) }
                if (a == null || b == null || !a.isOnBattlefield() || !b.isOnBattlefield()) trace.step("One of the creatures is no longer on the battlefield, so no damage is dealt.", "608.2b")
                else { val pw = a.power ?: 0; trace.step("${a.name}'s power is $pw, so it deals $pw damage to ${b.name}. This isn't a fight: ${b.name} deals none back.", "701.14a"); applyDamage(a.name, Ref.Obj(b.id), pw, a); stateBasedActions() }
            }
            is Effect.RedirectToSelf -> forEachLegalTarget(item, effect.target) { ref ->
                val target = (ref as? Ref.Stack)?.let { state.stackItem(it.id) } ?: (ref as? Ref.Obj)?.let { r -> state.stack.firstOrNull { it.source.id == r.id } }
                val me = item.source
                if (target == null) { trace.step("${state.nameOf(ref)} is no longer on the stack, so there's no target to change.", "115.7"); return@forEachLegalTarget }
                if (!me.isOnBattlefield()) { trace.step("${me.name} isn't on the battlefield, so nothing can be redirected to it.", "115.7"); return@forEachLegalTarget }
                val specs = target.effect?.targets() ?: emptyList()
                val idx = target.targets.indices.firstOrNull { i -> val spec = specs.getOrNull(i) ?: specs.firstOrNull(); spec != null && filterMatches(spec.filter, Ref.Obj(me.id), target.controller) && targetingProblem(target.source, target.controller, Ref.Obj(me.id)) == null && target.targets[i] != Ref.Obj(me.id) }
                if (idx == null) { trace.step("${me.name} isn't a legal target for ${target.describe}${specs.firstOrNull()?.let { " (\"${it.raw}\")" } ?: ""}, so its target can't be changed to ${me.name}; the ability does nothing.", "115.7", "115.3"); state.outcomes += "${target.describe}'s target isn't changed (${me.name} isn't a legal target for it)."; return@forEachLegalTarget }
                val was = state.nameOf(target.targets[idx])
                target.targets = target.targets.toMutableList().also { it[idx] = Ref.Obj(me.id) }; target.targetZones[me.id]?.let { } 
                trace.step("${target.describe}'s target is changed from $was to ${me.name}: ${me.name} is a legal target for it, so the change is made.", "115.7", "115.3")
                state.outcomes += "${target.describe} now targets ${me.name} instead of $was."
            }
            is Effect.Counter -> forEachLegalTarget(item, effect.target) { ref ->
                val target = (ref as? Ref.Stack)?.let { state.stackItem(it.id) } ?: (ref as? Ref.Obj)?.let { r -> state.stack.firstOrNull { it.source.id == r.id } }
                if (target == null) { trace.step("${state.nameOf(ref)} is no longer on the stack, so it can't be countered.", "701.6a"); return@forEachLegalTarget }
                if (target.kind == StackKind.SPELL && cant(target.source, "be countered")) { trace.step("${target.describe} can't be countered, so ${item.describe} has no effect on it.", "701.6a"); state.outcomes += "${target.describe} isn't countered (it can't be)."; return@forEachLegalTarget }
                state.stack.remove(target); state.lastCountered = target.source
                val instead = effect.insteadZone?.takeIf { target.kind == StackKind.SPELL }
                if (target.kind == StackKind.SPELL) target.source.zone = when (instead) { "exile" -> Zone.EXILE; "hand" -> Zone.HAND; "library" -> Zone.LIBRARY; else -> Zone.GRAVEYARD }
                val where = when (instead) { "exile" -> "the card is exiled instead of going to its owner's graveyard"; "hand" -> "the card goes to its owner's hand instead of their graveyard"; "library" -> "the card goes to its owner's library instead of their graveyard"; else -> "the card goes to its owner's graveyard" }
                trace.step("${target.describe} is countered: it's removed from the stack and none of its effects happen" +
                    (if (target.kind == StackKind.SPELL) "; $where" else "") + ".", "701.6a")
                state.outcomes += "${target.describe} is countered."
            }
            is Effect.Destroy -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { destroy(it, "${it.name} is destroyed and put into its owner's graveyard.", "701.8a", canRegenerate = !effect.noRegen) } }
            is Effect.Bounce -> {
                val bounce: (GameObject) -> Unit = { o -> move(o, Zone.HAND, "${o.name} is returned to its owner's hand. It becomes a new object with no memory of its previous existence.", "400.7") }
                if (effect.target == null) { if (item.source.isOnBattlefield() || item.source.zone == Zone.GRAVEYARD) bounce(item.source) else trace.step("${item.source.name} isn't on the battlefield or in a graveyard, so there's nothing to return.", "400.7") }
                else forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let(bounce) }
            }
            is Effect.LivingWeapon -> {
                val who = state.player(item.controller)
                val def = Generic.token("0/0 black Phyrexian Germ creature token") ?: run { state.unsupported += Unsupported(item.describe, "Couldn't read the Germ token."); return }
                val t = state.add(GameObject(freshObjectId(def.name), def, Zone.BATTLEFIELD, who.id, token = true)); t.timestamp = state.tick(); t.summoningSick = true
                trace.step("${who.subject} ${who.v("creates", "create")} a ${def.name} (0/0); it enters the battlefield under ${who.possessive} control.", "702.92a", "701.7a")
                state.outcomes += "${who.subject} ${who.v("gets", "get")} ${withArticle(def.name)}."
                onEvent(GameEvent.EntersBattlefield(t))
                if (item.source.isOnBattlefield()) {
                    item.source.attachedTo = t.id
                    trace.step("${item.source.name} becomes attached to the ${def.name} — all as part of the same ability resolving, so the 0/0 never faces state-based actions on its own.", "702.92a", "702.6a")
                    state.outcomes += "${item.source.name} is attached to the ${def.name}."
                    trace.step("The ${def.name} is now ${state.describePt(t)}.", "613.1")
                } else trace.step("${item.source.name} has left the battlefield, so it can't be attached to the ${def.name}; the 0/0 dies to state-based actions.", "702.92a", "704.5f")
            }
            is Effect.BounceChosen -> {
                val chooser = state.player(item.controller)
                val legal = state.objects.values.filter { it.isOnBattlefield() && state.matches(effect.filter, it, item.controller, item.source) }
                if (legal.isEmpty()) trace.step("${chooser.subject} ${chooser.v("controls", "control")} no ${effect.what}, so nothing is returned. ${item.source.name}'s ability still resolves.", "608.2b")
                else {
                    // The usual choice is something other than the source itself, and the cheapest such permanent.
                    val pick = legal.filter { it !== item.source }.minByOrNull { it.def.manaValue } ?: legal.first()
                    if (legal.size > 1) state.assumptions += "${chooser.subject} ${chooser.v("returns", "return")} ${pick.name} to ${if (pick.owner == item.controller) "your" else "its owner's"} hand; ${chooser.subject.lowercase()} could return ${legal.filter { it !== pick }.joinToString(" or ") { it.name }} instead."
                    move(pick, Zone.HAND, "${chooser.subject} ${chooser.v("chooses", "choose")} ${pick.name} and ${chooser.v("returns", "return")} it to its owner's hand. It becomes a new object with no memory of its previous existence.", "400.7")
                }
            }
            is Effect.DamagePlayer -> for (p in resolvePlayers(effect.who, item)) applyDamage(item.source.name, Ref.Player(p.id), effect.amount, item.source)
            is Effect.PumpCausing -> {
                val o = item.causedObject?.let { state.objects[it] }
                if (o != null && effect.unlessCausingHas != null && state.hasKeyword(o, effect.unlessCausingHas)) {
                    trace.step("${o.name} has ${effect.unlessCausingHas} too, so ${item.source.name}'s ${effect.unlessCausingHas} does nothing to it.", "702.25a")
                } else if (o == null || !o.isOnBattlefield()) trace.step("The creature that caused the trigger isn't on the battlefield, so nothing gets the bonus.", "611.2c")
                else {
                    if (effect.power != 0 || effect.toughness != 0) { o.pumps += effect.power to effect.toughness; trace.step("${o.name} gets ${signed(effect.power)}/${signed(effect.toughness)} until end of turn; it's now ${o.power}/${o.toughness}.", "611.2a"); state.outcomes += "${o.name} is ${o.power}/${o.toughness} until end of turn." }
                    if (effect.keywords.isNotEmpty()) { o.tempKeywords += effect.keywords; trace.step("${o.name} gains ${effect.keywords.joinToString(" and ")} until end of turn.", "611.2a"); state.outcomes += "${o.name} has ${effect.keywords.joinToString(" and ")} until end of turn." }
                }
            }
            is Effect.LoseLifeThatMuch -> {
                val n = item.causedAmount ?: run { state.unsupported += Unsupported(item.describe, "\"That much\" refers to an amount the engine didn't record."); return }
                resolvePlayers(effect.who, item).forEach { p -> p.life = p.life?.minus(n); trace.step("${p.subject} ${p.v("loses", "lose")} $n life (that much)${p.life?.let { " ($it)" } ?: ""}.", "119.3"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} $n life." }
            }
            is Effect.PumpAllCount -> {
                val x = when (val c = effect.count) { is CountExpr.Permanents -> state.objects.values.count { state.matches(c.filter, it, item.controller) }; is CountExpr.CardTypesInGraveyards -> state.cardTypesInGraveyards().size; is CountExpr.YourLifeTotal -> state.player(item.controller).life; is CountExpr.Unknown -> null }
                if (x == null) { state.unsupported += Unsupported(item.describe, "Couldn't count X."); return }
                trace.step("X is $x (counted as the effect resolves).", "608.2h")
                applyEffect(Effect.PumpAll(effect.filter, x, x, effect.keywords), item)
            }
            is Effect.ShuffleIntoLibrary -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { o -> move(o, Zone.LIBRARY, "${o.name} is shuffled into its owner's library.", "701.24a", "400.7") } }
            is Effect.CreateToken -> {
                val who = resolveWho(effect.who, item) ?: run { state.unsupported += Unsupported(item.describe, "Couldn't work out who creates the token."); return }
                val def = Generic.token(effect.token) ?: run { state.unsupported += Unsupported(item.describe, "Couldn't read the token \"${effect.token}\"."); return }
                if (effect.x) trace.step("X is ${item.x ?: 0}, so ${item.x ?: 0} token${if ((item.x ?: 0) == 1) "" else "s"} ${if ((item.x ?: 0) == 1) "is" else "are"} created.", "107.3a")
                var n = (if (effect.x) (item.x ?: 0) else null) ?: effect.countBy?.let { c -> when (c) { is CountExpr.Permanents -> state.objects.values.count { state.matches(c.filter, it, item.controller) }.also { trace.step("X is $it: the number of ${c.filter.raw}${if (c.filter.raw.endsWith("control", true)) "" else " ${who.subject.lowercase()} ${who.v("controls", "control")}"} as the ability resolves.", "608.2h") }; is CountExpr.CardTypesInGraveyards -> state.cardTypesInGraveyards().size; is CountExpr.YourLifeTotal -> (state.player(item.controller).life ?: 0); is CountExpr.Unknown -> { state.clarifications += Clarification("${item.describe}'s X", "X is \"${c.text}\", which isn't tracked; assuming 0."); 0 } } } ?: effect.count
                state.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.TokenMultiplier }.filter { it.anyPlayer || o.controller == who.id }.map { o to it } }
                    .forEach { (o, m) -> trace.step("${o.name} replaces the token creation: ${n * m.factor} tokens instead of $n.", "614.1a", "614.6"); n *= m.factor }
                repeat(n) {
                    val t = state.add(GameObject(freshObjectId(def.name), def, Zone.BATTLEFIELD, who.id, token = true)); t.timestamp = state.tick(); t.summoningSick = def.isCreature
                    trace.step("${who.subject} ${who.v("creates", "create")} ${withArticle(def.name)}${if (def.isCreature) " (${state.describePt(t)})" else ""}; it enters the battlefield under ${who.possessive} control.", "701.7a", "111.1")
                    state.outcomes += "${who.subject} ${who.v("gets", "get")} ${withArticle(def.name)}."
                    onEvent(GameEvent.EntersBattlefield(t))
                }
            }
            is Effect.CreateTokenCopy -> {
                val who = state.player(item.controller)
                val from = (if (effect.target == null) item.source else (item.targets.firstOrNull() as? Ref.Obj)?.let { state.objects[it.id] })
                if (from == null || !from.isOnBattlefield()) {
                    trace.step("${item.describe} would create a token copy, but ${if (effect.target == null) item.source.name + " isn't on the battlefield any more" else "what it was copying isn't there any more"}, so no token is created.", "707.2", "608.2b")
                } else repeat(effect.count) {
                    // 707.2: the token copies the printed card and other copy effects on it — not counters, damage or pumps.
                    val t = state.add(GameObject(freshObjectId(from.def.name), from.def, Zone.BATTLEFIELD, who.id, token = true)); t.timestamp = state.tick(); t.summoningSick = from.def.isCreature
                    trace.step("${who.subject} ${who.v("creates", "create")} a token that's a copy of ${from.name}${if (from.def.isCreature) " (${state.describePt(t)})" else ""}: it copies the printed card and any other copy effects on it, and nothing else — not counters, damage or anything else on ${from.name}.", "701.7a", "707.2", "111.1")
                    state.outcomes += "${who.subject} ${who.v("gets", "get")} a token copy of ${from.def.name}."
                    onEvent(GameEvent.EntersBattlefield(t))
                }
                effect.except?.let { state.unsupported += Unsupported(item.source.name, "The token copy's exception is not modeled: " + it.replace("~", item.source.name)) }
            }
            is Effect.SacrificeSource -> {
                val o = item.source; val p = state.player(o.controller)
                if (!o.isOnBattlefield()) trace.step("${o.name} is no longer on the battlefield, so there's nothing to sacrifice.", "701.21a")
                else { move(o, Zone.GRAVEYARD, "${p.subject} ${p.v("sacrifices", "sacrifice")} ${o.name}: it goes to its owner's graveyard. Sacrificing isn't destroying, so indestructible and regeneration don't help.", "701.21a"); state.outcomes += "${o.name} is sacrificed." }
            }
            is Effect.ExileGraveyard -> for (p in resolvePlayers(effect.who, item)) {
                val cards = state.objects.values.filter { it.zone == Zone.GRAVEYARD && it.owner == p.id }
                if (cards.isEmpty()) trace.step("${p.possessive.replaceFirstChar { c -> c.uppercase() }} graveyard is empty, so nothing is exiled.", "701.13a")
                else {
                    trace.step("Every card in ${p.possessive} graveyard is exiled at once: ${cards.joinToString(", ") { c -> c.name }}. They all leave the graveyard as a single event, so nothing can be returned from it in response.", "701.13a", "400.7")
                    for (c in cards) c.zone = Zone.EXILE
                    state.outcomes += "${p.possessive.replaceFirstChar { c -> c.uppercase() }} graveyard is exiled (${cards.joinToString(", ") { c -> c.name }})."
                }
            }
            is Effect.DiscardNamed -> {
                val named = item.choice ?: item.source.chosenName
                if (named == null) {
                    state.clarifications += Clarification("${item.source.name}'s name", "${item.source.name} names a ${effect.what} card; which name was chosen?")
                    trace.step("The situation doesn't say which name was chosen for ${item.source.name}, so what is discarded can't be said.", "701.9a")
                } else for (p in resolvePlayers(effect.who, item)) {
                    val hand = state.objects.values.filter { it.zone == Zone.HAND && it.owner == p.id }
                    trace.step("${state.player(item.controller).subject} named $named. ${p.subject} ${p.v("reveals", "reveal")} ${if (p.you) "your" else "their"} hand${if (hand.isEmpty()) "" else ": ${hand.joinToString(", ") { it.name }}"}.", "701.20a")
                    if (hand.isEmpty()) {
                        state.clarifications += Clarification("${p.possessive} hand", "${item.source.name} makes ${if (p.you) "you" else p.name} discard every $named; what is in ${if (p.you) "your" else "their"} hand?")
                        state.outcomes += "${item.source.name}: ${p.subject.lowercase()} ${p.v("discards", "discard")} every copy of $named (how many depends on ${p.possessive} hand)."
                    } else {
                        val hits = hand.filter { it.name.equals(named, true) }
                        if (hits.isEmpty()) { trace.step("No card in ${p.possessive} hand is named $named, so nothing is discarded.", "701.9a"); state.outcomes += "${p.subject} ${p.v("discards", "discard")} nothing (no $named in hand)." }
                        else {
                            trace.step("${hits.size} card${if (hits.size == 1) "" else "s"} named $named ${if (hits.size == 1) "is" else "are"} discarded, all at once; the rest of the hand stays.", "701.9a", "400.7")
                            hits.forEachIndexed { n, h -> moveRaw(h, Zone.GRAVEYARD); state.outcomes += "${h.name}: ${p.possessive} hand → ${p.possessive} graveyard${if (hits.size > 1) " (copy ${n + 1} of ${hits.size})" else ""}." }
                        }
                    }
                }
            }
            is Effect.DiscardChosen -> for (p in resolvePlayers(effect.who, item)) {
                val chooser = state.player(item.controller)
                val hand = state.objects.values.filter { it.zone == Zone.HAND && it.owner == p.id }
                trace.step("${p.subject} ${p.v("reveals", "reveal")} ${if (p.you) "your" else "their"} hand${if (hand.isEmpty()) "" else ": ${hand.joinToString(", ") { it.name }}"}.", "701.20a")
                if (hand.isEmpty()) {
                    state.clarifications += Clarification("${p.possessive} hand", "${item.source.name} has ${if (p.you) "you" else p.name} reveal ${if (p.you) "your" else "their"} hand and discard ${effect.what}; what is in it?")
                    trace.step("The situation doesn't say what is in ${p.possessive} hand, so which card is discarded can't be said.", "701.9a")
                    state.outcomes += "${item.source.name}: ${p.subject.lowercase()} ${p.v("discards", "discard")} ${effect.what} (which one depends on ${p.possessive} hand)."
                } else {
                    val legal = hand.filter { effect.filter == null || state.matches(effect.filter, it, p.id, item.source, anyZone = true) }
                    if (legal.isEmpty()) {
                        trace.step("Nothing in ${p.possessive} hand is ${effect.what}, so ${chooser.subject.lowercase()} ${chooser.v("chooses", "choose")} nothing and no card is discarded.", "701.9a")
                        state.outcomes += "${p.subject} ${p.v("discards", "discard")} nothing (no ${effect.what.removePrefix("a ").removePrefix("an ")} in hand)."
                    } else {
                        val pick = legal.maxByOrNull { it.def.manaValue }!!
                        if (legal.size > 1) state.assumptions += "${chooser.subject} ${chooser.v("takes", "take")} ${pick.name} with ${item.source.name}; ${chooser.subject.lowercase()} could take ${legal.filter { it !== pick }.joinToString(" or ") { it.name }} instead."
                        move(pick, Zone.GRAVEYARD, "${chooser.subject} ${chooser.v("chooses", "choose")} ${pick.name}, and ${p.subject.lowercase()} ${p.v("discards", "discard")} it: it goes from ${p.possessive} hand to ${p.possessive} graveyard.", "701.9a")
                    }
                }
            }
            is Effect.Mill -> for (p in resolvePlayers(effect.who, item)) {
                val lib = p.librarySize
                val n = if (lib != null && lib < effect.count) lib else effect.count
                if (lib != null && lib < effect.count) trace.step("${p.subject} ${p.v("has", "have")} only $lib card${if (lib == 1) "" else "s"} in ${p.possessive} library, so ${p.subject.lowercase()} ${p.v("mills", "mill")} as many as possible: $n. (Milling from a too-small library doesn't make a player lose; only drawing does.)", "701.17b", "704.5b")
                trace.step("${p.subject} ${p.v("mills", "mill")} $n card${if (n == 1) "" else "s"}: the top $n card${if (n == 1) "" else "s"} of ${p.possessive} library ${if (n == 1) "goes" else "go"} into ${p.possessive} graveyard${if (lib != null) "; ${lib - n} left" else ""}.", "701.17a")
                if (lib != null) p.librarySize = lib - n
                state.outcomes += "${p.subject} ${p.v("mills", "mill")} $n card${if (n == 1) "" else "s"}${if (lib != null) " (${lib - n} left in library)" else ""}."
            }
            is Effect.GainLifePerSpellThisTurn -> {
                val p = resolveWho(effect.who, item) ?: state.player(item.controller)
                val n = state.spellsThisTurn[p.id] ?: 0
                trace.step("${p.subject} ${p.v("has", "have")} cast $n spell${if (n == 1) "" else "s"} this turn (counting the one that triggered this), so ${p.subject.lowercase()} ${p.v("gains", "gain")} ${n * effect.per} life.", "608.2h", "119.3")
                gainLife(p, n * effect.per)
            }
            is Effect.WinIfDevotionCoversLibrary -> {
                val you = state.player(item.controller)
                val counted = state.objects.values.filter { it.isOnBattlefield() && it.controller == you.id }.sumOf { o -> (o.def.manaCost ?: "").count { ch -> ch == effect.color } }
                val x = you.devotion[effect.color] ?: counted
                val colour = mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")[effect.color]
                trace.step("X is ${you.possessive} devotion to $colour: $x${if (you.devotion[effect.color] != null) " (as stated)" else " (${effect.color} symbols in the mana costs of permanents ${you.subject.lowercase()} ${you.v("controls", "control")}, ${item.source.name} included)"}. ${you.subject} ${you.v("looks", "look")} at the top $x cards, putting up to one back on top and the rest on the bottom.", "700.5", "701.22a")
                val lib = you.librarySize
                if (lib == null) { state.clarifications += Clarification("${you.possessive} library", "${item.source.name} wins the game if X ($x) is at least the number of cards in ${you.possessive} library; how many cards are there?"); trace.step("Whether $x is at least the number of cards in ${you.possessive} library isn't known, so the win can't be decided.", "608.2h") }
                else if (x >= lib) { trace.step("X ($x) is greater than or equal to the $lib card${if (lib == 1) "" else "s"} in ${you.possessive} library, so ${you.subject.lowercase()} ${you.v("wins", "win")} the game. This happens as the ability resolves, not as a state-based action; an empty library on its own would only matter when drawing.", "104.2b"); state.players.filter { it.id != you.id }.forEach { it.lost = true }; state.outcomes += "${you.subject} ${you.v("wins", "win")} the game (${item.source.name}: X = $x, library = $lib)." }
                else { trace.step("X ($x) is less than the $lib cards in ${you.possessive} library, so nothing more happens.", "608.2h"); state.outcomes += "${item.source.name} doesn't win the game (X = $x, library = $lib)." }
            }
            is Effect.SacrificeThatMany -> {
                val p = resolveWho(effect.who, item); val n = item.causedAmount ?: 0
                if (p == null) trace.step("Nobody to sacrifice: the player this refers to isn't known.")
                else {
                    val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && state.matches(effect.filter, it, p.id) }
                    if (n <= 0) trace.step("The amount is 0, so ${p.subject.lowercase()} ${p.v("sacrifices", "sacrifice")} nothing.", "701.21a")
                    else if (mine.isEmpty()) { trace.step("${p.subject} ${p.v("controls", "control")} no ${effect.filter.raw}, so nothing is sacrificed.", "701.21a"); state.outcomes += "${p.subject} would have to sacrifice $n ${effect.filter.raw}${if (n > 1) "s" else ""} but ${p.v("controls", "control")} none." }
                    else {
                        // Their choice: assume the least valuable go first (tokens, then lowest mana value, lands among them).
                        val picks = mine.sortedWith(compareBy({ if (it.token) 0 else 1 }, { it.def.manaValue }, { it.power ?: 0 })).take(n)
                        trace.step("${p.subject} must sacrifice $n ${effect.filter.raw}${if (n > 1) "s" else ""} of ${p.possessive} choice${if (mine.size <= n) " (${p.subject.lowercase()} ${p.v("controls", "control")} only ${mine.size}, so all of them)" else ""}.", "701.21a")
                        if (mine.size > n) state.assumptions += "${p.subject} ${p.v("chooses", "choose")} which $n ${effect.filter.raw}s to sacrifice; assuming ${picks.joinToString(", ") { it.name }} (the least valuable)."
                        for (o in picks) { o.lkiPower = o.power; state.lastSacrificed = o; move(o, Zone.GRAVEYARD, "${p.subject} ${p.v("sacrifices", "sacrifice")} ${o.name}.", "701.21a") }
                    }
                }
            }
            is Effect.PutFromHand -> {
                val you = state.player(item.controller)
                val fromZone = if (effect.fromLibrary) Zone.LIBRARY else if (effect.fromGraveyard) Zone.GRAVEYARD else Zone.HAND; val zoneName = if (effect.fromLibrary) "library" else if (effect.fromGraveyard) "graveyard" else "hand"
                // Reanimate and Sun Titan name the card as a target; with several in the graveyard, that is the one.
                val chosen = item.targets.filterIsInstance<Ref.Obj>().firstOrNull()?.let { state.objects[it.id] }?.takeIf { it.zone == fromZone }
                    ?: (item.choice ?: state.pendingChoices.remove(item.source.id))?.let { c -> state.objects[c] } ?: state.objects.values.firstOrNull { it.zone == fromZone && it.controller == item.controller && state.matches(effect.filter, it, item.controller, anyZone = true) }
                    // A basic land fetched from the library: nobody needs to name it; assume one is there.
                    ?: if (effect.fromLibrary && effect.filter.raw.contains("basic land", true)) Generic.spell("basic land")?.let { def -> state.add(GameObject(freshObjectId("basic land"), def, Zone.LIBRARY, item.controller)).also { state.assumptions += "${item.source.name} finds a basic land (${you.possessive} library has one)." } } else null
                if (chosen == null) { state.clarifications += Clarification("${item.source.name}'s card", "${item.source.name} puts ${withArticle(effect.filter.raw)} from ${you.possessive} $zoneName onto the battlefield; which card? (none was named, so nothing is put)"); trace.step("No ${effect.filter.raw} in ${you.possessive} $zoneName was named for ${item.source.name}; nothing is put onto the battlefield${if (effect.fromLibrary) " (the library is still shuffled)" else ""}.") }
                else if (chosen.zone != fromZone) trace.step("${chosen.name} isn't in ${you.possessive} $zoneName, so ${item.source.name} can't put it onto the battlefield.", "608.2b")
                else if (effect.maxMv != null && chosen.def.manaValue.toInt() > effect.maxMv) { trace.step("${chosen.name} has mana value ${chosen.def.manaValue.toInt()}, more than ${effect.maxMv}, so ${item.source.name} can't find it.", "202.3"); state.outcomes += "${chosen.name} can't be put onto the battlefield (mana value too high)." }
                else if (!state.matches(effect.filter, chosen, item.controller, anyZone = true)) { trace.step("${chosen.name} isn't a ${effect.filter.raw}, so ${item.source.name} can't put it onto the battlefield.", "608.2b"); state.outcomes += "${chosen.name} stays in hand." }
                else {
                    val counters = effect.mvEqualsCounters?.let { item.source.counters[it] ?: 0 }
                    if (counters != null && chosen.def.manaValue.toInt() != counters) { trace.step("${item.source.name} has $counters ${effect.mvEqualsCounters} counter${if (counters == 1) "" else "s"} but ${chosen.name}'s mana value is ${chosen.def.manaValue.toInt()}, so it can't be put onto the battlefield with it.", "202.3"); state.outcomes += "${chosen.name} stays in hand (mana value ${chosen.def.manaValue.toInt()} ≠ $counters counters)." }
                    else if (uncastEntryBlocked(chosen, fromZone) != null) {
                        val (by, how) = uncastEntryBlocked(chosen, fromZone)!!
                        if (how == "cant") {
                            trace.step("$by stops cards in a $zoneName from entering the battlefield, so ${chosen.name} stays in ${you.possessive} $zoneName. Nothing enters, so no enters-the-battlefield ability triggers.", "614.1", "616.1")
                            state.outcomes += "${chosen.name} can't enter the battlefield ($by); it stays in ${you.possessive} $zoneName."
                        } else {
                            trace.step("${chosen.name} would enter the battlefield without having been cast, so $by exiles it instead. It never enters, so nothing triggers on it entering.", "614.1a", "614.6")
                            moveRaw(chosen, Zone.EXILE)
                            state.outcomes += "${chosen.name}: ${you.possessive} $zoneName → exile (replaced by $by)."
                        }
                    }
                    else {
                        trace.step("${you.subject} ${you.v("puts", "put")} ${chosen.name} from ${you.possessive} hand onto the battlefield${if (counters != null) " (its mana value ${chosen.def.manaValue.toInt()} matches the $counters counters)" else ""}. It's put there directly rather than cast, so it never was a spell: it can't be countered and 'whenever you cast' abilities don't trigger.", "608.2c", *(if (counters != null) arrayOf("202.3") else emptyArray()))
                        val host = chosen.attachedTo?.let { state.objects[it] }
                        if (chosen.def.isAura && host != null) trace.step("${chosen.name} is an Aura entering without being cast: ${you.subject.lowercase()} ${you.v("chooses", "choose")} what it enchants as it enters (it doesn't target, so hexproof and shroud don't stop it): ${host.name}.", "303.4f")
                        else if (chosen.def.isAura) { chosen.attachedTo = null; state.clarifications += Clarification("${chosen.name}'s host", "${chosen.name} enters the battlefield without being cast; what does it enchant? (303.4f)") }
                        // enter() already says it entered; only the attachment needs adding, or the line is doubled.
                        enter(chosen.id); if (chosen.def.isAura && host != null) applyControlEnchanted(chosen)
                        host?.let { state.outcomes += "${chosen.name} enters the battlefield attached to ${it.name}." }
                        if (effect.fromLibrary) trace.step("${you.possessive.replaceFirstChar { c -> c.uppercase() }} library is shuffled.", "701.24a")
                        if (effect.tapped) { chosen.tapped = true; trace.step("${chosen.name} enters tapped, as the effect says.", "614.1c") }
                        if (effect.attacking) {
                            val defender = item.source.attacking ?: state.opponentsOf(item.controller).singleOrNull()?.let { Ref.Player(it.id) }
                            if (defender != null && state.phase == "combat") { chosen.attacking = defender; trace.step("${chosen.name} is put onto the battlefield attacking ${state.nameOf(defender)}. It was never declared as an attacker, so \"whenever ~ attacks\" abilities don't trigger and it isn't affected by attack costs or restrictions; it will deal combat damage as an attacking creature.", "508.4"); state.outcomes += "${chosen.name} is attacking ${state.nameOf(defender)}." }
                            else trace.step("${chosen.name} would enter attacking, but there's no combat going on, so it simply enters the battlefield.", "508.4")
                        }
                    }
                }
            }
            is Effect.Repeat -> {
                val n = if (effect.x) (item.x ?: 0) else effect.times
                if (effect.x && item.x == null) state.clarifications += Clarification("${item.source.name}'s X", "${item.source.name} repeats its process X times; what was X? (assuming 0)")
                trace.step("The process is repeated $n time${if (n == 1) "" else "s"}, each repetition done in full before the next.", "608.2c")
                repeat(n) { k -> trace.step("Repetition ${k + 1} of $n:", "608.2c"); applyEffect(effect.body, item) }
            }
            is Effect.LoseLifeUnlessSacOrDiscard -> for (p in resolvePlayers(effect.who, item)) {
                val mine = effect.filter?.let { f -> state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && state.matches(f, it, p.id) } } ?: emptyList()
                val hand = p.handSize
                when {
                    mine.isNotEmpty() -> {
                        val pick = if (mine.size == 1) mine[0] else mine.minWith(compareBy({ if (it.def.isCreature) 1 else 0 }, { it.def.manaValue }, { it.power ?: 0 }))
                        if (mine.size > 1) state.assumptions += "${p.subject} ${p.v("sacrifices", "sacrifice")} ${pick.name} rather than losing ${effect.amount} life (${p.subject.lowercase()} ${p.v("chooses", "choose")} which ${effect.filter!!.raw}; assuming the least valuable)."
                        else state.assumptions += "${p.subject} ${p.v("sacrifices", "sacrifice")} ${pick.name} rather than losing ${effect.amount} life (${p.possessive} choice; assuming ${p.subject.lowercase()} ${p.v("keeps", "keep")} the life)."
                        move(pick, Zone.GRAVEYARD, "${p.subject} ${p.v("sacrifices", "sacrifice")} ${pick.name} instead of losing ${effect.amount} life.", "701.21a")
                    }
                    effect.discard && hand != null && hand > 0 -> {
                        p.handSize = hand - 1
                        state.assumptions += "${p.subject} ${p.v("discards", "discard")} a card rather than losing ${effect.amount} life (${p.possessive} choice)."
                        trace.step("${p.subject} ${p.v("has", "have")} no ${effect.filter?.raw ?: "permanent"} to sacrifice, so ${p.subject.lowercase()} ${p.v("discards", "discard")} a card instead of losing ${effect.amount} life (${p.handSize} left in hand).", "701.9a"); state.outcomes += "${p.subject} ${p.v("discards", "discard")} a card."
                    }
                    else -> {
                        if (effect.discard && hand == null) state.clarifications += Clarification("${p.possessive} hand", "${item.source.name} lets ${p.subject.lowercase()} discard a card instead of losing life; how many cards ${p.v("does", "do")} ${p.subject.lowercase()} have in hand? (assuming none)")
                        p.life = p.life?.minus(effect.amount)
                        trace.step("${p.subject} ${p.v("has", "have")} ${if (effect.filter != null) "no ${effect.filter.raw} to sacrifice" else "nothing to sacrifice"}${if (effect.discard) " and no card to discard" else ""}, so ${p.subject.lowercase()} ${p.v("loses", "lose")} ${effect.amount} life${p.life?.let { " ($it)" } ?: ""}.", "119.3"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} ${effect.amount} life."
                    }
                }
            }
            is Effect.SacrificeEach -> for (p in resolvePlayers(effect.who, item)) {
                val mine = state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id && state.matches(effect.filter, it, p.id) }
                when {
                    mine.isEmpty() -> trace.step("${p.subject} ${p.v("controls", "control")} no ${effect.filter.raw}, so ${p.subject.lowercase()} ${p.v("sacrifices", "sacrifice")} nothing.", "701.21a")
                    mine.size == 1 -> move(mine[0], Zone.GRAVEYARD, "${p.subject} ${p.v("sacrifices", "sacrifice")} ${mine[0].name} (${p.possessive} only ${effect.filter.raw}).", "701.21a")
                    effect.greatestPower -> {
                        val top = mine.maxOf { it.power ?: 0 }; val best = mine.filter { (it.power ?: 0) == top }; val pick = best.first()
                        if (best.size > 1) state.assumptions += "${p.subject} ${p.v("sacrifices", "sacrifice")} ${pick.name}: ${best.joinToString(" and ") { it.name }} are tied for greatest power ($top), and ${p.subject.lowercase()} ${p.v("chooses", "choose")} among them."
                        move(pick, Zone.GRAVEYARD, "${p.subject} ${p.v("sacrifices", "sacrifice")} ${pick.name}, ${p.possessive} creature with the greatest power ($top)${if (mine.size > 1) " (not ${mine.filter { it !== pick }.joinToString(", ") { "${it.name}, power ${it.power ?: 0}" }})" else ""}.", "701.21a")
                    }
                    else -> { val pick = mine.minWith(compareBy({ it.power ?: 0 }, { it.toughness ?: 0 })); state.assumptions += "${p.subject} ${p.v("sacrifices", "sacrifice")} ${pick.name} (${p.subject.lowercase()} ${p.v("chooses", "choose")} which ${effect.filter.raw}; assuming the smallest)."; move(pick, Zone.GRAVEYARD, "${p.subject} ${p.v("sacrifices", "sacrifice")} ${pick.name}, ${p.possessive} choice among ${mine.joinToString(", ") { it.name }}.", "701.21a") }
                }
            }
            is Effect.GainLifeEqualToPower -> {
                val o = item.targets.firstOrNull()?.let { objOf(it) }
                val amount = o?.let { if (it.isOnBattlefield()) it.power else it.lkiPower ?: it.def.power } ?: 0
                val who = resolveWho(effect.who, item)
                if (o == null || who == null) trace.step("No creature or player to measure, so no life is gained.", "608.2h")
                else { trace.step("${o.name}'s power is $amount${if (!o.isOnBattlefield()) " (its last known information, since it has left the battlefield)" else ""}.", "608.2h"); gainLife(who, amount) }
            }
            is Effect.NarratedTargeted -> forEachLegalTarget(item, effect.target) { ref -> trace.step("${item.source.name}: \"Target ${effect.target.raw} ${effect.text.replace("~", item.source.name)}.\" That target is ${state.nameOf(ref)}; the details aren't tracked here.", *effect.rules.toTypedArray()) }
            is Effect.Regenerate -> {
                val put: (GameObject) -> Unit = { o -> state.shields += Shield(Replacement.Regenerate, o.id, null, 1, item.describe); trace.step("${o.name} gets a regeneration shield: the next time it would be destroyed this turn, it's instead tapped, its damage is removed, and it's removed from combat.", "701.19a", "614.8"); state.outcomes += "${o.name} has a regeneration shield this turn." }
                if (effect.target == null) put(item.source) else forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let(put) }
            }
            is Effect.CreateShield -> {
                val r = effect.replacement
                // "The next time a source of your choice would deal damage": the source named with the spell is the choice.
                val chosen = item.targets.singleOrNull()?.let { it as? Ref.Obj }?.id?.takeIf { r.from == null && item.source.def.oracleText.contains("source of your choice", true) }
                if (effect.target == null) { state.shields += Shield(r, null, if (r.toPlayer == Who.YOU) item.controller else null, r.amount, item.describe, fromId = chosen); if (chosen != null) trace.step("${item.describe}'s source of your choice is ${state.obj(chosen).name}; it isn't a target, just a choice made as the spell resolves.", "608.2c"); trace.step("${item.describe} creates a prevention effect until end of turn: prevent ${r.amount?.toString() ?: "all"}${if (r.combatOnly) " combat" else ""} damage that would be dealt${r.from?.let { " by ${it.raw}" } ?: ""}${when {
                    // "prevent all combat damage that would be dealt this turn" (Fog) covers players and permanents
                    // alike; naming players only made the line narrower than the effect.
                    r.to?.raw == "everything" -> ""
                    r.toPlayer == Who.YOU -> " to " + you.subject.lowercase()
                    r.toPlayer != null && r.to == null -> " to any player"
                    r.to != null -> " to ${r.to.raw}"
                    else -> ""
                }}.", "615.1", "615.7", "611.2a"); state.outcomes += "Prevention effect until end of turn (${item.describe})." }
                else forEachLegalTarget(item, effect.target) { ref -> state.shields += Shield(r, (ref as? Ref.Obj)?.id, (ref as? Ref.Player)?.id, r.amount, item.describe); trace.step("${state.nameOf(ref)} gets a prevention shield: the next ${r.amount?.toString() ?: "all"} damage that would be dealt to it this turn is prevented.", "615.7", "615.1"); state.outcomes += "${state.nameOf(ref)} has a prevention shield (${r.amount?.toString() ?: "all"}) this turn." }
            }
            is Effect.Exile -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { move(it, Zone.EXILE, "${it.name} is exiled.", "701.13a") } }
            is Effect.Blink -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { o ->
                blinkObject(o, if (effect.ownersControl) o.owner else item.controller)
            } }
            is Effect.Tap -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { trace.step("${it.name} becomes tapped.", "701.26a"); tap(it); state.outcomes += "${it.name} is tapped." } }
            is Effect.Untap -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { it.tapped = false; trace.step("${it.name} becomes untapped.", "701.26b"); state.outcomes += "${it.name} is untapped." } }
            is Effect.Pump -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let {
                it.pumps += effect.power to effect.toughness
                trace.step("${it.name} gets ${signed(effect.power)}/${signed(effect.toughness)} until end of turn; it's now ${it.power}/${it.toughness}.", "611.2a")
                state.outcomes += "${it.name} is ${it.power}/${it.toughness} until end of turn."
            } }
            is Effect.GainKeywords -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let {
                val kws = effect.keywords.map { k -> if (k == "protection from the color of your choice") "protection from ${item.choice ?: run { state.clarifications += Clarification("${item.describe}'s colour", "${item.describe} grants protection from a colour of your choice; which colour? (assuming none)"); "nothing" }}" else k }
                if (kws.toSet() != effect.keywords.toSet()) { trace.step("${state.player(item.controller).subject} ${state.player(item.controller).v("chooses", "choose")} ${item.choice ?: "no colour"}.", "608.2c"); it.tempKeywords += kws; trace.step("${it.name} gains ${kws.joinToString(" and ")} until end of turn.", "611.2a"); state.outcomes += "${it.name} has ${kws.joinToString(" and ")} until end of turn."; return@let }
                it.tempKeywords += effect.keywords
                // "unblockable" is how the engine carries "can't be blocked"; it isn't a keyword anybody prints.
                val said = effect.keywords.joinToString(" and ") { k -> if (k == "unblockable") "\"can't be blocked\"" else k }
                trace.step("${it.name} gains $said until end of turn.", "611.2a")
                state.outcomes += if (effect.keywords.singleOrNull() == "unblockable") "${it.name} can't be blocked this turn." else "${it.name} has $said until end of turn."
            } }
            is Effect.GainLife -> resolvePlayers(effect.who, item).forEach { p -> gainLife(p, effect.amount) }
            is Effect.LoseLife -> { val n = if (effect.x) (item.x ?: 0) else effect.amount; resolvePlayers(effect.who, item).forEach { p -> p.life = p.life?.minus(n); item.lifeLost += n; trace.step("${p.subject} ${p.v("loses", "lose")} $n life${p.life?.let { " ($it)" } ?: ""}.", "119.3"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} $n life." } }
            is Effect.Discard -> for (p in resolvePlayers(effect.who, item)) {
                val n = if (effect.x) (item.x ?: 0) else effect.count
                if (effect.x && item.x == null) state.clarifications += Clarification("${item.source.name}'s X", "${item.source.name} makes a player discard X cards; what was X? (assuming 0)")
                val hand = p.handSize
                if (hand == null) { trace.step("${p.subject} ${p.v("discards", "discard")} $n card${if (n == 1) "" else "s"}${if (effect.random) " at random" else " of ${p.possessive} choice"} (${p.possessive} hand size wasn't given).", "701.9a"); state.outcomes += "${p.subject} ${p.v("discards", "discard")} $n card${if (n == 1) "" else "s"}." }
                else { val d = minOf(n, hand); p.handSize = hand - d; trace.step("${p.subject} ${p.v("discards", "discard")} $d card${if (d == 1) "" else "s"}${if (effect.random) " at random" else " of ${p.possessive} choice"}${if (d < n) " (only $hand in hand)" else ""}, leaving ${p.handSize} in hand.", "701.9a"); state.outcomes += "${p.subject} ${p.v("discards", "discard")} $d card${if (d == 1) "" else "s"} ($hand → ${p.handSize} in hand)." }
            }
            is Effect.ExileIfDamagedDies -> {
                val hit = item.damaged.mapNotNull { state.objects[it] }.filter { it.isOnBattlefield() }
                hit.forEach { it.exileOnDeath = item.source.name }
                trace.step(if (hit.isEmpty()) "Nothing was dealt damage this way, so there's nothing to exile instead." else "${hit.joinToString(", ") { it.name }}: if ${if (hit.size == 1) "it" else "any of them"} would die this turn, ${if (hit.size == 1) "it's" else "it is"} exiled instead (a replacement effect: no death, so no dies-triggers, persist or undying).", "614.1a", "700.4")
            }
            is Effect.RevealTopToHand -> {
                val p = resolveWho(effect.who, item) ?: state.player(item.controller)
                val top = state.objects.values.lastOrNull { it.zone == Zone.LIBRARY && it.owner == p.id }
                if (top == null) { trace.step("${p.subject} ${p.v("reveals", "reveal")} the top card of ${p.possessive} library and ${p.v("puts", "put")} it into ${p.possessive} hand; which card that is wasn't given, so it isn't tracked.", "701.20a"); state.clarifications += Clarification("${p.possessive} top card", "${item.source.name} reveals the top card of ${p.possessive} library; which card is it?") }
                else { state.lastRevealed = top; move(top, Zone.HAND, "${p.subject} ${p.v("reveals", "reveal")} ${top.name} off the top of ${p.possessive} library and ${p.v("puts", "put")} it into ${p.possessive} hand.", "701.20a"); p.handSize = p.handSize?.plus(1) }
            }
            is Effect.LoseLifeEqualToRevealedMv -> {
                val p = resolveWho(effect.who, item) ?: state.player(item.controller)
                val card = state.lastRevealed
                if (card == null) { trace.step("No revealed card is known, so how much life is lost can't be worked out.", "608.2h"); state.clarifications += Clarification("the revealed card", "${item.source.name} takes life equal to the revealed card's mana value; which card was it?") }
                else { val mv = card.def.manaValue.toInt(); trace.step("${card.name} has mana value $mv, so ${p.subject.lowercase()} ${p.v("loses", "lose")} $mv life.", "202.3"); p.life = p.life?.minus(mv); if (mv > 0) { trace.step("${p.subject} ${p.v("loses", "lose")} $mv life${p.life?.let { " ($it)" } ?: ""}.", "119.3"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} $mv life (${card.name}'s mana value)." } else state.outcomes += "${p.subject} ${p.v("loses", "lose")} no life (${card.name} has mana value 0)." }
            }
            is Effect.PutSelfOnLibraryTop -> {
                val o = item.source
                if (!o.isOnBattlefield() && o.zone != Zone.GRAVEYARD) trace.step("${o.name} isn't on the battlefield, so it can't be put on top of a library.", "400.7")
                else move(o, Zone.LIBRARY, "${o.name} is put on top of its owner's library. It becomes a new object with no memory of its previous existence.", "400.7")
            }
            is Effect.GainLifeLostThisWay -> { val you = state.player(item.controller); if (item.lifeLost == 0) trace.step("No life was lost this way, so ${you.subject.lowercase()} ${you.v("gains", "gain")} none.", "608.2h") else { trace.step("${item.lifeLost} life was lost this way in total.", "608.2h"); gainLife(you, item.lifeLost) } }
            is Effect.PutOnBottom -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { o -> move(o, Zone.LIBRARY, "${o.name} is put on the bottom of its owner's library. It becomes a new object with no memory of its previous existence.", "400.7") } }
            is Effect.GainLifeEqualToToughness -> {
                val o = item.targets.firstOrNull()?.let { objOf(it) }
                val amount = o?.let { if (it.isOnBattlefield()) it.toughness else it.def.toughness?.plus((it.counters["+1/+1"] ?: 0) - (it.counters["-1/-1"] ?: 0)) } ?: 0
                val who = resolveWho(effect.who, item)
                if (o == null || who == null) trace.step("No creature or player to measure, so no life is gained.", "608.2h")
                else { trace.step("${o.name}'s toughness is $amount${if (!o.isOnBattlefield()) " (its last known information, since it has left the battlefield)" else ""}.", "608.2h"); gainLife(who, amount) }
            }
            is Effect.PumpSelf -> { val o = item.source; if (o.isOnBattlefield()) { o.pumps += effect.power to effect.toughness; trace.step("${o.name} gets ${signed(effect.power)}/${signed(effect.toughness)} until end of turn; it's now ${o.power}/${o.toughness}.", "611.2a"); state.outcomes += "${o.name} is ${o.power}/${o.toughness} until end of turn." } else trace.step("${o.name} isn't on the battlefield, so there's nothing for the effect to modify.", "611.2c") }
            is Effect.PumpAll -> {
                val effect = if (effect.x) effect.copy(power = -(item.x ?: 0), toughness = -(item.x ?: 0)).also { if (item.x == null) state.clarifications += Clarification("${item.source.name}'s X", "${item.source.name} gives -X/-X; what was X? (assuming 0)") else trace.step("X is ${item.x}, so it's -${item.x}/-${item.x}.", "107.3a") } else effect
                val affected = state.objects.values.filter { state.matches(effect.filter, it, item.controller) }
                affected.forEach { it.pumps += effect.power to effect.toughness; it.tempKeywords += effect.keywords }
                if (effect.power == 0 && effect.toughness == 0 && effect.keywords.isNotEmpty()) {
                    trace.step("${if (affected.isEmpty()) "No permanents match \"${effect.filter.raw}\"" else affected.joinToString(", ") { it.name }} ${if (affected.size == 1) "gains" else "gain"} ${effect.keywords.joinToString(" and ")} until end of turn. Only permanents present now are affected.", "611.2a", "611.2c")
                    affected.forEach { state.outcomes += "${it.name} has ${effect.keywords.joinToString(" and ")} until end of turn." }
                    return
                }
                trace.step("${if (affected.isEmpty()) "No permanents match \"${effect.filter.raw}\"" else affected.joinToString(", ") { "${it.name} (now ${it.power}/${it.toughness})" }} ${if (affected.size == 1) "gets" else "get"} ${signed(effect.power)}/${signed(effect.toughness)}${if (effect.keywords.isEmpty()) "" else " and ${if (affected.size == 1) "gains" else "gain"} ${effect.keywords.joinToString(" and ")}"} until end of turn. Only permanents present now are affected.", "611.2a", "611.2c")
                // Overrun gives trample as well as the size: saying only the size left the keyword out of the answer.
                affected.forEach { state.outcomes += "${it.name} is ${it.power}/${it.toughness}${if (effect.keywords.isEmpty()) "" else " with ${effect.keywords.joinToString(" and ")}"} until end of turn." }
            }
            is Effect.SetBasePtAll -> {
                val n = if (effect.x) (item.x ?: 0) else effect.power; val t = if (effect.x) (item.x ?: 0) else effect.toughness
                if (effect.x && item.x == null) state.clarifications += Clarification("${item.source.name}'s X", "${item.source.name} sets base power and toughness to X/X; what was X? (assuming 0)")
                val affected = state.objects.values.filter { state.matches(effect.filter, it, item.controller) }
                affected.forEach { it.basePt = n to t }
                trace.step("${if (affected.isEmpty()) "No permanents match \"${effect.filter.raw}\"" else affected.joinToString(", ") { "${it.name} (now ${it.power}/${it.toughness})" }} ${if (affected.size == 1) "has" else "have"} base power and toughness $n/$t until end of turn${if (effect.allCreatureTypes) " and every creature type" else ""}. That's a layer 7b effect, so counters and +N/+N effects still apply on top of it; only permanents present now are affected.", "613.4b", "611.2c")
                affected.forEach { state.outcomes += "${it.name} is ${it.power}/${it.toughness} until end of turn." }
            }
            is Effect.PutCounters -> {
                val howMany = if (effect.x) (item.x ?: 0) else effect.count
                if (effect.x && item.x == null) state.clarifications += Clarification("${item.source.name}'s X", "${item.source.name} puts X ${effect.kind} counters; what was X? (assuming 0)")
                val put: (GameObject) -> Unit = { o ->
                    val n = countersPlaced(o, howMany, effect.kind)
                    if (n == 0) {
                        trace.step("No ${effect.kind} counter is put on ${o.name}${if (o.def.isCreature) "; it stays ${o.power}/${o.toughness}" else ""}.", "122.6")
                        state.outcomes += "${o.name} gets no ${effect.kind} counters."
                    } else {
                        o.counters[effect.kind] = (o.counters[effect.kind] ?: 0) + n
                        trace.step("$n ${effect.kind} counter${if (n > 1) "s are" else " is"} put on ${o.name}${if (o.def.isCreature) "; it's now ${o.power}/${o.toughness}" else ""}.", "122.1a", "122.6")
                        state.outcomes += "${o.name} has ${o.counters[effect.kind]} ${effect.kind} counter${if (o.counters[effect.kind]!! > 1) "s" else ""}."
                        onEvent(GameEvent.CountersPut(o, effect.kind, n))
                    }
                }
                if (effect.all != null) { val affected = state.objects.values.filter { state.matches(effect.all, it, item.controller, item.source) }; if (affected.isEmpty()) trace.step("No permanents match \"${effect.all.raw}\", so no counters are put anywhere.", "122.6") else affected.forEach(put) }
                else if (effect.target == null) { if (item.source.isOnBattlefield()) put(item.source) else trace.step("${item.source.name} isn't on the battlefield, so no counters are put on it.", "122.6") }
                else forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let(put) }
                if (effect.kind == "+1/+1" || effect.kind == "-1/-1") stateBasedActions()
            }
            is Effect.RemoveAllCounters -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { o ->
                val had = o.counters.filterValues { it > 0 }
                if (had.isEmpty()) trace.step("${o.name} has no counters on it, so nothing is removed.", "608.2c")
                else { trace.step("All counters are removed from ${o.name}: ${had.entries.joinToString(", ") { (k, n) -> "$n $k" }}${if (o.def.isCreature) "; it's now ${o.power}/${o.toughness} once they're gone" else ""}.", "608.2c", "122.1"); state.outcomes += "${o.name} loses all its counters (${had.entries.joinToString(", ") { (k, n) -> "$n $k" }})." }
                o.counters.clear()
                if (had.keys.any { it == "+1/+1" || it == "-1/-1" || it == "loyalty" }) stateBasedActions()
            } }
            is Effect.Attach -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { t ->
                item.source.attachedTo = t.id; applyControlEnchanted(item.source)
                trace.step("${item.source.name} becomes attached to ${t.name}${if (item.source.def.isEquipment) " (equipped creature)" else ""}.", *(if (item.source.def.isEquipment) arrayOf("702.6a", "301.5a") else arrayOf("701.3a")))
                state.outcomes += "${item.source.name} is attached to ${t.name}."
                if (t.def.isCreature) trace.step("${t.name} is now ${state.describePt(t)}.", "613.1")
            } }
            is Effect.IfYouDo -> {
                trace.step("${you.subject} may ${describe(effect.choice, item)}. If ${you.v("they do", "you do")}: ${describe(effect.then, item)}.", "608.2d")
                state.assumptions += "${you.subject} ${you.v("chooses", "choose")} to ${describe(effect.choice, item)} for ${item.describe}."
                applyEffect(effect.choice, item); applyEffect(effect.then, item)
            }
            is Effect.IfCondition -> {
                if (state.conditionHolds(effect.condition, item.source)) {
                    trace.step("${item.describe} checks \"if ${effect.raw}\" as it resolves, and it holds, so the rest happens.", "608.2")
                    applyEffect(effect.then, item)
                } else {
                    trace.step("${item.describe} checks \"if ${effect.raw}\" as it resolves. It needs ${state.describeCondition(effect.condition)}, which isn't so, so nothing happens.", "608.2")
                    state.outcomes += "Nothing happens from ${item.describe}: it needs ${state.describeCondition(effect.condition)}."
                }
            }
            is Effect.GainControl -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { o ->
                val was = state.player(o.controller); if (effect.untilEndOfTurn && o.controlRevertsTo == null) o.controlRevertsTo = o.controller; o.controller = item.controller
                trace.step("${state.player(item.controller).subject} ${state.player(item.controller).v("gains", "gain")} control of ${o.name}${if (effect.untilEndOfTurn) " until end of turn" else ""} (it was ${was.possessive}). A control-changing effect applies in layer 2; the permanent doesn't change zones, so it isn't summoning sick only if it has haste or has been under its new controller's control since the turn began.", "613.1b", "611.2a", "302.6")
                if (o.def.isCreature) o.summoningSick = true
                state.outcomes += "${state.player(item.controller).subject} ${state.player(item.controller).v("controls", "control")} ${o.name}${if (effect.untilEndOfTurn) " until end of turn" else ""}."
            } }
            is Effect.GainKeywordsSelf -> { val o = item.source; if (o.isOnBattlefield()) { o.tempKeywords += effect.keywords
                val said = effect.keywords.joinToString(" and ") { k -> if (k == "unblockable") "\"can't be blocked\"" else k }
                trace.step("${o.name} gains $said until end of turn.", "611.2a")
                state.outcomes += if (effect.keywords.singleOrNull() == "unblockable") "${o.name} can't be blocked this turn." else "${o.name} has $said until end of turn." } }
            is Effect.Modal -> {
                val chosen = item.modes
                if (chosen.isEmpty()) {
                    state.clarifications += Clarification("${item.describe}'s mode", "${item.describe} is modal (choose ${effect.count}): " + effect.modeTexts.mapIndexed { i, t -> "[${i + 1}] ${t.replace("~", item.source.name)}" }.joinToString("; ") + ". Which mode(s)? (700.2a: chosen as it's cast)")
                    trace.step("${item.describe} is modal; its mode was chosen as it was cast. Not told which, so its effect isn't applied.", "700.2a", "601.2b")
                } else for (mi in chosen) { effect.modes.getOrNull(mi - 1)?.let { trace.step("Mode ${mi}: ${effect.modeTexts[mi - 1].replace("~", item.source.name)}.", "700.2a"); applyEffect(it, item) } ?: run { state.clarifications += Clarification("mode", "Mode $mi doesn't exist on ${item.describe}.") } }
            }
            is Effect.CantLoseThisTurn -> {
                val p = state.player(item.controller)
                state.cantLoseThisTurn += p.id
                trace.step("${p.subject} can't lose the game this turn and ${p.possessive} opponents can't win it; the state-based action that would end the game doesn't apply until the effect ends.", "104.3b", "614.1")
                state.outcomes += "${p.subject} can't lose the game this turn."
            }
            is Effect.ExtraLandThisTurn -> {
                val p = state.player(item.controller)
                state.extraLandsThisTurn[p.id] = (state.extraLandsThisTurn[p.id] ?: 0) + effect.count
                trace.step("${p.subject} may play ${effect.count} additional land${if (effect.count == 1) "" else "s"} this turn.", "305.2")
                state.outcomes += "${p.subject} may play ${effect.count} additional land${if (effect.count == 1) "" else "s"} this turn."
            }
            is Effect.DamageLifeFloor -> {
                val p = state.player(item.controller)
                state.damageLifeFloor[p.id] = effect.floor
                trace.step("Until end of turn, damage that would reduce ${if (p.you) "your" else p.possessive} life total below ${effect.floor} reduces it to ${effect.floor} instead.", "614.1b")
                state.outcomes += "Damage can't take ${p.subject.lowercase()} below ${effect.floor} life this turn."
            }
            is Effect.AddMana -> trace.step("${describeManaEffect(effect)}.", "605.1a")
            is Effect.CounterThatSpell -> {
                val spell = item.causedObject?.let { id -> state.stack.firstOrNull { it.id == id } }
                if (spell == null) trace.step("The spell that made ${item.source.name} trigger is no longer on the stack, so nothing is countered.", "701.6a", "608.2b")
                else {
                    trace.step("${spell.describe} is countered: it never resolves and goes to its owner's graveyard. Countering isn't damage or destruction, so nothing about the spell can stop it.", "701.6a", "701.6b")
                    state.stack.remove(spell); state.lastCountered = spell.source
                    moveRaw(spell.source, Zone.GRAVEYARD)
                    state.outcomes += "${spell.source.name} is countered."
                }
            }
            is Effect.AddManaDevotion -> {
                val you = state.player(item.controller)
                val colour = item.choice?.firstOrNull()?.uppercaseChar()?.takeIf { it in "WUBRG" }
                    ?: you.devotion.entries.maxByOrNull { it.value }?.key
                    ?: "WUBRG".maxByOrNull { devotionOf(item.controller, it) }
                if (colour == null) { state.clarifications += Clarification("${item.source.name}'s colour", "${item.source.name} asks for a colour; which one?") }
                else {
                    val n = devotionOf(item.controller, colour)
                    val name = mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")[colour]
                    trace.step("${you.subject} ${you.v("chooses", "choose")} $name. ${you.possessive.replaceFirstChar { c -> c.uppercase() }} devotion to $name is $n${if (you.devotion[colour] != null) " (as stated)" else " ({$colour} symbols in the mana costs of the permanents ${you.subject.lowercase()} ${you.v("controls", "control")})"}, so ${item.source.name} adds ${if (n == 0) "no mana" else "{$colour}".repeat(n)}.", "700.5", "605.1a")
                }
            }
            is Effect.AddManaPer -> {
                val n = state.objects.values.count { it.isOnBattlefield() && state.matches(effect.filter, it, item.controller, item.source) }
                val pp = state.player(item.controller)
                trace.step("${pp.subject} ${pp.v("controls", "control")} $n ${effect.filter.raw.removeSuffix(" you control")}${if (n == 1) "" else "s"}, so ${item.source.name} adds ${if (n == 0) "no mana" else effect.symbol.repeat(n)}.", "605.1a", "107.3")
            }
            is Effect.AddManaInstead -> {
                fun key(x: String) = x.lowercase().replace(Regex("""[^a-z]"""), "")
                fun controls(n: String) = state.objects.values.any { o -> o.isOnBattlefield() && o.controller == item.controller && (key(o.def.name) == key(n) || o.def.subtypes.any { key(it) == key(n) }) }
                val missing = effect.required.filter { !controls(it) }
                val p = state.player(item.controller)
                if (missing.isEmpty()) trace.step("${p.subject} ${p.v("controls", "control")} ${effect.required.joinToString(" and ") { "an $it" }}, so ${item.source.name} adds ${effect.text} instead of what it would otherwise add.", "605.1a", "614.1")
                else trace.step("${p.subject} ${p.v("doesn't", "don't")} control ${missing.joinToString(" or ") { "an $it" }}, so ${item.source.name} adds only what it says first, not ${effect.text}.", "605.1a")
            }
            is Effect.Narrated -> {
                // "that many": the count of what the previous part affected (Settle the Wreckage's exiled attackers).
                val effect = item.lastCount?.takeIf { effect.text.contains("that many") }?.let { n -> effect.copy(text = effect.text.replace("that many", "$n (that many)")) } ?: effect
                state.lastCountered?.let { c -> if (effect.text.contains("that spell's mana value")) { val mv = c.def.manaValue.toInt(); trace.step("That spell was ${c.name}, mana value $mv. ${effect.text.replace("that spell's mana value", "$mv").replaceFirstChar { it.uppercase() }} (a delayed triggered ability created as ${item.source.name} resolves).", "202.3", "608.2h", *effect.rules.toTypedArray()); state.outcomes += "${item.source.name}: ${effect.text.replace("that spell's mana value", "$mv (${c.name}'s mana value)").replace("~", item.source.name).replaceFirstChar { it.uppercase() }.trimEnd('.')}."; return } }
                // Text with its own subject ("You choose…", "That player discards…", "Its controller may…") is quoted as the instruction it is.
                val ownSubject = Regex("""^(you|that player|its controller|each|the|its|their|if|search)\b""", RegexOption.IGNORE_CASE).containsMatchIn(effect.text)
                if (ownSubject) trace.step("${item.source.name}: \"${effect.text.replace("~", item.source.name).replaceFirstChar { it.uppercase() }.trimEnd('.')}.\" (${you.subject} ${you.v("carries", "carry")} this out; the details aren't tracked here.)", *effect.rules.toTypedArray())
                else trace.step("${you.subject} ${thirdPerson(effectText(effect.text, item), you)}.", *effect.rules.toTypedArray())
                val said = if (ownSubject || you.you) effect.text.replace("~", item.source.name).replaceFirstChar { it.uppercase() } else "${you.subject} ${thirdPerson(effectText(effect.text, item), you)}"
                state.outcomes += "${item.source.name}: ${said.trimEnd('.')} (not tracked in detail)."
            }
            is Effect.ForAll -> {
                val affected = state.objects.values.filter { state.matches(effect.filter, it, item.controller) }
                item.lastCount = affected.size
                if (affected.isEmpty()) trace.step("Nothing matches \"${effect.filter.raw}\", so ${effect.action} affects nothing.")
                // Everything leaves at once: abilities of permanents leaving simultaneously still see the others go (603.10a).
                if (effect.action in setOf("destroy", "exile", "bounce", "tuck") && affected.size > 1) { leavingTogether = affected.map { it.id }.toSet(); trace.step("All of them leave the battlefield simultaneously, so abilities that trigger on creatures dying or leaving look back and see every one of them.", "603.10a") }
                try { for (o in affected) when (effect.action) {
                    "destroy" -> destroy(o, "${o.name} is destroyed.", "701.8a", canRegenerate = !effect.noRegen)
                    "exile" -> move(o, Zone.EXILE, "${o.name} is exiled.", "701.13a")
                    "bounce" -> move(o, Zone.HAND, "${o.name} is returned to its owner's hand.", "400.7")
                    "tuck" -> move(o, Zone.LIBRARY, "${o.name} is put on the bottom of its owner's library. It isn't destroyed, so indestructible doesn't help, and it isn't a death, so \"when this dies\" abilities don't trigger.", "400.7")
                    "tap" -> { o.tapped = true; trace.step("${o.name} becomes tapped.", "701.26a"); state.outcomes += "${o.name} is tapped." }
                    "untap" -> { o.tapped = false; trace.step("${o.name} becomes untapped.", "701.26b") }
                    "damage" -> { applyDamage(item.source.name, Ref.Obj(o.id), effect.amount, item.source); item.damaged += o.id }
                } } finally { leavingTogether = emptySet() }
            }
            is Effect.Unparsed -> trace.step("(Not modeled: \"${effect.text}\")")
        }
    }

    /** "Enters tapped" / "enters with N counters": replacement effects that modify how it enters (614.1c, 614.12). */
    /**
     * 706.2: a permanent that enters as a copy takes on the copied permanent's copiable values — its printed card
     * plus any other copy effects on it — and nothing else. Counters, damage, Auras, control effects and pumps on
     * the original are not copied, and the copy keeps its own id, controller and everything it did on its way in.
     */
    private fun applyEntersAsCopy(o: GameObject, choice: String?) {
        val e = o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.EntersAsCopy>().firstOrNull() ?: return
        val was = o.name
        val asked = choice?.takeIf { it.startsWith("copy:") }?.removePrefix("copy:")?.let { state.objects[it] }
        val legal = state.objects.values.filter { it.isOnBattlefield() && it !== o && state.matches(e.filter, it, o.controller, o) }
        if (asked != null && asked !in legal) {
            trace.step("$was can only enter as a copy of ${withArticle(e.filter.raw)}, and ${asked.name} isn't one, so it enters as itself.", "707.2", "614.1c")
            state.outcomes += "$was enters as itself (${asked.name} isn't ${withArticle(e.filter.raw)})."
            return
        }
        val chosen = asked ?: legal.firstOrNull()
        if (chosen == null) {
            trace.step("$was would enter as a copy of ${withArticle(e.filter.raw)}, but there is none on the battlefield to copy, so nothing is copied and it enters as ${if (o.def.isCreature) "the ${o.def.power ?: 0}/${o.def.toughness ?: 0} it is printed as" else "itself"}.", "707.2", "614.1c")
            return
        }
        if (asked == null) state.assumptions += "$was enters as a copy of ${chosen.name}${if (legal.size > 1) " (nothing said which ${e.filter.raw}; there were ${legal.size} to choose from)" else ""}."
        trace.step("$was enters as a copy of ${chosen.name}: it copies the printed card and any other copy effects on it, and nothing else — not counters, damage, Auras, or anything else that has happened to ${chosen.name}.", "707.2", "707.2a", "614.1c")
        o.def = chosen.def
        if (e.tapped) o.tapped = true
        e.except?.let { state.unsupported += Unsupported(was, "The copy's exception is not modeled: " + it.replace("~", was)) }
    }

    private fun applyEntersReplacements(o: GameObject, choice: String? = null) {
        applyEntersAsCopy(o, choice)
        val startingLoyalty = o.def.loyalty
        if (o.def.isPlaneswalker && startingLoyalty != null) { val n = countersPlaced(o, startingLoyalty, "loyalty"); o.counters["loyalty"] = n; trace.step("${o.name} enters with $n loyalty counters.", "306.5b"); state.outcomes += "${o.name} has $n loyalty." }
        // Blind Obedience and friends: someone else's static makes this enter tapped.
        for (src in state.objects.values.filter { it.isOnBattlefield() && it !== o }) {
            for (e in src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.OthersEnterTapped>()) {
                if (e.opponentsOnly && src.controller == o.controller) continue
                if (e.filter.raw == "nonbasic land" && ("Basic" in o.def.supertypes || "Land" !in o.def.types)) continue
                if (e.filter.raw != "nonbasic land" && !state.matches(e.filter, o, src.controller, src, anyZone = true)) continue
                if (o.tapped != true) { o.tapped = true; trace.step("${src.name} makes each ${e.filter.raw} ${if (e.opponentsOnly) "${state.player(src.controller).possessive} opponents control " else ""}enter tapped, so ${o.name} enters tapped.", "614.1c", "614.12") }
            }
        }
        for (e in o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }) when (e) {
            is StaticEffect.EntersTapped -> {
                // The shocklands: the payment is made as it enters, so nothing can be done in between. Said
                // nothing, the choice the card offers is taken (the life is paid), unless that would be lethal.
                if (e.unlessPayLife != null) {
                    val p = state.player(o.controller)
                    val life = p.life
                    val pays = when { o.controller in state.wontPay -> false
                                      o.controller in state.willPay -> true
                                      life != null && life <= e.unlessPayLife -> false
                                      else -> true }
                    if (pays) {
                        p.life = life?.minus(e.unlessPayLife)
                        trace.step("${p.subject} ${p.v("pays", "pay")} ${e.unlessPayLife} life as ${o.name} enters${p.life?.let { " ($it)" } ?: ""}, so it enters untapped.", "614.1c", "614.12", "119.4")
                        state.outcomes += "${p.subject} ${p.v("pays", "pay")} ${e.unlessPayLife} life; ${o.name} enters untapped."
                        if (o.controller !in state.willPay) state.assumptions += "${p.subject} ${p.v("pays", "pay")} the ${e.unlessPayLife} life for ${o.name} (the card offers the choice as it enters); say so outright for the other choice."
                    } else {
                        o.tapped = true
                        trace.step("${p.subject} ${p.v("doesn't", "don't")} pay the ${e.unlessPayLife} life${if (life != null && life <= e.unlessPayLife) " (it would be lethal)" else ""}, so ${o.name} enters tapped.", "614.1c", "614.12")
                        state.outcomes += "${o.name} enters tapped (the ${e.unlessPayLife} life wasn't paid)."
                    }
                }
                else if (e.unless != null && state.conditionHolds(e.unless, o)) trace.step("${o.name} would enter tapped unless its condition is met; it is, so it enters untapped.", "614.1c", "614.12")
                else if (e.onlyIf != null && !state.conditionHolds(e.onlyIf, o)) trace.step("${o.name} enters tapped only if ${state.describeCondition(e.onlyIf)}, which isn't so, so it enters untapped.", "614.1c", "614.12")
                else { o.tapped = true; trace.step("${o.name} enters tapped (a replacement effect on how it enters${if (e.unless != null) "; its condition isn't met" else if (e.onlyIf != null) "; its condition is met" else ""}).", "614.1c", "614.12") }
            }
            is StaticEffect.EntersWithCounters -> if (e.onlyIfKicked && !o.wasKicked) {
                trace.step("${o.name} wasn't kicked, so it enters with no ${e.kind} counters.", "614.1c", "702.33d")
            } else if (e.per != null) {
                val x = when (val c = e.per) {
                    is CountExpr.Permanents -> state.objects.values.count { state.matches(c.filter, it, o.controller, o) }
                    is CountExpr.CardTypesInGraveyards -> state.cardTypesInGraveyards().size
                    is CountExpr.YourLifeTotal -> state.player(o.controller).life
                    is CountExpr.Unknown -> null
                    null -> null
                }
                if (x == null) state.clarifications += Clarification("${o.name}'s counters", "${o.name} enters with a ${e.kind} counter for each ${(e.per as? CountExpr.Unknown)?.text ?: "thing"}, which isn't tracked; say how many.")
                else { val n = countersPlaced(o, x, e.kind); o.counters[e.kind] = (o.counters[e.kind] ?: 0) + n
                    trace.step("${o.name} enters with $n ${e.kind} counter${if (n == 1) "" else "s"} on it, counted as it enters.", "614.1c", "122.6")
                    state.outcomes += "${o.name} enters with $n ${e.kind} counter${if (n == 1) "" else "s"}." }
            } else if (e.count != null) { val n = countersPlaced(o, e.count, e.kind); o.counters[e.kind] = (o.counters[e.kind] ?: 0) + n; trace.step("${o.name} enters with $n ${e.kind} counter${if (n > 1) "s" else ""} on it.", "614.1c", "122.6") }
                else if (o.x != null) { val n = countersPlaced(o, o.x!!, e.kind); o.counters[e.kind] = (o.counters[e.kind] ?: 0) + n; trace.step("${o.name} enters with $n ${e.kind} counter${if (n == 1) "" else "s"} on it (X was ${o.x}).", "614.1c", "107.3a"); state.outcomes += "${o.name} enters with $n ${e.kind} counter${if (n == 1) "" else "s"}." }
                else { state.clarifications += Clarification("${o.name}'s X", "${o.name} enters with X ${e.kind} counters; what was X?") }
            else -> {}
        }
    }

    private fun tap(o: GameObject) { if (o.tapped != true) { o.tapped = true; onEvent(GameEvent.BecomesTapped(o)) } }

    /** Mind Control and friends: the Aura's controller controls the enchanted creature while it's attached (613.1b). */
    private fun applyControlEnchanted(aura: GameObject) {
        if (aura.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.none { it is StaticEffect.ControlEnchanted }) return
        val host = aura.attachedTo?.let { state.objects[it] } ?: return
        if (host.controller == aura.controller) return
        val was = state.player(host.controller); host.controller = aura.controller; host.summoningSick = true
        trace.step("${aura.name} says its controller controls the enchanted creature: ${state.player(aura.controller).subject} ${state.player(aura.controller).v("controls", "control")} ${host.name} now (it was ${was.possessive}). It's summoning sick for its new controller unless it has haste.", "613.1b", "302.6")
        state.outcomes += "${state.player(aura.controller).subject} ${state.player(aura.controller).v("controls", "control")} ${host.name}."
    }

    private fun moveRaw(obj: GameObject, to: Zone) {
        if (obj.isOnBattlefield()) {
            // A control-changing Aura leaving gives the creature back (the effect ends, 611.2b).
            if (obj.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.ControlEnchanted }) obj.attachedTo?.let { state.objects[it] }?.let { host ->
                if (host.controller != host.owner) { host.controller = host.owner; trace.step("${obj.name} has left the battlefield, so its control effect ends and ${host.name} goes back to ${state.player(host.owner).possessive} control.", "611.2b"); state.outcomes += "${state.player(host.owner).subject} ${state.player(host.owner).v("controls", "control")} ${host.name} again." }
            }
        }
        if (obj.isOnBattlefield()) obj.lkiPower = obj.power
        obj.zone = to; obj.damage = 0; obj.pumps.clear(); obj.tempKeywords.clear(); obj.basePt = null; obj.animatedAs = null; obj.saddled = false; obj.saddledThisTurn = 0; obj.tapped = false; obj.attacking = null; obj.blocking = null; obj.dealtDeathtouchDamage = false
    }

    /** Destruction with regeneration (701.19a, 614.8): returns true if the destruction was replaced. */
    private fun destroy(obj: GameObject, text: String, vararg rules: String, canRegenerate: Boolean = true): Boolean {
        if (obj.has("indestructible")) { trace.step("${obj.name} is indestructible and can't be destroyed.", "702.12b"); state.outcomes += "${obj.name} is indestructible and isn't destroyed."; return true }
        val shield = state.shields.firstOrNull { it.replacement == Replacement.Regenerate && it.objectId == obj.id && (it.remaining ?: 0) > 0 }
        if (shield != null && !canRegenerate) trace.step("${obj.name} has a regeneration shield, but the effect says it can't be regenerated, so the shield can't replace this destruction.", "701.19c", "614.8")
        if (shield != null && canRegenerate) {
            shield.remaining = 0
            obj.tapped = true; obj.damage = 0; obj.attacking = null; obj.blocking = null
            trace.step("$text But ${obj.name} has a regeneration shield from ${shield.sourceName}: instead of being destroyed, it's tapped, all damage is removed from it and it's removed from combat.", *rules, "701.19a", "614.8")
            state.outcomes += "${obj.name} regenerates."
            return true
        }
        move(obj, Zone.GRAVEYARD, text, *rules)
        return false
    }

    private fun cant(o: GameObject, what: String): Boolean {
        // "Target creature can't be blocked this turn" is granted for the turn, not printed on the card.
        if (what == "be blocked" && o.has("unblockable")) return true
        val own = o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.Cant && it.by == null && it.applies == null && !it.powerAboveHand && it.unlessDefenderControls == null && it.unlessYouControl == null && (it.what == what || it.what == "attack or block" && (what == "attack" || what == "block")) }
        if (own) return true
        // "Enchanted creature can't attack or block" and the like, from other permanents.
        return state.objects.values.filter { it.isOnBattlefield() }.any { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { e -> e is StaticEffect.Cant && e.applies != null && (e.what == what || e.what == "attack or block" && (what == "attack" || what == "block")) && state.matches(e.applies, o, src.controller, src) } }
    }
    /**
     * "~ can't attack unless defending player controls an Island" / "… unless you control another artifact":
     * the condition that isn't met, or null if the creature may attack. [defenderId] is the player being attacked;
     * with none given (the "can it attack?" question) the attacker's opponent is used.
     */
    private fun attackConditionUnmet(a: GameObject, defenderId: String?): String? {
        for (e in a.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.Cant>()) {
            if (e.what != "attack") continue
            val defending = e.unlessDefenderControls != null
            val filter = e.unlessDefenderControls ?: e.unlessYouControl ?: continue
            val whose = (if (defending) defenderId ?: state.opponentsOf(a.controller).firstOrNull()?.id else a.controller) ?: continue
            if (state.objects.values.count { it.isOnBattlefield() && it.controller == whose && it !== a && state.matches(filter, it, whose) } >= e.unlessCount) continue
            val what = if (e.unlessCount > 1) "${e.unlessCount} or more ${filter.raw}s" else withArticle(filter.raw)
            return if (defending) "defending player controls $what" else "you control $what"
        }
        return null
    }

    private fun cantSource(o: GameObject, what: String): String? = state.objects.values.filter { it.isOnBattlefield() && it !== o }.firstOrNull { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { e -> e is StaticEffect.Cant && e.applies != null && (e.what == what || e.what == "attack or block") && state.matches(e.applies, o, src.controller, src) } }?.name

    /** "~ can't be blocked by [filter]": the restriction that forbids this particular blocker, if any. */
    private fun cantBeBlockedBy(a: GameObject, b: GameObject): StaticEffect.Cant? = a.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.Cant>().firstOrNull { it.what == "be blocked" && it.by != null && state.matches(it.by, b, b.controller) }

    private fun describeManaEffect(e: Effect): String = when (e) { is Effect.AddMana -> "add ${e.text}"; is Effect.AddManaPer -> "add ${e.symbol} for each ${e.filter.raw}"; is Effect.AddManaDevotion -> "choose a colour and add that much mana of it as your devotion to it"; is Effect.AddManaInstead -> "add ${e.text} instead if you control ${e.required.joinToString(" and ") { "an $it" }}"; is Effect.Narrated -> e.text.trimEnd('.'); is Effect.DamagePlayer -> "it deals ${e.amount} damage to ${when (e.who) { Who.YOU -> "you"; Who.EACH_OPPONENT -> "each opponent"; Who.EACH_PLAYER -> "each player"; else -> "that player" }}"; is Effect.Seq -> e.effects.joinToString(", then ") { describeManaEffect(it) }; else -> e.toString().lowercase() }
    /** "~" -> the source's name; first letter lowercased for use after a subject. */
    private fun effectText(t: String, item: StackItem) = t.replace("~", item.source.name).replaceFirstChar { it.lowercase() }

    /** "put two cards from your hand on top of your library" said of an opponent: "puts two cards from their hand on top of their library".
     *  Read as written, Brainstorm cast by the opponent had them putting back cards from the asker's hand. */
    private fun thirdPerson(t: String, p: Player): String {
        if (p.you) return t
        val words = t.split(" ", limit = 2)
        val verb = words[0]
        val conj = when {
            verb in setOf("may", "can", "must", "can't") -> verb
            verb.endsWith("s") || verb.endsWith("x") || verb.endsWith("ch") || verb.endsWith("sh") -> verb + "es"
            verb.endsWith("y") && verb.length > 1 && verb[verb.length - 2] !in "aeiou" -> verb.dropLast(1) + "ies"
            else -> verb + "s"
        }
        val rest = words.getOrNull(1)?.replace(Regex("""\byour\b"""), "their")?.replace(Regex("""\byou\b"""), "they")
        return if (rest == null) conj else "$conj $rest"
    }

    /** Applicable prevention effects for damage from [source] to [target]: static ones from the battlefield plus shields. */
    private fun preventionFor(source: GameObject?, target: Ref, combat: Boolean): List<Pair<String, Any>> {
        val out = mutableListOf<Pair<String, Any>>()
        val tObj = (target as? Ref.Obj)?.let { state.objects[it.id] }
        val tPlayer = (target as? Ref.Player)?.let { state.player(it.id) }
        fun applies(r: Replacement.PreventDamage, owner: GameObject?, ownerPlayer: String?, shield: Shield?): Boolean {
            if (r.combatOnly && !combat) return false
            if (r.fromSelf && source !== owner) return false
            // The source of damage can be a spell on the stack, so it is matched in whatever zone it is in.
            if (r.from != null && (source == null || !state.matches(r.from, source, ownerPlayer ?: "", owner, anyZone = true))) return false
            if (shield?.fromId != null && source?.id != shield.fromId) return false
            if (shield != null && shield.objectId != null) return tObj?.id == shield.objectId
            if (shield != null && shield.playerId != null) return tPlayer?.id == shield.playerId
            val toOk = when {
                r.to != null && r.to.raw == "~" -> tObj != null && tObj === owner
                r.to != null && r.to.raw == "everything" -> true
                r.to != null -> tObj != null && state.matches(r.to, tObj, ownerPlayer ?: "", owner)
                else -> false
            }
            val playerOk = r.toPlayer != null && tPlayer != null && when (r.toPlayer) { Who.YOU -> tPlayer.id == ownerPlayer; Who.OPPONENT -> tPlayer.id != ownerPlayer; else -> true }
            // "Prevent all damage that would be dealt by X" restricts the source only: any recipient qualifies.
            if (r.to == null && r.toPlayer == null) return true
            return toOk || playerOk
        }
        for (o in state.objects.values) if (o.isOnBattlefield()) for (e in o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }) {
            val r = (e as? StaticEffect.Replace)?.replacement as? Replacement.PreventDamage ?: continue
            if (applies(r, o, o.controller, null)) out += o.name to r
        }
        for (sh in state.shields) { val r = sh.replacement as? Replacement.PreventDamage ?: continue; if ((sh.remaining ?: 1) > 0 && applies(r, null, sh.playerId ?: state.players.first().id, sh)) out += sh.sourceName to sh }
        return out
    }

    private fun applyDamage(sourceName: String, target: Ref, amount: Int, source: GameObject? = state.objects.values.firstOrNull { it.name == sourceName }): Int {
        var amount = amount
        if (inCombatDamage && (source?.id in state.combatDamageMuted || (target as? Ref.Obj)?.id in state.combatDamageMuted)) {
            val who = if (source?.id in state.combatDamageMuted) source!!.name else state.nameOf(target)
            trace.step("All combat damage dealt to and by $who is prevented this turn (Maze of Ith-style effect), so the $amount combat damage ${if (source?.id in state.combatDamageMuted) "it would deal to ${state.nameOf(target)}" else "$sourceName would deal to it"} is prevented.", "615.1", "615.6")
            state.outcomes += "$amount combat damage ${if (source?.id in state.combatDamageMuted) "from $who" else "to $who"} is prevented."; return 0
        }
        // Replacement effects that modify damage (614.2, 609.7): doublers from the battlefield.
        val doublers = state.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.DamageMultiplier }.filter { d -> d.sourceControl == null || source == null || (d.sourceControl == Who.YOU) == (source.controller == o.controller) }.map { o to it } }
        val prevention = preventionFor(source, target, inCombatDamage)
        if (doublers.isNotEmpty() && prevention.isNotEmpty()) trace.step("Both a damage-doubling replacement effect and a prevention effect apply; the affected player chooses the order (616.1). Assuming the prevention is applied first, which is best for the affected player.", "616.1", "616.1e")
        // Comeuppance / Deflecting Palm: what a shield prevented is dealt back by the shield's own source (its ruling:
        // the spell is the source of the new damage, so it isn't combat damage and the original source's abilities don't apply).
        val reflections = mutableListOf<() -> Unit>()
        if (prevention.isNotEmpty()) {
            for ((name, p) in prevention) {
                if (amount <= 0) break
                when (p) {
                    is Replacement.PreventDamage -> { trace.step("$name prevents ${if (p.amount == null) "all" else p.amount.toString()} of the $amount damage $sourceName would deal to ${state.nameOf(target)}.", "615.1", "615.6"); amount = if (p.amount == null) 0 else maxOf(0, amount - p.amount) }
                    is Shield -> { val r = p.replacement as Replacement.PreventDamage; val prevented = if (r.amount == null) amount else minOf(amount, p.remaining ?: 0); trace.step("$name's prevention shield prevents $prevented of the $amount damage $sourceName would deal to ${state.nameOf(target)}.", "615.7", "615.6"); amount -= prevented; if (r.amount != null) p.remaining = (p.remaining ?: 0) - prevented else if (r.once) p.remaining = 0
                        if (prevented > 0 && source != null && (r.reflectToCreature || r.reflectToController)) {
                            val back: Ref? = if (r.reflectToCreature && state.isCreature(source)) Ref.Obj(source.id) else if (r.reflectToController) Ref.Player(source.controller) else null
                            if (back != null) reflections += { trace.step("$name prevented $prevented damage from ${source.name}, so $name deals $prevented damage to ${state.nameOf(back)}${if (back is Ref.Player) " (${source.name}'s controller)" else ""}. $name is the source of that damage, so it isn't combat damage.", "615.7"); applyDamage(name, back, prevented, state.objects.values.firstOrNull { it.name == name }) }
                        } }
                }
            }
            if (amount <= 0) { state.outcomes += "Damage to ${state.nameOf(target)} from $sourceName is prevented."; reflections.forEach { it() }; return 0 }
        }
        reflections.forEach { it() }
        for ((o, d) in doublers) { trace.step("${o.name} replaces the damage: $sourceName deals ${amount * d.factor} damage instead of $amount.", "614.1a", "614.6"); amount *= d.factor }
        if (source != null && target is Ref.Obj) {
            val o = state.objects[target.id]
            if (o != null) {
                val qualities = qualitiesOf(source.def)
                state.protections(o).firstOrNull { it == "everything" || it in qualities }?.let { q ->
                    trace.step("$sourceName would deal $amount damage to ${o.name}, but ${o.name} has protection from $q, so that damage is prevented.", "702.16e", "615.1")
                    state.outcomes += "Damage to ${o.name} from $sourceName is prevented (protection)."; return 0
                }
            }
        }
        val infect = source?.has("infect") == true; val wither = source?.has("wither") == true
        // Angel's Grace: damage that would take the player under the floor is replaced by just enough to reach it
        // (614.1b). Infect deals poison counters rather than life loss, so the floor doesn't touch it.
        if (target is Ref.Player && !infect) state.damageLifeFloor[target.id]?.let { floor ->
            val p = state.player(target.id); val life = p.life
            if (life != null && life - amount < floor) {
                val kept = maxOf(0, life - floor)
                trace.step("$sourceName would deal $amount damage to ${if (p.you) "you" else p.name}, but that would take ${if (p.you) "your" else p.possessive} life total below $floor, so it deals $kept instead.", "614.1b", "614.6")
                state.outcomes += "${p.subject} ${p.v("stays", "stay")} at $floor life."
                amount = kept
            }
        }
        when (target) {
            is Ref.Player -> { val p = state.player(target.id)
                if (infect) { p.poison += amount; trace.step("$sourceName has infect, so instead of losing life ${if (p.you) "you get" else p.name + " gets"} $amount poison counter${if (amount > 1) "s" else ""} (${p.poison} total).", "702.90b", "120.3b"); state.outcomes += "${p.subject} ${p.v("has", "have")} ${p.poison} poison counter${if (p.poison > 1) "s" else ""}." }
                else { p.life = p.life?.minus(amount); trace.step("$sourceName deals $amount damage to ${if (p.you) "you" else p.name}, ${if (p.you) "and you lose" else "who loses"} $amount life${p.life?.let { " ($it)" } ?: ""}.", "120.3a"); state.outcomes += "${p.subject} ${p.v("takes", "take")} $amount damage." }
                val toxic = source?.takeIf { inCombatDamage }?.let { src -> Regex("""\bToxic (\d+)""").findAll(src.def.oracleText).sumOf { it.groupValues[1].toInt() } } ?: 0
                if (source?.commander == true && inCombatDamage) { val total = (p.commanderDamage[source.id] ?: 0) + amount; p.commanderDamage[source.id] = total; trace.step("${source.name} is a commander: ${if (p.you) "you have" else p.name + " has"} now been dealt $total combat damage by it this game (21 or more loses the game).", "903.10a"); state.outcomes += "${p.subject} ${p.v("has", "have")} taken $total commander damage from ${source.name}." }
                if (toxic > 0) { p.poison += toxic; trace.step("$sourceName has toxic $toxic, so ${if (p.you) "you also get" else p.name + " also gets"} $toxic poison counter${if (toxic > 1) "s" else ""} (${p.poison} total).", "702.164c", "120.3g"); state.outcomes += "${p.subject} ${p.v("has", "have")} ${p.poison} poison counter${if (p.poison > 1) "s" else ""}." } }
            is Ref.Obj -> { val o = state.obj(target.id)
                // 702.2b is about damage, not only combat damage: a fight or a ping from a deathtouch source is
                // just as lethal. Without this a deathtouch creature won a fight it should have traded.
                if (amount > 0 && !o.def.isPlaneswalker && source?.has("deathtouch") == true) o.dealtDeathtouchDamage = true
                if (o.def.isPlaneswalker) { val before = o.counters["loyalty"] ?: 0; o.counters["loyalty"] = maxOf(0, before - amount); trace.step("$sourceName deals $amount damage to ${o.name}, so $amount loyalty counters are removed from it (${o.counters["loyalty"]} left).", "306.8", "120.3c"); state.outcomes += "${o.name} has ${o.counters["loyalty"]} loyalty." }
                else if (infect || wither) { o.counters["-1/-1"] = (o.counters["-1/-1"] ?: 0) + amount; trace.step("$sourceName has ${if (infect) "infect" else "wither"}, so the $amount damage to ${o.name} is dealt as $amount -1/-1 counter${if (amount > 1) "s" else ""}; it's now ${o.power}/${o.toughness}.", if (infect) "702.90c" else "702.80a", "120.3d"); state.outcomes += "${o.name} has ${o.counters["-1/-1"]} -1/-1 counter(s)." }
                else {
                    o.damage += amount
                    trace.step("$sourceName deals $amount damage to ${o.name}; it now has ${o.damage} damage marked (toughness ${o.toughness ?: "?"}).", "120.3e")
                    val line = "${o.name} has ${o.damage} damage marked."
                    val slot = damageOutcome[o.id]
                    if (slot != null && slot < state.outcomes.size) state.outcomes[slot] = line
                    else { damageOutcome[o.id] = state.outcomes.size; state.outcomes += line }
                } }
            is Ref.Stack -> { state.unsupported += Unsupported(sourceName, "Damage can't be dealt to something on the stack."); return 0 }
        }
        // Lifelink is about damage, not only combat damage (702.15b): a fight, a Rabid Bite or a pinger with
        // lifelink gains its controller the life too. Combat damage gains it in the combat damage step itself.
        if (!inCombatDamage && amount > 0 && source != null && target !is Ref.Stack && source.has("lifelink")) {
            val c = state.player(source.controller)
            trace.step("${source.name} has lifelink, so ${c.subject.lowercase()} ${c.v("gains", "gain")} $amount life from the damage it dealt.", "702.15b")
            gainLife(c, amount)
        }
        if (source != null && target !is Ref.Stack) onEvent(GameEvent.DamageDealt(source, target, amount, inCombatDamage))
        if (source != null && inCombatDamage) monarchCombatDamage(source, target)
        return amount
    }

    /** Doubling Season and friends: how many counters actually land on [o] when [n] would be placed. */
    private fun countersPlaced(o: GameObject, n: Int, kind: String): Int {
        var out = n
        state.objects.values.filter { it.isOnBattlefield() }.flatMap { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.CounterMultiplier }.filter { it.anyPlayer || src.controller == o.controller }.map { src to it } }
            .forEach { (src, m) -> if (m.factor == 0 && (m.kind == null || m.kind.equals(kind, true))) { trace.step("${src.name} says ${m.kind?.let { "$it counters" } ?: "counters"} can't be put on ${o.name}, so the $out $kind counter${if (out == 1) "" else "s"} ${if (out == 1) "isn't" else "aren't"} placed.", "614.1a", "122.1"); out = 0 } }
        state.objects.values.filter { false }.flatMap { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.CounterMultiplier }.map { src to it } }
            .forEach { (src, m) -> trace.step("${src.name} replaces the counter placement: ${out * m.factor} $kind counters are put on ${o.name} instead of $out.", "614.1a", "614.6"); out *= m.factor }
        if (out > 0) state.objects.values.filter { it.isOnBattlefield() && it.controller == o.controller }.flatMap { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.CounterMultiplier }.filter { it.factor > 1 }.map { src to it } }
            .forEach { (src, m) -> trace.step("${src.name} replaces the counter placement: ${out * m.factor} $kind counters are put on ${o.name} instead of $out.", "614.1a", "614.6"); out *= m.factor }
        // Hardened Scales: one more of that kind, however many were coming.
        if (out > 0) state.objects.values.filter { it.isOnBattlefield() && it.controller == o.controller }.flatMap { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.CounterMultiplier }.filter { it.extra > 0 && (it.kind == null || it.kind.equals(kind, true)) }.map { src to it } }
            .forEach { (src, m) -> trace.step("${src.name} replaces the counter placement: ${out + m.extra} $kind counters are put on ${o.name} instead of $out.", "614.1a", "614.6"); out += m.extra }
        return out
    }

    private fun gainLife(p: Player, amount: Int) {
        var n = amount
        state.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.LifeGainMultiplier }.filter { it.anyPlayer || o.controller == p.id }.map { o to it } }
            .forEach { (o, m) -> trace.step("${o.name} replaces the life gain: ${p.subject.lowercase()} ${p.v("gains", "gain")} ${n * m.factor} life instead of $n.", "614.1a", "614.6"); n *= m.factor }
        if (n == 0) { trace.step("${p.subject} ${p.v("gains", "gain")} no life.", "119.3"); state.outcomes += "${p.subject} ${p.v("gains", "gain")} no life (0)."; return }
        p.life = p.life?.plus(n)
        trace.step("${p.subject} ${p.v("gains", "gain")} $n life${p.life?.let { " ($it)" } ?: ""}.", "119.3")
        state.outcomes += "${p.subject} ${p.v("gains", "gain")} $n life."
        onEvent(GameEvent.LifeGained(p.id, n))
    }

    private var inCombatDamage = false

    /** "If X would die, exile it instead" (614.1a) and Rest-in-Peace style effects: the replaced destination, with the source's name. */
    /** A "would die/would be put into a graveyard, instead …" effect that applies to [obj], and whatever else it does. */
    private class GyRepl(val zone: Zone, val by: String, val source: GameObject?, val alsoDo: Effect?)

    private fun graveyardReplacement(obj: GameObject, from: Zone): GyRepl? {
        if (from == Zone.BATTLEFIELD && obj.exileOnDeath != null) return GyRepl(Zone.EXILE, "${obj.exileOnDeath}'s \"exile it instead\"", null, null)
        for (o in state.objects.values) {
            // A permanent leaving alongside the one that would die still applies its replacement: replacement
            // effects are applied to the game state as it was before the event (616.1, 603.10a). Wrath of God
            // killing Kalitas and the creatures it exiles at once was answered as a plain trip to the graveyard.
            if (!o.isOnBattlefield() && o.id !in leavingTogether) continue
            for (e in o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }) {
                val r = (e as? StaticEffect.Replace)?.replacement as? Replacement.GraveyardReplacement ?: continue
                if (r.self && o !== obj) continue
                if (!r.self && !r.fromAnywhere && from != Zone.BATTLEFIELD) continue
                if (!r.self && !(if (from == Zone.BATTLEFIELD) state.matches(r.filter, obj, o.controller, o) else matchesLki(r.filter, obj, o.controller))) continue
                if (r.filter.other && o === obj) continue
                if (r.filter.controller == Who.OPPONENT && obj.owner == o.controller) continue
                val zone = when (r.instead) { "exile" -> Zone.EXILE; "hand" -> Zone.HAND; else -> Zone.LIBRARY }
                return GyRepl(zone, o.name, o, r.alsoDo)
            }
        }
        return null
    }

    /** What a "nonbasic lands are Mountains" permanent (Blood Moon) does to the nonbasic lands on the battlefield, said once. */
    /** Rest in Peace and friends were already out when the situation was described, so nothing can be sitting in a graveyard. */
    /** Painter's Servant: say up front what colour everything is, since it changes what can be targeted. */
    fun narratePainter() {
        val (src, c) = state.painter() ?: run {
            state.objects.values.firstOrNull { o -> o.isOnBattlefield() && o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.EverythingIsChosenColour } }?.let { o ->
                state.clarifications += Clarification("${o.name}'s colour", "${o.name} names a colour as it enters; the situation didn't say which. Say \"${o.name} naming blue\" if it matters.")
            }
            return
        }
        val colour = when (c) { 'W' -> "white"; 'U' -> "blue"; 'B' -> "black"; 'R' -> "red"; else -> "green" }
        trace.step("${src.name} names $colour, so every card, spell and permanent is $colour on top of its own colours — including ${src.name} itself and every card in every library, hand and graveyard.", "613.1e", "105.2a")
    }

    fun emptyGraveyardsUnderReplacement() {
        val keeper = state.objects.values.firstOrNull { src ->
            src.isOnBattlefield() && src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { e ->
                ((e as? StaticEffect.Replace)?.replacement as? Replacement.GraveyardReplacement)?.let { it.fromAnywhere && !it.self && it.instead == "exile" && it.filter.controller == null } == true
            }
        } ?: return
        val stuck = state.objects.values.filter { it.zone == Zone.GRAVEYARD }
        if (stuck.isEmpty()) return
        trace.step("${keeper.name} was already on the battlefield, so nothing can be in a graveyard: ${stuck.joinToString(", ") { it.name }} ${if (stuck.size == 1) "is" else "are"} in exile instead.", "614.1a", "614.6")
        stuck.forEach { it.zone = Zone.EXILE }
    }

    fun narrateLandTypeSetters(only: GameObject? = null, land: GameObject? = null) {
        val moons = state.objects.values.filter { it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.NonbasicLandsAreMountains } && (only == null || it === only) }
        for (moon in moons) for (land in state.objects.values.filter { it.isOnBattlefield() && "Land" in it.def.types && "Basic" !in it.def.supertypes && (land == null || it === land) }) {
            val saga = "Saga" in land.def.subtypes
            trace.step("${moon.name} makes ${land.name} a Mountain: it loses its other land types and every ability from its rules text${if (saga) ", chapter abilities included," else ""} and has only \"{T}: Add {R}\" (a type-changing effect, layer 4).${if (saga) " It's still an enchantment and a Saga; its lore counters stay, and with no chapter abilities it is neither sacrificed nor able to do anything." else ""}", "613.1d", "305.7", *(if (saga) arrayOf("714.4") else emptyArray()))
            state.outcomes += "${land.name} is a Mountain with no abilities (${moon.name})."
            state.unsupported.removeAll { it.what == land.name }
        }
    }

    /** What mana a permanent can make, for "what color mana can it make?"; a land under a Blood Moon makes only {R}. */
    /** Whether [playerId] controls a permanent called or subtyped [n] (Urza's Mine, Urza's Power-Plant). */
    private fun controlsNamed(playerId: String, n: String): Boolean {
        fun key(x: String) = x.lowercase().replace(Regex("""[^a-z]"""), "")
        return state.objects.values.any { o -> o.isOnBattlefield() && o.controller == playerId && (key(o.def.name) == key(n) || o.def.subtypes.any { key(it) == key(n) }) }
    }

    /** What a mana ability actually adds right now, once conditional "add … instead" clauses are settled; null if it isn't that shape. */
    private fun manaMade(effect: Effect, playerId: String, choice: String? = null): String? {
        val parts = (effect as? Effect.Seq)?.effects ?: listOf(effect)
        if (parts.firstOrNull() is Effect.AddManaDevotion) {
            val you = state.player(playerId)
            val colour = choice?.firstOrNull()?.uppercaseChar()?.takeIf { it in "WUBRG" }
                ?: you.devotion.entries.filter { it.value > 0 }.maxByOrNull { it.value }?.key
                ?: "WUBRG".maxByOrNull { devotionOf(playerId, it) }?.takeIf { devotionOf(playerId, it) > 0 }
                ?: return null
            val n = devotionOf(playerId, colour)
            return if (n == 0) "add no mana (devotion 0)" else "add " + "{$colour}".repeat(n)
        }
        (parts.firstOrNull() as? Effect.AddManaPer)?.let { per ->
            val n = state.objects.values.count { it.isOnBattlefield() && state.matches(per.filter, it, playerId) }
            return if (n == 0) "add no mana (nothing to count)" else "add ${per.symbol.repeat(n)}"
        }
        val base = parts.firstOrNull() as? Effect.AddMana ?: return null
        val instead = parts.drop(1).filterIsInstance<Effect.AddManaInstead>().lastOrNull() ?: return null
        return if (instead.required.all { controlsNamed(playerId, it) }) "add ${instead.text}" else "add ${base.text}"
    }

    /** Every untapped permanent [playerId] controls that makes mana, what each makes, and the total if every amount is a fixed one. */
    /** "How much does my Lightning Bolt cost?" — the printed cost plus every tax and reduction on the battlefield (601.2f). */
    fun spellCost(objId: String): String {
        val obj = state.obj(objId); val card = obj.def; val p = state.player(obj.controller)
        // A commander nobody named has no printed cost to add the tax to — the stand-in card's own cost is not
        // the card's — but the tax is the answer the question asks for, so give that and ask for the name.
        if (obj.commander && card.name.equals("a commander", true))
            return if (obj.commanderCasts > 0) "The commander tax adds {${2 * obj.commanderCasts}} to its mana cost (903.8), because it has been cast from the command zone ${obj.commanderCasts} time${if (obj.commanderCasts == 1) "" else "s"}. Name the card for the total."
                   else "It has not been cast from the command zone yet, so there is no commander tax (903.8): it costs its mana cost. Name the card for the total."
        val printed = card.manaCost ?: return "${card.name} has no mana cost, so it can't be cast for mana."
        val taxes = state.objects.values.filter { it.isOnBattlefield() }
            .flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CostTax>()
                .filter { t -> (t.whose == null || (t.whose == Who.YOU) == (o.controller == obj.controller)) && spellMatches(t.filter, card) }.map { o to it } }
        val commanderTax = if (obj.commander && obj.commanderCasts > 0) 2 * obj.commanderCasts else 0
        // Blasphemous Act: the spell's own reduction, which can only take the generic part away (601.2f).
        val self = selfReduction(obj)
        val tax = taxes.sumOf { it.second.amount } + commanderTax - self.first
        val total = maxOf(colouredPips(printed), card.manaValue.toInt() + tax)
        if (taxes.isEmpty() && commanderTax == 0 && self.first == 0) return "${card.name} costs $printed — $total mana. Nothing on the battlefield changes it." +
            // "I have 3 lands untapped, can I pay for Cryptic Command?": the cost alone doesn't answer that.
            (p.mana?.let { avail -> if (avail >= total) " ${p.subject} ${p.v("has", "have")} $avail available, enough." else " ${p.subject} ${p.v("has", "have")} only $avail available, so it can't be cast." } ?: "")
        val parts = taxes.map { (o, t) -> "${o.name} makes it cost {${kotlin.math.abs(t.amount)}} ${if (t.amount < 0) "less" else "more"}" } +
            (if (self.first > 0) listOf("its own text makes it cost {${self.first}} less (${self.second})") else emptyList()) +
            (if (commanderTax > 0) listOf("the commander tax adds {$commanderTax}") else emptyList())
        trace.step("${parts.joinToString(" and ")}. ${card.name}'s total cost is $printed ${if (tax < 0) "less" else "plus"} {${kotlin.math.abs(tax)}}: $total mana in all. The extra is generic, so it can be paid with any colour.", "601.2f", "118.7")
        val avail = p.mana
        return "${card.name} costs $printed ${if (tax < 0) "minus" else "plus"} {${kotlin.math.abs(tax)}} (${parts.joinToString("; ")}) — $total mana in all." +
            (if (avail != null) if (avail >= total) " ${p.subject} ${p.v("has", "have")} $avail available, enough." else " ${p.subject} ${p.v("has", "have")} only $avail available, so it can't be cast." else "")
    }

    /** The coloured pips of a mana cost: a reduction can never take the cost below them (601.2f). */
    private fun colouredPips(cost: String) = Regex("""\{([^}]+)\}""").findAll(cost).count { !Regex("""^\d+$|^X$""").matches(it.groupValues[1]) }

    /** "~ costs {1} less to cast for each creature on the battlefield": how much less, and why. */
    private fun selfReduction(obj: GameObject): Pair<Int, String> {
        val r = obj.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.SelfCostReduction>().firstOrNull() ?: return 0 to ""
        val per = r.per ?: return r.amount to "a flat {${r.amount}}"
        val n = when (per) {
            is CountExpr.Permanents -> state.objects.values.count { it.isOnBattlefield() && state.matches(per.filter, it, obj.controller) }
            is CountExpr.CardTypesInGraveyards -> state.cardTypesInGraveyards().size
            is CountExpr.YourLifeTotal -> state.player(obj.controller).life ?: 0
            is CountExpr.Unknown -> return 0 to ""
        }
        val what = (per as? CountExpr.Permanents)?.filter?.raw ?: "of them"
        return r.amount * n to "$n ${if (n == 1 || what.endsWith("s")) what else what + "s"}".trim()
    }

    fun manaAvailable(playerId: String): String {
        val p = state.player(playerId)
        val sources = state.objects.values.filter { it.isOnBattlefield() && it.controller == playerId && it.tapped != true }
            .mapNotNull { o ->
                val a = o.def.abilities.filterIsInstance<ActivatedAbility>().firstOrNull { ab -> isManaEffect(ab.effect) } ?: return@mapNotNull null
                if (a.cost.contains("{T}") && o.def.isCreature && o.summoningSick == true && !state.hasKeyword(o, "haste")) return@mapNotNull Triple(o, "summoning sick, so it can't be tapped for mana yet", null as Int?)
                val moon = state.objects.values.firstOrNull { m -> m.isOnBattlefield() && m.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.NonbasicLandsAreMountains } }
                if (moon != null && "Land" in o.def.types && "Basic" !in o.def.supertypes) return@mapNotNull Triple(o, "{R} (it's a Mountain under ${moon.name})", 1)
                val made = manaMade(a.effect, playerId) ?: (a.effect as? Effect.AddMana)?.let { "add ${it.text}" } ?: ((a.effect as? Effect.Seq)?.effects?.firstOrNull() as? Effect.AddMana)?.let { "add ${it.text}" } ?: return@mapNotNull null
                if (made.startsWith("add no mana")) return@mapNotNull Triple(o, "nothing right now", 0)
                val text = made.removePrefix("add ")
                Triple(o, text, Regex("""\{[^}]+\}""").findAll(text).count().takeIf { it > 0 } ?: 1)
            }
        val stated = p.mana?.takeIf { it > 0 }
        if (sources.isEmpty()) return (if (stated != null) "${p.subject} ${p.v("has", "have")} $stated mana available (the situation says so; no permanent it named makes mana)."
                                       else "${p.subject} ${p.v("has", "have")} no untapped permanent with a mana ability the engine recognises.")
        val usable = sources.filter { it.third != null }
        val total = usable.sumOf { it.third ?: 0 }
        return "${p.subject} can make ${total + (stated ?: 0)} mana right now: " + sources.joinToString("; ") { (o, what, n) -> "${o.name} → $what" + (if (n == null) "" else "") } +
            (if (stated != null) "; plus the $stated the situation gives from permanents it didn't name" else "") + "."
    }

    fun manaOptions(objectId: String): String {
        val o = state.obj(objectId)
        val moon = state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.NonbasicLandsAreMountains } }
        if (moon != null && "Land" in o.def.types && "Basic" !in o.def.supertypes) return "${o.name} is a Mountain under ${moon.name}, so it taps for {R} and nothing else."
        val mana = o.def.abilities.filterIsInstance<ActivatedAbility>().filter { a -> isManaEffect(a.effect) }
        if (mana.isEmpty()) return "${o.name} has no mana ability the engine recognises."
        // "Add {B} for each Swamp you control" is more useful with the count worked out for the board described.
        return "${o.name} can make: " + mana.joinToString("; ") { a ->
            val now = manaMade(a.effect, o.controller)?.removePrefix("add ")?.takeIf { it.isNotBlank() && !it.startsWith("no mana") }
            a.text.replace("~", o.name) + (if (now != null && now != a.text) " \u2014 $now right now" else "")
        }
    }

    private fun move(obj: GameObject, to: Zone, text: String, vararg rules: String) {
        if (to == Zone.GRAVEYARD) graveyardReplacement(obj, obj.zone)?.let { r ->
            val from = obj.zone
            trace.step("$text But ${r.by} replaces that: instead of going to the graveyard, ${obj.name} is put into ${zoneName(r.zone, obj)}. The graveyard event never happens, so nothing triggers on it.", *rules, "614.1a", "614.6")
            moveRaw(obj, r.zone); state.outcomes += "${obj.name}: ${zoneName(from, obj)} → ${zoneName(r.zone, obj)} (replaced by ${r.by})."
            if (from == Zone.BATTLEFIELD) onEvent(GameEvent.LeavesBattlefield(obj))
            if (r.alsoDo != null && r.source != null) {
                // The rest of the replacement effect ("… and create a 2/2 black Zombie") happens as part of the same event, not as a trigger.
                trace.step("${r.by}'s replacement effect also does the rest of what it says; it's one event, not a separate trigger.", "614.1", "616.1")
                applyEffect(r.alsoDo, StackItem(state.newStackId(), StackKind.TRIGGERED, r.source.controller, r.source, r.alsoDo, emptyList(), emptyMap(), r.by))
            }
            return
        }
        val from = obj.zone
        // Undying and persist are read while it is still on the battlefield (603.10e).
        val hadUndying = from == Zone.BATTLEFIELD && to == Zone.GRAVEYARD && state.hasKeyword(obj, "undying")
        val hadPersist = from == Zone.BATTLEFIELD && to == Zone.GRAVEYARD && state.hasKeyword(obj, "persist")
        moveRaw(obj, to)
        trace.step(text, *rules)
        state.outcomes += "${obj.name}: ${zoneName(from, obj)} → ${zoneName(to, obj)}."
        if (from == Zone.BATTLEFIELD) {
            if (to == Zone.GRAVEYARD) { onEvent(GameEvent.Dies(obj)); undyingOrPersist(obj, hadUndying, hadPersist) } else onEvent(GameEvent.LeavesBattlefield(obj))
            // Whatever was attached to it, or it was attached to, is checked by state-based actions (704.5m/n).
        }
    }

    private fun afterResolution() {
        stateBasedActions()
        trace.step("The active player receives priority.", "117.3b")
    }

    // ---- targeting restrictions (hexproof, shroud, protection, ward) ----------------------------

    private val colorNames = mapOf('W' to "white", 'U' to "blue", 'B' to "black", 'R' to "red", 'G' to "green")

    private fun qualitiesOf(def: CardDef): Set<String> = def.colors.mapNotNull { colorNames[it] }.toSet() + def.types.map { it.lowercase() } + def.types.map { it.lowercase() + "s" }

    /** Why [ref] can't be targeted by a spell/ability from [source] controlled by [controller], or null if it can. */
    private fun targetingProblem(source: GameObject, controller: String, ref: Ref): Pair<String, String>? {
        if (ref is Ref.Player && ref.id != controller && state.objects.values.any { it.isOnBattlefield() && it.controller == ref.id && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.PlayerHexproof } })
            return "${state.nameOf(ref)} ${if (state.player(ref.id).you) "have" else "has"} hexproof (${state.objects.values.first { it.isOnBattlefield() && it.controller == ref.id && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.PlayerHexproof } }.name}) and can't be the target of spells or abilities an opponent controls" to "702.11c"
        val o = (ref as? Ref.Obj)?.let { state.objects[it.id] } ?: return null
        if (!o.isOnBattlefield()) return null
        if (o.has("shroud")) return "${o.name} has shroud and can't be the target of spells or abilities" to "702.18a"
        if (o.has("hexproof") && o.controller != controller) return "${o.name} has hexproof and can't be the target of spells or abilities its controller's opponents control" to "702.11b"
        val prots = state.protections(o)
        if (prots.isNotEmpty()) {
            val qualities = qualitiesOf(source.def)
            val isSpell = source.def.isInstantOrSorcery || source.zone == Zone.STACK
            val hit = prots.firstOrNull { it == "everything" || it in qualities || (it == "colored spells" && isSpell && source.def.colors.isNotEmpty()) || (it == "spells" && isSpell) }
            if (hit != null) return "${o.name} has protection from $hit, so it can't be targeted by ${if (source.def.isInstantOrSorcery || source.zone == Zone.STACK) "that spell" else "an ability from that source"}" to "702.16b"
        }
        return null
    }

    private val keywordTriggerRules = mapOf("Exalted" to "702.83a", "Prowess" to "702.108a", "Ward" to "702.21a", "Exploit" to "702.110a", "Mobilize" to "702.181a")

    private val castingKeywordRules = mapOf("kicker" to "702.33a", "flashback" to "702.34a", "madness" to "702.35a", "convoke" to "702.51a", "affinity" to "702.41a", "suspend" to "702.62a", "morph" to "702.37a", "improvise" to "702.126a", "cumulative upkeep" to "702.24a", "devoid" to "702.114a", "changeling" to "702.73a", "partner" to "702.124a", "evoke" to "702.74a", "echo" to "702.30a", "foretell" to "702.143a", "cascade" to "702.85a", "bestow" to "702.103a", "disguise" to "702.168a", "escape" to "702.138a", "mutate" to "702.140a", "companion" to "702.139a", "split second" to "702.61a", "buyback" to "702.27a", "overload" to "702.96a", "surge" to "702.117a", "emerge" to "702.119a", "spectacle" to "702.137a", "jump-start" to "702.133a", "retrace" to "702.81a", "delve" to "702.66a", "prototype" to "702.160a", "casualty" to "702.153a", "offspring" to "702.175a", "gift" to "702.174a", "impending" to "702.176a", "harmonize" to "702.180a")
    private val castingKeywordNotes = mapOf("kicker" to "its controller may pay the kicker cost as an additional cost while casting", "flashback" to "it may be cast from the graveyard for its flashback cost, then is exiled", "madness" to "it may be cast for its madness cost as it's discarded", "convoke" to "creatures may be tapped to help pay for it", "affinity" to "it costs less for each matching permanent", "suspend" to "it may be exiled with time counters instead of cast", "morph" to "it may be cast face down as a 2/2 for {3}", "improvise" to "artifacts may be tapped to help pay for it", "cumulative upkeep" to "at the beginning of its controller's upkeep an age counter is added and the cost paid per counter or it's sacrificed", "devoid" to "it is colorless", "changeling" to "it is every creature type", "partner" to "a deck can have two commanders with partner", "evoke" to "it may be cast for its evoke cost, then sacrificed when it enters", "echo" to "at the beginning of its controller's next upkeep they pay the echo cost or sacrifice it", "foretell" to "it may have been exiled face down for {2} earlier and cast later for its foretell cost", "cascade" to "when cast, exile cards from the top of the library until a cheaper nonland card is found and it may be cast free", "bestow" to "it may be cast as an Aura for its bestow cost", "disguise" to "it may be cast face down as a 2/2 with ward {2} for {3}", "escape" to "it may be cast from the graveyard by paying its escape cost", "mutate" to "it may be cast for its mutate cost to merge with a non-Human creature", "companion" to "it may start outside the game and be put into hand for {3}", "split second" to "while it's on the stack players can't cast spells or activate non-mana abilities", "buyback" to "its buyback cost may be paid to return it to hand as it resolves", "overload" to "it may be cast for its overload cost, changing 'target' to 'each'", "surge" to "it costs its surge cost if another spell was cast this turn", "emerge" to "it may be cast by sacrificing a creature for a reduced cost", "spectacle" to "it may be cast for its spectacle cost if an opponent lost life this turn", "jump-start" to "it may be cast from the graveyard by discarding a card", "retrace" to "it may be cast from the graveyard by discarding a land", "delve" to "cards may be exiled from the graveyard to pay generic mana", "prototype" to "it may be cast smaller for its prototype cost", "casualty" to "a creature may be sacrificed as it's cast to copy it", "offspring" to "its offspring cost may be paid to create a 1/1 token copy", "gift" to "a gift may be promised to an opponent as it's cast", "impending" to "it may be cast for its impending cost as a non-creature with time counters", "harmonize" to "it may be cast from the graveyard, tapping a creature to reduce the cost")

    /** Ward: targeting an opponent's warded permanent triggers "counter unless you pay [cost]" (702.21a). */
    private fun wardTriggers(item: StackItem) {
        for (ref in item.targets) {
            val o = (ref as? Ref.Obj)?.let { state.objects[it.id] } ?: continue
            val cost = state.wardCost(o) ?: continue
            if (o.controller == item.controller) continue
            val counterSpec = TargetSpec(ObjFilter(setOf(Kind.SPELL, Kind.ABILITY), raw = "spell or ability"), "that spell or ability")
            val effect = Effect.UnlessPays(Effect.Counter(counterSpec), Who.CONTROLLER_OF_TARGET, cost)
            val ward = StackItem(state.newStackId(), StackKind.TRIGGERED, o.controller, o, effect, listOf(Ref.Stack(item.id)), mapOf(item.id to Zone.STACK), "Ward $cost")
            state.stack += ward
            val caster = state.player(item.controller)
            trace.step("${o.name} has ward $cost: it became the target of a spell or ability an opponent controls, so its ward ability triggers and goes on the stack above ${item.describe}. When it resolves, ${item.describe} is countered unless ${caster.subject.lowercase()} ${caster.v("pays", "pay")} $cost.", "702.21a", "603.3")
        }
    }

    // ---- targets ---------------------------------------------------------------------------

    /**
     * When the user didn't say what a one-target spell targets, and exactly one thing in the
     * situation is a legal target, use it and say so. Several candidates: ask instead.
     */
    private fun inferTarget(what: String, spec: TargetSpec, controller: String, harmful: Boolean = false, source: GameObject? = null, beneficial: Boolean = false): List<Ref>? {
        if (!spec.filter.verifiable) return null
        val candidates = mutableListOf<Ref>()
        for (o in state.objects.values) if (o.zone == Zone.BATTLEFIELD || o.zone == Zone.STACK) { val r = Ref.Obj(o.id); if (filterMatches(spec.filter, r, controller, source)) candidates += r }
        for (s in state.stack) if (s.kind != StackKind.SPELL) { val r = Ref.Stack(s.id); if (filterMatches(spec.filter, r, controller)) candidates += r }
        if (Kind.PLAYER in spec.filter.kinds) state.players.forEach { candidates += Ref.Player(it.id) }
        var distinct = candidates.distinctBy { when (it) { is Ref.Obj -> "o:" + it.id; is Ref.Stack -> "s:" + it.id; is Ref.Player -> "p:" + it.id } }
        // Hexproof, shroud, protection: something that fits the words but can't be targeted isn't a candidate.
        if (source != null) {
            val untargetable = distinct.mapNotNull { r -> targetingProblem(source, controller, r)?.let { Triple(r, it.first, it.second) } }
            if (untargetable.isNotEmpty()) {
                distinct = distinct.filter { r -> untargetable.none { it.first == r } }
                val rules = untargetable.map { it.third }.distinct().toTypedArray()
                if (distinct.isEmpty()) { trace.step("${untargetable.joinToString("; ") { (r, why, _) -> "${state.nameOf(r)} fits \"${spec.raw}\" but can't be targeted: $why" }}. There is no legal target for $what.", "115.1", *rules); return emptyList() }
                trace.step("${untargetable.joinToString("; ") { (r, why, _) -> "${state.nameOf(r)} fits \"${spec.raw}\" but can't be targeted: $why" }}, so it isn't a candidate.", *rules)
            }
        }
        // Pumping, protecting, untapping: aimed at your own things when an opponent's also qualify; in combat, at the one that's fighting.
        if (beneficial && !harmful && distinct.size > 1) {
            val mine = distinct.filter { r -> r is Ref.Obj && state.objects[r.id]?.controller == controller }
            if (mine.isNotEmpty() && mine.size < distinct.size) distinct = mine
            if (distinct.size > 1) {
                // Something on the stack is already aiming at one of them: that's the one being saved.
                val underThreat = distinct.filter { r -> r is Ref.Obj && state.stack.any { s -> s.controller != controller && s.targets.any { t -> t is Ref.Obj && t.id == r.id } } }
                if (underThreat.size == 1) distinct = underThreat
            }
            if (distinct.size > 1) { val fighting = distinct.filter { r -> r is Ref.Obj && state.objects[r.id]?.let { it.attacking != null || it.blocking != null } == true }; if (fighting.size == 1) distinct = fighting }
            if (distinct.size == 1) { state.assumptions += "$what targets ${state.nameOf(distinct[0])}: of the legal targets, it's the one ${state.player(controller).subject.lowercase()} would help (own creature${if (state.objects[(distinct[0] as Ref.Obj).id]?.let { it.attacking != null || it.blocking != null } == true) ", in combat" else ""}); say so if it's another."; return distinct }
        }
        // Destroying, exiling or damaging: nobody aims that at their own things when an opponent's qualify.
        if (harmful && distinct.size > 1) {
            val theirs = distinct.filter { r -> when (r) { is Ref.Obj -> state.objects[r.id]?.controller != controller; is Ref.Player -> r.id != controller; is Ref.Stack -> state.stackItem(r.id)?.controller != controller } }
            if (theirs.isNotEmpty() && theirs.size < distinct.size) { distinct = theirs; if (theirs.size == 1) { state.assumptions += if (theirs[0] is Ref.Player) "$what targets ${state.nameOf(theirs[0])} (\"${spec.raw}\" with no target named; assuming its controller's opponent)." else "$what targets ${state.nameOf(theirs[0])}: of the legal targets for \"${spec.raw}\", it's the only one an opponent controls."; return theirs } }
        }
        // The only thing that fits is one of your own, and the effect would hurt it: that's a choice, not a default.
        if (harmful && distinct.size == 1 && (distinct[0] as? Ref.Obj)?.let { state.objects[it.id]?.controller == controller } == true) {
            state.clarifications += Clarification("$what's target", "$what needs a target (${spec.raw}), and the only one is ${state.nameOf(distinct[0])}, which ${state.player(controller).subject.lowercase()} ${state.player(controller).v("controls", "control")}. Is that the target?")
            trace.step("The only legal target for $what is ${state.nameOf(distinct[0])}, ${state.player(controller).possessive} own. Nothing here says ${state.player(controller).subject.lowercase()} would aim at it, so the target isn't assumed.", "115.1")
            return null
        }
        return when (distinct.size) {
            1 -> { state.assumptions += "$what targets ${state.nameOf(distinct[0])}, the only legal target for \"${spec.raw}\" in this situation."; distinct }
            0 -> null
            else -> {
                // Nothing but players to choose from (or "any target" with only players around): the opponent is the sensible default.
                val opp = state.opponentsOf(controller).singleOrNull()
                if (opp != null && distinct.all { it is Ref.Player }) { state.assumptions += "$what targets ${state.nameOf(Ref.Player(opp.id))} (\"${spec.raw}\" with no target named; assuming its controller's opponent)."; listOf(Ref.Player(opp.id)) }
                else { state.clarifications += Clarification("$what's target", "$what needs a target (${spec.raw}); it could be ${distinct.joinToString(", ") { state.nameOf(it) }}. Which?"); emptyList<Ref>().also { return null } }
            }
        }
    }

    private fun zonesOf(targets: List<Ref>): Map<String, Zone> = targets.filterIsInstance<Ref.Obj>().associate { it.id to state.obj(it.id).zone } +
        targets.filterIsInstance<Ref.Stack>().associate { it.id to Zone.STACK }

    private fun checkTargetsAtCast(item: StackItem) {
        val specs = item.effect?.targets() ?: return
        item.targets.zip(specs).forEach { (ref, spec) ->
            if (!spec.filter.verifiable) state.clarifications += Clarification("target legality", "Can't verify \"${spec.raw}\" for ${state.nameOf(ref)}: unrecognised qualifier(s) ${spec.filter.unknownWords.joinToString()}. Assuming it's a legal target.")
            // A spell on the stack named as the target of something that wants a permanent: the asker almost
            // certainly means the permanent it will become, so say what the words as given would do.
            else if (ref is Ref.Stack && Kind.SPELL !in spec.filter.kinds && !filterMatches(spec.filter, ref, item.controller)) {
                trace.step("${state.nameOf(ref)} is still a spell on the stack, and \"${spec.raw}\" names a permanent, so it isn't a legal target: a creature spell isn't a creature until it resolves. ${item.describe} will do nothing. If it was meant to answer the permanent, let the spell resolve first.", "109.2", "601.2c", "608.2b")
                state.outcomes += "${item.describe} can't target ${state.nameOf(ref)} while it's still a spell on the stack."
            }
            // A spell on the stack that isn't the kind the words name ("creature spell" aimed at a Lightning
            // Bolt): an illegal target, said at the point the spell is cast rather than only when it fizzles.
            else if (ref is Ref.Stack && Kind.SPELL in spec.filter.kinds && !filterMatches(spec.filter, ref, item.controller)) {
                val what = if (Regex("""^(?:an?|the|target) """).containsMatchIn(spec.raw)) spec.raw else (if (spec.raw.first().lowercaseChar() in "aeiou") "an " else "a ") + spec.raw
                trace.step("${state.nameOf(ref)} isn't $what, so it isn't a legal target for ${item.describe} (601.2c).", "601.2c", "115.1")
                state.outcomes += "${item.describe} can't target ${state.nameOf(ref)} (it isn't $what)."
            }
            else if (!filterMatches(spec.filter, ref, item.controller)) trace.step("Note: ${state.nameOf(ref)} doesn't look like a legal target for \"${spec.raw}\" (601.2c); proceeding as described.", "601.2c")
        }
    }

    private fun isTargetLegal(item: StackItem, ref: Ref): Boolean {
        // An object target that changed zones is a new object and never legal, whatever the spell says about it (608.2b, 400.7).
        if (ref is Ref.Obj) { val o = state.objects[ref.id] ?: return false; val z = item.targetZones[ref.id]; if (z != null && o.zone != z) return false }
        val spec = specFor(item, ref) ?: return true
        return when (ref) {
            is Ref.Player -> !state.player(ref.id).lost
            // A spell or ability on the stack has to fit the words too: without this Essence Scatter countered a
            // Lightning Bolt and Negate countered a Grizzly Bears, and the answer said it worked.
            is Ref.Stack -> state.stackItem(ref.id) != null && (!spec.filter.verifiable || filterMatches(spec.filter, ref, item.controller))
            is Ref.Obj -> { val o = state.objects[ref.id] ?: return false; (item.targetZones[ref.id] ?: o.zone) == o.zone && (!spec.filter.verifiable || filterMatches(spec.filter, ref, item.controller)) && targetingProblem(item.source, item.controller, ref) == null }
        }
    }

    private fun whyIllegal(item: StackItem, ref: Ref): String = when (ref) {
        // Still on the stack but not what the spell asked for: say that, not that it left.
        is Ref.Stack -> if (state.stackItem(ref.id) == null) "${state.nameOf(ref)} has left the stack"
                        else specFor(item, ref)?.raw?.let { raw -> "${state.nameOf(ref)} isn't " + (if (Regex("""^(?:an?|the|target) """).containsMatchIn(raw)) raw else (if (raw.first().lowercaseChar() in "aeiou") "an " else "a ") + raw) }
                            ?: "${state.nameOf(ref)} isn't a legal target"
        is Ref.Obj -> { val o = state.objects[ref.id]; if (o == null || o.zone != item.targetZones[ref.id]) "${state.nameOf(ref)} left ${zoneName(item.targetZones[ref.id] ?: Zone.BATTLEFIELD, o)}" else targetingProblem(item.source, item.controller, ref)?.first ?: "${state.nameOf(ref)} no longer matches \"${specFor(item, ref)?.raw}\"" }
        is Ref.Player -> "${state.nameOf(ref)} has left the game"
    }

    /** The item's effect with chosen modes substituted (700.2). */
    private fun effectiveEffect(item: StackItem): Effect? = substituteModes(item.effect, item)
    /** A Modal, possibly behind a rider sentence that makes the spell a Seq, with the chosen modes in its place. */
    private fun substituteModes(e: Effect?, item: StackItem): Effect? = when {
        e is Effect.Modal -> item.modes.mapNotNull { i -> e.modes.getOrNull(i - 1) }.let { if (it.isEmpty()) e else Effect.Seq(it) }
        e is Effect.Seq && e.effects.any { it is Effect.Modal } -> Effect.Seq(e.effects.map { substituteModes(it, item) ?: it })
        else -> e
    }
    private fun specFor(item: StackItem, ref: Ref): TargetSpec? { val specs = effectiveEffect(item)?.targets() ?: return null; val i = item.targets.indexOf(ref); return specs.getOrNull(i) }

    private inline fun forEachLegalTarget(item: StackItem, spec: TargetSpec, block: (Ref) -> Unit) {
        val specs = effectiveEffect(item)?.targets() ?: emptyList()
        val idx = specs.indexOf(spec)
        val ref = item.targets.getOrNull(idx) ?: run { state.unsupported += Unsupported(item.describe, "No target was given for \"${spec.raw}\"."); return }
        if (isTargetLegal(item, ref)) block(ref) else trace.step("${state.nameOf(ref)} is an illegal target now, so that part of the effect doesn't affect it.", "608.2b")
    }

    fun filterMatches(f: ObjFilter, ref: Ref, controller: String, source: GameObject? = null): Boolean = when (ref) {
        is Ref.Player -> Kind.PLAYER in f.kinds
        is Ref.Stack -> { val s = state.stackItem(ref.id) ?: return false; if (s.kind == StackKind.SPELL) filterMatchesSpell(f, s, controller) else Kind.ABILITY in f.kinds }
        is Ref.Obj -> {
            val o = state.objects[ref.id] ?: return false
            if (o.zone == Zone.STACK) { val s = state.stack.firstOrNull { it.source.id == o.id }; s != null && filterMatchesSpell(f, s, controller) }
            else if (!o.isOnBattlefield()) Kind.CARD in f.kinds
            else state.matches(f, o, controller, source)
        }
    }

    private fun filterMatchesSpell(f: ObjFilter, s: StackItem, controller: String): Boolean {
        if (s.kind != StackKind.SPELL) return Kind.ABILITY in f.kinds
        // A creature spell on the stack is not a creature (109.2): only a filter that says "spell" can name it.
        // Without this, "I cast Grizzly Bears and they Doom Blade it" let Doom Blade target the Bears while it was
        // still a spell, resolve doing nothing at all, and leave the Bears alive without a word about it.
        if (Kind.SPELL !in f.kinds) return false
        val d = s.source.def
        val notOk = f.notKinds.none { k -> when (k) { Kind.CREATURE -> d.isCreature; Kind.ARTIFACT -> "Artifact" in d.types; Kind.ENCHANTMENT -> "Enchantment" in d.types; Kind.LAND -> "Land" in d.types; else -> false } }
        // "Creature spell" is both a spell and a creature card: saying it is a spell is not enough, or Essence
        // Scatter countered a Lightning Bolt and Negate countered a Grizzly Bears.
        // SPELL, ABILITY and PLAYER say what kind of thing may be chosen, not what card type it is.
        val typeKinds = f.kinds - Kind.SPELL - Kind.ABILITY - Kind.PLAYER
        val kindOk = typeKinds.isEmpty() || typeKinds.any { k -> when (k) {
            Kind.CREATURE -> d.isCreature
            Kind.ARTIFACT -> "Artifact" in d.types
            Kind.ENCHANTMENT -> "Enchantment" in d.types
            Kind.LAND -> "Land" in d.types
            Kind.PLANESWALKER -> "Planeswalker" in d.types
            Kind.BATTLE -> "Battle" in d.types
            Kind.PERMANENT -> !d.isInstantOrSorcery
            Kind.CARD -> true
            else -> false
        } }
        val spellTypes = f.subtypes.filter { it in setOf("instant", "sorcery") }
        val spellTypeOk = spellTypes.isEmpty() || spellTypes.any { t -> d.types.any { it.equals(t, true) } }
        // Subtypes on a spell filter were never checked, so "whenever you cast an Aura, Equipment, or Vehicle
        // spell" drew a card off a Grizzly Bears.
        fun hasSub(t: String) = d.subtypes.any { it.equals(t, true) } || (d.changeling && d.isCreature)
        val subs = f.subtypes.filter { it !in setOf("instant", "sorcery") }
        val subOk = subs.isEmpty() || (if (f.subtypesAny) subs.any { hasSub(it) } else subs.all { hasSub(it) })
        val notSubOk = f.notSubtypes.none { hasSub(it) }
        val ctrlOk = when (f.controller) { Who.YOU -> s.controller == controller; Who.OPPONENT -> s.controller != controller; else -> true }
        return notOk && kindOk && spellTypeOk && subOk && notSubOk && ctrlOk
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun resolveWho(who: Who, item: StackItem): Player? = when (who) {
        Who.YOU -> state.player(item.controller)
        Who.OPPONENT -> state.opponentsOf(item.controller).singleOrNull() ?: run { state.clarifications += Clarification("which opponent", "${item.describe} refers to an opponent and there are several."); null }
        Who.THAT_PLAYER -> item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { state.player(it.id) } ?: causingPlayer(item)
        Who.TARGET_PLAYER -> item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { state.player(it.id) } ?: run {
            // "Target player draws three cards" with no target named: you'd aim that at yourself.
            // A modal spell's "target player" belongs to the mode chosen, so the whole Modal never looked helpful
            // and "Target player gains 7 life" was aimed at the opponent.
            if (helpsThePlayer(effectiveEffect(item))) { val you = state.player(item.controller); state.assumptions += "${item.describe} targets ${if (you.you) "you" else you.name} (\"target player\" wasn't specified; assuming its controller, since it helps that player)."; return@run you }
            // "Target player loses 1 life" with no target named: assume the one opponent (the sensible choice), and say so.
            val opp = state.opponentsOf(item.controller).singleOrNull()
            if (opp != null) state.assumptions += "${item.describe} targets ${if (opp.you) "you" else opp.name} (\"target player\" wasn't specified; assuming the opponent)."
            // Several opponents and nothing says which: with one it is assumed above, with two it is a real choice,
            // and leaving it out silently made "target player loses 1 life" vanish from the answer.
            else state.clarifications += Clarification("${item.describe}'s target player",
                "${item.describe} says \"target player\" and the situation doesn't say which (${state.opponentsOf(item.controller).joinToString(" or ") { if (it.you) "you" else it.name }}); what it does to that player is left out. Say who it targets for a complete answer.")
            opp
        }
        Who.CONTROLLER_OF_TARGET -> item.targets.firstOrNull()?.let { ref -> when (ref) { is Ref.Obj -> state.player(state.obj(ref.id).controller); is Ref.Stack -> state.stackItem(ref.id)?.let { state.player(it.controller) }; is Ref.Player -> state.player(ref.id) } }
        Who.ANY_PLAYER -> null
        Who.EACH_PLAYER, Who.EACH_OPPONENT -> resolvePlayers(who, item).singleOrNull()
    }

    /** Every player an effect applies to: "each player", "each opponent", or the single player [resolveWho] finds. */
    private fun resolvePlayers(who: Who, item: StackItem): List<Player> = when (who) {
        Who.EACH_PLAYER -> state.players
        Who.EACH_OPPONENT -> state.opponentsOf(item.controller)
        else -> listOfNotNull(resolveWho(who, item))
    }

    /** For "that player" in a "whenever an opponent casts a spell" trigger: the player who caused it. */
    private fun causingPlayer(item: StackItem): Player? {
        if (item.kind != StackKind.TRIGGERED) return null
        item.causedBy?.let { return state.player(it) }
        val trig = (item.source.def.abilities.filterIsInstance<TriggeredAbility>().firstOrNull { it.text == item.text })?.trigger
        return if (trig is Trigger.SpellCast) (if (trig.who == Who.OPPONENT) state.opponentsOf(item.controller).singleOrNull() else null) else null
    }

    private fun objOf(ref: Ref): GameObject? = (ref as? Ref.Obj)?.let { state.objects[it.id] }
    private fun describeTargets(targets: List<Ref>) = if (targets.isEmpty()) "" else " targeting " + targets.joinToString(" and ") { state.nameOf(it) }
    private fun describe(effect: Effect, item: StackItem): String = when (effect) {
        is Effect.Draw -> "draw ${if (effect.x) "X" else effect.count.toString()} card${if (effect.count > 1 || effect.x) "s" else ""}"
        is Effect.Damage -> "deal ${effect.amount} damage to ${effect.target.raw}"
        is Effect.CounterThatSpell -> "counter that spell"; is Effect.Counter -> "counter ${effect.target.raw}"; is Effect.Fight -> "${effect.mine.raw} fights ${effect.theirs.raw}"; is Effect.DealsPowerTo -> "${effect.mine.raw} deals damage equal to its power to ${effect.theirs.raw}"; is Effect.RedirectToSelf -> "change a target of ${effect.target.raw} to ${item.source.name}"; is Effect.Blink -> "exile ${effect.target.raw}, then return it to the battlefield under ${if (effect.ownersControl) "its owner's" else "your"} control"; is Effect.CopySpell -> "copy ${effect.target.raw}"; is Effect.StormCopy -> "copy it for each spell cast before it this turn"; is Effect.Monstrosity -> "become monstrous with ${effect.amount} +1/+1 counters"; is Effect.ReflectPrevented -> "deal damage prevented this way back to ${if (effect.toCreature) "that creature" else "the source's controller"}"; is Effect.MoveSourceCounters -> "put its ${effect.kind} counters on ${effect.target.raw}"; is Effect.Evolve -> "put a +1/+1 counter on it if the creature that entered is bigger"; is Effect.ChangeTarget -> "change the target of ${effect.target.raw}"; is Effect.DamageDivided -> "deal ${effect.amount} damage divided as you choose among ${effect.maxTargets?.let { "up to $it " } ?: ""}${effect.target.raw}"; is Effect.PreventCombatToAndBy -> "prevent all combat damage dealt to and by ${effect.target.raw} this turn"; is Effect.WinIfCastBefore -> "win the game if another spell with this name was cast this game, otherwise tuck it seventh from the top and gain ${effect.life} life"; is Effect.Destroy -> "destroy ${effect.target.raw}${if (effect.noRegen) " (it can't be regenerated)" else ""}"; is Effect.Exile -> "exile ${effect.target.raw}"
        is Effect.PumpCausing -> "that creature gets ${signed(effect.power)}/${signed(effect.toughness)}"; is Effect.CantLoseThisTurn -> "you can't lose the game this turn"; is Effect.DamageLifeFloor -> "damage can't reduce your life total below ${effect.floor} this turn"; is Effect.ExtraLandThisTurn -> "play ${effect.count} additional land${if (effect.count == 1) "" else "s"} this turn"; is Effect.CoinFlip -> "flip a coin"; is Effect.CantCastThisTurn -> "stop spells being cast this turn"; is Effect.Proliferate -> "proliferate"; is Effect.ForAllTargeted -> "${effect.action} all ${effect.filter.raw} ${effect.target.raw} controls"; is Effect.LoseLifeThatMuch -> "lose that much life"; is Effect.AnimateSelf -> "${item.source.name} becomes a ${effect.power}/${effect.toughness} creature until end of turn"; is Effect.SaddleSelf -> "${item.source.name} becomes saddled"; is Effect.BecomeMonarch -> "become the monarch"; is Effect.PumpSelfCount -> "${item.source.name} gets ${signed(effect.power)}/${signed(effect.toughness)} for each of them"; is Effect.TapAttached -> "tap the creature ${item.source.name} is attached to"; is Effect.ReturnSelfFromGraveyard -> "return ${item.source.name} from your graveyard to the battlefield${if (effect.tapped) " tapped" else ""}"; is Effect.DamageThatMuch -> "deal that much damage to ${effect.target.raw}"; is Effect.PumpAllCount -> "${effect.filter.raw} get +X/+X${if (effect.keywords.isEmpty()) "" else " and gain " + effect.keywords.joinToString(" and ")}"; is Effect.ShuffleIntoLibrary -> "shuffle ${effect.target.raw} into its owner's library"
        is Effect.DamagePlayer -> "deal ${effect.amount} damage to ${when (effect.who) { Who.THAT_PLAYER -> "that player"; Who.EACH_OPPONENT -> "each opponent"; Who.EACH_PLAYER -> "each player"; Who.YOU -> "you"; else -> "the player" }}"
        is Effect.CreateToken -> "create ${if (effect.countBy != null) "X" else effect.count.toString()} ${effect.token} token${if (effect.count > 1 || effect.countBy != null) "s" else ""}"; is Effect.CreateTokenCopy -> "create ${effect.count} token${if (effect.count > 1) "s" else ""} that's a copy of ${effect.target?.raw ?: item.source.name}"; is Effect.SacrificeEach -> "each such player sacrifices a ${effect.filter.raw}"; is Effect.SacrificeSource -> "sacrifice ${item.source.name}"; is Effect.GainLifePerSpellThisTurn -> "gain ${effect.per} life for each spell cast this turn"; is Effect.WinIfDevotionCoversLibrary -> "look at the top X cards (X = your devotion) and win if X is at least your library size"; is Effect.Mill -> "${when (effect.who) { Who.TARGET_PLAYER -> "target player"; Who.YOU -> "you"; Who.EACH_PLAYER -> "each player"; Who.EACH_OPPONENT -> "each opponent"; else -> "that player" }} mills ${effect.count} cards"; is Effect.ExileGraveyard -> "exile ${when (effect.who) { Who.TARGET_PLAYER -> "target player"; Who.YOU -> "your"; Who.EACH_PLAYER -> "each player"; Who.EACH_OPPONENT -> "each opponent"; else -> "that player" }}${if (effect.who == Who.YOU) "" else "'s"} graveyard"; is Effect.DiscardNamed -> "that player reveals their hand and discards every card with the name you chose"; is Effect.BounceChosen -> "return ${withArticle(effect.what)} you control to its owner's hand"; is Effect.LivingWeapon -> "create a 0/0 black Phyrexian Germ creature token, then attach ${item.source.name} to it"; is Effect.DiscardChosen -> "${when (effect.who) { Who.TARGET_PLAYER -> "target player"; Who.EACH_OPPONENT -> "each opponent"; Who.EACH_PLAYER -> "each player"; else -> "that player" }} reveals their hand and discards ${effect.what} of your choice"; is Effect.SacrificeThatMany -> "that player sacrifices that many ${effect.filter.raw}s"; is Effect.PutFromHand -> "put ${withArticle(effect.filter.raw)} from your ${if (effect.fromLibrary) "library" else if (effect.fromGraveyard) "graveyard" else "hand"} onto the battlefield"
        is Effect.Bounce -> "return ${effect.target?.raw ?: item.source.name} to its owner's hand"; is Effect.GainLifeEqualToPower -> "its controller gains life equal to its power"; is Effect.GainLifeEqualToToughness -> "its controller gains life equal to its toughness"; is Effect.PutOnBottom -> "put ${effect.target.raw} on the bottom of its owner's library"; is Effect.GainLifeLostThisWay -> "gain life equal to the life lost this way"; is Effect.RevealTopToHand -> "reveal the top card of your library and put it into your hand"; is Effect.PutSelfOnLibraryTop -> "put ${item.source.name} on top of its owner's library"; is Effect.LoseLifeEqualToRevealedMv -> "lose life equal to the revealed card's mana value"; is Effect.ExileIfDamagedDies -> "exile a creature dealt damage this way instead if it would die this turn"; is Effect.NarratedTargeted -> "${effect.target.raw}: ${effect.text}"
        is Effect.Tap -> "tap ${effect.target.raw}"; is Effect.Untap -> "untap ${effect.target.raw}"
        is Effect.Pump -> "${effect.target.raw} gets ${signed(effect.power)}/${signed(effect.toughness)}"
        is Effect.GainKeywords -> "${effect.target.raw} gains ${effect.keywords.joinToString(" and ")}"
        is Effect.GainControl -> "gain control of ${effect.target.raw}${if (effect.untilEndOfTurn) " until end of turn" else ""}"
        is Effect.PumpSelf -> "${item.source.name} gets ${signed(effect.power)}/${signed(effect.toughness)}"
        is Effect.PumpAll -> "${effect.filter.raw} get ${signed(effect.power)}/${signed(effect.toughness)}"; is Effect.SetBasePtAll -> "${effect.filter.raw} have base power and toughness ${if (effect.x) "X/X" else "${effect.power}/${effect.toughness}"} until end of turn"
        is Effect.PutCounters -> "put ${effect.count} ${effect.kind} counter(s) on ${effect.target?.raw ?: item.source.name}"; is Effect.RemoveAllCounters -> "remove all counters from ${effect.target.raw}"
        is Effect.AddMana -> "add ${effect.text}"; is Effect.AddManaPer -> "add ${effect.symbol} for each ${effect.filter.raw}"; is Effect.AddManaDevotion -> "choose a colour and add that much mana of it as your devotion to it"; is Effect.AddManaInstead -> "add ${effect.text} instead if you control ${effect.required.joinToString(" and ") { "an $it" }}"; is Effect.Narrated -> effect.text.replace("~", item.source.name).replaceFirstChar { it.lowercase() }
        is Effect.ForAll -> "${effect.action} ${if (effect.action == "damage") "${effect.amount} to " else ""}each ${effect.filter.raw}"
        is Effect.IfYouDo -> "${describe(effect.choice, item)}, and if so ${describe(effect.then, item)}"
        is Effect.IfCondition -> "if ${effect.raw}, ${describe(effect.then, item)}"
        is Effect.Attach -> "attach ${item.source.name} to ${effect.target.raw}"
        is Effect.GainKeywordsSelf -> "${item.source.name} gains ${effect.keywords.joinToString(" and ")}"
        is Effect.Modal -> "choose ${effect.count}: " + effect.modeTexts.joinToString(" / ")
        is Effect.CreateShield -> "prevent ${effect.replacement.amount?.toString() ?: "all"} damage" + (effect.target?.let { " to ${it.raw}" } ?: "") + " this turn"
        is Effect.Regenerate -> "regenerate ${effect.target?.raw ?: item.source.name}"
        is Effect.GainLife -> "gain ${effect.amount} life"; is Effect.LoseLife -> "lose ${if (effect.x) "X" else effect.amount.toString()} life"; is Effect.Discard -> "${when (effect.who) { Who.YOU -> "you"; Who.EACH_PLAYER -> "each player"; Who.EACH_OPPONENT -> "each opponent"; Who.TARGET_PLAYER -> "target player"; else -> "that player" }} discard${if (effect.who == Who.YOU) "" else "s"} ${if (effect.x) "X" else effect.count.toString()} card(s)${if (effect.random) " at random" else ""}"; is Effect.Repeat -> "repeat ${if (effect.x) "X" else effect.times.toString()} times: ${describe(effect.body, item)}"; is Effect.LoseLifeUnlessSacOrDiscard -> "${when (effect.who) { Who.EACH_OPPONENT -> "each opponent"; Who.EACH_PLAYER -> "each player"; else -> "that player" }} loses ${effect.amount} life unless they sacrifice ${effect.filter?.let { withArticle(it.raw) } ?: "a permanent"}${if (effect.discard) " or discard a card" else ""}"
        is Effect.May -> "may " + describe(effect.effect, item); is Effect.UnlessPays -> describe(effect.effect, item) + " unless ${effect.cost} is paid"
        is Effect.Seq -> effect.effects.joinToString(", then ") { describe(it, item) }; is Effect.Unparsed -> "\"${effect.text}\""
    }
    private fun unparsedText(e: Effect): String = when (e) { is Effect.Unparsed -> e.text; is Effect.May -> unparsedText(e.effect); is Effect.UnlessPays -> unparsedText(e.effect); is Effect.Seq -> e.effects.filter { it.hasUnparsed() }.joinToString(" | ") { unparsedText(it) }; is Effect.Modal -> e.modes.filter { it.hasUnparsed() }.joinToString(" | ") { "mode \"" + unparsedText(it) + "\"" }; else -> "" }
    private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"
    private fun withArticle(s: String) = (if (s.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an " else "a ") + s
    private fun freshObjectId(name: String): String { val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var id = base; var i = 2; while (state.objects.containsKey(id)) id = "${base}_${i++}"; return id }
    private fun zoneName(z: Zone, obj: GameObject?) = when (z) {
        Zone.BATTLEFIELD -> "the battlefield"; Zone.GRAVEYARD -> "${obj?.let { state.player(it.owner).possessive } ?: "its owner's"} graveyard"; Zone.HAND -> "${obj?.let { state.player(it.owner).possessive } ?: "its owner's"} hand"
        Zone.LIBRARY -> "${obj?.let { state.player(it.owner).possessive } ?: "its owner's"} library"; Zone.EXILE -> "exile"; Zone.STACK -> "the stack"; Zone.COMMAND -> "the command zone"
    }
}
