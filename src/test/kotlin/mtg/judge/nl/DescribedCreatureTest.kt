package mtg.judge.nl

import mtg.judge.carddb.Db
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Creatures given by their size or their role rather than their name. Needs the full database (MTG_JUDGE_DB);
 * skipped otherwise.
 */
class DescribedCreatureTest {
    private fun parser(dbPath: String) = Db.open(File(dbPath).toPath(), readOnly = true).use { SituationParser(NameIndex.load(it)) }

    /**
     * "creature" starts with a "c", and so does a marked card placeholder. Testing for the placeholder with
     * startsWith meant "kills my creature with Doom Blade" looked up a card named "creature" and threw
     * NoSuchElementException out of the parser — the CLI printed a stack trace instead of an answer.
     */
    @Test
    fun `a removal spell aimed at a creature nobody named is read, not thrown`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val p = parser(dbPath)
        for (text in listOf(
            "They kill my creature with Doom Blade.",
            "They target my creature with Swords to Plowshares.",
            "I kill their creature with Murder.",
        )) {
            val parsed = p.parse(text)
            assertTrue(parsed.unread.isEmpty(), "\"$text\" went unread: ${parsed.unread}")
            assertTrue(parsed.situation.events.any { it.verb == "cast" }, "\"$text\" cast nothing")
        }
    }

    @Test
    fun `blocking with creatures given by size puts every one of them in the block`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val p = parser(dbPath)
        val parsed = p.parse("I block their 5/5 trampler with a 2/2 and a 1/1.")
        assertTrue(parsed.unread.isEmpty(), "went unread: ${parsed.unread}")
        assertEquals(2, parsed.situation.events.count { it.verb == "block" }, parsed.situation.events.toString())
        assertEquals(1, parsed.situation.events.count { it.verb == "attack" }, "the thing that was blocked is attacking")
    }
}
