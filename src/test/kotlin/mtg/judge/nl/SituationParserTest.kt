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
    fun `short names of cards named in full, state fragments, and defender-first attacks`() {
        val p = parser.parse("I have Smothering Tithe with 2 damage on it and two +1/+1 counters. Then my opponent attacks Tithe with Rhystic Study.")
        val tithe = p.situation.objects.first { it.card.name == "Smothering Tithe" }
        assertEquals(2, tithe.damage); assertEquals(mapOf("+1/+1" to 2), tithe.counters)
        val attack = p.situation.events.first { it.verb == "attack" }
        assertEquals("opp", attack.player); assertEquals("rhystic_study", attack.obj); assertEquals(listOf("smothering_tithe"), attack.targets)
        assertTrue(p.unread.isEmpty(), "unread: ${p.unread}")
        val q = parser.parse("I have Time Vault at 4 loyalty. I activate Vault's +1.")
        assertEquals(mapOf("loyalty" to 4), q.situation.objects.first().counters)
        assertEquals("+1", q.situation.events.first { it.verb == "activate" }.to)
    }

    @Test
    fun `named players become players, with their turn, life, possessions and pronouns`() {
        val p = parser.parse("It's Alice's turn. Alice is at 12 life. Bob's Rhystic Study is out. Alice casts Sol Ring and doesn't pay. Then Bob attacks Alice with Time Vault and Carol with Smothering Tithe.")
        assertEquals(listOf("alice", "bob", "carol"), p.situation.players.map { it.id })
        assertEquals("Alice", p.situation.players[0].name); assertEquals(12, p.situation.players[0].life)
        assertEquals("alice", p.situation.turn.activePlayer)
        assertEquals("bob", p.situation.objects.first { it.card.name == "Rhystic Study" }.controller)
        val verbs = p.situation.events.map { it.verb }
        assertEquals(listOf("cast", "pay", "attack", "attack", "resolveAll"), verbs)
        assertEquals("alice", p.situation.events[0].player); assertEquals("no", p.situation.events[1].to)
        assertEquals(listOf("alice"), p.situation.events[2].targets); assertEquals(listOf("carol"), p.situation.events[3].targets)
        assertEquals("bob", p.situation.events[3].player)
        assertTrue(p.unread.isEmpty(), "unread: ${p.unread}")
        // "they" after a named actor is that player; "me" joins the table as a player.
        val q = parser.parse("Bob casts Sol Ring targeting me. They respond with Stifle on it.")
        assertEquals(listOf("me", "bob"), q.situation.players.map { it.id })
        assertEquals("bob", q.situation.events[1].player)
    }

    @Test
    fun `nothing recognisable yields no events and the text is unread`() {
        val p = parser.parse("The weather is nice today.")
        assertTrue(p.situation.events.isEmpty() && p.situation.objects.isEmpty())
        assertEquals(1, p.unread.size)
    }
}
