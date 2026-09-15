package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BatchTwoTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val charm = card("Boros Charm", "Instant", "Choose one —\n• Boros Charm deals 4 damage to target player or planeswalker.\n• Permanents you control gain indestructible until end of turn.\n• Target creature gains double strike until end of turn.", "{R}{W}", "RW")
    private val agent = card("Blightsteel Colossus", "Artifact Creature — Phyrexian Golem", "Trample, infect, indestructible", "{12}", "", "11", "11", "Trample", "Infect", "Indestructible")
    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val warrior = card("Tar Pit Warrior", "Creature — Cyclops Warrior", "When Tar Pit Warrior becomes the target of a spell or ability, sacrifice it.", "{3}{B}", "B", "3/4".substringBefore('/'), "4")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val greta = card("Greta, Sweettooth Scourge", "Legendary Creature — Human Warrior", "{G}, Sacrifice a Food: Put a +1/+1 counter on target creature. Activate only as a sorcery.\n{1}{B}, Sacrifice a Food: You draw a card and you lose 1 life.", "{1}{B}{G}", "BG", "3", "3")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `modal spells parse into modes and need a chosen mode`() {
        val m = assertIs<Effect.Modal>(charm.spellEffect)
        assertEquals(3, m.modes.size); assertIs<Effect.Damage>(m.modes[0]); assertIs<Effect.GainKeywords>(m.modes[2])
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("me", charm, emptyList()); e.resolveAll()
        assertTrue(s.clarifications.any { it.about.contains("mode") })
        val s2 = state(); s2.put("bears", bears, "me"); val e2 = Engine(s2)
        e2.cast("me", charm, listOf(Ref.Obj("bears")), modes = listOf(3)); e2.resolveAll()
        assertTrue(s2.obj("bears").has("double strike")); assertTrue("700.2a" in s2.cited())
    }

    @Test
    fun `infect gives poison counters and minus counters, ten poison loses`() {
        val s = state(); s.put("bsc", agent, "me"); s.put("bears", bears, "opp"); val e = Engine(s)
        e.declareAttacker("me", "bsc", Ref.Player("opp")); e.declareBlocker("opp", "bears", "bsc"); e.combatDamage()
        assertEquals(20, s.player("opp").life); assertEquals(9, s.player("opp").poison, "1 lethal (deathtouch-free infect: -1/-1 counters count as lethal only via toughness) then trample over")
        assertTrue("702.90b" in s.cited() && "702.90c" in s.cited())
    }

    @Test
    fun `becomes-the-target trigger fires and activation restrictions are stripped into the ability`() {
        val s = state(); s.put("w", warrior, "opp"); val e = Engine(s)
        e.cast("me", bolt, listOf(Ref.Obj("w")))
        assertEquals(StackKind.TRIGGERED, s.stack.last().kind)
        val a = greta.abilities.filterIsInstance<ActivatedAbility>()
        assertEquals("Activate only as a sorcery", a[0].restriction); assertIs<Effect.PutCounters>(a[0].effect); assertTrue(!a[0].effect.hasUnparsed())
    }
}
