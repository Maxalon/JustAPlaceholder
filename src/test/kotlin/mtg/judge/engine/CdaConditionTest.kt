package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdaConditionTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, 1.0, colors, p, t, kw.toList(), text)

    private val eluge = card("Eluge, the Shoreless Sea", "Legendary Creature — Leviathan", "Eluge, the Shoreless Sea's power and toughness are each equal to the number of Islands you control.", "{3}{U}{U}", "U", "*", "*")
    private val island = card("Island", "Basic Land — Island", "({T}: Add {U}.)")
    private val kavu = card("Mire Kavu", "Creature — Kavu", "Mire Kavu gets +1/+1 as long as you control a Swamp.", "{3}{R}", "R", "3", "2")
    private val swamp = card("Swamp", "Basic Land — Swamp", "({T}: Add {B}.)")
    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val cathar = card("Steadfast Cathar", "Creature — Human Soldier", "Whenever Steadfast Cathar attacks, target creature gets +0/+2 until end of turn. It gains vigilance until end of turn.", "{1}{W}", "W", "2", "1")
    private val walker = card("Bog Wraith", "Creature — Wraith", "Swampwalk", "{3}{B}", "B", "3", "3", "Swampwalk")
    private val counterGiver = card("Test Grower", "Sorcery", "Put a +1/+1 counter on target creature. It gets +2/+2 until end of turn.", "{G}", "G")
    private val uurg = card("Uurg, Spawn of Turg", "Legendary Creature — Frog Beast", "Uurg, Spawn of Turg's power is equal to the number of land cards in your graveyard.", "{B}{G}", "BG", "*", "5")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl))

    @Test
    fun `characteristic-defining power and toughness count permanents`() {
        assertIs<StaticEffect.PtCda>((eluge.abilities.single() as StaticAbility).effects.single())
        val s = state(); s.put("e", eluge, "me"); s.put("i1", island, "me"); s.put("i2", island, "me"); s.put("i3", island, "opp")
        assertEquals(2, s.obj("e").power); assertEquals(2, s.obj("e").toughness)
        s.put("i4", island, "me"); assertEquals(3, s.obj("e").power)
        val s2 = state(); s2.put("u", uurg, "me")
        assertNull(s2.obj("u").power, "graveyard contents aren't in the situation, so power is unknown rather than guessed"); assertEquals(5, s2.obj("u").toughness)
    }

    @Test
    fun `conditional static applies only while the condition holds`() {
        val s = state(); s.put("k", kavu, "me")
        assertEquals(3, s.obj("k").power)
        s.put("sw", swamp, "me"); assertEquals(4, s.obj("k").power); assertEquals(3, s.obj("k").toughness)
        assertTrue(s.describePt(s.obj("k")).contains("condition met"))
    }

    @Test
    fun `pronoun continuations refer to the previous target`() {
        val t = (cathar.abilities.single() as TriggeredAbility).effect
        val seq = assertIs<Effect.Seq>(t)
        assertIs<Effect.Pump>(seq.effects[0]); assertIs<Effect.GainKeywords>(seq.effects[1])
        assertTrue(!t.hasUnparsed())
        val g = assertIs<Effect.Seq>(counterGiver.spellEffect)
        assertIs<Effect.PutCounters>(g.effects[0]); assertIs<Effect.Pump>(g.effects[1])
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("me", counterGiver, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(5, s.obj("bears").power)
    }

    @Test
    fun `landwalk can't be blocked while the defender controls that land`() {
        val s = state(); s.put("w", walker, "me"); s.put("b", bears, "opp"); val e = Engine(s)
        e.declareAttacker("me", "w", Ref.Player("opp")); e.declareBlocker("opp", "b", "w")
        assertEquals("w", s.obj("b").blocking, "no Swamp: the block is fine")
        val s2 = state(); s2.put("w", walker, "me"); s2.put("b", bears, "opp"); s2.put("sw", swamp, "opp"); val e2 = Engine(s2)
        e2.declareAttacker("me", "w", Ref.Player("opp")); e2.declareBlocker("opp", "b", "w")
        assertNull(s2.obj("b").blocking); assertTrue(s2.trace.steps.any { "702.14c" in it.rules })
    }
}
