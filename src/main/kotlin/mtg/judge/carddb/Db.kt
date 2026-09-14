package mtg.judge.carddb

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Opens SQLite connections with the pragmas this project relies on. */
object Db {
    fun open(path: Path, readOnly: Boolean = false): Connection {
        val url = if (readOnly) "jdbc:sqlite:file:${path.toAbsolutePath()}?mode=ro" else "jdbc:sqlite:${path.toAbsolutePath()}"
        val conn = DriverManager.getConnection(url)
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            if (!readOnly) {
                st.execute("PRAGMA journal_mode = WAL")
                st.execute("PRAGMA synchronous = NORMAL")
            }
        }
        return conn
    }

    /** Tuned for a one-shot bulk build: no durability guarantees until the final commit. */
    fun openForBuild(path: Path): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode = OFF")
            st.execute("PRAGMA synchronous = OFF")
            st.execute("PRAGMA temp_store = MEMORY")
            st.execute("PRAGMA cache_size = -200000")
        }
        return conn
    }
}
