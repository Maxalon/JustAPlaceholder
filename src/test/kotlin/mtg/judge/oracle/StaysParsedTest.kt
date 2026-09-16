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
}
