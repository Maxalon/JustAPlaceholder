package mtg.judge.nl

import mtg.judge.carddb.Names
import java.sql.Connection

/**
 * In-memory index of every card name (full and face) for longest-match detection in free text.
 * Tokens are the words of the normalized name; a name is found when a run of input words matches.
 */
class NameIndex private constructor(private val byNorm: Map<String, Entry>, val maxWords: Int) {
    data class Entry(val display: String, val oracleId: String, val isCard: Boolean, val kind: String, val typeLine: String = "") {
        /** An instant or sorcery: it lives in hand or on the stack, never on the battlefield. */
        val isSpellOnly: Boolean get() = (typeLine.contains("Instant") || typeLine.contains("Sorcery")) && !typeLine.contains("Land")
    }

    fun lookup(norm: String): Entry? = byNorm[norm]
    val size: Int get() = byNorm.size

    /** Every card-name occurrence in [words] (already normalized tokens), longest match first, non-overlapping. */
    fun findAll(words: List<String>): List<Found> {
        val found = mutableListOf<Found>()
        var i = 0
        while (i < words.size) {
            var hit: Found? = null
            for (len in minOf(maxWords, words.size - i) downTo 1) {
                val span = words.subList(i, i + len)
                val key = span.joinToString(" ")
                val sing = singularize(span)
                // Exact name, then nickname, then singularized forms; a nickname beats a token that happens to share the word ("bears" -> Grizzly Bears, not a Bear token).
                val e = byNorm[key] ?: alias(key) ?: sing?.let { alias(it) } ?: sing?.let { byNorm[it] }
                // A name made only of everyday words ("The End", "Turn Aside" no, "Wear // Tear" yes) is table talk unless it's a nickname.
                val ordinary = span.flatMap { it.split(' ') }.all { it in commonWords || it.removeSuffix("s") in commonWords } && key !in aliases && sing !in aliases
                if (e != null && !ordinary && (len > 1 || isSafeSingleWord(key, e) || key in aliases || sing in aliases)) { hit = Found(i, i + len, e); break }
            }
            if (hit != null) { found += hit; i = hit.end } else i++
        }
        return found
    }

    /** "stifles" -> "stifle", "counterspells" -> "counterspell": verbified or plural card names. */
    private fun singularize(span: List<String>): String? {
        val last = span.last()
        if (last.length > 3 && last.endsWith("s") && !last.endsWith("ss")) return (span.dropLast(1) + last.dropLast(1)).joinToString(" ")
        return null
    }

    /**
     * Single common English words are also card names ("Fog", "Counter", "Opt", "Growth", "Study", "Turn").
     * Only accept them when they're not everyday words the situation grammar uses.
     */
    private fun isSafeSingleWord(key: String, e: Entry): Boolean = key !in stopWords && (key.length <= 3 || key.removeSuffix("s") !in stopWords) && e.isCard

    /** Table-talk nicknames. The value is the real card name (normalized). */
    private fun alias(key: String): Entry? = aliases[key]?.let { byNorm[it] }

    data class Found(val start: Int, val end: Int, val entry: Entry)

