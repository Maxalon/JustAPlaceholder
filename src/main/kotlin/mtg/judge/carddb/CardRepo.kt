package mtg.judge.carddb

import mtg.judge.carddb.ingest.ScryfallIngest
import java.sql.Connection

data class Card(
    val oracleId: String, val name: String, val layout: String, val manaCost: String?, val manaValue: Double,
    val typeLine: String, val oracleText: String, val colors: String, val colorIdentity: String,
    val power: String?, val toughness: String?, val loyalty: String?, val defense: String?,
    val keywords: String, val legalities: String, val reserved: Boolean, val gameChanger: Boolean, val faces: String?,
) { val isCard get() = layout !in ScryfallIngest.nonCardLayouts }

data class Ruling(val publishedAt: String, val source: String, val comment: String)

data class Printing(
    val scryfallId: String, val oracleId: String, val name: String, val setCode: String, val setName: String,
    val collectorNumber: String, val releasedAt: String?, val rarity: String?, val lang: String,
    val printedText: String?, val originalText: String?, val originalType: String?,
)

data class OracleVersion(val text: String, val firstSeen: String, val lastSeen: String, val current: Boolean)

/** How a piece of text was matched to a card. */
data class Resolution(val query: String, val matches: List<Match>) {
    data class Match(val card: Card, val how: How, val matchedName: String, val score: Double)
    enum class How { EXACT, FACE, ALIAS, FUZZY }
    val best: Match? get() = matches.firstOrNull()
    val ambiguous: Boolean get() = matches.size > 1 && matches[0].how == matches[1].how && matches[0].score == matches[1].score
}

/** Read-only access to cards, printings, rulings and Oracle history. */
class CardRepo(private val conn: Connection) {
    private fun readCard(rs: java.sql.ResultSet) = Card(
        rs.getString("oracle_id"), rs.getString("name"), rs.getString("layout"), rs.getString("mana_cost"), rs.getDouble("mana_value"),
        rs.getString("type_line"), rs.getString("oracle_text"), rs.getString("colors"), rs.getString("color_identity"),
        rs.getString("power"), rs.getString("toughness"), rs.getString("loyalty"), rs.getString("defense"),
        rs.getString("keywords"), rs.getString("legalities"), rs.getInt("reserved") == 1, rs.getInt("game_changer") == 1, rs.getString("faces"),
    )

    fun byOracleId(id: String): Card? = conn.prepareStatement("SELECT * FROM cards WHERE oracle_id = ?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { if (it.next()) readCard(it) else null }
    }

