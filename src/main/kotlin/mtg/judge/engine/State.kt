package mtg.judge.engine

enum class Zone { BATTLEFIELD, HAND, GRAVEYARD, LIBRARY, EXILE, STACK, COMMAND }

class Player(val id: String, val name: String, var life: Int?) {
    var drew = 0
    /** Cards drawn this turn, for "can't draw more than one card each turn" (Narset). */
    var drewThisTurn = 0
    var lost = false
    var poison = 0
    /** Cards in hand, when the situation said so (Ensnaring Bridge). */
    var handSize: Int? = null
    /** Devotion to each colour, when the situation said so ("my devotion to blue is 4"); otherwise counted from mana costs (700.5). */
    val devotion = mutableMapOf<Char, Int>()
    /** Mana available right now, when the situation said so ("only has one Mountain untapped"). */
    var mana: Int? = null
    /** Cards in library, when the situation said so (empty-library draws, 704.5b). */
    var librarySize: Int? = null
    /** Cards in graveyard, when the situation said so rather than naming them (threshold, delirium). */
    var graveyardSize: Int? = null
    /** Tried to draw from an empty library since state-based actions were last checked (121.4). */
    var drewFromEmpty = false
    /** Combat damage taken from each commander (903.10a), by object id. */
    val commanderDamage = mutableMapOf<String, Int>()
    /** The player asking the question is addressed as "you". */
    val you: Boolean get() = name.equals("me", true) || name.equals("you", true) || name.equals("i", true)
    val subject: String get() = if (you) "You" else name
    val possessive: String get() = if (you) "your" else "$name's"
    /** "You draw" / "Alice draws". */
    fun v(third: String, second: String) = if (you) second else third
}

/** What a land or artifact currently is while an "until end of turn, ~ becomes a creature" effect applies. */
data class Animation(val power: Int, val toughness: Int, val subtypes: List<String>, val colors: Set<Char>, val allCreatureTypes: Boolean, val stillALand: Boolean, val source: String)

