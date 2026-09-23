package mtg.judge.oracle

import mtg.judge.engine.Effect
import mtg.judge.engine.Kind
import mtg.judge.engine.Trigger
import mtg.judge.engine.TriggeredAbility
import mtg.judge.engine.Who
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OracleParserTest {
    @Test
    fun `rhystic study becomes a spell-cast trigger with may and unless`() {
        val d = OracleParser.parse("x", "Rhystic Study", "Enchantment", "{2}{U}", 3.0, "U", null, null, emptyList(),
            "Whenever an opponent casts a spell, you may draw a card unless that player pays {1}.")
        val t = assertIs<TriggeredAbility>(d.abilities.single())
        assertEquals(Trigger.SpellCast(Who.OPPONENT), t.trigger)
        val unless = assertIs<Effect.UnlessPays>(t.effect)
        assertEquals(Who.THAT_PLAYER, unless.payer); assertEquals("{1}", unless.cost)
        val may = assertIs<Effect.May>(unless.effect)
        assertEquals(Effect.Draw(Who.YOU, 1), may.effect)
    }

    @Test
    fun `targets and filters`() {
        val bolt = OracleParser.parse("x", "Lightning Bolt", "Instant", "{R}", 1.0, "R", null, null, emptyList(), "Lightning Bolt deals 3 damage to any target.")
        val dmg = assertIs<Effect.Damage>(bolt.spellEffect)
        assertEquals(3, dmg.amount); assertTrue(Kind.PLAYER in dmg.target.filter.kinds && Kind.CREATURE in dmg.target.filter.kinds)
        val stifle = OracleParser.parse("x", "Stifle", "Instant", "{U}", 1.0, "U", null, null, emptyList(), "Counter target activated or triggered ability.")
        assertEquals(setOf(Kind.ABILITY), assertIs<Effect.Counter>(stifle.spellEffect).target.filter.kinds)
        val negate = OracleParser.parse("x", "Negate", "Instant", "{1}{U}", 2.0, "U", null, null, emptyList(), "Counter target noncreature spell.")
        val f = assertIs<Effect.Counter>(negate.spellEffect).target.filter
        assertEquals(setOf(Kind.SPELL), f.kinds); assertEquals(setOf(Kind.CREATURE), f.notKinds)
        val opp = OracleParser.parseFilter("creature an opponent controls")
        assertEquals(Who.OPPONENT, opp.controller); assertTrue(opp.verifiable)
        val flyer = OracleParser.parseFilter("creature with flying")
        assertTrue(flyer.verifiable && "flying" in flyer.keywords)
        val odd = OracleParser.parseFilter("creature with mana value 3 or less")
        assertTrue(odd.verifiable && odd.maxManaValue == 3, odd.toString())
        val elves = OracleParser.parseFilter("Elves you control")
        assertEquals(setOf("elf"), elves.subtypes); assertEquals(setOf(Kind.CREATURE), elves.kinds)
    }

    @Test
    fun `sequences and unparsed parts are kept apart`() {
        val swords = OracleParser.parse("x", "Swords to Plowshares", "Instant", "{W}", 1.0, "W", null, null, emptyList(), "Exile target creature. Its controller gains life equal to its power.")
        val seq = assertIs<Effect.Seq>(swords.spellEffect)
        assertIs<Effect.Exile>(seq.effects[0]); assertIs<Effect.GainLifeEqualToPower>(seq.effects[1])
        val odd = OracleParser.parse("y", "Oddity", "Instant", "{W}", 1.0, "W", null, null, emptyList(), "Exile target creature. Its controller shuffles their graveyard into their library.")
        val seq2 = assertIs<Effect.Seq>(odd.spellEffect)
        assertIs<Effect.Exile>(seq2.effects[0]); assertIs<Effect.Unparsed>(seq2.effects[1])
        assertTrue(odd.spellEffect!!.hasUnparsed())
    }

    @Test
    fun `keywords, activated abilities and reminder text`() {
        val d = OracleParser.parse("x", "Prodigal Sorcerer", "Creature — Human Wizard", "{2}{U}", 3.0, "U", "1", "1", emptyList(),
            "{T}: Prodigal Sorcerer deals 1 damage to any target.")
        val a = assertIs<mtg.judge.engine.ActivatedAbility>(d.abilities.single())
        assertEquals("{T}", a.cost); assertIs<Effect.Damage>(a.effect)
        val flyer = OracleParser.parse("x", "Wind Drake", "Creature — Drake", "{2}{U}", 3.0, "U", "2", "2", listOf("Flying"), "Flying (This creature can't be blocked except by creatures with flying or reach.)")
        assertEquals("flying", assertIs<mtg.judge.engine.StaticAbility>(flyer.abilities.single()).keyword)
        assertTrue(flyer.has("Flying"))
    }
}

class LevelUpParseTest {
    @Test
    fun levelBandsAreParsed() {
        val d = OracleParser.parse("x", "Student of Warfare", "Creature — Human Knight", "{W}", 1.0, "W", "1", "1", listOf("Level up", "First strike", "Double strike"),
            "Level up {W} ({W}: Put a level counter on this. Level up only as a sorcery.)\nLEVEL 2-6\n3/3\nFirst strike\nLEVEL 7+\n4/4\nDouble strike")
        val bands = d.abilities.filterIsInstance<mtg.judge.engine.StaticAbility>().flatMap { it.effects }.filterIsInstance<mtg.judge.engine.StaticEffect.LevelBand>()
        println("ABILITIES: " + d.abilities.joinToString(" | ") { it.toString() })
        assertEquals(2, bands.size, "two level bands")
        assertEquals(mtg.judge.engine.StaticEffect.LevelBand(2, 6, 3, 3, setOf("first strike")), bands[0])
        assertEquals(mtg.judge.engine.StaticEffect.LevelBand(7, null, 4, 4, setOf("double strike")), bands[1])
        assertTrue(d.abilities.filterIsInstance<mtg.judge.engine.ActivatedAbility>().any { it.cost.startsWith("Level up") }, "level up ability")
    }
}
