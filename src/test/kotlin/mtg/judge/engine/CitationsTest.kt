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

    /**
     * The rules database is rebuilt daily from the live Comprehensive Rules, and Wizards renumbers rules
     * between releases. A citation that still exists but now points at a different rule is worse than a
     * missing one: it reads as authoritative and isn't. Each cited number is pinned to the opening words
     * of the rule it was checked against, so a renumbering (or a rewording worth re-reading) fails here
     * instead of shipping a confidently wrong citation.
     */
    @Test
    fun `each cited rule still says what it said when the citation was written`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping citation drift check"); return }
        val anchors = javaClass.getResourceAsStream("/citation-anchors.tsv")?.bufferedReader()?.readLines()
            ?: error("citation-anchors.tsv is missing")
        val drifted = mutableListOf<String>()
        Db.open(File(dbPath).toPath(), readOnly = true).use { conn ->
            conn.prepareStatement("SELECT text FROM rules WHERE number = ?").use { ps ->
                for (line in anchors) {
                    if (line.isBlank()) continue
                    val (number, expected) = line.split('\t', limit = 2)
                    ps.setString(1, number)
                    val text = ps.executeQuery().use { if (it.next()) it.getString(1) else null }
                    if (text == null) { drifted += "$number: no longer in the rules"; continue }
                    val opening = Regex("""[A-Za-z0-9]+""").findAll(text).take(6).joinToString(" ") { it.value.lowercase() }
                    if (opening != expected) drifted += "$number: was \"$expected\", now \"$opening\""
                }
            }
        }
        assertTrue(drifted.isEmpty(), "Cited rules whose text moved or changed — re-read them before trusting the citation:\n" + drifted.joinToString("\n"))
    }
}
