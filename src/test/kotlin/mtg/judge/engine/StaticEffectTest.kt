package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticEffectTest {
    private fun card(name: String, type: String, text: String, p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, "{1}", 1.0, "", p, t, kw.toList(), text)

    private val anthem = card("Glorious Anthem", "Enchantment", "Creatures you control get +1/+1.")
    private val curse = card("Night of Souls' Betrayal", "Legendary Enchantment", "All creatures get -1/-1.")
    private val archdruid = card("Elvish Archdruid", "Creature — Elf Druid", "Other Elf creatures you control get +1/+1.\n{T}: Add {G} for each Elf you control.", "2", "2")
    private val fervor = card("Fervor", "Enchantment", "Creatures you control have haste.")
    private val lord = card("Lord of the Unreal", "Creature — Human Wizard", "Other Illusion creatures you control get +1/+1 and have hexproof.", "1", "1")
    private val bears = card("Grizzly Bears", "Creature — Bear", "", "2", "2")
    private val elf = card("Llanowar Elves", "Creature — Elf Druid", "{T}: Add {G}.", "1", "1")
    private val illusion = card("Phantom Beast", "Creature — Illusion Beast", "", "4", "5")
    private val shock = card("Shock", "Instant", "Shock deals 2 damage to any target.")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String, sick: Boolean? = false) = add(GameObject(id, def, Zone.BATTLEFIELD, ctrl, summoningSick = sick))

    @Test
    fun `parser recognises anthems, lords and keyword grants`() {
        val a = (anthem.abilities.single() as StaticAbility).effects.single() as StaticEffect.PtModify
        assertEquals(Who.YOU, a.filter.controller); assertEquals(1, a.power)
        val d = (archdruid.abilities.first { it is StaticAbility } as StaticAbility).effects.single() as StaticEffect.PtModify
        assertTrue(d.filter.other); assertEquals(setOf("elf"), d.filter.subtypes)
        val l = (lord.abilities.single() as StaticAbility).effects
        assertEquals(2, l.size); assertTrue(l[1] is StaticEffect.KeywordGrant && (l[1] as StaticEffect.KeywordGrant).keywords == setOf("hexproof"))
        val f = (fervor.abilities.single() as StaticAbility).effects.single() as StaticEffect.KeywordGrant
        assertEquals(setOf("haste"), f.keywords)
    }

    @Test
    fun `anthem applies only to the controller's creatures and survives shock`() {
        val s = state(); s.put("anthem", anthem, "me"); s.put("bears", bears, "me"); s.put("theirs", bears, "opp")
        assertEquals(3, s.obj("bears").power); assertEquals(3, s.obj("bears").toughness); assertEquals(2, s.obj("theirs").power)
        val e = Engine(s); e.cast("opp", shock, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone)
        assertTrue(s.describePt(s.obj("bears")).contains("from Glorious Anthem"))
    }

    @Test
    fun `global minus one kills one-toughness creatures through 704_5f, and stops when the source leaves`() {
        val s = state(); s.put("elf", elf, "me"); s.put("curse", curse, "opp"); val e = Engine(s)
        assertEquals(0, s.obj("elf").toughness)
        e.stateBasedActions()
        assertEquals(Zone.GRAVEYARD, s.obj("elf").zone); assertTrue(s.trace.steps.any { "704.5f" in it.rules })
        s.put("elf2", elf, "me"); e.leave("curse", Zone.GRAVEYARD)
        assertEquals(1, s.obj("elf2").toughness)
    }

    @Test
    fun `lords affect others with the subtype, not themselves or other types`() {
        val s = state(); s.put("druid", archdruid, "me"); s.put("elf", elf, "me"); s.put("bears", bears, "me"); s.put("oppelf", elf, "opp")
        assertEquals(2, s.obj("elf").power); assertEquals(2, s.obj("druid").power); assertEquals(2, s.obj("bears").power); assertEquals(1, s.obj("oppelf").power)
        s.put("lord", lord, "me"); s.put("beast", illusion, "me")
        assertEquals(5, s.obj("beast").power); assertTrue(s.obj("beast").has("hexproof")); assertTrue(!s.obj("lord").has("hexproof"))
    }

    @Test
    fun `granted haste lets a summoning-sick creature attack`() {
        val s = state(); s.put("fervor", fervor, "me"); s.put("bears", bears, "me", sick = true); val e = Engine(s)
        e.declareAttacker("me", "bears", Ref.Player("opp"))
        assertTrue(s.obj("bears").attacking != null); assertTrue(s.trace.steps.any { "702.10b" in it.rules })
    }
}
