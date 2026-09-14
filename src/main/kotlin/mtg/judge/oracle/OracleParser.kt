package mtg.judge.oracle

import mtg.judge.engine.Ability
import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.CardDef
import mtg.judge.engine.Effect
import mtg.judge.engine.Kind
import mtg.judge.engine.ObjFilter
import mtg.judge.engine.StaticAbility
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
            when {
                isKeywordLine(selfRef, keywords) -> selfRef.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { abilities += StaticAbility(it, it.substringBefore(' ').lowercase()) }
                selfRef.startsWith("When ", true) || selfRef.startsWith("Whenever ", true) || selfRef.startsWith("At ", true) -> abilities += parseTriggered(selfRef)
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
        s = s.replace(Regex("""\b[Tt]his (creature|permanent|artifact|enchantment|land|planeswalker|spell|card)\b"""), "~")
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

    private fun parseActivated(line: String): Ability {
        val colon = line.indexOf(':')
        return ActivatedAbility(line.substring(0, colon).trim(), parseEffect(line.substring(colon + 1).trim()), line)
    }

    private val triggerRe = Regex("""^(When|Whenever|At)\s+(.+?),\s+(.+)$""", RegexOption.IGNORE_CASE)

    private fun parseTriggered(line: String): Ability {
        val m = triggerRe.matchEntire(line) ?: return UnparsedAbility(line)
        val cond = m.groupValues[2].trim()
        val trigger = parseTrigger(cond)
        return TriggeredAbility(trigger, parseEffect(m.groupValues[3]), line)
    }

    private val spellCastRe = Regex("""^(an opponent|you|a player|another player|each player) casts? (a|an|your first|their first) (.+?)(?: spell)?$""", RegexOption.IGNORE_CASE)

    fun parseTrigger(cond: String): Trigger {
        val c = cond.trim().trimEnd(',')
        spellCastRe.matchEntire(c)?.let { m ->
            val who = when (m.groupValues[1].lowercase()) { "you" -> Who.YOU; "an opponent" -> Who.OPPONENT; else -> Who.ANY_PLAYER }
            val what = m.groupValues[3].trim().lowercase()
            val filter = if (what == "spell") null else parseFilter(what.removeSuffix(" spell"), Kind.SPELL)
            return Trigger.SpellCast(who, filter)
        }
        if (Regex("""^~ enters(?: the battlefield)?$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisEnters
        if (Regex("""^~ dies$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisDies
        if (Regex("""^~ leaves the battlefield$""", RegexOption.IGNORE_CASE).matches(c)) return Trigger.ThisLeavesBattlefield
        return Trigger.Unknown(c)
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
    private val gainLifeRe = Regex("""^(you|target player|that player) gains? (\d+) life\.?$""", RegexOption.IGNORE_CASE)
    private val loseLifeRe = Regex("""^(you|target player|that player|each opponent) loses? (\d+) life\.?$""", RegexOption.IGNORE_CASE)

    private fun parseSentence(s: String): Effect {
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
        var attacking: Boolean? = null; var tapped: Boolean? = null
        for (w in core.split(Regex("""[\s,]+|\bor\b""")).map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                w in kindWords -> kinds += kindWords.getValue(w)
                w.startsWith("non") && w.removePrefix("non") in kindWords -> notKinds += kindWords.getValue(w.removePrefix("non"))
                w == "activated" || w == "triggered" -> { /* ability qualifiers: both counterable the same way */ }
                w == "attacking" -> attacking = true
                w == "tapped" -> tapped = true
                w == "untapped" -> tapped = false
                w == "target" || w == "a" || w == "an" || w == "the" -> {}
                else -> unknown += w
            }
        }
        if (kinds.isEmpty() && notKinds.isNotEmpty()) kinds += defaultKind ?: Kind.PERMANENT
        if (kinds.isEmpty() && defaultKind != null) kinds += defaultKind
        return ObjFilter(kinds, notKinds, controller, attacking, tapped, unknown, desc)
    }
}
