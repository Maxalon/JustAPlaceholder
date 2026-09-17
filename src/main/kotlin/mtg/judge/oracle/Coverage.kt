package mtg.judge.oracle

import mtg.judge.carddb.Card
import mtg.judge.carddb.CardRepo
import mtg.judge.engine.ActivatedAbility
import mtg.judge.engine.Effect
import mtg.judge.engine.StaticAbility
import mtg.judge.engine.Trigger
import mtg.judge.engine.TriggeredAbility
import mtg.judge.engine.UnparsedAbility
import mtg.judge.situation.Judge
import java.sql.Connection

/**
 * Measures how much of the card pool the Oracle parser turns into modeled abilities, and lists the
 * most common unparsed sentences so template work targets what actually appears on cards.
 */
object Coverage {
    /** Keywords the engine gives rules meaning to. Others parse as static abilities the engine ignores. */
    val modeledKeywords = setOf("flying", "reach", "trample", "first strike", "double strike", "deathtouch", "lifelink", "vigilance", "haste", "menace",
        "indestructible", "hexproof", "shroud", "defender", "flash", "protection", "ward", "enchant",
        // cited with their rule when the spell is cast, or converted to abilities by the parser
        "kicker", "flashback", "madness", "convoke", "affinity", "suspend", "morph", "improvise", "cumulative upkeep", "devoid", "changeling", "partner", "evoke", "echo", "foretell", "infect", "wither", "fear", "intimidate", "horsemanship", "shadow", "skulk",
        "cascade", "bestow", "disguise", "escape", "mutate", "companion", "split second", "buyback", "overload", "surge", "emerge", "spectacle", "jump-start", "retrace", "delve", "prototype", "casualty", "offspring", "gift", "impending", "harmonize", "level up", "cycling", "equip", "prowess", "unearth", "crew",
        "toxic", "exalted", "landwalk", "swampwalk", "islandwalk", "forestwalk", "mountainwalk", "plainswalk",
        // Triggered keywords the engine plays out in full.
        "undying", "persist", "living weapon", "bushido", "storm")

    data class Stats(var cards: Int = 0, var fully: Int = 0, var partly: Int = 0, var none: Int = 0, var noText: Int = 0, var abilities: Int = 0, var modeledAbilities: Int = 0)

    fun report(conn: Connection, format: String, top: Int, words: Int = 7) {
        val stats = Stats()
        val patterns = HashMap<String, Int>()
        val examples = HashMap<String, String>()
        val sql = "SELECT * FROM cards WHERE layout NOT IN ('token','double_faced_token','emblem','art_series') AND json_extract(legalities, '$.$format') IN ('legal','restricted')"
        conn.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) {
                val card = Card(rs.getString("oracle_id"), rs.getString("name"), rs.getString("layout"), rs.getString("mana_cost"), rs.getDouble("mana_value"),
                    rs.getString("type_line"), rs.getString("oracle_text"), rs.getString("colors"), rs.getString("color_identity"), rs.getString("power"), rs.getString("toughness"),
                    rs.getString("loyalty"), rs.getString("defense"), rs.getString("keywords"), rs.getString("legalities"), rs.getInt("reserved") == 1, rs.getInt("game_changer") == 1, rs.getString("faces"))
                val def = Judge.toDef(card)
                stats.cards++
                val units = mutableListOf<Pair<Boolean, String?>>()   // (modeled, unparsed text)
                for (a in def.abilities) when (a) {
                    is StaticAbility -> if (a.keyword != null) units += (a.keyword in modeledKeywords) to (if (a.keyword in modeledKeywords) null else "keyword: ${a.keyword}")
                                        else units += a.effects.isNotEmpty() to (if (a.effects.isNotEmpty()) null else a.text)
                    is TriggeredAbility -> { val ok = a.trigger !is Trigger.Unknown && !a.effect.hasUnparsed(); units += ok to (if (ok) null else (if (a.trigger is Trigger.Unknown) "trigger: " + (a.trigger as Trigger.Unknown).text else unparsed(a.effect))) }
                    is ActivatedAbility -> { val ok = !a.effect.hasUnparsed(); units += ok to (if (ok) null else unparsed(a.effect)) }
                    is UnparsedAbility -> units += false to a.text
                }
                def.spellEffect?.let { units += !it.hasUnparsed() to (if (it.hasUnparsed()) unparsed(it) else null) }
                if (units.isEmpty()) { stats.noText++; continue }
                stats.abilities += units.size; stats.modeledAbilities += units.count { it.first }
                when (units.count { it.first }) { units.size -> stats.fully++; 0 -> stats.none++; else -> stats.partly++ }
                for ((ok, text) in units) if (!ok && text != null) { val key = shape(text, words); patterns[key] = (patterns[key] ?: 0) + 1; examples.putIfAbsent(key, "${card.name}: $text") }
            }
        }
        val pct = { a: Int, b: Int -> if (b == 0) "0%" else "%.1f%%".format(100.0 * a / b) }
        println("Cards ($format-legal, non-token): ${stats.cards}")
        println("  fully modeled:   ${stats.fully} (${pct(stats.fully, stats.cards)})")
        println("  partly modeled:  ${stats.partly} (${pct(stats.partly, stats.cards)})")
        println("  nothing modeled: ${stats.none} (${pct(stats.none, stats.cards)})")
        println("  no rules text:   ${stats.noText}")
        println("Ability units modeled: ${stats.modeledAbilities} / ${stats.abilities} (${pct(stats.modeledAbilities, stats.abilities)})")
        println()
        println("Most common unmodeled shapes (numbers -> N, self -> ~):")
        patterns.entries.sortedByDescending { it.value }.take(top).forEach { (k, n) -> println("  %5d  %s".format(n, k) + "\n         e.g. " + examples[k]!!.take(140)) }
    }

    private fun unparsed(e: Effect): String = when (e) {
        is Effect.Unparsed -> e.text; is Effect.May -> unparsed(e.effect); is Effect.UnlessPays -> unparsed(e.effect)
        is Effect.Seq -> e.effects.firstOrNull { it.hasUnparsed() }?.let { unparsed(it) } ?: ""
        is Effect.IfYouDo -> if (e.choice.hasUnparsed()) unparsed(e.choice) else unparsed(e.then)
        is Effect.IfCondition -> unparsed(e.then)
        is Effect.Modal -> e.modes.firstOrNull { it.hasUnparsed() }?.let { unparsed(it) } ?: ""
        else -> ""
    }

    /** Collapse a sentence to its first words with numbers and names abstracted, for counting. */
    private fun shape(text: String, words: Int): String {
        val t = text.replace(Regex("""\{[^}]*\}"""), "{M}").replace(Regex("""\b\d+\b"""), "N").replace(Regex("""\b(one|two|three|four|five|six|seven)\b""", RegexOption.IGNORE_CASE), "N")
        val ws = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return ws.take(words).joinToString(" ") + if (ws.size > words) " …" else ""
    }
}