class GameObject(
    val id: String,
    /** Not final: a permanent that entered as a copy of another (706.2) has that card's copiable values from then on. */
    var def: CardDef,
    var zone: Zone,
    var controller: String,
    var owner: String = controller,
    var tapped: Boolean? = false,
    var summoningSick: Boolean? = null,
    val counters: MutableMap<String, Int> = mutableMapOf(),
    var damage: Int = 0,
    val token: Boolean = false,
    /** A commander (Commander format): its combat damage is tracked per player (903.10a). */
    var commander: Boolean = false,
    /** After a blink: the new object this one came back as (400.7), so questions about "it" follow it. */
    var successor: String? = null,
    /** Times this commander has already been cast from the command zone (903.8: {2} more each time). */
    var commanderCasts: Int = 0,
    /** The card name chosen as this entered (Meddling Mage, Pithing Needle). */
    var chosenName: String? = null,
    /** Targets named for a permanent spell that itself targets nothing: they go to its enters-the-battlefield trigger (603.3d). */
    var etbTargets: List<Ref>? = null,
    /** X chosen when this was cast ("enters with X counters"). */
    var x: Int? = null,
    /** Whether the spell that became this permanent was kicked (702.33d): "if this was kicked, it enters with …". */
    var wasKicked: Boolean = false,
) {
    /** Until-end-of-turn power/toughness modifications from resolved effects (611.2a). */
    val pumps = mutableListOf<Pair<Int, Int>>()
    /** An until-end-of-turn "base power and toughness N/N" (layer 7b, 613.4b); applied before counters and pumps. */
    var basePt: Pair<Int, Int>? = null
    /** Set while "until end of turn, ~ becomes a creature" is in effect (Mutavault). Cleared at cleanup and on a zone change. */
    var animatedAs: Animation? = null
    /** The controller an until-end-of-turn control change (Threaten) hands this back to at cleanup. */
    var controlRevertsTo: String? = null
    var timestamp: Int = 0
    /** Combat status this turn. */
    /** Aura/Equipment: id of the object this is attached to. */
    var attachedTo: String? = null
    var attacking: Ref? = null            // what this creature is attacking
    var blocking: String? = null          // id of the attacker this creature blocks
    var wasBlocked = false                // declared blocked this combat: stays blocked even if the blocker leaves (509.1h)
    var dealtDeathtouchDamage = false     // for 704.5h
    /** Set by "if a creature dealt damage this way would die this turn, exile it instead": the effect's name. */
    var exileOnDeath: String? = null
    /** Power as it last was on the battlefield (last known information, 113.7a) for "equal to its power" after a zone change. */
    var lkiPower: Int? = null

    /** Until-end-of-turn keyword grants from resolved effects. */
    val tempKeywords = mutableSetOf<String>()
    /** Monstrous: set by monstrosity and never unset while the permanent stays on the battlefield (701.31b). */
    var monstrous: Boolean = false
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
    var targets: List<Ref>,
    /** Zone each object target was in when chosen, for the 608.2b legality check. */
    val targetZones: Map<String, Zone>,
    val text: String,
    /** Chosen modes (1-based) for modal spells and abilities (700.2). */
    val modes: List<Int> = emptyList(),
    /** For triggered abilities: the player whose action caused the trigger ("that player"). */
    val causedBy: String? = null,
    /** For triggered abilities: the amount in the causing event ("that much life", "that much damage"). */
    val causedAmount: Int? = null,
    /** For triggered abilities: the object in the causing event ("that creature"). */
    val causedObject: String? = null,
    /** Cast for its evoke cost (702.74a): sacrificed by its own trigger when it enters. */
    val evoked: Boolean = false,
    /** Cast from the graveyard for its flashback cost: it is exiled as it resolves rather than going back there (702.34a). */
    val flashback: Boolean = false,
    /** The value chosen for X when this was cast or activated (107.3a). */
    val x: Int? = null,
    /** Whether the kicker cost was paid (702.33d). */
    val kicked: Boolean = false,
    /** A choice made on activation or casting ("the color of your choice"). */
    val choice: String? = null,
    /** The situation named no target for a spell that needs one: it's on the stack (so it can be countered or responded to) but its effect can't be shown. */
    val targetsUnknown: Boolean = false,
) {
    /** Life lost by players as this resolves ("the life lost this way"). */
    var lifeLost: Int = 0
    /** Objects this item dealt damage to ("a creature dealt damage this way"). */
    val damaged = mutableSetOf<String>()
    /** How many objects the last "all …" part of this item affected ("that many"). */
    var lastCount: Int? = null
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
    /** The player who is the monarch, if any (725.1). No monarch until an effect makes one. */
    var monarch: String? = null,
    /** Players who can't lose the game this turn (Angel's Grace); cleared in the cleanup step. */
    val cantLoseThisTurn: MutableSet<String> = mutableSetOf(),
    var phase: String? = null,
    var step: String? = null,
) {
    val trace = Trace()
    var combatDamageDealt = false
    /** The game's turn number, when the situation said so. */
    var turnNumber: Int? = null
    /** Creatures whose combat damage, dealt and received, is prevented this turn (Maze of Ith). */
    val combatDamageMuted = mutableSetOf<String>()
    /** Spells cast this turn, per player (storm counts, Aetherflux Reservoir). */
    val spellsThisTurn = mutableMapOf<String, Int>()
    /** Attackers whose "attacks and isn't blocked" trigger has already been put on the stack this combat. */
    val unblockedTriggered = mutableSetOf<String>()
    /** How many times each permanent has been targeted, for "the first time each turn" triggers. A situation is one turn. */
    val targetedThisTurn = mutableMapOf<String, Int>()
    /** The spells each player has cast this turn, for limits that only count some of them (Ethersworn Canonist). */
    val matchingSpellsThisTurn = mutableMapOf<String, MutableList<CardDef>>()
    /** Spells cast this game by name (Approach of the Second Sun). */
    val spellsCast = mutableMapOf<String, Int>()
    /** Prevention/regeneration shields created by resolved effects this turn (615.7, 701.19a). */
    val shields = mutableListOf<Shield>()
    val outcomes = mutableListOf<String>()
    /** The card most recently revealed off the top of a library (Dark Confidant's "its mana value"). */
    var lastRevealed: GameObject? = null
    /** The spell most recently countered (Mana Drain's "that spell's mana value"). */
    var lastCountered: GameObject? = null
    /** The permanent most recently sacrificed (Fling's "the sacrificed creature's power"), with last-known information. */
    var lastSacrificed: GameObject? = null
    val assumptions = mutableListOf<String>()
    /** Players who said they will pay the next "unless … pays" cost asked of them (Rhystic Study, Mana Leak…). */
    val willPay = mutableSetOf<String>()
    /** Choices announced for a permanent's next triggered ability, by source object id ("put Rakdos with Kaalia's trigger"): an object id. */
    val pendingChoices = mutableMapOf<String, String>()
    /** Players who said they will not pay the next optional cost asked of them. */
    val wontPay = mutableSetOf<String>()
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
            if (src.def.isCreature && abilitiesLostOn(src) != null) continue
            for (ab in src.def.abilities.filterIsInstance<StaticAbility>()) for (eff in ab.effects) {
                val filter = when (eff) { is StaticEffect.PtModify -> eff.filter; is StaticEffect.KeywordGrant -> eff.filter; else -> continue }
                if (filter.other && src === obj) continue
                if (eff is StaticEffect.PtModify && eff.self) { if (src === obj && conditionHolds(eff.condition, src)) out += src to eff; continue }
                if (filter.raw == "~") { if (src === obj) out += src to eff; continue }
                if (matches(filter, obj, src.controller, src)) out += src to eff
            }
        }
        return out
    }

    /** A player's devotion to a colour: the colour's symbols in the mana costs of the permanents they control (700.5). */
    fun devotion(playerId: String, colour: Char): Int {
        val p = players.firstOrNull { it.id == playerId } ?: return 0
        return p.devotion[colour] ?: objects.values.filter { it.isOnBattlefield() && it.controller == playerId }.sumOf { o -> (o.def.manaCost ?: "").count { ch -> ch == colour } }
    }

    /** The name of the ability keeping this permanent from being a creature right now, or null when it is one (or never was). */
    fun notACreatureBecause(o: GameObject): String? {
        if (!o.def.isCreature || !o.isOnBattlefield()) return null
        val god = o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.NotACreatureUnlessDevotion>().firstOrNull() ?: return null
        val have = devotion(o.controller, god.colour)
        val colourName = when (god.colour) { 'W' -> "white"; 'U' -> "blue"; 'B' -> "black"; 'R' -> "red"; else -> "green" }
        return if (have < god.threshold) "devotion to $colourName is $have, less than ${god.threshold}" else null
    }

    /** The permanent naming a colour that everything is, if one is on the battlefield (Painter's Servant), paired with the colour it chose. */
    fun painter(): Pair<GameObject, Char>? = objects.values.firstOrNull { o ->
        o.isOnBattlefield() && o.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.any { it is StaticEffect.EverythingIsChosenColour }
    }?.let { o -> colourChar(o.chosenName)?.let { o to it } }

    private fun colourChar(name: String?): Char? = when (name?.lowercase()) { "white" -> 'W'; "blue" -> 'U'; "black" -> 'B'; "red" -> 'R'; "green" -> 'G'; else -> null }

    /** An object's colours right now — printed, plus the colour Painter's Servant names (layer 5). */
    fun colorsOf(o: GameObject): Set<Char> {
        val own = o.animatedAs?.colors?.takeIf { it.isNotEmpty() } ?: o.def.colors
        return painter()?.let { (_, c) -> own + c } ?: own
    }

    /** Whether a permanent is a creature right now — printed type, minus anything currently turning it off. */
    fun isCreature(o: GameObject): Boolean = o.animatedAs != null || (o.def.isCreature && notACreatureBecause(o) == null)

    /** Whether a static ability's condition currently holds for its source. */
    fun conditionHolds(c: Condition?, src: GameObject): Boolean = when (c) {
        null -> true
        is Condition.LifeAtLeast -> {
            val p = if (c.opponent) players.firstOrNull { it.id != src.controller } else players.firstOrNull { it.id == src.controller }
            p?.life?.let { it >= c.amount } ?: false
        }
        Condition.YourTurn -> activePlayer == src.controller
        Condition.NotYourTurn -> activePlayer != null && activePlayer != src.controller
        is Condition.ControlsMatching -> objects.values.count { it !== src && matches(c.filter, it, src.controller, src) || (it === src && matches(c.filter, it, src.controller, src)) } >= c.atLeast
        is Condition.GraveyardAtLeast -> {
            val cards = objects.values.filter { it.zone == Zone.GRAVEYARD && it.controller == src.controller && !it.token }
            // A graveyard can be given as a count ("my graveyard has seven cards") or as named cards; take whichever says more.
            val stated = players.firstOrNull { it.id == src.controller }?.graveyardSize ?: 0
            (if (c.cardTypes) cards.flatMap { o -> o.def.types.filter { it in graveyardCardTypes } }.toSet().size
             else maxOf(cards.size, stated)) >= c.amount
        }
        Condition.WasKicked -> src.wasKicked
        is Condition.SourceTapped -> (src.tapped == true) == c.tapped
        is Condition.Unknown -> false
    }

    /** What a static ability's condition asks for, for traces and for saying why one doesn't apply. */
    fun describeCondition(c: Condition): String = when (c) {
        is Condition.LifeAtLeast -> "${if (c.opponent) "an opponent" else "its controller"} at ${c.amount} or more life"
        Condition.YourTurn -> "it to be its controller's turn"
        Condition.NotYourTurn -> "it to be another player's turn"
        is Condition.ControlsMatching -> "its controller to control ${if (c.atLeast > 1) "${c.atLeast} or more " else "a "}${c.filter.raw ?: "matching permanent"}"
        is Condition.GraveyardAtLeast -> "${c.amount} or more ${if (c.cardTypes) "card types among cards in" else "cards in"} its controller's graveyard"
        Condition.WasKicked -> "the spell to have been kicked"
        is Condition.SourceTapped -> "it to be ${if (c.tapped) "tapped" else "untapped"}"
        is Condition.Unknown -> c.text
    }

    /** Layer 7a: a characteristic-defining ability's value, or null when it depends on something the situation doesn't say. */
    fun cdaValuePublic(obj: GameObject, expr: CountExpr?): Int? = cdaValue(obj, expr)

    private fun cdaValue(obj: GameObject, expr: CountExpr?): Int? = when (expr) {
        null -> null
        is CountExpr.Permanents -> objects.values.count { matches(expr.filter, it, obj.controller, obj) }
        is CountExpr.CardTypesInGraveyards -> cardTypesInGraveyards().size
        is CountExpr.YourLifeTotal -> players.firstOrNull { it.id == obj.controller }?.life
        is CountExpr.Unknown -> null
    }
    /** The card types among cards in every graveyard (Tarmogoyf); tokens are not cards, and a Kindred card counts its type. */
    fun cardTypesInGraveyards(): Set<String> = objects.values.filter { it.zone == Zone.GRAVEYARD && !it.token }.flatMap { o -> o.def.types.filter { it in graveyardCardTypes }.map { if (it == "Tribal") "Kindred" else it } }.toSet()
    private val graveyardCardTypes = setOf("Artifact", "Battle", "Creature", "Enchantment", "Instant", "Kindred", "Tribal", "Land", "Planeswalker", "Sorcery")
    fun cdaOf(obj: GameObject): StaticEffect.PtCda? = obj.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.PtCda>().firstOrNull()

    /** A Humility-style effect applying to [obj] right now, with the permanent it comes from. */
    fun abilitiesLostOn(obj: GameObject): Pair<GameObject, StaticEffect.LoseAbilitiesSetPt>? {
        if (!obj.isOnBattlefield()) return null
        for (src in objects.values) {
            if (!src.isOnBattlefield()) continue
            for (ab in src.def.abilities.filterIsInstance<StaticAbility>()) for (eff in ab.effects) {
                if (eff !is StaticEffect.LoseAbilitiesSetPt) continue
                if (matches(eff.filter, obj, src.controller, src)) return src to eff
            }
        }
        return null
    }

    private fun basePower(obj: GameObject): Int? = obj.animatedAs?.power ?: obj.basePt?.first ?: abilitiesLostOn(obj)?.second?.power ?: cdaOf(obj)?.let { c -> if (c.power != null) cdaValue(obj, c.power)?.plus(c.plus) else obj.def.power } ?: obj.def.power
    private fun baseToughness(obj: GameObject): Int? = obj.animatedAs?.toughness ?: obj.basePt?.second ?: abilitiesLostOn(obj)?.second?.toughness ?: cdaOf(obj)?.let { c -> if (c.toughness != null) cdaValue(obj, c.toughness)?.plus(c.toughnessPlus ?: c.plus) else obj.def.toughness } ?: obj.def.toughness

    /** "~ gets -X/-X, where X is your life total": the amount, recomputed each time it's asked for. */
    fun selfCountPt(obj: GameObject, toughness: Boolean = false): Int {
        if (abilitiesLostOn(obj) != null) return 0
        var out = 0
        for (e in obj.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.PtModifyByCount>()) {
            val n = cdaValuePublic(obj, e.count) ?: continue
            val step = if (toughness) e.toughness else e.power
            out += if (e.negative) -n * step else n * step
        }
        return out
    }

    fun powerOf(obj: GameObject): Int? = basePower(obj)?.let { base ->
        base + staticEffectsOn(obj).sumOf { (_, e) -> (e as? StaticEffect.PtModify)?.power ?: 0 } + selfCountPt(obj) + obj.pumps.sumOf { it.first } + (obj.counters["+1/+1"] ?: 0) - (obj.counters["-1/-1"] ?: 0)
    }
    fun toughnessOf(obj: GameObject): Int? = baseToughness(obj)?.let { base ->
        base + staticEffectsOn(obj).sumOf { (_, e) -> (e as? StaticEffect.PtModify)?.toughness ?: 0 } + selfCountPt(obj, toughness = true) + obj.pumps.sumOf { it.second } + (obj.counters["+1/+1"] ?: 0) - (obj.counters["-1/-1"] ?: 0)
    }
    /** Keywords a keyword counter can be (122.1b). */
    val keywordCounters = setOf("flying", "first strike", "double strike", "deathtouch", "decayed", "exalted", "haste", "hexproof", "indestructible", "lifelink", "menace", "reach", "shadow", "trample", "vigilance")

    fun hasKeyword(obj: GameObject, keyword: String): Boolean {
        val k = keyword.lowercase()
        val stripped = abilitiesLostOn(obj) != null
        if (!stripped && (obj.def.has(k) || k in obj.tempKeywords)) return true
        if (k in keywordCounters && (obj.counters[k] ?: 0) > 0) return true   // 122.1b: a keyword counter grants the keyword, and isn't an ability of the creature
        return staticEffectsOn(obj).any { (src, e) ->
            e is StaticEffect.KeywordGrant && k in e.keywords && (e.filter.raw != "~" || src === obj) && conditionalKeywordOk(src, e) &&
                abilitiesLostOn(src) == null   // a lord that lost its own abilities grants nothing
        }
    }

    /** A "~ has X as long as …" grant is parsed as a zero PtModify carrying the condition plus a KeywordGrant on "~"; honour the condition. */
    private fun conditionalKeywordOk(src: GameObject, e: StaticEffect.KeywordGrant): Boolean {
        if (e.filter.raw != "~") return true
        val cond = src.def.abilities.filterIsInstance<StaticAbility>().firstOrNull { it.effects.contains(e) }?.effects?.filterIsInstance<StaticEffect.PtModify>()?.firstOrNull { it.self }?.condition
        return conditionHolds(cond, src)
    }

    /** Qualities an object has protection from ("red", "everything", "creatures"), lowercase. */
    fun protections(obj: GameObject): Set<String> {
        val out = mutableSetOf<String>()
        val stripped = abilitiesLostOn(obj) != null
        val texts = (if (stripped) emptyList() else obj.def.abilities.filterIsInstance<StaticAbility>().map { it.text } + obj.tempKeywords) +
            staticEffectsOn(obj).filter { (src, _) -> abilitiesLostOn(src) == null }.flatMap { (_, e) -> (e as? StaticEffect.KeywordGrant)?.keywords ?: emptySet() }
        for (t in texts) Regex("""protection from ([a-z]+(?: spells)?)(?: and from ([a-z]+(?: spells)?))?""", RegexOption.IGNORE_CASE).findAll(t).forEach { m ->
            out += m.groupValues[1].lowercase(); if (m.groupValues[2].isNotEmpty()) out += m.groupValues[2].lowercase()
        }
        return out
    }

    /** Ward cost on an object, if any ("Ward {2}", "Ward—Pay 3 life"). */
    fun wardCost(obj: GameObject): String? = obj.def.abilities.filterIsInstance<StaticAbility>().firstNotNullOfOrNull { a ->
        Regex("""^Ward(?:\s*[—-]\s*|\s+)(.+?)\.?$""", RegexOption.IGNORE_CASE).find(a.text)?.groupValues?.get(1)
    // A ward granted for the turn (an animated creature-land, say) counts the same as a printed one.
    } ?: obj.tempKeywords.firstNotNullOfOrNull { k -> Regex("""^ward(?:\s*[—-]\s*|\s+)(.+)$""", RegexOption.IGNORE_CASE).find(k)?.groupValues?.get(1) }

    /** "3/3 (2/2, +1/+1 from Glorious Anthem, +0/+0 …)" for traces and echoes. */
    fun describePt(obj: GameObject): String {
        if (obj.animatedAs == null) notACreatureBecause(obj)?.let { why -> return "not a creature right now ($why), so no power or toughness" }
        val p = obj.power ?: return "no power/toughness"
        val t = obj.toughness ?: return "no power/toughness"
        val parts = mutableListOf<String>()
        val lost = abilitiesLostOn(obj)
        if (lost != null) parts += "base set to ${lost.second.power}/${lost.second.toughness} by ${lost.first.name}, which also takes its abilities away (layers 6 and 7b)"
        if (lost == null) cdaOf(obj)?.let { c -> parts += "base set by its own ability (layer 7a${if (c.power is CountExpr.CardTypesInGraveyards || c.toughness is CountExpr.CardTypesInGraveyards) ": ${cardTypesInGraveyards().size} card type${if (cardTypesInGraveyards().size == 1) "" else "s"} in graveyards${cardTypesInGraveyards().takeIf { it.isNotEmpty() }?.let { t -> " (" + t.sorted().joinToString(", ") + ")" } ?: ""}" else ""})" }
        val statics = staticEffectsOn(obj).filter { it.second is StaticEffect.PtModify && !((it.second as StaticEffect.PtModify).power == 0 && (it.second as StaticEffect.PtModify).toughness == 0) }
        for ((src, e) in statics) { e as StaticEffect.PtModify; parts += "${sign(e.power)}/${sign(e.toughness)} from ${if (src === obj) "its own ability" + (e.condition?.let { " (condition met)" } ?: "") else src.name}" }
        // A size-changing ability of its own whose condition isn't met: saying so is the difference between "it is
        // 1/1" and "it is 1/1 because the graveyard you described has three cards in it, not seven".
        if (lost == null) for (e in obj.def.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.PtModify>())
            if (e.self && e.condition != null && !(e.power == 0 && e.toughness == 0) && !conditionHolds(e.condition, obj))
                parts += "not ${sign(e.power)}/${sign(e.toughness)} from its own ability, which needs ${describeCondition(e.condition)}"
        if (obj.pumps.isNotEmpty()) parts += "${sign(obj.pumps.sumOf { it.first })}/${sign(obj.pumps.sumOf { it.second })} until end of turn"
        (obj.counters["+1/+1"] ?: 0).let { if (it > 0) parts += "$it +1/+1 counter${if (it > 1) "s" else ""}" }
        (obj.counters["-1/-1"] ?: 0).let { if (it > 0) parts += "$it -1/-1 counter${if (it > 1) "s" else ""}" }
        return if (parts.isEmpty()) "$p/$t" else "$p/$t (${basePower(obj)}/${baseToughness(obj)} base, ${parts.joinToString(", ")})"
    }
    private fun sign(n: Int) = if (n >= 0) "+$n" else "$n"

    /** Whether a permanent matches a filter, relative to [controller] (the source's controller). Mirrors Engine.filterMatches for battlefield objects. */
    fun matches(f: ObjFilter, o: GameObject, controller: String, source: GameObject? = null, anyZone: Boolean = false): Boolean {
        if (!anyZone && !o.isOnBattlefield()) return false
        if (f.attachedToSource && (source == null || source.attachedTo != o.id)) return false
        if (f.other && source != null && source === o) return false
        val typeOk = f.kinds.any { k -> when (k) {
            Kind.CREATURE -> isCreature(o); Kind.ARTIFACT -> "Artifact" in o.def.types; Kind.ENCHANTMENT -> "Enchantment" in o.def.types
            Kind.LAND -> "Land" in o.def.types; Kind.PLANESWALKER -> "Planeswalker" in o.def.types; Kind.BATTLE -> "Battle" in o.def.types
            Kind.PERMANENT -> true; Kind.CARD -> !o.token; else -> false
        } }
        val notOk = f.notKinds.none { k -> when (k) { Kind.CREATURE -> isCreature(o); Kind.LAND -> "Land" in o.def.types; Kind.ARTIFACT -> "Artifact" in o.def.types; Kind.ENCHANTMENT -> "Enchantment" in o.def.types; else -> false } }
        val notSubOk = f.notSubtypes.none { st -> o.def.subtypes.any { it.equals(st, true) } }
        val ctrlOk = when (f.controller) { null -> true; Who.YOU -> o.controller == controller; Who.OPPONENT -> o.controller != controller; else -> true }
        val anim = o.animatedAs
        fun hasSub(st: String) = o.def.subtypes.any { it.equals(st, true) } || (anim?.subtypes?.any { it.equals(st, true) } == true) ||
            (o.def.changeling && o.def.isCreature) || anim?.allCreatureTypes == true
        val subOk = if (f.subtypesAny && f.subtypes.isNotEmpty()) f.subtypes.any { st -> hasSub(st) }
                    else f.subtypes.all { st -> hasSub(st) || (st.equals("basic", true) && o.def.supertypes.any { it.equals("Basic", true) }) || (st.equals("snow", true) && o.def.supertypes.any { it.equals("Snow", true) }) }
        val kwOk = f.keywords.all { hasKeyword(o, it) } && f.notKeywords.none { hasKeyword(o, it) }
        val tokenOk = f.token == null || f.token == o.token
        val legOk = f.legendary == null || f.legendary == ("Legendary" in o.def.supertypes)
        val stateOk = (f.tapped == null || o.tapped == f.tapped) && (f.attacking == null || (o.attacking != null) == f.attacking)
        val powerOk = (f.minPower == null || (o.power ?: 0) >= f.minPower) && (f.maxPower == null || (o.power ?: 0) <= f.maxPower) && (f.maxManaValue == null || o.def.manaValue.toInt() <= f.maxManaValue) && (f.minManaValue == null || o.def.manaValue.toInt() >= f.minManaValue)
        val cols = colorsOf(o)
        val colorOk = f.colors.all { it in cols } && f.notColors.none { it in cols }
        return typeOk && notOk && notSubOk && ctrlOk && subOk && kwOk && tokenOk && legOk && stateOk && powerOk && colorOk
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

/** A shield from a resolved effect: applies to [objectId] / [playerId] (null = the filter in the replacement), with a remaining amount for "prevent the next N". */
class Shield(val replacement: Replacement, val objectId: String?, val playerId: String?, var remaining: Int?, val sourceName: String)

class JudgeException(message: String) : RuntimeException(message)
