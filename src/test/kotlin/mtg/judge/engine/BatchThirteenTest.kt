package mtg.judge.engine

import mtg.judge.oracle.OracleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Evoke, Fling's sacrificed power, Phyrexian Obliterator, empty-library draws, Aether Vial, "target player" activations. */
class BatchThirteenTest {
    private fun card(name: String, type: String, text: String, cost: String = "{1}", colors: String = "", p: String? = null, t: String? = null, vararg kw: String) =
        OracleParser.parse("oid-$name", name, type, cost, cost.count { it in "WUBRGC" } + (Regex("""\{(\d+)\}""").find(cost)?.groupValues?.get(1)?.toDouble() ?: 0.0), colors, p, t, kw.toList(), text)

    private val bears = card("Grizzly Bears", "Creature — Bear", "", "{1}{G}", "G", "2", "2")
    private val forest = card("Forest", "Basic Land — Forest", "", "")
    private val mulldrifter = card("Mulldrifter", "Creature — Elemental", "Flying\nWhen Mulldrifter enters, draw two cards.\nEvoke {2}{U}", "{4}{U}", "U", "2", "2", "Flying", "Evoke")
    private val torporOrb = card("Torpor Orb", "Artifact", "Creatures entering the battlefield don't cause abilities to trigger.", "{2}")
    private val fling = card("Fling", "Instant", "As an additional cost to cast this spell, sacrifice a creature.\nFling deals damage equal to the sacrificed creature's power to any target.", "{1}{R}", "R")
    private val obliterator = card("Phyrexian Obliterator", "Creature — Phyrexian Horror", "Trample\nWhenever a source deals damage to Phyrexian Obliterator, that source's controller sacrifices that many permanents.", "{B}{B}{B}{B}", "B", "5", "5", "Trample")
    private val bolt = card("Lightning Bolt", "Instant", "Lightning Bolt deals 3 damage to any target.", "{R}", "R")
    private val brainstorm = card("Brainstorm", "Instant", "Draw three cards, then put two cards from your hand on top of your library in any order.", "{U}", "U")
    private val platinum = card("Platinum Angel", "Artifact Creature — Angel", "Flying\nYou can't lose the game and your opponents can't win the game.", "{7}", "", "4", "4", "Flying")
    private val vial = card("Aether Vial", "Artifact", "At the beginning of your upkeep, you may put a charge counter on Aether Vial.\n{T}: You may put a creature card with mana value equal to the number of charge counters on Aether Vial from your hand onto the battlefield.", "{1}")
    private val jace = card("Jace Beleren", "Legendary Planeswalker — Jace", "+2: Each player draws a card.\n−1: Target player draws a card.\n−10: Target player mills twenty cards.", "{1}{U}{U}", "U")
    private val kaalia = card("Kaalia of the Vast", "Legendary Creature — Human Cleric", "Flying\nWhenever Kaalia attacks an opponent, you may put an Angel, Demon, or Dragon creature card from your hand onto the battlefield tapped and attacking that opponent.", "{1}{R}{W}{B}", "RWB", "2", "2", "Flying")
    private val mirrorEntity = card("Mirror Entity", "Creature — Shapeshifter", "Changeling\n{X}: Until end of turn, creatures you control have base power and toughness X/X and gain all creature types.", "{2}{W}", "W", "1", "1", "Changeling")
    private val hailfire = card("Torment of Hailfire", "Sorcery", "Repeat the following process X times. Each opponent loses 3 life unless that player sacrifices a nonland permanent of their choice or discards a card.", "{X}{B}{B}", "B")
    private val bloodMoon = card("Blood Moon", "Enchantment", "Nonbasic lands are Mountains.", "{2}{R}", "R")
    private val urzasSaga = card("Urza's Saga", "Enchantment Land — Urza's Saga", "(As this Saga enters and after your draw step, add a lore counter.)\nI — Urza's Saga gains \"{T}: Add {C}.\"\nII — Urza's Saga gains \"{2}, {T}: Create a 0/0 colorless Construct artifact creature token with 'This creature gets +1/+1 for each artifact you control.'\"\nIII — Search your library for an artifact card with mana cost {0} or {1}, put it onto the battlefield, then shuffle.", "", "")
    private val stonySilence = card("Stony Silence", "Enchantment", "Activated abilities of artifacts can't be activated.", "{1}{W}", "W")
    private val solRing = card("Sol Ring", "Artifact", "{T}: Add {C}{C}.", "{1}", "")
    private val goyf = card("Tarmogoyf", "Creature — Lhurgoyf", "Tarmogoyf's power is equal to the number of card types among cards in all graveyards and its toughness is equal to that number plus 1.", "{1}{G}", "G", "*", "1+*")
    private val rancor = card("Rancor", "Enchantment — Aura", "Enchant creature\nEnchanted creature gets +2/+0 and has trample.\nWhen Rancor is put into a graveyard from the battlefield, return Rancor to its owner's hand.", "{G}", "G")
    private val hexmage = card("Vampire Hexmage", "Creature — Vampire Shaman", "First strike\nSacrifice Vampire Hexmage: Remove all counters from target permanent.", "{B}{B}", "B", "2", "1", "First strike")
    private val threaten = card("Threaten", "Sorcery", "Untap target creature and gain control of it until end of turn. That creature gains haste until end of turn.", "{2}{R}", "R")
    private val auraOfSilence = card("Aura of Silence", "Enchantment", "Artifact and enchantment spells your opponents cast cost {2} more to cast.\nSacrifice Aura of Silence: Destroy target artifact or enchantment.", "{1}{W}{W}", "W")
    private val cloudshift = card("Cloudshift", "Instant", "Exile target creature you control, then return that card to the battlefield under your control.", "{W}", "W")
    private val doomBlade = card("Doom Blade", "Instant", "Destroy target nonblack creature.", "{1}{B}", "B")
    private val cracklingDoom = card("Crackling Doom", "Instant", "Crackling Doom deals 2 damage to each opponent. Each opponent sacrifices a creature with the greatest power among creatures that player controls.", "{R}{W}{B}", "RWB")
    private val recall = card("Ancestral Recall", "Instant", "Target player draws three cards.", "{U}", "U")
    private val exsanguinate = card("Exsanguinate", "Sorcery", "Each opponent loses X life. You gain life equal to the life lost this way.", "{X}{B}{B}", "B")
    private val condemn = card("Condemn", "Instant", "Put target attacking creature on the bottom of its owner's library. Its controller gains life equal to its toughness.", "{W}", "W")
    private val spellskite = card("Spellskite", "Artifact Creature — Phyrexian Horror", "{U/P}: Change a target of target spell or ability to Spellskite.", "{2}", "", "0", "4")
    private val bloodArtist = card("Blood Artist", "Creature — Vampire", "Whenever Blood Artist or another creature dies, target player loses 1 life and you gain 1 life.", "{B}", "B", "0", "1")
    private val pyroclasm = card("Pyroclasm", "Sorcery", "Pyroclasm deals 2 damage to each creature.", "{1}{R}", "R")
    private val meddlingMage = card("Meddling Mage", "Creature — Human Wizard", "As this creature enters, choose a nonland card name.\nSpells with the chosen name can't be cast.", "{W}{U}", "WU", "2", "2")
    private val mindTwist = card("Mind Twist", "Sorcery", "Target player discards X cards at random.", "{X}{B}", "B")
    private val serra = card("Serra Angel", "Creature — Angel", "Flying, vigilance", "{3}{W}{W}", "W", "4", "4", "Flying", "Vigilance")
    private val viper = card("Ambush Viper", "Creature — Snake", "Flash\nDeathtouch", "{1}{G}", "G", "2", "1", "Flash", "Deathtouch")
    private val krenko = card("Krenko, Mob Boss", "Legendary Creature — Goblin Warrior", "{T}: Create X 1/1 red Goblin creature tokens, where X is the number of Goblins you control.", "{2}{R}{R}", "R", "3", "3")
    private val glimpse = card("Glimpse the Unthinkable", "Sorcery", "Target player mills ten cards.", "{U}{B}", "UB")
    private val terminus = card("Terminus", "Sorcery", "Put all creatures on the bottom of their owners' libraries.\nMiracle {W}", "{4}{W}{W}", "W")
    private val wrath = card("Wrath of God", "Sorcery", "Destroy all creatures. They can't be regenerated.", "{2}{W}{W}", "W")
    private val teferi = card("Teferi, Time Raveler", "Legendary Planeswalker — Teferi", "Each opponent can cast spells only any time they could cast a sorcery.\n+1: Until your next turn, you may cast sorcery spells as though they had flash.\n−3: Return up to one target artifact, creature, or enchantment to its owner's hand. Draw a card.", "{1}{W}{U}", "WU")
    private val twincast = card("Twincast", "Instant", "Copy target instant or sorcery spell. You may choose new targets for the copy.", "{U}{U}", "U")
    private val maze = card("Maze of Ith", "Land", "{T}: Untap target attacking creature. Prevent all combat damage that would be dealt to and dealt by that creature this turn.", "")
    private val approach = card("Approach of the Second Sun", "Sorcery", "If Approach of the Second Sun was cast from your hand and you've cast another spell named Approach of the Second Sun this game, you win the game. Otherwise, put Approach of the Second Sun into its owner's library seventh from the top and you gain 7 life.", "{6}{W}", "W")
    private val solemnity = card("Solemnity", "Enchantment", "Players can't get counters.\nCounters can't be put on artifacts, creatures, enchantments, or lands.", "{2}{W}", "W")
    private val ballista = card("Walking Ballista", "Artifact Creature — Construct", "Walking Ballista enters the battlefield with X +1/+1 counters on it.\n{4}: Put a +1/+1 counter on Walking Ballista.\nRemove a +1/+1 counter from Walking Ballista: It deals 1 damage to any target.", "{X}{X}", "", "0", "0")
    private val abolisher = card("Grand Abolisher", "Creature — Human Cleric", "During your turn, your opponents can't cast spells or activate abilities of artifacts, creatures, or enchantments.", "{W}{W}", "W", "2", "2")
    private val thalia = card("Thalia, Guardian of Thraben", "Legendary Creature — Human Soldier", "First strike\nNoncreature spells cost {1} more to cast.", "{1}{W}", "W", "2", "1", "First strike")
    private val reservoir = card("Aetherflux Reservoir", "Artifact", "Whenever you cast a spell, you gain 1 life for each spell you've cast this turn.\nPay 50 life: Aetherflux Reservoir deals 50 damage to any target.", "{4}")
    private val kci = card("Krark-Clan Ironworks", "Artifact", "Sacrifice an artifact: Add {C}{C}.", "{4}")
    private val guttersnipe = card("Guttersnipe", "Creature — Goblin Shaman", "Whenever you cast an instant or sorcery spell, Guttersnipe deals 2 damage to each opponent.", "{2}{R}", "R", "2", "2")
    private val rift = card("Cyclonic Rift", "Instant", "Return target nonland permanent you don't control to its owner's hand.\nOverload {6}{U}", "{1}{U}", "U", null, null, "Overload")
    private val avenger = card("Serra Avenger", "Creature — Angel", "You can't cast Serra Avenger during your first, second, or third turns of the game.\nFlying\nVigilance", "{W}{W}", "W", "3", "3", "Flying", "Vigilance")
    private val deluge = card("Toxic Deluge", "Sorcery", "As an additional cost to cast this spell, pay X life.\nAll creatures get -X/-X until end of turn.", "{2}{B}", "B")
    private val trampler = card("Rampaging Baloths", "Creature — Beast", "Trample", "{4}{G}{G}", "G", "6", "6", "Trample")
    private val unsummon = card("Unsummon", "Instant", "Return target creature to its owner's hand.", "{U}", "U")
    private val giantGrowth = card("Giant Growth", "Instant", "Target creature gets +3/+3 until end of turn.", "{G}", "G")
    private val sakura = card("Sakura-Tribe Elder", "Creature — Snake Shaman", "Sacrifice Sakura-Tribe Elder: Search your library for a basic land card, put that card onto the battlefield tapped, then shuffle.", "{1}{G}", "G", "1", "1")

