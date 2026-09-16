package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a {T} cost costs, mana ability or not. */
class BatchSeventeenTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, cost.count { it in "WUBRGC" } + (Regex("""\{(\d+)\}""").find(cost)?.groupValues?.get(1)?.toDouble() ?: 0.0), colors, p, t, kw.toList(), text)

    private val elves = card("Llanowar Elves", "Creature — Elf Druid", "{T}: Add {G}.", "{G}", "G", "1", "1")
    private val hasty = card("Hasty Elves", "Creature — Elf Druid", "Haste\n{T}: Add {G}.", "{G}", "G", "1", "1", "Haste")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String, zone: Zone = Zone.BATTLEFIELD) = add(GameObject(id, def, zone, ctrl))

    /**
     * 302.6 applies to any {T} cost. The check used to sit below the mana-ability branch, which returns first, so
     * a summoning-sick Llanowar Elves made mana the turn it came down — and the trace said so confidently.
     */
    @Test
    fun `a summoning-sick creature cannot pay a T cost for mana either`() {
        val s = state()
        s.put("elves", elves, "me").summoningSick = true
        val e = Engine(s); e.activate("me", "elves", null, emptyList())
        assertTrue(s.trace.steps.any { "can't be activated" in it.text && "302.6" in it.rules },
            s.trace.steps.joinToString("\n") { it.text })
        assertEquals(false, s.obj("elves").tapped, "it must not have been tapped for a cost it couldn't pay")
    }

    @Test
    fun `haste lets it pay the T cost the turn it arrives`() {
        val s = state()
        s.put("hasty", hasty, "me").summoningSick = true
        val e = Engine(s); e.activate("me", "hasty", null, emptyList())
        assertTrue(s.trace.steps.any { "mana ability" in it.text }, s.trace.steps.joinToString("\n") { it.text })
    }

    @Test
    fun `an already-tapped creature cannot pay a T cost for mana either`() {
        val s = state()
        s.put("elves", elves, "me").let { it.summoningSick = false; it.tapped = true }
        val e = Engine(s); e.activate("me", "elves", null, emptyList())
        assertTrue(s.trace.steps.any { "already tapped" in it.text }, s.trace.steps.joinToString("\n") { it.text })
    }
}
