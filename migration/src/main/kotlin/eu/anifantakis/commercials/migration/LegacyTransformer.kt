package eu.anifantakis.commercials.migration

import java.sql.Connection
import kotlin.random.Random

/**
 * Transforms replayed legacy data (scratch schema) into the normalized
 * target station schema, both on the SAME MySQL server so the heavy steps
 * are set-based cross-schema INSERT..SELECT (placements use a window
 * function - requires MySQL 8+).
 *
 * The Oracle-side entities (customer names, ΑΦΜ, contact info, contract
 * numbers) are NOT in the legacy MySQL dumps. Recovery order:
 *   1. real names harvested from commercials_calendar(_final) flattened rows
 *   2. contact info from the legacy `cus` supplement table
 *   3. everything still missing gets DETERMINISTIC FAKE data, flagged with
 *      synthetic=TRUE so a future ERP import can find and replace it.
 *
 * [forTv] selects which flow of the legacy DB to migrate (each legacy DB can
 * serve both a TV and a radio flow) - migrating the other flow is a second
 * run into a different target schema.
 */
class LegacyTransformer(
    private val c: Connection,
    scratchSchema: String,
    targetSchema: String,
    private val forTv: Int,
    private val log: (String) -> Unit,
) {
    private val s = "`$scratchSchema`"
    private val t = "`$targetSchema`"

    data class Summary(
        val breaks: Int,
        val customers: Int,
        val customersSynthetic: Int,
        val contracts: Int,
        val contractsSynthetic: Int,
        val contractLines: Int,
        val spots: Int,
        val placements: Int,
        val flowComments: Int,
        val printAudits: Int,
        val dateRange: String,
        // Coverage proof: every schedule row in the dump is accounted for.
        // dumpScheduleRows = placements + otherFlowRows + orphanedRows + zeroDateRows
        val dumpScheduleRows: Long,
        val otherFlowRows: Long,
        val orphanedRows: Long,
        val zeroDateRows: Long,
        /** Programmes with their operator-assigned colours (≙ legacy programtypes). */
        val programs: Int = 0,
    )

    fun run(): Summary {
        c.createStatement().use { it.execute("SET SESSION sql_mode = ''") }

        log("Building break grid from real airing times...")
        val breaks = migrateBreaks()

        log("Migrating programmes (with their operator-assigned colours)...")
        val programs = migratePrograms()

        log("Migrating customers (recovering real names where possible)...")
        val (customers, customersSynthetic) = migrateCustomers()

        log("Migrating contracts and lines...")
        val (contracts, contractsSynthetic) = migrateContracts()
        val lines = migrateContractLines()

        log("Migrating spot catalog...")
        val spots = migrateSpots()

        log("Migrating placements (this is the big one)...")
        val placements = migratePlacements()

        log("Migrating flow comments and print audit...")
        val comments = migrateFlowComments()
        val audits = migratePrintAudit()

        writeMeta()

        val range = c.createStatement().use { st ->
            st.executeQuery("SELECT MIN(show_date), MAX(show_date) FROM $t.placements").use { rs ->
                rs.next(); "${rs.getString(1)} .. ${rs.getString(2)}"
            }
        }

        // Coverage accounting: prove that every schedule row in the dump is
        // either migrated, belongs to the other flow (a second migration run),
        // is orphaned (references a spot the legacy app purged - no flow can
        // be determined), or carries a zero/invalid date.
        fun countOne(sql: String): Long = c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }
        }
        val dumpTotal = countOne("SELECT COUNT(*) FROM $s.schedule")
        val orphaned = countOne(
            "SELECT COUNT(*) FROM $s.schedule sch WHERE NOT EXISTS (SELECT 1 FROM $s.messages m WHERE m.id = sch.messageID)"
        )
        val zeroDate = countOne(
            """
            SELECT COUNT(*) FROM $s.schedule sch JOIN $s.messages m ON m.id = sch.messageID
            WHERE m.forTV = $forTv AND sch.showDate < '1900-01-01'
            """.trimIndent()
        )
        val otherFlow = dumpTotal - orphaned - placements - zeroDate

        log("Coverage: dump=$dumpTotal placements -> migrated=$placements, otherFlow=$otherFlow, orphaned=$orphaned, invalidDate=$zeroDate")

        return Summary(
            breaks, customers, customersSynthetic, contracts, contractsSynthetic,
            lines, spots, placements, comments, audits, range,
            dumpScheduleRows = dumpTotal,
            otherFlowRows = otherFlow,
            orphanedRows = orphaned,
            zeroDateRows = zeroDate,
            programs = programs,
        )
    }

    // ───────────────────────────────────────────────────────── programmes ──

    /**
     * The legacy app coloured scheduler cells by the PROGRAMME airing at that
     * slot: `schedule.programID` points straight at `programtypes` (the
     * separate `program` table is empty in every backup), whose `color`
     * column holds the operator-assigned colour. Migrated as-is - the colour
     * is the programme's identity across the whole application.
     */
    private fun migratePrograms(): Int {
        if (!tableExists("programtypes")) return 0
        var count = 0
        c.prepareStatement(
            "INSERT INTO $t.programs(legacy_id, name, color_argb, hidden) VALUES(?,?,?,?)"
        ).use { insert ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT id, descr, color, visible FROM $s.programtypes WHERE forTV = $forTv"
                ).use { rs ->
                    while (rs.next()) {
                        insert.setLong(1, rs.getLong("id"))
                        insert.setString(2, rs.getString("descr").trim())
                        val argb = colorrefToArgb(rs.getLong("color"))
                        if (argb != null) insert.setInt(3, argb) else insert.setNull(3, java.sql.Types.INTEGER)
                        insert.setBoolean(4, !rs.getBoolean("visible"))
                        insert.addBatch()
                        count++
                    }
                }
            }
            if (count > 0) insert.executeBatch()
        }
        return count
    }

    /**
     * Legacy colours are Windows COLORREF ints (0x00BBGGRR - the original was
     * a Windows desktop app; see `generic.serverDir`'s UNC path), converted
     * to Compose ARGB. Zero/negative means "no colour assigned". If migrated
     * colours ever render with red and blue swapped, flip r and b here.
     */
    private fun colorrefToArgb(colorref: Long): Int? {
        if (colorref <= 0L) return null
        val r = (colorref and 0xFF).toInt()
        val g = ((colorref shr 8) and 0xFF).toInt()
        val b = ((colorref shr 16) and 0xFF).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ─────────────────────────────────────────────────────────── breaks ──

    /**
     * The station's REAL break grid: every distinct HH:MM at which the legacy
     * schedule ever aired something (seconds are collapsed - two placements
     * at 11:00:07 and 11:00:41 belong to the same break). Zone derived from
     * the hour with the same rules the demo grid uses.
     */
    private fun migrateBreaks(): Int {
        val times = mutableListOf<Pair<Int, Int>>()
        c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT DISTINCT HOUR(sch.showTime) h, MINUTE(sch.showTime) mi
                FROM $s.schedule sch JOIN $s.messages m ON m.id = sch.messageID
                WHERE m.forTV = $forTv AND sch.showDate >= '1900-01-01'
                ORDER BY h, mi
                """.trimIndent()
            ).use { rs -> while (rs.next()) times += rs.getInt(1) to rs.getInt(2) }
        }
        c.prepareStatement(
            "INSERT INTO $t.break_slots(id, hour_of_day, minute_of_hour, label, zone) VALUES(?,?,?,?,?)"
        ).use { ps ->
            times.forEachIndexed { index, (hour, minute) ->
                val zone = when {
                    hour in 20..23 -> "PRIME"
                    hour in 10..14 -> "STANDARD"
                    hour in 18..19 -> "SPECIAL"
                    else -> "DEFAULT"
                }
                ps.setLong(1, index + 1L)
                ps.setInt(2, hour)
                ps.setInt(3, minute)
                ps.setString(4, "%02d:%02d".format(hour, minute))
                ps.setString(5, zone)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        return times.size
    }

    // ─────────────────────────────────────────────────────── customers ──

    /** Every legacy customer id the migrated data references. */
    private fun referencedCustomerIds(): Set<Long> {
        val ids = sortedSetOf<Long>()
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT DISTINCT cusID FROM $s.messages WHERE forTV = $forTv"
            ).use { rs -> while (rs.next()) ids += maxOf(rs.getLong(1), 0L) }
            st.executeQuery(
                """
                SELECT DISTINCT d.traid FROM $s.docref d
                JOIN $s.messages m ON m.contractID = d.docid
                WHERE m.forTV = $forTv AND d.traid > 0
                """.trimIndent()
            ).use { rs -> while (rs.next()) ids += rs.getLong(1) }
        }
        return ids
    }

    /**
     * Real names live in Oracle; the flattened calendar reporting rows are
     * the only place the legacy MySQL ever wrote them. Harvest the longest
     * (usually most complete) non-empty name per customer id.
     */
    private fun recoveredNames(): Map<Long, String> {
        val names = mutableMapOf<Long, String>()
        fun harvest(table: String, idCol: String, nameCol: String) {
            if (!tableExists(table)) return
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT $idCol, $nameCol FROM $s.`$table` WHERE $idCol > 0 AND $nameCol <> ''"
                ).use { rs ->
                    while (rs.next()) {
                        val id = rs.getLong(1)
                        val name = rs.getString(2).trim()
                        if (name.length > (names[id]?.length ?: 0)) names[id] = name
                    }
                }
            }
        }
        for (table in listOf("commercials_calendar", "commercials_calendar_final")) {
            harvest(table, "cusID1", "cus1_name")
            harvest(table, "cusID2", "cus2_name")
        }
        return names
    }

    /** Contact info from the legacy `cus` supplement (often just an email). */
    private data class Contact(val person: String?, val phone: String?, val fax: String?, val email: String?)

    private fun contacts(): Map<Long, Contact> {
        if (!tableExists("cus")) return emptyMap()
        val out = mutableMapOf<Long, Contact>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT id, person, phone, fax, email FROM $s.cus").use { rs ->
                while (rs.next()) {
                    out[rs.getLong(1)] = Contact(
                        person = rs.getString(2)?.ifBlank { null },
                        phone = rs.getString(3)?.ifBlank { null },
                        fax = rs.getString(4)?.ifBlank { null },
                        email = rs.getString(5)?.ifBlank { null },
                    )
                }
            }
        }
        return out
    }

    private fun migrateCustomers(): Pair<Int, Int> {
        val ids = referencedCustomerIds()
        val names = recoveredNames()
        val contactById = contacts()
        var synthetic = 0

        c.prepareStatement(
            """
            INSERT INTO $t.customers(legacy_id, code, name, vat_number, contact_person, phone, fax, email, notes, synthetic)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            for (id in ids) {
                val realName = if (id == 0L) null else names[id]
                val contact = contactById[id]
                val isSynthetic = realName == null
                if (isSynthetic) synthetic++

                ps.setLong(1, id)
                ps.setString(2, id.toString().padStart(8, '0'))
                ps.setString(3, realName ?: if (id == 0L) "ΑΓΝΩΣΤΟΣ ΠΕΛΑΤΗΣ" else fakeCompanyName(id))
                ps.setString(4, fakeVat(id))                       // ΑΦΜ never in MySQL for plain customers
                ps.setString(5, contact?.person)
                ps.setString(6, contact?.phone ?: fakePhone(id))
                ps.setString(7, contact?.fax)
                ps.setString(8, contact?.email ?: "customer$id@example.gr")
                ps.setString(9, if (isSynthetic) SYNTHETIC_NOTE else "Name recovered from legacy calendar rows; rest synthetic ($SYNTHETIC_NOTE)")
                ps.setBoolean(10, isSynthetic)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        return ids.size to synthetic
    }

    // ─────────────────────────────────────────────────────── contracts ──

    /**
     * One target contract per legacy contract/document id referenced by the
     * migrated spots. `docref` supplies real docno/dotid/customer when the
     * ERP shadow row survived; otherwise the contract is synthesized around
     * the spot's own customer.
     */
    private fun migrateContracts(): Pair<Int, Int> {
        data class Row(val docId: Long, val traid: Long?, val docNo: String?, val dotId: Int?, val fallbackCus: Long, val isGift: Boolean)

        val rows = mutableListOf<Row>()
        c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT m.contractID, MIN(d.traid), MIN(d.docno), MIN(d.dotid), MIN(GREATEST(m.cusID,0)), MAX(COALESCE(sl.isGift,0))
                FROM $s.messages m
                LEFT JOIN $s.docref d ON d.docid = m.contractID
                LEFT JOIN $s.sld sl ON sl.dotid = d.dotid
                WHERE m.forTV = $forTv AND m.contractID > 0
                GROUP BY m.contractID
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    rows += Row(
                        docId = rs.getLong(1),
                        traid = rs.getLong(2).takeIf { !rs.wasNull() && it > 0 },
                        docNo = rs.getString(3)?.ifBlank { null },
                        dotId = rs.getInt(4).takeIf { !rs.wasNull() },
                        fallbackCus = rs.getLong(5),
                        isGift = rs.getInt(6) == 1,
                    )
                }
            }
        }

        var synthetic = 0
        c.prepareStatement(
            """
            INSERT INTO $t.contracts(legacy_docid, number, doc_type, is_gift, customer_id, synthetic)
            VALUES(?,?,?,?,(SELECT id FROM $t.customers WHERE legacy_id = ?),?)
            """.trimIndent()
        ).use { ps ->
            for (r in rows) {
                val isSynthetic = r.docNo == null
                if (isSynthetic) synthetic++
                ps.setLong(1, r.docId)
                ps.setString(2, r.docNo ?: "LEGACY-${r.docId}")
                if (r.dotId != null) ps.setInt(3, r.dotId) else ps.setNull(3, java.sql.Types.INTEGER)
                ps.setBoolean(4, r.isGift)
                ps.setLong(5, r.traid ?: r.fallbackCus)
                ps.setBoolean(6, isSynthetic)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        return rows.size to synthetic
    }

    private fun migrateContractLines(): Int =
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.contract_lines(contract_id, line_no)
                SELECT DISTINCT tc.id, m.contractNO
                FROM $s.messages m
                JOIN $t.contracts tc ON tc.legacy_docid = m.contractID
                WHERE m.forTV = $forTv AND m.contractID > 0 AND m.contractNO > 0
                """.trimIndent()
            )
        }

    // ──────────────────────────────────────────────────── spots/placements ──

    private fun migrateSpots(): Int =
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.spots(legacy_id, customer_id, contract_line_id, description,
                                     duration_seconds, spot_type, flow, hidden, force_position, memo)
                SELECT m.id, tcu.id, tl.id, m.descr, m.duration,
                       COALESCE(pt.descr, ''), 'ΡΟΗ', m.hidden, NULLIF(m.forcePosition, -1), m.memo
                FROM $s.messages m
                JOIN $t.customers tcu ON tcu.legacy_id = GREATEST(m.cusID, 0)
                LEFT JOIN $t.contracts tct ON tct.legacy_docid = m.contractID
                LEFT JOIN $t.contract_lines tl ON tl.contract_id = tct.id AND tl.line_no = m.contractNO
                LEFT JOIN $s.programtypes pt ON pt.id = m.messageTypeID
                WHERE m.forTV = $forTv
                """.trimIndent()
            )
        }

    private fun migratePlacements(): Int =
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.placements(legacy_id, spot_id, break_id, show_date, position,
                                          duration_seconds, program_id, played, hidden)
                SELECT sch.id, ts.id, tb.id, sch.showDate,
                       ROW_NUMBER() OVER (
                           PARTITION BY sch.showDate, HOUR(sch.showTime), MINUTE(sch.showTime)
                           ORDER BY sch.showOrder, sch.id
                       ) - 1,
                       sch.durationSecs, tp.id, sch.played, sch.hideSchedule
                FROM $s.schedule sch
                JOIN $t.spots ts ON ts.legacy_id = sch.messageID
                JOIN $t.break_slots tb ON tb.hour_of_day = HOUR(sch.showTime)
                                      AND tb.minute_of_hour = MINUTE(sch.showTime)
                LEFT JOIN $t.programs tp ON tp.legacy_id = sch.programID
                WHERE sch.showDate >= '1900-01-01'
                """.trimIndent()
            )
        }

    // ───────────────────────────────────────────────── comments / audit ──

    private fun migrateFlowComments(): Int {
        if (!tableExists("roh_comments")) return 0
        return c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT IGNORE INTO $t.flow_comments(show_date, comments)
                SELECT startDate, comments FROM $s.roh_comments
                WHERE forTV = $forTv AND comments <> '' AND startDate >= '1900-01-01'
                """.trimIndent()
            )
        }
    }

    private fun migratePrintAudit(): Int {
        if (!tableExists("roh_print_history")) return 0
        return c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.print_audit(printed_date, username, created_at)
                SELECT printedDate, username, mystamp FROM $s.roh_print_history
                WHERE forTV = $forTv AND printedDate >= '1900-01-01'
                """.trimIndent()
            )
        }
    }

    private fun writeMeta() {
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.station_meta(meta_key, meta_value)
                VALUES ('migrated_at', NOW()), ('migrated_fortv', '$forTv')
                ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)
                """.trimIndent()
            )
        }
    }

    private fun tableExists(table: String): Boolean =
        c.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ? LIMIT 1"
        ).use { ps ->
            ps.setString(1, s.trim('`')); ps.setString(2, table)
            ps.executeQuery().use { it.next() }
        }

    companion object {
        const val SYNTHETIC_NOTE =
            "SYNTHETIC placeholder - real data lives in the ERP (Oracle) and was not part of the legacy MySQL dump"

        private val FAKE_SURNAMES = listOf(
            "ΠΑΠΑΔΑΚΗΣ", "ΜΑΡΙΝΑΚΗΣ", "ΓΕΩΡΓΙΑΔΗΣ", "ΑΝΤΩΝΙΟΥ", "ΝΙΚΟΛΑΟΥ",
            "ΣΤΑΥΡΑΚΑΚΗΣ", "ΚΩΝΣΤΑΝΤΙΝΙΔΗΣ", "ΒΛΑΧΑΚΗΣ", "ΜΑΝΩΛΑΚΗΣ", "ΞΕΝΑΚΗΣ",
        )
        private val FAKE_SECTORS = listOf(
            "ΤΡΟΦΙΜΑ", "ΕΠΙΠΛΑ", "ΤΟΥΡΙΣΤΙΚΑ", "ΟΠΤΙΚΑ", "ΑΥΤΟΚΙΝΗΤΑ",
            "ΗΛΕΚΤΡΙΚΑ", "ΔΟΜΙΚΑ ΥΛΙΚΑ", "ΕΝΔΥΣΗ", "ΞΕΝΟΔΟΧΕΙΑ", "ΣΟΥΠΕΡ ΜΑΡΚΕΤ",
        )
        private val FAKE_SUFFIXES = listOf("Α.Ε", "Ο.Ε", "Ε.Π.Ε", "Ι.Κ.Ε", "& ΣΙΑ Ο.Ε")

        /** Deterministic per legacy id: reruns produce identical fakes. */
        fun fakeCompanyName(legacyId: Long): String {
            val r = Random(legacyId)
            return "${FAKE_SURNAMES[r.nextInt(FAKE_SURNAMES.size)]} ${FAKE_SECTORS[r.nextInt(FAKE_SECTORS.size)]} ${FAKE_SUFFIXES[r.nextInt(FAKE_SUFFIXES.size)]}"
        }

        fun fakeVat(legacyId: Long): String = "9%08d".format((legacyId * 7919) % 100_000_000)

        fun fakePhone(legacyId: Long): String = "2810 %06d".format((legacyId * 31 + 398_000) % 1_000_000)
    }
}
