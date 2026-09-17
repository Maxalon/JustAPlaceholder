package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Enters-tapped conditions, blocker restrictions, toxic, control changes, counters on each, mass pumps with keywords, nth-draw triggers, land plays. */
class BatchThreeTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val fortress = card("Glacial Fortress", "Land", "Glacial Fortress enters tapped unless you control a Plains or an Island.\n{T}: Add {W} or {U}.", "")
    private val plains = card("Plains", "Basic Land — Plains", "({T}: Add {W}.)", "")
    private val lydia = card("Lydia Frye", "Legendary Creature — Human", "Lydia Frye can't be blocked by creatures with power 3 or greater.", "{1}{U}", "U", "3", "2")
    private val giant = card("Hill Giant", "Creature — Giant", "", "{3}{R}", "R", "3", "3")
    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val atrocity = card("Tyrranax Atrocity", "Creature — Phyrexian Dinosaur", "Toxic 3", "{4}{G}", "G", "4", "4", "Toxic")
    private val treason = card("Act of Treason", "Sorcery", "Gain control of target creature until end of turn. Untap that creature. It gains haste until end of turn.", "{2}{R}", "R")
    private val boon = card("Titania's Boon", "Instant", "Put a +1/+1 counter on each creature you control.", "{3}{G}", "G")
    private val zariel = card("Rally", "Instant", "Creatures you control get +1/+0 and gain haste until end of turn.", "{R}", "R")
    private val adept = card("Lat-Nam Adept", "Creature — Human Wizard", "Whenever you draw your second card each turn, put a +1/+1 counter on Lat-Nam Adept.", "{1}{U}", "U", "1", "1")
    private val divination = card("Divination", "Sorcery", "Draw two cards.", "{2}{U}", "U")
    private val cathar = card("Steadfast Cathar", "Creature — Human Soldier", "Whenever Steadfast Cathar attacks, it gets +0/+2 until end of turn.", "{1}{W}", "W", "2", "1")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String, tapped: Boolean = false) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl, tapped = tapped))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `enters tapped unless is a condition, and playing a land is a special action`() {
        val st = fortress.abilities.filterIsInstance<StaticAbility>().flatMap { it.effects }.filterIsInstance<StaticEffect.EntersTapped>().single()
        assertIs<Condition.ControlsMatching>(st.unless)
        val s = state(); val e = Engine(s)
        assertNull(e.cast("me", fortress, emptyList()))
        val f1 = s.objects.values.first { it.def === fortress }
        assertEquals(true, f1.tapped); assertTrue("305.1" in s.cited() && "614.12" in s.cited()); assertTrue(s.stack.isEmpty())
        val s2 = state(); s2.put("plains", plains, "me"); val e2 = Engine(s2)
        e2.cast("me", fortress, emptyList())
        assertEquals(false, s2.objects.values.first { it.def === fortress }.tapped)
    }

    @Test
    fun `can't be blocked by creatures with power 3 or greater`() {
        val s = state(); s.put("lydia", lydia, "me"); s.put("giant", giant, "opp"); s.put("bears", bears, "opp"); val e = Engine(s)
        e.declareAttacker("me", "lydia", Ref.Player("opp"))
        e.declareBlocker("opp", "giant", "lydia"); assertNull(s.obj("giant").blocking); assertTrue("509.1b" in s.cited())
        e.declareBlocker("opp", "bears", "lydia"); assertEquals("lydia", s.obj("bears").blocking)
    }

    @Test
    fun `toxic gives poison counters in addition to damage`() {
        val s = state(); s.put("atrocity", atrocity, "opp"); val e = Engine(s)
        e.declareAttacker("opp", "atrocity", Ref.Player("me")); e.combatDamage()
        assertEquals(16, s.player("me").life); assertEquals(3, s.player("me").poison); assertTrue("702.164c" in s.cited())
    }

    @Test
    fun `act of treason takes control, untaps and grants haste`() {
        val s = state(); s.put("giant", giant, "opp", tapped = true); val e = Engine(s)
        e.cast("me", treason, listOf(Ref.Obj("giant"))); e.resolveAll()
        val g = s.obj("giant")
        assertEquals("me", g.controller); assertEquals(false, g.tapped); assertTrue(g.has("haste")); assertTrue("613.1b" in s.cited())
        assertTrue(s.unsupported.isEmpty(), "unsupported: ${s.unsupported}")
    }

    @Test
    fun `counters on each creature you control and a mass pump that grants a keyword`() {
        val s = state(); s.put("bears", bears, "me"); s.put("giant", giant, "opp"); val e = Engine(s)
        e.cast("me", boon, emptyList()); e.resolveAll()
        assertEquals(1, s.obj("bears").counters["+1/+1"]); assertNull(s.obj("giant").counters["+1/+1"])
        e.cast("me", zariel, emptyList()); e.resolveAll()
        assertEquals(4, s.obj("bears").power); assertTrue(s.obj("bears").has("haste")); assertEquals(3, s.obj("giant").power)
    }

    @Test
    fun `second-card-each-turn triggers once, on the second draw`() {
        val s = state(); s.put("adept", adept, "me"); val e = Engine(s)
        e.cast("me", divination, emptyList()); e.resolveAll()
        assertEquals(1, s.obj("adept").counters["+1/+1"])
    }

    @Test
    fun `a self-trigger's "it" is the source`() {
        val s = state(); s.put("cathar", cathar, "me"); val e = Engine(s)
        assertTrue(cathar.abilities.filterIsInstance<TriggeredAbility>().none { it.effect.hasUnparsed() })
        e.declareAttacker("me", "cathar", Ref.Player("opp")); e.resolveAll()
        assertEquals(3, s.obj("cathar").toughness)
    }
}
