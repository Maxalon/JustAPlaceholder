package mtg.judge.engine

/** Who an effect or trigger refers to, relative to the ability's controller. */
enum class Who { YOU, OPPONENT, ANY_PLAYER, THAT_PLAYER, TARGET_PLAYER, CONTROLLER_OF_TARGET, EACH_PLAYER, EACH_OPPONENT }

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
    /** "enchanted creature" / "equipped creature": only the object the source is attached to. */
    val attachedToSource: Boolean = false,
    /** "with power N or greater" / "with power N or less". */
    val minPower: Int? = null,
    val maxPower: Int? = null,
    /** "a Plains or an Island": any one of [subtypes] suffices instead of all of them. */
    val subtypesAny: Boolean = false,
    /** "black creature" / "nonblack creature": colour letters (W U B R G) required / forbidden. */
    val colors: Set<Char> = emptySet(),
    val notColors: Set<Char> = emptySet(),
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
    /** "When you cast ~" */
    data object ThisCast : Trigger
    /** "At the beginning of [whose] [step]" (603.2b). step: upkeep | draw | precombat_main | combat | declare_attackers | end | ... */
    data class BeginningOfStep(val step: String, val whose: Who) : Trigger
    /** "Whenever ~ deals combat damage to a player" (toPlayer) / "deals damage to a creature" etc. */
    data class ThisDealsDamage(val combatOnly: Boolean, val toPlayer: Boolean?) : Trigger
    /** "Whenever a [filter] enters (the battlefield under your control)" incl. landfall. */
    data class PermanentEnters(val filter: ObjFilter, val other: Boolean) : Trigger
    /** "Whenever you attack" / "Whenever you attack with one or more creatures". */
    data object YouAttack : Trigger
    /** Exalted: "Whenever a creature you control attacks alone". */
    data object CreatureAttacksAlone : Trigger
    /** "Whenever you gain life". */
    data object YouGainLife : Trigger
    /** "Whenever a [filter] dies". */
    data class PermanentDies(val filter: ObjFilter, val other: Boolean) : Trigger
    /** "Whenever you draw a card". */
    data object YouDraw : Trigger
    /** "Whenever one or more land cards are put into your graveyard from anywhere" (The Gitrog Monster). */
    data class CardsToYourGraveyard(val filter: ObjFilter) : Trigger
    /** "Whenever an opponent draws a card" / "a player draws a card". */
    data class PlayerDraws(val who: Who) : Trigger
    /** "Whenever you draw your second card each turn". */
    data class YouDrawNth(val n: Int) : Trigger
    /** "Whenever ~ is dealt damage". */
    data object ThisIsDealtDamage : Trigger
    /** "Whenever ~ becomes blocked". */
    data object ThisBecomesBlocked : Trigger
    /** "Whenever ~ blocks". */
    data object ThisBlocks : Trigger
    /** "Whenever ~ becomes the target of a spell or ability". */
    data object ThisBecomesTarget : Trigger
    /** "Whenever ~ becomes tapped". */
    data object ThisBecomesTapped : Trigger
    /** "When you cycle ~". */
    data object ThisCycled : Trigger
    /** "Whenever [filter] attacks" (e.g. equipped creature attacks). */
    data class PermanentAttacks(val filter: ObjFilter) : Trigger
    /** "Whenever [filter] deals combat damage to a player". */
    data class PermanentDealsCombatDamageToPlayer(val filter: ObjFilter) : Trigger
    /** "Whenever one or more creatures you control deal combat damage to a player". */
    data object YourCreaturesDealCombatDamageToPlayer : Trigger
    data class Unknown(val text: String) : Trigger
}

