package mtg.judge.carddb.ingest

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mtg.judge.carddb.Db
import mtg.judge.carddb.Schema
import mtg.judge.cr.CrParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists

/**
 * Builds the whole database from a directory laid out like the `data` branch
 * (see .github/workflows/fetch-data.yml). Writes to a temp file and moves it into
 * place at the end, so a failed build never leaves a half-written database behind.
 */
object Build {
    data class Report(val cards: Int, val printings: Int, val rulings: Int, val rules: Int, val glossary: Int, val originalText: MtgjsonOriginalText.Result, val historyChanges: Int)

    fun run(dataDir: Path, out: Path, previous: Path? = null, log: (String) -> Unit = ::println): Report {
        val manifest: JsonObject? = if (Sources.exists(dataDir, "manifest.json")) Sources.json.parseToJsonElement(Sources.readText(dataDir, "manifest.json")).jsonObject else null
        val buildDate = manifest?.get("fetched_at")?.jsonPrimitive?.content?.take(10)
            ?: DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.now().atOffset(ZoneOffset.UTC))

        val tmp = out.resolveSibling(out.fileName.toString() + ".building")
        Files.deleteIfExists(tmp)
        val conn = Db.openForBuild(tmp)
        try {
            Schema.create(conn)
            conn.autoCommit = false

            log("Comprehensive Rules…")
            val cr = CrParser.parse(Sources.readText(dataDir, "MagicCompRules.txt"))
            val rules = CrIngest.ingest(conn, cr)
            log("  $rules rules, ${cr.glossary.size} glossary entries, effective ${cr.effectiveDate}")

            log("Scryfall oracle cards…")
            val cards = ScryfallIngest.ingestOracleCards(conn, dataDir)
            log("  $cards cards")
            log("Scryfall printings…")
            val printings = ScryfallIngest.ingestPrintings(conn, dataDir)
            log("  $printings printings")
            log("Scryfall rulings…")
            val rulings = ScryfallIngest.ingestRulings(conn, dataDir)
            log("  $rulings rulings")

            log("MTGJSON original printed text…")
            val ot = if (Sources.exists(dataDir, "mtgjson-AllPrintings.json.xz")) MtgjsonOriginalText.apply(conn, dataDir)
                     else MtgjsonOriginalText.Result(0, 0, 0)
            log("  ${ot.printingsSeen} printings seen, ${ot.withOriginalText} with original text, ${ot.updated} matched")

            log("Oracle text history…")
            val changes = OracleHistory.update(conn, previous?.takeIf { it.exists() }, buildDate)
            log("  $changes text changes recorded")

            log("Metadata and search indexes…")
            writeMeta(conn, manifest, cr.effectiveDate, buildDate)
            conn.commit()
            Schema.rebuildFts(conn)
            conn.commit()
            conn.autoCommit = true
            conn.createStatement().use { it.execute("VACUUM"); it.execute("ANALYZE") }
            conn.close()
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return Report(cards, printings, rulings, rules, cr.glossary.size, ot, changes)
        } catch (e: Exception) {
            runCatching { conn.close() }
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    private fun writeMeta(conn: Connection, manifest: JsonObject?, crEffective: String?, buildDate: String) {
        val ps = conn.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?,?)")
        fun put(k: String, v: String?) { if (v != null) { ps.setString(1, k); ps.setString(2, v); ps.addBatch() } }
        put("built_at", Instant.now().toString())
        put("build_date", buildDate)
        put("schema_version", "1")
        put("cr_effective", crEffective)
        manifest?.let { m ->
            put("data_fetched_at", m["fetched_at"]?.jsonPrimitive?.content)
            m["scryfall"]?.jsonArray?.forEach { e ->
                val o = e.jsonObject
                put("scryfall_${o["type"]?.jsonPrimitive?.content}_updated_at", o["updated_at"]?.jsonPrimitive?.content)
            }
            m["mtgjson"]?.jsonObject?.let { put("mtgjson_version", it["version"]?.jsonPrimitive?.content); put("mtgjson_date", it["date"]?.jsonPrimitive?.content) }
            m["comprehensive_rules"]?.jsonObject?.let { put("cr_source", it["source"]?.jsonPrimitive?.content) }
        }
        ps.executeBatch(); ps.close()
    }
}
