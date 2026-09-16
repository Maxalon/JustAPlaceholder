package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Permanents that enter as a copy of another (707.2). */
class BatchSixteenTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, cost.count { it in "WUBRGC" } + (Regex("""\{(\d+)\}""").find(cost)?.groupValues?.get(1)?.toDouble() ?: 0.0), colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val angel = card("Serra Angel", "Creature — Angel", "Flying, vigilance", "{3}{W}{W}", "W", "4", "4", "Flying", "Vigilance")
    private val clone = card("Clone", "Creature — Shapeshifter", "You may have ~ enter as a copy of any creature on the battlefield.", "{3}{U}", "U", "0", "0")
    private val vesuva = card("Vesuva", "Land", "You may have ~ enter tapped as a copy of any land on the battlefield.", "")
    private val thalia = card("Thalia, Guardian of Thraben", "Legendary Creature — Human Soldier", "First strike", "{1}{W}", "W", "2", "1", "First strike")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String, zone: Zone = Zone.BATTLEFIELD) = add(GameObject(id, def, zone, ctrl))

    @Test
    fun `a clone takes the copied card's printed values`() {
        val s = state(); s.put("angel", angel, "opp"); s.put("clone", clone, "me", Zone.HAND)
        val e = Engine(s); e.cast("me", clone, emptyList(), "clone", choice = "copy:angel"); e.resolveAll()
        val copy = s.obj("clone")
        assertEquals("Serra Angel", copy.def.name)
        assertEquals(4, copy.power)
        assertTrue(copy.has("flying"), "the copy has the copied card's abilities")
        assertEquals("me", copy.controller, "the copy keeps its own controller")
    }

    /** 707.2: counters are not copiable values, so a copy of a creature with counters doesn't get them. */
    @Test
    fun `counters on the original are not copied`() {
        val s = state()
        val bear = s.put("bear", bears, "opp"); bear.counters["+1/+1"] = 3
        s.put("clone", clone, "me", Zone.HAND)
        val e = Engine(s); e.cast("me", clone, emptyList(), "clone", choice = "copy:bear"); e.resolveAll()
        assertEquals(2, s.obj("clone").power, "copied the printed 2/2, not the 5/5 on the battlefield")
        assertEquals(0, s.obj("clone").counters["+1/+1"] ?: 0)
    }

    @Test
    fun `a clone of your own legend loses one of them to the legend rule`() {
        val s = state(); s.put("thalia", thalia, "me"); s.put("clone", clone, "me", Zone.HAND)
        val e = Engine(s); e.cast("me", clone, emptyList(), "clone", choice = "copy:thalia"); e.resolveAll(); e.stateBasedActions()
        val alive = s.objects.values.filter { it.isOnBattlefield() && it.def.name == "Thalia, Guardian of Thraben" }
        assertEquals(1, alive.size, "the legend rule should leave one: " + s.objects.values.map { "${it.id}=${it.def.name}@${it.zone}" })
    }

    @Test
    fun `with nothing to copy it enters as itself`() {
        val s = state(); s.put("clone", clone, "me", Zone.HAND)
        val e = Engine(s); e.cast("me", clone, emptyList(), "clone"); e.resolveAll(); e.stateBasedActions()
        assertEquals("Clone", s.obj("clone").def.name)
        assertEquals(Zone.GRAVEYARD, s.obj("clone").zone, "a 0/0 with no copy dies as a state-based action")
    }

    /** Vesuva's own wording adds "tapped" to the copy, and that isn't part of what it copies. */
    @Test
    fun `a land copy that enters tapped stays tapped`() {
        val s = state()
        val island = card("Island", "Basic Land — Island", "", "")
        s.put("island", island, "opp"); s.put("vesuva", vesuva, "me", Zone.HAND)
        val e = Engine(s); e.cast("me", vesuva, emptyList(), "vesuva", choice = "copy:island"); e.resolveAll()
        assertEquals("Island", s.obj("vesuva").def.name)
        assertEquals(true, s.obj("vesuva").tapped)
    }
}
