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
data class PlayerSpec(val id: String, val name: String = id, val life: Int? = null)

@Serializable
data class TurnSpec(val activePlayer: String? = null, val phase: String? = null, val step: String? = null)

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
