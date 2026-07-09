package eu.anifantakis.commercials.migration

import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * Parsing for the SEN (Oracle ERP) table exports that fill the migration's
 * known gap: the legacy MySQL never held customers/contracts master data
 * (Oracle-mastered), so the migration faked them deterministically. The
 * SEN exports supply the real rows:
 *
 *  - `lee.csv` - entities (LEEID, LEENAME, LEEAFM, ...): names + VAT
 *  - `cus.csv` - trader accounts (TRAID, LEEID, TRACODE, ...): the payer
 *    accounts; TRAID is the id series MySQL knew as `docref.traid`/
 *    `messages.cusID`
 *  - `sld.csv` - sales documents (DOCID, DOTID, DOCNUMBER, dates, ...):
 *    contracts; DOCID is the id MySQL shadows as `docref.docid` (verified:
 *    docno/dotid/traid all agree on the common rows)
 *
 * Export format quirks this parser absorbs:
 *  - tab-delimited, CRLF, ISO-8859-7 (or already-converted UTF-8)
 *  - text fields may contain EMBEDDED NEWLINES (addresses), splitting one
 *    record across physical lines -> records are reassembled by field count
 *  - a header row may or may not be present -> detected, with a caller-
 *    supplied fallback header for headerless files
 */
class SenTable(
    val columns: Map<String, Int>,
    val rows: List<List<String>>,
    /** Records dropped because a text cell contained literal tabs (field count overshot). */
    val rejected: Int = 0,
) {
    /** The value of [column] on [row], trimmed; "" when the column is absent. */
    fun value(row: List<String>, column: String): String {
        val i = columns[column] ?: return ""
        return row.getOrNull(i)?.trim().orEmpty()
    }
}

object SenExports {

    private val iso88597: Charset = Charset.forName("ISO-8859-7")

    /** A header cell is an ALL-CAPS identifier like DOCID / SYS_LUPD / TDOQTYA. */
    private val headerCell = Regex("^[A-Z][A-Z0-9_]*$")

    /**
     * Parses one export. [fallbackHeader] names the columns of a headerless
     * file (e.g. read from the sidecar `<table>.headers.txt`); when the file's
     * first line IS a header it wins and the fallback is ignored.
     *
     * The optional [keyColumn]/[keys] filter is the MySQL-FIRST rule made
     * concrete: the caller reads what it needs from the migrated schema and
     * keeps ONLY the export rows whose key matches - the rest of the ERP is
     * never materialized.
     */
    fun parse(
        file: File,
        fallbackHeader: List<String>? = null,
        keyColumn: String? = null,
        keys: Set<String>? = null,
    ): SenTable {
        val text = decode(file.readBytes())
        val lines = text.split("\n").map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }
        require(lines.isNotEmpty()) { "${file.name}: empty export" }

        val firstFields = lines.first().split('\t')
        val hasHeader = firstFields.all { headerCell.matches(it.trim()) }
        val header = when {
            hasHeader -> firstFields.map { it.trim() }
            fallbackHeader != null -> fallbackHeader
            else -> error(
                "${file.name}: no header row and no fallback header supplied " +
                    "(first line starts '${lines.first().take(60)}...')"
            )
        }

        val (rows, rejected) = reassemble(if (hasHeader) lines.drop(1) else lines, header.size, file.name)
        val keyIdx = keyColumn?.let { header.indexOf(it) }
        val kept = if (keyIdx != null && keyIdx >= 0 && keys != null) {
            rows.filter { it[keyIdx].trim() in keys }
        } else {
            require(keyColumn == null || (keyIdx != null && keyIdx >= 0)) {
                "${file.name}: filter column '$keyColumn' not in the header"
            }
            rows
        }
        return SenTable(header.withIndex().associate { (i, name) -> name to i }, kept, rejected)
    }

    /** UTF-8 when the bytes decode cleanly as such, else ISO-8859-7 (Greek). */
    private fun decode(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bytes, iso88597)
    }

    /**
     * Joins physical lines into logical records of exactly [targetFields]
     * fields. A record continued on the next line lost one field boundary to
     * the embedded newline, so joining adds `fields - 1`; the newline itself
     * becomes a single space (it was inside a text cell).
     *
     * A record that OVERSHOOTS the target had literal tabs typed inside a
     * text cell (seen in the wild: one doc comment in 122k lines) - its field
     * positions are unknowable, so it is dropped and counted. Anything beyond
     * a trace of such rows (>0.5%) means the header is wrong for this file,
     * and THAT is a loud failure.
     */
    private fun reassemble(
        lines: List<String>,
        targetFields: Int,
        name: String,
    ): Pair<List<List<String>>, Int> {
        val out = ArrayList<List<String>>(lines.size)
        var rejected = 0
        var buf: StringBuilder? = null
        var have = 0
        for (line in lines) {
            val n = 1 + line.count { it == '\t' }
            if (buf == null) {
                buf = StringBuilder(line); have = n
            } else {
                buf.append(' ').append(line); have += n - 1
            }
            if (have >= targetFields) {
                if (have == targetFields) out += buf.split('\t') else rejected++
                buf = null
            }
        }
        check(buf == null) { "$name: trailing partial record of $have fields (expected $targetFields)" }
        val total = out.size + rejected
        check(rejected <= total * 5 / 1000) {
            "$name: $rejected of $total records overshot $targetFields fields - wrong header for this file?"
        }
        return out to rejected
    }

    /**
     * Uppercases for Greek MATCHING: `String.uppercase()` keeps accents
     * ("Δώρα" -> "ΔΏΡΑ"), so a plain contains("ΔΩΡ") misses - Greek
     * typographic convention drops accents in capitals. Strips them.
     */
    fun greekUpper(value: String): String = buildString(value.length) {
        for (ch in value.uppercase()) append(
            when (ch) {
                'Ά' -> 'Α'; 'Έ' -> 'Ε'; 'Ή' -> 'Η'; 'Ί' -> 'Ι'; 'Ϊ' -> 'Ι'
                'Ό' -> 'Ο'; 'Ύ' -> 'Υ'; 'Ϋ' -> 'Υ'; 'Ώ' -> 'Ω'
                else -> ch
            }
        )
    }

    private val greekMonths = mapOf(
        "Ιαν" to 1, "Φεβ" to 2, "Μαρ" to 3, "Απρ" to 4,
        "Μαϊ" to 5, "Μαι" to 5, "Ιουν" to 6, "Ιουλ" to 7, "Αυγ" to 8,
        "Σεπ" to 9, "Οκτ" to 10, "Νοε" to 11, "Δεκ" to 12,
    )

    /**
     * Parses the exports' Greek date format `d-MMM-yyyy` (e.g. `1-Οκτ-2025`,
     * `24-Δεκ-2020 15:40:22` - any time-of-day part is ignored). Returns null
     * for blank/unparseable values - callers treat that as "no date".
     */
    fun parseDate(value: String): LocalDate? {
        val token = value.trim().substringBefore(' ')
        if (token.isEmpty()) return null
        val parts = token.split('-')
        if (parts.size != 3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = greekMonths[parts[1]] ?: return null
        val year = parts[2].toIntOrNull() ?: return null
        return try {
            LocalDate.of(year, month, day)
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}
