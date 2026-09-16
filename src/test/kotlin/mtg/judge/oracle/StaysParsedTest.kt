package mtg.judge.oracle

import mtg.judge.engine.Ability
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.TriggeredAbility
import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.UnparsedAbility
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Wordings that are modelled today and must stay modelled. Widening a template is a change to the order rules
 * are tried in as much as to what they match: twice now a broadened regex has swallowed text that a narrower
 * handler further down was reading correctly, and returned "unparsed" before that handler ran. Each line here
 * is one of those handlers, pinned so the next widening fails here rather than quietly losing a card.
 */
class StaysParsedTest {
    private fun parse(name: String, type: String, text: String, kw: List<String> = emptyList()) =
        OracleParser.parse("oid-$name", name, type, "{1}", 1.0, "", "2", "2", kw, text)

    private fun Ability.unparsed(): Boolean = when (this) {
        is UnparsedAbility -> true
        is TriggeredAbility -> effect.hasUnparsed()
        is ActivatedAbility -> effect.hasUnparsed()
        is StaticAbility -> false
    }

    private fun check(name: String, type: String, text: String, kw: List<String> = emptyList()) {
        val def = parse(name, type, text, kw)
        val bad = def.abilities.filter { it.unparsed() }.map { it.text } +
            (if (def.spellEffect?.hasUnparsed() == true) listOf(text) else emptyList())
        assertTrue(bad.isEmpty(), "$name went back to unparsed: $bad")
    }

    @Test
    fun `prevention wordings stay apart from one another`() {
        check("Fog", "Instant", "Prevent all combat damage that would be dealt this turn.")
        check("Angelsong", "Instant", "Prevent all combat damage that would be dealt to players this turn.")
        // "this turn" sits in the middle on some cards and at the end on others.
        check("Thwart the Enemy", "Instant", "Prevent all damage that would be dealt this turn by creatures your opponents control.")
        check("Deep Wood", "Instant", "Prevent all damage that would be dealt to you this turn by attacking creatures.")
        check("Shelter the Weak", "Instant", "Prevent all damage that would be dealt to creatures and planeswalkers you control this turn.")
        // Maze of Ith's "to and dealt by" has its own handler; a broader prevention rule must not take it first.
        check("Maze of Ith", "Land", "{T}: Untap target attacking creature. Prevent all combat damage that would be dealt to and dealt by that creature this turn.")
    }

    @Test
    fun `the wordings modelled in this pass stay modelled`() {
        check("Palace Sentinels", "Creature — Human Soldier", "When this creature enters, you become the monarch.")
        check("Mutavault", "Land", "{1}: This land becomes a 2/2 creature with all creature types until end of turn. It's still a land.")
        check("Celestial Colonnade", "Land", "{3}{W}{U}: Until end of turn, this land becomes a 4/4 white and blue Elemental creature with flying and vigilance. It's still a land.")
        check("Bloodghast", "Creature — Vampire Spirit", "Whenever a land you control enters, you may return this creature from your graveyard to the battlefield.")
        check("Yixlid Jailer", "Creature — Zombie Wizard", "Cards in graveyards lose all abilities.")
        check("Painter's Servant", "Artifact Creature — Scarecrow", "All cards that aren't on the battlefield, spells, and permanents are the chosen color in addition to their other colors.")
        check("Heliod", "Legendary Enchantment Creature — God", "As long as your devotion to white is less than five, this creature isn't a creature.")
        check("Umezawa's Jitte", "Legendary Artifact — Equipment", "Whenever equipped creature deals combat damage, put two charge counters on this Equipment.")
        check("Waterknot", "Enchantment — Aura", "When this Aura enters, tap enchanted creature.")
        check("Muxus", "Legendary Creature — Goblin Noble", "Whenever this creature attacks, this creature gets +1/+1 until end of turn for each other Goblin you control.")
        check("Eomer", "Legendary Creature — Human Noble", "This creature enters with a +1/+1 counter on it for each other Human you control.")
        check("Kavu Primarch", "Creature — Kavu", "If this creature was kicked, it enters with four +1/+1 counters on it.", listOf("Kicker"))
        check("Questing Beast", "Legendary Creature — Beast", "Whenever this creature deals combat damage to a player, it deals that much damage to target planeswalker that player controls.")
        check("Batterskull", "Artifact — Equipment", "Living weapon\nEquip {5}", listOf("Living weapon", "Equip"))
        check("Kor Skyfisher", "Creature — Kor Soldier", "When this creature enters, return a permanent you control to its owner's hand.")
    }

