package mtg.judge.nl

import mtg.judge.carddb.Db
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Game verbs that are also card names. "It is exiled" once reached the card Exile through the -ed stemmer and the
 * answer reported Exile resolving; "it fights their creature" found a card called Fight. A card the asker never
 * named turning up in the answer is worse than a clause going unread, because the answer looks complete.
 * Needs the full database (MTG_JUDGE_DB); skipped otherwise.
 */
class VerbsAreNotCardsTest {
    @Test
    fun `a game verb is not read as the card of that name`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val parser = Db.open(File(dbPath).toPath(), readOnly = true).use { SituationParser(NameIndex.load(it)) }
        val phrases = listOf(
            "it is exiled", "it is destroyed", "it is countered", "it is sacrificed", "it is regenerated",
            "it fights their creature", "I proliferate", "they goad it", "I surveil 2", "I mill three cards",
            "they scry 2", "I shuffle my library", "they mulligan", "I search my library",
        )
        val wrong = mutableListOf<String>()
        for (p in phrases) {
            val text = "I control Grizzly Bears and $p."
            val named = parser.debugMark(text).firstOrNull()?.substringAfter("[")?.substringBefore("]") ?: ""
            val cards = Regex("""c\d+=([^,\]]+)""").findAll(named).map { it.groupValues[1].trim() }.toList()
            if (cards != listOf("Grizzly Bears")) wrong += "\"$p\" found $cards"
        }
        assertTrue(wrong.isEmpty(), "Game verbs read as card names:\n" + wrong.joinToString("\n"))
    }
}
