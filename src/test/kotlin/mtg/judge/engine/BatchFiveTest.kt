package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sacrifice, the legend rule, split second, Torpor Orb, counter doubling, colour filters, Blood Artist. */
class BatchFiveTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val artist = card("Blood Artist", "Creature — Vampire", "Whenever Blood Artist or another creature dies, target player loses 1 life and you gain 1 life.", "{1}{B}", "B", "0", "1")
    private val sheoldred = card("Sheoldred, the Apocalypse", "Legendary Creature — Phyrexian Praetor", "Deathtouch", "{2}{B}{B}", "B", "4", "5", "Deathtouch")
    private val grip = card("Krosan Grip", "Instant", "Split second\nDestroy target artifact or enchantment.", "{2}{G}", "G", null, null, "Split second")
    private val ring = card("Sol Ring", "Artifact", "{T}: Add {C}{C}.", "{1}")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val orb = card("Torpor Orb", "Artifact", "Creatures entering don't cause abilities to trigger.", "{2}")
    private val solemn = card("Solemn Simulacrum", "Artifact Creature — Golem", "When Solemn Simulacrum enters, you may search your library for a basic land card, put that card onto the battlefield tapped, then shuffle.", "{4}", "", "2", "2")
    private val season = card("Doubling Season", "Enchantment", "If an effect would create one or more tokens under your control, it creates twice that many of those tokens instead.\nIf an effect would put one or more counters on a permanent you control, it puts twice that many of those counters on that permanent instead.", "{4}{G}", "G")
    private val jace = OracleParser.parse("oid-jace", "Jace Beleren", "Legendary Planeswalker — Jace", "{1}{U}{U}", 3.0, "U", null, null, emptyList(), "+2: Each player draws a card.", "3")
    private val doomBlade = card("Doom Blade", "Instant", "Destroy target nonblack creature.", "{1}{B}", "B")
    private val blackKnight = card("Black Knight", "Creature — Human Knight", "First strike\nProtection from white", "{B}{B}", "B", "2", "2", "First strike", "Protection")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `sacrificing a creature triggers blood artist, targeting the opponent by assumption`() {
        val s = state(); s.put("bears", bears, "me"); s.put("artist", artist, "me"); val e = Engine(s)
        e.sacrifice("me", "bears"); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("bears").zone); assertTrue("701.21a" in s.cited())
        assertEquals(19, s.player("opp").life); assertEquals(21, s.player("me").life)
        assertTrue(s.assumptions.any { "target player" in it })
    }

    @Test
    fun `the legend rule keeps the newer copy`() {
        val s = state(); s.put("one", sheoldred, "me"); val e = Engine(s)
        e.cast("me", sheoldred, emptyList()); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("one").zone); assertTrue("704.5j" in s.cited())
        assertEquals(1, s.objects.values.count { it.def === sheoldred && it.isOnBattlefield() })
    }

    @Test
    fun `split second stops responses`() {
        val s = state(); s.put("ring", ring, "me"); val e = Engine(s)
        e.cast("opp", grip, listOf(Ref.Obj("ring")))
        assertNull(e.cast("me", bolt, listOf(Ref.Player("opp")))); assertTrue("702.61a" in s.cited())
        assertNull(e.activate("me", "ring", null, emptyList()).also { }, "mana abilities are allowed and don't use the stack")
        e.resolveAll(); assertEquals(Zone.GRAVEYARD, s.obj("ring").zone)
    }

    @Test
    fun `torpor orb mutes enters-the-battlefield triggers of creatures`() {
        val s = state(); s.put("orb", orb, "me"); val e = Engine(s)
        e.cast("me", solemn, emptyList()); e.resolveAll()
        assertTrue(s.stack.isEmpty()); assertTrue(s.assumptions.none { "search" in it }); assertTrue("603.6" in s.cited())
    }

    @Test
    fun `doubling season doubles loyalty on entering and counters placed`() {
        val s = state(); s.put("season", season, "me"); val e = Engine(s)
        e.cast("me", jace, emptyList()); e.resolveAll()
        assertEquals(6, s.objects.values.first { it.def === jace }.counters["loyalty"]); assertTrue("614.1a" in s.cited())
    }

    @Test
    fun `colour words in filters`() {
        val spec = doomBlade.spellEffect!!.targets().single()
        assertTrue(spec.filter.verifiable); assertEquals(setOf('B'), spec.filter.notColors)
        val s = state(); s.put("knight", blackKnight, "opp"); s.put("bears", bears, "opp"); val e = Engine(s)
        // An illegal target is noted at casting and the spell fizzles on resolution (608.2b); the Knight survives.
        e.cast("me", doomBlade, listOf(Ref.Obj("knight"))); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("knight").zone); assertTrue(s.outcomes.any { "doesn't resolve" in it })
        e.cast("me", doomBlade, listOf(Ref.Obj("bears"))); e.resolveAll(); assertEquals(Zone.GRAVEYARD, s.obj("bears").zone)
    }
}
