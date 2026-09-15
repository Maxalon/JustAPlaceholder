package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Engine behaviour on hand-written card definitions (no database needed). */
class EngineTest {
    private fun card(name: String, type: String, text: String, cost: String? = null, p: String? = null, t: String? = null, keywords: List<String> = emptyList()) =
        OracleParser.parse("oid-$name", name, type, cost, 0.0, "", p, t, keywords, text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "2", "2")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}")
    private val growth = card("Giant Growth", "Instant", "Target creature gets +3/+3 until end of turn.", "{G}")
    private val counterspell = card("Counterspell", "Instant", "Counter target spell.", "{U}{U}")
    private val stifle = card("Stifle", "Instant", "Counter target activated or triggered ability.", "{U}")
    private val swords = card("Swords to Plowshares", "Instant", "Exile target creature. Its controller gains life equal to its power.", "{W}")
    private val rhystic = card("Rhystic Study", "Enchantment", "Whenever an opponent casts a spell, you may draw a card unless that player pays {1}.", "{2}{U}")
    private val growthSpell = card("Rampant Growth", "Sorcery", "Search your library for a basic land card, put that card onto the battlefield tapped, then shuffle.", "{1}{G}")
    private val elf = card("Elvish Visionary", "Creature — Elf Shaman", "When Elvish Visionary enters, draw a card.", "{1}{G}", "1", "1")

    private fun state(vararg objs: Pair<GameObject, Unit>): GameState = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.add(id: String, def: CardDef, controller: String) = GameObject(id, def, Zone.BATTLEFIELD, controller).also { objects[id] = it }
    private fun GameState.rulesCited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `bolt kills bears through state-based actions`() {
        val s = state(); s.add("bears", bears, "me"); val e = Engine(s)
        e.cast("opp", bolt, listOf(Ref.Obj("bears")))
        e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("bears").zone)
        assertTrue("704.5g" in s.rulesCited()); assertTrue("120.3e" in s.rulesCited())
    }

    @Test
    fun `giant growth in response saves bears, last in first out`() {
        val s = state(); s.add("bears", bears, "me"); val e = Engine(s)
        e.cast("opp", bolt, listOf(Ref.Obj("bears")))
        e.cast("me", growth, listOf(Ref.Obj("bears")))
        assertEquals(listOf("Lightning Bolt", "Giant Growth"), s.stack.map { it.source.name })
        e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone)
        assertEquals(5, s.obj("bears").toughness); assertEquals(3, s.obj("bears").damage)
        assertTrue("611.2a" in s.rulesCited())
    }

    @Test
    fun `counterspell removes the spell and it never resolves`() {
        val s = state(); s.add("bears", bears, "me"); val e = Engine(s)
        val boltItem = e.cast("opp", bolt, listOf(Ref.Obj("bears")))!!
        e.cast("me", counterspell, listOf(Ref.Stack(boltItem.id)))
        e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone); assertEquals(0, s.obj("bears").damage)
        assertEquals(Zone.GRAVEYARD, boltItem.source.zone)
        assertTrue("701.6a" in s.rulesCited())
    }

    @Test
    fun `spell with only illegal target fizzles`() {
        val s = state(); s.add("bears", bears, "me"); val e = Engine(s)
        e.cast("opp", bolt, listOf(Ref.Obj("bears")))
        e.cast("me", swords, listOf(Ref.Obj("bears")))
        e.resolveAll()
        assertEquals(Zone.EXILE, s.obj("bears").zone)
        assertTrue(s.outcomes.any { it.contains("doesn't resolve") })
        assertTrue("608.2b" in s.rulesCited())
        assertTrue(s.unsupported.none { it.detail.contains("gains life equal to its power") }, "the life gain is modeled now")
    }

    @Test
    fun `rhystic study triggers on opponent spells only and stifle counters one trigger`() {
        val s = state(); s.add("rhystic", rhystic, "me"); s.activePlayer = "opp"; val e = Engine(s)
        e.cast("me", growthSpell, emptyList())            // my own spell: no trigger
        assertEquals(1, s.stack.size)
        e.resolveAll()
        e.cast("opp", growthSpell, emptyList())           // opponent's spell: trigger goes above it
        assertEquals(listOf(StackKind.SPELL, StackKind.TRIGGERED), s.stack.map { it.kind })
        val trigger = s.stack.last()
        e.cast("opp", stifle, listOf(Ref.Stack(trigger.id)))  // Stifle itself triggers Rhystic Study again
        assertEquals(4, s.stack.size)
        e.resolveAll()
        assertEquals(1, s.player("me").drew, "the second trigger resolves first and draws; the first is countered")
        assertTrue(s.outcomes.any { it.contains("countered") })
        assertTrue(s.assumptions.any { it.contains("does not pay {1}") })
        assertTrue("603.2" in s.rulesCited()); assertTrue("603.3" in s.rulesCited()); assertTrue("701.6a" in s.rulesCited())
    }

    @Test
    fun `enters-the-battlefield trigger fires when a creature spell resolves`() {
        val s = state(); val e = Engine(s)
        e.cast("me", elf, emptyList())
        e.resolveTop()
        assertEquals(1, s.stack.size); assertEquals(StackKind.TRIGGERED, s.stack[0].kind)
        assertTrue("603.6a" in s.rulesCited()); assertTrue("608.3a" in s.rulesCited())
        e.resolveTop()
        assertEquals(1, s.player("me").drew)
    }

    @Test
    fun `missing target is asked for when there are several candidates, and defaults to the opponent when only players qualify`() {
        val s = state(); s.add("bears", bears, "me"); val e = Engine(s)
        val item = e.cast("opp", bolt, emptyList())
        assertEquals(null, item)
        assertTrue(s.clarifications.any { it.about.contains("target") })
        val s2 = state(); val e2 = Engine(s2)
        assertTrue(e2.cast("opp", bolt, emptyList()) != null)
        assertTrue(s2.assumptions.any { "assuming the opponent" in it })
    }

    @Test
    fun `apnap order asks whose turn it is when unknown`() {
        val s = state(); s.activePlayer = null
        s.add("rhystic", rhystic, "me"); s.add("rhystic2", rhystic, "opp")
        val e = Engine(s)
        // A third player casts: both Rhystic Studies trigger with different controllers.
        val s3 = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20), Player("p3", "p3", 20)), LinkedHashMap())
        s3.objects["r1"] = GameObject("r1", rhystic, Zone.BATTLEFIELD, "me"); s3.objects["r2"] = GameObject("r2", rhystic, Zone.BATTLEFIELD, "opp")
        Engine(s3).cast("p3", growthSpell, emptyList())
        assertTrue(s3.clarifications.any { it.about == "active player" })
        assertEquals(3, s3.stack.size)
    }
}
