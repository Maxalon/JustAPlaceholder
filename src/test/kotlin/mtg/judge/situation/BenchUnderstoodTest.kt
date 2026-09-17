package mtg.judge.situation

import mtg.judge.carddb.Db
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A scenario counts as ANSWERED as soon as its expectations hold, even if the parser skipped part of the
 * question — the "refused" verdict is only reached once an expectation has already failed. So a scenario can
 * pass while the answer was built from less than it was asked. The corpus is meant to be fully understood,
 * so nothing in it may leave a clause unread. Needs the full database (MTG_JUDGE_DB); skipped otherwise.
 */
class BenchUnderstoodTest {
    @Test
    fun `no bench scenario leaves part of its question unread`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        val results = Db.open(File(dbPath).toPath(), readOnly = true).use { Bench.run(it) }
        val partial = results.filter { it.unread.isNotEmpty() }
        assertTrue(partial.isEmpty(), "Scenarios whose text is only partly read:\n" +
            partial.joinToString("\n") { "  ${it.scenario.id}: ${it.unread.joinToString("; ")}" })
    }
}
