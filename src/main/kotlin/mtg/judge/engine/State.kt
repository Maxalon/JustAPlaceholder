package mtg.judge.engine

enum class Zone { BATTLEFIELD, HAND, GRAVEYARD, LIBRARY, EXILE, STACK, COMMAND }

class Player(val id: String, val name: String, var life: Int?) {
    var drew = 0
    var lost = false
    /** The player asking the question is addressed as "you". */
    val you: Boolean get() = name.equals("me", true) || name.equals("you", true) || name.equals("i", true)
    val subject: String get() = if (you) "You" else name
    val possessive: String get() = if (you) "your" else "$name's"
    /** "You draw" / "Alice draws". */
    fun v(third: String, second: String) = if (you) second else third
}

class GameObject(
    val id: String,
    val def: CardDef,
    var zone: Zone,
    var controller: String,
    var owner: String = controller,
    var tapped: Boolean? = false,
    var summoningSick: Boolean? = null,
    val counters: MutableMap<String, Int> = mutableMapOf(),
    var damage: Int = 0,
    val token: Boolean = false,
) {
    /** Until-end-of-turn power/toughness modifications from resolved effects (611.2a). */
    val pumps = mutableListOf<Pair<Int, Int>>()
    var timestamp: Int = 0
    /** Combat status this turn. */
    var attacking: Ref? = null            // what this creature is attacking
    var blocking: String? = null          // id of the attacker this creature blocks
    var dealtDeathtouchDamage = false     // for 704.5h

    /** Until-end-of-turn keyword grants from resolved effects. */
    val tempKeywords = mutableSetOf<String>()
    /** Set by the owning GameState so characteristics include static effects from other permanents. */
    var state: GameState? = null

    val power: Int? get() = state?.powerOf(this) ?: def.power?.let { it + pumps.sumOf { p -> p.first } + (counters["+1/+1"] ?: 0) - (counters["-1/-1"] ?: 0) }
    val toughness: Int? get() = state?.toughnessOf(this) ?: def.toughness?.let { it + pumps.sumOf { p -> p.second } + (counters["+1/+1"] ?: 0) - (counters["-1/-1"] ?: 0) }
    fun has(keyword: String): Boolean = state?.hasKeyword(this, keyword) ?: (def.has(keyword) || keyword.lowercase() in tempKeywords)
    val name get() = def.name
    fun isOnBattlefield() = zone == Zone.BATTLEFIELD
    override fun toString() = "$name [$id]"
}

enum class StackKind { SPELL, ACTIVATED, TRIGGERED }

/** A reference to something in the game: an object, a stack item, or a player. */
sealed interface Ref {
    data class Obj(val id: String) : Ref
    data class Stack(val id: String) : Ref
    data class Player(val id: String) : Ref
}

class StackItem(
    val id: String,
    val kind: StackKind,
    val controller: String,
    /** The card on the stack (spells) or the permanent whose ability this is. */
    val source: GameObject,
    val effect: Effect?,
    val targets: List<Ref>,
    /** Zone each object target was in when chosen, for the 608.2b legality check. */
    val targetZones: Map<String, Zone>,
    val text: String,
) {
    val describe: String get() = when (kind) {
        StackKind.SPELL -> source.name
        StackKind.TRIGGERED -> "${source.name}'s triggered ability"
        StackKind.ACTIVATED -> "${source.name}'s activated ability"
    }
}

data class TraceStep(val text: String, val rules: List<String>)

class Trace {
    val steps = mutableListOf<TraceStep>()
    fun step(text: String, vararg rules: String) { steps += TraceStep(text, rules.toList()) }
}

data class Clarification(val about: String, val why: String)
data class Unsupported(val what: String, val detail: String)

