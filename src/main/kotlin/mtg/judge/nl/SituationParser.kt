package mtg.judge.nl

import mtg.judge.carddb.Names
import mtg.judge.situation.CardRef
import mtg.judge.situation.EventSpec
import mtg.judge.situation.ObjectSpec
import mtg.judge.situation.PlayerSpec
import mtg.judge.situation.Situation
import mtg.judge.situation.TurnSpec

/**
 * Hand-written natural-language front door. Turns table talk like
 *
 *   "I have Rhystic Study out. It's my opponent's turn, they cast Rampant Growth and Stifle
 *    the trigger. What happens?"
 *
 * into a [Situation]. Card names are found by longest match against the database (never
 * invented); everything else is a small grammar of possession, casting, responding, targeting
 * and turn statements. Sentences it cannot read are returned in [Parsed.unread] so the answer
 * can say so, and the echo of what was understood lets the user correct it in one line.
 */
class SituationParser(private val names: NameIndex) {

    data class Parsed(val situation: Situation, val unread: List<String>, val notes: List<String>)

    private class Ctx {
        val objects = LinkedHashMap<String, ObjectSpec>()
        val events = mutableListOf<EventSpec>()
        val unread = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var activePlayer: String? = null
        val life = LinkedHashMap<String, Int>()
        val poison = LinkedHashMap<String, Int>()
        val handSize = LinkedHashMap<String, Int>()
        /** Named players in order of first mention: id -> display name. "me"/"opp" are added when the text uses them. */
        val players = LinkedHashMap<String, String>()
        var usesMe = false
        var usesOpp = false
        var lastNamed: String? = null              // the last named (non-"me") player who acted, for "they"/"he"/"she"
        var lastActor: String? = null
        var clauseActor: String? = null            // the actor of the clause being read (for "their X" defaults)
        val inHand = HashMap<String, MutableList<NameIndex.Entry>>()
        var nextTurn = false   // cards noted as in hand, per player ("I cast it")
        var lastVerb: String? = null               // "have" | "cast" for bare continuations ("… and Smothering Tithe")
        var lastOwner: String = "me"
        var lastMentioned: String? = null          // object id, or "cast:<slug>" for a spell just cast
        val castCards = mutableListOf<String>()     // display names of cards cast so far
        var explicitResolve = false

        /** The single other player, when there are exactly two; with more, callers fall back to the engine's clarification. */
        fun other(p: String?): String? {
            val ids = playerIds()
            if (p == null) return null
            if (ids.size == 2) return ids.firstOrNull { it != p }
            if (ids.size < 2) return when (p) { "me" -> "opp"; "opp" -> "me"; else -> null }
            return null
        }

        fun playerIds(): List<String> {
            val ids = mutableListOf<String>()
            if (usesMe || players.isEmpty()) ids += "me"
            ids += players.keys
            if (usesOpp || players.isEmpty()) ids += "opp"
            return ids
        }

        fun note(id: String) { if (id == "me") usesMe = true else if (id == "opp") usesOpp = true }
    }

    /** A sentence with card names replaced by placeholders C1, C2… */
    private class Marked(val text: String, val cards: Map<String, NameIndex.Entry>, val players: Map<String, String> = emptyMap())

    /** For debugging: the sentences with card names replaced by placeholders. */
    fun debugMark(text: String): List<String> = splitSentences(text).let { ss -> val short = shortNames(ss); val named = playerNames(ss); ss.map { s -> val m = mark(s, short, named); m.text + "   " + m.cards.map { (k, v) -> "$k=${v.display}" } + (if (m.players.isEmpty()) "" else "   players=" + m.players.values) + "   clauses=" + m.text.split(clauseSplit).map { it.trim() } } }
    private val clauseSplit = Regex("""\s*(?:,|\band\b)\s+""")

    fun parse(text: String): Parsed {
        val ctx = Ctx()
        val sentences = splitSentences(text)
        val short = shortNames(sentences)
        val named = playerNames(sentences)
        for (sentence in sentences) {
            val marked = mark(sentence, short, named)
            if (!readSentence(marked, ctx)) ctx.unread += sentence.trim()
        }
        if (ctx.events.isNotEmpty() && !ctx.explicitResolve) ctx.events += EventSpec("resolveAll")
        // Every player that took part; "me" and "opponent" only when the text spoke of them (or named nobody).
        for (e in ctx.events) { e.player?.let { ctx.note(it) }; e.targets.forEach { if (it == "me" || it == "opp") ctx.note(it) } }
        for (o in ctx.objects.values) ctx.note(o.controller)
        val players = ctx.playerIds().map { id -> PlayerSpec(id, when (id) { "me" -> "me"; "opp" -> "opponent"; else -> ctx.players[id] ?: id }, ctx.life[id], ctx.poison[id], ctx.handSize[id]) }
        if (ctx.players.isNotEmpty() && ctx.usesOpp && ctx.players.size >= 2) ctx.notes += "\"opponent\"/\"they\" was read as a separate player from ${ctx.players.values.joinToString(" and ")}; name the player instead if that's wrong."
        return Parsed(Situation(players, TurnSpec(ctx.activePlayer), ctx.objects.values.toList(), emptyList(), ctx.events), ctx.unread, ctx.notes)
    }

    // ---- sentence handling -----------------------------------------------------------------

    private val sentenceSplit = Regex("""(?<=[.!?;])\s+|\n+|\s+(?:and then|, then|then)\s+|,\s+and\s+(?=(?:i|my|the|they|he|she|opponent|opp)\b)""", RegexOption.IGNORE_CASE)

    private fun splitSentences(text: String): List<String> =
        text.split(sentenceSplit).map { it.trim().trimEnd('.', '!', '?', ';', ',') }.filter { it.isNotEmpty() }

    /**
     * Once a card has been named in full, people shorten it: "Jace" for Jace Beleren, "the Giant" for Hill Giant.
     * Collect the distinctive words of every fully named card so later sentences can use them, as long as the
     * word points at exactly one of the mentioned cards and isn't a card name (or grammar word) in its own right.
     */
    private fun shortNames(sentences: List<String>): Map<String, NameIndex.Entry> {
        val candidates = LinkedHashMap<String, MutableSet<NameIndex.Entry>>()
        for (sentence in sentences) {
            val words = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { w -> Names.normalize(stripPossessive(w)).ifEmpty { "_" } }
            for (f in names.findAll(words)) {
                val display = f.entry.display
                val head = display.substringBefore(",")   // "Jace, the Mind Sculptor" -> "Jace"
                val nameWords = Names.normalize(display).split(' ').filter { it.isNotEmpty() }
                val keys = LinkedHashSet<String>()
                if (head != display) keys += Names.normalize(head)
                if (nameWords.size > 1) { keys += nameWords.first(); keys += nameWords.last() }
                for (k in keys) if (k.length >= 3 && k !in shortNameStop) candidates.getOrPut(k) { LinkedHashSet() } += f.entry
            }
        }
        return candidates.filterValues { it.size == 1 }.mapValues { it.value.first() }
    }

    private val shortNameStop = setOf("the", "and", "with", "from", "into", "onto", "for", "that", "this", "your", "you", "all", "each", "any", "one", "two", "three", "of",
        "lord", "king", "queen", "knight", "wizard", "elf", "goblin", "dragon", "angel", "demon", "spirit", "soldier", "warrior", "beast", "bird", "cat", "dog", "rat", "wolf", "bear", "sphinx", "giant", "elemental",
        "study", "ring", "bolt", "counter", "spell", "creature", "land", "token", "card", "cards", "life", "turn", "step", "phase", "combat", "damage", "mana", "ability", "trigger", "top", "bottom", "first", "second", "last", "next")

    /**
     * Named players: a capitalised word that isn't (part of) a card name and that does something a player does
     * ("Alice casts…", "Bob's Bears", "attacks Carol with…"). Returns normalized word -> display name.
     */
    private fun playerNames(sentences: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (sentence in sentences) {
            val rawWords = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val normWords = rawWords.map { w -> Names.normalize(stripPossessive(w)).ifEmpty { "_" } }
            val covered = names.findAll(normWords).flatMap { it.start until it.end }.toSet()
            for ((i, raw) in rawWords.withIndex()) {
                if (i in covered) continue
                val core = raw.trimEnd(',', '.', ';', '!', '?').removeSuffix("'s").removeSuffix("\u2019s")
                if (!Regex("""[A-Z][a-z]{1,}""").matches(core)) continue
                val norm = normWords[i]
                if (norm in playerNameStop || out.containsKey(norm)) continue
                val possessive = raw.contains("'s") || raw.contains("\u2019s")
                val next = normWords.getOrNull(i + 1) ?: ""
                val prev = normWords.getOrNull(i - 1) ?: ""
                val actsLikePlayer = possessive || next in playerVerbs || prev in playerPreps
                if (actsLikePlayer) out[norm] = core
            }
        }
        return out
    }

    private val playerNameStop = setOf("i", "my", "me", "we", "opponent", "opp", "they", "he", "she", "it", "the", "then", "now", "so", "if", "when", "what", "does", "do", "can", "is", "are", "at", "during", "on", "in", "after", "before", "also",
        "both", "each", "everyone", "nobody", "no", "yes", "player", "someone", "another", "his", "her", "their", "its", "next", "later", "finally", "meanwhile", "who", "whose", "which", "how", "why", "there", "here", "this", "that", "these", "those",
        "one", "two", "three", "four", "first", "second", "third", "last", "turn", "upkeep", "combat", "stack", "response", "target", "counter", "damage", "life", "commander", "planeswalker", "creature", "spell", "ability", "trigger", "token", "land")
    private val playerVerbs = setOf("casts", "cast", "plays", "played", "attacks", "attacked", "blocks", "blocked", "has", "have", "had", "controls", "control", "activates", "activated", "responds", "responded", "taps", "sacrifices",
        "is", "are", "was", "swings", "targets", "counters", "draws", "pays", "declines", "passes", "says", "wants", "does", "gets", "takes", "loses", "gains", "dies", "wins", "uses", "equips", "flashes", "resolves", "fires", "slams", "runs")
    private val playerPreps = setOf("at", "targeting", "target", "to", "attacks", "attack", "attacking", "and", "hits", "hit", "with", "against", "on", "of", "then", "meanwhile")

