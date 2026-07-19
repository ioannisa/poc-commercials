package eu.anifantakis.commercials.galaxy

import java.io.File
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Parsers for the Galaxy (SingularLogic) ERP exports - format contract
 * verified in GALAXY-MATCHER.md §1 (old raw export) and §9.1 (flat export).
 *
 * Two families share one tokenizer:
 *  - the FLAT export (`COMMERCIALENTRY.txt`, tab-delimited) and its CSV
 *    dictionaries (`CUSTOMER.csv` etc., semicolon-delimited), and
 *  - the OLD raw table export (`customer.txt`, `TRADER.txt`, ..., tab-delimited)
 *    which serves as the full party dictionary until the uncapped delivery.
 *
 * Cells may be CSV-style quoted, and a quoted cell can contain the delimiter,
 * doubled quotes (`""`) AND raw newlines - one real row splits into 31 columns
 * under naive tab-splitting. That is why this is a character-level state
 * machine and NOT the field-count reassembly used by migration/SenExports
 * (different quoting mechanism; the modules deliberately share no code).
 *
 * Records end on CRLF (bare LF tolerated) outside quotes; the literal string
 * `NULL` and blank cells both mean SQL NULL; money is Greek-locale
 * (`8.000,00`); dates are `dd/MM/yyyy`. The OLD export additionally uses a
 * ×10¹² integer money encoding (dots as thousands separators, no decimal
 * comma) handled by [scaled12].
 */
object GalaxyExports {

    private val windows1253: Charset = Charset.forName("windows-1253")
    private val flatDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** UTF-8 when the bytes decode cleanly as such, else windows-1253 (Greek). */
    private fun decode(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, windows1253)
    }

    /**
     * Parses a headed, delimited Galaxy export. The first record is the header.
     * Data records whose field count differs from the header's are dropped and
     * counted in [GalaxyTable.rejected] - with quote-aware tokenizing that
     * should be zero; anything else means the export format shifted.
     */
    fun parse(file: File, delimiter: Char): GalaxyTable {
        val records = tokenize(decode(file.readBytes()), delimiter)
        require(records.isNotEmpty()) { "${file.name}: empty export" }
        val header = records.first().map { it.trim() }
        val columns = header.withIndex().associate { (i, name) -> name to i }
        val rows = ArrayList<List<String>>(records.size - 1)
        var rejected = 0
        for (r in records.subList(1, records.size)) {
            if (r.size == header.size) rows += r else rejected++
        }
        return GalaxyTable(file.name, columns, rows, rejected)
    }

    /**
     * Character-level tokenizer: delimiter-separated fields, CSV-style quoting.
     * A field starting with `"` is quoted until its closing `"`; inside quotes
     * `""` is a literal quote and delimiter/CR/LF are plain content. Outside
     * quotes CRLF (or bare LF) ends the record.
     */
    internal fun tokenize(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val cur = StringBuilder()
        var fieldPending = false   // distinguishes a trailing empty field from "nothing after the last CRLF"
        var inQuotes = false
        var i = 0
        fun endField() {
            fields += cur.toString()
            cur.setLength(0)
            fieldPending = false
        }
        fun endRecord() {
            endField()
            records += fields
            fields = mutableListOf()
        }
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                when {
                    ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cur.append('"'); i += 2 }
                    ch == '"' -> { inQuotes = false; i++ }
                    else -> { cur.append(ch); i++ }
                }
                continue
            }
            when {
                ch == '"' && cur.isEmpty() && !fieldPending -> { inQuotes = true; fieldPending = true; i++ }
                ch == delimiter -> { endField(); i++ }
                ch == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> { endRecord(); i += 2 }
                ch == '\n' -> { endRecord(); i++ }
                else -> { cur.append(ch); fieldPending = true; i++ }
            }
        }
        if (cur.isNotEmpty() || fieldPending || fields.isNotEmpty()) endRecord()
        return records
    }

    /** Greek-locale money/quantity: `8.000,00` → 8000.00, `-25.000,00` → -25000.00. */
    fun greekMoney(value: String?): BigDecimal? {
        val v = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return runCatching { BigDecimal(v.replace(".", "").replace(',', '.')) }.getOrNull()
    }

    /** Flat-export date: `dd/MM/yyyy`. */
    fun flatDate(value: String?): LocalDate? {
        val v = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return runCatching { LocalDate.parse(v, flatDateFormat) }.getOrNull()
    }

    /**
     * OLD-export decimal(?,12): integers exported with DOTS AS THOUSANDS
     * separators and no decimal comma - `6.168.300.000.000.000` = 6,168.30.
     * Strip dots, divide by 10¹².
     */
    fun scaled12(value: String?): BigDecimal? {
        val digits = value?.trim()?.replace(".", "").takeUnless { it.isNullOrEmpty() } ?: return null
        return runCatching { BigDecimal(digits).movePointLeft(12).stripTrailingZeros() }.getOrNull()
    }

    /**
     * ΑΦΜ normalization: Galaxy TINs lose leading zeros (`97690560` is really
     * `097690560`) - keep digits and left-pad to 9. Longer values (foreign
     * VAT ids) pass through untouched; non-numeric junk returns null.
     */
    fun normalizedTin(value: String?): String? {
        val digits = value?.filter { it.isDigit() }.takeUnless { it.isNullOrEmpty() } ?: return null
        return if (digits.length <= 9) digits.padStart(9, '0') else digits
    }

    /**
     * Item-code bridge normalization: our `spot_types.item_code` says `Σ101`,
     * Galaxy says `73003` or `73.003` - digits are the shared spine.
     * Returns null when no digits survive.
     */
    fun itemDigits(value: String?): String? =
        value?.filter { it.isDigit() }.takeUnless { it.isNullOrEmpty() }

    /** Document numbers compare with leading zeros stripped (`000450` ≡ `450`). */
    fun normalizedDocNumber(value: String?): String? {
        val v = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return v.trimStart('0').ifEmpty { "0" }
    }
}

