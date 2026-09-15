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

    fun cast(playerId: String, card: CardDef, targets: List<Ref>, objectId: String? = null, modes: List<Int> = emptyList(), overload: Boolean = false, x: Int? = null, kicked: Boolean = false, evoked: Boolean = false, choice: String? = null): StackItem? {
        val player = state.player(playerId)
        val obj = objectId?.let { state.objects[it] } ?: state.add(GameObject(objectId ?: freshObjectId(card.name), card, Zone.HAND, playerId))
        state.stack.firstOrNull { it.kind == StackKind.SPELL && it.source.def.has("split second") }?.let { ss ->
            trace.step("${ss.source.name} has split second and is on the stack, so players can't cast spells or activate abilities that aren't mana abilities. ${card.name} can't be cast now.", "702.61a")
            state.outcomes += "${card.name} can't be cast while ${ss.source.name} is on the stack (split second)."; return null
        }
        state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != playerId && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { s -> s is StaticEffect.OpponentsSorcerySpeed } }?.let { teferi ->
            val offTiming = state.stack.isNotEmpty() || state.phase == "combat" || (state.activePlayer != null && state.activePlayer != playerId) || state.step in setOf("upkeep", "draw", "end", "cleanup", "untap")
            if (offTiming) {
                trace.step("${teferi.name} says ${state.player(playerId).subject.lowercase()} can cast spells only any time ${state.player(playerId).subject.lowercase()} could cast a sorcery: during ${state.player(playerId).possessive} own main phase with an empty stack. ${card.name} can't be cast now${if (state.stack.isNotEmpty()) " (something is on the stack)" else ""}.", "307.1", "117.1a")
                state.outcomes += "${card.name} can't be cast (${teferi.name}: sorcery speed only)."; return null
            }
        }
        state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller != playerId && it.controller == state.activePlayer && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { s -> s is StaticEffect.OpponentsLockedOnYourTurn } }?.let { ab ->
            trace.step("It's ${state.player(ab.controller).possessive} turn and ${ab.name} says ${state.player(ab.controller).possessive} opponents can't cast spells during it. ${card.name} can't be cast now; it could be cast on ${state.player(playerId).possessive} own turn (or another opponent's).", "101.2")
            state.outcomes += "${card.name} can't be cast (${ab.name}: not during ${state.player(ab.controller).possessive} turn)."; return null
        }
        // Cost taxes (Thalia, the commander tax) against the mana the situation said is available.
        run {
            val taxes = state.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CostTax>().filter { t -> (t.whose == null || (t.whose == Who.YOU) == (o.controller == playerId)) && spellMatches(t.filter, card) }.map { o to it } }
            val commanderTax = if (obj.commander && obj.commanderCasts > 0) 2 * obj.commanderCasts else 0
            if (commanderTax > 0) trace.step("${card.name} is ${player.possessive} commander and has been cast from the command zone ${obj.commanderCasts} time${if (obj.commanderCasts == 1) "" else "s"} before, so it costs an additional {${commanderTax}} this time (the \"commander tax\").", "903.8")
            val tax = taxes.sumOf { it.second.amount } + commanderTax
            val cost = card.manaValue.toInt() + (x ?: 0) * maxOf(0, Regex("""\{X\}""").findAll(card.manaCost ?: "").count() - 1) + tax
            if (taxes.isNotEmpty() || commanderTax > 0) trace.step("${(taxes.map { "${it.first.name} makes ${card.name} cost {${kotlin.math.abs(it.second.amount)}} ${if (it.second.amount < 0) "less" else "more"}" } + (if (commanderTax > 0) listOf("the commander tax adds {$commanderTax}") else emptyList())).joinToString(" and ")}: its total cost is ${card.manaCost ?: "?"} plus {${tax}} (${cost} mana in all).", "601.2f", "118.7")
            if (false) trace.step("${taxes.joinToString(" and ") { "${it.first.name} makes ${card.name} cost {${kotlin.math.abs(it.second.amount)}} ${if (it.second.amount < 0) "less" else "more"}" }}: its total cost is ${card.manaCost ?: "?"} plus {${tax}}${if (tax > 0) "" else ""} (${cost} mana in all).", "601.2f", "118.7")
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
            obj.controller = playerId; enter(obj.id); stateBasedActions(); state.outcomes += "${card.name} enters the battlefield."
            return null
        }
        val effect = card.spellEffect ?: card.enchant?.takeIf { card.isAura }?.let { Effect.Attach(TargetSpec(it, "enchant ${it.raw}")) }
        val needed = effect?.targets() ?: emptyList()
        var asked = false
        var noLegalTarget = false
        var targets = if (targets.isEmpty() && needed.size == 1) inferTarget(card.name, needed[0], playerId, harmful = isHarmful(effect), source = obj, beneficial = isBeneficial(effect)).also { asked = it == null && state.clarifications.any { c -> c.about == "${card.name}'s target" }; noLegalTarget = it != null && it.isEmpty() } ?: targets else targets
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
        if (needed.size != targets.size && effect !is Effect.Modal && !(needed.isEmpty() && targets.size == 1 && targets[0] is Ref.Player && effect != null && targetsAPlayer(effect))) {
            if (!asked) state.clarifications += Clarification("${card.name}'s target${if (needed.size == 1) "" else "s"}",
                "${card.name} needs ${needed.size} target${if (needed.size == 1) "" else "s"} (${needed.joinToString("; ") { it.raw }}) but ${targets.size} ${if (targets.size == 1) "was" else "were"} given (601.2c).")
            if (needed.size > targets.size) return null
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
        state.spellsCast[card.name] = (state.spellsCast[card.name] ?: 0) + 1
        state.spellsThisTurn[playerId] = (state.spellsThisTurn[playerId] ?: 0) + 1
        if ("Instant" !in card.types) {
            val offTiming = state.phase == "combat" || state.stack.isNotEmpty() || (state.activePlayer != null && state.activePlayer != playerId)
            val kind = card.types.firstOrNull { it in setOf("Creature", "Sorcery", "Enchantment", "Artifact", "Planeswalker", "Battle") } ?: "permanent"
            val timingRule = mapOf("Creature" to "302.1", "Sorcery" to "307.1", "Enchantment" to "303.1", "Artifact" to "301.1", "Planeswalker" to "306.1", "Battle" to "310.1")[kind]
            if (offTiming && card.has("flash")) trace.step("${card.name} has flash, so it can be cast any time its controller could cast an instant, including now.", "702.8a")
            else if (offTiming && timingRule != null) { trace.step("${withArticle(kind.lowercase()).replaceFirstChar { c -> c.uppercase() }} spell can normally be cast only during its controller's main phase with an empty stack; ${card.name} doesn't have flash. Assuming an effect allows it, as described.", timingRule); state.assumptions += "${card.name} is cast at a time ${withArticle(kind.lowercase())} spell normally can't be (no flash); assuming something allows it." }
        }
        // A modal spell's targets belong to the chosen mode (700.2c): validate against that mode's needs.
        val modal = effect as? Effect.Modal
        // A modal spell with a target but no mode named: the mode whose target the given target fits ("Red Elemental Blast on Counterspell").
        val modes = if (modal != null && modes.isEmpty() && targets.size == 1) {
            val fits = modal.modes.withIndex().filter { (_, m) -> m.targets().size == 1 && m.targets()[0].filter.let { f -> when (val t = targets[0]) { is Ref.Stack -> Kind.SPELL in f.kinds || Kind.ABILITY in f.kinds; is Ref.Obj -> Kind.SPELL !in f.kinds && (!f.verifiable || filterMatches(f, t, playerId)); is Ref.Player -> Kind.PLAYER in f.kinds } } }
            if (fits.size == 1) { state.assumptions += "${card.name}'s mode: \"${modal.modeTexts.getOrNull(fits[0].index) ?: "?"}\" (the one the target fits)."; listOf(fits[0].index + 1) } else modes
        } else modes
        val modeEffect = modal?.let { m -> modes.mapNotNull { i -> m.modes.getOrNull(i - 1) }.let { if (it.isEmpty()) null else Effect.Seq(it) } }
        val item = StackItem(state.newStackId(), StackKind.SPELL, playerId, obj, effect, targets, zonesOf(targets), card.oracleText, modes, x = x, kicked = kicked, evoked = evoked && card.has("evoke"), choice = choice)
        state.stack += item
        if (evoked && !card.has("evoke")) { state.clarifications += Clarification("${card.name}'s evoke", "${card.name} doesn't have evoke, so it can't be cast for an evoke cost; treating it as cast normally.") }
        if (item.evoked) trace.step("${card.name} is cast for its evoke cost, an alternative cost paid instead of its mana cost. It's still a creature spell and resolves normally; its evoke trigger will sacrifice it once it has entered.", "702.74a", "601.2b")
        if (kicked) trace.step("${card.name} is kicked: its controller paid the kicker cost as an additional cost, so its \"if this spell was kicked\" parts apply.", "702.33a", "702.33d")
        if (x != null) trace.step("X is $x, chosen as ${card.name} is cast; the mana cost includes X.", "107.3a", "601.2b")
        else if (effect != null && usesX(effect)) state.clarifications += Clarification("${card.name}'s X", "${card.name} has X in its text; what was X? (assuming 0)")
        trace.step("${player.subject} ${player.v("casts", "cast")} ${card.name}${if (modes.isNotEmpty() && modal != null) " choosing " + modes.joinToString(" and ") { "\"${modal.modeTexts.getOrNull(it - 1) ?: "?"}\"" } else ""}${describeTargets(targets)}. It goes on top of the stack.", "601.2a", "405.2", *(if (modal != null) arrayOf("601.2b", "700.2a") else emptyArray()))
        val playerTargetMode = targets.isNotEmpty() && targets.all { it is Ref.Player } && modes.any { modal?.modeTexts?.getOrNull(it - 1)?.lowercase()?.contains("target player") == true }
        if (modeEffect != null && modeEffect.targets().size != targets.size && !playerTargetMode) state.clarifications += Clarification("${card.name}'s target", "The chosen mode needs ${modeEffect.targets().size} target(s) (${modeEffect.targets().joinToString("; ") { it.raw }}) but ${targets.size} given.")
        card.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CostText>().forEach {
            trace.step("Cost note for ${card.name}: \"${it.text.replace("~", card.name)}\" (the total cost is determined and paid as part of casting).", "601.2b", "601.2f", "601.2h")
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
        item.targets.forEach { ref -> objOf(ref)?.let { onEvent(GameEvent.BecomesTarget(it)) } }
        if (item.effect?.hasUnparsed() == true) state.unsupported += Unsupported(card.name, "Part of the spell's effect is not modeled: " + unparsedText(item.effect))
        onEvent(GameEvent.SpellCast(item))
        trace.step("${player.subject} ${player.v("receives", "receive")} priority again after casting.", "117.3c")
    }

    /** "I sacrifice X": its controller moves it from the battlefield to its owner's graveyard (701.21a). */
    fun sacrifice(playerId: String, objectId: String) {
        val o = state.obj(objectId); val p = state.player(playerId)
        if (!o.isOnBattlefield()) { trace.step("${o.name} isn't on the battlefield, so it can't be sacrificed.", "701.21a"); return }
        if (o.controller != playerId) { trace.step("${p.subject} ${p.v("doesn't", "don't")} control ${o.name}, so ${p.subject.lowercase()} can't sacrifice it.", "701.21a"); return }
        o.lkiPower = o.power; state.lastSacrificed = o
        move(o, Zone.GRAVEYARD, "${p.subject} ${p.v("sacrifices", "sacrifice")} ${o.name}: it goes from the battlefield to its owner's graveyard. Sacrificing isn't destroying, so indestructible and regeneration don't help.", "701.21a")
        stateBasedActions()
    }

    /** "I gain 5 life (from lifelink)": life gain as a given, with its triggers. */
    fun gainLifeEvent(playerId: String, amount: Int) { gainLife(state.player(playerId), amount); stateBasedActions() }
    fun loseLifeEvent(playerId: String, amount: Int) { val p = state.player(playerId); p.life = p.life?.minus(amount); trace.step("${p.subject} ${p.v("loses", "lose")} $amount life${p.life?.let { " ($it)" } ?: ""}.", "119.3"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} $amount life."; stateBasedActions() }

    /** A player draws cards outside any effect ("my opponent draws a card"): each draw is an event triggers can see. */
    fun draw(playerId: String, count: Int) { drawCards(state.player(playerId), count); stateBasedActions() }

    /** Draws, tracking the library size when it's known; drawing from an empty library flags the player for 704.5b. */
    private fun drawCards(who: Player, count: Int) {
        val lib = who.librarySize
        if (lib != null && lib < count) {
            if (lib > 0) { trace.step("${who.subject} ${who.v("draws", "draw")} $lib card${if (lib > 1) "s" else ""}, emptying ${who.possessive} library.", "121.1"); repeat(lib) { who.drew += 1; onEvent(GameEvent.Drew(who.id)) } }
            who.librarySize = 0; who.drewFromEmpty = true
            trace.step("${who.subject} ${who.v("attempts", "attempt")} to draw ${count - lib} card${if (count - lib > 1) "s" else ""} from a library with no cards in it. No card is drawn, and ${who.subject.lowercase()} will lose the game the next time a player would receive priority.", "121.4", "704.5b")
            state.outcomes += if (lib == 0) "${who.subject} can't draw: ${who.possessive} library is empty." else "${who.subject} ${who.v("draws", "draw")} $lib card${if (lib == 1) "" else "s"} and can't draw the rest (empty library)."
            return
        }
        if (count <= 0) { trace.step("${who.subject} ${who.v("draws", "draw")} no cards.", "121.1"); return }
        trace.step("${who.subject} ${who.v("draws", "draw")} $count card${if (count > 1) "s" else ""}${if (count > 1) " (one at a time)" else ""}${if (lib != null) "; ${lib - count} left in ${who.possessive} library" else ""}.", "121.1", *(if (count > 1) arrayOf("121.2") else emptyArray()))
        state.outcomes += "${who.subject} ${who.v("draws", "draw")} $count card${if (count > 1) "s" else ""}."
        if (lib != null) who.librarySize = lib - count
        repeat(count) { who.drew += 1; onEvent(GameEvent.Drew(who.id)) }
    }

    fun activate(playerId: String, objectId: String, abilityIndex: Int?, targets: List<Ref>, choice: String? = null, x: Int? = null): StackItem? {
        val obj = state.obj(objectId)
        val abilities = obj.def.abilities.filterIsInstance<ActivatedAbility>()
        if ("Land" in obj.def.types && "Basic" !in obj.def.supertypes) state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.NonbasicLandsAreMountains } }?.let { moon ->
            val p = state.player(playerId)
            trace.step("${moon.name} makes ${obj.name} a Mountain: it loses its other land types and all its printed abilities and has only \"{T}: Add {R}\" (a type-changing effect, layer 4).", "613.1d", "305.7")
            if (obj.tapped == true) { trace.step("${obj.name} is already tapped, so it can't be tapped for mana.", "602.5a"); state.outcomes += "${obj.name} can't be tapped (already tapped)."; return null }
            tap(obj); trace.step("${p.subject} ${p.v("taps", "tap")} ${obj.name} for {R}; that's the only mana it can make under ${moon.name}.", "605.1a", "605.3b"); state.outcomes += "${obj.name} adds {R} (only), because of ${moon.name}."; return null
        }
        state.objects.values.firstOrNull { it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.any { e -> e is StaticEffect.CantActivate && (!e.opponentsOnly || it.controller != playerId) && state.matches(e.filter, obj, it.controller, it) } }?.let { lock ->
            val e = lock.def.abilities.filterIsInstance<StaticAbility>().flatMap { a -> a.effects }.filterIsInstance<StaticEffect.CantActivate>().first { state.matches(it.filter, obj, lock.controller, lock) }
            trace.step("${lock.name} says activated abilities of ${e.filter.raw} can't be activated, and ${obj.name} is ${withArticle(e.filter.raw)}, so ${state.player(playerId).subject.lowercase()} can't begin to activate its ability${if (abilities.any { a -> a.effect is Effect.AddMana || (a.effect as? Effect.Seq)?.effects?.any { it is Effect.AddMana } == true }) " (mana abilities included: they are activated abilities too)" else ""}.", "602.5", "604.2", "101.2")
            state.outcomes += "${obj.name}'s ability can't be activated (${lock.name})."; return null
        }
        if (abilities.isEmpty()) { state.unsupported += Unsupported(obj.name, "No activated ability was recognised on ${obj.name}."); return null }
        if (abilities.size > 1 && abilityIndex == null) {
            state.clarifications += Clarification("${obj.name}'s ability", "${obj.name} has ${abilities.size} activated abilities; which one? " + abilities.mapIndexed { i, a -> "[$i] ${a.text}" }.joinToString(" "))
            return null
        }
        val ability = abilities[abilityIndex ?: 0]
        if (!paySacrificeCosts(playerId, obj, ability, choice)) return null
        val isMana = ability.effect is Effect.AddMana || (ability.effect is Effect.Seq && (ability.effect as Effect.Seq).effects.firstOrNull() is Effect.AddMana)
        if (!isMana) state.stack.firstOrNull { it.kind == StackKind.SPELL && it.source.def.has("split second") }?.let { ss ->
            trace.step("${ss.source.name} has split second and is on the stack, so abilities that aren't mana abilities can't be activated. ${obj.name}'s ability can't be activated now.", "702.61a")
            state.outcomes += "${obj.name}'s ability can't be activated while ${ss.source.name} is on the stack (split second)."; return null
        }
        if (ability.effect is Effect.AddMana || (ability.effect is Effect.Seq && (ability.effect as Effect.Seq).effects.firstOrNull() is Effect.AddMana)) {
            val p = state.player(playerId)
            trace.step("${p.subject} ${p.v("activates", "activate")} ${obj.name}'s mana ability (${ability.cost}). It's a mana ability, so it doesn't use the stack and resolves immediately: ${describeManaEffect(ability.effect)}.", "605.1a", "605.3b")
            if (ability.cost.contains("{T}")) tap(obj)
            state.outcomes += "${obj.name}'s mana ability: ${describeManaEffect(ability.effect)}."
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
        if (ability.cost.contains("{T}") && obj.tapped == true) { trace.step("${obj.name} is already tapped, so its {T} ability can't be activated.", "602.2b", "701.26a"); return null }
        if (ability.cost.contains("{T}") && obj.def.isCreature && obj.summoningSick == true && !obj.has("haste")) { trace.step("${obj.name} hasn't been under ${state.player(playerId).possessive} control since the turn began and doesn't have haste, so its {T} ability can't be activated.", "302.6"); state.outcomes += "${obj.name}'s {T} ability can't be activated (summoning sickness)."; return null }
        if (ability.cost.contains("{T}")) tap(obj)
        if (ability.cost.contains("discard this card", true)) onEvent(GameEvent.Cycled(obj))
        val needed = ability.effect.targets()
        if (needed.size != targets.size && !(needed.isEmpty() && targets.size == 1 && targets[0] is Ref.Player && targetsAPlayer(ability.effect))) {
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
        targets.forEach { ref -> objOf(ref)?.let { onEvent(GameEvent.BecomesTarget(it)) } }
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

    fun enter(objectId: String) {
        val obj = state.obj(objectId)
        obj.zone = Zone.BATTLEFIELD; obj.tapped = false; obj.timestamp = state.tick()
        applyEntersReplacements(obj)
        trace.step("${obj.name} enters the battlefield under ${state.player(obj.controller).possessive} control${if (obj.tapped == true) " tapped" else ""}.", "110.5b")
        onEvent(GameEvent.EntersBattlefield(obj))
    }

    fun leave(objectId: String, to: Zone) {
        val obj = state.obj(objectId)
        move(obj, to, "${obj.name} is put into ${zoneName(to, obj)}.", "400.7")
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

    /** Pumps, keyword grants, shields, +1/+1 counters: aimed at your own things when an opponent's also qualify. */
    private fun isBeneficial(e: Effect?): Boolean = when (e) { is Effect.Pump, is Effect.GainKeywords, is Effect.CreateShield, is Effect.Regenerate, is Effect.Untap -> true; is Effect.PutCounters -> e.kind.let { it == "+1/+1" || it.startsWith("+") }; is Effect.Seq -> e.effects.isNotEmpty() && e.effects.all { isBeneficial(it) || it is Effect.Narrated }; is Effect.May -> isBeneficial(e.effect); else -> false }
    private fun isHarmful(e: Effect?): Boolean = when (e) { is Effect.Destroy, is Effect.Exile, is Effect.Damage, is Effect.Bounce, is Effect.Tap, is Effect.Counter, is Effect.ShuffleIntoLibrary, is Effect.GainControl -> true; is Effect.Seq -> e.effects.any { isHarmful(it) }; is Effect.May -> isHarmful(e.effect); is Effect.UnlessPays -> isHarmful(e.effect); else -> false }
    /** Effects phrased "target player …" whose player is carried by a [Who] rather than a TargetSpec. */
    private fun targetsAPlayer(e: Effect): Boolean = when (e) {
        is Effect.Draw -> e.who == Who.TARGET_PLAYER; is Effect.GainLife -> e.who == Who.TARGET_PLAYER; is Effect.LoseLife -> e.who == Who.TARGET_PLAYER; is Effect.DamagePlayer -> e.who == Who.TARGET_PLAYER
        is Effect.CreateToken -> e.who == Who.TARGET_PLAYER; is Effect.Mill -> e.who == Who.TARGET_PLAYER; is Effect.SacrificeEach -> e.who == Who.TARGET_PLAYER; is Effect.LoseLifeThatMuch -> e.who == Who.TARGET_PLAYER
        is Effect.Seq -> e.effects.any { targetsAPlayer(it) }; is Effect.May -> targetsAPlayer(e.effect); is Effect.UnlessPays -> targetsAPlayer(e.effect); is Effect.Modal -> e.modes.any { targetsAPlayer(it) }
        is Effect.Narrated -> e.text.startsWith("target player", ignoreCase = true); else -> false
    }

    /** Sacrifice costs of an activated ability ("Sacrifice ~:", "Sacrifice an artifact:"), paid as it's activated. False if they can't be paid. */
    /** Why a creature can't attack or block right now (the permanent forbidding it), or null if it can. */
    fun cantWhy(objectId: String, what: String): String? {
        val o = state.obj(objectId)
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
        return typeOk && notOk
    }
    private fun usesX(e: Effect): Boolean = when (e) { is Effect.Damage -> e.x; is Effect.Draw -> e.x; is Effect.PumpAll -> e.x; is Effect.SetBasePtAll -> e.x; is Effect.Repeat -> e.x || usesX(e.body); is Effect.Seq -> e.effects.any { usesX(it) }; is Effect.May -> usesX(e.effect); is Effect.Modal -> e.modes.any { usesX(it) }; else -> false }

    /** Steps and combat can't begin while something is on the stack: everything pending resolves first (500.2). */
    private fun emptyStackFirst(what: String) {
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
            mine.forEach { it.tapped = false; it.summoningSick = false; it.attacking = null; it.blocking = null }
            trace.step("${p.possessive.replaceFirstChar { it.uppercase() }} turn begins: ${p.subject.lowercase()} ${p.v("untaps", "untap")} all ${p.possessive} permanents, and everything ${p.subject.lowercase()} ${p.v("has", "have")} controlled since the turn began can attack and use {T} abilities.", "502.3", "302.6")
            return
        }
        if (step == "cleanup") {
            state.activePlayer = activePlayer; state.step = step; state.phase = "ending"
            val affected = state.objects.values.filter { it.isOnBattlefield() && (it.pumps.isNotEmpty() || it.tempKeywords.isNotEmpty() || it.damage > 0 || it.basePt != null) }
            trace.step("The cleanup step: all damage marked on permanents is removed and all \"until end of turn\" effects end, simultaneously.", "514.2")
            for (o in affected) { o.pumps.clear(); o.tempKeywords.clear(); o.basePt = null; o.damage = 0; trace.step("${o.name} is back to ${if (o.def.isCreature) state.describePt(o) else "normal"} with no damage.", "514.2"); state.outcomes += "${o.name}'s until-end-of-turn effects and damage are gone (cleanup)." }
            state.shields.clear()
            return
        }
        state.activePlayer = activePlayer
        state.step = step
        state.phase = when (step) { "untap", "upkeep", "draw" -> "beginning"; "precombat_main" -> "precombat_main"; "postcombat_main" -> "postcombat_main"; "end", "cleanup" -> "ending"; else -> "combat" }
        trace.step("${state.player(activePlayer).possessive.replaceFirstChar { it.uppercase() }} ${step.replace('_', ' ')} begins.", when (step) { "upkeep" -> "503.1"; "end" -> "513.1"; "draw" -> "504.1"; else -> "500.1" })
        if (step == "end" && state.objects.values.any { it.isOnBattlefield() && (it.pumps.isNotEmpty() || it.tempKeywords.isNotEmpty()) }) {
            trace.step("\"Until end of turn\" effects don't end in the end step: \"at the beginning of the end step\" abilities trigger now, and the effects last until the cleanup step that follows.", "513.1", "514.2")
            state.outcomes += "Until-end-of-turn effects still apply during the end step; they end in the cleanup step."
        }
        onEvent(GameEvent.StepBegins(step, activePlayer))
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
                afterResolution(); return
            } else if (illegalCount > 0) {
                trace.step("Some targets of ${item.describe} are illegal (${legal.filter { !it.second }.joinToString("; ") { whyIllegal(item, it.first) }}); it resolves but won't affect them.", "608.2b")
            }
        }
        when (item.kind) {
            StackKind.SPELL -> {
                val def = item.source.def
                if (def.isInstantOrSorcery) {
                    item.effect?.let { applyEffect(it, item) } ?: if (!Generic.isGeneric(def)) state.unsupported.add(Unsupported(def.name, "The spell has no modeled effect.")) else Unit
                    item.source.zone = Zone.GRAVEYARD
                    if (item.source.token) trace.step("The copy of ${def.name} finishes resolving; a copy of a spell ceases to exist once it leaves the stack.", "608.2c", "707.10a")
                    else trace.step("${def.name} finishes resolving and is put into its owner's graveyard.", "608.2c", "608.2n")
                } else {
                    item.source.zone = Zone.BATTLEFIELD; item.source.tapped = false; item.source.summoningSick = def.isCreature; item.source.timestamp = state.tick()
                    if (def.isAura) {
                        val t = item.targets.firstOrNull()
                        val tid = (t as? Ref.Obj)?.id
                        item.source.attachedTo = tid; applyControlEnchanted(item.source)
                        trace.step("${def.name} enters the battlefield attached to ${t?.let { state.nameOf(it) } ?: "nothing"}.", "608.3b", "303.4")
                    }
                    applyEntersReplacements(item.source)
                    val before = state.objects.values.filter { it.isOnBattlefield() && it.def.isCreature && it !== item.source }.associate { it.id to (it.power to it.toughness) }
                    trace.step("${def.name} resolves and enters the battlefield under ${state.player(item.controller).possessive} control${if (def.isCreature) " as a ${state.describePt(item.source)}" else ""}${if (item.source.tapped == true) ", tapped" else ""}.", "608.3a")
                    if (def.abilities.any { it is StaticAbility && it.effects.isNotEmpty() }) trace.step("${def.name}'s static ability starts applying to the permanents it describes.", "604.2", "613.1")
                    state.outcomes += "${def.name} enters the battlefield."
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
            for (obj in state.objects.values.toList()) {
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
            }
            for (obj in state.objects.values.toList()) {
                if (obj.isOnBattlefield() && obj.def.isPlaneswalker && (obj.counters["loyalty"] ?: 0) <= 0) { move(obj, Zone.GRAVEYARD, "${obj.name} has 0 loyalty and is put into its owner's graveyard (state-based action).", "704.3", "704.5i", "306.9"); changed = true }
            }
            for (obj in state.objects.values.toList()) {
                if (obj.token && !obj.isOnBattlefield() && obj.zone != Zone.STACK) { state.objects.remove(obj.id); trace.step("${obj.name} token ceases to exist.", "704.5d"); changed = true }
            }
            for (obj in state.objects.values.toList()) {
                if (!obj.isOnBattlefield()) continue
                val host = obj.attachedTo?.let { state.objects[it] }
                if (obj.def.isAura) {
                    val legal = host != null && host.isOnBattlefield() && (obj.def.enchant == null || state.matches(obj.def.enchant, host, obj.controller, obj))
                    if (!legal) { move(obj, Zone.GRAVEYARD, "${obj.name} is ${if (host == null || !host.isOnBattlefield()) "no longer attached to anything" else "attached to something it can't enchant"}, so it's put into its owner's graveyard (state-based action).", "704.3", "704.5m"); changed = true }
                } else if (obj.def.isEquipment && obj.attachedTo != null) {
                    if (host == null || !host.isOnBattlefield() || !host.def.isCreature) { obj.attachedTo = null; trace.step("${obj.name} is no longer attached to a creature, so it becomes unattached and stays on the battlefield (state-based action).", "704.3", "704.5n"); changed = true }
                }
            }
            for (p in state.players) {
                p.commanderDamage.entries.firstOrNull { it.value >= 21 }?.let { (cid, dmg) -> if (!p.lost) { p.lost = true; trace.step("${p.subject} ${p.v("has", "have")} been dealt $dmg combat damage by ${state.objects[cid]?.name ?: "a commander"} and ${p.v("loses", "lose")} the game (state-based action).", "704.6c", "903.10a"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} the game (commander damage)."; changed = true } }
                if (p.poison >= 10 && !p.lost) { p.lost = true; trace.step("${p.subject} ${p.v("has", "have")} ${p.poison} poison counters and ${p.v("loses", "lose")} the game (state-based action).", "704.3", "704.5c"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} the game (poison)."; changed = true }
                val life = p.life
                val cantLose = state.objects.values.firstOrNull { it.isOnBattlefield() && it.controller == p.id && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.CantLose } }
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
        if (!a.def.isCreature || !a.isOnBattlefield()) { trace.step("${a.name} isn't a creature on the battlefield, so it can't attack.", "506.3"); state.outcomes += "${a.name} can't attack."; return }
        if (a.controller != playerId) { trace.step("${a.name} isn't controlled by ${p.subject.lowercase()}, so ${p.subject.lowercase()} can't attack with it.", "508.1a"); return }
        if (a.has("defender")) { trace.step("${a.name} has defender and can't attack.", "702.3b"); state.outcomes += "${a.name} can't attack (defender)."; return }
        if (cant(a, "attack")) { trace.step("${a.name} can't attack (a rules text says so${cantSource(a, "attack")?.let { ": $it" } ?: ""}).", "508.1c"); state.outcomes += "${a.name} can't attack."; return }
        if (a.tapped == true) { trace.step("${a.name} is tapped, so it can't be declared as an attacker.", "508.1a"); state.outcomes += "${a.name} can't attack (tapped)."; return }
        if (a.summoningSick == true && !a.has("haste")) { trace.step("${a.name} came under ${p.possessive} control this turn and doesn't have haste, so it can't attack (\"summoning sickness\").", "508.1a", "302.6"); state.outcomes += "${a.name} can't attack (summoning sick)."; return }
        (defender as? Ref.Obj)?.let { d -> val o = state.objects[d.id]; if (o == null || !o.isOnBattlefield() || !(o.def.isPlaneswalker || "Battle" in o.def.types)) { trace.step("${state.nameOf(defender)} isn't a player, planeswalker or battle, so it can't be attacked.", "506.3"); return } else if (o.controller == playerId) { trace.step("${o.name} is ${p.possessive} own permanent; only an opponent's planeswalker or a battle can be attacked.", "506.2", "508.1b"); return } }
        // Propaganda / Ghostly Prison: attacking that player costs mana per attacker.
        val defendingPlayer = when (defender) { is Ref.Player -> defender.id; is Ref.Obj -> state.objects[defender.id]?.controller; else -> null }
        state.objects.values.filter { it.isOnBattlefield() && it.controller == defendingPlayer }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.AttackTax>().map { o to it } }.forEach { (src, tax) ->
            when {
                state.wontPay.remove(playerId) -> { trace.step("${src.name} says creatures can't attack ${state.nameOf(Ref.Player(defendingPlayer!!))} unless their controller pays ${tax.cost} for each; ${p.subject.lowercase()} ${p.v("doesn't", "don't")} pay, so ${a.name} can't attack.", "508.1c"); state.outcomes += "${a.name} can't attack (${src.name}'s cost not paid)."; return }
                state.willPay.remove(playerId) -> trace.step("${p.subject} ${p.v("pays", "pay")} ${tax.cost} for ${src.name} so that ${a.name} can attack.", "508.1c")
                p.mana != null -> {
                    val n = Regex("""\{(\d+)\}""").find(tax.cost)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (p.mana!! >= n) { p.mana = p.mana!! - n; trace.step("${src.name} says creatures can't attack ${state.nameOf(Ref.Player(defendingPlayer!!))} unless their controller pays ${tax.cost} for each. ${p.subject} ${p.v("has", "have")} the mana, so ${p.subject.lowercase()} ${p.v("pays", "pay")} ${tax.cost} for ${src.name} and ${a.name} attacks (${p.mana} mana left).", "508.1c"); state.outcomes += "${p.subject} ${p.v("pays", "pay")} ${tax.cost} for ${src.name} (${a.name} attacks)." }
                    else { trace.step("${src.name} says creatures can't attack ${state.nameOf(Ref.Player(defendingPlayer!!))} unless their controller pays ${tax.cost} for each; ${p.subject.lowercase()} ${p.v("has", "have")} only ${p.mana} mana left, so ${a.name} can't attack.", "508.1c"); state.outcomes += "${a.name} can't attack (can't pay ${tax.cost} for ${src.name})."; return }
                }
                else -> { trace.step("${src.name} says creatures can't attack ${state.nameOf(Ref.Player(defendingPlayer!!))} unless their controller pays ${tax.cost} for each; since ${a.name} attacks, ${p.subject.lowercase()} must be paying.", "508.1c"); state.assumptions += "${p.subject} ${p.v("pays", "pay")} ${tax.cost} for ${src.name} (otherwise ${a.name} couldn't attack)." }
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
    fun declareBlocker(playerId: String, blockerId: String, attackerId: String) {
        emptyStackFirst("declaring blockers")
        val b = state.obj(blockerId); val a = state.obj(attackerId); val p = state.player(playerId)
        state.step = "declare_blockers"
        if (!b.def.isCreature || !b.isOnBattlefield()) { trace.step("${b.name} isn't a creature on the battlefield, so it can't block.", "506.3"); return }
        if (a.attacking == null) { trace.step("${a.name} isn't attacking, so ${b.name} can't block it.", "509.1a"); return }
        // A creature may only block an attacker that is attacking its controller, a planeswalker they control, or a battle they protect.
        val defendsAgainst = when (val d = a.attacking) { is Ref.Player -> d.id == playerId; is Ref.Obj -> state.objects[d.id]?.controller == playerId; else -> true }
        if (!defendsAgainst) { trace.step("${a.name} is attacking ${state.nameOf(a.attacking!!)}, not ${p.subject.lowercase()}${if (p.you) "" else " or a planeswalker ${p.subject} controls"}, so ${b.name} can't block it.", "509.1a"); state.outcomes += "${b.name} can't block ${a.name} (it isn't attacking ${p.subject.lowercase()})."; return }
        if (b.tapped == true) { trace.step("${b.name} is tapped, so it can't block.", "509.1a"); state.outcomes += "${b.name} can't block (tapped)."; return }
        if (cant(b, "block")) { trace.step("${b.name} can't block (a rules text says so${cantSource(b, "block")?.let { ": $it" } ?: ""}).", "509.1b"); state.outcomes += "${b.name} can't block."; return }
        if (cant(a, "be blocked")) { trace.step("${a.name} can't be blocked.", "509.1b"); state.outcomes += "${b.name} can't block ${a.name}."; return }
        cantBeBlockedBy(a, b)?.let { r -> trace.step("${a.name} can't be blocked by ${r.by!!.raw}, and ${b.name} is one, so it can't block ${a.name}.", "509.1b"); state.outcomes += "${b.name} can't block ${a.name}."; return }
        state.protections(a).takeIf { it.isNotEmpty() }?.let { prots ->
            val bq = qualitiesOf(b.def)
            prots.firstOrNull { it == "everything" || it in bq }?.let { q -> trace.step("${a.name} has protection from $q, so ${b.name} can't block it.", "702.16f"); state.outcomes += "${b.name} can't block ${a.name} (protection)."; return }
        }
        if (a.has("fear") && !(b.has("fear") || "Artifact" in b.def.types || 'B' in b.def.colors)) { trace.step("${a.name} has fear and can't be blocked except by artifact creatures and/or black creatures.", "702.36b"); state.outcomes += "${b.name} can't block ${a.name} (fear)."; return }
        if (a.has("intimidate") && !("Artifact" in b.def.types || b.def.colors.intersect(a.def.colors).isNotEmpty())) { trace.step("${a.name} has intimidate and can't be blocked except by artifact creatures and/or creatures that share a color with it.", "702.13b"); state.outcomes += "${b.name} can't block ${a.name} (intimidate)."; return }
        if (a.has("horsemanship") && !b.has("horsemanship")) { trace.step("${a.name} has horsemanship and can't be blocked except by creatures with horsemanship.", "702.31b"); return }
        if (a.has("shadow") != b.has("shadow")) { trace.step("${a.name} ${if (a.has("shadow")) "has" else "doesn't have"} shadow and ${b.name} ${if (b.has("shadow")) "has" else "doesn't have"}; creatures with shadow can only block and be blocked by creatures with shadow.", "702.28b"); return }
        if (a.has("skulk") && (b.power ?: 0) > (a.power ?: 0)) { trace.step("${a.name} has skulk and can't be blocked by creatures with greater power.", "702.118b"); return }
        (a.def.keywords.firstOrNull { it.endsWith("walk") && it != "landwalk" } ?: a.def.abilities.filterIsInstance<StaticAbility>().map { it.text.trimEnd('.').lowercase() }.firstOrNull { it.endsWith("walk") && !it.contains(' ') })?.let { walk ->
            val landType = walk.removeSuffix("walk").replaceFirstChar { it.uppercase() }
            val defender = state.player(b.controller)
            if (state.objects.values.any { it.isOnBattlefield() && it.controller == defender.id && "Land" in it.def.types && (it.def.subtypes.any { st -> st.equals(landType, true) } || it.def.name.equals(landType, true)) }) {
                trace.step("${a.name} has $walk and ${defender.subject.lowercase()} ${defender.v("controls", "control")} a $landType, so it can't be blocked.", "702.14c"); state.outcomes += "${b.name} can't block ${a.name} ($walk)."; return
            }
        }
        if (a.has("flying") && !(b.has("flying") || b.has("reach"))) { trace.step("${a.name} has flying and ${b.name} has neither flying nor reach, so ${b.name} can't block it.", "702.9b"); state.outcomes += "${b.name} can't block ${a.name} (flying)."; return }
        val firstBlocker = blockersOf(a).isEmpty()
        b.blocking = a.id; a.wasBlocked = true
        if (firstBlocker) onEvent(GameEvent.BecomesBlocked(a))
        onEvent(GameEvent.Blocks(b))
        trace.step("${p.subject} ${p.v("blocks", "block")} ${a.name} with ${b.name} (${state.describePt(b)}). ${a.name} is now a blocked creature and stays blocked even if ${b.name} leaves combat.", "509.1a", "509.1g", "509.1h")
    }

    /** The combat damage step (510), including a first-strike step when needed (510.4). */
    fun combatDamage() {
        state.step = "combat_damage"
        val attackers = state.objects.values.filter { it.attacking != null && it.isOnBattlefield() }
        if (attackers.isEmpty()) { trace.step("No creatures are attacking, so there is no combat damage step.", "506.1"); return }
        // Menace: a single blocker is not a legal block.
        for (a in attackers) if (a.has("menace")) {
            val bs = blockersOf(a)
            if (bs.size == 1) { trace.step("${a.name} has menace and can't be blocked except by two or more creatures; blocking it with only ${bs[0].name} isn't a legal block, so ${a.name} is unblocked.", "702.111b", "509.1a"); bs[0].blocking = null; a.wasBlocked = false }
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
        data class PlayerAttacks(val playerId: String) : GameEvent
        data class AttacksAlone(val obj: GameObject) : GameEvent
        data class StepBegins(val step: String, val activePlayer: String) : GameEvent
        data class DamageDealt(val source: GameObject, val target: Ref, val amount: Int, val combat: Boolean) : GameEvent
        data class LifeGained(val playerId: String, val amount: Int) : GameEvent
        data class BecomesBlocked(val obj: GameObject) : GameEvent
        data class Blocks(val obj: GameObject) : GameEvent
        data class BecomesTarget(val obj: GameObject) : GameEvent
        data class BecomesTapped(val obj: GameObject) : GameEvent
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
            for (ability in obj.def.abilities.filterIsInstance<TriggeredAbility>()) {
                if (matches(obj, ability.trigger, event)) triggered += obj to ability
            }
        }
        // Panharmonicon: an artifact or creature entering makes its controller's triggers trigger an additional time.
        if (event is GameEvent.EntersBattlefield && (event.obj.def.isCreature || "Artifact" in event.obj.def.types)) {
            val extra = triggered.filter { (obj, _) -> state.objects.values.any { p -> p.isOnBattlefield() && p.controller == obj.controller && p.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.ExtraEtbTrigger } } }
            if (extra.isNotEmpty()) { val src = state.objects.values.first { p -> p.isOnBattlefield() && p.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.ExtraEtbTrigger } }; trace.step("${src.name} makes ${extra.joinToString(" and ") { it.first.name + "'s ability" }} trigger an additional time.", "603.2"); triggered += extra }
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
                is GameEvent.PlayerAttacks -> "${state.player(event.playerId).subject.lowercase()} attacking"
                is GameEvent.AttacksAlone -> "${event.obj.name} attacking alone"
                is GameEvent.StepBegins -> "the beginning of ${state.player(event.activePlayer).possessive} ${event.step.replace('_', ' ')}"
                is GameEvent.DamageDealt -> "${event.source.name} dealing ${event.amount} damage to ${state.nameOf(event.target)}"
                is GameEvent.LifeGained -> "${state.player(event.playerId).subject.lowercase()} gaining life"
                is GameEvent.BecomesBlocked -> "${event.obj.name} becoming blocked"
                is GameEvent.Blocks -> "${event.obj.name} blocking"
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
            val causedObject = when (event) { is GameEvent.AttacksAlone -> event.obj.id; is GameEvent.Attacks -> event.obj.id; is GameEvent.EntersBattlefield -> event.obj.id; is GameEvent.Dies -> event.obj.id; is GameEvent.BecomesTarget -> event.obj.id; else -> null }
            putTriggerOnStack(obj, ability, emptyList(), causedBy, causedAmount, causedObject)
        }
        if (ordered.size > 1) trace.step("Multiple abilities triggered at once; they are put on the stack in APNAP order, each player choosing the order among their own.", "603.3b")
    }

    private fun matches(obj: GameObject, trigger: Trigger, event: GameEvent): Boolean = when (trigger) {
        is Trigger.SpellCast -> event is GameEvent.SpellCast && obj.isOnBattlefield() && when (trigger.who) {
            Who.YOU -> event.item.controller == obj.controller
            Who.OPPONENT -> event.item.controller != obj.controller
            else -> true
        } && (trigger.spellFilter == null || filterMatchesSpell(trigger.spellFilter, event.item, obj.controller))
        Trigger.ThisEnters -> event is GameEvent.EntersBattlefield && event.obj === obj
        Trigger.ThisDies -> event is GameEvent.Dies && event.obj === obj
        Trigger.ThisLeavesBattlefield -> (event is GameEvent.LeavesBattlefield || event is GameEvent.Dies) && (event as? GameEvent.LeavesBattlefield)?.obj === obj || (event as? GameEvent.Dies)?.obj === obj
        Trigger.ThisAttacks -> event is GameEvent.Attacks && event.obj === obj
        Trigger.ThisCast -> event is GameEvent.SpellCast && event.item.source === obj
        is Trigger.BeginningOfStep -> event is GameEvent.StepBegins && event.step == trigger.step && obj.isOnBattlefield() && when (trigger.whose) {
            Who.YOU -> event.activePlayer == obj.controller; Who.OPPONENT -> event.activePlayer != obj.controller; else -> true }
        is Trigger.ThisDealsDamage -> event is GameEvent.DamageDealt && event.source === obj && (!trigger.combatOnly || event.combat) &&
            (trigger.toPlayer == null || trigger.toPlayer == (event.target is Ref.Player))
        is Trigger.PermanentEnters -> event is GameEvent.EntersBattlefield && obj.isOnBattlefield() && !(trigger.other && event.obj === obj) && state.matches(trigger.filter, event.obj, obj.controller)
        is Trigger.PermanentDies -> event is GameEvent.Dies && (obj.isOnBattlefield() || event.obj === obj || obj.id in leavingTogether) && !(trigger.other && event.obj === obj) && matchesLki(trigger.filter, event.obj, obj.controller)
        Trigger.YouAttack -> event is GameEvent.PlayerAttacks && event.playerId == obj.controller && obj.isOnBattlefield()
        Trigger.CreatureAttacksAlone -> event is GameEvent.AttacksAlone && event.obj.controller == obj.controller && obj.isOnBattlefield()
        Trigger.YouGainLife -> event is GameEvent.LifeGained && event.playerId == obj.controller && obj.isOnBattlefield()
        Trigger.YouDraw -> event is GameEvent.Drew && event.playerId == obj.controller && obj.isOnBattlefield()
        is Trigger.CardsToYourGraveyard -> event is GameEvent.Dies && obj.isOnBattlefield() && event.obj.owner == obj.controller && matchesLki(trigger.filter, event.obj, obj.controller)
        is Trigger.PlayerDraws -> event is GameEvent.Drew && obj.isOnBattlefield() && when (trigger.who) { Who.YOU -> event.playerId == obj.controller; Who.OPPONENT -> event.playerId != obj.controller; else -> true }
        is Trigger.YouDrawNth -> event is GameEvent.Drew && event.playerId == obj.controller && obj.isOnBattlefield() && state.player(event.playerId).drew == trigger.n
        Trigger.ThisIsDealtDamage -> event is GameEvent.DamageDealt && (event.target as? Ref.Obj)?.id == obj.id
        Trigger.ThisBecomesBlocked -> event is GameEvent.BecomesBlocked && event.obj === obj
        Trigger.ThisBlocks -> event is GameEvent.Blocks && event.obj === obj
        Trigger.ThisBecomesTarget -> event is GameEvent.BecomesTarget && event.obj === obj
        Trigger.ThisBecomesTapped -> event is GameEvent.BecomesTapped && event.obj === obj
        Trigger.ThisCycled -> event is GameEvent.Cycled && event.obj === obj
        is Trigger.PermanentAttacks -> event is GameEvent.Attacks && obj.isOnBattlefield() && state.matches(trigger.filter, event.obj, obj.controller, obj)
        is Trigger.PermanentDealsCombatDamageToPlayer -> event is GameEvent.DamageDealt && event.combat && event.target is Ref.Player && obj.isOnBattlefield() && state.matches(trigger.filter, event.source, obj.controller, obj)
        Trigger.YourCreaturesDealCombatDamageToPlayer -> event is GameEvent.CreaturesDealtCombatDamageToPlayer && event.playerId == obj.controller && obj.isOnBattlefield()
        is Trigger.Unknown -> false
    }

    /** Filter match for something that just left the battlefield (last known information, 603.10a). */
    private fun matchesLki(f: ObjFilter, o: GameObject, controller: String): Boolean {
        val z = o.zone; o.zone = Zone.BATTLEFIELD
        try { return state.matches(f, o, controller) } finally { o.zone = z }
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
                ?: if (spec.filter.kinds == setOf(Kind.PLAYER) && state.opponentsOf(obj.controller).size == 1) {
                    state.clarifications.removeAll { it.about == "${obj.name}'s triggered ability's target" }
                    val opp = state.opponentsOf(obj.controller).single()
                    state.assumptions += "${obj.name}'s triggered ability targets ${state.nameOf(Ref.Player(opp.id))} (\"${spec.raw}\"; assuming the opponent, not ${state.player(obj.controller).subject.lowercase()})."
                    listOf(Ref.Player(opp.id))
                } else null
            if (inferred != null && inferred.isEmpty() && targets.isEmpty()) {
                trace.step("${obj.name}'s triggered ability has no legal target, so it's removed from the stack and does nothing.", "603.3d"); state.outcomes += "${obj.name}'s triggered ability has no legal target and is removed from the stack."; return null
            }
            if (inferred != null) targets = inferred
        }
        if (needed.size > targets.size) {
            if (state.clarifications.none { it.about == "${obj.name}'s triggered ability's target" }) state.clarifications += Clarification("${obj.name}'s trigger target", "${obj.name}'s triggered ability needs a target (${needed.joinToString("; ") { it.raw }}); which? (603.3d)")
            return null
        }
        val item = StackItem(state.newStackId(), StackKind.TRIGGERED, obj.controller, obj, ability.effect, targets, zonesOf(targets), ability.text, causedBy = causedBy, causedAmount = causedAmount, causedObject = causedObject)
        state.stack += item
        state.player(obj.controller).let { p -> trace.step("${p.subject} ${p.v("puts", "put")} ${obj.name}'s triggered ability on the stack${if (state.stack.size > 1) ", above ${state.stack[state.stack.size - 2].describe}" else ""}.", "603.3", "603.3a") }
        if (ability.effect.hasUnparsed()) state.unsupported += Unsupported(obj.name, "Part of the triggered ability is not modeled: " + unparsedText(ability.effect))
        return item
    }

    // ---- effects ---------------------------------------------------------------------------

    private fun applyEffect(effect: Effect, item: StackItem) {
        val you = state.player(item.controller)
        when (effect) {
            is Effect.Seq -> effect.effects.forEach { applyEffect(it, item) }
            is Effect.May -> {
                val chooser = (if (effect.who == Who.YOU) you else resolveWho(effect.who, item)) ?: you
                val what = describe(effect.effect, item).let { d -> if (chooser.you) d.replace("their library", "your library").replace("their hand", "your hand").replace("their graveyard", "your graveyard") else d }
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
                forEachLegalTarget(item, effect.target) { applyDamage(item.source.name, it, sacAmount ?: if (effect.x) (item.x ?: 0) else if (item.kicked && effect.kickedAmount != null) effect.kickedAmount else effect.amount) }
            }
            is Effect.Proliferate -> {
                val you = state.player(item.controller)
                val objs = state.objects.values.filter { it.isOnBattlefield() && it.counters.values.any { n -> n > 0 } && it.controller == item.controller }
                val players = state.players.filter { it.poison > 0 && it.id != item.controller }
                if (objs.isEmpty() && players.isEmpty()) trace.step("Nothing ${you.subject.lowercase()} would want to proliferate has a counter.", "701.34a")
                for (o in objs) { o.counters.keys.toList().forEach { k -> o.counters[k] = o.counters.getValue(k) + 1 }; trace.step("${you.subject} ${you.v("proliferates", "proliferate")} ${o.name}: one more of each kind of counter it has (${o.counters.entries.joinToString(", ") { "${it.value} ${it.key}" }}).", "701.34a"); state.outcomes += "${o.name} has ${o.counters.entries.joinToString(", ") { "${it.value} ${it.key}" }} counters." }
                for (p in players) { p.poison += 1; trace.step("${p.subject} ${p.v("gets", "get")} another poison counter (${p.poison}).", "701.34a"); state.outcomes += "${p.subject} ${p.v("has", "have")} ${p.poison} poison counters." }
                if (objs.isNotEmpty() || players.isNotEmpty()) state.assumptions += "Proliferate: ${you.subject.lowercase()} ${you.v("chooses", "choose")} all ${you.possessive} own permanents with counters${if (players.isNotEmpty()) " and each opponent with poison counters" else ""} (701.34a lets ${you.subject.lowercase()} choose any number)."
                stateBasedActions()
            }
            is Effect.ForAllTargeted -> forEachLegalTarget(item, effect.target) { ref ->
                val pid = (ref as? Ref.Player)?.id ?: return@forEachLegalTarget
                val affected = state.objects.values.filter { it.controller == pid && state.matches(effect.filter, it, pid) }
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
            }
            is Effect.Counter -> forEachLegalTarget(item, effect.target) { ref ->
                val target = (ref as? Ref.Stack)?.let { state.stackItem(it.id) } ?: (ref as? Ref.Obj)?.let { r -> state.stack.firstOrNull { it.source.id == r.id } }
                if (target == null) { trace.step("${state.nameOf(ref)} is no longer on the stack, so it can't be countered.", "701.6a"); return@forEachLegalTarget }
                if (target.kind == StackKind.SPELL && cant(target.source, "be countered")) { trace.step("${target.describe} can't be countered, so ${item.describe} has no effect on it.", "701.6a"); return@forEachLegalTarget }
                state.stack.remove(target); state.lastCountered = target.source
                if (target.kind == StackKind.SPELL) target.source.zone = Zone.GRAVEYARD
                trace.step("${target.describe} is countered: it's removed from the stack and none of its effects happen" + (if (target.kind == StackKind.SPELL) "; the card goes to its owner's graveyard" else "") + ".", "701.6a")
                state.outcomes += "${target.describe} is countered."
            }
            is Effect.Destroy -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { destroy(it, "${it.name} is destroyed and put into its owner's graveyard.", "701.8a", canRegenerate = !effect.noRegen) } }
            is Effect.Bounce -> {
                val bounce: (GameObject) -> Unit = { o -> move(o, Zone.HAND, "${o.name} is returned to its owner's hand. It becomes a new object with no memory of its previous existence.", "400.7") }
                if (effect.target == null) { if (item.source.isOnBattlefield() || item.source.zone == Zone.GRAVEYARD) bounce(item.source) else trace.step("${item.source.name} isn't on the battlefield or in a graveyard, so there's nothing to return.", "400.7") }
                else forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let(bounce) }
            }
            is Effect.DamagePlayer -> for (p in resolvePlayers(effect.who, item)) applyDamage(item.source.name, Ref.Player(p.id), effect.amount, item.source)
            is Effect.PumpCausing -> {
                val o = item.causedObject?.let { state.objects[it] }
                if (o == null || !o.isOnBattlefield()) trace.step("The creature that caused the trigger isn't on the battlefield, so nothing gets the bonus.", "611.2c")
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
                val x = when (val c = effect.count) { is CountExpr.Permanents -> state.objects.values.count { state.matches(c.filter, it, item.controller) }; is CountExpr.Unknown -> null }
                if (x == null) { state.unsupported += Unsupported(item.describe, "Couldn't count X."); return }
                trace.step("X is $x (counted as the effect resolves).", "608.2h")
                applyEffect(Effect.PumpAll(effect.filter, x, x, effect.keywords), item)
            }
            is Effect.ShuffleIntoLibrary -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { o -> move(o, Zone.LIBRARY, "${o.name} is shuffled into its owner's library.", "701.24a", "400.7") } }
            is Effect.CreateToken -> {
                val who = resolveWho(effect.who, item) ?: run { state.unsupported += Unsupported(item.describe, "Couldn't work out who creates the token."); return }
                val def = Generic.token(effect.token) ?: run { state.unsupported += Unsupported(item.describe, "Couldn't read the token \"${effect.token}\"."); return }
                var n = effect.countBy?.let { c -> when (c) { is CountExpr.Permanents -> state.objects.values.count { state.matches(c.filter, it, item.controller) }.also { trace.step("X is $it: the number of ${c.filter.raw} ${who.subject.lowercase()} ${who.v("controls", "control")} as the ability resolves.", "608.2h") }; is CountExpr.Unknown -> { state.clarifications += Clarification("${item.describe}'s X", "X is \"${c.text}\", which isn't tracked; assuming 0."); 0 } } } ?: effect.count
                state.objects.values.filter { it.isOnBattlefield() && it.controller == who.id }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.TokenMultiplier }.map { o to it } }
                    .forEach { (o, m) -> trace.step("${o.name} replaces the token creation: ${n * m.factor} tokens instead of $n.", "614.1a", "614.6"); n *= m.factor }
                repeat(n) {
                    val t = state.add(GameObject(freshObjectId(def.name), def, Zone.BATTLEFIELD, who.id, token = true)); t.timestamp = state.tick(); t.summoningSick = def.isCreature
                    trace.step("${who.subject} ${who.v("creates", "create")} a ${def.name}${if (def.isCreature) " (${state.describePt(t)})" else ""}; it enters the battlefield under ${who.possessive} control.", "701.7a", "111.1")
                    state.outcomes += "${who.subject} ${who.v("gets", "get")} a ${def.name}."
                    onEvent(GameEvent.EntersBattlefield(t))
                }
            }
            is Effect.SacrificeSource -> {
                val o = item.source; val p = state.player(o.controller)
                if (!o.isOnBattlefield()) trace.step("${o.name} is no longer on the battlefield, so there's nothing to sacrifice.", "701.21a")
                else { move(o, Zone.GRAVEYARD, "${p.subject} ${p.v("sacrifices", "sacrifice")} ${o.name}: it goes to its owner's graveyard. Sacrificing isn't destroying, so indestructible and regeneration don't help.", "701.21a"); state.outcomes += "${o.name} is sacrificed." }
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
                val chosen = (item.choice ?: state.pendingChoices.remove(item.source.id))?.let { c -> state.objects[c] } ?: state.objects.values.firstOrNull { it.zone == fromZone && it.controller == item.controller && state.matches(effect.filter, it, item.controller, anyZone = true) }
                    // A basic land fetched from the library: nobody needs to name it; assume one is there.
                    ?: if (effect.fromLibrary && effect.filter.raw.contains("basic land", true)) Generic.spell("basic land")?.let { def -> state.add(GameObject(freshObjectId("basic land"), def, Zone.LIBRARY, item.controller)).also { state.assumptions += "${item.source.name} finds a basic land (${you.possessive} library has one)." } } else null
                if (chosen == null) { state.clarifications += Clarification("${item.source.name}'s card", "${item.source.name} puts ${withArticle(effect.filter.raw)} from ${you.possessive} $zoneName onto the battlefield; which card? (none was named, so nothing is put)"); trace.step("No ${effect.filter.raw} in ${you.possessive} $zoneName was named for ${item.source.name}; nothing is put onto the battlefield${if (effect.fromLibrary) " (the library is still shuffled)" else ""}.") }
                else if (chosen.zone != fromZone) trace.step("${chosen.name} isn't in ${you.possessive} $zoneName, so ${item.source.name} can't put it onto the battlefield.", "608.2b")
                else if (effect.maxMv != null && chosen.def.manaValue.toInt() > effect.maxMv) { trace.step("${chosen.name} has mana value ${chosen.def.manaValue.toInt()}, more than ${effect.maxMv}, so ${item.source.name} can't find it.", "202.3"); state.outcomes += "${chosen.name} can't be put onto the battlefield (mana value too high)." }
                else if (!state.matches(effect.filter, chosen, item.controller, anyZone = true)) { trace.step("${chosen.name} isn't a ${effect.filter.raw}, so ${item.source.name} can't put it onto the battlefield.", "608.2b"); state.outcomes += "${chosen.name} stays in hand." }
                else {
                    val counters = effect.mvEqualsCounters?.let { item.source.counters[it] ?: 0 }
                    if (counters != null && chosen.def.manaValue.toInt() != counters) { trace.step("${item.source.name} has $counters ${effect.mvEqualsCounters} counter${if (counters == 1) "" else "s"} but ${chosen.name}'s mana value is ${chosen.def.manaValue.toInt()}, so it can't be put onto the battlefield with it.", "202.3"); state.outcomes += "${chosen.name} stays in hand (mana value ${chosen.def.manaValue.toInt()} ≠ $counters counters)." }
                    else {
                        trace.step("${you.subject} ${you.v("puts", "put")} ${chosen.name} from ${you.possessive} hand onto the battlefield${if (counters != null) " (its mana value ${chosen.def.manaValue.toInt()} matches the $counters counters)" else ""}. It's put there directly rather than cast, so it never was a spell: it can't be countered and 'whenever you cast' abilities don't trigger.", "608.2c", *(if (counters != null) arrayOf("202.3") else emptyArray()))
                        val host = chosen.attachedTo?.let { state.objects[it] }
                        if (chosen.def.isAura && host != null) trace.step("${chosen.name} is an Aura entering without being cast: ${you.subject.lowercase()} ${you.v("chooses", "choose")} what it enchants as it enters (it doesn't target, so hexproof and shroud don't stop it): ${host.name}.", "303.4f")
                        else if (chosen.def.isAura) { chosen.attachedTo = null; state.clarifications += Clarification("${chosen.name}'s host", "${chosen.name} enters the battlefield without being cast; what does it enchant? (303.4f)") }
                        enter(chosen.id); if (chosen.def.isAura && host != null) applyControlEnchanted(chosen); state.outcomes += "${chosen.name} enters the battlefield${host?.let { " attached to ${it.name}" } ?: ""}."
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
                if (effect.target == null) { state.shields += Shield(r, null, if (r.toPlayer == Who.YOU) item.controller else null, r.amount, item.describe); trace.step("${item.describe} creates a prevention effect until end of turn: prevent ${r.amount?.toString() ?: "all"}${if (r.combatOnly) " combat" else ""} damage that would be dealt${r.from?.let { " by ${it.raw}" } ?: ""}${r.toPlayer?.let { " to " + (if (it == Who.YOU) you.subject.lowercase() else "players") } ?: r.to?.let { " to ${it.raw}" } ?: ""}.", "615.1", "615.7", "611.2a"); state.outcomes += "Prevention effect until end of turn (${item.describe})." }
                else forEachLegalTarget(item, effect.target) { ref -> state.shields += Shield(r, (ref as? Ref.Obj)?.id, (ref as? Ref.Player)?.id, r.amount, item.describe); trace.step("${state.nameOf(ref)} gets a prevention shield: the next ${r.amount?.toString() ?: "all"} damage that would be dealt to it this turn is prevented.", "615.7", "615.1"); state.outcomes += "${state.nameOf(ref)} has a prevention shield (${r.amount?.toString() ?: "all"}) this turn." }
            }
            is Effect.Exile -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { move(it, Zone.EXILE, "${it.name} is exiled.", "701.13a") } }
            is Effect.Tap -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { trace.step("${it.name} becomes tapped.", "701.26a"); tap(it); state.outcomes += "${it.name} is tapped." } }
            is Effect.Untap -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { it.tapped = false; trace.step("${it.name} becomes untapped.", "701.26b"); state.outcomes += "${it.name} is untapped." } }
            is Effect.Pump -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let {
                it.pumps += effect.power to effect.toughness
                trace.step("${it.name} gets ${signed(effect.power)}/${signed(effect.toughness)} until end of turn; it's now ${it.power}/${it.toughness}.", "611.2a")
                state.outcomes += "${it.name} is ${it.power}/${it.toughness} until end of turn."
            } }
            is Effect.GainKeywords -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let {
                val kws = effect.keywords.map { k -> if (k == "protection from the color of your choice") "protection from ${item.choice ?: run { state.clarifications += Clarification("${item.describe}'s colour", "${item.describe} grants protection from a colour of your choice; which colour? (assuming none)"); "nothing" }}" else k }
                if (kws != effect.keywords) { trace.step("${state.player(item.controller).subject} ${state.player(item.controller).v("chooses", "choose")} ${item.choice ?: "no colour"}.", "608.2c"); it.tempKeywords += kws; trace.step("${it.name} gains ${kws.joinToString(" and ")} until end of turn.", "611.2a"); state.outcomes += "${it.name} has ${kws.joinToString(" and ")} until end of turn."; return@let }
                it.tempKeywords += effect.keywords
                trace.step("${it.name} gains ${effect.keywords.joinToString(" and ")} until end of turn.", "611.2a")
                state.outcomes += "${it.name} has ${effect.keywords.joinToString(" and ")} until end of turn."
            } }
            is Effect.GainLife -> resolvePlayers(effect.who, item).forEach { p -> gainLife(p, effect.amount) }
            is Effect.LoseLife -> resolvePlayers(effect.who, item).forEach { p -> p.life = p.life?.minus(effect.amount); trace.step("${p.subject} ${p.v("loses", "lose")} ${effect.amount} life${p.life?.let { " ($it)" } ?: ""}.", "119.3"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} ${effect.amount} life." }
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
                affected.forEach { state.outcomes += "${it.name} is ${it.power}/${it.toughness} until end of turn." }
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
                val put: (GameObject) -> Unit = { o -> val n = countersPlaced(o, effect.count, effect.kind); o.counters[effect.kind] = (o.counters[effect.kind] ?: 0) + n; trace.step("$n ${effect.kind} counter${if (n > 1) "s are" else " is"} put on ${o.name}${if (o.def.isCreature) "; it's now ${o.power}/${o.toughness}" else ""}.", "122.1a", "122.6"); state.outcomes += "${o.name} has ${o.counters[effect.kind]} ${effect.kind} counter${if (o.counters[effect.kind]!! > 1) "s" else ""}." }
                if (effect.all != null) { val affected = state.objects.values.filter { state.matches(effect.all, it, item.controller, item.source) }; if (affected.isEmpty()) trace.step("No permanents match \"${effect.all.raw}\", so no counters are put anywhere.", "122.6") else affected.forEach(put) }
                else if (effect.target == null) { if (item.source.isOnBattlefield()) put(item.source) else trace.step("${item.source.name} isn't on the battlefield, so no counters are put on it.", "122.6") }
                else forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let(put) }
                if (effect.kind == "+1/+1" || effect.kind == "-1/-1") stateBasedActions()
            }
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
            is Effect.GainControl -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { o ->
                val was = state.player(o.controller); o.controller = item.controller
                trace.step("${state.player(item.controller).subject} ${state.player(item.controller).v("gains", "gain")} control of ${o.name}${if (effect.untilEndOfTurn) " until end of turn" else ""} (it was ${was.possessive}). A control-changing effect applies in layer 2; the permanent doesn't change zones, so it isn't summoning sick only if it has haste or has been under its new controller's control since the turn began.", "613.1b", "611.2a", "302.6")
                if (o.def.isCreature) o.summoningSick = true
                state.outcomes += "${state.player(item.controller).subject} ${state.player(item.controller).v("controls", "control")} ${o.name}${if (effect.untilEndOfTurn) " until end of turn" else ""}."
            } }
            is Effect.GainKeywordsSelf -> { val o = item.source; if (o.isOnBattlefield()) { o.tempKeywords += effect.keywords; trace.step("${o.name} gains ${effect.keywords.joinToString(" and ")} until end of turn.", "611.2a"); state.outcomes += "${o.name} has ${effect.keywords.joinToString(" and ")} until end of turn." } }
            is Effect.Modal -> {
                val chosen = item.modes
                if (chosen.isEmpty()) {
                    state.clarifications += Clarification("${item.describe}'s mode", "${item.describe} is modal (choose ${effect.count}): " + effect.modeTexts.mapIndexed { i, t -> "[${i + 1}] $t" }.joinToString("; ") + ". Which mode(s)? (700.2a: chosen as it's cast)")
                    trace.step("${item.describe} is modal; its mode was chosen as it was cast. Not told which, so its effect isn't applied.", "700.2a", "601.2b")
                } else for (mi in chosen) { effect.modes.getOrNull(mi - 1)?.let { trace.step("Mode ${mi}: ${effect.modeTexts[mi - 1]}.", "700.2a"); applyEffect(it, item) } ?: run { state.clarifications += Clarification("mode", "Mode $mi doesn't exist on ${item.describe}.") } }
            }
            is Effect.AddMana -> trace.step("${describeManaEffect(effect)}.", "605.1a")
            is Effect.Narrated -> {
                state.lastCountered?.let { c -> if (effect.text.contains("that spell's mana value")) { val mv = c.def.manaValue.toInt(); trace.step("That spell was ${c.name}, mana value $mv. ${effect.text.replace("that spell's mana value", "$mv").replaceFirstChar { it.uppercase() }} (a delayed triggered ability created as ${item.source.name} resolves).", "202.3", "608.2h", *effect.rules.toTypedArray()); state.outcomes += "${item.source.name}: ${effect.text.replace("that spell's mana value", "$mv (${c.name}'s mana value)").replace("~", item.source.name).replaceFirstChar { it.uppercase() }.trimEnd('.')}."; return } }
                // Text with its own subject ("You choose…", "That player discards…", "Its controller may…") is quoted as the instruction it is.
                val ownSubject = Regex("""^(you|that player|its controller|each|the|its|their|if|search)\b""", RegexOption.IGNORE_CASE).containsMatchIn(effect.text)
                if (ownSubject) trace.step("${item.source.name}: \"${effect.text.replace("~", item.source.name).replaceFirstChar { it.uppercase() }.trimEnd('.')}.\" (${you.subject} ${you.v("carries", "carry")} this out; the details aren't tracked here.)", *effect.rules.toTypedArray())
                else trace.step("${you.subject} ${effectText(effect.text, item)}.", *effect.rules.toTypedArray())
                state.outcomes += "${item.source.name}: ${effect.text.replace("~", item.source.name).replaceFirstChar { it.uppercase() }.trimEnd('.')} (not tracked in detail)."
            }
            is Effect.ForAll -> {
                val affected = state.objects.values.filter { state.matches(effect.filter, it, item.controller) }
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
                    "damage" -> applyDamage(item.source.name, Ref.Obj(o.id), effect.amount, item.source)
                } } finally { leavingTogether = emptySet() }
            }
            is Effect.Unparsed -> trace.step("(Not modeled: \"${effect.text}\")")
        }
    }

    /** "Enters tapped" / "enters with N counters": replacement effects that modify how it enters (614.1c, 614.12). */
    private fun applyEntersReplacements(o: GameObject) {
        if (o.def.isPlaneswalker && o.def.loyalty != null) { val n = countersPlaced(o, o.def.loyalty, "loyalty"); o.counters["loyalty"] = n; trace.step("${o.name} enters with $n loyalty counters.", "306.5b"); state.outcomes += "${o.name} has $n loyalty." }
        for (e in o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }) when (e) {
            is StaticEffect.EntersTapped -> {
                if (e.unless != null && state.conditionHolds(e.unless, o)) trace.step("${o.name} would enter tapped unless its condition is met; it is, so it enters untapped.", "614.1c", "614.12")
                else { o.tapped = true; trace.step("${o.name} enters tapped (a replacement effect on how it enters${if (e.unless != null) "; its condition isn't met" else ""}).", "614.1c", "614.12") }
            }
            is StaticEffect.EntersWithCounters -> if (e.count != null) { val n = countersPlaced(o, e.count, e.kind); o.counters[e.kind] = (o.counters[e.kind] ?: 0) + n; trace.step("${o.name} enters with $n ${e.kind} counter${if (n > 1) "s" else ""} on it.", "614.1c", "122.6") }
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
        obj.zone = to; obj.damage = 0; obj.pumps.clear(); obj.tempKeywords.clear(); obj.basePt = null; obj.tapped = false; obj.attacking = null; obj.blocking = null; obj.dealtDeathtouchDamage = false
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
        val own = o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.Cant && it.by == null && it.applies == null && !it.powerAboveHand && (it.what == what || it.what == "attack or block" && (what == "attack" || what == "block")) }
        if (own) return true
        // "Enchanted creature can't attack or block" and the like, from other permanents.
        return state.objects.values.filter { it.isOnBattlefield() }.any { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { e -> e is StaticEffect.Cant && e.applies != null && (e.what == what || e.what == "attack or block" && (what == "attack" || what == "block")) && state.matches(e.applies, o, src.controller, src) } }
    }
    private fun cantSource(o: GameObject, what: String): String? = state.objects.values.filter { it.isOnBattlefield() && it !== o }.firstOrNull { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { e -> e is StaticEffect.Cant && e.applies != null && (e.what == what || e.what == "attack or block") && state.matches(e.applies, o, src.controller, src) } }?.name

    /** "~ can't be blocked by [filter]": the restriction that forbids this particular blocker, if any. */
    private fun cantBeBlockedBy(a: GameObject, b: GameObject): StaticEffect.Cant? = a.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.Cant>().firstOrNull { it.what == "be blocked" && it.by != null && state.matches(it.by, b, b.controller) }

    private fun describeManaEffect(e: Effect): String = when (e) { is Effect.AddMana -> "add ${e.text}"; is Effect.Seq -> e.effects.joinToString(", then ") { describeManaEffect(it) }; else -> e.toString().lowercase() }
    /** "~" -> the source's name; first letter lowercased for use after a subject. */
    private fun effectText(t: String, item: StackItem) = t.replace("~", item.source.name).replaceFirstChar { it.lowercase() }

    /** Applicable prevention effects for damage from [source] to [target]: static ones from the battlefield plus shields. */
    private fun preventionFor(source: GameObject?, target: Ref, combat: Boolean): List<Pair<String, Any>> {
        val out = mutableListOf<Pair<String, Any>>()
        val tObj = (target as? Ref.Obj)?.let { state.objects[it.id] }
        val tPlayer = (target as? Ref.Player)?.let { state.player(it.id) }
        fun applies(r: Replacement.PreventDamage, owner: GameObject?, ownerPlayer: String?, shield: Shield?): Boolean {
            if (r.combatOnly && !combat) return false
            if (r.fromSelf && source !== owner) return false
            if (r.from != null && (source == null || !state.matches(r.from, source, ownerPlayer ?: "", owner))) return false
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
        if (prevention.isNotEmpty()) {
            for ((name, p) in prevention) {
                if (amount <= 0) break
                when (p) {
                    is Replacement.PreventDamage -> { trace.step("$name prevents ${if (p.amount == null) "all" else p.amount.toString()} of the $amount damage $sourceName would deal to ${state.nameOf(target)}.", "615.1", "615.6"); amount = if (p.amount == null) 0 else maxOf(0, amount - p.amount) }
                    is Shield -> { val r = p.replacement as Replacement.PreventDamage; val prevented = if (r.amount == null) amount else minOf(amount, p.remaining ?: 0); trace.step("$name's prevention shield prevents $prevented of the $amount damage $sourceName would deal to ${state.nameOf(target)}.", "615.7", "615.6"); amount -= prevented; if (r.amount != null) p.remaining = (p.remaining ?: 0) - prevented }
                }
            }
            if (amount <= 0) { state.outcomes += "Damage to ${state.nameOf(target)} from $sourceName is prevented."; return 0 }
        }
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
        when (target) {
            is Ref.Player -> { val p = state.player(target.id)
                if (infect) { p.poison += amount; trace.step("$sourceName has infect, so instead of losing life ${if (p.you) "you get" else p.name + " gets"} $amount poison counter${if (amount > 1) "s" else ""} (${p.poison} total).", "702.90b", "120.3b"); state.outcomes += "${p.subject} ${p.v("has", "have")} ${p.poison} poison counter${if (p.poison > 1) "s" else ""}." }
                else { p.life = p.life?.minus(amount); trace.step("$sourceName deals $amount damage to ${if (p.you) "you" else p.name}, ${if (p.you) "and you lose" else "who loses"} $amount life${p.life?.let { " ($it)" } ?: ""}.", "120.3a"); state.outcomes += "${p.subject} ${p.v("takes", "take")} $amount damage." }
                val toxic = source?.takeIf { inCombatDamage }?.let { src -> Regex("""\bToxic (\d+)""").findAll(src.def.oracleText).sumOf { it.groupValues[1].toInt() } } ?: 0
                if (source?.commander == true && inCombatDamage) { val total = (p.commanderDamage[source.id] ?: 0) + amount; p.commanderDamage[source.id] = total; trace.step("${source.name} is a commander: ${if (p.you) "you have" else p.name + " has"} now been dealt $total combat damage by it this game (21 or more loses the game).", "903.10a"); state.outcomes += "${p.subject} ${p.v("has", "have")} taken $total commander damage from ${source.name}." }
                if (toxic > 0) { p.poison += toxic; trace.step("$sourceName has toxic $toxic, so ${if (p.you) "you also get" else p.name + " also gets"} $toxic poison counter${if (toxic > 1) "s" else ""} (${p.poison} total).", "702.164c", "120.3g"); state.outcomes += "${p.subject} ${p.v("has", "have")} ${p.poison} poison counter${if (p.poison > 1) "s" else ""}." } }
            is Ref.Obj -> { val o = state.obj(target.id)
                if (o.def.isPlaneswalker) { val before = o.counters["loyalty"] ?: 0; o.counters["loyalty"] = maxOf(0, before - amount); trace.step("$sourceName deals $amount damage to ${o.name}, so $amount loyalty counters are removed from it (${o.counters["loyalty"]} left).", "306.8", "120.3c"); state.outcomes += "${o.name} has ${o.counters["loyalty"]} loyalty." }
                else if (infect || wither) { o.counters["-1/-1"] = (o.counters["-1/-1"] ?: 0) + amount; trace.step("$sourceName has ${if (infect) "infect" else "wither"}, so the $amount damage to ${o.name} is dealt as $amount -1/-1 counter${if (amount > 1) "s" else ""}; it's now ${o.power}/${o.toughness}.", if (infect) "702.90c" else "702.80a", "120.3d"); state.outcomes += "${o.name} has ${o.counters["-1/-1"]} -1/-1 counter(s)." }
                else { o.damage += amount; trace.step("$sourceName deals $amount damage to ${o.name}; it now has ${o.damage} damage marked (toughness ${o.toughness ?: "?"}).", "120.3e"); state.outcomes += "${o.name} has ${o.damage} damage marked." } }
            is Ref.Stack -> { state.unsupported += Unsupported(sourceName, "Damage can't be dealt to something on the stack."); return 0 }
        }
        if (source != null && target !is Ref.Stack) onEvent(GameEvent.DamageDealt(source, target, amount, inCombatDamage))
        return amount
    }

    /** Doubling Season and friends: how many counters actually land on [o] when [n] would be placed. */
    private fun countersPlaced(o: GameObject, n: Int, kind: String): Int {
        var out = n
        state.objects.values.filter { it.isOnBattlefield() }.flatMap { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.CounterMultiplier }.filter { it.anyPlayer || src.controller == o.controller }.map { src to it } }
            .forEach { (src, m) -> if (m.factor == 0) { trace.step("${src.name} says counters can't be put on ${o.name}, so the $out $kind counter${if (out == 1) "" else "s"} ${if (out == 1) "isn't" else "aren't"} placed.", "614.1a", "122.1"); out = 0 } }
        state.objects.values.filter { false }.flatMap { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.CounterMultiplier }.map { src to it } }
            .forEach { (src, m) -> trace.step("${src.name} replaces the counter placement: ${out * m.factor} $kind counters are put on ${o.name} instead of $out.", "614.1a", "614.6"); out *= m.factor }
        if (out > 0) state.objects.values.filter { it.isOnBattlefield() && it.controller == o.controller }.flatMap { src -> src.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.CounterMultiplier }.filter { it.factor > 1 }.map { src to it } }
            .forEach { (src, m) -> trace.step("${src.name} replaces the counter placement: ${out * m.factor} $kind counters are put on ${o.name} instead of $out.", "614.1a", "614.6"); out *= m.factor }
        return out
    }

    private fun gainLife(p: Player, amount: Int) {
        var n = amount
        state.objects.values.filter { it.isOnBattlefield() }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.LifeGainMultiplier }.filter { it.anyPlayer || o.controller == p.id }.map { o to it } }
            .forEach { (o, m) -> trace.step("${o.name} replaces the life gain: ${p.subject.lowercase()} ${p.v("gains", "gain")} ${n * m.factor} life instead of $n.", "614.1a", "614.6"); n *= m.factor }
        if (n == 0) { trace.step("${p.subject} ${p.v("gains", "gain")} no life.", "119.3"); return }
        p.life = p.life?.plus(n)
        trace.step("${p.subject} ${p.v("gains", "gain")} $n life${p.life?.let { " ($it)" } ?: ""}.", "119.3")
        state.outcomes += "${p.subject} ${p.v("gains", "gain")} $n life."
        onEvent(GameEvent.LifeGained(p.id, n))
    }

    private var inCombatDamage = false

    /** "If X would die, exile it instead" (614.1a) and Rest-in-Peace style effects: the replaced destination, with the source's name. */
    private fun graveyardReplacement(obj: GameObject, from: Zone): Pair<Zone, String>? {
        for (o in state.objects.values) {
            if (!o.isOnBattlefield()) continue
            for (e in o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }) {
                val r = (e as? StaticEffect.Replace)?.replacement as? Replacement.GraveyardReplacement ?: continue
                if (r.self && o !== obj) continue
                if (!r.self && !r.fromAnywhere && from != Zone.BATTLEFIELD) continue
                if (!r.self && !(if (from == Zone.BATTLEFIELD) state.matches(r.filter, obj, o.controller, o) else matchesLki(r.filter, obj, o.controller))) continue
                if (r.filter.other && o === obj) continue
                if (r.filter.controller == Who.OPPONENT && obj.owner == o.controller) continue
                val zone = when (r.instead) { "exile" -> Zone.EXILE; "hand" -> Zone.HAND; else -> Zone.LIBRARY }
                return zone to o.name
            }
        }
        return null
    }

    /** What a "nonbasic lands are Mountains" permanent (Blood Moon) does to the nonbasic lands on the battlefield, said once. */
    fun narrateLandTypeSetters(only: GameObject? = null) {
        val moons = state.objects.values.filter { it.isOnBattlefield() && it.def.abilities.filterIsInstance<StaticAbility>().flatMap { e -> e.effects }.any { e -> e is StaticEffect.NonbasicLandsAreMountains } && (only == null || it === only) }
        for (moon in moons) for (land in state.objects.values.filter { it.isOnBattlefield() && "Land" in it.def.types && "Basic" !in it.def.supertypes }) {
            val saga = "Saga" in land.def.subtypes
            trace.step("${moon.name} makes ${land.name} a Mountain: it loses its other land types and every ability from its rules text${if (saga) ", chapter abilities included," else ""} and has only \"{T}: Add {R}\" (a type-changing effect, layer 4).${if (saga) " It's still an enchantment and a Saga; its lore counters stay, and with no chapter abilities it is neither sacrificed nor able to do anything." else ""}", "613.1d", "305.7", *(if (saga) arrayOf("714.4") else emptyArray()))
            state.outcomes += "${land.name} is a Mountain with no abilities (${moon.name})."
            state.unsupported.removeAll { it.what == land.name }
        }
    }

    private fun move(obj: GameObject, to: Zone, text: String, vararg rules: String) {
        if (to == Zone.GRAVEYARD) graveyardReplacement(obj, obj.zone)?.let { (zone, by) ->
            val from = obj.zone
            trace.step("$text But $by replaces that: instead of going to the graveyard, ${obj.name} is put into ${zoneName(zone, obj)}. The graveyard event never happens, so nothing triggers on it.", *rules, "614.1a", "614.6")
            moveRaw(obj, zone); state.outcomes += "${obj.name}: ${zoneName(from, obj)} → ${zoneName(zone, obj)} (replaced by $by)."
            if (from == Zone.BATTLEFIELD) onEvent(GameEvent.LeavesBattlefield(obj))
            return
        }
        val from = obj.zone
        moveRaw(obj, to)
        trace.step(text, *rules)
        state.outcomes += "${obj.name}: ${zoneName(from, obj)} → ${zoneName(to, obj)}."
        if (from == Zone.BATTLEFIELD) {
            if (to == Zone.GRAVEYARD) onEvent(GameEvent.Dies(obj)) else onEvent(GameEvent.LeavesBattlefield(obj))
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
        for (o in state.objects.values) if (o.zone == Zone.BATTLEFIELD || o.zone == Zone.STACK) { val r = Ref.Obj(o.id); if (filterMatches(spec.filter, r, controller)) candidates += r }
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
            if (distinct.size > 1) { val fighting = distinct.filter { r -> r is Ref.Obj && state.objects[r.id]?.let { it.attacking != null || it.blocking != null } == true }; if (fighting.size == 1) distinct = fighting }
            if (distinct.size == 1) { state.assumptions += "$what targets ${state.nameOf(distinct[0])}: of the legal targets, it's the one ${state.player(controller).subject.lowercase()} would help (own creature${if (state.objects[(distinct[0] as Ref.Obj).id]?.let { it.attacking != null || it.blocking != null } == true) ", in combat" else ""}); say so if it's another."; return distinct }
        }
        // Destroying, exiling or damaging: nobody aims that at their own things when an opponent's qualify.
        if (harmful && distinct.size > 1) {
            val theirs = distinct.filter { r -> when (r) { is Ref.Obj -> state.objects[r.id]?.controller != controller; is Ref.Player -> r.id != controller; is Ref.Stack -> state.stackItem(r.id)?.controller != controller } }
            if (theirs.isNotEmpty() && theirs.size < distinct.size) { distinct = theirs; if (theirs.size == 1) { state.assumptions += if (theirs[0] is Ref.Player) "$what targets ${state.nameOf(theirs[0])} (\"${spec.raw}\" with no target named; assuming the opponent)." else "$what targets ${state.nameOf(theirs[0])}: of the legal targets for \"${spec.raw}\", it's the only one an opponent controls."; return theirs } }
        }
        return when (distinct.size) {
            1 -> { state.assumptions += "$what targets ${state.nameOf(distinct[0])}, the only legal target for \"${spec.raw}\" in this situation."; distinct }
            0 -> null
            else -> {
                // Nothing but players to choose from (or "any target" with only players around): the opponent is the sensible default.
                val opp = state.opponentsOf(controller).singleOrNull()
                if (opp != null && distinct.all { it is Ref.Player }) { state.assumptions += "$what targets ${state.nameOf(Ref.Player(opp.id))} (\"${spec.raw}\" with no target named; assuming the opponent)."; listOf(Ref.Player(opp.id)) }
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
            else if (!filterMatches(spec.filter, ref, item.controller)) trace.step("Note: ${state.nameOf(ref)} doesn't look like a legal target for \"${spec.raw}\" (601.2c); proceeding as described.", "601.2c")
        }
    }

    private fun isTargetLegal(item: StackItem, ref: Ref): Boolean {
        val spec = specFor(item, ref) ?: return true
        return when (ref) {
            is Ref.Player -> !state.player(ref.id).lost
            is Ref.Stack -> state.stackItem(ref.id) != null
            is Ref.Obj -> { val o = state.objects[ref.id] ?: return false; o.zone == item.targetZones[ref.id] && (!spec.filter.verifiable || filterMatches(spec.filter, ref, item.controller)) && targetingProblem(item.source, item.controller, ref) == null }
        }
    }

    private fun whyIllegal(item: StackItem, ref: Ref): String = when (ref) {
        is Ref.Stack -> "${state.nameOf(ref)} has left the stack"
        is Ref.Obj -> { val o = state.objects[ref.id]; if (o == null || o.zone != item.targetZones[ref.id]) "${state.nameOf(ref)} left ${zoneName(item.targetZones[ref.id] ?: Zone.BATTLEFIELD, o)}" else targetingProblem(item.source, item.controller, ref)?.first ?: "${state.nameOf(ref)} no longer matches \"${specFor(item, ref)?.raw}\"" }
        is Ref.Player -> "${state.nameOf(ref)} has left the game"
    }

    /** The item's effect with chosen modes substituted (700.2). */
    private fun effectiveEffect(item: StackItem): Effect? = (item.effect as? Effect.Modal)?.let { m -> item.modes.mapNotNull { i -> m.modes.getOrNull(i - 1) }.let { if (it.isEmpty()) item.effect else Effect.Seq(it) } } ?: item.effect
    private fun specFor(item: StackItem, ref: Ref): TargetSpec? { val specs = effectiveEffect(item)?.targets() ?: return null; val i = item.targets.indexOf(ref); return specs.getOrNull(i) }

    private inline fun forEachLegalTarget(item: StackItem, spec: TargetSpec, block: (Ref) -> Unit) {
        val specs = effectiveEffect(item)?.targets() ?: emptyList()
        val idx = specs.indexOf(spec)
        val ref = item.targets.getOrNull(idx) ?: run { state.unsupported += Unsupported(item.describe, "No target was given for \"${spec.raw}\"."); return }
        if (isTargetLegal(item, ref)) block(ref) else trace.step("${state.nameOf(ref)} is an illegal target now, so that part of the effect doesn't affect it.", "608.2b")
    }

    fun filterMatches(f: ObjFilter, ref: Ref, controller: String): Boolean = when (ref) {
        is Ref.Player -> Kind.PLAYER in f.kinds
        is Ref.Stack -> { val s = state.stackItem(ref.id) ?: return false; if (s.kind == StackKind.SPELL) filterMatchesSpell(f, s, controller) else Kind.ABILITY in f.kinds }
        is Ref.Obj -> {
            val o = state.objects[ref.id] ?: return false
            if (o.zone == Zone.STACK) { val s = state.stack.firstOrNull { it.source.id == o.id }; s != null && filterMatchesSpell(f, s, controller) }
            else if (!o.isOnBattlefield()) Kind.CARD in f.kinds
            else state.matches(f, o, controller)
        }
    }

    private fun filterMatchesSpell(f: ObjFilter, s: StackItem, controller: String): Boolean {
        if (s.kind != StackKind.SPELL) return Kind.ABILITY in f.kinds
        if (Kind.SPELL !in f.kinds && f.kinds.none { it in setOf(Kind.CREATURE, Kind.ARTIFACT, Kind.ENCHANTMENT, Kind.PLANESWALKER) }) return false
        val d = s.source.def
        val notOk = f.notKinds.none { k -> when (k) { Kind.CREATURE -> d.isCreature; Kind.ARTIFACT -> "Artifact" in d.types; Kind.ENCHANTMENT -> "Enchantment" in d.types; Kind.LAND -> "Land" in d.types; else -> false } }
        val kindOk = Kind.SPELL in f.kinds || f.kinds.any { k -> when (k) { Kind.CREATURE -> d.isCreature; Kind.ARTIFACT -> "Artifact" in d.types; Kind.ENCHANTMENT -> "Enchantment" in d.types; else -> false } }
        val spellTypes = f.subtypes.filter { it in setOf("instant", "sorcery") }
        val spellTypeOk = spellTypes.isEmpty() || spellTypes.any { t -> d.types.any { it.equals(t, true) } }
        val ctrlOk = when (f.controller) { Who.YOU -> s.controller == controller; Who.OPPONENT -> s.controller != controller; else -> true }
        return notOk && kindOk && spellTypeOk && ctrlOk
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun resolveWho(who: Who, item: StackItem): Player? = when (who) {
        Who.YOU -> state.player(item.controller)
        Who.OPPONENT -> state.opponentsOf(item.controller).singleOrNull() ?: run { state.clarifications += Clarification("which opponent", "${item.describe} refers to an opponent and there are several."); null }
        Who.THAT_PLAYER -> item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { state.player(it.id) } ?: causingPlayer(item)
        Who.TARGET_PLAYER -> item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { state.player(it.id) } ?: run {
            // "Target player loses 1 life" with no target named: assume the one opponent (the sensible choice), and say so.
            val opp = state.opponentsOf(item.controller).singleOrNull()
            if (opp != null) state.assumptions += "${item.describe} targets ${if (opp.you) "you" else opp.name} (\"target player\" wasn't specified; assuming the opponent)."
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
        is Effect.Counter -> "counter ${effect.target.raw}"; is Effect.CopySpell -> "copy ${effect.target.raw}"; is Effect.PreventCombatToAndBy -> "prevent all combat damage dealt to and by ${effect.target.raw} this turn"; is Effect.WinIfCastBefore -> "win the game if another spell with this name was cast this game, otherwise tuck it seventh from the top and gain ${effect.life} life"; is Effect.Destroy -> "destroy ${effect.target.raw}${if (effect.noRegen) " (it can't be regenerated)" else ""}"; is Effect.Exile -> "exile ${effect.target.raw}"
        is Effect.PumpCausing -> "that creature gets ${signed(effect.power)}/${signed(effect.toughness)}"; is Effect.Proliferate -> "proliferate"; is Effect.ForAllTargeted -> "${effect.action} all ${effect.filter.raw} ${effect.target.raw} controls"; is Effect.LoseLifeThatMuch -> "lose that much life"; is Effect.PumpAllCount -> "${effect.filter.raw} get +X/+X${if (effect.keywords.isEmpty()) "" else " and gain " + effect.keywords.joinToString(" and ")}"; is Effect.ShuffleIntoLibrary -> "shuffle ${effect.target.raw} into its owner's library"
        is Effect.DamagePlayer -> "deal ${effect.amount} damage to ${when (effect.who) { Who.THAT_PLAYER -> "that player"; Who.EACH_OPPONENT -> "each opponent"; Who.EACH_PLAYER -> "each player"; Who.YOU -> "you"; else -> "the player" }}"
        is Effect.CreateToken -> "create ${if (effect.countBy != null) "X" else effect.count.toString()} ${effect.token} token${if (effect.count > 1 || effect.countBy != null) "s" else ""}"; is Effect.SacrificeEach -> "each such player sacrifices a ${effect.filter.raw}"; is Effect.SacrificeSource -> "sacrifice ${item.source.name}"; is Effect.GainLifePerSpellThisTurn -> "gain ${effect.per} life for each spell cast this turn"; is Effect.WinIfDevotionCoversLibrary -> "look at the top X cards (X = your devotion) and win if X is at least your library size"; is Effect.Mill -> "${when (effect.who) { Who.TARGET_PLAYER -> "target player"; Who.YOU -> "you"; Who.EACH_PLAYER -> "each player"; Who.EACH_OPPONENT -> "each opponent"; else -> "that player" }} mills ${effect.count} cards"; is Effect.SacrificeThatMany -> "that player sacrifices that many ${effect.filter.raw}s"; is Effect.PutFromHand -> "put ${withArticle(effect.filter.raw)} from your ${if (effect.fromLibrary) "library" else if (effect.fromGraveyard) "graveyard" else "hand"} onto the battlefield"
        is Effect.Bounce -> "return ${effect.target?.raw ?: item.source.name} to its owner's hand"; is Effect.GainLifeEqualToPower -> "its controller gains life equal to its power"; is Effect.NarratedTargeted -> "${effect.target.raw}: ${effect.text}"
        is Effect.Tap -> "tap ${effect.target.raw}"; is Effect.Untap -> "untap ${effect.target.raw}"
        is Effect.Pump -> "${effect.target.raw} gets ${signed(effect.power)}/${signed(effect.toughness)}"
        is Effect.GainKeywords -> "${effect.target.raw} gains ${effect.keywords.joinToString(" and ")}"
        is Effect.GainControl -> "gain control of ${effect.target.raw}${if (effect.untilEndOfTurn) " until end of turn" else ""}"
        is Effect.PumpSelf -> "${item.source.name} gets ${signed(effect.power)}/${signed(effect.toughness)}"
        is Effect.PumpAll -> "${effect.filter.raw} get ${signed(effect.power)}/${signed(effect.toughness)}"; is Effect.SetBasePtAll -> "${effect.filter.raw} have base power and toughness ${if (effect.x) "X/X" else "${effect.power}/${effect.toughness}"} until end of turn"
        is Effect.PutCounters -> "put ${effect.count} ${effect.kind} counter(s) on ${effect.target?.raw ?: item.source.name}"
        is Effect.AddMana -> "add ${effect.text}"; is Effect.Narrated -> effect.text.replace("~", item.source.name).replaceFirstChar { it.lowercase() }
        is Effect.ForAll -> "${effect.action} ${if (effect.action == "damage") "${effect.amount} to " else ""}each ${effect.filter.raw}"
        is Effect.IfYouDo -> "${describe(effect.choice, item)}, and if so ${describe(effect.then, item)}"
        is Effect.Attach -> "attach ${item.source.name} to ${effect.target.raw}"
        is Effect.GainKeywordsSelf -> "${item.source.name} gains ${effect.keywords.joinToString(" and ")}"
        is Effect.Modal -> "choose ${effect.count}: " + effect.modeTexts.joinToString(" / ")
        is Effect.CreateShield -> "prevent ${effect.replacement.amount?.toString() ?: "all"} damage" + (effect.target?.let { " to ${it.raw}" } ?: "") + " this turn"
        is Effect.Regenerate -> "regenerate ${effect.target?.raw ?: item.source.name}"
        is Effect.GainLife -> "gain ${effect.amount} life"; is Effect.LoseLife -> "lose ${effect.amount} life"; is Effect.Repeat -> "repeat ${if (effect.x) "X" else effect.times.toString()} times: ${describe(effect.body, item)}"; is Effect.LoseLifeUnlessSacOrDiscard -> "${when (effect.who) { Who.EACH_OPPONENT -> "each opponent"; Who.EACH_PLAYER -> "each player"; else -> "that player" }} loses ${effect.amount} life unless they sacrifice ${effect.filter?.let { withArticle(it.raw) } ?: "a permanent"}${if (effect.discard) " or discard a card" else ""}"
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
