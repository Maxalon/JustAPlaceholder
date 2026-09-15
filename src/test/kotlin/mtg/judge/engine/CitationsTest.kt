package mtg.judge.engine

import mtg.judge.carddb.Db
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every rule number the engine and parser cite must exist in the Comprehensive Rules.
 * Runs against the full database named by MTG_JUDGE_DB (CI fetches the data branch); skipped otherwise.
 */
class CitationsTest {
    @Test
    fun `all cited rule numbers exist in the rules text`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping citation check"); return }
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").exists() } ?: File(".")
        val sources = File(root, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        val re = Regex(""""(\d{3}(?:\.\d+[a-z]?)?)"""")
        val cited = sources.flatMap { f -> re.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue(cited.size > 100, "expected many citations, found ${cited.size}")
        val missing = Db.open(File(dbPath).toPath(), readOnly = true).use { conn ->
            conn.prepareStatement("SELECT 1 FROM rules WHERE number = ?").use { ps ->
                cited.filter { n -> ps.setString(1, n); ps.executeQuery().use { !it.next() } }
            }
        }
        assertTrue(missing.isEmpty(), "Cited rules that don't exist in the current Comprehensive Rules: $missing")
    }
}
