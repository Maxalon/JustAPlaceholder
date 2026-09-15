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
        assertEquals(listOf("cast", "pay", "resolveAll", "attack", "attack", "resolveAll"), verbs)
        assertEquals("alice", p.situation.events[0].player); assertEquals("no", p.situation.events[1].to)
        assertEquals(listOf("alice"), p.situation.events[3].targets); assertEquals(listOf("carol"), p.situation.events[4].targets)
        assertEquals("bob", p.situation.events[4].player)
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

    @Test
    fun `repeat casts, library size, with-lists, questions, his minus one and named responses`() {
        val p = parser.parse("I have Sol Ring and cast Stifle on it twice. I have no cards left in my library.")
        assertEquals(listOf("cast", "cast", "resolveAll"), p.situation.events.map { it.verb })
        assertEquals(listOf("sol_ring"), p.situation.events[1].targets)
        assertEquals(0, p.situation.players.first { it.id == "me" }.librarySize)
        val q = parser.parse("My opponent casts Stifle with Sol Ring and Time Vault on the battlefield under my control.")
        assertEquals(setOf("Sol Ring", "Time Vault"), q.situation.objects.map { it.card.name }.toSet())
        assertTrue(q.situation.objects.all { it.controller == "me" })
        val r = parser.parse("Bob casts Stifle at Alice's Sol Ring. Alice responds by casting Rhystic Study. Does Sol Ring survive?")
        assertEquals("bob", r.situation.events[0].player); assertEquals("alice", r.situation.events[1].player)
        assertTrue(r.unread.isEmpty(), "unread: ${r.unread}"); assertTrue(r.notes.any { "survive" in it })
        val t = parser.parse("I have Time Vault. I use its +1 targeting my opponent.")
        val act = t.situation.events.first { it.verb == "activate" }
        assertEquals("time_vault", act.obj); assertEquals("+1", act.to); assertEquals(listOf("opp"), act.targets)
        val u = parser.parse("I cast Sol Ring with evoke.")
        assertEquals("evoke", u.situation.events.first().to)
    }

    @Test
    fun `flash-in blockers, ultimates, trigger choices, uncast spell targets and declined payments`() {
        val p = parser.parse("I attack with Sol Ring and my opponent flashes in Time Vault to block it.")
        assertEquals(listOf("attack", "cast", "resolveAll", "block", "resolveAll"), p.situation.events.map { it.verb })
        assertEquals("time_vault", p.situation.events[3].obj); assertEquals(listOf("sol_ring"), p.situation.events[3].targets)
        val q = parser.parse("I cast Time Vault. Can I ultimate it right away?")
        assertEquals("ultimate", q.situation.events.first { it.verb == "activate" }.to); assertTrue(q.unread.isEmpty(), "unread: ${q.unread}")
        val r = parser.parse("I attack with Sol Ring and put Time Vault onto the battlefield with Sol Ring's trigger.")
        assertEquals(listOf("choose", "attack"), r.situation.events.take(2).map { it.verb })
        assertEquals("put:time_vault", r.situation.events[0].to); assertEquals("hand", r.situation.objects.first { it.card.name == "Time Vault" }.zone)
        val t = parser.parse("I cast Stifle on my opponent's Fire // Ice.")
        assertEquals(listOf("cast", "cast", "resolveAll"), t.situation.events.map { it.verb }); assertEquals("opp", t.situation.events[0].player)
        val u = parser.parse("I have Rhystic Study and my opponent casts two spells this turn, paying for none of them.")
        assertEquals(listOf("cast", "cast", "pay", "resolveAll"), u.situation.events.map { it.verb }); assertEquals("no", u.situation.events[2].to)
    }

    @Test
    fun `described creatures attack and block, mills target a player, and bare hand sizes stick to the last owner`() {
        val p = parser.parse("I attack with a 3/3 and my opponent blocks with two 2/2s. How do I assign damage?")
        assertEquals(listOf("attack", "block", "block", "resolveAll"), p.situation.events.map { it.verb })
        assertEquals(listOf("a 3/3 creature", "a 2/2 creature", "a 2/2 creature"), p.situation.objects.map { it.card.name })
        assertTrue(p.unread.isEmpty(), "unread: ${p.unread}")
        val q = parser.parse("My opponent has Sol Ring and 0 cards in hand. I control Rhystic Study and 3 other goblins.")
        assertEquals(0, q.situation.players.first { it.id == "opp" }.handSize)
        assertEquals(3, q.situation.objects.count { it.card.name == "a goblin creature" && it.controller == "me" })
        val r = parser.parse("My opponent mills me with Stifle. I have 5 cards in library.")
        assertEquals(listOf("me"), r.situation.events.first().targets); assertEquals("opp", r.situation.events.first().player)
        assertEquals(5, r.situation.players.first { it.id == "me" }.librarySize)
    }

    @Test
    fun `attachments by has-on, token fragments, commander damage and hit-with attacks`() {
        val p = parser.parse("My opponent has Sol Ring on Time Vault and a Treasure token. My commander Rhystic Study has dealt 18 damage to my opponent already. I hit them with Rhystic Study again unblocked.")
        val ring = p.situation.objects.first { it.card.name == "Sol Ring" }
        assertEquals("time_vault", ring.attachedTo); assertEquals("opp", ring.controller)
        assertTrue(p.situation.objects.any { it.card.name.equals("treasure token", ignoreCase = true) && it.token && it.controller == "opp" })
        assertEquals(mapOf("rhystic_study" to 18), p.situation.players.first { it.id == "opp" }.commanderDamage)
        assertTrue(p.situation.objects.first { it.card.name == "Rhystic Study" }.commander)
        val attack = p.situation.events.first { it.verb == "attack" }
        assertEquals("rhystic_study", attack.obj); assertEquals(listOf("opp"), attack.targets); assertTrue(p.unread.isEmpty(), "unread: ${p.unread}")
    }

    @Test
    fun `yes-no questions become ask events, and everyday-word card names stay table talk`() {
        val p = parser.parse("I attack with Sol Ring and my opponent blocks with Time Vault. Does the Sol Ring deal damage to my opponent?")
        val ask = p.situation.events.last()
        assertEquals("ask", ask.verb); assertEquals("damage", ask.to); assertEquals("sol_ring", ask.obj); assertEquals(listOf("opp"), ask.targets)
        assertTrue(p.unread.isEmpty(), "unread: ${p.unread}")
        val q = parser.parse("I have Rhystic Study and it attacks. Does my Rhystic Study survive?")
        assertEquals(listOf("attack", "resolveAll", "ask"), q.situation.events.map { it.verb }); assertEquals("survive", q.situation.events.last().to)
    }

    @Test
    fun `turn numbers, keyword adjectives, life at casting, paid life as X and player questions`() {
        val p = parser.parse("It's turn 3. My opponent controls hexproof Sol Ring. I cast Stifle at 5 life. Do I die?")
        assertEquals(3, p.situation.turn.number)
        assertEquals(listOf("hexproof"), p.situation.objects.first { it.card.name == "Sol Ring" }.keywords)
        assertEquals(5, p.situation.players.first { it.id == "me" }.life)
        val ask = p.situation.events.last(); assertEquals("ask", ask.verb); assertEquals("playerDie", ask.to); assertEquals("me", ask.player)
        val q = parser.parse("I have Stifle in hand. I cast it paying 3 life.")
        assertEquals(listOf("loseLife", "cast", "resolveAll"), q.situation.events.map { it.verb }); assertEquals(3, q.situation.events[1].amount)
    }

    @Test
    fun `verbs never become short names, bare blocks pick the defender's creature, and attack-with-both`() {
        val p = parser.parse("I control Sol Ring and my opponent casts Rhystic Study on it. I have two Time Vault and attack with both. They block.")
        assertEquals("opp", p.situation.events.first { it.verb == "cast" }.player)
        assertEquals(listOf("sol_ring"), p.situation.events.first { it.verb == "cast" }.targets)
        assertTrue(p.situation.events.any { it.verb == "attackAll" && it.player == "me" })
        val block = p.situation.events.first { it.verb == "block" }
        assertEquals("opp", block.player); assertEquals("rhystic_study", block.obj)
    }

    @Test
    fun `casts one, library and devotion trailers, a countered commander and pronoun questions`() {
        val p = parser.parse("Bob controls Rhystic Study. Carol casts a spell and pays, Dave casts one and doesn't.")
        assertEquals(listOf("cast:carol", "pay:carol:yes", "cast:dave", "pay:dave:no"), p.situation.events.filter { it.verb != "resolveAll" }.map { "${it.verb}:${it.player}${it.to?.let { t -> ":$t" } ?: ""}" })
        val q = parser.parse("I control Sol Ring with 3 cards in library and my devotion to blue is 4.")
        assertEquals(3, q.situation.players.first { it.id == "me" }.librarySize); assertEquals(mapOf("blue" to 4), q.situation.players.first { it.id == "me" }.devotion)
        val r = parser.parse("My commander is Sol Ring and it has been countered twice. Can I cast it again?")
        val ring = r.situation.objects.single(); assertEquals("command", ring.zone); assertTrue(ring.commander); assertEquals(2, ring.commanderCasts)
        assertEquals("sol_ring", r.situation.events.first { it.verb == "cast" }.obj)
        val d = parser.parse("My opponent controls Sol Ring. I cast Stifle on it. Does it die?")
        assertEquals("die", d.situation.events.last { it.verb == "ask" }.to); assertEquals("sol_ring", d.situation.events.last { it.verb == "ask" }.obj)
        val w = parser.parse("I control Sol Ring. Then I draw two.")
        assertEquals(2, w.situation.events.first { it.verb == "draw" }.amount)
    }

    @Test
    fun `player win and lose questions, described-creature questions, and where-questions are not card names`() {
        val p = parser.parse("I'm at 2 life. My opponent casts Sol Ring. Do I lose?")
        assertEquals("playerDie", p.situation.events.last { it.verb == "ask" }.to); assertEquals("me", p.situation.events.last { it.verb == "ask" }.player)
        assertEquals("playerWin", parser.parse("My opponent casts Sol Ring. Do I win?").situation.events.last { it.verb == "ask" }.to)
        assertEquals("playerDie", parser.parse("My opponent casts Sol Ring. Am I dead?").situation.events.last { it.verb == "ask" }.to)
        val q = parser.parse("I attack with a 2/2 with flying. My opponent blocks with Sol Ring. Does my creature survive?")
        assertEquals("2_2_creature_with_flying", q.situation.events.last { it.verb == "ask" }.obj); assertEquals("survive", q.situation.events.last { it.verb == "ask" }.to)
        val w = parser.parse("I control Sol Ring. My opponent casts Stifle on it. Where does Sol Ring go?")
        assertTrue(w.unread.isEmpty(), w.unread.toString()); assertTrue(w.situation.players.none { it.name == "Where" }); assertTrue(w.notes.any { "answered by the outcome" in it })
    }

    @Test
    fun `x on activations, giving-with as a cast, and a life total in the middle of a sentence`() {
        val p = parser.parse("I have Sol Ring. I activate Sol Ring with X=4.")
        assertEquals(4, p.situation.events.first { it.verb == "activate" }.amount); assertTrue(p.situation.events.first { it.verb == "activate" }.targets.isEmpty())
        val q = parser.parse("I control Sol Ring. My opponent casts Rhystic Study. Can I respond by giving my Sol Ring hexproof with Stifle?")
        val cast = q.situation.events.last { it.verb == "cast" }; assertEquals("me", cast.player); assertEquals("Stifle", cast.card?.name); assertEquals(listOf("sol_ring"), cast.targets)
        val r = parser.parse("My opponent attacks me with Sol Ring and I'm at 1 life. I cast Stifle.")
        assertEquals(1, r.situation.players.first { it.id == "me" }.life); assertTrue(r.situation.events.any { it.verb == "attack" && it.obj == "sol_ring" }); assertTrue(r.unread.isEmpty(), r.unread.toString())
        val t = parser.parse("I attack with Sol Ring. After damage, does Sol Ring untap?")
        assertEquals("tapped", t.situation.events.last { it.verb == "ask" }.to); assertEquals("sol_ring", t.situation.events.last { it.verb == "ask" }.obj); assertTrue(t.unread.isEmpty(), t.unread.toString())
        val u = parser.parse("My opponent cast Sol Ring last turn. I have 4 lands and attack with Time Vault.")
        assertEquals("opp", u.situation.objects.first { it.card.name == "Sol Ring" }.controller); assertEquals(false, u.situation.objects.first { it.card.name == "Sol Ring" }.summoningSick)
        assertEquals(4, u.situation.players.first { it.id == "me" }.mana); assertEquals("me", u.situation.events.first { it.verb == "attack" }.player); assertTrue(u.unread.isEmpty(), u.unread.toString())
    }

    @Test
    fun `graveyard contents, library sizes mid-sentence, stats questions, and same-named blockers`() {
        val p = parser.parse("My opponent controls Sol Ring. They have an instant and two creatures in their graveyard. What are its stats?")
        val gy = p.situation.objects.filter { it.zone == "graveyard" }; assertEquals(3, gy.size); assertTrue(gy.all { it.controller == "opp" }); assertEquals(listOf("an instant", "a creature", "a creature"), gy.map { it.card.name })
        assertEquals("pt", p.situation.events.last { it.verb == "ask" }.to); assertEquals("sol_ring", p.situation.events.last { it.verb == "ask" }.obj)
        val q = parser.parse("I cast Stifle with 1 card in my library. Do I lose?")
        assertEquals(1, q.situation.players.first { it.id == "me" }.librarySize); assertTrue(q.unread.isEmpty(), q.unread.toString())
        val r = parser.parse("My opponent attacks me with Sol Ring. I block with Sol Ring.")
        val block = r.situation.events.first { it.verb == "block" }; assertEquals("me", block.player); assertEquals("me", r.situation.objects.first { it.id == block.obj }.controller); assertEquals(2, r.situation.objects.size)
        val t = parser.parse("My opponent has 8 cards in hand at their cleanup step.")
        assertEquals(8, t.situation.players.first { it.id == "opp" }.handSize); assertEquals("cleanup", t.situation.events.first { it.verb == "step" }.to)
    }

    @Test
    fun `amount questions are not actions, and theirs or mine picks the other copy`() {
        val p = parser.parse("I cast Stifle targeting myself. Do I lose 2 life?")
        assertTrue(p.situation.events.none { it.verb == "loseLife" }, p.situation.events.toString()); assertTrue(p.notes.any { "answered by the outcome" in it })
        val q = parser.parse("I attack with Sol Ring and my opponent blocks with Sol Ring. I cast Stifle on mine. Does theirs die?")
        val ask = q.situation.events.last { it.verb == "ask" }; assertEquals("die", ask.to)
        assertEquals("opp", q.situation.objects.first { it.id == ask.obj }.controller); assertEquals(2, q.situation.objects.count { it.card.name == "Sol Ring" })
    }

    @Test
    fun `attacks me, get-it-back questions, payments after then, and no-creature statements`() {
        val p = parser.parse("I control Sol Ring. My opponent casts Stifle on it and attacks me. Do I get it back?")
        val attack = p.situation.events.first { it.verb == "attack" }; assertEquals("opp", attack.player); assertEquals("sol_ring", attack.obj); assertEquals(listOf("me"), attack.targets)
        val ask = p.situation.events.last { it.verb == "ask" }; assertEquals("control", ask.to); assertEquals("me", ask.player); assertEquals(listOf("opp"), ask.targets)
        val q = parser.parse("I control Rhystic Study and my opponent casts Sol Ring. Then they pay the 1.")
        val verbs = q.situation.events.map { it.verb }; assertTrue(verbs.indexOf("pay") < verbs.indexOf("resolveAll"), verbs.toString())
        val r = parser.parse("I attack with Sol Ring. My opponent has no creatures. Do they take 2?")
        assertTrue(r.unread.isEmpty(), r.unread.toString())
        val v = parser.parse("My opponent controls Sol Ring. I cast Stifle on it and they respond by bouncing it with Time Vault.")
        val bounce = v.situation.events.last { it.verb == "cast" }; assertEquals("opp", bounce.player); assertEquals("Time Vault", bounce.card?.name); assertTrue(bounce.targets.any { "sol_ring" in it }, bounce.targets.toString())
        val w = parser.parse("I control Sol Ring. My opponent casts Stifle on it in response to my attack.")
        assertTrue(w.situation.events.indexOfFirst { it.verb == "attack" } < w.situation.events.indexOfFirst { it.verb == "cast" }, w.situation.events.toString()); assertTrue(w.unread.isEmpty(), w.unread.toString())
    }
}
