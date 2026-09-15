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
        /** Named players in order of first mention: id -> display name. "me"/"opp" are added when the text uses them. */
        val players = LinkedHashMap<String, String>()
        var usesMe = false
        var usesOpp = false
        var lastNamed: String? = null              // the last named (non-"me") player who acted, for "they"/"he"/"she"
        var lastActor: String? = null
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
        val players = ctx.playerIds().map { id -> PlayerSpec(id, when (id) { "me" -> "me"; "opp" -> "opponent"; else -> ctx.players[id] ?: id }, ctx.life[id]) }
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

    private val actorMe = Regex("""^(i|me|my|i've|i'm|we)\b""")
    private val actorOpp = Regex("""^(my opponent|the opponent|opponent|opp|they|their|he|she|his|her|them)\b""")

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
        Regex("""\b(opponent|they|they're|opp|my opponent) (is at|are at|has|have|at|is on|are on) (\d+) life\b""").find(t2)?.let { ctx.life[pronounPlayer(ctx)] = it.groupValues[3].toInt(); any = true; t2 = t2.removeRange(it.range) }
        Regex("""@(\w+) (?:is at|is on|has|at|sits at|is) (\d+)(?: life)?\b""").findAll(t2).toList().asReversed().forEach { ctx.life[it.groupValues[1]] = it.groupValues[2].toInt(); ctx.players.putIfAbsent(it.groupValues[1], m.players[it.groupValues[1]] ?: it.groupValues[1]); any = true; t2 = t2.removeRange(it.range) }
        val t = t2.trim()

        // Pure questions carry no state; the engine answers "what happens" by default.
        if (m.cards.isEmpty() && Regex("""^(what happens|what now|who wins|so what|what's the result|does (it|that|this) (resolve|work|happen)|do i (draw|win|lose)|can (i|they|my opponent) respond)\b.*$""").matches(t)) return true

        // Resolution statements.
        if (Regex("""\b(everything resolves|let (it|them|everything|that) resolve|(it|they|both|all) resolves?|resolves? (it|everything|the stack)|nobody responds|no (one|body) responds|no responses?|no further responses?)\b""").containsMatchIn(t)) {
            ctx.events += EventSpec("resolveAll"); ctx.explicitResolve = true; any = true
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

    private fun isNoise(clause: String) = Regex("""^(what happens|what now|so|then|now|ok|okay|right|do i draw|does it work|is that right|correct)\??$""").matches(clause.trim())
    private fun restore(text: String, m: Marked): String = m.cards.entries.fold(text) { acc, (ph, e) -> acc.replace(Regex("\\b$ph\\b"), e.display) }

    private val castVerbs = """(?:casts?|casting|plays?|playing|fires? off|slams?)"""
    private val respondVerbs = """(?:respond(?:s|ed)? with|in response(?: i| they)? (?:casts?|plays?)|responds?|answers? with|counters? (?:it|that) with|flash(?:es)? in)"""
    private val activateVerbs = """(?:activates?|activating|uses?)"""

    private fun readClause(clause0: String, m: Marked, ctx: Ctx): Boolean {
        // Step beginnings keep their possessive: "my upkeep begins", "at the beginning of their end step".
        Regex("""^(?:at the beginning of |at the start of |during |on |at |it's |it is )?(my|their|the opponent's|opponent's|my opponent's|each|the) (upkeep|draw step|end step|end of turn|precombat main phase|main phase|combat|beginning of combat)(?: begins| starts| now)?$""").find(clause0)?.let { r ->
            val who = when (r.groupValues[1]) { "my" -> "me"; "each", "the" -> ctx.activePlayer ?: "me"; else -> "opp" }
            val step = when (r.groupValues[2]) { "upkeep" -> "upkeep"; "draw step" -> "draw"; "end step", "end of turn" -> "end"; "combat", "beginning of combat" -> "combat"; else -> "precombat_main" }
            ctx.activePlayer = who
            ctx.events += EventSpec("step", player = who, to = step); return true
        }
        // Leading connectives carry no information: "then my opponent…", "next, they…", "now I…", "so I…".
        var c = clause0.replace(Regex("""^(?:then|next|now|so|after that|afterwards|later|finally|also|meanwhile)\s*,?\s+"""), "")
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

        // A state fragment about the last-mentioned permanent: "… and 1 damage on it", "with three +1/+1 counters", "at 4 loyalty".
        if (Regex("""^(?:with |has |having |it has |at |is at )?(?:\d+|\w+) (?:loyalty(?: counters?)?|(?:[+-]\d+/[+-]\d+|[a-z]+) counters?(?: on it)?|damage(?: marked)?(?: on it)?)$""").matches(c)) {
            val id = ctx.lastMentioned?.takeIf { ctx.objects.containsKey(it) } ?: return false
            applyStateWords(id, "with " + c.replace(Regex("""^(?:with |has |having |it has |at |is at )"""), ""), ctx); return true
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
        // Paying, or not, for a tax: "pays", "pays the 1", "doesn't pay", "declines to pay (for Rhystic)".
        Regex("""^(?:(does not|doesn't|don't|do not|won't|will not|didn't|declines? to|refuses? to|isn't going to) )?(?:pays?|paid|paying)\b.*$""").find(c)?.let { r ->
            val who = actor ?: subject ?: ctx.events.lastOrNull { it.verb == "cast" }?.player ?: "me"
            ctx.events += EventSpec("pay", player = who, to = if (r.groupValues[1].isEmpty()) "yes" else "no"); ctx.lastActor = who; return true
        }
        // Possession: "have X (on the battlefield|out|in play)", "control X", "X is on the battlefield".
        Regex("""^(?:have|has|got|control|controls|controlling|'ve got|am playing|is playing|are playing|run|running)\s+(?:an? |the |my |their |(\d+|two|three|four|five) )?(c\d+)(?:'s)?(.*)$""").find(c)?.let { r ->
            val owner = actor ?: "me"
            val count = r.groupValues[1].let { numberWords[it] ?: it.toIntOrNull() ?: 1 }
            val rest = r.groupValues[3]
            if (count > 1) { repeat(count) { addObject(m.cards.getValue(r.groupValues[2]), owner, false, ctx, allowDuplicate = true) }; ctx.lastVerb = "have"; ctx.lastOwner = owner; return true }
            if (rest.contains("in hand") || rest.contains("in my hand")) {
                ctx.notes += "${m.cards.getValue(r.groupValues[2]).display} noted as in hand (hidden zones are only tracked when you cast from them)."
                return true
            }
            val hostId = addObject(m.cards.getValue(r.groupValues[2]), owner, tapped = rest.contains("tapped") && !rest.contains("untapped"), ctx)
            applyStateWords(hostId, rest, ctx)
            // "… with Rancor on it", "… enchanted with X", "… equipped with X"
            Regex("""(?:enchanted with|equipped with|wearing|with) (?:an? |the )?(c\d+)""").findAll(rest).forEach { a -> val id = addObject(m.cards.getValue(a.groupValues[1]), owner, false, ctx); ctx.objects[id] = ctx.objects.getValue(id).copy(attachedTo = hostId) }
            Regex("""(c\d+)""").findAll(rest).forEach { if (ctx.objects.values.none { o -> o.card.oracleId == m.cards.getValue(it.groupValues[1]).oracleId }) addObject(m.cards.getValue(it.groupValues[1]), owner, false, ctx) }
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
        Regex("""^(?:then )?(?:$castVerbs|$respondVerbs)\s+(?:an? |the |another |their |my )?(c\d+)(.*)$""").find(c)?.let { r ->
            emitCast(subject ?: "opp", m.cards.getValue(r.groupValues[1]), r.groupValues[2], m, ctx); return true
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
            // "it" after a spell means what that spell targeted ("I cast Act of Treason on their Giant and attack with it").
            val id = ctx.lastMentioned?.takeIf { !it.startsWith("cast:") } ?: ctx.events.lastOrNull { it.verb == "cast" }?.targets?.firstOrNull { it in ctx.objects } ?: return false
            ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(ctx.other(who) ?: "opp")); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; return true
        }
        // "attack Jace with Hill Giant", "attacks their planeswalker with c2": the defender comes first.
        Regex("""^(?:attacks?|attacking|swings? at|swinging at)\s+((?:an? |the |my |their |@\w+'s )?(?:c\d+|me|them|him|her|my opponent|the opponent|opponent|@\w+))\s+with\s+(?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val card = m.cards.getValue(r.groupValues[2])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            val defender = targetsIn("at " + r.groupValues[1], m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") }
            ctx.events += EventSpec("attack", player = who, obj = id, targets = defender); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; return true
        }
        Regex("""^(?:attacks?|attacking|swings?|swinging)(?: with)?\s+(?:an? |the |my |their )?(c\d+)(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("attack", player = who, obj = id, targets = targetsIn(r.groupValues[2], m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") }); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; return true
        }
        Regex("""^(?:blocks?|blocking)(?: it| that| the attacker)?(?: with)?\s+(?:an? |the |my |their |a single |one |just |only )?(c\d+)(.*)$""").find(c)?.let { r ->
            val attackerPlayer = ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }?.player
            val who = actor ?: attackerPlayer?.let { ctx.other(it) } ?: subject ?: "opp"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            // The attacker being blocked: the last creature declared attacking this player, else the last attacker.
            val attacker = ctx.events.lastOrNull { it.verb == "attack" && who in it.targets }?.obj ?: ctx.events.lastOrNull { it.verb == "attack" }?.obj
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOfNotNull(attacker)); ctx.lastActor = who; ctx.lastVerb = "block"; return true
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
        val targets = targetsIn(rest, m, ctx)
        ctx.events += EventSpec("cast", player = who, card = CardRef(name = card.display, oracleId = card.oracleId), targets = targets, modes = modes)
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
            val owner = when { seg.contains("my ") -> "me"; Regex("""@(\w+)'s""").containsMatchIn(seg) -> Regex("""@(\w+)'s""").find(seg)!!.groupValues[1].also { ctx.players.putIfAbsent(it, m.players[it] ?: it) }; seg.contains("their ") -> pronounPlayer(ctx, "their"); else -> ctx.other(ctx.lastActor) ?: "me" }
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
        ctx.objects[id] = spec.copy(counters = counters)
    }

    private fun number(w: String): Int? = w.toIntOrNull() ?: numberWords[w] ?: when (w) { "a", "an", "one" -> 1; else -> null }

    private fun objectIdFor(card: NameIndex.Entry, ctx: Ctx): String? = ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.id
    private fun other(p: String?) = when (p) { "me" -> "opp"; "opp" -> "me"; else -> null }
    private fun slug(name: String) = Names.normalize(name).replace(' ', '_')
}
