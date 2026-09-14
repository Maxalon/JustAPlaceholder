package mtg.judge.engine

/** Who an effect or trigger refers to, relative to the ability's controller. */
enum class Who { YOU, OPPONENT, ANY_PLAYER, THAT_PLAYER, TARGET_PLAYER, CONTROLLER_OF_TARGET }

/** What an object filter may match. */
enum class Kind { CREATURE, ARTIFACT, ENCHANTMENT, LAND, PLANESWALKER, BATTLE, PERMANENT, SPELL, ABILITY, PLAYER, CARD }

/**
 * A description like "creature an opponent controls" or "activated or triggered ability".
 * `kinds` is a disjunction ("artifact or enchantment"); `notKinds` handles "noncreature";
 * `unknownWords` holds qualifiers the parser did not understand, which makes legality unverifiable.
 */
data class ObjFilter(
    val kinds: Set<Kind>,
    val notKinds: Set<Kind> = emptySet(),
    val controller: Who? = null,
    val attacking: Boolean? = null,
    val tapped: Boolean? = null,
    val unknownWords: List<String> = emptyList(),
    val raw: String = "",
    /** Required creature/other subtypes ("Elf creatures"), singular lowercase. */
    val subtypes: Set<String> = emptySet(),
    /** Required keywords ("creature with flying"). */
    val keywords: Set<String> = emptySet(),
    val token: Boolean? = null,
    val legendary: Boolean? = null,
    /** "other …": excludes the source of the effect. */
    val other: Boolean = false,
) {
    val verifiable get() = unknownWords.isEmpty()
}

data class TargetSpec(val filter: ObjFilter, val raw: String)

sealed interface Trigger {
    data class SpellCast(val who: Who, val spellFilter: ObjFilter? = null) : Trigger
    data object ThisEnters : Trigger
    data object ThisDies : Trigger
    data object ThisLeavesBattlefield : Trigger
    data object ThisAttacks : Trigger
    data class Unknown(val text: String) : Trigger
}

sealed interface Effect {
    data class Draw(val who: Who, val count: Int) : Effect
    data class Damage(val amount: Int, val target: TargetSpec) : Effect
    data class Counter(val target: TargetSpec) : Effect
    data class Destroy(val target: TargetSpec) : Effect
    data class Exile(val target: TargetSpec) : Effect
    data class Tap(val target: TargetSpec) : Effect
    data class Untap(val target: TargetSpec) : Effect
    data class Pump(val target: TargetSpec, val power: Int, val toughness: Int) : Effect
    /** "target creature gains flying until end of turn" (layer 6, 611.2a). */
    data class GainKeywords(val target: TargetSpec, val keywords: Set<String>) : Effect
    data class GainLife(val who: Who, val amount: Int) : Effect
    data class LoseLife(val who: Who, val amount: Int) : Effect
    data class May(val effect: Effect) : Effect
    data class UnlessPays(val effect: Effect, val payer: Who, val cost: String) : Effect
    data class Seq(val effects: List<Effect>) : Effect
    data class Unparsed(val text: String) : Effect

    /** Every target specification this effect (recursively) needs, in order. */
    fun targets(): List<TargetSpec> = when (this) {
        is Damage -> listOf(target); is Counter -> listOf(target); is Destroy -> listOf(target); is Exile -> listOf(target)
        is Tap -> listOf(target); is Untap -> listOf(target); is Pump -> listOf(target); is GainKeywords -> listOf(target)
        is May -> effect.targets(); is UnlessPays -> effect.targets(); is Seq -> effects.flatMap { it.targets() }
        is Draw, is GainLife, is LoseLife, is Unparsed -> emptyList()
    }

    fun hasUnparsed(): Boolean = when (this) {
        is Unparsed -> true; is May -> effect.hasUnparsed(); is UnlessPays -> effect.hasUnparsed(); is Seq -> effects.any { it.hasUnparsed() }
        else -> false
    }
}

/** Continuous effects from static abilities (604), applied in the layer system (613). */
sealed interface StaticEffect {
    /** Layer 7c: "[filter] get +N/+N". */
    data class PtModify(val filter: ObjFilter, val power: Int, val toughness: Int) : StaticEffect
    /** Layer 6: "[filter] have [keywords]". */
    data class KeywordGrant(val filter: ObjFilter, val keywords: Set<String>) : StaticEffect
}

sealed interface Ability { val text: String }
data class TriggeredAbility(val trigger: Trigger, val effect: Effect, override val text: String) : Ability
data class ActivatedAbility(val cost: String, val effect: Effect, override val text: String) : Ability
data class StaticAbility(override val text: String, val keyword: String? = null, val effects: List<StaticEffect> = emptyList()) : Ability
data class UnparsedAbility(override val text: String) : Ability

/** Everything the engine knows about a card, independent of any game. */
data class CardDef(
    val oracleId: String,
    val name: String,
    val typeLine: String,
    val supertypes: Set<String>,
    val types: Set<String>,
    val subtypes: Set<String>,
    val manaCost: String?,
    val manaValue: Double,
    val colors: Set<Char>,
    val power: Int?,
    val toughness: Int?,
    val keywords: Set<String>,
    val abilities: List<Ability>,
    /** For instants and sorceries: what the spell does on resolution. */
    val spellEffect: Effect?,
    val oracleText: String,
) {
    val isCreature get() = "Creature" in types
    val isPermanentCard get() = types.any { it in permanentTypes }
    val isInstantOrSorcery get() = "Instant" in types || "Sorcery" in types
    fun has(keyword: String) = keywords.any { it.equals(keyword, ignoreCase = true) }

    companion object {
        val permanentTypes = setOf("Creature", "Artifact", "Enchantment", "Land", "Planeswalker", "Battle", "Kindred")
        private val nameNoise = Regex("""^"?(.+?)"?$""")

        /** "Legendary Creature — Human Wizard" -> (Legendary), (Creature), (Human, Wizard). */
        fun splitTypeLine(typeLine: String): Triple<Set<String>, Set<String>, Set<String>> {
            val front = typeLine.substringBefore(" // ")
            val (left, right) = if (" — " in front) front.split(" — ", limit = 2).let { it[0] to it[1] } else front to ""
            val words = left.split(' ').filter { it.isNotBlank() }
            val supers = words.filter { it in setOf("Legendary", "Basic", "Snow", "World", "Ongoing", "Elite", "Host") }.toSet()
            val types = words.filter { it !in supers }.toSet()
            val subs = right.split(' ').filter { it.isNotBlank() }.toSet()
            return Triple(supers, types, subs)
        }

        fun parseStat(s: String?): Int? = s?.toIntOrNull()
    }
}
