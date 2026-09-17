package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val rancor = card("Rancor", "Enchantment — Aura", "Enchant creature\nEnchanted creature gets +2/+0 and has trample.\nWhen Rancor is put into a graveyard from the battlefield, return Rancor to its owner's hand.", "{G}", "G", null, null, "Enchant")
    private val sword = card("Bonesplitter", "Artifact — Equipment", "Equipped creature gets +2/+0.\nEquip {1}", "{1}", "", null, null, "Equip")
    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val swords = card("Swords to Plowshares", "Instant", "Exile target creature. Its controller gains life equal to its power.", "{W}", "W")
    private val mage = card("Monastery Swiftspear", "Creature — Human Monk", "Haste\nProwess", "{R}", "R", "1", "2", "Haste", "Prowess")
    private val mirror = card("Minion Reflector", "Artifact", "Whenever a nontoken creature enters the battlefield under your control, you may pay {2}. If you do, create a token that's a copy of that creature, except it has haste.", "{5}")
    private val spine = card("Spine of Ish Sah", "Artifact", "When Spine of Ish Sah enters, destroy target permanent.\nWhen Spine of Ish Sah is put into a graveyard from the battlefield, return Spine of Ish Sah to its owner's hand.", "{7}")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `aura spell targets, enters attached, pumps the enchanted creature, and falls off`() {
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        assertTrue(rancor.isAura); assertEquals(setOf(Kind.CREATURE), rancor.enchant!!.kinds)
        e.cast("me", rancor, emptyList())          // single legal target inferred
        e.resolveAll()
        val r = s.objects.values.first { it.name == "Rancor" }
        assertEquals("bears", r.attachedTo); assertEquals(4, s.obj("bears").power); assertTrue(s.obj("bears").has("trample"))
        assertTrue("303.4a" in s.cited() && "608.3b" in s.cited())
        e.cast("opp", swords, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertTrue("704.5m" in s.cited(), "Aura goes to the graveyard when its creature leaves (704.5m)")
        assertEquals(Zone.HAND, r.zone, "and Rancor's own trigger then returns it to hand")
        assertTrue("704.5m" in s.cited())
        assertTrue(s.trace.steps.any { it.text.startsWith("Rancor's ability triggers on Rancor dying") }, "Rancor's dies trigger (put into a graveyard from the battlefield) fired: " + s.trace.steps.map { it.text })
    }

    @Test
    fun `equip attaches at sorcery speed and equipment stays when the creature dies`() {
        val s = state(); s.put("bears", bears, "me"); s.put("sw", sword, "me"); val e = Engine(s)
        e.activate("me", "sw", null, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals("bears", s.obj("sw").attachedTo); assertEquals(4, s.obj("bears").power); assertTrue("702.6a" in s.cited())
        e.cast("opp", bolt, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("bears").zone); assertEquals(Zone.BATTLEFIELD, s.obj("sw").zone); assertNull(s.obj("sw").attachedTo)
        assertTrue("704.5n" in s.cited())
    }

    @Test
    fun `prowess is a trigger, and if-you-do effects are modeled with an assumption`() {
        val s = state(); s.put("m", mage, "me"); s.put("refl", mirror, "me"); val e = Engine(s)
        e.cast("me", bolt, listOf(Ref.Player("opp")))
        assertEquals(StackKind.TRIGGERED, s.stack.last().kind)
        e.resolveAll(); assertEquals(2, s.obj("m").power)
        e.cast("me", bears, emptyList()); e.resolveAll()
        assertTrue(s.assumptions.any { it.contains("pay {2}") }, s.assumptions.toString())
        assertTrue(s.trace.steps.any { it.text.contains("create a token", ignoreCase = true) })
    }

    @Test
    fun `noncreature permanents die too`() {
        val s = state(); s.put("spine", spine, "me"); val e = Engine(s)
        e.leave("spine", Zone.GRAVEYARD)
        assertTrue(s.stack.any { it.kind == StackKind.TRIGGERED }, "700.4: dies means put into a graveyard from the battlefield, for any permanent")
    }
}
