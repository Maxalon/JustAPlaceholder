package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exiling a graveyard, dies-replacements that also do something else, and one damage line per creature. */
class BatchFourteenTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, cost.count { it in "WUBRGC" } + (Regex("""\{(\d+)\}""").find(cost)?.groupValues?.get(1)?.toDouble() ?: 0.0), colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val bog = card("Bojuka Bog", "Land", "Bojuka Bog enters tapped.\nWhen Bojuka Bog enters, exile target player's graveyard.", "")
    private val kalitas = card("Kalitas, Traitor of Ghet", "Legendary Creature — Vampire Warrior", "Lifelink\nIf a nontoken creature an opponent controls would die, instead exile that card and create a 2/2 black Zombie creature token.", "{2}{B}{B}", "B", "3", "4", "Lifelink")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val wall = card("Wall of Stone", "Creature — Wall", "Defender", "{1}{R}{R}", "R", "0", "8", "Defender")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String, zone: Zone = Zone.BATTLEFIELD) = add(GameObject(id, def, zone, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `bojuka bog exiles the whole graveyard at once`() {
        val s = state(); s.put("corpse", bears, "me", Zone.GRAVEYARD); s.put("corpse2", bolt, "me", Zone.GRAVEYARD)
        s.put("bog", bog, "opp", Zone.HAND)
        val e = Engine(s); e.enter("bog"); e.resolveAll()
        assertEquals(Zone.EXILE, s.obj("corpse").zone)
        assertEquals(Zone.EXILE, s.obj("corpse2").zone)
        assertTrue("701.13a" in s.cited())
        assertTrue(s.assumptions.any { "target player" in it }, s.assumptions.toString())
    }

    @Test
    fun `an empty graveyard is exiled without incident`() {
        val s = state(); s.put("bog", bog, "opp", Zone.HAND); val e = Engine(s); e.enter("bog"); e.resolveAll()
        assertTrue(s.trace.steps.any { it.text.contains("graveyard is empty") }, s.trace.steps.joinToString("\n") { it.text })
    }

    @Test
    fun `kalitas exiles an opponent's dying creature and makes a zombie`() {
        val s = state(); s.put("kal", kalitas, "me"); s.put("bears", bears, "opp")
        val e = Engine(s); e.cast("me", bolt, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.EXILE, s.obj("bears").zone)
        assertTrue(s.objects.values.any { it.token && it.name.contains("Zombie") }, s.objects.values.joinToString { it.name })
        assertTrue("614.1a" in s.cited())
    }

    @Test
    fun `kalitas leaves its controller's own creatures alone`() {
        val s = state(); s.put("kal", kalitas, "me"); s.put("bears", bears, "me")
        val e = Engine(s); e.cast("me", bolt, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("bears").zone)
        assertTrue(s.objects.values.none { it.token })
    }

    @Test
    fun `two hits on one creature leave a single damage outcome`() {
        val s = state(); s.put("wall", wall, "opp")
        val e = Engine(s)
        e.cast("me", bolt, listOf(Ref.Obj("wall"))); e.resolveAll()
        e.cast("me", bolt, listOf(Ref.Obj("wall"))); e.resolveAll()
        assertEquals(6, s.obj("wall").damage)
        assertEquals(1, s.outcomes.count { it.contains("damage marked") }, s.outcomes.toString())
        assertTrue(s.outcomes.any { it.contains("6 damage marked") }, s.outcomes.toString())
    }

    private val tower = card("Urza's Tower", "Land — Urza's Tower", "{T}: Add {C}. If you control an Urza's Mine and an Urza's Power-Plant, add {C}{C}{C} instead.", "")
    private val mine = card("Urza's Mine", "Land — Urza's Mine", "{T}: Add {C}. If you control an Urza's Power-Plant and an Urza's Tower, add {C}{C} instead.", "")
    private val plant = card("Urza's Power Plant", "Land — Urza's Power-Plant", "{T}: Add {C}. If you control an Urza's Mine and an Urza's Tower, add {C}{C} instead.", "")
    private val island = card("Island", "Basic Land — Island", "{T}: Add {U}.", "")
    private val birds = card("Birds of Paradise", "Creature — Bird", "Flying\n{T}: Add one mana of any color.", "{G}", "G", "0", "1", "Flying")

    @Test
    fun `urza lands make three only with the whole set`() {
        val s = state(); s.put("tower", tower, "me")
        val e = Engine(s); e.activate("me", "tower", 0, emptyList())
        assertTrue(s.outcomes.any { it == "Urza's Tower's mana ability: add {C}." }, s.outcomes.toString())

        val s2 = state(); s2.put("tower", tower, "me"); s2.put("mine", mine, "me"); s2.put("plant", plant, "me")
        val e2 = Engine(s2); e2.activate("me", "tower", 0, emptyList())
        assertTrue(s2.outcomes.any { it == "Urza's Tower's mana ability: add {C}{C}{C}." }, s2.outcomes.toString())
    }

    @Test
    fun `available mana counts untapped sources and skips summoning-sick ones`() {
        val s = state(); s.put("i1", island, "me"); s.put("i2", island, "me"); s.put("birds", birds, "me")
        s.obj("birds").summoningSick = true
        val e = Engine(s)
        val said = e.manaAvailable("me")
        assertTrue(said.startsWith("You can make 2 mana right now"), said)
        assertTrue(said.contains("summoning sick"), said)
        s.obj("i1").tapped = true
        assertTrue(e.manaAvailable("me").startsWith("You can make 1 mana right now"), e.manaAvailable("me"))
    }

    private val humility = card("Humility", "Enchantment", "All creatures lose all abilities and have base power and toughness 1/1.", "{2}{W}{W}", "W")
    private val serra = card("Serra Angel", "Creature — Angel", "Flying, vigilance", "{3}{W}{W}", "W", "4", "4", "Flying", "Vigilance")
    private val anthem = card("Glorious Anthem", "Enchantment", "Creatures you control get +1/+1.", "{1}{W}{W}", "W")
    private val lord = card("Lord of Atlantis", "Creature — Merfolk", "Other Merfolk creatures get +1/+1 and have islandwalk.", "{U}{U}", "U", "2", "2")
    private val merfolk = card("Merfolk Looter", "Creature — Merfolk Rogue", "{T}: Draw a card, then discard a card.", "{1}{U}", "U", "1", "1")

    @Test
    fun `humility makes every creature a vanilla one-one`() {
        val s = state(); s.put("hum", humility, "me"); s.put("angel", serra, "opp")
        assertEquals(1, s.obj("angel").power); assertEquals(1, s.obj("angel").toughness)
        assertTrue(!s.hasKeyword(s.obj("angel"), "flying"))
        assertTrue(s.describePt(s.obj("angel")).contains("Humility"), s.describePt(s.obj("angel")))
    }

    @Test
    fun `humility stops a lord granting anything but leaves a non-creature anthem alone`() {
        val s = state(); s.put("hum", humility, "me"); s.put("lord", lord, "me"); s.put("fish", merfolk, "me")
        assertEquals(1, s.obj("fish").power)
        assertTrue(!s.hasKeyword(s.obj("fish"), "islandwalk"))

        val s2 = state(); s2.put("hum", humility, "me"); s2.put("anthem", anthem, "me"); s2.put("fish", merfolk, "me")
        assertEquals(2, s2.obj("fish").power); assertEquals(2, s2.obj("fish").toughness)
    }

    @Test
    fun `three damage kills a serra angel under humility`() {
        val s = state(); s.put("hum", humility, "opp"); s.put("angel", serra, "opp")
        val e = Engine(s); e.cast("me", bolt, listOf(Ref.Obj("angel"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("angel").zone)
    }

    private val thoughtseize = card("Thoughtseize", "Sorcery", "Target player reveals their hand. You choose a nonland card from it. That player discards that card. You lose 2 life.", "{B}", "B")
    private val inquisition = card("Inquisition of Kozilek", "Sorcery", "Target player reveals their hand. You choose a nonland card from it with mana value 3 or less. That player discards that card.", "{B}", "B")
    private val forceOfWill = card("Force of Will", "Instant", "Counter target spell.", "{3}{U}{U}", "U")
    private val forest = card("Forest", "Basic Land — Forest", "", "")

    @Test
    fun `thoughtseize takes the card the situation names`() {
        val s = state(); s.put("bolt", bolt, "opp", Zone.HAND); s.put("land", forest, "opp", Zone.HAND)
        val e = Engine(s); e.cast("me", thoughtseize, listOf(Ref.Player("opp"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("bolt").zone)
        assertEquals(Zone.HAND, s.obj("land").zone)
        assertEquals(18, s.player("me").life)
        assertTrue("701.9a" in s.cited())
    }

    @Test
    fun `inquisition leaves a card that costs too much`() {
        val s = state(); s.put("fow", forceOfWill, "opp", Zone.HAND); s.put("bolt", bolt, "opp", Zone.HAND)
        val e = Engine(s); e.cast("me", inquisition, listOf(Ref.Player("opp"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("bolt").zone)
        assertEquals(Zone.HAND, s.obj("fow").zone)
    }

    @Test
    fun `an unknown hand is asked about rather than guessed`() {
        val s = state(); val e = Engine(s); e.cast("me", thoughtseize, listOf(Ref.Player("opp"))); e.resolveAll()
        assertTrue(s.clarifications.any { it.why.contains("what is in it?") }, s.clarifications.toString())
    }

    private val therapy = card("Cabal Therapy", "Sorcery", "Choose a nonland card name. Target player reveals their hand and discards all cards with that name.", "{B}", "B")

    @Test
    fun `cabal therapy takes every copy of the named card`() {
        val s = state()
        s.put("b1", bolt, "opp", Zone.HAND); s.put("b2", bolt, "opp", Zone.HAND); s.put("gy", bears, "opp", Zone.HAND)
        val e = Engine(s); e.cast("me", therapy, listOf(Ref.Player("opp")), choice = "Lightning Bolt"); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("b1").zone)
        assertEquals(Zone.GRAVEYARD, s.obj("b2").zone)
        assertEquals(Zone.HAND, s.obj("gy").zone)
        assertTrue("400.7" in s.cited())
    }

    @Test
    fun `cabal therapy with no name chosen asks instead of guessing`() {
        val s = state(); s.put("b1", bolt, "opp", Zone.HAND)
        val e = Engine(s); e.cast("me", therapy, listOf(Ref.Player("opp"))); e.resolveAll()
        assertEquals(Zone.HAND, s.obj("b1").zone)
        assertTrue(s.clarifications.any { it.why.contains("which name was chosen?") }, s.clarifications.toString())
    }

    private val cradle = card("Gaea's Cradle", "Legendary Land", "{T}: Add {G} for each creature you control.", "")
    private val coffers = card("Cabal Coffers", "Land", "{2}, {T}: Add {B} for each Swamp you control.", "")
    private val swamp = card("Swamp", "Basic Land — Swamp", "", "")

    @Test
    fun `gaeas cradle counts the creatures on the battlefield`() {
        val s = state(); s.put("cradle", cradle, "me"); s.put("b1", bears, "me"); s.put("b2", bears, "me")
        val e = Engine(s); e.activate("me", "cradle", 0, emptyList())
        assertTrue(s.outcomes.any { it == "Gaea's Cradle's mana ability: add {G}{G}." }, s.outcomes.toString())

        val s2 = state(); s2.put("cradle", cradle, "me")
        val e2 = Engine(s2); e2.activate("me", "cradle", 0, emptyList())
        assertTrue(s2.outcomes.any { it.contains("no mana") }, s2.outcomes.toString())
    }

    @Test
    fun `cabal coffers counts only swamps`() {
        val s = state(); s.put("coffers", coffers, "me"); s.put("s1", swamp, "me"); s.put("s2", swamp, "me"); s.put("bear", bears, "me")
        val e = Engine(s); e.activate("me", "coffers", 0, emptyList())
        assertTrue(s.outcomes.any { it == "Cabal Coffers's mana ability: add {B}{B}." }, s.outcomes.toString())
    }

    private val chalice = card("Chalice of the Void", "Artifact", "Chalice of the Void enters with X charge counters on it.\nWhenever a player casts a spell with mana value equal to the number of charge counters on Chalice of the Void, counter that spell.", "{X}{X}")
    private val counterspell = card("Counterspell", "Instant", "Counter target spell.", "{U}{U}", "U")

    @Test
    fun `chalice counters a spell whose mana value matches its counters`() {
        val s = state(); s.put("chalice", chalice, "me"); s.obj("chalice").counters["charge"] = 1
        val e = Engine(s); e.cast("opp", bolt, listOf(Ref.Player("me"))); e.resolveAll()
        assertEquals(20, s.player("me").life)
        assertTrue(s.outcomes.any { it == "Lightning Bolt is countered." }, s.outcomes.toString())
        assertTrue("701.5a" in s.cited())
    }

    @Test
    fun `chalice leaves a spell of another mana value alone`() {
        val s = state(); s.put("chalice", chalice, "me"); s.obj("chalice").counters["charge"] = 2
        val e = Engine(s); e.cast("opp", bolt, listOf(Ref.Player("me"))); e.resolveAll()
        assertEquals(17, s.player("me").life)

        val s2 = state(); s2.put("chalice", chalice, "me"); s2.obj("chalice").counters["charge"] = 2
        val e2 = Engine(s2); e2.cast("opp", counterspell, emptyList()); e2.resolveAll()
        assertTrue(s2.outcomes.any { it == "Counterspell is countered." }, s2.outcomes.toString())
    }
}
