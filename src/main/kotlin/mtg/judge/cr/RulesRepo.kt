package mtg.judge.cr

import kotlinx.serialization.json.Json
import mtg.judge.carddb.CardRepo
import mtg.judge.carddb.Names
import java.sql.Connection

data class RuleRow(val number: String, val parent: String?, val kind: String, val title: String?, val text: String, val examples: List<String>)
data class GlossaryRow(val term: String, val definition: String)

/** Read-only access to the Comprehensive Rules tables. */
class RulesRepo(private val conn: Connection) {
    private fun read(rs: java.sql.ResultSet) = RuleRow(
        rs.getString("number"), rs.getString("parent"), rs.getString("kind"), rs.getString("title"), rs.getString("text"),
        Json.decodeFromString<List<String>>(rs.getString("examples")),
    )

    fun rule(number: String): RuleRow? = conn.prepareStatement("SELECT * FROM rules WHERE number = ?").use { ps ->
        ps.setString(1, number.trimEnd('.')); ps.executeQuery().use { if (it.next()) read(it) else null }
    }

    fun children(number: String): List<RuleRow> = conn.prepareStatement("SELECT * FROM rules WHERE parent = ? ORDER BY ordinal").use { ps ->
        ps.setString(1, number); ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs else null }.map { read(it) }.toList() }
    }

    /** Rules this rule points at ("see rule …") and rules that point at it. */
    fun referencesFrom(number: String): List<String> = conn.prepareStatement("SELECT to_number FROM rule_refs WHERE from_number = ?").use { ps ->
        ps.setString(1, number); ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
    }
    fun referencesTo(number: String): List<String> = conn.prepareStatement("SELECT from_number FROM rule_refs WHERE to_number = ? ORDER BY from_number").use { ps ->
        ps.setString(1, number); ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
    }

    /** Exact glossary lookup, tolerant of singular/plural ("state-based action" finds "State-Based Actions"). */
    fun glossary(term: String): GlossaryRow? {
        val norm = Names.normalize(term)
        val variants = listOf(norm, "${norm}s", "${norm}es", norm.removeSuffix("s"), norm.removeSuffix("es"), norm.replace(Regex("ies$"), "y"), norm.replace(Regex("y$"), "ies")).distinct()
        val sql = "SELECT term, definition FROM glossary WHERE term_norm IN (" + variants.joinToString(",") { "?" } + ") ORDER BY term_norm = ? DESC LIMIT 1"
        return conn.prepareStatement(sql).use { ps ->
            variants.forEachIndexed { i, v -> ps.setString(i + 1, v) }
            ps.setString(variants.size + 1, norm)
            ps.executeQuery().use { if (it.next()) GlossaryRow(it.getString(1), it.getString(2)) else null }
        }
    }

    /** Keyword abilities (702.x) and keyword actions (701.x) by name, e.g. "trample" -> 702.19. */
    fun keywordRule(keyword: String): RuleRow? = conn.prepareStatement(
        "SELECT * FROM rules WHERE kind = 'rule' AND (parent = '702' OR parent = '701' OR parent = '703') AND lower(text) = ? ORDER BY parent DESC LIMIT 1"
    ).use { ps ->
        ps.setString(1, keyword.trim().lowercase()); ps.executeQuery().use { if (it.next()) read(it) else null }
    }

    fun search(query: String, limit: Int = 15): List<RuleRow> = conn.prepareStatement(
        "SELECT r.* FROM rules_fts f JOIN rules r ON r.rowid = f.rowid WHERE rules_fts MATCH ? ORDER BY rank LIMIT ?"
    ).use { ps ->
        ps.setString(1, CardRepo.ftsQuote(query)); ps.setInt(2, limit)
        runCatching { ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs else null }.map { read(it) }.toList() } }.getOrDefault(emptyList())
    }

    fun searchGlossary(query: String, limit: Int = 10): List<GlossaryRow> = conn.prepareStatement(
        "SELECT g.term, g.definition FROM glossary_fts f JOIN glossary g ON g.rowid = f.rowid WHERE glossary_fts MATCH ? ORDER BY rank LIMIT ?"
    ).use { ps ->
        ps.setString(1, CardRepo.ftsQuote(query)); ps.setInt(2, limit)
        runCatching { ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs else null }.map { GlossaryRow(it.getString(1), it.getString(2)) }.toList() } }.getOrDefault(emptyList())
    }
}
