package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exiling a graveyard, dies-replacements that also do something else, and one damage line per creature. */
class BatchFourteenTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, cost.count { it in "WUBRGC" } + (Regex("""\{(\d+)\}""").find(cost)?.groupValues?.get(1)?.toDouble() ?: 0.0), colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val bog = card("Bojuka Bog", "Land", "Bojuka Bog enters tapped.\nWhen Bojuka Bog enters, exile target player's graveyard.", "")
    private val kalitas = card("Kalitas, Traitor of Ghet", "Legendary Creature — Vampire Warrior", "Lifelink\nIf a nontoken creature an opponent controls would die, instead exile that card and create a 2/2 black Zombie creature token.", "{2}{B}{B}", "B", "3", "4", "Lifelink")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val wall = card("Wall of Stone", "Creature — Wall", "Defender", "{1}{R}{R}", "R", "0", "8", "Defender")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String, zone: Zone = Zone.BATTLEFIELD) = add(GameObject(id, def, zone, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `bojuka bog exiles the whole graveyard at once`() {
        val s = state(); s.put("corpse", bears, "me", Zone.GRAVEYARD); s.put("corpse2", bolt, "me", Zone.GRAVEYARD)
        s.put("bog", bog, "opp", Zone.HAND)
        val e = Engine(s); e.enter("bog"); e.resolveAll()
        assertEquals(Zone.EXILE, s.obj("corpse").zone)
        assertEquals(Zone.EXILE, s.obj("corpse2").zone)
        assertTrue("701.13a" in s.cited())
        assertTrue(s.assumptions.any { "target player" in it }, s.assumptions.toString())
    }

    @Test
    fun `an empty graveyard is exiled without incident`() {
        val s = state(); s.put("bog", bog, "opp", Zone.HAND); val e = Engine(s); e.enter("bog"); e.resolveAll()
        assertTrue(s.trace.steps.any { it.text.contains("graveyard is empty") }, s.trace.steps.joinToString("\n") { it.text })
    }

    @Test
    fun `kalitas exiles an opponent's dying creature and makes a zombie`() {
        val s = state(); s.put("kal", kalitas, "me"); s.put("bears", bears, "opp")
        val e = Engine(s); e.cast("me", bolt, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.EXILE, s.obj("bears").zone)
        assertTrue(s.objects.values.any { it.token && it.name.contains("Zombie") }, s.objects.values.joinToString { it.name })
        assertTrue("614.1a" in s.cited())
    }

    @Test
    fun `kalitas leaves its controller's own creatures alone`() {
        val s = state(); s.put("kal", kalitas, "me"); s.put("bears", bears, "me")
        val e = Engine(s); e.cast("me", bolt, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("bears").zone)
        assertTrue(s.objects.values.none { it.token })
    }

    @Test
    fun `two hits on one creature leave a single damage outcome`() {
        val s = state(); s.put("wall", wall, "opp")
        val e = Engine(s)
        e.cast("me", bolt, listOf(Ref.Obj("wall"))); e.resolveAll()
        e.cast("me", bolt, listOf(Ref.Obj("wall"))); e.resolveAll()
        assertEquals(6, s.obj("wall").damage)
        assertEquals(1, s.outcomes.count { it.contains("damage marked") }, s.outcomes.toString())
        assertTrue(s.outcomes.any { it.contains("6 damage marked") }, s.outcomes.toString())
    }
}
