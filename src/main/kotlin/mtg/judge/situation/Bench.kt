package mtg.judge.situation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mtg.judge.carddb.CardRepo
import mtg.judge.cr.RulesRepo
import mtg.judge.nl.NameIndex
import mtg.judge.nl.SituationParser
import java.sql.Connection

@Serializable
data class Scenario(val id: String, val text: String, val expect: List<String> = emptyList(), val notExpect: List<String> = emptyList(), val cite: List<String> = emptyList(), val note: String? = null)

/**
 * Our own measurement: plain-English scenarios with expected outcomes and citations.
 * A scenario is "answered" when every expectation is met and nothing forbidden appears,
 * "refused" when the engine reported something unsupported or asked a question instead,
 * and "wrong" when it answered confidently but missed an expectation.
 */
object Bench {
    enum class Verdict { ANSWERED, REFUSED, WRONG }
    data class Result(val scenario: Scenario, val verdict: Verdict, val details: List<String>, val answer: Answer, val unread: List<String>)

    fun load(): List<Scenario> = Json { ignoreUnknownKeys = true }.decodeFromString(javaClass.getResource("/bench/scenarios.json")!!.readText())

    fun run(conn: Connection, scenarios: List<Scenario> = load()): List<Result> {
        val cards = CardRepo(conn); val rules = RulesRepo(conn)
        val parser = SituationParser(NameIndex.load(conn)); val judge = Judge(cards, rules)
        return scenarios.map { sc ->
            val parsed = parser.parse(sc.text)
            val a = judge.answer(parsed.situation)
            val hay = (a.outcome + a.trace.map { it.text } + a.understood).joinToString("\n")
            val cited = a.trace.flatMap { it.rules }.toSet()
            val problems = mutableListOf<String>()
            sc.expect.filter { !hay.contains(it, ignoreCase = true) }.forEach { problems += "missing: \"$it\"" }
            sc.notExpect.filter { hay.contains(it, ignoreCase = true) }.forEach { problems += "should not appear: \"$it\"" }
            sc.cite.filter { it !in cited }.forEach { problems += "not cited: $it" }
            val refused = a.unsupported.isNotEmpty() || a.clarifications.isNotEmpty() || parsed.unread.isNotEmpty()
            val verdict = when { problems.isEmpty() -> Verdict.ANSWERED; refused -> Verdict.REFUSED; else -> Verdict.WRONG }
            Result(sc, verdict, problems + a.unsupported.map { "unsupported: $it" } + a.clarifications.map { "asked: $it" } + parsed.unread.map { "unread: $it" }, a, parsed.unread)
        }
    }

    fun report(results: List<Result>): String = buildString {
        val n = results.size
        val counts = Verdict.values().associateWith { v -> results.count { it.verdict == v } }
        appendLine("Scenarios: $n   answered ${counts[Verdict.ANSWERED]} (${pct(counts[Verdict.ANSWERED]!!, n)})   refused ${counts[Verdict.REFUSED]} (${pct(counts[Verdict.REFUSED]!!, n)})   wrong ${counts[Verdict.WRONG]} (${pct(counts[Verdict.WRONG]!!, n)})")
        for (r in results) if (r.verdict != Verdict.ANSWERED) {
            appendLine("  ${r.verdict.name.lowercase().padEnd(8)} ${r.scenario.id}")
            r.details.forEach { appendLine("           - $it") }
        }
    }
    private fun pct(a: Int, b: Int) = if (b == 0) "0%" else "%.0f%%".format(100.0 * a / b)
}
