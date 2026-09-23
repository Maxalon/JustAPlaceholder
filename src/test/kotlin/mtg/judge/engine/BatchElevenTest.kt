package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Harmful targets prefer opponents, protection from colored spells, Mind Control, the untap step. */
class BatchElevenTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val ring = card("Sol Ring", "Artifact", "{T}: Add {C}{C}.", "{1}")
    private val shards = card("Aura Shards", "Enchantment", "Whenever a creature you control enters, you may destroy target artifact or enchantment.", "{1}{G}{W}", "GW")
    private val emrakul = card("Emrakul, the Aeons Torn", "Legendary Creature — Eldrazi", "Protection from colored spells\nFlying, annihilator 6", "{15}", "", "15", "15", "Protection", "Flying", "Annihilator")
    private val swords = card("Swords to Plowshares", "Instant", "Exile target creature. Its controller gains life equal to its power.", "{W}", "W")
    private val mindControl = card("Mind Control", "Enchantment — Aura", "Enchant creature\nYou control enchanted creature.", "{3}{U}{U}", "U", null, null, "Enchant")
    private val angel = card("Serra Angel", "Creature — Angel", "Flying, vigilance", "{3}{W}{W}", "W", "4", "4", "Flying", "Vigilance")
    private val naturalize = card("Naturalize", "Instant", "Destroy target artifact or enchantment.", "{1}{G}", "G")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `a destroy trigger with several legal targets picks the opponent's`() {
        val s = state(); s.put("shards", shards, "me"); s.put("ring", ring, "opp"); val e = Engine(s)
        e.cast("me", bears, emptyList()); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("ring").zone); assertEquals(Zone.BATTLEFIELD, s.obj("shards").zone); assertTrue(s.clarifications.isEmpty())
    }

    @Test
    fun `protection from colored spells stops swords`() {
        val s = state(); s.put("emrakul", emrakul, "opp"); val e = Engine(s)
        assertNull(e.cast("me", swords, listOf(Ref.Obj("emrakul")))); assertTrue("702.16b" in s.cited())
    }

    @Test
    fun `mind control takes the creature, the next untap step lets it attack, and losing the aura gives it back`() {
        val s = state(); s.put("angel", angel, "opp"); val e = Engine(s)
        e.cast("me", mindControl, listOf(Ref.Obj("angel"))); e.resolveAll()
        assertEquals("me", s.obj("angel").controller); assertTrue("613.1b" in s.cited())
        e.declareAttacker("me", "angel", Ref.Player("opp")); assertNull(s.obj("angel").attacking, "summoning sick under its new controller")
        e.beginStep("untap", "me"); e.declareAttacker("me", "angel", Ref.Player("opp")); assertEquals(Ref.Player("opp"), s.obj("angel").attacking); assertTrue("502.3" in s.cited())
        e.cast("opp", naturalize, listOf(Ref.Obj(s.objects.values.first { it.def === mindControl }.id))); e.resolveAll()
        assertEquals("opp", s.obj("angel").controller); assertTrue("611.2b" in s.cited())
    }
}