    companion object {
        /** Common nicknames -> normalized card names. Grow this by hand; it is not data from any card source. */
        val aliases: Map<String, String> = mapOf(
            "bolt" to "lightning bolt", "bears" to "grizzly bears", "swords" to "swords to plowshares", "path" to "path to exile",
            "rhystic" to "rhystic study", "tithe" to "smothering tithe", "sol" to "sol ring", "wrath" to "wrath of god", "damnation" to "damnation",
            "cyc rift" to "cyclonic rift", "rift" to "cyclonic rift", "tutor" to "demonic tutor", "demonic" to "demonic tutor", "vamp tutor" to "vampiric tutor",
            "mana crypt" to "mana crypt", "crypt" to "mana crypt", "vault" to "mana vault", "top" to "sensei s divining top", "sdt" to "sensei s divining top",
            "farewell" to "farewell", "teferi s protection" to "teferi s protection", "tefprot" to "teferi s protection",
            "fow" to "force of will", "force" to "force of will", "fon" to "force of negation", "mana drain" to "mana drain", "drain" to "mana drain",
            "counterspell" to "counterspell", "negate" to "negate", "swan song" to "swan song", "arcane denial" to "arcane denial",
            "ur dragon" to "the ur dragon", "dockside" to "dockside extortionist", "thoracle" to "thassa s oracle", "thassa s oracle" to "thassa s oracle",
            "consult" to "demonic consultation", "pact" to "demonic pact", "tim" to "prodigal sorcerer", "sad robot" to "solemn simulacrum", "solemn" to "solemn simulacrum",
            "esper sentinel" to "esper sentinel", "sentinel" to "esper sentinel", "mystic remora" to "mystic remora", "remora" to "mystic remora",
            "fierce guardianship" to "fierce guardianship", "deflecting swat" to "deflecting swat", "swat" to "deflecting swat",
        )

        /** Everyday words: a card name made only of these is not read as a card ("The End", "Wear", "Attacking"). */
        private val commonWords = setOf("the", "a", "an", "of", "end", "start", "beginning", "turn", "step", "phase", "time", "game", "play", "attacking", "blocking", "wear", "tear", "begin",
            "my", "your", "their", "our", "it", "its", "this", "that", "and", "or", "not", "no", "yes", "in", "on", "at", "to", "for", "with", "from", "by", "as", "is", "are", "was", "be",
            "one", "two", "three", "first", "second", "last", "next", "new", "old", "big", "small", "up", "down", "out", "off", "over", "under", "back", "again", "now", "then", "here", "there",
            "life", "death", "damage", "counter", "target", "attack", "block", "draw", "hand", "deck", "library", "graveyard", "exile", "battlefield", "stack", "response", "trigger", "ability", "poison", "commander", "cards", "card",
            "they", "them", "he", "she", "we", "you", "i", "me", "re", "ve", "ll", "m", "s", "d", "t", "don", "doesn", "can", "won", "isn", "aren")
        private val stopWords = setOf(
            "counter", "target", "turn", "attack", "block", "cast", "play", "draw", "damage", "life", "control",
            "creature", "spell", "ability", "trigger", "stack", "response", "resolve", "resolves", "tap", "untap", "exile", "destroy", "sacrifice",
            "discard", "hand", "library", "graveyard", "battlefield", "token", "copy", "end", "step", "upkeep", "combat", "main", "phase", "pay",
            "mana", "land", "player", "opponent", "me", "my", "i", "you", "they", "it", "the", "a", "an",
            "then", "and", "or", "with", "on", "at", "to", "in", "of", "from", "is", "are", "was", "has", "have", "had", "do", "does", "did", "what", "happens",
            "who", "which", "when", "if", "that", "this", "their", "its", "his", "her", "him", "them", "kill", "dies", "die", "gets", "get", "becomes", "put",
            "one", "two", "three", "four", "five", "first", "second", "last", "next", "now", "still", "also", "just", "only", "again", "before", "after",
            "everything", "all", "nothing", "everyone", "nobody", "blockers", "attackers", "response", "responses", "counters", "loyalty", "marked", "regeneration", "regenerate", "shield", "flash", "sacrifice", "sac", "attacking", "blocking", "wear", "tear", "begin", "start", "time", "enchanted", "equipped", "poison", "unblocked", "alone", "x", "give", "gives", "grant", "elves", "goblins", "zombies", "tokens", "creatures",
        )

        fun load(conn: Connection): NameIndex {
            val map = HashMap<String, Entry>(80_000)
            var maxWords = 1
            conn.createStatement().executeQuery(
                """SELECT n.name_norm, n.display, n.oracle_id, n.kind, c.layout, c.type_line FROM card_names n JOIN cards c ON c.oracle_id = n.oracle_id"""
            ).use { rs ->
                while (rs.next()) {
                    val norm = rs.getString(1); val kind = rs.getString(4)
                    val isCard = rs.getString(5) !in mtg.judge.carddb.ingest.ScryfallIngest.nonCardLayouts
                    val e = Entry(rs.getString(2), rs.getString(3), isCard, kind, rs.getString(6) ?: "")
                    val prev = map[norm]
                    // Prefer real cards over tokens, full names over face names, on collisions.
                    if (prev == null || (!prev.isCard && isCard) || (prev.kind != "full" && kind == "full" && prev.isCard == isCard)) map[norm] = e
                    maxWords = maxOf(maxWords, norm.count { it == ' ' } + 1)
                }
            }
            return NameIndex(map, minOf(maxWords, 12))
        }

        fun tokenize(text: String): List<String> = Names.normalize(text).split(' ').filter { it.isNotEmpty() }
    }
}
