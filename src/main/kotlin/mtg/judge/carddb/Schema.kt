package mtg.judge.carddb

import java.sql.Connection

/**
 * Database layout.
 *
 * cards        one row per Oracle identity (Scryfall oracle_id)
 * card_names   every name that resolves to a card: full names, face names, hand-kept aliases
 * printings    one row per physical printing, with the text as printed on that card
 * rulings      official rulings, keyed by oracle_id
 * oracle_history  every distinct Oracle text a card has had, with the build dates it was seen
 * rules        the Comprehensive Rules, one row per numbered rule or subrule
 * rule_refs    "see rule X" cross references between rules
 * glossary     the Comprehensive Rules glossary
 * meta         build provenance (source versions, rules effective date, build time)
 */
object Schema {
    val ddl: List<String> = listOf(
        """CREATE TABLE IF NOT EXISTS meta (
             key TEXT PRIMARY KEY,
             value TEXT NOT NULL
           )""",
        """CREATE TABLE IF NOT EXISTS cards (
             oracle_id TEXT PRIMARY KEY,
             name TEXT NOT NULL,
             name_norm TEXT NOT NULL,
             layout TEXT NOT NULL,
             mana_cost TEXT,
             mana_value REAL NOT NULL DEFAULT 0,
             type_line TEXT NOT NULL,
             oracle_text TEXT NOT NULL DEFAULT '',
             colors TEXT NOT NULL DEFAULT '',
             color_identity TEXT NOT NULL DEFAULT '',
             power TEXT, toughness TEXT, loyalty TEXT, defense TEXT,
             keywords TEXT NOT NULL DEFAULT '[]',
             legalities TEXT NOT NULL DEFAULT '{}',
             reserved INTEGER NOT NULL DEFAULT 0,
             game_changer INTEGER NOT NULL DEFAULT 0,
             representative_printing TEXT,
             faces TEXT,
             json TEXT NOT NULL
           )""",
        "CREATE INDEX IF NOT EXISTS cards_name_norm ON cards(name_norm)",
        """CREATE TABLE IF NOT EXISTS card_names (
             name_norm TEXT NOT NULL,
             oracle_id TEXT NOT NULL REFERENCES cards(oracle_id),
             kind TEXT NOT NULL,          -- full | face | alias
             display TEXT NOT NULL,
             PRIMARY KEY (name_norm, oracle_id, kind)
           )""",
        "CREATE INDEX IF NOT EXISTS card_names_norm ON card_names(name_norm)",
        """CREATE TABLE IF NOT EXISTS printings (
             scryfall_id TEXT PRIMARY KEY,
             oracle_id TEXT NOT NULL,
             name TEXT NOT NULL,
             set_code TEXT NOT NULL,
             set_name TEXT NOT NULL,
             collector_number TEXT NOT NULL,
             released_at TEXT,
             rarity TEXT,
             lang TEXT NOT NULL,
             printed_name TEXT,
             printed_text TEXT,
             printed_type_line TEXT,
             original_text TEXT,           -- text as first printed (MTGJSON originalText)
             original_type TEXT,
             digital INTEGER NOT NULL DEFAULT 0,
             promo INTEGER NOT NULL DEFAULT 0
           )""",
        "CREATE INDEX IF NOT EXISTS printings_oracle ON printings(oracle_id, released_at)",
        "CREATE INDEX IF NOT EXISTS printings_set ON printings(set_code, collector_number)",
        """CREATE TABLE IF NOT EXISTS rulings (
             oracle_id TEXT NOT NULL,
             published_at TEXT NOT NULL,
             source TEXT NOT NULL,
             comment TEXT NOT NULL,
             PRIMARY KEY (oracle_id, published_at, comment)
           )""",
        """CREATE TABLE IF NOT EXISTS oracle_history (
             oracle_id TEXT NOT NULL,
             oracle_text TEXT NOT NULL,
             first_seen TEXT NOT NULL,     -- build date this text was first observed, or 'before-tracking'
             last_seen TEXT NOT NULL,      -- build date this text was last observed
             current INTEGER NOT NULL,
             PRIMARY KEY (oracle_id, oracle_text)
           )""",
        """CREATE TABLE IF NOT EXISTS rules (
             number TEXT PRIMARY KEY,      -- e.g. 702.19b
             parent TEXT,                  -- 702.19 for 702.19b, 702 for 702.19, null for chapters
             chapter INTEGER NOT NULL,     -- 702
             section INTEGER NOT NULL,     -- 7
             kind TEXT NOT NULL,           -- section | chapter | rule | subrule
             title TEXT,                   -- for sections and chapters
             text TEXT NOT NULL,
             examples TEXT NOT NULL DEFAULT '[]',
             ordinal INTEGER NOT NULL      -- document order
           )""",
        "CREATE INDEX IF NOT EXISTS rules_parent ON rules(parent)",
        """CREATE TABLE IF NOT EXISTS rule_refs (
             from_number TEXT NOT NULL,
             to_number TEXT NOT NULL,
             PRIMARY KEY (from_number, to_number)
           )""",
        """CREATE TABLE IF NOT EXISTS glossary (
             term TEXT PRIMARY KEY,
             term_norm TEXT NOT NULL,
             definition TEXT NOT NULL
           )""",
        "CREATE INDEX IF NOT EXISTS glossary_norm ON glossary(term_norm)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS card_names_trigram USING fts5(name_norm, content='card_names', content_rowid='rowid', tokenize='trigram')",
        "CREATE VIRTUAL TABLE IF NOT EXISTS cards_fts USING fts5(name, oracle_text, type_line, content='cards', content_rowid='rowid', tokenize='unicode61 remove_diacritics 2')",
        "CREATE VIRTUAL TABLE IF NOT EXISTS rules_fts USING fts5(number, text, content='rules', content_rowid='rowid', tokenize='unicode61 remove_diacritics 2')",
        "CREATE VIRTUAL TABLE IF NOT EXISTS glossary_fts USING fts5(term, definition, content='glossary', content_rowid='rowid', tokenize='unicode61 remove_diacritics 2')",
    )

    fun create(conn: Connection) {
        conn.createStatement().use { st -> ddl.forEach { st.execute(it) } }
    }

    /** External-content FTS tables must be told to re-read their source tables after a bulk load. */
    fun rebuildFts(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute("INSERT INTO card_names_trigram(card_names_trigram) VALUES('rebuild')")
            st.execute("INSERT INTO cards_fts(cards_fts) VALUES('rebuild')")
            st.execute("INSERT INTO rules_fts(rules_fts) VALUES('rebuild')")
            st.execute("INSERT INTO glossary_fts(glossary_fts) VALUES('rebuild')")
        }
    }
}
