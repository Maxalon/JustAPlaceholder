package mtg.judge.nl

import mtg.judge.carddb.CardRepo
import mtg.judge.carddb.Db
import mtg.judge.cr.RulesRepo
import mtg.judge.situation.AnswerRenderer
import mtg.judge.situation.Judge
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One situation, many ways to say it. Sweeping a family of phrasings for a single action is how most of the
 * reading-layer defects were found: of 47 phrasings across casting, blocking, attacking and countering, 21 went
 * unread, and a dropped clause is worse than a refusal because the answer still looks complete — a dropped
 * removal spell leaves the creature alive, a dropped block lets the attacker through.
 *
 * Each family below is one situation written the ways a player would write it; every one has to reach the same
 * answer. Adding a phrasing here is the cheapest way to pin a new way of saying something.
 * Needs the full database (MTG_JUDGE_DB); skipped otherwise.
 */
class PhrasingFamiliesTest {
    /** [prefix] sets the board, [expect] must appear in the answer for every phrasing in [phrasings]. */
    private data class Family(val name: String, val prefix: String, val expect: String, val phrasings: List<String>)

    private val families = listOf(
        Family(
            "a removal spell, in every tense and mood", "I control Grizzly Bears. ",
            "Grizzly Bears: the battlefield → your graveyard",
            listOf(
                "They cast Doom Blade on my Grizzly Bears.", "They casted Doom Blade on my Grizzly Bears.",
                "They have cast Doom Blade on my Grizzly Bears.", "They will cast Doom Blade on my Grizzly Bears.",
                "They are casting Doom Blade on my Grizzly Bears.", "They just cast Doom Blade on my Grizzly Bears.",
                "Suppose they cast Doom Blade on my Grizzly Bears.", "Say they cast Doom Blade on my Grizzly Bears.",
                "What if they cast Doom Blade on my Grizzly Bears?", "They point Doom Blade at my Grizzly Bears.",
                "They use Doom Blade on my Grizzly Bears.", "Doom Blade targets my Grizzly Bears.",
                "Doom Blade is cast on my Grizzly Bears.", "They kill my Grizzly Bears with Doom Blade.",
                "They killed my Grizzly Bears with Doom Blade.", "They remove my Grizzly Bears with Doom Blade.",
                "They nuke my Grizzly Bears with Doom Blade.",
            ),
        ),
        Family(
            "a block", "They attack with Grizzly Bears. ",
            "Grizzly Bears: the battlefield → opponent's graveyard",
            listOf(
                "I block their Grizzly Bears with Hill Giant.", "I blocked their Grizzly Bears with Hill Giant.",
                "My Hill Giant blocks their Grizzly Bears.", "Their Grizzly Bears is blocked by my Hill Giant.",
                "Their Grizzly Bears gets blocked by my Hill Giant.", "I throw Hill Giant in front of their Grizzly Bears.",
                "I put Hill Giant in the way of their Grizzly Bears.", "I declare Hill Giant as a blocker on their Grizzly Bears.",
            ),
        ),
        Family(
            "an attack", "I control Grizzly Bears. ", "opponent takes 2 damage",
            listOf(
                "I attack with Grizzly Bears.", "I attacked with Grizzly Bears.", "My Grizzly Bears attacks.",
                "I swing with Grizzly Bears.", "I send Grizzly Bears at my opponent.", "I turn Grizzly Bears sideways.",
                "I declare Grizzly Bears as an attacker.", "I am attacking with Grizzly Bears.",
                "I'm attacking with Grizzly Bears.", "I alpha strike with Grizzly Bears.",
            ),
        ),
        Family(
            "a counterspell", "I cast Lightning Bolt at my opponent. ", "Lightning Bolt is countered",
            listOf(
                "They counter my Lightning Bolt with Counterspell.", "They countered my Lightning Bolt with Counterspell.",
                "They Counterspell my Lightning Bolt.", "My Lightning Bolt gets countered by Counterspell.",
                "My Lightning Bolt is countered by Counterspell.", "They counter it with Counterspell.",
                "Counterspell counters my Lightning Bolt.", "They fizzle my Lightning Bolt with Counterspell.",
            ),
        ),
        Family(
            "damage to a player", "", "opponent takes 3 damage",
            listOf(
                "I cast Lightning Bolt at my opponent.", "Lightning Bolt deals 3 damage to my opponent.",
                "I deal 3 damage to my opponent with Lightning Bolt.", "I hit my opponent for 3 with Lightning Bolt.",
                "My opponent takes 3 from Lightning Bolt.", "I burn my opponent for 3 with Lightning Bolt.",
                "I Bolt my opponent.", "I Bolt their face.", "I throw Lightning Bolt at their face.",
                "Lightning Bolt hits my opponent.", "My opponent is dealt 3 damage by Lightning Bolt.",
            ),
        ),
        Family(
            "a creature dying", "I control Blood Artist and Grizzly Bears. ", "opponent loses 1 life",
            listOf(
                "My Grizzly Bears dies.", "My Grizzly Bears died.", "My Grizzly Bears is destroyed.",
                "My Grizzly Bears goes to the graveyard.", "I lose my Grizzly Bears.", "My Grizzly Bears hits the bin.",
                "My Grizzly Bears is put into my graveyard.", "I sacrifice my Grizzly Bears.",
                "I sacrificed my Grizzly Bears.", "I throw my Grizzly Bears away.",
            ),
        ),
        Family(
            "a mana ability", "I control Llanowar Elves that has been out. ", "mana ability",
            listOf(
                "I activate Llanowar Elves.", "I activated Llanowar Elves.", "I tap Llanowar Elves for mana.",
                "I use Llanowar Elves.", "I use Llanowar Elves's ability.", "Llanowar Elves taps for mana.",
                "I turn on Llanowar Elves.", "I fire off Llanowar Elves's ability.",
            ),
        ),
        Family(
            "tapping a permanent", "I control Grizzly Bears. ", "Grizzly Bears is tapped",
            listOf(
                "I tap my Grizzly Bears. Is it tapped?", "I tapped my Grizzly Bears. Is it tapped?",
                "My Grizzly Bears is tapped. Is it tapped?", "My Grizzly Bears gets tapped. Is it tapped?",
                "They tap my Grizzly Bears down. Is it tapped?", "My Grizzly Bears becomes tapped. Is it tapped?",
            ),
        ),
        Family(
            "a draw an opponent takes", "I control Narset, Parter of Veils. ", "opponent draws",
            listOf(
                "My opponent draws a card.", "My opponent draws one.", "My opponent is drawing a card.",
                "My opponent takes a card off the top.", "My opponent drew a card.",
            ),
        ),
        Family(
            "a bounce", "I control Grizzly Bears. ", "Grizzly Bears: the battlefield → your hand",
            listOf(
                "They bounce my Grizzly Bears with Unsummon.", "They return my Grizzly Bears to my hand with Unsummon.",
                "My Grizzly Bears gets bounced by Unsummon.", "They Unsummon my Grizzly Bears.",
                "My Grizzly Bears is returned to my hand.",
            ),
        ),
        Family(
            "an exile", "I control Grizzly Bears. ", "Grizzly Bears: the battlefield → exile",
            listOf(
                "They exile my Grizzly Bears with Swords to Plowshares.",
                "My Grizzly Bears is exiled by Swords to Plowshares.",
                "They Swords my Grizzly Bears.", "My Grizzly Bears gets exiled.",
            ),
        ),
        Family(
            "a pump spell", "I control Grizzly Bears. ", "Grizzly Bears is 5/5",
            listOf(
                "They cast Giant Growth on my Grizzly Bears.", "They pump my Grizzly Bears with Giant Growth.",
                "My Grizzly Bears gets +3/+3 from Giant Growth.", "They Giant Growth my Grizzly Bears.",
            ),
        ),
        Family(
            "putting a counter on something", "I control Grizzly Bears. ", "Grizzly Bears is 3/3",
            listOf(
                "I put a +1/+1 counter on my Grizzly Bears. What are its stats?",
                "I add a +1/+1 counter to my Grizzly Bears. What are its stats?",
                "I place a +1/+1 counter on it. What are its stats?",
            ),
        ),
    )

    @Test
    fun `every way of saying the same thing reaches the same answer`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val wrong = mutableListOf<String>()
        Db.open(File(dbPath).toPath(), readOnly = true).use { conn ->
            val parser = SituationParser(NameIndex.load(conn))
            val judge = Judge(CardRepo(conn), RulesRepo(conn))
            for (family in families) for (phrasing in family.phrasings) {
                val text = family.prefix + phrasing
                val answer = runCatching { judge.answer(parser.parse(text).situation) }
                when {
                    answer.isFailure -> wrong += "${family.name}: \"$phrasing\" threw ${answer.exceptionOrNull()?.let { it::class.simpleName }}: ${answer.exceptionOrNull()?.message}"
                    !AnswerRenderer.render(answer.getOrThrow(), withCitations = false).contains(family.expect) ->
                        wrong += "${family.name}: \"$phrasing\" never reached \"${family.expect}\""
                }
            }
        }
        assertTrue(wrong.isEmpty(), "Phrasings that don't reach the same answer:\n" + wrong.joinToString("\n"))
    }
}
