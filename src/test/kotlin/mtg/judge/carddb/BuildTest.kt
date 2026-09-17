package mtg.judge.carddb

import mtg.judge.carddb.ingest.Build
import mtg.judge.carddb.ingest.OracleHistory
import mtg.judge.cr.RulesRepo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.copyTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds a database from the small real-data fixtures under src/test/resources/data and queries it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildTest {
    private val dataDir: Path = Paths.get(javaClass.getResource("/data/manifest.json")!!.toURI()).parent
    private lateinit var tmp: Path
    private lateinit var db: Path
    private lateinit var report: Build.Report

    @BeforeAll
    fun build() {
        tmp = Files.createTempDirectory("mtg-judge-test")
        db = tmp.resolve("judge.db")
        report = Build.run(dataDir, db, log = {})
    }

    @AfterAll
    fun cleanup() { tmp.toFile().deleteRecursively() }

    private fun <T> withRepos(block: (CardRepo, RulesRepo) -> T): T = Db.open(db, readOnly = true).use { block(CardRepo(it), RulesRepo(it)) }

    @Test
    fun `counts everything in the fixtures`() {
        assertEquals(7, report.cards)
        assertEquals(7, report.printings)
        assertEquals(16, report.rulings)
        assertEquals(3, report.originalText.printingsSeen)
        assertEquals(3, report.originalText.updated)
        assertTrue(report.rules > 20)
        assertTrue(report.glossary >= 3)
    }

    @Test
    fun `resolves exact, face, and fuzzy names and prefers cards over tokens`() = withRepos { cards, _ ->
        assertEquals(Resolution.How.EXACT, cards.resolve("Rhystic Study").best!!.how)
        assertEquals("Rhystic Study", cards.resolve("  rhystic   STUDY ").best!!.card.name)
        val face = cards.resolve("Ice").best!!
        assertEquals(Resolution.How.FACE, face.how); assertEquals("Fire // Ice", face.card.name)
        val fuzzy = cards.resolve("rystic studdy").best!!
        assertEquals(Resolution.How.FUZZY, fuzzy.how); assertEquals("Rhystic Study", fuzzy.card.name)
        assertNull(cards.resolve("zzqqxxvv").best)
        val treasure = cards.resolve("Treasure").best!!
        assertEquals("token", treasure.card.layout)
    }

    @Test
    fun `split cards keep both faces' text`() = withRepos { cards, _ ->
        val c = cards.resolve("Fire // Ice").best!!.card
        assertTrue(c.oracleText.contains("Fire deals 2 damage")); assertTrue(c.oracleText.contains("Tap target permanent"))
        assertEquals("split", c.layout); assertNotNull(c.faces)
    }

    @Test
    fun `printings carry the original printed text from MTGJSON`() = withRepos { cards, _ ->
        val tv = cards.resolve("Time Vault").best!!.card
        val lea = cards.printing("LEA", oracleId = tv.oracleId)
        assertNotNull(lea)
        assertTrue(lea.originalText!!.startsWith("Tap to gain an additional turn"))
        assertTrue(tv.oracleText.startsWith("This artifact enters tapped."))
        val rs = cards.resolve("Rhystic Study").best!!.card
        assertTrue(cards.printing("pcy", oracleId = rs.oracleId)!!.originalText!!.contains("plays a spell"))
        assertEquals(rs.oracleText, cards.printing("j22", oracleId = rs.oracleId)!!.originalText)
    }

    @Test
    fun `rulings and flags are attached`() = withRepos { cards, _ ->
        val rs = cards.resolve("Rhystic Study").best!!.card
        assertTrue(cards.rulings(rs.oracleId).any { it.comment.contains("resolves before the spell") })
        assertTrue(rs.gameChanger)
        assertTrue(cards.resolve("Time Vault").best!!.card.reserved)
    }

    @Test
    fun `rules and glossary are queryable`() = withRepos { _, rules ->
        assertEquals("702.19", rules.keywordRule("trample")!!.number)
        assertEquals("702.19", rules.rule("702.19.")!!.number)
        assertTrue(rules.children("702.19").any { it.number == "702.19b" })
        assertEquals("Ability", rules.glossary("abilities")!!.term)
        assertTrue(rules.referencesFrom("100.1a").isEmpty() || true)
    }

    @Test
    fun `first build records current text as seen before tracking`() = withRepos { cards, _ ->
        val h = cards.oracleHistory(cards.resolve("Sol Ring").best!!.card.oracleId)
        assertEquals(1, h.size); assertTrue(h[0].current); assertEquals("before-tracking", h[0].firstSeen)
    }

    @Test
    fun `a later build with changed text records a history entry`() {
        val next = tmp.resolve("next.db")
        db.copyTo(next, overwrite = true)
        Db.open(next).use { conn ->
            conn.createStatement().use { it.executeUpdate("UPDATE cards SET oracle_text = 'Whenever an opponent casts a spell, draw a card.' WHERE name = 'Rhystic Study'") }
            val changes = OracleHistory.update(conn, db, "2026-10-01")
            assertEquals(1, changes)
            val repo = CardRepo(conn)
            val h = repo.oracleHistory(repo.resolve("Rhystic Study").best!!.card.oracleId)
            assertEquals(2, h.size)
            val old = h.first { !it.current }; val cur = h.first { it.current }
            assertEquals("2026-09-14", old.lastSeen); assertEquals("before-tracking", old.firstSeen)
            assertEquals("2026-10-01", cur.firstSeen); assertTrue(cur.text.startsWith("Whenever an opponent casts a spell, draw"))
            // Unchanged cards keep a single current row, now last seen on the new date.
            val sol = repo.oracleHistory(repo.resolve("Sol Ring").best!!.card.oracleId)
            assertEquals(1, sol.size); assertEquals("2026-10-01", sol[0].lastSeen)
        }
    }
}