sealed interface Effect {
    data class Draw(val who: Who, val count: Int, val x: Boolean = false) : Effect
    /** `x` = the amount is X, chosen when the spell is cast (107.3a). */
    data class Damage(val amount: Int, val target: TargetSpec, val x: Boolean = false, val kickedAmount: Int? = null, val sacrificedPower: Boolean = false) : Effect
    /** "Proliferate" (701.34a): assumed to choose everything of yours and your opponents' poison counters. */
    data object Proliferate : Effect
    /** "Exile all attacking creatures target player controls": an action on everything matching, among a target player's permanents. */
    data class ForAllTargeted(val target: TargetSpec, val filter: ObjFilter, val action: String) : Effect
    data class Counter(val target: TargetSpec) : Effect
    data class Destroy(val target: TargetSpec, val noRegen: Boolean = false) : Effect
    /** "~ deals N damage to that player / each opponent / each player / you". */
    data class DamagePlayer(val who: Who, val amount: Int) : Effect
    /** "Create a 3/3 green Beast creature token" / "Its controller creates …": [who] gets [count] tokens described by [token]. */
    data class CreateToken(val who: Who, val count: Int, val token: String, val countBy: CountExpr? = null) : Effect
    /** "Copy target instant or sorcery spell. You may choose new targets for the copy." (707.10) */
    data class CopySpell(val target: TargetSpec, val newTargets: Boolean) : Effect
    /** Thassa's Oracle: "look at the top X cards … where X is your devotion to [color] … If X is greater than or equal to the number of cards in your library, you win the game." */
    data class WinIfDevotionCoversLibrary(val color: Char) : Effect
    /** Aetherflux Reservoir: "you gain N life for each spell you've cast this turn". */
    data class GainLifePerSpellThisTurn(val who: Who, val per: Int) : Effect
    /** "Target player mills N cards" (701.17a). */
    data class Mill(val who: Who, val count: Int) : Effect
    /** "Each other player sacrifices a creature of their choice." */
    data class SacrificeEach(val who: Who, val filter: ObjFilter, val greatestPower: Boolean = false) : Effect
    /** Cloudshift, Ephemerate: "Exile target creature you control, then return it to the battlefield under your / its owner's control." */
    data class Blink(val target: TargetSpec, val ownersControl: Boolean) : Effect
    /** Spellskite: "Change a target of target spell or ability to ~." */
    data class RedirectToSelf(val target: TargetSpec) : Effect
    /** "Repeat the following process X times." followed by the process. */
    data class Repeat(val body: Effect, val times: Int, val x: Boolean) : Effect
    /** "Each opponent loses N life unless that player sacrifices a [filter] of their choice or discards a card." (Torment of Hailfire) */
    data class LoseLifeUnlessSacOrDiscard(val who: Who, val amount: Int, val filter: ObjFilter?, val discard: Boolean) : Effect
    /** "Sacrifice this permanent" (evoke's trigger, "sacrifice ~"). */
    object SacrificeSource : Effect
    /** "That player sacrifices that many permanents" (Phyrexian Obliterator): as many as the causing amount, their choice. */
    data class SacrificeThatMany(val who: Who, val filter: ObjFilter) : Effect
    /** "Put a creature card from your hand onto the battlefield" (Aether Vial: with mana value equal to its charge counters). */
    data class PutFromHand(val filter: ObjFilter, val mvEqualsCounters: String? = null, val tapped: Boolean = false, val attacking: Boolean = false, val fromLibrary: Boolean = false, val maxMv: Int? = null, val fromGraveyard: Boolean = false) : Effect
    /** Maze of Ith: "Prevent all combat damage that would be dealt to and dealt by that creature this turn." */
    data class PreventCombatToAndBy(val target: TargetSpec) : Effect
    /** Approach of the Second Sun: win if another spell with this name was cast this game, else tuck it seventh from the top and gain life. */
    data class WinIfCastBefore(val life: Int) : Effect
    /** "Return target X to its owner's hand" (null target = ~). */
    data class Bounce(val target: TargetSpec?) : Effect
    /** "Its controller gains life equal to its power" (uses last known information after a zone change). */
    data class GainLifeEqualToPower(val who: Who) : Effect
    /** Something that targets but whose effect is only narrated ("Target player reveals their hand"). */
    data class NarratedTargeted(val target: TargetSpec, val text: String, val rules: List<String>) : Effect
    data class Exile(val target: TargetSpec) : Effect
    data class Tap(val target: TargetSpec) : Effect
    data class Untap(val target: TargetSpec) : Effect
    data class Pump(val target: TargetSpec, val power: Int, val toughness: Int) : Effect
    /** Exalted's "that creature gets +1/+1": the attacking creature that caused the trigger. */
    data class PumpCausing(val power: Int, val toughness: Int, val keywords: List<String> = emptyList()) : Effect
    /** "target creature gains flying until end of turn" (layer 6, 611.2a). */
    data class GainKeywords(val target: TargetSpec, val keywords: Set<String>) : Effect
    data class GainLife(val who: Who, val amount: Int) : Effect
    data class LoseLife(val who: Who, val amount: Int, val x: Boolean = false) : Effect
    /** Exsanguinate: "You gain life equal to the life lost this way." */
    data object GainLifeLostThisWay : Effect
    /** Condemn: "Put target attacking creature on the bottom of its owner's library." */
    data class PutOnBottom(val target: TargetSpec) : Effect
    /** Condemn: "Its controller gains life equal to its toughness." */
    data class GainLifeEqualToToughness(val who: Who) : Effect
    /** "Target opponent loses that much life" after "whenever you gain life": the amount of the causing event. */
    data class LoseLifeThatMuch(val who: Who) : Effect
    /** "Creatures you control get +X/+X (and gain trample) until end of turn, where X is the number of [count]." */
    data class PumpAllCount(val filter: ObjFilter, val count: CountExpr, val keywords: List<String>) : Effect
    /** "The owner of target permanent shuffles it into their library." */
    data class ShuffleIntoLibrary(val target: TargetSpec) : Effect
    /** "~ gets +N/+N until end of turn" (no target). */
    data class PumpSelf(val power: Int, val toughness: Int) : Effect
    /** "[filter] get +N/+N until end of turn": affects the objects present when it resolves (611.2c). */
    data class PumpAll(val filter: ObjFilter, val power: Int, val toughness: Int, val keywords: List<String> = emptyList(), val x: Boolean = false) : Effect
    /** "[filter] have base power and toughness N/N (X/X) until end of turn": a layer-7b setting effect (613.4b) on the objects present when it resolves. */
    data class SetBasePtAll(val filter: ObjFilter, val power: Int, val toughness: Int, val x: Boolean = false, val allCreatureTypes: Boolean = false) : Effect
    /** "Gain control of target creature (until end of turn)". */
    data class GainControl(val target: TargetSpec, val untilEndOfTurn: Boolean) : Effect
    /** "Put N [kind] counters on target …" / "… on ~" (target null = self). */
    /** `target` = "target …"; null = on ~; `all` = "on each …". */
    data class PutCounters(val target: TargetSpec?, val kind: String, val count: Int, val all: ObjFilter? = null) : Effect
    /** "Remove all counters from target permanent." (Vampire Hexmage) */
    data class RemoveAllCounters(val target: TargetSpec) : Effect
    /** Mana abilities: "Add {G}", "Add one mana of any color". Doesn't use the stack (605.3b). */
    data class AddMana(val text: String) : Effect
    /** Effects the engine understands well enough to narrate with rule citations but doesn't track state for (libraries, hands). */
    data class Narrated(val text: String, val rules: List<String>) : Effect
    /** "Destroy all [filter]" / "Exile all …" / "~ deals N damage to each [filter]". */
    data class ForAll(val filter: ObjFilter, val action: String, val amount: Int = 0, val noRegen: Boolean = false) : Effect
    /** "You may pay [cost]. If you do, [effect]." / "You may [do X]. If you do, [effect]." */
    data class IfYouDo(val choice: Effect, val then: Effect, val cost: String?) : Effect
    /** Attach the source (Aura on resolution, Equipment via equip) to the target (301.5, 303.4). */
    data class Attach(val target: TargetSpec) : Effect
    /** "~ gains flying until end of turn". */
    data class GainKeywordsSelf(val keywords: Set<String>) : Effect
    /** "Choose one —" with bulleted modes (700.2). */
    data class Modal(val count: String, val modes: List<Effect>, val modeTexts: List<String>) : Effect
    /** One-shot effects that create a prevention shield until end of turn (615.7, 615.8): "Prevent the next 3 damage that would be dealt to any target this turn", "Prevent all combat damage that would be dealt this turn". */
    data class CreateShield(val replacement: Replacement.PreventDamage, val target: TargetSpec?) : Effect
    /** "Regenerate target creature" (701.19a). target null = self. */
    data class Regenerate(val target: TargetSpec?) : Effect
    /** "You may …" / "Its controller may …": [who] decides. */
    data class May(val effect: Effect, val who: Who = Who.YOU) : Effect
    data class UnlessPays(val effect: Effect, val payer: Who, val cost: String) : Effect
    data class Seq(val effects: List<Effect>) : Effect
    data class Unparsed(val text: String) : Effect

