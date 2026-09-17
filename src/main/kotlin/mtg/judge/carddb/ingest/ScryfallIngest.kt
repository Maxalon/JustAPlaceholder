package mtg.judge.carddb.ingest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mtg.judge.carddb.Names
import java.nio.file.Path
import java.sql.Connection

/** Loads Scryfall bulk files (oracle cards, default cards, rulings) into the database. */
object ScryfallIngest {
    /** Layouts that are not game objects a rules question can be about. */
    private val skipLayouts = setOf("art_series")

    /** Layouts that describe tokens, emblems and other non-card objects; kept, but ranked below real cards. */
    val nonCardLayouts = setOf("token", "double_faced_token", "emblem")

    private val prunedKeys = setOf(
        "image_uris", "purchase_uris", "related_uris", "prices", "uri", "scryfall_uri", "set_uri",
        "set_search_uri", "prints_search_uri", "rulings_uri", "scryfall_set_uri", "multiverse_ids", "mtgo_id",
        "mtgo_foil_id", "tcgplayer_id", "tcgplayer_etched_id", "cardmarket_id", "arena_id", "card_back_id",
        "artist_ids", "illustration_id", "image_status", "image_updated_at", "highres_image", "preview",
        "object", "all_parts",
    )

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonArray.strings(): String = joinToString("") { it.jsonPrimitive.content }

    private fun faces(card: JsonObject): List<JsonObject> = card.arr("card_faces")?.map { it.jsonObject } ?: emptyList()

    /** Top-level oracle_id, or the front face's for reversible cards. */
    fun oracleId(card: JsonObject): String? =
        card.str("oracle_id") ?: faces(card).firstNotNullOfOrNull { it.str("oracle_id") }

    /** Oracle text of the whole card: top-level, or faces joined with a separator line. */
    private fun oracleText(card: JsonObject): String =
        card.str("oracle_text") ?: faces(card).map { it.str("oracle_text") ?: "" }.joinToString("\n//\n")

    private fun printedText(card: JsonObject): String? =
        card.str("printed_text") ?: faces(card).mapNotNull { it.str("printed_text") }.takeIf { it.isNotEmpty() }?.joinToString("\n//\n")

    private fun prune(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> buildJsonObject {
            for ((k, v) in el) if (k !in prunedKeys) put(k, prune(v))
        }
        is JsonArray -> buildJsonArray { el.forEach { add(prune(it)) } }
        else -> el
    }

    private fun facesSummary(card: JsonObject): String? {
        val fs = faces(card)
        if (fs.isEmpty()) return null
        return buildJsonArray {
            for (f in fs) add(buildJsonObject {
                for (k in listOf("name", "mana_cost", "type_line", "oracle_text", "colors", "power", "toughness", "loyalty", "defense"))
                    f[k]?.let { put(k, it) }
            })
        }.toString()
    }

