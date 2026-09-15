package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exalted and attacking alone, commander damage, Ensnaring Bridge, Pacifism, the Colossus replacement, mass keyword grants. */
class BatchEightTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val rafiq = card("Rafiq of the Many", "Legendary Creature — Human Knight", "Exalted\nWhenever a creature you control attacks alone, it gains double strike until end of turn.", "{1}{G}{W}{U}", "GWU", "3", "3", "Exalted")
    private val bridge = card("Ensnaring Bridge", "Artifact", "Creatures with power greater than the number of cards in your hand can't attack.", "{3}")
    private val pacifism = card("Pacifism", "Enchantment — Aura", "Enchant creature\nEnchanted creature can't attack or block.", "{1}{W}", "W", null, null, "Enchant")
    private val colossus = card("Darksteel Colossus", "Artifact Creature — Golem", "Trample, indestructible\nIf Darksteel Colossus would be put into a graveyard from anywhere, reveal Darksteel Colossus and shuffle it into its owner's library instead.", "{11}", "", "11", "11", "Trample", "Indestructible")
    private val wrath = card("Wrath of God", "Sorcery", "Destroy all creatures. They can't be regenerated.", "{2}{W}{W}", "W")
    private val charm = card("Boros Charm", "Instant", "Choose one —\n• Boros Charm deals 4 damage to target player or planeswalker.\n• Permanents you control gain indestructible until end of turn.\n• Target creature gains double strike until end of turn.", "{R}{W}", "RW")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `exalted fires only for a creature attacking alone, and commander damage is tracked`() {
        val s = state(); s.put("rafiq", rafiq, "me").also { it.commander = true }; s.put("bears", bears, "me"); val e = Engine(s)
        e.beginDeclaringAttackers(); e.declareAttacker("me", "rafiq", Ref.Player("opp")); e.finishDeclaringAttackers(); e.resolveAll()
        assertEquals(4, s.obj("rafiq").power); assertTrue(s.obj("rafiq").has("double strike"))
        e.combatDamage()
        assertEquals(12, s.player("opp").life); assertEquals(8, s.player("opp").commanderDamage["rafiq"]); assertTrue("903.10a" in s.cited())
        val s2 = state(); s2.put("rafiq", rafiq, "me"); s2.put("bears", bears, "me"); val e2 = Engine(s2)
        e2.beginDeclaringAttackers(); e2.declareAttacker("me", "rafiq", Ref.Player("opp")); e2.declareAttacker("me", "bears", Ref.Player("opp")); e2.finishDeclaringAttackers(); e2.resolveAll()
        assertEquals(3, s2.obj("rafiq").power, "two attackers: nobody attacks alone")
    }

    @Test
    fun `ensnaring bridge compares power with the controller's hand`() {
        val s = state(); s.put("bridge", bridge, "opp"); s.put("bears", bears, "me"); s.player("opp").handSize = 0; val e = Engine(s)
        e.declareAttacker("me", "bears", Ref.Player("opp")); assertNull(s.obj("bears").attacking); assertTrue("508.1c" in s.cited())
        s.player("opp").handSize = 3; e.declareAttacker("me", "bears", Ref.Player("opp")); assertEquals(Ref.Player("opp"), s.obj("bears").attacking)
    }

    @Test
    fun `pacifism stops the enchanted creature`() {
        val s = state(); s.put("bears", bears, "me"); s.put("pac", pacifism, "opp").also { it.attachedTo = "bears" }; val e = Engine(s)
        e.declareAttacker("me", "bears", Ref.Player("opp")); assertNull(s.obj("bears").attacking)
        assertTrue(s.trace.steps.any { "Pacifism" in it.text && "can't attack" in it.text })
    }

    @Test
    fun `boros charm's indestructible mode saves the team from wrath`() {
        val s = state(); s.put("bears", bears, "me"); s.put("theirs", bears, "opp"); val e = Engine(s)
        e.cast("opp", wrath, emptyList()); e.cast("me", charm, emptyList(), modes = listOf(2)); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone); assertEquals(Zone.GRAVEYARD, s.obj("theirs").zone); assertTrue(s.obj("bears").has("indestructible"))
    }

    @Test
    fun `darksteel colossus is indestructible and would shuffle away rather than die`() {
        val s = state(); s.put("col", colossus, "opp"); val e = Engine(s)
        e.sacrifice("opp", "col")
        assertEquals(Zone.LIBRARY, s.obj("col").zone); assertTrue("614.6" in s.cited())
    }
}
