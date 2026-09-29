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
    fun `a Unicode line separator inside a rule is a line break, not the end of the rule`() {
        // The September 25, 2026 text joins 509.1b's second paragraph to its first with U+2028; read with "." and "$",
        // which stop at it, the whole line failed to be a subrule and was swallowed into 509.1a.
        val text = "Credits\n509.1a The defending player chooses which creatures they control, if any, will block.\n\u00a0\n" +
            "509.1b The defending player checks each creature they control for restrictions.\u2028\u00a0\u00a0\u00a0\u00a0 A restriction may be created by an evasion ability.\n" +
            "Example: An attacking creature with flying can\u2019t be blocked by a creature without flying.\n\u00a0\n" +
            "509.1c The defending player checks each creature they control for requirements.\nGlossary\nCredits\n"
        val parsed = CrParser.parse(text)
        val b = parsed.byNumber["509.1b"]; assertNotNull(b)
        assertTrue(b.text.startsWith("The defending player checks each creature they control for restrictions."))
        assertTrue(b.text.contains("A restriction may be created by an evasion ability."), b.text)
        assertEquals(1, b.examples.size)
        assertTrue(parsed.byNumber["509.1a"]!!.text.endsWith("will block."))
        assertNotNull(parsed.byNumber["509.1c"])
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
