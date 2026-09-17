package mtg.judge.carddb.ingest

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import java.nio.file.Path
import java.sql.Connection

/**
 * Streams MTGJSON AllPrintings and copies each printing's `originalText` / `originalType`
 * (the text as it appeared on that physical card) onto our printings, matched by Scryfall id.
 * The file is far too large to load as a tree, so this walks it token by token.
 */
object MtgjsonOriginalText {
    data class Result(val printingsSeen: Int, val withOriginalText: Int, val updated: Int)

    fun apply(conn: Connection, dir: Path, name: String = "mtgjson-AllPrintings.json.xz"): Result {
        val update = conn.prepareStatement("UPDATE printings SET original_text = ?, original_type = ? WHERE scryfall_id = ?")
        var seen = 0; var withText = 0; var updated = 0; var batch = 0
        Sources.open(dir, name).use { input ->
            val p = JsonFactory().createParser(input)
            expect(p.nextToken(), JsonToken.START_OBJECT)
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                if (p.currentName != "data") { p.nextToken(); p.skipChildren(); continue }
                expect(p.nextToken(), JsonToken.START_OBJECT)          // data: { SET: {...}, ... }
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    expect(p.nextToken(), JsonToken.START_OBJECT)      // one set
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        if (p.currentName != "cards") { p.nextToken(); p.skipChildren(); continue }
                        expect(p.nextToken(), JsonToken.START_ARRAY)
                        while (p.nextToken() == JsonToken.START_OBJECT) {
                            seen++
                            val c = readCard(p)
                            if (c.originalText != null && c.scryfallId != null) {
                                withText++
                                update.setString(1, c.originalText); update.setString(2, c.originalType); update.setString(3, c.scryfallId)
                                update.addBatch()
                                if (++batch % 5000 == 0) updated += update.executeBatch().sum()
                            }
                        }
                    }
                }
            }
        }
        updated += update.executeBatch().sum()
        update.close()
        return Result(seen, withText, updated)
    }

    private class Card(var scryfallId: String? = null, var originalText: String? = null, var originalType: String? = null)

    /** Parser is positioned on START_OBJECT of a card; returns after its END_OBJECT. */
    private fun readCard(p: JsonParser): Card {
        val c = Card()
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            when (p.currentName) {
                "originalText" -> c.originalText = p.nextTextValue()
                "originalType" -> c.originalType = p.nextTextValue()
                "identifiers" -> {
                    expect(p.nextToken(), JsonToken.START_OBJECT)
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        if (p.currentName == "scryfallId") c.scryfallId = p.nextTextValue() else { p.nextToken(); p.skipChildren() }
                    }
                }
                else -> { p.nextToken(); p.skipChildren() }
            }
        }
        return c
    }

    private fun expect(actual: JsonToken?, wanted: JsonToken) {
        check(actual == wanted) { "Unexpected MTGJSON structure: wanted $wanted, got $actual" }
    }
}
