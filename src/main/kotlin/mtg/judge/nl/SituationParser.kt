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
        var lifeMe: Int? = null
        var lifeOpp: Int? = null
        var lastActor: String? = null
        var lastVerb: String? = null               // "have" | "cast" for bare continuations ("… and Smothering Tithe")
        var lastOwner: String = "me"
        var lastMentioned: String? = null          // object id, or "cast:<slug>" for a spell just cast
        val castCards = mutableListOf<String>()     // display names of cards cast so far
        var explicitResolve = false
    }

    /** A sentence with card names replaced by placeholders C1, C2… */
    private class Marked(val text: String, val cards: Map<String, NameIndex.Entry>)

    /** For debugging: the sentences with card names replaced by placeholders. */
    fun debugMark(text: String): List<String> = splitSentences(text).map { s -> val m = mark(s); m.text + "   " + m.cards.map { (k, v) -> "$k=${v.display}" } + "   clauses=" + m.text.split(clauseSplit).map { it.trim() } }
    private val clauseSplit = Regex("""\s*(?:,|\band\b)\s+""")

    fun parse(text: String): Parsed {
        val ctx = Ctx()
        for (sentence in splitSentences(text)) {
            val marked = mark(sentence)
            if (!readSentence(marked, ctx)) ctx.unread += sentence.trim()
        }
        if (ctx.events.isNotEmpty() && !ctx.explicitResolve) ctx.events += EventSpec("resolveAll")
        val players = listOf(PlayerSpec("me", "me", ctx.lifeMe), PlayerSpec("opp", "opponent", ctx.lifeOpp))
        return Parsed(Situation(players, TurnSpec(ctx.activePlayer), ctx.objects.values.toList(), emptyList(), ctx.events), ctx.unread, ctx.notes)
    }

    // ---- sentence handling -----------------------------------------------------------------

    private val sentenceSplit = Regex("""(?<=[.!?;])\s+|\n+|\s+(?:and then|, then|then)\s+|,\s+and\s+(?=(?:i|my|the|they|he|she|opponent|opp)\b)""", RegexOption.IGNORE_CASE)

    private fun splitSentences(text: String): List<String> =
        text.split(sentenceSplit).map { it.trim().trimEnd('.', '!', '?', ';', ',') }.filter { it.isNotEmpty() }

    private fun mark(sentence: String): Marked {
        val rawWords = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Normalize per word so word indices line up with the original words.
        val normWords = rawWords.map { w -> Names.normalize(stripPossessive(w)).ifEmpty { "_" } }
        val found = names.findAll(normWords)
        val cards = LinkedHashMap<String, NameIndex.Entry>()
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
                val possessive = core.endsWith("'s") || core.endsWith("\u2019s")
                out.append(' ').append(ph).append(if (possessive) "'s" else "").append(trailing)
                i = f.end
            } else {
                out.append(' ').append(rawWords[i]); i++
            }
        }
        return Marked(out.toString().trim().lowercase().replace('’', '\''), cards)
    }

    private fun stripPossessive(w: String): String {
        val t = w.trimEnd(',', '.', ';', '!', '?')
        return t.removeSuffix("'s").removeSuffix("’s")
    }

    private val actorMe = Regex("""^(i|me|my|i've|i'm|we)\b""")
    private val actorOpp = Regex("""^(my opponent|the opponent|opponent|opp|they|their|he|she|his|her|them)\b""")

    private fun readSentence(m: Marked, ctx: Ctx): Boolean {
        val t0 = m.text.replace(Regex("""\s+"""), " ").trim()
        if (t0.isEmpty()) return true
        var any = false

        // Turn / life statements (removed from the text once read).
        var t2 = t0
        Regex("""\b(it's|it is|during|on|in) (my|their|the opponent's|opponent's|my opponent's) (?:turn|upkeep|end step|main phase|combat)\b""").find(t2)?.let { r ->
            ctx.activePlayer = if (r.groupValues[2] == "my") "me" else "opp"; any = true; t2 = t2.removeRange(r.range)
        }
        Regex("""\b(i'm|i am|i'm at|my life is|i have|i've got) (\d+) life\b""").find(t2)?.let { ctx.lifeMe = it.groupValues[2].toInt(); any = true; t2 = t2.removeRange(it.range) }
        Regex("""\b(opponent|they|they're|opp|my opponent) (is at|are at|has|have|at|is on|are on) (\d+) life\b""").find(t2)?.let { ctx.lifeOpp = it.groupValues[3].toInt(); any = true; t2 = t2.removeRange(it.range) }
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
        var c = clause0
        var actor: String? = null
        actorOpp.find(c)?.let { actor = "opp"; c = c.removeRange(it.range).trim() }
        if (actor == null) actorMe.find(c)?.let { actor = "me"; c = c.removeRange(it.range).trim() }
        if (actor == null && c.startsWith("in response")) actor = other(ctx.lastActor)
        val subject = actor ?: ctx.lastActor

        // Bare continuation: "… and Smothering Tithe" after a possession, "… and Counterspell" after a cast.
        Regex("""^(?:an? |the |my |their |another |also )?(c\d+)(?:'s)?(?: out| in play| on the battlefield| on board| on the field)?$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[1])
            when (ctx.lastVerb) {
                "have" -> { addObject(card, actor ?: ctx.lastOwner, false, ctx); return true }
                "cast" -> { emitCast(subject ?: "opp", card, "", m, ctx); return true }
                else -> return false
            }
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
            addObject(m.cards.getValue(r.groupValues[2]), owner, tapped = rest.contains("tapped") && !rest.contains("untapped"), ctx)
            Regex("""(c\d+)""").findAll(rest).forEach { addObject(m.cards.getValue(it.groupValues[1]), owner, false, ctx) }
            ctx.lastVerb = "have"; ctx.lastOwner = owner
            return true
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
        Regex("""^(?:$activateVerbs)\s+(?:an? |the |their |my )?(c\d+)(?:'s)?(?: ability)?(.*)$""").find(c)?.let { r ->
            val who = subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("activate", player = who, obj = id, targets = targetsIn(r.groupValues[2], m, ctx)); ctx.lastActor = who; return true
        }
        // Combat: "attack with c1", "swing with c1 (at them)", "block (it) with c2".
        Regex("""^(?:attacks?|attacking|swings?|swinging)(?: with)?\s+(?:an? |the |my |their )?(c\d+)(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("attack", player = who, obj = id, targets = targetsIn(r.groupValues[2], m, ctx).ifEmpty { listOf(other(who) ?: "opp") }); ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        Regex("""^(?:blocks?|blocking)(?: it| that| the attacker)?(?: with)?\s+(?:an? |the |my |their )?(c\d+)(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "opp"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            val attacker = ctx.events.lastOrNull { it.verb == "attack" }?.obj
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOfNotNull(attacker)); ctx.lastActor = who; return true
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
        val targets = targetsIn(rest, m, ctx)
        ctx.events += EventSpec("cast", player = who, card = CardRef(name = card.display, oracleId = card.oracleId), targets = targets)
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
            ?: r.takeIf { Regex("""^(it|that|them|me|the|my|their|c\d+)\b""").containsMatchIn(it) }
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
            val owner = when { seg.contains("my ") -> "me"; seg.contains("their ") -> "opp"; else -> other(ctx.lastActor) ?: "me" }
            out += addObject(card, owner, false, ctx)
            ctx.notes += "${card.display} wasn't mentioned before; assuming it's on the battlefield under ${if (owner == "me") "your" else "your opponent's"} control."
            return out
        }
        if (Regex("""^(it|that|that spell|the spell)\b""").containsMatchIn(seg)) {
            ctx.lastMentioned?.let { lm -> out += if (lm.startsWith("cast:")) lm.removePrefix("cast:") + ":spell" else lm; return out }
        }
        if (Regex("""^(me|my face|myself)\b""").containsMatchIn(seg)) { out += "me"; return out }
        if (Regex("""^(them|their face|my opponent|the opponent|opponent|opp|him|her)\b""").containsMatchIn(seg)) { out += "opp"; return out }
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

    private fun objectIdFor(card: NameIndex.Entry, ctx: Ctx): String? = ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.id
    private fun other(p: String?) = when (p) { "me" -> "opp"; "opp" -> "me"; else -> null }
    private fun slug(name: String) = Names.normalize(name).replace(' ', '_')
}
