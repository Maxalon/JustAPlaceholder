package mtg.judge.carddb.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.zip.GZIPInputStream
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Opens the files published on the `data` branch. A file may be present whole, or split
 * into `NAME.part00`, `NAME.part01`, … which are concatenated transparently. Compression is
 * chosen by extension (.gz, .xz).
 */
object Sources {
    val json = Json { ignoreUnknownKeys = true }

    fun exists(dir: Path, name: String): Boolean =
        dir.resolve(name).exists() || parts(dir, name).isNotEmpty()

    private fun parts(dir: Path, name: String): List<Path> =
        if (!dir.exists()) emptyList()
        else dir.listDirectoryEntries("$name.part*").sortedBy { it.name }

    /** Raw bytes as published (still compressed if the name says so). */
    fun openRaw(dir: Path, name: String): InputStream {
        val whole = dir.resolve(name)
        if (whole.exists()) return BufferedInputStream(Files.newInputStream(whole), 1 shl 16)
        val ps = parts(dir, name)
        require(ps.isNotEmpty()) { "Missing data file $name in $dir" }
        return SequenceInputStream(Collections.enumeration(ps.map { Files.newInputStream(it) }))
    }

    /** Decompressed bytes. */
    fun open(dir: Path, name: String): InputStream {
        val raw = openRaw(dir, name)
        return when {
            name.endsWith(".gz") -> GZIPInputStream(raw, 1 shl 16)
            name.endsWith(".xz") -> XZInputStream(raw)
            else -> raw
        }
    }

    fun readText(dir: Path, name: String): String =
        open(dir, name).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Streams a JSON Lines file, one object per line. */
    fun forEachJsonLine(dir: Path, name: String, block: (JsonObject) -> Unit) {
        BufferedReader(InputStreamReader(open(dir, name), Charsets.UTF_8), 1 shl 20).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                block(json.parseToJsonElement(line).jsonObject)
            }
        }
    }
}
