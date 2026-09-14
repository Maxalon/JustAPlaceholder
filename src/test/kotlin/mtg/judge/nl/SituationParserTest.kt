package mtg.judge.nl

import mtg.judge.carddb.Db
import mtg.judge.carddb.ingest.Build
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The natural-language front door, against the fixture database (Rhystic Study, Stifle, Sol Ring, Smothering Tithe, Time Vault, Fire // Ice). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SituationParserTest {
    private lateinit var tmp: Path
    private lateinit var parser: SituationParser

    @BeforeAll fun setUp() {
        tmp = Files.createTempDirectory("mtg-judge-nl")
        val db = tmp.resolve("judge.db")
        Build.run(Paths.get(javaClass.getResource("/data/manifest.json")!!.toURI()).parent, db, log = {})
        parser = Db.open(db, readOnly = true).use { SituationParser(NameIndex.load(it)) }
    }
    @AfterAll fun tearDown() { tmp.toFile().deleteRecursively() }

    @Test
    fun `possession, continuation, turn and a verbified card name with a trigger target`() {
        val p = parser.parse("I have Rhystic Study and Smothering Tithe out. It's my opponent's turn, they cast Sol Ring and then Stifle the Rhystic Study trigger. What happens?")
        val s = p.situation
        assertEquals(listOf("Rhystic Study", "Smothering Tithe"), s.objects.map { it.card.name })
        assertTrue(s.objects.all { it.controller == "me" && it.zone == "battlefield" })
        assertEquals("opp", s.turn.activePlayer)
        assertEquals(listOf("cast", "cast", "resolveAll"), s.events.map { it.verb })
        assertEquals("Sol Ring", s.events[0].card?.name); assertEquals("opp", s.events[0].player)
        assertEquals("Stifle", s.events[1].card?.name); assertEquals(listOf("rhystic_study:trigger"), s.events[1].targets)
        assertTrue(p.unread.isEmpty(), p.unread.toString())
    }

    @Test
    fun `nicknames, respond with, and it`() {
        val p = parser.parse("Opponent casts Sol Ring. I respond with Stifle on it.")
        val ev = p.situation.events
        assertEquals("Sol Ring", ev[0].card?.name)
        assertEquals("me", ev[1].player); assertEquals("Stifle", ev[1].card?.name); assertEquals(listOf("sol_ring:spell"), ev[1].targets)
        val q = parser.parse("they cast sol and I have rhystic and tithe")
        assertEquals("Sol Ring", q.situation.events[0].card?.name)
        assertEquals(setOf("Rhystic Study", "Smothering Tithe"), q.situation.objects.map { it.card.name }.toSet())
    }

    @Test
    fun `life totals, counts, and unread clauses are reported`() {
        val p = parser.parse("I'm at 12 life and my opponent has two Sol Rings. I flip a coin. I cast Stifle.")
        assertEquals(12, p.situation.players.first { it.id == "me" }.life)
        assertEquals(2, p.situation.objects.count { it.card.name == "Sol Ring" && it.controller == "opp" })
        assertTrue(p.unread.any { it.contains("flip a coin") }, p.unread.toString())
    }

    @Test
    fun `possessive card names and no explicit resolve add resolveAll`() {
        val p = parser.parse("I activate Time Vault's ability.")
        assertEquals(listOf("activate", "resolveAll"), p.situation.events.map { it.verb })
        assertEquals("time_vault", p.situation.events[0].obj)
    }

    @Test
    fun `nothing recognisable yields no events and the text is unread`() {
        val p = parser.parse("The weather is nice today.")
        assertTrue(p.situation.events.isEmpty() && p.situation.objects.isEmpty())
        assertEquals(1, p.unread.size)
    }
}
