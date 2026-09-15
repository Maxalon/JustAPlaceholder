package mtg.judge.oracle

import mtg.judge.engine.Ability
import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.CardDef
import mtg.judge.engine.Effect
import mtg.judge.engine.Kind
import mtg.judge.engine.ObjFilter
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.StaticEffect
import mtg.judge.engine.TargetSpec
import mtg.judge.engine.Trigger
import mtg.judge.engine.TriggeredAbility
import mtg.judge.engine.UnparsedAbility
import mtg.judge.engine.Who

/**
 * Turns Oracle text into abilities and effects the engine understands.
 *
 * Deliberately template-based: it recognises the common, well-templated phrasings and
 * returns [Effect.Unparsed] / [UnparsedAbility] for everything else, so the engine can
 * say "I can't model this" instead of guessing. Coverage grows by adding templates.
 */
object OracleParser {
    private val reminder = Regex("""\s*\([^)]*\)""")
    private val numberWords = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)

    fun parse(oracleId: String, name: String, typeLine: String, manaCost: String?, manaValue: Double, colors: String, power: String?, toughness: String?, keywords: Collection<String>, oracleText: String): CardDef {
        val (supers, types, subs) = CardDef.splitTypeLine(typeLine)
        val text = oracleText.substringBefore("\n//\n")   // front face only, for now
        val lines = text.lines().map { it.replace(reminder, "").trim() }.filter { it.isNotEmpty() }
        val abilities = mutableListOf<Ability>()
        var spellEffect: Effect? = null
        val isSpell = "Instant" in types || "Sorcery" in types
        val spellLines = mutableListOf<String>()
        for (line in lines) {
            val selfRef = selfReference(line, name)
            val statics = parseStatic(selfRef)
            when {
                isKeywordLine(selfRef, keywords) -> selfRef.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                    val kw = keywords.map { it.lowercase() }.filter { part.lowercase() == it || part.lowercase().startsWith("$it ") }.maxByOrNull { it.length } ?: part.substringBefore(' ').lowercase()
                    abilities += StaticAbility(part, kw)
                }
                statics.isNotEmpty() -> abilities += StaticAbility(selfRef, null, statics)
                selfRef.startsWith("When ", true) || selfRef.startsWith("Whenever ", true) || selfRef.startsWith("At ", true) || Regex("""^(Landfall|Constellation|Magecraft|Heroic|Raid|Enrage|Battalion|Alliance|Coven)\s+—\s+(When|Whenever|At)\b""").containsMatchIn(selfRef) -> abilities += parseTriggeredAll(selfRef)
                isActivated(selfRef) -> abilities += parseActivated(selfRef)
                isSpell -> spellLines += selfRef
                else -> abilities += UnparsedAbility(selfRef)
            }
        }
        if (isSpell && spellLines.isNotEmpty()) spellEffect = parseEffect(spellLines.joinToString(" "))
        return CardDef(oracleId, name, typeLine, supers, types, subs, manaCost, manaValue, colors.toSet(), CardDef.parseStat(power), CardDef.parseStat(toughness),
            keywords.map { it.lowercase() }.toSet(), abilities, spellEffect, oracleText)
    }

    /** Replace the card's own name and "this creature/permanent/…" with "~". */
    private fun selfReference(line: String, name: String): String {
        var s = line.replace(name, "~")
        val shortName = name.substringBefore(",")
        if (shortName != name) s = s.replace(shortName, "~")
        s = s.replace(Regex("""\b[Tt]his (creature|permanent|artifact|enchantment|land|planeswalker|spell|card|Aura|Equipment|Vehicle|token|battle|Saga|Class|Room)\b"""), "~")
        return s
    }

    private fun isKeywordLine(line: String, keywords: Collection<String>): Boolean {
        val parts = line.trimEnd('.').split(',', ';').map { it.trim().lowercase() }
        val kws = keywords.map { it.lowercase() }.toSet()
        return parts.isNotEmpty() && parts.all { p -> kws.any { k -> p == k || p.startsWith("$k ") } }
    }

    private fun isActivated(line: String): Boolean {
        val colon = line.indexOf(':')
        if (colon <= 0) return false
        val cost = line.substring(0, colon)
        return cost.contains('{') || cost.contains("Sacrifice", true) || cost.contains("Discard", true) || cost.contains("Pay", true) || cost.contains("Tap ", true) || cost.contains("Remove", true) || cost.contains("Exile", true)
    }

    private val manaRe = Regex("""^Add (\{[^}]+\}(?:\{[^}]+\})*(?:(?:, | or | and )\{[^}]+\}(?:\{[^}]+\})*)*|(?:one|two|three|four|five|N|X) mana (?:of any (?:one )?color|in any combination of colors|of any color(?: or type)?)|an amount of mana .+)\.?$""", RegexOption.IGNORE_CASE)

    private fun parseActivated(line: String): Ability {
        val colon = line.indexOf(':')
        val effText = line.substring(colon + 1).trim()
        val effect = manaRe.matchEntire(effText.substringBefore(". ").trimEnd('.'))?.let { Effect.AddMana(it.groupValues[1]) }?.let { mana ->
            val rest = effText.substringAfter(". ", "").trim()
            if (rest.isEmpty()) mana else Effect.Seq(listOf(mana, parseEffect(rest)))
        } ?: parseEffect(effText)
        return ActivatedAbility(line.substring(0, colon).trim(), effect, line)
    }

    private val triggerRe = Regex("""^(When|Whenever|At)\s+(.+?),\s+(.+)$""", RegexOption.IGNORE_CASE)

    private fun parseTriggered(line: String): Ability {
        val m = triggerRe.matchEntire(line) ?: return UnparsedAbility(line)
        val cond = m.groupValues[2].trim().replace(Regex("""^Landfall — """), "")
        val trigger = parseTrigger(cond)
        return TriggeredAbility(trigger, parseEffect(m.groupValues[3]), line)
    }

    /** "Whenever ~ enters or attacks, …" is two triggered abilities with the same effect. */
    private fun parseTriggeredAll(line: String): List<Ability> {
        val m = triggerRe.matchEntire(line.replace(Regex("""^(Landfall|Constellation|Magecraft|Heroic|Raid|Enrage|Battalion|Alliance|Coven)\s+—\s+"""), "")) ?: return listOf(UnparsedAbility(line))
        val cond = m.groupValues[2].trim()
        Regex("""^~ enters or attacks$""", RegexOption.IGNORE_CASE).matchEntire(cond)?.let {
            val eff = parseEffect(m.groupValues[3]); return listOf(TriggeredAbility(Trigger.ThisEnters, eff, line), TriggeredAbility(Trigger.ThisAttacks, eff, line))
        }
        return listOf(parseTriggered(m.groupValues[1] + " " + cond + ", " + m.groupValues[3]))
    }

    private val spellCastRe = Regex("""^(an opponent|you|a player|another player|each player) casts? (a|an|your first|their first) (.+?)(?: spell)?$""", RegexOption.IGNORE_CASE)

    private val stepNames = mapOf("upkeep" to "upkeep", "draw step" to "draw", "precombat main phase" to "precombat_main", "first main phase" to "precombat_main", "combat" to "combat",
        "end step" to "end", "next end step" to "end", "postcombat main phase" to "postcombat_main", "second main phase" to "postcombat_main", "untap step" to "untap", "end of combat step" to "end_of_combat", "turn" to "upkeep")

    fun parseTrigger(cond: String): Trigger {
        val c = cond.trim().trimEnd(',')
        Regex("""^the beginning of (your|each player's|each|an opponent's|each opponent's|the) (upkeep|draw step|precombat main phase|first main phase|combat on your turn|combat|end step|next end step|postcombat main phase|second main phase|untap step|end of combat step|turn)$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            val whose = when (m.groupValues[1].lowercase()) { "your" -> Who.YOU; "an opponent's", "each opponent's" -> Who.OPPONENT; else -> Who.ANY_PLAYER }
            val stepKey = m.groupValues[2].lowercase().removeSuffix(" on your turn")
            val whose2 = if (m.groupValues[2].lowercase().endsWith("on your turn")) Who.YOU else whose
            return Trigger.BeginningOfStep(stepNames[stepKey] ?: stepKey, whose2)
        }
        Regex("""^~ deals (combat )?damage to (a player|an opponent|a creature|a player or planeswalker|a permanent or player)$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            return Trigger.ThisDealsDamage(m.groupValues[1].isNotEmpty(), when (m.groupValues[2].lowercase()) { "a player", "an opponent", "a player or planeswalker" -> true; "a creature" -> false; else -> null })
        }
        if (Regex("""^~ deals (combat )?damage$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisDealsDamage(c.contains("combat", true), null)
        if (Regex("""^you attack( with one or more creatures)?$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.YouAttack
        if (Regex("""^you gain life$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.YouGainLife
        if (Regex("""^you cast ~$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisCast
        Regex("""^(?:Landfall — )?(?:whenever )?(another |one or more |a |an )?(.+?) (?:enters|enter)(?: the battlefield)?(?: under your control)?$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            if (m.groupValues[2].equals("~", true)) return Trigger.ThisEnters
            val underYou = c.contains("under your control", true)
            val f = parseFilter(m.groupValues[2], Kind.PERMANENT).let { if (underYou && it.controller == null) it.copy(controller = Who.YOU) else it }
            if (!f.verifiable) return Trigger.Unknown(c)
            return Trigger.PermanentEnters(f, m.groupValues[1].trim().equals("another", true))
        }
        Regex("""^(another |a |an )?(.+?) dies$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            if (m.groupValues[2].equals("~", true)) return Trigger.ThisDies
            val f = parseFilter(m.groupValues[2], Kind.CREATURE)
            if (!f.verifiable) return Trigger.Unknown(c)
            return Trigger.PermanentDies(f, m.groupValues[1].trim().equals("another", true))
        }
        spellCastRe.matchEntire(c)?.let { m ->
            val who = when (m.groupValues[1].lowercase()) { "you" -> Who.YOU; "an opponent" -> Who.OPPONENT; else -> Who.ANY_PLAYER }
            val what = m.groupValues[3].trim().lowercase()
            val filter = if (what == "spell") null else parseFilter(what.removeSuffix(" spell"), Kind.SPELL)
            return Trigger.SpellCast(who, filter)
        }
        if (Regex("""^~ enters(?: the battlefield)?$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisEnters
        if (Regex("""^~ dies$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisDies
        if (Regex("""^~ leaves the battlefield$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisLeavesBattlefield
        if (Regex("""^~ attacks$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisAttacks
        return Trigger.Unknown(c)
    }

    // ---- static abilities ----------------------------------------------------------------

    private val anthemRe = Regex("""^(all |each |other )?(.+?) (?:get|gets) ([+-]\d+)/([+-]\d+)(?: and (?:have|has) (.+?))?\.?$""", RegexOption.IGNORE_CASE)
    private val grantRe = Regex("""^(all |each |other )?(.+?) (?:have|has) (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val keywordList = setOf("flying", "first strike", "double strike", "deathtouch", "haste", "hexproof", "indestructible", "lifelink", "menace", "reach", "trample", "vigilance", "flash", "defender", "protection from everything", "ward 1", "ward 2")

    /** "Creatures you control get +1/+1", "Other Elf creatures you control get +1/+1 and have trample", "Creatures you control have haste". */
    fun parseStatic(line: String): List<StaticEffect> {
        Regex("""^~ enters(?: the battlefield)? tapped\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { return listOf(StaticEffect.EntersTapped) }
        Regex("""^~ enters(?: the battlefield)? with (a|an|X|\w+) ([+-]\d/[+-]\d|\w+) counters? on it\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val n = if (m.groupValues[1].equals("x", true)) null else (number(m.groupValues[1]) ?: return emptyList())
            return listOf(StaticEffect.EntersWithCounters(m.groupValues[2], n))
        }
        Regex("""^~ can't (block|attack|be countered|be blocked|attack or block)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { return listOf(StaticEffect.Cant(it.groupValues[1].lowercase())) }
        if (Regex("""^(As an additional cost to cast ~|~ costs \{[^}]+\} (less|more) to cast|You may cast ~ )""", RegexOption.IGNORE_CASE).containsMatchIn(line)) return listOf(StaticEffect.CostText(line))
        if (line.contains("until end of turn", true) || line.startsWith("~", true) || line.contains(" as long as ", true) || line.contains(" for each ", true) || line.contains(" where ", true)) return emptyList()
        anthemRe.matchEntire(line)?.let { m ->
            val filter = parseFilter(m.groupValues[2], Kind.CREATURE).let { if (m.groupValues[1].trim().equals("other", true)) it.copy(other = true) else it }
            if (!filter.verifiable || Kind.CREATURE !in filter.kinds && Kind.PERMANENT !in filter.kinds) return emptyList()
            val out = mutableListOf<StaticEffect>(StaticEffect.PtModify(filter, m.groupValues[3].toInt(), m.groupValues[4].toInt()))
            m.groupValues[5].takeIf { it.isNotBlank() }?.let { kws -> keywordsIn(kws)?.let { out += StaticEffect.KeywordGrant(filter, it) } ?: return emptyList() }
            return out
        }
        grantRe.matchEntire(line)?.let { m ->
            val kws = keywordsIn(m.groupValues[3]) ?: return emptyList()
            val filter = parseFilter(m.groupValues[2], Kind.CREATURE).let { if (m.groupValues[1].trim().equals("other", true)) it.copy(other = true) else it }
            if (!filter.verifiable) return emptyList()
            return listOf(StaticEffect.KeywordGrant(filter, kws))
        }
        return emptyList()
    }

    private fun keywordsIn(text: String): Set<String>? {
        val parts = text.lowercase().trimEnd('.').split(Regex(""",\s*|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isNotEmpty() && parts.all { it in keywordList }) parts.toSet() else null
    }

    // ---- effects -------------------------------------------------------------------------

    private val sentenceSplit = Regex("""(?<=\.)\s+(?=[A-Z~])""")

    fun parseEffect(text: String): Effect {
        val t = text.trim()
        val sentences = t.split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size > 1) return Effect.Seq(sentences.map { parseSentence(it) })
        return parseSentence(t)
    }

    private val unlessRe = Regex("""^(.+?) unless (that player|its controller|an opponent|you|target player|they) pays? (\{[^}]+\}(?:\{[^}]+\})*|\d+ life)\.?$""", RegexOption.IGNORE_CASE)
    private val mayRe = Regex("""^you may (.+)$""", RegexOption.IGNORE_CASE)
    private val drawRe = Regex("""^(you |target player |that player |each player )?draws? (a|an|\w+|\d+) cards?\.?$""", RegexOption.IGNORE_CASE)
    private val damageRe = Regex("""^(?:~|it) deals (\d+|X) damage to (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val counterRe = Regex("""^counter target (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val destroyRe = Regex("""^destroy target (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val exileRe = Regex("""^exile target (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val tapRe = Regex("""^tap target (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val untapRe = Regex("""^untap target (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val pumpRe = Regex("""^target (.+?) gets ([+-]\d+)/([+-]\d+) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val pumpGainRe = Regex("""^target (.+?) gets ([+-]\d+)/([+-]\d+) and gains (.+?) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val gainRe = Regex("""^target (.+?) gains (.+?) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val gainLifeRe = Regex("""^(you|target player|that player) gains? (\d+) life\.?$""", RegexOption.IGNORE_CASE)
    private val loseLifeRe = Regex("""^(you|target player|that player|each opponent) loses? (\d+) life\.?$""", RegexOption.IGNORE_CASE)

    private val selfPumpRe = Regex("""^~ gets ([+-]\d+)/([+-]\d+) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val massPumpRe = Regex("""^(?:all |each )?(.+?) (?:get|gets) ([+-]\d+)/([+-]\d+) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val countersOnRe = Regex("""^put (a|an|\w+|\d+|X) ([+-]\d/[+-]\d|\w+) counters? on (~|target .+?|each .+?)\.?$""", RegexOption.IGNORE_CASE)
    private val forAllRe = Regex("""^(destroy|exile|tap|untap) (?:all|each) (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val damageEachRe = Regex("""^(?:~|it) deals (\d+) damage to each (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val narratedRes: List<Pair<Regex, List<String>>> = listOf(
        Regex("""^scry (\d+)\.?$""", RegexOption.IGNORE_CASE) to listOf("701.22a"),
        Regex("""^surveil (\d+)\.?$""", RegexOption.IGNORE_CASE) to listOf("701.25a"),
        Regex("""^(?:you |target player |each player )?mills? (\w+|\d+) cards?\.?$""", RegexOption.IGNORE_CASE) to listOf("701.17a"),
        Regex("""^look at the top (\w+|\d+) cards? of your library.*$""", RegexOption.IGNORE_CASE) to listOf("701.22a"),
        Regex("""^search your library for (?:a|an|up to \w+) .+?(?:, then shuffle|\. Then shuffle|then shuffle)?\.?$""", RegexOption.IGNORE_CASE) to listOf("701.23a", "701.24a"),
        Regex("""^shuffle\.?$""", RegexOption.IGNORE_CASE) to listOf("701.24a"),
        Regex("""^(?:you |target player |each player )?discards? (a|an|\w+|\d+) cards?(?: at random)?\.?$""", RegexOption.IGNORE_CASE) to listOf("701.9a"),
        Regex("""^draw (a|\w+) cards?, then discard (a|\w+) cards?\.?$""", RegexOption.IGNORE_CASE) to listOf("121.1", "701.9a"),
        Regex("""^return target (.+?) card from your graveyard to your hand\.?$""", RegexOption.IGNORE_CASE) to listOf("400.7"),
        Regex("""^return (~|target .+?) to its owner's hand\.?$""", RegexOption.IGNORE_CASE) to listOf("400.7"),
        Regex("""^sacrifice (~|a|an|\w+) .*$""", RegexOption.IGNORE_CASE) to listOf("701.21a"),
        Regex("""^sacrifice ~\.?$""", RegexOption.IGNORE_CASE) to listOf("701.21a"),
        Regex("""^create (a|an|\w+|\d+|X) .+? tokens?.*$""", RegexOption.IGNORE_CASE) to listOf("701.7a"),
        Regex("""^you gain (\d+) life for each .+$""", RegexOption.IGNORE_CASE) to listOf("119.3"),
        Regex("""^regenerate (~|target .+?)\.?$""", RegexOption.IGNORE_CASE) to listOf("701.19a"),
        Regex("""^~ deals damage equal to .+$""", RegexOption.IGNORE_CASE) to listOf("120.3"),
        Regex("""^you get \{E\}.*$""", RegexOption.IGNORE_CASE) to listOf("122.1"),
    )

    private fun parseSentence(s: String): Effect {
        selfPumpRe.matchEntire(s)?.let { return Effect.PumpSelf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        massPumpRe.matchEntire(s)?.let { m -> if (!m.groupValues[1].startsWith("target", true)) { val f = parseFilter(m.groupValues[1], Kind.CREATURE); if (f.verifiable) return Effect.PumpAll(f, m.groupValues[2].toInt(), m.groupValues[3].toInt()) } }
        countersOnRe.matchEntire(s)?.let { m ->
            val n = number(m.groupValues[1]) ?: return Effect.Unparsed(s)
            val where = m.groupValues[3]
            return when {
                where == "~" -> Effect.PutCounters(null, m.groupValues[2], n)
                where.startsWith("target", true) -> Effect.PutCounters(target(where), m.groupValues[2], n)
                else -> Effect.Unparsed(s)
            }
        }
        forAllRe.matchEntire(s)?.let { m -> val f = parseFilter(m.groupValues[2], Kind.PERMANENT); if (f.verifiable) return Effect.ForAll(f, m.groupValues[1].lowercase()) }
        damageEachRe.matchEntire(s)?.let { m -> val f = parseFilter(m.groupValues[2], Kind.CREATURE); if (f.verifiable) return Effect.ForAll(f, "damage", m.groupValues[1].toInt()) }
        for ((re, rules) in narratedRes) if (re.matches(s)) return Effect.Narrated(s.trimEnd('.'), rules)
        unlessRe.matchEntire(s)?.let { m ->
            val payer = when (m.groupValues[2].lowercase()) { "you" -> Who.YOU; "an opponent" -> Who.OPPONENT; "target player" -> Who.TARGET_PLAYER; "its controller" -> Who.CONTROLLER_OF_TARGET; else -> Who.THAT_PLAYER }
            return Effect.UnlessPays(parseSentence(m.groupValues[1]), payer, m.groupValues[3])
        }
        mayRe.matchEntire(s)?.let { return Effect.May(parseSentence(it.groupValues[1])) }
        drawRe.matchEntire(s)?.let { m ->
            val who = when (m.groupValues[1].trim().lowercase()) { "target player" -> Who.TARGET_PLAYER; "that player" -> Who.THAT_PLAYER; "each player" -> Who.ANY_PLAYER; else -> Who.YOU }
            return Effect.Draw(who, number(m.groupValues[2]) ?: return Effect.Unparsed(s))
        }
        damageRe.matchEntire(s)?.let { m -> return Effect.Damage(m.groupValues[1].toIntOrNull() ?: return Effect.Unparsed(s), target(m.groupValues[2])) }
        counterRe.matchEntire(s)?.let { return Effect.Counter(target(it.groupValues[1], Kind.SPELL)) }
        destroyRe.matchEntire(s)?.let { return Effect.Destroy(target(it.groupValues[1])) }
        exileRe.matchEntire(s)?.let { return Effect.Exile(target(it.groupValues[1])) }
        tapRe.matchEntire(s)?.let { return Effect.Tap(target(it.groupValues[1])) }
        untapRe.matchEntire(s)?.let { return Effect.Untap(target(it.groupValues[1])) }
        pumpRe.matchEntire(s)?.let { return Effect.Pump(target(it.groupValues[1]), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        pumpGainRe.matchEntire(s)?.let { m -> keywordsIn(m.groupValues[4])?.let { kws -> return Effect.Seq(listOf(Effect.Pump(target(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toInt()), Effect.GainKeywords(target(m.groupValues[1]), kws))) } }
        gainRe.matchEntire(s)?.let { m -> keywordsIn(m.groupValues[2])?.let { kws -> return Effect.GainKeywords(target(m.groupValues[1]), kws) } }
        gainLifeRe.matchEntire(s)?.let { return Effect.GainLife(who(it.groupValues[1]), it.groupValues[2].toInt()) }
        loseLifeRe.matchEntire(s)?.let { return Effect.LoseLife(who(it.groupValues[1]), it.groupValues[2].toInt()) }
        return Effect.Unparsed(s)
    }

    private fun who(s: String) = when (s.lowercase()) { "you" -> Who.YOU; "target player" -> Who.TARGET_PLAYER; "each opponent" -> Who.OPPONENT; else -> Who.THAT_PLAYER }
    private fun number(s: String): Int? = s.toIntOrNull() ?: numberWords[s.lowercase()]

    private fun target(desc: String, defaultKind: Kind? = null): TargetSpec {
        val d = desc.trim().removePrefix("target ").trim()
        return TargetSpec(parseFilter(d, defaultKind), d)
    }

    /** "elves" -> "elf", "goblins" -> "goblin", "merfolk" -> "merfolk". */
    fun singular(w: String): String = when {
        w == "elves" -> "elf"; w == "dwarves" -> "dwarf"; w == "wolves" -> "wolf"; w == "thieves" -> "thief"
        w.endsWith("ies") -> w.dropLast(3) + "y"
        w.endsWith("sses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes") -> w.dropLast(2)
        w.endsWith("s") && !w.endsWith("ss") && w !in setOf("merfolk", "kithkin", "moonfolk", "sphinx", "gnomes") -> w.dropLast(1)
        else -> w
    }

    private val kindWords = mapOf(
        "creature" to Kind.CREATURE, "creatures" to Kind.CREATURE, "artifact" to Kind.ARTIFACT, "enchantment" to Kind.ENCHANTMENT,
        "land" to Kind.LAND, "planeswalker" to Kind.PLANESWALKER, "battle" to Kind.BATTLE, "permanent" to Kind.PERMANENT,
        "spell" to Kind.SPELL, "ability" to Kind.ABILITY, "player" to Kind.PLAYER, "opponent" to Kind.PLAYER, "card" to Kind.CARD,
    )

    /** "creature or player", "noncreature spell", "activated or triggered ability", "creature an opponent controls", "any target". */
    fun parseFilter(desc: String, defaultKind: Kind? = null): ObjFilter {
        val d = desc.lowercase().trim().trimEnd('.')
        if (d == "any target") return ObjFilter(setOf(Kind.CREATURE, Kind.PLAYER, Kind.PLANESWALKER, Kind.BATTLE), raw = desc)
        var controller: Who? = null
        var core = d
        Regex("""\s+(you control|an opponent controls|you don't control)$""").find(core)?.let { m ->
            controller = when (m.groupValues[1]) { "you control" -> Who.YOU; else -> Who.OPPONENT }
            core = core.removeRange(m.range)
        }
        val kinds = mutableSetOf<Kind>(); val notKinds = mutableSetOf<Kind>(); val unknown = mutableListOf<String>()
        val subtypes = mutableSetOf<String>(); val keywords = mutableSetOf<String>()
        var attacking: Boolean? = null; var tapped: Boolean? = null; var token: Boolean? = null; var legendary: Boolean? = null
        // "with flying" / "with reach or flying" -> keyword requirements
        Regex("""\s+with ([a-z ]+)$""").find(core)?.let { m ->
            val kws = m.groupValues[1].split(Regex("""\s*,\s*|\s+or\s+|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
            if (kws.all { it in keywordList }) { keywords += kws; core = core.removeRange(m.range) }
        }
        for (w in core.split(Regex("""[\s,]+|\bor\b""")).map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                w in kindWords -> kinds += kindWords.getValue(w)
                w.startsWith("non") && w.removePrefix("non") in kindWords -> notKinds += kindWords.getValue(w.removePrefix("non"))
                w == "activated" || w == "triggered" -> { /* ability qualifiers: both counterable the same way */ }
                w == "attacking" -> attacking = true
                w == "tapped" -> tapped = true
                w == "untapped" -> tapped = false
                w == "token" -> token = true
                w == "nontoken" -> token = false
                w == "legendary" -> legendary = true
                w == "nonlegendary" -> legendary = false
                w == "target" || w == "a" || w == "an" || w == "the" || w == "other" || w == "all" || w == "each" -> {}
                w.length > 2 && w.all { it.isLetter() } && kinds.isEmpty() -> subtypes += singular(w)   // "Elf creatures", "Goblin"
                w.length > 2 && w.all { it.isLetter() } -> unknown += w
                else -> unknown += w
            }
        }
        // A subtype word alone ("Elves you control") implies creature.
        if (kinds.isEmpty() && subtypes.isNotEmpty()) kinds += Kind.CREATURE
        if (kinds.isEmpty() && notKinds.isNotEmpty()) kinds += defaultKind ?: Kind.PERMANENT
        if (kinds.isEmpty() && defaultKind != null) kinds += defaultKind
        return ObjFilter(kinds, notKinds, controller, attacking, tapped, unknown, desc, subtypes, keywords, token, legendary)
    }
}
