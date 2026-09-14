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

    val power: Int? get() = def.power?.let { it + pumps.sumOf { p -> p.first } + (counters["+1/+1"] ?: 0) - (counters["-1/-1"] ?: 0) }
    val toughness: Int? get() = def.toughness?.let { it + pumps.sumOf { p -> p.second } + (counters["+1/+1"] ?: 0) - (counters["-1/-1"] ?: 0) }
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
