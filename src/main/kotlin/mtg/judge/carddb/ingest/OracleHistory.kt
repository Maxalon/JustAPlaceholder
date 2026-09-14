package mtg.judge.carddb.ingest

import mtg.judge.carddb.Db
import java.nio.file.Path
import java.sql.Connection

/**
 * Tracks every Oracle text a card has had. Neither Scryfall nor MTGJSON publishes a change log,
 * so history accumulates build by build: rows from the previous database are carried over, the
 * current text is marked current, and a text seen for the first time gets today's date.
 * Without a previous database the current text is recorded as seen since 'before-tracking'.
 */
object OracleHistory {
    fun update(conn: Connection, previous: Path?, buildDate: String): Int {
        conn.createStatement().use { it.execute("DELETE FROM oracle_history") }
        val insert = conn.prepareStatement("INSERT OR REPLACE INTO oracle_history(oracle_id, oracle_text, first_seen, last_seen, current) VALUES (?,?,?,?,?)")
        var changes = 0

        // Carry over what the previous build knew.
        val prevRows = HashMap<String, MutableList<Array<String>>>()   // oracle_id -> [text, first, last]
        if (previous != null) {
            Db.open(previous, readOnly = true).use { prev ->
                prev.createStatement().executeQuery("SELECT oracle_id, oracle_text, first_seen, last_seen FROM oracle_history").use { rs ->
                    while (rs.next()) prevRows.getOrPut(rs.getString(1)) { mutableListOf() } += arrayOf(rs.getString(2), rs.getString(3), rs.getString(4))
                }
            }
        }

        conn.createStatement().executeQuery("SELECT oracle_id, oracle_text FROM cards").use { rs ->
            var batch = 0
            while (rs.next()) {
                val oid = rs.getString(1); val text = rs.getString(2)
                val old = prevRows.remove(oid)
                var matched = false
                old?.forEach { (t, first, last) ->
                    val isCurrent = t == text
                    if (isCurrent) matched = true
                    insert.setString(1, oid); insert.setString(2, t); insert.setString(3, first)
                    insert.setString(4, if (isCurrent) buildDate else last); insert.setInt(5, if (isCurrent) 1 else 0); insert.addBatch()
                }
                if (!matched) {
                    val first = if (old == null && previous == null) "before-tracking" else buildDate
                    if (old != null) changes++
                    insert.setString(1, oid); insert.setString(2, text); insert.setString(3, first)
                    insert.setString(4, buildDate); insert.setInt(5, 1); insert.addBatch()
                }
                if (++batch % 5000 == 0) insert.executeBatch()
            }
        }
        // Cards that vanished from the source keep their history rows, none current.
        for ((oid, rows) in prevRows) for ((t, first, last) in rows) {
            insert.setString(1, oid); insert.setString(2, t); insert.setString(3, first); insert.setString(4, last); insert.setInt(5, 0); insert.addBatch()
        }
        insert.executeBatch(); insert.close()
        return changes
    }
}
