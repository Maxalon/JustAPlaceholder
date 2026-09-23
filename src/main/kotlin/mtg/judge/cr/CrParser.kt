package mtg.judge.cr

/** One numbered entry of the Comprehensive Rules. */
data class CrRule(
    val number: String,       // "1", "100", "100.1", "100.1a"
    val kind: Kind,
    val title: String?,       // sections and chapters only
    val text: String,
    val examples: List<String>,
    val ordinal: Int,
) {
    enum class Kind { SECTION, CHAPTER, RULE, SUBRULE }

    val chapter: Int get() = when (kind) {
        Kind.SECTION -> number.toInt() * 100
        else -> number.substringBefore('.').toInt()
    }
    val section: Int get() = when (kind) {
        Kind.SECTION -> number.toInt()
        else -> number.substringBefore('.').toInt() / 100
    }
    val parent: String? get() = when (kind) {
        Kind.SECTION -> null
        Kind.CHAPTER -> section.toString()
        Kind.RULE -> chapter.toString()
        Kind.SUBRULE -> number.dropLast(1)
    }
}

data class GlossaryEntry(val term: String, val definition: String)

data class ComprehensiveRules(
    val effectiveDate: String?,
    val rules: List<CrRule>,
    val glossary: List<GlossaryEntry>,
) {
    val byNumber: Map<String, CrRule> by lazy { rules.associateBy { it.number } }
}

/**
 * Parses the plain-text Comprehensive Rules file that Wizards publishes.
 *
 * Layout of that file: a preamble and table of contents, then the body (sections
 * "1. Game Concepts", chapters "100. General", rules "100.1. …", subrules "100.1a …",
 * and "Example: …" lines attached to the rule above), then "Glossary" with entries
 * separated by blank lines, then "Credits".
 */
object CrParser {
    private val sectionRe = Regex("""^(\d)\. (.+)$""")
    private val chapterRe = Regex("""^(\d{3})\. (.+)$""")
    private val ruleRe = Regex("""^(\d{3}\.\d+)\. (.*)$""")
    private val subruleRe = Regex("""^(\d{3}\.\d+[a-z]) (.*)$""")
    private val exampleRe = Regex("""^Example: (.*)$""")
    private val effectiveRe = Regex("""effective as of ([A-Za-z]+ \d{1,2}, \d{4})""")
    val refRe = Regex("""rule (\d{3}(?:\.\d+[a-z]?)?)""")

    fun parse(text: String): ComprehensiveRules {
        val lines = text.removePrefix("﻿").lines().map { it.trimEnd() }
        val effective = lines.take(10).firstNotNullOfOrNull { effectiveRe.find(it)?.groupValues?.get(1) }

        // The table of contents ends at the first "Credits" line; the body starts right after it.
        val tocCredits = lines.indexOfFirst { it.trim() == "Credits" }
        val bodyStart = if (tocCredits >= 0) tocCredits + 1 else 0
        val glossaryIdx = lines.withIndex().drop(bodyStart).firstOrNull { it.value.trim() == "Glossary" }?.index
            ?: lines.size
        val creditsIdx = lines.withIndex().drop(glossaryIdx + 1).firstOrNull { it.value.trim() == "Credits" }?.index
            ?: lines.size

        val rules = parseBody(lines.subList(bodyStart, glossaryIdx))
        val glossary = parseGlossary(lines.subList(minOf(glossaryIdx + 1, lines.size), creditsIdx))
        return ComprehensiveRules(effective, rules, glossary)
    }

    private class Building(val number: String, val kind: CrRule.Kind, val title: String?, var text: String) {
        val examples = mutableListOf<String>()
    }

    private fun parseBody(lines: List<String>): List<CrRule> {
        val out = mutableListOf<CrRule>()
        var current: Building? = null
        var ordinal = 0
        fun flush() {
            current?.let { out += CrRule(it.number, it.kind, it.title, it.text.trim(), it.examples.toList(), ordinal++) }
            current = null
        }
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            subruleRe.matchEntire(line)?.let { m ->
                flush(); current = Building(m.groupValues[1], CrRule.Kind.SUBRULE, null, m.groupValues[2]); return@let
            } ?: ruleRe.matchEntire(line)?.let { m ->
                flush(); current = Building(m.groupValues[1], CrRule.Kind.RULE, null, m.groupValues[2])
            } ?: chapterRe.matchEntire(line)?.let { m ->
                flush(); current = Building(m.groupValues[1], CrRule.Kind.CHAPTER, m.groupValues[2], m.groupValues[2])
            } ?: sectionRe.matchEntire(line)?.let { m ->
                flush(); current = Building(m.groupValues[1], CrRule.Kind.SECTION, m.groupValues[2], m.groupValues[2])
            } ?: exampleRe.matchEntire(line)?.let { m ->
                current?.examples?.add(m.groupValues[1])
            } ?: run {
                // Continuation of the previous entry (rare: wrapped paragraphs).
                current?.let { it.text = it.text + "\n" + line }
            }
        }
        flush()
        return out
    }

    private fun parseGlossary(lines: List<String>): List<GlossaryEntry> {
        val out = mutableListOf<GlossaryEntry>()
        var block = mutableListOf<String>()
        fun flush() {
            if (block.size >= 2) out += GlossaryEntry(block.first().trim(), block.drop(1).joinToString("\n").trim())
            block = mutableListOf()
        }
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) flush() else block += line
        }
        flush()
        return out
    }

    /** Rule numbers referenced from a piece of text ("See rule 614.1a" -> 614.1a). */
    fun references(text: String): List<String> = refRe.findAll(text).map { it.groupValues[1] }.distinct().toList()
}
