package mtg.judge.cli

import mtg.judge.carddb.CardRepo
import mtg.judge.carddb.Db
import mtg.judge.carddb.Resolution
import mtg.judge.carddb.ingest.Build
import mtg.judge.cr.RulesRepo
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val USAGE = """
mtg-judge <command> [options]

  build   --data DIR --out FILE [--previous FILE]   Build the database from a data-branch checkout
  card    NAME [--set CODE [--number N]] [--db FILE] Oracle text, rulings, printed-text warning
  rule    NUMBER|KEYWORD|TERM [--db FILE]            A rule with its subrules, or a glossary term
  search  TEXT [--db FILE]                           Full-text search over cards and rules
  resolve TEXT [--db FILE]                           Show how a name resolves
  meta    [--db FILE]                                Data provenance

The database defaults to ${'$'}MTG_JUDGE_DB or ./judge.db.
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) { println(USAGE.trim()); exitProcess(2) }
    val (positional, opts) = parseArgs(args.drop(1))
    when (args[0]) {
        "build" -> {
            val data = opts["data"] ?: fail("--data DIR is required")
            val out = opts["out"] ?: fail("--out FILE is required")
            val t0 = System.currentTimeMillis()
            val r = Build.run(Path(data), Path(out), opts["previous"]?.let { Path(it) })
            println("Built $out in ${(System.currentTimeMillis() - t0) / 1000}s: ${r.cards} cards, ${r.printings} printings, ${r.rulings} rulings, ${r.rules} rules, ${r.glossary} glossary entries")
        }
        "card" -> withDb(opts) { cards, rules -> showCard(cards, rules, positional.joinToString(" "), opts["set"], opts["number"]) }
        "rule" -> withDb(opts) { _, rules -> showRule(rules, positional.joinToString(" ")) }
        "search" -> withDb(opts) { cards, rules ->
            val q = positional.joinToString(" ")
            cards.search(q).forEach { println("card  ${it.name}  [${it.typeLine}]") }
            rules.search(q).forEach { println("rule  ${it.number}  ${it.text.take(110).replace('\n', ' ')}") }
            rules.searchGlossary(q).forEach { println("term  ${it.term}") }
        }
        "resolve" -> withDb(opts) { cards, _ ->
            val r = cards.resolve(positional.joinToString(" "))
            if (r.matches.isEmpty()) println("no match") else r.matches.forEach { println("${it.how.name.lowercase().padEnd(6)} ${"%.2f".format(it.score)}  ${it.card.name}  (matched \"${it.matchedName}\")") }
        }
        "meta" -> withDb(opts) { cards, _ -> cards.meta().toSortedMap().forEach { (k, v) -> println("$k = $v") } }
        else -> { println(USAGE.trim()); exitProcess(2) }
    }
}

private fun withDb(opts: Map<String, String>, block: (CardRepo, RulesRepo) -> Unit) {
    val path: Path = Path(opts["db"] ?: System.getenv("MTG_JUDGE_DB") ?: "judge.db")
    if (!path.exists()) fail("Database not found at $path. Build one with: mtg-judge build --data DIR --out $path")
    Db.open(path, readOnly = true).use { conn -> block(CardRepo(conn), RulesRepo(conn)) }
}

private fun showCard(cards: CardRepo, rules: RulesRepo, query: String, set: String?, number: String?) {
    val res = cards.resolve(query)
    val match = res.best ?: fail("No card matches \"$query\"")
    if (match.how == Resolution.How.FUZZY) println("(no exact match for \"$query\"; assuming ${match.card.name})")
    if (res.ambiguous) println("(ambiguous: ${res.matches.map { it.card.name }.distinct().joinToString(", ")}; showing the first)")
    val c = match.card
    println(c.name + (c.manaCost?.let { "  $it" } ?: ""))
    println(c.typeLine + listOfNotNull(c.power?.let { p -> "$p/${c.toughness}" }, c.loyalty?.let { "loyalty $it" }, c.defense?.let { "defense $it" }).joinToString(" ", prefix = "  "))
    println()
    println(c.oracleText.ifEmpty { "(no rules text)" })
    val flags = buildList { if (c.gameChanger) add("game changer"); if (c.reserved) add("reserved list") }
    if (flags.isNotEmpty()) println("\n[${flags.joinToString(", ")}]")

    // Printed-text warning, when the user names a printing or when any printing differs.
    val printings = cards.printings(c.oracleId)
    val chosen = if (set != null) cards.printing(set, number, c.oracleId) ?: run { println("\n(no printing of ${c.name} found in set $set${number?.let { " #$it" } ?: ""})"); null } else null
    if (chosen != null) {
        val printed = chosen.originalText ?: chosen.printedText
        println("\nPrinting: ${chosen.setName} (${chosen.setCode.uppercase()} #${chosen.collectorNumber}, ${chosen.releasedAt})")
        when {
            printed == null -> println("  (no printed text recorded for this printing)")
            textDiffers(printed, c.oracleText) -> { println("  WARNING: this printing's text is out of date. It reads:"); printed.lines().forEach { println("    $it") }; println("  The current Oracle text above is what applies.") }
            else -> println("  Printed text matches the current Oracle text.")
        }
    } else {
        val outdated = printings.filter { it.lang == "en" }.mapNotNull { p -> (p.originalText ?: p.printedText)?.let { p to it } }.filter { textDiffers(it.second, c.oracleText) }
        if (outdated.isNotEmpty()) println("\nNote: ${outdated.size} of ${printings.count { it.lang == "en" }} English printings carry older wording (e.g. ${outdated.first().first.setCode.uppercase()} ${outdated.first().first.releasedAt}). Use --set CODE to see one.")
    }

    val history = cards.oracleHistory(c.oracleId).filter { !it.current }
    if (history.isNotEmpty()) { println("\nOracle text changed ${history.size} time(s) since tracking began:"); history.forEach { println("  until ${it.lastSeen}: ${it.text.replace('\n', ' ').take(140)}") } }

    val rl = cards.rulings(c.oracleId)
    if (rl.isNotEmpty()) { println("\nRulings:"); rl.forEach { println("  ${it.publishedAt}  ${it.comment}") } }

    val keywords = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(c.keywords) }.getOrDefault(emptyList())
    val kwRules = keywords.mapNotNull { k -> rules.keywordRule(k)?.let { k to it.number } }
    if (kwRules.isNotEmpty()) println("\nKeyword rules: " + kwRules.joinToString(", ") { "${it.first} ${it.second}" })
}

private fun textDiffers(a: String, b: String): Boolean {
    fun canon(s: String) = s.lowercase().replace(Regex("[^a-z0-9{}/+\\-]+"), " ").trim()
    return canon(a) != canon(b)
}

private fun showRule(rules: RulesRepo, query: String) {
    val q = query.trim()
    val rule = rules.rule(q) ?: rules.keywordRule(q)
    if (rule != null) {
        println("${rule.number}${rule.title?.let { " $it" } ?: ""}${if (rule.title == null) "  " + rule.text else ""}")
        rule.examples.forEach { println("  Example: $it") }
        for (child in rules.children(rule.number)) {
            println("\n${child.number}${child.title?.let { " $it" } ?: "  " + child.text}")
            child.examples.forEach { println("  Example: $it") }
        }
        val refs = rules.referencesFrom(rule.number); if (refs.isNotEmpty()) println("\nSee also: ${refs.joinToString(", ")}")
        val back = rules.referencesTo(rule.number); if (back.isNotEmpty()) println("Referenced by: ${back.take(20).joinToString(", ")}${if (back.size > 20) " …" else ""}")
        return
    }
    val term = rules.glossary(q)
    if (term != null) { println("${term.term}\n${term.definition}"); return }
    val termHits = rules.searchGlossary(q, 3)
    if (termHits.isNotEmpty()) {
        println("Glossary: ${termHits.first().term}\n${termHits.first().definition}")
        if (termHits.size > 1) println("\nOther terms: ${termHits.drop(1).joinToString(", ") { it.term }}")
        return
    }
    val hits = rules.search(q)
    if (hits.isEmpty()) fail("No rule or glossary term matches \"$q\"")
    println("No exact rule or term; closest rules:")
    hits.forEach { println("  ${it.number}  ${it.text.take(120).replace('\n', ' ')}") }
}

private fun parseArgs(args: List<String>): Pair<List<String>, Map<String, String>> {
    val pos = mutableListOf<String>(); val opts = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) { opts[a.removePrefix("--")] = args.getOrNull(i + 1) ?: ""; i += 2 } else { pos += a; i++ }
    }
    return pos to opts
}

private fun fail(msg: String): Nothing { System.err.println(msg); exitProcess(1) }