    /** Every target specification this effect (recursively) needs, in order. */
    fun targets(): List<TargetSpec> = when (this) {
        is Damage -> listOf(target); is Counter -> listOf(target); is Destroy -> listOf(target); is Exile -> listOf(target); is Blink -> listOf(target); is RedirectToSelf -> listOf(target)
        is Tap -> listOf(target); is Untap -> listOf(target); is Pump -> listOf(target); is GainKeywords -> listOf(target)
        is PutCounters -> listOfNotNull(target); is RemoveAllCounters -> listOf(target); is PutOnBottom -> listOf(target); is Attach -> listOf(target); is CreateShield -> listOfNotNull(target); is Regenerate -> listOfNotNull(target); is GainControl -> listOf(target); is Bounce -> listOfNotNull(target); is NarratedTargeted -> listOf(target); is GainLifeEqualToPower -> emptyList(); is CreateToken -> emptyList(); is SacrificeEach -> emptyList(); is SacrificeSource -> emptyList(); is Mill -> emptyList(); is GainLifePerSpellThisTurn -> emptyList(); is WinIfDevotionCoversLibrary -> emptyList(); is CopySpell -> listOf(target); is PreventCombatToAndBy -> listOf(target); is WinIfCastBefore -> emptyList(); is SacrificeThatMany -> emptyList(); is PutFromHand -> emptyList(); is DamagePlayer -> emptyList(); is LoseLifeThatMuch -> emptyList(); is PumpAllCount -> emptyList(); is ShuffleIntoLibrary -> listOf(target); is PumpCausing -> emptyList(); is Proliferate -> emptyList(); is ForAllTargeted -> listOf(target)
        is May -> effect.targets(); is UnlessPays -> effect.targets(); is Seq -> effects.flatMap { it.targets() }.distinct()   // "It gets…" refers back to the same target
        is IfYouDo -> choice.targets() + then.targets()
        is Repeat -> body.targets()
        is LoseLifeUnlessSacOrDiscard -> emptyList()
        is Modal -> emptyList()   // mode targets are chosen with the mode (700.2c); handled when a mode is picked
        is Draw, is GainLife, is LoseLife, is Unparsed, is PumpSelf, is PumpAll, is SetBasePtAll, is AddMana, is GainLifeLostThisWay, is GainLifeEqualToToughness, is Narrated, is ForAll, is GainKeywordsSelf -> emptyList()
    }

