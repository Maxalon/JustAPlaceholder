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

    fun cast(playerId: String, card: CardDef, targets: List<Ref>, objectId: String? = null, modes: List<Int> = emptyList()): StackItem? {
        val player = state.player(playerId)
        val obj = objectId?.let { state.objects[it] } ?: state.add(GameObject(objectId ?: freshObjectId(card.name), card, Zone.HAND, playerId))
        val effect = card.spellEffect ?: card.enchant?.takeIf { card.isAura }?.let { Effect.Attach(TargetSpec(it, "enchant ${it.raw}")) }
        val needed = effect?.targets() ?: emptyList()
        var asked = false
        val targets = if (targets.isEmpty() && needed.size == 1) inferTarget(card.name, needed[0], playerId).also { asked = it == null && state.clarifications.any { c -> c.about == "${card.name}'s target" } } ?: targets else targets
        if (!card.isInstantOrSorcery && card.abilities.none { it is TriggeredAbility || it is ActivatedAbility || it is StaticAbility } && card.abilities.isNotEmpty()) {
            state.unsupported += Unsupported(card.name, "Rules text not modeled: " + card.abilities.filterIsInstance<UnparsedAbility>().joinToString(" | ") { it.text })
        }
        if (needed.size != targets.size) {
            if (!asked) state.clarifications += Clarification("${card.name}'s target${if (needed.size == 1) "" else "s"}",
                "${card.name} needs ${needed.size} target${if (needed.size == 1) "" else "s"} (${needed.joinToString("; ") { it.raw }}) but ${targets.size} ${if (targets.size == 1) "was" else "were"} given (601.2c).")
            if (needed.size > targets.size) return null
        }
        for (ref in targets) targetingProblem(obj, playerId, ref)?.let { (why, rule) ->
            trace.step("${card.name} can't be cast targeting ${state.nameOf(ref)}: $why.", rule, "601.2c")
            state.outcomes += "${card.name} can't target ${state.nameOf(ref)}."
            return null
        }
        obj.zone = Zone.STACK
        // A modal spell's targets belong to the chosen mode (700.2c): validate against that mode's needs.
        val modal = effect as? Effect.Modal
        val modeEffect = modal?.let { m -> modes.mapNotNull { i -> m.modes.getOrNull(i - 1) }.let { if (it.isEmpty()) null else Effect.Seq(it) } }
        val item = StackItem(state.newStackId(), StackKind.SPELL, playerId, obj, effect, targets, zonesOf(targets), card.oracleText, modes)
        state.stack += item
        trace.step("${player.subject} ${player.v("casts", "cast")} ${card.name}${if (modes.isNotEmpty() && modal != null) " choosing " + modes.joinToString(" and ") { "\"${modal.modeTexts.getOrNull(it - 1) ?: "?"}\"" } else ""}${describeTargets(targets)}. It goes on top of the stack.", "601.2a", "405.2", *(if (modal != null) arrayOf("601.2b", "700.2a") else emptyArray()))
        if (modeEffect != null && modeEffect.targets().size != targets.size) state.clarifications += Clarification("${card.name}'s target", "The chosen mode needs ${modeEffect.targets().size} target(s) (${modeEffect.targets().joinToString("; ") { it.raw }}) but ${targets.size} given.")
        card.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.CostText>().forEach {
            trace.step("Cost note for ${card.name}: \"${it.text.replace("~", card.name)}\" (the total cost is determined and paid as part of casting).", "601.2b", "601.2f", "601.2h")
        }
        card.abilities.filterIsInstance<StaticAbility>().filter { it.keyword in castingKeywordRules }.forEach { k ->
            trace.step("${card.name} has ${k.text.trimEnd('.')}: ${castingKeywordNotes[k.keyword]}.", castingKeywordRules.getValue(k.keyword!!))
        }
        if (card.isAura) trace.step("${card.name} is an Aura spell, so it targets what it will enchant.", "303.4a", "702.5a")
        checkTargetsAtCast(item)
        wardTriggers(item)
        targets.forEach { ref -> objOf(ref)?.let { onEvent(GameEvent.BecomesTarget(it)) } }
        if (effect?.hasUnparsed() == true) state.unsupported += Unsupported(card.name, "Part of the spell's effect is not modeled: " + unparsedText(effect))
        onEvent(GameEvent.SpellCast(item))
        trace.step("${player.subject} ${player.v("receives", "receive")} priority again after casting.", "117.3c")
        return item
    }

    fun activate(playerId: String, objectId: String, abilityIndex: Int?, targets: List<Ref>): StackItem? {
        val obj = state.obj(objectId)
        val abilities = obj.def.abilities.filterIsInstance<ActivatedAbility>()
        if (abilities.isEmpty()) { state.unsupported += Unsupported(obj.name, "No activated ability was recognised on ${obj.name}."); return null }
        if (abilities.size > 1 && abilityIndex == null) {
            state.clarifications += Clarification("${obj.name}'s ability", "${obj.name} has ${abilities.size} activated abilities; which one? " + abilities.mapIndexed { i, a -> "[$i] ${a.text}" }.joinToString(" "))
            return null
        }
        val ability = abilities[abilityIndex ?: 0]
        if (ability.effect is Effect.AddMana || (ability.effect is Effect.Seq && (ability.effect as Effect.Seq).effects.firstOrNull() is Effect.AddMana)) {
            val p = state.player(playerId)
            trace.step("${p.subject} ${p.v("activates", "activate")} ${obj.name}'s mana ability (${ability.cost}). It's a mana ability, so it doesn't use the stack and resolves immediately: ${describeManaEffect(ability.effect)}.", "605.1a", "605.3b")
            if (ability.cost.contains("{T}")) tap(obj)
            state.outcomes += "${obj.name}'s mana ability: ${describeManaEffect(ability.effect)}."
            return null
        }
        if (ability.cost.contains("{T}") && obj.tapped == true) { trace.step("${obj.name} is already tapped, so its {T} ability can't be activated.", "602.2b", "701.26a"); return null }
        if (ability.cost.contains("{T}") && obj.def.isCreature && obj.summoningSick == true && !obj.has("haste")) { trace.step("${obj.name} hasn't been under ${state.player(playerId).possessive} control since the turn began and doesn't have haste, so its {T} ability can't be activated.", "302.6"); state.outcomes += "${obj.name}'s {T} ability can't be activated (summoning sickness)."; return null }
        if (ability.cost.contains("{T}")) tap(obj)
        if (ability.cost.contains("discard this card", true)) onEvent(GameEvent.Cycled(obj))
        val needed = ability.effect.targets()
        if (needed.size != targets.size) {
            state.clarifications += Clarification("${obj.name}'s ability target", "The ability needs ${needed.size} target(s) (${needed.joinToString("; ") { it.raw }}) but ${targets.size} given (602.2b, 601.2c).")
            if (needed.size > targets.size) return null
        }
        for (ref in targets) targetingProblem(obj, playerId, ref)?.let { (why, rule) ->
            trace.step("${obj.name}'s ability can't target ${state.nameOf(ref)}: $why.", rule, "602.2b", "601.2c"); state.outcomes += "${obj.name}'s ability can't target ${state.nameOf(ref)}."; return null
        }
        ability.restriction?.let { trace.step("${obj.name}'s ability says \"$it\"; assuming that timing is satisfied.", "602.5", "602.2") }
        val item = StackItem(state.newStackId(), StackKind.ACTIVATED, playerId, obj, ability.effect, targets, zonesOf(targets), ability.text)
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
    fun beginStep(step: String, activePlayer: String) {
        state.activePlayer = activePlayer
        state.step = step
        state.phase = when (step) { "untap", "upkeep", "draw" -> "beginning"; "precombat_main" -> "precombat_main"; "postcombat_main" -> "postcombat_main"; "end", "cleanup" -> "ending"; else -> "combat" }
        trace.step("${state.player(activePlayer).possessive.replaceFirstChar { it.uppercase() }} ${step.replace('_', ' ')} begins.", when (step) { "upkeep" -> "503.1"; "end" -> "513.1"; "draw" -> "504.1"; else -> "500.1" })
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
                    item.effect?.let { applyEffect(it, item) } ?: state.unsupported.add(Unsupported(def.name, "The spell has no modeled effect."))
                    item.source.zone = Zone.GRAVEYARD
                    trace.step("${def.name} finishes resolving and is put into its owner's graveyard.", "608.2c", "608.2n")
                } else {
                    item.source.zone = Zone.BATTLEFIELD; item.source.tapped = false; item.source.summoningSick = def.isCreature; item.source.timestamp = state.tick()
                    if (def.isAura) {
                        val t = item.targets.firstOrNull()
                        val tid = (t as? Ref.Obj)?.id
                        item.source.attachedTo = tid
                        trace.step("${def.name} enters the battlefield attached to ${t?.let { state.nameOf(it) } ?: "nothing"}.", "608.3b", "303.4")
                    }
                    applyEntersReplacements(item.source)
                    trace.step("${def.name} resolves and enters the battlefield under ${state.player(item.controller).possessive} control${if (def.isCreature) " as a ${state.describePt(item.source)}" else ""}${if (item.source.tapped == true) ", tapped" else ""}.", "608.3a")
                    if (def.abilities.any { it is StaticAbility && it.effects.isNotEmpty() }) trace.step("${def.name}'s static ability starts applying to the permanents it describes.", "604.2", "613.1")
                    state.outcomes += "${def.name} enters the battlefield."
                    onEvent(GameEvent.EntersBattlefield(item.source))
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
                if (p.poison >= 10 && !p.lost) { p.lost = true; trace.step("${p.subject} ${p.v("has", "have")} ${p.poison} poison counters and ${p.v("loses", "lose")} the game (state-based action).", "704.3", "704.5c"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} the game (poison)."; changed = true }
                val life = p.life
                if (life != null && life <= 0 && !p.lost) { p.lost = true; trace.step("${p.subject} ${p.v("has", "have")} $life life and ${p.v("loses", "lose")} the game (state-based action).", "704.3", "704.5a"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} the game."; changed = true }
            }
        }
    }


    // ---- combat ----------------------------------------------------------------------------

    /** Declare one attacker (508.1). `defender` is a player, or a planeswalker/battle object. */
    fun declareAttacker(playerId: String, attackerId: String, defender: Ref) {
        val a = state.obj(attackerId)
        val p = state.player(playerId)
        state.phase = "combat"; state.step = "declare_attackers"
        if (!a.def.isCreature || !a.isOnBattlefield()) { trace.step("${a.name} isn't a creature on the battlefield, so it can't attack.", "506.3"); state.outcomes += "${a.name} can't attack."; return }
        if (a.controller != playerId) { trace.step("${a.name} isn't controlled by ${p.subject.lowercase()}, so ${p.subject.lowercase()} can't attack with it.", "508.1a"); return }
        if (a.has("defender")) { trace.step("${a.name} has defender and can't attack.", "702.3b"); state.outcomes += "${a.name} can't attack (defender)."; return }
        if (cant(a, "attack")) { trace.step("${a.name} can't attack (its own rules text says so).", "508.1c"); state.outcomes += "${a.name} can't attack."; return }
        if (a.tapped == true) { trace.step("${a.name} is tapped, so it can't be declared as an attacker.", "508.1a"); state.outcomes += "${a.name} can't attack (tapped)."; return }
        if (a.summoningSick == true && !a.has("haste")) { trace.step("${a.name} came under ${p.possessive} control this turn and doesn't have haste, so it can't attack (\"summoning sickness\").", "508.1a", "302.6"); state.outcomes += "${a.name} can't attack (summoning sick)."; return }
        if (a.summoningSick == null && !a.has("haste")) state.assumptions += "${a.name} has been under ${p.possessive} control since the turn began (otherwise it couldn't attack, 508.1a)."
        if (a.summoningSick == true && a.has("haste")) trace.step("${a.name} has haste, so it can attack the turn it came under ${p.possessive} control.", "702.10b")
        val firstAttacker = state.objects.values.none { it.attacking != null }
        a.attacking = defender
        if (a.has("vigilance")) trace.step("${p.subject} ${p.v("attacks", "attack")} with ${a.name} (${state.describePt(a)}), attacking ${state.nameOf(defender)}. It has vigilance, so it doesn't tap.", "508.1a", "702.20b")
        else { trace.step("${p.subject} ${p.v("attacks", "attack")} with ${a.name} (${state.describePt(a)}), attacking ${state.nameOf(defender)}. It becomes tapped.", "508.1a", "508.1f"); tap(a) }
        if (a.def.abilities.any { it is StaticAbility && it.effects.contains(StaticEffect.MustAttack) }) trace.step("${a.name} attacks each combat if able, so it had to be declared as an attacker.", "508.1d")
        onEvent(GameEvent.Attacks(a))
        if (firstAttacker) onEvent(GameEvent.PlayerAttacks(playerId))
    }

    /** Declare one blocker for one attacker (509.1). Legality of evasion abilities is checked here; menace is re-checked when damage is dealt. */
    fun declareBlocker(playerId: String, blockerId: String, attackerId: String) {
        val b = state.obj(blockerId); val a = state.obj(attackerId); val p = state.player(playerId)
        state.step = "declare_blockers"
        if (!b.def.isCreature || !b.isOnBattlefield()) { trace.step("${b.name} isn't a creature on the battlefield, so it can't block.", "506.3"); return }
        if (a.attacking == null) { trace.step("${a.name} isn't attacking, so ${b.name} can't block it.", "509.1a"); return }
        if (b.tapped == true) { trace.step("${b.name} is tapped, so it can't block.", "509.1a"); state.outcomes += "${b.name} can't block (tapped)."; return }
        if (cant(b, "block")) { trace.step("${b.name} can't block (its own rules text says so).", "509.1b"); state.outcomes += "${b.name} can't block."; return }
        if (cant(a, "be blocked")) { trace.step("${a.name} can't be blocked.", "509.1b"); state.outcomes += "${b.name} can't block ${a.name}."; return }
        state.protections(a).takeIf { it.isNotEmpty() }?.let { prots ->
            val bq = qualitiesOf(b.def)
            prots.firstOrNull { it == "everything" || it in bq }?.let { q -> trace.step("${a.name} has protection from $q, so ${b.name} can't block it.", "702.16f"); state.outcomes += "${b.name} can't block ${a.name} (protection)."; return }
        }
        if (a.has("fear") && !(b.has("fear") || "Artifact" in b.def.types || 'B' in b.def.colors)) { trace.step("${a.name} has fear and can't be blocked except by artifact creatures and/or black creatures.", "702.36b"); state.outcomes += "${b.name} can't block ${a.name} (fear)."; return }
        if (a.has("intimidate") && !("Artifact" in b.def.types || b.def.colors.intersect(a.def.colors).isNotEmpty())) { trace.step("${a.name} has intimidate and can't be blocked except by artifact creatures and/or creatures that share a color with it.", "702.13b"); state.outcomes += "${b.name} can't block ${a.name} (intimidate)."; return }
        if (a.has("horsemanship") && !b.has("horsemanship")) { trace.step("${a.name} has horsemanship and can't be blocked except by creatures with horsemanship.", "702.31b"); return }
        if (a.has("shadow") != b.has("shadow")) { trace.step("${a.name} ${if (a.has("shadow")) "has" else "doesn't have"} shadow and ${b.name} ${if (b.has("shadow")) "has" else "doesn't have"}; creatures with shadow can only block and be blocked by creatures with shadow.", "702.28b"); return }
        if (a.has("skulk") && (b.power ?: 0) > (a.power ?: 0)) { trace.step("${a.name} has skulk and can't be blocked by creatures with greater power.", "702.118b"); return }
        a.def.keywords.firstOrNull { it.endsWith("walk") }?.let { walk ->
            val landType = walk.removeSuffix("walk").replaceFirstChar { it.uppercase() }
            val defender = state.player(b.controller)
            if (state.objects.values.any { it.isOnBattlefield() && it.controller == defender.id && "Land" in it.def.types && (it.def.subtypes.any { st -> st.equals(landType, true) } || it.def.name.equals(landType, true)) }) {
                trace.step("${a.name} has $walk and ${defender.subject.lowercase()} ${defender.v("controls", "control")} a $landType, so it can't be blocked.", "702.14c"); state.outcomes += "${b.name} can't block ${a.name} ($walk)."; return
            }
        }
        if (a.has("flying") && !(b.has("flying") || b.has("reach"))) { trace.step("${a.name} has flying and ${b.name} has neither flying nor reach, so ${b.name} can't block it.", "702.9b"); state.outcomes += "${b.name} can't block ${a.name} (flying)."; return }
        val firstBlocker = blockersOf(a).isEmpty()
        b.blocking = a.id
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
            if (bs.size == 1) { trace.step("${a.name} has menace and can't be blocked except by two or more creatures; blocking it with only ${bs[0].name} isn't a legal block, so ${a.name} is unblocked.", "702.111b", "509.1a"); bs[0].blocking = null }
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

    private fun onEvent(event: GameEvent) {
        val triggered = mutableListOf<Pair<GameObject, TriggeredAbility>>()
        for (obj in state.objects.values) {
            for (ability in obj.def.abilities.filterIsInstance<TriggeredAbility>()) {
                if (matches(obj, ability.trigger, event)) triggered += obj to ability
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
                is GameEvent.PlayerAttacks -> "${state.player(event.playerId).subject.lowercase()} attacking"
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
            trace.step("${obj.name}'s ability triggers on $cause.", "603.2", *(if (event is GameEvent.EntersBattlefield) arrayOf("603.6a") else emptyArray()))
            putTriggerOnStack(obj, ability, emptyList())
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
        is Trigger.PermanentDies -> event is GameEvent.Dies && (obj.isOnBattlefield() || event.obj === obj) && !(trigger.other && event.obj === obj) && matchesLki(trigger.filter, event.obj, obj.controller)
        Trigger.YouAttack -> event is GameEvent.PlayerAttacks && event.playerId == obj.controller && obj.isOnBattlefield()
        Trigger.YouGainLife -> event is GameEvent.LifeGained && event.playerId == obj.controller && obj.isOnBattlefield()
        Trigger.YouDraw -> event is GameEvent.Drew && event.playerId == obj.controller && obj.isOnBattlefield()
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

    private fun putTriggerOnStack(obj: GameObject, ability: TriggeredAbility, targets: List<Ref>): StackItem? {
        val needed = ability.effect.targets()
        if (needed.size > targets.size) {
            state.clarifications += Clarification("${obj.name}'s trigger target", "${obj.name}'s triggered ability needs a target (${needed.joinToString("; ") { it.raw }}); which? (603.3d)")
            return null
        }
        val item = StackItem(state.newStackId(), StackKind.TRIGGERED, obj.controller, obj, ability.effect, targets, zonesOf(targets), ability.text)
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
                trace.step("${you.subject} may choose to ${describe(effect.effect, item)}.", "608.2d")
                state.assumptions += "${you.subject} ${you.v("chooses", "choose")} to ${describe(effect.effect, item)} (${item.describe} says \"you may\")."
                applyEffect(effect.effect, item)
            }
            is Effect.UnlessPays -> {
                val payer = resolveWho(effect.payer, item)
                trace.step("${payer?.subject ?: "The named player"} may pay ${effect.cost}. If ${if (payer?.you == true) "you do" else "they do"}, nothing more happens; if not: ${describe(effect.effect, item)}.", "608.2g", "117.3d")
                state.assumptions += "${payer?.subject ?: "The player"} ${payer?.v("does", "do") ?: "does"} not pay ${effect.cost} for ${item.describe}."
                applyEffect(effect.effect, item)
            }
            is Effect.Draw -> {
                val who = resolveWho(effect.who, item)
                if (who == null) { state.unsupported += Unsupported(item.describe, "Couldn't work out who draws."); return }
                who.drew += effect.count
                trace.step("${who.subject} ${who.v("draws", "draw")} ${effect.count} card${if (effect.count > 1) "s" else ""}${if (effect.count > 1) " (one at a time)" else ""}.", "121.1", *(if (effect.count > 1) arrayOf("121.2") else emptyArray()))
                state.outcomes += "${who.subject} ${who.v("draws", "draw")} ${effect.count} card${if (effect.count > 1) "s" else ""}."
                repeat(effect.count) { onEvent(GameEvent.Drew(who.id)) }
            }
            is Effect.Damage -> forEachLegalTarget(item, effect.target) { applyDamage(item.source.name, it, effect.amount) }
            is Effect.Counter -> forEachLegalTarget(item, effect.target) { ref ->
                val target = (ref as? Ref.Stack)?.let { state.stackItem(it.id) } ?: (ref as? Ref.Obj)?.let { r -> state.stack.firstOrNull { it.source.id == r.id } }
                if (target == null) { trace.step("${state.nameOf(ref)} is no longer on the stack, so it can't be countered.", "701.6a"); return@forEachLegalTarget }
                if (target.kind == StackKind.SPELL && cant(target.source, "be countered")) { trace.step("${target.describe} can't be countered, so ${item.describe} has no effect on it.", "701.6a"); return@forEachLegalTarget }
                state.stack.remove(target)
                if (target.kind == StackKind.SPELL) target.source.zone = Zone.GRAVEYARD
                trace.step("${target.describe} is countered: it's removed from the stack and none of its effects happen" + (if (target.kind == StackKind.SPELL) "; the card goes to its owner's graveyard" else "") + ".", "701.6a")
                state.outcomes += "${target.describe} is countered."
            }
            is Effect.Destroy -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { destroy(it, "${it.name} is destroyed and put into its owner's graveyard.", "701.8a") } }
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
            is Effect.Tap -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { trace.step("${it.name} becomes tapped.", "701.26a"); tap(it) } }
            is Effect.Untap -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { it.tapped = false; trace.step("${it.name} becomes untapped.", "701.26b") } }
            is Effect.Pump -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let {
                it.pumps += effect.power to effect.toughness
                trace.step("${it.name} gets ${signed(effect.power)}/${signed(effect.toughness)} until end of turn; it's now ${it.power}/${it.toughness}.", "611.2a")
                state.outcomes += "${it.name} is ${it.power}/${it.toughness} until end of turn."
            } }
            is Effect.GainKeywords -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let {
                it.tempKeywords += effect.keywords
                trace.step("${it.name} gains ${effect.keywords.joinToString(" and ")} until end of turn.", "611.2a")
                state.outcomes += "${it.name} has ${effect.keywords.joinToString(" and ")} until end of turn."
            } }
            is Effect.GainLife -> resolveWho(effect.who, item)?.let { p -> gainLife(p, effect.amount) }
            is Effect.LoseLife -> resolveWho(effect.who, item)?.let { p -> p.life = p.life?.minus(effect.amount); trace.step("${p.subject} ${p.v("loses", "lose")} ${effect.amount} life${p.life?.let { " ($it)" } ?: ""}.", "119.3"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} ${effect.amount} life." }
            is Effect.PumpSelf -> { val o = item.source; if (o.isOnBattlefield()) { o.pumps += effect.power to effect.toughness; trace.step("${o.name} gets ${signed(effect.power)}/${signed(effect.toughness)} until end of turn; it's now ${o.power}/${o.toughness}.", "611.2a"); state.outcomes += "${o.name} is ${o.power}/${o.toughness} until end of turn." } else trace.step("${o.name} isn't on the battlefield, so there's nothing for the effect to modify.", "611.2c") }
            is Effect.PumpAll -> {
                val affected = state.objects.values.filter { state.matches(effect.filter, it, item.controller) }
                affected.forEach { it.pumps += effect.power to effect.toughness }
                trace.step("${if (affected.isEmpty()) "No permanents match \"${effect.filter.raw}\"" else affected.joinToString(", ") { "${it.name} (now ${it.power}/${it.toughness})" }} ${if (affected.size == 1) "gets" else "get"} ${signed(effect.power)}/${signed(effect.toughness)} until end of turn. Only permanents present now are affected.", "611.2a", "611.2c")
                affected.forEach { state.outcomes += "${it.name} is ${it.power}/${it.toughness} until end of turn." }
            }
            is Effect.PutCounters -> {
                val put: (GameObject) -> Unit = { o -> o.counters[effect.kind] = (o.counters[effect.kind] ?: 0) + effect.count; trace.step("${effect.count} ${effect.kind} counter${if (effect.count > 1) "s are" else " is"} put on ${o.name}${if (o.def.isCreature) "; it's now ${o.power}/${o.toughness}" else ""}.", "122.1a", "122.6"); state.outcomes += "${o.name} has ${o.counters[effect.kind]} ${effect.kind} counter${if (o.counters[effect.kind]!! > 1) "s" else ""}." }
                if (effect.target == null) { if (item.source.isOnBattlefield()) put(item.source) else trace.step("${item.source.name} isn't on the battlefield, so no counters are put on it.", "122.6") }
                else forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let(put) }
                if (effect.kind == "+1/+1" || effect.kind == "-1/-1") stateBasedActions()
            }
            is Effect.Attach -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { t ->
                item.source.attachedTo = t.id
                trace.step("${item.source.name} becomes attached to ${t.name}${if (item.source.def.isEquipment) " (equipped creature)" else ""}.", *(if (item.source.def.isEquipment) arrayOf("702.6a", "301.5a") else arrayOf("701.3a")))
                state.outcomes += "${item.source.name} is attached to ${t.name}."
                if (t.def.isCreature) trace.step("${t.name} is now ${state.describePt(t)}.", "613.1")
            } }
            is Effect.IfYouDo -> {
                trace.step("${you.subject} may ${describe(effect.choice, item)}. If ${you.v("they do", "you do")}: ${describe(effect.then, item)}.", "608.2d")
                state.assumptions += "${you.subject} ${you.v("chooses", "choose")} to ${describe(effect.choice, item)} for ${item.describe}."
                applyEffect(effect.choice, item); applyEffect(effect.then, item)
            }
            is Effect.GainKeywordsSelf -> { val o = item.source; if (o.isOnBattlefield()) { o.tempKeywords += effect.keywords; trace.step("${o.name} gains ${effect.keywords.joinToString(" and ")} until end of turn.", "611.2a"); state.outcomes += "${o.name} has ${effect.keywords.joinToString(" and ")} until end of turn." } }
            is Effect.Modal -> {
                val chosen = item.modes
                if (chosen.isEmpty()) {
                    state.clarifications += Clarification("${item.describe}'s mode", "${item.describe} is modal (choose ${effect.count}): " + effect.modeTexts.mapIndexed { i, t -> "[${i + 1}] $t" }.joinToString("; ") + ". Which mode(s)? (700.2a: chosen as it's cast)")
                    trace.step("${item.describe} is modal; its mode was chosen as it was cast. Not told which, so its effect isn't applied.", "700.2a", "601.2b")
                } else for (mi in chosen) { effect.modes.getOrNull(mi - 1)?.let { trace.step("Mode ${mi}: ${effect.modeTexts[mi - 1]}.", "700.2a"); applyEffect(it, item) } ?: run { state.clarifications += Clarification("mode", "Mode $mi doesn't exist on ${item.describe}.") } }
            }
            is Effect.AddMana -> trace.step("${describeManaEffect(effect)}.", "605.1a")
            is Effect.Narrated -> trace.step("${you.subject} ${effectText(effect.text, item)}.", *effect.rules.toTypedArray())
            is Effect.ForAll -> {
                val affected = state.objects.values.filter { state.matches(effect.filter, it, item.controller) }
                if (affected.isEmpty()) trace.step("Nothing matches \"${effect.filter.raw}\", so ${effect.action} affects nothing.")
                for (o in affected) when (effect.action) {
                    "destroy" -> destroy(o, "${o.name} is destroyed.", "701.8a")
                    "exile" -> move(o, Zone.EXILE, "${o.name} is exiled.", "701.13a")
                    "tap" -> { o.tapped = true; trace.step("${o.name} becomes tapped.", "701.26a") }
                    "untap" -> { o.tapped = false; trace.step("${o.name} becomes untapped.", "701.26b") }
                    "damage" -> applyDamage(item.source.name, Ref.Obj(o.id), effect.amount, item.source)
                }
                stateBasedActions()
            }
            is Effect.Unparsed -> trace.step("(Not modeled: \"${effect.text}\")")
        }
    }

    /** "Enters tapped" / "enters with N counters": replacement effects that modify how it enters (614.1c, 614.12). */
    private fun applyEntersReplacements(o: GameObject) {
        for (e in o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }) when (e) {
            StaticEffect.EntersTapped -> { o.tapped = true; trace.step("${o.name} enters tapped (a replacement effect on how it enters).", "614.1c", "614.12") }
            is StaticEffect.EntersWithCounters -> if (e.count != null) { o.counters[e.kind] = (o.counters[e.kind] ?: 0) + e.count; trace.step("${o.name} enters with ${e.count} ${e.kind} counter${if (e.count > 1) "s" else ""} on it.", "614.1c", "122.6") }
                else { state.clarifications += Clarification("${o.name}'s X", "${o.name} enters with X ${e.kind} counters; what was X?") }
            else -> {}
        }
    }

    private fun tap(o: GameObject) { if (o.tapped != true) { o.tapped = true; onEvent(GameEvent.BecomesTapped(o)) } }

    private fun moveRaw(obj: GameObject, to: Zone) {
        obj.zone = to; obj.damage = 0; obj.pumps.clear(); obj.tempKeywords.clear(); obj.tapped = false; obj.attacking = null; obj.blocking = null; obj.dealtDeathtouchDamage = false
    }

    /** Destruction with regeneration (701.19a, 614.8): returns true if the destruction was replaced. */
    private fun destroy(obj: GameObject, text: String, vararg rules: String): Boolean {
        if (obj.has("indestructible")) { trace.step("${obj.name} is indestructible and can't be destroyed.", "702.12b"); return true }
        val shield = state.shields.firstOrNull { it.replacement == Replacement.Regenerate && it.objectId == obj.id && (it.remaining ?: 0) > 0 }
        if (shield != null) {
            shield.remaining = 0
            obj.tapped = true; obj.damage = 0; obj.attacking = null; obj.blocking = null
            trace.step("$text But ${obj.name} has a regeneration shield from ${shield.sourceName}: instead of being destroyed, it's tapped, all damage is removed from it and it's removed from combat.", *rules, "701.19a", "614.8")
            state.outcomes += "${obj.name} regenerates."
            return true
        }
        move(obj, Zone.GRAVEYARD, text, *rules)
        return false
    }

    private fun cant(o: GameObject, what: String) = o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.Cant && (it.what == what || it.what == "attack or block" && (what == "attack" || what == "block")) }

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
                else { p.life = p.life?.minus(amount); trace.step("$sourceName deals $amount damage to ${if (p.you) "you" else p.name}, ${if (p.you) "and you lose" else "who loses"} $amount life${p.life?.let { " ($it)" } ?: ""}.", "120.3a"); state.outcomes += "${p.subject} ${p.v("takes", "take")} $amount damage." } }
            is Ref.Obj -> { val o = state.obj(target.id)
                if (infect || wither) { o.counters["-1/-1"] = (o.counters["-1/-1"] ?: 0) + amount; trace.step("$sourceName has ${if (infect) "infect" else "wither"}, so the $amount damage to ${o.name} is dealt as $amount -1/-1 counter${if (amount > 1) "s" else ""}; it's now ${o.power}/${o.toughness}.", if (infect) "702.90c" else "702.80a", "120.3d"); state.outcomes += "${o.name} has ${o.counters["-1/-1"]} -1/-1 counter(s)." }
                else { o.damage += amount; trace.step("$sourceName deals $amount damage to ${o.name}; it now has ${o.damage} damage marked (toughness ${o.toughness ?: "?"}).", "120.3e"); state.outcomes += "${o.name} has ${o.damage} damage marked." } }
            is Ref.Stack -> { state.unsupported += Unsupported(sourceName, "Damage can't be dealt to something on the stack."); return 0 }
        }
        if (source != null && target !is Ref.Stack) onEvent(GameEvent.DamageDealt(source, target, amount, inCombatDamage))
        return amount
    }

    private fun gainLife(p: Player, amount: Int) {
        var n = amount
        state.objects.values.filter { it.isOnBattlefield() && it.controller == p.id }.flatMap { o -> o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.mapNotNull { (it as? StaticEffect.Replace)?.replacement as? Replacement.LifeGainMultiplier }.map { o to it } }
            .forEach { (o, m) -> trace.step("${o.name} replaces the life gain: ${p.subject.lowercase()} ${p.v("gains", "gain")} ${n * m.factor} life instead of $n.", "614.1a", "614.6"); n *= m.factor }
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
        val o = (ref as? Ref.Obj)?.let { state.objects[it.id] } ?: return null
        if (!o.isOnBattlefield()) return null
        if (o.has("shroud")) return "${o.name} has shroud and can't be the target of spells or abilities" to "702.18a"
        if (o.has("hexproof") && o.controller != controller) return "${o.name} has hexproof and can't be the target of spells or abilities its controller's opponents control" to "702.11b"
        val prots = state.protections(o)
        if (prots.isNotEmpty()) {
            val qualities = qualitiesOf(source.def)
            val hit = prots.firstOrNull { it == "everything" || it in qualities }
            if (hit != null) return "${o.name} has protection from $hit, so it can't be targeted by ${if (source.def.isInstantOrSorcery || source.zone == Zone.STACK) "that spell" else "an ability from that source"}" to "702.16b"
        }
        return null
    }

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
    private fun inferTarget(what: String, spec: TargetSpec, controller: String): List<Ref>? {
        if (!spec.filter.verifiable) return null
        val candidates = mutableListOf<Ref>()
        for (o in state.objects.values) if (o.zone == Zone.BATTLEFIELD || o.zone == Zone.STACK) { val r = Ref.Obj(o.id); if (filterMatches(spec.filter, r, controller)) candidates += r }
        for (s in state.stack) if (s.kind != StackKind.SPELL) { val r = Ref.Stack(s.id); if (filterMatches(spec.filter, r, controller)) candidates += r }
        if (Kind.PLAYER in spec.filter.kinds) state.players.forEach { candidates += Ref.Player(it.id) }
        val distinct = candidates.distinctBy { when (it) { is Ref.Obj -> "o:" + it.id; is Ref.Stack -> "s:" + it.id; is Ref.Player -> "p:" + it.id } }
        return when (distinct.size) {
            1 -> { state.assumptions += "$what targets ${state.nameOf(distinct[0])}, the only legal target for \"${spec.raw}\" in this situation."; distinct }
            0 -> null
            else -> { state.clarifications += Clarification("$what's target", "$what needs a target (${spec.raw}); it could be ${distinct.joinToString(", ") { state.nameOf(it) }}. Which?"); emptyList<Ref>().also { return null } }
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
        val ctrlOk = when (f.controller) { Who.YOU -> s.controller == controller; Who.OPPONENT -> s.controller != controller; else -> true }
        return notOk && kindOk && ctrlOk
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun resolveWho(who: Who, item: StackItem): Player? = when (who) {
        Who.YOU -> state.player(item.controller)
        Who.OPPONENT -> state.opponentsOf(item.controller).singleOrNull() ?: run { state.clarifications += Clarification("which opponent", "${item.describe} refers to an opponent and there are several."); null }
        Who.THAT_PLAYER, Who.TARGET_PLAYER -> item.targets.filterIsInstance<Ref.Player>().firstOrNull()?.let { state.player(it.id) } ?: causingPlayer(item)
        Who.CONTROLLER_OF_TARGET -> item.targets.firstOrNull()?.let { ref -> when (ref) { is Ref.Obj -> state.player(state.obj(ref.id).controller); is Ref.Stack -> state.stackItem(ref.id)?.let { state.player(it.controller) }; is Ref.Player -> state.player(ref.id) } }
        Who.ANY_PLAYER -> null
    }

    /** For "that player" in a "whenever an opponent casts a spell" trigger: the player who caused it. */
    private fun causingPlayer(item: StackItem): Player? {
        if (item.kind != StackKind.TRIGGERED) return null
        val trig = (item.source.def.abilities.filterIsInstance<TriggeredAbility>().firstOrNull { it.text == item.text })?.trigger
        return if (trig is Trigger.SpellCast) (if (trig.who == Who.OPPONENT) state.opponentsOf(item.controller).singleOrNull() else null) else null
    }

    private fun objOf(ref: Ref): GameObject? = (ref as? Ref.Obj)?.let { state.objects[it.id] }
    private fun describeTargets(targets: List<Ref>) = if (targets.isEmpty()) "" else " targeting " + targets.joinToString(" and ") { state.nameOf(it) }
    private fun describe(effect: Effect, item: StackItem): String = when (effect) {
        is Effect.Draw -> "draw ${effect.count} card${if (effect.count > 1) "s" else ""}"
        is Effect.Damage -> "deal ${effect.amount} damage to ${effect.target.raw}"
        is Effect.Counter -> "counter ${effect.target.raw}"; is Effect.Destroy -> "destroy ${effect.target.raw}"; is Effect.Exile -> "exile ${effect.target.raw}"
        is Effect.Tap -> "tap ${effect.target.raw}"; is Effect.Untap -> "untap ${effect.target.raw}"
        is Effect.Pump -> "${effect.target.raw} gets ${signed(effect.power)}/${signed(effect.toughness)}"
        is Effect.GainKeywords -> "${effect.target.raw} gains ${effect.keywords.joinToString(" and ")}"
        is Effect.PumpSelf -> "${item.source.name} gets ${signed(effect.power)}/${signed(effect.toughness)}"
        is Effect.PumpAll -> "${effect.filter.raw} get ${signed(effect.power)}/${signed(effect.toughness)}"
        is Effect.PutCounters -> "put ${effect.count} ${effect.kind} counter(s) on ${effect.target?.raw ?: item.source.name}"
        is Effect.AddMana -> "add ${effect.text}"; is Effect.Narrated -> effect.text.replace("~", item.source.name).replaceFirstChar { it.lowercase() }
        is Effect.ForAll -> "${effect.action} ${if (effect.action == "damage") "${effect.amount} to " else ""}each ${effect.filter.raw}"
        is Effect.IfYouDo -> "${describe(effect.choice, item)}, and if so ${describe(effect.then, item)}"
        is Effect.Attach -> "attach ${item.source.name} to ${effect.target.raw}"
        is Effect.GainKeywordsSelf -> "${item.source.name} gains ${effect.keywords.joinToString(" and ")}"
        is Effect.Modal -> "choose ${effect.count}: " + effect.modeTexts.joinToString(" / ")
        is Effect.CreateShield -> "prevent ${effect.replacement.amount?.toString() ?: "all"} damage" + (effect.target?.let { " to ${it.raw}" } ?: "") + " this turn"
        is Effect.Regenerate -> "regenerate ${effect.target?.raw ?: item.source.name}"
        is Effect.GainLife -> "gain ${effect.amount} life"; is Effect.LoseLife -> "lose ${effect.amount} life"
        is Effect.May -> "may " + describe(effect.effect, item); is Effect.UnlessPays -> describe(effect.effect, item) + " unless ${effect.cost} is paid"
        is Effect.Seq -> effect.effects.joinToString(", then ") { describe(it, item) }; is Effect.Unparsed -> "\"${effect.text}\""
    }
    private fun unparsedText(e: Effect): String = when (e) { is Effect.Unparsed -> e.text; is Effect.May -> unparsedText(e.effect); is Effect.UnlessPays -> unparsedText(e.effect); is Effect.Seq -> e.effects.filter { it.hasUnparsed() }.joinToString(" | ") { unparsedText(it) }; else -> "" }
    private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"
    private fun freshObjectId(name: String): String { val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_'); var id = base; var i = 2; while (state.objects.containsKey(id)) id = "${base}_${i++}"; return id }
    private fun zoneName(z: Zone, obj: GameObject?) = when (z) {
        Zone.BATTLEFIELD -> "the battlefield"; Zone.GRAVEYARD -> "${obj?.let { state.player(it.owner).possessive } ?: "its owner's"} graveyard"; Zone.HAND -> "hand"
        Zone.LIBRARY -> "library"; Zone.EXILE -> "exile"; Zone.STACK -> "the stack"; Zone.COMMAND -> "the command zone"
    }
}
