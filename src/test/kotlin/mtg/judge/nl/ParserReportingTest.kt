package mtg.judge.nl

import mtg.judge.carddb.Db
import mtg.judge.carddb.ingest.Build
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The front door's contract is that a clause it cannot read is handed back rather than dropped. A compound
 * clause combines its halves with "or", so an unreadable half used to be hidden by a readable one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParserReportingTest {
    private lateinit var tmp: Path
    private lateinit var parser: SituationParser

    @BeforeAll fun setUp() {
        tmp = Files.createTempDirectory("mtg-judge-reporting")
        val db = tmp.resolve("judge.db")
        Build.run(Paths.get(javaClass.getResource("/data/manifest.json")!!.toURI()).parent, db, log = {})
        parser = Db.open(db, readOnly = true).use { SituationParser(NameIndex.load(it)) }
    }
    @AfterAll fun tearDown() { tmp.toFile().deleteRecursively() }

    @Test
    fun `an unreadable half of a compound clause is still reported`() {
        val parsed = parser.parse("I cast Sol Ring while they control the flerbs.")
        assertTrue(parsed.unread.any { it.contains("flerb") }, "unread was ${parsed.unread}")
    }

    @Test
    fun `an unreadable half does not stop the readable half being acted on`() {
        val parsed = parser.parse("I cast Sol Ring while they control the flerbs.")
        assertTrue(parsed.situation.events.any { it.verb == "cast" }, "events were ${parsed.situation.events}")
    }
}