    fun hasUnparsed(): Boolean = when (this) {
        is Unparsed -> true; is May -> effect.hasUnparsed(); is UnlessPays -> effect.hasUnparsed(); is Seq -> effects.any { it.hasUnparsed() }
        is IfYouDo -> choice.hasUnparsed() || then.hasUnparsed()
        is Repeat -> body.hasUnparsed()
        is Modal -> modes.any { it.hasUnparsed() }
        else -> false
    }
}

/** Continuous effects from static abilities (604), applied in the layer system (613). */
/** A condition on a static ability: "as long as you control a Swamp", "as long as it's your turn". */
sealed interface Condition {
    data class ControlsMatching(val filter: ObjFilter, val atLeast: Int = 1) : Condition
    data object YourTurn : Condition
    data object NotYourTurn : Condition
    data class Unknown(val text: String) : Condition
}

/** How a characteristic-defining ability computes a number (604.3, 613.4a). */
sealed interface CountExpr {
    data class Permanents(val filter: ObjFilter) : CountExpr
    /** Tarmogoyf: "the number of card types among cards in all graveyards". */
    data object CardTypesInGraveyards : CountExpr
    data class Unknown(val text: String) : CountExpr
}

sealed interface StaticEffect {
    /** Layer 7c: "[filter] get +N/+N". `self` = "~ gets"; `condition` = "as long as …". */
    data class PtModify(val filter: ObjFilter, val power: Int, val toughness: Int, val self: Boolean = false, val condition: Condition? = null) : StaticEffect
    /** Layer 7a: "~'s power and toughness are each equal to the number of …" (604.3). */
    data class PtCda(val power: CountExpr?, val toughness: CountExpr?, val plus: Int = 0, val toughnessPlus: Int? = null) : StaticEffect
    /** Layer 6: "[filter] have [keywords]". */
    data class KeywordGrant(val filter: ObjFilter, val keywords: Set<String>) : StaticEffect
    /** "~ enters tapped" (614.1c replacement on entering). */
    /** "~ enters tapped" / "~ enters tapped unless [condition]". */
    data class EntersTapped(val unless: Condition? = null) : StaticEffect
    /** "~ enters with N +1/+1 counters on it" (614.1c). count null = X. */
    data class EntersWithCounters(val kind: String, val count: Int?) : StaticEffect
    /** "~ can't block" / "~ can't attack" / "~ can't be countered" / "~ can't be blocked". */
    /** "You have hexproof" (Leyline of Sanctity): the controller can't be targeted by opponents (702.11c). */
    data object PlayerHexproof : StaticEffect
    /** "You can't lose the game and your opponents can't win the game" (Platinum Angel). */
    data object CantLose : StaticEffect
    /** "Nonbasic lands are Mountains" (Blood Moon): a type-changing effect, layer 4 (613.1d, 305.7). */
    data object NonbasicLandsAreMountains : StaticEffect
    /** "You control enchanted creature" (Mind Control): a control-changing static, layer 2. */
    data object ControlEnchanted : StaticEffect
    /** Panharmonicon: artifacts and creatures entering make your triggered abilities trigger an additional time. */
    data object ExtraEtbTrigger : StaticEffect
    /** Propaganda / Ghostly Prison: "Creatures can't attack you unless their controller pays [cost] for each creature …". */
    data class AttackTax(val cost: String) : StaticEffect
    /** "Creatures entering the battlefield (or dying) don't cause abilities to trigger." (Torpor Orb, Hushbringer) */
    data class NoEtbTriggers(val alsoDies: Boolean) : StaticEffect
    /** "~ can't attack" / "~ can't be blocked by [filter]" (`by` restricts which blockers the rule applies to). */
    data class Cant(val what: String, val by: ObjFilter? = null, val applies: ObjFilter? = null, val powerAboveHand: Boolean = false) : StaticEffect
    /** Teferi, Time Raveler: "Each opponent can cast spells only any time they could cast a sorcery." */
    data object OpponentsSorcerySpeed : StaticEffect
    /** Stony Silence, Linvala: "Activated abilities of [filter] can't be activated." ([mana] restricts it to mana abilities, as Damping Sphere-style text does not). */
    data class CantActivate(val filter: ObjFilter, val manaOnly: Boolean = false, val opponentsOnly: Boolean = false) : StaticEffect
    /** Grand Abolisher: "During your turn, your opponents can't cast spells or activate abilities of artifacts, creatures, or enchantments." */
    data object OpponentsLockedOnYourTurn : StaticEffect
    /** Serra Avenger: "You can't cast this spell during your first, second, or third turns of the game." */
    data class CantCastBeforeTurn(val turn: Int) : StaticEffect
    /** Thalia: "Noncreature spells cost {1} more to cast." (a tax on spells matching the filter; `yours` limits it to the controller's / opponents' spells) */
    data class CostTax(val filter: ObjFilter, val amount: Int, val whose: Who? = null) : StaticEffect
    /** Cost modifiers and additional costs: narrated when the spell is cast (601.2b, 601.2f). */
    data class CostText(val text: String) : StaticEffect
    /** "~ attacks each combat if able." (508.1d) */
    data object MustAttack : StaticEffect
    /** Recognised static text the engine cites but has no game model for (level-up stats, "look at the top card any time", …). */
    data class Note(val text: String, val rules: List<String>) : StaticEffect
    /** A continuous replacement or prevention effect from a static ability (614, 615). */
    data class Replace(val replacement: Replacement) : StaticEffect
}

