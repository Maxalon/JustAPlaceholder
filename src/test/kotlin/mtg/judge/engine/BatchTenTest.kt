package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Enters-the-battlefield targets named on the creature spell, kicker, "you may gain 1 life". */
class BatchTenTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val chupacabra = card("Ravenous Chupacabra", "Creature — Beast Horror", "When Ravenous Chupacabra enters, destroy target creature an opponent controls.", "{2}{B}{B}", "B", "2", "2")
    private val burst = card("Burst Lightning", "Instant", "Kicker {4}\nBurst Lightning deals 2 damage to any target. If Burst Lightning was kicked, it deals 4 damage instead.", "{R}", "R", null, null, "Kicker")
    private val angel = card("Serra Angel", "Creature — Angel", "Flying, vigilance", "{3}{W}{W}", "W", "4", "4", "Flying", "Vigilance")
    private val firewalker = card("Kor Firewalker", "Creature — Kor Soldier", "Protection from red\nWhenever a player casts a red spell, you may gain 1 life.", "{W}{W}", "W", "2", "2", "Protection")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `targets named on a creature spell go to its enters trigger`() {
        val s = state(); s.put("b1", bears, "opp"); s.put("b2", bears, "opp"); val e = Engine(s)
        e.cast("me", chupacabra, listOf(Ref.Obj("b2"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("b2").zone); assertEquals(Zone.BATTLEFIELD, s.obj("b1").zone)
        assertTrue(s.clarifications.isEmpty()); assertTrue("603.3d" in s.cited())
    }

    @Test
    fun `a kicked burst lightning deals 4`() {
        val d = assertIs<Effect.Damage>(burst.spellEffect); assertEquals(4, d.kickedAmount)
        val s = state(); s.put("angel", angel, "opp"); val e = Engine(s)
        e.cast("me", burst, listOf(Ref.Obj("angel"))); e.resolveAll(); assertEquals(Zone.BATTLEFIELD, s.obj("angel").zone)
        e.cast("me", burst, listOf(Ref.Obj("angel")), kicked = true); e.resolveAll(); assertEquals(Zone.GRAVEYARD, s.obj("angel").zone); assertTrue("702.33d" in s.cited())
    }

    @Test
    fun `you may gain 1 life is read as an optional life gain`() {
        val eff = firewalker.abilities.filterIsInstance<TriggeredAbility>().single().effect
        assertIs<Effect.May>(eff); assertIs<Effect.GainLife>(eff.effect)
    }
}
