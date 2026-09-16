package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** When a trigger from an activation cost goes on the stack. */
class BatchFifteenTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, cost.count { it in "WUBRGC" } + (Regex("""\{(\d+)\}""").find(cost)?.groupValues?.get(1)?.toDouble() ?: 0.0), colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val artist = card("Blood Artist", "Creature — Vampire", "Whenever Blood Artist or another creature dies, target player loses 1 life and you gain 1 life.", "{1}{B}", "B", "0", "1")
    private val seer = card("Viscera Seer", "Creature — Vampire Wizard", "Sacrifice a creature: Scry 1.", "{B}", "B", "1", "1")
    private val altar = card("Ashnod's Altar", "Artifact", "Sacrifice a creature: Add {C}{C}.", "{3}")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String, zone: Zone = Zone.BATTLEFIELD) = add(GameObject(id, def, zone, ctrl))

    /**
     * 603.3 and 117.5: an ability that triggers while a cost is being paid isn't put on the stack until a player
     * would next receive priority, and by then the ability being activated is already there. So the trigger goes
     * ON TOP and resolves first. Stacking it as the creature dies puts it underneath and resolves it last, which
     * is the opposite order.
     */
    @Test
    fun `a trigger from an activation cost goes on the stack above the ability`() {
        val s = state()
        s.put("artist", artist, "me"); s.put("seer", seer, "me"); s.put("bear", bears, "me")
        val e = Engine(s)
        e.activate("me", "seer", null, emptyList(), choice = "sacrifice:bear")
        assertEquals(2, s.stack.size, s.stack.joinToString { it.describe })
        assertEquals("Viscera Seer", s.stack[0].source.name, "the activated ability should be underneath")
        assertEquals("Blood Artist", s.stack[1].source.name, "the trigger should be on top and resolve first")
    }

    @Test
    fun `the same trigger waits for a mana ability to finish resolving`() {
        val s = state()
        s.put("artist", artist, "me"); s.put("altar", altar, "me"); s.put("bear", bears, "me")
        val e = Engine(s)
        e.activate("me", "altar", null, emptyList(), choice = "sacrifice:bear")
        // A mana ability never uses the stack, so once it has resolved the trigger is the only thing there.
        assertEquals(1, s.stack.size, s.stack.joinToString { it.describe })
        assertEquals("Blood Artist", s.stack[0].source.name)
        val steps = s.trace.steps.map { it.text }
        val mana = steps.indexOfFirst { "mana ability" in it }
        val put = steps.indexOfFirst { "on the stack" in it && "Blood Artist" in it }
        assertTrue(mana in 0 until put, "the mana ability should resolve before the trigger is put on the stack:\n" + steps.joinToString("\n"))
    }
}
