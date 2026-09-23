package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaneswalkerTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, loyalty: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text, loyalty)

    private val jace = card("Jace Beleren", "Legendary Planeswalker — Jace",
        "+2: Each player draws a card.\n−1: Target player draws a card.\n−10: Target player mills twenty cards.", "{1}{U}{U}", "U", loyalty = "3")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val hill = card("Hill Giant", "Creature — Giant", "", "{3}{R}", "R", "3", "3")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `parser reads loyalty and loyalty abilities`() {
        assertTrue(jace.isPlaneswalker); assertEquals(3, jace.loyalty)
        val abilities = jace.abilities.filterIsInstance<ActivatedAbility>()
        assertEquals(listOf(2, -1, -10), abilities.map { it.loyaltyCost })
        assertTrue(abilities.all { it.restriction?.contains("sorcery") == true })
    }

    @Test
    fun `a planeswalker enters with its printed loyalty`() {
        val s = state(); val e = Engine(s)
        e.cast("me", jace, emptyList()); e.resolveAll()
        val j = s.objects.values.first { it.def === jace }
        assertEquals(Zone.BATTLEFIELD, j.zone); assertEquals(3, j.counters["loyalty"]); assertTrue("306.5b" in s.cited())
    }

    @Test
    fun `plus and minus abilities move loyalty, and a cost that can't be paid is refused`() {
        val s = state(); s.put("jace", jace, "me"); s.obj("jace").counters["loyalty"] = 3; val e = Engine(s)
        assertNotNull(e.activate("me", "jace", 0, emptyList())); e.resolveAll()
        assertEquals(5, s.obj("jace").counters["loyalty"]); assertTrue("606.4" in s.cited())
        assertEquals(1, s.player("me").drew); assertEquals(1, s.player("opp").drew)
        // A second loyalty ability in the same turn is refused (606.3), whatever its cost.
        assertNull(e.activate("me", "jace", 1, listOf(Ref.Player("opp"))), "second loyalty ability in one turn")
        assertTrue("606.3" in s.cited()); assertEquals(5, s.obj("jace").counters["loyalty"])
        s.loyaltyUsedThisTurn.clear()   // a new turn
        assertNull(e.activate("me", "jace", 2, listOf(Ref.Player("opp"))), "−10 with 5 loyalty")
        assertTrue("606.6" in s.cited()); assertEquals(5, s.obj("jace").counters["loyalty"])
        s.loyaltyUsedThisTurn.clear()
        assertNotNull(e.activate("me", "jace", 1, listOf(Ref.Player("opp")))); e.resolveAll()
        assertEquals(4, s.obj("jace").counters["loyalty"]); assertEquals(2, s.player("opp").drew)
    }

    @Test
    fun `damage removes loyalty and zero loyalty is a state-based action`() {
        val s = state(); s.put("jace", jace, "me"); s.obj("jace").counters["loyalty"] = 3; val e = Engine(s)
        e.cast("opp", bolt, listOf(Ref.Obj("jace"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("jace").zone)
        assertTrue("306.8" in s.cited()); assertTrue("704.5i" in s.cited())
        assertEquals(20, s.player("me").life, "damage to a planeswalker isn't damage to its controller")
    }

    @Test
    fun `attacking a planeswalker removes loyalty from it and a blocker stops it`() {
        val s = state(); s.put("jace", jace, "opp"); s.obj("jace").counters["loyalty"] = 5; s.put("giant", hill, "me"); s.put("bears", bears, "opp"); val e = Engine(s)
        e.declareAttacker("me", "giant", Ref.Obj("jace")); e.combatDamage()
        assertEquals(2, s.obj("jace").counters["loyalty"]); assertEquals(Zone.BATTLEFIELD, s.obj("jace").zone)
        assertEquals(20, s.player("opp").life)
        val s2 = state(); s2.put("jace", jace, "opp"); s2.obj("jace").counters["loyalty"] = 5; s2.put("giant", hill, "me"); s2.put("bears", bears, "opp"); val e2 = Engine(s2)
        e2.declareAttacker("me", "giant", Ref.Obj("jace")); e2.declareBlocker("opp", "bears", "giant"); e2.combatDamage()
        assertEquals(5, s2.obj("jace").counters["loyalty"]); assertEquals(Zone.GRAVEYARD, s2.obj("bears").zone)
    }

    @Test
    fun `you can't attack your own planeswalker or a creature`() {
        val s = state(); s.put("jace", jace, "me"); s.put("giant", hill, "me"); s.put("bears", bears, "opp"); val e = Engine(s)
        e.declareAttacker("me", "giant", Ref.Obj("jace"))
        assertNull(s.obj("giant").attacking); assertTrue("506.2" in s.cited())
        e.declareAttacker("me", "giant", Ref.Obj("bears"))
        assertNull(s.obj("giant").attacking); assertTrue("506.3" in s.cited())
    }
}
