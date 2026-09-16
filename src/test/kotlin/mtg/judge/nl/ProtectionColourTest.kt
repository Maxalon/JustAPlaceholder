package mtg.judge.nl

import mtg.judge.carddb.Db
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Protection from black" names a colour. With Black Knight on the board the colour was matched to that card's
 * short name instead, and the creature ended up with "protection from Black Knight" — protection from nothing.
 * Nothing said so: the sentence parsed, the answer read as complete, and the creature simply died in combat.
 * Needs the full database (MTG_JUDGE_DB); skipped otherwise.
 */
class ProtectionColourTest {
    private fun parser(dbPath: String) = Db.open(File(dbPath).toPath(), readOnly = true).use { SituationParser(NameIndex.load(it)) }

    @Test
    fun `the colour after protection from stays a colour`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val p = parser(dbPath)
        val wrong = mutableListOf<String>()
        // Each colour, with a card whose name begins with that colour also on the board.
        val cases = listOf(
            "black" to "Black Knight", "white" to "White Knight", "blue" to "Blue Elemental Blast",
            "red" to "Red Elemental Blast", "green" to "Green Mana Battery",
        )
        for ((colour, card) in cases) {
            val text = "My Grizzly Bears has protection from $colour. They control $card."
            val kws = p.parse(text).situation.objects.firstOrNull { it.card.name == "Grizzly Bears" }?.keywords ?: emptyList()
            if (kws != listOf("protection from $colour")) wrong += "\"$text\" gave Grizzly Bears $kws"
        }
        assertTrue(wrong.isEmpty(), "Colours read as cards:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `protection can be stated with the permanent named last`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val p = parser(dbPath)
        val parsed = p.parse("My Grizzly Bears blocks their Black Knight. I have protection from black on my Grizzly Bears.")
        assertEquals(emptyList(), parsed.unread, "the trailing form went unread")
        assertEquals(
            listOf("protection from black"),
            parsed.situation.objects.first { it.card.name == "Grizzly Bears" }.keywords,
        )
    }

    /**
     * A creature described by keywords rather than by name: the words in front of "creature" are the keywords it
     * has. Before this the adjective slot took one word from a colour/size whitelist, so "a 2/2 indestructible
     * creature" matched nothing at all and the whole clause was dropped.
     */
    @Test
    fun `keywords in front of the kind describe the creature`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val p = parser(dbPath)
        val wrong = mutableListOf<String>()
        val cases = listOf(
            "I have a 2/2 indestructible creature." to "a 2/2 creature with indestructible",
            "I have a 3/3 flying creature." to "a 3/3 creature with flying",
            "I control a 4/4 deathtouch creature." to "a 4/4 creature with deathtouch",
            "I have a 3/3 flying deathtouch creature." to "a 3/3 creature with flying, deathtouch",
            "I have a 2/2 first strike creature." to "a 2/2 creature with first strike",
        )
        for ((text, want) in cases) {
            val got = p.parse(text).situation.objects.map { it.card.name }
            if (got != listOf(want)) wrong += "\"$text\" gave $got, not [$want]"
        }
        assertTrue(wrong.isEmpty(), "Keyword adjectives:\n" + wrong.joinToString("\n"))
    }
}