    private fun mark(sentence: String, short: Map<String, NameIndex.Entry> = emptyMap(), named: Map<String, String> = emptyMap()): Marked {
        val rawWords = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Normalize per word so word indices line up with the original words.
        val normWords = rawWords.map { w -> Names.normalize(stripPossessive(w)).ifEmpty { "_" } }
        // Names that themselves contain "'s" ("Titania's Boon", "Sensei's Divining Top") are matched with the possessive kept…
        val normKept = rawWords.map { w -> Names.normalize(w.trimEnd(',', '.', ';', '!', '?')).ifEmpty { "_" } }
        val kept = names.findAll(normKept).filter { f -> (f.start until f.end).any { normKept[it] != normWords[it] } }
        val keptCovered = kept.flatMap { it.start until it.end }.toSet()
        // …then the rest with it stripped. A single word that is both a card name and the short form of a card named in full ("Jace" the token vs. Jace Beleren) means the named card.
        val full = kept + names.findAll(normWords).filter { f -> (f.start until f.end).none { it in keptCovered } }.map { f -> if (f.end - f.start == 1) short[normWords[f.start]]?.let { f.copy(entry = it) } ?: f else f }
        val covered = full.flatMap { it.start until it.end }.toSet()
        val found = (full + normWords.indices.filter { it !in covered }.mapNotNull { i -> short[normWords[i]]?.let { NameIndex.Found(i, i + 1, it) } }).sortedBy { it.start }
        val keptSpans = kept.map { it.start to it.end }.toSet()
        val cards = LinkedHashMap<String, NameIndex.Entry>()
        val players = LinkedHashMap<String, String>()
        val out = StringBuilder()
        var i = 0
        var n = 1
        while (i < rawWords.size) {
            val f = found.firstOrNull { it.start == i }
            if (f != null) {
                val ph = "c${n++}"
                cards[ph] = f.entry
                val lastRaw = rawWords[f.end - 1]
                val trailing = lastRaw.takeLastWhile { it in ",.;!?" }
                val core = lastRaw.dropLast(trailing.length)
                val possessive = (core.endsWith("'s") || core.endsWith("\u2019s")) && (f.start to f.end) !in keptSpans
                out.append(' ').append(ph).append(if (possessive) "'s" else "").append(trailing)
                i = f.end
            } else if (named.containsKey(normWords[i])) {
                val raw = rawWords[i]
                val trailing = raw.takeLastWhile { it in ",.;!?" }
                val core = raw.dropLast(trailing.length)
                val possessive = core.endsWith("'s") || core.endsWith("\u2019s")
                out.append(" @").append(normWords[i]).append(if (possessive) "'s" else "").append(trailing)
                players[normWords[i]] = named.getValue(normWords[i]); i++
            } else {
                out.append(' ').append(rawWords[i]); i++
            }
        }
        return Marked(out.toString().trim().lowercase().replace('’', '\''), cards, players)
    }

    private fun stripPossessive(w: String): String {
        val t = w.trimEnd(',', '.', ';', '!', '?')
        return t.removeSuffix("'s").removeSuffix("’s")
    }

    private val actorMe = Regex("""^(i|me|my|i've|i'm|we)\b(?!'s)""")
    private val actorOpp = Regex("""^(my opponent|the opponent|opponent|opp|they|their|he|she|his|her|them)\b(?!'s)""")

    private fun actorOfClause(c: String): String? = when { actorOpp.containsMatchIn(c) -> "opp"; actorMe.containsMatchIn(c) -> "me"; Regex("""^@(\w+)""").containsMatchIn(c) -> Regex("""^@(\w+)""").find(c)!!.groupValues[1]; else -> null }

    /**
     * Who "they"/"opponent" means. With no named players it's the one opponent. With named players, "they/he/she"
     * is the last named player who acted, and "opponent" is the single named player other than me, if there is one.
     */
    private fun pronounPlayer(ctx: Ctx, word: String = "opponent"): String {
        if (ctx.players.isEmpty()) { ctx.usesOpp = true; return "opp" }
        val pronoun = word in setOf("they", "their", "he", "she", "his", "her", "them")
        if (pronoun) ctx.lastNamed?.let { return it }
        if (ctx.players.size == 1) return ctx.players.keys.first()
        ctx.usesOpp = true; return "opp"
    }

    private fun readSentence(m: Marked, ctx: Ctx): Boolean {
        val t0 = m.text.replace(Regex("""\s+"""), " ").trim()
        if (t0.isEmpty()) return true
        var any = false

        // Turn / life statements (removed from the text once read).
        var t2 = t0
        Regex("""\b(it's|it is|during|on|in) (my|their|the opponent's|opponent's|my opponent's|@\w+'s) (?:turn|upkeep|end step|main phase|combat)\b""").find(t2)?.let { r ->
            ctx.activePlayer = when { r.groupValues[2] == "my" -> "me"; r.groupValues[2].startsWith("@") -> r.groupValues[2].removePrefix("@").removeSuffix("'s"); else -> "opp" }
            ctx.note(ctx.activePlayer!!); any = true; t2 = t2.removeRange(r.range)
        }
        Regex("""\b(i'm|i am|i'm at|my life is|i have|i've got) (\d+) life\b""").find(t2)?.let { ctx.life["me"] = it.groupValues[2].toInt(); ctx.usesMe = true; any = true; t2 = t2.removeRange(it.range) }
        Regex("""\b(opponent|they|they're|opp|my opponent)(?: who)? (?:(?:is at|are at|at|is on|are on|'re at|'s at) (\d+)(?: life)?|(?:has|have) (\d+) life)\b""").find(t2)?.let { ctx.life[pronounPlayer(ctx)] = (it.groupValues[2].ifEmpty { it.groupValues[3] }).toInt(); any = true; t2 = t2.replaceRange(it.range, it.groupValues[1]) }
        Regex("""@(\w+) (?:is at|is on|has|at|sits at|is) (\d+)(?: life)?\b""").findAll(t2).toList().asReversed().forEach { ctx.life[it.groupValues[1]] = it.groupValues[2].toInt(); ctx.players.putIfAbsent(it.groupValues[1], m.players[it.groupValues[1]] ?: it.groupValues[1]); any = true; t2 = t2.removeRange(it.range) }
        t2 = t2.replace(Regex("""^\s*(?:what happens|what triggers|what do i do|what's the outcome)\s+(?=(?:at|during|on|in|when)\b)""", RegexOption.IGNORE_CASE), "")
        // "has only one untapped creature, Grizzly Bears, and …": the appositive name belongs to the noun before the comma.
        t2 = t2.replace(Regex("""\b(creature|blocker|attacker|permanent|artifact|enchantment|land|thing|card), (c\d+),?(?= and | which | that |$)"""), "$1 $2")
        val t = t2.trim()

        // Pure questions carry no state; the engine answers "what happens" by default.
        if (m.cards.isEmpty() && Regex("""^(what happens|what now|who wins|so what|what's the result|does (it|that|this) (resolve|work|happen)|do i (draw|win|lose)|can (i|they|my opponent) respond)\b.*$""").matches(t)) return true

        // Resolution statements.
        if (Regex("""\b(everything resolves|let (it|them|everything|that) resolve|(it|they|both|all) resolves?|resolves? (it|everything|the stack)|nobody responds|no (one|body) responds|no responses?|no further responses?)\b""").containsMatchIn(t)) {
            ctx.events += EventSpec("resolveAll"); ctx.explicitResolve = true; any = true
        }

        // "Opponent casts Settle the Wreckage after I attack with Kaalia and Serra Angel": the "after" part, with all its clauses, happened first.
        Regex("""^(.+?)\s+(after|while|when|once) ((?:i|my|they|their|the opponent|my opponent|opponent|@\w+)\b.*)$""").find(t)?.let { r ->
            if (Regex("""\b(?:attack|attacks|cast|casts|play|plays|activate|activates|block|blocks|control|controls|have|has)\b""").containsMatchIn(r.groupValues[3]) && r.groupValues[3].split(clauseSplit).size > 1) {
                var anySub = false
                for (clause in r.groupValues[3].split(clauseSplit).map { it.trim() }.filter { it.isNotEmpty() }) if (readClause(clause, m, ctx)) anySub = true
                for (clause in r.groupValues[1].split(clauseSplit).map { it.trim() }.filter { it.isNotEmpty() }) if (readClause(clause, m, ctx)) any = true else if (!isNoise(clause)) ctx.unread += restore(clause, m)
                return any || anySub
            }
        }
        // Clause-by-clause for actions.
        val clauses = t.split(clauseSplit).map { it.trim() }.filter { it.isNotEmpty() }
        val unreadClauses = mutableListOf<String>()
        for (clause in clauses) {
            if (readClause(clause, m, ctx)) any = true else unreadClauses += clause
        }
        if (any && unreadClauses.isNotEmpty()) {
            // Partially understood: report the leftover clauses with card names restored.
            for (u in unreadClauses) if (!isNoise(u)) ctx.unread += restore(u, m)
        }
        return any
    }

    private fun isNoise(clause: String) = Regex("""^(what happens|what now|so|then|now|ok|okay|right|do i draw|does it work|is that right|correct|and|but|also|too|as well|no wait|wait|never mind|nevermind|sorry|hmm|uh|um|actually)\??$""").matches(clause.trim()) ||
        (!Regex("""c\d+""").containsMatchIn(clause) && Regex("""^(?:do|does|did|can|could|will|would|is|are|was|were|what|who|which|how|should|when|why|am)\b""").matches(clause.trim().substringBefore(' ')))
    private fun restore(text: String, m: Marked): String = m.cards.entries.fold(text) { acc, (ph, e) -> acc.replace(Regex("\\b$ph\\b"), e.display) }

    private val castVerbs = """(?:casts?|casting|plays?|playing|fires? off|slams?|kicks?|kicked)"""
    private val respondVerbs = """(?:respond(?:s|ed)? with|in response(?: i| they)? (?:casts?|plays?)|responds?|answers? with|counters? (?:it|that) with|flash(?:es)? in)"""
    private val activateVerbs = """(?:activates?|activating|uses?)"""

