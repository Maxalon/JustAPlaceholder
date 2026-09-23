package mtg.judge.carddb.ingest

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mtg.judge.carddb.Names
import mtg.judge.cr.ComprehensiveRules
import mtg.judge.cr.CrParser
import java.sql.Connection

/** Loads a parsed Comprehensive Rules document into the rules, rule_refs and glossary tables. */
object CrIngest {
    fun ingest(conn: Connection, cr: ComprehensiveRules): Int {
        conn.createStatement().use { it.execute("DELETE FROM rules"); it.execute("DELETE FROM rule_refs"); it.execute("DELETE FROM glossary") }
        val ins = conn.prepareStatement(
            "INSERT INTO rules(number, parent, chapter, section, kind, title, text, examples, ordinal) VALUES (?,?,?,?,?,?,?,?,?)"
        )
        val ref = conn.prepareStatement("INSERT OR IGNORE INTO rule_refs(from_number, to_number) VALUES (?,?)")
        val known = cr.rules.map { it.number }.toSet()
        for (r in cr.rules) {
            ins.apply {
                setString(1, r.number); setString(2, r.parent); setInt(3, r.chapter); setInt(4, r.section)
                setString(5, r.kind.name.lowercase()); setString(6, r.title); setString(7, r.text)
                setString(8, Json.encodeToString(r.examples)); setInt(9, r.ordinal); addBatch()
            }
            for (to in CrParser.references(r.text + "\n" + r.examples.joinToString("\n"))) {
                if (to in known && to != r.number) { ref.setString(1, r.number); ref.setString(2, to); ref.addBatch() }
            }
        }
        ins.executeBatch(); ref.executeBatch(); ins.close(); ref.close()
        val g = conn.prepareStatement("INSERT OR REPLACE INTO glossary(term, term_norm, definition) VALUES (?,?,?)")
        for (e in cr.glossary) { g.setString(1, e.term); g.setString(2, Names.normalize(e.term)); g.setString(3, e.definition); g.addBatch() }
        g.executeBatch(); g.close()
        return cr.rules.size
    }
}