/**
 * A parsed export: header-name → column index, plus the data rows.
 * [value] returns null for missing columns, blank cells and the literal `NULL`.
 */
class GalaxyTable(
    val name: String,
    val columns: Map<String, Int>,
    val rows: List<List<String>>,
    val rejected: Int,
) {
    fun value(row: List<String>, column: String): String? {
        val i = columns[column] ?: return null
        val v = row.getOrNull(i)?.trim() ?: return null
        return if (v.isEmpty() || v == "NULL") null else v
    }

    fun require(column: String) {
        require(column in columns) { "$name: expected column '$column' is missing (has: ${columns.keys.take(30)})" }
    }
}

/** Column names of the flat COMMERCIALENTRY export (one row per document line). */
object FlatCols {
    const val COMPANY_CODE = "companycode"
    const val COMPANY_NAME = "companyname"
    const val COMPANY_ID = "companyid"
    const val CUST_CODE = "custcode"
    const val CUST_NAME = "custname"
    const val CUST_ID = "custid"
    const val DATE = "date"
    const val DOC_NUMBER = "docnumber"
    const val DOC_CODE = "doccode"
    const val TYPE = "Type"

    /**
     * "The OTHER party" - advertiser on Εντολή docs, agency on SEN-triangular
     * docs (GALAXY-MATCHER.md §9.3). Name is verbatim from the export header.
     */
    const val ADV_CODE = "Διαφημιζόμενος / Διαφημιστής Code"
    const val ADV_NAME = "Διαφημιζόμενος / Διαφημιστής Name"

    const val ITEM_ID = "item_ID"
    const val ITEM_CODE = "item_code"
    const val ITEM_NAME = "itemname"
    const val AQTY = "aqty"
    const val SECONDS = "Seconds"
    const val SPOT = "Spot"
    const val COMMENTS = "Comments"
    const val VALUE = "Value"
}

/** One flat-export line, typed. Field semantics: GALAXY-MATCHER.md §9.1. */
data class FlatLine(
    val companyCode: String,
    val custCode: String,
    val custName: String?,
    val custId: String?,
    val date: LocalDate?,
    val docNumber: String?,
    val docCode: String,
    val type: String?,
    val advCode: String?,
    val advName: String?,
    val itemId: String?,
    val itemCode: String?,
    val itemName: String?,
    val seconds: BigDecimal?,
    val spots: BigDecimal?,
    val comments: String?,
    val value: BigDecimal?,
) {
    /**
     * Natural document key until the final delivery adds GXID
     * (GALAXY-MATCHER.md §9.1/§11): company code + doc series + number.
     * Null for the handful of rows that carry no document number.
     */
    val docKey: String?
        get() = GalaxyExports.normalizedDocNumber(docNumber)
            ?.let { "$companyCode:$docCode:$it" }
}

/** Maps the raw table to typed lines; rows without a doc code are dropped. */
fun GalaxyTable.flatLines(): List<FlatLine> {
    listOf(
        FlatCols.COMPANY_CODE, FlatCols.CUST_CODE, FlatCols.DOC_CODE, FlatCols.DATE,
        FlatCols.ADV_CODE, FlatCols.ITEM_CODE, FlatCols.VALUE,
    ).forEach { require(it) }
    return rows.mapNotNull { r ->
        val company = value(r, FlatCols.COMPANY_CODE) ?: return@mapNotNull null
        val cust = value(r, FlatCols.CUST_CODE) ?: return@mapNotNull null
        val docCode = value(r, FlatCols.DOC_CODE) ?: return@mapNotNull null
        FlatLine(
            companyCode = company,
            custCode = cust,
            custName = value(r, FlatCols.CUST_NAME),
            custId = value(r, FlatCols.CUST_ID),
            date = GalaxyExports.flatDate(value(r, FlatCols.DATE)),
            docNumber = value(r, FlatCols.DOC_NUMBER),
            docCode = docCode,
            type = value(r, FlatCols.TYPE),
            advCode = value(r, FlatCols.ADV_CODE),
            advName = value(r, FlatCols.ADV_NAME),
            itemId = value(r, FlatCols.ITEM_ID),
            itemCode = value(r, FlatCols.ITEM_CODE),
            itemName = value(r, FlatCols.ITEM_NAME),
            seconds = GalaxyExports.greekMoney(value(r, FlatCols.SECONDS)),
            spots = GalaxyExports.greekMoney(value(r, FlatCols.SPOT)),
            comments = value(r, FlatCols.COMMENTS),
            value = GalaxyExports.greekMoney(value(r, FlatCols.VALUE)),
        )
    }
}
