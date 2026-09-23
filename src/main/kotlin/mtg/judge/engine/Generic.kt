package mtg.judge.engine

import mtg.judge.oracle.OracleParser

/** Stand-in cards for things described rather than named: "a spell", "a creature", "a 5/5 Zombie token with flying". */
object Generic {
    private val colorMap = mapOf("white" to "W", "blue" to "U", "black" to "B", "red" to "R", "green" to "G")
    private val tokenRe = Regex("""^(?:(\d+)/(\d+) )?((?:(?:white|blue|black|red|green|colorless) )*)((?:[a-z]+ )*?)(?:(creature|artifact|enchantment|artifact creature) )?tokens?(?: with (.+))?$""")

    /** "5/5 zombie token", "2/2 zombie creature token", "treasure token", "1/1 white soldier creature token with flying". */
    fun token(desc: String): CardDef? {
        val n1 = desc.lowercase().removePrefix("a ").removePrefix("an ").trim()
        val legendary = n1.startsWith("legendary ")
        val named = Regex("""\s+named (.+)$""").find(n1)?.groupValues?.get(1)
        val n = n1.removePrefix("legendary ").replace(Regex("""\s+named .+$"""), "")
        val m = tokenRe.matchEntire(n) ?: return null
        val colors = m.groupValues[3].trim().split(' ').filter { it.isNotEmpty() }.mapNotNull { colorMap[it] }.joinToString("")
        val subs = m.groupValues[4].trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val creature = m.groupValues[1].isNotEmpty() || m.groupValues[5].contains("creature")
        val artifact = m.groupValues[5].contains("artifact") || subs in setOf("Treasure", "Food", "Clue", "Blood", "Powerstone", "Map")
        val typeLine = "Token " + (if (legendary) "Legendary " else "") + listOfNotNull(if (artifact) "Artifact" else null, if (creature) "Creature" else null, if (m.groupValues[5] == "enchantment") "Enchantment" else null).joinToString(" ").ifEmpty { "Permanent" } + (if (subs.isEmpty()) "" else " — $subs")
        val keywords = m.groupValues[6].split(Regex("""\s*,\s*|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val text = when (subs) { "Treasure" -> "{T}, Sacrifice this token: Add one mana of any color."; "Food" -> "{2}, {T}, Sacrifice this token: You gain 3 life."; "Clue" -> "{2}, Sacrifice this token: Draw a card."; else -> "" }
        val kwLine = keywords.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
        return OracleParser.parse("generic-token-$n", named?.let { it.split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } } } ?: if (subs.isNotEmpty()) "$subs token" else if (m.groupValues[1].isNotEmpty()) "a ${m.groupValues[1]}/${m.groupValues[2]} token" else "a token", typeLine, null, 0.0, colors,
            m.groupValues[1].ifEmpty { null }, m.groupValues[2].ifEmpty { null }, keywords, listOf(kwLine, text).filter { it.isNotEmpty() }.joinToString("\n"))
    }

    /** "a spell", "an instant", "a creature spell", "a creature" (an unnamed 1/1 whose stats are assumed). */
    fun spell(name: String): CardDef? {
        val n = name.lowercase().removePrefix("a ").removePrefix("an ").trim()
        creature(n)?.let { return it }
        if (n in setOf("counterspell", "counter", "counter spell", "generic counterspell")) return OracleParser.parse("generic-counterspell", "a counterspell", "Instant", "{1}{U}", 2.0, "U", null, null, emptyList(), "Counter target spell.")
        Regex("""^(\d+)[- ]mana (spell|instant|sorcery|creature spell|creature|artifact|enchantment|noncreature spell)$""").find(n)?.let { m ->
            val mv = m.groupValues[1].toInt(); val kind = m.groupValues[2]
            val type = when (kind) { "spell", "instant", "noncreature spell" -> "Instant"; "sorcery" -> "Sorcery"; "creature", "creature spell" -> "Creature"; "artifact" -> "Artifact"; else -> "Enchantment" }
            return OracleParser.parse("generic-$mv-mana-$kind", "a $mv mana $kind", type, if (mv == 0) "{0}" else "{$mv}", mv.toDouble(), "", if (type == "Creature") "2" else null, if (type == "Creature") "2" else null, emptyList(), "")
        }
        if (n in setOf("sorcery", "a sorcery", "sorcery spell")) return OracleParser.parse("generic-sorcery", "a sorcery", "Sorcery", "{2}", 2.0, "", null, null, emptyList(), "")
        if (n in setOf("instant", "an instant", "instant spell")) return OracleParser.parse("generic-instant", "an instant", "Instant", "{2}", 2.0, "", null, null, emptyList(), "")
        if (n in setOf("artifact", "an artifact", "artifact spell")) return OracleParser.parse("generic-artifact", "an artifact", "Artifact", "{2}", 2.0, "", null, null, emptyList(), "")
        if (n in setOf("enchantment", "an enchantment", "enchantment spell")) return OracleParser.parse("generic-enchantment", "an enchantment", "Enchantment", "{2}", 2.0, "", null, null, emptyList(), "")
        if (n in setOf("flashback spell", "spell with flashback", "flashback card")) return OracleParser.parse("generic-flashback", "a flashback spell", "Instant", "{1}{U}", 2.0, "U", null, null, listOf("Flashback"), "Draw a card.\nFlashback {2}{U}")
        if (n in setOf("removal spell", "kill spell", "removal")) return OracleParser.parse("generic-removal", "a removal spell", "Instant", "{1}{B}", 2.0, "B", null, null, emptyList(), "Destroy target creature.")
        if (n in setOf("burn spell", "burn")) return OracleParser.parse("generic-burn", "a burn spell", "Instant", "{R}", 1.0, "R", null, null, emptyList(), "This spell deals 3 damage to any target.")
        // "my creature with an Aura on it dies": which Aura it is doesn't matter to the question — that it is an
        // Aura does, because an Aura with nothing to enchant goes to the graveyard while an Equipment stays.
        if (n in setOf("aura", "aura card", "enchantment aura")) return OracleParser.parse("generic-aura", "an Aura", "Enchantment — Aura", "{1}{W}", 2.0, "W", null, null, emptyList(), "Enchant creature")
        // "I have 2 permanents": what they are doesn't matter to the question (Torment of Hailfire counts them).
        if (n in setOf("permanent", "a permanent", "nonland permanent", "a nonland permanent")) return OracleParser.parse("generic-permanent", "a permanent", "Artifact", "{1}", 1.0, "", null, null, emptyList(), "")
        if (n in setOf("equipment", "equipment card")) return OracleParser.parse("generic-equipment", "an Equipment", "Artifact — Equipment", "{2}", 2.0, "", null, null, emptyList(), "")
        if (n in setOf("basic land", "land", "a basic land", "basic land card")) return OracleParser.parse("generic-basic-land", "a basic land", if (n.contains("basic")) "Basic Land" else "Land", null, 0.0, "", null, null, emptyList(), "{T}: Add one mana of any color.")
        val typeLine = when (n) {
            "spell", "instant", "instant spell", "noncreature spell" -> "Instant"
            "sorcery", "sorcery spell" -> "Sorcery"
            "creature", "creature spell" -> "Creature"
            // "I control a commander": what matters is that it is one, not which card it is.
            "commander" -> "Legendary Creature"
            "artifact", "artifact spell" -> "Artifact"
            "enchantment", "enchantment spell" -> "Enchantment"
            "planeswalker", "planeswalker card" -> "Planeswalker"
            "battle", "battle card" -> "Battle"
            "instant card" -> "Instant"; "sorcery card" -> "Sorcery"; "creature card" -> "Creature"; "artifact card" -> "Artifact"; "enchantment card" -> "Enchantment"; "land card" -> "Land"
            // "three artifacts", "two creatures": a plural stands for the same thing the singular does.
            else -> return if (n.endsWith("s") && n.length > 2) spell(n.dropLast(1)) else null
        }
        val article = if (n.first() in "aeiou") "an" else "a"
        val pt = if (typeLine.endsWith("Creature")) (if (n == "commander") "2" else "1") else null
        return OracleParser.parse("generic-$n", "$article $n", typeLine, "{1}", 1.0, "", pt, pt, emptyList(), "")
    }

    private val creatureRe = Regex("""^(?:(\d+)/(\d+) )?((?:[a-z]+ )*?)creature(?: with (.+))?$""")

    /** "3/3 creature", "2/2 goblin creature", "4/4 creature with flying": an unnamed creature card (not a token). */
    fun creature(desc: String): CardDef? {
        val n = desc.lowercase().removePrefix("a ").removePrefix("an ").trim()
        val m = creatureRe.matchEntire(n) ?: return null
        if (m.groupValues[1].isEmpty() && m.groupValues[3].isEmpty() && m.groupValues[4].isEmpty()) return null
        val subs = m.groupValues[3].trim().split(' ').filter { it.isNotEmpty() && it !in colorMap.keys }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val colors = m.groupValues[3].trim().split(' ').mapNotNull { colorMap[it] }.joinToString("")
        val keywords0 = m.groupValues[4].split(Regex("""\s*,\s*|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        // "a Dragon" / "an Angel": the type says it flies, whatever else was left out.
        val flyers = setOf("dragon", "angel", "bird", "drake", "phoenix", "sphinx", "bat", "faerie", "griffin", "pegasus", "thopter")
        val keywords = if (subs.lowercase().split(' ').any { it in flyers } && keywords0.none { it.equals("flying", true) }) keywords0 + "flying" else keywords0
        val kwLine = keywords.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
        val article = if (n.first().lowercaseChar() in "aeiou") "an" else "a"
        return OracleParser.parse("generic-$n", "$article $n", "Creature" + (if (subs.isEmpty()) "" else " — $subs"), "{1}", 1.0, colors,
            m.groupValues[1].ifEmpty { "1" }, m.groupValues[2].ifEmpty { "1" }, keywords, kwLine)
    }

    fun isGeneric(def: CardDef) = def.oracleId.startsWith("generic-")
}
