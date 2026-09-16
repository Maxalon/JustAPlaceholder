package mtg.judge.nl

import mtg.judge.carddb.Db
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Short statements about one permanent, said the way players say them. Sweeping these systematically found a
 * dozen that went unread — and each one made the sentence after it answer the wrong question, because the board
 * it described was not the board the asker meant. One of them (stating summoning sickness) exposed an engine bug
 * that had been there all along.
 *
 * The leading possessive matters: the clause reader takes "my" or "their" as the actor and removes it before any
 * rule sees the text, so a rule written for "my Bears dies" has to match "Bears dies" too. Both forms are swept.
 * Needs the full database (MTG_JUDGE_DB); skipped otherwise.
 */
class ClauseShapesTest {
    private val statements = listOf(
        "attacks", "blocks", "dies", "is destroyed", "is exiled", "is tapped", "is sacrificed",
        "gets +2/+2", "gains flying", "gets a +1/+1 counter", "has summoning sickness", "regenerates",
        "untaps", "is returned to their hand", "is shuffled into their library",
        "enters the battlefield", "leaves the battlefield", "has two +1/+1 counters", "has protection from black",
    )

    @Test
    fun `a short statement about a permanent is read, whichever side it is on`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val parser = Db.open(File(dbPath).toPath(), readOnly = true).use { SituationParser(NameIndex.load(it)) }
        val unread = mutableListOf<String>()
        for (statement in statements) for (side in listOf("My", "Their")) {
            // An attack to block, so "blocks" has something to block.
            val text = "I control Blood Artist. They attack with Hill Giant. $side Serra Angel $statement."
            val parsed = parser.parse(text)
            if (parsed.unread.isNotEmpty()) unread += "\"$side Serra Angel $statement\" -> ${parsed.unread}"
        }
        assertTrue(unread.isEmpty(), "Statements about a permanent that went unread:\n" + unread.joinToString("\n"))
    }
}
