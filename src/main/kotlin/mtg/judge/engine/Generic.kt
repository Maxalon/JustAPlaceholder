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
        return OracleParser.parse("generic-token-$n", if (subs.isEmpty()) "token" else "$subs token", typeLine, null, 0.0, colors,
            m.groupValues[1].ifEmpty { null }, m.groupValues[2].ifEmpty { null }, keywords, listOf(kwLine, text).filter { it.isNotEmpty() }.joinToString("\n"))
    }

    /** "a spell", "an instant", "a creature spell", "a creature" (an unnamed 1/1 whose stats are assumed). */
    fun spell(name: String): CardDef? {
        val n = name.lowercase().removePrefix("a ").removePrefix("an ").trim()
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

    fun isGeneric(def: CardDef) = def.oracleId.startsWith("generic-")
}
