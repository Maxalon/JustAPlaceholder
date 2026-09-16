package mtg.judge.nl

import mtg.judge.carddb.Db
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "My" is the asker's and "their" is the asker's opponent's, whichever player is doing the thing. A rule that
 * resolves the possessive against the actor instead of the speaker passes the sentence it was written for and
 * fails its mirror image, which is how three of these went unnoticed: each phrasing is checked from both sides.
 * Needs the full database (MTG_JUDGE_DB); skipped otherwise.
 */
class PossessiveSymmetryTest {
    private fun parser(dbPath: String) = Db.open(File(dbPath).toPath(), readOnly = true).use { SituationParser(NameIndex.load(it)) }

    private fun ownerOf(p: SituationParser, text: String, card: String): String? =
        p.parse(text).situation.objects.firstOrNull { it.card.name?.contains(card, true) == true }?.controller

    @Test
    fun `a possessive names the asker's side whichever player acts`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val p = parser(dbPath)
        // Each pair is the same sentence with the roles swapped; the marked creature must follow the possessive.
        val pairs = listOf(
            Triple("I kill my Grizzly Bears with Murder.", "My opponent kills their Grizzly Bears with Murder.", "Grizzly Bears"),
            Triple("I kill their Grizzly Bears with Murder.", "My opponent kills my Grizzly Bears with Murder.", "Grizzly Bears"),
            Triple("I blink my Solemn Simulacrum with Restoration Angel.", "They blink their Solemn Simulacrum with Restoration Angel.", "Solemn Simulacrum"),
            Triple("I pump my Grizzly Bears with Giant Growth.", "They pump their Grizzly Bears with Giant Growth.", "Grizzly Bears"),
            Triple("I bounce my Grizzly Bears with Unsummon.", "They bounce their Grizzly Bears with Unsummon.", "Grizzly Bears"),
        )
        val wrong = mutableListOf<String>()
        for ((mine, theirs, card) in pairs) {
            val a = ownerOf(p, mine, card)
            val b = ownerOf(p, theirs, card)
            if (a == null || b == null) { wrong += "\"$mine\" / \"$theirs\": $card never reached the battlefield"; continue }
            // "my …" said by the asker is always "me"; the mirror sentence's "their …" is always the opponent.
            if (mine.contains(" my ")) { if (a != "me") wrong += "\"$mine\" put $card under $a" }
            else if (a != "opp") wrong += "\"$mine\" put $card under $a"
            if (theirs.contains(" their ")) { if (b != "opp") wrong += "\"$theirs\" put $card under $b" }
            else if (b != "me") wrong += "\"$theirs\" put $card under $b"
        }
        assertTrue(wrong.isEmpty(), "Possessives resolved against the actor rather than the asker:\n" + wrong.joinToString("\n"))
    }

    /**
     * With named players the possessive is a name, not a pronoun: "Alice kills Bob's Bears" puts the Bears under
     * Bob. This went unread entirely for a while — "kills" wasn't a word that made the capitalised word before it
     * a player — so the sentence reported nothing rather than reporting the wrong owner.
     */
    @Test
    fun `a named player's possessive names that player`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val p = parser(dbPath)
        val wrong = mutableListOf<String>()
        // text -> who must own the marked card.
        val cases = listOf(
            Triple("Alice kills Bob's Grizzly Bears with Murder.", "Grizzly Bears", "bob"),
            Triple("Bob kills Alice's Grizzly Bears with Murder.", "Grizzly Bears", "alice"),
            Triple("Alice bounces Bob's Grizzly Bears with Unsummon.", "Grizzly Bears", "bob"),
            Triple("Alice blinks her own Solemn Simulacrum with Restoration Angel.", "Solemn Simulacrum", "alice"),
            Triple("Bob pumps his own Grizzly Bears with Giant Growth.", "Grizzly Bears", "bob"),
        )
        for ((text, card, owner) in cases) {
            val got = ownerOf(p, text, card)
            if (got != owner) wrong += "\"$text\" put $card under ${got ?: "nobody (the sentence went unread)"}, not $owner"
        }
        assertTrue(wrong.isEmpty(), "Named players' possessives:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `the asker's own creature stays theirs when they act on it`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val p = parser(dbPath)
        assertEquals("me", ownerOf(p, "I sacrifice my Grizzly Bears to Ashnod's Altar.", "Grizzly Bears"))
        assertEquals("me", ownerOf(p, "I equip my Grizzly Bears with Bonesplitter.", "Grizzly Bears"))
    }
}
