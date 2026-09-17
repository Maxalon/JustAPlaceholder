package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Player hexproof, Platinum Angel, Blood Moon, Mother of Runes' colour choice, intrinsic land mana. */
class BatchTwelveTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val leyline = card("Leyline of Sanctity", "Enchantment", "If Leyline of Sanctity is in your opening hand, you may begin the game with it on the battlefield.\nYou have hexproof.", "{2}{W}{W}", "W")
    private val thoughtseize = card("Thoughtseize", "Sorcery", "Target player reveals their hand. You choose a nonland card from it. That player discards that card. You lose 2 life.", "{B}", "B")
    private val platinum = card("Platinum Angel", "Artifact Creature — Angel", "Flying\nYou can't lose the game and your opponents can't win the game.", "{7}", "", "4", "4", "Flying")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val moon = card("Blood Moon", "Enchantment", "Nonbasic lands are Mountains.", "{2}{R}", "R")
    private val tropical = card("Tropical Island", "Land — Forest Island", "({T}: Add {G} or {U}.)", "")
    private val mother = card("Mother of Runes", "Creature — Human Cleric", "{T}: Target creature you control gains protection from the color of your choice until end of turn.", "{W}", "W", "1", "1")
    private val doomBlade = card("Doom Blade", "Instant", "Destroy target nonblack creature.", "{1}{B}", "B")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `leyline of sanctity gives its controller hexproof`() {
        val s = state(); s.put("leyline", leyline, "me"); val e = Engine(s)
        assertNull(e.cast("opp", thoughtseize, listOf(Ref.Player("me")))); assertTrue("702.11c" in s.cited())
        assertTrue(e.cast("me", bolt, listOf(Ref.Player("me"))) != null, "your own spells may target you")
    }

    @Test
    fun `platinum angel keeps its controller in the game`() {
        val s = state(); s.put("angel", platinum, "opp"); s.player("opp").life = 2; val e = Engine(s)
        e.cast("me", bolt, listOf(Ref.Player("opp"))); e.resolveAll()
        assertEquals(-1, s.player("opp").life); assertTrue(!s.player("opp").lost)
        e.cast("me", doomBlade, listOf(Ref.Obj("angel"))); e.resolveAll()
        assertTrue(s.player("opp").lost, "once the Angel is gone the state-based action applies")
    }

    @Test
    fun `blood moon turns a dual land into a mountain`() {
        assertEquals(2, tropical.abilities.filterIsInstance<ActivatedAbility>().size, "intrinsic Forest and Island mana abilities")
        val s = state(); s.put("trop", tropical, "me"); s.put("moon", moon, "opp"); val e = Engine(s)
        e.activate("me", "trop", null, emptyList())
        assertTrue(s.outcomes.any { "{R}" in it && "only" in it }); assertTrue("305.7" in s.cited()); assertEquals(true, s.obj("trop").tapped)
    }

    @Test
    fun `mother of runes protects from the chosen colour`() {
        val s = state(); s.put("mom", mother, "me"); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("opp", doomBlade, listOf(Ref.Obj("bears")))
        e.activate("me", "mom", null, listOf(Ref.Obj("bears")), choice = "black"); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone); assertTrue(s.outcomes.any { "doesn't resolve" in it })
    }
}
