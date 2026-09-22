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
        /** Creatures the asker called "blockers" before anything was attacking: matched up at the end. */
        val pendingBlockers = mutableMapOf<String, MutableList<String>>()
        val objects = LinkedHashMap<String, ObjectSpec>()
        val events = mutableListOf<EventSpec>()
        val unread = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val asks = mutableListOf<EventSpec>()   // "does my Blood Artist trigger?": answered after everything has resolved
        var castingCounter = false               // the spell whose targets are being read counters spells ("Counterspell on my Bears")
        var castingCounterName: String? = null   // which spell that is, so a modal blast can aim at a permanent
        var activePlayer: String? = null
        val life = LinkedHashMap<String, Int>()
        val poison = LinkedHashMap<String, Int>()
        val handSize = LinkedHashMap<String, Int>()
        val mana = LinkedHashMap<String, Int>()
        val librarySize = LinkedHashMap<String, Int>()
        val graveyardSize = LinkedHashMap<String, Int>()
        var turnNumber: Int? = null
        val devotion = LinkedHashMap<String, MutableMap<String, Int>>()
        /** "I have cast four spells this turn": a storm-style count the situation stated rather than played out. */
        val spellsThisTurn = LinkedHashMap<String, Int>()
        /** Life stated as paid while casting the next spell, carried to the cast event. */
        var payLife: Int? = null
        val commanderDamage = LinkedHashMap<String, MutableMap<String, Int>>()
        /** Named players in order of first mention: id -> display name. "me"/"opp" are added when the text uses them. */
        val players = LinkedHashMap<String, String>()
        var usesMe = false
        var usesOpp = false
        var lastNamed: String? = null              // the last named (non-"me") player who acted, for "they"/"he"/"she"
        var lastActor: String? = null
        /** Position of the clause being read within its sentence (0 = first); a later clause with no subject continues the sentence's. */
        var clauseIndex = 0
        var clauseActor: String? = null            // the actor of the clause being read (for "their X" defaults)
        val inHand = HashMap<String, MutableList<NameIndex.Entry>>()
        var nextTurn = false   // cards noted as in hand, per player ("I cast it")
        var lastVerb: String? = null               // "have" | "cast" for bare continuations ("… and Smothering Tithe")
        var lastOwner: String = "me"
        var lastMentioned: String? = null          // object id, or "cast:<slug>" for a spell just cast
        val castCards = mutableListOf<String>()     // display names of cards cast so far
        var lastCastEntry: NameIndex.Entry? = null  // the card behind lastMentioned's "cast:<slug>", for acting on it once it resolves
        var explicitResolve = false
        /** The running total a bare "and 2 more" adds to: "poison counters", "life", "+1/+1 counters". */
        var lastStat: String? = null
        var lastStatWho: String? = null

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
    private val clauseSplit = Regex("""\s*(?:,|\band\b|\bbut\b(?= (?:i|we|they|he|she|my|their|his|her|the|it)\b))\s+""")

    /** Words that name a cycle of lands rather than a card. Picking a member for the asker would be guessing. */
    private val landCycles = mapOf(
        "fetchland" to "Flooded Strand, Wooded Foothills, …", "fetch land" to "Flooded Strand, Wooded Foothills, …",
        "fetchlands" to "Flooded Strand, Wooded Foothills, …", "fetch lands" to "Flooded Strand, Wooded Foothills, …",
        "shockland" to "Steam Vents, Blood Crypt, …", "shock land" to "Steam Vents, Blood Crypt, …",
        "shocklands" to "Steam Vents, Blood Crypt, …", "shock lands" to "Steam Vents, Blood Crypt, …",
        "triome" to "Raugrin Triome, Zagoth Triome, …", "triomes" to "Raugrin Triome, Zagoth Triome, …",
        "painland" to "Adarkar Wastes, Karplusan Forest, …", "pain land" to "Adarkar Wastes, Karplusan Forest, …",
        "checkland" to "Glacial Fortress, Rootbound Crag, …", "fastland" to "Seachrome Coast, Blackcleave Cliffs, …",
        "manland" to "Celestial Colonnade, Raging Ravine, …", "creature land" to "Celestial Colonnade, Raging Ravine, …")

    fun parse(text: String): Parsed {
        val ctx = Ctx()
        val sentences = splitSentences(text)
        // "I crack a fetchland": the word names a cycle, and every member does something different. Saying which
        // card it was read as would be picking one for the asker, so the answer asks instead.
        for ((word, examples) in landCycles) if (Regex("""(?i)\b${Regex.escape(word)}\b""").containsMatchIn(text)) {
            ctx.notes += "\"$word\" names a cycle of lands, not a card, and they don't all do the same thing — name the one you have ($examples)."
            break
        }
        val short = shortNames(sentences)
        val named = playerNames(sentences)
        for (sentence in sentences) {
            val marked = mark(sentence, short, named)
            marked.cards.values.filter { it.alternatives.isNotEmpty() }.distinctBy { it.oracleId }.forEach { e -> val note = "\"${e.display.substringBefore(",")}\" is read as ${e.display}; it could also be ${e.alternatives.joinToString(" or ")}. Use the full name if you meant another."; if (note !in ctx.notes) ctx.notes += note }
            if (!readSentence(marked, ctx) && !isNoise(marked.text)) ctx.unread += sentence.trim()
        }
        // Cards the text put in a hand become objects in that hand, unless they were already used for something.
        for ((who, cards) in ctx.inHand) for ((n, card) in cards.withIndex()) {
            val already = ctx.objects.values.count { it.card.oracleId == card.oracleId && it.controller == who }
            if (already >= cards.count { it.oracleId == card.oracleId }) continue
            var id = slug(card.display); var k = 2; while (ctx.objects.containsKey(id)) id = slug(card.display) + "_" + (k++)
            ctx.objects[id] = ObjectSpec(id, CardRef(name = card.display, oracleId = card.oracleId), zone = "hand", controller = who)
        }
        // "They've got two blockers and I attack with a 5/5": the blockers were named before the attack, so the
        // rule that makes them block had nothing to block yet. Match them up once the whole question is read.
        if (ctx.pendingBlockers.isNotEmpty()) {
            for ((who, ids) in ctx.pendingBlockers) {
                val attacks = ctx.events.filter { it.verb == "attack" && (it.targets.contains(who) || it.targets.isEmpty()) }
                val free = ids.filter { id -> ctx.events.none { e -> e.verb == "block" && e.obj == id } }
                if (attacks.isEmpty() || free.isEmpty()) continue
                free.forEachIndexed { i, id -> attacks.getOrNull(i)?.obj?.let { atk -> ctx.events += EventSpec("block", player = who, obj = id, targets = listOf(atk)) } }
                ctx.notes += "\"${if (free.size == 1) "a blocker" else "${free.size} blockers"}\" is read as blocking${if (free.size > 1) ", one attacker each" else ""}; say otherwise if they don't block."
            }
        }
        // An explicit "it resolves" mid-situation settles what was on the stack then; anything cast after it still needs to resolve.
        val tail = ctx.events.lastOrNull()?.verb
        if (ctx.events.isNotEmpty() && (!ctx.explicitResolve || tail in setOf("cast", "activate", "trigger", "attack", "attackAll", "block"))) ctx.events += EventSpec("resolveAll")
        // "I draw during my draw step": the step draws the card itself (504.1), so a draw said right after it is
        // the same draw said twice, and the answer had the player drawing two cards.
        for (i in ctx.events.indices.reversed()) {
            val e = ctx.events[i]
            if (e.verb != "draw" || (e.amount ?: 1) != 1) continue
            val prev = ctx.events.getOrNull(i - 1) ?: continue
            if (prev.verb == "step" && prev.to == "draw" && prev.player == e.player) ctx.events.removeAt(i)
        }
        // "I draw during my draw step": the step draws the card itself (504.1), so a draw said right after it is
        // the same draw said twice, and the answer had the player drawing two cards.
        for (i in ctx.events.indices.reversed()) {
            val e = ctx.events[i]
            if (e.verb != "draw" || (e.amount ?: 1) != 1) continue
            val prev = ctx.events.getOrNull(i - 1) ?: continue
            if (prev.verb == "step" && prev.to == "draw" && prev.player == e.player) ctx.events.removeAt(i)
        }
        // "I activate Grim Monolith. How much mana do I get?" — the question says which of its abilities is meant.
        for (ask in ctx.asks.filter { it.verb == "ask" && (it.to == "mana" || it.to == "manaAvailable") }) {
            // "how much mana do I get?" names no card, so the activation it is about is the last one.
            val i = ctx.events.indexOfLast { it.verb == "activate" && it.to == null && (ask.obj == null || it.obj == ask.obj) }
            if (i >= 0) ctx.events[i] = ctx.events[i].copy(to = "mana")
        }
        ctx.events += ctx.asks
        // Every player that took part; "me" and "opponent" only when the text spoke of them (or named nobody).
        for (e in ctx.events) { e.player?.let { ctx.note(it) }; e.targets.forEach { if (it == "me" || it == "opp") ctx.note(it) } }
        for (o in ctx.objects.values) ctx.note(o.controller)
        val turnSpec = TurnSpec(ctx.activePlayer, null, null, ctx.turnNumber)
        val players = ctx.playerIds().map { id -> PlayerSpec(id, when (id) { "me" -> "me"; "opp" -> "opponent"; else -> ctx.players[id] ?: id }, ctx.life[id], ctx.poison[id], ctx.handSize[id], ctx.librarySize[id], ctx.commanderDamage[id] ?: emptyMap(), ctx.mana[id], ctx.devotion[id] ?: emptyMap(), ctx.spellsThisTurn[id], ctx.graveyardSize[id]) }
        if (ctx.players.isNotEmpty() && ctx.usesOpp && ctx.players.size >= 2) ctx.notes += "\"opponent\"/\"they\" was read as a separate player from ${ctx.players.values.joinToString(" and ")}; name the player instead if that's wrong."
        return Parsed(Situation(players, turnSpec, ctx.objects.values.toList(), emptyList(), ctx.events), ctx.unread, ctx.notes)
    }

    // ---- sentence handling -----------------------------------------------------------------

    // "I cast Go Nuts! choosing mode 1": 73 cards have a name that ends in "!" or "?", and splitting there left
    // the rest of the sentence orphaned. A real sentence break is followed by something that starts one.
    // The split keeps the "then": dropped, "I cast Angel's Grace, then they Bolt me" read as a response to a
    // spell still on the stack instead of something done after it had resolved.
    private val sentenceSplit = Regex("""(?<=[.;])\s+|(?<=[!?])\s+(?=(?-i:[A-Z"'(])|$)|\n+|\s*(?:and\s+|,\s*)?(?=then\s)|,\s+and\s+(?=(?:i|my|the|they|he|she|opponent|opp)\b)""", RegexOption.IGNORE_CASE)

    /**
     * "use her plus one", "activate its minus three": loyalty costs said out loud. Written as words they are read
     * as card names — "Plus One" is a card — so they are turned back into "+1" / "-3" before anything is marked.
     */
    private val spokenLoyalty = Regex("""\b(its|his|her|their|the|my) (plus|minus) (one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|\d+)\b""", RegexOption.IGNORE_CASE)
    private val spokenNumbers = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen")

    /** "Bob then bolts my Bears": the subject stands alone, so the "then" has to step over it to stay in front. */
    private val leadingThen = Regex("""^(?:then|next|later|finally)\s+""", RegexOption.IGNORE_CASE)

    private fun splitSentences(text0: String): List<String> {
        val text = spokenLoyalty.replace(text0) { r ->
            val n = r.groupValues[3].toIntOrNull() ?: (spokenNumbers.indexOf(r.groupValues[3].lowercase()) + 1)
            "${r.groupValues[1]} ${if (r.groupValues[2].lowercase() == "minus") "-" else "+"}$n"
        }
        val pieces = text.split(sentenceSplit).map { it.trim().trimEnd('.', '!', '?', ';', ',') }.filter { it.isNotEmpty() }
        // "Bob then bolts my Bears": a lone subject before "then" belongs to what follows.
        val out = mutableListOf<String>()
        var carry: String? = null
        for (p in pieces) { if (p.split(' ').size == 1 && Regex("""^(?:[A-Z][a-z]+|I|They|He|She|We|Opponent)$""").matches(p) && names.lookup(Names.normalize(p)) == null) { carry = (carry?.let { "$it " } ?: "") + p; continue }; out += carry?.let { cr -> leadingThen.find(p)?.let { t -> t.value + cr + " " + p.substring(t.range.last + 1) } ?: "$cr $p" } ?: p; carry = null }
        carry?.let { out += it }
        return out
    }

    /**
     * Once a card has been named in full, people shorten it: "Jace" for Jace Beleren, "the Giant" for Hill Giant.
     * Collect the distinctive words of every fully named card so later sentences can use them, as long as the
     * word points at exactly one of the mentioned cards and isn't a card name (or grammar word) in its own right.
     */
    private fun shortNames(sentences: List<String>): Map<String, NameIndex.Entry> {
        val candidates = LinkedHashMap<String, MutableSet<NameIndex.Entry>>()
        val typeCandidates = LinkedHashMap<String, MutableSet<NameIndex.Entry>>()
        for (sentence in sentences) {
            val rawWords = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val words = rawWords.map { w -> Names.normalize(stripPossessive(w)).ifEmpty { "_" } }
            // "Ashnod's Altar" is only found with the possessive kept, so both readings are collected.
            val keptWords = rawWords.map { w -> Names.normalize(w.trimEnd(',', '.', ';', '!', '?')).ifEmpty { "_" } }
            val foundAll = (names.findAll(keptWords) + names.findAll(words)).distinctBy { it.entry.oracleId }
            for (f in foundAll) {
                if (f.entry.alternatives.isNotEmpty()) continue   // a bare first name isn't a card named in full
                val display = f.entry.display
                val head = display.substringBefore(",")   // "Jace, the Mind Sculptor" -> "Jace"
                val nameWords = Names.normalize(display).split(' ').filter { it.isNotEmpty() }
                val keys = LinkedHashSet<String>()
                if (head != display) keys += Names.normalize(head)
                if (nameWords.size > 1) { keys += nameWords.first(); keys += nameWords.last() }
                for (k in keys) if (k.length >= 3 && k !in shortNameStop) candidates.getOrPut(k) { LinkedHashSet() } += f.entry
                // "the Angel" for Serra Angel: a creature-type word in the name, when only one mentioned card carries it.
                for (k in nameWords) if (k in typeShortNames && Regex("""(?i)\b${Regex.escape(k)}\b""").containsMatchIn(f.entry.typeLine.substringAfter("—", ""))) typeCandidates.getOrPut(k) { LinkedHashSet() } += f.entry
            }
        }
        val out = LinkedHashMap<String, NameIndex.Entry>()
        typeCandidates.filterValues { it.size == 1 }.forEach { (k, v) -> out[k] = v.first() }
        candidates.filterValues { it.size == 1 }.forEach { (k, v) -> out[k] = v.first() }
        return out
    }

    /** Creature-type words people shorten a name to ("the Angel", "the Giant"); only used with "the"/"my"/"their" in front. */
    /** Words that make the next word a description rather than a name: "a Goblin", "two Walls". */
    private val indefiniteWords = setOf("a", "an", "another", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "some", "any", "no", "each", "every", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")
    /** The card types a graveyard can be described by, when nobody names the cards. */
    private val cardTypeWord = """(?:instant|sorcery|creature|land|artifact|enchantment|planeswalker|battle)"""
    /** Number words are counts, never the card of that name; a card whose name starts with one is longer than a word. */
    private val countWords = setOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
    /** Card-type words. A count or an article in front of one makes it a description, whatever card shares the name. */
    private val cardTypeWords = setOf("artifact", "artifacts", "creature", "creatures", "land", "lands", "enchantment", "enchantments",
        "planeswalker", "planeswalkers", "instant", "instants", "sorcery", "sorceries", "permanent", "permanents", "spell", "spells", "battle", "battles")
    /** Phrases that name a zone or a part of the game, whatever card shares the words. */
    private val zonePhrases = setOf("the command zone", "command zone", "the battlefield", "the stack", "the graveyard")
    /** Mechanic and status words that are also card names ("Delirium", "The Monarch"); said of a player they mean the mechanic. */
    private val mechanicWords = setOf("delirium", "threshold", "metalcraft", "revolt", "spell mastery", "ferocious", "formidable",
        "hellbent", "undergrowth", "the monarch", "monarch", "the city's blessing", "landfall", "morbid", "raid", "monstrosity",
        "ascend", "constellation", "prowess", "coven", "the initiative")
    private val typeShortNames = setOf("wall", "angel", "demon", "dragon", "giant", "wizard", "knight", "elf", "goblin", "sphinx", "beast", "bird", "cat", "wolf", "bear", "elemental", "spirit", "soldier", "warrior", "lord", "king", "queen", "rat", "dog", "zombie", "vampire", "hydra", "titan", "golem", "wurm", "drake", "djinn", "phoenix", "shaman", "druid", "cleric", "rogue", "archer")

    private val shortNameStop = setOf("the", "and", "with", "from", "into", "onto", "for", "that", "this", "your", "you", "all", "each", "any", "one", "two", "three", "of",
        "control", "controls", "cast", "casts", "have", "has", "play", "plays", "tap", "taps", "sacrifice", "return", "destroy", "exile", "draw", "gain", "lose", "pay", "attack", "block", "counter", "magic", "growth", "study",
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
                // "Bob bolts my Bears": the next word is a card name used as a verb, so the capitalised word before it is a player.
                val nextIsVerbifiedCard = (i + 1) in covered &&
                    rawWords.getOrNull(i + 1)?.trimEnd(',', '.', ';', '!', '?')?.lowercase()?.let { w -> (w.endsWith("s") && !w.endsWith("ss")) || w.endsWith("ed") } == true
                // "Alice, Bob and I are playing": a roster sentence names players and nothing else, so every
                // capitalised word in it that isn't a card is one. Without this Bob was never in the game.
                val roster = Regex("""\b(?:are|is|'re) (?:playing|in the game|at the table)\b""", RegexOption.IGNORE_CASE).containsMatchIn(sentence)
                val actsLikePlayer = possessive || next in playerVerbs || prev in playerPreps || nextIsVerbifiedCard || roster
                if (actsLikePlayer) out[norm] = core
            }
        }
        return out
    }

    private val playerNameStop = setOf("i", "my", "me", "we", "opponent", "opp", "they", "he", "she", "it", "the", "then", "now", "so", "if", "when", "what", "where", "why", "which", "who", "whom", "how", "will", "would", "could", "should", "am", "was", "were", "does", "do", "can", "is", "are", "at", "during", "on", "in", "after", "before", "also",
        "both", "each", "everyone", "nobody", "no", "yes", "player", "someone", "another", "his", "her", "their", "its", "next", "later", "finally", "meanwhile", "who", "whose", "which", "how", "why", "there", "here", "this", "that", "these", "those",
        "one", "two", "three", "four", "first", "second", "third", "last", "turn", "upkeep", "combat", "stack", "response", "target", "counter", "damage", "life", "commander", "planeswalker", "creature", "spell", "ability", "trigger", "token", "land",
        "mine", "theirs", "yours", "ours", "attacker", "blocker", "neither", "either", "everything", "nothing", "something", "anything",
        "let", "lets", "say", "suppose", "imagine", "assume", "tell", "explain", "question", "quick", "just", "ok", "okay", "alright", "hey", "well", "anyway")
    private val playerVerbs = setOf("casts", "cast", "plays", "played", "attacks", "attacked", "blocks", "blocked", "has", "have", "had", "controls", "control", "activates", "activated", "responds", "responded", "taps", "sacrifices",
        "is", "are", "was", "swings", "targets", "counters", "draws", "pays", "declines", "passes", "says", "wants", "does", "gets", "takes", "loses", "gains", "dies", "wins", "uses", "equips", "flashes", "resolves", "fires", "slams", "runs",
        // What one player does to another's things: "Alice kills Bob's Bears", "Bob bounces Alice's Titan".
        "kills", "killed", "destroys", "destroyed", "exiles", "exiled", "bounces", "bounced", "blinks", "blinked", "flickers", "flickered", "removes", "removed", "nukes", "nuked", "pings", "pinged", "zaps", "zapped", "pumps", "pumped", "shrinks", "answers", "sacs", "discards", "mills", "returns", "steals", "copies", "untaps", "reveals", "searches", "makes", "creates", "attacks", "blocks", "swings", "wipes", "scoops", "concedes")
    private val playerPreps = setOf("at", "targeting", "target", "to", "attacks", "attack", "attacking", "and", "hits", "hit", "with", "against", "on", "of", "then", "meanwhile")

    /** Whether the last thing played or cast was a land: "another one" after a land means another land. */
    private fun lastPlayWasALand(ctx: Ctx) = ctx.events.lastOrNull { it.verb in setOf("playLand", "cast", "activate") }?.verb == "playLand"

    /** "I play a land", said again: [n] more land plays for [who], each counted against the turn's allowance. */
    private fun playALand(who: String, n: Int, ctx: Ctx) {
        repeat(n) {
            var id = slug("a land"); var k = 2; while (ctx.objects.containsKey(id)) id = slug("a land") + "_" + (k++)
            ctx.objects[id] = ObjectSpec(id, CardRef(name = "a basic land"), controller = who, zone = "hand")
            ctx.events += EventSpec("playLand", player = who, obj = id); ctx.lastMentioned = id
        }
        ctx.lastActor = who; ctx.lastOwner = who; ctx.lastVerb = "playLand"; ctx.note(who)
    }

    private val castWordsBeforeAName = setOf("cast", "casts", "casting", "play", "plays", "playing", "played", "resolve", "resolves")

    private fun mark(sentence: String, short: Map<String, NameIndex.Entry> = emptyMap(), named: Map<String, String> = emptyMap()): Marked {
        val rawWords = sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Normalize per word so word indices line up with the original words.
        val normWords = rawWords.map { w -> Names.normalize(stripPossessive(w)).ifEmpty { "_" } }
        // Names that themselves contain "'s" ("Titania's Boon", "Sensei's Divining Top") are matched with the possessive kept…
        val normKept = rawWords.map { w -> Names.normalize(w.trimEnd(',', '.', ';', '!', '?')).ifEmpty { "_" } }
        val kept = names.findAll(normKept).filter { f -> (f.start until f.end).any { normKept[it] != normWords[it] } }
        val keptCovered = kept.flatMap { it.start until it.end }.toSet()
        // …then the rest with it stripped. A single word that is both a card name and the short form of a card named in full ("Jace" the token vs. Jace Beleren) means the named card.
        fun shortAt(i: Int): NameIndex.Entry? = short[normWords[i]]?.takeIf { normWords[i] !in typeShortNames || (i > 0 && (normWords[i - 1] in setOf("the", "my", "their", "that", "this", "his", "her", "your") || rawWords[i - 1].endsWith("'s") || rawWords[i - 1].endsWith("\u2019s"))) }
        val full = kept + names.findAll(normWords).filter { f -> (f.start until f.end).none { it in keptCovered } }.map { f -> if (f.end - f.start == 1) shortAt(f.start)?.let { f.copy(entry = it) } ?: f else f }
        val covered = full.flatMap { it.start until it.end }.toSet()
        val found = (full + normWords.indices.filter { it !in covered }.mapNotNull { i -> shortAt(i)?.let { NameIndex.Found(i, i + 1, it) } })
            // "cast my commander from the command zone": the zone, not the card called "The Command Zone".
            .filter { f -> (f.start until f.end).joinToString(" ") { normWords[it] } !in zonePhrases }
            // "I have delirium", "my opponent is the monarch": the mechanic, not the card that shares its name.
            .filter { f -> (f.start until f.end).joinToString(" ") { normWords[it] } !in mechanicWords }
            // "a Goblin", "two Walls": a bare creature type after an indefinite article or a count is a description, not the card of that name.
            .filter { f -> !(f.end - f.start == 1 && normWords[f.start] in typeShortNames && normWords.getOrNull(f.start - 1) in indefiniteWords) }
            // "three artifacts", "two creatures": a bare card-type word after a count or an article describes what
            // it is, not the card of that name — there is a card called "Artifacts", and it was taking the count
            // Dispatch's metalcraft check needed with it.
            .filter { f -> !(f.end - f.start == 1 && normWords[f.start] in cardTypeWords &&
                (normWords.getOrNull(f.start - 1) in indefiniteWords || normWords.getOrNull(f.start - 1) in countWords || normWords.getOrNull(f.start - 1) in setOf("more", "other", "fewer") || Regex("""^\d+$""").matches(normWords.getOrNull(f.start - 1) ?: ""))) }
            // "my graveyard has seven cards": a number word on its own is a count. It once reached a card and the
            // count went unread, so the graveyard the asker described wasn't there.
            .filter { f -> !(f.end - f.start == 1 && normWords[f.start] in countWords) }
            // "a Charge counter", "two Shield tokens": the word before "counter"/"token" names the kind, not a card.
            // Unless the word is the verb: "their Counterspell counters it" is a spell doing something, not a kind of counter.
            .filter { f -> !(f.end - f.start == 1 && normWords.getOrNull(f.end) in setOf("token", "tokens", "counter", "counters") && normWords[f.start] !in short &&
                !(normWords.getOrNull(f.end) in setOf("counters", "tokens") && normWords.getOrNull(f.end + 1) in setOf("it", "that", "them", "this", "my", "their", "the", "his", "her", "its"))) }
            // "can they redirect it?", "they remove my Bears with Swords to Plowshares": a card name used as a
            // verb on an object is the verb. "Remove" turned up as a card in an answer about Swords to Plowshares,
            // and the spell the asker did name never resolved.
            .filter { f -> !(f.end - f.start == 1 && normWords[f.start] in verbsBeforeAnObject && normWords[f.start] !in short &&
                normWords.getOrNull(f.end) in setOf("it", "that", "them", "this", "my", "their", "his", "her", "the", "a", "an", "its",
                    "one", "two", "three", "four", "five", "all", "both", "counters", "counter", "damage", "half")) }
            // "I fire off its ability", "I turn on my Mutavault": a verb before a particle is a verb, not the card
            // of that name — "Fire // Ice" made "fire off" into a card being cast.
            .filter { f -> !(f.end - f.start == 1 && normWords[f.start] in verbsBeforeParticle && normWords.getOrNull(f.end) in setOf("off", "on", "out", "up", "down", "away", "back")) }
            // "I tutor for Lightning Bolt", "I fetch for a land": a verb before "for" is a verb, even when a card
            // named in full in the same question (Demonic Tutor) makes it a short name.
            .filter { f -> !(f.end - f.start == 1 && normWords[f.start] in verbsBeforeFor && normWords.getOrNull(f.end) == "for") }
            // "to protect it", "to save my Bears": a verb after "to" is a verb, not the card of that name.
            .filter { f -> !(f.end - f.start == 1 && normWords.getOrNull(f.start - 1) == "to" && normWords[f.start] in verbsAfterTo && normWords[f.start] !in short) }
            // "protection from black", "a black creature", "a red card": a colour word describes something here,
            // it doesn't name the card whose name starts with it. Black Knight on the board turned "protection from
            // black" into "protection from Black Knight", and the creature then had protection from nothing at all.
            .filter { f -> !(f.end - f.start == 1 && normWords[f.start] in colorWords &&
                ((normWords.getOrNull(f.start - 1) == "from" && (maxOf(0, f.start - 4) until f.start).any { normWords[it] == "protection" }) ||
                 normWords.getOrNull(f.end) in colorNouns)) }
            // "a 2/2 with lifelink and deathtouch", "a 2/2 lifelink": a keyword in a keyword list, or straight
            // after the size, is a keyword and not the card of that name.
            .filter { f -> !(f.end - f.start == 1 && normWords[f.start] in keywordWords && normWords[f.start] !in short &&
                (normWords.getOrNull(f.start - 1) in setOf("with", "and", "&", "gains", "gain", "has", "have", "granted") || Regex("""^\d+(?:/\d+)?$""").matches(normWords.getOrNull(f.start - 1) ?: "") || Regex("""^\d+/\d+$""").matches(rawWords.getOrNull(f.start - 1) ?: ""))) }
            // A first name that could mean several cards ("Jace") means the one named in full earlier, however it was matched.
            .map { f -> if (f.end - f.start == 1 && f.entry.alternatives.isNotEmpty()) short[normWords[f.start]]?.let { f.copy(entry = it) } ?: f else f }
            // "Urza's Tower, Mine and Power Plant": once a card whose name starts with a possessive is named,
            // the same possessive is tried on the names that follow it. Without this "Mine" was a clause of its
            // own and "Power Plant" was taken for a player's name.
            .let { fs ->
                val prefixes = fs.mapNotNull { f -> Regex("""^(\w+ s)\b""").find(Names.normalize(f.entry.display))?.groupValues?.get(1) }.distinct()
                if (prefixes.isEmpty()) fs else {
                    val taken = fs.flatMap { it.start until it.end }.toMutableSet()
                    val extra = mutableListOf<NameIndex.Found>()
                    var i = 0
                    while (i < normWords.size) {
                        if (i in taken) { i++; continue }
                        var hit: NameIndex.Found? = null
                        for (len in minOf(3, normWords.size - i) downTo 1) {
                            if ((i until i + len).any { it in taken }) continue
                            val span = (i until i + len).joinToString(" ") { normWords[it] }
                            val e = prefixes.firstNotNullOfOrNull { p -> names.lookup("$p $span")?.takeIf { it.isCard } }
                            if (e != null) { hit = NameIndex.Found(i, i + len, e); break }
                        }
                        if (hit != null) { extra += hit; (hit.start until hit.end).forEach { taken += it }; i = hit.end } else i++
                    }
                    fs + extra
                }
            }
            // "I cast Explore": a keyword-action word is kept out of the name index so the grammar can use it
            // ("the creature explores"), but a capital right after a cast verb means the card of that name.
            .let { fs -> fs + normWords.indices.filter { i -> i !in fs.flatMap { it.start until it.end }.toSet() &&
                    normWords.getOrNull(i - 1) in castWordsBeforeAName && rawWords[i].firstOrNull()?.isUpperCase() == true }
                .mapNotNull { i -> names.lookup(normWords[i])?.takeIf { it.isCard }?.let { NameIndex.Found(i, i + 1, it) } } }
            .sortedBy { it.start }
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

    // "my opponent's Bears": the clause opens with "my" but the player it names is the opponent, and taking the
    // "my" off left a possessive ("opponent's …") that the rules' own owner lists didn't have.
    private val actorMe = Regex("""^(i've|i'm|i|me|my|we)\b(?!'s)(?! opponent)""")
    private val actorOpp = Regex("""^(?:(they|he|she)'re|(they|he|she)'s(?= )|(my opponent|the opponent|opponent|opp|they|their|he|she|his|her|them))\b(?!'s)""")

    private fun actorOfClause(c: String): String? = when { actorOpp.containsMatchIn(c) -> "opp"; actorMe.containsMatchIn(c) -> "me"; Regex("""^@(\w+)""").containsMatchIn(c) -> Regex("""^@(\w+)""").find(c)!!.groupValues[1]; else -> null }

    /**
     * Who "they"/"opponent" means. With no named players it's the one opponent. With named players, "they/he/she"
     * is the last named player who acted, and "opponent" is the single named player other than me, if there is one.
     */
    /** The possessives a card name can be introduced by: "my Bears", "my opponent's Bears", "Alice's Bears". */
    // Longest first: "my " would otherwise win against "my opponent's " and give the wrong player.
    private val possPrefix = """(?:my opponent's |the opponent's |an opponent's |opponent's |@\w+'s |my |their |his |her |the |own )"""
    /** Who such a possessive points at, or null when it names nobody ("the Bears"). */
    private fun possessiveOwner(prefix: String, ctx: Ctx, m: Marked? = null): String? {
        val raw = prefix.trim()
        // "@alice's library": a named player, who may not have been registered yet by anything else in the sentence.
        if (raw.startsWith("@")) return raw.removePrefix("@").removeSuffix("'s").also { ctx.players.putIfAbsent(it, m?.players?.get(it) ?: it) }
        return when (val w = raw.removeSuffix("'s")) {
            "my", "own", "i" -> "me"
            "their", "his", "her" -> pronounPlayer(ctx, "their")
            "my opponent", "the opponent", "opponent", "an opponent" -> pronounPlayer(ctx, "opponent")
            "", "the", "a", "an" -> null
            else -> w.takeIf { it in ctx.players }
        }
    }

    private fun pronounPlayer(ctx: Ctx, word: String = "opponent"): String {
        if (ctx.players.isEmpty()) { ctx.usesOpp = true; return "opp" }
        val pronoun = word in setOf("they", "their", "he", "she", "his", "her", "them")
        if (pronoun) ctx.lastNamed?.let { return it }
        if (ctx.players.size == 1) return ctx.players.keys.first()
        ctx.usesOpp = true; return "opp"
    }

    private fun readSentence(m: Marked, ctx: Ctx): Boolean {
        // Counts written as words become digits here, after card names have been replaced by placeholders, so a
        // card called "Seven Dwarves" is never touched. Every count rule already reads digits; without this
        // "my opponent is at seven life" and "I control six Grizzly Bears" went unread. "One" is left alone: it
        // is a word in its own right ("blocks one of them", "choose one").
        val t0 = wordCounts.replace(m.text.replace(Regex("""\s+"""), " ").trim()) { r -> numberWords.getValue(r.value.lowercase()).toString() }
        if (t0.isEmpty()) return true
        var any = false

        // Turn / life statements (removed from the text once read).
        // "I alpha strike", "I go wide with three 1/1s": table talk for attacking with everything and for having
        // a wide board. Said this way the whole clause went unread, so it is turned into plain words first.
        var t2 = t0.replace(Regex("""\balpha[- ]?strikes\b"""), "attacks").replace(Regex("""\balpha[- ]?strike\b"""), "attack")
            .replace(Regex("""\b(?:go|goes|going|went) wide with\b"""), "have")
            // "hard cast", "board wipe with X", "tutor for X with Y": table talk for casting something.
            .replace(Regex("""\bhard[- ]?casts\b"""), "casts").replace(Regex("""\bhard[- ]?cast\b"""), "cast")
            .replace(Regex("""\bboard[- ]?wipes? with\b"""), "casts").replace(Regex("""\bwipes the board with\b"""), "casts")
            .replace(Regex("""\btutors (?:up )?for (?:an? |the )?c\d+ with\b"""), "casts")
            .replace(Regex("""\btutor (?:up )?for (?:an? |the )?c\d+ with\b"""), "cast")
            // "… with Force of Will pitching a blue card": the alternative cost is its own clause.
            .replace(Regex("""\s+(?:by )?(pitching|exiling) (?=(?:an?|one|two) )"""), ", $1 ")
            // Tense and mood: the judge's answer is the same whether the asker says it happened, has happened,
            // will happen or is happening. Said any way but the plain present, the clause went unread.
            .replace(Regex("""\bcasted\b"""), "cast")
            // "I have cast four spells this turn" is a count the situation states, not four casts to play out;
            // dropping the "have" made it the second, and five Aetherflux triggers instead of one.
            .replace(Regex("""\b(?:has|have|had) (?!cast (?:a|an|one|two|three|four|five|six|seven|eight|nine|ten|\d+)(?: other| more)? spells?\b)(?=(?:cast|played|attacked|blocked|activated|targeted|countered|killed|destroyed|exiled|sacrificed|bounced|drawn|discarded|tapped|untapped)\b)"""), "")
            .replace(Regex("""\bwill (?=(?:cast|play|attack|block|activate|target|counter|kill|destroy|exile|sacrifice|bounce|draw|discard|tap|untap|gain|lose|deal|take|die|trigger|remove|ping|nuke|zap)\b)"""), "")
            .replace(Regex("""\b(?:is|are|'s|'re) casting\b"""), "casts")
            .replace(Regex("""\b(?:i am|i'm|we are|we're) drawing\b"""), "i draw")
            .replace(Regex("""\b(?:am i|are we) losing\b"""), "do i lose").replace(Regex("""\b(?:are they|is he|is she) losing\b"""), "do they lose")
            // "at the beginning of my upkeep", "my upkeep comes around": the step, said the long way round.
            .replace(Regex("""^at the (?:beginning|start) of (my|their|his|her|the) """), "during $1 ")
            .replace(Regex("""\b(my|their|his|her) (upkeep|end step|draw step|main phase|combat|untap step) (?:comes around|rolls around|arrives|begins|starts)"""), "it is $1 $2")
            .replace(Regex("""\b(?:is|are|'s|'re) drawing\b"""), "draws")
            .replace(Regex("""\b(?:takes?|took) (an?|one|two|three|\d+) cards? off the top\b"""), "draws $1 card")
            .replace(Regex("""\b(draws?|drew) one\b(?!\s+card)"""), "$1 a card")
            .replace(Regex("""\breturns? ((?:$possPrefix)?c\d+) to (?:my|their|its owner's|the owner's|his|her) hand with ((?:$possPrefix|an? )?c\d+)"""), "bounces $1 with $2")
            // "they point Doom Blade at my Bears", "they use Doom Blade on it", "Doom Blade targets my Bears",
            // "Doom Blade is cast on my Bears": more ways to say a spell was cast at something.
            .replace(Regex("""\b(?:points?|pointed|aims?|aimed) ((?:$possPrefix|an? )?c\d+) (?:at|on|against|targeting) """), "casts $1 targeting ")
            .replace(Regex("""^((?:$possPrefix|an? )?c\d+) (?:is|was|gets?|got) (?:being )?cast (?:on|at|targeting) """), "casts $1 targeting ")
            .replace(Regex("""^((?:$possPrefix|an? )?c\d+) targets? """), "casts $1 targeting ")
            // "I attacked", "I am attacking", "I declare Bears as an attacker", "I turn Bears sideways",
            // "I send Bears at my opponent": more ways to declare the same attack.
            .replace(Regex("""\b(i|we|they|he|she|you|my opponent|the opponent|@\w+|c\d+) attacked (?=(?:with|it|that|them|me|the|an?|my|their|his|her|c\d+)\b)"""), "$1 attacks ")
            .replace(Regex("""\b(i|we|they|he|she|you|my opponent|the opponent|@\w+|c\d+) countered (?=(?:it|that|them|the|an?|my|their|his|her|c\d+)\b)"""), "$1 counters ")
            .replace(Regex("""\b(?:fizzles?|fizzled) (?=(?:it|that|them|the|an?|my|their|his|her|c\d+)\b)"""), "counters ")
            .replace(Regex("""\b(?:i am|i'm|we are|we're) attacking\b"""), "i attack")
            // "their 5/5 hits me" / "the token connects with me": a creature connecting is an attack nothing
            // blocked. Only a described creature, never a card: "Lightning Bolt hits my opponent" is a spell,
            // and so is "I hit my opponent for 3 with Lightning Bolt".
            .replace(Regex("""\b((?:\d+/\d+|creatures?|tokens?|dudes?|guys?|beaters?)s?)\s+(?:hits|hit|connects with|connected with)\s+(me|them|him|her|my opponent|the opponent|@\w+)\b(?!(?:\s+for \d+)?\s+with\b)"""), "$1 attacks $2 unblocked")
            // "it hits them" / "they connect with me": the same, with the creature said as a pronoun.
            .replace(Regex("""\b(it|they)\s+(?:hits|hit|connects with|connected with)\s+(me|them|him|her|my opponent|the opponent|@\w+)\b(?!(?:\s+for \d+)?\s+with\b)"""), "$1 attacks $2 unblocked")
            .replace(Regex("""\b(?:they are|they're|he is|he's|she is|she's) attacking\b"""), "they attack")
            .replace(Regex("""\bdeclares? ((?:$possPrefix|an? )?c\d+) as an attacker\b"""), "attacks with $1")
            .replace(Regex("""\bturns? ((?:$possPrefix|an? )?c\d+) sideways\b"""), "attacks with $1")
            .replace(Regex("""\bsends? ((?:$possPrefix|an? )?c\d+) (?:at|into|after) """), "attacks $1 at ")
            // "I blocked", "I declared Hill Giant as a blocker", "I throw Hill Giant in front of their Bears":
            // more ways to say a block that only the plain present tense was read from.
            .replace(Regex("""\b(i|we|they|he|she|you|my opponent|the opponent|@\w+|c\d+) blocked (?=(?:it|that|them|the|an?|one|two|three|another|both|all|my|their|his|her|with|\d+/\d+|c\d+)\b)"""), "$1 blocks ")
            .replace(Regex("""\bdeclares? ((?:$possPrefix|an? )?c\d+) as a blocker(?: on| against| for)? """), "$1 blocks ")
            .replace(Regex("""\b(?:throws?|threw|puts?|drops?|chumps?) ((?:$possPrefix|an? )?c\d+) in (?:front of|the way of) """), "$1 blocks ")
            // "I activated it", "I turn on my Elves", "I fire off its ability", "Llanowar Elves taps for mana":
            // more ways to say the same activation.
            .replace(Regex("""\b(i|we|they|he|she|you|my opponent|the opponent|@\w+) activated (?=(?:it|that|them|the|an?|my|their|his|her|c\d+)\b)"""), "$1 activates ")
            .replace(Regex("""\b(i|we|they|he|she|you|my opponent|the opponent|@\w+) tapped (?=(?:it|that|them|the|an?|my|their|his|her|c\d+)\b)"""), "$1 taps ")
            .replace(Regex("""\b(?:turns? on|fires? off|sets? off) (?=(?:$possPrefix)?c\d+)"""), "activates ")
            .replace(Regex("""^((?:$possPrefix)?c\d+) taps for (mana|\{)"""), "taps $1 for $2")
            // "I hit my opponent for 3 with Bolt", "my opponent takes 3 from Bolt", "Bolt hits my opponent":
            // more ways to say a spell was aimed at somebody.
            .replace(Regex("""\b(?:deals?|dealt|hits?|hit|burns?|burned|pings?|zaps?) (me|them|my opponent|the opponent|@\w+|(?:my |their )?face)(?: for)? \d+(?: damage)? with ((?:$possPrefix|an? )?c\d+)"""), "casts $2 targeting $1")
            .replace(Regex("""\b(?:deals?|dealt) \d+ damage to (me|them|my opponent|the opponent|@\w+) with ((?:$possPrefix|an? )?c\d+)"""), "casts $2 targeting $1")
            .replace(Regex("""\b(me|they|them|my opponent|the opponent|@\w+) (?:takes?|took) \d+(?: damage)? from ((?:$possPrefix|an? )?c\d+)"""), "casts $2 targeting $1")
            .replace(Regex("""\b(me|them|my opponent|the opponent|@\w+) (?:is|are|was|were|gets?|got) dealt \d+ damage by ((?:$possPrefix|an? )?c\d+)"""), "casts $2 targeting $1")
            .replace(Regex("""\b(?:throws?|threw|chucks?|lobs?) ((?:$possPrefix|an? )?c\d+) at """), "casts $1 targeting ")
            // "it does 3 damage to them": the same as "deals", and on its own it opens with a question word,
            // which the noise filter would drop.
            .replace(Regex("""\b(?:does|do|did) (\d+) damage\b"""), "deals $1 damage")
            // "Grizzly Bears which dies", "the Bolt that gets countered": a relative clause about something that
            // happens to the thing is a second statement about it. Only event verbs, so "a creature that has
            // flying" stays a description.
            .replace(Regex("""\s*,?\s*\b(?:which|that)\s+(?=(?:dies|died|is destroyed|is exiled|is countered|is sacrificed|gets destroyed|gets exiled|gets countered|is killed)\b)"""), " and it ")
            .replace(Regex("""^((?:$possPrefix|an? )?(c\d+)) (?:hits?|burns?|connects? with|connected with) (?=(?:me|them|my opponent|the opponent|@\w+|my face|their face)\b)""")) { r ->
                if (isCreatureName(m.cards[r.groupValues[2]]?.display)) "${r.groupValues[1]} attacks " else "casts ${r.groupValues[1]} targeting "
            }
            // "I lose my Bears", "my Bears hits the bin", "my Bears is put into my graveyard": more ways to say
            // a permanent died, and "I sacrificed it" / "I throw it away" for the sacrifice.
            .replace(Regex("""\b(?:i|we|they|he|she|my opponent|the opponent|@\w+) loses? ((?:$possPrefix)?c\d+)(?!\w)"""), "$1 dies")
            .replace(Regex("""((?:$possPrefix)?c\d+) (?:hits the bin|hits the yard|bites it|bites the dust|eats it)"""), "$1 dies")
            .replace(Regex("""((?:$possPrefix)?c\d+) (?:is|are|was|were|gets?|got) put into (?:my |their |its owner's |the )?graveyard"""), "$1 dies")
            .replace(Regex("""\b(i|we|they|he|she|you|my opponent|the opponent|@\w+) sacrificed (?=(?:it|that|them|the|an?|my|their|his|her|c\d+)\b)"""), "$1 sacrifices ")
            .replace(Regex("""\b(?:throws?|threw|chucks?) ((?:$possPrefix)?c\d+) away"""), "sacrifices $1")
            // "my life total goes up by 3", "I go up 3": a life change said as a total rather than a gain.
            .replace(Regex("""\bmy life total (?:goes up|rises|increases)(?: by)? (\d+)(?: life)?"""), "i gain $1 life")
            .replace(Regex("""\bmy life total (?:goes down|drops|falls|decreases)(?: by)? (\d+)(?: life)?"""), "i lose $1 life")
            .replace(Regex("""\b(?:their|his|her) life total (?:goes up|rises|increases)(?: by)? (\d+)(?: life)?"""), "they gain $1 life")
            .replace(Regex("""\b(?:their|his|her) life total (?:goes down|drops|falls|decreases)(?: by)? (\d+)(?: life)?"""), "they lose $1 life")
            .replace(Regex("""\bi go up (\d+)(?: life)?"""), "i gain $1 life").replace(Regex("""\bi go down (\d+)(?: life)?"""), "i lose $1 life")
            // "Grizzly Bears hits the battlefield", "I drop it", "I put it onto the battlefield": more ways to say
            // a permanent arrived, and more ways to say something happens while a spell is still on the stack.
            .replace(Regex("""((?:$possPrefix)?c\d+) hits the (?:battlefield|table|board)"""), "$1 enters the battlefield")
            .replace(Regex("""\bdrops? ((?:$possPrefix|an? )?c\d+)(?!\w)"""), "casts $1")
            // Not after "to", and not when a trigger or an ability is the subject putting it there: those name
            // the effect doing the work, and rewriting them away loses it.
            .replace(Regex("""(?<!to )(?<!trigger )(?<!ability )\bputs? ((?:$possPrefix|an? )?c\d+) (?:onto|on to|into) (?:the battlefield|play)(?!\s+(?:with|using|off|from)\b)"""), "$1 enters the battlefield")
            .replace(Regex("""^with (?:the |an? |my |their )?c\d+ (?:still )?on the stack,? """), "in response ")
            .replace(Regex("""^before (?:it|that|the spell) resolves,? """), "in response ")
            // "I attack with an 8/8 trampler into a 2/2 blocker": the thing attacked into is the blocker.
            .let { t0 -> Regex("""\b(attacks?|attacking|swings?|swinging)((?: with)? .+?) into ((?:an? |the |their |his |her )?(?:untapped |tapped |fresh )?(?:\d+/\d+|c\d+)(?:\s+(?!blocker)[a-z]+)*)(?:\s+blockers?)?(?=[.,]|$)""").replace(t0) { r ->
                r.groupValues[1] + r.groupValues[2] + ", they block with " + r.groupValues[3].replace(Regex("""\b(?:untapped|fresh) """), "")
            } }
            // "a 4/4 attacks my 2/2": the attacker is said without an owner, but a creature of mine blocking it
            // makes it the opponent's.
            .replace(Regex("""\b(?:an?|the) ((?:\d+/\d+|c\d+)(?:\s+(?!attacks?\b|attacking\b|swings?\b|swinging\b)[a-z]+)*\s+(?:attacks?|attacking|swings?|swinging) (?:my|our) )"""), "their $1")
            // "their 4/4 attacks my 2/2": nobody attacks a creature, so the creature named after the verb is the
            // blocker. Whose it is says who blocks. A planeswalker really can be attacked, so a named card only
            // reads this way when it is a creature.
            .let { t0 -> Regex("""\b(attacks?|attacking|swings?|swinging) (my|our|their|his|her|the opponent's|my opponent's) ((?:untapped |tapped |fresh )?(?:(\d+/\d+)|(c\d+))(?:\s+(?!blockers?\b|and\b|or\b|plus\b|but\b)[a-z]+)*)(?:\s+blockers?)?(?=[.,]|$)""").replace(t0) { r ->
                val spec = r.groupValues[3].replace(Regex("""\b(?:untapped|fresh) """), "")
                val isCreature = r.groupValues[4].isNotEmpty() || m.cards[r.groupValues[5]]?.typeLine?.contains("Creature", true) == true
                val mine = r.groupValues[2] == "my" || r.groupValues[2] == "our"
                if (!isCreature) r.value
                else r.groupValues[1] + if (mine) ", i block with $spec" else ", they block with $spec"
            } }
            // "when it connects", "if my Skirge connects": table talk for dealing combat damage to a player.
            .replace(Regex("""\b(?:when|if|after) ((?:their |his |her )c\d+) connects\b"""), "and $1 deals combat damage to me")
            .replace(Regex("""\b(?:when|if|after) ((?:it|that|(?:$possPrefix)?c\d+)) connects\b"""), "and $1 deals combat damage to my opponent")
            .replace(Regex("""\b((?:their |his |her )c\d+) connects\b"""), "$1 deals combat damage to me")
            .replace(Regex("""\b((?:it|that|(?:$possPrefix)?c\d+)) connects\b"""), "$1 deals combat damage to my opponent")
            // "I attack Alice with a 5/5 and Bob with a 3/3": two attacks on two players, which the clause splitter
            // can only see once the second one says "attacks" too.
            .replace(Regex("""\b(attacks?|swings? at) ((?:@\w+|me|them|my opponent|the opponent)) with (.+?) and ((?:@\w+|me|them|my opponent|the opponent)) with """), "$1 $2 with $3, $1 $4 with ")
            // "I attack with a 4/4 at Alice": the player named after the attacker rather than before it.
            .replace(Regex("""\b(attacks?|swings?)(?: with)? (.+?) (?:at|into) (@\w+|me|them|my opponent|the opponent)(?=[.,]|$)"""), "$1 $3 with $2")
            // "cast Mind Twist for 2 at Alice": the amount said before the target, where the grammar wants it after.
            .replace(Regex("""\b(casts?|plays?) ((?:$possPrefix|an? )?c\d+) (for \d+|with x ?= ?\d+|for x ?(?:=|equals|of) ?\d+) ((?:at|targeting|on|against) .+)$"""), "$1 $2 $4 $3")
            // "I play it as my land for turn": the land drop said the way players say it.
            .replace(Regex("""\s+as (?:my|their|his|her|the) land (?:for|of) (?:the )?turn\b"""), "")
            .replace(Regex("""\s+(?:as|for) (?:my|their|his|her) land drop\b"""), "")
            // "I cast Serra Angel with haste": a keyword the asker says it has, not a way of casting it.
            .replace(Regex("""\b(casts?|plays?) ((?:$possPrefix|an? )?c\d+) with ((?:haste|flying|trample|lifelink|deathtouch|vigilance|first strike|double strike|menace|hexproof|indestructible|reach))\b"""), "$1 $2, $2 has $3")
            // "what's its power?" / "whats my life total?": the contraction, written either way. Spelled out once
            // here, every "what is …" question below reads it without carrying the two spellings itself.
            .replace(Regex("""\bwhat'?s\b""", RegexOption.IGNORE_CASE), "what is")
            // "is it legal to bolt it?" / "am I allowed to block?": the question is about the action, so it is
            // read as the asker taking it and the answer says whether it works.
            .replace(Regex("""^(?:is it (?:legal|ok|okay|allowed|fine) (?:to|for me to)|am i allowed to|may i|can i legally|would it be legal to) """, RegexOption.IGNORE_CASE), "can i ")
            // "My creature blocks", "their guy blocks": the same, for a block with no attacker named.
            .let { t0 -> Regex("""\b(my opponent's|the opponent's|opponent's|their|his|her|my|the) (creature|guy|dude|token) (?:blocks|block|is blocking|blocked)\b(?! (?:my|their|his|her|the|an?|it|that|\d)\b)""", RegexOption.IGNORE_CASE).replace(t0) { r ->
                val theirs = Regex("""^(?:my opponent's|the opponent's|opponent's|their|his|her)$""", RegexOption.IGNORE_CASE).matches(r.groupValues[1])
                (if (theirs) "they block with a " else "i block with a ") + r.groupValues[2]
            } }
            // "My opponent's creature attacks" / "my guy attacks": a creature given by nothing but the word.
            .let { t0 -> Regex("""\b(my opponent's|the opponent's|opponent's|their|his|her|my|the) (creature|guy|dude|token) (?:attacks|attack|is attacking|attacked)\b""", RegexOption.IGNORE_CASE).replace(t0) { r ->
                val theirs = Regex("""^(?:my opponent's|the opponent's|opponent's|their|his|her)$""", RegexOption.IGNORE_CASE).matches(r.groupValues[1])
                (if (theirs) "they attack with a " else "i attack with a ") + r.groupValues[2]
            } }
            // "a 2/2 that has a +1/+1 counter and lifelink": the relative clause describes the creature, the same
            // way "with" does. Left as it was, the whole clause went unread.
            .replace(Regex("""\b(\d+/\d+|creature|token) (?:that|which) (?:has|have|carries) """), "$1 with ")
            // "with a +1/+1 counter and lifelink": the counter is read at the end of the list, so a keyword after
            // it went unread. Said the other way round ("with lifelink and a +1/+1 counter") it already worked.
            .let { t0 -> Regex("""\bwith (an? |one |two |three |\d+ )?([+-]\d+/[+-]\d+) counters?(?: on it)? and ($kwPhrase(?:(?:,|,? and) $kwPhrase)*)""", RegexOption.IGNORE_CASE).replace(t0) { r ->
                "with ${r.groupValues[3]} and ${r.groupValues[1].ifEmpty { "a " }}${r.groupValues[2]} counter"
            } }
            // "I remove a counter to ping their 1/1": removing the counter is the ability's cost, so the whole
            // thing is one activation aimed at what it names (Walking Ballista, Hangarback Walker).
            .let { t0 -> Regex("""\bremoves? (?:an?|one|two|three|\d+)(?: (?:[+-]\d+/[+-]\d+ |loyalty |charge )?counters?)?(?: from (?:(?:my |their |the )?(c\d+)|it|that))? to (?:ping|shoot|hit|kill|damage|snipe|zap|burn|finish off) """).replace(t0) { r ->
                "activates " + r.groupValues[1].ifEmpty { "it" } + " targeting "
            } }
            // "I give it +3/+3", "they pump their blocker +2/+2": a size bonus handed over by a player, with no
            // card named. "Mine" and "theirs" are the creature of that player's already in the combat.
            .let { t0 -> Regex("""\b(i|we|they|he|she|my opponent|the opponent|@\w+) (?:gives?|gave|grants?|granted|pumps?|pumped) (mine|theirs|it|that|(?:$possPrefix)?(?:c\d+|\d+/\d+|creature)) ([+-]\d+/[+-]\d+)""").replace(t0) { r ->
                // Nobody pumps the other player's creature, so "it" is the giver's own — after a block it would
                // otherwise be the last one named, which is whichever creature the other player just declared.
                val mine = r.groupValues[1] == "i" || r.groupValues[1] == "we"
                (when (r.groupValues[2]) { "mine" -> "my creature"; "theirs" -> "their creature"
                                           "it", "that" -> if (mine) "my creature" else "their creature"
                                           else -> r.groupValues[2] }) + " gets " + r.groupValues[3]
            } }
            // "I give my Bears protection from red", "they grant it flying": a keyword handed to a permanent by a
            // player, with no card named. Where a card grants it ("I give it protection" off Mother of Runes)
            // the activation says so, so this only fires when the permanent is named.
            .replace(Regex("""\b(?:i|we|they|he|she|my opponent|the opponent|@\w+) (?:gives?|gave|grants?|granted) ((?:$possPrefix)(?:c\d+|\d+/\d+|creature)) (?=(?:$kwPhrase)\b)"""), "$1 has ")
            // "suppose they …", "say they …", "what if they …": a hypothetical is the same question.
            .replace(Regex("""^(?:so|ok|okay|alright|all right|hey|hi|right|well|anyway|anyways|actually)[,:]? +""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:quick question|question|my question is|one more|another one|new question|a question)[,:]? +(?:whether |if |about )?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:just to check|just checking|to check|to be clear|for clarity)[,:]? +""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:can you (?:tell me|explain|say)|tell me|explain|i'd like to know|i would like to know|i want to know|i wonder|wondering)[,:]? +(?:what happens |whether |if |about )?(?:when |if )?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:is it true that|is it right that|am i right that|what's the ruling (?:when|if|on)|what is the ruling (?:when|if|on)|how does it work (?:when|if))[,:]? +""", RegexOption.IGNORE_CASE), "")
            // "…, both die right?" / "…, correct?" / "…, yeah?": a tag asking for confirmation, not part of the board.
            .let { t0 -> Regex("""\s*[,;]?\s*(?:right|correct|yeah|isn't it|isnt it|is that right|am i right|is that correct|is this correct|true)\s*(\??)$""", RegexOption.IGNORE_CASE).replace(t0) { r -> r.groupValues[1] } }
            .replace(Regex("""\?\s*(?:yes or no|y/n)\?*$""", RegexOption.IGNORE_CASE), "?")
            .replace(Regex("""\s*[,.]?\s*(?:yes or no|y/n)\?*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:suppose|say|let's say|lets say|imagine|assume|what if|hypothetically,?) (?:that )?""", RegexOption.IGNORE_CASE), "")
            // "my 1/1 survives a Shock": the statement form of "does my 1/1 survive a Shock?".
            .replace(Regex("""^((?:$possPrefix|an? )(?:c\d+|\d+/\d+|creature|guy|dude)[a-z0-9/+ ]*?) (dies|survives|lives)((?: to| against)? (?:an? |the )?c\d+)\.?$""", RegexOption.IGNORE_CASE), "does $1 $2$3")
            // "…, they Doom Blade it, it dies?" / "…, both die right?": a bare "it dies" at the very end of a
            // sentence that already said what happened is the question, not one more thing that happens. Read as
            // an action it killed the creature itself, before the spell aimed at it could.
            .let { t0 -> Regex("""(?:(?<=[,;] )|^)(it|that|they|both|both of them) (dies|die|survives|survive|lives|live)$""", RegexOption.IGNORE_CASE).replace(t0) { r ->
                // On its own it is only a question once something has already happened: "my 2/2 dies" said first
                // is the player telling the engine that it died.
                if (r.range.first == 0 && ctx.events.isEmpty()) r.value
                else {
                    val subj = r.groupValues[1].lowercase()
                    (if (subj == "it" || subj == "that") "does " else "do ") + subj + " " + r.groupValues[2].lowercase().removeSuffix("s")
                }
            } }
            // "Who wins the fight between Grizzly Bears and Hill Giant?": a fight with no card making it happen.
            // The "and" would otherwise break the sentence and leave the second creature as a clause of its own.
            .replace(Regex("""^(?:who|which(?: creature| one)?) (?:wins|survives|comes out on top|lives)(?: the fight| a fight| in a fight| the combat)?(?: (?:between|with)) ((?:$possPrefix|an? )?c\d+) (?:and|vs\.?|versus) ((?:$possPrefix|an? )?c\d+)\??$"""), "$1 fights $2, does $1 die, does $2 die")
            .replace(Regex("""^(?:what happens )?(?:if|when) ((?:$possPrefix|an? )?c\d+) fights ((?:$possPrefix|an? )?c\d+)\??$"""), "$1 fights $2, does $1 die, does $2 die")
            // "Does Doom Blade kill Serra Angel?" / "will my Bears die to Lightning Bolt?": a card against a card,
            // with nothing else said. The spell is cast at the creature and the question is whether it dies.
            // "Is Lightning Bolt enough to kill a Serra Angel?" asks the same thing the long way round.
            .replace(Regex("""^(?:is|are|will|would) ((?:$possPrefix)?c\d+) (?:be )?(?:enough|good enough|able|sufficient) to (?:kill|destroy|finish off) (?:$possPrefix|an? )?(c\d+)\??$"""), "does $1 kill $2")
            .replace(Regex("""^(?:does|do|will|would|can|could) ((?:$possPrefix)?c\d+) (?:kill|destroy|finish off) ((?:$possPrefix)?c\d+)\??$"""), "i cast $1 targeting their $2, does their $2 die")
            .replace(Regex("""^(?:will|would|does|do|is|are|can|could) ((?:$possPrefix)?c\d+) (?:die|be killed|be destroyed) to ((?:$possPrefix|an? )?c\d+)\??$"""), "they cast $2 targeting $1, does $1 die")
            // "Can Serra Angel block Grizzly Bears?": the second card is the attacker, whichever way round the
            // sentence puts them. Without this the first card was read as attacking and combat ran backwards.
            .replace(Regex("""^((?:$possPrefix)?c\d+) can block ((?:$possPrefix)?c\d+)\??$"""), "can $1 block $2")
            .let { t0 -> Regex("""^can ($possPrefix)?(c\d+) block ($possPrefix)?(c\d+)\??$""").replace(t0) { r ->
                val blk = r.groupValues[1].ifEmpty { "my " }; val atk = r.groupValues[3].ifEmpty { "their " }
                "$atk${r.groupValues[4]} attacks, can $blk${r.groupValues[2]} block it"
            } }
            // "how much damage does it deal when it attacks unblocked?": the "when" clause is the situation.
            .replace(Regex("""^what happens when (it|they|(?:$possPrefix)?c\d+) attacks?( unblocked| unopposed)?\??$"""), "$1 attacks$2")
            .replace(Regex("""\bwhen (it|they|(?:$possPrefix)?c\d+) attacks?( unblocked| unopposed)?\b"""), "and $1 attacks$2")
            // "Does Torpor Orb stop Mulldrifter?" / "how does Kalitas interact with Wrath of God?": a question about
            // two cards with no situation around them. One reading is picked and said in the notes.
            .let { t0 -> Regex("""^(?:does|do|can|could|will|would) ($possPrefix)?(c\d+) (?:stop|shut off|turn off|beat|answer) ($possPrefix)?(c\d+)\??$""").replace(t0) { r ->
                ctx.notes += "Read as: your opponent controls the first card and you cast the second; say the situation outright for a different one."
                "my opponent controls ${r.groupValues[2]} and i cast ${r.groupValues[4]}"
            } }
            .let { t0 -> Regex("""^how (?:does|do) ($possPrefix)?(c\d+) (?:interact with|work with|work against|play with) ($possPrefix)?(c\d+)\??$""").replace(t0) { r ->
                ctx.notes += "Read as: you control the first card and cast the second; say the situation outright for a different one."
                "i control ${r.groupValues[2]} and i cast ${r.groupValues[4]}"
            } }
            // "Does Pacifism stop Serra Angel from attacking?": an Aura is on the creature, anything else is just
            // on the battlefield beside it, and the question is whether the creature can attack or block.
            .let { t0 -> Regex("""^does ($possPrefix)?(c\d+) (?:stop|prevent|keep) ($possPrefix)?(c\d+) from (attacking|blocking)\??$""").replace(t0) { r ->
                val aura = m.cards[r.groupValues[2]]?.typeLine?.contains("Aura", true) == true
                val verb = if (r.groupValues[5] == "attacking") "attack" else "block"
                if (aura) "i control ${r.groupValues[4]} enchanted with ${r.groupValues[2]}, can ${r.groupValues[4]} $verb"
                else "i control ${r.groupValues[2]} and ${r.groupValues[4]}, can ${r.groupValues[4]} $verb"
            } }
            // "Can Doom Blade target Black Knight?": cast it and the answer says whether the target is legal.
            .let { t0 -> Regex("""^can ($possPrefix)?(c\d+) target ($possPrefix)?(c\d+)\??$""").replace(t0) { r ->
                "i cast ${r.groupValues[1]}${r.groupValues[2]} targeting ${r.groupValues[3].ifEmpty { "their " }}${r.groupValues[4]}"
            } }
            // "Does my Llanowar Elves tap for mana?": the creature is the subject, but the player is the one who
            // taps it. Said as "can <player> tap it for mana" the question keeps any "the turn it comes down" tail.
            .let { t0 -> Regex("""^(?:does|do|will|would|can|could)(?:n't| not)? (my|our|their|his|her|the) (c\d+|it|that) tap for (?:mana|\{[wubrgc]\}|(?:white|blue|black|red|green|colorless) mana)\b""").replace(t0) { r ->
                val who = if (r.groupValues[1] == "their" || r.groupValues[1] == "his" || r.groupValues[1] == "her") "they" else "i"
                "can $who tap ${r.groupValues[1]} ${r.groupValues[2]} for mana"
            } }
            // "How much damage does a 3/3 with lifelink deal to me?": the creature is attacking, and what it
            // deals is what the answer is about.
            .let { t0 -> Regex("""^how much damage (?:does|do|would|will|can|could) ((?:$possPrefix|an? )?(?:c\d+|\d+/\d+)(?:(?: with)? [a-z+/ ]+?)?) deal (?:to )?(me|us|them|him|her|my opponent|the opponent|@\w+)\??$""").replace(t0) { r ->
                // Dealing it to me makes it the other player's creature, unless the asker already said whose.
                val toMe = r.groupValues[2] == "me" || r.groupValues[2] == "us"
                val spec = if (toMe && Regex("""^(?:an? )""").containsMatchIn(r.groupValues[1])) "their " + r.groupValues[1].replaceFirst(Regex("""^an? """), "") else r.groupValues[1]
                "$spec attacks ${r.groupValues[2]}"
            } }
            // "I blink my creature with a counter on it": the counter is part of the board, not of the blinking.
            .replace(Regex("""\b(i|we|they|he|she|my opponent|the opponent|@\w+) (blinks?|flickers?) ((?:$possPrefix|an? )(?:c\d+|\d+/\d+|creature|guy|dude)) with ((?:an? |one |two |three |\d+ )?(?:[+-]\d+/[+-]\d+ |[a-z]+ )?counters?(?: on it)?)"""), "$3 has $4, $1 $2 it")
            // "My equipped creature dies" / "my enchanted creature": the adjective says what is on it.
            .replace(Regex("""\b((?:$possPrefix|an? )?)equipped (creature|guy|dude|\d+/\d+)\b"""), "$1$2 with an equipment on it")
            .replace(Regex("""\b((?:$possPrefix|an? )?)enchanted (creature|guy|dude|\d+/\d+)\b"""), "$1$2 with an aura on it")
            // "I sacrifice my creature with an Aura on it": the attachment is board, the verb is the action.
            .let { t0 -> Regex("""\b(i|we|they|he|she|my opponent|the opponent|@\w+) (sacrifices?|sacs?|blinks?|flickers?|bounces?|exiles?|destroys?|kills?) ((?:$possPrefix|an? )(?:c\d+|\d+/\d+|creature|guy|dude|token)) with (?:an? |the )?(c\d+|aura|equipment)(?: on it| attached(?: to it)?)?""").replace(t0) { r ->
                // "with X" is the instrument far more often than it is an attachment ("they kill my creature with
                // Doom Blade"), so this only fires for something that really can be attached.
                val word = r.groupValues[4]
                val attachable = word == "aura" || word == "equipment" ||
                    m.cards[word]?.typeLine?.let { it.contains("Aura", true) || it.contains("Equipment", true) } == true
                if (!attachable) r.value
                else "${r.groupValues[3]} has ${if (word == "aura" || word == "equipment") "an $word" else word} on it, ${r.groupValues[1]} ${r.groupValues[2]} it"
            } }
            // "My 2/2 with a Rancor on it dies" / "my creature dies with a Rancor on it": the attachment is part
            // of the board, not of the dying, and said either way round it left the whole clause unread.
            .replace(Regex("""\b((?:$possPrefix|an? )(?:c\d+|\d+/\d+|creature|guy|dude|token)) with (an? |the )?(c\d+|aura|equipment)(?: on it| attached(?: to it)?)? (dies|died|is destroyed|gets destroyed|is sacrificed|is exiled|leaves the battlefield)\b"""), "$1 has $2$3 on it and it $4")
            .replace(Regex("""\b((?:$possPrefix|an? )(?:c\d+|\d+/\d+|creature|guy|dude|token)) (dies|died|is destroyed|gets destroyed|is sacrificed|is exiled|leaves the battlefield) with (an? |the )?(c\d+|aura|equipment)(?: on it| attached(?: to it)?)?"""), "$1 has $3$4 on it and it $2")
            // "My only untapped land is a Mountain": the board, said as what is left rather than what is there.
            .let { t0 -> Regex("""\b(my|our|their|his|her) only untapped (?:land|permanent|mana source|creature) is ((?:an? |the )?c\d+)""").replace(t0) { r ->
                (if (r.groupValues[1] == "my" || r.groupValues[1] == "our") "i have " else "they have ") + r.groupValues[2] + " untapped"
            } }
            // "Can I pay for Counterspell?" / "can I afford it?": the cost answer says whether the mana is there.
            .replace(Regex("""\bcan (?:i|we|they|he|she|my opponent|the opponent|@\w+) (?:pay for|afford) ((?:$possPrefix|an? )?c\d+)\??$"""), "how much does $1 cost")
            // "How much mana do I need for Emrakul?": the cost question said from the payer's side.
            .replace(Regex("""^how (?:much|many)(?: mana)? (?:do|does|would|will) (?:i|we|they|he|she|my opponent|the opponent|@\w+) need(?: to (?:cast|pay for)| for| to pay for)? ((?:$possPrefix|an? )?c\d+)\??$"""), "how much does $1 cost")
            // "…, how much mana is that?" / "how much is that in total?": the mana the speaker can make.
            .replace(Regex("""(?:^|(?<=[,;] ))how (?:much|many)(?: mana)?(?: is| does) (?:that|this|those|these)(?: (?:give me|add up to|come to))?(?: in total| total| all together| altogether)?\??$"""), "how much mana do i have")
            // "How much mana does Cabal Coffers make with six Swamps?": the board said after the question.
            .replace(Regex("""^((?:how (?:much|many)|what|does|do|will|would|can|could|is|are)\b.*?) with (\d+ (?:$possPrefix)?c\d+s?)\??$"""), "i have $2, $1")
            // "Does indestructible save my creature from Doom Blade?": a keyword question asked with no board.
            // It is the same question as giving a creature the keyword and letting the spell at it.
            .replace(Regex("""^does(?:n't| not)? ($kwPhrase) (?:save|protect|defend|help|stop|keep|prevent) (?:my |the |an? )?(creature|guy|dude|\d+/\d+|c\d+)(?: creature)? (?:from|against) (?:an? |the )?(c\d+)\??$"""), "my $2 has $1, they cast $3 on it")
            // "Does protection from green stop a Giant Growth my opponent cast on my creature?": the same question
            // with the spell named first and the creature it is aimed at said at the end.
            .replace(Regex("""^does(?:n't| not)? ($kwPhrase) (?:stop|prevent|beat|handle|help against|do anything about) (?:an? |the )?(c\d+)(?: (?:that )?(?:my opponent|the opponent|they|he|she) (?:casts?|cast|played?|plays)| cast| played)?(?: (?:on|at|targeting|against) (?:my |the |an? )?(?:creature|guy|dude|\d+/\d+|c\d+)(?: creature)?)?\??$"""), "my creature has $1, they cast $2 on it")
            // "can it be hit by Lightning Bolt?": table talk for letting that spell at it.
            .replace(Regex("""\bcan (it|that|him|her|them|(?:my |the |their |his |her )?(?:creature|guy|dude|c\d+|\d+/\d+)) be (?:hit|targeted|killed|destroyed|damaged|burned|bolted) by (?:an? |the )?(c\d+)\??"""), "they cast $2 on $1")
            // "Is Serra Angel able to block?" is "can Serra Angel block?"
            .replace(Regex("""\b(?:is|are) ((?:$possPrefix)?c\d+) able to """), "can $1 ")
            // "What happens if I Doom Blade it?" / "if I block, do I die?": the "if" is the question, not a condition.
            .replace(Regex("""^what happens if """), "")
            .replace(Regex("""^if (?=(?:i|we|they|he|she|my opponent|the opponent|@\w+)\b)"""), "")
            // "How much does Wrath of God cost if my opponent controls Thalia?": the trailing "if" is the board,
            // not a hypothetical. Said last it stayed glued to the question and the whole sentence went unread.
            .replace(Regex("""^((?:how much|how many|what|does|do|will|would|can|could|is|are)\b.*?) if ((?:i|we|they|he|she|my opponent|the opponent|@\w+)\s+(?:controls?|has|have|had|own|owns)\b.*)$"""), "$2, $1")
        // "they use Doom Blade on my Bears": a cast, but only for a card that is cast — "they use Maze on it"
        // names a land whose ability is activated, and reading that as a cast loses the ability entirely.
        t2 = Regex("""\b(?:uses?|used|plays?|played) ((?:an? |the |my |their )?)(c\d+) (?:at|on|against|targeting) """).replace(t2) { r ->
            if (m.cards[r.groupValues[2]]?.typeLine?.let { it.contains("Instant") || it.contains("Sorcery") } == true) "casts ${r.groupValues[1]}${r.groupValues[2]} targeting " else r.value
        }
        // "my Bears is exiled by Swords to Plowshares", "my Bears gets bounced by Unsummon": the passive voice with
        // the spell named. Only for a spell — "destroyed by their Giant" is combat, not a cast.
        t2 = Regex("""((?:$possPrefix)?c\d+) (?:is|are|was|were|gets?|got) (?:bounced|exiled|destroyed|killed|removed) by ((?:an? |the |my |their )?)(c\d+)""").replace(t2) { r ->
            // "by my Vandalblast" says who cast it; without that the cast fell to the other player.
            val by = when (r.groupValues[2].trim()) { "my" -> "i "; "their" -> "they "; else -> "" }
            if (m.cards[r.groupValues[3]]?.isSpellOnly == true) "$by" + "casts ${r.groupValues[2]}${r.groupValues[3]} targeting ${r.groupValues[1]}" else r.value
        }
        // "my Bears gets +3/+3 from Giant Growth": the spell said as the source of the bonus rather than as a cast.
        t2 = Regex("""((?:$possPrefix)?c\d+|it|that) gets? [+-]\d+/[+-]\d+ from ((?:an? |the |my |their )?)(c\d+)""").replace(t2) { r ->
            // "by my Vandalblast" says who cast it; without that the cast fell to the other player.
            val by = when (r.groupValues[2].trim()) { "my" -> "i "; "their" -> "they "; else -> "" }
            if (m.cards[r.groupValues[3]]?.isSpellOnly == true) "$by" + "casts ${r.groupValues[2]}${r.groupValues[3]} targeting ${r.groupValues[1]}" else r.value
        }
        // "it's turn 3" / "on turn 2": the game's turn number.
        Regex("""\b(?:it's|it is|this is|on|during|in) turn (\d+)\b|\bturn (\d+) of the game\b""").find(t2)?.let { r -> ctx.turnNumber = (r.groupValues[1].ifEmpty { r.groupValues[2] }).toInt(); any = true; t2 = t2.removeRange(r.range) }
        Regex("""\b(it's|it is|during|on|in) (my|their|the opponent's|opponent's|my opponent's|@\w+'s) (turn|upkeep|end step|main phase|combat|draw step|beginning of combat)\b""").find(t2)?.let { r ->
            ctx.activePlayer = when { r.groupValues[2] == "my" -> "me"; r.groupValues[2].startsWith("@") -> r.groupValues[2].removePrefix("@").removeSuffix("'s"); else -> "opp" }
            ctx.note(ctx.activePlayer!!); any = true; t2 = t2.removeRange(r.range)
            val step = when (r.groupValues[3]) { "upkeep" -> "upkeep"; "end step" -> "end"; "main phase" -> "precombat_main"; "combat", "beginning of combat" -> "combat"; "draw step" -> "draw"; else -> null }
            if (step != null) ctx.events += EventSpec("step", player = ctx.activePlayer, to = step)
        }
        Regex("""\b(i'm|i am|i'm at|my life is|i have|i've got) (\d+) life\b|^(i'm at|i am at|i'm on|i am on|i'm sitting at) (\d+)$""").find(t2)?.let { ctx.life["me"] = (it.groupValues[2].ifEmpty { it.groupValues[4] }).toInt(); ctx.usesMe = true; any = true; t2 = t2.removeRange(it.range) }
        // "we're both at 20 life", "we are both at 3": one life total for each player.
        Regex("""\b(?:we're|we are|we|both of us|everyone|everybody) (?:both |each |all )?(?:is |are |'re )?(?:at|on) (\d+)(?: life)?\b""").find(t2)?.let {
            val n = it.groupValues[1].toInt(); ctx.life["me"] = n; ctx.life[pronounPlayer(ctx)] = n; ctx.usesMe = true; any = true; t2 = t2.removeRange(it.range)
        }
        // "There are two Grizzly Bears on the battlefield, one mine one theirs": an existential way of saying who
        // has what. With nobody said it is the speaker's side; "one mine, one theirs" puts one on each.
        t2 = Regex("""\bthere(?: is| are|'s| was| were) ((?:an?|one|two|three|four|five|six|\d+) )?((?:$possPrefix|an? )?c\d+)(?: on the battlefield| in play| out)(,? ?(?:and )?(?:one (?:of )?)?(?:mine|yours),? ?(?:and )?(?:one (?:of )?)?(?:theirs|his|hers))?""").replace(t2) { r ->
            val card = r.groupValues[2]
            if (r.groupValues[3].isNotEmpty()) "i control $card and my opponent controls $card" else "i control ${r.groupValues[1]}$card"
        }
        // "is dealt 1 damage twice": two separate hits, which is not the same as one hit for two — a prevention
        // shield stops only one of them — and read as one the second was dropped.
        t2 = Regex("""\b(is dealt|are dealt|was dealt|were dealt|takes?|took|deals?|dealt) (\d+) damage twice\b""").replace(t2) { r ->
            "${r.groupValues[1]} ${r.groupValues[2]} damage and ${if (r.groupValues[1].startsWith("deal")) r.groupValues[1] else "takes"} ${r.groupValues[2]} more damage"
        }
        // "I go to 0 life", "they drop to 3": a life total after something, said as a change.
        Regex("""\b(?:i|we) (?:go|goes|went|drop|drops|dropped|fall|falls|fell) (?:to|down to) (-?\d+)(?: life)?\b""").find(t2)?.let { ctx.life["me"] = it.groupValues[1].toInt(); ctx.usesMe = true; any = true; t2 = t2.removeRange(it.range) }
        Regex("""\b(?:they|he|she|my opponent|the opponent|opponent) (?:go|goes|went|drop|drops|dropped|fall|falls|fell) (?:to|down to) (-?\d+)(?: life)?\b""").find(t2)?.let { ctx.life[pronounPlayer(ctx)] = it.groupValues[1].toInt(); any = true; t2 = t2.removeRange(it.range) }
        Regex("""\s*\b(?:with|at|and) (\d+) life (?:left|remaining|to go)(?: for me| on my side)?\b""").find(t2)?.let { ctx.life["me"] = it.groupValues[1].toInt(); ctx.usesMe = true; any = true; t2 = t2.removeRange(it.range) }
        Regex("""\s*\b(?:with|and) (?:them|my opponent|the opponent|opponent) (?:at|on) (\d+)(?: life)?\b""").find(t2)?.let { ctx.life[pronounPlayer(ctx)] = it.groupValues[1].toInt(); any = true; t2 = t2.removeRange(it.range) }
        Regex("""\b(opponent|they|they're|opp|my opponent|he|he's|she|she's)(?: who)? (?:(?:is at|are at|at|is on|are on|'re at|'s at) (\d+)(?: life)?|(?:has|have) (\d+) life)\b""").find(t2)?.let { ctx.life[pronounPlayer(ctx)] = (it.groupValues[2].ifEmpty { it.groupValues[3] }).toInt(); any = true; t2 = t2.replaceRange(it.range, it.groupValues[1]) }
        // "@alice has 3 cards in hand" is not a life total: without the lookahead it set Alice to 3 life and left
        // "cards in hand" unread, so the answer quietly had her life wrong.
        Regex("""@(\w+),? (?:who |that )?(?:is at|is on|has|at|sits at|is) (\d+)(?: life)?\b(?!\s*(?:cards?|counters?|permanents?|creatures?|lands?|poison|mana|damage))""").findAll(t2).toList().asReversed().forEach { ctx.life[it.groupValues[1]] = it.groupValues[2].toInt(); ctx.players.putIfAbsent(it.groupValues[1], m.players[it.groupValues[1]] ?: it.groupValues[1]); any = true
            // The player stays in the sentence: "Alice casts Bolt at Bob who is at 2 life" still says who it targets.
            t2 = t2.replaceRange(it.range, "@" + it.groupValues[1]) }
        // A life total stated here is the running total a later "and gains 3 more" adds to.
        if (ctx.life.isNotEmpty()) { ctx.lastStat = "life"; ctx.lastStatWho = ctx.life.keys.last() }
        // "I cast Brainstorm with 1 card in my library" / "with no cards left in their library": library sizes, wherever they sit.
        Regex("""\s*\b(?:with|and|at|having) (\d+|no|one|two|three|four|five|six|seven) cards? (?:left )?in (?:(my|their|his|her|the) )?library\b""").find(t2)?.let { r ->
            val who = if (r.groupValues[2] == "my") "me" else if (r.groupValues[2] == "the" || r.groupValues[2].isEmpty()) (actorOfClause(t2.trim()) ?: "me") else pronounPlayer(ctx, "their")
            ctx.librarySize[who] = if (r.groupValues[1] == "no") 0 else number(r.groupValues[1]) ?: 0; ctx.note(who); any = true; t2 = t2.removeRange(r.range)
        }
        // "… attacks me with Bears and I'm at 1 life": the life total was taken out above; a dangling "and" is left behind.
        t2 = t2.replace(Regex("""\s*\b(?:and|but|while|,)\s*$"""), "").replace(Regex("""^\s*(?:and|but|while)\b\s*"""), "").replace(Regex("""\band\s+and\b"""), "and")
        t2 = t2.replace(Regex("""^\s*(?:what happens|what triggers|what do i do|what is the outcome)\s+(?=(?:at|during|on|in|when)\b)""", RegexOption.IGNORE_CASE), "")
        // "My opponent has 5 life and Platinum Angel": after the life total was taken out, the rest is a possession.
        t2 = t2.replace(Regex("""^\s*(my opponent|the opponent|opponent|they|@\w+) and (?=(?:an? |the |two |three |\d+ )?c\d+)"""), "$1 has ").replace(Regex("""^\s*i and (?=(?:an? |the |two |three |\d+ )?c\d+)"""), "i have ")
        // "they have Counterspell and Grizzly Bears in hand": the same as holding them.
        t2 = t2.replace(Regex("""\b(?:has|have|got|'ve got) ((?:an? |the |two |three |four |\d+ )?c\d+s?(?:,? (?:and )?(?:an? |the |two |three |four |\d+ )?c\d+s?)*) in (?:(?:their|my|his|her) )?hand$"""), "holds $1")
        // "against two opponents" / "in a three-player game": more than one opponent.
        Regex("""\b(?:against|versus|vs\.?|with|and|facing) (two|three|four|\d) opponents\b|\b(?:in )?an? (three|four|five|\d)-player (?:game|pod)\b""").find(t2)?.let { r ->
            val n = (r.groupValues[1].ifEmpty { r.groupValues[2] }).let { number(it) ?: it.toIntOrNull() ?: 2 } - (if (r.groupValues[2].isNotEmpty()) 1 else 0)
            for (i in 2..n) ctx.players.putIfAbsent("opponent$i", "Opponent $i")
            ctx.usesOpp = true; ctx.notes += "$n opponents: the first is \"opponent\", the others \"Opponent 2\"${if (n > 2) " and so on" else ""}."; any = true; t2 = t2.removeRange(r.range)
        }
        // "they reveal Counterspell and Forest" / "my hand is Bolt, Bears and Forest": cards in hand, kept together before the clause split.
        Regex("""\b(?:reveals?|revealing|shows? me|(?:my|their|his|her) hand (?:is|has|contains)|(?:i'm|i am|they're|they are) holding|holds?|holding) ((?:an? |the |two |three |four |\d+ )?c\d+s?(?:,? (?:and )?(?:an? |the |two |three |four |\d+ )?c\d+s?)*)$""").find(t2)?.let { r ->
            val before = t2.substring(0, r.range.first)
            val lastWord = Regex("""\b(i|my|i'm|i am|i've|we|they|their|he|she|his|her|my opponent|the opponent|opponent)\b""").findAll(before).lastOrNull()?.groupValues?.get(1)
            val who = (lastWord?.let { w -> if (w in setOf("i", "my", "i'm", "i am", "i've", "we")) "me" else pronounPlayer(ctx, "they") })
                ?: actorOfClause(t2.trim()) ?: ctx.lastActor ?: "opp"
            val cards = Regex("""(?:(two|three|four|five|\d+) )?(c\d+)""").findAll(r.groupValues[1]).flatMap { mm ->
                val n = mm.groupValues[1].let { if (it.isEmpty()) 1 else number(it) ?: it.toIntOrNull() ?: 1 }
                List(n) { mm.groupValues[2] }
            }.toList()
            t2 = t2.substring(0, r.range.first).trim().let { if (it.isEmpty()) "" else "$it, " }.replace(Regex("""(?:i|they|he|she|my opponent|the opponent|opponent|@\w+), $"""), "") + cards.joinToString(", ") { "${if (who == "me") "i have" else if (who == "opp") "they have" else "@$who has"} $it in hand" }
        }
        // "they have an instant and a creature in their graveyard": card types in a graveyard (Tarmogoyf), kept together before the clause split.
        // "my graveyard has a land and an instant", "their graveyard contains a creature": the owner said first.
        // Read clause by clause the "and" splits the list, so this has to happen before the sentence is split.
        Regex("""\b(my|their|his|her|the|my opponent's|the opponent's|opponent's|@\w+'s) graveyards? (?:has|have|contains?|holds?|is|are) ((?:an? |two |three |\d+ )?(?:instant|sorcery|sorceries|creature|land|artifact|enchantment|planeswalker|battle)s?(?: cards?)?(?:,? (?:and )?(?:an? |two |three |\d+ )?(?:instant|sorcery|sorceries|creature|land|artifact|enchantment|planeswalker|battle)s?(?: cards?)?)*)(?: in it| in there)?""").find(t2)?.let { r ->
            val who = when (val w = r.groupValues[1]) { "my" -> "me"; "the" -> "me"; else -> if (w.startsWith("@")) w.removePrefix("@").removeSuffix("'s").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) } else pronounPlayer(ctx, "their") }
            for (part in r.groupValues[2].split(Regex(""",\s*(?:and\s+)?|\s+and\s+"""))) {
                val pm = Regex("""^(?:(an?|two|three|\d+) )?(\w+?)(?:s)?(?: cards?)?$""").find(part.trim()) ?: continue
                val n = pm.groupValues[1].let { if (it.isEmpty() || it == "a" || it == "an") 1 else number(it) ?: 1 }
                val kind = pm.groupValues[2].let { if (it == "sorcerie") "sorcery" else it }
                val name = (if (kind.startsWith("i") || kind.startsWith("a") || kind.startsWith("e")) "an " else "a ") + kind
                repeat(n) { var id = slug("$kind card"); var k = 2; while (ctx.objects.containsKey(id)) id = slug("$kind card") + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = name), zone = "graveyard", controller = who) }
            }
            ctx.note(who); any = true; t2 = t2.removeRange(r.range)
        }
        Regex("""\b(?:has|have|with|got|holds?|there is|there are|there's) ((?:an? |two |three |\d+ )?(?:instant|sorcery|sorceries|creature|land|artifact|enchantment|planeswalker|battle)s?(?: cards?)?(?:,? (?:and )?(?:an? |two |three |\d+ )?(?:instant|sorcery|sorceries|creature|land|artifact|enchantment|planeswalker|battle)s?(?: cards?)?)*) (?:is |are )?in (?:(my|their|his|her|the|my opponent's|the opponent's|opponent's) )?graveyards?\b""").find(t2)?.let { r ->
            val before = t2.substring(0, r.range.first)
            // "in the graveyards" with no owner: the card types are all that matter, and the engine counts every
            // graveyard, so which one holds them doesn't change the answer.
            val who = when (r.groupValues[2]) { "my", "" -> "me"; "the" -> if (Regex("""\b(?:there is|there are|there's)\s*$""").containsMatchIn(before.trim() + " ") || before.isBlank()) "me" else actorOfClause(t2.trim()) ?: "me"; else -> if (Regex("""\b(?:i|my|i've|i'm)\b""").containsMatchIn(before)) "me" else pronounPlayer(ctx, "their") }
            for (part in r.groupValues[1].split(Regex(""",\s*(?:and\s+)?|\s+and\s+"""))) {
                val pm = Regex("""^(?:(an?|two|three|\d+) )?(\w+?)(?:s)?(?: cards?)?$""").find(part.trim()) ?: continue
                val n = pm.groupValues[1].let { if (it.isEmpty() || it == "a" || it == "an") 1 else number(it) ?: 1 }
                val kind = pm.groupValues[2].let { if (it == "sorcerie") "sorcery" else it }; val name = (if (kind.startsWith("i") || kind.startsWith("a") || kind.startsWith("e")) "an " else "a ") + kind
                repeat(n) { var id = slug("$kind card"); var k = 2; while (ctx.objects.containsKey(id)) id = slug("$kind card") + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = name), zone = "graveyard", controller = who) }
            }
            ctx.notes += "${if (who == "me") "Your" else (ctx.players[who] ?: "Your opponent") + "'s"} graveyard is read as holding: ${r.groupValues[1]} (only the card types matter to the engine)."; ctx.note(who); any = true
            t2 = (before.trim().replace(Regex("""(?:^|\s)(?:i|they|he|she|my opponent|the opponent|opponent|@\w+|there is|there are|there's)$"""), "").trim() + t2.substring(r.range.last + 1)).trim()
        }
        // "I control Goblin Bushwhacker and cast it kicked": the card is being cast, not already on the
        // battlefield, so the control statement isn't one — it only says which card "it" is.
        t2 = t2.replace(Regex("""\b(?:controls?|have|has|got) ((?:$possPrefix|an? )?c\d+) and (casts?|plays?|casting|playing) it\b"""), "$2 $1")
        // "controls three artifacts and two enchantments": both counts belong to the one statement, so the "and"
        // is not a clause break — split there and the second count went unread and the answer came out short.
        val typeCount = """(?:an?|one|\d+|two|three|four|five|six|seven|eight|nine|ten) (?:artifacts?|enchantments?|lands?|creatures?|planeswalkers?|permanents?)"""
        t2 = t2.replace(Regex("""\b(controls?|has|have|got) ($typeCount) and ($typeCount)\b"""), "$1 $2 and $1 $3")
        // "targeting Grizzly Bears and Hill Giant": both are targets of the one spell, so the "and" is not a
        // clause break — split there and the second card was read as a spell of its own being cast.
        t2 = t2.replace(Regex("""\b(targeting|aimed at) ((?:$possPrefix|an? )?c\d+) and ((?:$possPrefix|an? )?c\d+)\b"""), "$1 $2 & $3")
        // "choosing modes 1 and 4": the mode numbers are a list, not an "and" between two clauses, which would
        // leave the bare "4" behind as a clause of its own and report it unread.
        t2 = Regex("""\bmodes? \d+(?:(?:,| and|, and) \d+)+""").replace(t2) { r -> r.value.replace(Regex("""(?:,| and|, and) """), " & ") }
        // "choosing the counter mode and the draw mode": both modes stay in one clause.
        t2 = t2.replace(Regex("""\b(choosing|picking|selecting) (the )?(\w+)( mode)?,? and (the )?(\w+)( mode)?\b"""), "$1 $2$3$4 & $5$6$7")
        // "choosing counter target spell and draw a card": the modes are spelled out. Joining them keeps "draw a card"
        // from being read as a draw of its own, which would have the card drawn before the spell resolved.
        t2 = t2.replace(Regex("""\b(choosing|picking|selecting) ((?:the )?[a-z][a-z0-9' ]{2,60}?),? and ((?:the )?[a-z][a-z0-9' ]{2,60}?)(?=[.,;?]|\s*$)"""), "$1 $2 & $3")
        // "What happens when it enters?": "it" is the permanent the asker described, not the last card named.
        Regex("""^(?:what happens )?when (it|that|(?:$possPrefix)?c\d+) (?:enters|enter|comes in|etbs)(?: the battlefield)?\??$""").find(t2)?.let { r ->
            val named = Regex("""c\d+""").find(r.groupValues[1])?.value ?: Regex("""c\d+""").find(t2)?.value
            if (named != null) { t2 = t2.substring(0, r.range.first) + "$named enters" }
            else {
                // Nothing named in this sentence: "it" is the first permanent the asker described, named by its id.
                val own = ctx.objects.values.firstOrNull { it.controller == "me" } ?: ctx.objects.values.firstOrNull()
                if (own != null) t2 = t2.substring(0, r.range.first) + "@@obj:${own.id} enters"
            }
            any = true
        }
        // "… to protect it" / "… to save my Bears": a purpose, not another action.
        t2 = t2.replace(Regex("""\s+(?:in order )?to (?:protect|save|shroud|shield|defend|keep) (?:it|that|them|him|her|(?:$possPrefix)?c\d+)(?=[.,]|$)"""), "")
        // "a creature with deathtouch and first strike": a keyword list joined by "and" stays in one clause.
        run {
            val kw = """(?:flying|trample|deathtouch|lifelink|first strike|double strike|haste|vigilance|reach|menace|hexproof|indestructible|infect|wither|shroud|defender|flash|regenerate|protection from \w+)"""
            t2 = t2.replace(Regex("""\b(with $kw(?:(?:,| &) $kw)*) and ($kw)\b"""), "$1 & $2")
        }
        // "attack with a 3/3 and a 2/2" / "blocks with two 2/2s and a 1/1": described creatures joined by "and" stay in one clause.
        if (Regex("""\b(?:attacks?|attacking|swings?|swinging|blocks?|blocked|blocking|chumps?)\b""").containsMatchIn(t2)) t2 = t2.replace(Regex("""\b((?:an? |\d+ |two |three |four |five )?\d+/\d+s?(?: (?!and\b)[a-z]+){0,3}) and ((?:an? |\d+ |two |three |four |five )?\d+/\d+s?)(?!\s+(?:chump[- ]?)?blocks?\b)"""), "$1 plus $2")
        // "… with Grizzly Bears and Hill Giant on the battlefield (under my control)": one "with X out" per card, before the clause split takes the "and".
        Regex("""(?:^|\s+)with ((?:(?:$possPrefix|an? )?c\d+)(?:,? (?:and )?(?:$possPrefix|an? )?c\d+)*) (?:out|on the battlefield|in play|on board|on the field)(?: under (my|their|@\w+'s) control)?$""").find(t2)?.let { r ->
            val cards = Regex("""c\d+""").findAll(r.groupValues[1]).map { it.value }.toList()
            if (cards.size > 1 || r.groupValues[2].isNotEmpty()) {
                val owner = when (r.groupValues[2]) { "" -> null; "my" -> "me"; "their" -> pronounPlayer(ctx, "their"); else -> r.groupValues[2].removePrefix("@").removeSuffix("'s") }
                t2 = t2.removeRange(r.range) + cards.joinToString("") { " with ${owner?.let { o -> "@$o's " } ?: ""}$it out" }
            }
        }
        // "has only one untapped creature, Grizzly Bears, and …": the appositive name belongs to the noun before the comma.
        t2 = t2.replace(Regex("""\b(creature|blocker|attacker|permanent|artifact|enchantment|land|thing|card), (c\d+),?(?= and | which | that |$)"""), "$1 $2")
        // "There are two Grizzly Bears on the battlefield, mine and theirs": one on each side, which is what the
        // rest of the grammar already reads when it is said that way round.
        t2 = t2.replace(Regex("""^there (?:is|are|'s) (?:two|2|a pair of) (c\d+)(?: on the battlefield| in play| out)?,? (?:one )?(?:mine|yours) and (?:one )?theirs$"""), "both of us control $1")
        t2 = t2.replace(Regex("""^there (?:is|are|'s) (?:two|2|a pair of) (c\d+)(?: on the battlefield| in play| out)?,? (?:one )?theirs and (?:one )?(?:mine|yours)$"""), "both of us control $1")
        // "we both control X", "both of us each have an X": the same statement, and it can carry on into the rest
        // of the sentence, which the anchored form left unread.
        t2 = t2.replace(Regex("""\b(?:we|both of us)(?: both| each)? (?:control|controls|have|has) (?:an? |the )?(c\d+)"""), "both of us control $1")
        // "I flash back Faithless Looting": the same as casting it with flashback, which is read.
        t2 = t2.replace(Regex("""\b(?:flash(?:es)? back|flashing back|flashbacks?) ((?:$possPrefix|an? )?c\d+)"""), "casts $1 with flashback")
        // "there is a Bolt and a Bears in my graveyard" / "my graveyard has a Bolt and a Bears": the clause splitter
        // cuts at the "and", leaving the second card with no zone, so each card is given the zone before it splits.
        t2 = Regex("""\bthere(?:'s| is| are) ((?:an? |the )?c\d+(?:,? (?:and )?(?:an? |the )?c\d+)+) in ($possPrefix)?(graveyard|yard|bin)\b""").replace(t2) { r ->
            Regex("""c\d+""").findAll(r.groupValues[1]).joinToString(" and ") { "there is a ${it.value} in ${r.groupValues[2]}${r.groupValues[3]}" }
        }
        t2 = Regex("""(?:^|(?<=\s))($possPrefix)(graveyard|yard|bin) (?:has|contains|holds) ((?:an? |the )?c\d+(?:,? (?:and )?(?:an? |the )?c\d+)+)""").replace(t2) { r ->
            Regex("""c\d+""").findAll(r.groupValues[3]).joinToString(" and ") { "there is a ${it.value} in ${r.groupValues[1]}${r.groupValues[2]}" }
        }
        // The same for a graveyard given by card type ("my graveyard has an instant and a creature").
        t2 = Regex("""\b(?:there(?:'s| is| are) )?((?:an? |\d+ )?$cardTypeWord(?: cards?)?(?:,? (?:and )?(?:an? |\d+ )?$cardTypeWord(?: cards?)?)+) (?:is |are )?in ($possPrefix)?(graveyard|yard|bin)s?\b""").replace(t2) { r ->
            Regex("""(?:an? |\d+ )?$cardTypeWord(?: cards?)?""").findAll(r.groupValues[1]).joinToString(" and ") { "there is ${it.value.trim()} in ${r.groupValues[2]}${r.groupValues[3]}" }
        }
        t2 = Regex("""(?:^|(?<=\s))($possPrefix)(graveyard|yard|bin) (?:has|contains|holds) ((?:an? |\d+ )?$cardTypeWord(?: cards?)?(?:,? (?:and )?(?:an? |\d+ )?$cardTypeWord(?: cards?)?)+)""").replace(t2) { r ->
            Regex("""(?:an? |\d+ )?$cardTypeWord(?: cards?)?""").findAll(r.groupValues[3]).joinToString(" and ") { "there is ${it.value.trim()} in ${r.groupValues[1]}${r.groupValues[2]}" }
        }
        // "There is a Lightning Bolt on the stack targeting my Bears" and "my Bears has a Bolt on the stack
        // targeting it" say the same thing as "they have a Bolt on the stack targeting my Bears", which is read.
        // Whose spell it is follows from whose permanent it points at.
        t2 = Regex("""\bthere(?:'s| is| are) (?:an? |the )?(c\d+) on the stack targeting (my|their|my opponent's|the) (c\d+)""").replace(t2) { r ->
            "${if (r.groupValues[2] == "my") "they have" else "i have"} a ${r.groupValues[1]} on the stack targeting ${r.groupValues[2]} ${r.groupValues[3]}"
        }
        t2 = Regex("""\b(my|their|my opponent's|the) (c\d+) has (?:an? |the )?(c\d+) on the stack targeting it""").replace(t2) { r ->
            "${if (r.groupValues[1] == "my") "they have" else "i have"} a ${r.groupValues[3]} on the stack targeting ${r.groupValues[1]} ${r.groupValues[2]}"
        }
        val t = t2.trim()

        // Pure questions carry no state; the engine answers "what happens" by default.
        // "Who wins?" with nobody named is still a question the outcome can answer; ask it before giving up on the
        // sentence, or it was skipped as small talk.
        if (m.cards.isEmpty() && Regex("""^(what happens|what now|who wins|who dies|who loses|so what|what is the result|does (it|that|this) (resolve|work|happen)|can (i|they|my opponent) respond)\b.*$""").matches(t)) { askQuestion(t.trim().trimEnd('?'), m, ctx); return true }

        // Resolution statements.
        if (Regex("""\b(everything resolves|let (it|them|everything|that) resolve|(it|they|both|all) resolves?|resolves? (it|everything|the stack)|nobody responds|no (one|body) responds|no responses?|no further responses?)\b""").containsMatchIn(t)) {
            ctx.events += EventSpec("resolveAll"); ctx.explicitResolve = true; any = true
        }

        // "Opponent casts Settle the Wreckage after I attack with Kaalia and Serra Angel": the "after" part, with all its clauses, happened first.
        Regex("""^(.+?)\s+(after|while|when|once) ((?:i|my|they|their|the opponent|my opponent|opponent|@\w+)\b.*)$""").find(t)?.let { r ->
            if (Regex("""\b(?:attack|attacks|cast|casts|play|plays|activate|activates|block|blocks|control|controls|have|has|gain|gains|lose|loses|draw|draws)\b""").containsMatchIn(r.groupValues[3]) && r.groupValues[3].split(clauseSplit).size > 1) {
                var anySub = false
                for (clause in r.groupValues[3].split(clauseSplit).map { it.trim() }.filter { it.isNotEmpty() }) if (readClause(clause, m, ctx)) anySub = true
                for (clause in r.groupValues[1].split(clauseSplit).map { it.trim() }.filter { it.isNotEmpty() }) if (readClause(clause, m, ctx)) any = true else if (!isNoise(clause)) ctx.unread += restore(clause, m)
                return any || anySub
            }
        }
        // "Alice, Bob and I are playing", "there are four players": who is at the table. Split on the commas and
        // the "and" it becomes a handful of bare names, each of which looks like a clause nobody could read.
        Regex("""^((?:@\w+|i|me|we|you)(?:,? (?:and )?(?:@\w+|i|me|you))*) (?:are|is|'re) (?:playing|in the game|at the table|all playing)$""").find(t)?.let { r ->
            for (n in Regex("""@(\w+)""").findAll(r.groupValues[1]).map { it.groupValues[1] }) { ctx.players.putIfAbsent(n, m.players[n] ?: n); ctx.note(n) }
            if (Regex("""\b(?:i|me|we)\b""").containsMatchIn(r.groupValues[1])) ctx.usesMe = true
            return true
        }
        // Clause-by-clause for actions.
        // "has lifelink and deathtouch" splits on the "and", and a clause that is only a keyword belongs to the
        // one before it. Left on its own, "deathtouch" made a creature of the asker's with deathtouch.
        val clauses = t.split(clauseSplit).map { it.trim() }.filter { it.isNotEmpty() }.fold(mutableListOf<String>()) { acc, cl ->
            val bare = Regex("""^(?:$kwPhrase)$""", RegexOption.IGNORE_CASE).matches(cl)
            if (bare && acc.isNotEmpty() && Regex("""\b(?:has|have|with|gains?|gained|granted)\b""").containsMatchIn(acc.last())) acc[acc.lastIndex] = acc.last() + " and " + cl
            else acc += cl
            acc
        }
        val unreadClauses = mutableListOf<String>()
        for ((ci, clause) in clauses.withIndex()) {
            ctx.clauseIndex = ci
            if (readClause(clause, m, ctx)) any = true else unreadClauses += clause
        }
        ctx.clauseIndex = 0
        if (any && unreadClauses.isNotEmpty()) {
            // Partially understood: report the leftover clauses with card names restored.
            for (u in unreadClauses) if (!isNoise(u)) ctx.unread += restore(u, m)
        }
        return any
    }

    /**
     * Half of a compound clause ("cast X while they control Y"). The two halves are combined with "or", so a half
     * nobody can read used to disappear: the other half made the whole clause count as read. Each half now reports
     * itself, because a clause that quietly changes nothing is worse than one the answer admits it skipped.
     */
    private fun readPart(text: String, m: Marked, ctx: Ctx): Boolean {
        if (readClause(text, m, ctx)) return true
        if (!isNoise(text)) ctx.unread += restore(text.trim(), m)
        return false
    }

    private fun isNoise(clause: String) = Regex("""(?i)^(what happens|what now|so|then|now|ok|okay|right|they're|they are|i'm|i am|he's|she's|we're|it's|does it wear off|do(?:es)? (?:it|that|they) (?:wear off|go away|end|stay)|after (?:combat )?damage|after blockers|after blocks|after combat|after that|after this|before damage|i don't respond|they don't respond|i do(?:n't| not) respond|no response|no responses|nobody responds|no one responds|i pass|they pass|everyone passes|all players pass|does it work|is that right|correct|yes or no|y/n|legal|is that legal|is it legal|allowed|is that allowed|and|but|also|too|as well|no wait|wait|never mind|nevermind|sorry|hmm|uh|um|actually|they durdle|i durdle|durdles?|they do nothing|i do nothing|nothing happens)\??$""").matches(clause.trim()) ||
        (!Regex("""c\d+""").containsMatchIn(clause) && Regex("""^(?:do|does|did|can|could|will|would|is|are|was|were|what|who|which|how|should|when|why|am)\b""").matches(clause.trim().substringBefore(' ')) &&
            // "is blocked by a 1/1", "was countered": a passive statement whose subject was left out opens with
            // the same word a question does, and dropping it as noise lost the block with nothing said about it.
            !passiveStatement.containsMatchIn(clause.trim()))
    /** A passive clause with no subject: the verb, then a participle. "is it blocked?" has a subject and is a question. */
    private val passiveStatement = Regex("""^(?:is|are|was|were|gets|got|has been|have been)\s+(?!\d)\w+(?:ed|n)\b""")
    private fun restore(text: String, m: Marked): String = m.cards.entries.fold(text) { acc, (ph, e) -> acc.replace(Regex("\\b$ph\\b"), e.display) }

    private val castVerbs = """(?:casts?|casting|plays?|playing|fires? off|slams?|kicks?|kicked|evokes?|evoked|evoking)"""
    private val respondVerbs = """(?:respond(?:s|ed)? with|in response(?: i| they)? (?:casts?|plays?)|responds?|answers? with|counters? (?:it|that) with|flash(?:es)? in)"""
    private val activateVerbs = """(?:activates?|activating|uses?|using|cracks?|cracking|pops?|popping|fires? off)"""
    /** A marked card placeholder ("c1", "c12") — as opposed to a word that merely begins with a "c". */
    private val cardRef = Regex("""^c\d+$""")
    /**
     * A statement that the attack wasn't blocked. Nothing to add: with no block described the engine already has
     * the attacker unblocked. Only the negative forms belong here — "it is blocked" says something quite different.
     */
    private val unblockedRe = Regex("""^(?:(?:it|they|he|she|c\d+|the attacker|the attackers|my attacker|their attacker|the creature|the creatures) )?(?:(?:is|are|was|were|goes|go|went|gets|get|stays|stay|remains|remain) )?un(?:blocked|contested)$""" +
        """|^(?:(?:it|they|he|she|c\d+|the attacker|the attackers|my attacker|their attacker|the creature|the creatures) )?(?:isn't|is not|aren't|are not|wasn't|was not|weren't|were not|didn't get|doesn't get|don't get) blocked$""" +
        """|^(?:nobody|no one|no-one|neither(?: player| of them)?|none of them|no creature|no blockers?) (?:blocks?|blocked|block)$""" +
        """|^(?:it|they|he|she|c\d+) (?:gets?|got|goes?|went) through$""")

    private fun readClause(clauseIn0: String, m: Marked, ctx: Ctx): Boolean {
        // "I try to activate it" / "they attempt to block": the attempt is the action, and the answer says how it goes.
        val clauseIn = clauseIn0.replace(Regex("""\b(?:tr(?:y|ies|ied)|attempts?|attempted|want(?:s|ed)?|would like) to (?=(?:activate|use|tap|untap|block|attack|cast|play|sacrifice|equip|counter|draw|search|target|crack|pop|fire|give|put|destroy|exile|bounce|kill|return|regenerate)\b)"""), "")
            // "I control Valakut and five other Mountains": "other" only says they aren't the card just named.
            .replace(Regex("""^(\d+) other (?=c\d+\b|[a-z])"""), "$1 ")
            // "a 3/3 deathtouch trampler": a run of keyword words after the size describes the creature the same
            // way "a 3/3 with deathtouch and trample" does. The noun forms ("trampler") mean the keyword.
            .let { t0 -> Regex("""\b(\d+/\d+) ((?:$kwNouns)(?: (?:$kwNouns))+)(?=$|[.,;?]| (?:and|or|plus|blocks?|attacks?)\b)""").replace(t0) { r ->
                val kws = r.groupValues[2].split(' ').filter { it.isNotEmpty() }.map { w ->
                    w.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample")
                        .replace("deathtoucher", "deathtouch").replace("lifelinker", "lifelink").replace("striker", "strike")
                }
                r.groupValues[1] + " creature with " + kws.joinToString(", ")
            } }
            // "I control a 2/2 vigilance": a keyword straight after the size, with no noun after it, describes the
            // creature the same way "a 2/2 with vigilance" does.
            .replace(Regex("""\b(\d+/\d+) ($kwPhrase)(?=$|[.,;?]| (?:and|or|plus)\b)"""), "$1 creature with $2")
            .let { t0 -> Regex("""\b(\d+/\d+) ($kwNouns)(?= (?:blocks?|blocking|attacks?|attacking|swings?|dies|die|died|is|was|has|have|gets?|takes?|deals?|and|or|plus)\b| with (?:an? |one |two |three |\d+ )?[+-]\d+/[+-]\d+ counter)""").replace(t0) { r ->
                r.groupValues[1] + " creature with " + r.groupValues[2].removeSuffix("s").replace("flier", "flying").replace("flyer", "flying")
                    .replace("trampler", "trample").replace("deathtoucher", "deathtouch").replace("lifelinker", "lifelink").replace("striker", "strike")
            } }
            // "with double strike with a +1/+1 counter": two "with" phrases for one creature, which the keyword
            // rewrite above can leave behind. The second is part of the same description.
            .replace(Regex("""\bwith ($kwPhrase(?:(?:,|,? and) $kwPhrase)*) with ((?:an? |one |two |three |\d+ )?[+-]\d+/[+-]\d+ counters?)"""), "with $1 and $2")
            // "activate its monstrosity" / "activate its ability": the permanent is the thing being activated.
            .replace(Regex("""\b(activates?|activating|uses?|using) (?:its|his|her|their) (?:monstrosity|ability)\b"""), "$1 it")
        val read = readClause0(clauseIn, m, ctx)
        // "I am at 20 life and cast Toxic Deluge": the first player named in the situation is the one acting until
        // someone else acts. Without this the cast fell to a default and became the opponent's. Set after the
        // clause is read, so a rule that works out the actor itself still has the last word.
        if (ctx.lastActor == null && Regex("""^(?:i|i'm|i am|i've|we)\b""").containsMatchIn(clauseIn.trim())) ctx.lastActor = "me"
        // A clause no rule could read still says who is acting, and the clauses after it carry on from there:
        // "I control a commander and cast Deflecting Swat" had the cast falling to the player who acted last.
        if (!read) {
            if (Regex("""^(?:i|i'm|i am|i've|we)\b""").containsMatchIn(clauseIn.trim())) ctx.lastActor = "me"
            else if (Regex("""^(?:they|my opponent|the opponent)\b(?!'s)""").containsMatchIn(clauseIn.trim())) ctx.lastActor = pronounPlayer(ctx, "their")
        }
        return read
    }

    private fun readClause0(clauseIn: String, m: Marked, ctx: Ctx): Boolean {
        // MTG_DEBUG_CLAUSE=1 prints every clause as the rules see it. A clause that is read by the wrong rule
        // leaves no note behind, so seeing the exact text is the quickest way to find which rule took it.
        if (System.getenv("MTG_DEBUG_CLAUSE") != null) System.err.println("clause: [$clauseIn]")
        // "… with a Wall of Omens out" left on its own once the life total was taken out of the sentence: it says
        // what is on the battlefield, the same as "I have a Wall of Omens out".
        Regex("""^(?:\s*with (?:$possPrefix|an? )?c\d+(?:,? (?:and )?(?:$possPrefix|an? )?c\d+)*(?: out| on the battlefield| in play| on board| on the field))+$""").find(clauseIn.trim())?.let {
            var read = false
            for (part in Regex("""with ((?:(?:$possPrefix|an? )?c\d+)(?:,? (?:and )?(?:$possPrefix|an? )?c\d+)*)(?: out| on the battlefield| in play| on board| on the field)""").findAll(clauseIn.trim()))
                if (readClause("have " + part.groupValues[1] + " out", m, ctx)) read = true
            if (read) return true
        }
        // "cast it kicked with three 1/1 Goblins out": the trailer says what is already on the battlefield and the
        // head is the action. Only a trailer that gives creatures by size is taken this way — two earlier attempts
        // at a general "with X out" split each broke a sentence where "with" belonged to the action itself.
        Regex("""^(.+?)\s+with ((?:an?|\d+)\s+\d+/\d+[a-z0-9/ ]*?)\s+(?:out|on the battlefield|in play)$""").find(clauseIn.trim())?.let { r ->
            val head = r.groupValues[1].trim()
            if (head.isEmpty() || Regex("""c\d+""").containsMatchIn(r.groupValues[2])) return@let
            val mentionedBefore = ctx.lastMentioned
            val readTrailer = readClause("have " + r.groupValues[2].trim() + " out", m, ctx)
            if (!readTrailer) return@let
            // The head is read after the board, but "it" in it points at what was named before this clause, not
            // at one of the creatures the trailer just made.
            ctx.lastMentioned = mentionedBefore
            if (!readClause(head, m, ctx) && !isNoise(head)) ctx.unread += restore(head, m)
            return true
        }
        // "Isochron Scepter imprinting Lightning Bolt" / "with Lightning Bolt imprinted on it": the imprinted card
        // is in exile, not on the battlefield. Read as part of the permanent it was put there as a second permanent,
        // so an instant turned up on the battlefield and nothing said so.
        Regex("""^(.+?)\s+(?:imprinting (?:an? |the )?(c\d+)|with (?:an? |the )?(c\d+) imprinted(?: on it| on that)?)$""").find(clauseIn.trim())?.let { r ->
            val ph = r.groupValues[2].ifEmpty { r.groupValues[3] }
            val card = m.cards[ph] ?: return@let
            val read = readClause(r.groupValues[1], m, ctx)
            val host = ctx.lastMentioned?.takeIf { it in ctx.objects }?.let { ctx.objects.getValue(it).card.name }
            val was = ctx.lastMentioned
            addObject(card, ctx.lastOwner, false, ctx, zone = "exile", allowDuplicate = true)
            ctx.lastMentioned = was
            ctx.notes += "${card.display} is imprinted on ${host ?: "it"} (exiled). The engine doesn't model imprint, so nothing in the answer turns on what the imprinted card says."
            return read
        }
        // "taps out for Grizzly Bears": a cast, said the way players say it. The mana is spent, not available.
        Regex("""^(.*?)\btaps? out (?:for|to cast|casting|and casts?) (.+)$""", RegexOption.IGNORE_CASE).find(clauseIn.trim())?.let { r ->
            val read = readClause((r.groupValues[1].trim() + " casts " + r.groupValues[2].trim()).trim(), m, ctx)
            if (read) ctx.notes += "\"Taps out\" is read as casting it; whoever it was has no mana left afterwards."
            return read
        }
        // "my Bears gets chumped by a 1/1": the passive way to say the 1/1 blocked it.
        Regex("""^(.+?) (?:gets?|got|is|was|were) chump(?:ed|[- ]?blocked) by (.+)$""", RegexOption.IGNORE_CASE).find(clauseIn.trim())?.let { r ->
            return readClause("${r.groupValues[2].trim()} blocks ${r.groupValues[1].trim()}", m, ctx)
        }
        // "it resolves" / "everything resolves": already acted on when the sentence was read, so it is not unread.
        if (Regex("""^(?:and )?(?:it|they|that|this|both|all|everything)?\s*resolves?$|^(?:nobody|no one|nothing) responds?$|^no responses?$""").matches(clauseIn.trim())) return true
        // "I already control one" after "a second Sheoldred": the first copy is already on the battlefield from that clause.
        if (Regex("""^(?:i|they|he|she|we)?\s*(?:already )?(?:control|have|had|had out|got)\s*(?:one|another one|the other one|a copy|one already)$""").matches(clauseIn.trim()) &&
            ctx.events.any { it.verb == "cast" }) return true
        // "… no wait, I cast Murder instead" / "I mean Murder on it": the last spell is taken back and this one replaces it.
        Regex("""^(?:no,? wait|wait,? no|actually|scratch that|sorry|i mean|i meant|rather)[,:]?\s+(.*)$""").find(clauseIn.trim())?.let { r ->
            val rest = r.groupValues[1].trim()
            if (rest.isEmpty()) return true
            val lastCast = ctx.events.indexOfLast { it.verb == "cast" }
            if (lastCast >= 0) {
                val undone = ctx.events.removeAt(lastCast)
                undone.card?.name?.let { n -> ctx.castCards.remove(n); ctx.objects.values.firstOrNull { it.card.name == n }?.let { ctx.objects.remove(it.id) } }
                ctx.notes += "\"${undone.card?.name ?: "The previous spell"}\" was taken back before anything happened; only what follows is read."
            }
            return readClause((if (Regex("""^(?:i|they|he|she|we|my opponent|the opponent|@\w+)\b""").containsMatchIn(rest)) "" else "i ") + rest, m, ctx)
        }
        // "… but can't pay" / "… but doesn't pay": the action, then the declined payment.
        Regex("""^(.+?) but (?:can't|cannot|can not|couldn't|could not|doesn't|does not|don't|do not|won't|will not|declines? to|refuses? to|(?:am|is|are) unable to) pay(?: for (?:it|that|them|the tax)| the tax| the cost| \{?\d\}?)?$""").find(clauseIn.trim())?.let { r ->
            val first = readClause(r.groupValues[1], m, ctx)
            return readClause((actorOfClause(r.groupValues[1].trim())?.let { if (it == "me") "i " else if (it == "opp") "they " else "@$it " } ?: "") + "doesn't pay", m, ctx) || first
        }
        // Step beginnings keep their possessive: "my upkeep begins", "at the beginning of their end step".
        Regex("""^(?:at the beginning of |at the start of |during |on |at |it's |it is |we are in |we're in |in )?(my|their|the opponent's|opponent's|my opponent's|each|the|@\w+'s) (untap step|untap|upkeep|draw step|end step|end of turn|precombat main phase|main phase|combat|beginning of combat|cleanup step|cleanup)(?: begins| starts| now)?$""").find(clauseIn)?.let { r ->
            val who = when (r.groupValues[1]) { "my" -> "me"; "each", "the" -> ctx.activePlayer ?: "me"; else -> if (r.groupValues[1].startsWith("@")) r.groupValues[1].removePrefix("@").removeSuffix("'s") else "opp" }
            val step = when (r.groupValues[2]) { "upkeep" -> "upkeep"; "draw step" -> "draw"; "end step", "end of turn" -> "end"; "combat", "beginning of combat" -> "combat"; "cleanup step", "cleanup" -> "cleanup"; "untap step", "untap" -> "untap"; else -> "precombat_main" }
            ctx.activePlayer = who
            if (ctx.events.lastOrNull()?.let { it.verb == "step" && it.to == step && it.player == who } != true) ctx.events += EventSpec("step", player = who, to = step)
            return true
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
        // "then it's my next turn": the turn has already been taken care of above, and what is left is only the
        // words that introduced it. Left to the rules below it was reported unread although it had been read.
        if (ctx.nextTurn && Regex("""^(?:and |then |now |so )*(?:it's|it is|we're on|we are on|on)? ?(?:my|their|his|her|the)?$""").matches(clause0.trim())) return true
        while (true) {
            val r = Regex("""\s+with (?:an? |the |my |their |@(\w+)'s )?(?:(\d+|two|three|four|five) )?(c\d+) (?:out|in play|on the battlefield|on board|on the field)$""").find(clause0) ?: break
            val owner = r.groupValues[1].ifEmpty { actorOfClause(clause0) ?: ctx.lastActor ?: "me" }
            val n = r.groupValues[2].takeIf { it.isNotEmpty() }?.let { number(it) } ?: 1
            repeat(n) { addObject(m.cards.getValue(r.groupValues[3]), owner, false, ctx, allowDuplicate = n > 1) }; clause0 = clause0.removeRange(r.range)
        }
        // Trailing "with an indestructible creature out" / "with two 2/2s in play": the same as the card version
        // above, for a creature given by its size or its keywords. Without it the whole clause went unread.
        while (true) {
            val r = Regex("""\s+with (an? |\d+ |two |three |four |five )((?:\d+/\d+ ?)?(?:$kwNouns ?)*)(creatures?|permanents?)?s? (?:out|in play|on the battlefield|on board|on the field)$""").find(clause0) ?: break
            if (r.groupValues[2].isBlank() && r.groupValues[3].isBlank()) break
            val size = Regex("""\d+/\d+""").find(r.groupValues[2])?.value ?: ""
            val kws = r.groupValues[2].replace(Regex("""\d+/\d+"""), "").trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(", ")
            val owner = actorOfClause(clause0) ?: ctx.lastActor ?: "me"
            if (describedCreatures(r.groupValues[1], size, "creature", owner, ctx, kws).isEmpty()) break
            clause0 = clause0.removeRange(r.range)
        }
        Regex("""\s+after (?:combat )?damage(?: is dealt)?$""").find(clause0)?.let { r -> if (ctx.events.any { it.verb == "attack" || it.verb == "attackAll" }) ctx.events += EventSpec("combatDamage"); clause0 = clause0.removeRange(r.range) }
        // "… with 4 mana (up)": what the actor has available, said as a trailer on the action it pays for.
        Regex("""\s+(?:with|having) (\d+) mana(?: available| up| open| untapped| left)?$""").find(clause0)?.let { r ->
            val who = actorOfClause(clause0) ?: ctx.lastActor ?: "me"
            ctx.mana[who] = r.groupValues[1].toInt(); ctx.note(who)
            ctx.notes += "${if (who == "me") "You have" else (ctx.players[who] ?: "Your opponent") + " has"} ${r.groupValues[1]} mana available; costs are checked against that."
            clause0 = clause0.removeRange(r.range)
        }
        // "before combat", "before blockers", "in their main phase": when it happened, which the order of the
        // clauses already says. Left on the end, the whole clause went unread.
        clause0 = clause0.replace(Regex("""\s+(?:before (?:combat|blockers|blocks|attackers|attacks|the combat phase|combat starts)|precombat|in (?:their|my|the) (?:precombat |first )?main phase|during (?:their|my) main phase)$"""), "")
        clause0 = clause0.replace(Regex("""\s+with no (?:blockers|blocks|responses?|creatures)$"""), "").replace(Regex("""\s+(?:and|with) (?:no|nothing) (?:else|on board|in play)$"""), "")
            .replace(Regex("""\s+(?:unblocked|and (?:it's|it is|they're|they are) (?:not|un)blocked|and (?:nobody|no one) blocks)$"""), "")
            .replace(Regex("""\s+(?:that|which|who) (?:goes? unblocked|(?:i|they|he|she|we) (?:don't|doesn't|do not|does not|can't|cannot) block(?: it| them)?|(?:isn't|is not|aren't|are not) blocked)$"""), "")
        // "… during their beginning of combat" / "… on my end step": that step begins first.
        Regex("""\s+(?:during|on|at|in) (my|their|the opponent's|opponent's|my opponent's|@\w+'s|the) (upkeep|draw step|end step|end of turn|precombat main phase|main phase|combat|beginning of combat|declare attackers step|declare blockers step|declare blockers|blockers step|combat damage step|cleanup step)$""").find(clause0)?.let { r ->
            val who = when (r.groupValues[1]) { "my" -> "me"; "the" -> ctx.activePlayer ?: ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }?.player ?: ctx.lastActor ?: "me"; else -> if (r.groupValues[1].startsWith("@")) r.groupValues[1].removePrefix("@").removeSuffix("'s") else pronounPlayer(ctx, "their") }
            val step = when (r.groupValues[2]) { "upkeep" -> "upkeep"; "draw step" -> "draw"; "end step", "end of turn" -> "end"; "combat", "beginning of combat" -> "combat"; "declare attackers step" -> "declare_attackers"; "declare blockers step", "declare blockers", "blockers step" -> "declare_blockers"; "combat damage step" -> "combat_damage"; "cleanup step" -> "cleanup"; else -> "precombat_main" }
            // Inside combat after attackers were declared, the step marker only orders the action; the engine is already in combat.
            if (step in setOf("declare_blockers", "combat_damage") && ctx.events.any { it.verb == "attack" || it.verb == "attackAll" }) { clause0 = clause0.removeRange(r.range); return@let }
            ctx.activePlayer = who; ctx.events += EventSpec("step", player = who, to = step); clause0 = clause0.removeRange(r.range)
        }
        // "… while I control X" / "… when they have Y out": the state part is read first, then the action.
        Regex("""^(.+?)\s+after ((?:i|my|they|their|the opponent|my opponent|opponent|@\w+)\b.*)$""").find(clause0)?.let { r ->
            if (Regex("""\b(?:attack|attacks|cast|casts|play|plays|activate|activates|block|blocks|declare|declares|gain|gains|lose|loses|draw|draws|sacrifice|sacrifices|tap|taps|resolve|resolves)\b""").containsMatchIn(r.groupValues[2])) {
                val first = readPart(r.groupValues[2], m, ctx)
                return readPart(r.groupValues[1], m, ctx) || first
            }
        }
        Regex("""^(.+?)\s+(?:while|when|although|even though|given that) ((?:i|my|they|their|the opponent|my opponent|opponent|it|it's|@\w+)\b.*)$""").find(clause0)?.let { r ->
            if (Regex("""\b(?:control|controls|have|has|got|is|are|out|in play|attacking|blocking|life|at -?\d+)\b|'re\b|'m\b|'s\b""").containsMatchIn(r.groupValues[2])) {
                // A state clause naming a card comes first (it sets up the board); one about "it" refers to the action's target, so it comes second.
                return if (Regex("""c\d+""").containsMatchIn(r.groupValues[2])) { val stateRead = readPart(r.groupValues[2], m, ctx); readPart(r.groupValues[1], m, ctx) || stateRead }
                else { val main = readPart(r.groupValues[1], m, ctx); readPart(r.groupValues[2], m, ctx) || main }
            }
        }
        // "After damage, does Serra Angel untap?": the time phrase adds nothing the ordering doesn't already say.
        clause0 = clause0.replace(Regex("""^(?:after|once|when) (?:combat )?(?:damage|blockers|blocks|combat|that|this|it resolves|everything resolves)(?: is dealt| are declared)?,?\s+"""), "")
        // "I pump it with Giant Growth after blockers": a timing phrase at the end says when, not what, and left
        // on the clause it stopped every rule that anchors at the end from matching.
        clause0 = clause0.replace(Regex("""\s+(?:after (?:blockers|blocks|attackers|attacks)(?: are declared)?|before (?:combat )?damage(?: is dealt)?|during combat|after combat|in combat|post[- ]?blocks?)$"""), "")
        // "I bounce my token to my hand": after "bounce" the destination is never in doubt, so saying it adds
        // nothing but a tail that stopped the clause matching. "Return … to its owner's hand" keeps its tail:
        // there the destination is what makes it a bounce at all.
        if (Regex("""\b(?:bounces?|bounced|bouncing)\b""").containsMatchIn(clause0))
            clause0 = clause0.replace(Regex("""\s+(?:back )?to (?:its owner's|their owner's|the owner's|my|their|his|her|your) hand$"""), "")
        // "how much mana do I have?" / "how much mana can I make?": every untapped source that player controls.
        Regex("""^how (?:much|many) mana(?: (?:do|does|can|could|would) (i|we|they|he|she|my opponent|the opponent|opponent|@\w+) (?:have|make|produce|get|tap for|generate|have available|have up))?(?: (?:available|up|right now|now|in total|altogether|is there|do i have))?$""").find(clause0)?.let { q ->
            val who = when (val w = q.groupValues[1]) { "" -> "me"; "i", "we" -> "me"; "opponent", "my opponent", "the opponent" -> "opp"; else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w) }
            ctx.asks += EventSpec("ask", player = who, to = "manaAvailable"); ctx.note(who)
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "how much does my Lightning Bolt cost?": the printed cost plus every tax on the battlefield.
        Regex("""^(?:how (?:much|many)(?: mana)?|what) (?:does|do|would|will|did) ($possPrefix|an? )?(c\d+|it|that|commander) cost(?: to cast)?(?: me| us| them| for me| for them)?(?: to cast)?(?: now| right now| at the moment)?$""").find(clause0)?.let { q ->
            val who = when { q.groupValues[1].startsWith("@") -> q.groupValues[1].removePrefix("@").removeSuffix("'s "); q.groupValues[1] == "their " -> pronounPlayer(ctx, "their"); else -> "me" }
            // "My commander is Atraxa … how much does it cost now?": the card was named earlier in the question.
            // A card the asker said they hold is the one they are about to cast; then a commander waiting in the
            // command zone; only then whatever was mentioned last.
            val fallback = ctx.objects.values.lastOrNull { it.zone == "command" } ?: ctx.lastMentioned?.let { ctx.objects[it] } ?: ctx.objects.values.lastOrNull()
            val card = m.cards[q.groupValues[2]]
                ?: ctx.inHand[who]?.lastOrNull()
                ?: fallback?.card?.name?.let { n -> names.lookup(Names.normalize(n)) }
                // "my commander died twice, what does it cost now?": nobody named the card, so there is no cost
                // to add the tax to — but the tax itself is what the question is about, and is still an answer.
                ?: run {
                    val gid = fallback?.takeIf { it.commander }?.id ?: return@let
                    ctx.asks += EventSpec("ask", obj = gid, to = "spellCost")
                    ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
                }
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx).also { ctx.objects[it] = ctx.objects.getValue(it).copy(zone = "hand") }
            ctx.asks += EventSpec("ask", obj = id, to = "spellCost")
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "where does Rancor go?" / "what happens to the Bears?" / "how much does it cost?": the outcome answers it.
        // "how many counters does it have?" has its own answer further down; this catch-all would take it first.
        if (Regex("""^how (?:much|many)(?: (?:combat )?(?:damage|life|cards?|mana|counters?))? (?:does|do|did|will|would|is|are)\b.*\b(?:cost|costs|pay|gain|lose|deal|draw|get|have|left)\b.*$""").matches(clause0)
            && !Regex("""^how many (?:[+-]\d+/[+-]\d+ |[a-z]+ )?counters?\b""").containsMatchIn(clause0) && !Regex("""\bdamage (?:do|does|will|would) .*\b(?:take|receive|suffer)\b""").containsMatchIn(clause0)) {
            // "… if I attack with everything": the attack is made so the answer can be shown.
            Regex("""\bif (i|they|my opponent|the opponent|@\w+) attacks? with (?:everything|all|both|my team|all my creatures|the team)\b""").find(clause0)?.let { a ->
                val who = when (val w = a.groupValues[1]) { "i" -> "me"; else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w.substringAfterLast(' ')) }
                if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
                ctx.events += EventSpec("attackAll", player = who, targets = listOf(ctx.other(who) ?: "opp")); ctx.lastActor = who; ctx.lastVerb = "attack"
            }
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        if (Regex("""^(?:where|what) (?:does|do|did|will|would|happens? to|is|are)\b.*\b(?:go|end up|land|happen|happens|it|them|now)$""").matches(clause0) || Regex("""^what happens to\b""").containsMatchIn(clause0)) {
            // "What happens to Bob's Grizzly Bears?" can be the first time that card is named, and with nothing on
            // the battlefield the answer was "Nothing changes". The owner said in the question puts it there.
            Regex("""^what happens to ($possPrefix)(c\d+)$""").find(clause0)?.let { q ->
                val card = m.cards[q.groupValues[2]] ?: return@let
                if (objectIdFor(card, ctx) != null || card.isSpellOnly) return@let
                val who = possessiveOwner(q.groupValues[1], ctx, m) ?: return@let
                addObject(card, who, false, ctx)
                ctx.notes += "${card.display} was named only in the question; it is taken to be on the battlefield under ${if (who == "me") "your" else (ctx.players[who] ?: "your opponent") + "'s"} control."
            }
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "Can my opponent respond …?" / "Could I block with …?": the question is read as the action.
        Regex("""^(?:can|could|may|should|is it legal for|am i allowed to|are they allowed to|do|does|will|would|am|is|are) ((?:i|my opponent|the opponent|opponent|they|he|she|we|it|that|@\w+|(?:my |the |their |his |her |an? )?(?:c\d+|\d+/\d+(?: [a-z]+)*|creature|token|guy|attacker|blocker|dude|beater))\b.*)$""").find(clause0)?.let { r ->
            // A question about what can be done now comes after what was described, unless it says "in response".
            if (!Regex("""\b(?:in response|respond|responding)\b""").containsMatchIn(r.groupValues[1]) && ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
            // "My Grizzly Bears. Does it die to Lightning Bolt?": the spell the question names is cast at the
            // creature. Answered without casting it, the answer was "no, it's still on the battlefield".
            Regex("""^(it|that|(?:my |the |their |his |her |an? )?(?:c\d+|\d+/\d+(?:[a-z0-9 ,/+&]*?)?|creature|guy|dude)) (?:dies?|die|survive|is killed|get(?:s)? killed|is destroyed|get(?:s)? destroyed|survives?|live(?:s)?)(?: (?:to|against))? (?:$possPrefix|an? )?(c\d+)$""").find(r.groupValues[1])?.let { q ->
                val spell = m.cards[q.groupValues[2]] ?: return@let
                val subj = q.groupValues[1]
                val victim = (when {
                    Regex("""c\d+""").containsMatchIn(subj) -> Regex("""c\d+""").find(subj)!!.value.let { ph -> m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } }
                    Regex("""\d+/\d+""").containsMatchIn(subj) -> Regex("""\d+/\d+""").find(subj)!!.value.let { pt ->
                        val owner = if (Regex("""^(?:their|his|her)\b""").containsMatchIn(subj)) pronounPlayer(ctx, "their") else "me"
                        val kws = Regex("""$kwNouns""").findAll(subj).map { k -> k.value.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample").replace("deathtoucher", "deathtouch").replace("lifelinker", "lifelink").replace("striker", "strike") }.distinct().joinToString(", ")
                        ctx.objects.values.lastOrNull { it.controller == owner && (it.card.name ?: "").startsWith("a $pt") }?.id ?: describedCreatures("a ", pt, "creature", owner, ctx, kws).firstOrNull() }
                    subj in setOf("it", "that") -> ctx.lastMentioned?.takeIf { it in ctx.objects }
                    else -> ctx.objects.values.lastOrNull { it.controller == "me" && isCreatureName(it.card.name) }?.id ?: describedCreatures("a ", "", "creature", "me", ctx).firstOrNull()
                }) ?: return@let
                val caster = ctx.other(ctx.objects[victim]?.controller ?: "me") ?: "opp"
                emitCast(caster, spell, "", m, ctx)
                ctx.events.indexOfLast { it.verb == "cast" }.takeIf { it >= 0 }?.let { i -> ctx.events[i] = ctx.events[i].copy(targets = listOf(victim)) }
                Regex("""\b(an?|one|two|three|four|five|\d+) ([+-]\d+/[+-]\d+) counters?\b""").find(subj)?.let { cm ->
                    ctx.events.add(0, EventSpec("counters", obj = victim, amount = number(cm.groupValues[1]) ?: 1, to = cm.groupValues[2]))
                }
                // "does it survive?" and "does it die?" are the same question asked the other way round.
                ctx.asks += EventSpec("ask", obj = victim, to = if (Regex("""\b(?:survives?|lives?)\b""").containsMatchIn(q.groupValues[0])) "survive" else "die")
                ctx.lastMentioned = victim; return true
            }
            // "does my 2/2 die to a 2/2 with deathtouch in combat?": what it dies to is a creature, not a spell,
            // so the question is about a fight in combat. Answered without one, it was always "no".
            Regex("""^(it|that|(?:my |the |their |his |her )?(?:c\d+|\d+/\d+|creature|guy|dude)) (?:dies?|die|is killed|get(?:s)? killed|survives?|live(?:s)?|trades? with) (?:to|against|with)? ?(?:an? |the |their |my )?(\d+/\d+)([a-z ,/+&]*?)(?: in combat| in a fight| in the fight)?$""").find(r.groupValues[1])?.let { q ->
                val minePh = q.groupValues[1]
                val mineId = when {
                    Regex("""c\d+""").containsMatchIn(minePh) -> Regex("""c\d+""").find(minePh)!!.value.let { ph -> m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } }
                    Regex("""\d+/\d+""").containsMatchIn(minePh) -> Regex("""\d+/\d+""").find(minePh)!!.value.let { pt ->
                        ctx.objects.values.lastOrNull { it.controller == "me" && (it.card.name ?: "").startsWith("a $pt") }?.id ?: describedCreatures("a ", pt, "creature", "me", ctx).firstOrNull() }
                    minePh in setOf("it", "that") -> ctx.lastMentioned?.takeIf { it in ctx.objects }
                    else -> ctx.objects.values.lastOrNull { it.controller == "me" && isCreatureName(it.card.name) }?.id ?: describedCreatures("a ", "", "creature", "me", ctx).firstOrNull()
                } ?: return@let
                val kws = Regex("""$kwNouns""").findAll(q.groupValues[3]).map { k -> k.value.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample").replace("deathtoucher", "deathtouch").replace("lifelinker", "lifelink").replace("striker", "strike") }.distinct().joinToString(", ")
                val theirs = ctx.other(ctx.objects[mineId]?.controller ?: "me") ?: "opp"
                val foe = describedCreatures("a ", q.groupValues[2], "creature", theirs, ctx, kws).firstOrNull() ?: return@let
                ctx.events += EventSpec("attack", player = ctx.objects[mineId]?.controller ?: "me", obj = mineId, targets = listOf(theirs))
                ctx.events += EventSpec("block", player = theirs, obj = foe, targets = listOf(mineId))
                ctx.notes += "Read as combat: ${ctx.objects[mineId]?.card?.name ?: "it"} attacks and ${ctx.objects[foe]?.card?.name ?: "the other creature"} blocks."
                ctx.asks += EventSpec("ask", obj = mineId, to = "die"); ctx.lastMentioned = mineId; return true
            }
            // "can it attack the turn it comes down?" / "can I tap it for mana the turn I play it?": the tail says
            // it came under its controller's control this turn. Ignored, the answer assumed it had been there
            // since the turn began and said the attack went through.
            Regex("""^(.*?),? (?:the (?:same )?turn (?:it|i|they|he|she) (?:comes? down|came down|plays? it|played it|casts? it|cast it|enters?(?: the battlefield)?|entered)|the turn it came into play)$""").find(r.groupValues[1])?.let { q ->
                val ph = Regex("""c\d+""").find(q.groupValues[1])?.value
                fun sick(id: String) {
                    ctx.objects[id] = ctx.objects.getValue(id).copy(summoningSick = true)
                    ctx.notes += "\"${restore(clause0, m)}?\" asks about the turn ${ctx.objects.getValue(id).card.name ?: "it"} came down, so it is summoning sick."
                }
                fun find(): String? = ph?.let { p -> m.cards[p]?.let { objectIdFor(it, ctx) } }
                    ?: ctx.lastMentioned?.takeIf { it in ctx.objects }
                    ?: ctx.objects.values.lastOrNull { it.zone == "battlefield" && isCreatureName(it.card.name) }?.id
                val id = find()
                if (id != null) sick(id)
                val rest = readClause(clause0.removeRange(clause0.length - (r.groupValues[1].length - q.groupValues[1].length), clause0.length), m, ctx)
                // The creature the question asks about may not have been on the table yet — "can I attack with
                // Llanowar Elves the turn it comes down?" puts it there only once the rest of the clause is read.
                // Marking it sick afterwards is what keeps the answer from quietly letting the attack through.
                if (id == null) find()?.let { sick(it) }
                return rest
            }
            // "can I still block with it?" / "can my Bears attack?": a yes/no about that creature, answered once everything has resolved.
            if (askQuestion(clause0, m, ctx)) return true
            // "do I lose 2 life?" / "does my opponent take 3 damage?" / "do I draw a card?": a question about an amount, which the outcome answers; never an action.
            if (Regex("""^(?:i|they|my opponent|the opponent|opponent|he|she|we|@\w+) (?:still |then |also |even )?(?:lose|loses|gain|gains|take|takes|draw|draws|get|gets|pay|pays|discard|discards|mill|mills|deal|deals) (?:\d+|a|an|any|two|three|four|five|that|the|no|some|all)\b""").containsMatchIn(r.groupValues[1])) { ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true }
            // "can I block with both?" / "can they block with all of them?": every creature on that side blocks the attacker.
            Regex("""^(i|we|they|he|she|my opponent|the opponent|@\w+) (?:still |even )?blocks? with (?:both|both of them|all of them|all three|everything|all my creatures|all of my creatures|my whole board)$""").find(r.groupValues[1])?.let { q ->
                val who = when (val w = q.groupValues[1]) { "i", "we" -> "me"; "my opponent", "the opponent" -> "opp"; else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w) }
                val attacker = ensureAttacker(ctx, who)?.obj ?: return@let
                val blockers = ctx.objects.values.filter { it.controller == who && it.id != attacker && isCreatureName(it.card.name) }
                if (blockers.isEmpty()) return@let
                for (b in blockers) ctx.events += EventSpec("block", player = who, obj = b.id, targets = listOf(attacker))
                ctx.lastVerb = "block"; ctx.lastActor = who; ctx.note(who)
                ctx.notes += "\"${restore(clause0, m)}?\" is read as blocking with ${blockers.joinToString(" and ") { it.card.name ?: it.id }}; the outcome says how it goes."
                return true
            }
            // "can it attack this turn?": try the attack, and the engine says whether it can.
            Regex("""^(?:it|that|(?:my |the )?(c\d+|\d+/\d+(?: [a-z]+)*)) (?:still |even )?attacks?(?: (?:this|next) turn| now| right away| at all)?$""").find(r.groupValues[1])?.let { q ->
                // Already declared as an attacker: the question is about that attack, not a second one. Declaring
                // it twice makes the answer contradict itself ("it's tapped, so it can't be declared as an attacker").
                val attacking = ctx.events.lastOrNull { it.verb == "attack" }?.obj
                val named = q.groupValues[1].takeIf { it.isNotEmpty() && cardRef.matches(it) }?.let { m.cards[it] }?.let { objectIdFor(it, ctx) }
                if (attacking != null && (named == null || named == attacking)) {
                    ctx.asks += EventSpec("ask", obj = attacking, to = "attack")
                    ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."
                    return true
                }
                // "can it attack?" about a creature the asker doesn't control: the attack is its controller's, not
                // the asker's. Read as "I attack with it" the answer was "you don't control it", never the reason asked about.
                val subjId = (if (q.groupValues[1].isEmpty()) ctx.lastMentioned
                              else q.groupValues[1].takeIf { cardRef.matches(it) }?.let { ph -> m.cards[ph] }?.let { card -> objectIdFor(card, ctx) })?.takeIf { it in ctx.objects }
                val attacker = subjId?.let { ctx.objects.getValue(it).controller } ?: "me"
                val says = if (attacker == "me") "i " else if (attacker == "opp") "they " else "@$attacker "
                return readClause(says + "attack with " + (if (q.groupValues[1].isEmpty()) "it" else q.groupValues[1]), m, ctx)
            }
            // "can I attack with it the turn I play it?" / "can I attack with Dryad Arbor the turn it comes down?"
            Regex("""^(?:i|we|they|he|she|my opponent|the opponent|@\w+) (?:still |even )?attacks? with (?:it|that|(?:my |the |their )?(c\d+|\d+/\d+(?: [a-z]+)*))( the (?:same )?turn (?:i|it|they|he|she) (?:plays? it|played it|casts? it|cast it|comes? down|came down|enters?(?: the battlefield)?|entered)| right away| immediately)?$""").find(r.groupValues[1])?.let { q ->
                val what = q.groupValues[1].ifEmpty { "it" }
                if (q.groupValues[2].isNotBlank()) {
                    val id = if (what == "it") ctx.lastMentioned?.takeIf { it in ctx.objects } else m.cards[what]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: ctx.objects.values.lastOrNull { it.card.name == what }?.id
                    if (id != null) {
                        ctx.objects[id] = ctx.objects.getValue(id).copy(summoningSick = true)
                        ctx.notes += "Read as: ${ctx.objects.getValue(id).card.name ?: id} came under its controller's control this turn."
                    }
                }
                return readClause("i attack with $what", m, ctx)
            }
            // "can my 2/2 block it?" / "can I block with a 2/2?": a described blocker, when an attack is already on the table.
            Regex("""^(?:(?:i|we|they|he|she|my opponent|the opponent|@\w+) (?:still |even )?blocks? with (?:an? |the |my |their )?((?:\d+/\d+)(?: [a-z]+)*)|(?:my |the |their |his |her )?((?:\d+/\d+)(?: [a-z]+)*) (?:still |even )?blocks?(?: it| that| the attacker)?)(?: (?:this|next) turn| now| right away| at all)?$""").find(r.groupValues[1])?.let { q ->
                val desc = q.groupValues[1].ifEmpty { q.groupValues[2] }
                // "Their Serra Angel. Can my 2/2 block it?": nobody said it attacked, but a block question only
                // makes sense against an attack, so the creature it names is read as attacking.
                val defender = Regex("""^(i|we|they|he|she|my opponent|the opponent|@\w+)\b""").find(r.groupValues[1])?.groupValues?.get(1)?.let { w ->
                    when (w) { "i", "we" -> "me"; "my opponent", "the opponent" -> ctx.other("me") ?: "opp"; else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w) }
                } ?: if (Regex("""^(?:their|his|her)\b""").containsMatchIn(r.groupValues[1])) pronounPlayer(ctx, "their") else "me"
                if (ctx.events.none { it.verb == "attack" || it.verb == "attackAll" } && ensureAttacker(ctx, defender) == null) return@let
                return readClause("blocks with a $desc", m, ctx)
            }
            Regex("""^(?:i|they|my opponent|the opponent|opponent|he|she|@\w+) (?:still |even |then )?(block)(?: with)? (?:it|that|him|her|(?:my |the |their |his |her )?(c\d+))(?: (?:this|next) turn| now| right away| at all)?$|^(?:it|that|(?:my |the |their |his |her )?(c\d+)) (?:still |even )?(block)(?: (?:this|next) turn| now| right away| at all)?$""").find(r.groupValues[1])?.let { q0 ->
                val q = object { val groupValues = listOf(q0.groupValues[0], q0.groupValues[1].ifEmpty { q0.groupValues[4] }, q0.groupValues[2].ifEmpty { q0.groupValues[3] }) }
                val id = q.groupValues[2].takeIf { it.isNotEmpty() }?.let { m.cards.getValue(it) }?.let { objectIdFor(it, ctx) ?: addObject(it, actorOfClause(r.groupValues[1]) ?: ctx.lastActor ?: "me", false, ctx) } ?: ctx.lastMentioned?.takeIf { it in ctx.objects && isCreatureName(ctx.objects.getValue(it).card.name) } ?: ctx.events.lastOrNull { it.verb == "cast" }?.targets?.firstOrNull { it in ctx.objects && isCreatureName(ctx.objects.getValue(it).card.name) } ?: ctx.objects.values.lastOrNull { isCreatureName(it.card.name) }?.id ?: ctx.lastMentioned?.takeIf { it in ctx.objects }
                if (id != null && ctx.events.none { it.verb == "attack" || it.verb == "attackAll" }) { ctx.asks += EventSpec("ask", obj = id, to = q.groupValues[1]); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true }
            }
            // "does it get -1/-1 counters?" asks about the state afterwards; it is never an instruction to put
            // them on. Read as an action, the question answered itself — the counters were there because it said so.
            if (Regex("""^(?:it|that|they|both|mine|theirs|(?:$possPrefix|an? )?(?:c\d+|\d+/\d+|creature|guy|dude|token))(?: creature)? (?:gets?|has|have|gains?|gained|is given|are given|ends? up with|keeps?|still has) .*\b(?:counters?|damage|[+-]\d+/[+-]\d+)\b.*$""").matches(r.groupValues[1])) {
                ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
            }
            if (readClause(r.groupValues[1], m, ctx)) return true
            // Not an action after all ("do I have to pay life?"): a question the outcome answers.
            if (Regex("""\b(?:have to|must|need|pay|gain|lose|take|deal|assign|draw|win|survive|die|trigger|resolve|get|keep|count|still|come back|return|fizzle|fizzles|countered|work|works|happen|happens|shuffle|search|scry|look|reveal|discard|sacrifice|tap|untap|cost|costs)\b""").containsMatchIn(r.groupValues[1])) { ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true }
            return false
        }
        // Leading connectives carry no information: "then my opponent…", "next, they…", "now I…", "so I…".
        // "Then …" / "after that …": the earlier actions have resolved before this one.
        // Unless this clause is aimed at the stack itself: "cast Bolt, then cast Twincast targeting Bolt" and
        // "cast Sol Ring and then Stifle the trigger" both need what came before still sitting there.
        val onStack = ctx.events.asReversed().takeWhile { it.verb != "resolveAll" }.filter { it.verb == "cast" }.mapNotNull { it.card?.name }.toSet()
        val aimsAtTheStack = Regex("""\b(?:triggers?|spell)\b""").containsMatchIn(clause0) ||
            Regex("""\b(?:targeting|target|targets|copy|copies|copying|counter|counters|countering|at) (?:the |that |my |their |his |her )?(c\d+)""").findAll(clause0).any { m.cards[it.groupValues[1]]?.display in onStack }
        if (Regex("""^(?:then|after that|afterwards|next|later)\b""").containsMatchIn(clause0) && !aimsAtTheStack && !Regex("""\b(?:respond|responds|responded|in response|responding|answers? with|counters?)\b""").containsMatchIn(clause0) && ctx.events.lastOrNull { it.verb != "pay" }?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
        clause0 = clause0.replace(Regex("""\s+in response to (?:nothing|no one|nobody)$"""), "")
        // "… in response to my attack (with Bears)": the attack was declared first.
        Regex("""\s+(?:in response to|after|when) (my|their|his|her|@\w+'s) (?:attack|attacking|swing|swinging|declaring attackers|attack declaration)(?: with (?:my |the )?(c\d+))?$""").find(clause0)?.let { r ->
            val attacker = when (val w = r.groupValues[1]) { "my" -> "me"; "their", "his", "her" -> pronounPlayer(ctx, "their"); else -> w.removePrefix("@").removeSuffix("'s") }
            val id = r.groupValues[2].takeIf { it.isNotEmpty() }?.let { m.cards[it] }?.let { objectIdFor(it, ctx) ?: addObject(it, attacker, false, ctx) } ?: ctx.objects.values.lastOrNull { it.controller == attacker && isCreatureName(it.card.name) }?.id
            if (id != null && ctx.events.none { it.verb == "attack" && it.obj == id }) { ctx.events += EventSpec("attack", player = attacker, obj = id, targets = listOf(ctx.other(attacker) ?: "opp")); ctx.note(ctx.other(attacker) ?: "opp") }
            clause0 = clause0.removeRange(r.range)
        }
        var c = clause0.replace(Regex("""^(?:after (?:blockers|blocks|attackers|attacks)(?: are declared)?|before (?:combat )?damage|in the (?:declare blockers|declare attackers|end|combat damage|beginning of combat) step|during (?:combat|the combat phase)|at (?:that|this) point|with (?:that|it|the trigger|the spell) on the stack|in response|after that|afterwards|as soon as|then|next|now|so|when|if|after|once|later|finally|also|meanwhile)\s*,?\s+"""), "")
        // "They have a Lightning Bolt on the stack targeting my Grizzly Bears": a spell already cast and waiting.
        // Without this the spell was filed as a card in hand and its target was dropped, quietly.
        Regex("""^(?:(?:i|they|he|she|my opponent|the opponent|@\w+) )?(?:has|have|got|'ve got) (?:an? |the )?(c\d+) on the stack(?: targeting (my |their |my opponent's |the |@\w+'s )?(c\d+))?$""").find(c)?.let { r ->
            val spell = m.cards.getValue(r.groupValues[1])
            val caster = actorOfClause(c) ?: ctx.lastActor ?: "opp"
            val tgt = r.groupValues[3].takeIf { it.isNotEmpty() }?.let { ph ->
                val card = m.cards.getValue(ph)
                val owner = when (val w = r.groupValues[2].trim()) {
                    "my" -> "me"
                    "their", "my opponent's" -> pronounPlayer(ctx, "their")
                    "", "the" -> ctx.other(caster) ?: "me"
                    else -> if (w.startsWith("@")) w.removePrefix("@").removeSuffix("'s").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) } else ctx.other(caster) ?: "me"
                }
                objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx)
            }
            emitCast(caster, spell, "", m, ctx)
            if (tgt != null) ctx.events[ctx.events.lastIndex] = ctx.events.last().copy(targets = listOf(tgt))
            // "…targeting my Bears. I cast Giant Growth on it": "it" is the permanent under threat, not the spell.
            if (tgt != null) ctx.lastMentioned = tgt
            ctx.lastActor = caster; ctx.lastVerb = "cast"; ctx.note(caster); return true
        }
        // "They deal 3 damage to me", "I take 3 damage": damage from a source nobody named, so the answer can still
        // show what prevention and replacement effects do to it.
        Regex("""^(?:(i|they|he|she|we|my opponent|the opponent|@\w+) )?(?:deals?|dealt) (\d+) damage to (me|you|them|him|her|my opponent|the opponent|@\w+|it|that|(?:$possPrefix)?c\d+|(?:my |their |the )?\d+/\d+)$|^(?:(i|they|he|she|we|my opponent|the opponent|@\w+|(?:$possPrefix)?c\d+|(?:$possPrefix)?\d+/\d+|it|that) )?(?:takes?|took) (\d+) damage$|^(?:(?:my |their |his |her |the )?(c\d+|it|that) )?(?:is|are|was|were) dealt (\d+) damage$""").find(c)?.let { r ->
            val dealt = r.groupValues[2].isNotEmpty()
            val passive = r.groupValues[7].isNotEmpty()
            val amount = (if (dealt) r.groupValues[2] else if (passive) r.groupValues[7] else r.groupValues[5]).toIntOrNull() ?: return@let
            // "My Grizzly Bears has two +1/+1 counters and takes 3 damage": a subjectless "takes N damage" carries
            // on from the permanent the sentence was about, not from the player.
            val carriesOn = !dealt && !passive && r.groupValues[4].isEmpty() && ctx.clauseIndex > 0 &&
                ctx.lastMentioned?.let { lm -> ctx.objects[lm]?.zone == "battlefield" } == true
            val victimWord = if (dealt) r.groupValues[3] else if (passive) r.groupValues[6].ifEmpty { "it" } else r.groupValues[4].ifEmpty { if (carriesOn) "it" else "me" }
            // "deals 3 damage to my Grizzly Bears" / "it is dealt 3 damage": a permanent can be the one damaged.
            val objWord = victimWord.replace(Regex("""^(?:$possPrefix)"""), "")
            val victimObj = when {
                // A subjectless "takes N damage" is about the creature the clause before it was about, which is
                // the one its actor controls — not the other creature in the same combat.
                objWord == "it" || objWord == "that" -> (if (carriesOn) ctx.lastActor?.let { who ->
                        ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).controller == who }
                            ?: ctx.objects.values.lastOrNull { it.controller == who && it.zone == "battlefield" && isCreatureName(it.card.name) }?.id
                    } else null) ?: ctx.lastMentioned?.takeIf { it in ctx.objects }
                Regex("""^c\d+$""").matches(objWord) -> m.cards[objWord]?.let { card ->
                    val owner: String = ctx.lastOwner ?: "me"
                    objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx)
                }
                Regex("""^\d+/\d+$""").matches(objWord) -> ctx.objects.values.lastOrNull { (it.card.name ?: "").startsWith("a $objWord") }?.id
                    ?: describedCreatures("a ", objWord, "creature", possessiveOwner(victimWord.removeSuffix(objWord), ctx, m) ?: ctx.lastOwner ?: "me", ctx).firstOrNull()
                else -> null
            }
            val victim = victimObj ?: when (victimWord) {
                "me", "you", "i" -> "me"
                "them", "him", "her", "my opponent", "the opponent", "they", "he", "she" -> pronounPlayer(ctx, "their")
                else -> if (victimWord.startsWith("@")) victimWord.removePrefix("@").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) } else return@let
            }
            ctx.events += EventSpec("damage", source = "a source", targets = listOf(victim), amount = amount)
            // "… and takes 1 more": the running total a bare continuation adds to.
            if (victimObj != null) { ctx.lastMentioned = victimObj; ctx.lastStat = "damage"; ctx.lastStatWho = "obj:$victimObj" }
            ctx.notes += "No source was named for the $amount damage; it is read as coming from a source nobody named, which is enough to show what prevention and replacement effects do to it."
            if (victimObj == null) ctx.note(victim) else ctx.lastMentioned = victimObj
            return true
        }
        // "I sacrifice two creatures", "three creatures die": several creatures nobody named, described only by
        // how many there are. The engine needs objects to move, so it gets that many unnamed ones.
        Regex("""^(?:(?:i|we|they|he|she|my opponent|the opponent|@\w+) )?(?:(sacrifices?|sacs?|sacrificed)|(?:my |their |his |her )?(\d+|a|an|one|two|three|four|five) (?:other )?creatures? (?:die|dies|died|are destroyed|is destroyed|get destroyed)) ?(\d+|a|an|one|two|three|four|five)? ?(?:other )?(?:creatures?)?$""").find(c)?.let { r ->
            val sacrificed = r.groupValues[1].isNotEmpty()
            val count = (if (sacrificed) r.groupValues[3] else r.groupValues[2]).takeIf { it.isNotEmpty() } ?: return@let
            val n = number(count) ?: return@let
            if (n < 1 || n > 20) return@let
            val who = actorOfClause(c) ?: (if (sacrificed) ctx.lastActor ?: "me" else ctx.lastOwner)
            val ids = describedCreatures("$n ", "", "creature", who ?: "me", ctx)
            if (ids.isEmpty()) return@let
            for (id in ids) ctx.events += if (sacrificed) EventSpec("sacrifice", player = who ?: "me", obj = id) else EventSpec("leave", obj = id, to = "graveyard")
            ctx.lastActor = who ?: ctx.lastActor; ctx.lastMentioned = ids.last(); return true
        }
        // Said, and nothing follows from it: the engine doesn't track library order, so a shuffle or a scry
        // changes nothing it can show. Read rather than dropped, with a note saying why nothing came of it.
        Regex("""^(?:(?:i|we|they|he|she|my opponent|the opponent|@\w+) )?(?:shuffles?(?: my| their| his| her| the)? (?:library|deck)|scr(?:y|ies) \d+|looks? at the top (?:\d+ )?cards? of (?:my|their|his|her|the) library|pass(?:es)?(?: the turn| priority| it back)?)$""").find(c)?.let {
            ctx.notes += "\"${restore(clause0, m)}\" is read, but the engine doesn't track library order, so nothing in the answer turns on it."
            actorOfClause(c)?.let { a -> ctx.lastActor = a; ctx.note(a) }
            return true
        }
        // "I discard a card", "they mill three cards", "I get a poison counter": no card named, so only the count
        // is known — which is still enough for hand size, library size and the poison loss condition.
        Regex("""^(?:(?:i|we|they|he|she|my opponent|the opponent|@\w+) )?(?:discards?|discarded) (an?|one|two|three|four|five|\d+) cards?(?: at random| of my choice| of their choice)?$""").find(c)?.let { r ->
            val who = actorOfClause(c) ?: ctx.lastActor ?: "me"
            ctx.events += EventSpec("discardcount", player = who, amount = number(r.groupValues[1]) ?: 1); ctx.lastActor = who; ctx.note(who); return true
        }
        Regex("""^(?:(?:i|we|they|he|she|my opponent|the opponent|@\w+) )?(?:mills?|milled) (an?|one|two|three|four|five|six|seven|eight|nine|ten|\d+) cards?$""").find(c)?.let { r ->
            val who = actorOfClause(c) ?: ctx.lastActor ?: "me"
            ctx.events += EventSpec("mill", player = who, amount = number(r.groupValues[1]) ?: 1); ctx.lastActor = who; ctx.note(who); return true
        }
        Regex("""^(?:(?:i|we|they|he|she|my opponent|the opponent|@\w+) )?(?:gives?|gave|deals?|dealt) (me|us|them|him|her|my opponent|the opponent|@\w+) (an?|one|two|three|four|five|\d+) (?:more |additional |extra |further )?poison counters?$""").find(c)?.let { r ->
            val victim = when (val w = r.groupValues[1]) { "me", "us" -> "me"; "them", "him", "her", "my opponent", "the opponent" -> pronounPlayer(ctx, "their"); else -> w.removePrefix("@").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) } }
            ctx.events += EventSpec("poison", player = victim, amount = number(r.groupValues[2]) ?: 1); ctx.note(victim); return true
        }
        Regex("""^(?:(?:i|we|they|he|she|my opponent|the opponent|@\w+) )?(?:gets?|got|gains?|gained|takes?|took|receives?|received|is dealt|are dealt|was dealt|were dealt|is given|are given) (an?|one|two|three|four|five|\d+) (?:more |additional |extra |further )?poison counters?$""").find(c)?.let { r ->
            val who = actorOfClause(c) ?: ctx.lastActor ?: "me"
            ctx.events += EventSpec("poison", player = who, amount = number(r.groupValues[1]) ?: 1); ctx.lastActor = who; ctx.note(who); return true
        }
        // "I discard Vengevine", "they discard Lightning Bolt to Liliana": a named card leaves hand for the graveyard.
        Regex("""^(?:(?:i|they|he|she|we|my opponent|the opponent|@\w+) )?discards? (?:an? |the |my |their )?(c\d+)(?: (?:to|for|with) (?:$possPrefix|an? )?c\d+)?$""").find(c)?.let { r ->
            val who = actorOfClause(c) ?: ctx.lastActor ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            val id = ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId && it.zone == "hand" && it.controller == who }?.id
                ?: addObject(card, who, false, ctx, zone = "hand", allowDuplicate = true)
            ctx.objects[id] = ctx.objects.getValue(id).copy(zone = "hand")
            ctx.events += EventSpec("discard", player = who, obj = id)
            ctx.lastActor = who; ctx.lastVerb = "discard"; ctx.lastMentioned = id; ctx.note(who); return true
        }
        // "… in response to them tapping Sol Ring for mana": the mana ability happens first (and can't be responded to, as the engine will say).
        Regex("""\s+in response to (?:them|my opponent|the opponent|me|@\w+) tapping (?:an? |the |their |my )?(c\d+|it)(?: for mana| for \{.*)?$""").find(c)?.let { r ->
            val ph = if (r.groupValues[1] == "it") Regex("""(?:on|targeting|at) (?:their |my |the |an? )?(c\d+)""").find(c)?.groupValues?.get(1) ?: return@let else r.groupValues[1]
            val card = m.cards.getValue(ph); val tapper = if (Regex("""in response to me""").containsMatchIn(c)) "me" else ctx.other(actorOfClause(c) ?: ctx.lastActor ?: "me") ?: "opp"
            val id = objectIdFor(card, ctx) ?: addObject(card, tapper, false, ctx)
            ctx.events += EventSpec("activate", player = tapper, obj = id, to = "mana"); ctx.notes += "A mana ability doesn't use the stack, so nothing can be done \"in response\" to it; the ${card.display} activation simply happens first (605.3b)."
            c = c.removeRange(r.range)
        }
        // "… in response to Lightning Bolt": that spell was cast first, by the other player, and is still on the stack.
        Regex("""\s+in response to (?:an? |the |their |my |my opponent's |the opponent's |opponent's |@\w+'s )?(c\d+)(?:'s)?(?: (?:targeting|on|at|aimed at) (?:it|that|itself|(?:$possPrefix)?c\d+))?$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[1])
            c = c.removeRange(r.range)
            if (card.display !in ctx.castCards) {
                val caster = ctx.other(actorOfClause(c) ?: ctx.lastActor ?: "me") ?: "opp"
                // What was that spell aimed at? Read the response's own target as the best guess ("Swords on my Angel in response to Bolt").
                val guess0 = Regex("""(?:targeting|target|on|at)\s+(?:my |their |the |an? |own |my own )*(c\d+)""").find(c)?.let { t -> m.cards[t.groupValues[1]]?.let { objectIdFor(it, ctx) ?: addObject(it, actorOfClause(c) ?: ctx.lastActor ?: "me", false, ctx) } }
                    ?: Regex("""^(?:i |they |@\w+ )?(?:sacrifices?|sacs?|regenerates?|pumps?|protects?|saves?|blinks?|flickers?|bounces?) (?:it|itself|(?:the |my )?(c\d+))""").find(c)?.let { g ->
                        if (g.groupValues[1].isNotEmpty()) m.cards[g.groupValues[1]]?.let { objectIdFor(it, ctx) ?: addObject(it, actorOfClause(c) ?: ctx.lastActor ?: "me", false, ctx) }
                        else ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.lastOrNull { it.verb == "cast" }?.card?.name?.let { n -> ctx.objects.values.firstOrNull { it.card.name == n }?.id ?: slug(n) }
                    }
                // The thing being responded to was cast after whatever this player did just before ("I cast Elder and sacrifice it in response to Bolt").
                if (ctx.events.lastOrNull()?.let { it.verb in setOf("cast", "activate") && it.player != caster } == true) ctx.events += EventSpec("resolveAll")
                var guess = guess0
                if (guess == null && needsSpellTarget(card)) {
                    val responder = ctx.other(caster) ?: "me"
                    ctx.events += EventSpec("cast", player = responder, card = CardRef(name = "a spell")); guess = "a_spell:spell"
                    ctx.notes += "${card.display} needs a spell to counter and none was mentioned; assuming it targets a spell of ${if (responder == "me") "yours" else (ctx.players[responder] ?: "your opponent") + "'s"}."
                }
                ctx.events += EventSpec("cast", player = caster, card = CardRef(name = card.display, oracleId = card.oracleId), targets = listOfNotNull(guess)); ctx.castCards += card.display; ctx.note(caster)
                if (guess != null && guess != "a_spell:spell") ctx.notes += "${card.display} was read as targeting ${ctx.objects[guess]?.card?.name ?: ctx.events.lastOrNull { it.verb == "cast" && it.card != null && slug(it.card.name ?: "") == guess }?.card?.name ?: guess} (the thing the response protects); say otherwise if it targeted something else."
            }
        }
        c = c.replace(Regex("""^(@\w+|i|they|he|she|we|my opponent|the opponent|opponent) (?:then|also|now|next|just|simply|instead|immediately|still|even) """), "$1 ")
        var actor: String? = null
        // A named player: "@alice casts…", "@bob's c1 …" (the possessive is left for the possession rules below).
        Regex("""^@(\w+)(?!'s)\b""").find(c)?.let { r ->
            actor = r.groupValues[1]; ctx.players.putIfAbsent(actor!!, m.players[actor!!] ?: actor!!); ctx.lastNamed = actor
            c = c.removeRange(r.range).trim()
        }
        if (actor == null) actorOpp.find(c)?.let { r -> actor = pronounPlayer(ctx, (1..3).firstNotNullOfOrNull { g -> r.groupValues[g].takeIf { it.isNotEmpty() } } ?: "they"); c = c.removeRange(r.range).trim() }
        if (actor == null) actorMe.find(c)?.let { actor = "me"; ctx.usesMe = true; c = c.removeRange(it.range).trim() }
        if (actor == null && c.startsWith("in response")) actor = ctx.other(ctx.lastActor)
        actor?.let { ctx.note(it) }
        ctx.clauseActor = actor
        val subject = actor ?: ctx.lastActor
        // Read before the attack rules below: "a 1/1 blocks it" is a block, and a rule looking for a described
        // attacker would otherwise take the 1/1 for a second attacker on the same side.
        // "My 3/3 with first strike blocks a 3/3": the keywords may be said on either creature, and either side
        // may be the one named first; without the "with …" tail the whole clause went unread.
        val kwList = """$kwPhrase(?:(?:,|,? and|,? &) $kwPhrase)*"""
        Regex("""^($possPrefix|an? )?(c\d+|it|that|they|\d+/\d+|creature|guy|dude|token|blocker)(?: creature)?(?: with ($kwList))? (?:chump[- ]?)?blocks? ($possPrefix|an? )?(?:(c\d+)|(\d+/\d+|creature|guy|dude|token|attacker)((?: (?!with\b)[a-z]+)*)(?: creature)?(?: with ($kwList))?|(it|that))$""").find(c)?.let { r ->
            val blockerRef = r.groupValues[2]
            val attackEvent = ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }
            val blocker = when {
                blockerRef in setOf("it", "that", "they") -> ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                Regex("""^\d+/\d+$""").matches(blockerRef) || blockerRef in setOf("creature", "guy", "dude", "token", "blocker") -> {
                    // "A 3/3 blocks my 3/3": the side is said on the attacker, so the blocker is the other one.
                    val atkSide = possessiveOwner(r.groupValues[4], ctx, m)
                    val defender = possessiveOwner(r.groupValues[1], ctx, m) ?: atkSide?.let { ctx.other(it) } ?: actor ?: ctx.other(attackEvent?.player ?: "opp") ?: "me"
                    val pt = blockerRef.takeIf { Regex("""^\d+/\d+$""").matches(it) } ?: ""
                    ctx.objects.values.lastOrNull { it.controller == defender && it.zone == "battlefield" && isCreatureName(it.card.name) && (pt.isEmpty() || (it.card.name ?: "").startsWith("a $pt")) }?.id
                        ?: describedCreatures("a ", pt, "creature", defender, ctx, r.groupValues[3].replace(" and ", ", ").replace(" & ", ", ")).firstOrNull() ?: return@let
                }
                else -> m.cards[blockerRef]?.let { objectIdFor(it, ctx) ?: addObject(it, possessiveOwner(r.groupValues[1], ctx, m) ?: possessiveOwner(r.groupValues[4], ctx, m)?.let { a -> ctx.other(a) } ?: "me", false, ctx) } ?: return@let
            }
            val who = ctx.objects[blocker]?.controller ?: "me"
            val foe = ctx.other(who) ?: "opp"
            val attacker = when {
                r.groupValues[9].isNotEmpty() -> attackEvent?.obj ?: return@let
                r.groupValues[5].isNotEmpty() -> m.cards[r.groupValues[5]]?.let { objectIdFor(it, ctx) ?: addObject(it, foe, false, ctx) } ?: return@let
                else -> {
                    val trailer = r.groupValues[7].trim()
                    val kws = (Regex("""$kwNouns|flying|trample|deathtouch|lifelink|first strike|double strike|menace|vigilance|indestructible|infect|wither""").findAll(trailer)
                        .map { k -> k.value.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample") }.toList()
                        + r.groupValues[8].split(Regex(""",| and | & """)).map { it.trim() }.filter { it.isNotEmpty() }).distinct().joinToString(", ")
                    val kind = Regex("""$creatureKinds""").find(trailer)?.value ?: "creature"
                    val pt = r.groupValues[6].takeIf { Regex("""^\d+/\d+$""").matches(it) } ?: ""
                    ctx.objects.values.lastOrNull { it.controller == foe && it.zone == "battlefield" && isCreatureName(it.card.name) && (pt.isEmpty() || (it.card.name ?: "").startsWith("a $pt")) }?.id
                        ?: describedCreatures("a ", pt, kind, foe, ctx, kws).firstOrNull() ?: return@let
                }
            }
            if (attacker == blocker) return@let
            if (ctx.events.none { it.verb == "attack" && it.obj == attacker }) {
                ctx.events += EventSpec("attack", player = foe, obj = attacker, targets = listOf(who))
                ctx.notes += "${ctx.objects[attacker]?.card?.name ?: attacker} is read as attacking ${if (who == "me") "you" else who}, since something blocked it."
            }
            ctx.events += EventSpec("block", player = who, obj = blocker, targets = listOf(attacker))
            ctx.lastActor = who; ctx.lastVerb = "block"; ctx.note(who); ctx.note(foe); return true
        }
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
                // Anything else after the card ("@alice's Bears has a Rancor on it") belongs to another rule.
                // Claiming the clause here dropped the rest of it without saying so; the permanent is on the
                // battlefield either way, and the rules that read a possessive take the clause from here.
                // A tail the state words above already applied ("has 3 +1/+1 counters on it") is not passed on,
                // or the counters would be put on twice.
                if (!Regex("""(?:with|at|has|having|and) (?:\d+|\w+) (?:loyalty|(?:[+-]\d+/[+-]\d+|[a-z]+) counters?|damage)\b""").containsMatchIn(tail) &&
                    !Regex("""\b(?:summoning sick|summoning sickness|just (?:played|cast)|played this turn|cast this turn|has been out|from last turn)\b""").containsMatchIn(tail))
                    return@let
            }
            return true
        }

        // "… and 51 life" / "at 51 life" / "I'm at 20": a life total for the actor, with or without the word.
        Regex("""^(?:(?:at|with|on|am at|is at|are at) (-?\d+)|(?:at |with |on |am at |is at |are at )?(-?\d+) life)$""").find(c)?.let { r -> val who = actor ?: ctx.lastActor ?: "me"; ctx.life[who] = (r.groupValues[1].ifEmpty { r.groupValues[2] }).toInt(); ctx.note(who); ctx.lastStat = "life"; ctx.lastStatWho = who; return true }
        // "I control Platinum Angel and go to -5 life": said as a second clause the subject is left out, so the
        // sentence-level reading of "I go to N life" never saw it and the life change was dropped.
        Regex("""^(?:go(?:es)?|went|drops?|dropped|falls?|fell)(?: down)? to (-?\d+)(?: life)?$""").find(c)?.let { r ->
            val who = actor ?: ctx.lastActor ?: "me"; val to = r.groupValues[1].toInt()
            ctx.note(who); ctx.lastStat = "life"; ctx.lastStatWho = who
            // Said after something has already happened it is where that player ends up, not where they started:
            // set at setup, a spell cast in the same sentence would resolve after the game had already ended.
            if (ctx.events.any { it.verb !in setOf("resolveAll", "ask") }) {
                // "I cast Angel's Grace and go to 0 life": the life total is where the player stands once the
                // spell has resolved, not while it is still on the stack.
                if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
                ctx.events += EventSpec("setLife", player = who, amount = to); return true
            }
            ctx.life[who] = to; return true
        }
        // "my opponent gains control of my creature", "they steal my Bears": a control change with no card
        // named. Unread, the creature stayed on the asker's side and the question about it was answered wrong.
        Regex("""^(?:gains?|gained|takes?|took|steals?|stole) (?:control of )?(?:$possPrefix|an? |the )?(c\d+|\d+/\d+|creature|guy|dude|it|that)(?: creature)?(?: (?:until end of turn|this turn|for the turn|permanently))?$""").find(c)?.let { r ->
            if (!Regex("""^(?:gains?|gained|takes?|took|steals?|stole) (?:control|my|their|the|an?|c\d+|\d+/\d+|it|that)""").containsMatchIn(c)) return@let
            val who = actor ?: ctx.lastActor ?: "opp"
            val from = ctx.other(who) ?: "me"
            val ph = r.groupValues[1]
            val id = when {
                ph in setOf("it", "that") -> ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                cardRef.matches(ph) -> m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, from, false, ctx) } ?: return@let
                Regex("""^\d+/\d+$""").matches(ph) -> ctx.objects.values.lastOrNull { it.controller == from && (it.card.name ?: "").startsWith("a $ph") }?.id
                    ?: describedCreatures("a ", ph, "creature", from, ctx).firstOrNull() ?: return@let
                else -> ctx.objects.values.lastOrNull { it.controller == from && isCreatureName(it.card.name) }?.id
                    ?: describedCreatures("a ", "", "creature", from, ctx).firstOrNull() ?: return@let
            }
            val eot = Regex("""\b(?:until end of turn|this turn|for the turn)\b""").containsMatchIn(c)
            ctx.events += EventSpec("gainControl", player = who, obj = id, to = if (eot) "eot" else null)
            ctx.lastActor = who; ctx.lastMentioned = id; ctx.note(who); return true
        }
        // "I lose the flip" / "they win the coin flip" / "the coin comes up tails": a coin flip the asker settled.
        Regex("""^(?:lose[sd]?|los[et]|win[s]?|won)(?: the)?(?: coin)? flip$|^(?:the )?(?:coin )?flip is (?:lost|won)$|^(?:the coin )?comes? up (?:heads|tails)$""").find(c)?.let {
            val lost = Regex("""\blos|\btails\b""").containsMatchIn(c)
            ctx.events += EventSpec("flip", player = actor ?: ctx.lastActor ?: "me", to = if (lost) "lose" else "win"); return true
        }
        // "they target me with Thoughtseize": the spell is cast at that player.
        Regex("""^(?:targets?|targeted|aims? at|points? at) (me|them|him|her|my opponent|the opponent|@\w+) with (?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            val caster = actor ?: ctx.lastActor ?: "opp"
            val victim = when (val w = r.groupValues[1]) { "me" -> "me"; "them", "him", "her", "my opponent", "the opponent" -> pronounPlayer(ctx, w.substringAfterLast(' ')); else -> w.removePrefix("@").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) } }
            val card = m.cards[r.groupValues[2]] ?: return@let
            emitCast(caster, card, " targeting $victim", m, ctx); ctx.note(victim); return true
        }
        // "tries to Murder it", "attempts to cast Bolt on it": the attempt is the action.
        // "goes to combat" is a step, not an attempt to do something: without the guard "goes to" was stripped and
        // the step word was left as a clause of its own.
        Regex("""^(?:try|tries|tried|attempt|attempts|attempted|want|wants|goes|go) to (?!(?:my |their |his |her |the )?(?:combat|upkeep|draw step|end step|end of turn|main phase|combat phase|second main phase)\b)(.+)$""").find(c)?.let { r ->
            if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")   // the attempt comes after what was already happening
            return readClause((actor?.let { if (it == "me") "i " else if (it == "opp") "they " else "@$it " } ?: "") + r.groupValues[1], m, ctx)
        }
        // "activate its first ability" / "use the second ability of Deathrite Shaman": the ability picked by its place in the text.
        Regex("""^(?:$activateVerbs)\s+(?:its|the|his|her|their|my)? ?(first|second|third|fourth|1st|2nd|3rd|4th) ability(?: of (?:an? |the |my |their )?(c\d+))?(.*)$|^(?:$activateVerbs)\s+(?:an? |the |my |their )?(c\d+)'s (first|second|third|fourth) ability(.*)$""").find(c)?.let { r ->
            val whichWord = r.groupValues[1].ifEmpty { r.groupValues[5] }
            val which = mapOf("first" to 0, "1st" to 0, "second" to 1, "2nd" to 1, "third" to 2, "3rd" to 2, "fourth" to 3, "4th" to 3)[whichWord] ?: return@let
            val who = actor ?: subject ?: "me"
            val ph = r.groupValues[2].ifEmpty { r.groupValues[4] }
            val id = if (ph.isNotEmpty()) m.cards.getValue(ph).let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
                     else ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).zone == "battlefield" }
                         ?: ctx.objects.values.lastOrNull { it.controller == who && it.zone == "battlefield" }?.id ?: return@let
            ctx.events += EventSpec("activate", player = who, obj = id, abilityIndex = which, targets = targetsIn(r.groupValues[3].ifEmpty { r.groupValues[6] }, m, ctx))
            ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        // "choosing mode 1" left on its own: a card name that ends in "!" splits the sentence there, so the modes
        // arrive as a clause of their own and the spell was cast with no mode chosen at all.
        Regex("""^(?:choosing|picking|selecting|taking)(?: the)? (?:mode|modes|option) ?(\d+(?:\s*(?:,|and|&)\s*\d+)*)(.*)$""").find(c)?.let { r ->
            val i = ctx.events.indexOfLast { it.verb == "cast" && it.modes.isEmpty() }
            if (i < 0) return@let
            // "targeting it" here means a permanent, not the spell that was just cast and is what "it" last named.
            val tg = targetsIn(r.groupValues[2].trim(), m, ctx).filter { it in ctx.objects }
                .ifEmpty { if (Regex("""\b(?:it|that|them)\b""").containsMatchIn(r.groupValues[2])) listOfNotNull(ctx.objects.values.lastOrNull { o -> o.zone == "battlefield" }?.id) else emptyList() }
            ctx.events[i] = ctx.events[i].copy(modes = Regex("""\d+""").findAll(r.groupValues[1]).map { it.value.toInt() }.toList(),
                targets = if (tg.isEmpty()) ctx.events[i].targets else ctx.events[i].targets + tg)
            return true
        }
        // "my opponent is the monarch": whoever it is keeps drawing at their end step until someone takes it (725.1).
        Regex("""^(?:(i|we|they|he|she|my opponent|the opponent|opponent|@\w+) )?(?:is|am|are|becomes?|became|'m|'re) the monarch$|^(?:(i|we|they|he|she|my opponent|the opponent|opponent|@\w+) )?(?:has|have) the monarch(?:ship)?$""").find(c)?.let { r ->
            val said = r.groupValues[1].ifEmpty { r.groupValues[2] }.let { w -> when (w) { "" -> null; "i", "we" -> "me"; "my opponent", "the opponent", "opponent" -> pronounPlayer(ctx, "their"); else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w) } }
            val who = said ?: actor ?: ctx.lastActor ?: "me"
            ctx.events += EventSpec("monarch", player = who); ctx.note(who); return true
        }
        // "it triggers" / "its ability triggers" / "the Blood Artist trigger goes off": the permanent's triggered
        // ability, said out loud. When something has already happened the engine put the trigger on the stack
        // itself and saying so again would put a second one there; with nothing else described it is the event.
        Regex("""^(?:(c\d+|it|that|they)(?:'s)? )?(?:(?:the|its|his|her|their) )?(?:triggered )?(?:ability |abilities )?(?:triggers?|triggered|goes off|go off|went off|fires?|fired)$""").find(c)?.let { r ->
            if (ctx.events.isNotEmpty()) {
                // "does Blood Artist trigger?" is often the first time that card is named: it has to be on
                // the battlefield for the engine to have anything to trigger for what was described.
                r.groupValues[1].takeIf { cardRef.matches(it) }?.let { ph -> m.cards[ph] }
                    ?.takeIf { objectIdFor(it, ctx) == null }
                    ?.let { card -> ctx.lastMentioned = addObject(card, actor ?: ctx.lastOwner, false, ctx) }
                ctx.notes += "\"${restore(clause0, m)}\" is read as the trigger the engine already puts on the stack for what was described; it isn't added a second time."
                return true
            }
            val ph = r.groupValues[1]
            val id = (if (cardRef.matches(ph)) m.cards[ph]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, actor ?: ctx.lastOwner, false, ctx) }
                      else ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).zone == "battlefield" }) ?: return@let
            ctx.events += EventSpec("trigger", obj = id); ctx.lastMentioned = id; return true
        }
        // "activate it (targeting X)": the last-mentioned permanent's ability.
        // "use her +1" is a loyalty ability, read further down; this rule must not take it first.
        // "activate it for green" names a colour the ability asks for: that rule is further down and needs the whole clause.
        // "I equip it": equipping is an Equipment's ability aimed at a creature you control, so neither "it" is
        // just the last permanent named — read that way it activated the creature and targeted the opponent's.
        Regex("""^(?:equips?|equipping)(?: (?:it|that|them))?$""").find(c)?.let {
            val who = actor ?: subject ?: "me"
            fun isEquipment(o: ObjectSpec) = o.card.name?.let { n -> names.lookup(Names.normalize(n))?.typeLine?.contains("Equipment", true) } == true
            val eq = ctx.objects.values.lastOrNull { it.controller == who && it.zone == "battlefield" && isEquipment(it) } ?: return@let
            val crea = ctx.objects.values.lastOrNull { it.controller == who && it.zone == "battlefield" && it.id != eq.id && isCreatureName(it.card.name) } ?: return@let
            ctx.events += EventSpec("activate", player = who, obj = eq.id, targets = listOf(crea.id))
            ctx.notes += "\"${restore(clause0, m)}\" is read as equipping ${eq.card.name} to ${crea.card.name}."
            ctx.lastActor = who; ctx.lastMentioned = crea.id; return true
        }
        Regex("""^(?:$activateVerbs)\s+(?:it|that|him|her|them|its ability|his ability|her ability|it's ability)\b(?!\s*[+\u2212-]?\d)(?!\s+(?:choosing|naming|picking|for|on) (?:white|blue|black|red|green|colou?rless)\b)(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            // "I control Mutavault and they control Serra Angel. I activate it": you can only activate your own,
            // so "it" is the actor's permanent, not whichever one was named last.
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).controller == who }
                ?: ctx.objects.values.lastOrNull { it.controller == who && it.zone == "battlefield" }?.id
                ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return false
            val timesRe = Regex("""\b(twice|two times|three times|four times|five times|(\d+) times)\b""")
            val times = timesRe.find(r.groupValues[1])?.let { t -> if (t.groupValues[1].startsWith("twice")) 2 else number(t.groupValues[2].ifEmpty { t.groupValues[1].substringBefore(' ') }) ?: 1 } ?: 1
            val targets = targetsIn(r.groupValues[1].replace(timesRe, "").trim(), m, ctx)
            repeat(times) { ctx.events += EventSpec("activate", player = who, obj = id, targets = targets) }
            ctx.lastActor = who; return true
        }
        // Read before isNoise: "is dealt 2 more" opens with a question word and would be dropped as noise.
        // "… and I get 2 more" / "… and loses 2 more": the running total the clause before it stated
        // ("8 poison counters", "3 +1/+1 counters", "5 life"), with the direction the verb gives it.
        Regex("""^(gets?|got|gains?|gained|takes?|took|receives?|received|is dealt|are dealt|was dealt|were dealt|loses?|lost|drops?) (an?|one|two|three|four|five|\d+) more(?: (damage|life|poison counters?))?$""").find(c)?.let { r ->
            val stat = r.groupValues[3].ifEmpty { ctx.lastStat ?: return@let }
            // "takes 1 more damage": damage stacks on the permanent the sentence was about, and without this the
            // second hit was dropped and a creature with lethal damage on it was answered as surviving.
            if (stat == "damage") {
                val id = (ctx.lastStatWho?.removePrefix("obj:")?.takeIf { it in ctx.objects && ctx.lastStatWho!!.startsWith("obj:") }
                    ?: ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).zone == "battlefield" }) ?: return@let
                val n = number(r.groupValues[2]) ?: return@let
                ctx.events += EventSpec("damage", source = "a source", targets = listOf(id), amount = n)
                ctx.lastMentioned = id; ctx.lastStat = "damage"; ctx.lastStatWho = "obj:$id"; return true
            }
            val losing = r.groupValues[1].startsWith("lo") || r.groupValues[1].startsWith("drop")
            if (losing && stat != "life") return@let      // "loses 2 more poison counters" isn't a thing
            val who = actor ?: subject ?: ctx.lastStatWho ?: return@let
            val said = if (who.startsWith("obj:")) "it " else if (who == "me") "i " else if (who == "opp") "they " else "@$who "
            val verb = if (losing) "loses" else if (stat == "life") "gains" else "gets"
            return readClause("$said$verb ${r.groupValues[2]} $stat", m, ctx)
        }
        // Questions with no card in them carry no state ("do they get a land?"); "doesn't block" / "no blocks" / "takes it" mean no block.
        // A card-less question can still be about something ("what are its stats?", "does it survive?"): asked before it's dropped as noise.
        if (isNoise(c)) { askQuestion(clause0, m, ctx); return true }
        if (Regex("""^(?:(?:doesn't|does not|don't|do not|didn't|won't|declines? to|chooses? not to) block\b.*|no blocks?|takes? (?:it|the damage|the hit|the other|the other one|the rest|the others)|lets? (?:it|the other|the others|the rest) through)$""").matches(c)) {
            val who = actor ?: ctx.other(ctx.events.lastOrNull { it.verb == "attack" }?.player) ?: ctx.lastActor
            val plain = Regex("""^(?:(?:doesn't|does not|don't|do not|didn't|won't|declines? to|chooses? not to) block(?: at all| this turn| it| that| the attacker| anything)?|no blocks?|takes? (?:it|the damage|the hit)|lets? it through)$""").matches(c)
            if (plain && ctx.events.removeAll { it.verb == "block" && (who == null || it.player == who) }) ctx.notes += "\"${restore(clause0, m)}\": the block read from an earlier clause is taken back."
            ctx.lastActor = actor ?: ctx.lastActor; return true
        }
        // "has no creatures" / "have no blockers": nothing to add to the board.
        // "my opponent has no untapped lands": nothing to add to the board, but it does say there is no mana,
        // which is what a tax or a response is checked against.
        Regex("""^(?:(?:has|have|got|controls?) )?no (untapped lands?|untapped permanents?|untapped mana sources?|mana(?: available| open| up)?|lands? untapped|mana sources? untapped)(?: on the battlefield| in play| out| at all| left)?$""").find(c)?.let {
            val who = actor ?: ctx.lastActor ?: "me"
            ctx.mana[who] = 0; ctx.note(who)
            ctx.notes += "${if (who == "me") "You have" else (ctx.players[who] ?: "Your opponent") + " has"} no mana available; costs are checked against that."
            if (actor != null) ctx.lastActor = actor; return true
        }
        if (Regex("""^(?:(?:has|have|got|controls?) )?no (?:creatures?|blockers?|permanents?|other creatures?|untapped creatures?|flyers?|fliers?|lands?|artifacts?|enchantments?|planeswalkers?)(?: on the battlefield| in play| out| at all)?$""").matches(c)) { if (actor != null) ctx.lastActor = actor; return true }
        // "I have Kird Ape and no Forest": a card the player says they don't control. Nothing is put on the
        // battlefield for it, which is the state already — but saying so out loud left the clause unread.
        if (Regex("""^(?:(?:has|have|got|controls?) )?no (c\d+|forests?|islands?|mountains?|plains|swamps?)(?:s)?(?: on the battlefield| in play| out| at all| anywhere)?$""").matches(c)) { if (actor != null) ctx.lastActor = actor; return true }
        // "with a regeneration shield", "regenerated X", "X is regenerated": a regeneration shield on that permanent.
        Regex("""^(?:regenerates? |regenerated |gives? (?:a )?regeneration (?:shield )?to |activates? regeneration on )(?:an? |the |my |their )?(c\d+|it|that|that creature)$""").find(c)?.let { r ->
            val id = if (r.groupValues[1].startsWith("c")) m.cards.getValue(r.groupValues[1]).let { card -> objectIdFor(card, ctx) ?: addObject(card, actor ?: ctx.lastOwner, false, ctx) }
                     // "it blocks a 3/3. I regenerate it": "it" is the one being regenerated, which is the actor's,
                     // not the attacker they happened to name last — the shield went on the wrong creature.
                     else ctx.lastMentioned?.takeIf { it in ctx.objects && isCreatureName(ctx.objects.getValue(it).card.name) && (actor == null || ctx.objects.getValue(it).controller == actor) }
                        ?: ctx.objects.values.lastOrNull { (actor == null || it.controller == actor) && isCreatureName(it.card.name) }?.id ?: return@let
            ctx.events += EventSpec("regenerate", obj = id); return true
        }
        // "sacrifice a Bears (to Viscera Seer)": the sacrifice, then the ability it paid for.
        // "sacrifice a land" / "sac an artifact to it": the cost of an ability of a permanent already out.
        Regex("""^(?:sacrifices?|sacs?|sacrificing|saccing) (?:an? |one |another )(land|creature|artifact|enchantment|permanent|token)(?: (?:to|into|with) (?:it|that|(?:my |the )?(c\d+))(?:'s ability)?)?(?: to (?:protect|save|shroud|shield) (?:it|that|them|(?:my |the )?(c\d+)))?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val kind = r.groupValues[1]
            // "My Gitrog Monster and I sacrifice a land": with no outlet named, the sacrifice is just a
            // sacrifice. Read as the cost of some permanent's ability it was paid to a card that has none.
            val answering = ctx.events.lastOrNull { it.verb == "cast" && it.player != who }?.targets?.firstOrNull { it in ctx.objects }
            if (answering == null && !Regex("""\s(?:to|into|with)\s+(?:it|that|(?:my |the )?c\d+)|\sto (?:protect|save|shroud|shield)\b""").containsMatchIn(r.groupValues[0])) {
                val id = ctx.objects.values.lastOrNull { o -> o.controller == who && o.zone == "battlefield" &&
                        (if (kind == "land") (o.card.name ?: "").contains("land", true) else if (kind == "creature") isCreatureName(o.card.name) else (o.card.name ?: "").contains(kind, true)) }?.id
                    ?: if (kind == "creature") describedCreatures("a ", "", "creature", who, ctx).firstOrNull()
                       else {
                           var nid = slug("a $kind"); var k = 2; while (ctx.objects.containsKey(nid)) nid = slug("a $kind") + "_" + (k++)
                           ctx.objects[nid] = ObjectSpec(nid, CardRef(name = if (kind == "land") "a basic land" else "a $kind"), controller = who)
                           ctx.notes += "No $kind was described, so one is taken as read for the sacrifice."
                           nid
                       }
                if (id != null) { ctx.events += EventSpec("sacrifice", player = who, obj = id); ctx.lastActor = who; ctx.lastMentioned = id; return true }
            }
            val named = r.groupValues[2].takeIf { it.isNotEmpty() }?.let { m.cards[it] }?.let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
            // The outlet is the other permanent that player controls; the engine checks whether the cost can actually be paid.
            val protectedId = ctx.events.lastOrNull { it.verb == "cast" && it.player != who }?.targets?.firstOrNull { it in ctx.objects }
            val outlet = named ?: ctx.objects.values.firstOrNull { o -> o.controller == who && o.id != protectedId && o.card.oracleId != null }?.id ?: return@let
            val protectPh = r.groupValues[3]
            val target = if (protectPh.isNotEmpty()) m.cards[protectPh]?.let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
                         else protectedId ?: ctx.lastMentioned?.takeIf { it in ctx.objects && it != outlet }
            // Nothing of that kind was described, so one is taken as read: the cost has to be payable for the question to make sense.
            if (kind == "land" && ctx.objects.values.none { it.controller == who && (it.card.name ?: "").contains("land", true) }) {
                var lid = slug("a land"); var k = 2; while (ctx.objects.containsKey(lid)) lid = slug("a land") + "_" + (k++)
                ctx.objects[lid] = ObjectSpec(lid, CardRef(name = "a basic land"), controller = who)
                ctx.notes += "No land was described, so one is taken as read for the sacrifice."
            }
            ctx.events += EventSpec("activate", player = who, obj = outlet, to = "sacrifice:$kind", targets = listOfNotNull(target))
            ctx.notes += "\"Sacrifice a $kind\" is read as the cost of ${ctx.objects[outlet]?.card?.name ?: outlet}'s ability."
            ctx.lastActor = who; return true
        }
        Regex("""^(?:sacrifices?|sacs?|sacrificing|saccing) (?:an? |the |my |one |another )?(c\d+|it|itself|(?:\d+/\d+)(?: (?!to\b|into\b|targeting\b|at\b|on\b)[a-z]+)*)(?: (?:to|into) (?:an? |the |my )?(c\d+|it|that)(?:'s ability)?)?(.*)$""").find(c.replace(Regex("""\s+(?:in response(?: to (?:it|that|the spell))?|for mana|for value|instead|first|before it resolves|with (?:the )?(?:trigger|spell) on the stack)(?=\s|$)"""), ""))?.let { r ->
            val who = actor ?: subject ?: "me"
            val what = r.groupValues[1]
            // "I sacrifice it to Viscera Seer": the Seer is the outlet, never the thing being fed to it. Without
            // this "it" took the last thing named — the Seer — and the answer sacrificed the outlet to itself.
            val outletId = r.groupValues[2].takeIf { cardRef.matches(it) }?.let { ph -> m.cards[ph]?.let { objectIdFor(it, ctx) } }
            // Only your own permanents can be sacrificed, so "it" is the actor's rather than the last one named.
            val id = if (what == "it" || what == "itself") (ctx.lastMentioned?.takeIf { it in ctx.objects && it != outletId && ctx.objects.getValue(it).controller == who } ?: ctx.events.lastOrNull { it.verb == "cast" && it.player == who }?.card?.name?.let { slug(it) }?.takeIf { it != outletId } ?: ctx.events.lastOrNull { it.verb == "cast" || it.verb == "activate" }?.targets?.firstOrNull { it in ctx.objects && it != outletId && ctx.objects.getValue(it).controller == who } ?: ctx.objects.values.lastOrNull { it.controller == who && it.id != outletId }?.id ?: return@let)
                     else if (Regex("""^\d+/\d+""").containsMatchIn(what)) {
                         // "sacrifice a 2/2": a creature nobody named, already on the battlefield or described now.
                         val pt = Regex("""^(\d+/\d+)""").find(what)!!.groupValues[1]
                         ctx.objects.values.firstOrNull { it.controller == who && it.card.name == "a $pt creature" }?.id
                             ?: describedCreatures("a ", pt, "creature", who, ctx, "").firstOrNull() ?: return@let
                     }
                     else m.cards.getValue(what).let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) }
            if (r.groupValues[2].isNotEmpty()) {
                // "sacrifice Bears to Ashnod's Altar" / "to it": the outlet's ability, the sacrifice being its cost.
                val oid = if (r.groupValues[2] == "it" || r.groupValues[2] == "that") (ctx.lastMentioned?.takeIf { it in ctx.objects && it != id } ?: ctx.objects.values.lastOrNull { it.controller == who && it.id != id }?.id ?: return@let)
                          else m.cards.getValue(r.groupValues[2]).let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
                ctx.events += EventSpec("activate", player = who, obj = oid, to = "sacrifice:$id", targets = targetsIn(r.groupValues[3], m, ctx)); ctx.lastActor = who; ctx.lastMentioned = oid; return true
            }
            // "sacrifice Hexmage targeting my Bears": the target belongs to the "Sacrifice this:" ability the sacrifice pays for.
            ctx.events += EventSpec("sacrifice", player = who, obj = id, targets = targetsIn(r.groupValues[3], m, ctx))
            ctx.lastActor = who; return true
        }
        // "I control three artifacts", "they have two enchantments": permanents nobody named, counted. Cards that
        // count a type ("metalcraft") need the count to be there; without this the clause went unread.
        // "I control a commander": which card it is doesn't matter, only that its controller has one, which is
        // what the free-spell cycle (Deflecting Swat, Fierce Guardianship) asks about.
        Regex("""^(?:controls?|controlling|have|has|got)\s+(?:an?|one|my|their|his|her) commander(?: on the battlefield| in play| out)?$""").find(c)?.let {
            val who = actor ?: subject ?: "me"
            var id = "commander"; var k = 2; while (ctx.objects.containsKey(id)) id = "commander_" + (k++)
            ctx.objects[id] = ObjectSpec(id, CardRef(name = "a commander"), controller = who, commander = true)
            ctx.notes += "${if (who == "me") "You control" else "They control"} a commander; it stands in for whichever card it is, as a 2/2 legendary creature."
            ctx.lastMentioned = id; ctx.lastOwner = who; ctx.lastActor = who; ctx.note(who); return true
        }
        // "their planeswalker has 4 loyalty": a planeswalker nobody named, said by what is on it rather than counted.
        Regex("""^planeswalkers?(?: card)? (?:has|have|is at|is on|with|starts with|at) (\d+) loyalty(?: counters?)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: ctx.lastOwner ?: "me"
            var id = "planeswalker"; var k = 2; while (ctx.objects.containsKey(id)) id = "planeswalker_" + (k++)
            ctx.objects[id] = ObjectSpec(id, CardRef(name = "a planeswalker"), controller = who, counters = mapOf("loyalty" to (r.groupValues[1].toIntOrNull() ?: 0)))
            ctx.notes += "A planeswalker nobody named, with ${r.groupValues[1]} loyalty; only the loyalty matters to the answer."
            ctx.lastMentioned = id; ctx.lastOwner = who; ctx.lastActor = who; ctx.note(who); return true
        }
        Regex("""^(controls?|controlling|have|has|got|there (?:is|are))\s+(an?|one|\d+|two|three|four|five|six|seven|eight|nine|ten)(?: (?:more|other))? (artifacts?|enchantments?|planeswalkers?|permanents?|lands?)(?: with (\d+) loyalty(?: counters?)?)?(?: on the battlefield| in play| out)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: ctx.lastOwner ?: "me"
            val n = number(r.groupValues[2]) ?: 1
            val kind = r.groupValues[3].removeSuffix("s")
            // "I have four lands" is the mana idiom and is read as mana further down; "I control four lands" is a board.
            if (kind == "land" && !r.groupValues[1].startsWith("control")) return@let
            val name = (if (kind.first() in "aeiou") "an " else "a ") + kind
            // "they control a planeswalker with 4 loyalty": a planeswalker with no loyalty is already dead, so a
            // stand-in needs one — the asker's number if they gave it, and otherwise it is theirs to say.
            val loyalty = r.groupValues[4].toIntOrNull()
            val ids = (1..n).map { var id = slug(kind); var k = 2; while (ctx.objects.containsKey(id)) id = slug(kind) + "_" + (k++)
                ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = who, counters = if (kind == "planeswalker" && loyalty != null) mapOf("loyalty" to loyalty) else emptyMap()); id }
            ctx.notes += "$n unnamed ${kind}${if (n > 1) "s" else ""} on the battlefield; only being ${if (kind.first() in "aeiou") "an" else "a"} $kind matters to the answer."
            ctx.lastMentioned = ids.last(); ctx.lastOwner = who; ctx.lastActor = who; ctx.note(who); return true
        }
        // "I put Rancor on my Grizzly Bears" / "attach Bonesplitter to it": an Aura or Equipment on a permanent
        // that is already there. Without this the attachment was dropped and the creature answered its bare size.
        Regex("""^(?:puts?|attach(?:es)?|attached|sticks?|stuck|enchants? with|hangs?) (?:an? |the |my |their )?(c\d+) (?:on|onto|to|on top of) (?:($possPrefix|an? ))?(?:own )?(c\d+|it|that|creature|guy|dude)(?: by mistake| by accident| accidentally| anyway)?$""").find(c)?.let { r ->
            val aura = m.cards[r.groupValues[1]] ?: return@let
            if (aura.typeLine.let { !it.contains("Aura", true) && !it.contains("Equipment", true) && !it.contains("Enchantment", true) }) return@let
            val hostWho = possessiveOwner(r.groupValues[2], ctx, m) ?: actor ?: ctx.lastOwner ?: "me"
            val host = if (r.groupValues[3] in setOf("creature", "guy", "dude")) (ctx.objects.values.lastOrNull { it.controller == hostWho && it.zone == "battlefield" && isCreatureName(it.card.name) }?.id
                           ?: describedCreatures("a ", "", "creature", hostWho, ctx).firstOrNull() ?: return@let)
                       else if (r.groupValues[3] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                       else m.cards[r.groupValues[3]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, hostWho, false, ctx) } ?: return@let
            val att = objectIdFor(aura, ctx) ?: addObject(aura, actor ?: ctx.lastOwner ?: "me", false, ctx)
            ctx.objects[att] = ctx.objects.getValue(att).copy(attachedTo = host)
            ctx.lastMentioned = host; return true
        }
        // "I put a Rancor on my 2/2", "they put Pacifism on my Bears": an Aura attached to a creature. Said this
        // way rather than "enchanted with", the whole clause went unread.
        Regex("""^(?:puts?|put|putting|attaches?|attached|sticks?|stuck) (?:an? |the |my |their )?(c\d+) on (?:to )?($possPrefix|an? )?(c\d+|\d+/\d+|creature|guy|dude|token|it|that)$""").find(c)?.let { r ->
            val aura = m.cards.getValue(r.groupValues[1])
            val who = actor ?: subject ?: ctx.lastOwner ?: "me"
            val target = r.groupValues[3]
            val hostOwner = when (r.groupValues[2].trim()) { "my" -> who; "their", "his", "her" -> ctx.other(who) ?: "opp"; else -> who }
            val host = when {
                target in setOf("it", "that") -> ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                cardRef.matches(target) -> m.cards[target]?.let { objectIdFor(it, ctx) ?: addObject(it, hostOwner, false, ctx) } ?: return@let
                else -> {
                    val pt = target.takeIf { Regex("""^\d+/\d+$""").matches(it) } ?: ""
                    ctx.objects.values.lastOrNull { it.controller == hostOwner && it.zone == "battlefield" && isCreatureName(it.card.name) && (pt.isEmpty() || (it.card.name ?: "").startsWith("a $pt")) }?.id
                        ?: describedCreatures("a ", pt, "creature", hostOwner, ctx).firstOrNull() ?: return@let
                }
            }
            val att = objectIdFor(aura, ctx) ?: addObject(aura, who, false, ctx)
            ctx.objects[att] = ctx.objects.getValue(att).copy(attachedTo = host)
            ctx.lastVerb = "have"; ctx.lastOwner = who; ctx.lastActor = who; ctx.lastMentioned = host; return true
        }
        // "My 1/1 has a Bonesplitter (equipped)", "my 2/2 has Rancor on it": the creature given by its size,
        // carrying a named Aura or Equipment. Read as the creature alone, the attachment was dropped.
        Regex("""^(?:(?:have|has|got|controls?|controlling)\s+)?(an? |\d+ |two |three )?(\d+/\d+|creature|guy|dude|token)s?(?: ($creatureKinds))? (?:has|have|with|carrying|wearing) (?:an? |the |my |their )?(?:(c\d+)|(aura|equipment))(?: card)?(?: equipped| attached| on it| enchanting it)?$""").find(c)?.let { r ->
            // "a creature with an Aura on it": which one it is doesn't matter to the question, but Aura or
            // Equipment does — one goes to the graveyard when its host leaves, the other stays unattached.
            val generic = r.groupValues[5].takeIf { it.isNotEmpty() }?.let { if (it == "aura") "an Aura" else "an Equipment" }
            val gear = if (generic != null) null else m.cards[r.groupValues[4]] ?: return@let
            if (gear != null && (gear.isSpellOnly || !(gear.typeLine.contains("Equipment") || gear.typeLine.contains("Aura") || gear.typeLine.contains("Enchantment") || gear.typeLine.contains("Artifact")))) return@let
            val who = actor ?: subject ?: ctx.lastOwner ?: "me"
            val pt = r.groupValues[2].takeIf { Regex("""^\d+/\d+$""").matches(it) } ?: ""
            val ids = ctx.objects.values.lastOrNull { it.controller == who && it.zone == "battlefield" && isCreatureName(it.card.name) && (pt.isEmpty() || (it.card.name ?: "").startsWith("a $pt")) }?.let { listOf(it.id) }
                ?: describedCreatures(r.groupValues[1].ifEmpty { "a " }, pt, r.groupValues[3], who, ctx)
            if (ids.isEmpty()) return@let
            for (id in ids) {
                val att = if (gear != null) addObject(gear, who, false, ctx, allowDuplicate = ids.size > 1)
                          else { var a = slug(generic!!); var k = 2; while (ctx.objects.containsKey(a)) a = slug(generic) + "_" + (k++); ctx.objects[a] = ObjectSpec(a, CardRef(name = generic), controller = who); a }
                ctx.objects[att] = ctx.objects.getValue(att).copy(attachedTo = id)
            }
            if (generic != null) ctx.notes += "\"$generic\" stands in for whichever one it is; what matters is that it is ${if (generic == "an Aura") "an Aura (it goes to the graveyard when its host leaves, 704.5m)" else "an Equipment (it stays on the battlefield, unattached, 704.5n)"}."
            ctx.lastVerb = "have"; ctx.lastOwner = who; ctx.lastActor = who; ctx.lastMentioned = ids.last(); return true
        }
        // "a creature enchanted with Pacifism", "a 2/2 equipped with Bonesplitter": a creature nobody named,
        // carrying something that is named.
        Regex("""^(?:(?:have|has|got|controls?|controlling)\s+)?(an? |\d+ |two |three )?(?:(\d+/\d+)s?\s*)?($creatureKinds)?\s*(?:enchanted with|equipped with|wearing|carrying) (?:an? |the |my |their )?(c\d+)((?: .+)?)$""").find(c)?.let { r ->
            if (r.groupValues[2].isEmpty() && r.groupValues[3].isEmpty()) return@let
            val who = actor ?: subject ?: ctx.lastOwner ?: "me"
            val ids = describedCreatures(r.groupValues[1], r.groupValues[2], r.groupValues[3], who, ctx)
            if (ids.isEmpty()) return@let
            val aura = m.cards.getValue(r.groupValues[4])
            for (id in ids) {
                val att = addObject(aura, who, false, ctx, allowDuplicate = true)
                ctx.objects[att] = ctx.objects.getValue(att).copy(attachedTo = id)
            }
            ctx.lastVerb = "have"; ctx.lastOwner = who; ctx.lastActor = who; ctx.lastMentioned = ids.last()
            // "My 2/2 enchanted with Rancor dies": what the creature then does is a statement of its own.
            val rest = r.groupValues[5].trim()
            if (rest.isNotEmpty()) return readClause("it $rest", m, ctx)
            return true
        }
        // "I have a creature with indestructible and one without": the second creature, described by contrast
        // with the first. Left unread the board was a creature short and a sweeper killed nothing.
        Regex("""^(?:and )?(?:one|another|a second)(?: creature)? without(?: (?:it|that|$kwNouns))?$""").find(c)?.let {
            val who = actor ?: ctx.lastOwner ?: "me"
            describedCreatures("a ", "", "creature", who, ctx).firstOrNull()?.let { id -> ctx.lastMentioned = id; ctx.lastOwner = who; ctx.note(who); return true }
        }
        // "My creature gets -3/-3 and it's a 2/2": the size said after the creature, rather than with it.
        Regex("""^(?:it's|it is|its|that's|that is) an? (\d+/\d+)(?: creature)?$""").find(c)?.let { r ->
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects && isCreatureName(ctx.objects.getValue(it).card.name) } ?: return@let
            val spec = ctx.objects.getValue(id)
            val n0 = spec.card.name ?: return@let
            if (!n0.startsWith("a ") && !n0.startsWith("an ")) return@let
            val name = "a ${r.groupValues[1]} " + n0.substringAfter(' ').removePrefix("1/1 ")
            ctx.objects[id] = spec.copy(card = spec.card.copy(name = name))
            return true
        }
        // "Both have first strike" / "mine has deathtouch" / "the blocker has trample": keywords on creatures already described.
        Regex("""^(both|both of them|they both|all of them|mine|theirs|yours|his|hers|the attacker|the blocker|my creature|their creature|(?:my opponent's |the opponent's |opponent's |their |his |her |my )?(?:creature|token|guy|dude|\d+/\d+)|it) (?:has|have|gets?|gained?|is|are) ((?:$kwNouns|$kwPhrase))(?:,? (?:and )?((?:$kwNouns|$kwPhrase)))?$""").find(c)?.let { r ->
            fun kw(x: String) = x.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample").replace("deathtoucher", "deathtouch").replace("lifelinker", "lifelink")
            val kws = listOfNotNull(r.groupValues[2].takeIf { it.isNotEmpty() }, r.groupValues[3].takeIf { it.isNotEmpty() }).map { kw(it) }
            if (kws.isEmpty()) return@let
            val who = r.groupValues[1]
            val attackerId = ctx.events.lastOrNull { it.verb == "attack" }?.obj
            val blockerId = ctx.events.lastOrNull { it.verb == "block" }?.obj
            val ids = when {
                who in setOf("both", "both of them", "they both", "all of them") -> listOfNotNull(attackerId, blockerId).ifEmpty { ctx.objects.values.toList().takeLast(2).map { o -> o.id } }
                who in setOf("mine", "yours", "my creature") -> listOfNotNull(ctx.objects.values.lastOrNull { it.controller == "me" }?.id)
                who in setOf("theirs", "his", "hers", "their creature") -> listOfNotNull(ctx.objects.values.lastOrNull { it.controller != "me" }?.id)
                who == "the attacker" -> listOfNotNull(attackerId)
                who == "the blocker" -> listOfNotNull(blockerId)
                // "my opponent's creature has lifelink": the possessive says whose it is, and with nothing of
                // theirs on the battlefield yet the creature they described is made here.
                Regex("""(?:creature|token|guy|dude|\d+/\d+)$""").containsMatchIn(who) -> {
                    val owner = when {
                        Regex("""^(?:my opponent's|the opponent's|opponent's|their|his|her)\b""").containsMatchIn(who) -> pronounPlayer(ctx, "their")
                        who.startsWith("my") -> "me"
                        else -> actor ?: ctx.lastOwner ?: "me"
                    }
                    val pt = Regex("""\d+/\d+$""").find(who)?.value ?: ""
                    listOfNotNull(ctx.objects.values.lastOrNull { it.controller == owner && isCreatureName(it.card.name) && (pt.isEmpty() || (it.card.name ?: "").startsWith("a $pt")) }?.id
                        ?: describedCreatures("a ", pt, "creature", owner, ctx).firstOrNull())
                }
                else -> listOfNotNull(ctx.lastMentioned?.takeIf { it in ctx.objects })
            }.filter { it in ctx.objects }
            if (ids.isEmpty()) return@let
            for (id in ids) {
                val spec = ctx.objects.getValue(id)
                val name = (spec.card.name ?: "").let { n -> if (n.startsWith("a ")) (if (n.contains(" with ")) "$n, ${kws.joinToString(", ")}" else "$n with ${kws.joinToString(", ")}") else n }
                ctx.objects[id] = spec.copy(card = spec.card.copy(name = name))
            }
            ctx.notes += "Read as: ${ids.joinToString(" and ") { ctx.objects.getValue(it).card.name ?: it }}."
            return true
        }
        // "my opponent's 6/6 is attacking me" / "their 3/3 is attacking": a described creature already declared as an attacker.
        // "My 2/2 attacks" says the same as "my 2/2 is attacking"; without it the creature was put on the
        // battlefield with "attacks" swallowed into its description and no attack was declared at all.
        Regex("""^(?:(my opponent's|the opponent's|opponent's|their|his|her|my|the|@\w+'s) )?(an? |\d+ |two |three )?(\d+/\d+)s?(?: ($kwNouns))?(?: ($creatureKinds))?(?: creature)?(?: with ((?:$kwPhrase)(?:(?:,|,? and|,? &) (?:$kwPhrase))*))? (?:(?:is|are) attacking|attacks?|swings?(?: in)?)(?: (me|you|them|my opponent|the opponent|@\w+))?$""").find(c)?.let { r ->
            val head = r.groupValues[1]
            val who = when {
                head.startsWith("my opponent") || head.startsWith("the opponent") || head.startsWith("opponent") || head in setOf("their", "his", "her") -> pronounPlayer(ctx, "their")
                head == "my" -> "me"
                head.startsWith("@") -> head.removePrefix("@").removeSuffix("'s")
                else -> actor ?: ctx.lastOwner ?: "opp"
            }
            val kw = (listOfNotNull(r.groupValues[4].takeIf { it.isNotEmpty() }?.removeSuffix("s")?.replace("flier", "flying")?.replace("flyer", "flying")?.replace("trampler", "trample"))
                + r.groupValues[6].split(Regex(""",| and | & """)).map { it.trim() }.filter { it.isNotEmpty() }).distinct().joinToString(", ")
            val ids = describedCreatures(r.groupValues[2], r.groupValues[3], r.groupValues[5], who, ctx, kw)
            if (ids.isEmpty()) return@let
            val defender = when (val d = r.groupValues[7]) {
                "", "me", "you" -> ctx.other(who) ?: "me"
                "them", "my opponent", "the opponent" -> pronounPlayer(ctx, "their")
                else -> d.removePrefix("@")
            }
            for (id in ids) ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(defender))
            ctx.lastVerb = "attack"; ctx.lastActor = who; ctx.lastOwner = who; ctx.lastMentioned = ids.last(); ctx.note(who); ctx.note(defender)
            return true
        }
        // "… and a 1/1", right after "block their 5/5 with a 2/2": another blocker on the same attacker, not a
        // creature standing around. Only when a block was the last thing read.
        if (ctx.lastVerb == "block") Regex("""^(an? |\d+ |two |three )?(\d+/\d+)((?: [a-z]+)*)$""").find(c)?.let { r ->
            val last = ctx.events.lastOrNull { it.verb == "block" } ?: return@let
            val attacker = last.targets.firstOrNull() ?: return@let
            val ids = describedFrom(r.groupValues[1], r.groupValues[2], r.groupValues[3], last.player ?: "me", ctx)
            if (ids.isEmpty()) return@let
            ids.forEach { ctx.events += EventSpec("block", player = last.player, obj = it, targets = listOf(attacker)) }
            ctx.lastMentioned = ids.last(); return true
        }
        // Unnamed creatures: "I have three creatures", "control two other creatures" (stats unknown; assumed 1/1 and said so).
        Regex("""^(?:(?:have|has|got|control|controls|controlling|'ve got)\s+)?(an? |one |\d+ |two |three |four |five )?(?:other |more )?(tapped |untapped )?(?:(\d+/\d+)s?\s*)?((?:first strike |double strike |(?!with )[a-z]+ )*)($creatureKinds|blockers?|attackers?|guys?|dudes?|beaters?|bodies|body|$kwNouns)?(?: with ($kwPhrase(?:(?:,|,? and|,? &) $kwPhrase)*|(?:an? |one |two |three |four |five |\d+ )?[+-]\d+/[+-]\d+ counters?(?: on it)?))?(?: plus .*)?(?: on the battlefield| in play| out)?((?: that (?:i|they|he|she) just (?:played|cast)(?: this turn)?| that just came down| i just played| with summoning sickness| that has been out| from last turn)?)$""").find(c)?.let { r ->
            val hasVerb = Regex("""^(?:have|has|got|control|controls|controlling|'ve got)\b""").containsMatchIn(c)
            val pt = r.groupValues[3]; val adj = r.groupValues[4].trim()
            // "a 2/2 flier", "a 3/3 trampler": the word after the size is the keyword, not a creature type.
            val kindKw = r.groupValues[5].takeIf { Regex("""^$kwNouns$""").matches(it) && !Regex("""^$creatureKinds$""").matches(it) }
                ?.removeSuffix("s")?.replace("flier", "flying")?.replace("flyer", "flying")?.replace("trampler", "trample")
                ?.replace("deathtoucher", "deathtouch")?.replace("lifelinker", "lifelink")?.replace("first striker", "first strike")?.replace("double striker", "double strike")
            // "one blocker", "two beaters": words for a creature that aren't creature types.
            val kind = if (kindKw != null) "creature" else r.groupValues[5].let { if (Regex("""^(?:blockers?|attackers?|guys?|dudes?|beaters?|bodies|body)$""").matches(it)) "creature" else it }
            // Needs a verb or a state context, and something creature-like: "a 3/3", "two goblins", "3 other goblins"; not "the", "it", or a lone number.
            if (kind.isEmpty() && pt.isEmpty()) return@let
            if (!hasVerb && ctx.lastVerb != "have" && ctx.lastOwner == null) return@let
            // "a 2/2 indestructible creature", "my 3/3 flying deathtouch creature": the words in front of the
            // kind are either keywords the creature has or the plain adjectives below; anything else isn't a
            // creature description and the clause belongs to another rule.
            val adjWords = adj.replace("first strike", "first-strike").replace("double strike", "double-strike").split(' ').filter { it.isNotEmpty() }
            val adjKw = adjWords.map { it.replace('-', ' ') }.filter { it in keywordAdjectives }
            val plainAdj = adjWords.map { it.replace('-', ' ') }.filter { it !in keywordAdjectives }
            if (plainAdj.any { it !in setOf("vanilla", "big", "small", "random", "chump", "spare", "extra", "red", "green", "white", "blue", "black") }) return@let
            val who = actor ?: (if (hasVerb) subject else ctx.lastOwner ?: subject) ?: "me"
            // "a 1/1 with a +1/+1 counter on it": that's a counter, not a keyword, and it goes on every creature described.
            val withTail = r.groupValues[6]
            val counterTail = withTail.takeIf { Regex("""[+-]\d+/[+-]\d+ counters?""").containsMatchIn(it) }
            val kw = (listOfNotNull(withTail.takeIf { it.isNotEmpty() && counterTail == null }, kindKw) + adjKw).distinct().joinToString(", ")
            val colour = plainAdj.firstOrNull { it in setOf("red", "green", "white", "blue", "black") }
            val made = describedCreatures(r.groupValues[1], pt, if (colour != null) "$colour ${kind.ifEmpty { "creature" }}" else kind, who, ctx, kw)
            // "a tapped 4/4": the word in front of the size says how it is on the battlefield.
            if (r.groupValues[2].trim() == "tapped") made.forEach { ctx.objects[it] = ctx.objects.getValue(it).copy(tapped = true) }
            if (counterTail != null) made.forEach { applyStateWords(it, "with $counterTail", ctx) }
            // "a 2/2 that I just played this turn": summoning sickness, said as part of the description.
            r.groupValues[7].takeIf { it.isNotBlank() }?.let { tail -> made.forEach { applyStateWords(it, tail.trim(), ctx) } }
            Regex(""" plus (an? |\d+ |two |three |four |five )?(\d+/\d+)s?(?: ($kwNouns))?(?: ($creatureKinds))?""").findAll(c).forEach { x ->
                describedCreatures(x.groupValues[1], x.groupValues[2], x.groupValues[4], who, ctx, x.groupValues[3].let { k -> if (k.isEmpty()) "" else k.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample") })
            }
            // "I have a blocker", "I have two blockers": the word says what they are for. If something is already
            // attacking that player, they block it — otherwise the answer is about an attack nobody blocked.
            if (Regex("""^blockers?$""").matches(r.groupValues[5])) {
                val attacks = ctx.events.filter { it.verb == "attack" && (it.targets.contains(who) || it.targets.isEmpty()) }
                if (attacks.isNotEmpty()) {
                    made.forEachIndexed { i, id -> attacks.getOrNull(i)?.obj?.let { atk -> ctx.events += EventSpec("block", player = who, obj = id, targets = listOf(atk)) } }
                    ctx.notes += "\"${if (made.size == 1) "a blocker" else "${made.size} blockers"}\" is read as blocking${if (made.size > 1) ", one attacker each" else ""}; say otherwise if they don't block."
                    ctx.lastVerb = "block"; ctx.lastOwner = who; ctx.lastActor = who; return true
                }
                // Nothing is attacking yet; remember them and match them up once the whole question is read.
                made.forEach { ctx.pendingBlockers.getOrPut(who) { mutableListOf() } += it }
            }
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
                // "a Saproling token": the type says which token, and the token has a printed size. Without it the
                // token had no size, so it wasn't a creature and Overrun pumped nothing.
                .let { n0 -> if (Regex("""\d+/\d+""").containsMatchIn(n0)) n0 else tokenSize(n0)?.let { sz ->
                    ctx.notes += "The $n0's size wasn't given; the usual one is $sz. Say the size if yours is different."
                    "$sz $n0" } ?: n0 }
            // "I make three 1/1 tokens" is something happening, not a board that was already there: without the
            // enter events an Impact Tremors sitting next to them never triggered.
            val made = Regex("""^(?:makes?|made|creates?|created)\b""").containsMatchIn(c)
            repeat(n) { i ->
                var id = slug(name); var k = 2; while (ctx.objects.containsKey(id)) id = slug(name) + "_" + (k++)
                ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = who, token = true, tapped = r.groupValues[3].contains("tapped") && !r.groupValues[3].contains("untapped"))
                if (made) ctx.events += EventSpec("enter", obj = id)
                ctx.lastMentioned = id
            }
            ctx.lastVerb = "have"; ctx.lastOwner = who; ctx.lastActor = who; return true
        }
        // "cast a second Sheoldred while I already control one": the first is on the battlefield, the second is cast.
        Regex("""^(?:$castVerbs) (?:a second|another|a 2nd) (c\d+)((?: while .*| when .*| with .*)?)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            val tail = r.groupValues[2].takeIf { Regex("""^ with (?:evoke|kicker|the kicker|kicker paid|overload|x ?=)""").containsMatchIn(it) } ?: ""
            val previous = ctx.events.lastOrNull { it.verb == "cast" && it.card?.name == card.display && it.targets.isNotEmpty() }?.targets
            if (!card.isSpellOnly) addObject(card, who, false, ctx)
            emitCast(who, card, tail + (if (c.startsWith("evok")) " with evoke" else ""), m, ctx)
            // "a second Bolt": at the same thing as the first, unless said otherwise.
            if (previous != null && ctx.events.lastOrNull()?.let { it.verb == "cast" && it.card?.name == card.display && it.targets.isEmpty() } == true) { ctx.events[ctx.events.lastIndex] = ctx.events.last().copy(targets = previous); ctx.notes += "The second ${card.display} is read as aimed at the same target as the first; say otherwise if not." }
            return true
        }
        // "tap Llanowar Elves for mana", "tap Sol Ring for {C}{C}"
        Regex("""^(?:still )?taps? (?:an? |the |my |their |his |her |our )?(c\d+|it) for (?:mana|\{.*|[a-z]+ mana|[a-z]+)(?: in response(?: to (?:it|that))?)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            // "it" is the tapper's own permanent: the last one mentioned, unless that belongs to someone else.
            // "I cast Llanowar Elves and tap it for mana": the Elves has to resolve first, and once it has, it is
            // summoning sick. Without this "it" found nothing and the clause was dropped, or a second copy was
            // conjured onto the battlefield and tapped while the real one was still on the stack.
            val id = (if (ctx.lastCastEntry != null && (r.groupValues[1] != "it" || ctx.events.lastOrNull { it.verb == "cast" }?.player == who)) castPermanentObject(ctx) else null)
                ?.takeIf { ctx.objects[it]?.controller == who && (r.groupValues[1] == "it" || m.cards[r.groupValues[1]]?.oracleId == ctx.objects[it]?.card?.oracleId) }
                ?: if (r.groupValues[1] == "it") (ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).controller == who } ?: ctx.objects.values.lastOrNull { it.controller == who }?.id ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let) else m.cards.getValue(r.groupValues[1]).let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) }
            ctx.events += EventSpec("activate", player = who, obj = id, to = "mana"); ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        // "they draw", "I draw": one card, said without saying so.
        // "draw my card for turn" is the draw step's own draw: the step draws it, so adding a draw as well drew twice.
        if (Regex("""^(?:(?:has|have|had) to |must |needs? to |am about to |is about to |are about to |would )?(?:draws?|drew|drawing|draw)(?: (?:a|my|their|his|her|the) card)?(?: for (?:the |my |their |his |her )?turn)?(?: (?:during|in|for|at) (?:their|my|the|his|her) draw step)?$""").matches(c)) {
            val who = actor ?: subject ?: "me"
            val stepDraw = c.contains("draw step") || Regex("""for (?:the |my |their |his |her )?turn""").containsMatchIn(c)
            if (stepDraw) { ctx.activePlayer = who; ctx.events += EventSpec("step", player = who, to = "draw"); ctx.lastActor = who; ctx.note(who); return true }
            ctx.events += EventSpec("draw", player = who, amount = 1); ctx.lastActor = who; ctx.note(who); return true
        }
        // "draws a card", "draws two cards (during their draw step)"
        Regex("""^(?:draws?|drew|drawing) (a|an|\d+|two|three|four|five)(?: cards?| more| again| more cards?)?(?: (?:during|in|for|at) (?:their|my|the|his|her) draw step)?(?: (?:from|off|with|thanks to) (?:an? |the |my |their )?(?:c\d+|[a-z ]+))?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            if (c.contains("draw step")) { ctx.activePlayer = who; ctx.events += EventSpec("step", player = who, to = "draw") }
            ctx.events += EventSpec("draw", player = who, amount = number(r.groupValues[1]) ?: 1); ctx.lastActor = who; return true
        }
        // readClause0 had grown past the JVM's 64 KB limit on one method, so the rules carry on in a second
        // function. The split is only for size: the order the rules are tried in is unchanged.
        return readClause1(c, clause0, actor, subject, clauseIn, m, ctx)
    }

    /** The rest of [readClause0]'s rules, in the same order; see the note there. */
    private fun readClause1(c0: String, clause0: String, actor: String?, subject: String?, clauseIn: String, m: Marked, ctx: Ctx): Boolean {
        var c = c0
        val kwList = """$kwPhrase(?:(?:,|,? and|,? &) $kwPhrase)*"""
        // "two spells are cast this turn": the same statement as "I have cast two spells this turn", in the passive.
        Regex("""^(a|an|one|two|three|four|five|six|seven|eight|nine|ten|\d+) spells? (?:are|is|were|was|have been|has been) (?:already )?cast(?: by (me|them|my opponent|@\w+))?(?: this turn| so far this turn| already)$""").find(c)?.let { r ->
            val who = r.groupValues[2].takeIf { it.isNotEmpty() }?.let { w -> if (w == "me") "me" else if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, "their") } ?: actor ?: subject ?: "me"
            val n = number(r.groupValues[1]) ?: return@let
            ctx.spellsThisTurn[who] = n
            ctx.notes += "${if (who == "me") "You have" else (ctx.players[who] ?: "Your opponent") + " has"} cast $n spell${if (n == 1) "" else "s"} this turn; abilities that count spells start from there."
            ctx.note(who); return true
        }
        // "casts three spells", "casts a creature spell", "plays two more instants"
        // "I play a land. Can I play two more?" / "can I play a third?": after a land play, a bare count is
        // more lands. Read as spells, the question answered about casting and the land limit never came up.
        if (lastPlayWasALand(ctx)) Regex("""^(?:plays?|played|playing) (?:(a|an|one|another|two|three|four|\d+)(?: more| other| additional)?|an?(?: (second|third|fourth|fifth))(?: one)?)$""").find(c)?.let { r ->
            val n = r.groupValues[1].takeIf { it.isNotEmpty() }?.let { if (it in setOf("a", "an", "one", "another")) 1 else number(it) ?: 1 } ?: 1
            playALand(actor ?: subject ?: "me", n, ctx); return true
        }
        Regex("""^(?:$castVerbs) (?:one|another|another one|one too|one as well|one of their own|one of my own)$""").find(c)?.let {
            val who = actor ?: subject ?: "me"
            // "I play a land. Can I play another one?": after a land, "another one" is another land, not a spell.
            if (lastPlayWasALand(ctx)) { playALand(who, 1, ctx); return true }
            ctx.events += EventSpec("cast", player = who, card = CardRef(name = "a spell")); ctx.lastActor = who; ctx.lastVerb = "cast"; return true
        }
        Regex("""^(?:$castVerbs)\s+(?:my |their |his |her |the )?(?:(a|an|another|\d+|two|three|four|five)(?: more| other)? )?(spells?|instants?|sorcer(?:y|ies)|creature spells?|creatures?|noncreature spells?|artifacts?|enchantments?|one|(?:second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)(?: one| spell)?)(?: this turn| in a row| in one turn| on their turn| on my turn)?((?:,? (?:paying for none of them|paying for nothing|without paying|not paying|and pays? for none|never paying|declining to pay each time|and doesn't pay|and never pays)(?: for (?:any|each|all) of them)?)?)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            // "casts their second spell this turn": the ordinal says how many came before it, and this is one cast.
            val ordinal = mapOf("second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10)[r.groupValues[2].substringBefore(' ')]
            // Only when nothing was cast earlier in the situation: "I cast five spells, then a sixth one" has
            // already played out the five, and stating the count as well would count them twice.
            if (ordinal != null && ctx.events.none { it.verb == "cast" && it.player == who }) { ctx.spellsThisTurn[who] = ordinal - 1; ctx.notes += "${if (who == "me") "You have" else (ctx.players[who] ?: "Your opponent") + " has"} cast ${ordinal - 1} spell${if (ordinal == 2) "" else "s"} this turn already; this one is the ${r.groupValues[2].substringBefore(' ')}." }
            val n = if (ordinal != null) 1 else number(r.groupValues[1]) ?: 1
            if (r.groupValues[3].isNotEmpty()) ctx.events += EventSpec("pay", player = who, to = "no")
            val kind = r.groupValues[2].removeSuffix("s").replace("sorceries", "sorcery").replace(Regex("""^(?:second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)(?: (?:one|spell))?$"""), "spell").replace(Regex("""^(?:one|spell)$"""), "spell").let { if (it == "creature" || it == "artifact" || it == "enchantment") "$it spell" else it }
            repeat(n) { ctx.events += EventSpec("cast", player = who, card = CardRef(name = "a $kind")) }
            ctx.lastActor = who; ctx.lastVerb = "cast"; return true
        }
        // "Both of us control Grizzly Bears" / "we each have X": one for each player.
        Regex("""^(?:both of us|we both|we each|each of us|everyone|all players|both players) (?:controls?|has|have|got) (?:an? |the )?(c\d+)$""").find(clauseIn.replace(Regex("""^(?:i|we) """), ""))?.let { r ->
            val card = m.cards.getValue(r.groupValues[1])
            for (pid in ctx.playerIds()) addObject(card, pid, false, ctx, allowDuplicate = true)
            ctx.lastVerb = "have"; return true
        }
        // "activate Mother of Runes choosing black" / "use it naming white": the colour an ability asks its controller to choose.
        Regex("""^(?:$activateVerbs) (?:my |the |their )?(c\d+|it|that)(?:'s ability)? (?:choosing|naming|picking|for|on) (white|blue|black|red|green|colou?rless)(?: in response)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val src = if (r.groupValues[1] in setOf("it", "that")) (ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let)
                      else m.cards[r.groupValues[1]]?.let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) } ?: return@let
            ctx.events += EventSpec("activate", player = who, obj = src, to = "color:${r.groupValues[2]}")
            ctx.lastActor = who; return true
        }
        // "My Grizzly Bears gets +2/+2", "it untaps", "their Serra Angel is returned to their hand": short
        // statements about a permanent already on the battlefield. The leading possessive has been taken as the
        // actor by now, so what arrives here starts with the card.
        // "My 2/2 gets +2/+2" / "my creature gets -3/-3 from Disfigure": the creature may be described rather than
        // named, and the card that does it may be said — in which case it is cast at that creature.
        Regex("""^($possPrefix|an? )?(c\d+|it|that|creature|\d+/\d+)(?: creature)? (?:gets?|gains?|gained|is given|has) ([+-]\d+/[+-]\d+)(?: until end of turn| this turn)?(?: from (?:an? |the |my |their )?(c\d+))?$""").find(c)?.let { r0 ->
            val r = object { val groupValues = listOf(r0.groupValues[0], r0.groupValues[2], r0.groupValues[3]) }
            val who = possessiveOwner(r0.groupValues[1], ctx, m) ?: actor ?: ctx.lastOwner ?: "me"
            val id = if (r.groupValues[1] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else if (r.groupValues[1] == "creature") ctx.objects.values.lastOrNull { it.controller == who && it.zone == "battlefield" && isCreatureName(it.card.name) }?.id
                        ?: describedCreatures("a ", "", "creature", who, ctx).firstOrNull() ?: return@let
                     else if (Regex("""^\d+/\d+$""").matches(r.groupValues[1])) describedCreatures("the ", r.groupValues[1], "creature", who, ctx).firstOrNull()
                        ?: describedCreatures("a ", r.groupValues[1], "creature", who, ctx).firstOrNull() ?: return@let
                     else m.cards[r.groupValues[1]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) } ?: return@let
            // "… from Disfigure": the spell is cast at it rather than the size being taken as a given.
            r0.groupValues[4].takeIf { it.isNotEmpty() }?.let { ph -> m.cards[ph] }?.let { card ->
                emitCast(ctx.other(who) ?: "opp", card, "", m, ctx)
                ctx.events.indexOfLast { it.verb == "cast" }.takeIf { it >= 0 }?.let { i -> ctx.events[i] = ctx.events[i].copy(targets = listOf(id)) }
                ctx.lastMentioned = id; return true
            }
            val spec = ctx.objects.getValue(id)
            ctx.objects[id] = spec.copy(pump = r.groupValues[2])
            ctx.notes += "${spec.card.name ?: id} is read as having ${r.groupValues[2]} until end of turn (from an effect; say what gives it if that matters)."
            ctx.lastMentioned = id; return true
        }
        // "My Grizzly Bears gets a +1/+1 counter", "it has two charge counters on it".
        // "has +1/+1 counter on it": people leave the article out, and dropping the clause quietly lost the counter.
        // "my creature has a -1/-1 counter": the creature may be described rather than named.
        Regex("""^($possPrefix)?(c\d+|it|that|creature|guy|dude|\d+/\d+) (?:gets?|has|have|is given|gains?|gained) (an?|one|two|three|four|five|\d+)? ?(?:([+-]\d+/[+-]\d+|[a-z]+) )?counters?(?: on it)?$""").find(c)?.let { r ->
            val owner = possessiveOwner(r.groupValues[1], ctx) ?: actor ?: ctx.lastOwner ?: "me"
            val id = if (r.groupValues[2] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else if (r.groupValues[2] in setOf("creature", "guy", "dude")) ctx.objects.values.lastOrNull { it.controller == owner && it.zone == "battlefield" && isCreatureName(it.card.name) }?.id
                        ?: describedCreatures("a ", "", "creature", owner, ctx).firstOrNull() ?: return@let
                     else if (Regex("""^\d+/\d+$""").matches(r.groupValues[2])) describedCreatures("the ", r.groupValues[2], "creature", owner, ctx).firstOrNull()
                        ?: describedCreatures("a ", r.groupValues[2], "creature", owner, ctx).firstOrNull() ?: return@let
                     else m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx) } ?: return@let
            val n = r.groupValues[3].ifEmpty { "one" }.let { number(it) } ?: return@let
            // "my Walking Ballista has two counters": the kind left unsaid on a creature is +1/+1 — no other kind
            // is put on creatures often enough to be what "counters" means on its own. Dropped, the clause left a
            // 0/0 Ballista that died to state-based actions before the question could be answered.
            val kind = r.groupValues[4].ifEmpty {
                val nm = ctx.objects.getValue(id).card.name
                if (!isCreatureName(nm)) return@let
                ctx.notes += "\"$n counter${if (n == 1) "" else "s"}\" on ${nm ?: id} is read as +1/+1 counters; say the kind if you meant another."
                "+1/+1"
            }
            val spec = ctx.objects.getValue(id)
            ctx.objects[id] = spec.copy(counters = spec.counters + (kind to ((spec.counters[kind] ?: 0) + n)))
            ctx.lastMentioned = id; ctx.lastStat = "$kind counters"; ctx.lastStatWho = "obj:$id"; return true
        }
        // "there are three +1/+1 counters on my Walking Ballista": the same statement with the counters said first.
        Regex("""^there (?:is|are) (an?|one|two|three|four|five|\d+) ([+-]\d+/[+-]\d+|[a-z]+) counters? on ($possPrefix)?(c\d+|it|that)$""").find(c)?.let { r ->
            val owner = possessiveOwner(r.groupValues[3], ctx) ?: actor ?: ctx.lastOwner
            val id = if (r.groupValues[4] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[4]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx) } ?: return@let
            val n = number(r.groupValues[1]) ?: return@let
            val kind = r.groupValues[2]
            val spec = ctx.objects.getValue(id)
            ctx.objects[id] = spec.copy(counters = spec.counters + (kind to ((spec.counters[kind] ?: 0) + n)))
            ctx.lastMentioned = id; ctx.lastStat = "$kind counters"; ctx.lastStatWho = "obj:$id"; return true
        }
        // "it came down this turn", "the Bears entered the battlefield this turn": summoning sickness, said as history.
        Regex("""^($possPrefix)?(c\d+|it|that) (?:just )?(?:came down|came in|came into play|entered|entered the battlefield|hit the battlefield|was cast|was played|resolved) (?:this|the same|that) turn$""").find(c)?.let { r ->
            val owner = possessiveOwner(r.groupValues[1], ctx) ?: actor ?: ctx.lastOwner
            val id = if (r.groupValues[2] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx) } ?: return@let
            ctx.objects[id] = ctx.objects.getValue(id).copy(summoningSick = true); ctx.lastMentioned = id; return true
        }
        // "My Grizzly Bears has summoning sickness" / "it regenerates" / "it is shuffled into my library".
        Regex("""^($possPrefix)?(c\d+|it|that) (?:has|is) summoning ?sick(?:ness)?$""").find(c)?.let { r0 ->
            val r = object { val groupValues = listOf(r0.groupValues[0], r0.groupValues[2]) }
            val id = if (r.groupValues[1] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[1]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, possessiveOwner(r0.groupValues[1], ctx, m) ?: actor ?: ctx.lastOwner ?: "me", false, ctx) } ?: return@let
            ctx.objects[id] = ctx.objects.getValue(id).copy(summoningSick = true); ctx.lastMentioned = id; return true
        }
        Regex("""^($possPrefix)?(c\d+|it|that) (?:regenerates|is regenerated|gets regenerated|has a regeneration shield)$""").find(c)?.let { r0 ->
            val r = object { val groupValues = listOf(r0.groupValues[0], r0.groupValues[2]) }
            val id = if (r.groupValues[1] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[1]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, possessiveOwner(r0.groupValues[1], ctx, m) ?: actor ?: ctx.lastOwner ?: "me", false, ctx) } ?: return@let
            ctx.events += EventSpec("regenerate", obj = id); ctx.lastMentioned = id; return true
        }
        Regex("""^($possPrefix)?(c\d+|it|that) (?:is|gets?|got|was) shuffled into (?:its owner's|my|their|his|her|the owner's) (?:library|deck)$""").find(c)?.let { r0 ->
            val r = object { val groupValues = listOf(r0.groupValues[0], r0.groupValues[2]) }
            val id = if (r.groupValues[1] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[1]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, possessiveOwner(r0.groupValues[1], ctx, m) ?: actor ?: ctx.lastOwner ?: "me", false, ctx) } ?: return@let
            ctx.events += EventSpec("leave", obj = id, to = "library"); ctx.lastMentioned = id; return true
        }
        // "I put a +1/+1 counter on my Bears", "add two charge counters to it": counters placed now, which is not
        // the same as a permanent that already has them — doublers and Solemnity apply to the placement.
        Regex("""^(?:puts?|put|adds?|added|places?|placed) (an?|one|two|three|four|five|\d+) ([+-]\d+/[+-]\d+|[a-z]+) counters? (?:on|onto|to) (?:(?:my |their |the |his |her )?(c\d+)|(?:(my|their|the|his|her) )?(\d+/\d+|creature|guy|dude|token)(?: creature)?|it|that|itself)$""").find(c)?.let { r ->
            // "I cast Grizzly Bears and put a +1/+1 counter on it": the Bears has to resolve before a counter can
            // go on it, and until it does there is no object for "it" to mean.
            val kind = r.groupValues[2]
            // "on my 1/1" / "on my creature": the creature the question describes rather than names.
            if (r.groupValues[5].isNotEmpty()) {
                val owner = when (r.groupValues[4]) { "my", "" -> actor ?: ctx.lastOwner ?: "me"; "the" -> actor ?: ctx.lastOwner ?: "me"; else -> pronounPlayer(ctx, "their") }
                val pt = r.groupValues[5].takeIf { Regex("""^\d+/\d+$""").matches(it) } ?: ""
                val id0 = ctx.objects.values.lastOrNull { it.controller == owner && it.zone == "battlefield" && isCreatureName(it.card.name) && (pt.isEmpty() || (it.card.name ?: "").startsWith("a $pt")) }?.id
                    ?: describedCreatures("a ", pt, "creature", owner, ctx).firstOrNull() ?: return@let
                ctx.events += EventSpec("counters", obj = id0, amount = number(r.groupValues[1]) ?: 1, to = kind)
                ctx.lastMentioned = id0; return true
            }
            val id = if (r.groupValues[3].isEmpty()) {
                val ref = castPermanentObject(ctx) ?: ctx.lastMentioned?.takeIf { it in ctx.objects }
                // A +1/+1 or -1/-1 counter goes on a creature. "I control Grizzly Bears and Doubling Season and
                // put a +1/+1 counter on it" put the counter on Doubling Season, and nothing said so.
                // "+1/+1 counter on it" helps the creature, so with one on each side it is the actor's own.
                val mine = !Regex("""^\+\d+/\+\d+$""").matches(kind) || actor == null || ctx.objects[ref]?.controller == actor
                if (Regex("""^[+-]\d+/[+-]\d+$""").matches(kind) && (ref == null || !isCreatureName(ctx.objects[ref]?.card?.name) || !mine))
                    ctx.objects.values.lastOrNull { it.zone == "battlefield" && (actor == null || it.controller == actor) && isCreatureName(it.card.name) }?.id ?: ref ?: return@let
                else ref ?: return@let
            } else m.cards[r.groupValues[3]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, actor ?: ctx.lastOwner ?: "me", false, ctx) } ?: return@let
            ctx.events += EventSpec("counters", obj = id, amount = number(r.groupValues[1]) ?: 1, to = kind)
            ctx.lastMentioned = id; return true
        }
        // "and search for two basic lands", "I dig for a creature": what a tutor finds isn't tracked in detail, so
        // the clause is read and said to be untracked rather than dropped as if it had never been said.
        Regex("""^(?:and )?(?:searches?|search|digs?|dig|looks?|look|finds?|find|fetch(?:es)?|grabs?|tutors?)(?: (?:my|their|his|her|the) (?:library|deck))? for (.+?)(?: and (?:puts?|shuffles?).*)?$""").find(c)?.let { r ->
            val what = restore(r.groupValues[1], m).trim()
            if (what.isEmpty() || what.length > 60) return@let
            ctx.notes += "Searching a library isn't tracked in detail; \"$what\" is taken to be found."
            return true
        }
        // "I tap my Grizzly Bears", "they tap it down", "it becomes tapped": tapping a permanent, which is not the
        // same as using a {T} ability — a creature with no {T} ability can still be tapped by an effect.
        Regex("""^taps? (?:an? |the |my |their |his |her )?(c\d+|it|that|\d+/\d+)(?: creature)?(?: down)?$|^(?:my |their |his |her |the |own )?(c\d+|it|that|\d+/\d+|creature|guy|dude|blocker|attacker)(?: creature)? (?:becomes tapped|gets tapped|is tapped|was tapped|got tapped|taps down)$""").find(c)?.let { r ->
            val ph = r.groupValues[1].ifEmpty { r.groupValues[2] }
            val mine = Regex("""\bmy\b""").containsMatchIn(clause0) || (actor != null && actor != "me")
            val id = if (ph in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else if (Regex("""^\d+/\d+$""").matches(ph)) describedCreatures("a ", ph, "creature", if (mine) "me" else (actor ?: ctx.lastOwner ?: "me"), ctx).firstOrNull() ?: return@let
                     else if (ph in setOf("creature", "guy", "dude", "blocker", "attacker")) describedCreatures("a ", "", "creature", if (Regex("""^(?:their|his|her)\b""").containsMatchIn(clause0)) (ctx.other(actor ?: "me") ?: "opp") else (actor ?: ctx.lastOwner ?: "me"), ctx).firstOrNull() ?: return@let
                     else m.cards[ph]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, actor ?: ctx.lastOwner ?: "me", false, ctx) } ?: return@let
            // "I tap Cabal Coffers" means using its ability, not turning it sideways for nothing. The bare active
            // form is only a tap-down when it says "down" or when it is somebody else's permanent.
            if (r.groupValues[1].isNotEmpty() && !c.endsWith(" down") && ctx.objects[id]?.controller == (actor ?: "me")) return@let
            // Said before anything happens it is board state; said after, it is something that happened.
            if (ctx.events.isEmpty()) ctx.objects[id] = ctx.objects.getValue(id).copy(tapped = true)
            else ctx.events += EventSpec("tap", obj = id)
            ctx.lastMentioned = id; return true
        }
        Regex("""^(?:my |their |his |her |the |own )?(c\d+|it|that) (?:untaps|is untapped|becomes untapped|gets untapped)$""").find(c)?.let { r ->
            val id = if (r.groupValues[1] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[1]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, actor ?: ctx.lastOwner ?: "me", false, ctx) } ?: return@let
            ctx.events += EventSpec("untap", obj = id); ctx.lastMentioned = id; return true
        }
        Regex("""^(?:my |their |his |her |the |own )?(c\d+|it|that) (?:is|gets?|got|was) (?:returned|bounced|put back) to (?:its owner's|my|their|his|her|the owner's) hand$""").find(c)?.let { r ->
            val id = if (r.groupValues[1] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[1]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, actor ?: ctx.lastOwner ?: "me", false, ctx) } ?: return@let
            ctx.events += EventSpec("leave", obj = id, to = "hand"); ctx.lastMentioned = id; return true
        }
        // "it deals combat damage to my opponent": the way to say a creature attacked and got through, which is
        // what abilities that trigger on combat damage need.
        Regex("""^(?:(my|their|his|her|the|my opponent's|@\w+'s) )?(c\d+|it|that|\d+/\d+|creature|guy|dude|token)(?: creature)? (?:deals?|dealt|connects? for) (?:combat )?damage to (me|them|him|her|my opponent|the opponent|opponent|@\w+)$""").find(c)?.let { r ->
            val owner = when (val w = r.groupValues[1].trim()) {
                "my" -> "me"
                "their", "his", "her", "my opponent's" -> pronounPlayer(ctx, "their")
                else -> actor ?: ctx.lastOwner ?: "me"
            }
            val ref = r.groupValues[2]
            val pt = ref.takeIf { Regex("""^\d+/\d+$""").matches(it) } ?: ""
            val id = if (ref in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else if (pt.isNotEmpty() || ref in setOf("creature", "guy", "dude", "token"))
                         ctx.objects.values.lastOrNull { it.controller == owner && it.zone == "battlefield" && isCreatureName(it.card.name) && (pt.isEmpty() || (it.card.name ?: "").startsWith("a $pt")) }?.id
                             ?: describedCreatures("a ", pt, "creature", owner, ctx).firstOrNull() ?: return@let
                     else m.cards[ref]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx) } ?: return@let
            val who = ctx.objects[id]?.controller ?: owner
            val victim = when (val w = r.groupValues[3]) {
                "me" -> "me"
                "them", "him", "her", "my opponent", "the opponent", "opponent" -> pronounPlayer(ctx, w.substringAfterLast(' '))
                else -> w.removePrefix("@").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) }
            }
            if (ctx.events.none { it.verb == "attack" && it.obj == id }) {
                ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(victim))
                ctx.notes += "${ctx.objects[id]?.card?.name ?: id} is read as attacking ${if (victim == "me") "you" else ctx.players[victim] ?: "your opponent"} and going unblocked; that is how it deals combat damage."
            }
            ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; ctx.note(victim); return true
        }
        // "my Grizzly Bears has flying", "their Serra Angel has protection from black": a keyword the asker states
        // rather than one the card is printed with. Said as a fact about the board, not as something happening now.
        Regex("""^(?:(my|their|his|her|the|my opponent's|@\w+'s) )?(c\d+|it|that) (?:has|have|already has|is given|comes with|now has|gains?|gained|gets) ((?:$kwPhrase)(?:(?:,| and|, and) (?:$kwPhrase))*)$""").find(c)?.let { r ->
            val owner = when (val w = r.groupValues[1].trim()) {
                "my" -> "me"
                "their", "his", "her", "my opponent's" -> pronounPlayer(ctx, "their")
                "", "the" -> actor ?: ctx.lastOwner
                else -> if (w.startsWith("@")) w.removePrefix("@").removeSuffix("'s").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) } else ctx.lastOwner
            }
            val id = if (r.groupValues[2] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx) } ?: return@let
            val kws = r.groupValues[3].split(Regex(""",? and |, """)).map { it.trim() }.filter { it.isNotEmpty() }
            val spec = ctx.objects.getValue(id)
            ctx.objects[id] = spec.copy(keywords = spec.keywords + kws.filter { it !in spec.keywords })
            ctx.notes += "${spec.card.name ?: id} is read as having ${kws.joinToString(" and ")} (from an effect; say what gives it if that matters)."
            ctx.lastMentioned = id; ctx.lastOwner = owner ?: ctx.lastOwner; return true
        }
        // "I have protection from black on my Bears", "there is hexproof on it": the same statement with the
        // permanent named at the end rather than at the front.
        Regex("""^(?:have|has|had|there is|there's|gave|gives?|granted|put) ((?:$kwPhrase)(?:(?:,| and|, and) (?:$kwPhrase))*) on (?:(my|their|his|her|the|my opponent's) )?(c\d+|it|that)$""").find(c)?.let { r ->
            val owner = when (val w = r.groupValues[2].trim()) {
                "my" -> "me"
                "their", "his", "her", "my opponent's" -> pronounPlayer(ctx, "their")
                else -> actor ?: ctx.lastOwner
            }
            val id = if (r.groupValues[3] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[3]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, owner ?: "me", false, ctx) } ?: return@let
            val kws = r.groupValues[1].split(Regex(""",? and |, """)).map { it.trim() }.filter { it.isNotEmpty() }
            val spec = ctx.objects.getValue(id)
            ctx.objects[id] = spec.copy(keywords = spec.keywords + kws.filter { it !in spec.keywords })
            ctx.notes += "${spec.card.name ?: id} is read as having ${kws.joinToString(" and ")} (from an effect; say what gives it if that matters)."
            ctx.lastMentioned = id; return true
        }
        // "give it protection from black": the actor's other permanent grants it (Mother of Runes).
        Regex("""^(?:gives?|granting|grants?) (?:it|that|(?:the |my )?(c\d+)) protection from (white|blue|black|red|green)(?: in response)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val target = if (r.groupValues[1].isNotEmpty()) (objectIdFor(m.cards.getValue(r.groupValues[1]), ctx) ?: addObject(m.cards.getValue(r.groupValues[1]), who, false, ctx))
                         else ctx.events.lastOrNull { it.verb == "cast" }?.targets?.firstOrNull { it in ctx.objects } ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            val source = ctx.objects.values.lastOrNull { it.controller == who && it.id != target } ?: return@let
            ctx.events += EventSpec("activate", player = who, obj = source.id, targets = listOf(target), to = "color:${r.groupValues[2]}"); ctx.lastActor = who; return true
        }
        // "has 2 poison counters", "has 0 cards in hand", "with three cards in hand"
        Regex("""^(?:(?:already |now |currently )?(?:has|have|is at|at|with|sits at|is on)(?: already| now)? )?(\d+|\w+) poison(?: counters?)?(?: already| so far| now)?$""").find(c)?.let { r -> val who = actor ?: subject ?: "me"; ctx.poison[who] = number(r.groupValues[1]) ?: 0; ctx.note(who); ctx.lastStat = "poison counters"; ctx.lastStatWho = who; return true }
        // "there are 8 poison counters on my opponent": the same state with the player said last.
        Regex("""^there (?:is|are) (\d+|\w+) poison counters? on (me|us|them|him|her|my opponent|the opponent|opponent|@\w+)$""").find(c)?.let { r ->
            val who = when (val w = r.groupValues[2]) {
                "me", "us" -> "me"
                "them", "him", "her" -> pronounPlayer(ctx, "their")
                "my opponent", "the opponent", "opponent" -> pronounPlayer(ctx, "opponent")
                else -> w.removePrefix("@")
            }
            ctx.poison[who] = number(r.groupValues[1]) ?: 0; ctx.note(who); ctx.lastStat = "poison counters"; ctx.lastStatWho = who; return true
        }
        Regex("""^(?:there (?:is|are) )?(?:(?:has|have|holds?|holding|with) )?(\d+|\w+|no) cards? in ($possPrefix)?hand(?: (?:at|during|in) (?:(?:their|my|his|her|the) )?(cleanup|end of turn|end|discard)(?: step)?)?$""").find(c)?.let { r ->
            if (r.groupValues[3].isNotEmpty()) { val who = actor ?: subject ?: "me"; ctx.activePlayer = who; ctx.events += EventSpec("step", player = who, to = if (r.groupValues[3].startsWith("end")) "end" else "cleanup") }
            // "there are 3 cards in my opponent's hand": whose hand it is, said in the clause rather than by the
            // subject. Without reading it the count landed on the asker and the answer had the wrong hand.
            val whose = possessiveOwner(r.groupValues[2], ctx, m)
            val who = whose ?: actor ?: (if (c.startsWith("ha") || c.startsWith("ho") || c.startsWith("with")) subject else ctx.lastOwner ?: subject) ?: "me"
            ctx.handSize[who] = if (r.groupValues[1] == "no") 0 else number(r.groupValues[1]) ?: 0; ctx.note(who); if (actor != null) ctx.lastActor = actor; return true }
        // "only has one Mountain untapped", "has 2 untapped lands", "with 3 mana open/available/up"
        Regex("""^(?:only )?(?:has|have|with|got)(?: only)? (\d+|\w+) (?:(?:untapped |open )(?:lands?|c\d+s?|mana sources?)|(?:lands?|c\d+s?|mana sources?) (?:untapped|open|available|up|left)|mana(?: (?:open|available|up|left|untapped))?)$""").find(c)?.let { r ->
            // "I have an untapped Forest" names a permanent; "an" is an article, not a count, and reading it as
            // one mana available left the land off the battlefield entirely.
            if (r.groupValues[1] in setOf("a", "an") && Regex("""c\d+""").containsMatchIn(r.groupValues[0])) return@let
            val who = actor ?: subject ?: "me"; val n = number(r.groupValues[1]) ?: return@let
            ctx.mana[who] = n; ctx.note(who); if (actor != null) ctx.lastActor = actor; ctx.notes += "${if (who == "me") "You have" else (ctx.players[who] ?: "Your opponent") + " has"} $n mana available; costs are checked against that."; return true
        }
        // "have an instant and a creature in their graveyard" / "my graveyard has a land and a sorcery": card types in a graveyard (Tarmogoyf).
        Regex("""^(?:(?:there (?:is|are)|there's )?(?:has|have|with|got)? ?((?:an? |two |three |\d+ )?(?:instant|sorcery|creature|land|artifact|enchantment|planeswalker|battle)(?: cards?)?(?:,? (?:and )?(?:an? |two |three |\d+ )?(?:instant|sorcery|creature|land|artifact|enchantment|planeswalker|battle)(?: cards?)?)*) (?:is |are )?in ($possPrefix)?graveyards?|($possPrefix)?graveyard (?:has|contains|is) ((?:an? |two |three |\d+ )?(?:instant|sorcery|creature|land|artifact|enchantment|planeswalker|battle)(?: cards?)?(?:,? (?:and )?(?:an? |two |three |\d+ )?(?:instant|sorcery|creature|land|artifact|enchantment|planeswalker|battle)(?: cards?)?)*))(?: in it| in there)?$""").find(c)?.let { r ->
            val who = possessiveOwner(r.groupValues[2].ifEmpty { r.groupValues[3] }, ctx, m)
                ?: actor ?: (if (clauseIn.trim().startsWith("my")) "me" else if (Regex("""^(?:their|his|her)""").containsMatchIn(clauseIn.trim())) pronounPlayer(ctx, "their") else subject) ?: "me"
            val list = r.groupValues[1].ifEmpty { r.groupValues[4] }
            for (part in list.split(Regex(""",\s*(?:and\s+)?|\s+and\s+"""))) {
                val pm = Regex("""^(?:(an?|two|three|\d+) )?(\w+)(?: cards?)?$""").find(part.trim()) ?: continue
                val n = pm.groupValues[1].let { if (it.isEmpty() || it == "a" || it == "an") 1 else number(it) ?: 1 }
                val kind = pm.groupValues[2]; val name = (if (kind.startsWith("i") || kind.startsWith("a") || kind.startsWith("e")) "an " else "a ") + kind
                repeat(n) { var id = slug("$kind card"); var k = 2; while (ctx.objects.containsKey(id)) id = slug("$kind card") + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = name), zone = "graveyard", controller = who) }
            }
            ctx.notes += "${if (who == "me") "Your" else (ctx.players[who] ?: "Your opponent") + "'s"} graveyard is read as holding: $list (only the card types matter to the engine)."; ctx.note(who); if (actor != null) ctx.lastActor = actor; return true
        }
        // "there is a Lightning Bolt in my opponent's graveyard" / "they have a Bolt in the yard": a named card in a graveyard.
        // "I have cast four spells this turn" / "they've cast two spells already this turn": a count for storm-style abilities.
        Regex("""^(?:i|we|they|he|she|my opponent|the opponent|opponent)?\s*(?:have|has|'ve|'s|had)?\s*(?:already )?cast (a|an|one|two|three|four|five|six|seven|eight|nine|ten|\d+) (?:other |more )?spells? (?:already )?(?:this turn|so far this turn|before this)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val n = number(r.groupValues[1]) ?: return@let
            ctx.spellsThisTurn[who] = n
            ctx.notes += "${if (who == "me") "You have" else (ctx.players[who] ?: "Your opponent") + " has"} cast $n spell${if (n == 1) "" else "s"} this turn; abilities that count spells start from there."
            ctx.note(who); return true
        }
        // "Stinkweed Imp is in my graveyard" / "Snapcaster Mage sits in their graveyard": the card named first.
        Regex("""^(?:an? |the |my |their )?(c\d+)(?: card)? (?:is|are|sits|sit|was|were|went|goes)(?: put)?(?: in| into|) ($possPrefix)?(?:graveyard|yard|bin)$""").find(c)?.let { r ->
            val who = possessiveOwner(r.groupValues[2], ctx, m) ?: actor ?: "me"
            val was = ctx.lastMentioned
            addObject(m.cards.getValue(r.groupValues[1]), who, false, ctx, zone = "graveyard", allowDuplicate = true)
            ctx.lastMentioned = was
            ctx.note(who); return true
        }
        // The owner-first form ("my graveyard has a Mountain") loses its "my" to the actor rule before it gets here.
        Regex("""^(?:there (?:is|are)|i have|they have|there's) ((?:an? |the )?c\d+(?:(?:,| and|, and) (?:an? |the )?c\d+)*) in ($possPrefix)?(?:graveyard|yard|bin)$|^($possPrefix)?(?:graveyard|yard|bin) (?:has|contains|holds) ((?:an? |the )?c\d+(?:(?:,| and|, and) (?:an? |the )?c\d+)*)(?: in it)?$""").find(c)?.let { r ->
            val who = possessiveOwner(r.groupValues[2].ifEmpty { r.groupValues[3] }, ctx) ?: actor ?: "me"
            val was = ctx.lastMentioned
            for (ph in Regex("""c\d+""").findAll(r.groupValues[1].ifEmpty { r.groupValues[4] }).map { it.value })
                addObject(m.cards.getValue(ph), who, false, ctx, zone = "graveyard", allowDuplicate = true)
            ctx.lastMentioned = was   // a card in a graveyard isn't what "it" means next
            ctx.note(who); return true
        }
        // "the top card of my library is Lightning Bolt" / "my top card is X": that card sits on top of the library.
        Regex("""^(?:the )?top (?:card )?(?:of (?:my|their|his|her|the) library )?is (?:an? |the )?(c\d+)$|^(?:my|their|his|her) top card is (?:an? |the )?(c\d+)$""").find(c)?.let { r ->
            val ph = r.groupValues[1].ifEmpty { r.groupValues[2] }
            val who = actor ?: (if (Regex("""\btheir|his|her\b""").containsMatchIn(clauseIn)) pronounPlayer(ctx, "their") else "me")
            val wasTop = ctx.lastMentioned
            addObject(m.cards.getValue(ph), who, false, ctx, zone = "library", allowDuplicate = true)
            ctx.lastMentioned = wasTop   // a card on a library isn't what "it" means next
            ctx.notes += "${m.cards.getValue(ph).display} is on top of ${if (who == "me") "your" else "their"} library."; ctx.note(who); return true
        }
        // "have 4 lands" / "with six lands": mana available, the lands assumed untapped.
        Regex("""^(?:only )?(?:has|have|with|got|control|controls)(?: only)? (\d+|two|three|four|five|six|seven|eight|nine|ten) lands?(?: in play| on the battlefield| out)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"; val n = number(r.groupValues[1]) ?: return@let
            ctx.mana[who] = n; ctx.note(who); if (actor != null) ctx.lastActor = actor
            ctx.notes += "${if (who == "me") "You have" else (ctx.players[who] ?: "Your opponent") + " has"} $n lands, read as $n mana available (assuming they're untapped); costs are checked against that."; return true
        }
        // "my devotion to blue is 4"
        Regex("""^($possPrefix)?devotion to (white|blue|black|red|green) (?:is|=|of) (\d+)$""").find(c)?.let { r ->
            val who = possessiveOwner(r.groupValues[1], ctx, m) ?: actor ?: (if (c.startsWith("their")) pronounPlayer(ctx, "their") else "me")
            ctx.devotion.getOrPut(who) { LinkedHashMap() }[r.groupValues[2]] = r.groupValues[3].toInt(); ctx.note(who); return true
        }
        // "I cast my commander Atraxa from the command zone" / "my commander Atraxa is in the command zone":
        // the card is that player's commander and starts in the command zone, where the tax is counted from.
        Regex("""^(?:($castVerbs)\s+)?(?:my |their |his |her )?commander (c\d+)((?:\s+.*)?)$|^(?:my |their |his |her )?(?:commander )?(c\d+) is (?:my |their |his |her )?(?:commander )?in the command zone$""").find(c)?.let { r ->
            val ph = r.groupValues[2].ifEmpty { r.groupValues[4] }
            if (ph.isEmpty()) return@let
            val tail = r.groupValues[3].trim()
            // Only a cast, or a bare "my commander X (is in the command zone)": anything else is another rule's.
            if (r.groupValues[1].isEmpty() && !(tail.isEmpty() || tail == "is in the command zone") && r.groupValues[4].isEmpty()) return@let
            val who = actor ?: subject ?: "me"
            val card = m.cards.getValue(ph)
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.objects[id] = ctx.objects.getValue(id).copy(commander = true, zone = "command", controller = who)
            ctx.notes += "${card.display} is read as ${if (who == "me") "your" else "their"} commander, in the command zone."
            ctx.lastMentioned = id; ctx.lastOwner = who; ctx.lastActor = who; ctx.note(who)
            if (r.groupValues[1].isEmpty()) return true
            return readClause(r.groupValues[1] + " " + ph + " " + tail.replace(Regex("""^from the command zone\b"""), "").trim(), m, ctx)
        }
        // "it has been countered twice" / "Kaalia was countered once": the commander tax.
        // "it died twice" says the same thing in the active voice, and went unread, so the tax came out as {0}.
        Regex("""^(?:(?:my |their |his |her )?commander )?(?:(?:my |their |his |her )?(c\d+)|it|that)? ?(?:(?:has been|was|got|has already been|had been|have been) (?:countered|killed|cast)|died|has died|have died|was sacrificed|went to the command zone) (once|twice|three times|four times|\d+ times?)(?: (?:already|before|so far|this game))?$""").find(c)?.let { r ->
            // "my commander died twice, what does it cost now?": the card was never named, so make the commander
            // the question is about. Without one the clause was dropped and the tax came out as {0}.
            val id = r.groupValues[1].takeIf { it.isNotEmpty() }?.let { m.cards.getValue(it) }?.let { objectIdFor(it, ctx) ?: addObject(it, actor ?: "me", false, ctx) } ?: ctx.objects.values.lastOrNull { it.commander } ?: ctx.lastMentioned?.takeIf { it in ctx.objects }
                ?: (if (!c.contains("commander")) null else (actor ?: subject ?: "me").let { who ->
                    var cid = "commander"; var k = 2; while (ctx.objects.containsKey(cid)) cid = "commander_" + (k++)
                    ctx.objects[cid] = ObjectSpec(cid, CardRef(name = "a commander"), controller = who, commander = true, zone = "command"); ctx.note(who); cid
                }) ?: return@let
            val n = when (val w = r.groupValues[2]) { "once" -> 1; "twice" -> 2; "three times" -> 3; "four times" -> 4; else -> w.substringBefore(' ').toIntOrNull() ?: 1 }
            val idStr = if (id is String) id else (id as ObjectSpec).id
            ctx.objects[idStr] = ctx.objects.getValue(idStr).copy(commander = true, commanderCasts = n, zone = if (r.groupValues[0].contains(" cast ")) ctx.objects.getValue(idStr).zone else "command"); ctx.notes += "${ctx.objects.getValue(idStr).card.name} has been cast from the command zone $n time${if (n == 1) "" else "s"} already, so the commander tax applies (903.8)."; return true
        }
        // "have 0 cards in library", "with no cards left in my library", "my library is empty", "library has 2 cards"
        Regex("""^(?:there (?:is|are) )?(?:(?:has|have|with|am at|is at|at) )?(\d+|\w+|no) cards? (?:left )?in ($possPrefix)?library$|^($possPrefix)?library (?:is empty|has (\d+|\w+|no) cards?(?: left)?|is out of cards)$|^(?:has|have) (?:an )?empty library$|^(?:has|have) no library(?: left)?$|^(?:am|is|are) out of cards$""").find(c)?.let { r ->
            // "there are 2 cards in my opponent's library": whose library it is, said in the clause. Without it the
            // count landed on the asker and the answer drew from the wrong library.
            val who = possessiveOwner(r.groupValues[2].ifEmpty { r.groupValues[3] }, ctx, m) ?: actor ?: subject ?: "me"
            val word = r.groupValues[1].ifEmpty { r.groupValues[4] }
            ctx.librarySize[who] = if (word.isEmpty() || word == "no") 0 else number(word) ?: 0; ctx.note(who); return true
        }
        // "I have threshold", "my delirium is on", "they have metalcraft", "I'm hellbent": a mechanic said to be
        // met. It is a statement about the board, and each one says what the board has to look like.
        Regex("""^(?:(?:has|have|got|'ve got|am|is|are|reached|with) )?(?:my |their |his |her |the )?(threshold|delirium|metalcraft|hellbent)(?: is| are)?(?: on| active| met| satisfied| turned on| online| enabled| up| already)?$""").find(c)?.let { r ->
            val who = actor ?: ctx.lastActor ?: "me"
            val whose = if (who == "me") "Your" else (ctx.players[who] ?: "Your opponent") + "'s"
            when (r.groupValues[1]) {
                "threshold" -> { ctx.graveyardSize[who] = maxOf(7, ctx.graveyardSize[who] ?: 0); ctx.notes += "$whose graveyard is read as holding seven cards, which is what threshold asks for." }
                "delirium" -> {
                    for (kind in listOf("instant", "creature", "land", "sorcery")) {
                        val name = (if (kind.first() in "aeiou") "an " else "a ") + kind
                        if (ctx.objects.values.any { it.zone == "graveyard" && it.controller == who && it.card.name == name }) continue
                        var id = slug("$kind card"); var k = 2; while (ctx.objects.containsKey(id)) id = slug("$kind card") + "_" + (k++)
                        ctx.objects[id] = ObjectSpec(id, CardRef(name = name), zone = "graveyard", controller = who)
                    }
                    ctx.notes += "$whose graveyard is read as holding an instant, a creature, a land and a sorcery, which is the four card types delirium asks for."
                }
                "metalcraft" -> {
                    val have = ctx.objects.values.count { it.controller == who && it.zone == "battlefield" && (it.card.name ?: "").contains("artifact", true) }
                    repeat(maxOf(0, 3 - have)) { var id = slug("an artifact"); var k = 2; while (ctx.objects.containsKey(id)) id = slug("an artifact") + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = "an artifact"), controller = who) }
                    ctx.notes += "$whose board is read as having three artifacts, which is what metalcraft asks for."
                }
                "hellbent" -> { ctx.handSize[who] = 0; ctx.notes += "$whose hand is read as empty, which is what hellbent asks for." }
            }
            ctx.note(who); if (actor != null) ctx.lastActor = actor; return true
        }
        // "I tap three lands": mana that has been made and is there to spend.
        Regex("""^taps? (\d+|one|two|three|four|five|six|seven|eight|nine|ten) (?:lands?|mana sources?|permanents?) ?(?:for mana)?$""").find(c)?.let { r ->
            val who = actor ?: ctx.lastActor ?: "me"
            val n = number(r.groupValues[1]) ?: return@let
            ctx.mana[who] = n; ctx.note(who)
            ctx.notes += "${if (who == "me") "You have" else (ctx.players[who] ?: "Your opponent") + " has"} $n mana available; costs are checked against that."
            return true
        }
        // "my graveyard has seven cards" / "there are 7 cards in my graveyard": a graveyard given as a count
        // rather than by naming the cards, which is what threshold and delirium are asked about.
        Regex("""^(?:there (?:is|are) )?(?:(?:has|have|with|holds?) )?(\d+|\w+|no) cards? in ($possPrefix)?(?:graveyard|yard|bin)$|^($possPrefix)?(?:graveyard|yard|bin) (?:has|contains|holds) (\d+|\w+|no) cards?(?: in it)?$""").find(c)?.let { r ->
            val who = possessiveOwner(r.groupValues[2].ifEmpty { r.groupValues[3] }, ctx, m) ?: actor ?: subject ?: "me"
            val word = r.groupValues[1].ifEmpty { r.groupValues[4] }
            val n = if (word == "no") 0 else number(word) ?: return@let
            ctx.graveyardSize[who] = n; ctx.note(who); return true
        }
        // "… and 6 lands" / "3 untapped lands" as a fragment after a possession: mana available.
        Regex("""^(?:(?:has|have|with|got|holds?) )?(\d+|two|three|four|five|six|seven|eight|nine|ten) (?:untapped |open )?(?:lands?|mana|mana sources?)(?: untapped| open| available| left| to spend)?$""").find(c)?.let { r ->
            if (ctx.lastVerb != "have" && actor == null && !Regex("""^(?:has|have|with|got|holds?) """).containsMatchIn(c)) return@let
            val who = actor ?: ctx.lastOwner ?: subject ?: "me"; val n = number(r.groupValues[1]) ?: return@let
            ctx.mana[who] = n; ctx.note(who); if (actor != null) ctx.lastActor = actor; ctx.notes += "${if (who == "me") "You have" else (ctx.players[who] ?: "Your opponent") + " has"} $n mana available; costs are checked against that."; return true
        }
        // "… and 4 untapped Plains" / "two Forests" as a fragment after a possession: more permanents of the last owner.
        Regex("""^(\d+|two|three|four|five|six|seven|eight) (untapped |tapped )?(c\d+)$""").find(c)?.let { r ->
            if (ctx.lastVerb != "have") return@let
            val who = actor ?: ctx.lastOwner ?: subject ?: "me"
            val n = number(r.groupValues[1]) ?: 1
            repeat(n) { addObject(m.cards.getValue(r.groupValues[3]), who, r.groupValues[2].trim() == "tapped", ctx, allowDuplicate = true) }
            ctx.lastOwner = who; return true
        }
        // "… and a Treasure token", "two 1/1 Soldier tokens" as a fragment after a possession: tokens of the last owner.
        Regex("""^(?:(?:has|have|got|control|controls) )?(an? |\d+ |two |three |four |five )?((?:\d+/\d+ )?(?:[a-z]+ )*?tokens?)(?: with ([a-z ,&]+?))?$""").find(c)?.let { r ->
            val hasVerb = Regex("""^(?:has|have|got|control|controls)\b""").containsMatchIn(c)
            if (!hasVerb && ctx.lastVerb != "have") return@let
            val who = actor ?: (if (hasVerb) subject else ctx.lastOwner ?: subject) ?: "me"
            val n = r.groupValues[1].trim().let { if (it.isEmpty()) 1 else number(it) ?: 1 }
            val name = r.groupValues[2].removeSuffix("s").let { if (it.endsWith(" token")) it else "$it token" }.let { if (Regex("""^\d+/\d+""").containsMatchIn(it) || it != "token") it else "1/1 creature token" } + (r.groupValues[3].takeIf { it.isNotEmpty() }?.let { " with $it" } ?: "")
            repeat(n) { var id = slug(name); var k = 2; while (ctx.objects.containsKey(id)) id = slug(name) + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = who, token = true); ctx.lastMentioned = id }
            ctx.lastVerb = "have"; ctx.lastOwner = who; return true
        }
        // A state fragment about the last-mentioned permanent: "… and 1 damage on it", "with three +1/+1 counters", "at 4 loyalty".
        // "… and gets two -1/-1 counters": the same fragment said with a verb, which was left unread.
        if (Regex("""^(?:with |has |having |it has |at |is at |gets? |gains?ed? |gains? |is given |takes? )?(?:\d+|\w+) (?:loyalty(?: counters?)?|(?:[+-]\d+/[+-]\d+|[a-z]+) counters?(?: on it)?|damage(?: marked)?(?: on it)?)$""").matches(c) || Regex("""^(?:with |has )?(?:an? )?[+-]\d+/[+-]\d+ (?:pump|bonus|boost)(?: from .*)?$""").matches(c)) {
            val id = ctx.lastMentioned?.takeIf { ctx.objects.containsKey(it) } ?: return false
            applyStateWords(id, "with " + c.replace(Regex("""^(?:with |has |having |it has |at |is at |gets? |gains?ed? |gains? |is given |takes? )"""), ""), ctx); return true
        }
        // "… and three Elves" continuing an attack: typed 1/1 tokens join the attack.
        if (ctx.lastVerb == "attack") Regex("""^(\d+|two|three|four|five) (elves|elf|goblins|zombies|soldiers|humans|spirits|angels|dragons|beasts|elementals|saprolings|thopters|knights|warriors|wizards|vampires|merfolk|cats|dogs|birds|insects|squirrels|servos|tokens|creatures)(?: tokens?)?$""").find(c)?.let { r ->
            return readClause((ctx.lastActor?.let { if (it == "me") "i " else if (it == "opp") "they " else "@$it " } ?: "i ") + "attack with " + c, m, ctx)
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
        Regex("""^(?:gains?|gained|gaining) (\d+) life\b.*$""").find(c)?.let { r -> val who = actor ?: subject ?: "me"; ctx.events += EventSpec("gainLife", player = who, amount = r.groupValues[1].toInt()); ctx.lastActor = who; ctx.lastMentioned = ctx.lastMentioned; return true }
        Regex("""^(?:loses?|lost|losing) (\d+) life\b.*$""").find(c)?.let { r -> val who = actor ?: subject ?: "me"; ctx.events += EventSpec("loseLife", player = who, amount = r.groupValues[1].toInt()); ctx.lastActor = who; return true }
        // "X dies", "my opponent's X is destroyed", "X leaves the battlefield", "X gets exiled"
        // "it dies" / "that gets destroyed": the permanent just mentioned.
        Regex("""^(an?|two|three|four|five|\d+) of (my|their|his|her|@\w+'s) (?:(\d+/\d+) )?(?:$creatureKinds) (dies|die|died|are destroyed|is destroyed|get destroyed|gets destroyed|go to the graveyard|goes to the graveyard|are sacrificed|is sacrificed)(?: at once| together| simultaneously| at the same time)?$""").find(c)?.let { r ->
            val who = when (r.groupValues[2]) { "my" -> "me"; "their", "his", "her" -> pronounPlayer(ctx, "their"); else -> r.groupValues[2].removePrefix("@").removeSuffix("'s") }
            val n = number(r.groupValues[1]) ?: 1
            val ids = describedCreatures(if (n == 1) "a " else "$n ", r.groupValues[3], "creature", who, ctx, "")
            if (ids.isEmpty()) return@let
            val sacrificed = r.groupValues[4].contains("sacrific")
            for (id in ids) ctx.events += if (sacrificed) EventSpec("sacrifice", player = who, obj = id) else EventSpec("leave", obj = id, to = "graveyard")
            ctx.lastMentioned = ids.last(); ctx.lastActor = who; ctx.lastOwner = who; ctx.note(who)
            ctx.notes += "\"${restore(c, m)}\" is read as ${ids.size} creature${if (ids.size == 1) "" else "s"} of ${if (who == "me") "yours" else "theirs"} going to the graveyard as one event."
            return true
        }
        // "my creature dies", "a 2/2 dies", "their 3/3 is destroyed": a creature nobody named, by owner or by size.
        val c0x = c
        Regex("""^($possPrefix|an? |one )?(?:(\d+/\d+)(?: tokens?)?|(\d+/\d+) ($creatureKinds)|($creatureKinds|tokens?|guys?|dudes?))(?: with ($kwPhrase(?:(?:,|,? and) $kwPhrase)*))? (dies|died|is destroyed|gets destroyed|goes to the graveyard|is sacrificed|gets sacrificed|is exiled|gets exiled|is bounced|gets bounced|leaves the battlefield)(?: to (?:my |their |his |her |a |an )?(?:removal(?: spell)?|removal spells?|it|that|combat damage|damage|a spell|the spell))?$""").find(c0x)?.let { r0x ->
            // "my creature with persist dies": the keyword describes the creature, and the verb is still the event.
            val r = object { val groupValues = listOf(r0x.groupValues[0], r0x.groupValues[1], r0x.groupValues[2], r0x.groupValues[3], r0x.groupValues[4], r0x.groupValues[5], r0x.groupValues[7]) }
            val kws = r0x.groupValues[6]
            val who = possessiveOwner(r.groupValues[1], ctx, m) ?: actor ?: ctx.lastOwner ?: "me"
            val pt = r.groupValues[2].ifEmpty { r.groupValues[3] }
            val kind = r.groupValues[4].ifEmpty { r.groupValues[5] }
            if (pt.isEmpty() && kind.isEmpty()) return@let
            val id = ctx.objects.values.lastOrNull { o -> o.controller == who && o.zone == "battlefield" && (o.card.name ?: "").startsWith("a ") &&
                (pt.isEmpty() || (o.card.name ?: "").startsWith("a $pt")) }?.id
                ?: (if (kind.startsWith("token") || Regex("""\btokens?\b""").containsMatchIn(c)) describedTokens("a ", pt, "creature", who, ctx)
                    else describedCreatures("a ", pt, if (kind == "creature") "" else kind, who, ctx, kws)).firstOrNull() ?: return@let
            if (r.groupValues[6].contains("sacrific")) ctx.events += EventSpec("sacrifice", player = who, obj = id)
            else ctx.events += EventSpec("leave", obj = id, to = if (r.groupValues[6].contains("exile")) "exile" else if (r.groupValues[6].contains("bounce")) "hand" else "graveyard")
            ctx.lastMentioned = id; ctx.lastOwner = who; ctx.note(who); return true
        }
        // "I reanimate a creature from my graveyard": a card in a graveyard is put onto the battlefield with no
        // card named for doing it. Read as the spell of that name, the creature it named went unused.
        Regex("""^(?:reanimates?|reanimated|reanimating) (?:an? |the |my |their |his |her )?(c\d+|creature|\d+/\d+)(?: card)?(?: from (?:my|their|his|her|the|a) graveyard)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: ctx.lastOwner ?: "me"
            val w = r.groupValues[1]
            val id = if (cardRef.matches(w)) m.cards[w]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) } ?: return@let
                     else ctx.objects.values.lastOrNull { it.controller == who && it.zone == "graveyard" && isCreatureName(it.card.name) }?.id
                        ?: describedCreatures("a ", w.takeIf { Regex("""^\d+/\d+$""").matches(it) } ?: "", "creature", who, ctx).firstOrNull() ?: return@let
            ctx.objects[id] = ctx.objects.getValue(id).copy(zone = "graveyard", controller = who)
            ctx.events += EventSpec("reanimate", player = who, obj = id)
            ctx.lastActor = who; ctx.lastMentioned = id; ctx.note(who); return true
        }
        // "I flicker my Wall of Omens" / "they blink it": exiling and returning it at once, with no card named.
        Regex("""^(?:blinks?|blinked|blinking|flickers?|flickered|flickering) ($possPrefix|an? )?(c\d+|it|that|creature|guy|dude|\d+/\d+)(?: creature)?$""").find(c)?.let { r ->
            val who = possessiveOwner(r.groupValues[1], ctx, m) ?: actor ?: ctx.lastOwner ?: "me"
            val id = when (val w = r.groupValues[2]) {
                "it", "that" -> ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                "creature", "guy", "dude" -> ctx.objects.values.lastOrNull { it.controller == who && it.zone == "battlefield" && isCreatureName(it.card.name) }?.id
                    ?: describedCreatures("a ", "", "creature", who, ctx).firstOrNull() ?: return@let
                else -> if (Regex("""^\d+/\d+$""").matches(w)) describedCreatures("a ", w, "creature", who, ctx).firstOrNull() ?: return@let
                        else m.cards[w]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) } ?: return@let
            }
            ctx.events += EventSpec("blink", player = ctx.objects[id]?.controller ?: who, obj = id)
            ctx.lastActor = actor ?: who; ctx.lastMentioned = id; return true
        }
        // "I have two Solemn Simulacrums and both die": every creature that side controls goes at once.
        Regex("""^(?:both|both of them|all of them|they all|all three|all four|everything|all my creatures|all of my creatures) (?:dies|die|died|are destroyed|is destroyed|get destroyed|gets destroyed)$""").find(c)?.let {
            val who = actor ?: subject ?: ctx.lastOwner ?: "me"
            val ids = ctx.objects.values.filter { o -> o.controller == who && o.zone == "battlefield" && isCreatureName(o.card.name) }.map { it.id }
            if (ids.isEmpty()) return@let
            for (id in ids) ctx.events += EventSpec("leave", obj = id, to = "graveyard")
            ctx.lastActor = who; ctx.lastMentioned = ids.last(); return true
        }
        // "I exile their graveyard with Tormod's Crypt": the named permanent's ability, aimed at that player.
        Regex("""^(?:exiles?|exiled|wipes?|hoses?|hits?) ($possPrefix)?graveyards?(?: with| using| via) (?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: ctx.lastActor ?: "me"
            val whose = possessiveOwner(r.groupValues[1], ctx, m) ?: ctx.other(who) ?: "opp"
            val src = m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) } ?: return@let
            ctx.events += EventSpec("activate", player = who, obj = src, targets = listOf(whose))
            ctx.note(whose); ctx.lastActor = who; return true
        }
        // "I return my Grizzly Bears to my hand" / "return it to its owner's hand": the long way of saying bounce,
        // and it went unread, so the permanent stayed on the battlefield.
        Regex("""^returns?(?: back)? ($possPrefix|an? )?(c\d+|it|that|them)(?: back)? to (?:its owner's|their owner's|my|their|his|her|the owner's|your) (hand|library|graveyard)(?: from the battlefield)?$""").find(c)?.let { r ->
            val id = if (r.groupValues[2] in setOf("it", "that", "them")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, possessiveOwner(r.groupValues[1], ctx, m) ?: actor ?: ctx.lastOwner ?: "me", false, ctx) } ?: return@let
            ctx.events += EventSpec("leave", obj = id, to = r.groupValues[3]); ctx.lastMentioned = id; return true
        }
        // "I exile it" / "they bounce it": removal aimed at whatever was named last, said the active way.
        Regex("""^(?:kills?|killed|destroys?|destroyed|exiles?|exiled|bounces?|bounced|removes?|removed|nukes?|nuked) (?:it|that|them|him|her)$""").find(c)?.let {
            // A card the actor said they hold is how they would do it, and the rule above casts it; this one is
            // for when nothing was named at all.
            if (ctx.inHand[actor ?: ctx.lastActor ?: "me"]?.any { !it.typeLine.contains("Land", true) } == true) return@let
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).zone == "battlefield" }
                ?: ctx.objects.values.lastOrNull { it.zone == "battlefield" }?.id ?: return@let
            val to = when { c.contains("exile") -> "exile"; c.contains("bounce") -> "hand"; else -> "graveyard" }
            ctx.events += EventSpec("leave", obj = id, to = to); ctx.lastMentioned = id; return true
        }
        Regex("""^(?:it|that|this|he|she|they) (dies|died|is destroyed|gets destroyed|goes to the graveyard|leaves the battlefield|is exiled|gets exiled|is bounced|is sacrificed|gets sacrificed)$""").find(c)?.let { r ->
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.objects.values.lastOrNull()?.id ?: return@let
            val to = when { r.groupValues[1].contains("exiled") -> "exile"; r.groupValues[1].contains("bounced") -> "hand"; else -> "graveyard" }
            if (r.groupValues[1].contains("sacrificed")) ctx.events += EventSpec("sacrifice", player = ctx.objects.getValue(id).controller, obj = id) else ctx.events += EventSpec("leave", obj = id, to = to)
            ctx.lastMentioned = id; return true
        }
        // "I have Lightning Bolt and they have Grizzly Bears. Can I kill it?": the card in hand is how it would be
        // done, so it is cast at the creature asked about rather than the removal happening by itself.
        Regex("""^(?:kills?|killed|destroys?|destroyed|exiles?|exiled|removes?|removed|answers?|deals? with|bounces?|bounced) (?:$possPrefix|an? )?(c\d+|it|that|them|creature|blocker|attacker)$""").find(c)?.let { r ->
            val who = actor ?: ctx.lastActor ?: "me"
            val card = ctx.inHand[who]?.lastOrNull { !it.typeLine.contains("Land", true) } ?: return@let
            val ph = r.groupValues[1]
            val id = if (cardRef.matches(ph)) m.cards[ph]?.let { objectIdFor(it, ctx) } ?: return@let
                     else ctx.objects.values.lastOrNull { it.zone == "battlefield" && it.controller != who && isCreatureName(it.card.name) }?.id
                        ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            ctx.inHand.getValue(who).remove(card)
            ctx.notes += "\"${restore(clause0, m)}\" is read as casting ${card.display} from ${if (who == "me") "your" else "their"} hand at ${ctx.objects[id]?.card?.name ?: id}."
            emitCast(who, card, "", m, ctx)
            // The target is a permanent the asker described, so it is named by its id rather than by any word
            // the clause carried; emitCast has no way to read that, so the cast it just made is pointed here.
            ctx.events.indexOfLast { it.verb == "cast" }.takeIf { it >= 0 }?.let { i -> ctx.events[i] = ctx.events[i].copy(targets = listOf(id)) }
            ctx.asks += EventSpec("ask", obj = id, to = "die")
            ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        // "I bounce my own token", "they exile my 2/2": the permanent is described rather than named.
        Regex("""^(kills?|killed|destroys?|destroyed|exiles?|exiled|bounces?|bounced|sacrifices?|sacrificed|sacced?) (?:(my opponent's|the opponent's|opponent's|their own|their|my own|my|own|the|an?)\s+)?(\d+/\d+|creature|token|guy|dude)(?: creature)?$""").find(c)?.let { r ->
            val doer = actor ?: subject ?: "me"
            val owner = when (r.groupValues[2]) {
                "my opponent's", "the opponent's", "opponent's", "their" -> pronounPlayer(ctx, "their")
                "my", "my own", "own" -> "me"
                "their own" -> doer
                else -> if (r.groupValues[1].startsWith("sac")) doer else ctx.other(doer) ?: "opp"
            }
            val pt = r.groupValues[3].takeIf { Regex("""^\d+/\d+$""").matches(it) } ?: ""
            val id = ctx.objects.values.lastOrNull { o -> o.controller == owner && o.zone == "battlefield" && isCreatureName(o.card.name) && (pt.isEmpty() || (o.card.name ?: "").startsWith("a $pt")) }?.id
                ?: (if (r.groupValues[3] == "token") describedTokens("a ", pt, "creature", owner, ctx) else describedCreatures("a ", pt, "creature", owner, ctx)).firstOrNull() ?: return@let
            val verb = r.groupValues[1]
            if (verb.startsWith("sac")) ctx.events += EventSpec("sacrifice", player = owner, obj = id)
            else ctx.events += EventSpec("leave", obj = id, to = if (verb.startsWith("exile")) "exile" else if (verb.startsWith("bounce")) "hand" else "graveyard")
            ctx.lastActor = doer; ctx.lastOwner = owner; ctx.lastMentioned = id; return true
        }
        // "My opponent kills my Grizzly Bears", "I lose my Bears": said the active way, with nothing named as
        // what did it. Where a card is named ("kills it with Doom Blade") another rule has already taken it.
        Regex("""^(?:kills?|killed|destroys?|destroyed|exiles?|exiled|bounces?|bounced|removes?|removed|nukes?|nuked|loses?|lost|sacrifices?|sacrificed) (?:my opponent's |the opponent's |opponent's |their own |their |my own |my |own |the |@(\w+)'s )?(c\d+)$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[2])
            val ownerWord = c.substringBefore(" c").substringAfter(' ').trim()
            val owner = when {
                r.groupValues[1].isNotEmpty() -> r.groupValues[1]
                ownerWord.startsWith("my opponent") || ownerWord.startsWith("opponent") || ownerWord.startsWith("the opponent") || ownerWord == "their" -> pronounPlayer(ctx, "their")
                ownerWord == "my" || ownerWord == "my own" -> "me"
                else -> ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.controller ?: ctx.other(actor) ?: "me"
            }
            val id = objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx)
            val to = when { c.startsWith("exile") -> "exile"; c.startsWith("bounce") -> "hand"; else -> "graveyard" }
            if (c.startsWith("sacrific")) ctx.events += EventSpec("sacrifice", player = owner, obj = id) else ctx.events += EventSpec("leave", obj = id, to = to)
            ctx.lastMentioned = id; ctx.lastOwner = owner; return true
        }
        Regex("""^(?:my opponent's |the opponent's |opponent's |their own |their |my own |my |own |the |@(\w+)'s )?(c\d+) (?:dies|died|is destroyed|gets destroyed|would die|is put into (?:a|the|its owner's) graveyard|goes to the graveyard|is exiled|gets exiled|leaves the battlefield|is bounced|is sacrificed|gets sacrificed)$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[2])
            val ownerWord = c.substringBefore(" c").trim()
            val owner = when { r.groupValues[1].isNotEmpty() -> r.groupValues[1]; ownerWord.startsWith("my opponent") || ownerWord.startsWith("opponent") || ownerWord.startsWith("the opponent") || ownerWord == "their" -> pronounPlayer(ctx, "their"); ownerWord == "my" -> "me"; else -> actor ?: ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.controller ?: ctx.lastOwner }
            val id = objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx)
            val to = when { c.contains("exiled") -> "exile"; c.contains("bounced") -> "hand"; else -> "graveyard" }
            if (c.contains("sacrificed")) ctx.events += EventSpec("sacrifice", player = owner, obj = id) else ctx.events += EventSpec("leave", obj = id, to = to)
            ctx.lastMentioned = id; return true
        }
        // Bare continuation: "… and Smothering Tithe" after a possession, "… and Counterspell" after a cast.
        // "… and a Forest untapped": the state word can come after the card as well as before it.
        Regex("""^(?:an? |the |my |their |another |also |(\d+|two|three|four|five) )?(?:(tapped|untapped) )?(c\d+)(?:'s)?(?: (?:is |are )?(?:tapped|untapped))?(?: out| in play| on the battlefield| on board| on the field)?((?: (?:i|they|he|she|we) (?:just )?(?:played|cast|dropped|resolved)(?: this turn| earlier this turn| earlier| last turn| a turn ago)?)?)$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[3])
            val count = r.groupValues[1].let { numberWords[it] ?: it.toIntOrNull() ?: 1 }
            val isTapped = (r.groupValues[2] == "tapped") || (Regex("""\btapped\b""").containsMatchIn(c) && !Regex("""\buntapped\b""").containsMatchIn(c))
            // "a Birds of Paradise I just played this turn" / "a Bears they cast last turn": whether it is summoning sick.
            val played = r.groupValues[4].trim()
            val sick = if (played.isEmpty()) null else !Regex("""last turn|a turn ago""").containsMatchIn(played)
            when (ctx.lastVerb) {
                "have" -> {
                    // An instant or sorcery never sits on the battlefield. Said by a player who is acting ("my
                    // opponent Wraths") it is them casting it; otherwise it is a card they hold.
                    if (card.isSpellOnly) {
                        if (actor != null) { emitCast(actor, card, "", m, ctx); return true }
                        val who = ctx.lastOwner ?: "me"
                        ctx.inHand.getOrPut(who) { mutableListOf() } += card
                        ctx.notes += "${card.display} noted as in hand (hidden zones are only tracked when you cast from them)."
                        return true
                    }
                    repeat(count) { id0 ->
                        val id = addObject(card, actor ?: ctx.lastOwner, isTapped, ctx, allowDuplicate = count > 1)
                        if (isTapped) ctx.objects[id] = ctx.objects.getValue(id).copy(tapped = true)
                        if (sick != null) ctx.objects[id] = ctx.objects.getValue(id).copy(summoningSick = sick)
                    }
                    if (actor != null) { ctx.lastOwner = actor; ctx.lastActor = actor }; return true
                }
                "cast" -> { if (Regex("""^(?:their|my|his|her|the|an?) """).containsMatchIn(clauseIn.trim())) { addObject(card, if (clauseIn.trim().startsWith("my")) (ctx.lastActor ?: "me") else ctx.other(ctx.lastActor) ?: "opp", isTapped, ctx); return true }; emitCast(subject ?: "opp", card, "", m, ctx); return true }
                "attack" -> { val who = ctx.lastActor ?: "me"; val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx); ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(ctx.other(who) ?: "opp")); return true }
                "block" -> { val who = ctx.lastActor ?: "opp"; val id = ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId && it.controller == who }?.id ?: addObject(card, who, false, ctx, allowDuplicate = true); val attacker = ctx.events.lastOrNull { it.verb == "attack" && who in it.targets }?.obj ?: ctx.events.lastOrNull { it.verb == "attack" }?.obj; ctx.events += EventSpec("block", player = who, obj = id, targets = listOfNotNull(attacker)); return true }
                // "My opponent Wraths": an instant or sorcery standing alone, with nothing before it to continue.
                // It can only be someone casting it, and read as nothing at all the sweeper never happened.
                // "My Tarmogoyf with an instant in the graveyard. How big?": a permanent named on its own, with
                // nothing before it to continue, is one that player controls. Read as nothing it wasn't there.
                else -> {
                    if (card.isSpellOnly) { if ((actor ?: subject) != null) { emitCast(actor ?: subject!!, card, "", m, ctx); return true }; return false }
                    val who = actor ?: subject ?: ctx.lastOwner ?: "me"
                    val id = objectIdFor(card, ctx) ?: addObject(card, who, isTapped, ctx)
                    ctx.lastMentioned = id; ctx.lastOwner = who; ctx.note(who); return true
                }
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
            if (spell.display !in ctx.castCards) {
                val caster = ctx.other(who) ?: "opp"
                // A counterspell "that targets my Bears": the Bears is a spell on the stack, cast by me just before.
                val vid = if (needsSpellTarget(spell) && objectIdFor(victim, ctx) == null && !victim.isSpellOnly) { emitCast(who, victim, "", m, ctx); slug(victim.display) + ":spell" }
                          else if (needsSpellTarget(spell) && victim.display in ctx.castCards) slug(victim.display) + ":spell"
                          else objectIdFor(victim, ctx) ?: addObject(victim, who, false, ctx)
                emitCast(caster, spell, "", m, ctx); ctx.events[ctx.events.lastIndex] = ctx.events.last().copy(targets = listOf(vid)); ctx.lastActor = who
            }
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
        Regex("""^(?:(?:$castVerbs) (?:it|that|the card)|flash(?:es)? (?:it|that) in|flash(?:es)? in (?:it|that))(?: after (?:blockers|blocks|attackers)| before damage| in response| on my end step| on their end step| at instant speed| at the end of my turn| at the end of their turn| during my turn| during their turn)?(?: paying (\d+) life| for (\d+) life)?(?: (?:on|at|targeting) (.*?))?(?: paying \d+ life| for \d+ life)?((?: (?:with|for|where|at) x ?(?:=|equal to|equals|being|of|as) ?\d+| x ?= ?\d+| for \d+| kicked| overloaded| with evoke| with flashback| from (?:my|their) graveyard)?)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            // "Faithless Looting is in my graveyard and I cast it with flashback": the card being cast is the one
            // sitting in that graveyard, not one in hand.
            val fromYard = if (!Regex("""\b(?:with flashback|from (?:my|their) graveyard|using flashback)\b""").containsMatchIn(c)) null
                else ctx.objects.values.lastOrNull { it.zone == "graveyard" && it.controller == who && it.card.oracleId != null }
            // "I have Lightning Bolt and two Mountains in hand. Can I cast it?": "it" is the spell, not the land that
            // happened to be named last — a land isn't cast at all.
            val card = ctx.inHand[who]?.lastOrNull { !it.typeLine.contains("Land", true) } ?: ctx.inHand[who]?.lastOrNull()
                ?: fromYard?.card?.name?.let { n -> names.lookup(Names.normalize(n)) }?.also { ctx.objects.remove(fromYard.id) }
                ?: return@let
            val life = r.groupValues[1].ifEmpty { r.groupValues[2] }
            if (life.isNotEmpty()) { ctx.payLife = life.toInt(); ctx.notes += "${card.display}: ${if (who == "me") "you pay" else "they pay"} $life life as it is cast, so X is $life." }
            emitCast(who, card, (if (r.groupValues[3].isEmpty()) "" else " targeting " + r.groupValues[3]) + r.groupValues[4] + (if (life.isNotEmpty()) " with x = $life" else ""), m, ctx)
            return true
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
        Regex("""^(?:kills?|killed|destroys?|destroyed|exiles?|exiled|removes?|removed|answers?|deals? with|bounces?|bounced|blinks?|flickers?|shrinks?|pumps?|targets?|targeting|nukes?|nuked|pings?|pinged|zaps?|zapped|burns?|burned) (an? |the |my |their |his |her |my opponent's |@\w+'s |(?:my|their|his|her|its) own )?(c\d+|(?:their |my |the )?(?:blocker|attacker|creature)) (?:with|using|via) (?:an? |the |my )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val spell = m.cards.getValue(r.groupValues[3])
            // "blink MY Solemn Simulacrum": a creature named as the speaker's is theirs, not the other player's.
            // Without this a "creature you control" trigger has no legal target and the answer says it fizzles.
            // The possessive belongs to whoever is speaking, not to whoever is acting: in "my opponent kills my
            // Bears" the actor is the opponent and "my" is still the asker.
            val victimOwner = when (val poss = r.groupValues[1].trim()) {
                // "blinks her own Solemn Simulacrum": "own" means the player doing it, whoever that is.
                "my own", "their own", "his own", "her own", "its own" -> who
                "my" -> "me"
                "their", "his", "her", "my opponent's" -> pronounPlayer(ctx, "their")
                // "Alice kills Bob's Grizzly Bears": a named player owns it.
                else -> Regex("""^@(\w+)'s$""").find(poss)?.groupValues?.get(1)?.also { ctx.players.putIfAbsent(it, m.players[it] ?: it); ctx.note(it) }
                    ?: ctx.other(who) ?: "opp"
            }
            // "their blocker" / "my attacker": the creature in that combat role.
            // "creature" also starts with a "c", so a placeholder has to be matched exactly or "kills my creature
            // with Doom Blade" looks up a card called "creature" and throws.
            val roleId = if (!cardRef.matches(r.groupValues[2])) { val role = r.groupValues[2].substringAfterLast(' ')
                val ev = if (role == "blocker") ctx.events.lastOrNull { it.verb == "block" } else if (role == "attacker") ctx.events.lastOrNull { it.verb == "attack" } else null
                ev?.obj
                    ?: ctx.objects.values.lastOrNull { it.controller == victimOwner && isCreatureName(it.card.name) }?.id
                    ?: ctx.lastMentioned?.takeIf { it in ctx.objects }
                    // "they kill my creature" with nothing named: a creature nobody named, rather than dropping the clause.
                    ?: describedFrom("a ", "", "", victimOwner, ctx).firstOrNull()?.also {
                        ctx.notes += "Nothing was said about which creature; it is read as one nobody named. Name it for a precise answer."
                    } ?: return@let
            } else null
            val victim = if (roleId == null) m.cards.getValue(r.groupValues[2]) else null
            val vid = roleId ?: (objectIdFor(victim!!, ctx) ?: addObject(victim, victimOwner, false, ctx))
            emitCast(who, spell, "", m, ctx); ctx.events[ctx.events.lastIndex] = ctx.events.last().copy(targets = listOf(vid)); return true
        }
        // "can my opponent respond to my Bolt with Counterspell?" → the Bolt is cast, then the response.
        Regex("""^(?:respond(?:s|ed)? to|answers?) (?:my |their |the |an? |@\w+'s )?(c\d+) with (?:an? |the |my |their )?(c\d+)(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "opp"
            val first = m.cards.getValue(r.groupValues[1])
            if (first.display !in ctx.castCards) emitCast(ctx.other(who) ?: "me", first, "", m, ctx)
            emitCast(who, m.cards.getValue(r.groupValues[2]), " targeting ${r.groupValues[1]}", m, ctx); return true
        }
        // "my commander Atraxa has dealt 18 damage to my opponent already" / "Atraxa has already dealt them 18 commander damage"
        Regex("""^(?:$possPrefix)?(?:(\d+/\d+) )?(?:commander )?(c\d+|it|that|commander) (?:has |have )?(?:already )?(?:dealt|done|hit (?:them|me|@\w+|my opponent) for) (\d+)(?: commander| combat)? damage(?: to (me|them|my opponent|the opponent|@\w+))?(?: already| so far| this game)?$|^(?:$possPrefix)?(?:(\d+/\d+) )?(?:commander )?(c\d+|it|that|commander) (?:has |have )?(?:already )?dealt (me|them|my opponent|the opponent|@\w+) (\d+)(?: commander| combat)? damage(?: already| so far| this game)?$""").find(c)?.let { r ->
            val ph = r.groupValues[2].ifEmpty { r.groupValues[6] }; val amount = (r.groupValues[3].ifEmpty { r.groupValues[8] }).toInt(); val victimWord = r.groupValues[4].ifEmpty { r.groupValues[7] }
            val pt = r.groupValues[1].ifEmpty { r.groupValues[5] }
            // "Alice's commander has dealt 18 damage to me": whose commander it is, said in the clause.
            val who = Regex("""^($possPrefix)""").find(c)?.let { possessiveOwner(it.groupValues[1], ctx, m) } ?: actor ?: ctx.lastActor ?: "me"
            // "My commander is Atraxa and it has dealt 18 commander damage": "it" is the commander just named.
            val id = if (ph == "it" || ph == "that") ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     // "my commander has dealt 18 damage" with no name: an unnamed commander, sized if the asker said so.
                     else if (ph == "commander") ctx.objects.values.lastOrNull { it.controller == who && it.commander }?.id
                         ?: describedCreatures("a ", pt, "creature", who, ctx, "").first().also {
                             ctx.notes += "The commander wasn't named" + (if (pt.isEmpty()) " and no size was given; name it, or say how big it is, for a precise answer." else "; it is read as the described creature.")
                         }
                     else objectIdFor(m.cards.getValue(ph), ctx) ?: addObject(m.cards.getValue(ph), who, false, ctx)
            ctx.objects[id] = ctx.objects.getValue(id).copy(commander = true)
            val victim = when { victimWord.isEmpty() -> ctx.other(who) ?: "opp"; victimWord == "me" -> "me"; victimWord.startsWith("@") -> victimWord.removePrefix("@").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) }; else -> pronounPlayer(ctx, victimWord.substringAfterLast(' ')) }
            ctx.commanderDamage.getOrPut(victim) { LinkedHashMap() }[id] = amount; ctx.note(victim); ctx.note(who)
            ctx.lastVerb = "have"; ctx.lastOwner = who; ctx.lastMentioned = id; return true
        }
        return readClauseB(clause0, c, actor, subject, m, ctx)
    }

    /** The rest of the clause rules; split out only because one method may not exceed the JVM's size limit. */
    private fun readClauseB(clause00: String, c0: String, actor: String?, subject: String?, m: Marked, ctx: Ctx): Boolean {
        var clause0 = clause00
        var c = c0
        // "hit them with Atraxa again unblocked" / "swing at Bob with Kaalia": an attack.
        Regex("""^(?:hits?|hitting|swings? at|swinging at|attacks?) (me|them|him|her|my opponent|the opponent|@\w+) with (?:my |the |an? |their )?(?:commander )?(?:(c\d+)|(\d+/\d+)(?: creature)?(?: with ($kwPhrase(?:(?:,|,? and) $kwPhrase)*))?)(?: again| once more)?( unblocked| and (?:it's|it is|they're) (?:not|un)blocked)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val defender = when (val d = r.groupValues[1]) { "me" -> "me"; else -> if (d.startsWith("@")) d.removePrefix("@").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) } else pronounPlayer(ctx, d.substringAfterLast(' ')) }
            // "I hit them with a 1/1 infect": the creature may be described by its size rather than named.
            val id = if (r.groupValues[2].isEmpty()) describedCreatures("a ", r.groupValues[3], "creature", who, ctx, r.groupValues[4]).firstOrNull() ?: return@let
                     else m.cards.getValue(r.groupValues[2]).let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) }
            ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(defender)); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; ctx.note(defender); return true
        }
        // "my commander is Atraxa" / "Atraxa is my commander"
        Regex("""^(?:commander is|commander's) (c\d+)$|^(c\d+) is (?:my|their) commander$""").find(c)?.let { r ->
            val ph = r.groupValues[1].ifEmpty { r.groupValues[2] }; val who = actor ?: ctx.lastActor ?: "me"
            val id = objectIdFor(m.cards.getValue(ph), ctx) ?: addObject(m.cards.getValue(ph), who, false, ctx)
            ctx.objects[id] = ctx.objects.getValue(id).copy(commander = true); ctx.lastVerb = "have"; ctx.lastOwner = who; ctx.lastMentioned = id; return true
        }
        // "counter my Grizzly Bears (with Counterspell)": the spell is cast (by its owner) and then countered, with the named counter or a generic one.
        // "I counter it" / "counter that with Mana Leak": the last spell cast, countered by a named spell or one already in hand.
        Regex("""^counters? (?:it|that|that spell|the spell)(?: with (?:an? |the |my |their )?(c\d+))?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val castEvent = ctx.events.lastOrNull { it.verb == "cast" && it.player != who } ?: return@let
            val targetName = castEvent.card?.name ?: ctx.objects[castEvent.obj ?: ""]?.card?.name ?: return@let
            val counter = r.groupValues[1].takeIf { it.isNotEmpty() }?.let { m.cards.getValue(it) }
                ?: ctx.inHand[who]?.lastOrNull { needsSpellTarget(it) }?.also { ctx.notes += "\"Counter it\" is read as casting ${it.display} from ${if (who == "me") "your" else "their"} hand." }
            if (counter != null) { ctx.inHand[who]?.remove(counter); emitCast(who, counter, " targeting " + slug(targetName) + ":spell", m, ctx) }
            else { ctx.events += EventSpec("cast", player = who, card = CardRef(name = "a counterspell"), targets = listOf(slug(targetName) + ":spell")); ctx.lastActor = who; ctx.lastVerb = "cast"; ctx.notes += "No counterspell was named; assuming a plain \"counter target spell\"." }
            return true
        }
        Regex("""^counters? ($possPrefix)?(c\d+)(?: with (?:an? |the |my |their )?(c\d+))?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "opp"
            val target = m.cards.getValue(r.groupValues[2])
            if (objectIdFor(target, ctx) != null && target.display !in ctx.castCards) return@let   // a permanent on the battlefield can't be countered; not this rule
            val owner = when (val w = r.groupValues[1].trim()) { "my" -> "me"; "their", "my opponent's" -> pronounPlayer(ctx, "their"); "" -> ctx.other(who) ?: "me"; else -> if (w.startsWith("@")) w.removePrefix("@").removeSuffix("'s") else ctx.other(who) ?: "me" }
            if (target.display !in ctx.castCards) emitCast(owner, target, "", m, ctx)
            if (r.groupValues[3].isNotEmpty()) emitCast(who, m.cards.getValue(r.groupValues[3]), " targeting ${r.groupValues[2]}", m, ctx)
            else { ctx.events += EventSpec("cast", player = who, card = CardRef(name = "a counterspell"), targets = listOf(slug(target.display) + ":spell")); ctx.lastActor = who; ctx.lastVerb = "cast"; ctx.notes += "No counterspell was named; assuming a plain \"counter target spell\"." }
            return true
        }
        // The same thing said in the passive: "my Lightning Bolt gets countered (by Counterspell)", "it was countered".
        // Whoever owns the spell isn't the one countering it, so the counterspell belongs to the other player.
        Regex("""^(?:($possPrefix)?(c\d+)|(it|that|that spell|the spell)) (?:gets?|got|is|was|are|were) countered(?: by (?:an? |the |my |their )?(c\d+))?$""").find(c)?.let { r ->
            val named = r.groupValues[2].takeIf { it.isNotEmpty() }
            val target = named?.let { m.cards.getValue(it) }
            if (target != null && objectIdFor(target, ctx) != null && target.display !in ctx.castCards) return@let   // a permanent already out isn't a spell
            val lastCast = ctx.events.lastOrNull { it.verb == "cast" && (target == null || it.card?.name == target.display) }
            // The clause may have had its leading "my" taken off as the actor, leaving "opponent's c1 …".
            val owner = possessiveOwner(r.groupValues[1], ctx, m) ?: lastCast?.player ?: actor ?: subject ?: "me"
            val targetName = target?.display ?: lastCast?.card?.name ?: ctx.objects[lastCast?.obj ?: ""]?.card?.name ?: return@let
            val who = ctx.other(owner) ?: "opp"
            if (target != null && target.display !in ctx.castCards) emitCast(owner, target, "", m, ctx)
            val counter = r.groupValues[4].takeIf { it.isNotEmpty() }?.let { m.cards.getValue(it) }
            if (counter != null) emitCast(who, counter, " targeting " + slug(targetName) + ":spell", m, ctx)
            else { ctx.events += EventSpec("cast", player = who, card = CardRef(name = "a counterspell"), targets = listOf(slug(targetName) + ":spell")); ctx.lastActor = who; ctx.lastVerb = "cast"; ctx.notes += "No counterspell was named; assuming a plain \"counter target spell\"." }
            ctx.note(who); ctx.note(owner)
            return true
        }
        // "their Counterspell counters it": the counterspell itself is the subject. Only an instant or sorcery can
        // be the subject this way — a permanent named here is doing something else, and belongs to another rule.
        Regex("""^($possPrefix)?(c\d+) counters? (?:it|that|that spell|the spell|($possPrefix)?(c\d+))$""").find(c)?.let { r ->
            val counterCard = m.cards.getValue(r.groupValues[2])
            if (!counterCard.isSpellOnly) return@let
            // "Counterspell counters my Lightning Bolt" with nobody named: whoever didn't cast the spell being
            // countered. Read as the speaker's, it countered its own side and cast a second copy of the target.
            val who = possessiveOwner(r.groupValues[1], ctx, m)
                // "Counterspell counters my Lightning Bolt": the countered spell's own possessive says whose it is,
                // so the counterspell is the other player's.
                ?: possessiveOwner(r.groupValues[3], ctx, m)?.let { ctx.other(it) }
                ?: (r.groupValues[4].takeIf { it.isNotEmpty() }?.let { ph -> m.cards[ph]?.display }
                        ?.let { name -> ctx.events.lastOrNull { it.verb == "cast" && it.card?.name == name }?.player }?.let { ctx.other(it) }
                    ?: ctx.events.lastOrNull { it.verb == "cast" }?.player?.let { ctx.other(it) }) ?: actor ?: subject ?: "me"
            val targetCard = r.groupValues[4].takeIf { it.isNotEmpty() }?.let { m.cards.getValue(it) }
            val lastCast = ctx.events.lastOrNull { it.verb == "cast" && (targetCard == null || it.card?.name == targetCard.display) }
            val targetName = targetCard?.display ?: lastCast?.card?.name ?: ctx.objects[lastCast?.obj ?: ""]?.card?.name ?: return@let
            if (targetCard != null && targetCard.display !in ctx.castCards) emitCast(ctx.other(who) ?: "opp", targetCard, "", m, ctx)
            emitCast(who, counterCard, " targeting " + slug(targetName) + ":spell", m, ctx)
            ctx.note(who); return true
        }
        // "I control Kiki-Jiki and copy Zealous Conscripts": the permanent just named is what does the copying, and
        // the named creature is what its ability targets.
        Regex("""^cop(?:y|ies|ying) (?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: ctx.lastActor ?: "me"
            val src = ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).zone == "battlefield" } ?: return@let
            val card = m.cards.getValue(r.groupValues[1])
            val tgt = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            if (tgt == src) return@let
            ctx.events += EventSpec("activate", player = who, obj = src, targets = listOf(tgt))
            ctx.lastActor = who; ctx.lastMentioned = src; return true
        }
        // "copy my opponent's Lightning Bolt with Twincast": the copy spell targeting that spell.
        Regex("""^cop(?:y|ies|ying) ($possPrefix)?(c\d+|it|that|that spell) with (?:an? |the |my )?(c\d+)((?: (?:targeting|aiming (?:it )?at|pointing (?:it )?at|retargeting (?:it )?to|choosing) .*)?)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val what = if (r.groupValues[2].startsWith("c")) "${r.groupValues[1]}${r.groupValues[2]}" else "it"
            emitCast(who, m.cards.getValue(r.groupValues[3]), " targeting $what", m, ctx)
            val castIndex = ctx.events.lastIndex
            val newTargets = r.groupValues[4].trim().replace(Regex("""^(?:aiming (?:it )?at|pointing (?:it )?at|retargeting (?:it )?to|choosing)"""), "targeting").let { if (it.isEmpty()) emptyList() else targetsIn(it, m, ctx) }
            if (newTargets.isNotEmpty()) ctx.events[castIndex] = ctx.events[castIndex].copy(to = "copytarget:" + newTargets.joinToString("|"))
            return true
        }
        // "sacrifice two Treasure tokens to it" / "sac a Treasure to Krark-Clan Ironworks": that permanent's sacrifice-cost ability, once per sacrificed thing.
        Regex("""^(?:sacrifices?|sacs?|feeds?) (an? |\d+ |two |three |four |five )?((?:\d+/\d+ )?(?:[a-z]+ )*?tokens?|c\d+)(?: (?:to|into|for) (?:it|that|(?:my |the )?(c\d+))(?:'s ability)?)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val srcId = r.groupValues[3].takeIf { it.isNotEmpty() }?.let { m.cards.getValue(it) }?.let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) } ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            val n = r.groupValues[1].trim().let { if (it.isEmpty()) 1 else number(it) ?: 1 }
            val ids = if (r.groupValues[2].startsWith("c") && r.groupValues[2].drop(1).all { it.isDigit() }) (1..n).map { m.cards.getValue(r.groupValues[2]).let { c -> objectIdFor(c, ctx)?.takeIf { it != srcId && ctx.events.none { e -> e.verb == "activate" && e.to == "sacrifice:$it" } } ?: addObject(c, who, false, ctx, allowDuplicate = true) } }
                      else describedTokens(r.groupValues[1], "", r.groupValues[2].removeSuffix("s").removeSuffix(" token").removeSuffix(" tokens"), who, ctx)
            ids.forEach { ctx.events += EventSpec("activate", player = who, obj = srcId, to = "sacrifice:$it") }
            ctx.lastActor = who; ctx.lastMentioned = srcId; return true
        }
        // "it gets Lightning Bolted" / "it gets bolted": the other player casts that spell at the thing just mentioned.
        Regex("""^(?:it|that|he|she) (?:gets?|got|is|was) (c\d+)$""").find(c)?.let { r ->
            val target = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.lastOrNull { it.verb == "cast" }?.targets?.firstOrNull { it in ctx.objects } ?: return@let
            val who = ctx.other(ctx.objects.getValue(target).controller) ?: "opp"
            if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
            emitCast(who, m.cards.getValue(r.groupValues[1]), "", m, ctx); ctx.events[ctx.events.lastIndex] = ctx.events.last().copy(targets = listOf(target)); return true
        }
        // "mills me with Glimpse the Unthinkable" / "mills them for ten with Glimpse": the mill spell, targeting that player.
        Regex("""^mills? (me|myself|them|themselves|him|her|my opponent|the opponent|@\w+)(?: for (?:\d+|\w+)(?: cards)?)? (?:with|using|via) (?:an? |the |my )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "opp"
            emitCast(who, m.cards.getValue(r.groupValues[2]), " targeting " + when (r.groupValues[1]) { "me", "myself" -> "me"; "themselves" -> if (who == "me") "me" else "them"; else -> r.groupValues[1] }, m, ctx); return true
        }
        // "tap Krenko": its {T} ability (not for mana).
        Regex("""^taps? (?:an? |the |my )?(c\d+)(?: for (?:its|the) (?:ability|effect|tokens?|goblins?))?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1]); val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("activate", player = who, obj = id); ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        // "cast equip on Bonesplitter targeting it" / "equip Bonesplitter to the Bears" / "pay equip for it onto Bears": the Equipment's equip ability.
        Regex("""^(?:casts? |pays? |uses? |activates? )?equips? (?:on |for |with |the )?(?:an? |the |my )?(c\d+)(?:'s equip(?: ability)?)? (?:targeting|to|onto|on) (?:an? |the |my )?(c\d+|it|that)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val eq = m.cards.getValue(r.groupValues[1]).let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
            // "I cast Grizzly Bears and equip Lightning Greaves to it": the Bears has to resolve before it can be
            // equipped, and until it does there is no object for "it" to mean.
            val target = if (r.groupValues[2] == "it" || r.groupValues[2] == "that")
                (castPermanentObject(ctx)?.takeIf { ctx.objects[it]?.controller == who }
                    ?: ctx.objects.values.lastOrNull { it.id != eq && it.controller == who && isCreatureName(it.card.name) }?.id ?: return@let)
                else m.cards.getValue(r.groupValues[2]).let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
            ctx.events += EventSpec("activate", player = who, obj = eq, targets = listOf(target)); ctx.lastActor = who; ctx.lastMentioned = target; return true
        }
        // "tap Grizzly Bears with Icy Manipulator": Icy's ability targeting the Bears.
        Regex("""^taps? (?:an? |the |my |their |down )?(c\d+) (?:with|using) (?:an? |the |my )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val target = m.cards.getValue(r.groupValues[1]); val src = m.cards.getValue(r.groupValues[2])
            val tid = objectIdFor(target, ctx) ?: addObject(target, ctx.other(who) ?: "opp", false, ctx); val sid = objectIdFor(src, ctx) ?: addObject(src, who, false, ctx)
            ctx.events += EventSpec("activate", player = who, obj = sid, targets = listOf(tid)); ctx.lastActor = who; ctx.lastMentioned = tid; return true
        }
        // Paying, or not, for a tax: "pays", "pays the 1", "doesn't pay", "declines to pay (for Rhystic)".
        Regex("""^(?:but |and |so |yet )?(?:(does not|doesn't|don't|do not|won't|will not|didn't|declines? to|refuses? to|isn't going to|never|without|can't|cannot|can not|couldn't|could not|(?:am|is|are) unable to|(?:am|is|are)n't able to) )?(?:pays?|paid|paying)\b(.*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: ctx.events.lastOrNull { it.verb == "cast" }?.player ?: "me"
            val declines = r.groupValues[1].isNotEmpty() || Regex("""^ (?:for )?(?:none|nothing|no|neither)\b""").containsMatchIn(r.groupValues[2])
            val pay = EventSpec("pay", player = who, to = if (declines) "no" else "yes")
            // Paying (or not) for an attack tax decides whether the attack happens: it goes before the attack it belongs to.
            val lastAttack = ctx.events.indexOfLast { (it.verb == "attack" || it.verb == "attackAll") && it.player == who }
            if (lastAttack >= 0 && ctx.events.drop(lastAttack + 1).all { it.verb == "attack" || it.verb == "attackAll" }) ctx.events.add(lastAttack, pay)
            // "Then they pay the 1": the payment is part of resolving what's on the stack, so it goes before the "then" resolution.
            else if (ctx.events.lastOrNull()?.verb == "resolveAll" && ctx.events.getOrNull(ctx.events.lastIndex - 1)?.verb in setOf("cast", "activate", "trigger")) ctx.events.add(ctx.events.lastIndex, pay)
            // "I play Steam Vents and pay 2 life": a shockland's payment is made as the land enters, so nothing
            // can happen in between; said after the land play it has to go before it to be made at all.
            else if (ctx.events.lastOrNull()?.let { e -> e.player == who && (e.verb == "playLand" || (e.verb == "cast" &&
                (e.card?.name ?: ctx.objects[e.obj]?.card?.name)?.let { n -> names.lookup(Names.normalize(n))?.typeLine?.contains("Land", true) } == true)) } == true)
                ctx.events.add(ctx.events.lastIndex, pay)
            else ctx.events += pay
            ctx.lastActor = who; return true
        }
        // Possession: "have X (on the battlefield|out|in play)", "control X", "X is on the battlefield".
        Regex("""^(?:have|has|got|control|controls|controlling|'ve got|am playing|is playing|are playing|run|running)\s+(?:an? |the |my |their |(\d+|two|three|four|five)(?: copies of| copys of)? )?(?:(commander )|my commander )?((?:(?:hexproof|indestructible|flying|trample|lifelink|deathtouch|haste|vigilance|reach|menace|shroud|unblockable|tapped|untapped) )*)(c\d+)(?:'s)?(.*)$""").find(c)?.let { r00 ->
            val adjectives = r00.groupValues[3].trim().split(' ').filter { it.isNotEmpty() }
            val r0 = object { val groupValues = listOf(r00.groupValues[0], r00.groupValues[1], r00.groupValues[2], r00.groupValues[4], r00.groupValues[5] + (if ("tapped" in adjectives) " tapped" else "")) }
            val isCommander = r0.groupValues[2].isNotEmpty() || r0.groupValues[0].contains("my commander ")
            val r = object { val groupValues = listOf(r0.groupValues[0], r0.groupValues[1], r0.groupValues[3], r0.groupValues[4]) }
            val owner = actor ?: (if (ctx.clauseIndex > 0) ctx.lastActor else null) ?: "me"
            val count = r.groupValues[1].let { numberWords[it] ?: it.toIntOrNull() ?: 1 }
            val rest = r.groupValues[3]
            if (count > 1) { repeat(count) { addObject(m.cards.getValue(r.groupValues[2]), owner, false, ctx, allowDuplicate = true) }; ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner; return true }
            // "I have Mana Crypt at 20 life": the life total, said as part of the board.
            Regex("""\b(?:at|on|with) (\d+) life\b""").find(rest)?.let { l -> ctx.life[owner] = l.groupValues[1].toInt(); ctx.note(owner) }
            Regex("""\bwith (\d+|\w+|no) cards? in (?:their |my |his |her )?hand\b""").find(rest)?.let { h -> ctx.handSize[owner] = if (h.groupValues[1] == "no") 0 else number(h.groupValues[1]) ?: 0 }
            Regex("""\bwith (\d+|\w+|no) cards? (?:left )?in (?:their |my |his |her |the )?library\b""").find(rest)?.let { h -> ctx.librarySize[owner] = if (h.groupValues[1] == "no") 0 else number(h.groupValues[1]) ?: 0 }
            if (Regex("""\bin (?:my |their |his |her |the )?(?:graveyard|yard|bin|exile)\b""").containsMatchIn(rest)) {
                val zone = if (rest.contains("exile")) "exile" else "graveyard"
                val id = addObject(m.cards.getValue(r.groupValues[2]), owner, false, ctx, zone = zone, allowDuplicate = ctx.objects.values.any { it.card.oracleId == m.cards.getValue(r.groupValues[2]).oracleId && it.zone != zone })
                ctx.objects[id] = ctx.objects.getValue(id).copy(zone = zone); ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner; return true
            }
            if (((rest.contains("in hand") || rest.contains("in my hand")) && !Regex("""\bcards? in (?:their |my |his |her )?hand\b""").containsMatchIn(rest)) || m.cards.getValue(r.groupValues[2]).isSpellOnly) {
                ctx.notes += "${m.cards.getValue(r.groupValues[2]).display} noted as in hand (hidden zones are only tracked when you cast from them)."
                ctx.inHand.getOrPut(owner) { mutableListOf() } += m.cards.getValue(r.groupValues[2])
                addObject(m.cards.getValue(r.groupValues[2]), owner, false, ctx, zone = "hand", allowDuplicate = true)
                ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner
                return true
            }
            // "I control Grizzly Bears and my opponent controls Grizzly Bears": two permanents, not one. Matching on
            // the card alone collapsed them and a board wipe then killed only one of the two.
            val hostCard = m.cards.getValue(r.groupValues[2])
            val otherSide = ctx.objects.values.any { it.card.oracleId == hostCard.oracleId && it.zone == "battlefield" && it.controller != owner }
            val hostId = addObject(hostCard, owner, tapped = rest.contains("tapped") && !rest.contains("untapped"), ctx, allowDuplicate = otherSide)
            if (isCommander) ctx.objects[hostId] = ctx.objects.getValue(hostId).copy(commander = true)
            // "a Llanowar Elves that just came down" / "with summoning sickness": it came under its controller's
            // control this turn. Without this the tap ability was answered as if it had been there all along.
            if (Regex("""\bthat (?:i|they|he|she) just (?:played|cast)\b|\bthat just came down\b|\b(?:i|they) just played\b|\bwith summoning sickness\b|\bthat (?:just )?(?:came down|entered|hit the battlefield) this turn\b|\bcast this turn\b|\bplayed this turn\b""").containsMatchIn(rest))
                ctx.objects[hostId] = ctx.objects.getValue(hostId).copy(summoningSick = true)
            // "Meddling Mage naming Lightning Bolt" / "Pithing Needle on Sensei's Divining Top" (named): the chosen card name.
            Regex("""\b(?:naming|that names|which names|named on|set to|choosing|on) (?:the number )?(\d+)\b""").find(rest)?.let { n ->
                ctx.objects[hostId] = ctx.objects.getValue(hostId).copy(named = n.groupValues[1])
                ctx.notes += "${m.cards.getValue(r.groupValues[2]).display} names the number ${n.groupValues[1]}."
                ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner; return true
            }
            Regex("""\b(?:naming|that names|which names|named on|set to|choosing) (?:an? |the )?(c\d+)\b""").find(rest)?.let { n -> val named = m.cards.getValue(n.groupValues[1]); ctx.objects[hostId] = ctx.objects.getValue(hostId).copy(named = named.display); ctx.notes += "${m.cards.getValue(r.groupValues[2]).display} names ${named.display}."; ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner; return true }
            // "Painter's Servant naming blue" / "Iona choosing white": the chosen colour.
            Regex("""\b(?:naming|that names|which names|set to|choosing|on) (white|blue|black|red|green)\b""").find(rest)?.let { n ->
                val colour = n.groupValues[1].lowercase()
                ctx.objects[hostId] = ctx.objects.getValue(hostId).copy(named = colour)
                ctx.notes += "${m.cards.getValue(r.groupValues[2]).display} names $colour."
                ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner; return true
            }
            // "Grizzly Bears with hexproof" / "with flying and lifelink": keywords as a trailer.
            // "Krenko, Mob Boss with five Goblins": the trailer says what else is on the battlefield beside it,
            // which is what an ability counting them will look at.
            Regex("""^ (?:with|alongside|next to|and) (an?|one|two|three|four|five|six|seven|eight|nine|ten|\d+) ($creatureKinds)$""").find(rest)?.let { a ->
                val n = number(a.groupValues[1]) ?: 1
                describedCreatures("$n ", "", a.groupValues[2], owner, ctx, "")
                ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner; ctx.lastMentioned = hostId; return true
            }
            val trailerKws = Regex("""^ (?:with|that has|which has|having) ((?:hexproof|indestructible|flying|trample|lifelink|deathtouch|haste|vigilance|reach|menace|shroud|unblockable|first strike|double strike|infect|wither|protection from \w+)(?:(?:,| and|, and) (?:hexproof|indestructible|flying|trample|lifelink|deathtouch|haste|vigilance|reach|menace|shroud|unblockable|first strike|double strike|infect|wither|protection from \w+))*)$""").find(rest)?.groupValues?.get(1)?.split(Regex(""",? and |, """))?.map { it.trim() } ?: emptyList()
            (adjectives + trailerKws).filter { it != "tapped" && it != "untapped" }.takeIf { it.isNotEmpty() }?.let { kws -> ctx.objects[hostId] = ctx.objects.getValue(hostId).copy(keywords = ctx.objects.getValue(hostId).keywords + kws); ctx.notes += "${m.cards.getValue(r.groupValues[2]).display} is read as having ${kws.joinToString(" and ")} (from an effect; say what gives it if that matters)." }
            // "Grizzly Bears with Darksteel Plate" / "Bears with Rancor on it": the Equipment or Aura is attached to it.
            Regex("""^ (?:with|wearing|carrying|equipped with|enchanted with) (?:an? |the |my |their )?(c\d+)(?: attached| equipped| on it| enchanting it)?$""").find(rest)?.let { a ->
                val eqCard = m.cards.getValue(a.groupValues[1])
                if (eqCard.typeLine.contains("Equipment") || eqCard.typeLine.contains("Aura")) {
                    val eq = objectIdFor(eqCard, ctx) ?: addObject(eqCard, owner, false, ctx)
                    ctx.objects[eq] = ctx.objects.getValue(eq).copy(attachedTo = hostId); ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner; ctx.lastMentioned = hostId; return true
                }
            }
            // "has Lightning Greaves on Grizzly Bears" / "has Rancor on their Bears": the first card is attached to the second.
            Regex("""^ (?:on|attached to|equipped to|enchanting|equipping) ($possPrefix|an? )?(c\d+)(?: (?:creature )?token)?$""").find(rest)?.let { a ->
                val hostOwner = when { a.groupValues[1].startsWith("@") -> a.groupValues[1].removePrefix("@").removeSuffix("'s "); a.groupValues[1] == "my " -> "me"; a.groupValues[1] == "their " -> pronounPlayer(ctx, "their"); else -> owner }
                val target = m.cards.getValue(a.groupValues[2]).let { objectIdFor(it, ctx) ?: addObject(it, hostOwner, false, ctx) }
                ctx.objects[hostId] = ctx.objects.getValue(hostId).copy(attachedTo = target); ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner; ctx.lastMentioned = target; return true
            }
            applyStateWords(hostId, rest, ctx)
            if (Regex("""\b(?:with (?:a )?regeneration(?: shield)?(?: (?:from|via|up|active|already paid|on it))?|regenerated|regeneration shield(?:ed)?|already regenerated)\b""").containsMatchIn(rest)) ctx.events += EventSpec("regenerate", obj = hostId)
            // "… with Rancor on it", "… enchanted with X", "… equipped with X"
            // "Grizzly Bears with Rancor" attaches the Rancor to the Bears; "Rancor with a Grizzly Bears" is the same
            // board said the other way round, so the Aura or Equipment is the one that attaches either way.
            Regex("""(?:enchanted with|equipped with|wearing|with) (?:an? |the )?(c\d+)""").findAll(rest).forEach { a ->
                val card = m.cards.getValue(a.groupValues[1])
                val id = addObject(card, owner, false, ctx)
                val hostIsAttachment = m.cards[r.groupValues[2]]?.typeLine?.let { it.contains("Aura") || it.contains("Equipment") } == true
                val namedIsAttachment = card.typeLine.contains("Aura") || card.typeLine.contains("Equipment")
                if (hostIsAttachment && !namedIsAttachment) ctx.objects[hostId] = ctx.objects.getValue(hostId).copy(attachedTo = id)
                else ctx.objects[id] = ctx.objects.getValue(id).copy(attachedTo = hostId)
            }
            // "Sword of Fire and Ice equipped to a 2/2": the thing it is on is a creature nobody named.
            Regex("""^\s*(?:equipped|attached|enchanting) (?:to |on )?(?:an? |the |my |their )?((\d+/\d+)(?: [a-z]+)*)$""").find(rest)?.let { a ->
                val wornBy = ctx.objects.values.lastOrNull { o -> o.controller == owner && o.id != hostId && (o.card.name ?: "").startsWith("a ${a.groupValues[2]}") }?.id
                    ?: describedCreatures("a ", a.groupValues[2], "creature", owner, ctx, "").firstOrNull()
                if (wornBy != null) { ctx.objects[hostId] = ctx.objects.getValue(hostId).copy(attachedTo = wornBy); ctx.lastMentioned = wornBy }
            }
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
        // "cast Sol Ring last turn" / "played Bears two turns ago": it's on the battlefield and has been since before this turn.
        Regex("""^(?:cast|played|put|resolved|dropped|slammed) (?:an? |the |their |my )?(c\d+)(?: (?:onto the battlefield|into play))? (?:last turn|on (?:their|my|his|her) last turn|a turn ago|(?:two|three|\d+|a few|several) turns ago|earlier in the game|on turn (?:one|two|three|\d+)|previously)$""").find(c)?.let { r ->
            val owner = actor ?: subject ?: "me"
            val id = objectIdFor(m.cards.getValue(r.groupValues[1]), ctx) ?: addObject(m.cards.getValue(r.groupValues[1]), owner, false, ctx)
            ctx.objects[id] = ctx.objects.getValue(id).copy(summoningSick = false); ctx.lastVerb = "have"; ctx.lastOwner = owner; ctx.lastActor = owner; ctx.lastMentioned = id; return true
        }
        // "respond by activating X's ability" / "respond by casting Y"
        Regex("""^respond(?:s|ed)? by (activating|casting|playing|tapping|sacrificing|attacking|blocking|giving|\w+ing) (.*)$""").find(c)?.let { r ->
            val who = actor ?: ctx.other(ctx.lastActor) ?: "me"
            val prefix = when (who) { "me" -> "i "; "opp" -> "they "; else -> "@$who " }
            val verb = when (r.groupValues[1]) { "activating" -> "activate "; "tapping" -> "tap "; "sacrificing" -> "sacrifice "; "attacking" -> "attack "; "blocking" -> "block "; "giving" -> "give "; "casting", "playing" -> "cast "; else -> r.groupValues[1] + " " }
            return readClause(prefix + verb + r.groupValues[2], m, ctx).also { ctx.lastActor = who }
        }
        // "bouncing it with Unsummon" / "killing their Bears with Doom Blade" / "pumping it with Giant Growth": the named spell cast at that permanent.
        Regex("""^(?:bounc|kill|destroy|exil|pump|protect|sav|shrink|hit|burn|target|counter|shock|bolt|tap|untap|blink|flicker|remov|answer|deal with|stop|fog|zap|murder|path|swords|plow)(?:e|es|s|ing)? (?:my |their |the |his |her |that )?(c\d+|it|that)(?: (?:in response|again))? (?:with|using|via|by casting|through) (?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            return readClause("${when (who) { "me" -> "i"; "opp" -> "they"; else -> "@$who" }} cast ${r.groupValues[2]} targeting ${r.groupValues[1]}", m, ctx)
        }
        // "give my Bears hexproof with Blossoming Defense" / "giving it +3/+3 with Giant Growth": the named spell cast at that permanent.
        Regex("""^giv(?:e|es|ing) (?:my |their |the |his |her )?(c\d+|it|that) (?:[a-z+/\d, -]+?) (?:with|using|via|by casting|through) (?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            return readClause("${when (who) { "me" -> "i"; "opp" -> "they"; else -> "@$who" }} cast ${r.groupValues[2]} targeting ${r.groupValues[1]}", m, ctx)
        }
        // "put Rakdos onto the battlefield with the trigger" / "with Kaalia's trigger": the choice for that permanent's triggered ability.
        Regex("""^(?:puts?|putting|drops?|cheats?) (?:an? |the |my )?(c\d+) (?:onto the battlefield|into play|out|in)(?: tapped and attacking| attacking| tapped)? (?:with|off|using|via|from) (?:the |its |her |his )?(?:(c\d+)(?:'s)? )?trigger(?:ed ability)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val srcId = r.groupValues[2].takeIf { it.isNotEmpty() }?.let { m.cards.getValue(it) }?.let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
                ?: ctx.events.lastOrNull { it.verb == "attack" && it.player == who }?.obj ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            val cardId = addObject(m.cards.getValue(r.groupValues[1]), who, false, ctx, zone = "hand", allowDuplicate = true)
            // The choice has to be known before the trigger resolves: it goes in front of the attack (or whatever) that caused it.
            val at = ctx.events.indexOfLast { (it.verb == "attack" || it.verb == "attackAll" || it.verb == "cast" || it.verb == "enter") && (it.obj == srcId || slug(it.card?.name ?: "") == srcId) }
            val choose = EventSpec("choose", player = who, obj = srcId, to = "put:$cardId")
            if (at >= 0) { var i = at; while (i > 0 && (ctx.events[i - 1].verb == "attack" || ctx.events[i - 1].verb == "attackAll") && ctx.events[i - 1].player == ctx.events[at].player) i--; ctx.events.add(i, choose) } else ctx.events += choose
            ctx.lastActor = who; ctx.lastMentioned = cardId; return true
        }
        // "equip it right away" / "tap it": the last equipment of mine onto the creature just cast; the creature just cast/mentioned taps.
        Regex("""^equips? (?:it|that|him|her)(?: right away| immediately| now| this turn| at once)?$""").find(c)?.let {
            val who = actor ?: subject ?: "me"
            val hostId = ctx.events.lastOrNull { it.verb == "cast" && it.player == who }?.card?.name?.let { n -> ctx.objects.values.firstOrNull { it.card.name == n }?.id ?: slug(n) } ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            val eqId = ctx.objects.values.lastOrNull { it.controller == who && it.id != hostId && !it.token }?.id ?: return@let
            if (ctx.events.lastOrNull()?.verb == "cast") ctx.events += EventSpec("resolveAll")
            ctx.events += EventSpec("activate", player = who, obj = eqId, targets = listOf(hostId)); ctx.lastActor = who; ctx.lastMentioned = hostId; return true
        }
        Regex("""^taps? (?:it|that|him|her)(?: for (?:its|the) (?:ability|effect|tokens?))?$""").find(c)?.let {
            val who = actor ?: subject ?: "me"
            val id = ctx.events.lastOrNull { it.verb == "cast" && it.player == who }?.card?.name?.let { n -> ctx.objects.values.firstOrNull { it.card.name == n }?.id ?: slug(n) } ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            if (ctx.events.lastOrNull()?.verb == "cast") ctx.events += EventSpec("resolveAll")
            ctx.events += EventSpec("activate", player = who, obj = id); ctx.lastActor = who; return true
        }
        // "I have Ulamog and cast it": the card wasn't on the battlefield after all; it's cast now.
        Regex("""^(?:casts?|casting) (?:it|that|him|her)$""").find(c)?.let {
            val who = actor ?: subject ?: "me"
            if (ctx.inHand[who]?.isNotEmpty() == true) return@let
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.events.none { e -> e.obj == it || it in e.targets } } ?: return@let
            val spec = ctx.objects.remove(id) ?: return@let
            ctx.events += EventSpec("cast", player = who, card = spec.card); ctx.lastActor = who; ctx.lastVerb = "cast"; ctx.castCards += spec.card.name ?: ""; ctx.lastMentioned = "cast:$id"
            ctx.notes += "${spec.card.name} was read as being cast now (not already on the battlefield)."; return true
        }
        // "and get Lightning Bolt" / "find Sol Ring" after a tutor: the card is now in hand.
        Regex("""^(?:gets?|getting|fetch(?:es)?|finds?|tutors?(?: for| up)?|grabs?|searches? (?:for|up)) (?:an? |the |my )?(c\d+)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            if (ctx.events.lastOrNull { it.verb == "cast" }?.player != who && ctx.lastVerb != "cast") return@let
            val card = m.cards.getValue(r.groupValues[1])
            ctx.inHand.getOrPut(who) { mutableListOf() } += card; ctx.notes += "${card.display} is now in ${if (who == "me") "your" else (ctx.players[who] ?: "your opponent") + "'s"} hand (found by the search)."; return true
        }
        // "fetch Pacifism with Zur" / "tutor up X with Y's trigger": the card comes from the library through that permanent's ability.
        Regex("""^(?:fetch(?:es)?|tutors?(?: for| up)?|searches? (?:for|up)|grabs?|gets?|finds?) (?:an? |the |my )?(c\d+) (?:with|off|using|via|from) (?:an? |the |my )?(c\d+)(?:'s (?:trigger|ability))?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val src = m.cards.getValue(r.groupValues[2]).let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
            val cardId = addObject(m.cards.getValue(r.groupValues[1]), who, false, ctx, zone = "library", allowDuplicate = true)
            val choose = EventSpec("choose", player = who, obj = src, to = "put:$cardId")
            val at = ctx.events.indexOfLast { (it.verb == "attack" || it.verb == "attackAll") && (it.obj == src || it.player == who) }
            if (at >= 0) { var i = at; while (i > 0 && (ctx.events[i - 1].verb == "attack" || ctx.events[i - 1].verb == "attackAll") && ctx.events[i - 1].player == ctx.events[at].player) i--; ctx.events.add(i, choose) } else ctx.events += choose
            ctx.lastActor = who; ctx.lastMentioned = cardId; return true
        }
        // "put it on their Grizzly Bears": the Aura just fetched goes on that creature as it enters.
        Regex("""^(?:puts?|putting|sticks?|slaps?) (?:it|that) (?:on|onto) (?:their |my |the |my opponent's |@\w+'s )?(c\d+)$""").find(c)?.let { r ->
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).zone != "battlefield" } ?: return@let
            val who = ctx.objects.getValue(id).controller
            val hostCard = m.cards.getValue(r.groupValues[1]); val host = objectIdFor(hostCard, ctx) ?: addObject(hostCard, if (c.contains("their") || c.contains("opponent")) (ctx.other(who) ?: "opp") else who, false, ctx)
            ctx.objects[id] = ctx.objects.getValue(id).copy(attachedTo = host); ctx.lastMentioned = id; return true
        }
        // "equip Bonesplitter to the Bears"
        Regex("""^equips? (?:my |the )?(c\d+|it) (?:to|onto) (?:my |the |an? )?(c\d+|(?:\d+/\d+ )?(?:[a-z]+ )*?token|(?:\d+/\d+)(?: [a-z]+)*)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val eqId = if (r.groupValues[1] == "it") (ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.lastOrNull { it.verb == "cast" && it.player == who }?.card?.name?.let { slug(it) } ?: return@let) else m.cards.getValue(r.groupValues[1]).let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
            val hostId = if (r.groupValues[2].startsWith("c") && r.groupValues[2].drop(1).all { it.isDigit() }) m.cards.getValue(r.groupValues[2]).let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
                         else if (Regex("""^\d+/\d+(?: [a-z]+)*$""").matches(r.groupValues[2]) && !r.groupValues[2].endsWith("token")) {
                             // "equip it to a 1/1": a creature described rather than named, and not a token.
                             val pt = Regex("""^(\d+/\d+)""").find(r.groupValues[2])!!.groupValues[1]
                             ctx.objects.values.lastOrNull { o -> o.controller == who && (o.card.name ?: "").startsWith("a $pt") }?.id
                                 ?: describedCreatures("a ", pt, "creature", who, ctx, "").firstOrNull() ?: return@let
                         }
                         else { val name = r.groupValues[2]; var id = slug(name); var k = 2; while (ctx.objects.containsKey(id)) id = slug(name) + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = who, token = true); id }
            if (ctx.events.lastOrNull()?.verb == "cast" && r.groupValues[1] == "it") ctx.events += EventSpec("resolveAll")
            ctx.events += EventSpec("activate", player = who, obj = eqId, targets = listOf(hostId)); ctx.lastActor = who; ctx.lastMentioned = hostId; return true
        }
        // "Rancor is on my Bears", "Rancor is attached to / enchanting / equipping my Bears", "there is a Rancor on my Bears"
        Regex("""^(?:there(?: is| are|'s) )?(?:an? |$possPrefix)?(c\d+) (?:is |are )?(?:on|attached to|enchanting|equipping|equips|enchants) (?:an? |($possPrefix))?(c\d+)$""").find(c)?.let { r ->
            val hostCard = m.cards.getValue(r.groupValues[3]); val attCard = m.cards.getValue(r.groupValues[1])
            val hostId = objectIdFor(hostCard, ctx) ?: addObject(hostCard, possessiveOwner(r.groupValues[2], ctx) ?: actor ?: "me", false, ctx)
            val attId = objectIdFor(attCard, ctx) ?: addObject(attCard, ctx.objects.getValue(hostId).controller, false, ctx)
            ctx.objects[attId] = ctx.objects.getValue(attId).copy(attachedTo = hostId); ctx.lastMentioned = hostId; return true
        }
        // "my opponent's Bears has a Rancor on it", "the Bears with Jitte attached": the host said first, the Aura last.
        Regex("""^($possPrefix)?(c\d+|it|that) (?:has|have|had|with) (?:an? |the |$possPrefix)?(c\d+) (?:on it|attached|attached to it|equipped|equipped to it|enchanting it|on top)$""").find(c)?.let { r ->
            val owner = possessiveOwner(r.groupValues[1], ctx) ?: actor ?: ctx.lastOwner
            val hostId = if (r.groupValues[2] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                         else m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx) } ?: return@let
            val attCard = m.cards[r.groupValues[3]] ?: return@let
            val attId = objectIdFor(attCard, ctx) ?: addObject(attCard, ctx.objects.getValue(hostId).controller, false, ctx)
            ctx.objects[attId] = ctx.objects.getValue(attId).copy(attachedTo = hostId); ctx.lastMentioned = hostId; return true
        }
        // "my opponent's Grizzly Bears is enchanted with Pacifism": the host said first, under a possessive the
        // actor rule doesn't strip. "my Bears is equipped with X" is read earlier; only the longer forms reach here.
        Regex("""^($possPrefix)?(c\d+|it|that) (?:is |are |was |were )?(?:enchanted with|equipped with|wearing|carrying) (?:an? |the |$possPrefix)?(c\d+)$""").find(c)?.let { r ->
            val owner = possessiveOwner(r.groupValues[1], ctx) ?: actor ?: ctx.lastOwner
            val hostId = if (r.groupValues[2] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                         else m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx) } ?: return@let
            val attCard = m.cards[r.groupValues[3]] ?: return@let
            val attId = objectIdFor(attCard, ctx) ?: addObject(attCard, ctx.objects.getValue(hostId).controller, false, ctx)
            ctx.objects[attId] = ctx.objects.getValue(attId).copy(attachedTo = hostId); ctx.lastMentioned = hostId; return true
        }
        Regex("""^(?:an? |the )?(c\d+) (?:is|are) (?:on the battlefield|in play|out|on board|on my side|on my board|on my field|on the field|under my control)""").find(c)?.let { r ->
            addObject(m.cards.getValue(r.groupValues[1]), actor ?: "me", clause0.contains("tapped"), ctx); return true
        }
        // "@@obj:<id> enters": the referent a question about entering already settled.
        Regex("""^@@obj:(\S+) (?:enters|comes in|etbs)(?: the battlefield)?$""").find(c)?.let { r ->
            val id = r.groupValues[1].takeIf { it in ctx.objects } ?: return@let
            ctx.objects[id] = ctx.objects.getValue(id).copy(zone = "hand")
            ctx.events += EventSpec("enter", obj = id); ctx.lastMentioned = id; return true
        }
        Regex("""^(?:it|that|he|she) (?:enters|comes in|etbs|enters the battlefield)(?: now| again)?$""").find(c)?.let {
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            ctx.objects[id] = ctx.objects.getValue(id).copy(zone = "hand"); ctx.events += EventSpec("enter", obj = id); return true
        }
        Regex("""^(an? |\d+ |two |three )?(?:(\d+/\d+)s?\s*)?($creatureKinds|artifact|enchantment|land|token|permanent)?s? ?(?:enters|enter|comes? in|etbs?)(?: the battlefield)?(?: under (my|their|his|her) control)?(?: with (an?|one|two|three|four|five|\d+) ([+-]\d+/[+-]\d+|[a-z]+) counters?(?: on it)?)?$""").find(c)?.let { r ->
            val kind = r.groupValues[3]
            if (kind.isEmpty() && r.groupValues[2].isEmpty()) return@let
            if (kind.isNotEmpty() && kind !in setOf("artifact", "enchantment", "land", "token", "permanent") && !Regex("""^(?:$creatureKinds)$""").matches(kind)) return@let
            val who = when (r.groupValues[4]) { "my" -> "me"; "their", "his", "her" -> pronounPlayer(ctx, "their"); else -> actor ?: subject ?: ctx.lastOwner ?: "me" }
            val ids = if (kind == "land") {
                val n = r.groupValues[1].trim().let { if (it.isEmpty()) 1 else number(it) ?: 1 }
                (1..n).map { var id = slug("a land"); var k = 2; while (ctx.objects.containsKey(id)) id = slug("a land") + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = "a basic land"), controller = who); id }
            } else describedCreatures(r.groupValues[1], r.groupValues[2], if (kind.isEmpty() || kind in setOf("artifact", "enchantment", "token", "permanent")) "creature" else kind, who, ctx, "")
            if (ids.isEmpty()) return@let
            // "a creature enters with two +1/+1 counters": the counters go on as it enters, so counter doublers
            // and Hardened Scales apply to them. Left to the engine rather than put on the object here.
            val entersWith = r.groupValues[5].takeIf { it.isNotEmpty() }?.let { n -> "counters:${number(n) ?: 1}:${r.groupValues[6]}" }
            for (id in ids) { ctx.objects[id] = ctx.objects.getValue(id).copy(zone = "hand"); ctx.events += EventSpec("enter", obj = id, to = entersWith) }
            ctx.lastMentioned = ids.last(); ctx.lastActor = who; ctx.lastOwner = who; ctx.note(who); return true
        }
        // "I play a land" / "they put a land onto the battlefield": the same as a land entering, which is what
        // landfall cares about. A land nobody named, so its type doesn't matter.
        Regex("""^(?:plays?|played|playing|puts?|put|putting) (an? |another |the |\d+ |two |three )?(?:basic )?lands?(?: card)?(?: onto the battlefield| into play| down| out| from (?:my|their|his|her) hand)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: ctx.lastOwner ?: "me"
            val n = r.groupValues[1].trim().let { if (it.isEmpty() || it == "a" || it == "an" || it == "another" || it == "the") 1 else number(it) ?: 1 }
            val ids = (1..n).map { var id = slug("a land"); var k = 2; while (ctx.objects.containsKey(id)) id = slug("a land") + "_" + (k++)
                ctx.objects[id] = ObjectSpec(id, CardRef(name = "a basic land"), controller = who, zone = "hand"); id }
            val played = Regex("""^(?:plays?|played|playing)\b""").containsMatchIn(c)
            for (id in ids) ctx.events += EventSpec(if (played) "playLand" else "enter", player = if (played) who else null, obj = id)
            ctx.lastMentioned = ids.last(); ctx.lastActor = who; ctx.lastOwner = who; if (played) ctx.lastVerb = "playLand"; ctx.note(who); return true
        }
        Regex("""^(?:an? |the )?(c\d+) (?:enters|comes in|etbs|enters the battlefield)""").find(c)?.let { r ->
            val id = addObject(m.cards.getValue(r.groupValues[1]), actor ?: subject ?: "me", false, ctx, zone = "hand")
            ctx.events += EventSpec("enter", obj = id); ctx.lastMentioned = id; return true
        }

        return readClauseC(clause0, c, actor, subject, m, ctx)
    }

    /** The last of the clause rules; see readClauseB. */
    private fun readClauseC(clause00: String, c0: String, actor: String?, subject: String?, m: Marked, ctx: Ctx): Boolean {
        var clause0 = clause00
        var c = c0
        // Casting, possibly with targets: "casts C1 (targeting|on|at) <ref>".
        // A bare "cast X" names nobody: it is whoever was last acting — read as the opponent, "I'm at 3 life and
        // cast Angel's Grace" gave the opponent the spell and answered about their life total instead.
        Regex("""^(?:then )?(?:$castVerbs|$respondVerbs)\s+(?:an? |the |another |their |my |a second |a third |my second )?(kicked |overloaded )?(c\d+)(.*)$""").find(c)?.let { r ->
            emitCast(subject ?: ctx.lastActor ?: "me", m.cards.getValue(r.groupValues[2]), r.groupValues[3] + (if (r.groupValues[1].isNotEmpty()) " " + r.groupValues[1].trim() else "") + (if (c.startsWith("kick")) " kicked" else "") + (if (c.startsWith("evok")) " with evoke" else ""), m, ctx); return true
        }
        // Verbified card name right after the actor: "Stifles the trigger", "Bolt the bears".
        Regex("""^(c\d+)s?\s+(?:(?:targeting|on|at)\s+)?((?:the|my|their|that|this|it|them|me|an?|c\d+|\d+/\d+)\b.*)$""").find(c)?.let { r ->
            emitCast(subject ?: ctx.lastActor ?: "me", m.cards.getValue(r.groupValues[1]), " targeting " + r.groupValues[2], m, ctx); return true
        }
        // Loyalty abilities: "activate Jace's +1", "use Jace's -3 on the Bears", "+1 Jace", "Jace -3 targeting X"
        Regex("""^(?:(?:$activateVerbs)\s+)?(?:an? |the |their |my )?(c\d+)(?:'s)?\s+([+\u2212-]?\d+)(?: ability)?(.*)$""").find(c)?.let { r ->
            val who = subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("activate", player = who, obj = id, to = r.groupValues[2].replace('\u2212', '-'), targets = targetsIn(r.groupValues[3], m, ctx)); ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        Regex("""^(?:(?:$activateVerbs)\s+)?(?:(?:the |its |his |her )?ultimate|ult|ultimates?|(?:use|activate|fire|go for) (?:the |its |his |her )?ultimate(?: ability)?)(?: of| on| with)? (?:him|her|it|them|(?:$possPrefix|an? )?c\d+)?(?: right away| immediately| this turn| now| the turn (?:he|she|it) comes down)?(.*)$""").find(c)?.let { r ->
            val who = subject ?: "me"
            val named = Regex("""(c\d+)""").find(r.groupValues[0].substringBefore(r.groupValues[1].ifEmpty { "\u0000" }))?.groupValues?.get(1)?.let { m.cards.getValue(it) }
            val id = named?.let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) } ?: ctx.lastMentioned?.takeIf { it in ctx.objects }
                ?: ctx.events.lastOrNull { it.verb == "cast" && it.player == who }?.card?.name?.let { n -> ctx.objects.values.firstOrNull { it.card.name == n }?.id ?: slug(n) } ?: return@let
            if (ctx.events.lastOrNull()?.verb == "cast") ctx.events += EventSpec("resolveAll")
            ctx.events += EventSpec("activate", player = who, obj = id, to = "ultimate", targets = targetsIn(r.groupValues[1], m, ctx)); ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        Regex("""^(?:$activateVerbs)\s+(?:its|his|her|the|their|my) ([+\u2212-]?\d+)(?: ability| loyalty ability)?(.*)$""").find(c)?.let { r ->
            val who = subject ?: "me"
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: castPermanentObject(ctx) ?: ctx.objects.values.lastOrNull { it.controller == who }?.id ?: return@let
            ctx.events += EventSpec("activate", player = who, obj = id, to = r.groupValues[1].replace('\u2212', '-'), targets = targetsIn(r.groupValues[2], m, ctx)); ctx.lastActor = who; ctx.lastMentioned = id; return true
        }
        Regex("""^(?:$activateVerbs|taps?|tapping)\s+(?:an? |the |their |my )?(c\d+)(?:'s)?(?: ability)?(?: with (\d+|\w+) (?:(\w+) )?counters?(?: on it)?)? (?:to put|putting|and puts?|to drop|to cheat) (?:an? |the |my )?(c\d+) (?:onto the battlefield|into play|out|onto the field)$""").find(c)?.let { r ->
            val who = subject ?: "me"
            val srcCard = m.cards.getValue(r.groupValues[1]); val sid = objectIdFor(srcCard, ctx) ?: addObject(srcCard, who, false, ctx)
            if (r.groupValues[2].isNotEmpty()) number(r.groupValues[2])?.let { n -> val kind = r.groupValues[3].ifEmpty { "charge" }; ctx.objects[sid] = ctx.objects.getValue(sid).copy(counters = ctx.objects.getValue(sid).counters + (kind to n)) }
            val cardId = addObject(m.cards.getValue(r.groupValues[4]), who, false, ctx, zone = "hand", allowDuplicate = true)
            ctx.events += EventSpec("activate", player = who, obj = sid, to = "put:$cardId"); ctx.lastActor = who; ctx.lastMentioned = cardId; return true
        }
        Regex("""^(?:$activateVerbs)\s+(?:an? |the |their |my )?(c\d+)(?:'s)?(?: ability)?(.*)$""").find(c)?.let { r ->
            val who = subject ?: "me"
            val card = m.cards.getValue(r.groupValues[1])
            val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            val xRe = Regex("""\b(?:with|for|where|at) x ?(?:=|equal to|equals|being|of|as) ?(\d+)\b|\bx ?= ?(\d+)\b""")
            val xValue = xRe.find(r.groupValues[2])?.let { x -> (x.groupValues[1].ifEmpty { x.groupValues[2] }).toIntOrNull() }
            // "activate it twice", "activate it three times": the ability is used that many times in a row.
            val timesRe = Regex("""\b(twice|two times|three times|four times|five times|(\d+) times)\b""")
            val times = timesRe.find(r.groupValues[2])?.let { t -> if (t.groupValues[1].startsWith("twice")) 2 else number(t.groupValues[2].ifEmpty { t.groupValues[1].substringBefore(' ') }) ?: 1 } ?: 1
            val targets = targetsIn(r.groupValues[2].replace(xRe, "").replace(timesRe, "").trim(), m, ctx)
            repeat(times) { ctx.events += EventSpec("activate", player = who, obj = id, targets = targets, amount = xValue) }
            ctx.lastActor = who; return true
        }
        // Step beginnings: "at the beginning of my upkeep", "on their end step", "my upkeep starts".
        // "I go to my upkeep", "my opponent goes to combat", "moves to combat": the same statement as the step
        // beginning, said as something the player does — and it went unread.
        Regex("""^(?:(i|we|they|he|she|my opponent|the opponent|opponent|@\w+) )?(?:goes?|go|going|moves?|moving|passes? to|heads? to) (?:to |into )?(my|their|his|her|the)? ?(upkeep|draw step|end step|end of turn|precombat main phase|main phase|combat|beginning of combat|combat phase|second main phase)$""").find(c)?.let { r0 ->
            val r = object { val groupValues = listOf(r0.groupValues[0], r0.groupValues[2], r0.groupValues[3]) }
            val said = r0.groupValues[1].let { w -> when (w) { "" -> null; "i", "we" -> "me"; "my opponent", "the opponent", "opponent" -> pronounPlayer(ctx, "their"); else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w) } }
            val who = said ?: when (r.groupValues[1]) { "my" -> "me"; "their", "his", "her" -> pronounPlayer(ctx, "their"); else -> actor ?: ctx.lastActor ?: ctx.activePlayer ?: "me" }
            val step = when (r.groupValues[2]) { "upkeep" -> "upkeep"; "draw step" -> "draw"; "end step", "end of turn" -> "end"; "combat", "beginning of combat", "combat phase" -> "combat"; else -> "precombat_main" }
            ctx.activePlayer = who
            ctx.events += EventSpec("step", player = who, to = step); ctx.lastActor = who; ctx.note(who); return true
        }
        Regex("""^(?:at the beginning of |at the start of |during |on |at )?(my|their|the opponent's|opponent's|my opponent's|each|the) (upkeep|draw step|end step|end of turn|precombat main phase|main phase|combat|beginning of combat)(?: begins| starts)?$""").find(c)?.let { r ->
            val who = when (r.groupValues[1]) { "my" -> "me"; "each", "the" -> ctx.activePlayer ?: "me"; else -> "opp" }
            val step = when (r.groupValues[2]) { "upkeep" -> "upkeep"; "draw step" -> "draw"; "end step", "end of turn" -> "end"; "combat", "beginning of combat" -> "combat"; else -> "precombat_main" }
            ctx.activePlayer = who
            ctx.events += EventSpec("step", player = who, to = step); return true
        }
        // "attack with everything" / "no blocks".
        if (Regex("""^(?:attacks?|attacking|swings?|swinging)(?: with)? (?:everything|everyone|all(?: of)?(?: my)?(?: creatures)?|(?:the|my|their|his|her) (?:whole |entire )?(?:team|board|squad|side)|with everything)$""").matches(c) || Regex("""^(?:attacks?|swings?|go to combat)$""").matches(c)) {
            val who = actor ?: subject ?: "me"
            ctx.events += EventSpec("attackAll", player = who, targets = listOf(ctx.other(who) ?: "opp")); ctx.lastActor = who; ctx.lastVerb = "attack"; return true
        }
        if (Regex("""^(?:have no blockers|has no blockers|don't block|doesn't block|no blocks?|can't block|won't block|take it|takes it)$""").matches(c)) return true
        // "it isn't blocked", "nobody blocks", "it goes unblocked", "it gets through": there was no block, which is what the engine assumes anyway.
        if (unblockedRe.matches(c)) { if (actor != null) ctx.lastActor = actor; return true }
        // Combat: "attack with c1", "swing with c1 (at them)", "block (it) with c2".
        Regex("""^(?:attacks?|attacking|swings?|swinging) with (?:it|that|him|her|them)(?: again| once more| one more time| this turn| now)?$""").find(c)?.let {
            val who = actor ?: subject ?: "me"
            // "it" after a spell means what that spell targeted ("I cast Act of Treason on their Giant and attack with it"); an Aura's "it" is what it enchants.
            // Nothing was targeted, so "it" is the creature just cast: it has to resolve first, and then it's summoning sick.
            val lm = ctx.lastMentioned?.takeIf { !it.startsWith("cast:") }?.let { l -> ctx.objects[l]?.attachedTo ?: l }
            // You attack with your own, so "it" is the actor's creature — unless a spell of theirs took it
            // (Act of Treason), which is exactly when the last-named creature is someone else's and still right.
            val stolen = lm != null && ctx.events.any { (it.verb == "cast" || it.verb == "activate") && lm in it.targets }
            val id = lm?.takeIf { stolen || ctx.objects[it]?.controller == who }
                ?: ctx.objects.values.lastOrNull { it.controller == who && it.zone == "battlefield" && isCreatureName(it.card.name) }?.id
                ?: lm ?: ctx.events.lastOrNull { it.verb == "cast" }?.targets?.firstOrNull { it in ctx.objects } ?: castPermanentObject(ctx) ?: return false
            ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(ctx.other(who) ?: "opp")); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; return true
        }
        // "attack Jace with Hill Giant", "attacks their planeswalker with c2": the defender comes first.
        // "I attack their Jace with a 3/3" / "… with two 2/2s": the attacker may be described by its size too.
        Regex("""^(?:attacks?|attacking|swings? at|swinging at)\s+((?:$possPrefix|an? )?(?:c\d+|planeswalker|me|them|him|her|my opponent|the opponent|opponent|@\w+)|it|that)\s+with\s+(an? |the |my |their |\d+ |two |three |four |five )?(my commander |commander )?(c\d+|\d+/\d+)s?(?: ($kwNouns))?(?: ($creatureKinds))?(?: alone| by itself| only)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            // "I attack it with a 5/5" right after a planeswalker was described: "it" is that planeswalker. Read
            // as the player, the answer said they took the damage and the planeswalker was never touched.
            val lastPw = ctx.lastMentioned?.takeIf { id -> ctx.objects[id]?.let { o -> o.controller != who && o.zone == "battlefield" &&
                (o.card.name == "a planeswalker" || names.lookup(Names.normalize(o.card.name ?: ""))?.typeLine?.contains("Planeswalker", true) == true) } == true }
            val what = r.groupValues[4]
            val ids = if (cardRef.matches(what)) {
                val card = m.cards.getValue(what)
                val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
                if (r.groupValues[3].isNotEmpty()) ctx.objects[id] = ctx.objects.getValue(id).copy(commander = true)
                listOf(id)
            } else describedCreatures(r.groupValues[2].ifEmpty { "a " }, what, r.groupValues[6], who, ctx,
                r.groupValues[5].let { k -> if (k.isEmpty()) "" else k.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample") })
            if (ids.isEmpty()) return@let
            // "their planeswalker": one nobody named, which the defender has to have for the attack to mean anything.
            val defTail = r.groupValues[1]
            val defender = if ((defTail == "it" || defTail == "that") && lastPw != null) listOf(lastPw)
            else if (defTail == "it" || defTail == "that") return@let
            else if (Regex("""\bplaneswalker$""").containsMatchIn(defTail)) {
                val foe = possessiveOwner(defTail.substringBefore("planeswalker"), ctx, m) ?: ctx.other(who) ?: "opp"
                // A planeswalker nobody named has no loyalty to count the damage against, so the attack is read as
                // being at its controller and the question is asked rather than a stand-in being invented.
                listOfNotNull(ctx.objects.values.lastOrNull { it.controller == foe && it.zone == "battlefield" && (it.card.name == "a planeswalker" || names.lookup(Names.normalize(it.card.name ?: ""))?.typeLine?.contains("Planeswalker", true) == true) }?.id
                    ?: run { ctx.notes += "No planeswalker was named, so the attack is read as being at ${if (foe == "me") "you" else (ctx.players[foe] ?: "your opponent")}; name the planeswalker and its loyalty for the answer you want."; foe })
            } else targetsIn("at " + defTail, m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") }
            for (id in ids) ctx.events += EventSpec("attack", player = who, obj = id, targets = defender)
            ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = ids.last(); return true
        }
        // "I attack with a 1/1 with double strike and a +1/+1 counter": the attack rules read keywords but not
        // counters, so the counter was dropped and the creature attacked at its printed size. The creature is
        // put on the battlefield first, counter and all, and then attacks.
        Regex("""^(?:attacks?|attacking|swings?|swinging) with ((?:an? |one |two |three |\d+ )?\d+/\d+[a-z ,&]*?) (?:and|with) ((?:an? |one |two |three |\d+ )?[+-]\d+/[+-]\d+ counters?(?: on it)?)$""").find(c)?.let { r ->
            val says = (actor ?: subject)?.let { if (it == "me") "i " else if (it == "opp") "they " else "@$it " } ?: "i "
            if (!readClause(says + "have " + r.groupValues[1].trim(), m, ctx)) return@let
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            Regex("""^(an?|one|two|three|four|five|\d+)? ?([+-]\d+/[+-]\d+) counters?""").find(r.groupValues[2].trim())?.let { cm ->
                ctx.events += EventSpec("counters", obj = id, amount = number(cm.groupValues[1].ifEmpty { "a" }) ?: 1, to = cm.groupValues[2])
            }
            return readClause(says + "attacks with it", m, ctx)
        }
        Regex("""^(?:attacks?|attacking|swings?|swinging)(?: with)?\s+(\d+|two|three|four|five) (elves|elf|goblins|zombies|soldiers|humans|spirits|angels|dragons|beasts|elementals|saprolings|thopters|knights|warriors|wizards|vampires|merfolk|cats|dogs|birds|insects|squirrels|servos|tokens)(?: tokens?)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val n = number(r.groupValues[1]) ?: 1
            val sub = r.groupValues[2].removeSuffix("s").let { if (it == "elve") "elf" else it }
            val name = if (sub == "token") "1/1 creature token" else "1/1 $sub token"
            repeat(n) { var id = slug(name); var k = 2; while (ctx.objects.containsKey(id)) id = slug(name) + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = who, token = true); ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(ctx.other(who) ?: "opp")) }
            ctx.notes += "$n unnamed ${r.groupValues[2]} assumed to be 1/1 tokens; name them for a precise answer."
            ctx.lastActor = who; ctx.lastVerb = "attack"; return true
        }
        Regex("""^(?:attacks?|attacking|swings?|swinging)(?: (@\w+|me|them|him|her|my opponent|the opponent|opponent|(?:$possPrefix)?c\d+|it|that))?(?: with)?\s+(an? |the |my |their |\d+ |two |three |four |five )?(?:(\d+/\d+)s?\s*)?(?:(red|green|white|blue|black|colorless|flying) )?(?:($kwNouns)\b ?)?($creatureKinds)?( tokens?)?(?: with ([a-z ,&]+?))?(?: plus .*)?((?: but .*| and .*)?)$""").find(c)?.let { r0 ->
            val kwNoun = r0.groupValues[5].let { if (it.isEmpty()) "" else it.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample").replace("deathtoucher", "deathtouch").replace("lifelinker", "lifelink").replace("striker", "strike") }
            val r = object { val groupValues = listOf(r0.groupValues[0], r0.groupValues[1], r0.groupValues[2], r0.groupValues[3], (r0.groupValues[4] + " " + r0.groupValues[6].ifEmpty { if (r0.groupValues[4].isEmpty() && kwNoun.isEmpty()) "" else "creature" }).trim(), r0.groupValues[7], listOf(r0.groupValues[8], kwNoun).filter { it.isNotEmpty() }.joinToString(", ")) }
            val who = actor ?: subject ?: "opp"
            // "Alice attacks Bob": a defender was named but no creature. Whatever that player has attacks, and if
            // nothing was described one is read as being there — the clause went unread otherwise.
            if (r.groupValues[3].isEmpty() && r.groupValues[4].isEmpty()) {
                if (r.groupValues[1].isEmpty()) return@let
                val mine = ctx.objects.values.filter { it.controller == who && it.zone == "battlefield" && isCreatureName(it.card.name) }
                if (mine.isEmpty()) return@let
                val def0 = r.groupValues[1].let { d -> when { d.startsWith("@") -> d.removePrefix("@").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) }; d == "me" -> "me"; else -> pronounPlayer(ctx, d.substringAfterLast(' ')) } }
                for (o in mine) ctx.events += EventSpec("attack", player = who, obj = o.id, targets = listOf(def0))
                ctx.notes += "Nothing was said about what ${ctx.players[who] ?: who} attacks with, so ${if (mine.size == 1) "the creature they have" else "every creature they have"} attacks."
                ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.note(def0); return true
            }
            // "they attack my Jace Beleren with a 3/3": the thing attacked is a planeswalker or battle, not a player.
            // "they attack it with a 3/3" right after one was described means that planeswalker.
            fun planeswalkerOf(): String? = ctx.objects.values.lastOrNull { o ->
                o.controller != who && o.card.name?.let { n -> names.lookup(Names.normalize(n))?.typeLine?.contains("Planeswalker") == true } == true
            }?.id
            val defender: String = r.groupValues[1].takeIf { it.isNotEmpty() }?.let { d ->
                when {
                    d == "it" || d == "that" -> planeswalkerOf()
                    Regex("""c\d+""").containsMatchIn(d) -> Regex("""c\d+""").find(d)!!.value.let { ph -> m.cards[ph]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, if (d.startsWith("my")) "me" else ctx.other(who) ?: "me", false, ctx) } }
                    d.startsWith("@") -> d.removePrefix("@").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) }
                    d == "me" -> "me"
                    else -> pronounPlayer(ctx, d.substringAfterLast(' '))
                }
            } ?: ctx.other(who) ?: "me"
            val ids = (if (r.groupValues[5].isNotEmpty()) describedTokens(r.groupValues[2], r.groupValues[3], r.groupValues[4], who, ctx, r.groupValues[6]) else describedCreatures(r.groupValues[2], r.groupValues[3], r.groupValues[4], who, ctx, r.groupValues[6])) +
                Regex(""" plus (an? |\d+ |two |three |four |five )?(\d+/\d+)s?(?: ($kwNouns))?(?: ($creatureKinds))?""").findAll(c).flatMap { x -> describedCreatures(x.groupValues[1], x.groupValues[2], x.groupValues[4], who, ctx, x.groupValues[3].let { k -> if (k.isEmpty()) "" else k.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample") }) }.toList()
            val payFor = Regex("""\bcan (?:only )?(?:pay|afford)(?: the tax)?(?: for)? (\d+|one|two|three|four|five)\b""").find(c)?.let { number(it.groupValues[1]) }
            val attackers = if (payFor != null && payFor < ids.size) ids.take(payFor).also { ctx.notes += "Only $payFor of the ${ids.size} attack taxes can be paid, so only $payFor creatures attack (an attack whose cost can't be paid is illegal, 508.1c)."; ctx.events += EventSpec("pay", player = who, to = "yes") } else ids
            attackers.forEach { ctx.events += EventSpec("attack", player = who, obj = it, targets = listOf(defender)) }
            Regex("""\bonly ha(?:s|ve) (\d+) mana\b|\bha(?:s|ve) only (\d+) mana\b|\bwith (\d+) mana\b""").find(c)?.let { mm -> ctx.notes += "${if (who == "me") "You have" else "They have"} ${mm.groupValues.drop(1).first { it.isNotEmpty() }} mana available; the engine doesn't track mana, so whether a tax can be paid for every attacker is stated as an assumption." }
            ctx.lastActor = who; ctx.lastVerb = "attack"
            val tail = r0.groupValues[9].trim().removePrefix("but ").removePrefix("and ").trim()
            if (tail.isNotEmpty() && payFor == null && !Regex("""\bmana\b|\bpay\b|\bafford\b""").containsMatchIn(tail) && !isNoise(tail)) readClause(tail, m, ctx)
            return true
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
        // "Grizzly Bears fights Hill Giant": the fight itself, with no card named as what caused it. Each creature
        // goes on the battlefield, on opposite sides of the table unless the asker said whose they are (701.14a).
        Regex("""^($possPrefix|an? )?(c\d+|it|that) (?:fights?|fought) ($possPrefix|an? )?(c\d+)$""").find(c)?.let { r ->
            fun side(word: String, fallback: String) = when (val w = word.trim()) {
                "my", "own" -> "me"
                "their", "his", "her" -> pronounPlayer(ctx, w)
                "my opponent's", "the opponent's", "an opponent's", "opponent's" -> pronounPlayer(ctx)
                "", "the", "a", "an" -> fallback
                else -> w.removePrefix("@").removeSuffix("'s")
            }
            val whoA = side(r.groupValues[1], "me")
            val aId = if (r.groupValues[2] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                      else m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, whoA, false, ctx) } ?: return@let
            val whoB = side(r.groupValues[3], ctx.other(ctx.objects[aId]?.controller ?: whoA) ?: "opp")
            val bId = m.cards[r.groupValues[4]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, whoB, false, ctx) } ?: return@let
            if (aId == bId) return@let
            ctx.events += EventSpec("fight", obj = aId, targets = listOf(bId))
            ctx.lastVerb = "fight"; ctx.lastMentioned = bId; return true
        }
        // "their Grizzly Bears is blocked by my Hill Giant": the same declaration, said from the attacker's side.
        // "My Grizzly Bears is blocked by two 1/1s" says the attack too: being blocked is only possible in combat,
        // so the creature is put on the battlefield and made to attack rather than the whole clause going unread.
        Regex("""^($possPrefix)?(c\d+|it|that|\d+/\d+) (?:is|are|was|were|gets?|got) (?:chump[- ]?)?blocked by (.+)$""").find(c)?.let { r ->
            val atkWho = when (val w = r.groupValues[1].trim()) {
                "my", "own" -> "me"
                "their", "his", "her" -> pronounPlayer(ctx, w)
                "my opponent's", "the opponent's", "an opponent's", "opponent's" -> pronounPlayer(ctx)
                "" , "the" -> null
                else -> w.removePrefix("@").removeSuffix("'s")
            }
            val attacker = when {
                r.groupValues[2] in setOf("it", "that") -> ctx.events.lastOrNull { it.verb == "attack" }?.obj ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                cardRef.matches(r.groupValues[2]) -> m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, atkWho ?: ctx.lastOwner, false, ctx) } ?: return@let
                else -> describedFrom(if (atkWho == null) "the " else "my ", r.groupValues[2], "", atkWho ?: ctx.lastOwner, ctx).firstOrNull() ?: return@let
            }
            val who = ctx.other(ctx.objects[attacker]?.controller ?: "opp") ?: "me"
            // "blocked by a 2/2 and a 3/3" — the sentence reader joins them with "plus", so the block is multiple.
            val blockers = r.groupValues[3].split(" plus ", " & ").mapNotNull { part0 ->
                val part = part0.trim().removePrefix("a ").removePrefix("an ").removePrefix("the ")
                    .removePrefix("my ").removePrefix("their ").removePrefix("his ").removePrefix("her ").trim()
                val cnt = Regex("""^(one|two|three|four|five|\d+) """).find(part)?.groupValues?.get(1)
                val rest = if (cnt != null) part.substring(part.indexOf(' ') + 1) else part
                if (cardRef.matches(rest)) m.cards[rest]?.let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }?.let { listOf(it) }
                else Regex("""\d+/\d+""").find(rest)?.value?.let { pt -> describedFrom("${cnt ?: "a"} ", pt, rest, who, ctx) }
            }.flatten()
            if (blockers.isEmpty()) return@let
            ensureAttacker(ctx, who)
            for (b in blockers) ctx.events += EventSpec("block", player = who, obj = b, targets = listOf(attacker))
            ctx.lastActor = who; ctx.lastVerb = "block"; ctx.lastMentioned = attacker; return true
        }
        // "I untap my lands": the untap step in other words, and it went unread.
        Regex("""^untaps?(?: (?:my|their|his|her|all(?: my| their)?))? (?:lands?|permanents?|creatures?|everything|board|team)$|^untap(?: step)?$""").find(c)?.let {
            val who = actor ?: ctx.lastActor ?: "me"
            ctx.activePlayer = who; ctx.events += EventSpec("step", player = who, to = "untap"); ctx.note(who); return true
        }
        // "I proliferate": one more of each kind of counter already on the table (701.34a).
        Regex("""^proliferates?(?: once| again)?$""").find(c)?.let {
            val who = actor ?: ctx.lastActor ?: "me"
            ctx.events += EventSpec("proliferate", player = who); ctx.note(who); return true
        }
        // "I discard my hand": every card in it, which needs the hand size to have been said.
        Regex("""^discards? (?:my|their|his|her) (?:whole |entire )?hand$""").find(c)?.let {
            val who = actor ?: ctx.lastActor ?: "me"
            val n = ctx.handSize[who]
            if (n == null) { ctx.notes += "How many cards ${if (who == "me") "you have" else "they have"} in hand wasn't said, so discarding the hand isn't counted; say the hand size for a precise answer."; ctx.note(who); return true }
            ctx.events += EventSpec("discardCount", player = who, amount = n); ctx.note(who); return true
        }
        // "my Grizzly Bears becomes a 4/4 until end of turn": a size given outright rather than as a bonus.
        Regex("""^(?:($possPrefix)?(c\d+|it|that) )?becomes? (?:an? )?(\d+)/(\d+)(?: creature)?(?: until end of turn| this turn)?$""").find(c)?.let { r ->
            val id = if (r.groupValues[2].isEmpty() || r.groupValues[2] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, possessiveOwner(r.groupValues[1], ctx, m) ?: actor ?: ctx.lastOwner ?: "me", false, ctx) } ?: return@let
            ctx.events += EventSpec("setPt", obj = id, to = "${r.groupValues[3]}/${r.groupValues[4]}")
            ctx.lastMentioned = id; return true
        }
        // "I crew my Smuggler's Copter with a 2/2": crewing is the Vehicle's own activated ability, paid by tapping
        // creatures; said this way round neither the Vehicle nor the crew was read.
        Regex("""^crews?(?: up)? ($possPrefix|an? )?(c\d+|it|that)(?: with (?:$possPrefix|an? )?(c\d+|\d+/\d+|creature|creatures))?$""").find(c)?.let { r ->
            val who = actor ?: ctx.lastActor ?: "me"
            val vid = if (r.groupValues[2] in setOf("it", "that")) ctx.objects.values.lastOrNull { o -> o.controller == who && o.zone == "battlefield" && names.lookup(Names.normalize(o.card.name ?: ""))?.typeLine?.contains("Vehicle", true) == true }?.id
                          ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                      else m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) } ?: return@let
            // The crew is a cost, not something the answer tracks; a creature said to pay it is put out if it isn't.
            r.groupValues[3].takeIf { it.isNotEmpty() }?.let { ph ->
                if (cardRef.matches(ph)) m.cards[ph]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) }
                else if (Regex("""^\d+/\d+$""").matches(ph)) describedCreatures("a ", ph, "creature", who, ctx).firstOrNull()
                else null
            }
            ctx.events += EventSpec("activate", player = who, obj = vid)
            ctx.lastActor = who; ctx.lastMentioned = vid; return true
        }
        // "my opponent's Jace Beleren has 4 loyalty": a loyalty total said as a statement of its own.
        Regex("""^($possPrefix)?(c\d+|it|that) (?:is at|has|have|starts at|sits at|is on) (\d+) loyalty(?: counters?)?$""").find(c)?.let { r ->
            val id = if (r.groupValues[2] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, possessiveOwner(r.groupValues[1], ctx, m) ?: actor ?: ctx.lastOwner ?: "me", false, ctx) } ?: return@let
            val spec = ctx.objects.getValue(id)
            ctx.objects[id] = spec.copy(counters = spec.counters + ("loyalty" to (r.groupValues[3].toInt())))
            ctx.lastMentioned = id; return true
        }
        // "my opponent scoops" / "they concede": that player loses the game (104.3a).
        Regex("""^(?:scoops?(?: up)?|concedes?|conceded|quits?|gives? up|packs? it in)$""").find(c)?.let {
            val who = actor ?: ctx.lastActor ?: "opp"
            ctx.events += EventSpec("concede", player = who); ctx.note(who); ctx.lastActor = who; return true
        }
        // "I have an empty board" / "they have nothing on board": nothing to add, but it is a statement, not noise.
        Regex("""^(?:has|have|got|'ve got|control|controls) (?:an? )?(?:empty (?:board|battlefield)|nothing(?: on (?:the )?(?:board|battlefield))?|no (?:permanents|creatures|blockers))$""").find(c)?.let {
            val who = actor ?: ctx.lastActor ?: "me"; ctx.note(who); ctx.lastOwner = who; ctx.lastActor = who; return true
        }
        // "I mill my opponent for 5" / "they mill me 3": cards from the top of a library into a graveyard.
        Regex("""^mills?(?: (me|myself|them|him|her|my opponent|the opponent|@\w+))?(?: for)? (\d+|one|two|three|four|five|six|seven|eight|nine|ten)(?: cards?)?$""").find(c)?.let { r ->
            val target = when (val w = r.groupValues[1]) {
                "", "them", "him", "her", "my opponent", "the opponent" -> if (w.isEmpty()) ctx.other(actor ?: ctx.lastActor ?: "me") ?: "opp" else pronounPlayer(ctx, "their")
                "me", "myself" -> "me"
                else -> w.removePrefix("@")
            }
            val n = number(r.groupValues[2]) ?: return@let
            ctx.events += EventSpec("mill", player = target, amount = n); ctx.note(target); return true
        }
        // "my opponent taps out": they spent their mana, so there is none left for a tax or a response.
        Regex("""^taps? out(?: for (?:it|that|everything))?$|^(?:is|are) tapped out$""").find(c)?.let {
            val who = actor ?: ctx.lastActor ?: "opp"
            ctx.mana[who] = 0; ctx.note(who)
            ctx.notes += "\"${restore(clause0, m)}\" is read as ${if (who == "me") "you having" else (ctx.players[who] ?: "your opponent") + " having"} no mana available."
            return true
        }
        // "my opponent flashes in a blocker": a creature nobody named arrives and blocks what is attacking.
        Regex("""^(?:flash(?:es)?|flashes in|flash in|drops?|plays?) (?:in )?(?:an? |the )?(blocker|chump blocker|creature|surprise blocker)$""").find(c)?.let {
            val who = actor ?: ctx.lastActor ?: "opp"
            val id = describedCreatures("a ", "", "creature", who, ctx).firstOrNull() ?: return@let
            ctx.events += EventSpec("enter", obj = id)
            ctx.notes += "Nothing was said about what ${if (who == "me") "you flash" else (ctx.players[who] ?: "your opponent") + " flashes"} in, so it is read as an unnamed creature that then blocks; name it for a precise answer."
            val atk = ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" } ?: ensureAttacker(ctx, who)
            if (atk?.obj != null) ctx.events += EventSpec("block", player = who, obj = id, targets = listOf(atk.obj!!))
            ctx.lastActor = who; ctx.lastVerb = "block"; ctx.lastMentioned = id; return true
        }
        // Bare "block" / "blocks (it)" / "block with it": the creature just mentioned blocks the last attacker.
        // Said the other way round ("it is blocked", "it gets chump blocked") it's the same statement, and the
        // blocker is whatever the defending player has — or, if nobody said, a creature nobody named.
        // "They block my 5/5": the attacker is named but the blocker isn't, which is still a block.
        Regex("""^(?:chump[- ]?)?blocks?(?: it| that| the attacker| with it| one (?:guy|dude|of them|of the attackers|attacker)| a guy| a dude| ($possPrefix|an? )?(c\d+|\d+/\d+))?$|^(?:(?:it|that|they|the attacker|my attacker|their attacker|the creature) )?(?:is|are|was|were|gets?|got) (?:chump[- ]?)?blocked$""").find(c)?.let { r ->
            // A block with no attack described says there was one: "I control a 3/3 and my opponent blocks with a 1/1".
            val named = r.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { ph ->
                val owner = possessiveOwner(r.groupValues[1], ctx, m) ?: ctx.other(actor ?: ctx.lastActor ?: "me") ?: "opp"
                val aid = if (cardRef.matches(ph)) m.cards[ph]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx) }
                          else describedCreatures("the ", ph, "creature", owner, ctx).firstOrNull() ?: describedCreatures("a ", ph, "creature", owner, ctx).firstOrNull()
                aid?.let { a ->
                    ctx.events.lastOrNull { it.verb == "attack" && it.obj == a } ?: run {
                        val atkWho = ctx.objects[a]?.controller ?: owner
                        EventSpec("attack", player = atkWho, obj = a, targets = listOf(ctx.other(atkWho) ?: "me")).also { ctx.events += it }
                    }
                }
            }
            val attackerEvent = named ?: ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }
                ?: ensureAttacker(ctx, actor ?: ctx.lastActor ?: "me") ?: return false
            val defender = actor ?: ctx.other(attackerEvent.player) ?: "me"
            // A creature just cast has no object yet; the judge gives it the id the engine will use (its slug). Otherwise the defender's last creature.
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).controller == defender && isCreatureName(ctx.objects.getValue(it).card.name) } ?: ctx.events.lastOrNull { it.verb == "cast" && it.player == defender }?.card?.name?.let { n -> ctx.objects.values.firstOrNull { it.card.name == n }?.id ?: slug(n) }
                // "they block" when nobody said what they have: a blocker nobody named, rather than dropping the clause.
                ?: ctx.objects.values.lastOrNull { it.controller == defender && it.id != attackerEvent.obj && isCreatureName(it.card.name) }?.id
                ?: describedCreatures("a ", "", "creature", defender, ctx, "").firstOrNull()?.also {
                    ctx.notes += "Nothing was said about what ${if (defender == "me") "you" else (ctx.players[defender] ?: "your opponent")} ${if (defender == "me") "block" else "blocks"} with, so the blocker is read as an unnamed creature; name it for a precise answer."
                } ?: return false
            val who = actor ?: ctx.objects[id]?.controller ?: ctx.events.lastOrNull { it.verb == "cast" }?.player ?: ctx.other(attackerEvent.player) ?: "me"
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOfNotNull(attackerEvent.obj)); ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        // "I block the 3/3 with a 4/4": the attacker is named by its size, so it is that attack that is blocked
        // and not whichever one was declared last.
        Regex("""^(?:chump[- ]?)?(?:blocks?|blocking)\s+(?:the |a |an |their |his |her |my opponent's |the opponent's )?(\d+/\d+)(?: ($kwNouns))?(?: ($creatureKinds))?\s+with\s+(an? |the |my |their )?(\d+/\d+)(?: ($kwNouns))?(?: ($creatureKinds))?$""").find(c)?.let { r ->
            val atkPt = r.groupValues[1]
            val atk = ctx.events.lastOrNull { e -> e.verb == "attack" && e.obj?.let { o -> (ctx.objects[o]?.card?.name ?: "").startsWith("a $atkPt") } == true } ?: return@let
            val who = actor ?: ctx.other(atk.player) ?: "me"
            val kw = r.groupValues[6].let { k -> if (k.isEmpty()) "" else k.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample").replace("deathtoucher", "deathtouch").replace("lifelinker", "lifelink") }
            val ids = describedCreatures(r.groupValues[4].ifEmpty { "a " }, r.groupValues[5], r.groupValues[7], who, ctx, kw)
            if (ids.isEmpty()) return@let
            ids.forEach { ctx.events += EventSpec("block", player = who, obj = it, targets = listOfNotNull(atk.obj)) }
            ctx.lastActor = who; ctx.lastVerb = "block"; ctx.lastMentioned = ids.last(); return true
        }
        // "I block their 3/3 with two 2/2s": the attacker is named inside the block and nobody said it attacked.
        // The attack is declared first, then the rest of the clause is read as an ordinary "blocks with …".
        Regex("""^(?:chump[- ]?)?blocks? ($possPrefix|an? )(?:($kwNouns) )?(c\d+|\d+/\d+|creature|guy|dude)(?: ($kwNouns))?(?: creature)? with (.+)$""").find(c)?.let { r0 ->
            // "I block their infect creature with my 2/2": the attacker may be described by a keyword instead of
            // a size, with the keyword said before the noun as readily as after it.
            val r = object { val groupValues = listOf(r0.groupValues[0], r0.groupValues[1], r0.groupValues[3],
                listOf(r0.groupValues[2], r0.groupValues[4]).firstOrNull { it.isNotEmpty() } ?: "", r0.groupValues[5]) }
            if (ctx.events.any { it.verb == "attack" || it.verb == "attackAll" }) return@let
            val defender = actor ?: subject ?: "me"
            val owner = when (r.groupValues[1].trim()) { "my" -> defender; "" , "a", "an", "the" -> ctx.other(defender) ?: "opp"; else -> ctx.other(defender) ?: "opp" }
            val atkKw = r.groupValues[3].let { k -> if (k.isEmpty()) "" else k.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample").replace("deathtoucher", "deathtouch").replace("lifelinker", "lifelink").replace("striker", "strike") }
            val id = if (cardRef.matches(r.groupValues[2])) m.cards[r.groupValues[2]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, owner, false, ctx) } ?: return@let
                     else describedCreatures("a ", r.groupValues[2].takeIf { Regex("""^\d+/\d+$""").matches(it) } ?: "", "creature", owner, ctx, atkKw).firstOrNull() ?: return@let
            ctx.events += EventSpec("attack", player = owner, obj = id, targets = listOf(defender)); ctx.note(defender)
            ctx.notes += "No attack was described, so ${ctx.objects[id]?.card?.name ?: "the creature"} is read as attacking ${if (defender == "me") "you" else ctx.players[defender] ?: "your opponent"}; the block answers it."
            return readClause((if (defender == "me") "i " else if (defender == "opp") "they " else "@$defender ") + "blocks with " + r.groupValues[4], m, ctx)
        }
        // "blocks with two 2/2s", "chump blocks with a 1/1 goblin": described creatures block the last attacker (all of them the same one).
        Regex("""^(?:(?:chump[- ]?)?(?:blocks?|blocking)|chumps?)(?: it| that| the attacker)?(?: with)?\s+(an? |the |my |their |\d+ |two |three |four |five )?(?:(\d+/\d+)s?\s*)?(?:(red|green|white|blue|black|colorless|flying) )?(?:($kwNouns)\b ?)?($creatureKinds)?( tokens?)?(?: with ([a-z ,&]+?))?(?: plus .*)?$""").find(c)?.let { r0 ->
            val kwNoun = r0.groupValues[4].let { if (it.isEmpty()) "" else it.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample").replace("deathtoucher", "deathtouch").replace("lifelinker", "lifelink").replace("striker", "strike") }
            val r = object { val groupValues = listOf(r0.groupValues[0], r0.groupValues[1], r0.groupValues[2], (r0.groupValues[3] + " " + r0.groupValues[5].ifEmpty { if (r0.groupValues[3].isEmpty() && kwNoun.isEmpty()) "" else "creature" }).trim(), r0.groupValues[6], listOf(r0.groupValues[7], kwNoun).filter { it.isNotEmpty() }.joinToString(", ")) }
            if (r.groupValues[2].isEmpty() && r.groupValues[3].isEmpty()) return@let
            val attackerEvent = ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }
                ?: ensureAttacker(ctx, actor ?: subject ?: "opp") ?: return@let
            val who = actor ?: ctx.other(attackerEvent.player) ?: subject ?: "opp"
            val ids = (if (r.groupValues[4].isNotEmpty()) describedTokens(r.groupValues[1], r.groupValues[2], r.groupValues[3], who, ctx, r.groupValues[5]) else describedCreatures(r.groupValues[1], r.groupValues[2], r.groupValues[3], who, ctx, r.groupValues[5])) +
                Regex(""" plus (an? |\d+ |two |three |four |five )?(\d+/\d+)s?(?: ($kwNouns))?(?: ($creatureKinds))?""").findAll(c).flatMap { x -> describedCreatures(x.groupValues[1], x.groupValues[2], x.groupValues[4], who, ctx, x.groupValues[3].let { k -> if (k.isEmpty()) "" else k.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample") }) }.toList()
            ids.forEach { ctx.events += EventSpec("block", player = who, obj = it, targets = listOfNotNull(attackerEvent.obj)) }
            ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        // "My Stoneforge Mystic dies with a Batterskull attached": the attachment, said as a trailer on what
        // happens to the creature. Unread, the Equipment was never on the battlefield at all.
        Regex("""^(c\d+|it|that)(?: creature)? (dies|died|is destroyed|gets destroyed|is exiled|is sacrificed|leaves the battlefield|is bounced) with (?:an? |the |my |their )?(c\d+) (?:attached|equipped|on it)$""").find(c)?.let { r ->
            val who = actor ?: ctx.lastOwner ?: "me"
            val host = if (r.groupValues[1] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                       else m.cards[r.groupValues[1]]?.let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) } ?: return@let
            val gear = m.cards[r.groupValues[3]]?.let { objectIdFor(it, ctx) ?: addObject(it, ctx.objects[host]?.controller ?: who, false, ctx) } ?: return@let
            ctx.objects[gear] = ctx.objects.getValue(gear).copy(attachedTo = host)
            ctx.lastMentioned = host
            return readClause("it ${r.groupValues[2]}", m, ctx)
        }
        // "My Liliana is attacked by a 2/2", "my Bears got attacked by a 3/3": the passive form. A planeswalker
        // is attacked; a creature can't be (506.3), so for a creature it is read as that creature blocking.
        Regex("""^(?:(c\d+|\d+/\d+|planeswalker|walker|creature|guy|dude|token)(?: creature)? )?(?:is|are|was|were|gets?|got) attacked by (?:an? |the |my |their |his |her )?(c\d+|\d+/\d+|creature|guy|dude|token)(?: creature)?$""").find(c)?.let { r ->
            val mine = actor ?: ctx.lastOwner ?: "me"
            val them = ctx.other(mine) ?: "opp"
            val subjPh = r.groupValues[1]
            val subj = when {
                subjPh.isEmpty() -> ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                cardRef.matches(subjPh) -> m.cards[subjPh]?.let { objectIdFor(it, ctx) ?: addObject(it, mine, false, ctx) } ?: return@let
                Regex("""^\d+/\d+$""").matches(subjPh) -> ctx.objects.values.lastOrNull { it.controller == mine && (it.card.name ?: "").startsWith("a $subjPh") }?.id
                    ?: describedCreatures("a ", subjPh, "creature", mine, ctx).firstOrNull() ?: return@let
                subjPh in setOf("creature", "guy", "dude", "token") -> ctx.objects.values.lastOrNull { it.controller == mine && isCreatureName(it.card.name) }?.id
                    ?: describedCreatures("a ", "", "creature", mine, ctx).firstOrNull() ?: return@let
                else -> return@let
            }
            val atk = if (cardRef.matches(r.groupValues[2])) m.cards[r.groupValues[2]]?.let { objectIdFor(it, ctx) ?: addObject(it, them, false, ctx) } ?: return@let
                      else describedCreatures("a ", r.groupValues[2].takeIf { Regex("""^\d+/\d+$""").matches(it) } ?: "", "creature", them, ctx).firstOrNull() ?: return@let
            val walker = (ctx.objects[subj]?.card?.name ?: "").let { n -> m.cards.values.firstOrNull { it.display == n }?.typeLine?.contains("Planeswalker") == true } || subjPh in setOf("planeswalker", "walker")
            if (walker) { ctx.events += EventSpec("attack", player = them, obj = atk, targets = listOf(subj)) }
            else {
                ctx.events += EventSpec("attack", player = them, obj = atk, targets = listOf(mine))
                ctx.events += EventSpec("block", player = mine, obj = subj, targets = listOf(atk))
                ctx.notes += "Only a player, a planeswalker or a battle can be attacked (506.3), so \"attacked by\" is read as ${ctx.objects[subj]?.card?.name ?: "it"} blocking."
            }
            ctx.lastActor = them; ctx.lastVerb = if (walker) "attack" else "block"; ctx.lastMentioned = subj; return true
        }
        // "attacks me (with it)" after a Threaten: the last-mentioned creature attacks; the engine knows who controls it now.
        Regex("""^(?:attacks?|swings? at|swings? (?:it )?(?:at|into)|comes? at) (me|us|them|my opponent|the opponent|opponent|@\w+)(?: with (?:it|that|that creature))?(?: again| once more| a second time| this turn| now)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "opp"
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.lastOrNull { it.verb == "cast" || it.verb == "activate" }?.targets?.firstOrNull { it in ctx.objects } ?: return@let
            val defender = when (r.groupValues[1]) { "me", "us" -> "me"; "them", "my opponent", "the opponent", "opponent" -> ctx.other(who) ?: "opp"; else -> r.groupValues[1].removePrefix("@") }
            if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
            ctx.events += EventSpec("attack", player = who, obj = id, targets = listOf(defender)); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.note(defender); return true
        }
        // "attack with both" / "swing with everything" / "attack with my team": every creature attacks.
        Regex("""^(?:attacks?|swings?|swinging|attacking)(?: with)? (?:both|all|everything|everyone|my team|the team|all my creatures|both of them|all of them|the whole team)(?: of them)?(?: (?:at|into) (.*))?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
            ctx.events += EventSpec("attackAll", player = who, targets = targetsIn("at " + (r.groupValues[1].ifEmpty { "them" }), m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") }); ctx.lastActor = who; ctx.lastVerb = "attack"; return true
        }
        // "recast it this turn" / "cast it again" / "replay the Bears": the card, back in hand, is cast as the object it already is.
        Regex("""^(?:re-?casts?|re-?plays?|casts? (?:it|that|(?:the |my )?c\d+) again|(?:casts?|plays?) (?:it|that|(?:the |my )?c\d+) (?:back|once more))(?: (?:it|that|(?:the |my )?(c\d+)))?(?: this turn| again| now| right away| the same turn)?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val ph = r.groupValues[1].ifEmpty { Regex("""(c\d+)""").find(r.groupValues[0])?.groupValues?.get(1) ?: "" }
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) } ?: return@let else ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.asReversed().firstNotNullOfOrNull { e -> if (e.verb == "cast" || e.verb == "activate") e.targets.firstOrNull { it in ctx.objects } else null } ?: return@let
            if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
            ctx.events += EventSpec("cast", player = who, obj = id); ctx.lastActor = who; ctx.lastVerb = "cast"; return true
        }
        // "exiling a blue card" / "pitching a blue card": Force of Will's alternative cost, already part of the cast.
        if (Regex("""^(?:by )?(?:exiling|pitching|removing) (?:an? )?(?:blue|red|green|white|black|colou?red)? ?card(?: from (?:my|their|his|her) hand)?(?: and paying 1 life| and losing 1 life)?$""").matches(c)) { ctx.notes += "The alternative cost (exiling a card from hand and paying 1 life) is paid as the spell is cast (118.9)."; return true }
        // "flashes in a 0/4 wall and blocks" / "flash in a 2/2 with flash": a described creature cast now (the engine says whether it can be, given flash), then it blocks.
        Regex("""^(?:flash(?:es)? in|casts?|plays?) (an? |\d+ |two |three )?(\d+/\d+)?(?: ?($kwNouns))? ?($creatureKinds|walls?)?(?: with ([a-z ,&]+?))?( (?:and|then) (?:blocks?|chumps?)(?: (?:it|the attacker|with it))?)?$""").find(c)?.let { r ->
            if (r.groupValues[2].isEmpty() && r.groupValues[4].isEmpty()) return@let
            if (r.groupValues[2].isEmpty() && r.groupValues[5].isEmpty() && r.groupValues[3].isEmpty()) return@let
            val who = actor ?: subject ?: "opp"
            val kw = listOf(r.groupValues[3].let { if (it.isEmpty()) "" else it.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample") }, r.groupValues[5]).filter { it.isNotEmpty() }.joinToString(", ")
            val ids = describedCreatures(r.groupValues[1], r.groupValues[2], r.groupValues[4], who, ctx, if (c.startsWith("flash") && !kw.contains("flash")) (if (kw.isEmpty()) "flash" else "$kw, flash") else kw)
            for (id in ids) { ctx.objects[id] = ctx.objects.getValue(id).copy(zone = "hand"); ctx.events += EventSpec("cast", player = who, obj = id) }
            if (r.groupValues[6].isNotEmpty()) {
                val attacker = ctx.events.lastOrNull { it.verb == "attack" && who in it.targets }?.obj ?: ctx.events.lastOrNull { it.verb == "attack" }?.obj
                ctx.events += EventSpec("resolveAll"); ids.forEach { ctx.events += EventSpec("block", player = who, obj = it, targets = listOfNotNull(attacker)) }; ctx.lastVerb = "block"
            } else ctx.lastVerb = "cast"
            ctx.lastActor = who; return true
        }
        // "if I block one" / "I block one of them" with nothing named: one creature that player already has
        // blocks the first attacker. Without this the whole clause went unread and every attacker got through.
        Regex("""^(?:if )?(?:i |they |we |he |she )?(?:chump[- ]?)?(?:blocks?|blocking|chumps?)(?: one| one of them| the first| the first one| a single one| just one| only one)$""").find(c)?.let {
            val attackEvent = ctx.events.firstOrNull { it.verb == "attack" } ?: return@let
            val who = actor ?: ctx.other(attackEvent.player ?: "opp") ?: "me"
            val blocker = ctx.objects.values.lastOrNull { o -> o.controller == who && o.zone == "battlefield" && isCreatureName(o.card.name) && ctx.events.none { e -> e.verb == "block" && e.obj == o.id } }?.id
                ?: describedCreatures("a ", "", "creature", who, ctx, "").firstOrNull()?.also {
                    ctx.notes += "Nothing was said about what ${if (who == "me") "you" else "they"} block with, so the blocker is read as an unnamed creature; name it for a precise answer."
                } ?: return@let
            ctx.events += EventSpec("block", player = who, obj = blocker, targets = listOfNotNull(attackEvent.obj))
            ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        // "blocks one with a 1/1": a described creature blocks one of the attackers.
        Regex("""^(?:chump[- ]?)?(?:blocks?|blocking|chumps?) (?:one|one of them|the first|the first one|a single one) with (an? |\d+ |two |three )?(?:(\d+/\d+)s?\s*)?(?:($kwNouns)\b ?)?($creatureKinds)?( tokens?)?$""").find(c)?.let { r ->
            if (r.groupValues[2].isEmpty() && r.groupValues[4].isEmpty()) return@let
            val attacker = ctx.events.firstOrNull { it.verb == "attack" }?.obj ?: ctx.objects.values.firstOrNull { it.controller == ctx.events.firstOrNull { e -> e.verb == "attackAll" }?.player }?.id ?: return@let
            val who = actor ?: ctx.other(ctx.events.first { it.verb == "attack" || it.verb == "attackAll" }.player) ?: "me"
            val kw = r.groupValues[3].let { if (it.isEmpty()) "" else it.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample") }
            val ids = if (r.groupValues[5].isNotEmpty()) describedTokens(r.groupValues[1], r.groupValues[2], r.groupValues[4], who, ctx, kw) else describedCreatures(r.groupValues[1], r.groupValues[2], r.groupValues[4], who, ctx, kw)
            ids.forEach { ctx.events += EventSpec("block", player = who, obj = it, targets = listOf(attacker)) }; ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        // "My opponent blocks my Serra Angel with their Grizzly Bears": the attacker comes first and the blocker
        // after "with", both named. The attacker is read as attacking, since something blocked it.
        Regex("""^(?:chump[- ]?)?blocks?(?:ing)? ($possPrefix|an? )?(c\d+) with ($possPrefix|an? )?(c\d+)$""").find(c)?.let { r ->
            val who = possessiveOwner(r.groupValues[3], ctx, m) ?: actor ?: subject ?: "me"
            val foe = possessiveOwner(r.groupValues[1], ctx, m) ?: ctx.other(who) ?: "opp"
            if (foe == who) return@let
            val atkCard = m.cards.getValue(r.groupValues[2]); val blkCard = m.cards.getValue(r.groupValues[4])
            val attacker = objectIdFor(atkCard, ctx) ?: addObject(atkCard, foe, false, ctx)
            val blocker = objectIdFor(blkCard, ctx) ?: addObject(blkCard, who, false, ctx)
            if (attacker == blocker) return@let
            if (ctx.events.none { it.verb == "attack" && it.obj == attacker }) {
                ctx.events += EventSpec("attack", player = foe, obj = attacker, targets = listOf(who))
                ctx.notes += "${atkCard.display} is read as attacking ${if (who == "me") "you" else ctx.players[who] ?: who}, since something blocked it."
            }
            ctx.events += EventSpec("block", player = who, obj = blocker, targets = listOf(attacker))
            ctx.lastActor = who; ctx.lastVerb = "block"; ctx.lastMentioned = blocker; return true
        }
        // "I block their 5/5 trampler with a 2/2": neither creature is named, both are described. The attacker is
        // read as attacking, since something blocked it.
        Regex("""^(?:chump[- ]?)?blocks?(?:ing)? (?:their |the |an? |my |his |her |my opponent's )?(\d+/\d+)((?: (?!with\b)[a-z]+)*) with (an? |\d+ |two |three )?(\d+/\d+)((?: (?!plus\b)[a-z]+)*)((?: plus .*)?)$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val foe = ctx.other(who) ?: "opp"
            val attacker = ctx.events.lastOrNull { it.verb == "attack" }?.obj
                ?: describedFrom("a ", r.groupValues[1], r.groupValues[2], foe, ctx).firstOrNull() ?: return@let
            // "with a 2/2 and a 1/1" arrives here as "a 2/2 plus a 1/1": every one of them blocks.
            val blockers = describedFrom(r.groupValues[3], r.groupValues[4], r.groupValues[5], who, ctx) +
                Regex(""" plus (an? |\d+ |two |three )?(\d+/\d+)((?: [a-z]+)*)""").findAll(r.groupValues[6])
                    .flatMap { x -> describedFrom(x.groupValues[1], x.groupValues[2], x.groupValues[3], who, ctx) }
            if (blockers.isEmpty() || attacker in blockers) return@let
            if (ctx.events.none { it.verb == "attack" && it.obj == attacker }) {
                ctx.events += EventSpec("attack", player = foe, obj = attacker, targets = listOf(who))
                ctx.notes += "${ctx.objects[attacker]?.card?.name ?: attacker} is read as attacking ${if (who == "me") "you" else ctx.players[who] ?: who}, since something blocked it."
            }
            blockers.forEach { ctx.events += EventSpec("block", player = who, obj = it, targets = listOf(attacker)) }
            ctx.lastActor = who; ctx.lastVerb = "block"; ctx.lastOwner = who; ctx.lastMentioned = blockers.last(); ctx.note(who); ctx.note(foe); return true
        }
        // "Sakura-Tribe Elder blocks a 4/4" / "it blocks their Hill Giant": the blocker leads and the attacker is named after it,
        // which also says the attacker is attacking even when nobody said so.
        Regex("""^(?:my |the |their )?(c\d+|it|that|they) (?:chump[- ]?)?blocks? (?:an? |the |my |their )?(?:(c\d+)|(\d+/\d+)((?: [a-z]+)*))$""").find(c)?.let { r ->
            val blockerRef = r.groupValues[1]
            val blocker = if (blockerRef in setOf("it", "that", "they")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                          else m.cards[blockerRef]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
            val who = ctx.objects[blocker]?.controller ?: "me"
            val foe = ctx.other(who) ?: "opp"
            val attacker = if (r.groupValues[2].isNotEmpty()) m.cards[r.groupValues[2]]?.let { objectIdFor(it, ctx) ?: addObject(it, foe, false, ctx) } ?: return@let
                           else {
                               val trailer = r.groupValues[4].trim()
                               val kws = Regex("""$kwNouns|flying|trample|deathtouch|lifelink|first strike|double strike|menace|vigilance|indestructible|infect|wither""").findAll(trailer)
                                   .map { k -> k.value.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample") }.distinct().joinToString(", ")
                               val kind = Regex("""$creatureKinds""").find(trailer)?.value ?: "creature"
                               describedCreatures("a ", r.groupValues[3], kind, foe, ctx, kws).firstOrNull() ?: return@let
                           }
            if (attacker == blocker) return@let
            if (ctx.events.none { it.verb == "attack" && it.obj == attacker }) {
                ctx.events += EventSpec("attack", player = foe, obj = attacker, targets = listOf(who))
                ctx.notes += "${ctx.objects[attacker]?.card?.name ?: attacker} is read as attacking ${if (who == "me") "you" else who}, since something blocked it."
            }
            ctx.events += EventSpec("block", player = who, obj = blocker, targets = listOf(attacker))
            ctx.lastActor = who; ctx.lastVerb = "block"; ctx.note(who); ctx.note(foe); return true
        }
        // "their Wall of Omens blocks" with nothing after it: that creature blocks the attacker already described.
        // (The leading possessive is taken as the actor before any rule sees the clause, so "c2 blocks" is what
        // arrives here.)
        Regex("""^(?:my |the |their )?(c\d+|it|that) (?:chump[- ]?)?blocks?$""").find(c)?.let { r ->
            val attackerEvent = ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }
                ?: ensureAttacker(ctx, actor ?: ctx.lastActor ?: "me") ?: return@let
            val who = actor ?: ctx.other(attackerEvent.player) ?: "me"
            val id = if (r.groupValues[1] in setOf("it", "that")) ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
                     else m.cards[r.groupValues[1]]?.let { card -> objectIdFor(card, ctx) ?: addObject(card, who, false, ctx) } ?: return@let
            if (id == attackerEvent.obj) return@let
            ctx.events += EventSpec("block", player = ctx.objects[id]?.controller ?: who, obj = id, targets = listOfNotNull(attackerEvent.obj))
            ctx.lastActor = who; ctx.lastVerb = "block"; ctx.lastMentioned = id; return true
        }
        // "… and blocks one": the creature just mentioned blocks one of the attackers.
        Regex("""^(?:chump[- ]?)?(?:blocks?|blocking) (?:one|one of them|the first|the first one|a single one)$""").find(c)?.let {
            val attacker = ctx.events.firstOrNull { it.verb == "attack" }?.obj ?: return@let
            val who = actor ?: ctx.other(ctx.events.first { it.verb == "attack" }.player) ?: "me"
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).controller == who } ?: ctx.objects.values.lastOrNull { it.controller == who }?.id ?: return@let
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOf(attacker)); ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        // "block one (of them) with Hill Giant": one of the attackers.
        Regex("""^(?:chump[- ]?)?(?:blocks?|blocking) (?:one|one of them|the first|the first one|a single one) with (?:an? |the |my )?(c\d+)$""").find(c)?.let { r ->
            val attacker = ctx.events.firstOrNull { it.verb == "attack" }?.obj ?: return@let
            val who = actor ?: ctx.other(ctx.events.first { it.verb == "attack" }.player) ?: "me"
            val card = m.cards.getValue(r.groupValues[1]); val id = objectIdFor(card, ctx) ?: addObject(card, who, false, ctx)
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOf(attacker)); ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        // "… and the Bears with Hill Giant": another block, continuing "blocks the Angel with Wall of Omens".
        Regex("""^(?:the |my |their |an? )?(c\d+) with (?:an? |the |my |their )?(c\d+)$""").find(c)?.let { r ->
            if (ctx.lastVerb != "block") return@let
            return readClause("blocks ${r.groupValues[1]} with ${r.groupValues[2]}", m, ctx)
        }
        // "blocks Hill Giant with Serra Angel": attacker first.
        Regex("""^(?:chump[- ]?)?(?:blocks?|blocking)\s+(?:an? |the |my |their )?(c\d+) with (?:an? |the |my |their )?(c\d+|it)$""").find(c)?.let { r ->
            val attackerCard = m.cards.getValue(r.groupValues[1])
            val attacker = objectIdFor(attackerCard, ctx) ?: return@let
            val attackerPlayer = ctx.events.lastOrNull { it.verb == "attack" && it.obj == attacker }?.player
            val who = actor ?: attackerPlayer?.let { ctx.other(it) } ?: ctx.objects[attacker]?.controller?.let { ctx.other(it) } ?: subject ?: "opp"
            val id = if (r.groupValues[2] == "it") (ctx.lastMentioned?.takeIf { it in ctx.objects && it != attacker } ?: ctx.events.lastOrNull { it.verb == "cast" && it.player == who }?.card?.name?.let { n -> ctx.objects.values.firstOrNull { it.card.name == n }?.id ?: slug(n) } ?: return@let)
                     else m.cards.getValue(r.groupValues[2]).let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
            // Nobody said the blocked creature attacked: its controller attacks with it first.
            if (attackerPlayer == null && ctx.events.none { (it.verb == "attack" || it.verb == "attackAll") && it.obj == attacker }) {
                val ap = ctx.objects[attacker]?.controller ?: ctx.other(who) ?: "opp"
                if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
                ctx.events += EventSpec("attack", player = ap, obj = attacker, targets = listOf(who)); ctx.notes += "${attackerCard.display} is read as attacking ${if (who == "me") "you" else if (who == "opp") "your opponent" else ctx.players[who] ?: who} (nothing said it attacked)."
            }
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOf(attacker)); ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        Regex("""^(?:(?:chump[- ]?)?(?:blocks?|blocking)|chumps?)(?: it| that| the attacker)?(?: with)?\s+(?:an? |the |my |their |a single |one |just |only )?(c\d+)(.*)$""").find(c)?.let { r ->
            val attackerPlayer0 = ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }?.player
            val who = actor ?: attackerPlayer0?.let { ctx.other(it) } ?: subject ?: "opp"
            ensureAttacker(ctx, who)
            val attackerPlayer = ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }?.player
            val card = m.cards.getValue(r.groupValues[1])
            val id = ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId && it.controller == who }?.id ?: addObject(card, who, false, ctx, allowDuplicate = objectIdFor(card, ctx) != null)
            // The attacker being blocked: the last creature declared attacking this player, else the last attacker.
            val attacker = ctx.events.lastOrNull { it.verb == "attack" && who in it.targets }?.obj ?: ctx.events.lastOrNull { it.verb == "attack" }?.obj
            ctx.events += EventSpec("block", player = who, obj = id, targets = listOfNotNull(attacker)); ctx.lastActor = who; ctx.lastVerb = "block"; return true
        }
        // "it attacks" / "he swings": the creature just mentioned attacks.
        Regex("""^(?:it|he|she|that) (?:attacks?|swings?)(?: alone| by itself)?(?: (?:at |into )?(.*))?$""").find(c)?.let { r ->
            val id = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.objects.values.lastOrNull()?.id ?: return@let
            val who = ctx.objects.getValue(id).controller
            ctx.events += EventSpec("attack", player = who, obj = id, targets = targetsIn("at " + (r.groupValues[1].ifEmpty { "them" }), m, ctx).ifEmpty { listOf(ctx.other(who) ?: "opp") }); ctx.lastActor = who; ctx.lastVerb = "attack"; ctx.lastMentioned = id; return true
        }
        // "I return Grizzly Bears from my graveyard" (Sun Titan): the choice for the trigger of the creature that just attacked or entered.
        Regex("""^(?:returns?|bring(?:s)? back|reanimates?|gets? back) (?:an? |the |my )?(c\d+)(?: from (?:my |the |their |his |her |@\w+'s )?graveyard)?(?: to the battlefield| with (?:it|its trigger|(c\d+)(?:'s trigger)?))?$""").find(c)?.let { r ->
            val who = actor ?: subject ?: "me"
            val srcId = r.groupValues[2].takeIf { it.isNotEmpty() }?.let { m.cards.getValue(it) }?.let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
                ?: ctx.events.lastOrNull { (it.verb == "attack" || it.verb == "enter") && it.player == who }?.obj ?: ctx.events.lastOrNull { it.verb == "cast" && it.player == who }?.card?.name?.let { slug(it) } ?: return@let
            val cardId = addObject(m.cards.getValue(r.groupValues[1]), who, false, ctx, zone = "graveyard", allowDuplicate = true)
            val choose = EventSpec("choose", player = who, obj = srcId, to = "put:$cardId")
            val at = ctx.events.indexOfLast { (it.verb == "attack" || it.verb == "attackAll" || it.verb == "cast" || it.verb == "enter") && (it.obj == srcId || slug(it.card?.name ?: "") == srcId) }
            if (at >= 0) { var i = at; while (i > 0 && (ctx.events[i - 1].verb == "attack" || ctx.events[i - 1].verb == "attackAll") && ctx.events[i - 1].player == ctx.events[at].player) i--; ctx.events.add(i, choose) } else ctx.events += choose
            ctx.lastActor = who; ctx.lastMentioned = cardId; return true
        }
        // "my commander Kaalia attacks (Bob)", "the Bears attack me": the creature is the subject.
        Regex("""^(commander |my commander |(?:$possPrefix)?commander |$possPrefix|an? )?(c\d+) (?:attacks?|swings?)(?: alone| by itself)?(?: (?:at |into )?(.*))?$""").find(c)?.let { r ->
            val card = m.cards.getValue(r.groupValues[2])
            // "my opponent's Grizzly Bears attacks me": the possessive says whose creature is attacking.
            val said = possessiveOwner(r.groupValues[1].replace("commander", "").trim().let { if (it.isEmpty()) "" else "$it " }, ctx, m)
            val who = said ?: actor ?: ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.controller ?: subject ?: "me"
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
        // "… and doesn't" / "declines" right after a cast: the payment is declined.
        if (Regex("""^(?:doesn't|does not|don't|declines?|won't|refuses?)$""").matches(c) && ctx.events.lastOrNull()?.verb == "cast") { val who = actor ?: ctx.events.last().player ?: "opp"; ctx.events += EventSpec("pay", player = who, to = "no"); ctx.lastActor = who; return true }
        // "… and is countered by my Counterspell": a clause whose subject was left out carries on from the last
        // thing mentioned. Tried after every other rule, so it never takes a clause one of them reads; without it
        // the Counterspell went unread and the answer had the spell resolving.
        if (actor == null && !c.startsWith("it ") && ctx.lastMentioned != null &&
            Regex("""^(?:is|are|was|were|gets?|got|has been|have been) (?!\d)\w+(?:ed|n|t)\b""").containsMatchIn(c) && readClause("it $c", m, ctx)) return true
        // "does Rancor come back", "is the Bears dead", "will Jace survive": a question about the outcome, which the answer covers.
        // "does my Blood Artist trigger?": a permanent named only in the question is on the battlefield under that player.
        Regex("""\b(my|their|his|her|@\w+'s) (c\d+)\b""").findAll(clause0).forEach { q -> val card = m.cards.getValue(q.groupValues[2]); if (objectIdFor(card, ctx) == null && !card.isSpellOnly && card.display !in ctx.castCards) addObject(card, when (val w = q.groupValues[1]) { "my" -> "me"; "their", "his", "her" -> pronounPlayer(ctx, w); else -> w.removePrefix("@").removeSuffix("'s") }, false, ctx) }
        if (askQuestion(clause0, m, ctx)) return true
        if (Regex("""^(?:does|do|did|is|are|will|would|can|could|should|what|who|whose|which|how)\b""").containsMatchIn(clause0) && Regex("""\b(?:graveyard|come back|comes back|return|returns|survive|survives|die|dies|dead|trigger|triggers|resolve|resolves|happen|happens|work|works|count|counts|still|get|gets|win|wins|lose|loses|legal|allowed|left|remain|remains|go|goes|stay|stays|assign|assigned|pay|have to|must|need|gain|gains|take|takes|deal|deals|how much|how many|order|first)\b""").containsMatchIn(clause0)) {
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."
            return true
        }
        return false
    }

    /** "does X trigger?" / "does X survive / die?": queues an explicit yes/no answer for after everything has resolved. */
    private fun askQuestion(clause0: String, m: Marked, ctx: Ctx): Boolean {
        // "My opponent casts Toxic Deluge for 3. Does my 4/4 die?": a creature the question gives by its size is
        // on the battlefield too. Without it the sweeper resolved against nothing and the question went unanswered.
        Regex("""\b(my|their|his|her) (\d+/\d+)(?: creature)?\b""").find(clause0)?.let { q ->
            val who = if (q.groupValues[1] == "my") "me" else pronounPlayer(ctx, q.groupValues[1])
            if (ctx.objects.values.none { it.controller == who && (it.card.name ?: "").startsWith("a ${q.groupValues[2]}") })
                describedCreatures("a ", q.groupValues[2], "creature", who, ctx)
        }

        // "who dies?" / "who wins?" / "who loses?": every player's fate, one answer each.
        Regex("""^who (dies|loses|wins|survives|is dead|is alive|comes out ahead)(?: here| then| the game| now)?$""").find(clause0)?.let { q ->
            val to = when (q.groupValues[1]) { "wins", "comes out ahead" -> "playerWin"; "survives", "is alive" -> "playerSurvive"; else -> "playerDie" }
            // "Who dies?" after a block is about the creatures in that combat. Answered about the players it
            // said "you are still in the game", which is true and is not what was asked.
            val blocks = ctx.events.filter { it.verb == "block" }
            // "Who wins?" after a block is about the two creatures too, not about who is still in the game.
            if (blocks.isNotEmpty()) {
                val ids = (blocks.mapNotNull { it.obj } + blocks.mapNotNull { it.targets.firstOrNull() }).distinct().filter { it in ctx.objects }
                if (ids.isNotEmpty()) {
                    for (id in ids) ctx.asks += EventSpec("ask", obj = id, to = if (to == "playerDie") "die" else "survive")
                    ctx.notes += "\"${restore(clause0, m)}?\" is answered for each creature in that combat in the outcome below."
                    return true
                }
            }
            for (pid in ctx.playerIds()) ctx.asks += EventSpec("ask", player = pid, to = to)
            ctx.notes += "\"${restore(clause0, m)}?\" is answered for each player in the outcome below."; return true
        }
        // "both die?" / "do they trade?" / "do both survive?": the last attacker and the creature that blocked it.
        Regex("""^(?:do |does |will |would )?(?:both|they both|the two|both of them|they) (?:die|trade|survive|live)\??$|^(?:is it a )?trade\??$""").find(clause0)?.let {
            val block = ctx.events.lastOrNull { it.verb == "block" } ?: return@let
            val ids = listOfNotNull(block.obj, block.targets.firstOrNull()).filter { it in ctx.objects }
            if (ids.isEmpty()) return@let
            val to = if (clause0.contains("survive") || clause0.contains("live")) "survive" else "die"
            ids.forEach { ctx.asks += EventSpec("ask", obj = it, to = to) }
            ctx.notes += "\"${restore(clause0, m)}?\" is answered for each of the two creatures in the outcome below."; return true
        }
        // "do I die?" / "do they lose?" / "am I dead?" / "does Bob survive?"
        Regex("""^(?:do|does|will|would|am|is|are) (i|they|my opponent|the opponent|opponent|he|she|@\w+) (?:still |then |also )?(die|dies|lose|loses|lose the game|survive|survives|dead|alive|win|wins|make it|live|lives)(?: the game)?(?: (?:this|next) turn| now| here)?$""").find(clause0)?.let { q ->
            val who = when (val w = q.groupValues[1]) { "i" -> "me"; else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w.substringAfterLast(' ')) }
            val to = when (q.groupValues[2]) { "die", "dies", "lose", "loses", "lose the game", "dead" -> "playerDie"; "win", "wins" -> "playerWin"; else -> "playerSurvive" }
            ctx.asks += EventSpec("ask", player = who, to = to); ctx.note(who)
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "does Bob take damage?" / "do I take any damage?" / "how much damage do I take?"
        Regex("""^(?:does|do|will|would) (i|they|my opponent|the opponent|opponent|he|she|@\w+) (?:still |even )?(?:take|takes|receive|suffer) (?:any |the |combat |that )?damage\b|^how much (?:combat )?damage (?:do|does|will|would) (i|they|my opponent|the opponent|opponent|he|she|@\w+) (?:take|receive|suffer)\b""").find(clause0)?.let { q0 ->
            val q = object { val groupValues = listOf(q0.groupValues[0], q0.groupValues[1].ifEmpty { q0.groupValues[2] }) }
            val who = when (val w = q.groupValues[1]) { "i" -> "me"; else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w.substringAfterLast(' ')) }
            ctx.asks += EventSpec("ask", player = who, to = "playerDamage"); ctx.note(who)
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "do I draw?" / "how many cards do I draw?"
        Regex("""^(?:(?:does|do|did|will|would) (i|we|they|he|she|my opponent|the opponent|opponent|@\w+) (?:still |even |actually )?draw(?: any(?: cards?)?| a card| cards| anything)?|how many cards? (?:do|does|did|will|would) (i|we|they|he|she|my opponent|the opponent|opponent|@\w+) draw)$""").find(clause0)?.let { q ->
            val w = q.groupValues[1].ifEmpty { q.groupValues[2] }
            val who = when (w) { "i", "we" -> "me"; else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w.substringAfterLast(' ')) }
            ctx.asks += EventSpec("ask", player = who, to = "playerDraw"); ctx.note(who)
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "what's their life total?" / "how much life do I have left?" / "what am I at?"
        Regex("""^(?:what(?:'s| is| are)? (?:my|their|his|her|the|@\w+'s) life(?: total)?|what life (?:total )?(?:do|does) (i|we|they|he|she|my opponent|the opponent|opponent|@\w+) (?:have|end (?:up )?(?:at|on))|how much life (?:do|does|will|would) (i|we|they|he|she|my opponent|the opponent|opponent|@\w+) (?:have|end (?:up )?(?:at|on|with)|end at)(?: left| now| in the end)?|what (?:am|are|is) (i|we|they|he|she|my opponent|the opponent|opponent|@\w+) (?:at|on)|how much life (?:do|does) (i|we|they|he|she|my opponent|the opponent|opponent|@\w+) end at)$""").find(clause0)?.let { q ->
            val word = q.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
                ?: Regex("""^what(?:'s| is| are)? (my|their|his|her|@\w+'s) """).find(clause0)?.groupValues?.get(1) ?: "i"
            val who = when (val w = word.removeSuffix("'s")) {
                "i", "we", "my", "me" -> "me"
                else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w.substringAfterLast(' '))
            }
            ctx.asks += EventSpec("ask", player = who, to = "playerLife"); ctx.note(who)
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "does the Bears deal damage to my opponent?" / "does it hit me?"
        Regex("""^(?:does|do|did|will|would)\b.*?\b(?:my |their |his |her |the |@\w+'s )?(c\d+)(?:'s)?\b.*?\b(?:deals?|dealt|hits?|connects?|damages?|gets? through|gets? in|go(?:es)? through)\b(?: (?:any |its |combat )?damage)?(?: to)? (me|myself|them|my opponent|the opponent|opponent|him|her|@\w+|(?:my|their) face)\b""").find(clause0)?.let { q ->
            val card = m.cards[q.groupValues[1]] ?: return@let
            val id = objectIdFor(card, ctx) ?: (if (card.display in ctx.castCards) slug(card.display) else return@let)
            val victim = when (val w = q.groupValues[2]) { "me", "myself", "my face" -> "me"; else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w.substringAfterLast(' ')) }
            ctx.asks += EventSpec("ask", obj = id, to = "damage", targets = listOf(victim)); ctx.note(victim)
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "what do I discard?" / "how many cards do I discard?" at the end of a turn: the cleanup step is played out.
        Regex("""^(?:what|how many(?: cards)?|do|does|will|must) (?:do |does |will |must )?(i|they|my opponent|the opponent|opponent|he|she|@\w+) (?:have to |need to )?discard(?: down to| to hand size| at cleanup| at end of turn)?$""").find(clause0)?.let { q ->
            val who = when (val w = q.groupValues[1]) { "i" -> "me"; else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w.substringAfterLast(' ')) }
            ctx.events += EventSpec("resolveAll"); ctx.events += EventSpec("step", player = ctx.activePlayer ?: who, to = "cleanup"); ctx.explicitResolve = true; ctx.note(who)
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by playing the turn out to its cleanup step, where hand size is checked (514.1)."; return true
        }
        // "can I activate it the turn it comes down?" / "can I tap Deathrite Shaman right away?": summoning sickness and {T} costs.
        Regex("""^can (?:i|we|they|he|she|my opponent|the opponent|@\w+) (?:activate|use|tap|fire off|crack) (?:it|that|its ability|its abilities|(?:my |their |his |her |the |@\w+'s )?(c\d+)(?:'s (?:ability|abilities))?)( the (?:same )?turn (?:it|i|they|he|she) (?:comes? down|came down|come down|enters?(?: the battlefield)?|entered(?: the battlefield)?|is played|was played|play it|played it|cast it)| right away| immediately| the turn it drops| straight away)?$""").find(clause0)?.let { q ->
            val ph = q.groupValues[1]
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
                     else ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.objects.values.lastOrNull()?.id ?: return@let
            if (q.groupValues[2].isNotBlank()) {
                ctx.objects[id] = ctx.objects.getValue(id).copy(summoningSick = true)
                ctx.notes += "Read as: ${ctx.objects.getValue(id).card.name ?: id} came under its controller's control this turn."
            }
            ctx.asks += EventSpec("ask", obj = id, to = "activate"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "what color mana can it make?" / "what does it tap for?": the permanent's mana abilities as they stand.
        Regex("""^(?:(?:what|how much|how many) (?:colou?r )?(?:of )?mana (?:can|does|do|will) (?:it|that|(?:my |their |the |@\w+'s )?(c\d+)) (?:make|produce|add|give|tap for)|what (?:does|do|can) (?:it|that|(?:my |their |the )?(c\d+)) tap for)(?: now| then| for me)?$""").find(clause0)?.let { q ->
            val ph = q.groupValues[1].ifEmpty { q.groupValues[2] }
            // "I control Elvish Archdruid and two other Elves. How much mana does it make?" — "it" is the card
            // that was named, not the last unnamed creature described after it.
            // "I control Cabal Coffers and four Swamps. How much mana does it make?" — the question is about the
            // card that counts them, not about the last basic land named.
            fun basic(id: String) = (ctx.objects[id]?.card?.name ?: "") in setOf("Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes")
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
                     else ctx.lastMentioned?.takeIf { it in ctx.objects && !basic(it) && !Regex("""^an? """).containsMatchIn(ctx.objects.getValue(it).card.name ?: "") }
                         ?: ctx.events.lastOrNull { (it.verb == "cast" || it.verb == "play") && it.card?.name != null }?.card?.name?.let { slug(it) }
                         ?: ctx.objects.values.lastOrNull { it.zone == "battlefield" && !basic(it.id) && !Regex("""^an? """).containsMatchIn(it.card.name ?: "") }?.id
                         ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "mana"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "which of my creatures can attack?": one answer per creature that player controls.
        Regex("""^(?:which|what) (?:of (?:my|their|his|her)|my|their|his|her) creatures?(?: can| could| may| is able to| are able to)? (attack|block)(?:\s+(?:it|that|them|the attacker|this turn|now|at all))*$""").find(clause0)?.let { q ->
            val who = Regex("""\b(my|their|his|her)\b""").find(clause0)?.groupValues?.get(1)?.let { if (it == "my") "me" else pronounPlayer(ctx, it) } ?: "me"
            val ids = ctx.objects.values.filter { it.controller == who && it.zone == "battlefield" && isCreatureName(it.card.name) }.map { it.id }
            if (ids.isEmpty()) return@let
            ids.forEach { ctx.asks += EventSpec("ask", obj = it, to = q.groupValues[1]) }
            ctx.notes += "\"${restore(clause0, m)}?\" is answered for each of ${if (who == "me") "your" else "their"} creatures in the outcome below."; return true
        }
        // "which one dies?" / "which of them dies?": the last attacker and the creature that blocked it, one answer each.
        Regex("""^(?:which|who) (?:one|of them|of the two|creature)? ?(?:dies|die|survives|survive|lives|is left)(?: here| then| now)?$""").find(clause0)?.let {
            val block = ctx.events.lastOrNull { it.verb == "block" } ?: return@let
            val ids = listOfNotNull(block.obj, block.targets.firstOrNull()).filter { it in ctx.objects }
            if (ids.isEmpty()) return@let
            val to = if (clause0.contains("surviv") || clause0.contains("lives") || clause0.contains("is left")) "survive" else "die"
            ids.forEach { ctx.asks += EventSpec("ask", obj = it, to = to) }
            ctx.notes += "\"${restore(clause0, m)}?\" is answered for each of the two creatures in the outcome below."; return true
        }
        // "is it blocked?" / "is my attacker blocked?": combat state, not a statement that it is blocked.
        Regex("""^(?:is|are|was|were|does|do|did) (?:it|that|they|(?:my |their |his |her |the |@\w+'s )?(c\d+))(?:'s)? (?:still |even |actually )*(?:get |getting |become )?blocked(?: still| now| at all| by anything)?$""").find(clause0)?.let { q ->
            val ph = q.groupValues[1]
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
                     else ctx.events.lastOrNull { it.verb == "attack" }?.obj ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "blocked"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "can it be targeted?" / "can they target my Bears?": hexproof, shroud, protection and ward on that permanent.
        Regex("""^(?:can|could|may|is|are|does|do) (?:it|that|they|(?:my |their |his |her |the |@\w+'s )?(c\d+))(?:'s)? (?:still |even )?(?:be targeted|be a legal target|be targetted)(?: by (?:anything|a spell|spells|them|me))?$|^(?:can|could|may) (?:i|they|he|she|my opponent|the opponent|@\w+) (?:still |even )?target (?:it|that|(?:my |their |his |her |the |@\w+'s )?(c\d+))$""").find(clause0)?.let { q ->
            val ph = q.groupValues[1].ifEmpty { q.groupValues[2] }
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
                     else ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "targetable"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "can it tap for mana?" / "can my Elves make mana?": the activation question, which knows about
        // summoning sickness and about the permanent already being tapped.
        Regex("""^(?:can|could|may|is it able to) (?:it|that|(?:my |their |his |her |the |@\w+'s )?(c\d+)) (?:still |even )?(?:tap for mana|tap for it|make mana|produce mana|add mana|be tapped for mana)(?: now| yet| this turn| already)?$""").find(clause0)?.let { q ->
            val ph = q.groupValues[1]
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
                     else ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.lastOrNull { it.verb == "cast" && it.card?.name != null }?.card?.name?.let { slug(it) } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "activate"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "is it a 3/3 now?": the creature's size, asked as a yes/no. Without this the clause read as the
        // statement "it is a 3/3" and the answer was "nothing changes".
        Regex("""^(?:is|are|'s) (?:it|that|they|(?:my |their |his |her |the |@\w+'s )?(c\d+))(?:'s)? (?:still |now |actually |really |even )*an? (\d+/\d+)(?: now| still| right now| then| after that| at that point)?$""").find(clause0)?.let { q ->
            val ph = q.groupValues[1]
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
                     else ctx.lastMentioned?.takeIf { it in ctx.objects && isCreatureName(ctx.objects.getValue(it).card.name) }
                         ?: ctx.events.lastOrNull { it.verb == "cast" || it.verb == "activate" }?.targets?.firstOrNull { it in ctx.objects } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "pt"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "how many counters does it have?" / "how many +1/+1 counters are on my Ballista?"
        Regex("""^how many (?:([+-]\d+/[+-]\d+|[a-z]+) )?counters? (?:does|do|is|are) (?:it|that|they|(?:my |their |his |her |the |@\w+'s )?(c\d+))(?:'s)? (?:have|has|on it|got)(?: now| in total| altogether)?$|^how many (?:([+-]\d+/[+-]\d+|[a-z]+) )?counters? (?:is|are) (?:on|there on) (?:it|that|(?:my |their |his |her |the |@\w+'s )?(c\d+))$""").find(clause0)?.let { q ->
            val ph = q.groupValues[2].ifEmpty { q.groupValues[4] }
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
                     else ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "counters"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "is it summoning sick?" / "does my Elves have summoning sickness?": whether it came down this turn.
        Regex("""^(?:is|are|does|do|did|was|were) (?:it|that|they|(?:my |their |his |her |the |@\w+'s )?(c\d+))(?:'s)? (?:still |even |actually )*(?:summoning[- ]sick|have summoning sickness|has summoning sickness)(?: still| now| right now)?$""").find(clause0)?.let { q ->
            val ph = q.groupValues[1]
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
                     else ctx.lastMentioned?.takeIf { it in ctx.objects }
                         ?: ctx.events.lastOrNull { it.verb == "cast" && it.card?.name != null }?.card?.name?.let { slug(it) } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "summoningSick"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "does my Serra Angel have flying?" / "is it still indestructible?": a question about a keyword, not a
        // statement granting one. Read as a statement it granted the keyword and then answered "nothing changes".
        Regex("""^(?:does|do|did|will|would|is|are) (?:it|that|they|(?:my |their |his |her |the |an? |@\w+'s )?(c\d+))(?:'s)? (?:still |even |really |actually |now )*(?:have |has |keep |keeps |retain |retains |got )?($kwPhrase)(?: any ?more| still| now| at all| right now| then| after that)?$""").find(clause0)?.let { q ->
            val ph = q.groupValues[1]
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
                     else ctx.lastMentioned?.takeIf { it in ctx.objects && isCreatureName(ctx.objects.getValue(it).card.name) }
                         ?: ctx.events.lastOrNull { it.verb == "cast" || it.verb == "activate" }?.targets?.firstOrNull { it in ctx.objects }
                         ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "keyword:${q.groupValues[2]}")
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "is Heliod a creature?" / "is it a creature right now?": the gods and anything else that turns creature-ness off.
        Regex("""^(?:is|are|'s) (?:it|that|(?:my |their |the |an? |@\w+'s )?(c\d+))(?:'s)? (?:still |currently |actually |even |really )?an? creature(?: right now| now| yet| at the moment| currently)?$""").find(clause0)?.let { q ->
            val ph = q.groupValues[1]
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
                     else ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "isCreature"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "what are my Bears now?" / "what is it now?": the creature's size once everything is done.
        Regex("""^what (?:are|is|'s) (?:my |their |the |@\w+'s )?(c\d+|it|they)(?: now| then| after that| after this| at that point)?$""").find(clause0)?.let { q ->
            val id = if (q.groupValues[1] == "it" || q.groupValues[1] == "they") ctx.lastMentioned?.takeIf { it in ctx.objects && isCreatureName(ctx.objects.getValue(it).card.name) } ?: return@let else m.cards[q.groupValues[1]]?.takeIf { it.typeLine.contains("Creature") }?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "pt"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "do I get it back?" / "does it come back to me at end of turn?" / "do I regain control?": the turn is played out to cleanup and control checked.
        Regex("""^(?:do|does|will|would) (i|they|my opponent|the opponent|opponent|@\w+|it) (?:get (?:it|that|(?:my |the )?(c\d+)) back|regain control(?: of (?:it|that|(?:my |the )?(c\d+)))?|come back to (?:me|them|@\w+)|return to (?:me|them|@\w+)|go back(?: to (?:me|them|its owner|@\w+))?)(?: at (?:the )?end of (?:the )?turn| at end of turn| after (?:the|this|their) turn| next turn| at cleanup| afterwards| after that)?$""").find(clause0)?.let { q ->
            val ph = q.groupValues[2].ifEmpty { q.groupValues[3] }
            val id = if (ph.isNotEmpty()) m.cards[ph]?.let { objectIdFor(it, ctx) } ?: return@let else ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.lastOrNull { it.verb == "cast" || it.verb == "activate" }?.targets?.firstOrNull { it in ctx.objects } ?: return@let
            val who = when (val w = q.groupValues[1]) { "i" -> "me"; "it" -> ctx.objects.getValue(id).controller; else -> if (w.startsWith("@")) w.removePrefix("@") else pronounPlayer(ctx, w.substringAfterLast(' ')) }
            val active = ctx.activePlayer ?: ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }?.player ?: ctx.other(who) ?: "opp"
            ctx.events += EventSpec("resolveAll"); ctx.explicitResolve = true; ctx.note(active)
            ctx.asks += EventSpec("ask", obj = id, player = who, to = "control", targets = listOf(active)); ctx.note(who)
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by playing the turn out to its cleanup step; the outcome below says who controls ${ctx.objects.getValue(id).card.name} then."; return true
        }
        // "what are their stats?" / "how big are my creatures?": every creature that player has, one answer each.
        Regex("""^(?:what (?:are|'s) (?:their|(?:my|our|his|her|the opponent's|my opponent's|@\w+'s) creatures'?) (?:stats|sizes|size|power and toughness|p/t)|how big are (?:they|(?:my|their|his|her|@\w+'s) creatures))(?: now| then| after that)?$""").find(clause0)?.let { q ->
            val whose = Regex("""\b(my|our|his|her|their|the opponent's|my opponent's|@\w+'s)\b""").find(clause0)?.groupValues?.get(1)
            val who = when (whose) {
                "my", "our", null -> "me"
                // "I control Goblin Chieftain and two other Goblins. What are their stats?" — "their" is the
                // creatures', not a player's, so it means whoever controls the ones just described.
                "their" -> ctx.lastMentioned?.takeIf { it in ctx.objects }?.let { ctx.objects.getValue(it).controller } ?: pronounPlayer(ctx, "their")
                "his", "her" -> pronounPlayer(ctx, "their")
                "the opponent's", "my opponent's" -> pronounPlayer(ctx, "opponent")
                else -> whose.removePrefix("@").removeSuffix("'s")
            }
            val ids = ctx.objects.values.filter { it.zone == "battlefield" && it.controller == who && isCreatureName(it.card.name) }.map { it.id }
            if (ids.isEmpty()) return@let
            for (id in ids) ctx.asks += EventSpec("ask", obj = id, to = "pt")
            ctx.notes += "\"${restore(clause0, m)}?\" is answered for each of those creatures in the outcome below."; return true
        }
        // "what are its stats?" / "how big is Tarmogoyf?" / "what's Kird Ape's power and toughness?": the creature's size once everything is done.
        Regex("""^(?:what (?:are|is|'s|s) (?:its|(?:my |their |the |@\w+'s )?(c\d+)(?:'s)?) (?:stats|size|power and toughness|p/t|power|toughness|power/toughness)|how big(?: is)? (?:it|(?:my |their |the |@\w+'s )?(c\d+))|how big|how large|what size is (?:it|(?:my |their |the )?(c\d+)))(?: now| right now| then| after that| at that point)?$""").find(clause0)?.let { q ->
            val ph = q.groupValues[1].ifEmpty { q.groupValues[2] }.ifEmpty { q.groupValues[3] }
            // "My delirium is on and I cast Grim Flayer. How big is it?": a card described in a graveyard is still
            // a creature card, so without the zone check "it" was answered about that rather than the new creature.
            val id = if (ph.isEmpty()) (ctx.events.lastOrNull { it.verb == "cast" && it.card?.name != null && isCreatureName(it.card.name) }?.card?.name?.let { slug(it) }
                ?: ctx.lastMentioned?.takeIf { it in ctx.objects && ctx.objects.getValue(it).zone == "battlefield" && isCreatureName(ctx.objects.getValue(it).card.name) }
                ?: ctx.objects.values.lastOrNull { it.controller == "me" && it.zone == "battlefield" && isCreatureName(it.card.name) }?.id
                ?: ctx.lastMentioned?.takeIf { it in ctx.objects } ?: return@let) else m.cards[ph]?.let { objectIdFor(it, ctx) ?: addObject(it, "me", false, ctx) } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "pt"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "does Serra Angel untap?" / "is the Angel tapped?" / "is it still untapped?": whether it's tapped once everything is done.
        Regex("""^(?:does|do|did|will|would|is|are)\b.*?\b(?:my |their |his |her |the |@\w+'s )?(c\d+|it)(?:'s)?\b.*?\b(untap|untaps|untapped|tapped|tap|taps|stay tapped|stay untapped|still tapped|still untapped)\b""").find(clause0)?.let { q ->
            val id = if (q.groupValues[1] == "it") (ctx.lastMentioned?.takeIf { it in ctx.objects }
                ?: ctx.events.lastOrNull { it.verb == "cast" && it.card?.name != null }?.card?.name?.let { slug(it) } ?: return@let)
                else m.cards[q.groupValues[1]]?.let { objectIdFor(it, ctx) ?: slug(it.display) } ?: return@let
            ctx.asks += EventSpec("ask", obj = id, to = "tapped"); ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."; return true
        }
        // "does my creature survive?" / "does their token die?": the last creature that player described.
        Regex("""^(?:does|do|did|will|would|is|are) (my|their|his|her|the|@\w+'s) (?:(\d+/\d+)(?: creature)?|creature|token|guy|attacker|blocker|dude|beater) (?:still |going to |gonna )?(trigger|triggers|go off|survive|survives|die|dies|dead|still alive|live|lives|make it)\b""").find(clause0)?.let { q0 ->
            val q = object { val groupValues = listOf(q0.groupValues[0], q0.groupValues[1], q0.groupValues[3]) }
            val who = when (val w = q.groupValues[1]) { "my" -> "me"; "the" -> null; "their", "his", "her" -> pronounPlayer(ctx, w); else -> w.removePrefix("@").removeSuffix("'s") }
            val pt = q0.groupValues[2]
            val id = (if (pt.isNotEmpty()) ctx.objects.values.lastOrNull { (who == null || it.controller == who) && (it.card.name ?: "").startsWith("a $pt") } else null)
                ?: ctx.objects.values.lastOrNull { (who == null || it.controller == who) && (it.card.name ?: "").startsWith("a ") } ?: ctx.objects.values.lastOrNull { who == null || it.controller == who } ?: return@let
            ctx.asks += EventSpec("ask", obj = id.id, to = if (q.groupValues[2].startsWith("trigger") || q.groupValues[2] == "go off") "trigger" else if (q.groupValues[2] in setOf("die", "dies", "dead")) "die" else "survive")
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below (read as ${id.card.name ?: id.id})."; return true
        }
        // "does it survive?" / "will it die?": the last-mentioned permanent.
        Regex("""^(?:does|do|did|will|would|is|are) (?:(?:its|the|his|her|their) (?:ability|abilities|trigger|triggered ability) )?(it|that|this|he|she|they|theirs|mine|yours|the other one|the other)?(?: (?:still |going to |gonna ))? ?(trigger|triggers|go off|survive|survives|die|dies|dead|still alive|live|lives|make it)\b""").find(clause0)?.let { q0 ->
            val q = object { val groupValues = listOf(q0.groupValues[0], q0.groupValues[2]) }
            val aboutTrigger = q0.groupValues[2].startsWith("trigger") || q0.groupValues[2] == "go off"
            val last = ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.events.asReversed().firstNotNullOfOrNull { e -> if (e.verb == "cast" || e.verb == "activate") e.targets.firstOrNull { it in ctx.objects } else null }
                // "Does its ability trigger?" right after casting a creature: the creature that was cast. But
                // "does it survive?" after casting Infest is about a creature, not about the sweeper.
                ?: ctx.events.lastOrNull { it.verb == "cast" && it.card?.name != null }?.card?.name
                    ?.takeIf { n -> aboutTrigger || names.lookup(Names.normalize(n))?.isSpellOnly != true }?.let { slug(it) }
                // Only for a plain "it": "theirs" and "mine" are resolved against the other side below, and need
                // the referent left alone.
                ?: ctx.objects.values.lastOrNull { q0.groupValues[1] !in setOf("theirs", "mine", "yours", "the other one", "the other") && it.zone == "battlefield" && isCreatureName(it.card.name) }?.id
                // "I control Mutavault and they Wrath. Does it die?": a land or another permanent can be the one
                // asked about, and asking whether it dies is a fair question about anything on the battlefield.
                ?: ctx.objects.values.lastOrNull { q0.groupValues[1] !in setOf("theirs", "mine", "yours", "the other one", "the other") && it.zone == "battlefield" }?.id
            // "theirs" / "mine": the same-named creature on the other side of the table.
            val id = when (q0.groupValues[1]) {
                "theirs", "the other one", "the other" -> last?.let { l -> ctx.objects[l] }?.let { o -> ctx.objects.values.lastOrNull { it.card.name == o.card.name && it.controller != o.controller }?.id } ?: ctx.objects.values.lastOrNull { it.controller == pronounPlayer(ctx, "their") }?.id ?: return@let
                "mine", "yours" -> last?.let { l -> ctx.objects[l] }?.let { o -> ctx.objects.values.lastOrNull { it.card.name == o.card.name && it.controller == "me" }?.id } ?: ctx.objects.values.lastOrNull { it.controller == "me" }?.id ?: return@let
                else -> last ?: return@let
            }
            ctx.asks += EventSpec("ask", obj = id, to = if (q.groupValues[1].startsWith("trigger") || q.groupValues[1] == "go off") "trigger" else if (q.groupValues[1] in setOf("die", "dies", "dead")) "die" else "survive")
            val known = ctx.objects[id]
            ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below" + (known?.let { o -> " (\"${q0.groupValues[1].ifEmpty { "its ability" }}\" read as ${o.card.name}${if (o.controller == "me") ", yours" else ", ${ctx.players[o.controller] ?: "your opponent"}'s"})" } ?: "") + "."; return true
        }
        val q = Regex("""^(?:does|do|did|will|would|is|are)\b.*?\b(?:my |their |his |her |the |@\w+'s )?(c\d+)(?:'s)?\b.*?\b(trigger|triggers|go off|survive|survives|die|dies|dead|still alive|live|lives|make it)\b""").find(clause0) ?: return false
        val card = m.cards[q.groupValues[1]] ?: return false
        // "does my Blood Artist trigger?": a permanent named only in the question is on the battlefield under that player.
        val owner = Regex("""\b(my|their|his|her|@\w+'s) ${q.groupValues[1]}\b""").find(clause0)?.groupValues?.get(1)?.let { w -> when (w) { "my" -> "me"; "their", "his", "her" -> pronounPlayer(ctx, w); else -> w.removePrefix("@").removeSuffix("'s") } }
        // "does Blood Artist trigger?" names nobody: the question is the asker's, so the permanent is theirs.
        val id = objectIdFor(card, ctx) ?: (if (card.display in ctx.castCards && !card.isSpellOnly) slug(card.display)
            else if (!card.isSpellOnly) addObject(card, owner ?: "me", false, ctx).also { if (owner == null) ctx.notes += "${card.display} was named only in the question; it is taken to be on the battlefield under your control." }
            else return false)
        ctx.asks += EventSpec("ask", obj = id, to = if (q.groupValues[2].startsWith("trigger") || q.groupValues[2] == "go off") "trigger" else if (q.groupValues[2] in setOf("die", "dies", "dead")) "die" else "survive")
        ctx.notes += "\"${restore(clause0, m)}?\" is answered by the outcome below."
        return true
    }

    /** Modal "counter a spell or destroy a permanent" cards: their target may well be on the battlefield. */
    private val modalBlasts = setOf("Red Elemental Blast", "Pyroblast", "Hydroblast", "Blue Elemental Blast")
    private fun needsSpellTarget(card: NameIndex.Entry) = card.display in setOf("Counterspell", "Negate", "Mana Drain", "Force of Will", "Swan Song", "Dovin's Veto", "Arcane Denial", "Mana Leak", "Dispel", "Miscast", "Spell Pierce", "Force of Negation", "Fierce Guardianship", "Mystical Dispute", "Memory Lapse", "Remand", "Daze", "Stubborn Denial", "Cancel", "Dissolve", "Absorb", "Essence Scatter", "Counterflux", "Render Silent", "Disallow", "Void Shatter", "Syncopate", "Power Sink", "Mana Tithe", "Delay", "Rewind", "Hinder", "Spell Snare", "An Offer You Can't Refuse", "Flusterstorm", "Red Elemental Blast", "Pyroblast", "Hydroblast", "Blue Elemental Blast")

    private fun emitCast(who: String, card: NameIndex.Entry, rest0000: String, m: Marked, ctx: Ctx) {
        // "… with revolt" / "revolt is on": Fatal Push's condition has been met.
        val revolt = Regex("""\s*\b(?:with revolt(?: (?:on|active|turned on|enabled))?|revolt (?:on|active)|and revolt is (?:on|active|met))\b""").find(rest0000)
        if (revolt != null) ctx.notes += "Revolt is read as satisfied (a permanent you controlled left the battlefield this turn)."
        val mastery = Regex("""\s*\b(?:with spell mastery(?: (?:on|active))?|spell mastery (?:on|active)|and spell mastery is (?:on|active|met))\b""").find(rest0000)
        if (mastery != null) ctx.notes += "Spell mastery is read as satisfied (two or more instant and sorcery cards in your graveyard)."
        val rest0000r = (mastery?.let { rest0000.removeRange(it.range) } ?: rest0000).let { r0 -> revolt?.takeIf { mastery == null }?.let { r0.removeRange(it.range) } ?: r0 }
        // "… with 1 Mountain untapped" / "with only 2 lands open": the caster's mana, not a target.
        val rest00000 = Regex("""\s*\bwith (?:only |just )?(\d+|one|two|three|four|five|six) (?:untapped |open )?(?:c\d+s?|lands?|mana(?: sources?)?)(?: untapped| open| available| up| left)?$""").find(rest0000r)?.let { r ->
            val n = number(r.groupValues[1]) ?: return@let null
            ctx.mana[who] = n; ctx.note(who); ctx.notes += "${if (who == "me") "You have" else (ctx.players[who] ?: "Your opponent") + " has"} $n mana available; costs are checked against that."; rest0000r.removeRange(r.range)
        } ?: rest0000r
        // "casts Bolt at Bob who is at 2 life": the life total belongs to the player it was said about. Read as the
        // caster's it went to the wrong player and the answer had the wrong player losing the game.
        val aboutLife = Regex("""\s*\b(me|them|him|her|my opponent|the opponent|@\w+)\s*,?\s+(?:who(?:'s| is| was)?|that(?:'s| is| was)?|is|was)\s+(?:sitting |currently |already )?at (\d+) life\b,?""").find(rest00000)
        // "… at 5 life" / "while at 5 life" with nobody named: the caster's life total.
        val rest000 = if (aboutLife != null) {
            val whose = when (val w = aboutLife.groupValues[1]) {
                "me" -> "me"
                "them", "him", "her", "my opponent", "the opponent" -> ctx.other(who) ?: "opp"
                else -> w.removePrefix("@")
            }
            ctx.life[whose] = aboutLife.groupValues[2].toInt(); ctx.note(whose)
            rest00000.replaceRange(aboutLife.range, " " + aboutLife.groupValues[1])   // the player is still the target
        } else Regex("""\s*\b(?:while |when |sitting )?at (\d+) life\b""").find(rest00000)?.let { r -> ctx.life[who] = r.groupValues[1].toInt(); ctx.note(who); rest00000.removeRange(r.range) } ?: rest00000
        // "flashes in Ambush Viper to block it": the creature blocks once it has resolved.
        val blockAfter = Regex("""\s*\b(?:to|and) (?:chump[- ]?)?blocks? (?:it|that|the attacker|(?:the |my |their )?c\d+)$""").find(rest000)
        val rest00 = blockAfter?.let { rest000.removeRange(it.range) } ?: rest000
        if (blockAfter != null) {
            emitCast(who, card, rest00, m, ctx)
            val attacker = Regex("""(c\d+)""").find(blockAfter.value)?.groupValues?.get(1)?.let { m.cards.getValue(it) }?.let { objectIdFor(it, ctx) } ?: ctx.events.lastOrNull { it.verb == "attack" }?.obj ?: return
            ctx.events += EventSpec("resolveAll")
            ctx.events += EventSpec("block", player = who, obj = slug(card.display), targets = listOf(attacker)); ctx.lastVerb = "block"; return
        }
        // "… sacrificing my 5/5 Beast token" / "sacrificing Grizzly Bears": an additional cost paid while casting.
        val rest0 = Regex("""\s*\b(?:sacrificing|saccing|by sacrificing|and sacrifices?) (?:my |the |an? |their )?((?:\d+/\d+ )?(?:[a-z]+ )*?token|c\d+|it|that)\b""").find(rest00)?.let { r ->
            val what = r.groupValues[1]
            val id = if (what == "it" || what == "that") (ctx.lastMentioned?.takeIf { it in ctx.objects } ?: ctx.objects.values.lastOrNull { it.controller == who }?.id ?: return@let null)
                     else if (what.startsWith("c") && what.drop(1).all { it.isDigit() }) m.cards.getValue(what).let { objectIdFor(it, ctx) ?: addObject(it, who, false, ctx) }
                     else { var id = slug(what); var k = 2; while (ctx.objects.containsKey(id)) id = slug(what) + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = what), controller = who, token = true); id }
            ctx.events += EventSpec("sacrifice", player = who, obj = id)
            ctx.notes += "The sacrifice is an additional cost of ${card.display}, paid while casting it (601.2b); it's shown just before the spell goes on the stack."
            rest00.removeRange(r.range)
        } ?: rest00
        // "cast Giant Growth on it twice": the same spell that many times. But "choosing draw a card three times"
        // (Mystic Confluence) picks the same mode that many times on one spell, not that many spells.
        val timesIsModeRepeat = Regex("""\b(?:choosing|picking|selecting)\b.*\b(?:twice|two times|three times|\d+ times)\b""").containsMatchIn(rest0)
        val times = if (timesIsModeRepeat) null else Regex("""\s*\b(twice|two times|three times|(\d+) times)\b""").find(rest0)
        val rest1 = times?.let { rest0.removeRange(it.range) }
            ?: if (timesIsModeRepeat) rest0.replace(Regex("""\s*\b(?:twice|two times|three times|four times|\d+ times)\b"""), "") else rest0
        // The repeat count rides along with the mode so the judge can pick it that many times.
        val modeRepeat = if (!timesIsModeRepeat) 1 else Regex("""\b(twice|two times|three times|four times|(\d+) times)\b""").find(rest0)?.let { r ->
            r.groupValues[2].toIntOrNull() ?: when { r.groupValues[1].startsWith("two") || r.groupValues[1] == "twice" -> 2; r.groupValues[1].startsWith("three") -> 3; r.groupValues[1].startsWith("four") -> 4; else -> 1 }
        } ?: 1
        val secondTime = Regex("""\s*\b(?:for (?:the|a) second time|a second time|for the 2nd time)(?: this game)?\b""").find(rest1)
        val rest = secondTime?.let { rest1.removeRange(it.range) } ?: rest1
        val n = times?.let { if (it.groupValues[2].isNotEmpty()) it.groupValues[2].toInt() else if (it.groupValues[1].startsWith("three")) 3 else 2 } ?: if (secondTime != null) 2 else 1
        if (secondTime != null) ctx.notes += "\"For the second time\": the first ${card.display} is shown resolving earlier this game, then this one."

        val evoked = Regex("""\b(?:with evoke|evoked|for (?:its|the) evoke cost|via evoke|evoking it|using evoke)\b""").containsMatchIn(rest)
        // "with flashback", "from my graveyard", "flashing it back": cast from the graveyard, so it is exiled afterwards.
        val flashedBack = Regex("""\b(?:with flashback|for (?:its|the) flashback cost|via flashback|flashing it back|using flashback|from (?:my|their|his|her|the) graveyard)\b""").containsMatchIn(rest)
        // "choosing the second mode", "mode 2", "choosing modes 1 and 3"
        val modes = Regex("""(?:choosing |with |picking )?(?:the )?(?:mode|modes) (\d+(?:\s*(?:,|and|&)\s*\d+)*)|(?:choosing |picking )(?:the )?(first|second|third|fourth) (?:mode|option)""").find(rest)?.let { mm ->
            if (mm.groupValues[1].isNotEmpty()) Regex("""\d+""").findAll(mm.groupValues[1]).map { it.value.toInt() }.toList()
            else listOf(mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4).getValue(mm.groupValues[2]))
        // "choosing both modes", "choosing both": a "choose one or both" spell with every mode taken.
        } ?: Regex("""\b(?:choosing|picking|with|taking) (?:both|all)(?: (?:of the )?modes| of them| the modes)?\b""").find(rest)?.let { listOf(1, 2) } ?: emptyList()
        val overload = Regex("""\b(?:overloaded|with overload|for (?:its|the) overload cost|via overload)\b""").containsMatchIn(rest)
        // "naming Lightning Bolt" / "calling Brainstorm": the card name a spell asks its caster to choose.
        val namedCard = Regex("""\b(?:naming|calling|and names?|which names) (?:an? |the )?(c\d+)\b""").find(rest)?.let { n -> m.cards[n.groupValues[1]]?.display }
        // "with X = 3", "for X of 3", and the bare "Mind Twist for 3" / "Fireball for 5" at the end of the clause.
        // "paying 3 life" on a spell whose additional cost is "pay X life" (Toxic Deluge, Dismember) says what X is.
        val payLife = Regex("""\b(?:paying|and pays?|pay) (\d+) life\b|\bfor (\d+) life\b""").find(rest)?.let { r -> (r.groupValues[1].ifEmpty { r.groupValues[2] }).toIntOrNull() }
        val xValue = Regex("""\b(?:with|for|where|at) x ?(?:=|equal to|equals|being|of|as) ?(\d+)\b|\bx ?= ?(\d+)\b""").find(rest)?.let { r -> (r.groupValues[1].ifEmpty { r.groupValues[2] }).toIntOrNull() }
            ?: Regex("""\bfor (\d+)(?=\s*$|\s+(?:targeting|at|on|against)\b)""").find(rest.trim())?.groupValues?.get(1)?.toIntOrNull()
            ?: payLife
        val kicked = Regex("""\b(?:kicked|with (?:the )?kicker|with kicker paid|paying (?:the )?kicker|kicking it)\b""").containsMatchIn(rest)
        // "copying their Grizzly Bears", "as a copy of Serra Angel": which permanent a Clone enters as a copy of.
        val copyOf = Regex("""\b(?:copying|as a copy of|to copy) (?:it|that|them)\b""").find(rest)?.let { ctx.lastMentioned?.takeIf { lm -> lm in ctx.objects } }
            ?: Regex("""\b(?:copying|as a copy of|to copy) ($possPrefix|an? )?(c\d+)\b""").find(rest)?.let { r ->
            val copied = m.cards[r.groupValues[2]] ?: return@let null
            val owner = when (val w = r.groupValues[1].trim()) {
                "my" -> "me"
                "their", "my opponent's" -> pronounPlayer(ctx, "their")
                "", "the", "a", "an" -> null
                else -> if (w.startsWith("@")) w.removePrefix("@").removeSuffix("'s").also { ctx.players.putIfAbsent(it, m.players[it] ?: it) } else null
            }
            objectIdFor(copied, ctx) ?: addObject(copied, owner ?: ctx.other(who) ?: "opp", false, ctx)
        }
        // "choosing counter target spell & draw a card": whole mode lines, matched against the card's own modes by the judge.
        val modeVerbs = """counter|draw|destroy|exile|return|tap|untap|gain|deal|discard|sacrifice|prevent|create|search|put|mill"""
        val modeWord = (Regex("""\b(?:choosing|picking|selecting) (?:the )?((?:$modeVerbs)[a-z0-9' ]*?) (?:and|&) (?:the )?((?:$modeVerbs)[a-z0-9' ]*?)\s*$""").find(rest.trim())?.let { mm -> mm.groupValues[1].trim() + "|" + mm.groupValues[2].trim() }
            ?: Regex("""\b(?:choosing|picking|selecting) (?:the )?((?:$modeVerbs)[a-z0-9' ]*?)\s*$""").find(rest.trim())?.groupValues?.get(1)?.trim()
            ?: Regex("""\b(?:choosing|picking|selecting) (?:the )?(indestructible|double strike|first strike|damage|lifelink|hexproof|trample|flying|counter|draw|destroy|exile|bounce|pump|tap|untap|return)(?: mode)?,? (?:and|&) (?:the )?(indestructible|double strike|first strike|damage|lifelink|hexproof|trample|flying|counter|draw|destroy|exile|bounce|pump|tap|untap|return)(?: mode)?$""").find(rest.trim())?.let { mm -> mm.groupValues[1] + "|" + mm.groupValues[2] }
            ?: Regex("""(?:choosing|picking|with|for|selecting) (?:the )?([a-z][a-z-]+) (?:mode|option)\b""").find(rest)?.groupValues?.get(1)
            ?: Regex("""\b(?:choosing|picking|selecting|for|giving (?:it |them |my creatures? |my team |everything )?|to give (?:it |them )?|granting (?:it |them )?) ?(?:the )?(indestructible|double strike|first strike|damage|lifelink|hexproof|trample|flying|counter|draw|destroy|exile|bounce|pump)(?: until end of turn| this turn| mode)?$""").find(rest.trim())?.groupValues?.get(1)
            ?: Regex("""\b(?:to |and )?(gain|prevent|draw|destroy|exile|counter|return|deal|discard|scry|sacrifice|tap|untap)(?:ing|s)?\b(?: \d+ (?:life|cards?|damage))?$""").find(rest.trim())?.groupValues?.get(1))?.takeIf { it !in setOf("first", "second", "third", "fourth", "same", "other") }
        ctx.castingCounter = needsSpellTarget(card); ctx.castingCounterName = card.display
        val targets0 = if (overload) emptyList() else targetsIn(rest.replace(Regex("""\b(?:paying|and pays?|pay) \d+ life\b"""), "").replace(Regex("""\b(?:with (?:the )?kicker|with kicker paid|paying (?:the )?kicker|kicked|with evoke|evoked|for (?:its|the) evoke cost|via evoke|evoking it|using evoke)\b"""), "").replace(Regex("""\b(?:with|for|where|at) x ?(?:=|equal to|equals|being|of|as) ?\d+\b|\bx ?= ?\d+\b"""), ""), m, ctx)
        // "They cast Counterspell" with nothing on the stack to counter: it must be answering a spell of the other player's.
        var targets = targets0
        if (targets.isEmpty() && needsSpellTarget(card) && ctx.events.lastOrNull()?.let { it.verb == "cast" && it.player != who } != true) {
            val other = ctx.other(who) ?: "me"
            if (ctx.events.lastOrNull()?.verb in setOf("cast", "activate", "trigger")) ctx.events += EventSpec("resolveAll")
            ctx.events += EventSpec("cast", player = other, card = CardRef(name = "a spell")); targets = listOf("a_spell:spell")
            ctx.notes += "${card.display} needs a spell to counter and none was mentioned; assuming it targets a spell of ${if (other == "me") "yours" else (ctx.players[other] ?: "your opponent") + "'s"}."
        }
        // "Healing Salve gaining 3 life": the life is theirs, so the spell targets its caster.
        if (targets.isEmpty() && modeWord == "gain" && Regex("""\bgaining \d+ life\b""").containsMatchIn(rest)) targets = listOf(who)
        // "Prey Upon on my Bears targeting theirs": the fight's other creature.
        secondTarget(rest, targets.firstOrNull(), ctx)?.let { second -> if (targets.size == 1 && second !in targets) targets = targets + second }
        ctx.castingCounter = false; ctx.castingCounterName = null
        if (payLife != null) { ctx.payLife = payLife; ctx.notes += "${card.display}: ${if (who == "me") "you pay" else "they pay"} $payLife life as it is cast, so X is $payLife." }
        repeat(n) { i -> ctx.events += EventSpec("cast", player = who, card = CardRef(name = card.display, oracleId = card.oracleId), targets = targets, modes = modes, to = if (copyOf != null) "copy:$copyOf" else if (flashedBack) "flashback" else if (overload) "overload" else if (kicked) "kicked" else if (evoked) "evoke" else if (revolt != null) "revolt" else if (mastery != null) "spellmastery" else namedCard?.let { "name:$it" } ?: modeWord?.let { "mode:" + List(modeRepeat) { _ -> it }.joinToString("|") }, amount = xValue, payLife = ctx.payLife); if (secondTime != null && i == 0) ctx.events += EventSpec("resolveAll") }
        ctx.payLife = null
        if (n > 1) ctx.notes += "${card.display} is cast $n times, one copy after another (each is its own spell)." 
        ctx.lastActor = who
        ctx.lastVerb = "cast"
        ctx.castCards += card.display
        ctx.lastMentioned = "cast:" + slug(card.display)
        ctx.lastCastEntry = card
    }

    /**
     * "I cast Chandra and use her +1", "I cast Grizzly Bears and attack with it": the next clause acts on a
     * permanent that is still a spell on the stack. Give that spell an object so the clause has something to
     * name, and let the stack resolve first — a permanent spell has to resolve before anyone can do anything
     * with the permanent. Returns the object's id, or null if the last thing cast wasn't a permanent spell.
     */
    private fun castPermanentObject(ctx: Ctx): String? {
        val entry = ctx.lastCastEntry ?: return null
        if (entry.isSpellOnly) return null
        val lm = ctx.lastMentioned ?: return null
        if (lm != "cast:" + slug(entry.display)) return null
        val at = ctx.events.indexOfLast { it.verb == "cast" && it.obj == null && it.card?.oracleId == entry.oracleId }
        if (at < 0) return null
        val ev = ctx.events[at]
        val id = addObject(entry, ev.player ?: "me", false, ctx, zone = "hand", allowDuplicate = true)
        ctx.events[at] = ev.copy(obj = id, card = null)
        if (ctx.events.getOrNull(at + 1)?.verb != "resolveAll") ctx.events.add(at + 1, EventSpec("resolveAll"))
        ctx.notes += "${entry.display} has to resolve before anything can be done with it, so the stack is read as resolving first."
        ctx.lastCastEntry = null
        ctx.lastMentioned = id
        return id
    }

    /** "targeting the C2 trigger", "on my C3", "at me", "targeting it". */
    /** "on my Bears targeting theirs": the second phrase names a creature of the other player's, matched by the first's name. */
    private fun secondTarget(rest: String, firstId: String?, ctx: Ctx): String? {
        if (firstId == null || firstId !in ctx.objects) return null
        if (!Regex("""\b(?:targeting|at|against|to fight|fighting|to bite|biting|and) (?:their|theirs|his|her|my opponent's|the opponent's|opponent's)\b""").containsMatchIn(rest)) return null
        val mine = ctx.objects.getValue(firstId)
        return ctx.objects.values.lastOrNull { it.id != firstId && it.controller != mine.controller && isCreatureName(it.card.name) }?.id
    }

    private fun targetsIn(rest: String, m: Marked, ctx: Ctx): List<String> {
        val r = rest.trim().replace(Regex("""\s+(?:again|once more|a second time|for a second time)$"""), "")
        if (r.isEmpty()) return emptyList()
        val seg = Regex("""^(?:targeting|targets?|aimed at|on|at|to|into)\s+(.*)$""").find(r)?.groupValues?.get(1)
            ?: Regex("""^(?:my own|their own)\s+(.*)$""").find(r)?.groupValues?.get(1)
            ?: r.takeIf { Regex("""^(it|that|them|me|the|my|their|c\d+|@\w+)\b""").containsMatchIn(it) }
            // "cast Cryptic Command choosing mode 2 targeting it": the target is named after the mode, so it isn't
            // at the front of what's left; without this the spell was cast with no target at all.
            ?: Regex("""\b(?:targeting|aimed at)\s+(.+)$""").find(r)?.groupValues?.get(1)
            ?: return emptyList()
        val out = mutableListOf<String>()
        // "targeting Grizzly Bears & Hill Giant": a spell that divides its damage has more than one target.
        Regex("""^(.+?)\s*(?:&|,)\s*(.+)$""").find(seg)?.let { r ->
            if (!Regex("""c\d+""").containsMatchIn(r.groupValues[1]) || !Regex("""c\d+""").containsMatchIn(r.groupValues[2])) return@let
            val a = targetsIn("targeting " + r.groupValues[1].trim(), m, ctx)
            val b = targetsIn("targeting " + r.groupValues[2].trim(), m, ctx)
            if (a.isNotEmpty() && b.isNotEmpty()) { out += a; out += b; return out }
        }
        // "my opponent's token" / "their 2/2 token": a described token.
        Regex("""^(?:my opponent's |their |the opponent's |opponent's |my )?((?:\d+/\d+ )?(?:[a-z]+ )*?token)$""").find(seg)?.let { r ->
            val owner = if (seg.startsWith("my ") && !seg.startsWith("my opponent")) "me" else pronounPlayer(ctx, "their")
            val name = r.groupValues[1].let { if (Regex("""^\d+/\d+""").containsMatchIn(it) || it != "token") it else "1/1 creature token" }
            var id = slug(name); var k = 2; while (ctx.objects.containsKey(id)) id = slug(name) + "_" + (k++)
            ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = owner, token = true); if (name == "1/1 creature token") ctx.notes += "The token's stats weren't given; assuming a 1/1 creature token."
            out += id; return out
        }
        // "the wall" / "the 0/4" / "the zombie": a described creature, by a word of its description.
        Regex("""^(?:the |my |their |that |my opponent's |the opponent's )?(\d+/\d+|[a-z]+)$""").find(seg)?.let { r ->
            val w = r.groupValues[1].removeSuffix("s")
            if (w in setOf("it", "them", "me", "that", "opponent", "face")) return@let
            ctx.objects.values.lastOrNull { o -> (o.card.name ?: "").let { n -> n.startsWith("a ") && Regex("""\b${Regex.escape(w)}s?\b""").containsMatchIn(n) } }?.let { o -> out += o.id; return out }
        }
        // "a 4/4" / "their 2/2 flier": a described creature nobody put on the battlefield yet.
        Regex("""^(?:(my|their|his|her|my opponent's|the opponent's|opponent's|an?|the) )?(\d+/\d+)((?: [a-z]+)*)$""").find(seg)?.let { r ->
            val pt = r.groupValues[2]
            val extra = r.groupValues[3].trim()
            val head = r.groupValues[1]
            val owner = when (head) { "my" -> "me"; "their", "his", "her", "my opponent's", "the opponent's", "opponent's" -> pronounPlayer(ctx, "their"); else -> pronounPlayer(ctx, "their") }
            val kw = Regex("""$kwNouns""").find(extra)?.value?.let { k -> k.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample") } ?: ""
            val kind = Regex("""$creatureKinds""").find(extra)?.value ?: ""
            ctx.objects.values.lastOrNull { o -> o.controller == owner && (o.card.name ?: "").startsWith("a $pt") }?.let { o -> out += o.id; return out }
            val ids = describedCreatures("a ", pt, kind, owner, ctx, kw)
            if (ids.isNotEmpty()) { out += ids.first(); ctx.note(owner); return out }
        }
        // "on my own land", "on my opponent's creature": a permanent nobody named, given only by its type. The
        // target was dropped, so the spell had none and the answer was "nothing changes".
        Regex("""^(?:(my own|my|their|his|her|my opponent's|the opponent's|opponent's|an?|the) )?(creatures?|lands?|artifacts?|enchantments?|permanents?|planeswalkers?|guys?|dudes?)$""").find(seg)?.let { r ->
            val head = r.groupValues[1]
            val owner = when (head) { "my", "my own" -> "me"; "their", "his", "her", "my opponent's", "the opponent's", "opponent's" -> pronounPlayer(ctx, "their"); else -> pronounPlayer(ctx, "their") }
            val kind = r.groupValues[2].removeSuffix("s")
            ctx.objects.values.lastOrNull { o -> o.controller == owner && o.zone == "battlefield" &&
                (if (kind in setOf("creature", "guy", "dude")) isCreatureName(o.card.name) else (o.card.name ?: "").contains(kind, true)) }?.let { o -> out += o.id; return out }
            if (kind in setOf("creature", "guy", "dude")) describedCreatures("a ", "", "creature", owner, ctx).firstOrNull()?.let { out += it; ctx.note(owner); return out }
            var id = slug("a $kind"); var k = 2; while (ctx.objects.containsKey(id)) id = slug("a $kind") + "_" + (k++)
            ctx.objects[id] = ObjectSpec(id, CardRef(name = if (kind == "land") "a basic land" else "a $kind"), controller = owner)
            ctx.notes += "\"$seg\" names no card, so an unnamed $kind of ${if (owner == "me") "yours" else "theirs"} is taken as read; name it for a precise answer."
            out += id; ctx.note(owner); return out
        }
        // "a creature with protection from red" / "their creature with flying": a target described by what it
        // has rather than by its size. Without this the target was dropped and the spell hit a player instead.
        Regex("""^(?:(my|their|his|her|my opponent's|the opponent's|opponent's|an?|the) )?($creatureKinds) with ([a-z][a-z ,]*)$""").find(seg)?.let { r ->
            val owner = when (r.groupValues[1]) { "my" -> "me"; "their", "his", "her", "my opponent's", "the opponent's", "opponent's" -> pronounPlayer(ctx, "their"); else -> pronounPlayer(ctx, "their") }
            val kws = r.groupValues[3].trim()
            ctx.objects.values.lastOrNull { o -> o.controller == owner && (o.card.name ?: "").contains(kws, true) }?.let { o -> out += o.id; return out }
            val ids = describedCreatures("a ", "", if (r.groupValues[2] == "creature") "" else r.groupValues[2], owner, ctx, kws)
            if (ids.isNotEmpty()) { out += ids.first(); ctx.note(owner); return out }
        }
        // "me", "my face", "them" leading the phrase win over a card named later ("at my face with Guttersnipe out").
        if (Regex("""^(me|my face|myself)\b""").containsMatchIn(seg)) { out += "me"; ctx.usesMe = true; return out }
        if (Regex("""^(them|their face|my opponent|the opponent|opponent|opp|him|her)\b""").containsMatchIn(seg) && !Regex("""^(?:my opponent's|the opponent's|opponent's|their)\b""").containsMatchIn(seg)) {
            val p0 = pronounPlayer(ctx, Regex("""^(\w+)""").find(seg)!!.groupValues[1])
            // "Bob casts Lightning Bolt at her": with players named, the pronoun is somebody other than the one
            // acting. Read as the last player who acted it was Bob aiming at himself.
            val named = ctx.players.keys.filter { it != "me" && it != "opp" }
            val acting = ctx.clauseActor ?: ctx.lastActor
            out += if (named.isNotEmpty() && p0 == acting) (named.lastOrNull { it != acting } ?: p0) else p0
            return out
        }
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
            if (card.display in ctx.castCards) {
                val castIndex = ctx.events.indexOfLast { it.verb == "cast" && it.card?.name == card.display }
                val resolved = castIndex >= 0 && ctx.events.drop(castIndex + 1).any { it.verb == "resolveAll" }
                out += if (resolved && !card.isSpellOnly) slug(card.display) else slug(card.display) + ":spell"; return out
            }
            // "Counterspell on my opponent's Wrath of God": an instant or sorcery nobody said was cast is on the stack, cast by the other player.
            // "Counterspell on my Grizzly Bears": a permanent card a counter is aimed at was cast too (it's a spell until it resolves).
            // A blast's target said with a possessive ("their Grizzly Bears") is a permanent, not a spell being cast.
            val blastAtPermanent = ctx.castingCounterName in modalBlasts && !card.isSpellOnly &&
                Regex("""\b(?:my opponent's|the opponent's|opponent's|their|his|her|my|@\w+'s)\b""").containsMatchIn(seg)
            if ((card.isSpellOnly || ctx.castingCounter) && !blastAtPermanent) {
                val caster = when {
                    Regex("""\b(?:my opponent's|the opponent's|opponent's|their|his|her)\b""").containsMatchIn(seg) -> pronounPlayer(ctx, "their")
                    Regex("""@(\w+)'s""").containsMatchIn(seg) -> Regex("""@(\w+)'s""").find(seg)!!.groupValues[1].also { ctx.players.putIfAbsent(it, m.players[it] ?: it) }
                    seg.contains("my ") -> "me"
                    else -> ctx.other(ctx.clauseActor ?: ctx.lastActor) ?: "opp"
                }
                val needsSpell = Regex("""(?i)\bcounter target\b""").containsMatchIn(card.typeLine) || card.display.contains("Counter", true) || card.display in setOf("Negate", "Mana Drain", "Force of Will", "Swan Song", "Dovin's Veto", "Arcane Denial", "Mana Leak", "Dispel", "Miscast", "Spell Pierce", "Force of Negation", "Fierce Guardianship", "Mystical Dispute", "Memory Lapse", "Remand", "Daze", "Stubborn Denial")
                val victim = if (needsSpell && ctx.events.none { it.verb == "cast" && it.player != caster }) { val other = ctx.other(caster) ?: "me"; ctx.events += EventSpec("cast", player = other, card = CardRef(name = "a spell")); ctx.notes += "${card.display} needs a spell to counter and none was mentioned; assuming it targets a spell of ${if (other == "me") "yours" else (ctx.players[other] ?: "your opponent") + "'s"}."; "a_spell:spell" } else null
                ctx.events += EventSpec("cast", player = caster, card = CardRef(name = card.display, oracleId = card.oracleId), targets = listOfNotNull(victim)); ctx.castCards += card.display; ctx.note(caster)
                ctx.notes += "${card.display} wasn't mentioned before; reading it as cast by ${when (caster) { "me" -> "you"; "opp" -> "your opponent"; else -> ctx.players[caster] ?: caster }} and still on the stack."
                out += slug(card.display) + ":spell"; return out
            }
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
            val lm = ctx.lastMentioned
            // The spell itself is only "it" while it's still on the stack (nothing has resolved since it was cast).
            val spellStillThere = lm != null && lm.startsWith("cast:") && ctx.events.lastOrNull()?.let { it.verb == "cast" && slug(it.card?.name ?: "") == lm.removePrefix("cast:") } == true
            if (lm != null) { if (!lm.startsWith("cast:")) candidates += lm else if (spellStillThere) candidates += lm.removePrefix("cast:") + ":spell" }
            if (lm?.startsWith("cast:") == true) {
                val casts = ctx.events.filter { it.verb == "cast" }
                val last = casts.lastOrNull { slug(it.card?.name ?: "") == lm.removePrefix("cast:") } ?: casts.lastOrNull()
                val own = last?.targets?.filter { it in ctx.objects } ?: emptyList()
                // A response cast with no stated target ("I respond with Giant Growth") was about whatever the answered spell targeted.
                candidates += own.ifEmpty { casts.dropLast(1).lastOrNull { c -> c.targets.any { it in ctx.objects } }?.targets?.filter { it in ctx.objects } ?: emptyList() }
            }
            // After a block, "it" may be the blocker or the creature it blocks.
            ctx.events.lastOrNull()?.takeIf { it.verb == "block" }?.let { b -> candidates += listOfNotNull(b.obj) + b.targets.filter { it in ctx.objects } }
            if (candidates.isNotEmpty()) { out += candidates.joinToString("|"); return out }
        }
        if (Regex("""^(me|my face|myself)\b""").containsMatchIn(seg)) { out += "me"; ctx.usesMe = true; return out }
        Regex("""^@(\w+)(?!'s)\b""").find(seg)?.let { r -> val id = r.groupValues[1]; ctx.players.putIfAbsent(id, m.players[id] ?: id); ctx.note(id); out += id; return out }
        if (Regex("""^(them|their face|my opponent|the opponent|opponent|opp|him|her)\b""").containsMatchIn(seg)) { out += pronounPlayer(ctx, Regex("""^(\w+)""").find(seg)!!.groupValues[1]); return out }
        return out
    }

    /** Counts written as words, as whole words and case-insensitively; "one" is deliberately not among them. */
    private val wordCounts = Regex("""\b(?:zero|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\b""", RegexOption.IGNORE_CASE)
    private val numberWords = mapOf("zero" to 0, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20)

    /** Verbs that are also card names ("Protect", "Bloodrush"); right after "to" they are verbs. */
    private val verbsAfterTo = setOf("protect", "save", "shield", "defend", "keep", "destroy", "kill", "draw", "search", "block", "attack",
        "sacrifice", "regenerate", "bounce", "exile", "tap", "untap", "pay", "cast", "play", "target", "fight", "counter", "discard", "mill", "scry", "activate", "stop", "answer", "remove", "trigger")
    /** Verbs that are also card names and are followed by a particle ("I fire off its ability", "I turn on my Mutavault"). */
    private val verbsBeforeParticle = setOf("fire", "fires", "fired", "turn", "turns", "turned", "set", "sets", "pop", "pops", "popped", "cash", "crack", "cracks")
    /** Verbs that are also card names and are followed by "for" ("I tutor for Lightning Bolt"). */
    private val verbsBeforeFor = setOf("tutor", "tutors", "search", "searches", "dig", "digs", "fetch", "fetches", "look", "looks", "pay", "pays", "swing", "swings")
    /** Verbs that are also card names and take an object ("can they redirect it?", "they remove my Bears"). */
    private val verbsBeforeAnObject = setOf("reanimate", "reanimates", "reanimated", "redirect", "redirects", "reflect", "reflects", "deflect", "deflects", "steal", "steals",
        "swap", "swaps", "remove", "removes", "nuke", "nukes", "ping", "pings", "zap", "zaps", "answer", "answers", "shrink", "shrinks",
        "wipe", "wipes", "burn", "burns", "burned")
    /** Colour words that are also the start of card names ("Black Knight"); after "protection from" they are colours. */
    private val colorWords = setOf("white", "blue", "black", "red", "green")
    /** Nouns a colour word describes ("a black creature"), as opposed to naming a card that begins with that colour. */
    private val colorNouns = setOf("creature", "creatures", "permanent", "permanents", "card", "cards", "spell", "spells",
        "source", "sources", "token", "tokens", "land", "lands", "artifact", "artifacts", "enchantment", "enchantments",
        "planeswalker", "planeswalkers", "instant", "instants", "sorcery", "sorceries", "mana", "damage", "one", "ones")
    /** Keyword words that are also card names ("Lifelink", "Flying"); in a keyword list they mean the keyword. */
    private val keywordWords = setOf("flying", "trample", "deathtouch", "lifelink", "haste", "vigilance", "reach", "menace", "hexproof", "indestructible", "infect", "defender", "flash", "shroud", "intimidate", "fear", "wither", "changeling", "banding", "horsemanship", "shadow", "persist", "undying", "exalted", "prowess")
    /** Keywords an asker can state on a permanent ("has flying", "with protection from black"). */
    private val kwPhrase = """(?:hexproof|indestructible|flying|trample|lifelink|deathtouch|haste|vigilance|reach|menace|shroud|unblockable|first strike|double strike|infect|wither|defender|flash|regenerate|regeneration|prowess|exalted|persist|undying|protection from \w+)"""
    /** Keywords an asker can put in front of the kind: "a 2/2 indestructible creature", "my flying blocker". */
    private val keywordAdjectives = setOf("flying", "trample", "deathtouch", "lifelink", "haste", "vigilance", "reach", "menace",
        "hexproof", "indestructible", "infect", "wither", "defender", "shroud", "unblockable", "first strike", "double strike")
    private val kwNouns = """(?:fliers?|flyers?|flying|tramplers?|trample|deathtouchers?|deathtouch|lifelinkers?|lifelink|first strikers?|first strike|double strikers?|double strike|haste|vigilance|reach|menace|hexproof|indestructible|infect)"""
    private val creatureKinds = """(?:creatures?|walls?|goblins?|elves|elf|zombies?|soldiers?|spirits?|angels?|dragons?|humans?|vampires?|beasts?|birds?|cats?|dogs?|wolves|wolf|knights?|warriors?|wizards?|merfolk|dinosaurs?|hydras?|demons?|elementals?|insects?|rats?|snakes?|thopters?|servos?|saprolings?|squirrels?|bears?|giants?|orcs?|slivers?|faeries|faerie|treefolk|horrors?|constructs?|golems?)"""
    private val singularKind = mapOf("elves" to "elf", "wolves" to "wolf", "faeries" to "faerie")

    /** "a 3/3", "two 2/2 goblins", "3 other goblins", "a 4/4 flier": unnamed creatures with the stats given (else 1/1, and said so). */
    private fun describedCreatures(count: String, pt: String, kindWord: String, who: String, ctx: Ctx, keywords0: String = ""): List<String> {
        val keywords = keywords0.replace(" & ", ", ")
        val n = count.trim().let { if (it.isEmpty()) 1 else number(it) ?: 1 }
        val words = kindWord.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val kind = words.joinToString("") { w -> val k = singularKind[w] ?: w.removeSuffix("s"); if (k == "creature" || k == "flying") "" else "$k " }
        val extraKw = if ("flying" in words && "flying" !in keywords) (if (keywords.isEmpty()) "flying" else "$keywords, flying") else keywords
        val described = (if (pt.isNotEmpty()) "$pt " else "") + kind + "creature" + (if (extraKw.isNotEmpty()) " with $extraKw" else "")
        val article = if (described.first().lowercaseChar() in "aeiou") "an " else "a "
        val name = article + described
        // "the 2/2" / "my 3/3": one already described, not a new one.
        if (count.trim() in setOf("the", "my", "their")) {
            ctx.objects.values.lastOrNull { o -> o.controller == who && (o.card.name ?: "").startsWith(article + (if (pt.isNotEmpty()) "$pt " else "")) }?.let { o ->
                ctx.lastMentioned = o.id; return listOf(o.id)
            }
        }
        val ids = (1..n).map { var id = slug(described); var k = 2; while (ctx.objects.containsKey(id)) id = slug(described) + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = who); id }
        if (pt.isEmpty()) ctx.notes += "$n unnamed ${kind}creature${if (n > 1) "s" else ""} assumed to be vanilla 1/1s; name them for a precise answer."
        ctx.lastMentioned = ids.last()
        return ids
    }

    /** "a 1/1 Soldier token", "two Treasure tokens": described tokens. */
    private fun describedTokens(count: String, pt: String, kindWord: String, who: String, ctx: Ctx, keywords0: String = ""): List<String> {
        val keywords = keywords0.replace(" & ", ", ")
        val n = count.trim().let { if (it.isEmpty()) 1 else number(it) ?: 1 }
        val kind = kindWord.trim().lowercase().let { singularKind[it] ?: it.removeSuffix("s") }.let { if (it == "creature" || it.isEmpty()) "" else "$it " }
        val name = (if (pt.isNotEmpty()) "$pt " else "") + kind + (if (pt.isEmpty() && kind.isEmpty()) "1/1 creature " else if (pt.isNotEmpty()) "creature " else "") + "token" + (if (keywords.isNotEmpty()) " with $keywords" else "")
        val ids = (1..n).map { var id = slug(name); var k = 2; while (ctx.objects.containsKey(id)) id = slug(name) + "_" + (k++); ctx.objects[id] = ObjectSpec(id, CardRef(name = name), controller = who, token = true); id }
        ctx.lastMentioned = ids.last()
        return ids
    }

    /** Whether a situation card name is a creature: a described one ("a 3/3 creature", "a 2/2 token") or a database card with Creature in its type line. */
    private fun isCreatureName(name: String?): Boolean = name != null && (Regex("""\b\d+/\d+\b|\bcreature\b""").containsMatchIn(name) || names.lookup(Names.normalize(name))?.typeLine?.contains("Creature") == true)

    /**
     * "a 5/5 trampler", "a 2/2 Goblin with lifelink": a creature given by its size and words rather than its name.
     * [trailer] is whatever followed the size; the keywords and the creature kind are picked out of it.
     */
    private fun describedFrom(count: String, pt: String, trailer: String, who: String, ctx: Ctx): List<String> {
        val t = trailer.trim()
        val kws = Regex("""$kwNouns|flying|trample|deathtouch|lifelink|first strike|double strike|menace|vigilance|indestructible|infect|wither|reach|defender""").findAll(t)
            .map { k -> k.value.removeSuffix("s").replace("flier", "flying").replace("flyer", "flying").replace("trampler", "trample") }.distinct().joinToString(", ")
        val kind = Regex("""$creatureKinds""").find(t)?.value ?: "creature"
        return describedCreatures(count.ifEmpty { "a " }, pt, kind, who, ctx, kws)
    }

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
        // "a Llanowar Elves with summoning sickness" / "that I just played this turn": the asker said it can't tap yet.
        if (Regex("""\b(?:with summoning sickness|summoning sick|that (?:i|they|he|she) just (?:played|cast)(?: this turn)?|(?:i|they) just (?:played|cast) (?:it|this)(?: this turn)?|played this turn|cast this turn)\b""").containsMatchIn(rest)) spec = spec.copy(summoningSick = true)
        if (Regex("""\b(?:without summoning sickness|not summoning sick|has been out|since before this turn|from last turn)\b""").containsMatchIn(rest)) spec = spec.copy(summoningSick = false)
        val counters = spec.counters.toMutableMap()
        Regex("""(?:with|at|has|having) (\d+|\w+) loyalty(?: counters?)?""").find(rest)?.let { r -> number(r.groupValues[1])?.let { counters["loyalty"] = it } }
        Regex("""(?:with|has|having) (?:(\d+|\w+) )?([+-]\d+/[+-]\d+|[a-z]+) counters?(?: on it)?""").findAll(rest).forEach { r ->
            val n = number(r.groupValues[1].ifEmpty { "one" }) ?: return@forEach
            val kind = r.groupValues[2]
            if (kind == "loyalty") counters["loyalty"] = n else counters[kind] = (counters[kind] ?: 0) + n
        }
        Regex("""(?:with|has|having|and) (\d+|\w+) damage(?: marked)?(?: on it)?""").find(rest)?.let { r -> number(r.groupValues[1])?.let { spec = spec.copy(damage = it); ctx.lastStat = "damage"; ctx.lastStatWho = "obj:$id" } }
        Regex("""(?:with |has |having |and )?(?:an? )?([+-]\d+/[+-]\d+) (?:pump|bonus|boost|until end of turn)""").find(rest)?.let { r -> spec = spec.copy(pump = r.groupValues[1]) }
        ctx.objects[id] = spec.copy(counters = counters)
    }

    private fun number(w: String): Int? = w.toIntOrNull() ?: numberWords[w] ?: when (w) { "a", "an", "one" -> 1; else -> null }

    /** "My opponent blocks with Serra Angel" with no attack described: the attack it answers is taken as read. */
    private fun ensureAttacker(ctx: Ctx, defender: String): EventSpec? {
        ctx.events.lastOrNull { it.verb == "attack" || it.verb == "attackAll" }?.let { return it }
        val attacker = ctx.other(defender) ?: return null
        // "I have a creature and they tap it. Can I block?": a block question with nothing attacking is about a
        // block, so an attacker nobody named is taken as read rather than the question going unanswered.
        val creature = ctx.objects.values.lastOrNull { it.controller == attacker && isCreatureName(it.card.name) }
            ?: describedCreatures("a ", "", "creature", attacker, ctx).firstOrNull()?.let { ctx.objects[it] } ?: return null
        val e = EventSpec("attack", player = attacker, obj = creature.id, targets = listOf(defender))
        ctx.events += e; ctx.note(defender)
        ctx.notes += "No attack was described, so ${creature.card.name} is read as attacking ${if (defender == "me") "you" else ctx.players[defender] ?: "your opponent"}; the block answers it."
        return e
    }

    /** "a Saproling token" / "a Soldier token": the size most printings of that creature token come with. */
    private fun tokenSize(name: String): String? {
        val word = name.removeSuffix(" token").trim().split(' ').lastOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if (word in setOf("creature", "artifact", "treasure", "clue", "food", "blood", "gold", "map", "powerstone", "incubator")) return null
        return names.tokenSizes[Names.normalize(word)]
    }

    private fun objectIdFor(card: NameIndex.Entry, ctx: Ctx): String? = ctx.objects.values.firstOrNull { it.card.oracleId == card.oracleId }?.id
    private fun other(p: String?) = when (p) { "me" -> "opp"; "opp" -> "me"; else -> null }
    private fun slug(name: String) = Names.normalize(name).replace(' ', '_')
}
