package mtg.judge.situation

import kotlinx.serialization.json.Json
import mtg.judge.carddb.CardRepo
import mtg.judge.carddb.Db
import mtg.judge.carddb.ingest.Build
import mtg.judge.cr.RulesRepo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JudgeTest {
    private lateinit var tmp: Path
    private lateinit var db: Path
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll fun build() {
        tmp = Files.createTempDirectory("mtg-judge-sit"); db = tmp.resolve("judge.db")
        Build.run(Paths.get(javaClass.getResource("/data/manifest.json")!!.toURI()).parent, db, log = {})
    }
    @AfterAll fun cleanup() { tmp.toFile().deleteRecursively() }

    private fun judge(text: String): Answer = Db.open(db, readOnly = true).use { Judge(CardRepo(it), RulesRepo(it)).answer(json.decodeFromString<Situation>(text)) }

    @Test
    fun `rhystic study and stifle from the situation language`() {
        val a = judge("""
            {"players":[{"id":"me","life":40},{"id":"opp","name":"opponent","life":40}],
             "turn":{"activePlayer":"opp"},
             "objects":[{"id":"rhystic","card":"Rhystic Study","controller":"me"}],
             "events":[{"verb":"cast","player":"opp","card":"Sol Ring"},
                       {"verb":"cast","player":"opp","card":"Stifle","targets":["rhystic:trigger"]},
                       {"verb":"resolveAll"}]}
        """)
        assertTrue(a.outcome.any { it.contains("countered") })
        assertTrue(a.outcome.any { it.contains("draws 1 card") }, "Stifle itself triggers Rhystic Study: ${a.outcome}")
        assertTrue(a.outcome.any { it.contains("Sol Ring enters the battlefield") })
        assertTrue(a.trace.any { "701.6a" in it.rules })
        assertTrue(a.understood.any { it.startsWith("Event 2: opponent casts Stifle targeting Rhystic Study's triggered ability") })
        assertTrue(a.unsupported.isEmpty(), a.unsupported.toString())
    }

    @Test
    fun `card given as an object with oracle id`() {
        val a = judge("""{"objects":[{"id":"tv","card":{"name":"Time Vault"},"controller":"me","tapped":true}],"events":[]}""")
        assertTrue(a.understood.any { it.contains("Time Vault [tv]") && it.contains("tapped") })
        assertEquals(listOf("Nothing changes."), a.outcome)
    }

    @Test
    fun `unknown card is reported, not guessed`() {
        val a = judge("""{"objects":[{"id":"x","card":"Zzyzx Unicorn of Nowhere","controller":"me"}]}""")
        assertTrue(a.unsupported.any { it.contains("Unknown card") })
    }
}
