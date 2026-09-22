package mtg.judge.cr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules-question table answers questions with no board at all. The risk in a table like this is not that an
 * answer is wrong — each one is written against the rule it cites — but that a later entry is shadowed by an
 * earlier one whose terms happen to appear in the same sentence, so a question quietly gets the wrong answer.
 * These cases are one per entry, written the way a player would type them.
 */
class RulesAnswersTest {

    private val cases = listOf(
        "What order do triggers go on the stack?" to "603.3b",
        "We both have a trigger at the same time, whose goes first?" to "603.3b",
        "My opponent and I both have a trigger, whose resolves first?" to "603.3b",
        "Can I respond to a land being played?" to "116.2a",
        "Does my opponent get priority before my spell resolves?" to "117.4",
        "Can I respond after blockers are declared but before damage?" to "509.2",
        "When can I cast a creature with flash?" to "702.8a",
        "Can I cast a sorcery on my opponent's turn?" to "307.1",
        "What does sorcery speed mean?" to "307.1",
        "Can I activate two loyalty abilities in one turn?" to "606.3",
        "Can I use a loyalty ability twice?" to "606.3",
        "Does my mana empty between phases?" to "500.4",
        "I draw from an empty library, when do I lose?" to "121.4",
        "In the untap step do I untap everything?" to "502.3",
        "Can I respond to a mana ability?" to "605.3a",
        "How much commander damage does it take?" to "903.10a",
        "How many poison counters before I lose?" to "704.5c",
        "What does summoning sick mean?" to "302.6",
        "How does the legend rule work?" to "704.5j",
        "What does deathtouch do?" to "702.2b",
        "How does first strike damage work?" to "510.4",
        "How do I assign trample damage?" to "702.19b",
        "Can a tapped creature block?" to "509.1a",
        "When does damage wear off?" to "514.2",
        "Do a +1/+1 counter and a -1/-1 counter cancel?" to "704.5q",
        "What happens when every target is illegal on resolve?" to "608.2b",
        "What does it mean when a spell fizzles?" to "608.2b",
        "At 0 life do I lose right away?" to "704.5a",
        "When are state-based actions checked?" to "704.3",
        "How does the commander tax work?" to "903.8",
        "Does indestructible stop destroy effects?" to "702.12b",
        "What can block a creature with flying?" to "702.9b",
        "What does reach do for blocking?" to "702.9b",
        "Does indestructible protect against exile?" to "702.12b",
        "Does regeneration work against exile?" to "701.19a",
        "Can my opponent make me sacrifice a creature if it has hexproof?" to "702.11b",
        "If my creature has shroud can I target it with my own pump spell?" to "702.18a",
        "If my lifelink creature deals damage and dies at the same time do I still gain life?" to "702.15b",
        "Double strike vs first strike blocker, who dies?" to "702.4b",
        "If both creatures have first strike do they trade?" to "702.7b",
        "Can I activate a creature's tap ability the turn it enters?" to "302.6",
        "Can I sacrifice a creature in response to it being exiled?" to "608.2b",
        "If both players are at 0 life who wins?" to "104.4a",
        "If both of us go to 0 life at once, who wins the game?" to "104.4a",
        "When can a player concede?" to "104.3a",
        "They scoop in response to my spell, what happens?" to "104.3a",
        "When does an extra turn happen?" to "500.7",
    )

    @Test
    fun `each question reaches its own answer`() {
        val wrong = cases.mapNotNull { (q, rule) ->
            val a = RulesAnswers.lookup(q)
            when {
                a == null -> "$q -> no answer (expected $rule)"
                rule !in a.rules -> "$q -> cites ${a.rules} (expected $rule)"
                else -> null
            }
        }
        assertTrue(wrong.isEmpty(), "Questions that don't reach their answer:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `every answer cites at least one rule and says something`() {
        for ((q, _) in cases) {
            val a = assertNotNull(RulesAnswers.lookup(q), q)
            assertTrue(a.rules.isNotEmpty(), "$q cites no rule")
            assertTrue(a.text.length > 40, "$q has a one-word answer")
        }
    }

    @Test
    fun `a question the table doesn't know gets no answer`() {
        // The table is deliberately not a search: an unknown question must fall through to the ordinary refusal
        // rather than pick up whichever entry shares a word with it.
        assertNull(RulesAnswers.lookup("Does my Grizzly Bears survive a Shock?"))
        assertNull(RulesAnswers.lookup("I attack with a 2/2 and they block with a 3/3"))
        assertNull(RulesAnswers.lookup("what is the best commander"))
    }

    @Test
    fun `wording around the question doesn't matter`() {
        val a = assertNotNull(RulesAnswers.lookup("hey quick question — what ORDER do triggers go on the stack??"))
        assertEquals(RulesAnswers.lookup("what order do triggers go on the stack")?.text, a.text)
    }
}