    private fun readClause(clauseIn: String, m: Marked, ctx: Ctx): Boolean {
        // Step beginnings keep their possessive: "my upkeep begins", "at the beginning of their end step".
        Regex("""^(?:at the beginning of |at the start of |during |on |at |it's |it is |we are in |we're in |in )?(my|their|the opponent's|opponent's|my opponent's|each|the|@\w+'s) (upkeep|draw step|end step|end of turn|precombat main phase|main phase|combat|beginning of combat|cleanup step|cleanup)(?: begins| starts| now)?$""").find(clauseIn)?.let { r ->
            val who = when (r.groupValues[1]) { "my" -> "me"; "each", "the" -> ctx.activePlayer ?: "me"; else -> if (r.groupValues[1].startsWith("@")) r.groupValues[1].removePrefix("@").removeSuffix("'s") else "opp" }
            val step = when (r.groupValues[2]) { "upkeep" -> "upkeep"; "draw step" -> "draw"; "end step", "end of turn" -> "end"; "combat", "beginning of combat" -> "combat"; "cleanup step", "cleanup" -> "cleanup"; else -> "precombat_main" }
            ctx.activePlayer = who
            ctx.events += EventSpec("step", player = who, to = step); return true
        }
        // "… next turn" / "on my next turn": a turn has passed, so nothing is summoning sick any more.
        Regex("""\s+(?:next turn|on my next turn|the next turn|the turn after|a turn later|on their next turn)$""").find(clauseIn)?.let { r ->
            ctx.objects.keys.toList().forEach { id -> ctx.objects[id] = ctx.objects.getValue(id).copy(summoningSick = false) }
            ctx.notes += "A turn has passed: nothing is summoning sick any more, and until-end-of-turn effects from earlier are over."
            if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
            val who = actorOfClause(clauseIn) ?: ctx.lastActor ?: "me"
            ctx.events += EventSpec("step", player = who, to = "cleanup"); ctx.events += EventSpec("step", player = who, to = "untap")
            ctx.nextTurn = true
        }
        // Trailing "with Guttersnipe out" / "with Rhystic Study on the battlefield": a permanent of the actor's; "with no blockers" says nothing.
        var clause0 = clauseIn.replace(Regex("""\s+(?:next turn|on my next turn|the next turn|the turn after|a turn later|on their next turn)$"""), "")
        Regex("""\s+with (?:an? |the |my |their |@(\w+)'s )?(c\d+) (?:out|in play|on the battlefield|on board|on the field)$""").find(clause0)?.let { r ->
            val owner = r.groupValues[1].ifEmpty { actorOfClause(clause0) ?: ctx.lastActor ?: "me" }
            addObject(m.cards.getValue(r.groupValues[2]), owner, false, ctx); clause0 = clause0.removeRange(r.range)
        }
        clause0 = clause0.replace(Regex("""\s+with no (?:blockers|blocks|responses?|creatures)$"""), "").replace(Regex("""\s+(?:and|with) (?:no|nothing) (?:else|on board|in play)$"""), "")
            .replace(Regex("""\s+(?:unblocked|and (?:it's|it is|they're|they are) (?:not|un)blocked|and (?:nobody|no one) blocks)$"""), "")
        // "… during their beginning of combat" / "… on my end step": that step begins first.
        Regex("""\s+(?:during|on|at|in) (my|their|the opponent's|opponent's|my opponent's|@\w+'s) (upkeep|draw step|end step|end of turn|precombat main phase|main phase|combat|beginning of combat|declare attackers step|declare blockers step|cleanup step)$""").find(clause0)?.let { r ->
            val who = when (r.groupValues[1]) { "my" -> "me"; else -> if (r.groupValues[1].startsWith("@")) r.groupValues[1].removePrefix("@").removeSuffix("'s") else pronounPlayer(ctx, "their") }
            val step = when (r.groupValues[2]) { "upkeep" -> "upkeep"; "draw step" -> "draw"; "end step", "end of turn" -> "end"; "combat", "beginning of combat" -> "combat"; "declare attackers step" -> "declare_attackers"; "declare blockers step" -> "declare_blockers"; "cleanup step" -> "cleanup"; else -> "precombat_main" }
            ctx.activePlayer = who; ctx.events += EventSpec("step", player = who, to = step); clause0 = clause0.removeRange(r.range)
        }
        // "… while I control X" / "… when they have Y out": the state part is read first, then the action.
        Regex("""^(.+?)\s+after ((?:i|my|they|their|the opponent|my opponent|opponent|@\w+)\b.*)$""").find(clause0)?.let { r ->
            if (Regex("""\b(?:attack|attacks|cast|casts|play|plays|activate|activates|block|blocks|declare|declares)\b""").containsMatchIn(r.groupValues[2])) {
                val first = readClause(r.groupValues[2], m, ctx)
                return readClause(r.groupValues[1], m, ctx) || first
            }
        }
        Regex("""^(.+?)\s+(?:while|when|although|even though|given that) ((?:i|my|they|their|the opponent|my opponent|opponent|it|it's|@\w+)\b.*)$""").find(clause0)?.let { r ->
            if (Regex("""\b(?:control|controls|have|has|got|is|are|out|in play|attacking|blocking)\b""").containsMatchIn(r.groupValues[2])) {
                // A state clause naming a card comes first (it sets up the board); one about "it" refers to the action's target, so it comes second.
                return if (Regex("""c\d+""").containsMatchIn(r.groupValues[2])) { val stateRead = readClause(r.groupValues[2], m, ctx); readClause(r.groupValues[1], m, ctx) || stateRead }
                else { val main = readClause(r.groupValues[1], m, ctx); readClause(r.groupValues[2], m, ctx) || main }
            }
        }
        // "Can my opponent respond …?" / "Could I block with …?": the question is read as the action.
        Regex("""^(?:can|could|may|is it legal for|am i allowed to|are they allowed to|do|does|will|would) ((?:i|my opponent|the opponent|opponent|they|he|she|we|@\w+)\b.*)$""").find(clause0)?.let { r -> return readClause(r.groupValues[1], m, ctx) }
        // Leading connectives carry no information: "then my opponent…", "next, they…", "now I…", "so I…".
        // "Then …" / "after that …": the earlier actions have resolved before this one.
        if (Regex("""^(?:then|after that|afterwards|next|later)\b""").containsMatchIn(clause0) && ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
        var c = clause0.replace(Regex("""^(?:after (?:blockers|blocks|attackers|attacks)(?: are declared)?|before (?:combat )?damage|in the (?:declare blockers|declare attackers|end|combat damage|beginning of combat) step|during (?:combat|the combat phase)|at (?:that|this) point|with (?:that|it|the trigger|the spell) on the stack|in response|after that|afterwards|as soon as|then|next|now|so|when|if|after|once|later|finally|also|meanwhile)\s*,?\s+"""), "")
        // "… in response to them tapping Sol Ring for mana": the mana ability happens first (and can't be responded to, as the engine will say).
        Regex("""\s+in response to (?:them|my opponent|the opponent|me|@\w+) tapping (?:an? |the |their |my )?(c\d+|it)(?: for mana| for \{.*)?$""").find(c)?.let { r ->
            val ph = if (r.groupValues[1] == "it") Regex("""(?:on|targeting|at) (?:their |my |the |an? )?(c\d+)""").find(c)?.groupValues?.get(1) ?: return@let else r.groupValues[1]
            val card = m.cards.getValue(ph); val tapper = if (Regex("""in response to me""").containsMatchIn(c)) "me" else ctx.other(actorOfClause(c) ?: ctx.lastActor ?: "me") ?: "opp"
            val id = objectIdFor(card, ctx) ?: addObject(card, tapper, false, ctx)
            ctx.events += EventSpec("activate", player = tapper, obj = id, to = "mana"); ctx.notes += "A mana ability doesn't use the stack, so nothing can be done \"in response\" to it; the ${card.display} activation simply happens first (605.3b)."
            c = c.removeRange(r.range)
        }
        // "… in response to Lightning Bolt": that spell was cast first, by the other player, and is still on the stack.
        Regex("""\s+in response to (?:an? |the |their |my |my opponent's |the opponent's |opponent's |@\w+'s )?(c\d+)(?:'s)?$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[1])
            c = c.removeRange(r.range)
            if (card.display !in ctx.castCards) {
                val caster = ctx.other(actorOfClause(c) ?: ctx.lastActor ?: "me") ?: "opp"
                // What was that spell aimed at? Read the response's own target as the best guess ("Swords on my Angel in response to Bolt").
                val guess = Regex("""(?:targeting|target|on|at)\s+(?:my |their |the |an? |own |my own )*(c\d+)""").find(c)?.let { t -> m.cards[t.groupValues[1]]?.let { objectIdFor(it, ctx) ?: addObject(it, actorOfClause(c) ?: ctx.lastActor ?: "me", false, ctx) } }
                    ?: Regex("""^(?:i |they |@\w+ )?(?:sacrifices?|sacs?|regenerates?|pumps?|protects?|saves?) (?:it|itself|the c\d+|my c\d+|c\d+)""").find(c)?.let { ctx.events.lastOrNull { it.verb == "cast" }?.card?.name?.let { n -> ctx.objects.values.firstOrNull { it.card.name == n }?.id ?: slug(n) } }
                // The thing being responded to was cast after whatever this player did just before ("I cast Elder and sacrifice it in response to Bolt").
                if (ctx.events.lastOrNull()?.let { it.verb in setOf("cast", "activate") && it.player != caster } == true) ctx.events += EventSpec("resolveAll")
                ctx.events += EventSpec("cast", player = caster, card = CardRef(name = card.display, oracleId = card.oracleId), targets = listOfNotNull(guess)); ctx.castCards += card.display; ctx.note(caster)
                if (guess != null) ctx.notes += "${card.display} was read as targeting ${ctx.objects[guess]?.card?.name ?: ctx.events.lastOrNull { it.verb == "cast" && it.card != null && slug(it.card.name ?: "") == guess }?.card?.name ?: guess} (the thing the response protects); say otherwise if it targeted something else."
            }
        }
        var actor: String? = null
        // A named player: "@alice casts…", "@bob's c1 …" (the possessive is left for the possession rules below).
        Regex("""^@(\w+)(?!'s)\b""").find(c)?.let { r ->
            actor = r.groupValues[1]; ctx.players.putIfAbsent(actor!!, m.players[actor!!] ?: actor!!); ctx.lastNamed = actor
            c = c.removeRange(r.range).trim()
        }
        if (actor == null) actorOpp.find(c)?.let { actor = pronounPlayer(ctx, it.groupValues[1]); c = c.removeRange(it.range).trim() }
        if (actor == null) actorMe.find(c)?.let { actor = "me"; ctx.usesMe = true; c = c.removeRange(it.range).trim() }
        if (actor == null && c.startsWith("in response")) actor = ctx.other(ctx.lastActor)
        actor?.let { ctx.note(it) }
        ctx.clauseActor = actor
        val subject = actor ?: ctx.lastActor
        // "@bob's c1 is tapped", "@bob's c1 has 2 damage": possession by a named player.
        Regex("""^@(\w+)'s (?:(\d+|two|three|four|five) )?(c\d+)(.*)$""").find(c)?.let { r ->
            val owner = r.groupValues[1]; ctx.players.putIfAbsent(owner, m.players[owner] ?: owner); ctx.note(owner)
            val count = r.groupValues[2].let { numberWords[it] ?: it.toIntOrNull() ?: 1 }
            val card = m.cards.getValue(r.groupValues[3]); val rest = r.groupValues[4]
            if (count > 1) { repeat(count) { addObject(card, owner, false, ctx, allowDuplicate = true) } }
            else { val id = objectIdFor(card, ctx) ?: addObject(card, owner, rest.contains("tapped") && !rest.contains("untapped"), ctx); ctx.objects[id] = ctx.objects.getValue(id).copy(controller = owner); applyStateWords(id, rest, ctx); ctx.lastMentioned = id }
            ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner
            // The rest may be an action: "@bob's c1 attacks @carol" -> the object acts.
            val tail = rest.trim()
            if (tail.isNotEmpty() && !Regex("""^(?:is |are )?(?:tapped|untapped|out|in play|on the battlefield|on board)$""").matches(tail)) {
                val action = Regex("""^(attacks?|swings?|blocks?)\b(.*)$""").find(tail)
                if (action != null) return readClause("@$owner ${action.groupValues[1]} with ${r.groupValues[3]}${action.groupValues[2]}", m, ctx) || true
            }
            return true
        }

        // "… and 51 life" / "at 51 life": a life total for the actor.
        Regex("""^(?:at |with |on |am at |is at |are at )?(\d+) life$""").find(c)?.let { r -> val who = actor ?: ctx.lastActor ?: "me"; ctx.life[who] = r.groupValues[1].toInt(); ctx.note(who); return true }
        // "tries to Murder it", "attempts to cast Bolt on it": the attempt is the action.
        Regex("""^(?:tries|tried|attempts|attempted|wants|goes) to (.+)$""").find(c)?.let { r ->
            if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")   // the attempt comes after what was already happening
            return readClause((actor?.let { if (it == "me") "i " else if (it == "opp") "they " else "@$it " } ?: "") + r.groupValues[1], m, ctx)
        }
        // "activate it (targeting X)": the last-mentioned permanent's ability.
        Regex("""^(?:$activateVerbs)\s+(?:it|that|its ability|it's ability)(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return false
            ctx.events += EventSpec("activate", player = who, obj = id, targets = targetsIn(r.groupValues[1], m, ctx)); ctx.lastActor = who; return true
        }
        // Questions with no card in them carry no state ("do they get a land?"); "doesn't block" / "no blocks" / "takes it" mean no block.
        if (isNoise(c)) return true
        if (Regex("""^(?:(?:doesn't|does not|don't|do not|didn't|won't|declines? to|chooses? not to) block\b.*|no blocks?|takes? (?:it|the damage|the hit)|lets? it through)$""").matches(c)) { ctx.lastActor = actor ?: ctx.lastActor; return true }
        // "with a regeneration shield", "regenerated X", "X is regenerated": a regeneration shield on that permanent.
        Regex("""^(?:regenerates? |regenerated |gives? (?:a )?regeneration (?:shield )?to |activates? regeneration on )(?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[1]); val id = objectIdFor(card, ctx) ?: addObject(card, actor ?: ctx.lastOwner, false, ctx)
            ctx.events += EventSpec("regenerate", obj = id); return true
        }
        // "sacrifice a Bears (to Viscera Seer)": the sacrifice, then the ability it paid for.
        Regex("""^(?:sacrifices?|sacs?|sacrificing|saccing) (?:an? |the |my |one |another )?(c\d+|it|itself)(?: to (?:an? |the |my )?(c\d+)(?:'s ability)?)?(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val id = if (r.groupValues[1] == "it" || r.groupValues[1] == "itself") (ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.lastOrNull { it.verb == "cast" && it.player == who }?.card?.name?.let { slug(it) } ?: return@let)
                     else m.cards.getValue(r.groupValues[1]).let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) }
            ctx.events += EventSpec("sacrifice", player = who, obj = id)
            if (r.groupValues[2].isNotEmpty()) { val outlet = m.cards.getValue(r.groupValues[2]); val oid = objectIdFor(outlet, ctx) ?: addObject(outlet, who, false, ctx); ctx.events += EventSpec("activate", player = who, obj = oid, abilityIndex = 0, targets = targetsIn(r.groupValues[3], m, ctx)) }
            ctx.lastActor = who; return true
        }
        // Unnamed creatures: "I have three creatures", "control two other creatures" (stats unknown; assumed 1/1 and said so).
        Regex("""^(?:have|has|got|control|controls|controlling|'ve got)\s+(an? |\d+ |two |three |four |five )?(?:other |more |untapped )?creatures?(?: on the battlefield| in play| out)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val n = r.groupValues[1].trim().let { if (it.isEmpty()) 1 else number(it) ?: 1 }
            repeat(n) { i -> var id = "creature"; var k = 2; while (ctx.objects.containsKey(id)) id = "creature_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = "a creature"), controller = who); ctx.lastMentioned = id }
            ctx.notes += "$n unnamed creature${if (n > 1) "s" else ""} assumed to be vanilla 1/1s; name them for a precise answer."
            ctx.lastVerb = "have"; ctx.lastOwner = who; ctx.lastActor = who; return true
        }
        // "attack with a 4/4 Angel token with flying"
        Regex("""^(?:attacks?|attacking|swings?|swinging)(?: with)?\s+(an? |\d+ |two |three )?((?:\d+/\d+ )?(?:(?:white|blue|black|red|green|colorless) )*(?:[a-z]+ )*?(?:creature |artifact )?tokens?(?: with [a-z ,]+?)?)(?: (?:at|into) (.*))?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val n = r.groupValues[1].trim().let { if (it.isEmpty()) 1 else number(it) ?: 1 }
            val name = r.groupValues[2].trim().replace(Regex("""tokens\b"""), "token")
            repeat(n) {
                var id = slug(name); var k = 2; while (ctx.objects.containsKey(id)) id = slug(name) + "_" + (k++)
                ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = who, token = true)
                ctx.events += EventSpec("attack", player = who, obj = id, targets = targetsIn("at " + r.groupValues[3], m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") }); ctx.lastMentioned = id
            }
            ctx.lastActor = who; ctx.lastVerb = "attack"; return true
        }
        // Tokens without a card: "has a 5/5 Zombie token", "control two Treasure tokens", "a 1/1 white Soldier creature token".
        Regex("""^(?:have|has|got|control|controls|controlling|'ve got|make|makes|made|create|creates|created)\s+(an? |\d+ |two |three |four |five )?((?:\d+/\d+ )?(?:(?:white|blue|black|red|green|colorless) )*(?:[a-z]+ )*?(?:creature |artifact )?tokens?)(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val n = r.groupValues[1].trim().let { if (it.isEmpty()) 1 else number(it) ?: 1 }
            val name = r.groupValues[2].trim().removeSuffix("s").let { if (it.endsWith(" token")) it else "$it token" }
            repeat(n) { i ->
                var id = slug(name); var k = 2; while (ctx.objects.containsKey(id)) id = slug(name) + "_" + (k++)
                ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = who, token = true, tapped = r.groupValues[3].contains("tapped") && !r.groupValues[3].contains("untapped"))
                ctx.lastMentioned = id
            }
            ctx.lastVerb = "have"; ctx.lastOwner = who; ctx.lastActor = who; return true
        }
        // "cast a second Sheoldred while I already control one": the first is on the battlefield, the second is cast.
        Regex("""^(?:$castVerbs) (?:a second|another|a 2nd) (c\d+)(?: while .*| when .*| with .*)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            addObject(card, who, false, ctx); emitCast(who, card, "", m, ctx); return true
        }
        // "tap Llanowar Elves for mana", "tap Sol Ring for {C}{C}"
        Regex("""^taps? (?:an? |the |my )?(c\d+) for (?:mana|\{.*|[a-z]+ mana|[a-z]+)(?: in response(?: to (?:it|that))?)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1]); val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("activate", player = who, obj = id, to = "mana"); ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        // "draws a card", "draws two cards (during their draw step)"
        Regex("""^(?:draws?|drew|drawing) (a|an|\d+|two|three|four|five) cards?(?: (?:during|in|for|at) (?:their|my|the|his|her) draw step)?(?: (?:from|off|with|thanks to) (?:an? |the |my |their )?(?:c\d+|[a-z ]+))?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            if (c.contains("draw step")) { ctx.activePlayer = who; ctx.events += EventSpec("step", player = who, to = "draw") }
            ctx.events += EventSpec("draw", player = who, amount = number(r.groupValues[1]) ?: 1); ctx.lastActor = who; return true
        }
        // "casts three spells", "casts a creature spell", "plays two more instants"
        Regex("""^(?:$castVerbs)\s+(a|an|another|\d+|two|three|four|five)(?: more| other)? (spells?|instants?|sorcer(?:y|ies)|creature spells?|creatures?|noncreature spells?|artifacts?|enchantments?)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val n = number(r.groupValues[1]) ?: 1
            val kind = r.groupValues[2].removeSuffix("s").replace("sorceries", "sorcery").let { if (it == "creature" || it == "artifact" || it == "enchantment") "$it spell" else it }
            repeat(n) { ctx.events += EventSpec("cast", player = who, card = CardRef(name = "a $kind")) }
            ctx.lastActor = who; ctx.lastVerb = "cast"; return true
        }
        // "has 2 poison counters", "has 0 cards in hand", "with three cards in hand"
        Regex("""^(?:has|have|is at|at|with) (\d+|\w+) poison(?: counters?)?$""").find(c)?.let { r -> val who = actor ?: subject ?: "me"; ctx.poison[who] = number(r.groupValues[1]) ?: 0; ctx.note(who); return true }
        Regex("""^(?:has|have|holds?|holding|with) (\d+|\w+|no) cards? in (?:their |my |his |her )?hand$""").find(c)?.let { r -> val who = actor ?: subject ?: "me"; ctx.handSize[who] = if (r.groupValues[1] == "no") 0 else number(r.groupValues[1]) ?: 0; ctx.note(who); return true }
        // A state fragment about the last-mentioned permanent: "… and 1 damage on it", "with three +1/+1 counters", "at 4 loyalty".
        if (Regex("""^(?:with |has |having |it has |at |is at )?(?:\d+|\w+) (?:loyalty(?: counters?)?|(?:[+-]\d+/[+-]\d+|[a-z]+) counters?(?: on it)?|damage(?: marked)?(?: on it)?)$""").matches(c) || Regex("""^(?:with |has )?(?:an? )?[+-]\d+/[+-]\d+ (?:pump|bonus|boost)(?: from .*)?$""").matches(c)) {
            val id = ctx.lastMentioned?.takeIf { ctx.objects.containsKey(it) } ?: return false
            applyStateWords(id, "with " + c.replace(Regex("""^(?:with |has |having |it has |at |is at )"""), ""), ctx); return true
        }
        // Token phrase as a bare continuation: "I have Bears and five 1/1 Elf tokens" / "… and a 7/7 token".
        if (ctx.lastVerb == "have") Regex("""^(an? |\d+ |two |three |four |five |six |seven |eight |nine |ten )?((?:\d+/\d+ )?(?:(?:white|blue|black|red|green|colorless) )*(?:[a-z]+ )*?(?:creature |artifact )?tokens?(?: with [a-z ,]+?)?)$""").find(c)?.let { r ->
            val who = actor ?: ctx.lastOwner
            val n = r.groupValues[1].trim().let { if (it.isEmpty()) 1 else number(it) ?: 1 }
            val name = r.groupValues[2].trim().replace(Regex("""tokens\b"""), "token")
            repeat(n) { var id = slug(name); var k = 2; while (ctx.objects.containsKey(id)) id = slug(name) + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = who, token = true); ctx.lastMentioned = id }
            return true
        }
        // "Bears has two -1/-1 counters and one +1/+1 counter", "my Bears is tapped": a permanent with state.
        Regex("""^(?:the |an? )?(c\d+) (?:has|have|with|is|are) (.+)$""").find(c)?.let { r ->
            val rest = r.groupValues[2]
            if (Regex("""\b(?:counters?|damage|loyalty|tapped|untapped|attacking|blocking|pump|equipped|enchanted)\b""").containsMatchIn(rest)) {
                val card = m.cards.getValue(r.groupValues[1]); val who = actor ?: ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.controller ?: ctx.lastOwner
                val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
                if (rest.contains("tapped") && !rest.contains("untapped")) ctx.objects[id] = ctx.objects.getValue(id).copy(tapped = true)
                applyStateWords(id, "with $rest", ctx)
                Regex("""(?:enchanted with|equipped with|wearing|with) (?:an? |the )?(c\d+)""").findAll(rest).forEach { a -> val att = addObject(m.cards.getValue(a.groupValues[1]), who, false, ctx); ctx.objects[att] = ctx.objects.getValue(att).copy(attachedTo = id) }
                if (Regex("""\battacking\b""").containsMatchIn(rest)) ctx.events += EventSpec("attack", player = who, obj = id, targets = targetsIn(rest.substringAfter("attacking"), m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") })
                ctx.lastMentioned = id; ctx.lastOwner = who; ctx.lastVerb = "have"; return true
            }
        }
        // "my opponent's Darksteel Colossus is indestructible", "their Bears is tapped": a permanent, with a state word that may or may not matter.
        Regex("""^(?:my opponent's |the opponent's |opponent's |their |my |the |@(\w+)'s )?(c\d+) (?:is|are) (indestructible|hexproof|tapped|untapped|blocking|on the battlefield|in play|out|a token|legendary|black|white|blue|red|green|colorless|huge|big|small|already out)$""").find(c)?.let { r ->
            val head = c.substringBefore(" c").trim()
            val owner = when { r.groupValues[1].isNotEmpty() -> r.groupValues[1]; head.startsWith("my opponent") || head.startsWith("opponent") || head.startsWith("the opponent") || head == "their" -> pronounPlayer(ctx, "their"); head == "my" -> "me"; else -> actor ?: ctx.lastOwner }
            val card = m.cards.getValue(r.groupValues[2]); val id = objectIdFor(card, ctx) ?: addObject(card, owner, r.groupValues[3] == "tapped", ctx)
            if (r.groupValues[3] == "tapped") ctx.objects[id] = ctx.objects.getValue(id).copy(tapped = true)
            ctx.lastMentioned = id; ctx.lastOwner = owner; ctx.lastVerb = "have"; return true
        }
        // "has only one untapped creature, Grizzly Bears": the creature is the object.
        Regex("""^(?:has|have|controls?|got) (?:only |just )?(?:one |an? |a single )?(?:untapped |tapped )?(?:creature|blocker|permanent|thing)(?:,? (?:which is|namely|being))? (c\d+)(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "opp"
            val id = addObject(m.cards.getValue(r.groupValues[1]), who, c.contains(" tapped "), ctx)
            ctx.lastMentioned = id; ctx.lastOwner = who; ctx.lastVerb = "have"; ctx.lastActor = who; return true
        }
        // "it's attacking me", "it is attacking": the last-mentioned creature is an attacker.
        Regex("""^(?:it's|it is|it) attacking(?: (.*))?$""").find(c)?.let { r ->
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.lastOrNull { it.verb == "cast" }?.targets?.firstOrNull { it in ctx.objects } ?: return false
            val defender = targetsIn("at " + (r.groupValues[1]), m, ctx)
            val who = defender.firstOrNull()?.let { d -> if (d in ctx.playerIds() || d == "me" || d == "opp") ctx.other(d) else null } ?: ctx.objects.getValue(id).controller
            ctx.objects[id] = ctx.objects.getValue(id).copy(controller = who)
            ctx.notes.removeAll { it.startsWith(ctx.objects.getValue(id).card.name + " wasn't mentioned before") }
            ctx.events.add(0, EventSpec("attack", player = who, obj = id, targets = defender.ifEmpty { listOf(ctx.other(who) ?: "opp") })); return true
        }
        // "gains 5 life (from lifelink)", "loses 3 life"
        Regex("""^(?:gains?|gained|gaining) (\d+) life\b.*$""").find(c)?.let { r -> val who = actor ?: subject ?: "me"; ctx.events += EventSpec("gainLife", player = who, amount = r.groupValues[1].toInt()); ctx.lastActor = who; return true }
        Regex("""^(?:loses?|lost|losing) (\d+) life\b.*$""").find(c)?.let { r -> val who = actor ?: subject ?: "me"; ctx.events += EventSpec("loseLife", player = who, amount = r.groupValues[1].toInt()); ctx.lastActor = who; return true }
        // "X dies", "my opponent's X is destroyed", "X leaves the battlefield", "X gets exiled"
        Regex("""^(?:my opponent's |the opponent's |opponent's |their |my |the |@(\w+)'s )?(c\d+) (?:dies|died|is destroyed|gets destroyed|would die|is put into (?:a|the|its owner's) graveyard|goes to the graveyard|is exiled|gets exiled|leaves the battlefield|is bounced|is sacrificed|gets sacrificed)$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[2])
            val ownerWord = c.substringBefore(" c").trim()
            val owner = when { r.groupValues[1].isNotEmpty() -> r.groupValues[1]; ownerWord.startsWith("my opponent") || ownerWord.startsWith("opponent") || ownerWord.startsWith("the opponent") || ownerWord == "their" -> pronounPlayer(ctx, "their"); ownerWord == "my" -> "me"; else -> actor ?: ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.controller ?: ctx.lastOwner }
            val id = objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx)
            val to = when { c.contains("exiled") -> "exile"; c.contains("bounced") -> "hand"; else -> "graveyard" }
            if (c.contains("sacrificed")) ctx.events += EventSpec("sacrifice", player = owner, obj = id) else ctx.events += EventSpec("leave", obj = id, to = to)
            ctx.lastMentioned = id; return true
        }
        // Bare continuation: "… and Smothering Tithe" after a possession, "… and Counterspell" after a cast.
        Regex("""^(?:an? |the |my |their |another |also |(\d+|two|three|four|five) )?(c\d+)(?:'s)?(?: out| in play| on the battlefield| on board| on the field)?$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[2])
            val count = r.groupValues[1].let { numberWords[it] ?: it.toIntOrNull() ?: 1 }
            when (ctx.lastVerb) {
                "have" -> { repeat(count) { addObject(card, actor ?: ctx.lastOwner, false, ctx, allowDuplicate = count > 1) }; if (actor != null) { ctx.lastOwner = actor; ctx.lastActor = actor }; return true }
                "cast" -> { emitCast(subject ?: "opp", card, "", m, ctx); return true }
                "attack" -> { val who = ctx.lastActor ?: "me"; val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx); ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(ctx.other(who) ?: "opp")); return true }
                "block" -> { val who = ctx.lastActor ?: "opp"; val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx); val attacker = ctx.events.lastOrNull { it.verb == "attack" && who in it.targets }?.obj ?: ctx.events.lastOrNull { it.verb == "attack" }?.obj; ctx.events += EventSpec("block", player = who, obj = id, targets = listOfNotNull(attacker)); return true }
                else -> return false
            }
        }

        // Continuing an attack across a clause split: "attacks Bob with Hill Giant and Carol with Grizzly Bears".
        // The defender was stripped as the "actor" above ("@carol with c2", "me with c2"); the attacker is still the last actor.
        if (ctx.lastVerb == "attack" && actor != null && actor != ctx.lastActor) Regex("""^with\s+(?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            val who = ctx.lastActor ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(actor!!)); ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        // "Nobody pays": every player declines the next optional cost.
        if (Regex("""^(?:nobody|no one|no-one|neither of us|none of us|nobody else) (?:pays?|will pay|wants to pay)$""").matches(c)) { for (pid in ctx.playerIds()) ctx.events += EventSpec("pay", player = pid, to = "no"); return true }
        // "… on my opponent's Counterspell that targets my Bears": that spell is on the stack first, cast by them.
        Regex("""\s+(?:on|targeting|at) (?:my opponent's|the opponent's|opponent's|their) (c\d+) (?:that|which) (?:targets?|is targeting) (?:my |the |an? )?(c\d+)$""").find(c)?.let { r ->
            val spell = m.cards.getValue(r.groupValues[1]); val victim = m.cards.getValue(r.groupValues[2])
            val who = actor ?: subject ?: "me"
            if (spell.display !in ctx.castCards) { val vid = objectIdFor(victim, ctx) ?: addObject(victim, who, false, ctx); val caster = ctx.other(who) ?: "opp"; emitCast(caster, spell, "", m, ctx); ctx.events[ctx.events.lastIndex] = ctx.events.last().copy(targets = listOf(vid)); ctx.lastActor = who }
            c = c.replaceRange(r.range, " targeting ${r.groupValues[1]}")
        }
        // "My opponent counters my Grizzly Bears with Mana Leak": my spell is cast, then theirs targets it.
        Regex("""^counters? (?:my |their |the |an? |@\w+'s )?(c\d+) with (?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "opp"
            val first = m.cards.getValue(r.groupValues[1])
            if (first.display !in ctx.castCards) emitCast(ctx.other(who) ?: "me", first, "", m, ctx)
            emitCast(who, m.cards.getValue(r.groupValues[2]), " targeting ${r.groupValues[1]}", m, ctx); return true
        }
        // "recast it" / "cast it again" / "replay it": the card that just went to hand.
        Regex("""^(?:recasts?|replays?|casts? (?:it|that) again|plays? (?:it|that) again)(?: it| that)?$""").find(c)?.let {
            val who = actor ?: subject ?: "me"
            val id = ctx.events.lastOrNull { it.verb == "cast" }?.targets?.firstOrNull { it in ctx.objects } ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")   // it went to hand first
            ctx.events += EventSpec("cast", player = who, obj = id); ctx.lastActor = who; ctx.lastVerb = "cast"; return true
        }
        // "cast it" after "I have X in hand": the card noted in hand.
        Regex("""^(?:$castVerbs) (?:it|that|the card)$""").find(c)?.let {
            val who = actor ?: subject ?: "me"
            val card = ctx.inHand[who]?.lastOrNull() ?: return@let
            emitCast(who, card, "", m, ctx); return true
        }
        // "attack with Bears into their untapped Serra Angel": the defender's creature is on the battlefield; the attack is at the player.
        Regex("""^((?:attacks?|attacking|swings?|swinging)(?: with)? .+?) into (?:their |my opponent's |the opponent's |an? |the )?(?:untapped |tapped |open )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            addObject(m.cards.getValue(r.groupValues[2]), ctx.other(who) ?: "opp", c.contains(" tapped "), ctx)
            return readClause((actor?.let { if (it == "me") "i " else if (it == "opp") "they " else "@$it " } ?: "") + r.groupValues[1], m, ctx)
        }
        // "Rhystic Study triggers (for me) and I draw": the trigger is the engine's business; nothing to add.
        Regex("""^(?:the |my |their )?(c\d+) triggers?(?: (for me|for them|for my opponent))?$""").find(c)?.let { r ->
            val who = when (r.groupValues[2]) { "for me" -> "me"; "for them", "for my opponent" -> pronounPlayer(ctx, "them"); else -> actor ?: ctx.lastOwner }
            val card = m.cards.getValue(r.groupValues[1]); if (objectIdFor(card, ctx) == null) addObject(card, who, false, ctx)
            return true
        }
        if (Regex("""^draws?(?: a card| for it| off it)?$""").matches(c) && ctx.events.any { it.verb == "cast" }) return true   // the engine draws for the trigger
        // "kills the Bears with Doom Blade" / "removes X with Y": the spell is cast at the creature.
        Regex("""^(?:kills?|killed|destroys?|destroyed|exiles?|exiled|removes?|removed|answers?|deals? with) (?:an? |the |my |their |my opponent's )?(c\d+) (?:with|using|via) (?:an? |the |my )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val victim = m.cards.getValue(r.groupValues[1]); val spell = m.cards.getValue(r.groupValues[2])
            val vid = objectIdFor(victim, ctx) ?: addObject(victim, ctx.other(who) ?: "opp", false, ctx)
            emitCast(who, spell, "", m, ctx); ctx.events[ctx.events.lastIndex] = ctx.events.last().copy(targets = listOf(vid)); return true
        }
        // "can my opponent respond to my Bolt with Counterspell?" → the Bolt is cast, then the response.
        Regex("""^(?:respond(?:s|ed)? to|answers?) (?:my |their |the |an? |@\w+'s )?(c\d+) with (?:an? |the |my |their )?(c\d+)(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "opp"
            val first = m.cards.getValue(r.groupValues[1])
            if (first.display !in ctx.castCards) emitCast(ctx.other(who) ?: "me", first, "", m, ctx)
            emitCast(who, m.cards.getValue(r.groupValues[2]), " targeting ${r.groupValues[1]}", m, ctx); return true
        }
        // "my commander is Atraxa" / "Atraxa is my commander"
        Regex("""^(?:commander is|commander's) (c\d+)$|^(c\d+) is (?:my|their) commander$""").find(c)?.let { r ->
            val ph = r.groupValues[1].ifEmpty { r.groupValues[2] }; val who = actor ?: ctx.lastActor ?: "me"
            val id = objectIdFor(m.cards.getValue(ph), ctx) ?: addObject(m.cards.getValue(ph), who, false, ctx)
            ctx.objects[id] = ctx.objects.getValue(id).copy(commander = true); ctx.lastVerb = "have"; ctx.lastOwner = who; ctx.lastMentioned = id; return true
        }
        // "tap Grizzly Bears with Icy Manipulator": Icy's ability targeting the Bears.
        Regex("""^taps? (?:an? |the |my |their |down )?(c\d+) (?:with|using) (?:an? |the |my )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val target = m.cards.getValue(r.groupValues[1]); val src = m.cards.getValue(r.groupValues[2])
            val tid = objectIdFor(target, ctx) ?: addObject(target, ctx.other(who) ?: "opp", false, ctx); val sid = objectIdFor(src, ctx) ?: addObject(src, who, false, ctx)
            ctx.events += EventSpec("activate", player = who, obj = sid, targets = listOf(tid)); ctx.lastActor = who; ctx.lastMentioned = tid; return true
        }
        // Paying, or not, for a tax: "pays", "pays the 1", "doesn't pay", "declines to pay (for Rhystic)".
        Regex("""^(?:(does not|doesn't|don't|do not|won't|will not|didn't|declines? to|refuses? to|isn't going to) )?(?:pays?|paid|paying)\b.*$""").find(c)?.let { r ->
            val who = actor ?: subject ?: ctx.events.lastOrNull { it.verb == "cast" }?.player ?: "me"
            val pay = EventSpec("pay", player = who, to = if (r.groupValues[1].isEmpty()) "yes" else "no")
            // Paying (or not) for an attack tax decides whether the attack happens: it goes before the attack it belongs to.
            val lastAttack = ctx.events.indexOfLast { (it.verb == "attack" || it.verb == "attackAll") && it.player == who }
            if (lastAttack >= 0 && ctx.events.drop(lastAttack + 1).all { it.verb == "attack" || it.verb == "attackAll" }) ctx.events.add(lastAttack, pay) else ctx.events += pay
            ctx.lastActor = who; return true
        }
        // Possession: "have X (on the battlefield|out|in play)", "control X", "X is on the battlefield".
        Regex("""^(?:have|has|got|control|controls|controlling|'ve got|am playing|is playing|are playing|run|running)\s+(?:an? |the |my |their |(\d+|two|three|four|five) )?(?:(commander )|my commander )?(c\d+)(?:'s)?(.*)$""").find(c)?.let { r0 ->
            val isCommander = r0.groupValues[2].isNotEmpty() || r0.groupValues[0].contains("my commander ")
            val r = object { val groupValues = listOf(r0.groupValues[0], r0.groupValues[1], r0.groupValues[3], r0.groupValues[4]) }
            val owner = actor ?: "me"
            val count = r.groupValues[1].let { numberWords[it] ?: it.toIntOrNull() ?: 1 }
            val rest = r.groupValues[3]
            if (count > 1) { repeat(count) { addObject(m.cards.getValue(r.groupValues[2]), owner, false, ctx, allowDuplicate = true) }; ctx.lastVerb = "have"; ctx.lastOwner = owner; return true }
            Regex("""\bwith (\d+|\w+|no) cards? in (?:their |my |his |her )?hand\b""").find(rest)?.let { h -> ctx.handSize[owner] = if (h.groupValues[1] == "no") 0 else number(h.groupValues[1]) ?: 0 }
            if ((rest.contains("in hand") || rest.contains("in my hand")) && !Regex("""\bcards? in (?:their |my |his |her )?hand\b""").containsMatchIn(rest)) {
                ctx.notes += "${m.cards.getValue(r.groupValues[2]).display} noted as in hand (hidden zones are only tracked when you cast from them)."
                ctx.inHand.getOrPut(owner) { mutableListOf() } += m.cards.getValue(r.groupValues[2])
                ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner
                return true
            }
            val hostId = addObject(m.cards.getValue(r.groupValues[2]), owner, tapped = rest.contains("tapped") && !rest.contains("untapped"), ctx)
            if (isCommander) ctx.objects[hostId] = ctx.objects.getValue(hostId).copy(commander = true)
            applyStateWords(hostId, rest, ctx)
            if (Regex("""\b(?:with (?:a )?regeneration shield|regenerated|regeneration shield(?:ed)?|already regenerated)\b""").containsMatchIn(rest)) ctx.events += EventSpec("regenerate", obj = hostId)
            // "… with Rancor on it", "… enchanted with X", "… equipped with X"
            Regex("""(?:enchanted with|equipped with|wearing|with) (?:an? |the )?(c\d+)""").findAll(rest).forEach { a -> val id = addObject(m.cards.getValue(a.groupValues[1]), owner, false, ctx); ctx.objects[id] = ctx.objects.getValue(id).copy(attachedTo = hostId) }
            // Other cards in the rest are more permanents, unless they're only the source of something ("a +3/+3 pump from Giant Growth").
            Regex("""(?<!from )(?<!via )(?<!by )(c\d+)""").findAll(rest).forEach { if (ctx.objects.values.none { o -> o.card.oracleId == m.cards.getValue(it.groupValues[1]).oracleId }) addObject(m.cards.getValue(it.groupValues[1]), owner, false, ctx) }
            ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner
            return true
        }
        // "I just cast Grizzly Bears this turn" / "I played X this turn": it's on the battlefield and summoning sick.
        Regex("""^(?:just )?(?:cast|played|put) (?:an? |the )?(c\d+)(?: this turn| earlier this turn| earlier)$""").find(c)?.let { r ->
            val owner = actor ?: "me"
            val id = addObject(m.cards.getValue(r.groupValues[1]), owner, false, ctx)
            ctx.objects[id] = ctx.objects.getValue(id).copy(summoningSick = true); ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner; ctx.lastMentioned = id; return true
        }
        // "respond by activating X's ability" / "respond by casting Y"
        Regex("""^respond(?:s|ed)? by (activating|casting|playing) (.*)$""").find(c)?.let { r ->
            val who = actor ?: ctx.other(ctx.lastActor) ?: "me"
            return readClause((if (r.groupValues[1] == "activating") "activate " else "cast ") + r.groupValues[2], m, ctx).also { ctx.lastActor = who }
        }
        // "equip Bonesplitter to the Bears"
        Regex("""^equips? (?:my |the )?(c\d+) (?:to|onto) (?:my |the )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val eq = m.cards.getValue(r.groupValues[1]); val host = m.cards.getValue(r.groupValues[2])
            val eqId = objectIdFor(eq, ctx) ?: addObject(eq, who, false, ctx); val hostId = objectIdFor(host, ctx) ?: addObject(host, who, false, ctx)
            ctx.events += EventSpec("activate", player = who, obj = eqId, targets = listOf(hostId)); ctx.lastActor = who; ctx.lastMentioned = hostId; return true
        }
        // "Rancor is on my Bears", "Rancor is attached to / enchanting / equipping my Bears"
        Regex("""^(?:an? |the |my |their )?(c\d+) (?:is |are )?(?:on|attached to|enchanting|equipping|equips|enchants) (?:my |their |the |an? )?(c\d+)$""").find(c)?.let { r ->
            val hostCard = m.cards.getValue(r.groupValues[2]); val attCard = m.cards.getValue(r.groupValues[1])
            val hostId = objectIdFor(hostCard, ctx) ?: addObject(hostCard, actor ?: "me", false, ctx)
            val attId = objectIdFor(attCard, ctx) ?: addObject(attCard, actor ?: ctx.objects.getValue(hostId).controller, false, ctx)
            ctx.objects[attId] = ctx.objects.getValue(attId).copy(attachedTo = hostId); return true
        }
        Regex("""^(?:an? |the )?(c\d+) (?:is|are) (?:on the battlefield|in play|out|on board)""").find(c)?.let { r ->
            addObject(m.cards.getValue(r.groupValues[1]), actor ?: "me", clause0.contains("tapped"), ctx); return true
        }
        Regex("""^(?:an? |the )?(c\d+) (?:enters|comes in|etbs|enters the battlefield)""").find(c)?.let { r ->
            val id = addObject(m.cards.getValue(r.groupValues[1]), actor ?: subject ?: "me", false, ctx, zone = "hand")
            ctx.events += EventSpec("enter", obj = id); ctx.lastMentioned = id; return true
        }

        // Casting, possibly with targets: "casts C1 (targeting|on|at) <ref>".
        Regex("""^(?:then )?(?:$castVerbs|$respondVerbs)\s+(?:an? |the |another |their |my )?(kicked |overloaded )?(c\d+)(.*)$""").find(c)?.let { r ->
            emitCast(subject ?: "opp", m.cards.getValue(r.groupValues[2]), r.groupValues[3] + (if (r.groupValues[1].isNotEmpty()) " " + r.groupValues[1].trim() else "") + (if (c.startsWith("kick")) " kicked" else ""), m, ctx); return true
        }
        // Verbified card name right after the actor: "Stifles the trigger", "Bolt the bears".
        Regex("""^(c\d+)s?\s+(?:(?:targeting|on|at)\s+)?((?:the|my|their|that|this|it|them|me|c\d+)\b.*)$""").find(c)?.let { r ->
            emitCast(subject ?: "opp", m.cards.getValue(r.groupValues[1]), " targeting " + r.groupValues[2], m, ctx); return true
        }
        // Loyalty abilities: "activate Jace's +1", "use Jace's -3 on the Bears", "+1 Jace", "Jace -3 targeting X"
        Regex("""^(?:(?:$activateVerbs)\s+)?(?:an? |the |their |my )?(c\d+)(?:'s)?\s+([+\u2212-]?\d+)(?: ability)?(.*)$""").find(c)?.let { r ->
            val who = subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("activate", player = who, obj = id, to = r.groupValues[2].replace('\u2212', '-'), targets = targetsIn(r.groupValues[3], m, ctx)); ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        Regex("""^(?:$activateVerbs)\s+(?:an? |the |their |my )?(c\d+)(?:'s)?(?: ability)?(.*)$""").find(c)?.let { r ->
            val who = subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("activate", player = who, obj = id, targets = targetsIn(r.groupValues[2], m, ctx)); ctx.lastActor = who; return true
        }
        // Step beginnings: "at the beginning of my upkeep", "on their end step", "my upkeep starts".
        Regex("""^(?:at the beginning of |at the start of |during |on |at )?(my|their|the opponent's|opponent's|my opponent's|each|the) (upkeep|draw step|end step|end of turn|precombat main phase|main phase|combat|beginning of combat)(?: begins| starts)?$""").find(c)?.let { r ->
            val who = when (r.groupValues[1]) { "my" -> "me"; "each", "the" -> ctx.activePlayer ?: "me"; else -> "opp" }
            val step = when (r.groupValues[2]) { "upkeep" -> "upkeep"; "draw step" -> "draw"; "end step", "end of turn" -> "end"; "combat", "beginning of combat" -> "combat"; else -> "precombat_main" }
            ctx.activePlayer = who
            ctx.events += EventSpec("step", player = who, to = step); return true
        }
        // "attack with everything" / "no blocks".
        if (Regex("""^(?:attacks?|attacking|swings?|swinging)(?: with)? (?:everything|everyone|all(?: of)?(?: my)?(?: creatures)?|the team|with everything)$""").matches(c) || Regex("""^(?:attacks?|swings?|go to combat)$""").matches(c)) {
            val who = actor ?: subject ?: "me"
            ctx.events += EventSpec("attackAll", player = who, targets = listOf(ctx.other(who) ?: "opp")); ctx.lastActor = who; ctx.lastVerb = "attack"; return true
        }
        if (Regex("""^(?:have no blockers|has no blockers|don't block|doesn't block|no blocks?|can't block|won't block|take it|takes it)$""").matches(c)) return true
        // Combat: "attack with c1", "swing with c1 (at them)", "block (it) with c2".
        Regex("""^(?:attacks?|attacking|swings?|swinging) with (?:it|that|him|her|them)$""").find(c)?.let {
            val who = actor ?: subject ?: "me"
            // "it" after a spell means what that spell targeted ("I cast Act of Treason on their Giant and attack with it"); an Aura's "it" is what it enchants.
            val id = ctx.lastMentioned?.takeIf { !it.startsWith("cast:") }?.let { lm -> ctx.objects[lm]?.attachedTo ?: lm } ?: ctx.events.lastOrNull { it.verb == "cast" }?.targets?.firstOrNull { it in ctx.objects } ?: return false
            ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(ctx.other(who) ?: "opp")); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; return true
        }
        // "attack Jace with Hill Giant", "attacks their planeswalker with c2": the defender comes first.
        Regex("""^(?:attacks?|attacking|swings? at|swinging at)\s+((?:an? |the |my |their |@\w+'s )?(?:c\d+|me|them|him|her|my opponent|the opponent|opponent|@\w+))\s+with\s+(?:an? |the |my |their )?(my commander |commander )?(c\d+)(?: alone| by itself| only)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val card = m.cards.getValue(r.groupValues[3])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            if (r.groupValues[2].isNotEmpty()) ctx.objects[id] = ctx.objects.getValue(id).copy(commander = true)
            val defender = targetsIn("at " + r.groupValues[1], m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") }
            ctx.events += EventSpec("attack", player = who, obj = id, targets = defender); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; return true
        }
        Regex("""^(?:attacks?|attacking|swings?|swinging)(?: with)?\s+(\d+|two|three|four|five) creatures?(?: but .*| and .*)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "opp"
            val n = number(r.groupValues[1]) ?: 1
            val ids = (1..n).map { i -> var id = "creature"; var k = 2; while (ctx.objects.containsKey(id)) id = "creature_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = "a creature"), controller = who); id }
            ctx.notes += "$n unnamed creatures assumed to be vanilla 1/1s; name them for a precise answer."
            ids.forEach { ctx.events += EventSpec("attack", player = who, obj = it, targets = listOf(ctx.other(who) ?: "me")) }
            Regex("""\bonly has (\d+) mana\b|\bhas only (\d+) mana\b|\bwith (\d+) mana\b""").find(c)?.let { mm -> ctx.notes += "${if (who == "me") "You have" else "They have"} ${mm.groupValues.drop(1).first { it.isNotEmpty() }} mana available; the engine doesn't track mana, so whether a tax can be paid for every attacker is stated as an assumption." }
            ctx.lastActor = who; ctx.lastVerb = "attack"; return true
        }
        Regex("""^(?:attacks?|attacking|swings?|swinging)(?: with)?\s+(\d+|two|three|four|five) (c\d+)(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val n = number(r.groupValues[1]) ?: 1; val card = m.cards.getValue(r.groupValues[2])
            val existing = ctx.objects.values.filter { it.card.oracleId == card.oracleId && it.controller == who }.map { it.id }
            val ids = existing.take(n).toMutableList(); while (ids.size < n) ids += addObject(card, who, false, ctx, allowDuplicate = true)
            val defender = targetsIn(r.groupValues[3], m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") }
            ids.forEach { ctx.events += EventSpec("attack", player = who, obj = it, targets = defender) }
            ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = ids.last(); return true
        }
        Regex("""^(?:attacks?|attacking|swings?|swinging)(?: with)?\s+(?:an? |the |my |their )?(my commander |commander )?(c\d+)(?: alone| by itself)?(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val card = m.cards.getValue(r.groupValues[2])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            if (r.groupValues[1].isNotEmpty()) ctx.objects[id] = ctx.objects.getValue(id).copy(commander = true)
            val r = object { val groupValues = listOf(r.groupValues[0], r.groupValues[2], r.groupValues[3]) }
            ctx.events += EventSpec("attack", player = who, obj = id, targets = targetsIn(r.groupValues[2], m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") }); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; return true
        }
        // Bare "block" / "blocks (it)" / "block with it": the creature just mentioned blocks the last attacker.
        Regex("""^(?:chump[- ]?)?blocks?(?: it| that| the attacker| with it)?$""").find(c)?.let {
            // A creature just cast has no object yet; the judge gives it the id the engine will use (its slug).
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.lastOrNull { it.verb == "cast" }?.card?.name?.let { n -> ctx.objects.values.firstOrNull { it.card.name == n }?.id ?: slug(n) } ?: return false
            val attackerEvent = ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" } ?: return false
            val who = actor ?: ctx.objects[id]?.controller ?: ctx.events.lastOrNull { it.verb == "cast" }?.player ?: ctx.other(attackerEvent.player) ?: "me"
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOfNotNull(attackerEvent.obj)); ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        // "block one (of them) with Hill Giant": one of the attackers.
        Regex("""^(?:chump[- ]?)?(?:blocks?|blocking) (?:one|one of them|the first|the first one|a single one) with (?:an? |the |my )?(c\d+)$""").find(c)?.let { r ->
            val attacker = ctx.events.firstOrNull { it.verb == "attack" }?.obj ?: return@let
            val who = actor ?: ctx.other(ctx.events.first { it.verb == "attack" }.player) ?: "me"
            val card = m.cards.getValue(r.groupValues[1]); val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOf(attacker)); ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        // "blocks Hill Giant with Serra Angel": attacker first.
        Regex("""^(?:chump[- ]?)?(?:blocks?|blocking)\s+(?:an? |the |my |their )?(c\d+) with (?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            val attackerCard = m.cards.getValue(r.groupValues[1]); val blockerCard = m.cards.getValue(r.groupValues[2])
            val attacker = objectIdFor(attackerCard, ctx) ?: return@let
            val attackerPlayer = ctx.events.lastOrNull { it.verb == "attack" && it.obj == attacker }?.player
            val who = actor ?: attackerPlayer?.let { ctx.other(it) } ?: subject ?: "opp"
            val id = objectIdFor(blockerCard, ctx) ?: addObject(blockerCard, who, false, ctx)
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOf(attacker)); ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        Regex("""^(?:chump[- ]?)?(?:blocks?|blocking)(?: it| that| the attacker)?(?: with)?\s+(?:an? |the |my |their |a single |one |just |only )?(c\d+)(.*)$""").find(c)?.let { r ->
            val attackerPlayer = ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }?.player
            val who = actor ?: attackerPlayer?.let { ctx.other(it) } ?: subject ?: "opp"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            // The attacker being blocked: the last creature declared attacking this player, else the last attacker.
            val attacker = ctx.events.lastOrNull { it.verb == "attack" && who in it.targets }?.obj ?: ctx.events.lastOrNull { it.verb == "attack" }?.obj
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOfNotNull(attacker)); ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        // "my commander Kaalia attacks (Bob)", "the Bears attack me": the creature is the subject.
        Regex("""^(commander |my commander |the |an? |their )?(c\d+) (?:attacks?|swings?)(?: alone| by itself)?(?: (?:at |into )?(.*))?$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[2])
            val who = actor ?: ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.controller ?: subject ?: "me"
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            if (r.groupValues[1].contains("commander")) ctx.objects[id] = ctx.objects.getValue(id).copy(commander = true)
            val r = object { val groupValues = listOf(r.groupValues[0], r.groupValues[2], r.groupValues[3]) }
            ctx.events += EventSpec("attack", player = who, obj = id, targets = targetsIn("at " + r.groupValues[2], m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") }); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; return true
        }
        // "its trigger puts Avacyn onto the battlefield", "the trigger returns X to the battlefield"
        Regex("""^(?:its|the|that|this) (?:trigger|triggered ability|ability|effect) (?:puts?|returns?|brings?) (?:an? |the |my )?(c\d+) (?:onto|to|into|on) (?:the )?(?:battlefield|play)(?: tapped)?$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[1]); val who = actor ?: ctx.lastActor ?: "me"
            val id = addObject(card, who, false, ctx, zone = "hand"); ctx.events += EventSpec("enter", obj = id); ctx.lastMentioned = id; return true
        }
        // "Wrath of God resolves": cast (by whoever) and resolved.
        Regex("""^(?:an? |the |their |my )?(c\d+) resolves$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[1])
            if (card.display !in ctx.castCards) emitCast(actor ?: ctx.other(ctx.lastActor) ?: "opp", card, "", m, ctx)
            ctx.events += EventSpec("resolveAll"); ctx.explicitResolve = true; return true
        }
        // Damage as a given: "C1 deals 3 damage to <ref>".
        Regex("""^(?:an? |the |my |their )?(c\d+) deals (\d+) damage to (.*)$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[1])
            ctx.events += EventSpec("damage", source = objectIdFor(card, ctx) ?: card.display, amount = r.groupValues[2].toInt(), targets = targetsIn(r.groupValues[3], m, ctx)); return true
        }
        if (actor != null && c.isEmpty()) { ctx.lastActor = actor; return true }
        return false
    }

    private fun emitCast(who: String, card: NameIndex.Entry, rest: String, m: Marked, ctx: Ctx) {
        // "choosing the second mode", "mode 2", "choosing modes 1 and 3"
        val modes = Regex("""(?:choosing |with |picking )?(?:the )?(?:mode|modes) (\d+(?:(?:,| and) \d+)*)|(?:choosing |picking )(?:the )?(first|second|third|fourth) (?:mode|option)""").find(rest)?.let { mm ->
            if (mm.groupValues[1].isNotEmpty()) Regex("""\d+""").findAll(mm.groupValues[1]).map { it.value.toInt() }.toList()
            else listOf(mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4).getValue(mm.groupValues[2]))
        } ?: emptyList()
        val overload = Regex("""\b(?:overloaded|with overload|for (?:its|the) overload cost|via overload)\b""").containsMatchIn(rest)
        val xValue = Regex("""\b(?:with|for|where|at) x ?(?:=|equal to|equals|being|of|as) ?(\d+)\b|\bx ?= ?(\d+)\b""").find(rest)?.let { r -> (r.groupValues[1].ifEmpty { r.groupValues[2] }).toIntOrNull() }
        val kicked = Regex("""\b(?:kicked|with (?:the )?kicker|with kicker paid|paying (?:the )?kicker|kicking it)\b""").containsMatchIn(rest)
        val modeWord = Regex("""(?:choosing|picking|with|for|selecting) (?:the )?([a-z][a-z-]+) (?:mode|option)\b""").find(rest)?.groupValues?.get(1)?.takeIf { it !in setOf("first", "second", "third", "fourth", "same", "other") }
        val targets = if (overload) emptyList() else targetsIn(rest.replace(Regex("""\b(?:with (?:the )?kicker|with kicker paid|paying (?:the )?kicker|kicked)\b"""), "").replace(Regex("""\b(?:with|for|where|at) x ?(?:=|equal to|equals|being|of|as) ?\d+\b|\bx ?= ?\d+\b"""), ""), m, ctx)
        ctx.events += EventSpec("cast", player = who, card = CardRef(name = card.display, oracleId = card.oracleId), targets = targets, modes = modes, to = if (overload) "overload" else if (kicked) "kicked" else modeWord?.let { "mode:$it" }, amount = xValue)
        ctx.lastActor = who
        ctx.lastVerb = "cast"
        ctx.castCards += card.display
        ctx.lastMentioned = "cast:" + slug(card.display)
    }

    /** "targeting the C2 trigger", "on my C3", "at me", "targeting it". */
    private fun targetsIn(rest: String, m: Marked, ctx: Ctx): List<String> {
        val r = rest.trim()
        if (r.isEmpty()) return emptyList()
        val seg = Regex("""^(?:targeting|target|on|at|to|into)\s+(.*)$""").find(r)?.groupValues?.get(1)
            ?: Regex("""^(?:my own|their own)\s+(.*)$""").find(r)?.groupValues?.get(1)
            ?: r.takeIf { Regex("""^(it|that|them|me|the|my|their|c\d+|@\w+)\b""").containsMatchIn(it) }
            ?: return emptyList()
        val out = mutableListOf<String>()
        // "me", "my face", "them" leading the phrase win over a card named later ("at my face with Guttersnipe out").
        if (Regex("""^(me|my face|myself)\b""").containsMatchIn(seg)) { out += "me"; ctx.usesMe = true; return out }
        if (Regex("""^(them|their face|my opponent|the opponent|opponent|opp|him|her)\b""").containsMatchIn(seg) && !Regex("""^(?:my opponent's|the opponent's|opponent's|their)\b""").containsMatchIn(seg)) { out += pronounPlayer(ctx, Regex("""^(\w+)""").find(seg)!!.groupValues[1]); return out }
        // Triggers/abilities of a card: "the C2 trigger", "C2's trigger(ed ability)", "C2's ability".
        Regex("""(?:the |my |their )?(c\d+)(?:'s)? (?:trigger(?:ed ability)?|triggered ability)""").find(seg)?.let { t ->
            val card = m.cards.getValue(t.groupValues[1])
            out += (objectIdFor(card, ctx) ?: addObject(card, "me", false, ctx)) + ":trigger"; return out
        }
        Regex("""(?:the |my |their )?(c\d+)(?:'s)? (?:activated )?ability""").find(seg)?.let { t ->
            val card = m.cards.getValue(t.groupValues[1])
            out += (objectIdFor(card, ctx) ?: addObject(card, "me", false, ctx)) + ":ability"; return out
        }
        // "the trigger" / "that trigger" with no card: the most recently cast spell's trigger source is unknown; use last object.
        if (Regex("""^(?:the|that|this) (?:trigger|triggered ability)\b""").containsMatchIn(seg)) {
            val lastObj = ctx.objects.keys.lastOrNull()
            if (lastObj != null) { out += "$lastObj:trigger"; return out }
        }
        // A spell on the stack, or a permanent.
        Regex("""(?:the |my |their |an? )?(c\d+)""").find(seg)?.let { t ->
            val card = m.cards.getValue(t.groupValues[1])
            objectIdFor(card, ctx)?.let { out += it; return out }
            if (card.display in ctx.castCards) { out += slug(card.display) + ":spell"; return out }
            // A permanent mentioned for the first time as a target: assume it's on the battlefield under the other player.
            val owner = when {
                Regex("""\b(?:my opponent's|the opponent's|opponent's|their|his|her)\b""").containsMatchIn(seg) -> pronounPlayer(ctx, "their")
                seg.contains("my ") -> "me"
                Regex("""@(\w+)'s""").containsMatchIn(seg) -> Regex("""@(\w+)'s""").find(seg)!!.groupValues[1].also { ctx.players.putIfAbsent(it, m.players[it] ?: it) }
                else -> ctx.other(ctx.clauseActor ?: ctx.lastActor) ?: "opp"
            }
            out += addObject(card, owner, seg.contains("tapped") && !seg.contains("untapped"), ctx)
            ctx.notes += "${card.display} wasn't mentioned before; assuming it's on the battlefield under ${when (owner) { "me" -> "your"; "opp" -> "your opponent's"; else -> (ctx.players[owner] ?: owner) + "'s" }} control."
            return out
        }
        if (Regex("""^(it|that|that spell|the spell)\b""").containsMatchIn(seg)) {
            // "it" is the last thing mentioned; when that was a spell just cast, what that spell targeted is the other reading
            // ("Bob bolts my Bears, I respond with Giant Growth on it"). The judge picks whichever the new spell can target.
            val candidates = LinkedHashSet<String>()
            ctx.lastMentioned?.let { lm -> candidates += if (lm.startsWith("cast:")) lm.removePrefix("cast:") + ":spell" else lm }
            if (ctx.lastMentioned?.startsWith("cast:") == true) ctx.events.lastOrNull { it.verb == "cast" }?.targets?.filter { it in ctx.objects }?.let { candidates += it }
            // After a block, "it" may be the blocker or the creature it blocks.
            ctx.events.lastOrNull()?.takeIf { it.verb == "block" }?.let { b -> candidates += listOfNotNull(b.obj) + b.targets.filter { it in ctx.objects } }
            if (candidates.isNotEmpty()) { out += candidates.joinToString("|"); return out }
        }
        if (Regex("""^(me|my face|myself)\b""").containsMatchIn(seg)) { out += "me"; ctx.usesMe = true; return out }
        Regex("""^@(\w+)(?!'s)\b""").find(seg)?.let { r -> val id = r.groupValues[1]; ctx.players.putIfAbsent(id, m.players[id] ?: id); ctx.note(id); out += id; return out }
        if (Regex("""^(them|their face|my opponent|the opponent|opponent|opp|him|her)\b""").containsMatchIn(seg)) { out += pronounPlayer(ctx, Regex("""^(\w+)""").find(seg)!!.groupValues[1]); return out }
        return out
    }

    private val numberWords = mapOf("two" to 2, "three" to 3, "four" to 4, "five" to 5)

    private fun addObject(card: NameIndex.Entry, controller: String, tapped: Boolean, ctx: Ctx, zone: String = "battlefield", allowDuplicate: Boolean = false): String {
        if (!allowDuplicate) ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.let { return it.id }
        var id = slug(card.display)
        var i = 2
        while (ctx.objects.containsKey(id)) id = slug(card.display) + "_" + (i++)
        ctx.objects[id] = ObjectSpec(id, CardRef(name = card.display, oracleId = card.oracleId), zone = zone, controller = controller, tapped = tapped)
        ctx.lastMentioned = id
        return id
    }

    /** "with 3 loyalty", "at 5 loyalty", "with two +1/+1 counters (on it)", "with 2 damage (on it)", "that has 3 damage marked". */
    private fun applyStateWords(id: String, rest: String, ctx: Ctx) {
        var spec = ctx.objects.getValue(id)
        val counters = spec.counters.toMutableMap()
        Regex("""(?:with|at|has|having) (\d+|\w+) loyalty(?: counters?)?""").find(rest)?.let { r -> number(r.groupValues[1])?.let { counters["loyalty"] = it } }
        Regex("""(?:with|has|having) (\d+|\w+) ([+-]\d+/[+-]\d+|[a-z]+) counters?(?: on it)?""").findAll(rest).forEach { r ->
            val n = number(r.groupValues[1]) ?: return@forEach
            val kind = r.groupValues[2]
            if (kind == "loyalty") counters["loyalty"] = n else counters[kind] = (counters[kind] ?: 0) + n
        }
        Regex("""(?:with|has|having|and) (\d+|\w+) damage(?: marked)?(?: on it)?""").find(rest)?.let { r -> number(r.groupValues[1])?.let { spec = spec.copy(damage = it) } }
        Regex("""(?:with |has |having |and )?(?:an? )?([+-]\d+/[+-]\d+) (?:pump|bonus|boost|until end of turn)""").find(rest)?.let { r -> spec = spec.copy(pump = r.groupValues[1]) }
        ctx.objects[id] = spec.copy(counters = counters)
    }

    private fun number(w: String): Int? = w.toIntOrNull() ?: numberWords[w] ?: when (w) { "a", "an", "one" -> 1; else -> null }

    private fun objectIdFor(card: NameIndex.Entry, ctx: Ctx): String? = ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.id
    private fun other(p: String?) = when (p) { "me" -> "opp"; "opp" -> "me"; else -> null }
    private fun slug(name: String) = Names.normalize(name).replace(' ', '_')
}