    private fun state() = GameState(listOf(Player("me", "me", 20), Player("opp", "opp", 20)), LinkedHashMap(), activePlayer = "me")
    private fun GameState.put(id: String, def: CardDef, ctrl: String, zone: Zone = Zone.BATTLEFIELD) = add(GameObject(id, def, zone, ctrl))
    private fun GameState.cited() = trace.steps.flatMap { it.rules }.toSet()

    @Test
    fun `evoke sacrifices the creature by its own enters trigger, which torpor orb silences`() {
        val s = state(); val e = Engine(s)
        e.cast("me", mulldrifter, emptyList(), evoked = true); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.objects.values.first { it.name == "Mulldrifter" }.zone)
        assertEquals(2, s.player("me").drew, "the draw trigger still resolves")
        assertTrue("702.74a" in s.cited() && "701.21a" in s.cited())

        val s2 = state(); s2.put("orb", torporOrb, "opp"); val e2 = Engine(s2)
        e2.cast("me", mulldrifter, emptyList(), evoked = true); e2.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s2.objects.values.first { it.name == "Mulldrifter" }.zone, "no enters trigger, no sacrifice")
        assertEquals(0, s2.player("me").drew)
    }

    @Test
    fun `evoke on a card without evoke is just a normal cast with a clarification`() {
        val s = state(); val e = Engine(s)
        e.cast("me", bears, emptyList(), evoked = true); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.objects.values.first().zone); assertTrue(s.clarifications.any { "evoke" in it.about })
    }

    @Test
    fun `fling deals damage equal to the sacrificed creature's last known power`() {
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        e.sacrifice("me", "bears")
        e.cast("me", fling, listOf(Ref.Player("opp"))); e.resolveAll()
        assertEquals(18, s.player("opp").life); assertTrue("608.2h" in s.cited())
        val s2 = state(); val e2 = Engine(s2)
        e2.cast("me", fling, listOf(Ref.Player("opp"))); e2.resolveAll()
        assertEquals(20, s2.player("opp").life); assertTrue(s2.clarifications.any { "sacrifice" in it.about })
    }

    @Test
    fun `phyrexian obliterator makes the damage source's controller sacrifice that many permanents`() {
        val s = state(); s.put("obl", obliterator, "opp"); s.put("f1", forest, "me"); s.put("f2", forest, "me"); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("me", bolt, listOf(Ref.Obj("obl"))); e.resolveAll()
        assertEquals(3, s.obj("obl").damage)
        assertEquals(setOf(Zone.GRAVEYARD), listOf("f1", "f2", "bears").map { s.obj(it).zone }.toSet(), "3 damage: all three permanents go")
        assertTrue(s.assumptions.none { "least valuable" in it }, "no choice when everything must go")
    }

    @Test
    fun `sacrifice this as an effect is a real sacrifice`() {
        val s = state(); s.put("elder", sakura, "me"); val e = Engine(s)
        val ability = sakura.abilities.filterIsInstance<ActivatedAbility>().first()
        assertTrue(ability.cost.contains("Sacrifice", true))
        assertEquals(Effect.SacrificeSource, OracleParser.parseEffect("Sacrifice ~."))
    }

    @Test
    fun `drawing from an empty library loses the game unless platinum angel says otherwise`() {
        val s = state(); s.player("me").librarySize = 1; val e = Engine(s)
        e.cast("me", brainstorm, emptyList()); e.resolveAll()
        assertTrue(s.player("me").lost); assertTrue("704.5b" in s.cited() && "121.4" in s.cited())
        assertEquals(1, s.player("me").drew, "the one card there was gets drawn")

        val s2 = state(); s2.player("me").librarySize = 0; s2.put("angel", platinum, "me"); val e2 = Engine(s2)
        e2.cast("me", brainstorm, emptyList()); e2.resolveAll()
        assertTrue(!s2.player("me").lost); assertTrue(s2.outcomes.any { "Platinum Angel keeps" in it })
    }

    @Test
    fun `library size is tracked across draws`() {
        val s = state(); s.player("me").librarySize = 5; val e = Engine(s)
        e.draw("me", 3); assertEquals(2, s.player("me").librarySize); assertTrue(!s.player("me").lost)
        e.draw("me", 3); assertEquals(0, s.player("me").librarySize); assertTrue(s.player("me").lost)
    }

    @Test
    fun `aether vial puts a creature with matching mana value onto the battlefield`() {
        val s = state(); s.put("vial", vial, "me").counters["charge"] = 2; s.put("bears", bears, "me", Zone.HAND); val e = Engine(s)
        e.activate("me", "vial", null, emptyList(), choice = "bears"); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone); assertTrue("202.3" in s.cited())

        val s2 = state(); s2.put("vial", vial, "me").counters["charge"] = 3; s2.put("bears", bears, "me", Zone.HAND); val e2 = Engine(s2)
        e2.activate("me", "vial", null, emptyList(), choice = "bears"); e2.resolveAll()
        assertEquals(Zone.HAND, s2.obj("bears").zone); assertTrue(s2.outcomes.any { "stays in hand" in it })
    }

    @Test
    fun `a player target for a target-player draw is accepted`() {
        val s = state(); s.put("jace", jace, "me").counters["loyalty"] = 3; val e = Engine(s)
        val idx = jace.abilities.filterIsInstance<ActivatedAbility>().indexOfFirst { it.cost.replace('−', '-') == "-1" }
        e.activate("me", "jace", idx, listOf(Ref.Player("opp"))); e.resolveAll()
        assertEquals(1, s.player("opp").drew); assertTrue(s.clarifications.isEmpty(), "clarifications: ${s.clarifications}")
    }

    @Test
    fun `kaalia puts the announced angel onto the battlefield tapped and attacking`() {
        val s = state(); s.put("kaalia", kaalia, "me"); s.put("serra", serra, "me", Zone.HAND); val e = Engine(s)
        assertTrue(kaalia.abilities.filterIsInstance<TriggeredAbility>().single().trigger == Trigger.ThisAttacks, "the short self-reference is understood")
        s.pendingChoices["kaalia"] = "serra"
        e.beginDeclaringAttackers(); e.declareAttacker("me", "kaalia", Ref.Player("opp")); e.finishDeclaringAttackers(); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("serra").zone); assertEquals(true, s.obj("serra").tapped); assertEquals(Ref.Player("opp"), s.obj("serra").attacking)
        assertTrue("508.4" in s.cited())
        e.combatDamage(); assertEquals(14, s.player("opp").life)
    }

    @Test
    fun `flash is noted at instant speed and a creature without it gets a timing assumption`() {
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        e.beginDeclaringAttackers(); e.declareAttacker("me", "bears", Ref.Player("opp")); e.finishDeclaringAttackers()
        e.cast("opp", viper, emptyList()); assertTrue("702.8a" in s.cited()); assertTrue(s.assumptions.none { "normally can't" in it })
        e.cast("opp", serra, emptyList()); assertTrue("302.1" in s.cited()); assertTrue(s.assumptions.any { "normally can't" in it })
    }

    @Test
    fun `krenko counts goblins as the ability resolves`() {
        val s = state(); s.put("krenko", krenko, "me"); s.put("g1", Generic.creature("goblin creature")!!, "me"); s.put("g2", Generic.creature("2/2 goblin creature")!!, "me"); val e = Engine(s)
        e.activate("me", "krenko", null, emptyList()); e.resolveAll()
        assertEquals(3, s.objects.values.count { it.token }); assertTrue("608.2h" in s.cited())
    }

    @Test
    fun `milling is capped by the library and never loses the game by itself`() {
        val s = state(); s.player("me").librarySize = 4; val e = Engine(s)
        e.cast("opp", glimpse, listOf(Ref.Player("me"))); e.resolveAll()
        assertEquals(0, s.player("me").librarySize); assertTrue(!s.player("me").lost); assertTrue("701.17b" in s.cited())
    }

    @Test
    fun `terminus tucks indestructible creatures and wrath does not destroy one with an indestructible counter`() {
        val s = state(); s.put("bears", bears, "opp").counters["indestructible"] = 1; val e = Engine(s)
        e.cast("opp", wrath, emptyList()); e.resolveAll(); assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone)
        e.cast("me", terminus, emptyList()); e.resolveAll(); assertEquals(Zone.LIBRARY, s.obj("bears").zone)
    }

    @Test
    fun `described creatures get their stats and keywords`() {
        val d = Generic.creature("4/4 creature with flying")!!
        assertEquals(4, d.power); assertEquals(4, d.toughness); assertTrue(d.has("flying"))
        val z = Generic.spell("a 2/2 zombie creature")!!
        assertTrue("Zombie" in z.subtypes); assertTrue(Generic.creature("creature") == null)
    }

    @Test
    fun `teferi keeps opponents at sorcery speed`() {
        val s = state(); s.put("teferi", teferi, "me").counters["loyalty"] = 4; val e = Engine(s)
        e.cast("me", bears, emptyList())
        assertTrue(e.cast("opp", bolt, listOf(Ref.Player("me"))) == null, "something is on the stack"); assertTrue("307.1" in s.cited())
        e.resolveAll()
        assertTrue(e.cast("opp", bolt, listOf(Ref.Player("me"))) == null, "not the opponent's turn either")
        s.activePlayer = "opp"; assertTrue(e.cast("opp", bolt, listOf(Ref.Player("me"))) != null, "their own main phase with an empty stack is fine")
    }

    @Test
    fun `twincast copies a spell, retargeting when it was aimed at the copier`() {
        val s = state(); val e = Engine(s)
        val boltItem = e.cast("opp", bolt, listOf(Ref.Player("me")))!!
        e.cast("me", twincast, listOf(Ref.Stack(boltItem.id))); e.resolveAll()
        assertEquals(17, s.player("me").life); assertEquals(17, s.player("opp").life); assertTrue("707.10c" in s.cited())
        assertTrue(s.objects.values.none { it.token && it.zone == Zone.STACK }, "the copy is gone once it resolved")
    }

    @Test
    fun `maze of ith prevents combat damage to and by the creature`() {
        val s = state(); s.put("bears", bears, "me"); s.put("maze", maze, "opp"); val e = Engine(s)
        e.beginDeclaringAttackers(); e.declareAttacker("me", "bears", Ref.Player("opp")); e.finishDeclaringAttackers()
        e.activate("opp", "maze", null, listOf(Ref.Obj("bears"))); e.resolveAll(); e.combatDamage()
        assertEquals(20, s.player("opp").life); assertEquals(false, s.obj("bears").tapped); assertTrue("615.1" in s.cited())
    }

    @Test
    fun `approach of the second sun wins on the second cast`() {
        val s = state(); s.player("me").librarySize = 10; val e = Engine(s)
        e.cast("me", approach, emptyList()); e.resolveAll()
        assertEquals(27, s.player("me").life); assertEquals(11, s.player("me").librarySize); assertTrue(!s.player("opp").lost)
        e.cast("me", approach, emptyList()); e.resolveAll()
        assertTrue(s.player("opp").lost); assertTrue("104.2b" in s.cited())
    }

    @Test
    fun `solemnity stops X counters so a ballista dies, and X is read from the cast`() {
        val s = state(); val e = Engine(s)
        e.cast("me", ballista, emptyList(), x = 2); e.resolveAll()
        assertEquals(2, s.objects.values.first { it.name == "Walking Ballista" }.counters["+1/+1"])
        val s2 = state(); s2.put("sol", solemnity, "opp"); val e2 = Engine(s2)
        e2.cast("me", ballista, emptyList(), x = 2); e2.resolveAll()
        assertEquals(Zone.GRAVEYARD, s2.objects.values.first { it.name == "Walking Ballista" }.zone); assertTrue("122.1" in s2.cited())
    }

    @Test
    fun `grand abolisher locks opponents out on its controller's turn only`() {
        val s = state(); s.put("ab", abolisher, "opp"); s.activePlayer = "opp"; val e = Engine(s)
        assertTrue(e.cast("me", bolt, listOf(Ref.Player("opp"))) == null); assertTrue("101.2" in s.cited())
        s.activePlayer = "me"; assertTrue(e.cast("me", bolt, listOf(Ref.Player("opp"))) != null)
    }

    @Test
    fun `thalia's tax is checked against the mana the situation gave`() {
        val s = state(); s.put("thalia", thalia, "me"); s.player("opp").mana = 1; val e = Engine(s)
        assertTrue(e.cast("opp", bolt, listOf(Ref.Player("me"))) == null); assertTrue("601.2h" in s.cited())
        s.player("opp").mana = 2; assertTrue(e.cast("opp", bolt, listOf(Ref.Player("me"))) != null)
        s.player("opp").mana = 2; assertTrue(e.cast("opp", bears, emptyList()) != null, "creature spells aren't taxed")
    }

    @Test
    fun `aetherflux counts the spells cast this turn`() {
        val s = state(); s.put("res", reservoir, "me"); val e = Engine(s)
        repeat(3) { e.cast("me", bolt, listOf(Ref.Player("opp"))); e.resolveAll() }
        assertEquals(26, s.player("me").life, "1 + 2 + 3")
    }

    @Test
    fun `sacrifice-an-artifact costs take the named permanent, or refuse without one`() {
        val s = state(); s.put("kci", kci, "me"); s.put("t1", Generic.token("treasure token")!!, "me"); val e = Engine(s)
        e.activate("me", "kci", null, emptyList(), choice = "sacrifice:t1")
        assertEquals(Zone.GRAVEYARD, s.obj("t1").zone); assertTrue("701.21a" in s.cited())
        assertTrue(e.activate("me", "kci", null, emptyList()) == null, "nothing left to sacrifice"); assertTrue(s.outcomes.any { "nothing to sacrifice" in it })
    }

    @Test
    fun `guttersnipe triggers on instants only and hits each opponent`() {
        val s = state(); s.put("snipe", guttersnipe, "me"); val e = Engine(s)
        e.cast("me", bears, emptyList()); e.resolveAll(); assertEquals(20, s.player("opp").life, "a creature spell doesn't trigger it")
        e.cast("me", bolt, listOf(Ref.Player("opp"))); e.resolveAll(); assertEquals(15, s.player("opp").life, "2 from the trigger, 3 from the Bolt")
    }

    @Test
    fun `a target that doesn't fit the spell's filter is refused at casting`() {
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        assertTrue(e.cast("me", rift, listOf(Ref.Obj("bears"))) == null); assertTrue(s.outcomes.any { "can't target" in it })
        val s2 = state(); s2.put("bears", bears, "opp"); val e2 = Engine(s2)
        assertTrue(e2.cast("me", rift, listOf(Ref.Obj("bears"))) != null)
    }

    @Test
    fun `serra avenger waits for turn four and toxic deluge shrinks by the life paid`() {
        val s = state(); s.turnNumber = 3; val e = Engine(s)
        assertTrue(e.cast("me", avenger, emptyList()) == null); assertTrue(s.outcomes.any { "can't be cast yet" in it })
        s.turnNumber = 4; assertTrue(e.cast("me", avenger, emptyList()) != null)
        val s2 = state(); s2.put("bears", bears, "opp"); s2.put("serra", serra, "opp"); val e2 = Engine(s2)
        e2.cast("me", deluge, emptyList(), x = 3); e2.resolveAll()
        assertEquals(Zone.GRAVEYARD, s2.obj("bears").zone); assertEquals(Zone.BATTLEFIELD, s2.obj("serra").zone); assertEquals(1, s2.obj("serra").power)
    }

    @Test
    fun `mirror entity sets base power and toughness under counters and pumps until cleanup`() {
        val s = state(); s.put("mirror", mirrorEntity, "me"); s.put("bears", bears, "me"); s.obj("bears").counters["+1/+1"] = 1; s.put("serra", serra, "opp"); val e = Engine(s)
        assertTrue(e.activate("me", "mirror", 0, emptyList(), x = 4) != null); e.resolveAll()
        assertEquals(4, s.obj("mirror").power); assertEquals(5, s.obj("bears").power, "the +1/+1 counter still applies above the new base"); assertEquals(4, s.obj("serra").power, "only your creatures")
        assertTrue("613.4b" in s.cited())
        e.cast("me", giantGrowth, listOf(Ref.Obj("bears"))); e.resolveAll(); assertEquals(8, s.obj("bears").power, "a pump applies above the new base")
        e.beginStep("cleanup", "me"); assertEquals(3, s.obj("bears").power, "base 2 plus the counter once the effect ends"); assertEquals(1, s.obj("mirror").power)
    }

    @Test
    fun `torment of hailfire repeats and takes a permanent, then a card, then life`() {
        assertTrue(hailfire.spellEffect is Effect.Repeat, hailfire.spellEffect.toString())
        val s = state(); s.put("bears", bears, "opp"); s.player("opp").handSize = 1; val e = Engine(s)
        e.cast("me", hailfire, emptyList(), x = 3); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("bears").zone); assertEquals(0, s.player("opp").handSize); assertEquals(17, s.player("opp").life)
        assertTrue("701.21a" in s.cited() && "701.9a" in s.cited() && "119.3" in s.cited())
    }

    @Test
    fun `blood moon turns urza's saga into a mountain that does nothing`() {
        val s = state(); s.put("moon", bloodMoon, "opp"); s.put("saga", urzasSaga, "me"); val e = Engine(s)
        e.narrateLandTypeSetters()
        assertTrue(s.trace.steps.any { it.text.contains("chapter abilities included") && "714.4" in it.rules && "305.7" in it.rules }, s.trace.steps.joinToString("\n") { it.text })
        assertTrue(s.outcomes.any { it.startsWith("Urza's Saga is a Mountain") })
    }

    @Test
    fun `stony silence stops artifact abilities, mana abilities included`() {
        val s = state(); s.put("silence", stonySilence, "me"); s.put("ring", solRing, "opp"); val e = Engine(s)
        assertTrue(e.activate("opp", "ring", 0, emptyList()) == null); assertTrue(s.outcomes.any { it.contains("can't be activated (Stony Silence)") }); assertTrue("602.5" in s.cited())
        val s2 = state(); s2.put("ring", solRing, "opp"); val e2 = Engine(s2)
        assertTrue(e2.activate("opp", "ring", 0, emptyList()) == null, "a mana ability resolves at once and leaves nothing on the stack"); assertTrue(s2.outcomes.any { it.contains("add {C}{C}") }, s2.outcomes.toString())
    }

    @Test
    fun `a pump with no target named goes to your own creature, the one in combat first`() {
        val s = state(); s.put("bears", bears, "me"); s.put("serra", serra, "me"); s.put("wall", trampler, "opp"); val e = Engine(s)
        e.beginDeclaringAttackers(); e.declareAttacker("me", "bears", Ref.Player("opp")); e.finishDeclaringAttackers()
        assertTrue(e.cast("me", giantGrowth, emptyList()) != null); e.resolveAll()
        assertEquals(5, s.obj("bears").power); assertEquals(4, s.obj("serra").power); assertTrue(s.assumptions.any { it.contains("in combat") }, s.assumptions.toString())
    }

    @Test
    fun `tarmogoyf grows when the bolt that hit it reaches the graveyard`() {
        val s = state(); s.put("goyf", goyf, "opp"); s.put("gy_creature", bears, "opp", Zone.GRAVEYARD); s.put("gy_land", card("Forest", "Basic Land — Forest", "", "", ""), "me", Zone.GRAVEYARD); val e = Engine(s)
        assertEquals(2, s.obj("goyf").power); assertEquals(3, s.obj("goyf").toughness)
        e.cast("me", bolt, listOf(Ref.Obj("goyf"))); e.resolveAll()
        assertEquals(Zone.BATTLEFIELD, s.obj("goyf").zone, "the Bolt is in the graveyard by the time state-based actions look: 3/4 with 3 damage")
        assertEquals(3, s.obj("goyf").power); assertEquals(4, s.obj("goyf").toughness)
    }

    @Test
    fun `a fizzled aura goes to the graveyard from the stack, so its dies trigger stays quiet`() {
        val s = state(); s.put("bears", bears, "me"); val e = Engine(s)
        e.cast("me", rancor, listOf(Ref.Obj("bears"))); e.cast("opp", bolt, listOf(Ref.Obj("bears"))); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("bears").zone); assertTrue(s.trace.steps.any { it.text.contains("not from the battlefield") && "603.6c" in it.rules }, s.trace.steps.joinToString("\n") { it.text })
        assertTrue(s.objects.values.first { it.def.name == "Rancor" }.zone == Zone.GRAVEYARD)
    }

    @Test
    fun `cleanup discards down to seven`() {
        val s = state(); s.player("me").handSize = 9; val e = Engine(s)
        e.beginStep("cleanup", "me"); assertEquals(7, s.player("me").handSize); assertTrue("514.1" in s.cited()); assertTrue(s.outcomes.any { it.contains("discard 2 cards to hand size") }, s.outcomes.toString())
    }

    @Test
    fun `vampire hexmage strips every counter and state-based actions follow`() {
        val s = state(); s.put("hexmage", hexmage, "opp"); s.put("bears", bears, "me"); s.obj("bears").counters["+1/+1"] = 2; s.obj("bears").counters["-1/-1"] = 0; val e = Engine(s)
        assertTrue(e.activate("opp", "hexmage", 0, listOf(Ref.Obj("bears"))) != null); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("hexmage").zone); assertTrue(s.obj("bears").counters.isEmpty()); assertEquals(2, s.obj("bears").power)
        assertTrue(s.outcomes.any { it.startsWith("Grizzly Bears loses all its counters (2 +1/+1)") }, s.outcomes.toString())
    }

    @Test
    fun `threaten hands the creature back at cleanup`() {
        val s = state(); s.put("serra", serra, "opp"); s.obj("serra").tapped = true; val e = Engine(s)
        e.cast("me", threaten, listOf(Ref.Obj("serra"))); e.resolveAll()
        assertEquals("me", s.obj("serra").controller); assertEquals(false, s.obj("serra").tapped); assertTrue(s.hasKeyword(s.obj("serra"), "haste"))
        e.beginStep("cleanup", "me"); assertEquals("opp", s.obj("serra").controller); assertTrue(s.outcomes.any { it.contains("back under opp's control") }, s.outcomes.toString())
    }

    @Test
    fun `aura of silence taxes an opponent's artifact spell only`() {
        val s = state(); s.put("aura", auraOfSilence, "me"); val e = Engine(s)
        e.cast("opp", solRing, emptyList()); assertTrue(s.trace.steps.any { it.text.contains("Aura of Silence makes Sol Ring cost {2} more") }, s.trace.steps.joinToString("\n") { it.text })
        val s2 = state(); s2.put("aura", auraOfSilence, "me"); val e2 = Engine(s2)
        e2.cast("me", solRing, emptyList()); assertTrue(s2.trace.steps.none { it.text.contains("cost {2} more") })
    }

    @Test
    fun `a spell whose target wasn't stated still goes on the stack and can be countered`() {
        val s = state(); val e = Engine(s)
        val decay = card("Abrupt Decay", "Instant", "This spell can't be countered.\nDestroy target nonland permanent with mana value 3 or less.", "{B}{G}", "BG")
        val item = e.cast("opp", decay, emptyList()); assertTrue(item != null, "on the stack despite the missing target"); assertTrue(item!!.targetsUnknown)
        e.cast("me", card("Counterspell", "Instant", "Counter target spell.", "{U}{U}", "U"), listOf(Ref.Stack(item.id))); e.resolveAll()
        assertTrue(s.trace.steps.any { it.text.contains("can't be countered") }, s.trace.steps.joinToString("\n") { it.text }); assertTrue(s.trace.steps.any { it.text.contains("target was never stated") })
    }

    @Test
    fun `cloudshift in response makes doom blade fizzle and returns a fresh creature`() {
        val s = state(); s.put("bears", bears, "me"); s.obj("bears").counters["+1/+1"] = 1; val e = Engine(s)
        e.cast("opp", doomBlade, listOf(Ref.Obj("bears"))); e.cast("me", cloudshift, emptyList()); e.resolveAll()
        assertEquals(Zone.EXILE, s.obj("bears").zone); val fresh = s.objects.values.first { it.def.name == "Grizzly Bears" && it.isOnBattlefield() }
        assertTrue(fresh.counters.isEmpty()); assertEquals(true, fresh.summoningSick); assertTrue(s.outcomes.any { it.contains("Doom Blade doesn't resolve") }, s.outcomes.toString()); assertTrue("400.7" in s.cited())
    }

    @Test
    fun `crackling doom takes the biggest creature and ancestral recall targets its caster by default`() {
        val s = state(); s.put("bears", bears, "opp"); s.put("serra", serra, "opp"); val e = Engine(s)
        e.cast("me", cracklingDoom, emptyList()); e.resolveAll()
        assertEquals(Zone.GRAVEYARD, s.obj("serra").zone); assertEquals(Zone.BATTLEFIELD, s.obj("bears").zone); assertEquals(18, s.player("opp").life)
        val s2 = state(); val e2 = Engine(s2); e2.cast("me", recall, emptyList()); e2.resolveAll()
        assertEquals(3, s2.player("me").drew); assertTrue(s2.assumptions.any { it.contains("assuming its controller") }, s2.assumptions.toString())
    }

    @Test
    fun `exsanguinate drains each opponent for X and condemn tucks an attacker for life`() {
        val s = GameState(listOf(Player("me", "me", 20), Player("a", "Alice", 20), Player("b", "Bob", 20)), LinkedHashMap(), activePlayer = "me"); val e = Engine(s)
        e.cast("me", exsanguinate, emptyList(), x = 4); e.resolveAll()
        assertEquals(16, s.player("a").life); assertEquals(16, s.player("b").life); assertEquals(28, s.player("me").life)
        val s2 = state(); s2.put("serra", serra, "me"); val e2 = Engine(s2)
        e2.beginDeclaringAttackers(); e2.declareAttacker("me", "serra", Ref.Player("opp")); e2.finishDeclaringAttackers()
        e2.cast("opp", condemn, listOf(Ref.Obj("serra"))); e2.resolveAll()
        assertEquals(Zone.LIBRARY, s2.obj("serra").zone); assertEquals(24, s2.player("me").life, "its controller, not Condemn's, gains the toughness")
    }

    @Test
    fun `spellskite redirects a bolt to itself but not a spell it isn't a legal target for`() {
        val s = state(); s.put("skite", spellskite, "me"); s.put("bears", bears, "me"); val e = Engine(s)
        val boltItem = e.cast("opp", bolt, listOf(Ref.Obj("bears")))!!
        assertTrue(e.activate("me", "skite", 0, listOf(Ref.Stack(boltItem.id))) != null); e.resolveAll()
        assertEquals(0, s.obj("bears").damage); assertEquals(3, s.obj("skite").damage); assertEquals(Zone.BATTLEFIELD, s.obj("skite").zone); assertTrue("115.7" in s.cited())
        val s2 = state(); s2.put("skite", spellskite, "me"); s2.put("bears", bears, "me"); val e2 = Engine(s2)
        val blade = e2.cast("opp", card("Doom Blade", "Instant", "Destroy target nonblack creature.", "{1}{B}", "B"), listOf(Ref.Obj("bears")))!!
        e2.activate("me", "skite", 0, listOf(Ref.Stack(blade.id))); e2.resolveAll()
        assertEquals(Zone.GRAVEYARD, s2.obj("skite").zone, "Spellskite is a colorless creature, a legal Doom Blade target, so it takes the Blade"); assertEquals(Zone.BATTLEFIELD, s2.obj("bears").zone)
        val s3 = state(); s3.put("skite", spellskite, "me"); s3.put("bears", bears, "me"); val e3 = Engine(s3)
        val naturalize = e3.cast("opp", card("Shatter", "Instant", "Destroy target artifact.", "{1}{R}", "R"), listOf(Ref.Obj("skite")))!!
        e3.activate("me", "skite", 0, listOf(Ref.Stack(naturalize.id))); e3.resolveAll()
        assertTrue(s3.outcomes.any { it.contains("target isn't changed") || it.contains("already targets Spellskite") }, "already targeting Spellskite: nothing to change: " + s3.outcomes)
    }

    @Test
    fun `blood artist sees itself and the other creatures die to the same state-based check`() {
        val s = state(); s.put("artist", bloodArtist, "me"); s.put("bears", bears, "me"); s.put("bears2", bears, "me"); val e = Engine(s)
        e.cast("opp", pyroclasm, emptyList()); e.resolveAll()
        assertEquals(17, s.player("opp").life, "three deaths at once, three triggers"); assertTrue("603.10a" in s.cited())
    }

    @Test
    fun `meddling mage stops the named spell and mind twist empties a hand`() {
        val s = state(); s.put("mage", meddlingMage, "opp"); s.obj("mage").chosenName = "Lightning Bolt"; val e = Engine(s)
        assertTrue(e.cast("me", bolt, listOf(Ref.Player("opp"))) == null); assertTrue(s.outcomes.any { it.contains("can't be cast (Meddling Mage names it)") }, s.outcomes.toString())
        assertTrue(e.cast("me", card("Shock", "Instant", "Shock deals 2 damage to any target.", "{R}", "R"), listOf(Ref.Player("opp"))) != null)
        val s2 = state(); s2.player("opp").handSize = 5; val e2 = Engine(s2)
        e2.cast("me", mindTwist, listOf(Ref.Player("opp")), x = 3); e2.resolveAll(); assertEquals(2, s2.player("opp").handSize); assertTrue("701.9a" in s2.cited())
    }

    @Test
    fun `a blocked creature stays blocked when its blocker leaves, unless it has trample`() {
        val s = state(); s.put("bears", bears, "me"); s.put("giant", serra, "opp"); val e = Engine(s)
        e.beginDeclaringAttackers(); e.declareAttacker("me", "bears", Ref.Player("opp")); e.finishDeclaringAttackers(); e.declareBlocker("opp", "giant", "bears")
        e.cast("me", unsummon, listOf(Ref.Obj("giant"))); e.resolveAll(); e.combatDamage()
        assertEquals(20, s.player("opp").life); assertTrue("509.1h" in s.cited())
        val s2 = state(); s2.put("baloth", trampler, "me"); s2.put("giant", serra, "opp"); val e2 = Engine(s2)
        e2.beginDeclaringAttackers(); e2.declareAttacker("me", "baloth", Ref.Player("opp")); e2.finishDeclaringAttackers(); e2.declareBlocker("opp", "giant", "baloth")
        e2.cast("me", unsummon, listOf(Ref.Obj("giant"))); e2.resolveAll(); e2.combatDamage()
        assertEquals(14, s2.player("opp").life); assertTrue("702.19d" in s2.cited())
    }
}
