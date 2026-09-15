package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Evoke, Fling's sacrificed power, Phyrexian Obliterator, empty-library draws, Aether Vial, "target player" activations. */
class BatchThirteenTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, cost.count { it in "WUBRGC" } + (Regex("""\{(\d+)\}""").find(cost)?.groupValues?.get(1)?.toDouble() ?: 0.0), colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val forest = card("Forest", "Basic Land — Forest", "", "")
    private val mulldrifter = card("Mulldrifter", "Creature — Elemental", "Flying\nWhen Mulldrifter enters, draw two cards.\nEvoke {2}{U}", "{4}{U}", "U", "2", "2", "Flying", "Evoke")
    private val torporOrb = card("Torpor Orb", "Artifact", "Creatures entering the battlefield don't cause abilities to trigger.", "{2}")
    private val fling = card("Fling", "Instant", "As an additional cost to cast this spell, sacrifice a creature.\nFling deals damage equal to the sacrificed creature's power to any target.", "{1}{R}", "R")
    private val obliterator = card("Phyrexian Obliterator", "Creature — Phyrexian Horror", "Trample\nWhenever a source deals damage to Phyrexian Obliterator, that source's controller sacrifices that many permanents.", "{B}{B}{B}{B}", "B", "5", "5", "Trample")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val brainstorm = card("Brainstorm", "Instant", "Draw three cards, then put two cards from your hand on top of your library in any order.", "{U}", "U")
    private val platinum = card("Platinum Angel", "Artifact Creature — Angel", "Flying\nYou can't lose the game and your opponents can't win the game.", "{7}", "", "4", "4", "Flying")
    private val vial = card("Aether Vial", "Artifact", "At the beginning of your upkeep, you may put a charge counter on Aether Vial.\n{T}: You may put a creature card with mana value equal to the number of charge counters on Aether Vial from your hand onto the battlefield.", "{1}")
    private val jace = card("Jace Beleren", "Legendary Planeswalker — Jace", "+2: Each player draws a card.\n−1: Target player draws a card.\n−10: Target player mills twenty cards.", "{1}{U}{U}", "U")
    private val sakura = card("Sakura-Tribe Elder", "Creature — Snake Shaman", "Sacrifice Sakura-Tribe Elder: Search your library for a basic land card, put that card onto the battlefield tapped, then shuffle.", "{1}{G}", "G", "1", "1")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String, zone: Zone = Zone.BATTLEFIELD) = add(GameObject(id, def, zone, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `evoke sacrifices the creature by its own enters trigger, which torpor orb silences`() {
        val s = state(); val e = Engine(s)
        e.cast("me", mulldrifter, emptyList(), evoked = true); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.objects.values.first { it.name == "Mulldrifter" }.zone)
        assertEquals(2, s.player("me").drew, "the draw trigger still resolves")
        assertTrue("702.74a" in s.cited() && "701.21a" in s.cited())

        val s2 = state(); s2.put("orb", torporOrb, "opp"); val e2 = Engine(s2)
        e2.cast("me", mulldrifter, emptyList(), evoked = true); e2.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s2.objects.values.first { it.name == "Mulldrifter" }.zone, "no enters trigger, no sacrifice")
        assertEquals(0, s2.player("me").drew)
    }

    @Test
    fun `evoke on a card without evoke is just a normal cast with a clarification`() {
        val s = state(); val e = Engine(s)
        e.cast("me", bears, emptyList(), evoked = true); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.objects.values.first().zone); assertTrue(s.clarifications.any { "evoke" in it.about })
    }

    @Test
    fun `fling deals damage equal to the sacrificed creature's last known power`() {
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        e.sacrifice("me", "bears")
        e.cast("me", fling, listOf(Ref.Player("opp"))); e.resolveAll()
        assertEquals(18, s.player("opp").life); assertTrue("608.2h" in s.cited())
        val s2 = state(); val e2 = Engine(s2)
        e2.cast("me", fling, listOf(Ref.Player("opp"))); e2.resolveAll()
        assertEquals(20, s2.player("opp").life); assertTrue(s2.clarifications.any { "sacrifice" in it.about })
    }

    @Test
    fun `phyrexian obliterator makes the damage source's controller sacrifice that many permanents`() {
        val s = state(); s.put("obl", obliterator, "opp"); s.put("f1", forest, "me"); s.put("f2", forest, "me"); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("me", bolt, listOf(Ref.Obj("obl"))); e.resolveAll()
        assertEquals(3, s.obj("obl").damage)
        assertEquals(setOf(Zone.GRAVEYARD), listOf("f1", "f2", "bears").map { s.obj(it).zone }.toSet(), "3 damage: all three permanents go")
        assertTrue(s.assumptions.none { "least valuable" in it }, "no choice when everything must go")
    }

    @Test
    fun `sacrifice this as an effect is a real sacrifice`() {
        val s = state(); s.put("elder", sakura, "me"); val e = Engine(s)
        val ability = sakura.abilities.filterIsInstance<ActivatedAbility>().first()
        assertTrue(ability.cost.contains("Sacrifice", true))
        assertEquals(Effect.SacrificeSource, OracleParser.parseEffect("Sacrifice ~."))
    }

    @Test
    fun `drawing from an empty library loses the game unless platinum angel says otherwise`() {
        val s = state(); s.player("me").librarySize = 1; val e = Engine(s)
        e.cast("me", brainstorm, emptyList()); e.resolveAll()
        assertTrue(s.player("me").lost); assertTrue("704.5b" in s.cited() && "121.4" in s.cited())
        assertEquals(1, s.player("me").drew, "the one card there was gets drawn")

        val s2 = state(); s2.player("me").librarySize = 0; s2.put("angel", platinum, "me"); val e2 = Engine(s2)
        e2.cast("me", brainstorm, emptyList()); e2.resolveAll()
        assertTrue(!s2.player("me").lost); assertTrue(s2.outcomes.any { "Platinum Angel keeps" in it })
    }

    @Test
    fun `library size is tracked across draws`() {
        val s = state(); s.player("me").librarySize = 5; val e = Engine(s)
        e.draw("me", 3); assertEquals(2, s.player("me").librarySize); assertTrue(!s.player("me").lost)
        e.draw("me", 3); assertEquals(0, s.player("me").librarySize); assertTrue(s.player("me").lost)
    }

    @Test
    fun `aether vial puts a creature with matching mana value onto the battlefield`() {
        val s = state(); s.put("vial", vial, "me").counters["charge"] = 2; s.put("bears", bears, "me", Zone.HAND); val e = Engine(s)
        e.activate("me", "vial", null, emptyList(), choice = "bears"); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone); assertTrue("202.3" in s.cited())

        val s2 = state(); s2.put("vial", vial, "me").counters["charge"] = 3; s2.put("bears", bears, "me", Zone.HAND); val e2 = Engine(s2)
        e2.activate("me", "vial", null, emptyList(), choice = "bears"); e2.resolveAll()
        assertEquals(Zone.HAND, s2.obj("bears").zone); assertTrue(s2.outcomes.any { "stays in hand" in it })
    }

    @Test
    fun `a player target for a target-player draw is accepted`() {
        val s = state(); s.put("jace", jace, "me").counters["loyalty"] = 3; val e = Engine(s)
        val idx = jace.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { it.cost.replace('−', '-') == "-1" }
        e.activate("me", "jace", idx, listOf(Ref.Player("opp"))); e.resolveAll()
        assertEquals(1, s.player("opp").drew); assertTrue(s.clarifications.isEmpty(), "clarifications: ${s.clarifications}")
    }
}