/** Replacement and prevention effects (614, 615). */
sealed interface Replacement {
    /** Prevent [amount] (null = all) damage that would be dealt to things matching [to] (or the player [toPlayer]), optionally only combat damage / only from sources matching [from]. */
    data class PreventDamage(val amount: Int?, val to: ObjFilter?, val toPlayer: Who?, val combatOnly: Boolean, val from: ObjFilter?, val fromSelf: Boolean = false) : Replacement
    /** "If [filter] would die, [instead] instead" / "would be put into a graveyard from anywhere". instead: exile | hand | library_bottom | library_top */
    data class GraveyardReplacement(val filter: ObjFilter, val self: Boolean, val instead: String, val fromAnywhere: Boolean) : Replacement
    /** "If a source (you control) would deal damage …, it deals double that damage instead." */
    data class DamageMultiplier(val factor: Int, val sourceControl: Who?) : Replacement
    /** "If you would gain life, you gain twice that much life instead." */
    data class LifeGainMultiplier(val factor: Int, val anyPlayer: Boolean = false) : Replacement
    /** "If an effect would place one or more counters on a permanent you control, it places twice that many instead." (Doubling Season) */
    data class CounterMultiplier(val factor: Int, val anyPlayer: Boolean = false) : Replacement
    /** "If an effect would create one or more tokens under your control, it creates twice that many instead." */
    data class TokenMultiplier(val factor: Int) : Replacement
    /** Regeneration shield: the next time it would be destroyed this turn (701.19a). */
    data object Regenerate : Replacement
}

sealed interface Ability { val text: String }
data class TriggeredAbility(val trigger: Trigger, val effect: Effect, override val text: String) : Ability
data class ActivatedAbility(val cost: String, val effect: Effect, override val text: String, val restriction: String? = null) : Ability {
    /** Loyalty abilities have a +N / −N / 0 cost (606.2). */
    val loyaltyCost: Int? get() = Regex("""^([+\u2212-]?\d+)$""").matchEntire(cost.trim())?.groupValues?.get(1)?.replace('\u2212', '-')?.toIntOrNull()
}
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
    /** Auras: what this can enchant (702.5a); the Aura spell targets accordingly (303.4a). */
    val enchant: ObjFilter? = null,
    /** Changeling: every creature type (702.73a). */
    val changeling: Boolean = false,
    /** Printed loyalty for planeswalkers (306.5a). */
    val loyalty: Int? = null,
) {
    val isPlaneswalker get() = "Planeswalker" in types
    val isAura get() = "Aura" in subtypes
    val isEquipment get() = "Equipment" in subtypes
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
