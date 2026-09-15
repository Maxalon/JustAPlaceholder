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

    fun cast(playerId: String, card: CardDef, targets: List<Ref>, objectId: String? = null): StackItem? {
        val player = state.player(playerId)
        val obj = objectId?.let { state.objects[it] } ?: state.add(GameObject(objectId ?: freshObjectId(card.name), card, Zone.HAND, playerId))
        val effect = card.spellEffect
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
        val item = StackItem(state.newStackId(), StackKind.SPELL, playerId, obj, effect, targets, zonesOf(targets), card.oracleText)
        state.stack += item
        trace.step("${player.subject} ${player.v("casts", "cast")} ${card.name}${describeTargets(targets)}. It goes on top of the stack.", "601.2a", "405.2")
        checkTargetsAtCast(item)
        wardTriggers(item)
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
        val needed = ability.effect.targets()
        if (needed.size != targets.size) {
            state.clarifications += Clarification("${obj.name}'s ability target", "The ability needs ${needed.size} target(s) (${needed.joinToString("; ") { it.raw }}) but ${targets.size} given (602.2b, 601.2c).")
            if (needed.size > targets.size) return null
        }
        for (ref in targets) targetingProblem(obj, playerId, ref)?.let { (why, rule) ->
            trace.step("${obj.name}'s ability can't target ${state.nameOf(ref)}: $why.", rule, "602.2b", "601.2c"); state.outcomes += "${obj.name}'s ability can't target ${state.nameOf(ref)}."; return null
        }
        val item = StackItem(state.newStackId(), StackKind.ACTIVATED, playerId, obj, ability.effect, targets, zonesOf(targets), ability.text)
        state.stack += item
        wardTriggers(item)
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
        trace.step("${obj.name} enters the battlefield under ${state.player(obj.controller).possessive} control.", "110.5b")
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
                    trace.step("${def.name} resolves and enters the battlefield under ${state.player(item.controller).possessive} control${if (def.isCreature) " as a ${state.describePt(item.source)}" else ""}.", "608.3a")
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
                    if (lethal) { move(obj, Zone.GRAVEYARD, "${obj.name} has ${obj.damage} damage marked and toughness $t, so it's destroyed (state-based action).", "704.3", "704.5g"); changed = true; continue }
                    if (obj.dealtDeathtouchDamage && obj.damage > 0) { move(obj, Zone.GRAVEYARD, "${obj.name} was dealt damage by a source with deathtouch, so it's destroyed (state-based action).", "704.3", "704.5h", "702.2b"); changed = true; continue }
                }
            }
            for (obj in state.objects.values.toList()) {
                if (obj.token && !obj.isOnBattlefield() && obj.zone != Zone.STACK) { state.objects.remove(obj.id); trace.step("${obj.name} token ceases to exist.", "704.5d"); changed = true }
            }
            for (p in state.players) {
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
        if (a.tapped == true) { trace.step("${a.name} is tapped, so it can't be declared as an attacker.", "508.1a"); state.outcomes += "${a.name} can't attack (tapped)."; return }
        if (a.summoningSick == true && !a.has("haste")) { trace.step("${a.name} came under ${p.possessive} control this turn and doesn't have haste, so it can't attack (\"summoning sickness\").", "508.1a", "302.6"); state.outcomes += "${a.name} can't attack (summoning sick)."; return }
        if (a.summoningSick == null && !a.has("haste")) state.assumptions += "${a.name} has been under ${p.possessive} control since the turn began (otherwise it couldn't attack, 508.1a)."
        if (a.summoningSick == true && a.has("haste")) trace.step("${a.name} has haste, so it can attack the turn it came under ${p.possessive} control.", "702.10b")
        a.attacking = defender
        if (a.has("vigilance")) trace.step("${p.subject} ${p.v("attacks", "attack")} with ${a.name} (${state.describePt(a)}), attacking ${state.nameOf(defender)}. It has vigilance, so it doesn't tap.", "508.1a", "702.20b")
        else { a.tapped = true; trace.step("${p.subject} ${p.v("attacks", "attack")} with ${a.name} (${state.describePt(a)}), attacking ${state.nameOf(defender)}. It becomes tapped.", "508.1a", "508.1f") }
        onEvent(GameEvent.Attacks(a))
    }

    /** Declare one blocker for one attacker (509.1). Legality of evasion abilities is checked here; menace is re-checked when damage is dealt. */
    fun declareBlocker(playerId: String, blockerId: String, attackerId: String) {
        val b = state.obj(blockerId); val a = state.obj(attackerId); val p = state.player(playerId)
        state.step = "declare_blockers"
        if (!b.def.isCreature || !b.isOnBattlefield()) { trace.step("${b.name} isn't a creature on the battlefield, so it can't block.", "506.3"); return }
        if (a.attacking == null) { trace.step("${a.name} isn't attacking, so ${b.name} can't block it.", "509.1a"); return }
        if (b.tapped == true) { trace.step("${b.name} is tapped, so it can't block.", "509.1a"); state.outcomes += "${b.name} can't block (tapped)."; return }
        state.protections(a).takeIf { it.isNotEmpty() }?.let { prots ->
            val bq = qualitiesOf(b.def)
            prots.firstOrNull { it == "everything" || it in bq }?.let { q -> trace.step("${a.name} has protection from $q, so ${b.name} can't block it.", "702.16f"); state.outcomes += "${b.name} can't block ${a.name} (protection)."; return }
        }
        if (a.has("flying") && !(b.has("flying") || b.has("reach"))) { trace.step("${a.name} has flying and ${b.name} has neither flying nor reach, so ${b.name} can't block it.", "702.9b"); state.outcomes += "${b.name} can't block ${a.name} (flying)."; return }
        b.blocking = a.id
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
        data class Hit(val source: GameObject, val target: Ref, val amount: Int)
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
        for (h in hits) {
            applyDamage(h.source.name, h.target, h.amount)
            if (h.source.has("deathtouch")) (h.target as? Ref.Obj)?.let { state.objects[it.id]?.dealtDeathtouchDamage = true }
            if (h.source.has("lifelink")) { val c = state.player(h.source.controller); c.life = c.life?.plus(h.amount); trace.step("${h.source.name} has lifelink, so ${c.subject.lowercase()} ${c.v("gains", "gain")} ${h.amount} life${c.life?.let { " ($it)" } ?: ""}.", "702.15b"); state.outcomes += "${c.subject} ${c.v("gains", "gain")} ${h.amount} life (lifelink)." }
        }
        stateBasedActions()
    }

    // ---- triggers --------------------------------------------------------------------------

    sealed interface GameEvent {
        data class SpellCast(val item: StackItem) : GameEvent
        data class EntersBattlefield(val obj: GameObject) : GameEvent
        data class Dies(val obj: GameObject) : GameEvent
        data class LeavesBattlefield(val obj: GameObject) : GameEvent
        data class Attacks(val obj: GameObject) : GameEvent
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
        is Trigger.Unknown -> false
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
                trace.step("${who.subject} ${who.v("draws", "draw")} ${effect.count} card${if (effect.count > 1) "s" else ""}.", "121.1")
                state.outcomes += "${who.subject} ${who.v("draws", "draw")} ${effect.count} card${if (effect.count > 1) "s" else ""}."
            }
            is Effect.Damage -> forEachLegalTarget(item, effect.target) { applyDamage(item.source.name, it, effect.amount) }
            is Effect.Counter -> forEachLegalTarget(item, effect.target) { ref ->
                val target = (ref as? Ref.Stack)?.let { state.stackItem(it.id) } ?: (ref as? Ref.Obj)?.let { r -> state.stack.firstOrNull { it.source.id == r.id } }
                if (target == null) { trace.step("${state.nameOf(ref)} is no longer on the stack, so it can't be countered.", "701.6a"); return@forEachLegalTarget }
                state.stack.remove(target)
                if (target.kind == StackKind.SPELL) target.source.zone = Zone.GRAVEYARD
                trace.step("${target.describe} is countered: it's removed from the stack and none of its effects happen" + (if (target.kind == StackKind.SPELL) "; the card goes to its owner's graveyard" else "") + ".", "701.6a")
                state.outcomes += "${target.describe} is countered."
            }
            is Effect.Destroy -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { move(it, Zone.GRAVEYARD, "${it.name} is destroyed and put into its owner's graveyard.", "701.8a") } }
            is Effect.Exile -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { move(it, Zone.EXILE, "${it.name} is exiled.", "701.13a") } }
            is Effect.Tap -> forEachLegalTarget(item, effect.target) { ref -> objOf(ref)?.let { it.tapped = true; trace.step("${it.name} becomes tapped.", "701.26a") } }
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
            is Effect.GainLife -> resolveWho(effect.who, item)?.let { p -> p.life = p.life?.plus(effect.amount); trace.step("${p.subject} ${p.v("gains", "gain")} ${effect.amount} life${p.life?.let { " ($it)" } ?: ""}.", "119.3"); state.outcomes += "${p.subject} ${p.v("gains", "gain")} ${effect.amount} life." }
            is Effect.LoseLife -> resolveWho(effect.who, item)?.let { p -> p.life = p.life?.minus(effect.amount); trace.step("${p.subject} ${p.v("loses", "lose")} ${effect.amount} life${p.life?.let { " ($it)" } ?: ""}.", "119.3"); state.outcomes += "${p.subject} ${p.v("loses", "lose")} ${effect.amount} life." }
            is Effect.Unparsed -> trace.step("(Not modeled: \"${effect.text}\")")
        }
    }

    private fun applyDamage(sourceName: String, target: Ref, amount: Int, source: GameObject? = state.objects.values.firstOrNull { it.name == sourceName }) {
        if (source != null && target is Ref.Obj) {
            val o = state.objects[target.id]
            if (o != null) {
                val qualities = qualitiesOf(source.def)
                state.protections(o).firstOrNull { it == "everything" || it in qualities }?.let { q ->
                    trace.step("$sourceName would deal $amount damage to ${o.name}, but ${o.name} has protection from $q, so that damage is prevented.", "702.16e", "615.1")
                    state.outcomes += "Damage to ${o.name} from $sourceName is prevented (protection)."; return
                }
            }
        }
        when (target) {
            is Ref.Player -> { val p = state.player(target.id); p.life = p.life?.minus(amount); trace.step("$sourceName deals $amount damage to ${if (p.you) "you" else p.name}, ${if (p.you) "and you lose" else "who loses"} $amount life${p.life?.let { " ($it)" } ?: ""}.", "120.3a"); state.outcomes += "${p.subject} ${p.v("takes", "take")} $amount damage." }
            is Ref.Obj -> { val o = state.obj(target.id); o.damage += amount; trace.step("$sourceName deals $amount damage to ${o.name}; it now has ${o.damage} damage marked (toughness ${o.toughness ?: "?"}).", "120.3e"); state.outcomes += "${o.name} has ${o.damage} damage marked." }
            is Ref.Stack -> state.unsupported += Unsupported(sourceName, "Damage can't be dealt to something on the stack.")
        }
    }

    private fun move(obj: GameObject, to: Zone, text: String, vararg rules: String) {
        val from = obj.zone
        obj.zone = to; obj.damage = 0; obj.pumps.clear(); obj.tempKeywords.clear(); obj.tapped = false; obj.attacking = null; obj.blocking = null; obj.dealtDeathtouchDamage = false
        trace.step(text, *rules)
        state.outcomes += "${obj.name}: ${zoneName(from, obj)} → ${zoneName(to, obj)}."
        if (from == Zone.BATTLEFIELD) {
            if (to == Zone.GRAVEYARD && obj.def.isCreature) onEvent(GameEvent.Dies(obj)) else onEvent(GameEvent.LeavesBattlefield(obj))
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

    private fun specFor(item: StackItem, ref: Ref): TargetSpec? { val specs = item.effect?.targets() ?: return null; val i = item.targets.indexOf(ref); return specs.getOrNull(i) }

    private inline fun forEachLegalTarget(item: StackItem, spec: TargetSpec, block: (Ref) -> Unit) {
        val specs = item.effect?.targets() ?: emptyList()
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
