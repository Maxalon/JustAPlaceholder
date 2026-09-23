package mtg.judge.situation

import mtg.judge.carddb.CardRepo
import mtg.judge.carddb.Db
import mtg.judge.cr.RulesRepo
import mtg.judge.nl.NameIndex
import mtg.judge.nl.SituationParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A triggered ability whose trigger condition the Oracle parser couldn't read can never fire. Until this was
 * reported, Kavu Predator ("whenever an opponent gains life, put that many +1/+1 counters on it") sat on the
 * battlefield while the answer said only "opponent gains 3 life" — no counters, and nothing to say they were
 * missing. Only abilities the parser dropped whole were surfaced; a half-read one was invisible.
 * Needs the full database (MTG_JUDGE_DB); skipped otherwise.
 */
class UnreadableTriggerTest {
    @Test
    fun `a trigger the parser could not read is reported rather than silently dropped`() {
        val dbPath = System.getenv("MTG_JUDGE_DB") ?: run { println("MTG_JUDGE_DB not set; skipping"); return }
        Db.open(File(dbPath).toPath(), readOnly = true).use { conn ->
            val parsed = SituationParser(NameIndex.load(conn)).parse("I control Kavu Predator and they gain 3 life.")
            val answer = Judge(CardRepo(conn), RulesRepo(conn)).answer(parsed.situation)
            assertTrue(
                answer.unsupported.any { it.contains("Kavu Predator") },
                "Kavu Predator's unreadable trigger went unreported; unsupported was ${answer.unsupported}",
            )
        }
    }
}
