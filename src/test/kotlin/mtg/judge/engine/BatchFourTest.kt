package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bounce, overload, "can't be regenerated", life equal to power, opponent-draw triggers with "they", draws as events, targeted reveals. */
class BatchFourTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val rift = card("Cyclonic Rift", "Instant", "Return target nonland permanent you don't control to its owner's hand.\nOverload {6}{U}", "{1}{U}", "U", null, null, "Overload")
    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val ring = card("Sol Ring", "Artifact", "{T}: Add {C}{C}.", "{1}")
    private val wrath = card("Wrath of God", "Sorcery", "Destroy all creatures. They can't be regenerated.", "{2}{W}{W}", "W")
    private val skeletons = card("Drudge Skeletons", "Creature — Skeleton", "{B}: Regenerate Drudge Skeletons.", "{1}{B}", "B", "1", "1")
    private val swords = card("Swords to Plowshares", "Instant", "Exile target creature. Its controller gains life equal to its power.", "{W}", "W")
    private val sheoldred = card("Sheoldred, the Apocalypse", "Legendary Creature — Phyrexian Praetor", "Deathtouch\nWhenever you draw a card, you gain 2 life.\nWhenever an opponent draws a card, they lose 2 life.", "{2}{B}{B}", "B", "4", "5", "Deathtouch")
    private val thoughtseize = card("Thoughtseize", "Sorcery", "Target player reveals their hand. You choose a nonland card from it. That player discards that card. You lose 2 life.", "{B}", "B")
    private val brainstorm = card("Brainstorm", "Instant", "Draw three cards, then put two cards from your hand on top of your library in any order.", "{U}", "U")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `bounce targets one permanent, overloaded it bounces every matching one`() {
        assertIs<Effect.Bounce>(rift.spellEffect)
        val s = state(); s.put("bears", bears, "me"); s.put("ring", ring, "me"); s.put("theirs", bears, "opp"); val e = Engine(s)
        e.cast("opp", rift, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.HAND, s.obj("bears").zone); assertEquals(Zone.BATTLEFIELD, s.obj("ring").zone); assertTrue("400.7" in s.cited())
        val s2 = state(); s2.put("bears", bears, "me"); s2.put("ring", ring, "me"); s2.put("theirs", bears, "opp"); val e2 = Engine(s2)
        e2.cast("opp", rift, emptyList(), overload = true); e2.resolveAll()
        assertEquals(Zone.HAND, s2.obj("bears").zone); assertEquals(Zone.HAND, s2.obj("ring").zone); assertEquals(Zone.BATTLEFIELD, s2.obj("theirs").zone, "the caster's own permanent is spared")
        assertTrue("702.96a" in s2.cited()); assertTrue(s2.clarifications.isEmpty())
    }

    @Test
    fun `wrath of god ignores regeneration shields`() {
        val eff = wrath.spellEffect; assertIs<Effect.ForAll>(eff); assertTrue(eff.noRegen)
        val s = state(); s.put("skel", skeletons, "opp"); val e = Engine(s)
        e.activate("opp", "skel", null, emptyList()); e.resolveAll()
        e.cast("me", wrath, emptyList()); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("skel").zone); assertTrue("701.19c" in s.cited())
    }

    @Test
    fun `swords uses the exiled creature's last known power`() {
        val s = state(); s.put("bears", bears, "opp"); s.obj("bears").counters["+1/+1"] = 2; val e = Engine(s)
        e.cast("me", swords, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.EXILE, s.obj("bears").zone); assertEquals(24, s.player("opp").life); assertTrue("608.2h" in s.cited())
    }

    @Test
    fun `opponent draws trigger with "they" is the drawing player, and draws are events`() {
        val s = state(); s.put("sheol", sheoldred, "me"); val e = Engine(s)
        e.draw("opp", 2); e.resolveAll()
        assertEquals(16, s.player("opp").life)
        e.draw("me", 1); e.resolveAll()
        assertEquals(22, s.player("me").life)
    }

    @Test
    fun `thoughtseize targets a player and brainstorm's then-clause is modeled`() {
        assertTrue(!thoughtseize.spellEffect!!.hasUnparsed())
        assertTrue(!brainstorm.spellEffect!!.hasUnparsed())
        val s = state(); val e = Engine(s)
        e.cast("opp", thoughtseize, listOf(Ref.Player("me"))); e.cast("me", brainstorm, emptyList()); e.resolveAll()
        assertEquals(3, s.player("me").drew); assertEquals(18, s.player("opp").life)
        // Nobody said what is in the hand, so the engine asks instead of guessing which card goes.
        assertTrue(s.clarifications.any { it.why.contains("what is in it?") }, s.clarifications.toString())
    }
}
