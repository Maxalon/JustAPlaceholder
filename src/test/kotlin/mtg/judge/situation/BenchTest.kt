package mtg.judge.situation

import mtg.judge.carddb.Db
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The plain-English scenario corpus must stay fully answered. Needs the full database (MTG_JUDGE_DB); skipped otherwise. */
class BenchTest {
    @Test
    fun `every bench scenario is answered correctly`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping bench"); return }
        val results = Db.open(File(dbPath).toPath(), readOnly = true).use { Bench.run(it) }
        val bad = results.filter { it.verdict != Bench.Verdict.ANSWERED }
        assertTrue(bad.isEmpty(), "\n" + Bench.report(results))
    }
}
