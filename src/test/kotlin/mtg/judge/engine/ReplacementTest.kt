package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReplacementTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val fog = card("Fog", "Instant", "Prevent all combat damage that would be dealt this turn.", "{G}", "G")
    private val heal = card("Healing Salve", "Instant", "Prevent the next 3 damage that would be dealt to any target this turn.", "{W}", "W")
    private val furnace = card("Furnace of Rath", "Enchantment", "If a source would deal damage to a permanent or player, it deals double that damage to that permanent or player instead.", "{1}{R}{R}{R}", "R")
    private val rip = card("Rest in Peace", "Enchantment", "When Rest in Peace enters, exile all graveyards.\nIf a card or token would be put into a graveyard from anywhere, exile it instead.", "{1}{W}", "W")
    private val wurm = card("Regenerating Wurm", "Creature — Wurm", "{G}: Regenerate Regenerating Wurm.", "{4}{G}", "G", "3", "4")
    private val alhammarret = card("Alhammarret's Archive", "Legendary Artifact", "If you would gain life, you gain twice that much life instead.\nIf you would draw a card except the first one you draw in each of your draw steps, draw two cards instead.", "{5}")
    private val salve2 = card("Chaplain's Blessing", "Sorcery", "You gain 5 life.", "{W}", "W")
    private val candletrap = card("Candletrap", "Enchantment — Aura", "Enchant creature\nPrevent all combat damage that would be dealt by enchanted creature.", "{W}", "W", null, null, "Enchant")
    private val wrath = card("Wrath of God", "Sorcery", "Destroy all creatures. They can't be regenerated.", "{2}{W}{W}", "W")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `fog prevents combat damage but not a bolt`() {
        assertIs<Effect.CreateShield>(fog.spellEffect)
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("opp", fog, emptyList()); e.resolveAll()
        e.declareAttacker("me", "bears", Ref.Player("opp")); e.combatDamage()
        assertEquals(20, s.player("opp").life); assertTrue("615.7" in s.cited() || "615.6" in s.cited())
        e.cast("me", bolt, listOf(Ref.Player("opp"))); e.resolveAll()
        assertEquals(17, s.player("opp").life, "Fog only stops combat damage")
    }

    @Test
    fun `prevent-the-next-3 shield is used up, and prevention is applied before doubling`() {
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("me", heal, listOf(Ref.Obj("bears"))); e.resolveAll()
        e.cast("opp", bolt, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone); assertEquals(0, s.obj("bears").damage)
        e.cast("opp", bolt, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("bears").zone, "the shield had 3 and was spent")
        val s2 = state(); s2.put("furnace", furnace, "opp"); val e2 = Engine(s2)
        e2.cast("me", heal, listOf(Ref.Player("me"))); e2.resolveAll()
        e2.cast("opp", bolt, listOf(Ref.Player("me"))); e2.resolveAll()
        assertEquals(20, s2.player("me").life, "prevent 3 first, then double 0")
        assertTrue("616.1" in s2.cited())
        e2.cast("opp", bolt, listOf(Ref.Player("me"))); e2.resolveAll()
        assertEquals(14, s2.player("me").life, "6 with the doubler")
        assertTrue("614.1a" in s2.cited())
    }

    @Test
    fun `rest in peace exiles instead and dies triggers never happen`() {
        val s = state(); s.put("rip", rip, "opp"); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("opp", bolt, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.EXILE, s.obj("bears").zone); assertTrue("614.6" in s.cited())
    }

    @Test
    fun `regeneration replaces destruction, taps, and is a one-time shield, and wrath forbids it`() {
        val s = state(); s.put("wurm", wurm, "me"); val e = Engine(s)
        e.activate("me", "wurm", null, emptyList()); e.resolveAll()
        e.cast("opp", bolt, listOf(Ref.Obj("wurm"))); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("wurm").zone, "3 damage on a 3/4 isn't lethal; the shield is unused")
        assertEquals(1, s.shields.count { it.replacement == Replacement.Regenerate && (it.remaining ?: 0) > 0 })
    }

    @Test
    fun `regeneration actually saves from lethal`() {
        val s = state(); s.put("wurm", wurm, "me"); val e = Engine(s)
        e.activate("me", "wurm", null, emptyList()); e.resolveAll()
        e.cast("opp", bolt, listOf(Ref.Obj("wurm"))); e.resolveAll(); e.cast("opp", bolt, listOf(Ref.Obj("wurm"))); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("wurm").zone); assertEquals(true, s.obj("wurm").tapped); assertEquals(0, s.obj("wurm").damage)
        assertTrue("701.19a" in s.cited())
        e.cast("opp", bolt, listOf(Ref.Obj("wurm"))); e.resolveAll(); e.cast("opp", bolt, listOf(Ref.Obj("wurm"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("wurm").zone, "the shield was used up")
    }

    @Test
    fun `life gain doubling and a static prevention aura`() {
        val s = state(); s.put("arch", alhammarret, "me"); val e = Engine(s)
        e.cast("me", salve2, emptyList()); e.resolveAll()
        assertEquals(30, s.player("me").life)
        val s2 = state(); s2.put("bears", bears, "opp"); s2.put("trap", candletrap, "me").attachedTo = "bears"; val e2 = Engine(s2)
        e2.declareAttacker("opp", "bears", Ref.Player("me")); e2.combatDamage()
        assertEquals(20, s2.player("me").life); assertTrue("615.1" in s2.cited())
    }
}
