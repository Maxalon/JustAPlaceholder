package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetingTest {
    private fun card(name: String, type: String, text: String, cost: String, colors: String, p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val hexBear = card("Slippery Bear", "Creature — Bear", "Hexproof", "{1}{G}", "G", "2", "2", "Hexproof")
    private val shroudBear = card("Hidden Bear", "Creature — Bear", "Shroud", "{1}{G}", "G", "2", "2", "Shroud")
    private val proBear = card("Red Ward Bear", "Creature — Bear", "Protection from red", "{1}{W}", "W", "2", "2", "Protection")
    private val wardBear = card("Warded Bear", "Creature — Bear", "Ward {2}", "{1}{G}", "G", "2", "2", "Ward")
    private val growth = card("Giant Growth", "Instant", "Target creature gets +3/+3 until end of turn.", "{G}", "G")
    private val redBear = card("Red Bear", "Creature — Bear", "", "{1}{R}", "R", "2", "2")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `hexproof blocks opponents but not the controller`() {
        val s = state(); s.put("b", hexBear, "me"); val e = Engine(s)
        assertNull(e.cast("opp", bolt, listOf(Ref.Obj("b")))); assertTrue("702.11b" in s.cited())
        assertTrue(e.cast("me", growth, listOf(Ref.Obj("b"))) != null)
    }

    @Test
    fun `shroud blocks everyone`() {
        val s = state(); s.put("b", shroudBear, "me"); val e = Engine(s)
        assertNull(e.cast("me", growth, listOf(Ref.Obj("b")))); assertTrue("702.18a" in s.cited())
    }

    @Test
    fun `protection from red stops red targeting, red damage, and red blockers`() {
        val s = state(); s.put("b", proBear, "me"); s.put("r", redBear, "opp"); val e = Engine(s)
        assertNull(e.cast("opp", bolt, listOf(Ref.Obj("b")))); assertTrue("702.16b" in s.cited())
        assertTrue(e.cast("me", growth, listOf(Ref.Obj("b"))) != null)
        e.resolveAll()
        e.declareAttacker("me", "b", Ref.Player("opp")); e.declareBlocker("opp", "r", "b")
        assertNull(s.obj("r").blocking); assertTrue("702.16f" in s.cited())
        e.dealDamage("Red Bear", Ref.Obj("b"), 2)
        assertEquals(0, s.obj("b").damage); assertTrue("702.16e" in s.cited())
    }

    @Test
    fun `ward counters the spell unless its controller pays`() {
        val s = state(); s.put("b", wardBear, "me"); val e = Engine(s)
        val item = e.cast("opp", bolt, listOf(Ref.Obj("b")))!!
        assertEquals(2, s.stack.size); assertEquals(StackKind.TRIGGERED, s.stack.last().kind); assertTrue("702.21a" in s.cited())
        e.resolveAll()
        assertEquals(Zone.GRAVEYARD, item.source.zone); assertEquals(0, s.obj("b").damage)
        assertTrue(s.assumptions.any { it.contains("{2}") })
    }
}