    fun ingestOracleCards(conn: Connection, dir: Path, name: String = "scryfall-oracle_cards.jsonl.gz"): Int {
        val insertCard = conn.prepareStatement(
            """INSERT OR REPLACE INTO cards(oracle_id, name, name_norm, layout, mana_cost, mana_value, type_line, oracle_text,
               colors, color_identity, power, toughness, loyalty, defense, keywords, legalities, reserved, game_changer,
               representative_printing, faces, json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        )
        val insertName = conn.prepareStatement("INSERT OR IGNORE INTO card_names(name_norm, oracle_id, kind, display) VALUES (?,?,?,?)")
        var n = 0
        Sources.forEachJsonLine(dir, name) { card ->
            val layout = card.str("layout") ?: "normal"
            if (layout in skipLayouts) return@forEachJsonLine
            val oid = oracleId(card) ?: return@forEachJsonLine
            val fullName = card.str("name") ?: return@forEachJsonLine
            insertCard.apply {
                setString(1, oid); setString(2, fullName); setString(3, Names.normalize(fullName)); setString(4, layout)
                setString(5, card.str("mana_cost") ?: faces(card).mapNotNull { it.str("mana_cost") }.filter { it.isNotEmpty() }.joinToString(" // ").ifEmpty { null })
                setDouble(6, card.num("cmc") ?: 0.0)
                setString(7, card.str("type_line") ?: faces(card).mapNotNull { it.str("type_line") }.joinToString(" // "))
                setString(8, oracleText(card))
                setString(9, card.arr("colors")?.strings() ?: faces(card).flatMap { it.arr("colors")?.map { c -> c.jsonPrimitive.content } ?: emptyList() }.distinct().joinToString(""))
                setString(10, card.arr("color_identity")?.strings() ?: "")
                setString(11, card.str("power")); setString(12, card.str("toughness")); setString(13, card.str("loyalty")); setString(14, card.str("defense"))
                setString(15, (card["keywords"] ?: JsonArray(emptyList())).toString())
                setString(16, (card["legalities"] ?: JsonObject(emptyMap())).toString())
                setInt(17, if (card.bool("reserved")) 1 else 0)
                setInt(18, if (card.bool("game_changer")) 1 else 0)
                setString(19, card.str("id"))
                setString(20, facesSummary(card))
                setString(21, prune(card).toString())
                addBatch()
            }
            insertName.apply { setString(1, Names.normalize(fullName)); setString(2, oid); setString(3, "full"); setString(4, fullName); addBatch() }
            for (face in faces(card)) {
                val fn = face.str("name") ?: continue
                if (fn == fullName) continue
                insertName.apply { setString(1, Names.normalize(fn)); setString(2, oid); setString(3, "face"); setString(4, fn); addBatch() }
            }
            if (++n % 5000 == 0) { insertCard.executeBatch(); insertName.executeBatch() }
        }
        insertCard.executeBatch(); insertName.executeBatch()
        insertCard.close(); insertName.close()
        return n
    }

    fun ingestPrintings(conn: Connection, dir: Path, name: String = "scryfall-default_cards.jsonl.gz"): Int {
        val ps = conn.prepareStatement(
            """INSERT OR REPLACE INTO printings(scryfall_id, oracle_id, name, set_code, set_name, collector_number, released_at,
               rarity, lang, printed_name, printed_text, printed_type_line, digital, promo) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        )
        var n = 0
        Sources.forEachJsonLine(dir, name) { card ->
            val layout = card.str("layout") ?: "normal"
            if (layout in skipLayouts) return@forEachJsonLine
            val oid = oracleId(card) ?: return@forEachJsonLine
            ps.apply {
                setString(1, card.str("id")); setString(2, oid); setString(3, card.str("name"))
                setString(4, card.str("set")); setString(5, card.str("set_name") ?: ""); setString(6, card.str("collector_number") ?: "")
                setString(7, card.str("released_at")); setString(8, card.str("rarity")); setString(9, card.str("lang") ?: "en")
                setString(10, card.str("printed_name") ?: faces(card).mapNotNull { it.str("printed_name") }.takeIf { it.isNotEmpty() }?.joinToString(" // "))
                setString(11, printedText(card))
                setString(12, card.str("printed_type_line"))
                setInt(13, if (card.bool("digital")) 1 else 0); setInt(14, if (card.bool("promo")) 1 else 0)
                addBatch()
            }
            if (++n % 5000 == 0) ps.executeBatch()
        }
        ps.executeBatch(); ps.close()
        return n
    }

    fun ingestRulings(conn: Connection, dir: Path, name: String = "scryfall-rulings.jsonl.gz"): Int {
        val ps = conn.prepareStatement("INSERT OR IGNORE INTO rulings(oracle_id, published_at, source, comment) VALUES (?,?,?,?)")
        var n = 0
        Sources.forEachJsonLine(dir, name) { r ->
            val oid = r.str("oracle_id") ?: return@forEachJsonLine
            ps.apply {
                setString(1, oid); setString(2, r.str("published_at") ?: ""); setString(3, r.str("source") ?: "")
                setString(4, r.str("comment") ?: ""); addBatch()
            }
            if (++n % 5000 == 0) ps.executeBatch()
        }
        ps.executeBatch(); ps.close()
        return n
    }

    @Suppress("unused")
    private fun JsonElement.isNull() = this is JsonNull
}
