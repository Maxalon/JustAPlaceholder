package mtg.judge.cr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrParserTest {
    private val text = javaClass.getResource("/cr-excerpt.txt")!!.readText()
    private val cr = CrParser.parse(text)

    @Test
    fun `reads the effective date`() = assertEquals("August 7, 2026", cr.effectiveDate)

    @Test
    fun `skips the table of contents and finds sections chapters rules and subrules`() {
        val s1 = cr.byNumber["1"]; assertNotNull(s1); assertEquals(CrRule.Kind.SECTION, s1.kind); assertEquals("Game Concepts", s1.title)
        val c100 = cr.byNumber["100"]; assertNotNull(c100); assertEquals(CrRule.Kind.CHAPTER, c100.kind); assertEquals("1", c100.parent)
        val r = cr.byNumber["100.1"]; assertNotNull(r); assertEquals(CrRule.Kind.RULE, r.kind); assertEquals("100", r.parent)
        assertTrue(r.text.startsWith("These Magic rules apply"))
        val sub = cr.byNumber["100.1a"]; assertNotNull(sub); assertEquals(CrRule.Kind.SUBRULE, sub.kind); assertEquals("100.1", sub.parent)
        // Only one entry per number: the TOC copy of "100. General" must not be counted twice.
        assertEquals(1, cr.rules.count { it.number == "100" })
    }

    @Test
    fun `attaches examples to the preceding rule`() {
        val trample = cr.byNumber["702.19b"]
        assertNotNull(trample)
        assertTrue(trample.examples.isNotEmpty(), "702.19b has an example in the rules text")
        assertTrue(trample.examples.first().contains("trample"))
    }

    @Test
    fun `derives chapter and section numbers`() {
        val sub = cr.byNumber["702.19b"]!!
        assertEquals(702, sub.chapter); assertEquals(7, sub.section)
    }

    @Test
    fun `parses glossary entries separated by blank lines`() {
        val ability = cr.glossary.first { it.term == "Ability" }
        assertTrue(ability.definition.startsWith("1. Text on an object"))
        assertTrue(ability.definition.contains("See rule 113"))
        assertTrue(cr.glossary.any { it.term == "Absorb" })
    }

    @Test
    fun `extracts cross references`() {
        assertEquals(listOf("113"), CrParser.references("See rule 113, “Abilities,” and section 6"))
        assertEquals(listOf("614.1a", "702.19"), CrParser.references("see rule 614.1a and rule 702.19 and rule 614.1a"))
    }
}
