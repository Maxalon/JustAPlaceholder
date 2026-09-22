package mtg.judge.nl

import mtg.judge.carddb.Names
import java.sql.Connection

/**
 * In-memory index of every card name (full and face) for longest-match detection in free text.
 * Tokens are the words of the normalized name; a name is found when a run of input words matches.
 */
class NameIndex private constructor(private val byNorm: Map<String, Entry>, val maxWords: Int, private val heads: Map<String, List<Entry>> = emptyMap(),
                                    /** "saproling" -> "1/1": the size most printings of that creature token come with. */
                                    val tokenSizes: Map<String, String> = emptyMap()) {
    data class Entry(val display: String, val oracleId: String, val isCard: Boolean, val kind: String, val typeLine: String = "",
                     /** Other cards this word could have meant ("Atraxa": Praetors' Voice or Grand Unifier). */
                     val alternatives: List<String> = emptyList(),
                     /** Printed size, where the card has one: a token named by its type carries the size it comes with. */
                     val power: String? = null, val toughness: String? = null) {
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
                val e0 = byNorm[key] ?: alias(key) ?: sing?.let { alias(it) } ?: sing?.let { byNorm[it] }
                    ?: (if (len >= 2) byNorm["the $key"] ?: sing?.let { byNorm["the $it"] } else null)
                // A first name shared by several legendary creatures ("Sheoldred", "Atraxa"): the front face of a transforming card
                // that happens to carry it ("Sheoldred // The True Scriptures") doesn't win over them.
                val e = if (len == 1 && key !in aliases && (e0 == null || e0.kind != "full" || !e0.isCard) && heads[key] != null) heads.getValue(key).let { hs -> hs.first().copy(alternatives = hs.drop(1).map { it.display } + listOfNotNull(e0?.display)) } else e0
                // A name made only of everyday words ("The End", "Turn Aside" no, "Wear // Tear" yes) is table talk unless it's a nickname.
                // The singularized form counts too: "exiled" reaches the card Exile through "exile", and "exile" is
                // an everyday word here, so "it is exiled" is table talk, not a card being cast.
                val ordinary = (span.flatMap { it.split(' ') }.all { it in commonWords || it.removeSuffix("s") in commonWords } ||
                    sing?.split(' ')?.all { it in commonWords || it.removeSuffix("s") in commonWords } == true) && key !in aliases && sing !in aliases
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
        // "bolted", "pathed", "wrathed": a card name used as a past-tense verb.
        if (last.length > 4 && last.endsWith("ed")) { val stem = last.dropLast(2); val stemD = last.dropLast(1); return listOf(stem, stemD).firstOrNull { st -> val k = (span.dropLast(1) + st).joinToString(" "); byNorm.containsKey(k) || aliases.containsKey(k) }?.let { st -> (span.dropLast(1) + st).joinToString(" ") } }
        return null
    }

    /**
     * Single common English words are also card names ("Fog", "Counter", "Opt", "Growth", "Study", "Turn").
     * Only accept them when they're not everyday words the situation grammar uses.
     */
    private fun isSafeSingleWord(key: String, e: Entry): Boolean = (key !in stopWords && (key.length <= 3 || key.removeSuffix("s") !in stopWords) && e.isCard) || key in heads

    /** Table-talk nicknames. The value is the real card name (normalized). */
    private fun alias(key: String): Entry? = aliases[key]?.let { byNorm[it] }

    data class Found(val start: Int, val end: Int, val entry: Entry)

    companion object {
        /** Common nicknames -> normalized card names. Grow this by hand; it is not data from any card source. */
        val aliases: Map<String, String> = mapOf(
            "bolt" to "lightning bolt", "bears" to "grizzly bears", "swords" to "swords to plowshares", "path" to "path to exile",
            "rhystic" to "rhystic study", "tithe" to "smothering tithe", "sol" to "sol ring", "wrath" to "wrath of god", "damnation" to "damnation",
            "cyc rift" to "cyclonic rift", "rift" to "cyclonic rift", "tutor" to "demonic tutor", "demonic" to "demonic tutor", "vamp tutor" to "vampiric tutor",
            "mana crypt" to "mana crypt", "crypt" to "mana crypt", "vault" to "mana vault", "goyf" to "tarmogoyf", "sdt" to "sensei s divining top", "divining top" to "sensei s divining top",
            "farewell" to "farewell", "teferi s protection" to "teferi s protection", "tefprot" to "teferi s protection",
            // "drain" on its own is the verb for what Blood Artist and Exsanguinate do far more often than it is
            // the counterspell, and read as the card it put a Mana Drain on a battlefield nobody said it was on.
            "fow" to "force of will", "force" to "force of will", "fon" to "force of negation", "mana drain" to "mana drain",
            "counterspell" to "counterspell", "negate" to "negate", "swan song" to "swan song", "arcane denial" to "arcane denial",
            "ur dragon" to "the ur dragon", "dockside" to "dockside extortionist", "thoracle" to "thassa s oracle", "thassa s oracle" to "thassa s oracle",
            "consult" to "demonic consultation", "pact" to "demonic pact", "tim" to "prodigal sorcerer", "sad robot" to "solemn simulacrum", "solemn" to "solemn simulacrum",
            "esper sentinel" to "esper sentinel", "sentinel" to "esper sentinel", "mystic remora" to "mystic remora", "remora" to "mystic remora",
            "fierce guardianship" to "fierce guardianship", "deflecting swat" to "deflecting swat", "swat" to "deflecting swat",
        )

        /** Everyday words: a card name made only of these is not read as a card ("The End", "Wear", "Attacking"). */
        private val commonWords = setOf("the", "a", "an", "where", "why", "when", "which", "how", "who", "whom", "of", "thing", "things", "end", "start", "beginning", "turn", "step", "phase", "time", "game", "play", "attacking", "blocking", "wear", "tear", "begin", "hit", "run", "swing", "bolt", "away", "far", "right", "left", "return", "never", "blue", "red", "green", "white", "black", "colorless", "deal", "deals", "damage", "take", "takes", "gain", "gains", "lose", "loses", "die", "dies", "survive", "trigger", "resolve", "work", "happen", "count", "still", "get", "gets", "win", "wins", "keep", "come", "back", "go", "goes", "stay", "stays", "does", "do", "did", "will", "would", "can", "could", "should",
            // Game verbs that are also card names: "I blink my Solemn Simulacrum", "they flicker it", "I bounce their Bears".
            "blink", "blinks", "flicker", "flickers", "bounce", "bounces", "pump", "pumps", "sac", "sacs", "tap", "taps", "untap", "untaps", "block", "blocks", "attack", "attacks",
            // Keyword actions and game verbs that are also card names: "it fights their creature", "it is regenerated".
            "fight", "fights", "regenerate", "regenerates", "destroy", "destroys", "sacrifice", "sacrifices", "scry", "mill", "mills", "mulligan", "discard", "discards", "reveal", "reveals",
            "shuffle", "shuffles", "search", "searches", "proliferate", "surveil", "goad", "goads", "amass", "investigate", "populate", "connive", "transform", "transforms", "equip", "equips", "attach",
            "my", "your", "their", "our", "it", "its", "this", "that", "and", "or", "not", "no", "yes", "in", "on", "at", "to", "for", "with", "from", "by", "as", "is", "are", "was", "be",
            "one", "two", "three", "first", "second", "last", "next", "new", "old", "big", "small", "up", "down", "out", "off", "over", "under", "back", "again", "now", "then", "here", "there",
            "life", "death", "damage", "counter", "target", "attack", "block", "draw", "hand", "deck", "library", "graveyard", "exile", "battlefield", "stack", "response", "trigger", "ability", "poison", "commander", "cards", "card",
            "they", "them", "he", "she", "we", "you", "i", "me", "re", "ve", "ll", "m", "s", "d", "t", "don", "doesn", "can", "won", "isn", "aren")
        private val stopWords = setOf(
            "counter", "target", "turn", "attack", "block", "cast", "play", "draw", "damage", "life", "control", "survive", "survives", "dead", "alive", "die", "dies", "grow", "resolve", "experience", "energy", "storm", "sacrifice", "sacrificed", "top", "bottom", "overload", "overloaded", "kick", "kicked", "evoke", "convoke", "cycle", "flashback", "recast", "replay",
            "creature", "spell", "ability", "trigger", "stack", "response", "resolve", "resolves", "tap", "untap", "exile", "destroy", "sacrifice",
            "discard", "hand", "library", "graveyard", "battlefield", "token", "copy", "end", "step", "upkeep", "combat", "main", "phase", "pay",
            "mana", "land", "player", "opponent", "me", "my", "i", "you", "they", "it", "the", "a", "an",
            "then", "and", "or", "with", "on", "at", "to", "in", "of", "from", "is", "are", "was", "has", "have", "had", "do", "does", "did", "what", "happens",
            "who", "which", "when", "if", "that", "this", "their", "its", "his", "her", "him", "them", "kill", "dies", "die", "gets", "get", "becomes", "put",
            "one", "two", "three", "four", "five", "first", "second", "last", "next", "now", "still", "also", "just", "only", "again", "before", "after",
            // Mechanic words a player types meaning the mechanic, never the legend whose name starts with it.
            "monstrosity", "monstrous", "ninjutsu", "bushido", "landfall", "prowess", "cascade", "proliferate", "populate", "investigate", "adapt", "amass", "explore", "surveil", "scry", "morph", "megamorph", "mutate", "escape", "embalm", "eternalize", "delve", "madness", "bestow", "rebound",
            "leave", "leaves", "left", "enter", "enters", "entered", "return", "returns", "bounce", "bounces",
            "lifelink", "vigilance", "fear", "persist", "landfall", "provoke", "intimidate",
            "everything", "all", "nothing", "everyone", "nobody", "blockers", "attackers", "response", "responses", "counters", "loyalty", "marked", "regeneration", "regenerate", "shield", "flash", "sacrifice", "sac", "attacking", "blocking", "wear", "tear", "begin", "start", "time", "enchanted", "equipped", "poison", "unblocked", "alone", "x", "give", "gives", "grant", "elves", "goblins", "zombies", "tokens", "creatures",
        )

        fun load(conn: Connection): NameIndex {
            val map = HashMap<String, Entry>(80_000)
            var maxWords = 1
            conn.createStatement().executeQuery(
                """SELECT n.name_norm, n.display, n.oracle_id, n.kind, c.layout, c.type_line, c.power, c.toughness FROM card_names n JOIN cards c ON c.oracle_id = n.oracle_id"""
            ).use { rs ->
                while (rs.next()) {
                    val norm = rs.getString(1); val kind = rs.getString(4)
                    // Alchemy rebalances are named "A-Blood Artist", which normalizes to "a blood artist" — the
                    // same words as "a Blood Artist". They are Arena-only and never what the article means, and
                    // indexed they took the whole phrase: the Blood Artist on the battlefield was the rebalanced
                    // card, whose text is not the one the asker had in mind.
                    if (rs.getString(2).startsWith("A-")) continue
                    val isCard = rs.getString(5) !in mtg.judge.carddb.ingest.ScryfallIngest.nonCardLayouts
                    val e = Entry(rs.getString(2), rs.getString(3), isCard, kind, rs.getString(6) ?: "", power = rs.getString(7), toughness = rs.getString(8))
                    val prev = map[norm]
                    // Prefer real cards over tokens, full names over face names, on collisions.
                    if (prev == null || (!prev.isCard && isCard) || (prev.kind != "full" && kind == "full" && prev.isCard == isCard)) map[norm] = e
                    maxWords = maxOf(maxWords, norm.count { it == ' ' } + 1)
                }
            }
            // Legendary creatures are called by their first name: "Atraxa", "Sheoldred", "Kaalia". Ambiguous first names keep the alternatives.
            val heads = HashMap<String, MutableList<Entry>>()
            for (e in map.values) {
                if (e.kind != "full" || !e.isCard || !e.typeLine.contains("Legendary")) continue
                val head = if (e.display.contains(",")) Names.normalize(e.display.substringBefore(",")) else Regex("""^([A-Z][\w'-]+) (?:of|the)\b""").find(e.display)?.groupValues?.get(1)?.let { Names.normalize(it) } ?: continue
                if (head.contains(' ') || head.length < 4 || head in commonWords || head in stopWords || map[head]?.let { it.kind == "full" && it.isCard } == true) continue
                heads.getOrPut(head) { mutableListOf() }.let { l -> if (l.none { it.oracleId == e.oracleId }) l += e }
            }
            // A creature token type is printed in several sizes ("Soldier" is 1/1 far more often than 2/2). The
            // size most printings use is the one to assume when the asker doesn't give one.
            val sizeCounts = HashMap<String, HashMap<String, Int>>()
            conn.createStatement().executeQuery(
                """SELECT name_norm, power, toughness FROM cards WHERE type_line LIKE 'Token%' AND type_line LIKE '%Creature%' AND power GLOB '[0-9]*' AND toughness GLOB '[0-9]*'"""
            ).use { rs -> while (rs.next()) sizeCounts.getOrPut(rs.getString(1)) { HashMap() }.merge("${rs.getString(2)}/${rs.getString(3)}", 1, Int::plus) }
            val tokenSizes = sizeCounts.mapNotNull { (name, counts) ->
                val total = counts.values.sum()
                counts.maxByOrNull { it.value }?.takeIf { it.value * 2 >= total }?.let { name to it.key }
            }.toMap()
            return NameIndex(map, minOf(maxWords, 12), heads.mapValues { (_, l) -> l.sortedBy { it.display.lowercase() } }, tokenSizes)
        }

        fun tokenize(text: String): List<String> = Names.normalize(text).split(' ').filter { it.isNotEmpty() }
    }
}
