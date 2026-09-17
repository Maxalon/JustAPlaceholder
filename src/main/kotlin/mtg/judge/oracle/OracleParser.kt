package mtg.judge.oracle

import mtg.judge.engine.Ability
import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.CardDef
import mtg.judge.engine.Effect
import mtg.judge.engine.Generic
import mtg.judge.engine.Kind
import mtg.judge.engine.ObjFilter
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.StaticEffect
import mtg.judge.engine.Replacement
import mtg.judge.engine.Condition
import mtg.judge.engine.CountExpr
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

    fun parse(oracleId: String, name: String, typeLine: String, manaCost: String?, manaValue: Double, colors: String, power: String?, toughness: String?, keywords: Collection<String>, oracleText: String, loyalty: String? = null): CardDef {
        val (supers, types, subs) = CardDef.splitTypeLine(typeLine)
        val text = oracleText.substringBefore("\n//\n")   // front face only, for now
        val rawLines = text.lines().map { it.replace(reminder, "").trim() }.filter { it.isNotEmpty() }
        // Modal text: "Choose one —" followed by "• mode" lines becomes one line the effect parser understands.
        val lines = mutableListOf<String>()
        var i0 = 0
        while (i0 < rawLines.size) {
            val l = rawLines[i0]
            if (Regex("""^(.*?)(Choose ($modeCount)(?: —|\.)?)(?: You may choose the same mode more than once\.)?\s*$""", RegexOption.IGNORE_CASE).matches(l) && rawLines.getOrNull(i0 + 1)?.startsWith("•") == true) {
                val modes = mutableListOf<String>()
                var j = i0 + 1
                while (j < rawLines.size && rawLines[j].startsWith("•")) { modes += rawLines[j].removePrefix("•").trim(); j++ }
                // "Choose three. You may choose the same mode more than once." (Mystic Confluence): the permission is a
                // note, not an effect, and the full stop would split the header off its own modes. Normalise to the
                // em-dash form the effect parser reads.
                val header = l.trimEnd().replace(Regex(""" You may choose the same mode more than once\.$"""), "")
                    .let { h -> Regex("""^(.*?Choose (?:$modeCount))[\s—.]*$""", RegexOption.IGNORE_CASE).matchEntire(h)?.groupValues?.get(1)?.plus(" \u2014") ?: h }
                lines += header + " " + modes.joinToString(" ") { "• $it" }
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
            // "Threshold — As long as …", "Landfall — ~ gets …": the ability word names the ability and says
            // nothing by itself (207.2c). Without stripping it the line reads as a bare keyword and loses its effect.
            val statics = parseStatic(selfRef.replace(abilityWordStatic, ""))
            when {
                Regex("""^Activate only .+$""", RegexOption.IGNORE_CASE).matches(selfRef) -> {
                    val i = abilities.indexOfLast { it is ActivatedAbility }
                    if (i >= 0) abilities[i] = (abilities[i] as ActivatedAbility).copy(restriction = selfRef.trimEnd('.'), text = abilities[i].text + " " + selfRef) else abilities += UnparsedAbility(selfRef)
                }
                isKeywordLine(selfRef, keywords) && statics.isEmpty() -> selfRef.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                    val kw = keywords.map { it.lowercase() }.filter { part.lowercase() == it || part.lowercase().startsWith("$it ") }.maxByOrNull { it.length } ?: part.substringBefore(' ').lowercase()
                    when (kw) {
                        "enchant" -> { val what = part.substring(7).trim().trimEnd('.'); enchant = if (what.equals("player", true)) ObjFilter(setOf(Kind.PLAYER), raw = what) else parseFilter(what, Kind.PERMANENT); abilities += StaticAbility(part, kw) }
                        "equip" -> abilities += ActivatedAbility(part.trimEnd('.'), Effect.Attach(TargetSpec(ObjFilter(setOf(Kind.CREATURE), controller = Who.YOU, raw = "creature you control"), "creature you control")), part, "Activate only as a sorcery")
                        "cycling" -> abilities += ActivatedAbility(part.trimEnd('.') + " (discard this card from your hand)", Effect.Draw(Who.YOU, 1), part, "Activate only while this card is in your hand")
                        "prowess" -> abilities += TriggeredAbility(Trigger.SpellCast(Who.YOU, ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.CREATURE), raw = "noncreature spell")), Effect.PumpSelf(1, 1), "Prowess (Whenever you cast a noncreature spell, ~ gets +1/+1 until end of turn.)")
                        "unearth" -> abilities += ActivatedAbility(part.trimEnd('.') + " (from your graveyard)", Effect.Narrated("return ~ from your graveyard to the battlefield; it gains haste; exile it at the beginning of the next end step or if it would leave the battlefield", listOf("702.84a")), part, "Activate only as a sorcery")
                        "level up" -> abilities += ActivatedAbility(part.trimEnd('.'), Effect.PutCounters(null, "level", 1), part, "Activate only as a sorcery")
                        "bushido" -> {
                            val n = part.substringAfter(' ').trim().trimEnd('.').toIntOrNull()
                            val txt = "Bushido $n (Whenever this creature blocks or becomes blocked, it gets +$n/+$n until end of turn.)"
                            if (n == null) abilities += StaticAbility(part, kw)
                            else { abilities += TriggeredAbility(Trigger.ThisBlocks, Effect.PumpSelf(n, n), txt); abilities += TriggeredAbility(Trigger.ThisBecomesBlocked, Effect.PumpSelf(n, n), txt) }
                        }
                        "storm" -> abilities += TriggeredAbility(Trigger.ThisCast, Effect.StormCopy, "Storm (When you cast this spell, copy it for each spell cast before it this turn. You may choose new targets for the copies.)")
                        "exalted" -> abilities += TriggeredAbility(Trigger.CreatureAttacksAlone, Effect.PumpCausing(1, 1), "Exalted (Whenever a creature you control attacks alone, that creature gets +1/+1 until end of turn.)")
                        "living weapon" -> abilities += TriggeredAbility(Trigger.ThisEnters, Effect.LivingWeapon, "Living weapon (When this Equipment enters, create a 0/0 black Phyrexian Germ creature token, then attach this to it.)")
                        "crew" -> abilities += ActivatedAbility(part.trimEnd('.') + " (tap any number of other untapped creatures you control with total power N or more)", Effect.Narrated("~ becomes an artifact creature until end of turn", listOf("702.122a")), part)
                        else -> abilities += StaticAbility(part, kw)
                    }
                }
                statics.isNotEmpty() -> abilities += StaticAbility(selfRef, null, statics)
                selfRef.startsWith("When ", true) || selfRef.startsWith("Whenever ", true) || selfRef.startsWith("At ", true) || abilityWord.containsMatchIn(selfRef) -> abilities += parseTriggeredAll(selfRef)
                isActivated(selfRef) -> abilities += parseActivated(selfRef)
                isSpell -> spellLines += selfRef
                else -> abilities += UnparsedAbility(selfRef)
            }
        }
        if (isSpell && spellLines.isNotEmpty()) spellEffect = parseEffect(spellLines.joinToString(" "))
        val kws = keywords.map { it.lowercase() }.toSet()
        // Basic land types carry intrinsic mana abilities (305.6): "({T}: Add {G} or {U}.)" is reminder text, so add them from the type line.
        if ("Land" in types) for (sub in subs) basicLandMana[sub]?.let { sym -> if (abilities.none { it is ActivatedAbility && it.cost == "{T}" && (it.effect as? Effect.AddMana)?.text == sym }) abilities += ActivatedAbility("{T}", Effect.AddMana(sym), "{T}: Add $sym. (intrinsic, from being ${if (sub.first() in "AEIOU") "an" else "a"} $sub)") }
        return CardDef(oracleId, name, typeLine, supers, types, subs, manaCost, manaValue, if ("devoid" in kws) emptySet() else colors.toSet(), CardDef.parseStat(power), CardDef.parseStat(toughness),
            kws, abilities, spellEffect, oracleText, enchant, "changeling" in kws, loyalty?.toIntOrNull())
    }

    /** Replace the card's own name and "this creature/permanent/…" with "~". */
    private fun selfReference(line: String, name: String): String {
        var s = line.replace(name, "~")
        val shortName = name.substringBefore(",")
        if (shortName != name) s = s.replace(shortName, "~")
        // Legendary names shortened in their own text: "Kaalia of the Vast" -> "Kaalia", "Ezuri the Claw" -> "Ezuri", "Arcades, the Strategist" already handled.
        Regex("""^([A-Z][\w'-]+) (?:of|the|,)\b""").find(name)?.groupValues?.get(1)?.let { first -> if (first.length >= 3) s = s.replace(Regex("""\b${Regex.escape(first)}\b(?! (?:of|the))"""), "~") }
        s = s.replace(Regex("""\b[Tt]his (creature|permanent|artifact|enchantment|land|planeswalker|spell|card|Aura|Equipment|Vehicle|token|battle|Saga|Class|Room)\b"""), "~")
        return s
    }

    private fun isKeywordLine(line: String, keywords: Collection<String>): Boolean {
        val parts = line.trimEnd('.').split(',', ';').map { it.trim().lowercase() }
        val kws = keywords.map { it.lowercase() }.toSet()
        // "Evoke—Exile a black card from your hand": a keyword whose cost follows an em-dash with no space.
        return parts.isNotEmpty() && parts.all { p -> kws.any { k -> p == k || p.startsWith("$k ") || p.startsWith("$k—") || p.startsWith("$k–") } }
    }

    private fun isActivated(line: String): Boolean {
        val colon = line.indexOf(':')
        if (colon <= 0) return false
        val cost = line.substring(0, colon)
        if (Regex("""^[+\u2212-]?\d+$""").matches(cost.trim())) return true   // loyalty ability (606.2)
        return cost.contains('{') || cost.contains("Sacrifice", true) || cost.contains("Discard", true) || cost.contains("Pay", true) || cost.contains("Tap ", true) || cost.contains("Remove", true) || cost.contains("Exile", true)
    }

    private val manaRe = Regex("""^Add (\{[^}]+\}(?:\{[^}]+\})*(?:(?:, or |, | or | and )\{[^}]+\}(?:\{[^}]+\})*)*|(?:one|two|three|four|five|N|X) mana (?:of any (?:one )?color(?: in your commander's color identity)?|in any combination of colors|of any color(?: or type)?)|an amount of mana .+)\.?$""", RegexOption.IGNORE_CASE)

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
        val cost = line.substring(0, colon).trim()
        val isLoyalty = Regex("""^[+\u2212-]?\d+$""").matches(cost)
        return ActivatedAbility(cost, effect, line, restriction ?: if (isLoyalty) "Activate only as a sorcery and only once each turn (loyalty ability)" else null)
    }

    private val triggerRe = Regex("""^(When|Whenever|At)\s+(.+?),\s+(.+)$""", RegexOption.IGNORE_CASE)
    /**
     * "an Aura, Equipment, or Vehicle spell": the commas inside a list of card descriptions are not the comma
     * that separates a trigger from its effect. Sram read only the Aura and reported the rest unparsed, so the
     * list is joined with "or" before the split, which the filter parser already reads.
     */
    private fun joinTypeLists(line: String): String =
        Regex("""\b([A-Za-z][\w'-]*)((?:, [A-Za-z][\w'-]*)+),? or ([A-Za-z][\w'-]*)(?= (?:spell|spells|card|cards)\b)""").replace(line) { r ->
            (listOf(r.groupValues[1]) + r.groupValues[2].split(", ").filter { it.isNotBlank() } + r.groupValues[3]).joinToString(" or ")
        }.let { t -> Regex("""\b([A-Za-z][\w'-]*), or ([A-Za-z][\w'-]*)(?= (?:spell|spells|card|cards)\b)""").replace(t) { r -> "${r.groupValues[1]} or ${r.groupValues[2]}" } }
    /**
     * An ability word ("Landfall — ", "Flurry of Blows — ") names an ability and says nothing by itself (207.2c).
     * Matched only when a trigger word follows, so a modal "Choose one —" is left alone.
     */
    private val abilityWord = Regex("""^[A-Z][A-Za-z'’]*(?: [A-Za-z'’]+){0,4}\s*[—–]\s*(?=Whenever\b|When\b|At\b)""")
    /** The same for a static ability. A cost can follow an em-dash too ("Ward—Pay 3 life"), so only sentences count. */
    private val abilityWordStatic = Regex("""^[A-Z][A-Za-z'’]*(?: [A-Za-z'’]+){0,4}\s*[—–]\s*(?=As long as\b|~|Creatures\b|Each\b|You\b|If\b)""")

    private fun parseTriggered(line: String): Ability {
        val m = triggerRe.matchEntire(joinTypeLists(line)) ?: return UnparsedAbility(line)
        val cond = m.groupValues[2].trim().replace(Regex("""^Landfall — """), "")
        val trigger = parseTrigger(cond)
        // "Whenever ~ attacks, it gets +1/+1" / "…, put a +1/+1 counter on it": in a self-trigger, a leading "it" is ~.
        val selfTrigger = trigger is Trigger.ThisDies || trigger is Trigger.ThisLeavesBattlefield || trigger is Trigger.ThisAttacks || trigger is Trigger.ThisEnters || trigger is Trigger.ThisDealsDamage || trigger is Trigger.ThisBecomesBlocked || trigger is Trigger.ThisBecomesBlockedByCreature || trigger is Trigger.ThisBlocks ||
            trigger is Trigger.ThisBecomesTarget || trigger is Trigger.ThisBecomesTapped || trigger is Trigger.ThisIsDealtDamage || trigger is Trigger.ThisCast
        val effText = if (selfTrigger) selfEffText(m.groupValues[3]) else m.groupValues[3]
        // "Whenever a creature you control attacks alone, it gains double strike / gets +2/+2 until end of turn": the attacking creature.
        if (trigger is Trigger.CreatureAttacksAlone) {
            Regex("""^(?:it|that creature) gets ([+-]\d+)/([+-]\d+)(?: and gains (.+?))? until end of turn\.?$""", RegexOption.IGNORE_CASE).matchEntire(effText)?.let { r ->
                val kws = r.groupValues[3].takeIf { it.isNotEmpty() }?.let { keywordsIn(it) ?: return TriggeredAbility(trigger, Effect.Unparsed(effText), line) } ?: emptySet()
                return TriggeredAbility(trigger, Effect.PumpCausing(r.groupValues[1].toInt(), r.groupValues[2].toInt(), kws.toList()), line)
            }
            Regex("""^(?:it|that creature) gains (.+?) until end of turn\.?$""", RegexOption.IGNORE_CASE).matchEntire(effText)?.let { r ->
                val kws = keywordsIn(r.groupValues[1]) ?: return TriggeredAbility(trigger, Effect.Unparsed(effText), line)
                return TriggeredAbility(trigger, Effect.PumpCausing(0, 0, kws.toList()), line)
            }
        }
        return TriggeredAbility(trigger, parseEffect(effText), line)
    }

    /** In a trigger about ~ itself, a leading "it" is ~. */
    private fun selfEffText(t: String) = t.replace(Regex("""^it (gets|gains) """), "~ $1 ")
        .replace(Regex("""^(put (?:a|an|\w+|\d+|X) [+-]\d/[+-]\d counters? on) it\b"""), "$1 ~")
        .replace(Regex("""^return it to its owner's hand"""), "return ~ to its owner's hand")

    /** "Whenever ~ enters or attacks, …" is two triggered abilities with the same effect. */
    private fun parseTriggeredAll(line: String): List<Ability> {
        // An ability word ("Landfall — ", "Flurry of Blows — ") is flavour: it names the ability and says nothing.
        // Stripped only when a trigger word follows, so a modal "Choose one —" is left alone.
        val m = triggerRe.matchEntire(joinTypeLists(line.replace(abilityWord, ""))) ?: return listOf(UnparsedAbility(line))
        val cond = m.groupValues[2].trim()
        Regex("""^~ enters or attacks$""", RegexOption.IGNORE_CASE).matchEntire(cond)?.let {
            val eff = parseEffect(selfEffText(m.groupValues[3])); return listOf(TriggeredAbility(Trigger.ThisEnters, eff, line), TriggeredAbility(Trigger.ThisAttacks, eff, line))
        }
        // "Whenever ~ blocks or becomes blocked(, by a creature)" — the two halves are separate triggers, and the
        // "by a creature" half triggers once for each creature blocking it, so it keeps its own trigger.
        Regex("""^~ blocks or becomes blocked( by a creature)?$""", RegexOption.IGNORE_CASE).matchEntire(cond)?.let { bm ->
            val eff = parseEffect(selfEffText(m.groupValues[3]))
            val blocked = if (bm.groupValues[1].isEmpty()) Trigger.ThisBecomesBlocked else Trigger.ThisBecomesBlockedByCreature
            return listOf(TriggeredAbility(Trigger.ThisBlocks, eff, line), TriggeredAbility(blocked, eff, line))
        }
        Regex("""^~ attacks or blocks$""", RegexOption.IGNORE_CASE).matchEntire(cond)?.let {
            val eff = parseEffect(selfEffText(m.groupValues[3])); return listOf(TriggeredAbility(Trigger.ThisAttacks, eff, line), TriggeredAbility(Trigger.ThisBlocks, eff, line))
        }
        Regex("""^~ enters or dies$""", RegexOption.IGNORE_CASE).matchEntire(cond)?.let {
            val eff = parseEffect(selfEffText(m.groupValues[3])); return listOf(TriggeredAbility(Trigger.ThisEnters, eff, line), TriggeredAbility(Trigger.ThisDies, eff, line))
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
        if (Regex("""^~ is dealt damage$|^a source deals damage to ~$|^~ is dealt damage by a source$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisIsDealtDamage
        if (Regex("""^~ becomes blocked$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisBecomesBlocked
        if (Regex("""^~ becomes blocked by a creature$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisBecomesBlockedByCreature
        if (Regex("""^one or more creatures you control deal combat damage to a player$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.YourCreaturesDealCombatDamageToPlayer
        // "~ becomes the target of a spell (or ability) (an opponent controls) (for the first time each turn)"
        Regex("""^~ becomes the target of a spell(?: or ability)?( an opponent controls| you control)?( for the first time each turn)?$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            return Trigger.ThisBecomesTarget(m.groupValues[1].trim() == "an opponent controls", m.groupValues[2].isNotEmpty())
        }
        if (Regex("""^~ becomes tapped$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisBecomesTapped
        if (Regex("""^you cycle ~$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisCycled
        if (Regex("""^~ blocks$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisBlocks
        Regex("""^(.+?) attacks$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m -> if (!m.groupValues[1].equals("~", true)) { val f = parseFilter(m.groupValues[1], Kind.CREATURE); if (f.verifiable) return Trigger.PermanentAttacks(f) } }
        Regex("""^(.+?) deals combat damage to a player$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m -> if (!m.groupValues[1].equals("~", true)) { val f = parseFilter(m.groupValues[1], Kind.CREATURE); if (f.verifiable) return Trigger.PermanentDealsCombatDamageToPlayer(f) } }
        // Umezawa's Jitte: "whenever equipped creature deals combat damage" — to a blocker as well as to a player.
        Regex("""^(.+?) deals combat damage$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m -> if (!m.groupValues[1].equals("~", true)) { val f = parseFilter(m.groupValues[1], Kind.CREATURE); if (f.verifiable) return Trigger.PermanentDealsCombatDamage(f) } }
        if (Regex("""^you gain life$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.YouGainLife
        if (Regex("""^you draw a card$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.YouDraw
        if (Regex("""^a creature you control attacks alone$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.CreatureAttacksAlone
        Regex("""^one or more (.+?) cards? (?:are|is) put into your graveyard from anywhere$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m -> val f = parseFilter(m.groupValues[1], Kind.PERMANENT); return if (f.verifiable) Trigger.CardsToYourGraveyard(f) else Trigger.Unknown(c) }
        Regex("""^(an opponent|a player) draws a card$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { return Trigger.PlayerDraws(if (it.groupValues[1].lowercase() == "an opponent") Who.OPPONENT else Who.ANY_PLAYER) }
        Regex("""^you draw your (first|second|third) card each turn$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { return Trigger.YouDrawNth(mapOf("first" to 1, "second" to 2, "third" to 3).getValue(it.groupValues[1].lowercase())) }
        Regex("""^~ or another (.+?) enters(?: the battlefield)?(?: under your control)?$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.CREATURE).let { if (c.contains("under your control", true) || it.controller == null) it.copy(controller = Who.YOU) else it }
            if (f.verifiable) return Trigger.PermanentEnters(f, false)
        }
        if (Regex("""^you cast ~$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisCast
        Regex("""^(?:Landfall — )?(?:whenever )?(another |one or more |a |an )?(.+?) (?:enters|enter)(?: the battlefield)?(?: under your control)?$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            if (m.groupValues[2].equals("~", true)) return Trigger.ThisEnters
            val underYou = c.contains("under your control", true)
            val f = parseFilter(m.groupValues[2], Kind.PERMANENT).let { if (underYou && it.controller == null) it.copy(controller = Who.YOU) else it }
            if (!f.verifiable) return Trigger.Unknown(c)
            return Trigger.PermanentEnters(f, m.groupValues[1].trim().equals("another", true))
        }
        // "~ or another creature dies" (Blood Artist): any creature, this one included.
        Regex("""^~ or another (.+?) dies$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.CREATURE)
            return if (f.verifiable) Trigger.PermanentDies(f, false) else Trigger.Unknown(c)
        }
        Regex("""^(another |a |an )?(.+?) dies$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            if (m.groupValues[2].equals("~", true)) return Trigger.ThisDies
            val f = parseFilter(m.groupValues[2], Kind.CREATURE)
            if (!f.verifiable) return Trigger.Unknown(c)
            return Trigger.PermanentDies(f, m.groupValues[1].trim().equals("another", true))
        }
        // Chalice of the Void: "a player casts a spell with mana value equal to the number of charge counters on ~"
        Regex("""^an? (?:player|opponent) casts a spell with mana value equal to the number of (\w+) counters on ~$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            return Trigger.SpellCastMvEqualsCounters(m.groupValues[1].lowercase())
        }
        // "you cast your second spell each turn" / "an opponent casts their second spell each turn"
        Regex("""^(you|an opponent|a player) cast(?:s)? (?:your|their) (second|third|fourth) spell each turn$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            val who = when (m.groupValues[1].lowercase()) { "you" -> Who.YOU; "an opponent" -> Who.OPPONENT; else -> Who.ANY_PLAYER }
            return Trigger.NthSpellEachTurn(mapOf("second" to 2, "third" to 3, "fourth" to 4).getValue(m.groupValues[2].lowercase()), who)
        }
        // "you attack with two or more creatures" / "with one or more Elves"
        Regex("""^you attack with (one|two|three|four|five|\d+) or more (.+?)$""", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            val n = number(m.groupValues[1]) ?: m.groupValues[1].toIntOrNull() ?: 1
            val what = m.groupValues[2].trim()
            val f = parseFilter(what.split(" ").joinToString(" ") { w -> singular(w) }, Kind.CREATURE)
            if (!f.verifiable) return@let
            return Trigger.AttackWithNOrMore(n, f)
        }
        spellCastRe.matchEntire(c)?.let { m ->
            val who = when (m.groupValues[1].lowercase()) { "you" -> Who.YOU; "an opponent" -> Who.OPPONENT; else -> Who.ANY_PLAYER }
            val what = m.groupValues[3].trim().lowercase()
            // "a creature spell": the filter has to say it is a spell as well as a creature card, or the matcher
            // rejects it outright and "whenever you cast a creature spell" never triggers. The regex eats the
            // word "spell" before parseFilter sees it, so the kind is put back here.
            val filter = if (what == "spell") null else parseFilter(what, Kind.SPELL).let { if (Kind.SPELL in it.kinds) it else it.copy(kinds = it.kinds + Kind.SPELL) }
            return Trigger.SpellCast(who, filter)
        }
        if (Regex("""^~ enters(?: the battlefield)?$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisEnters
        if (Regex("""^~ dies$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisDies
        if (Regex("""^~ leaves the battlefield$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisLeavesBattlefield
        if (Regex("""^~ attacks(?: an opponent| a player)?$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisAttacks
        return Trigger.Unknown(c)
    }

    // ---- static abilities ----------------------------------------------------------------

    private val anthemRe = Regex("""^(all |each |other )?(.+?) (?:get|gets) ([+-]\d+)/([+-]\d+)(?: and (?:have|has) (.+?))?\.?$""", RegexOption.IGNORE_CASE)
    private val grantRe = Regex("""^(all |each |other )?(.+?) (?:have|has) (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val keywordList = setOf("flying", "first strike", "double strike", "deathtouch", "haste", "hexproof", "indestructible", "lifelink", "menace", "reach", "trample", "vigilance", "flash", "defender", "shroud", "fear", "intimidate", "skulk", "horsemanship", "shadow", "infect", "wither", "protection from everything", "ward 1", "ward 2", "undying", "persist",
        "islandwalk", "swampwalk", "forestwalk", "mountainwalk", "plainswalk")

    private val preventStaticRe = Regex("""^prevent all (combat )?damage that would be dealt (to|by) (~|enchanted creature|equipped creature|you|creatures you control|other creatures you control|creatures|players|you and permanents you control)\.?$""", RegexOption.IGNORE_CASE)
    private val diesReplRe = Regex("""^if (~|a creature|a nontoken creature|a creature you control|another creature|a creature an opponent controls|a permanent|a nontoken permanent|an? (.+?)) would die, (exile it|return it to its owner's hand|put it on the bottom of its owner's library|put it on top of its owner's library|shuffle it into its owner's library|exile it instead)(?: instead)?\.?$""", RegexOption.IGNORE_CASE)
    /** Kalitas: "If a nontoken creature an opponent controls would die, instead exile that card and create a 2/2 black Zombie creature token." */
    private val diesInsteadRe = Regex("""^if (~|another .+?|an? .+?) would die, instead (exile that card|exile it|return that card to its owner's hand|put that card on the bottom of its owner's library|put that card on top of its owner's library|shuffle that card into its owner's library)(?: and (.+?))?\.?$""", RegexOption.IGNORE_CASE)
    private val gyReplRe = Regex("""^if (a card or token|a card|a creature card|a nontoken creature|a permanent|a nontoken permanent|a creature) would be put into (a|an opponent's|your|a player's) graveyard from anywhere, (?:exile it instead|instead exile it(?: with an? \w+ counter on it)?)\.?$""", RegexOption.IGNORE_CASE)
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
        Regex("""^if ~ would be put into a graveyard from anywhere, (?:reveal ~ and )?shuffle (?:it|~) into its owner's library instead\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return StaticEffect.Replace(Replacement.GraveyardReplacement(ObjFilter(setOf(Kind.PERMANENT), raw = "~"), true, "library_shuffle", true)) }
        diesInsteadRe.matchEntire(line)?.let { m ->
            val what = m.groupValues[1]; val self = what == "~"
            val filter = if (self) ObjFilter(setOf(Kind.PERMANENT), raw = "~") else parseFilter(what.removePrefix("a ").removePrefix("an ").removePrefix("another "), Kind.CREATURE).let { if (what.startsWith("another", true)) it.copy(other = true) else it }
            if (!filter.verifiable) return null
            val instead = when { m.groupValues[2].startsWith("exile", true) -> "exile"; m.groupValues[2].contains("hand", true) -> "hand"; m.groupValues[2].contains("bottom", true) -> "library_bottom"; m.groupValues[2].contains("top", true) -> "library_top"; else -> "library_shuffle" }
            val rider = m.groupValues[3].trim().takeIf { it.isNotEmpty() }?.let { parseSentence(it.replaceFirstChar { c -> c.lowercase() }) }
            if (m.groupValues[3].trim().isNotEmpty() && (rider == null || rider.hasUnparsed())) return null
            return StaticEffect.Replace(Replacement.GraveyardReplacement(filter, self, instead, false, rider))
        }
        diesReplRe.matchEntire(line)?.let { m ->
            val what = m.groupValues[1]; val self = what == "~"
            val filter = if (self) ObjFilter(setOf(Kind.PERMANENT), raw = "~") else parseFilter(what.removePrefix("a ").removePrefix("an "), Kind.CREATURE).let { if (what.startsWith("another")) it.copy(other = true) else it }
            if (!filter.verifiable) return null
            val instead = when { m.groupValues[3].startsWith("exile", true) -> "exile"; m.groupValues[3].contains("hand", true) -> "hand"; m.groupValues[3].contains("bottom", true) -> "library_bottom"; m.groupValues[3].contains("top", true) -> "library_top"; else -> "library_shuffle" }
            return StaticEffect.Replace(Replacement.GraveyardReplacement(filter, self, instead, false))
        }
        Regex("""^if an? (.+?) an opponent owns would die or a creature card not on the battlefield would be put into an opponent's graveyard, exile that card instead\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.CREATURE)
            if (f.verifiable) return StaticEffect.Replace(Replacement.GraveyardReplacement(f.copy(controller = Who.OPPONENT), false, "exile", true))
        }
        gyReplRe.matchEntire(line)?.let { m ->
            val what = m.groupValues[1].lowercase()
            val filter = when (what) { "a card or token" -> ObjFilter(setOf(Kind.PERMANENT, Kind.CARD), raw = "card or token"); "a card" -> ObjFilter(setOf(Kind.PERMANENT, Kind.CARD), token = false, raw = "card"); else -> parseFilter(what.removePrefix("a "), Kind.CREATURE) }
            val whose = when (m.groupValues[2].lowercase()) { "an opponent's" -> Who.OPPONENT; "your" -> Who.YOU; else -> null }
            return StaticEffect.Replace(Replacement.GraveyardReplacement(filter.copy(controller = whose), false, "exile", true))
        }
        // "If a nontoken creature would enter and it wasn't cast, exile it instead." (Containment Priest)
        Regex("""^if an? (.+?) would enter(?: the battlefield)? and it wasn't cast, exile it instead\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.CREATURE)
            if (f.verifiable) return StaticEffect.ExileIfEntersUncast(f)
        }
        // "Creature cards in graveyards and libraries can't enter the battlefield." (Grafdigger's Cage)
        Regex("""^(.+?) cards? in (graveyards and libraries|graveyards|libraries) can't enter the battlefield\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.CREATURE)
            val zones = when (m.groupValues[2].lowercase()) { "graveyards" -> setOf("graveyard"); "libraries" -> setOf("library"); else -> setOf("graveyard", "library") }
            if (f.verifiable) return StaticEffect.CantEnterFrom(f, zones)
        }
        // "Each player can't cast more than one spell each turn." (Rule of Law, Arcane Laboratory)
        Regex("""^each player can't cast more than (one|two|three|\d+) spells? each turn\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            number(m.groupValues[1])?.let { return StaticEffect.SpellsPerTurn(it, null) }
        }
        // "Each player who has cast a nonartifact spell this turn can't cast additional nonartifact spells." (Ethersworn Canonist)
        Regex("""^each player who has cast an? (.+?) spell this turn can't cast additional \1 spells\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val word = m.groupValues[1].lowercase()
            val f = when (word) {
                "nonartifact" -> ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.ARTIFACT), raw = "nonartifact spell")
                "noncreature" -> ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.CREATURE), raw = "noncreature spell")
                "creature" -> ObjFilter(setOf(Kind.CREATURE), raw = "creature spell")
                else -> return@let
            }
            return StaticEffect.SpellsPerTurn(1, f)
        }
        // "Each opponent can't draw more than one card each turn." (Narset, Spirit of the Labyrinth)
        Regex("""^(each opponent|each player|your opponents|players) can't draw more than (one|two|three|\d+) cards? each turn\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val n = number(m.groupValues[2]) ?: return@let
            return StaticEffect.CantDrawMoreThan(n, if (m.groupValues[1].lowercase() == "each player" || m.groupValues[1].lowercase() == "players") Who.EACH_PLAYER else Who.EACH_OPPONENT)
        }
        // "All creatures lose all abilities and have base power and toughness 1/1." (Humility)
        Regex("""^(.+?) lose all abilities and have base power and toughness (\d+)/(\d+)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val f = parseFilter(m.groupValues[1].removePrefix("all ").removePrefix("All "), Kind.CREATURE)
            if (f.verifiable) return StaticEffect.LoseAbilitiesSetPt(f, m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        doublerRe.matchEntire(line)?.let { m -> return StaticEffect.Replace(Replacement.DamageMultiplier(2, when (m.groupValues[1].trim().lowercase()) { "you control" -> Who.YOU; "an opponent controls" -> Who.OPPONENT; else -> null })) }
        lifeDoubleRe.matchEntire(line)?.let { return StaticEffect.Replace(Replacement.LifeGainMultiplier(2)) }
        Regex("""^if a player would gain life, that player gains no life instead\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return StaticEffect.Replace(Replacement.LifeGainMultiplier(0, anyPlayer = true)) }
        Regex("""^if you would gain life, you gain no life instead\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return StaticEffect.Replace(Replacement.LifeGainMultiplier(0)) }
        Regex("""^if an effect would create one or more tokens under your control, it creates twice that many of those tokens instead\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return StaticEffect.Replace(Replacement.TokenMultiplier(2)) }
        // Primal Vigor: no "under your control", so it doubles every player's tokens.
        Regex("""^if one or more tokens would be created, twice that many of those tokens are created instead\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return StaticEffect.Replace(Replacement.TokenMultiplier(2, anyPlayer = true)) }
        Regex("""^if an effect would (?:place|put) one or more counters on a permanent you control, it (?:places|puts) twice that many of those counters on that permanent instead\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return StaticEffect.Replace(Replacement.CounterMultiplier(2)) }
        Regex("""^creatures you control can't have ([+-]\d/[+-]\d|\w+) counters put on them\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            return StaticEffect.Replace(Replacement.CounterMultiplier(0, kind = m.groupValues[1]))
        }
        // Corpsejack Menace: "If one or more +1/+1 counters would be put on a creature you control, twice that
        // many +1/+1 counters are put on it instead." — the same doubling Doubling Season does, for one kind.
        Regex("""^if one or more ([+-]\d+/[+-]\d+|\w+) counters would be put on an? (?:creature|artifact|permanent|creature or artifact|artifact or creature) you control, twice that many \1 counters are put on (?:it|that permanent|that creature) instead\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            return StaticEffect.Replace(Replacement.CounterMultiplier(2, kind = m.groupValues[1]))
        }
        // Primal Vigor: the same without "you control", so it doubles for every player.
        Regex("""^if one or more ([+-]\d+/[+-]\d+|\w+) counters would be put on an? (?:creature|artifact|permanent), twice that many \1 counters are put on (?:it|that permanent|that creature) instead\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            return StaticEffect.Replace(Replacement.CounterMultiplier(2, anyPlayer = true, kind = m.groupValues[1]))
        }
        // Winding Constrictor: every kind of counter, one more of each.
        Regex("""^if one or more counters would be put on an? (?:artifact or creature|creature or artifact|creature|artifact|permanent) you control, that many plus (one|two|\d+) of each of those kinds of counters are put on that permanent instead\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val n = number(m.groupValues[1]) ?: return@let
            return StaticEffect.Replace(Replacement.CounterMultiplier(1, extra = n))
        }
        // Hardened Scales: "If one or more +1/+1 counters would be put on a creature you control, that many plus one +1/+1 counters are put on it instead."
        Regex("""^if one or more ([+-]\d+/[+-]\d+|\w+) counters would be put on an? (?:creature|artifact|permanent) you control, that many plus (one|two|\d+) \1 counters are put on it instead\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val n = number(m.groupValues[2]) ?: return@let
            return StaticEffect.Replace(Replacement.CounterMultiplier(1, extra = n, kind = m.groupValues[1]))
        }
        return null
    }

    /** "Creatures you control get +1/+1", "Other Elf creatures you control get +1/+1 and have trample", "Creatures you control have haste". */
    fun parseStatic(line: String): List<StaticEffect> {
        parseReplacementStatic(line)?.let { return listOf(it) }
        Regex("""^~ enters(?: the battlefield)? tapped\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { return listOf(StaticEffect.EntersTapped()) }
        Regex("""^all cards that aren't on the battlefield, spells, and permanents are the chosen colou?r in addition to their other colou?rs\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { return listOf(StaticEffect.EverythingIsChosenColour) }
        Regex("""^cards in graveyards lose all abilities\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { return listOf(StaticEffect.GraveyardCardsLoseAbilities) }
        // "You may cast spells as though they had flash" / "creature spells" / "Spirit spells".
        Regex("""^you may cast (.*?)spells as though they had flash\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val what = m.groupValues[1].trim()
            if (what.isEmpty()) return listOf(StaticEffect.CastAsThoughFlash(null))
            val f = parseFilter("$what spell", Kind.SPELL)
            if (f.verifiable) return listOf(StaticEffect.CastAsThoughFlash(f))
        }
        // "You may have ~ enter (tapped) as a copy of any creature on the battlefield(, except …)." (Clone and the 70-odd cards like it.)
        Regex("""^you may have ~ enter(?: the battlefield)?( tapped)? as a copy of (.+)$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            var rest = m.groupValues[2].trim().trimEnd('.')
            val except = rest.split(", except ", limit = 2).getOrNull(1)
            rest = rest.split(", except ", limit = 2)[0].removeSuffix(" on the battlefield").trim()
            // Copies of cards in a graveyard, a library or a hand aren't modeled; leave those cards unparsed rather than guess.
            if (Regex("""(?i)\b(?:graveyard|library|hand|exile|card)\b""").containsMatchIn(rest)) return@let
            val f = parseFilter(rest.replace(Regex("""(?i)^(?:any|a|an)\s+"""), ""), Kind.PERMANENT)
            return listOf(StaticEffect.EntersAsCopy(f, m.groupValues[1].isNotEmpty(), except))
        }
        // The Theros gods. This has to come before the "as long as" bail below.
        Regex("""^as long as your devotion to (white|blue|black|red|green) is less than (one|two|three|four|five|six|seven|\d+), ~ isn't a creature\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val colour = when (m.groupValues[1].lowercase()) { "white" -> 'W'; "blue" -> 'U'; "black" -> 'B'; "red" -> 'R'; else -> 'G' }
            val n = m.groupValues[2].lowercase().toIntOrNull() ?: listOf("zero", "one", "two", "three", "four", "five", "six", "seven").indexOf(m.groupValues[2].lowercase())
            if (n > 0) return listOf(StaticEffect.NotACreatureUnlessDevotion(colour, n))
        }
        // "Artifacts and creatures your opponents control enter tapped." (Blind Obedience, Urabrask, Kismet)
        Regex("""^(.+?) (your opponents control|you control|)\s*enters?(?: the battlefield)? tapped\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val what = m.groupValues[1].trim().lowercase()
            if (what == "~") return@let
            val f = when (what) {
                "artifacts and creatures" -> ObjFilter(setOf(Kind.ARTIFACT, Kind.CREATURE), raw = "artifact or creature")
                "creatures" -> ObjFilter(setOf(Kind.CREATURE), raw = "creature")
                "artifacts" -> ObjFilter(setOf(Kind.ARTIFACT), raw = "artifact")
                "lands" -> ObjFilter(setOf(Kind.LAND), raw = "land")
                "nonbasic lands" -> ObjFilter(setOf(Kind.LAND), raw = "nonbasic land")
                "permanents" -> ObjFilter(setOf(Kind.PERMANENT), raw = "permanent")
                "artifacts, creatures, and lands" -> ObjFilter(setOf(Kind.ARTIFACT, Kind.CREATURE, Kind.LAND), raw = "artifact, creature, or land")
                // Thalia, Heretic Cathar: "nonbasic" qualifies only the lands, so this is two effects.
                "creatures and nonbasic lands" -> return listOf(
                    StaticEffect.OthersEnterTapped(ObjFilter(setOf(Kind.CREATURE), raw = "creature"), m.groupValues[2].trim().equals("your opponents control", true)),
                    StaticEffect.OthersEnterTapped(ObjFilter(setOf(Kind.LAND), raw = "nonbasic land"), m.groupValues[2].trim().equals("your opponents control", true)))
                else -> return@let
            }
            val nonbasic = what == "nonbasic lands"
            return listOf(StaticEffect.OthersEnterTapped(if (nonbasic) f.copy(raw = "nonbasic land") else f, m.groupValues[2].trim().equals("your opponents control", true)))
        }
        Regex("""^~ enters(?: the battlefield)? tapped unless (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val cond = parseCondition(m.groupValues[1]) ?: return emptyList()
            return listOf(StaticEffect.EntersTapped(cond))
        }
        Regex("""^if an artifact or creature entering(?: the battlefield)? causes a triggered ability of a permanent you control to trigger, that ability triggers an additional time\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return listOf(StaticEffect.ExtraEtbTrigger()) }
        Regex("""^if a permanent entering(?: the battlefield)? causes a triggered ability of a permanent you control to trigger, that ability triggers an additional time\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return listOf(StaticEffect.ExtraEtbTrigger(anyPermanent = true)) }
        Regex("""^permanents entering(?: the battlefield)? don't cause abilities of permanents your opponents control to trigger\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return listOf(StaticEffect.NoEtbTriggersForOpponents) }
        Regex("""^creatures can't attack you(?: or planeswalkers you control)? unless their controller pays (\{[^}]+\}(?:\{[^}]+\})*) for each creature they control that's attacking you(?: or planeswalkers you control)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m -> return listOf(StaticEffect.AttackTax(m.groupValues[1])) }
        Regex("""^creatures entering(?: the battlefield)?( or dying)? don't cause abilities to trigger\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m -> return listOf(StaticEffect.NoEtbTriggers(m.groupValues[1].isNotEmpty())) }
        Regex("""^~ can't be blocked by (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.CREATURE)
            return if (f.verifiable) listOf(StaticEffect.Cant("be blocked", f)) else emptyList()
        }
        Regex("""^~ enters(?: the battlefield)? with (a|an|X|\w+) ([+-]\d/[+-]\d|\w+) counters? on it\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val n = if (m.groupValues[1].equals("x", true)) null else (number(m.groupValues[1]) ?: return emptyList())
            return listOf(StaticEffect.EntersWithCounters(m.groupValues[2], n))
        }
        // Chasm Skulker: "~ enters with a +1/+1 counter on it for each creature you control."
        Regex("""^~ enters(?: the battlefield)? with (?:a|an|\w+) ([+-]\d/[+-]\d|\w+) counters? on it for each (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val per = parseCount("the number of " + m.groupValues[2].trim())
            if (per !is CountExpr.Unknown) return listOf(StaticEffect.EntersWithCounters(m.groupValues[1], null, per = per))
        }
        // Kavu Primarch: "If ~ was kicked, it enters with four +1/+1 counters on it."
        Regex("""^if ~ was kicked, it enters(?: the battlefield)? with (a|an|\w+) ([+-]\d/[+-]\d|\w+) counters? on it\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val n = number(m.groupValues[1]) ?: return emptyList()
            return listOf(StaticEffect.EntersWithCounters(m.groupValues[2], n, onlyIfKicked = true))
        }
        Regex("""^~ can't (block|attack|be countered|be blocked|attack or block)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { return listOf(StaticEffect.Cant(it.groupValues[1].lowercase())) }
        Regex("""^(enchanted|equipped) (creature|permanent) can't (block|attack|attack or block|be blocked)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            return listOf(StaticEffect.Cant(m.groupValues[3].lowercase(), applies = ObjFilter(setOf(Kind.PERMANENT), raw = "${m.groupValues[1].lowercase()} ${m.groupValues[2].lowercase()}", attachedToSource = true)))
        }
        Regex("""^you control enchanted (?:creature|permanent|artifact|land)\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return listOf(StaticEffect.ControlEnchanted) }
        Regex("""^you have hexproof\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return listOf(StaticEffect.PlayerHexproof) }
        Regex("""^you can't lose the game and your opponents can't win the game\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return listOf(StaticEffect.CantLose) }
        Regex("""^nonbasic lands are mountains\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return listOf(StaticEffect.NonbasicLandsAreMountains) }
        // "Creatures without flying can't attack" (Moat), "Non-Eye creatures you control can't block": a filter
        // and a restriction, the same shape the engine already checks for enchanted creatures.
        Regex("""^([a-z][a-z0-9' -]*) can't (attack|block|attack or block)(?: this turn)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val what = m.groupValues[1].trim().lowercase()
            if ("creature" !in what) return@let    // "players can't…" is a different thing
            val f = parseFilter(what.replace(Regex("""^creatures\b"""), "creature").replace(Regex("""\bcreatures\b"""), "creature"), Kind.CREATURE)
            if (f.verifiable) return listOf(StaticEffect.Cant(m.groupValues[2].lowercase(), applies = f))
        }
        Regex("""^creatures with power greater than the number of cards in your hand can't attack\.?$""", RegexOption.IGNORE_CASE).matches(line).let { if (it) return listOf(StaticEffect.Cant("attack", powerAboveHand = true)) }
        // "~ can't attack unless defending player controls an Island" (Islandwalk's mirror image), "~ can't attack
        // unless you control another artifact": a restriction on declaring this creature as an attacker.
        Regex("""^(?:~|this creature) can't attack unless (?:the )?(defending player|you) controls? (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            var what = m.groupValues[2].trim().lowercase()
            // "four or more artifacts", "two or more other Wolves": the count is kept beside the filter.
            var n = 1
            Regex("""^(one|two|three|four|five|six|seven|\d+) or more (.+)$""").matchEntire(what)?.let { c ->
                n = c.groupValues[1].toIntOrNull() ?: listOf("one", "two", "three", "four", "five", "six", "seven").indexOf(c.groupValues[1]) + 1
                what = c.groupValues[2].removeSuffix("s")
            }
            if (Regex("""\b(?:more|fewer|than)\b""").containsMatchIn(what)) return@let
            val f = parseFilter(what.removePrefix("an ").removePrefix("a "), Kind.PERMANENT)
            if (!f.verifiable) return@let
            return listOf(if (m.groupValues[1].lowercase() == "you") StaticEffect.Cant("attack", unlessYouControl = f, unlessCount = n) else StaticEffect.Cant("attack", unlessDefenderControls = f, unlessCount = n))
        }
        if (Regex("""^~ attacks each combat if able\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.MustAttack)
        // "As long as you have 30 or more life, ~ gets +5/+5 and has flying." — the same thing said the other way round.
        // "…, ~ gets +2/+2, has flying, and attacks each combat if able" (Dragon's Rage Channeler): a list of
        // things the condition grants, not just one.
        Regex("""^as long as (.+?), ~ gets ([+-]\d+)/([+-]\d+)(?:,? and has (.+?)|, has (.+?)(?:,? and attacks each combat if able)?)?(?:,? and attacks each combat if able)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val cond = parseCondition(m.groupValues[1]) ?: return emptyList()
            val self = ObjFilter(setOf(Kind.PERMANENT), raw = "~")
            val out = mutableListOf<StaticEffect>(StaticEffect.PtModify(self, m.groupValues[2].toInt(), m.groupValues[3].toInt(), self = true, condition = cond))
            val kws = m.groupValues[4].ifEmpty { m.groupValues[5] }
            if (kws.isNotEmpty()) keywordsIn(kws)?.let { out += StaticEffect.KeywordGrant(self, it) } ?: return emptyList()
            if (line.contains("attacks each combat if able", true)) out += StaticEffect.MustAttack
            return out
        }
        Regex("""^~ gets ([+-]\d+)/([+-]\d+)(?: and has (.+?))? as long as (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val cond = parseCondition(m.groupValues[4]) ?: return emptyList()
            val self = ObjFilter(setOf(Kind.PERMANENT), raw = "~")
            val out = mutableListOf<StaticEffect>(StaticEffect.PtModify(self, m.groupValues[1].toInt(), m.groupValues[2].toInt(), self = true, condition = cond))
            if (m.groupValues[3].isNotEmpty()) keywordsIn(m.groupValues[3])?.let { out += StaticEffect.KeywordGrant(self, it) } ?: return emptyList()
            return out
        }
        Regex("""^~ has (.+?) as long as (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val cond = parseCondition(m.groupValues[2]) ?: return emptyList()
            val kws = keywordsIn(m.groupValues[1]) ?: return emptyList()
            return listOf(StaticEffect.KeywordGrant(ObjFilter(setOf(Kind.PERMANENT), raw = "~"), kws).let { StaticEffect.PtModify(it.filter, 0, 0, self = true, condition = cond) }).let { listOf(it[0], StaticEffect.KeywordGrant(ObjFilter(setOf(Kind.PERMANENT), raw = "~"), kws)) }
        }
        Regex("""^~'s power and toughness are each equal to (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m -> val c = parseCount(m.groupValues[1]); return listOf(StaticEffect.PtCda(c, c)) }
        Regex("""^~'s power is equal to (.+?) and its toughness is equal to that number plus (\d+)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m -> val c = parseCount(m.groupValues[1]); return listOf(StaticEffect.PtCda(c, c, 0, m.groupValues[2].toInt())) }
        Regex("""^~'s power is equal to (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m -> return listOf(StaticEffect.PtCda(parseCount(m.groupValues[1]), null)) }
        Regex("""^~'s toughness is equal to (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m -> return listOf(StaticEffect.PtCda(null, parseCount(m.groupValues[1]))) }
        if (Regex("""^(As ~ enters, choose (a|an) .+|As ~ enters, .+)$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.Note(line, listOf("614.1c")))
        if (Regex("""^(You may choose not to untap ~ during your untap step|~ doesn't untap during your untap step|Enchanted (creature|permanent) doesn't untap during its controller's untap step)\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.Note(line, listOf("502.3")))
        if (Regex("""^(LEVEL \d+.*|\d+/\d+|\{[^}]+\}(?:\{[^}]+\})* — \d+/\d+.*)$""").matches(line)) return listOf(StaticEffect.Note(line, listOf("702.87a")))
        if (Regex("""^If ~ is in your opening hand, you may begin the game with it on the battlefield\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.Note(line, listOf("103.6")))
        // "If you control a commander, you may cast this spell without paying its mana cost" (Fierce Guardianship
        // and the rest of that cycle): permission to cast for an alternative cost, which is not an effect the
        // spell has on resolution. Read as a static ability of the card, the way it works from hand.
        if (Regex("""^(?:if [^,]+, )?you may cast (?:~|this spell) without paying its mana cost\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.Note(line, listOf("118.9", "601.3")))
        // "If it's not your turn, you may exile a blue card from your hand rather than pay this spell's mana cost"
        // (the Force cycle, Daze, Misdirection): the same thing said as an alternative cost rather than as none.
        if (Regex("""^(?:if [^,]+, )?you may .+? rather than pay (?:~'s|this spell's) mana cost\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.Note(line, listOf("118.9", "601.3")))
        if (Regex("""^(?:Combat )?damage that would be dealt by (?:creatures|sources) you control can't be prevented\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.Note(line, listOf("615.12")))
        if (Regex("""^Each opponent can cast spells only any time they could cast a sorcery\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.OpponentsSorcerySpeed)
        if (Regex("""^Players can cast spells only during their own turns\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.OwnTurnOnly)
        if (Regex("""^Spells with the chosen name can't be cast\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.CantCastNamed)
        if (Regex("""^Prevent all combat damage that would be dealt to and (?:dealt )?by ~\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.PreventOwnCombatDamage)
        Regex("""^(Noncreature spells|Creature spells|Spells|Artifact spells|Enchantment spells|Instant and sorcery spells)(?: with mana value (\d+) or greater)?(?: with \{X\} in their mana costs)? can't be cast\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val f = when (m.groupValues[1].lowercase()) {
                "noncreature spells" -> ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.CREATURE), raw = "noncreature spell")
                "creature spells" -> ObjFilter(setOf(Kind.CREATURE), raw = "creature spell")
                "artifact spells" -> ObjFilter(setOf(Kind.ARTIFACT), raw = "artifact spell")
                "enchantment spells" -> ObjFilter(setOf(Kind.ENCHANTMENT), raw = "enchantment spell")
                "instant and sorcery spells" -> ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.CREATURE, Kind.ARTIFACT, Kind.ENCHANTMENT, Kind.PLANESWALKER, Kind.LAND), raw = "instant or sorcery spell")
                else -> ObjFilter(setOf(Kind.SPELL), raw = "spell")
            }
            return listOf(StaticEffect.CantCastFiltered(f, m.groupValues[2].toIntOrNull(), xInCost = line.contains("{X}")))
        }
        Regex("""^(noncreature spells|creature spells|spells) with mana value equal to the chosen number can't be cast\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val f = when (m.groupValues[1].lowercase()) {
                "noncreature spells" -> ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.CREATURE), raw = "noncreature spell")
                "creature spells" -> ObjFilter(setOf(Kind.CREATURE), raw = "creature spell")
                else -> ObjFilter(setOf(Kind.SPELL), raw = "spell")
            }
            return listOf(StaticEffect.CantCastFiltered(f, chosenNumber = true))
        }
        // "Activated abilities of artifacts can't be activated." / "Activated abilities of creatures your opponents control can't be activated."
        if (Regex("""^Activated abilities of sources with the chosen name can't be activated unless they're mana abilities\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.CantActivate(ObjFilter(setOf(Kind.PERMANENT), raw = "sources with the chosen name"), named = true, exceptMana = true))
        Regex("""^Activated abilities of (.+?) can't be activated\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.PERMANENT); if (f.verifiable) return listOf(StaticEffect.CantActivate(f))
        }
        if (Regex("""^During your turn, your opponents can't cast spells or activate abilities of artifacts, creatures, or enchantments\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.OpponentsLockedOnYourTurn)
        Regex("""^You can't cast ~ during your first(?:, second)?(?:, or third| or second)? turns? of the game\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { return listOf(StaticEffect.CantCastBeforeTurn(if (line.contains("third")) 4 else if (line.contains("second")) 3 else 2)) }
        Regex("""^(White|Blue|Black|Red|Green|Colorless|Multicolored) spells(?: your opponents cast| you cast)? cost \{(\d+)\} (more|less) to cast\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val c = mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')[m.groupValues[1].lowercase()] ?: return@let
            val f = ObjFilter(setOf(Kind.SPELL), colors = setOf(c), raw = "${m.groupValues[1].lowercase()} spell")
            val whose = when { line.contains("your opponents cast", true) -> Who.OPPONENT; line.contains("you cast", true) -> Who.YOU; else -> null }
            return listOf(StaticEffect.CostTax(f, m.groupValues[2].toInt() * (if (m.groupValues[3].lowercase() == "less") -1 else 1), whose))
        }
        Regex("""^(Noncreature spells|Nonartifact spells|Creature spells|Instant and sorcery spells|Spells|Artifact spells|Enchantment spells|Artifact and enchantment spells|Artifact, creature, and enchantment spells)(?: your opponents cast| you cast)? cost \{(\d+)\} (more|less) to cast\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val f = when (m.groupValues[1].lowercase()) {
                "artifact and enchantment spells" -> ObjFilter(setOf(Kind.ARTIFACT, Kind.ENCHANTMENT), raw = "artifact or enchantment spell")
                "artifact, creature, and enchantment spells" -> ObjFilter(setOf(Kind.ARTIFACT, Kind.CREATURE, Kind.ENCHANTMENT), raw = "artifact, creature, or enchantment spell") "nonartifact spells" -> ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.ARTIFACT), raw = "nonartifact spell"); "noncreature spells" -> ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.CREATURE), raw = "noncreature spell"); "creature spells" -> ObjFilter(setOf(Kind.CREATURE), raw = "creature spell"); "instant and sorcery spells" -> ObjFilter(setOf(Kind.SPELL), notKinds = setOf(Kind.CREATURE, Kind.ARTIFACT, Kind.ENCHANTMENT, Kind.PLANESWALKER, Kind.LAND), raw = "instant or sorcery spell"); "artifact spells" -> ObjFilter(setOf(Kind.ARTIFACT), raw = "artifact spell"); "enchantment spells" -> ObjFilter(setOf(Kind.ENCHANTMENT), raw = "enchantment spell"); else -> ObjFilter(setOf(Kind.SPELL), raw = "spell") }
            val whose = when { line.contains("your opponents cast", true) -> Who.OPPONENT; line.contains("you cast", true) -> Who.YOU; else -> null }
            return listOf(StaticEffect.CostTax(f, m.groupValues[2].toInt() * (if (m.groupValues[3].lowercase() == "less") -1 else 1), whose))
        }
        if (Regex("""^Counters can't be put on artifacts, creatures, enchantments, or lands\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.Replace(Replacement.CounterMultiplier(0, anyPlayer = true)))
        if (Regex("""^Players can't get counters\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.Note(line, listOf("122.1")))
        if (Regex("""^You may look at the top card of your library any time\.?$""", RegexOption.IGNORE_CASE).matches(line)) return listOf(StaticEffect.Note(line, listOf("401.5")))
        if (Regex("""^(As an additional cost to cast ~|~ costs \{[^}]+\} (less|more) to cast|You may cast ~ )""", RegexOption.IGNORE_CASE).containsMatchIn(line)) return listOf(StaticEffect.CostText(line))
        // "~ gets -X/-X, where X is your life total." (Death's Shadow)
        Regex("""^~ gets ([+-])X/\1X, where X is (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val c = parseCount(m.groupValues[2])
            if (c is CountExpr.Unknown) return emptyList()
            return listOf(StaticEffect.PtModifyByCount(c, m.groupValues[1] == "-"))
        }
        // "~ gets +1/+0 for each artifact you control": a static that recounts every time the size is asked for.
        Regex("""^~ gets ([+-]\d+)/([+-]\d+) for each (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { m ->
            val c = parseCount("the number of " + m.groupValues[3].trim())
            if (c is CountExpr.Unknown) return emptyList()
            return listOf(StaticEffect.PtModifyByCount(c, negative = false, power = m.groupValues[1].removePrefix("+").toInt(), toughness = m.groupValues[2].removePrefix("+").toInt()))
        }
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

    /** "you control a Swamp", "you control an artifact", "it's your turn", "you control three or more creatures". */
    fun parseCondition(text: String): Condition? {
        val t = text.trim().trimEnd('.')
        if (Regex("""^it's your turn$""", RegexOption.IGNORE_CASE).matches(t)) return Condition.YourTurn
        if (Regex("""^it's not your turn$""", RegexOption.IGNORE_CASE).matches(t)) return Condition.NotYourTurn
        Regex("""^you have (\d+) or more life$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m -> return Condition.LifeAtLeast(m.groupValues[1].toInt()) }
        Regex("""^an opponent has (\d+) or (?:more|less) life$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m -> return Condition.LifeAtLeast(m.groupValues[1].toInt(), opponent = true) }
        // Threshold and delirium, and the same counts written as a plain sentence.
        Regex("""^(?:there are |you have )?(one|two|three|four|five|six|seven|eight|nine|ten|\d+) or more cards? in your graveyard$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m ->
            return number(m.groupValues[1])?.let { Condition.GraveyardAtLeast(it) }
        }
        Regex("""^(?:there are )?(one|two|three|four|five|six|seven|eight|nine|ten|\d+) or more card types among cards in your graveyard$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m ->
            return number(m.groupValues[1])?.let { Condition.GraveyardAtLeast(it, cardTypes = true) }
        }
        Regex("""^you control (?:a|an|another|(?:at least )?(one|two|three|four|five|six|seven|\d+)(?: or more)?) (.+?)$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m ->
            val n = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { number(it) } ?: 1
            // "a Plains or an Island": either land type counts.
            val what = m.groupValues[2].replace(Regex("""\s+or\s+an?\s+""", RegexOption.IGNORE_CASE), " or ")
            val f = parseFilter(what, Kind.PERMANENT).let { it.copy(controller = Who.YOU) }
            return if (f.verifiable) Condition.ControlsMatching(f, n) else null
        }
        return null
    }

    /** "the number of Islands you control", "the number of creatures you control", else unknown. */
    fun parseCount(text: String): CountExpr {
        val t = text.trim().trimEnd('.')
        Regex("""^the number of (.+?) you control$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m ->
            // parseFilter skips the word "other" without recording it, and "for each other Human you control"
            // must not count the permanent doing the counting.
            val other = Regex("""^(?:other|another)\b""", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[1].trim())
            val f = parseFilter(m.groupValues[1], Kind.PERMANENT).copy(controller = Who.YOU, other = other)
            return if (f.verifiable) CountExpr.Permanents(f) else CountExpr.Unknown(t)
        }
        if (Regex("""^the number of card types among cards in all graveyards$""", RegexOption.IGNORE_CASE).matches(t)) return CountExpr.CardTypesInGraveyards
        if (Regex("""^your life total$""", RegexOption.IGNORE_CASE).matches(t)) return CountExpr.YourLifeTotal
        Regex("""^the number of (.+?) on the battlefield$""", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.PERMANENT)
            return if (f.verifiable) CountExpr.Permanents(f) else CountExpr.Unknown(t)
        }
        return CountExpr.Unknown(t)
    }

    private fun keywordsIn(text: String): Set<String>? {
        // "protection from colorless or from the color of your choice": one grant with a choice in it.
        if (Regex("""^protection from colou?rless or from the colou?r of your choice$""", RegexOption.IGNORE_CASE).matches(text.trim().trimEnd('.')))
            return setOf("protection from the color of your choice")
        val parts = text.lowercase().trimEnd('.').replace(" and from ", " and protection from ").split(Regex(""",\s*|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val ok = parts.isNotEmpty() && parts.all { it in keywordList || Regex("""^protection from (?:white|blue|black|red|green|colorless|everything|colored spells|spells|artifacts|creatures|instants|sorceries|planeswalkers|the color of your choice|[a-z]+)$""").matches(it) }
        return if (ok) parts.toSet() else null
    }

    // ---- effects -------------------------------------------------------------------------

    private val sentenceSplit = Regex("""(?<=\.)\s+(?=[A-Z~])""")

    fun parseEffect(text: String): Effect {
        Regex("""^look at the top X cards of your library, where X is your devotion to (white|blue|black|red|green)\. Put up to one of them on top of your library and the rest on the bottom of your library in a random order\. If X is greater than or equal to the number of cards in your library, you win the game\.?$""", RegexOption.IGNORE_CASE).matchEntire(text.trim())?.let { return Effect.WinIfDevotionCoversLibrary(mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G').getValue(it.groupValues[1].lowercase())) }
        if (Regex("""^If ~ was cast from your hand and you've cast another spell named ~ this game, you win the game\. Otherwise, put ~ into its owner's library seventh from the top and you gain (\d+) life\.?$""", RegexOption.IGNORE_CASE).matchEntire(text.trim())?.let { return Effect.WinIfCastBefore(it.groupValues[1].toInt()) } != null) Unit
        // "Exile up to one other target creature" (Solitude): read as one target. A spell cast with none chosen
        // is rarer than one cast with one, and the answer says what happens to the creature that was named.
        val t = text.trim().replace(Regex("""(?i)\bup to one (other )?target """), "target $1")
            .replace(Regex("""(?i)^(copy target [^.]+?)\. You may choose new targets for the copy\."""), "$1. you may choose new targets for the copy.").let { s ->
            Regex("""(?i)^(copy target [^.]+?)\. you may choose new targets for the copy\.$""").matchEntire(s)?.let { r -> return Effect.CopySpell(target(r.groupValues[1].removePrefix("copy target ").removePrefix("Copy target "), Kind.SPELL), true) } ?: s
        }
        // "Counter target spell. If that spell is countered this way, exile it instead of putting it into its
        // owner's graveyard." — that sentence says where the countered card goes, not a second effect. It is
        // taken out here so the rest of the card (Remand's "Draw a card.") still reads as its own sentences.
        val riderRe = Regex("""(?i)\s*if that spell is countered this way, (exile it|put it into its owner's hand|put it on top of its owner's library|put it on the bottom of its owner's library) instead of (?:putting it )?into (?:its owner's|that player's) graveyard\.""")
        riderRe.find(t)?.let { r ->
            val zone = when {
                r.groupValues[1].startsWith("exile", true) -> "exile"
                r.groupValues[1].contains("hand", true) -> "hand"
                else -> "library"
            }
            val inner = parseEffect(t.removeRange(r.range).trim())
            fun withZone(e: Effect): Effect = when (e) {
                is Effect.Counter -> e.copy(insteadZone = zone)
                is Effect.Seq -> Effect.Seq(e.effects.map { withZone(it) })
                else -> e
            }
            return withZone(inner)
        }
        val sentences = t.split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size > 1) {
            // "You may pay {2}. If you do, draw a card." / "You may sacrifice a creature. If you do, …"
            val out = mutableListOf<Effect>()
            var i = 0
            var lastTarget: TargetSpec? = null
            while (i < sentences.size) {
                val cur0 = sentences[i]; val next = sentences.getOrNull(i + 1)
                // Pronoun continuations: "It gets +1/+1 until end of turn." / "Put a +1/+1 counter on it." refer to the previous target.
                val lt = lastTarget
                fun deref(x: String): String = if (lt == null) x else x.replace(Regex("""(?i)^Prevent all combat damage that would be dealt to and dealt by (?:it|that creature) this turn"""), "Prevent all combat damage that would be dealt to and dealt by target ${lt.raw} this turn").replace(Regex("""(?i)^(?:It|That (?:creature|permanent|artifact|enchantment|land)) (gets|gains) """), "Target ${lt.raw} $1 ").replace(Regex("""(?i) on (?:it|that (?:creature|permanent))\.?$"""), " on target ${lt.raw}.")
                    .replace(Regex("""(?i)^(Untap|Tap|Destroy|Exile|Sacrifice) (?:it|that (?:creature|permanent|artifact|enchantment|land))\.?$"""), "$1 target ${lt.raw}.")
                    // "Then that creature deals damage equal to its power to target creature an opponent controls."
                    .replace(Regex("""(?i)^(?:Then )?(?:It|That creature) deals damage equal to its power to """), "Target ${lt.raw} deals damage equal to its power to ")
                // The same continuation can sit behind an intervening "if" ("Metalcraft — If you control three or
                // more artifacts, exile that creature"), where the sentence no longer starts with the verb.
                val cur = deref(cur0).let { c -> Regex("""(?i)^((?:[A-Z][A-Za-z'’]*(?: [A-Za-z'’]+){0,4}\s*[—–]\s*)?if [^,]+, )(.+)$""").matchEntire(c)?.let { r -> r.groupValues[1] + deref(r.groupValues[2]) } ?: c }
                // "That player may pay {2}. If they don't, you create a Treasure token." is an "unless they pay" effect.
                val mayPay = Regex("""^(That player|Its controller|Target player|Each opponent|You) may pay (\{[^}]+\}(?:\{[^}]+\})*|\d+ life)\.?$""", RegexOption.IGNORE_CASE).matchEntire(cur)
                val ifNot = Regex("""^If (?:they|that player|the player|you) (?:don't|doesn't|do not|does not), (.+)$""", RegexOption.IGNORE_CASE)
                val repeatRe = Regex("""^Repeat the following process (X|\d+|\w+) times?\.?$""", RegexOption.IGNORE_CASE)
                val revealRe = Regex("""^(Target player|Target opponent|Each opponent|Each player|That player) reveals? their hand\.?$""", RegexOption.IGNORE_CASE)
                val chooseRe = Regex("""^You choose an? (.+?) card from it(?: with mana value (\d+) or less)?\.?$""", RegexOption.IGNORE_CASE)
                val discardRe = Regex("""^(?:That player|They) discards? that card\.?$""", RegexOption.IGNORE_CASE)
                val third = sentences.getOrNull(i + 2)
                if (Regex("""^Choose a colou?r\.?$""", RegexOption.IGNORE_CASE).matches(cur) && next != null &&
                    Regex("""^Add an amount of mana of that colou?r equal to your devotion to that colou?r\.?$""", RegexOption.IGNORE_CASE).matches(next)) {
                    out += Effect.AddManaDevotion; i += 2
                    continue
                }
                val nameRe = Regex("""^Choose an? (.+?) card name\.?$""", RegexOption.IGNORE_CASE)
                val revealAllRe = Regex("""^(Target player|Target opponent|Each opponent|Each player|That player) reveals? their hand and discards? all cards with that name\.?$""", RegexOption.IGNORE_CASE)
                if (nameRe.matches(cur) && next != null && revealAllRe.matches(next)) {
                    val who = when (revealAllRe.find(next)!!.groupValues[1].lowercase()) {
                        "target player", "target opponent" -> Who.TARGET_PLAYER; "each opponent" -> Who.EACH_OPPONENT; "each player" -> Who.EACH_PLAYER; else -> Who.THAT_PLAYER
                    }
                    out += Effect.DiscardNamed(who, nameRe.find(cur)!!.groupValues[1].trim())
                    i += 2
                } else if (revealRe.matches(cur) && next != null && chooseRe.matches(next) && third != null && discardRe.matches(third)) {
                    val who = when (revealRe.find(cur)!!.groupValues[1].lowercase()) {
                        "target player", "target opponent" -> Who.TARGET_PLAYER; "each opponent" -> Who.EACH_OPPONENT; "each player" -> Who.EACH_PLAYER; else -> Who.THAT_PLAYER
                    }
                    val cm = chooseRe.find(next)!!
                    val what = cm.groupValues[1].trim()
                    val mv = cm.groupValues[2].toIntOrNull()
                    val f = parseFilter("$what card", Kind.CARD).let { if (mv == null) it else it.copy(maxManaValue = mv) }
                    out += Effect.DiscardChosen(who, f.takeIf { it.verifiable }, "a $what card" + (mv?.let { " with mana value $it or less" } ?: ""))
                    i += 3
                } else if (repeatRe.matches(cur) && next != null) {
                    val w = repeatRe.find(cur)!!.groupValues[1]
                    out += Effect.Repeat(parseSentence(next), if (w.equals("X", true)) 0 else (w.toIntOrNull() ?: number(w) ?: 1), x = w.equals("X", true)); i += 2
                } else if (mayPay != null && next != null && ifNot.containsMatchIn(next)) {
                    val payer = when (mayPay.groupValues[1].lowercase()) { "you" -> Who.YOU; "its controller" -> Who.CONTROLLER_OF_TARGET; "target player" -> Who.TARGET_PLAYER; "each opponent" -> Who.EACH_OPPONENT; else -> Who.THAT_PLAYER }
                    out += Effect.UnlessPays(parseSentence(ifNot.find(next)!!.groupValues[1].replaceFirstChar { it.uppercase() }), payer, mayPay.groupValues[2])
                    i += 2
                } else                 if (cur.startsWith("You may ", true) && next != null && next.startsWith("If you do, ", true)) {
                    val choice = cur.removePrefix("You may ").removePrefix("you may ").trimEnd('.')
                    val cost = payRe.matchEntire(choice)?.groupValues?.get(1)
                    out += Effect.IfYouDo(if (cost != null) Effect.Narrated("pay $cost", listOf("608.2g")) else parseSentence(choice.replaceFirstChar { it.uppercase() }), parseSentence(next.removePrefix("If you do, ").removePrefix("if you do, ").replaceFirstChar { it.uppercase() }), cost)
                    i += 2
                } else if (Regex("""^Spell mastery — If there are two or more instant and/or sorcery cards in your graveyard, ~ deals (\d+) damage instead\.?$""", RegexOption.IGNORE_CASE).matches(cur) && out.lastOrNull() is Effect.Damage) {
                    val n = Regex("""(\d+) damage""").find(cur)!!.groupValues[1].toInt()
                    out[out.lastIndex] = (out.last() as Effect.Damage).copy(masteryAmount = n); i++
                } else if (Regex("""^If (?:this spell|~) was kicked, it deals (\d+) damage instead\.?$""", RegexOption.IGNORE_CASE).matches(cur) && out.lastOrNull() is Effect.Damage) {
                    val n = Regex("""(\d+)""").find(cur)!!.groupValues[1].toInt()
                    out[out.lastIndex] = (out.last() as Effect.Damage).copy(kickedAmount = n); i++
                } else if (Regex("""^(?:It|They) can't be regenerated\.?$""", RegexOption.IGNORE_CASE).matches(cur) && out.isNotEmpty()) {
                    val prev = out.removeAt(out.lastIndex)
                    out += when (prev) { is Effect.Destroy -> prev.copy(noRegen = true); is Effect.ForAll -> prev.copy(noRegen = true); else -> Effect.Seq(listOf(prev, Effect.Narrated("it can't be regenerated", listOf("701.19c")))) }
                    i++
                } else { val e = parseSentence(cur); out += e; e.targets().lastOrNull()?.let { lastTarget = it }; i++ }
            }
            return if (out.size == 1) out[0] else Effect.Seq(out)
        }
        return parseSentence(t)
    }

    private val unlessRe = Regex("""^(.+?) unless (that player|its controller|an opponent|you|target player|they) pays? (\{[^}]+\}(?:\{[^}]+\})*(?:, where X is [^.]+)?|\d+ life)\.?$""", RegexOption.IGNORE_CASE)
    private val mayRe = Regex("""^you may (.+)$""", RegexOption.IGNORE_CASE)
    private val drawRe = Regex("""^(you |target player |that player |each player )?draws? (a|an|\w+|\d+) cards?\.?$""", RegexOption.IGNORE_CASE)
    private val damageRe = Regex("""^(?:~|it) deals (\d+|X) damage to (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val counterRe = Regex("""^counter target (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val destroyRe = Regex("""^destroy target (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val bounceRe = Regex("""^return (~|target .+?) to its owner's hand\.?$""", RegexOption.IGNORE_CASE)
    // Kor Skyfisher, Whitemane Lion, Stonecloaker: a chosen permanent, not a targeted one.
    private val bounceChosenRe = Regex("""^return (?:a|an) (.+?) you control to (?:its owner's hand|your hand)\.?$""", RegexOption.IGNORE_CASE)
    private val createTokenRe = Regex("""^(?:(you|its controller|that player|target player|each opponent|each player) )?creates? (a|an|\d+|two|three|four|five) ((?:\d+/\d+ )?(?:(?:white|blue|black|red|green|colorless)(?: and \w+)? )*(?:[A-Z][a-z]+ )*(?:artifact creature |creature |artifact |enchantment )?tokens?(?: with [a-z ,]+?)?)(?: named .+)?\.?$""", RegexOption.IGNORE_CASE)
    private val exileRe = Regex("""^exile target (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val tapRe = Regex("""^tap target (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val untapRe = Regex("""^untap target (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val pumpRe = Regex("""^target (.+?) gets ([+-]\d+)/([+-]\d+) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val pumpGainRe = Regex("""^target (.+?) gets ([+-]\d+)/([+-]\d+) and gains (.+?) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val gainRe = Regex("""^(another )?target (.+?) gains (.+?) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val gainSelfRe = Regex("""^~ gains (.+?) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val payRe = Regex("""^(?:you may )?pay (\{[^}]+\}(?:\{[^}]+\})*|\d+ life)\.?$""", RegexOption.IGNORE_CASE)
    private val gainLifeRe = Regex("""^(you|target player|that player|each player|each opponent|its controller) gains? (\d+) life\.?$""", RegexOption.IGNORE_CASE)
    private val loseLifeRe = Regex("""^(you|target player|that player|they|each opponent|each player|its controller|that creature's controller) loses? (\d+|X) life\.?$""", RegexOption.IGNORE_CASE)

    private val selfPumpRe = Regex("""^~ gets ([+-]\d+)/([+-]\d+) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val massPumpRe = Regex("""^(?:all |each )?(.+?) (?:get|gets) ([+-]\d+)/([+-]\d+) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val massPumpGainRe = Regex("""^(?:all |each )?(.+?) (?:get|gets) ([+-]\d+)/([+-]\d+) and (?:gain|gains) (.+?) until end of turn\.?$""", RegexOption.IGNORE_CASE)
    private val gainControlRe = Regex("""^gain control of target (.+?)( until end of turn)?\.?$""", RegexOption.IGNORE_CASE)
    private val countersOnRe = Regex("""^put (a|an|\w+|\d+|X) ([+-]\d/[+-]\d|\w+) counters? on (~|target .+?|each .+?)\.?$""", RegexOption.IGNORE_CASE)
    // Zur: "search your library for an enchantment card with mana value 3 or less, put it onto the battlefield, then shuffle"
    private val zurRe = Regex("""^search your library for (an?) (.+?) card(?: with mana value (\d+) or less)?, put (?:it|that card) onto the battlefield( tapped)?( and attacking)?, then shuffle\.?$""", RegexOption.IGNORE_CASE)
    private fun zurEffect(m: MatchResult): Effect? {
        val f = parseFilter(m.groupValues[2], Kind.PERMANENT)
        return if (f.verifiable) Effect.PutFromHand(f, null, tapped = m.groupValues[4].isNotEmpty(), attacking = m.groupValues[5].isNotEmpty(), fromLibrary = true, maxMv = m.groupValues[3].toIntOrNull()) else null
    }
    private val forAllRe = Regex("""^(destroy|exile|tap|untap) (?:all|each) (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val tuckAllRe = Regex("""^put (?:all|each) (.+?) on the bottom of (?:their|its) owners?' librar(?:y|ies)(?: in a random order)?\.?$""", RegexOption.IGNORE_CASE)
    private val damageEachRe = Regex("""^(?:~|it) deals (\d+) damage to each (.+?)\.?$""", RegexOption.IGNORE_CASE)
    private val narratedRes: List<Pair<Regex, List<String>>> = listOf(
        Regex("""^(?:then )?(?:(?:defending|that|target|each) player )?reveals? the top card of (?:their|your) library\.?$""", RegexOption.IGNORE_CASE) to listOf("701.20a"),
        // "~ connives": draw then discard, with a counter if a nonland card was discarded (701.50a).
        Regex("""^(?:~|it|each of them) connives?(?: \d+)?\.?$""", RegexOption.IGNORE_CASE) to listOf("701.50a"),
        // "You may play the exiled card this turn": a permission the engine doesn't track.
        Regex("""^(?:you may )?play (?:the exiled card|that card|those cards|the exiled cards)(?: this turn| until the end of your next turn| for as long as it remains exiled)?\.?$""", RegexOption.IGNORE_CASE) to listOf("601.3"),
        Regex("""^if it's a permanent card, they put it onto the battlefield\.?$""", RegexOption.IGNORE_CASE) to listOf("608.2c"),
        Regex("""^if it's an? \w+(?: \w+)? card, (?:that player|they|you) puts? it into (?:their|your) hand\.?$""", RegexOption.IGNORE_CASE) to listOf("608.2c"),
        Regex("""^you choose an? (?:(?:nonland|noncreature|nonbasic|nonartifact|creature|land|artifact|enchantment|instant or sorcery|instant|sorcery),? )*card from it\.?$""", RegexOption.IGNORE_CASE) to listOf("701.20a"),
        Regex("""^that player discards (?:that card|it|a card|\w+ cards?)\.?$""", RegexOption.IGNORE_CASE) to listOf("701.9a"),
        Regex("""^put (?:a|an|\w+|\d+) cards? from your hand on top of your library(?: in any order)?\.?$""", RegexOption.IGNORE_CASE) to listOf("401.4"),
        Regex("""^scry (\d+)\.?$""", RegexOption.IGNORE_CASE) to listOf("701.22a"),
        Regex("""^surveil (\d+)\.?$""", RegexOption.IGNORE_CASE) to listOf("701.25a"),
        Regex("""^(?:you |target player |each player )?mills? (\w+|\d+) cards?\.?$""", RegexOption.IGNORE_CASE) to listOf("701.17a"),
        Regex("""^look at the top (?:(\w+|\d+) )?cards? of your library.*$""", RegexOption.IGNORE_CASE) to listOf("701.22a"),
        // Looking at a hidden zone: the engine doesn't track what is in one, so the answer says it happened and why nothing turns on it.
        Regex("""^look at (?:target player's|that player's|each opponent's|an opponent's) hand\.?$""", RegexOption.IGNORE_CASE) to listOf("400.2"),
        Regex("""^look at the top (?:(\w+|\d+) )?cards? of (?:target player's|that player's|an opponent's) library\.?$""", RegexOption.IGNORE_CASE) to listOf("400.2"),
        Regex("""^search your library for (?:a|an|up to \w+) .+?(?:, then shuffle|\. Then shuffle|then shuffle)?\.?$""", RegexOption.IGNORE_CASE) to listOf("701.23a", "701.24a"),
        Regex("""^shuffle\.?$""", RegexOption.IGNORE_CASE) to listOf("701.24a"),
        Regex("""^(?:you |target player |each player )?discards? (a|an|\w+|\d+) cards?(?: at random)?\.?$""", RegexOption.IGNORE_CASE) to listOf("701.9a"),
        Regex("""^draw (a|\w+) cards?, then discard (a|\w+) cards?\.?$""", RegexOption.IGNORE_CASE) to listOf("121.1", "701.9a"),
        Regex("""^return target (.+?) card from your graveyard to your hand\.?$""", RegexOption.IGNORE_CASE) to listOf("400.7"),
        Regex("""^sacrifice (~|a|an|\w+) .*$""", RegexOption.IGNORE_CASE) to listOf("701.21a"),
        Regex("""^sacrifice ~\.?$""", RegexOption.IGNORE_CASE) to listOf("701.21a"),
        Regex("""^create (a|an|\w+|\d+|X) (?:.+? )?tokens?.*$""", RegexOption.IGNORE_CASE) to listOf("701.7a"),
        Regex("""^you gain (\d+) life for each .+$""", RegexOption.IGNORE_CASE) to listOf("119.3"),
        Regex("""^~ deals damage equal to .+$""", RegexOption.IGNORE_CASE) to listOf("120.3"),
        Regex("""^you get \{E\}.*$""", RegexOption.IGNORE_CASE) to listOf("107.14"),
        Regex("""^it can't be regenerated\.?$""", RegexOption.IGNORE_CASE) to listOf("701.19a"),
        Regex("""^target (?:opponent|player) reveals their hand\.?$""", RegexOption.IGNORE_CASE) to listOf("701.20a"),
        Regex("""^reveal .+$""", RegexOption.IGNORE_CASE) to listOf("701.20a"),
        Regex("""^attach (?:~|it) to target .+$""", RegexOption.IGNORE_CASE) to listOf("701.3a"),
        Regex("""^put (?:the rest|them|it|the other cards?|the remaining cards?|(?:one|two|three|\d+) of them) (?:on the bottom|on top|into your hand|into your graveyard|back).*$""", RegexOption.IGNORE_CASE) to listOf("401.4"),
        Regex("""^return (?:~|target .+?|up to \w+ target .+?) from (?:your|a|their) graveyard to (?:your hand|its owner's hand|the battlefield|the top of your library).*$""", RegexOption.IGNORE_CASE) to listOf("400.7"),
        Regex("""^transform (?:~|it|target .+?)\.?$""", RegexOption.IGNORE_CASE) to listOf("701.27a"),
        Regex("""^exile ~\.?$""", RegexOption.IGNORE_CASE) to listOf("701.13a"),
        Regex("""^destroy ~\.?$""", RegexOption.IGNORE_CASE) to listOf("701.8a"),
        Regex("""^untap ~\.?$""", RegexOption.IGNORE_CASE) to listOf("701.26b"),
        Regex("""^tap ~\.?$""", RegexOption.IGNORE_CASE) to listOf("701.26a"),
        Regex("""^as ~ enters, choose (?:a|an) .+$""", RegexOption.IGNORE_CASE) to listOf("614.1c"),
        Regex("""^you may have ~ enter as a copy of .+$""", RegexOption.IGNORE_CASE) to listOf("707.9", "614.1c"),
        Regex("""^copy target .+$""", RegexOption.IGNORE_CASE) to listOf("707.10"),
        Regex("""^at the beginning of (?:your|the) next .+, .+$""", RegexOption.IGNORE_CASE) to listOf("603.7a"),
        Regex("""^~ deals (\d+) damage to you\.?$""", RegexOption.IGNORE_CASE) to listOf("120.3a"),
        Regex("""^~ fights target .+$""", RegexOption.IGNORE_CASE) to listOf("701.14a"),
        Regex("""^(?:each|target) (?:opponent|player) (?:discards|sacrifices|mills|exiles) .+$""", RegexOption.IGNORE_CASE) to listOf("701.9a"),
        Regex("""^shuffle (?:~|it|target .+?) into (?:its|your) owner's library\.?$""", RegexOption.IGNORE_CASE) to listOf("701.24a"),
        Regex("""^exile the top (?:card|(?:\w+|\d+) cards) of your library.*$""", RegexOption.IGNORE_CASE) to listOf("406.3"),
        Regex("""^spend this mana only .+$""", RegexOption.IGNORE_CASE) to listOf("106.6"),
        Regex("""^(?:you may )?play (?:it|that card|those cards|cards exiled with ~) .+$""", RegexOption.IGNORE_CASE) to listOf("601.3"),
        Regex("""^goad target .+$""", RegexOption.IGNORE_CASE) to listOf("701.15a"),
        Regex("""^investigate\.?$""", RegexOption.IGNORE_CASE) to listOf("701.16a"),
        Regex("""^populate\.?$""", RegexOption.IGNORE_CASE) to listOf("701.36a"),
        Regex("""^manifest (?:the top card of your library|dread).*$""", RegexOption.IGNORE_CASE) to listOf("701.40a"),
        Regex("""^you may cast (?:it|that card|the exiled card).*$""", RegexOption.IGNORE_CASE) to listOf("601.2"),
        Regex("""^amass .+$""", RegexOption.IGNORE_CASE) to listOf("701.47a"),
        Regex("""^support \d+\.?$""", RegexOption.IGNORE_CASE) to listOf("701.41a"),
        Regex("""^explore\.?$""", RegexOption.IGNORE_CASE) to listOf("701.44a"),
        Regex("""^~ explores\.?$""", RegexOption.IGNORE_CASE) to listOf("701.44a"),
        Regex("""^(?:you |target player )?(?:may )?draw (a|\w+) cards? for each .+$""", RegexOption.IGNORE_CASE) to listOf("121.1"),
        Regex("""^(?:you|each player) (?:may )?shuffle (?:your|their) (?:hand and )?graveyard into (?:your|their) library.*$""", RegexOption.IGNORE_CASE) to listOf("701.24a"),
        Regex("""^(?:target player |each player |you )?exiles? .+ from (?:your|their|a) graveyard.*$""", RegexOption.IGNORE_CASE) to listOf("701.13a"),
        Regex("""^destroy target (.+?) at the beginning of the next end step\.?$""", RegexOption.IGNORE_CASE) to listOf("603.7a"),
    )

    // Longest first: "one or more" and "one or both" must beat the bare "one", or the count is read as "one".
    private val modeCount = """one or more|one or both|any number|up to \w+|one|two|three|four|five"""
    private val modalRe = Regex("""^(.*?)Choose ($modeCount)(?: —|\.)?(?: You may choose the same mode more than once\.)?\s*((?:• .+?)+)$""", RegexOption.IGNORE_CASE)
    private val preventNextRe = Regex("""^prevent the next (\d+) damage that would be dealt to (any target|target creature or player|target creature|target player|you|target creature or planeswalker|target permanent or player) this turn\.?$""", RegexOption.IGNORE_CASE)
    // The "to" part is read as a filter rather than matched against a fixed list, so "creatures and planeswalkers
    // you control" and "creature tokens you control" work without their own entries. Anything parseFilter can't
    // verify is still reported unparsed.
    // "this turn" sits either after what the damage is dealt to or right at the end, depending on the card.
    private val preventAllTurnRe = Regex("""^prevent all (combat )?damage that would be dealt(?: to (?!and\b)([a-z][a-z' ]*?))?(?: this turn)?(?: by ([a-z][a-z' ]*?))?(?: this turn)?\.?$""", RegexOption.IGNORE_CASE)
    private val regenerateRe = Regex("""^regenerate (~|target .+?)\.?$""", RegexOption.IGNORE_CASE)

    private fun parseSentence(s0: String): Effect {
        // "Metalcraft — If you control three or more artifacts, …": the ability word names the ability and says
        // nothing (207.2c). Abilities have it stripped as they are read; a spell's own sentences need it too.
        val s = s0.replace(abilityWordStatic, "")
        manaRe.matchEntire(s.trimEnd('.'))?.let { return Effect.AddMana(it.groupValues[1]) }
        // "(You may) have ~ deal 3 damage to any target" says what "~ deals 3 damage to any target" says.
        Regex("""^have (?:~|it) deal (.+)$""", RegexOption.IGNORE_CASE).matchEntire(s.trim())?.let { return parseSentence("~ deals " + it.groupValues[1]) }
        Regex("""^target (player|opponent) reveals their hand\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.NarratedTargeted(target(m.groupValues[1].lowercase()), "reveals their hand", listOf("701.20a")) }
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
        // The Circles of Protection: "The next time a red source of your choice would deal damage to you this
        // turn, prevent that damage." One damage event from one source, then the shield is spent (615.8).
        Regex("""^the next time a (white|blue|black|red|green|colou?rless) source of your choice would deal damage to you this turn, prevent that damage\.?$""", RegexOption.IGNORE_CASE).matchEntire(s.trim())?.let { m ->
            val colour = m.groupValues[1].lowercase()
            val ch = mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')[colour] ?: return@let
            val from = ObjFilter(setOf(Kind.PERMANENT), colors = setOf(ch), raw = "a $colour source")
            return Effect.CreateShield(Replacement.PreventDamage(null, null, Who.YOU, false, from, once = true), null)
        }
        preventAllTurnRe.matchEntire(s)?.let { m ->
            val combat = m.groupValues[1].isNotEmpty(); val to = m.groupValues[2].lowercase(); val by = m.groupValues[3]
            val from = if (by.isEmpty()) null else parseFilter(by.removePrefix("target ").removePrefix("a ").removePrefix("an "), Kind.CREATURE).takeIf { it.verifiable } ?: return Effect.Unparsed(s)
            return when {
                to.isEmpty() -> Effect.CreateShield(Replacement.PreventDamage(null, null, Who.ANY_PLAYER, combat, from).copy(to = ObjFilter(setOf(Kind.PERMANENT), raw = "everything")), null)
                to == "you" -> Effect.CreateShield(Replacement.PreventDamage(null, null, Who.YOU, combat, from), null)
                // "you and creatures you control": the player plus everything the rest of the phrase describes.
                to.startsWith("you and ") -> {
                    val f = parseFilter(to.removePrefix("you and "), Kind.PERMANENT)
                    if (!f.verifiable) return Effect.Unparsed(s)
                    Effect.CreateShield(Replacement.PreventDamage(null, f, Who.YOU, combat, from), null)
                }
                to.startsWith("target") || to == "any target" -> Effect.CreateShield(Replacement.PreventDamage(null, null, null, combat, from), target(to))
                else -> {
                    val f = parseFilter(to, Kind.CREATURE)
                    if (!f.verifiable) return Effect.Unparsed(s)
                    Effect.CreateShield(Replacement.PreventDamage(null, f, null, combat, from), null)
                }
            }
        }
        selfPumpRe.matchEntire(s)?.let { return Effect.PumpSelf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        // "Each opponent loses 3 life unless that player sacrifices a nonland permanent of their choice or discards a card."
        Regex("""^(each opponent|each player|target player|that player) loses (\d+) life unless (?:that player|they) (?:sacrifices? (?:an? |two )?(.+?)(?: of (?:their|his or her) choice)?)?(?:(?: or )?discards? a card)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val who = when (m.groupValues[1].lowercase()) { "each opponent" -> Who.EACH_OPPONENT; "each player" -> Who.EACH_PLAYER; "target player" -> Who.TARGET_PLAYER; else -> Who.THAT_PLAYER }
            val filter = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { parseFilter(it, Kind.PERMANENT) }
            if (filter == null || filter.verifiable) return Effect.LoseLifeUnlessSacOrDiscard(who, m.groupValues[2].toInt(), filter, discard = s.contains("discard", true))
        }
        // "Until end of turn, creatures you control have base power and toughness X/X and gain all creature types." (Mirror Entity)
        Regex("""^(?:until end of turn, )?(?:all |each )?(.+?) (?:have|has) base power and toughness (X|\d+)/(X|\d+)((?: and gain all creature types)?)(?: until end of turn)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            if (!m.groupValues[1].startsWith("target", true) && m.groupValues[1] != "~") { val f = parseFilter(m.groupValues[1], Kind.CREATURE); if (f.verifiable) return Effect.SetBasePtAll(f, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].toIntOrNull() ?: 0, x = m.groupValues[2].equals("X", true), allCreatureTypes = m.groupValues[4].isNotEmpty()) }
        }
        massPumpRe.matchEntire(s)?.let { m -> if (!m.groupValues[1].startsWith("target", true)) { val f = parseFilter(m.groupValues[1], Kind.CREATURE); if (f.verifiable) return Effect.PumpAll(f, m.groupValues[2].toInt(), m.groupValues[3].toInt()) } }
        Regex("""^(?:all |each )?(.+?) (?:gain|gains) (.+?) and (?:get|gets) \+X/\+X until end of turn, where X is (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.CREATURE); val kws = keywordsIn(m.groupValues[2]); val count = parseCount(m.groupValues[3])
            if (f.verifiable && kws != null && count !is CountExpr.Unknown) return Effect.PumpAllCount(f, count, kws.toList())
        }
        Regex("""^(?:all |each )?(.+?) (?:get|gets) \+X/\+X until end of turn, where X is (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.CREATURE); val count = parseCount(m.groupValues[2])
            if (f.verifiable && count !is CountExpr.Unknown && !m.groupValues[1].startsWith("target", true)) return Effect.PumpAllCount(f, count, emptyList())
        }
        Regex("""^(target opponent|target player|that player|each opponent|you) loses? that much life\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            return Effect.LoseLifeThatMuch(when (m.groupValues[1].lowercase()) { "target opponent", "target player" -> Who.TARGET_PLAYER; "each opponent" -> Who.EACH_OPPONENT; "you" -> Who.YOU; else -> Who.THAT_PLAYER })
        }
        // Muxus: "~ gets +1/+1 until end of turn for each other Goblin you control."
        Regex("""^~ gets ([+-]\d+)/([+-]\d+)(?: until end of turn)? for each (.+?)(?: until end of turn)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val count = parseCount("the number of " + m.groupValues[3].trim())
            if (count !is CountExpr.Unknown) return Effect.PumpSelfCount(count, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        // Palace Sentinels, Custodi Lich: "you become the monarch." / "that player becomes the monarch."
        Regex("""^(you|target opponent|target player|that player|its controller|they) becomes? the monarch\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            return Effect.BecomeMonarch(when (m.groupValues[1].lowercase()) {
                "you" -> Who.YOU; "target opponent", "target player" -> Who.TARGET_PLAYER; else -> Who.THAT_PLAYER
            })
        }
        // Waterknot, Kasmina's Transmutation: "tap enchanted creature."
        Regex("""^tap enchanted (?:creature|permanent)\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { return Effect.TapAttached }
        // Mutavault, Celestial Colonnade, Inkmoth Nexus: "until end of turn, ~ becomes a 4/4 white and blue Elemental creature with flying and vigilance."
        Regex("""^(?:until end of turn, )?~ becomes an? (\d+)/(\d+)((?: (?:white|blue|black|red|green|colorless)(?:,|(?: and)?)?)*)((?: [A-Za-z'-]+)*?) (?:artifact )?creature(?: with (.+?))?(?: until end of turn)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val colours = Regex("""white|blue|black|red|green""", RegexOption.IGNORE_CASE).findAll(m.groupValues[3]).map { c ->
                when (c.value.lowercase()) { "white" -> 'W'; "blue" -> 'U'; "black" -> 'B'; "red" -> 'R'; else -> 'G' }
            }.toSet()
            val words = m.groupValues[4].trim().split(' ').map { it.trim() }.filter { it.isNotEmpty() }
            val allTypes = m.groupValues[5].contains("all creature types", true)
            val kws = if (allTypes) emptyList() else keywordsIn(m.groupValues[5])?.toList() ?: return@let
            val subtypes = words.filter { it.first().isUpperCase() }
            if (words.size == subtypes.size) return Effect.AnimateSelf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), subtypes, colours, kws, allTypes, stillALand = false)
        }
        // "~ becomes a 2/2 creature with all creature types until end of turn."
        Regex("""^(?:until end of turn, )?~ becomes an? (\d+)/(\d+) creature with all creature types(?: until end of turn)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            return Effect.AnimateSelf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), emptyList(), emptySet(), emptyList(), allCreatureTypes = true, stillALand = false)
        }
        // "It's still a land." always follows an animation; it changes nothing on its own but shouldn't read as unmodeled.
        Regex("""^it's still an? (?:land|artifact|enchantment)\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { return Effect.Seq(emptyList()) }
        // Bloodghast: "return ~ from your graveyard to the battlefield."
        Regex("""^return ~ from your graveyard to the battlefield( tapped)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.ReturnSelfFromGraveyard(m.groupValues[1].isNotEmpty()) }
        // Questing Beast: "it deals that much damage to target planeswalker that player controls."
        Regex("""^(?:~|it) deals that much damage to target (.+?)(?: that player controls| that opponent controls)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val raw = m.groupValues[1].trim()
            val t = target("target $raw")
            if (t.filter.verifiable) return Effect.DamageThatMuch(if (m.groupValues[0].contains("that player controls", true) || m.groupValues[0].contains("that opponent controls", true)) t.copy(filter = t.filter.copy(controller = Who.OPPONENT, raw = "$raw that player controls"), raw = "target $raw that player controls") else t)
        }
        Regex("""^the owner of target (.+?) shuffles it into their library\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.ShuffleIntoLibrary(target(m.groupValues[1], Kind.PERMANENT)) }
        massPumpGainRe.matchEntire(s)?.let { m -> if (!m.groupValues[1].startsWith("target", true)) { val f = parseFilter(m.groupValues[1], Kind.CREATURE); val kws = keywordsIn(m.groupValues[4]); if (f.verifiable && kws != null) return Effect.PumpAll(f, m.groupValues[2].toInt(), m.groupValues[3].toInt(), kws.toList()) } }
        gainControlRe.matchEntire(s)?.let { m -> return Effect.GainControl(target("target " + m.groupValues[1]), m.groupValues[2].isNotEmpty()) }
        // "Permanents you control gain indestructible until end of turn", "Creatures you control gain flying until end of turn"
        Regex("""^(?:all |each )?(.+?) (?:gain|gains) (.+?) until end of turn\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            if (!m.groupValues[1].startsWith("target", true) && !m.groupValues[1].startsWith("another target", true) && m.groupValues[1] != "~") { val f = parseFilter(m.groupValues[1], Kind.PERMANENT); val kws = keywordsIn(m.groupValues[2]); if (f.verifiable && kws != null) return Effect.PumpAll(f, 0, 0, kws.toList()) }
        }
        Regex("""^Remove all counters from (target .+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.RemoveAllCounters(target(m.groupValues[1])) }
        countersOnRe.matchEntire(s)?.let { m ->
            val isX = m.groupValues[1].equals("X", true)
            val n = if (isX) 0 else (number(m.groupValues[1]) ?: return Effect.Unparsed(s))
            val where = m.groupValues[3]
            return when {
                where == "~" -> Effect.PutCounters(null, m.groupValues[2], n, x = isX)
                where.startsWith("target", true) -> Effect.PutCounters(target(where), m.groupValues[2], n, x = isX)
                where.startsWith("each ", true) -> { val f = parseFilter(where.substring(5), Kind.CREATURE); if (f.verifiable) Effect.PutCounters(null, m.groupValues[2], n, all = f, x = isX) else Effect.Unparsed(s) }
                else -> Effect.Unparsed(s)
            }
        }
        forAllRe.matchEntire(s)?.let { m -> val f = parseFilter(m.groupValues[2], Kind.PERMANENT); if (f.verifiable) return Effect.ForAll(f, m.groupValues[1].lowercase()) }
        // Aetherize, Evacuation: "Return all attacking creatures to their owner's hand." / "Return all creatures to their owners' hands."
        Regex("""^return (?:all|each) (.+?) to (?:their owners?' hands?|its owner's hand|their owner's hands?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> val f = parseFilter(m.groupValues[1], Kind.PERMANENT); if (f.verifiable) return Effect.ForAll(f, "bounce") }
        // "Each player discards their hand, then draws seven cards." (Wheel of Fortune, Windfall-style)
        Regex("""^each player discards (?:their|his or her) hand, then draws (\w+|\d+) cards?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            number(m.groupValues[1])?.let { return Effect.Seq(listOf(Effect.Narrated("each player discards their hand", listOf("701.9a")), Effect.Draw(Who.EACH_PLAYER, it))) }
        }
        Regex("""^you may (search your library for .+)$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> zurRe.matchEntire(m.groupValues[1])?.let { z -> zurEffect(z)?.let { return Effect.May(it) } } }
        Regex("""^(that player|its controller|you|target player) may search (?:their|your) library for (.+?)(?:, then shuffle)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            return Effect.May(Effect.Narrated("search ${if (m.groupValues[1].lowercase() == "you") "your" else "their"} library for ${m.groupValues[2]}, then shuffle", listOf("701.23a", "701.24a")), when (m.groupValues[1].lowercase()) { "you" -> Who.YOU; "target player" -> Who.TARGET_PLAYER; "its controller" -> Who.CONTROLLER_OF_TARGET; else -> Who.THAT_PLAYER })
        }
        tuckAllRe.matchEntire(s)?.let { m -> val f = parseFilter(m.groupValues[1], Kind.PERMANENT); if (f.verifiable) return Effect.ForAll(f, "tuck") }
        // "Add {G} for each creature you control." (Gaea's Cradle, Cabal Coffers)
        Regex("""^add (\{[^}]+\}) for each (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val f = parseFilter(m.groupValues[2], Kind.PERMANENT)
            if (f.verifiable) return Effect.AddManaPer(m.groupValues[1], f)
        }
        // "If you control an Urza's Mine and an Urza's Power-Plant, add {C}{C}{C} instead."
        Regex("""^if you control (an?[^,]+?) and (an?[^,]+?), add ((?:\{[^}]+\})+) instead\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val a = m.groupValues[1].trim().removePrefix("an ").removePrefix("a ").trim()
            val b = m.groupValues[2].trim().removePrefix("an ").removePrefix("a ").trim()
            if (a.isNotEmpty() && b.isNotEmpty()) return Effect.AddManaInstead(listOf(a, b), m.groupValues[3])
        }
        if (Regex("""^counter that spell(?: or ability)?\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.CounterThatSpell
        // "Exile target player's graveyard" (Bojuka Bog), "exile each opponent's graveyard".
        Regex("""^exile (target player|target opponent|that player|each player|each opponent|your)(?:'s)? graveyard\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            return Effect.ExileGraveyard(when (m.groupValues[1].lowercase()) { "target player", "target opponent" -> Who.TARGET_PLAYER; "that player" -> Who.THAT_PLAYER; "each player" -> Who.EACH_PLAYER; "each opponent" -> Who.EACH_OPPONENT; else -> Who.YOU })
        }
        Regex("""^(you |target player |that player |each player |each opponent )?mills? (\w+|\d+) cards?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val who = when (m.groupValues[1].trim().lowercase()) { "target player" -> Who.TARGET_PLAYER; "that player" -> Who.THAT_PLAYER; "each player" -> Who.EACH_PLAYER; "each opponent" -> Who.EACH_OPPONENT; else -> Who.YOU }
            number(m.groupValues[2])?.let { return Effect.Mill(who, it) }
        }
        // "Create X 1/1 red Goblin creature tokens, where X is the number of Goblins you control."
        Regex("""^(?:(you|that player|target player) )?creates? X (.+? tokens?), where X is the number of (.+?)(?: you control)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val who = when (m.groupValues[1].lowercase().trim()) { "that player" -> Who.THAT_PLAYER; "target player" -> Who.TARGET_PLAYER; else -> Who.YOU }
            val desc = m.groupValues[2].trim().removeSuffix("s").let { if (it.endsWith(" token")) it else "$it token" }
            val f = parseFilter(m.groupValues[3], Kind.PERMANENT).let { if (s.contains("you control", true)) it.copy(controller = Who.YOU, raw = it.raw + " you control") else it }
            if (Generic.token(desc) != null && f.verifiable) return Effect.CreateToken(who, 0, desc, CountExpr.Permanents(f))
        }
        damageEachRe.matchEntire(s)?.let { m ->
            when (m.groupValues[2].lowercase().trim()) { "opponent" -> return Effect.DamagePlayer(Who.EACH_OPPONENT, m.groupValues[1].toInt()); "player" -> return Effect.DamagePlayer(Who.EACH_PLAYER, m.groupValues[1].toInt()) }
            val f = parseFilter(m.groupValues[2], Kind.CREATURE); if (f.verifiable) return Effect.ForAll(f, "damage", m.groupValues[1].toInt())
        }
        // "sacrifice it" / "its controller sacrifices it" in a trigger on the permanent itself.
        if (Regex("""^(?:its controller sacrifices|sacrifice) (?:~|it|this creature|this permanent)\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.SacrificeSource
        Regex("""^(?:that source's controller|that player|that creature's controller) sacrifices that many (permanents?|creatures?|lands?|artifacts?)(?: of (?:their|his or her) choice)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            return Effect.SacrificeThatMany(Who.THAT_PLAYER, parseFilter(m.groupValues[1].removeSuffix("s"), Kind.PERMANENT))
        }
        Regex("""^~ deals damage equal to the sacrificed (?:creature|permanent)'s power to (.+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.Damage(0, target(m.groupValues[1]), sacrificedPower = true) }
        Regex("""^put (?:a|an) ((?:[A-Z][a-z]+, )*(?:[A-Z][a-z]+,? or [A-Z][a-z]+ )?(?:creature|land|artifact|permanent|enchantment|Equipment|Aura|Vehicle|Fortification)) card(?: with mana value equal to the number of (charge|\w+) counters on ~)? from your hand onto the battlefield( tapped)?( and attacking(?: that opponent| that player)?)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            return Effect.PutFromHand(parseFilter(m.groupValues[1], Kind.PERMANENT), m.groupValues[2].ifEmpty { null }, tapped = m.groupValues[3].isNotEmpty(), attacking = m.groupValues[4].isNotEmpty())
        }
        zurRe.matchEntire(s)?.let { m -> zurEffect(m)?.let { return it } }
        // Sun Titan: "return target permanent card with mana value 3 or less from your graveyard to the battlefield"
        Regex("""^return target (.+?) card(?: with mana value (\d+) or less)? from your graveyard to the battlefield(?: tapped)?(?: under your control)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val f = parseFilter(m.groupValues[1], Kind.PERMANENT)
            if (f.verifiable) return Effect.PutFromHand(f, null, tapped = s.contains("battlefield tapped", true), fromGraveyard = true, maxMv = m.groupValues[2].toIntOrNull())
        }
        Regex("""^you gain (\d+) life for each spell you've cast this turn\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { return Effect.GainLifePerSpellThisTurn(Who.YOU, it.groupValues[1].toInt()) }
        // "Target player discards X cards at random" / "each player discards two cards": modeled, so it goes before the narrated table.
        Regex("""^(you|target player|target opponent|each player|each opponent|that player) discards? (a|an|\d+|X|two|three|four) cards?( at random)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val w = when (m.groupValues[1].lowercase()) { "you" -> Who.YOU; "target player", "target opponent" -> Who.TARGET_PLAYER; "each player" -> Who.EACH_PLAYER; "each opponent" -> Who.EACH_OPPONENT; else -> Who.THAT_PLAYER }
            val n = m.groupValues[2].let { if (it.equals("X", true)) 0 else number(it) ?: 1 }
            return Effect.Discard(w, n, x = m.groupValues[2].equals("X", true), random = m.groupValues[3].isNotEmpty())
        }
        // "Each opponent sacrifices a creature (with the greatest power among creatures that player controls)": modeled, so it goes before the narrated table.
        Regex("""^each (other player|opponent|player) sacrifices (?:a|an|one) (.+?)(?: of their choice)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val greatest = Regex("""^(.+?) with the greatest power among (?:creatures|permanents) (?:that player controls|they control)$""", RegexOption.IGNORE_CASE).matchEntire(m.groupValues[2])
            val f = parseFilter(greatest?.groupValues?.get(1) ?: m.groupValues[2], Kind.CREATURE)
            if (f.verifiable) return Effect.SacrificeEach(if (m.groupValues[1].lowercase() == "player") Who.EACH_PLAYER else Who.EACH_OPPONENT, f, greatestPower = greatest != null)
        }
        if (Regex("""^put ~ on top of its owner's library\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.PutSelfOnLibraryTop
        Regex("""^~ deals (\d+) damage to you\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.DamagePlayer(Who.YOU, m.groupValues[1].toInt()) }
        if (Regex("""^reveal the top card of your library and put that card into your hand\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.RevealTopToHand(Who.YOU)
        if (Regex("""^you lose life equal to its mana value\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.LoseLifeEqualToRevealedMv(Who.YOU)
        // "Create a token that's a copy of target creature you control(, except …)." (Kiki-Jiki and the 300-odd like it.)
        Regex("""^creates? (a|an|two|three|\d+) tokens? that(?:'s| are) (?:a )?cop(?:y|ies) of (.+)$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            var what = m.groupValues[2].trim().trimEnd('.')
            val except = what.split(", except ", limit = 2).getOrNull(1)
            what = what.split(", except ", limit = 2)[0].trim()
            val n = if (m.groupValues[1].lowercase() in setOf("a", "an")) 1 else number(m.groupValues[1]) ?: m.groupValues[1].toIntOrNull() ?: 1
            if (Regex("""(?i)^(?:~|this creature|this permanent|this token|it)$""").matches(what)) return Effect.CreateTokenCopy(null, n, except)
            if (what.startsWith("target ", true)) return Effect.CreateTokenCopy(target(what), n, except)
            return@let   // "a copy of the exiled card", "of that creature": not modeled, so the card stays unparsed
        }
        // "create two 2/2 black Zombie creature tokens": modeled, so it goes before the narrated table.
        createTokenRe.matchEntire(s)?.let { m ->
            val n0 = m.groupValues[2]; val desc0 = m.groupValues[3]
            val n = if (n0 == "a" || n0 == "an") 1 else number(n0) ?: n0.toIntOrNull() ?: 1
            if (Generic.token(desc0) != null) return Effect.CreateToken(who(m.groupValues[1].ifEmpty { "you" }), n, desc0)
        }
        // "create a 3/3 … token with deathtouch and a 3/3 … token with lifelink": two tokens, told as one sentence.
        Regex("""^(?:(you|its controller|that player) )?creates? (a|an) (.+? tokens?(?: with [a-z ,]+?)?) and (?:a|an) (.+? tokens?(?: with [a-z ,]+?)?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val w = who(m.groupValues[1].ifEmpty { "you" })
            val a = m.groupValues[3].trim(); val b = m.groupValues[4].trim()
            if (Generic.token(a) != null && Generic.token(b) != null) return Effect.Seq(listOf(Effect.CreateToken(w, 1, a), Effect.CreateToken(w, 1, b)))
        }
        for ((re, rules) in narratedRes) if (re.matches(s)) return Effect.Narrated(s.trimEnd('.'), rules)
        // "You draw a card and you lose 1 life." / "Each opponent loses 1 life and you gain 1 life.": two effects joined by "and".
        Regex("""^(.+?)(?: and |, then |, and then )(you |each opponent |target player |that player |it |~ |create |draw |gain |lose |put |exile |destroy |sacrifice |tap |untap |return |scry |mill |discard )(.+)$""", RegexOption.IGNORE_CASE).matchEntire(s.trimEnd('.'))?.let { m ->
            if (!m.groupValues[1].contains(" and ", true) && !m.groupValues[1].startsWith("if ", true)) {
                val left = parseSentence(m.groupValues[1].replaceFirstChar { it.uppercase() })
                val right = parseSentence((m.groupValues[2] + m.groupValues[3]).replaceFirstChar { it.uppercase() })
                if (left !is Effect.Unparsed && right !is Effect.Unparsed) return Effect.Seq(listOf(left, right))
            }
        }
        unlessRe.matchEntire(s)?.let { m ->
            val payer = when (m.groupValues[2].lowercase()) { "you" -> Who.YOU; "an opponent" -> Who.OPPONENT; "target player" -> Who.TARGET_PLAYER; "its controller" -> Who.CONTROLLER_OF_TARGET; else -> Who.THAT_PLAYER }
            return Effect.UnlessPays(parseSentence(m.groupValues[1]), payer, m.groupValues[3].replace(Regex(""", where X is (.+)$"""), " (X = $1)"))
        }
        mayRe.matchEntire(s)?.let { m ->
            val inner = parseSentence(m.groupValues[1])
            // "You may gain 1 life": the imperative reads as "You gain 1 life".
            return Effect.May(if (inner is Effect.Unparsed) parseSentence("You " + m.groupValues[1]).let { if (it is Effect.Unparsed) inner else it } else inner)
        }
        drawRe.matchEntire(s)?.let { m ->
            val who = when (m.groupValues[1].trim().lowercase()) { "target player" -> Who.TARGET_PLAYER; "that player" -> Who.THAT_PLAYER; "each player" -> Who.EACH_PLAYER; else -> Who.YOU }
            if (m.groupValues[2].equals("X", true)) return Effect.Draw(who, 0, x = true)
            return Effect.Draw(who, number(m.groupValues[2]) ?: return Effect.Unparsed(s))
        }
        damageRe.matchEntire(s)?.let { m ->
            if (m.groupValues[1].equals("X", true)) return Effect.Damage(0, target(m.groupValues[2]), x = true)
            val n = m.groupValues[1].toIntOrNull() ?: return Effect.Unparsed(s)
            when (m.groupValues[2].lowercase().trim()) {
                "that player" -> return Effect.DamagePlayer(Who.THAT_PLAYER, n); "each opponent" -> return Effect.DamagePlayer(Who.EACH_OPPONENT, n)
                "each player" -> return Effect.DamagePlayer(Who.EACH_PLAYER, n); "you" -> return Effect.DamagePlayer(Who.YOU, n); "that player's controller", "its controller" -> return Effect.DamagePlayer(Who.CONTROLLER_OF_TARGET, n)
            }
            return Effect.Damage(n, target(m.groupValues[2]))
        }
        counterRe.matchEntire(s)?.let { return Effect.Counter(target(it.groupValues[1], Kind.SPELL)) }
        Regex("""^(all creatures|creatures your opponents control|creatures you control|all other creatures|other creatures) get -X/-X until end of turn\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val f = parseFilter(m.groupValues[1].removePrefix("all ").removePrefix("All "), Kind.CREATURE); if (f.verifiable) return Effect.PumpAll(f, 0, 0, x = true)
        }
        Regex("""^prevent all combat damage that would be dealt to and (?:dealt )?by (target .+?) this turn\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { return Effect.PreventCombatToAndBy(target(it.groupValues[1].removePrefix("target "))) }
        Regex("""^copy target (.+? spell)(?:\. you may choose new targets for the copy)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { return Effect.CopySpell(target(it.groupValues[1], Kind.SPELL), s.contains("new targets", true)) }
        destroyRe.matchEntire(s)?.let { return Effect.Destroy(target(it.groupValues[1])) }
        bounceRe.matchEntire(s)?.let { m -> return Effect.Bounce(if (m.groupValues[1] == "~") null else target(m.groupValues[1])) }
        bounceChosenRe.matchEntire(s)?.let { m ->
            val raw = m.groupValues[1].trim()
            val f = parseFilter("$raw you control", Kind.PERMANENT)
            if (f.verifiable) return Effect.BounceChosen(f, raw)
        }
        if (Regex("""^proliferate\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.Proliferate
        Regex("""^(exile|destroy|tap) all (.+?) target (player|opponent) controls\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val f = parseFilter(m.groupValues[2], Kind.PERMANENT)
            if (f.verifiable) return Effect.ForAllTargeted(target(m.groupValues[3]), f, m.groupValues[1].lowercase())
        }
        createTokenRe.matchEntire(s)?.let { m ->
            val who = when (m.groupValues[1].lowercase().trim()) { "its controller" -> Who.CONTROLLER_OF_TARGET; "that player" -> Who.THAT_PLAYER; "target player" -> Who.TARGET_PLAYER; "each opponent" -> Who.EACH_OPPONENT; "each player" -> Who.EACH_PLAYER; else -> Who.YOU }
            val n = number(m.groupValues[2]) ?: return Effect.Unparsed(s)
            val desc = m.groupValues[3].trim().let { if (it.endsWith(" token") || it.endsWith(" tokens")) it else "$it token" }
            return if (Generic.token(desc) != null) Effect.CreateToken(who, n, desc) else Effect.Unparsed(s)
        }
        Regex("""^each (other player|opponent|player) sacrifices (?:a|an|one) (.+?)(?: of their choice)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            // Crackling Doom: "a creature with the greatest power among creatures that player controls"
            val greatest = Regex("""^(.+?) with the greatest power among (?:creatures|permanents) (?:that player controls|they control)$""", RegexOption.IGNORE_CASE).matchEntire(m.groupValues[2])
            val f = parseFilter(greatest?.groupValues?.get(1) ?: m.groupValues[2], Kind.CREATURE)
            return if (f.verifiable) Effect.SacrificeEach(if (m.groupValues[1].lowercase() == "player") Who.EACH_PLAYER else Who.EACH_OPPONENT, f, greatestPower = greatest != null) else Effect.Unparsed(s)
        }
        // "That creature's controller gains life equal to its power" (Solitude) is the same as "its controller …".
        if (Regex("""^(?:its|that (?:creature|permanent)'s) controller gains life equal to its power\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.GainLifeEqualToPower(Who.CONTROLLER_OF_TARGET)
        if (Regex("""^its controller may search their library for a basic land card, put that card onto the battlefield tapped, then shuffle\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.May(Effect.Narrated("search their library for a basic land card, put it onto the battlefield tapped, then shuffle", listOf("701.23a", "701.23e")), Who.CONTROLLER_OF_TARGET)
        // "Draw three cards, then put two cards from your hand on top of your library in any order."
        Regex("""^(.+?), then (.+)$""").matchEntire(s)?.let { m ->
            val a = parseSentence(m.groupValues[1].trimEnd('.') + "."); val b = parseSentence(m.groupValues[2].replaceFirstChar { it.uppercase() })
            if (!a.hasUnparsed() && !b.hasUnparsed()) return Effect.Seq(listOf(a, b))
        }
        Regex("""^exile (target .+?), then return (?:that card|it|them|that creature) to the battlefield under (your|its owner's|their owner's) control\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.Blink(target(m.groupValues[1]), ownersControl = !m.groupValues[2].equals("your", true)) }
        exileRe.matchEntire(s)?.let { return Effect.Exile(target(it.groupValues[1])) }
        tapRe.matchEntire(s)?.let { return Effect.Tap(target(it.groupValues[1])) }
        // Threaten: "Untap target creature and gain control of it until end of turn."
        Regex("""^untap (target .+?) and gain control of it( until end of turn)?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> val t = target(m.groupValues[1]); return Effect.Seq(listOf(Effect.Untap(t), Effect.GainControl(t, m.groupValues[2].isNotEmpty()))) }
        Regex("""^gain control of (target .+?) until end of turn\. untap (?:that|it|that creature|that permanent).*?\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> val t = target(m.groupValues[1]); return Effect.Seq(listOf(Effect.GainControl(t, true), Effect.Untap(t))) }
        untapRe.matchEntire(s)?.let { return Effect.Untap(target(it.groupValues[1])) }
        pumpRe.matchEntire(s)?.let { return Effect.Pump(target(it.groupValues[1]), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        pumpGainRe.matchEntire(s)?.let { m -> keywordsIn(m.groupValues[4])?.let { kws -> return Effect.Seq(listOf(Effect.Pump(target(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toInt()), Effect.GainKeywords(target(m.groupValues[1]), kws))) } }
        gainRe.matchEntire(s)?.let { m -> keywordsIn(m.groupValues[3])?.let { kws ->
            val t = target(m.groupValues[2])
            return Effect.GainKeywords(if (m.groupValues[1].isNotEmpty()) t.copy(filter = t.filter.copy(other = true), raw = "another " + t.raw) else t, kws)
        } }
        gainSelfRe.matchEntire(s)?.let { m -> keywordsIn(m.groupValues[1])?.let { kws -> return Effect.GainKeywordsSelf(kws) } }
        gainLifeRe.matchEntire(s)?.let { return Effect.GainLife(who(it.groupValues[1]), it.groupValues[2].toInt()) }
        loseLifeRe.matchEntire(s)?.let { return Effect.LoseLife(who(it.groupValues[1]), it.groupValues[2].toIntOrNull() ?: 0, x = it.groupValues[2].equals("X", true)) }
        if (Regex("""^you gain life equal to the life lost this way\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.GainLifeLostThisWay
        if (Regex("""^if a (?:creature|permanent) dealt damage this way would die this turn, exile it instead\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.ExileIfDamagedDies
        Regex("""^change a target of (target spell or ability|target spell|target ability) to ~\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.RedirectToSelf(target(m.groupValues[1])) }
        Regex("""^(target creature you control) fights (target creature (?:you don't control|an opponent controls))\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.Fight(target(m.groupValues[1], Kind.CREATURE), target(m.groupValues[2], Kind.CREATURE)) }
        // "Target creature you control deals damage equal to its power to target creature an opponent controls"
        // and its cousins ("target Dinosaur you control", "target creature or planeswalker you don't control").
        Regex("""^(target [a-z' ]*?you control) deals damage equal to its power to (target [a-z' ]+?)\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.DealsPowerTo(target(m.groupValues[1], Kind.CREATURE), target(m.groupValues[2], Kind.CREATURE)) }
        Regex("""^put (target .+?) on the bottom of its owner's library\.?$""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m -> return Effect.PutOnBottom(target(m.groupValues[1])) }
        if (Regex("""^its controller gains life equal to its toughness\.?$""", RegexOption.IGNORE_CASE).matches(s)) return Effect.GainLifeEqualToToughness(Who.CONTROLLER_OF_TARGET)
        // An intervening "if" on the effect itself (Valakut): the condition is checked as the effect happens, and
        // the rest of the sentence is an ordinary effect. Last of all, so a template that reads the whole sentence wins.
        Regex("""^if ([^,]+), (.+)$""", RegexOption.IGNORE_CASE).matchEntire(s.trim())?.let { m ->
            val cond = parseCondition(m.groupValues[1]) ?: return@let
            val then = parseEffect(m.groupValues[2].replaceFirstChar { c -> c.uppercase() })
            if (!then.hasUnparsed()) return Effect.IfCondition(cond, then, m.groupValues[1].lowercase())
        }
        return Effect.Unparsed(s)
    }

    private fun who(s: String) = when (s.lowercase()) { "you" -> Who.YOU; "target player" -> Who.TARGET_PLAYER; "each opponent" -> Who.EACH_OPPONENT; "each player" -> Who.EACH_PLAYER; "its controller", "that creature's controller" -> Who.CONTROLLER_OF_TARGET; else -> Who.THAT_PLAYER }
    private fun number(s: String): Int? = s.toIntOrNull() ?: numberWords[s.lowercase()]

    private fun target(desc: String, defaultKind: Kind? = null): TargetSpec {
        val d = desc.trim().let { if (it.startsWith("target ", true)) it.drop(7) else it }.trim()
        return TargetSpec(parseFilter(d, defaultKind), d)
    }

    private val landTypes = setOf("plains", "island", "swamp", "mountain", "forest", "desert", "gate", "lair", "locus", "mine", "power-plant", "tower", "urza's", "sphere", "cave", "town", "cloud")
    /** Subtypes that say the card type without naming it (205.3g, 205.3h). */
    private val artifactSubtypes = setOf("equipment", "vehicle", "fortification", "clue", "food", "treasure", "blood", "powerstone", "map", "incubator", "junk", "contraption", "attraction", "gold")
    private val enchantmentSubtypes = setOf("aura", "saga", "shrine", "cartouche", "curse", "rune", "background", "class", "case", "role")

    /** "elves" -> "elf", "goblins" -> "goblin", "merfolk" -> "merfolk". */
    fun singular(w: String): String = when {
        w == "elves" -> "elf"; w == "dwarves" -> "dwarf"; w == "wolves" -> "wolf"; w == "thieves" -> "thief"
        w.endsWith("ies") -> w.dropLast(3) + "y"
        w.endsWith("sses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes") -> w.dropLast(2)
        w.endsWith("s") && !w.endsWith("ss") && w !in setOf("merfolk", "kithkin", "moonfolk", "sphinx", "gnomes", "plains", "locus", "urza's") -> w.dropLast(1)
        else -> w
    }

    private val basicLandMana = mapOf("Plains" to "{W}", "Island" to "{U}", "Swamp" to "{B}", "Mountain" to "{R}", "Forest" to "{G}")
    private val colorWords = mapOf("white" to 'W', "blue" to 'U', "black" to 'B', "red" to 'R', "green" to 'G')
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
        Regex("""\s+(you control|an opponent controls|you don't control|your opponents control|opponents control)$""").find(core)?.let { m ->
            controller = when (m.groupValues[1]) { "you control" -> Who.YOU; else -> Who.OPPONENT }
            core = core.removeRange(m.range)
        }
        val kinds = mutableSetOf<Kind>(); val notKinds = mutableSetOf<Kind>(); val unknown = mutableListOf<String>()
        val subtypes = mutableSetOf<String>(); val keywords = mutableSetOf<String>(); val notKeywords = mutableSetOf<String>(); val notSubtypes = mutableListOf<String>()
        var attacking: Boolean? = null; var tapped: Boolean? = null; var token: Boolean? = null; var legendary: Boolean? = null; var attachedToSource = false
        // "with flying" / "with reach or flying" -> keyword requirements
        var minPower: Int? = null; var maxPower: Int? = null; var maxManaValue: Int? = null; var minManaValue: Int? = null
        Regex("""\s+(?:if it has|with) mana value (\d+) or less$""").find(core)?.let { m -> maxManaValue = m.groupValues[1].toInt(); core = core.removeRange(m.range) }
        Regex("""\s+(?:if it has|with) mana value (\d+) or (?:greater|more)$""").find(core)?.let { m -> minManaValue = m.groupValues[1].toInt(); core = core.removeRange(m.range) }
        val colors = mutableSetOf<Char>(); val notColors = mutableSetOf<Char>()
        Regex("""\s+with power (\d+) or (greater|less)$""").find(core)?.let { m ->
            if (m.groupValues[2] == "greater") minPower = m.groupValues[1].toInt() else maxPower = m.groupValues[1].toInt()
            core = core.removeRange(m.range)
        }
        Regex("""\s+with ([a-z ]+)$""").find(core)?.let { m ->
            val kws = m.groupValues[1].split(Regex("""\s*,\s*|\s+or\s+|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
            if (kws.all { it in keywordList }) { keywords += kws; core = core.removeRange(m.range) }
        }
        // "creatures without flying" (Moat), "creature without flying or islandwalk".
        Regex("""\s+without ([a-z ]+)$""").find(core)?.let { m ->
            val kws = m.groupValues[1].split(Regex("""\s*,\s*|\s+or\s+|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
            if (kws.all { it in keywordList }) { notKeywords += kws; core = core.removeRange(m.range) }
        }
        // "nonflying creatures": the same thing said as one word.
        Regex("""^non-?([a-z]+) """).find(core)?.let { m ->
            if (m.groupValues[1] in keywordList) { notKeywords += m.groupValues[1]; core = core.removeRange(m.range).let { "$it" }.trim().let { if (it.isEmpty()) "creature" else it } }
        }
        val subtypesAny = Regex("""\bor\b""").containsMatchIn(core)
        // "creatures and planeswalkers you control": "and" joins two kinds the same way "or" does. Kinds are matched
        // as a union (an object is one of them), while subtypes still have to all be present.
        for (w in core.split(Regex("""[\s,]+|\bor\b|\band\b""")).map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                w in colorWords -> colors += colorWords.getValue(w)
                w.startsWith("non") && w.removePrefix("non") in colorWords -> notColors += colorWords.getValue(w.removePrefix("non"))
                w in kindWords -> kinds += kindWords.getValue(w)
                singular(w) in kindWords -> kinds += kindWords.getValue(singular(w))
                w.startsWith("non") && w.removePrefix("non") in kindWords -> notKinds += kindWords.getValue(w.removePrefix("non"))
                w.startsWith("non-") && w.removePrefix("non-") in kindWords -> notKinds += kindWords.getValue(w.removePrefix("non-"))
                w.startsWith("non-") && w.length > 4 -> notSubtypes += singular(w.removePrefix("non-"))
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
        // A subtype word alone implies creature ("Elves you control"), or land for land types ("Islands you control").
        // A bare subtype says which card type it belongs to: "Equipment" is an artifact, "Aura" an enchantment,
        // "Island" a land. Read as a creature type, "put an Equipment card onto the battlefield" found nothing.
        if (kinds.isEmpty() && subtypes.isNotEmpty()) kinds += when {
            subtypes.all { it in landTypes } -> Kind.LAND
            subtypes.all { it in setOf("instant", "sorcery") } -> Kind.SPELL
            subtypes.all { it in artifactSubtypes } -> Kind.ARTIFACT
            subtypes.all { it in enchantmentSubtypes } -> Kind.ENCHANTMENT
            // "an Aura, Equipment, or Vehicle spell" mixes card types, so the kind is whatever the caller asked for.
            subtypes.all { it in artifactSubtypes || it in enchantmentSubtypes || it in landTypes } -> defaultKind ?: Kind.PERMANENT
            else -> Kind.CREATURE
        }
        if (kinds.isEmpty() && notKinds.isNotEmpty()) kinds += defaultKind ?: Kind.PERMANENT
        if (kinds.isEmpty() && defaultKind != null) kinds += defaultKind
        if (kinds.isEmpty() && notSubtypes.isNotEmpty()) kinds += defaultKind ?: Kind.CREATURE
        return ObjFilter(kinds, notKinds, notSubtypes, controller, attacking, tapped, unknown, desc, subtypes, keywords, notKeywords, token, legendary, attachedToSource = attachedToSource, minPower = minPower, maxPower = maxPower, subtypesAny = subtypesAny && subtypes.size > 1, colors = colors, notColors = notColors, maxManaValue = maxManaValue, minManaValue = minManaValue)
    }
}
