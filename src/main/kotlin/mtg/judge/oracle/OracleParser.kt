package mtg.judge.oracle

import mtg.judge.engine.Ability
import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.CardDef
import mtg.judge.engine.Effect
import mtg.judge.engine.Kind
import mtg.judge.engine.ObjFilter
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.StaticEffect
import mtg.judge.engine.Replacement
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
        val rawLines = text.lines().map { it.replace(reminder, "").trim() }.filter { it.isNotEmpty() }
        // Modal text: "Choose one —" followed by "• mode" lines becomes one line the effect parser understands.
        val lines = mutableListOf<String>()
        var i0 = 0
        while (i0 < rawLines.size) {
            val l = rawLines[i0]
            if (Regex("""^(.*?)(Choose (one|two|three|any number|one or more|up to \w+)(?: or more)?(?: —|\.)?)\s*$""", RegexOption.IGNORE_CASE).matches(l) && rawLines.getOrNull(i0 + 1)?.startsWith("•") == true) {
                val modes = mutableListOf<String>()
                var j = i0 + 1
                while (j < rawLines.size && rawLines[j].startsWith("•")) { modes += rawLines[j].removePrefix("•").trim(); j++ }
                lines += l.trimEnd() + " " + modes.joinToString(" ") { "• $it" }
                i0 = j
            } else { lines += l; i0++ }
        }
        val abilities = mutableListOf<Ability>()
        var spellEffect: Effect? = null
        var enchant: ObjFilter? = null
        val isSpell = "Instant" in types || "Sorcery" in types
        val spellLines = mutableListOf<String>()
        for (line in lines) {
            val selfRef = selfReference(line, name)
            val statics = parseStatic(selfRef)
            when {
                Regex("""^Activate only .+$""", RegexOption.IGNORE_CASE).matches(selfRef) -> {
                    val i = abilities.indexOfLast { it is ActivatedAbility }
                    if (i >= 0) abilities[i] = (abilities[i] as ActivatedAbility).copy(restriction = selfRef.trimEnd('.'), text = abilities[i].text + " " + selfRef) else abilities += UnparsedAbility(selfRef)
                }
                isKeywordLine(selfRef, keywords) -> selfRef.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                    val kw = keywords.map { it.lowercase() }.filter { part.lowercase() == it || part.lowercase().startsWith("$it ") }.maxByOrNull { it.length } ?: part.substringBefore(' ').lowercase()
                    when (kw) {
                        "enchant" -> { val what = part.substring(7).trim().trimEnd('.'); enchant = if (what.equals("player", true)) ObjFilter(setOf(Kind.PLAYER), raw = what) else parseFilter(what, Kind.PERMANENT); abilities += StaticAbility(part, kw) }
                        "equip" -> abilities += ActivatedAbility(part.trimEnd('.'), Effect.Attach(TargetSpec(ObjFilter(setOf(Kind.CREATURE), controller = Who.YOU, raw = "creature you control"), "creature you control")), part, "Activate only as a sorcery")
                        "cycling" -> abilities += ActivatedAbility(part.trimEnd('.') + " (discard this card from your hand)", Effect.Draw(Who.YOU, 1), part, "Activate only while this card is in your hand")
                        "prowess" -> abilities += TriggeredAbility(Trigger.SpellCast(Who.YOU, ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.CREATURE), raw = "noncreature spell")), Effect.PumpSelf(1, 1), "Prowess (Whenever you cast a noncreature spell, ~ gets +1/+1 until end of turn.)")
                        "unearth" -> abilities += ActivatedAbility(part.trimEnd('.') + " (from your graveyard)", Effect.Narrated("return ~ from your graveyard to the battlefield; it gains haste; exile it at the beginning of the next end step or if it would leave the battlefield", listOf("702.84a")), part, "Activate only as a sorcery")
                        "level up" -> abilities += ActivatedAbility(part.trimEnd('.'), Effect.PutCounters(null, "level", 1), part, "Activate only as a sorcery")
                        "crew" -> abilities += ActivatedAbility(part.trimEnd('.') + " (tap any number of other untapped creatures you control with total power N or more)", Effect.Narrated("~ becomes an artifact creature until end of turn", listOf("702.122a")), part)
                        else -> abilities += StaticAbility(part, kw)
                    }
                }
                statics.isNotEmpty() -> abilities += StaticAbility(selfRef, null, statics)
                selfRef.startsWith("When ", true) || selfRef.startsWith("Whenever ", true) || selfRef.startsWith("At ", true) || Regex("""^(Landfall|Constellation|Magecraft|Heroic|Raid|Enrage|Battalion|Alliance|Coven)\s+—\s+(When|Whenever|At)\b""").containsMatchIn(selfRef) -> abilities += parseTriggeredAll(selfRef)
                isActivated(selfRef) -> abilities += parseActivated(selfRef)
                isSpell -> spellLines += selfRef
                else -> abilities += UnparsedAbility(selfRef)
            }
        }
        if (isSpell && spellLines.isNotEmpty()) spellEffect = parseEffect(spellLines.joinToString(" "))
        val kws = keywords.map { it.lowercase() }.toSet()
        return CardDef(oracleId, name, typeLine, supers, types, subs, manaCost, manaValue, if ("devoid" in kws) emptySet() else colors.toSet(), CardDef.parseStat(power), CardDef.parseStat(toughness),
            kws, abilities, spellEffect, oracleText, enchant, "changeling" in kws)
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

    private val manaRe = Regex("""^Add (\{[^}]+\}(?:\{[^}]+\})*(?:(?:, or |, | or | and )\{[^}]+\}(?:\{[^}]+\})*)*|(?:one|two|three|four|five|N|X) mana (?:of any (?:one )?color|in any combination of colors|of any color(?: or type)?)|an amount of mana .+)\.?$""", RegexOption.IGNORE_CASE)

    private fun parseActivated(line: String): Ability {
        val colon = line.indexOf(':')
        var restriction: String? = null
        val effText = line.substring(colon + 1).trim().let { t ->
            Regex("""\s*(Activate (?:only|no more than) .+?)\.?$""", RegexOption.IGNORE_CASE).find(t)?.let { m -> restriction = m.groupValues[1]; t.removeRange(m.range).trim() } ?: t
        }
        val effect = manaRe.matchEntire(effText.substringBefore(". ").trimEnd('.'))?.let { Effect.AddMana(it.groupValues[1]) }?.let { mana ->
            val rest = effText.substringAfter(". ", "").trim()
            if (rest.isEmpty()) mana else Effect.Seq(listOf(mana, parseEffect(rest)))
        } ?: parseEffect(effText)
        return ActivatedAbility(line.substring(0, colon).trim(), effect, line, restriction)
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
        Regex("""^~ attacks or blocks$""", RegexOption.IGNORE_CASE).matchEntire(cond)?.let {
            val eff = parseEffect(m.groupValues[3]); return listOf(TriggeredAbility(Trigger.ThisAttacks, eff, line), TriggeredAbility(Trigger.ThisBlocks, eff, line))
        }
        Regex("""^~ enters or dies$""", RegexOption.IGNORE_CASE).matchEntire(cond)?.let {
            val eff = parseEffect(m.groupValues[3]); return listOf(TriggeredAbility(Trigger.ThisEnters, eff, line), TriggeredAbility(Trigger.ThisDies, eff, line))
        }
        return listOf(parseTriggered(m.groupValues[1] + " " + cond + ", " + m.groupValues[3]))
    }

    private val spellCastRe = Regex("""^(an opponent|you|a player|another player|each player) casts? (a|an|your first|their first) (.+?)(?: spell)?$""", RegexOption.IGNORE_CASE)

    private val stepNames = mapOf("upkeep" to "upkeep", "draw step" to "draw", "precombat main phase" to "precombat_main", "first main phase" to "precombat_main", "combat" to "combat",
        "end step" to "end", "next end step" to "end", "postcombat main phase" to "postcombat_main", "second main phase" to "postcombat_main", "untap step" to "untap", "end of combat step" to "end_of_combat", "turn" to "upkeep")

    fun parseTrigger(cond: String): Trigger {
        val c = cond.trim().trimEnd(',')
        Regex("""^the beginning of (?:(your|each player's|each|an opponent's|each opponent's|the) )?(upkeep|draw step|precombat main phase|first main phase|combat on your turn|combat on each of your turns|combat on each opponent's turn|combat|end step|next end step|postcombat main phase|second main phase|untap step|end of combat step|turn)$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            val whose = when (m.groupValues[1].lowercase()) { "your" -> Who.YOU; "an opponent's", "each opponent's" -> Who.OPPONENT; else -> Who.ANY_PLAYER }
            val stepKey = m.groupValues[2].lowercase().substringBefore(" on ")
            val whose2 = when { m.groupValues[2].lowercase().contains("on your turn") || m.groupValues[2].lowercase().contains("on each of your turns") -> Who.YOU; m.groupValues[2].lowercase().contains("opponent's turn") -> Who.OPPONENT; else -> whose }
            return Trigger.BeginningOfStep(stepNames[stepKey] ?: stepKey, whose2)
        }
        Regex("""^~ deals (combat )?damage to (a player|an opponent|a creature|a player or planeswalker|a permanent or player)$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            return Trigger.ThisDealsDamage(m.groupValues[1].isNotEmpty(), when (m.groupValues[2].lowercase()) { "a player", "an opponent", "a player or planeswalker" -> true; "a creature" -> false; else -> null })
        }
        if (Regex("""^~ deals (combat )?damage$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisDealsDamage(c.contains("combat", true), null)
        if (Regex("""^you attack( with one or more creatures)?$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.YouAttack
        if (Regex("""^~ is put into a graveyard from the battlefield$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisDies
        if (Regex("""^~ is dealt damage$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisIsDealtDamage
        if (Regex("""^~ becomes blocked$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisBecomesBlocked
        if (Regex("""^one or more creatures you control deal combat damage to a player$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.YourCreaturesDealCombatDamageToPlayer
        if (Regex("""^~ becomes the target of a spell or ability$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisBecomesTarget
        if (Regex("""^~ becomes tapped$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisBecomesTapped
        if (Regex("""^you cycle ~$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisCycled
        if (Regex("""^~ blocks$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisBlocks
        Regex("""^(.+?) attacks$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m -> if (!m.groupValues[1].equals("~", true)) { val f = parseFilter(m.groupValues[1], Kind.CREATURE); if (f.verifiable) return Trigger.PermanentAttacks(f) } }
        Regex("""^(.+?) deals combat damage to a player$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m -> if (!m.groupValues[1].equals("~", true)) { val f = parseFilter(m.groupValues[1], Kind.CREATURE); if (f.verifiable) return Trigger.PermanentDealsCombatDamageToPlayer(f) } }
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

    private val preventStaticRe = Regex("""^prevent all (combat )?damage that would be dealt (to|by) (~|enchanted creature|equipped creature|you|creatures you control|other creatures you control|creatures|players|you and permanents you control)\.?$""", RegexOption.IGNORE_CASE)
    private val diesReplRe = Regex("""^if (~|a creature|a nontoken creature|a creature you control|another creature|a creature an opponent controls|a permanent|a nontoken permanent|an? (.+?)) would die, (exile it|return it to its owner's hand|put it on the bottom of its owner's library|put it on top of its owner's library|shuffle it into its owner's library|exile it instead)(?: instead)?\.?$""", RegexOption.IGNORE_CASE)
    private val gyReplRe = Regex("""^if (a card or token|a card|a creature card|a nontoken creature|a permanent|a nontoken permanent|a creature) would be put into (a|an opponent's|your|a player's) graveyard from anywhere, exile it instead\.?$""", RegexOption.IGNORE_CASE)
    private val doublerRe = Regex("""^if a source (you control |an opponent controls )?would deal damage to (?:a permanent or player|a creature or player|a player|a creature|a permanent|you|an opponent|a player or planeswalker|a creature or planeswalker|any target), it deals (double|twice) that damage(?: to that (?:permanent or player|creature or player|player|creature|permanent|player or planeswalker))? instead\.?$""", RegexOption.IGNORE_CASE)
    private val lifeDoubleRe = Regex("""^if you would gain life, you gain (twice|double) that much life instead\.?$""", RegexOption.IGNORE_CASE)

    /** Replacement and prevention statics (614.1a, 615). */
    fun parseReplacementStatic(line: String): StaticEffect? {
        preventStaticRe.matchEntire(line)?.let { m ->
            val combat = m.groupValues[1].isNotEmpty(); val what = m.groupValues[3].lowercase()
            return if (m.groupValues[2].equals("to", true)) {
                when (what) { "you" -> StaticEffect.Replace(Replacement.PreventDamage(null, null, Who.YOU, combat, null)); "players" -> StaticEffect.Replace(Replacement.PreventDamage(null, null, Who.ANY_PLAYER, combat, null))
                    "~" -> StaticEffect.Replace(Replacement.PreventDamage(null, ObjFilter(setOf(Kind.PERMANENT), raw = "~"), null, combat, null, fromSelf = false).let { it.copy(to = it.to!!.copy(raw = "~")) })
                    "you and permanents you control" -> StaticEffect.Replace(Replacement.PreventDamage(null, parseFilter("permanents you control", Kind.PERMANENT), Who.YOU, combat, null))
                    else -> StaticEffect.Replace(Replacement.PreventDamage(null, parseFilter(what, Kind.CREATURE), null, combat, null)) }
            } else {
                when (what) { "~" -> StaticEffect.Replace(Replacement.PreventDamage(null, null, null, combat, null, fromSelf = true))
                    else -> StaticEffect.Replace(Replacement.PreventDamage(null, null, null, combat, parseFilter(what, Kind.CREATURE))) }
            }
        }
        diesReplRe.matchEntire(line)?.let { m ->
            val what = m.groupValues[1]; val self = what == "~"
            val filter = if (self) ObjFilter(setOf(Kind.PERMANENT), raw = "~") else parseFilter(what.removePrefix("a ").removePrefix("an "), Kind.CREATURE).let { if (what.startsWith("another")) it.copy(other = true) else it }
            if (!filter.verifiable) return null
            val instead = when { m.groupValues[3].startsWith("exile", true) -> "exile"; m.groupValues[3].contains("hand", true) -> "hand"; m.groupValues[3].contains("bottom", true) -> "library_bottom"; m.groupValues[3].contains("top", true) -> "library_top"; else -> "library_shuffle" }
            return StaticEffect.Replace(Replacement.GraveyardReplacement(filter, self, instead, false))
        }
        gyReplRe.matchEntire(line)?.let { m ->
            val what = m.groupValues[1].lowercase()
            val filter = when (what) { "a card or token" -> ObjFilter(setOf(Kind.PERMANENT, Kind.CARD), raw = "card or token"); "a card" -> ObjFilter(setOf(Kind.PERMANENT, Kind.CARD), token = false, raw = "card"); else -> parseFilter(what.removePrefix("a "), Kind.CREATURE) }
            val whose = when (m.groupValues[2].lowercase()) { "an opponent's" -> Who.OPPONENT; "your" -> Who.YOU; else -> null }
            return StaticEffect.Replace(Replacement.GraveyardReplacement(filter.copy(controller = whose), false, "exile", true))
        }
        doublerRe.matchEntire(line)?.let { m -> return StaticEffect.Replace(Replacement.DamageMultiplier(2, when (m.groupValues[1].trim().lowercase()) { "you control" -> Who.YOU; "an opponent controls" -> Who.OPPONENT; else -> null })) }
        lifeDoubleRe.matchEntire(line)?.let { return StaticEffect.Replace(Replacement.LifeGainMultiplier(2)) }
        return null
    }

    /** "Creatures you control get +1/+1", "Other Elf creatures you control get +1/+1 and have trample", "Creatures you control have haste". */
    fun parseStatic(line: String): List<StaticEffect> {
        parseReplacementStatic(line)?.let { return listOf(it) }
        Regex("""^~ enters(?: the battlefield)? tapped\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { return listOf(StaticEffect.EntersTapped) }
        Regex("""^~ enters(?: the battlefield)? with (a|an|X|\w+) ([+-]\d/[+-]\d|\w+) counters? on it\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val n = if (m.groupValues[1].equals("x", true)) null else (number(m.groupValues[1]) ?: return emptyList())
            return listOf(StaticEffect.EntersWithCounters(m.groupValues[2], n))
        }
        Regex("""^~ can't (block|attack|be countered|be blocked|attack or block)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { return listOf(StaticEffect.Cant(it.groupValues[1].lowercase())) }
        if (Regex("""^~ attacks each combat if able\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.MustAttack)
        if (Regex("""^(LEVEL \d+.*|\d+/\d+|\{[^}]+\}(?:\{[^}]+\})* — \d+/\d+.*)$""").matches(line)) return listOf(StaticEffect.Note(line, listOf("702.87a")))
        if (Regex("""^You may look at the top card of your library any time\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.Note(line, listOf("401.5")))
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
        if (sentences.size > 1) {
            // "You may pay {2}. If you do, draw a card." / "You may sacrifice a creature. If you do, …"
            val out = mutableListOf<Effect>()
            var i = 0
            while (i < sentences.size) {
                val cur = sentences[i]; val next = sentences.getOrNull(i + 1)
                if (cur.startsWith("You may ", true) && next != null && next.startsWith("If you do, ", true)) {
                    val choice = cur.removePrefix("You may ").removePrefix("you may ").trimEnd('.')
                    val cost = payRe.matchEntire(choice)?.groupValues?.get(1)
                    out += Effect.IfYouDo(if (cost != null) Effect.Narrated("pay $cost", listOf("608.2g")) else parseSentence(choice.replaceFirstChar { it.uppercase() }), parseSentence(next.removePrefix("If you do, ").removePrefix("if you do, ").replaceFirstChar { it.uppercase() }), cost)
                    i += 2
                } else { out += parseSentence(cur); i++ }
            }
            return if (out.size == 1) out[0] else Effect.Seq(out)
        }
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
    private val gainSelfRe = Regex("""^~ gains (.+?) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val payRe = Regex("""^(?:you may )?pay (\{[^}]+\}(?:\{[^}]+\})*|\d+ life)\.?$""", RegexOption.IGNORE_CASE)
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
        Regex("""^create (a|an|\w+|\d+|X) (?:.+? )?tokens?.*$""", RegexOption.IGNORE_CASE) to listOf("701.7a"),
        Regex("""^you gain (\d+) life for each .+$""", RegexOption.IGNORE_CASE) to listOf("119.3"),
        Regex("""^~ deals damage equal to .+$""", RegexOption.IGNORE_CASE) to listOf("120.3"),
        Regex("""^you get \{E\}.*$""", RegexOption.IGNORE_CASE) to listOf("122.1"),
        Regex("""^it can't be regenerated\.?$""", RegexOption.IGNORE_CASE) to listOf("701.19a"),
        Regex("""^target (?:opponent|player) reveals their hand\.?$""", RegexOption.IGNORE_CASE) to listOf("701.20a"),
        Regex("""^reveal .+$""", RegexOption.IGNORE_CASE) to listOf("701.20a"),
        Regex("""^attach (?:~|it) to target .+$""", RegexOption.IGNORE_CASE) to listOf("701.3a"),
        Regex("""^put (?:the rest|them|it|the other cards?|the remaining cards?|(?:one|two|three|\d+) of them) (?:on the bottom|on top|into your hand|into your graveyard|back).*$""", RegexOption.IGNORE_CASE) to listOf("401.4"),
        Regex("""^return (?:~|target .+?|up to \w+ target .+?) from (?:your|a|their) graveyard to (?:your hand|its owner's hand|the battlefield|the top of your library).*$""", RegexOption.IGNORE_CASE) to listOf("400.7"),
        Regex("""^transform (?:~|it|target .+?)\.?$""", RegexOption.IGNORE_CASE) to listOf("701.27a"),
        Regex("""^exile ~\.?$""", RegexOption.IGNORE_CASE) to listOf("701.13a"),
        Regex("""^destroy ~\.?$""", RegexOption.IGNORE_CASE) to listOf("701.8a"),
        Regex("""^return ~ to its owner's hand\.?$""", RegexOption.IGNORE_CASE) to listOf("400.7"),
        Regex("""^(?:you |target player |each player )?loses? (\d+) life\.?$""", RegexOption.IGNORE_CASE) to listOf("119.3"),
        Regex("""^untap ~\.?$""", RegexOption.IGNORE_CASE) to listOf("701.26b"),
        Regex("""^tap ~\.?$""", RegexOption.IGNORE_CASE) to listOf("701.26a"),
        Regex("""^as ~ enters, choose (?:a|an) .+$""", RegexOption.IGNORE_CASE) to listOf("614.1c"),
        Regex("""^you may have ~ enter as a copy of .+$""", RegexOption.IGNORE_CASE) to listOf("707.9", "614.1c"),
        Regex("""^copy target .+$""", RegexOption.IGNORE_CASE) to listOf("707.10"),
        Regex("""^~ deals (\d+) damage to you\.?$""", RegexOption.IGNORE_CASE) to listOf("120.3a"),
        Regex("""^~ deals (\d+) damage to each opponent\.?$""", RegexOption.IGNORE_CASE) to listOf("120.3a"),
        Regex("""^~ deals (\d+) damage to each player\.?$""", RegexOption.IGNORE_CASE) to listOf("120.3a"),
        Regex("""^~ deals (\d+) damage to that player\.?$""", RegexOption.IGNORE_CASE) to listOf("120.3a"),
        Regex("""^~ fights target .+$""", RegexOption.IGNORE_CASE) to listOf("701.14a"),
        Regex("""^(?:each|target) (?:opponent|player) (?:discards|sacrifices|mills|exiles) .+$""", RegexOption.IGNORE_CASE) to listOf("701.9a"),
        Regex("""^shuffle (?:~|it|target .+?) into (?:its|your) owner's library\.?$""", RegexOption.IGNORE_CASE) to listOf("701.24a"),
    )

    private val modalRe = Regex("""^(.*?)Choose (one|two|three|any number|one or more|up to \w+)(?: or more)?(?: —|\.)?\s*((?:• .+?)+)$""", RegexOption.IGNORE_CASE)
    private val preventNextRe = Regex("""^prevent the next (\d+) damage that would be dealt to (any target|target creature or player|target creature|target player|you|target creature or planeswalker|target permanent or player) this turn\.?$""", RegexOption.IGNORE_CASE)
    private val preventAllTurnRe = Regex("""^prevent all (combat )?damage that would be dealt(?: to (you|any target|target creature|target creature or player|creatures you control|target player|you and permanents you control))?(?: by (.+?))? this turn\.?$""", RegexOption.IGNORE_CASE)
    private val regenerateRe = Regex("""^regenerate (~|target .+?)\.?$""", RegexOption.IGNORE_CASE)

    private fun parseSentence(s: String): Effect {
        modalRe.matchEntire(s)?.let { m ->
            val modeTexts = m.groupValues[3].split("•").map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
            return Effect.Modal(m.groupValues[2].lowercase(), modeTexts.map { parseEffect(it) }, modeTexts)
        }
        regenerateRe.matchEntire(s)?.let { m -> return Effect.Regenerate(if (m.groupValues[1] == "~") null else target(m.groupValues[1])) }
        preventNextRe.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toInt(); val to = m.groupValues[2].lowercase()
            return if (to == "you") Effect.CreateShield(Replacement.PreventDamage(n, null, Who.YOU, false, null), null)
            else Effect.CreateShield(Replacement.PreventDamage(n, null, null, false, null), target(to))
        }
        preventAllTurnRe.matchEntire(s)?.let { m ->
            val combat = m.groupValues[1].isNotEmpty(); val to = m.groupValues[2].lowercase(); val by = m.groupValues[3]
            val from = if (by.isEmpty()) null else parseFilter(by.removePrefix("target ").removePrefix("a ").removePrefix("an "), Kind.CREATURE).takeIf { it.verifiable } ?: return Effect.Unparsed(s)
            return when {
                to.isEmpty() -> Effect.CreateShield(Replacement.PreventDamage(null, null, Who.ANY_PLAYER, combat, from).copy(to = ObjFilter(setOf(Kind.PERMANENT), raw = "everything")), null)
                to == "you" -> Effect.CreateShield(Replacement.PreventDamage(null, null, Who.YOU, combat, from), null)
                to == "you and permanents you control" -> Effect.CreateShield(Replacement.PreventDamage(null, parseFilter("permanents you control", Kind.PERMANENT), Who.YOU, combat, from), null)
                to.startsWith("target") || to == "any target" -> Effect.CreateShield(Replacement.PreventDamage(null, null, null, combat, from), target(to))
                else -> Effect.CreateShield(Replacement.PreventDamage(null, parseFilter(to, Kind.CREATURE), null, combat, from), null)
            }
        }
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
        gainSelfRe.matchEntire(s)?.let { m -> keywordsIn(m.groupValues[1])?.let { kws -> return Effect.GainKeywordsSelf(kws) } }
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
        var attacking: Boolean? = null; var tapped: Boolean? = null; var token: Boolean? = null; var legendary: Boolean? = null; var attachedToSource = false
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
                w == "enchanted" || w == "equipped" -> attachedToSource = true
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
        return ObjFilter(kinds, notKinds, controller, attacking, tapped, unknown, desc, subtypes, keywords, token, legendary, attachedToSource = attachedToSource)
    }
}
