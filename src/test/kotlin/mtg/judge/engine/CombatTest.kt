package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombatTest {
    private fun creature(name: String, p: Int, t: Int, vararg keywords: String) =
        OracleParser.parse("oid-$name", name, "Creature — Test", "{1}", 1.0, "", p.toString(), t.toString(), keywords.toList(), keywords.joinToString(", "))

    private fun state(): GameState = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.add(id: String, def: CardDef, controller: String, sick: Boolean? = false) = GameObject(id, def, Zone.BATTLEFIELD, controller, summoningSick = sick).also { objects[id] = it }
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `unblocked attacker deals damage to the defending player and taps`() {
        val s = state(); s.add("a", creature("Bear", 2, 2), "me"); val e = Engine(s)
        e.declareAttacker("me", "a", Ref.Player("opp")); e.combatDamage()
        assertEquals(18, s.player("opp").life); assertEquals(true, s.obj("a").tapped)
        assertTrue("508.1f" in s.cited() && "510.1b" in s.cited() && "120.3a" in s.cited())
    }

    @Test
    fun `mutual lethal damage kills both`() {
        val s = state(); s.add("a", creature("Bear", 2, 2), "me"); s.add("b", creature("Wolf", 2, 2), "opp"); val e = Engine(s)
        e.declareAttacker("me", "a", Ref.Player("opp")); e.declareBlocker("opp", "b", "a"); e.combatDamage()
        assertEquals(Zone.GRAVEYARD, s.obj("a").zone); assertEquals(Zone.GRAVEYARD, s.obj("b").zone); assertEquals(20, s.player("opp").life)
        assertTrue("510.2" in s.cited() && "704.5g" in s.cited())
    }

    @Test
    fun `trample assigns lethal to the blocker and the rest to the player`() {
        val s = state(); s.add("a", creature("Rhino", 4, 4, "Trample"), "me"); s.add("b", creature("Chump", 1, 1), "opp"); val e = Engine(s)
        e.declareAttacker("me", "a", Ref.Player("opp")); e.declareBlocker("opp", "b", "a"); e.combatDamage()
        assertEquals(17, s.player("opp").life); assertEquals(Zone.GRAVEYARD, s.obj("b").zone); assertTrue("702.19b" in s.cited())
    }

    @Test
    fun `first strike kills the blocker before it deals damage`() {
        val s = state(); s.add("a", creature("Knight", 2, 1, "First strike"), "me"); s.add("b", creature("Bear", 2, 2), "opp"); val e = Engine(s)
        e.declareAttacker("me", "a", Ref.Player("opp")); e.declareBlocker("opp", "b", "a"); e.combatDamage()
        assertEquals(Zone.BATTLEFIELD, s.obj("a").zone); assertEquals(Zone.GRAVEYARD, s.obj("b").zone); assertTrue("510.4" in s.cited())
    }

    @Test
    fun `deathtouch destroys and indestructible survives`() {
        val s = state(); s.add("a", creature("Giant", 5, 5), "me"); s.add("b", creature("Snake", 1, 1, "Deathtouch"), "opp"); val e = Engine(s)
        e.declareAttacker("me", "a", Ref.Player("opp")); e.declareBlocker("opp", "b", "a"); e.combatDamage()
        assertEquals(Zone.GRAVEYARD, s.obj("a").zone); assertTrue("704.5h" in s.cited())
        val s2 = state(); s2.add("a", creature("Statue", 3, 3, "Indestructible"), "me"); s2.add("b", creature("Snake", 1, 1, "Deathtouch"), "opp"); val e2 = Engine(s2)
        e2.declareAttacker("me", "a", Ref.Player("opp")); e2.declareBlocker("opp", "b", "a"); e2.combatDamage()
        assertEquals(Zone.BATTLEFIELD, s2.obj("a").zone); assertTrue("702.12b" in s2.cited())
    }

    @Test
    fun `flying cannot be blocked by ground creatures, reach can block it`() {
        val s = state(); s.add("a", creature("Drake", 2, 2, "Flying"), "me"); s.add("b", creature("Bear", 2, 2), "opp"); s.add("c", creature("Spider", 1, 4, "Reach"), "opp"); val e = Engine(s)
        e.declareAttacker("me", "a", Ref.Player("opp")); e.declareBlocker("opp", "b", "a")
        assertEquals(null, s.obj("b").blocking); assertTrue("702.9b" in s.cited())
        e.declareBlocker("opp", "c", "a"); assertEquals("a", s.obj("c").blocking)
    }

    @Test
    fun `summoning sickness, haste, vigilance, defender, menace, lifelink`() {
        val s = state()
        s.add("sick", creature("Newcomer", 2, 2), "me", sick = true); s.add("hasty", creature("Raider", 2, 2, "Haste"), "me", sick = true)
        s.add("vig", creature("Sentry", 2, 2, "Vigilance"), "me"); s.add("wall", creature("Wall", 0, 4, "Defender"), "me")
        s.add("men", creature("Brute", 3, 3, "Menace", "Lifelink"), "me"); s.add("b", creature("Bear", 2, 2), "opp")
        val e = Engine(s)
        e.declareAttacker("me", "sick", Ref.Player("opp")); assertEquals(null, s.obj("sick").attacking)
        e.declareAttacker("me", "hasty", Ref.Player("opp")); assertTrue(s.obj("hasty").attacking != null); assertTrue("702.10b" in s.cited())
        e.declareAttacker("me", "vig", Ref.Player("opp")); assertEquals(false, s.obj("vig").tapped); assertTrue("702.20b" in s.cited())
        e.declareAttacker("me", "wall", Ref.Player("opp")); assertEquals(null, s.obj("wall").attacking); assertTrue("702.3b" in s.cited())
        e.declareAttacker("me", "men", Ref.Player("opp")); e.declareBlocker("opp", "b", "men")
        e.combatDamage()
        assertTrue("702.111b" in s.cited(), "single blocker on menace is illegal")
        assertEquals(20 - 2 - 2 - 3, s.player("opp").life)
        assertEquals(23, s.player("me").life); assertTrue("702.15b" in s.cited())
    }
}
