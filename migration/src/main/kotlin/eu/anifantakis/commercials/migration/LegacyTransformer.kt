package eu.anifantakis.commercials.migration

import eu.anifantakis.commercials.server.scheduler.StationDb
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
        /** Docs where the paying trader differs from the end client. */
        val triangularContracts: Int = 0,
        /** End clients known to MySQL only as an ERP lee id (created synthetic). */
        val endClientsSynthesized: Int = 0,
        /** Archived legacy emails migrated (metadata always; bodies capped). */
        val emails: Int = 0,
        val emailBodiesKept: Int = 0,
        /** Airtime price list rows (versioned; full history migrates). */
        val zones: Int = 0,
        val zoneFillers: Int = 0,
    )

    fun run(): Summary {
        c.createStatement().use { it.execute("SET SESSION sql_mode = ''") }

        log("Building break grid from real airing times...")
        val breaks = migrateBreaks()

        log("Migrating programmes (with their operator-assigned colours)...")
        val programs = migratePrograms()

        log("Building the lee<->tra id mapping (triangular contracts)...")
        val triangular = buildLeeTraMap()

        log("Migrating customers (recovering real names where possible)...")
        val (customers, customersSynthetic, endClients) = migrateCustomers()

        // The type catalog MUST precede the product lines: lines carry
        // spot_type_id (z.mciid) and spots resolve their default line by type.
        log("Migrating the spot-type catalog (legacy programtypes = the ERP item classes)...")
        val spotTypes = migrateSpotTypes()
        log("  $spotTypes spot types (the SEN import adds each type's ERP sales item)")

        log("Migrating contracts and lines...")
        val (contracts, contractsSynthetic) = migrateContracts()
        val lines = migrateContractLines()

        log("Migrating spot catalog (triangular spots land on their END client)...")
        val spots = migrateSpots()

        log("Migrating placements (this is the big one)...")
        val placements = migratePlacements()

        log("Backfilling provisional contract period dates from placements (until the ERP import)...")
        val provisionalContracts = backfillProvisionalContractDates()
        log("  $provisionalContracts contracts got provisional start/end dates (dates_provisional=TRUE)")

        log("Migrating flow comments and print audit...")
        val comments = migrateFlowComments()
        val audits = migratePrintAudit()

        log("Migrating email archive (bodies capped at ${StationDb.EMAIL_BODY_RETENTION_PER_CUSTOMER}/customer, summaries for all)...")
        val (emails, emailBodiesKept) = migrateEmailHistory()

        log("Migrating airtime price zones (full price history)...")
        val (zones, zoneFillers) = migrateZones()

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
            triangularContracts = triangular,
            endClientsSynthesized = endClients,
            emails = emails,
            emailBodiesKept = emailBodiesKept,
            zones = zones,
            zoneFillers = zoneFillers,
        )
    }

    // ─────────────────────────────────────────────── triangular contracts ──

    /**
     * LEE and TRA are two ERP id series for the same entities. On a
     * non-triangular doc `targetleeid = pelatislee` (the payer's own lee),
     * which yields a nearly 1:1 lee->tra dictionary. On a TRIANGULAR doc
     * `targetleeid` is the END CLIENT's lee (`pelatislee` stays the payer's,
     * despite the name - verified against the main dump, see
     * migration/legacy-schema.md). The map lives in the scratch schema and
     * is dropped with it. Returns this flow's triangular doc count.
     */
    private fun buildLeeTraMap(): Int {
        c.createStatement().use { st ->
            st.executeUpdate("DROP TABLE IF EXISTS $s.lee_tra_map")
            st.executeUpdate(
                """
                CREATE TABLE $s.lee_tra_map (lee BIGINT PRIMARY KEY, traid BIGINT NOT NULL)
                AS SELECT targetleeid AS lee, MIN(traid) AS traid
                   FROM $s.docref
                   WHERE targetleeid = pelatislee AND targetleeid > 0 AND traid > 0
                   GROUP BY targetleeid
                """.trimIndent()
            )
        }
        return c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT COUNT(DISTINCT d.docid) FROM $s.docref d
                WHERE d.targetleeid <> d.pelatislee AND d.targetleeid > 0
                  AND EXISTS (SELECT 1 FROM $s.messages m WHERE m.contractID = d.docid AND m.forTV = $forTv)
                """.trimIndent()
            ).use { rs -> rs.next(); rs.getInt(1) }
        }
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
            // END CLIENTS of this flow's triangular docs whose lee resolves
            // to a tra id - they need a customer row even if they never
            // bought anything directly on this flow.
            st.executeQuery(
                """
                SELECT DISTINCT map.traid FROM $s.docref d
                JOIN $s.lee_tra_map map ON map.lee = d.targetleeid
                JOIN $s.messages m ON m.contractID = d.docid
                WHERE m.forTV = $forTv AND d.targetleeid <> d.pelatislee
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

    private fun migrateCustomers(): Triple<Int, Int, Int> {
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

        // Stamp each customer's lee id from the dictionary. A traid mapped
        // by several lees takes the smallest one, so no lee lands on two
        // customers (the dictionary itself is unique per lee).
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                UPDATE $t.customers tc
                JOIN (SELECT traid, MIN(lee) AS lee FROM $s.lee_tra_map GROUP BY traid) m
                  ON m.traid = tc.legacy_id
                SET tc.legacy_lee_id = m.lee
                """.trimIndent()
            )
        }

        // END CLIENTS of triangular docs whose lee has NO tra mapping: they
        // never bought directly, so MySQL knows them only as a lee id (their
        // name lives in Oracle alone). Synthesize them, keyed by lee.
        val unresolvedLees = c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT DISTINCT d.targetleeid FROM $s.docref d
                JOIN $s.messages m ON m.contractID = d.docid
                WHERE m.forTV = $forTv AND d.targetleeid <> d.pelatislee AND d.targetleeid > 0
                  AND NOT EXISTS (SELECT 1 FROM $s.lee_tra_map map WHERE map.lee = d.targetleeid)
                ORDER BY d.targetleeid
                """.trimIndent()
            ).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
        c.prepareStatement(
            """
            INSERT INTO $t.customers(legacy_lee_id, code, name, vat_number, phone, email, notes, synthetic)
            VALUES(?,?,?,?,?,?,?,TRUE)
            """.trimIndent()
        ).use { ps ->
            for (lee in unresolvedLees) {
                ps.setLong(1, lee)
                ps.setString(2, "LEE-$lee")
                ps.setString(3, fakeCompanyName(lee))
                ps.setString(4, fakeVat(lee))
                ps.setString(5, fakePhone(lee))
                ps.setString(6, "endclient$lee@example.gr")
                ps.setString(7, "$SYNTHETIC_NOTE - end client of triangular contracts, known here only as ERP lee id $lee")
                ps.addBatch()
            }
            if (unresolvedLees.isNotEmpty()) ps.executeBatch()
        }

        return Triple(ids.size + unresolvedLees.size, synthetic + unresolvedLees.size, unresolvedLees.size)
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

        // Legacy calendar_excluded_docs: documents kept OFF printed reports.
        // Verified across all dumps: these docids match NOTHING in the legacy
        // DB itself (not docref, not schedule.docID, not messages.contractID)
        // - they are the EXTERNAL ERP's document ids, fed to the staging
        // commercials_calendar. Preserve the raw list per station so the
        // future ERP import can set contracts.exclude_from_reports from it.
        if (tableExists("calendar_excluded_docs")) {
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE IF NOT EXISTS $t.erp_excluded_docs (erp_docid BIGINT PRIMARY KEY) ENGINE=InnoDB")
                val kept = st.executeUpdate(
                    "INSERT IGNORE INTO $t.erp_excluded_docs(erp_docid) SELECT docid FROM $s.calendar_excluded_docs"
                )
                if (kept > 0) log("  $kept ERP doc ids preserved in erp_excluded_docs (report exclusions, applied at ERP import)")
            }
        }

        return rows.size to synthetic
    }

    /**
     * Contract PRODUCT lines, straight from the dump's `z_commercials` (the
     * owner's Oracle view over the ERP document lines): one row per
     * (document, lineno), each selling ONE item class (mciid -> spot_types).
     * A fallback line (line_no = 1000 + type id, clearly synthetic) is added
     * for any (document, type) pair the view did not carry, so every message
     * still finds its product.
     */
    private fun migrateContractLines(): Int {
        val real = if (tableExists("z_commercials")) {
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO $t.contract_lines(contract_id, line_no, spot_type_id)
                    SELECT DISTINCT tc.id, z.lineno, tst.id
                    FROM $s.z_commercials z
                    JOIN $t.contracts tc ON tc.legacy_docid = z.docid
                    LEFT JOIN $t.spot_types tst ON tst.legacy_id = z.mciid
                    """.trimIndent()
                )
            }
        } else 0
        val fallback = c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.contract_lines(contract_id, line_no, spot_type_id)
                SELECT DISTINCT tc.id, 1000 + m.messageTypeID, tst.id
                FROM $s.messages m
                JOIN $t.contracts tc ON tc.legacy_docid = m.contractID
                LEFT JOIN $t.spot_types tst ON tst.legacy_id = m.messageTypeID
                WHERE m.forTV = $forTv AND m.contractID > 0
                  AND NOT EXISTS (
                      SELECT 1 FROM $t.contract_lines cl
                      WHERE cl.contract_id = tc.id AND cl.spot_type_id = tst.id
                  )
                """.trimIndent()
            )
        }
        log("  $real product lines from z_commercials + $fallback fallback lines (line_no >= 1000)")
        return real + fallback
    }

    /**
     * Provisional contract-period backfill: the legacy MySQL has no contract
     * start/end (Oracle-ERP-mastered), so derive each contract's period from
     * its own aired placements - MIN/MAX(show_date) - and flag it
     * dates_provisional=TRUE. The future ERP import (see erp_excluded_docs)
     * overwrites exactly these rows; renewed_at stays NULL (renewal has no
     * source until then). Contracts that never aired keep NULL dates.
     * TODO: replace with real periods once the Oracle ERP import exists.
     */
    private fun backfillProvisionalContractDates(): Int =
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                UPDATE $t.contracts ct
                JOIN (
                    SELECT cl.contract_id AS cid, MIN(p.show_date) AS mn, MAX(p.show_date) AS mx
                    FROM $t.placements p
                    JOIN $t.spots s ON s.id = p.spot_id
                    JOIN $t.contract_lines cl ON cl.id = s.contract_line_id
                    WHERE p.hidden = FALSE
                    GROUP BY cl.contract_id
                ) agg ON agg.cid = ct.id
                SET ct.start_date = agg.mn, ct.end_date = agg.mx, ct.dates_provisional = TRUE
                """.trimIndent()
            )
        }

    // ──────────────────────────────────────────────────── spots/placements ──

    /**
     * The spot belongs to its END CLIENT ("My Advert Company has a contract
     * FOR the customer Unilever, FOR WHOM the spot is scheduled"). Legacy
     * `messages.cusID` is the PAYER (== docref.traid 99.9%), so on
     * triangular docs the customer is resolved through `targetleeid`
     * instead; the payer stays reachable via the contract
     * (contracts.customer_id = traid).
     */
    /**
     * The spot-type CATALOG: legacy `programtypes` is the ERP's item-class
     * list (MCI - `messages.messageTypeID` points here, and its `sen` index
     * is named after the ERP). Kept as a REFERENCE table, exactly like the
     * legacy model: a spot's type/item is a lookup, never frozen text. The
     * SEN import later fills each type's `sales_item` from the ERP item
     * catalog (STI is 1:1 with the class).
     */
    private fun migrateSpotTypes(): Int {
        if (!tableExists("programtypes")) return 0
        return c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.spot_types(legacy_id, name)
                SELECT id, descr FROM $s.programtypes
                """.trimIndent()
            )
        }
    }

    private fun migrateSpots(): Int =
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.spots(legacy_id, customer_id, contract_line_id, description,
                                     duration_seconds, spot_type, spot_type_id, flow, hidden, force_position, memo)
                SELECT m.id, COALESCE(tend.id, tcu.id), tl.id, m.descr, m.duration,
                       COALESCE(pt.descr, ''), tst.id, 'ΡΟΗ', m.hidden, NULLIF(m.forcePosition, -1), m.memo
                FROM $s.messages m
                JOIN $t.customers tcu ON tcu.legacy_id = GREATEST(m.cusID, 0)
                LEFT JOIN $s.docref d ON d.docid = m.contractID
                                     AND d.targetleeid <> d.pelatislee AND d.targetleeid > 0
                LEFT JOIN $t.customers tend ON tend.legacy_lee_id = d.targetleeid
                LEFT JOIN $t.contracts tct ON tct.legacy_docid = m.contractID
                LEFT JOIN $s.programtypes pt ON pt.id = m.messageTypeID
                LEFT JOIN $t.spot_types tst ON tst.legacy_id = m.messageTypeID
                -- the message's CURRENT default product: its contract's line
                -- selling the message's item class (real line first, fallback after)
                LEFT JOIN $t.contract_lines tl ON tl.id = (
                    SELECT cl.id FROM $t.contract_lines cl
                    WHERE cl.contract_id = tct.id AND cl.spot_type_id = tst.id
                    ORDER BY cl.line_no LIMIT 1
                )
                WHERE m.forTV = $forTv
                """.trimIndent()
            )
        }

    private fun migratePlacements(): Int =
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.placements(legacy_id, spot_id, break_id, show_date, position,
                                          duration_seconds, contract_line_id, program_id, played, hidden)
                SELECT sch.id, ts.id, tb.id, sch.showDate,
                       ROW_NUMBER() OVER (
                           PARTITION BY sch.showDate, HOUR(sch.showTime), MINUTE(sch.showTime)
                           ORDER BY sch.showOrder, sch.id
                       ) - 1,
                       sch.durationSecs, pcl.id, tp.id, sch.played, sch.hideSchedule
                FROM $s.schedule sch
                JOIN $t.spots ts ON ts.legacy_id = sch.messageID
                JOIN $t.break_slots tb ON tb.hour_of_day = HOUR(sch.showTime)
                                      AND tb.minute_of_hour = MINUTE(sch.showTime)
                -- the airing's ACTUAL charge: schedule.docID + lineno name the
                -- product line directly (NULL falls back to the spot's default)
                LEFT JOIN $t.contracts pct ON pct.legacy_docid = sch.docID
                LEFT JOIN $t.contract_lines pcl ON pcl.contract_id = pct.id AND pcl.line_no = sch.lineno
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

    // ─────────────────────────────────────────────────── email archive ──

    /**
     * Legacy `emailhistory` -> `email_log`. Every send keeps its summary
     * row (who/what/when - the audit trail the operators rely on to avoid
     * double-sends), but the heavy HTML BODY survives only for the newest
     * [StationDb.EMAIL_BODY_RETENTION_PER_CUSTOMER] per customer - the same
     * cap the live email sender enforces, so the archive can never balloon
     * back to the legacy 1.2 GB.
     *
     * `emailhistory` has no forTV: the archive belongs to the legacy DB as
     * a whole, so it lands with whichever flow migrates it. The period
     * ("Μάρτιος 2006") is parsed in Kotlin - accents and case vary.
     */
    private fun migrateEmailHistory(): Pair<Int, Int> {
        if (!tableExists("emailhistory")) return 0 to 0

        // Distinct period labels -> (year, month), parsed here and joined
        // in SQL, so the big INSERT..SELECT stays set-based.
        val labels = c.createStatement().use { st ->
            st.executeQuery("SELECT DISTINCT periodRequested FROM $s.emailhistory").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) ?: "") }
            }
        }
        c.createStatement().use { st ->
            st.executeUpdate("DROP TABLE IF EXISTS $s.email_periods")
            st.executeUpdate(
                "CREATE TABLE $s.email_periods (label VARCHAR(100) PRIMARY KEY, py INT NOT NULL, pm INT NOT NULL)"
            )
        }
        c.prepareStatement("INSERT IGNORE INTO $s.email_periods(label, py, pm) VALUES(?,?,?)").use { ps ->
            for (label in labels) {
                val (y, m) = parseGreekPeriod(label)
                ps.setString(1, label); ps.setInt(2, y); ps.setInt(3, m)
                ps.addBatch()
            }
            if (labels.isNotEmpty()) ps.executeBatch()
        }

        // The ROW_NUMBER subquery deliberately carries only (id, cusID,
        // date) - sorting must never drag the longtext bodies through a
        // temp table.
        val cap = StationDb.EMAIL_BODY_RETENTION_PER_CUSTOMER
        val migrated = c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.email_log(customer_code, customer_name, recipient, subject,
                    period_year, period_month, spot_count, transmission_count, body_html,
                    sent_by, sent_at, status)
                SELECT COALESCE(tcu.code, LPAD(e.cusID, 8, '0')),
                       COALESCE(tcu.name, CONCAT('ΠΕΛΑΤΗΣ #', e.cusID)),
                       e.recipientEmailAddress,
                       LEFT(e.subject, 255),
                       COALESCE(p.py, 0), COALESCE(p.pm, 0),
                       0, 0,
                       CASE WHEN r.rn <= $cap THEN NULLIF(e.body, '') END,
                       LEFT(e.emailFrom, 64),
                       IF(e.entryDate >= '1971-01-01', TIMESTAMP(e.entryDate, e.entryTime), '1971-01-01 00:00:00'),
                       'SENT'
                FROM $s.emailhistory e
                JOIN (SELECT id, ROW_NUMBER() OVER (
                          PARTITION BY cusID ORDER BY entryDate DESC, entryTime DESC, id DESC
                      ) AS rn FROM $s.emailhistory) r ON r.id = e.id
                LEFT JOIN $s.email_periods p ON p.label = e.periodRequested
                LEFT JOIN $t.customers tcu ON tcu.legacy_id = e.cusID
                """.trimIndent()
            )
        }
        val bodiesKept = c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $t.email_log WHERE body_html IS NOT NULL").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
        return migrated to bodiesKept
    }

    // ───────────────────────────────────────────────── zones (pricing) ──

    /**
     * The airtime price list, WITH its full history: legacy zones/zonefillers
     * are versioned by an integer fromDate (YYYYMMDD) - every price change
     * since 2004 is a new row, and all of them migrate. Fillers first (zones
     * reference them); legacy `fillerID = -1` means none.
     */
    private fun migrateZones(): Pair<Int, Int> {
        if (!tableExists("zones")) return 0 to 0
        val fillers = if (tableExists("zonefillers")) {
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO $t.zone_fillers(legacy_id, code, label, price, valid_from)
                    SELECT id, LEFT(code, 32), LEFT(descr, 64), price,
                           STR_TO_DATE(NULLIF(fromDATE, 0), '%Y%m%d')
                    FROM $s.zonefillers
                    WHERE forTV = $forTv
                    """.trimIndent()
                )
            }
        } else 0
        val zones = c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.zones(legacy_id, code, label, from_time, end_time, filler_from_time,
                                     price, valid_from, public_sector, filler_id)
                SELECT z.id, LEFT(z.code, 32), LEFT(z.descr, 64),
                       TIME(z.fromTime), TIME(z.endTime), TIME(z.fromFillerTime),
                       z.price, STR_TO_DATE(NULLIF(z.fromDate, 0), '%Y%m%d'),
                       COALESCE(z.dimosio, 0), tf.id
                FROM $s.zones z
                LEFT JOIN $t.zone_fillers tf ON tf.legacy_id = NULLIF(z.fillerID, -1)
                WHERE z.forTV = $forTv
                """.trimIndent()
            )
        }
        return zones to fillers
    }

    /** "Μάρτιος 2006" -> 2006 to 3; unparsable parts become 0. */
    private fun parseGreekPeriod(label: String): Pair<Int, Int> {
        val normalized = label.uppercase()
            .map { GREEK_TONOS_FOLD[it] ?: it }
            .filterNot { it == '́' || it == '̈' }  // combining tonos/dialytika ("Μαΐου")
            .joinToString("")
        val year = Regex("(19|20)\\d{2}").find(normalized)?.value?.toInt() ?: 0
        val month = GREEK_MONTH_STEMS.indexOfFirst { normalized.contains(it) } + 1
        return year to month
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

        /** Uppercased Greek tonos/dialytika vowels folded to their base letter. */
        private val GREEK_TONOS_FOLD = mapOf(
            'Ά' to 'Α', 'Έ' to 'Ε', 'Ή' to 'Η', 'Ί' to 'Ι', 'Ό' to 'Ο',
            'Ύ' to 'Υ', 'Ώ' to 'Ω', 'Ϊ' to 'Ι', 'Ϋ' to 'Υ',
        )

        /** Index+1 = month number; stems survive tonos-folding and casing. */
        private val GREEK_MONTH_STEMS = listOf(
            "ΙΑΝΟΥΑΡ", "ΦΕΒΡΟΥΑΡ", "ΜΑΡΤ", "ΑΠΡΙΛ", "ΜΑΙΟ", "ΙΟΥΝ",
            "ΙΟΥΛ", "ΑΥΓΟΥΣΤ", "ΣΕΠΤΕΜΒΡ", "ΟΚΤΩΒΡ", "ΝΟΕΜΒΡ", "ΔΕΚΕΜΒΡ",
        )

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
