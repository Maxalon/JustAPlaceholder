package mtg.judge.carddb

import kotlin.test.Test
import kotlin.test.assertEquals

class NamesTest {
    @Test
    fun `normalizes diacritics ligatures punctuation and case`() {
        assertEquals("lim dul s vault", Names.normalize("Lim-Dûl's Vault"))
        assertEquals("aether vial", Names.normalize("Æther Vial"))
        assertEquals("fire ice", Names.normalize("Fire // Ice"))
        assertEquals("jotun grunt", Names.normalize("Jötun Grunt"))
        assertEquals("ach hans run", Names.normalize("\"Ach! Hans, Run!\""))
        assertEquals("sol ring", Names.normalize("  SOL   ring "))
    }

    @Test
    fun `splits face names`() {
        assertEquals(listOf("Fire", "Ice"), Names.faceNames("Fire // Ice"))
        assertEquals(listOf("Sol Ring"), Names.faceNames("Sol Ring"))
    }

    @Test
    fun `edit distance counts transpositions as one`() {
        assertEquals(1, CardRepo.damerauLevenshtein("rhystic", "rhystci"))
        assertEquals(0, CardRepo.damerauLevenshtein("a", "a"))
        assertEquals(3, CardRepo.damerauLevenshtein("", "abc"))
    }
}
