package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Token creation, Doubling Season tokens, Panharmonicon, attack taxes, life costs, sacrifice-each, damage to "that player", simultaneous deaths, generic tokens. */
class BatchSixTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val elves = card("Llanowar Elves", "Creature — Elf Druid", "{T}: Add {G}.", "{G}", "G", "1", "1")
    private val ring = card("Sol Ring", "Artifact", "{T}: Add {C}{C}.", "{1}")
    private val beastWithin = card("Beast Within", "Instant", "Destroy target permanent. Its controller creates a 3/3 green Beast creature token.", "{2}{G}", "G")
    private val season = card("Doubling Season", "Enchantment", "If an effect would create one or more tokens under your control, it creates twice that many of those tokens instead.", "{4}{G}", "G")
    private val panharmonicon = card("Panharmonicon", "Artifact", "If an artifact or creature entering causes a triggered ability of a permanent you control to trigger, that ability triggers an additional time.", "{4}")
    private val mulldrifter = card("Mulldrifter", "Creature — Elemental", "Flying\nWhen Mulldrifter enters, draw two cards.", "{4}{U}", "U", "2", "2", "Flying")
    private val propaganda = card("Propaganda", "Enchantment", "Creatures can't attack you unless their controller pays {2} for each creature they control that's attacking you.", "{2}{U}", "U")
    private val reservoir = card("Aetherflux Reservoir", "Artifact", "Pay 50 life: Aetherflux Reservoir deals 50 damage to any target.", "{4}")
    private val gravePact = card("Grave Pact", "Enchantment", "Whenever a creature you control dies, each other player sacrifices a creature of their choice.", "{1}{B}{B}{B}", "B")
    private val murder = card("Murder", "Instant", "Destroy target creature.", "{1}{B}{B}", "B")
    private val vortex = card("Sulfuric Vortex", "Enchantment", "At the beginning of each player's upkeep, Sulfuric Vortex deals 2 damage to that player.\nIf a player would gain life, that player gains no life instead.", "{1}{R}{R}", "R")
    private val zulaport = card("Zulaport Cutthroat", "Creature — Human Rogue Ally", "Whenever Zulaport Cutthroat or another creature you control dies, each opponent loses 1 life and you gain 1 life.", "{1}{B}", "B", "1", "1")
    private val damnation = card("Damnation", "Sorcery", "Destroy all creatures. They can't be regenerated.", "{2}{B}{B}", "B")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `beast within creates a token for the permanent's controller, doubled by doubling season`() {
        assertIs<Effect.Seq>(beastWithin.spellEffect); assertTrue(!beastWithin.spellEffect!!.hasUnparsed())
        val s = state(); s.put("ring", ring, "opp"); val e = Engine(s)
        e.cast("me", beastWithin, listOf(Ref.Obj("ring"))); e.resolveAll()
        val beasts = s.objects.values.filter { it.token && it.isOnBattlefield() }
        assertEquals(1, beasts.size); assertEquals("opp", beasts[0].controller); assertEquals(3, beasts[0].power); assertTrue("701.7a" in s.cited())
        val s2 = state(); s2.put("ring", ring, "me"); s2.put("season", season, "me"); val e2 = Engine(s2)
        e2.cast("me", beastWithin, listOf(Ref.Obj("ring"))); e2.resolveAll()
        assertEquals(2, s2.objects.values.count { it.token && it.isOnBattlefield() })
    }

    @Test
    fun `panharmonicon doubles enters-the-battlefield triggers`() {
        val s = state(); s.put("pan", panharmonicon, "me"); val e = Engine(s)
        e.cast("me", mulldrifter, emptyList()); e.resolveAll()
        assertEquals(4, s.player("me").drew)
    }

    @Test
    fun `propaganda attacks are assumed paid, and refused when the player will not pay`() {
        val s = state(); s.put("prop", propaganda, "opp"); s.put("bears", bears, "me"); val e = Engine(s)
        e.declareAttacker("me", "bears", Ref.Player("opp"))
        assertEquals(Ref.Player("opp"), s.obj("bears").attacking); assertTrue(s.assumptions.any { "{2}" in it }); assertTrue("508.1c" in s.cited())
        val s2 = state(); s2.put("prop", propaganda, "opp"); s2.put("bears", bears, "me"); val e2 = Engine(s2)
        s2.wontPay += "me"; e2.declareAttacker("me", "bears", Ref.Player("opp"))
        assertNull(s2.obj("bears").attacking)
    }

    @Test
    fun `paying life is a cost that needs the life`() {
        val s = state(); s.put("res", reservoir, "me"); s.player("me").life = 51; val e = Engine(s)
        e.activate("me", "res", null, listOf(Ref.Player("opp"))); e.resolveAll()
        assertEquals(1, s.player("me").life); assertEquals(-30, s.player("opp").life); assertTrue(s.player("opp").lost)
        val s2 = state(); s2.put("res", reservoir, "me"); val e2 = Engine(s2)
        assertNull(e2.activate("me", "res", null, listOf(Ref.Player("opp")))); assertTrue("118.3" in s2.cited())
    }

    @Test
    fun `grave pact makes each other player sacrifice`() {
        val s = state(); s.put("pact", gravePact, "opp"); s.put("theirs", bears, "opp"); s.put("elves", elves, "me"); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("me", murder, listOf(Ref.Obj("theirs"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("elves").zone, "the smallest creature is assumed sacrificed"); assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone)
        assertTrue(s.assumptions.any { "sacrifice" in it })
    }

    @Test
    fun `sulfuric vortex hits the upkeep player and stops life gain for everyone`() {
        val s = state(); s.put("vortex", vortex, "opp"); s.put("zul", zulaport, "me"); s.put("bears", bears, "me"); val e = Engine(s)
        e.beginStep("upkeep", "me"); e.resolveAll()
        assertEquals(18, s.player("me").life)
        e.sacrifice("me", "bears"); e.resolveAll()
        assertEquals(18, s.player("me").life, "no life gained under Vortex"); assertEquals(19, s.player("opp").life)
    }

    @Test
    fun `creatures dying together all see each other die`() {
        val s = state(); s.put("zul", zulaport, "me"); s.put("b1", bears, "me"); s.put("b2", bears, "me"); val e = Engine(s)
        e.cast("opp", damnation, emptyList()); e.resolveAll()
        assertEquals(17, s.player("opp").life); assertTrue("603.10a" in s.cited())
    }

    @Test
    fun `generic tokens and creatures are read from their description`() {
        val angel = Generic.token("4/4 Angel token with flying")!!
        assertEquals(4, angel.power); assertTrue(angel.has("flying")); assertTrue(angel.isCreature)
        val treasure = Generic.token("Treasure token")!!
        assertTrue("Artifact" in treasure.types); assertTrue(treasure.abilities.any { it is ActivatedAbility })
        assertNull(Generic.token("Grizzly Bears"))
        assertEquals("Creature", Generic.spell("a creature")!!.types.first())
    }
}
