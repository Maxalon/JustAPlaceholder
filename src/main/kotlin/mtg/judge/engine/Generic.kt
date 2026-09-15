package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/** Stand-in cards for things described rather than named: "a spell", "a creature", "a 5/5 Zombie token with flying". */
object Generic {
    private val colorMap = mapOf("white" to "W", "blue" to "U", "black" to "B", "red" to "R", "green" to "G")
    private val tokenRe = Regex("""^(?:(\d+)/(\d+) )?((?:(?:white|blue|black|red|green|colorless) )*)((?:[a-z]+ )*?)(?:(creature|artifact|enchantment|artifact creature) )?tokens?(?: with (.+))?$""")

    /** "5/5 zombie token", "2/2 zombie creature token", "treasure token", "1/1 white soldier creature token with flying". */
    fun token(desc: String): CardDef? {
        val n = desc.lowercase().removePrefix("a ").removePrefix("an ").trim()
        val m = tokenRe.matchEntire(n) ?: return null
        val colors = m.groupValues[3].trim().split(' ').filter { it.isNotEmpty() }.mapNotNull { colorMap[it] }.joinToString("")
        val subs = m.groupValues[4].trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val creature = m.groupValues[1].isNotEmpty() || m.groupValues[5].contains("creature")
        val artifact = m.groupValues[5].contains("artifact") || subs in setOf("Treasure", "Food", "Clue", "Blood", "Powerstone", "Map")
        val typeLine = "Token " + listOfNotNull(if (artifact) "Artifact" else null, if (creature) "Creature" else null, if (m.groupValues[5] == "enchantment") "Enchantment" else null).joinToString(" ").ifEmpty { "Permanent" } + (if (subs.isEmpty()) "" else " — $subs")
        val keywords = m.groupValues[6].split(Regex("""\s*,\s*|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val text = when (subs) { "Treasure" -> "{T}, Sacrifice this token: Add one mana of any color."; "Food" -> "{2}, {T}, Sacrifice this token: You gain 3 life."; "Clue" -> "{2}, Sacrifice this token: Draw a card."; else -> "" }
        val kwLine = keywords.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
        return OracleParser.parse("generic-token-$n", if (subs.isNotEmpty()) "$subs token" else if (m.groupValues[1].isNotEmpty()) "a ${m.groupValues[1]}/${m.groupValues[2]} token" else "a token", typeLine, null, 0.0, colors,
            m.groupValues[1].ifEmpty { null }, m.groupValues[2].ifEmpty { null }, keywords, listOf(kwLine, text).filter { it.isNotEmpty() }.joinToString("\n"))
    }

    /** "a spell", "an instant", "a creature spell", "a creature" (an unnamed 1/1 whose stats are assumed). */
    fun spell(name: String): CardDef? {
        val n = name.lowercase().removePrefix("a ").removePrefix("an ").trim()
        creature(n)?.let { return it }
        if (n in setOf("counterspell", "counter", "counter spell", "generic counterspell")) return OracleParser.parse("generic-counterspell", "a counterspell", "Instant", "{1}{U}", 2.0, "U", null, null, emptyList(), "Counter target spell.")
        if (n in setOf("removal spell", "kill spell", "removal")) return OracleParser.parse("generic-removal", "a removal spell", "Instant", "{1}{B}", 2.0, "B", null, null, emptyList(), "Destroy target creature.")
        if (n in setOf("burn spell", "burn")) return OracleParser.parse("generic-burn", "a burn spell", "Instant", "{R}", 1.0, "R", null, null, emptyList(), "This spell deals 3 damage to any target.")
        if (n in setOf("basic land", "land", "a basic land", "basic land card")) return OracleParser.parse("generic-basic-land", "a basic land", if (n.contains("basic")) "Basic Land" else "Land", null, 0.0, "", null, null, emptyList(), "")
        val typeLine = when (n) {
            "spell", "instant", "instant spell", "noncreature spell" -> "Instant"
            "sorcery", "sorcery spell" -> "Sorcery"
            "creature", "creature spell" -> "Creature"
            "artifact", "artifact spell" -> "Artifact"
            "enchantment", "enchantment spell" -> "Enchantment"
            else -> return null
        }
        return OracleParser.parse("generic-$n", "a $n", typeLine, "{1}", 1.0, "", if (typeLine == "Creature") "1" else null, if (typeLine == "Creature") "1" else null, emptyList(), "")
    }

    private val creatureRe = Regex("""^(?:(\d+)/(\d+) )?((?:[a-z]+ )*?)creature(?: with (.+))?$""")

    /** "3/3 creature", "2/2 goblin creature", "4/4 creature with flying": an unnamed creature card (not a token). */
    fun creature(desc: String): CardDef? {
        val n = desc.lowercase().removePrefix("a ").removePrefix("an ").trim()
        val m = creatureRe.matchEntire(n) ?: return null
        if (m.groupValues[1].isEmpty() && m.groupValues[3].isEmpty() && m.groupValues[4].isEmpty()) return null
        val subs = m.groupValues[3].trim().split(' ').filter { it.isNotEmpty() && it !in colorMap.keys }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val colors = m.groupValues[3].trim().split(' ').mapNotNull { colorMap[it] }.joinToString("")
        val keywords = m.groupValues[4].split(Regex("""\s*,\s*|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val kwLine = keywords.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
        return OracleParser.parse("generic-$n", "a $n", "Creature" + (if (subs.isEmpty()) "" else " — $subs"), "{1}", 1.0, colors,
            m.groupValues[1].ifEmpty { "1" }, m.groupValues[2].ifEmpty { "1" }, keywords, kwLine)
    }

    fun isGeneric(def: CardDef) = def.oracleId.startsWith("generic-")
}