    /** The mode count is read from the header; "one or more" must not be read as "one". */
    @Test
    fun `modal headers keep their own counts`() {
        for ((header, count) in listOf("Choose one —" to "one", "Choose two —" to "two", "Choose one or both —" to "one or both",
                "Choose one or more —" to "one or more", "Choose any number —" to "any number")) {
            val def = parse("Modal Test", "Instant", "$header\n• Draw a card.\n• You gain 2 life.")
            val modal = def.spellEffect as? mtg.judge.engine.Effect.Modal
            assertTrue(modal != null && modal.count == count, "\"$header\" was read as ${(def.spellEffect as? mtg.judge.engine.Effect.Modal)?.count ?: def.spellEffect}")
        }
    }

    /** An ability word before an em-dash is flavour, whatever it is called (207.2c). */
    @Test
    fun `an ability word does not stop a trigger being read`() {
        check("Monk of the Open Hand", "Creature — Elf Monk", "Flurry of Blows — Whenever you cast your second spell each turn, put a +1/+1 counter on ~.")
        check("Steppe Lynx", "Creature — Elemental Cat", "Landfall — Whenever a land enters under your control, ~ gets +2/+2 until end of turn.")
        check("Sacred Cat", "Creature — Cat", "When ~ enters, you gain 1 life.")
    }

    @Test
    fun `attack-with-N-or-more wordings stay modelled`() {
        check("Military Intelligence", "Enchantment", "Whenever you attack with two or more creatures, draw a card.")
        check("Hired Claw", "Creature — Lizard Mercenary", "Whenever you attack with one or more Lizards, ~ gets +1/+0 until end of turn.")
    }

    @Test
    fun `becomes-the-target wordings stay modelled`() {
        check("Shimmering Glasskite", "Creature — Bird Spirit", "Flying\nWhenever ~ becomes the target of a spell or ability for the first time each turn, counter that spell or ability.", listOf("flying"))
        check("Scaled Hulk", "Creature — Dinosaur", "Whenever ~ becomes the target of a spell or ability an opponent controls, put a +1/+1 counter on ~.")
        check("Vine Dryad", "Creature — Dryad", "Whenever ~ becomes the target of a spell, sacrifice it.")
    }

    @Test
    fun `one-sided damage wordings stay modelled`() {
        check("Rabid Bite", "Sorcery", "Target creature you control deals damage equal to its power to target creature you don't control.")
        check("Nature's Way", "Sorcery", "Target creature you control gains vigilance and trample until end of turn. It deals damage equal to its power to target creature you don't control.")
        check("Pounce", "Instant", "Target creature you control fights target creature you don't control.", listOf("fight"))
    }

    @Test
    fun `copy wordings stay modelled`() {
        check("Clone", "Creature — Shapeshifter", "You may have ~ enter as a copy of any creature on the battlefield.")
        check("Vesuva", "Land", "You may have ~ enter tapped as a copy of any land on the battlefield.")
        check("Phyrexian Metamorph", "Artifact Creature — Phyrexian Shapeshifter",
            "You may have ~ enter as a copy of any artifact or creature on the battlefield, except it's an artifact in addition to its other types.")
        check("Cackling Counterpart", "Instant", "Create a token that's a copy of target creature you control.")
        check("Kiki-Jiki, Mirror Breaker", "Legendary Creature — Goblin Shaman",
            "{T}: Create a token that's a copy of target nonlegendary creature you control, except it has haste. Sacrifice it at the beginning of the next end step.")
    }
}
