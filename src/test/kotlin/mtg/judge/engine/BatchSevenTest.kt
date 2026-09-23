package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** X-based mass pumps, "that much life", Smothering Tithe's may-pay-or-else, Chaos Warp, the cleanup step, life events. */
class BatchSevenTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val hoof = card("Craterhoof Behemoth", "Creature — Beast", "Haste\nWhen Craterhoof Behemoth enters, creatures you control gain trample and get +X/+X until end of turn, where X is the number of creatures you control.", "{5}{G}{G}{G}", "G", "5", "5", "Haste")
    private val bond = card("Sanguine Bond", "Enchantment", "Whenever you gain life, target opponent loses that much life.", "{3}{B}{B}", "B")
    private val tithe = card("Smothering Tithe", "Enchantment", "Whenever an opponent draws a card, that player may pay {2}. If the player doesn't, you create a Treasure token.", "{3}{W}", "W")
    private val warp = card("Chaos Warp", "Instant", "The owner of target permanent shuffles it into their library, then reveals the top card of their library. If it's a permanent card, they put it onto the battlefield.", "{2}{R}", "R")
    private val ring = card("Sol Ring", "Artifact", "{T}: Add {C}{C}.", "{1}")
    private val growth = card("Giant Growth", "Instant", "Target creature gets +3/+3 until end of turn.", "{G}", "G")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `craterhoof counts creatures as it resolves`() {
        val trig = hoof.abilities.filterIsInstance<TriggeredAbility>().single(); assertIs<Effect.PumpAllCount>(trig.effect)
        val s = state(); s.put("b1", bears, "me"); s.put("b2", bears, "me"); val e = Engine(s)
        e.cast("me", hoof, emptyList()); e.resolveAll()
        assertEquals(5, s.obj("b1").power); assertTrue(s.obj("b1").has("trample")); assertEquals(8, s.objects.values.first { it.def === hoof }.power)
    }

    @Test
    fun `sanguine bond drains that much`() {
        val s = state(); s.put("bond", bond, "me"); val e = Engine(s)
        e.gainLifeEvent("me", 5); e.resolveAll()
        assertEquals(25, s.player("me").life); assertEquals(15, s.player("opp").life)
    }

    @Test
    fun `smothering tithe is paid once, then makes a treasure`() {
        val eff = tithe.abilities.filterIsInstance<TriggeredAbility>().single().effect; assertIs<Effect.UnlessPays>(eff)
        val s = state(); s.put("tithe", tithe, "me"); val e = Engine(s)
        s.willPay += "opp"; e.draw("opp", 2); e.resolveAll()
        assertEquals(1, s.objects.values.count { it.token && it.isOnBattlefield() && it.name == "Treasure token" })
    }

    @Test
    fun `chaos warp shuffles the permanent away`() {
        assertTrue(!warp.spellEffect!!.hasUnparsed()); assertEquals(1, warp.spellEffect!!.targets().size)
        val s = state(); s.put("ring", ring, "opp"); val e = Engine(s)
        e.cast("me", warp, listOf(Ref.Obj("ring"))); e.resolveAll()
        assertEquals(Zone.LIBRARY, s.obj("ring").zone); assertTrue("701.24a" in s.cited())
    }

    @Test
    fun `until end of turn lasts through the end step and ends in cleanup`() {
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("me", growth, listOf(Ref.Obj("bears"))); e.resolveAll(); e.dealDamage("Shock", Ref.Obj("bears"), 2)
        e.beginStep("end", "me"); assertEquals(5, s.obj("bears").power); assertTrue("513.1" in s.cited())
        e.beginStep("cleanup", "me"); assertEquals(2, s.obj("bears").power); assertEquals(0, s.obj("bears").damage); assertTrue("514.2" in s.cited())
    }
}
