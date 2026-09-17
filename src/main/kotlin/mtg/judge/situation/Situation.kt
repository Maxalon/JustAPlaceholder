package mtg.judge.situation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The situation language (see docs/situation-language.md) as Kotlin types.
 * `null` on a boolean or string field means "unknown / not stated".
 */
@Serializable
data class Situation(
    val players: List<PlayerSpec> = listOf(PlayerSpec("me", "me"), PlayerSpec("opp", "opponent")),
    val turn: TurnSpec = TurnSpec(),
    val objects: List<ObjectSpec> = emptyList(),
    val stack: List<StackSpec> = emptyList(),
    val events: List<EventSpec> = emptyList(),
    val question: QuestionSpec = QuestionSpec(),
)

@Serializable
data class PlayerSpec(val id: String, val name: String = id, val life: Int? = null, val poison: Int? = null, val handSize: Int? = null, val librarySize: Int? = null,
                      /** Combat damage already taken from each commander this game, by object id (903.10a). */
                      val commanderDamage: Map<String, Int> = emptyMap(),
                      /** Mana available right now, when stated ("only has one Mountain untapped"). */
                      val mana: Int? = null,
                      /** Devotion to colours (W/U/B/R/G), when stated. */
                      val devotion: Map<String, Int> = emptyMap(),
                      /** Spells already cast this turn, when stated ("I have cast four spells this turn") — storm-style counts. */
                      val spellsThisTurn: Int? = null,
                      /** Cards in graveyard, when stated ("my graveyard has seven cards") — threshold and delirium. */
                      val graveyardSize: Int? = null)

@Serializable
data class TurnSpec(val activePlayer: String? = null, val phase: String? = null, val step: String? = null,
                    /** The game's turn number, when stated ("it's turn 3"). */
                    val number: Int? = null)

/** A card reference: `"Rhystic Study"` or `{ "name": "...", "oracleId": "..." }`. */
@Serializable(with = CardRefSerializer::class)
data class CardRef(val name: String? = null, val oracleId: String? = null) {
    override fun toString() = name ?: oracleId ?: "?"
}

object CardRefSerializer : KSerializer<CardRef> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CardRef")
    override fun deserialize(decoder: Decoder): CardRef {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when (el) {
            is JsonPrimitive -> CardRef(name = el.content)
            is JsonObject -> CardRef(el["name"]?.jsonPrimitive?.content, el["oracleId"]?.jsonPrimitive?.content)
            else -> throw IllegalArgumentException("card must be a name or an object")
        }
    }
    override fun serialize(encoder: Encoder, value: CardRef) {
        val je = encoder as JsonEncoder
        if (value.oracleId == null && value.name != null) je.encodeJsonElement(JsonPrimitive(value.name))
        else je.encodeJsonElement(buildJsonObject { value.name?.let { put("name", JsonPrimitive(it)) }; value.oracleId?.let { put("oracleId", JsonPrimitive(it)) } })
    }
}

@Serializable
data class ObjectSpec(
    val id: String,
    val card: CardRef,
    val zone: String = "battlefield",
    val controller: String = "me",
    val owner: String? = null,
    val tapped: Boolean? = false,
    val summoningSick: Boolean? = null,
    val counters: Map<String, Int> = emptyMap(),
    val damage: Int = 0,
    val token: Boolean = false,
    val attachedTo: String? = null,
    /** An until-end-of-turn modification already applied, e.g. "+3/+3". */
    val pump: String? = null,
    /** Keywords the permanent has been given ("hexproof Grizzly Bears"). */
    val keywords: List<String> = emptyList(),
    /** The player's commander (its combat damage to each player is tracked, 903.10a). */
    val commander: Boolean = false,
    /** Times this commander was already cast from the command zone (903.8). */
    val commanderCasts: Int = 0,
    /** A card name this permanent names ("Meddling Mage naming Lightning Bolt"). */
    val named: String? = null,
)

@Serializable
data class StackSpec(
    val id: String? = null,
    val kind: String = "spell",          // spell | activated | triggered
    val source: String? = null,          // object id (abilities) or omitted for a spell given by card
    val card: CardRef? = null,
    val controller: String = "me",
    val targets: List<String> = emptyList(),
    val abilityIndex: Int? = null,
)

@Serializable
data class EventSpec(
    val verb: String,
    val player: String? = null,
    val card: CardRef? = null,
    @SerialName("object") val obj: String? = null,
    val targets: List<String> = emptyList(),
    val abilityIndex: Int? = null,
    val to: String? = null,
    val amount: Int? = null,
    val source: String? = null,
    /** Chosen mode numbers (1-based) for modal spells (700.2). */
    val modes: List<Int> = emptyList(),
    /** Life the caster said they paid ("cast it paying 3 life"); paid only if the card's own cost doesn't. */
    val payLife: Int? = null,
)

@Serializable
data class QuestionSpec(val kind: String = "whatHappens", @SerialName("object") val obj: String? = null, val which: String? = null)

@Serializable
data class Answer(
    val outcome: List<String>,
    val trace: List<TraceLine>,
    val assumptions: List<String>,
    val clarifications: List<String>,
    val unsupported: List<String>,
    val citations: Map<String, String>,
    val understood: List<String>,
)

@Serializable
data class TraceLine(val text: String, val rules: List<String>)
