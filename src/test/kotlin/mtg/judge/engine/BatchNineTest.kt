package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** X spells, proliferate, targeted mass exile, Gitrog's graveyard trigger, Rancor's return, default player targets. */
class BatchNineTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val blaze = card("Blaze", "Sorcery", "Blaze deals X damage to any target.", "{X}{R}", "R")
    private val atraxa = card("Atraxa, Praetors' Voice", "Legendary Creature — Phyrexian Angel Horror", "Flying, vigilance, deathtouch, lifelink\nAt the beginning of your end step, proliferate.", "{G}{W}{U}{B}", "GWUB", "4", "4", "Flying", "Vigilance", "Deathtouch", "Lifelink")
    private val jace = OracleParser.parse("oid-jace", "Jace Beleren", "Legendary Planeswalker — Jace", "{1}{U}{U}", 3.0, "U", null, null, emptyList(), "+2: Each player draws a card.", "3")
    private val settle = card("Settle the Wreckage", "Instant", "Exile all attacking creatures target player controls. That player may search their library for that many basic land cards, put those cards onto the battlefield tapped, then shuffle.", "{2}{W}{W}", "W")
    private val gitrog = card("The Gitrog Monster", "Legendary Creature — Frog Horror", "Deathtouch\nWhenever one or more land cards are put into your graveyard from anywhere, draw a card.", "{3}{B}{G}", "BG", "6", "6", "Deathtouch")
    private val forest = card("Forest", "Basic Land — Forest", "({T}: Add {G}.)", "")
    private val rancor = card("Rancor", "Enchantment — Aura", "Enchant creature\nEnchanted creature gets +2/+0 and has trample.\nWhen Rancor is put into a graveyard from the battlefield, return Rancor to its owner's hand.", "{G}", "G", null, null, "Enchant")
    private val doomBlade = card("Doom Blade", "Instant", "Destroy target nonblack creature.", "{1}{B}", "B")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `x is chosen when the spell is cast`() {
        val d = assertIs<Effect.Damage>(blaze.spellEffect); assertTrue(d.x)
        val s = state(); val e = Engine(s)
        e.cast("me", blaze, listOf(Ref.Player("opp")), x = 5); e.resolveAll()
        assertEquals(15, s.player("opp").life); assertTrue("107.3a" in s.cited())
        val s2 = state(); val e2 = Engine(s2)
        e2.cast("me", blaze, listOf(Ref.Player("opp"))); e2.resolveAll()
        assertTrue(s2.clarifications.any { "X" in it.about })
    }

    @Test
    fun `atraxa proliferates loyalty at the end step`() {
        val s = state(); s.put("atraxa", atraxa, "me"); s.put("jace", jace, "me"); s.obj("jace").counters["loyalty"] = 3; s.player("opp").poison = 1; val e = Engine(s)
        e.beginStep("end", "me"); e.resolveAll()
        assertEquals(4, s.obj("jace").counters["loyalty"]); assertEquals(2, s.player("opp").poison); assertTrue("701.34a" in s.cited())
    }

    @Test
    fun `settle the wreckage exiles the target player's attackers`() {
        val s = state(); s.put("b1", bears, "me"); s.put("b2", bears, "me"); s.put("theirs", bears, "opp"); val e = Engine(s)
        e.declareAttacker("me", "b1", Ref.Player("opp")); e.declareAttacker("me", "b2", Ref.Player("opp"))
        e.cast("opp", settle, emptyList()); e.resolveAll()
        assertEquals(Zone.EXILE, s.obj("b1").zone); assertEquals(Zone.EXILE, s.obj("b2").zone); assertEquals(Zone.BATTLEFIELD, s.obj("theirs").zone)
        assertTrue(s.assumptions.any { "assuming the opponent" in it })
    }

    @Test
    fun `gitrog draws when a land goes to the graveyard, and rancor comes back`() {
        val s = state(); s.put("gitrog", gitrog, "me"); s.put("forest", forest, "me"); val e = Engine(s)
        e.sacrifice("me", "forest"); e.resolveAll()
        assertEquals(1, s.player("me").drew)
        val s2 = state(); s2.put("bears", bears, "me"); val e2 = Engine(s2)
        e2.cast("me", rancor, listOf(Ref.Obj("bears"))); e2.resolveAll()
        e2.cast("opp", doomBlade, listOf(Ref.Obj("bears"))); e2.resolveAll()
        assertEquals(Zone.HAND, s2.objects.values.first { it.def === rancor }.zone)
    }

    @Test
    fun `an unnamed any-target defaults to the one opponent`() {
        val s = state(); val e = Engine(s)
        e.cast("me", bolt, emptyList()); e.resolveAll()
        assertEquals(17, s.player("opp").life); assertTrue(s.clarifications.isEmpty())
    }
}