class GameState(
    val players: List<Player>,
    val objects: LinkedHashMap<String, GameObject>,
    val stack: MutableList<StackItem> = mutableListOf(),
    var activePlayer: String? = null,
    var phase: String? = null,
    var step: String? = null,
) {
    val trace = Trace()
    var combatDamageDealt = false
    val outcomes = mutableListOf<String>()
    val assumptions = mutableListOf<String>()
    val clarifications = mutableListOf<Clarification>()
    val unsupported = mutableListOf<Unsupported>()
    private var nextId = 1
    private var clock = 1

    /** Registers an object so its characteristics see the static effects of everything else. */
    fun add(obj: GameObject): GameObject { objects[obj.id] = obj; obj.state = this; return obj }

    // ---- characteristics through the layer system (613) -----------------------------------

    /** Static effects currently applying to [obj] from permanents on the battlefield (604.2), with their sources. */
    fun staticEffectsOn(obj: GameObject): List<Pair<GameObject, StaticEffect>> {
        val out = mutableListOf<Pair<GameObject, StaticEffect>>()
        for (src in objects.values) {
            if (!src.isOnBattlefield()) continue
            for (ab in src.def.abilities.filterIsInstance<StaticAbility>()) for (eff in ab.effects) {
                val filter = when (eff) { is StaticEffect.PtModify -> eff.filter; is StaticEffect.KeywordGrant -> eff.filter; else -> continue }
                if (filter.other && src === obj) continue
                if (matches(filter, obj, src.controller)) out += src to eff
            }
        }
        return out
    }

    fun powerOf(obj: GameObject): Int? = obj.def.power?.let { base ->
        base + staticEffectsOn(obj).sumOf { (_, e) -> (e as? StaticEffect.PtModify)?.power ?: 0 } + obj.pumps.sumOf { it.first } + (obj.counters["+1/+1"] ?: 0) - (obj.counters["-1/-1"] ?: 0)
    }
    fun toughnessOf(obj: GameObject): Int? = obj.def.toughness?.let { base ->
        base + staticEffectsOn(obj).sumOf { (_, e) -> (e as? StaticEffect.PtModify)?.toughness ?: 0 } + obj.pumps.sumOf { it.second } + (obj.counters["+1/+1"] ?: 0) - (obj.counters["-1/-1"] ?: 0)
    }
    fun hasKeyword(obj: GameObject, keyword: String): Boolean {
        val k = keyword.lowercase()
        if (obj.def.has(k) || k in obj.tempKeywords) return true
        return staticEffectsOn(obj).any { (_, e) -> e is StaticEffect.KeywordGrant && k in e.keywords }
    }

    /** Qualities an object has protection from ("red", "everything", "creatures"), lowercase. */
    fun protections(obj: GameObject): Set<String> {
        val out = mutableSetOf<String>()
        val texts = obj.def.abilities.filterIsInstance<StaticAbility>().map { it.text } + obj.tempKeywords +
            staticEffectsOn(obj).flatMap { (_, e) -> (e as? StaticEffect.KeywordGrant)?.keywords ?: emptySet() }
        for (t in texts) Regex("""protection from ([a-z]+)(?: and from ([a-z]+))?""", RegexOption.IGNORE_CASE).findAll(t).forEach { m ->
            out += m.groupValues[1].lowercase(); if (m.groupValues[2].isNotEmpty()) out += m.groupValues[2].lowercase()
        }
        return out
    }

    /** Ward cost on an object, if any ("Ward {2}", "Ward—Pay 3 life"). */
    fun wardCost(obj: GameObject): String? = obj.def.abilities.filterIsInstance<StaticAbility>().firstNotNullOfOrNull { a ->
        Regex("""^Ward(?:\s*[—-]\s*|\s+)(.+?)\.?$""", RegexOption.IGNORE_CASE).find(a.text)?.groupValues?.get(1)
    }

    /** "3/3 (2/2, +1/+1 from Glorious Anthem, +0/+0 …)" for traces and echoes. */
    fun describePt(obj: GameObject): String {
        val p = obj.power ?: return "no power/toughness"
        val t = obj.toughness ?: return "no power/toughness"
        val parts = mutableListOf<String>()
        val statics = staticEffectsOn(obj).filter { it.second is StaticEffect.PtModify }
        for ((src, e) in statics) { e as StaticEffect.PtModify; parts += "${sign(e.power)}/${sign(e.toughness)} from ${src.name}" }
        if (obj.pumps.isNotEmpty()) parts += "${sign(obj.pumps.sumOf { it.first })}/${sign(obj.pumps.sumOf { it.second })} until end of turn"
        (obj.counters["+1/+1"] ?: 0).let { if (it > 0) parts += "$it +1/+1 counter${if (it > 1) "s" else ""}" }
        (obj.counters["-1/-1"] ?: 0).let { if (it > 0) parts += "$it -1/-1 counter${if (it > 1) "s" else ""}" }
        return if (parts.isEmpty()) "$p/$t" else "$p/$t (${obj.def.power}/${obj.def.toughness} base, ${parts.joinToString(", ")})"
    }
    private fun sign(n: Int) = if (n >= 0) "+$n" else "$n"

    /** Whether a permanent matches a filter, relative to [controller] (the source's controller). Mirrors Engine.filterMatches for battlefield objects. */
    fun matches(f: ObjFilter, o: GameObject, controller: String): Boolean {
        if (!o.isOnBattlefield()) return false
        val typeOk = f.kinds.any { k -> when (k) {
            Kind.CREATURE -> o.def.isCreature; Kind.ARTIFACT -> "Artifact" in o.def.types; Kind.ENCHANTMENT -> "Enchantment" in o.def.types
            Kind.LAND -> "Land" in o.def.types; Kind.PLANESWALKER -> "Planeswalker" in o.def.types; Kind.BATTLE -> "Battle" in o.def.types
            Kind.PERMANENT -> true; else -> false
        } }
        val notOk = f.notKinds.none { k -> when (k) { Kind.CREATURE -> o.def.isCreature; Kind.LAND -> "Land" in o.def.types; Kind.ARTIFACT -> "Artifact" in o.def.types; Kind.ENCHANTMENT -> "Enchantment" in o.def.types; else -> false } }
        val ctrlOk = when (f.controller) { null -> true; Who.YOU -> o.controller == controller; Who.OPPONENT -> o.controller != controller; else -> true }
        val subOk = f.subtypes.all { st -> o.def.subtypes.any { it.equals(st, true) } }
        val kwOk = f.keywords.all { hasKeyword(o, it) }
        val tokenOk = f.token == null || f.token == o.token
        val legOk = f.legendary == null || f.legendary == ("Legendary" in o.def.supertypes)
        val stateOk = (f.tapped == null || o.tapped == f.tapped) && (f.attacking == null || (o.attacking != null) == f.attacking)
        return typeOk && notOk && ctrlOk && subOk && kwOk && tokenOk && legOk && stateOk
    }

    fun player(id: String): Player = players.firstOrNull { it.id == id } ?: throw JudgeException("Unknown player '$id'")
    fun obj(id: String): GameObject = objects[id] ?: throw JudgeException("Unknown object '$id'")
    fun stackItem(id: String): StackItem? = stack.firstOrNull { it.id == id }
    fun opponentsOf(playerId: String) = players.filter { it.id != playerId }
    fun newStackId() = "s${nextId++}"
    fun tick() = clock++
    fun nameOf(ref: Ref): String = when (ref) {
        is Ref.Obj -> objects[ref.id]?.name ?: ref.id
        is Ref.Stack -> stackItem(ref.id)?.describe ?: ref.id
        is Ref.Player -> players.firstOrNull { it.id == ref.id }?.let { if (it.you) "you" else it.name } ?: ref.id
    }
}

class JudgeException(message: String) : RuntimeException(message)
