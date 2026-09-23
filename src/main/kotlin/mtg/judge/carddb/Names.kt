package mtg.judge.carddb

import java.text.Normalizer

/** Card and glossary name normalization: the single key used for every lookup. */
object Names {
    private val ligatures = mapOf('Æ' to "AE", 'æ' to "ae", 'Œ' to "OE", 'œ' to "oe", 'ß' to "ss", 'Ø' to "O", 'ø' to "o")
    private val combining = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9]+")

    /**
     * "Lim-Dûl's Vault" -> "lim dul s vault", "Æther Vial" -> "aether vial",
     * "Fire // Ice" -> "fire ice". Lowercase ASCII letters and digits separated by single spaces.
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) sb.append(ligatures[ch] ?: ch.toString())
        val decomposed = Normalizer.normalize(sb, Normalizer.Form.NFKD).replace(combining, "")
        return decomposed.lowercase().replace(nonAlnum, " ").trim()
    }

    /** Names of the individual faces of "A // B", or just [name] for single-faced cards. */
    fun faceNames(fullName: String): List<String> =
        fullName.split(" // ").map { it.trim() }.filter { it.isNotEmpty() }
}