    /** Resolve free text to cards: exact normalized name, then face name, then alias, then trigram fuzzy match. */
    fun resolve(text: String, limit: Int = 5): Resolution {
        val norm = Names.normalize(text)
        if (norm.isEmpty()) return Resolution(text, emptyList())
        val exact = conn.prepareStatement(
            """SELECT c.*, n.kind, n.display FROM card_names n JOIN cards c ON c.oracle_id = n.oracle_id
               WHERE n.name_norm = ? ORDER BY CASE n.kind WHEN 'full' THEN 0 WHEN 'face' THEN 1 ELSE 2 END"""
        ).use { ps ->
            ps.setString(1, norm)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs else null }.map {
                    val how = when (it.getString("kind")) { "full" -> Resolution.How.EXACT; "face" -> Resolution.How.FACE; else -> Resolution.How.ALIAS }
                    Resolution.Match(readCard(it), how, it.getString("display"), 1.0)
                }.toList()
            }
        }
        if (exact.isNotEmpty()) {
            // Real cards outrank tokens and emblems that share a name (e.g. "Food", "Treasure").
            return Resolution(text, exact.sortedWith(compareBy({ it.how }, { !it.card.isCard })).take(limit))
        }
        // Fuzzy: names sharing the most trigrams with the query, then ranked by edit distance.
        // (A quoted phrase against the trigram tokenizer is a substring match, so the query is
        // broken into its individual trigrams and OR-ed; bm25 rank favours names sharing many.)
        val trigrams = (0..norm.length - 3).map { norm.substring(it, it + 3) }.filter { it.isNotBlank() }.distinct()
        if (trigrams.isEmpty()) return Resolution(text, emptyList())
        val trigramQuery = trigrams.joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }
        val candidates = conn.prepareStatement(
            "SELECT n.name_norm, n.oracle_id, n.kind, n.display FROM card_names_trigram t JOIN card_names n ON n.rowid = t.rowid WHERE card_names_trigram MATCH ? ORDER BY rank LIMIT 60"
        ).use { ps ->
            ps.setString(1, trigramQuery)
            runCatching { ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs else null }.map { arrayOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }.toList() } }
                .getOrDefault(emptyList())
        }
        val scored = candidates.map { (cn, oid, kind, display) ->
            val d = damerauLevenshtein(norm, cn)
            val score = 1.0 - d.toDouble() / maxOf(norm.length, cn.length)
            Triple(oid, display, score to kind)
        }.filter { it.third.first >= 0.6 }.sortedByDescending { it.third.first }.distinctBy { it.first }.take(limit)
        return Resolution(text, scored.mapNotNull { (oid, display, sk) -> byOracleId(oid)?.let { Resolution.Match(it, Resolution.How.FUZZY, display, sk.first) } })
    }

    fun rulings(oracleId: String): List<Ruling> = conn.prepareStatement("SELECT published_at, source, comment FROM rulings WHERE oracle_id = ? ORDER BY published_at").use { ps ->
        ps.setString(1, oracleId); ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs else null }.map { Ruling(it.getString(1), it.getString(2), it.getString(3)) }.toList() }
    }

    fun printings(oracleId: String): List<Printing> = conn.prepareStatement("SELECT * FROM printings WHERE oracle_id = ? ORDER BY released_at, set_code, collector_number").use { ps ->
        ps.setString(1, oracleId); ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs else null }.map { readPrinting(it) }.toList() }
    }

    fun printing(setCode: String, collectorNumber: String? = null, oracleId: String? = null): Printing? {
        val sql = StringBuilder("SELECT * FROM printings WHERE set_code = ?")
        if (collectorNumber != null) sql.append(" AND collector_number = ?")
        if (oracleId != null) sql.append(" AND oracle_id = ?")
        sql.append(" ORDER BY lang = 'en' DESC LIMIT 1")
        return conn.prepareStatement(sql.toString()).use { ps ->
            var i = 1
            ps.setString(i++, setCode.lowercase())
            if (collectorNumber != null) ps.setString(i++, collectorNumber)
            if (oracleId != null) ps.setString(i, oracleId)
            ps.executeQuery().use { if (it.next()) readPrinting(it) else null }
        }
    }

    private fun readPrinting(rs: java.sql.ResultSet) = Printing(
        rs.getString("scryfall_id"), rs.getString("oracle_id"), rs.getString("name"), rs.getString("set_code"), rs.getString("set_name"),
        rs.getString("collector_number"), rs.getString("released_at"), rs.getString("rarity"), rs.getString("lang"),
        rs.getString("printed_text"), rs.getString("original_text"), rs.getString("original_type"),
    )

    fun oracleHistory(oracleId: String): List<OracleVersion> = conn.prepareStatement("SELECT oracle_text, first_seen, last_seen, current FROM oracle_history WHERE oracle_id = ? ORDER BY current, last_seen").use { ps ->
        ps.setString(1, oracleId); ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs else null }.map { OracleVersion(it.getString(1), it.getString(2), it.getString(3), it.getInt(4) == 1) }.toList() }
    }

    /** Full-text search over names and Oracle text. */
    fun search(query: String, limit: Int = 20): List<Card> = conn.prepareStatement(
        "SELECT c.* FROM cards_fts f JOIN cards c ON c.rowid = f.rowid WHERE cards_fts MATCH ? ORDER BY rank LIMIT ?"
    ).use { ps ->
        ps.setString(1, ftsQuote(query)); ps.setInt(2, limit)
        runCatching { ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs else null }.map { readCard(it) }.toList() } }.getOrDefault(emptyList())
    }

    fun meta(): Map<String, String> = conn.createStatement().executeQuery("SELECT key, value FROM meta").use { rs ->
        generateSequence { if (rs.next()) rs else null }.associate { it.getString(1) to it.getString(2) }
    }

    companion object {
        /** Quote each word so FTS5 treats punctuation and reserved words literally. */
        fun ftsQuote(q: String): String = q.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"" }

        fun damerauLevenshtein(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
            return d[a.length][b.length]
        }
    }
}
