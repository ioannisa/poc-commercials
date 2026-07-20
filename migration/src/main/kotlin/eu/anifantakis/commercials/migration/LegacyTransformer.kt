package eu.anifantakis.commercials.migration

import eu.anifantakis.commercials.server.scheduler.BreakSeeder
import eu.anifantakis.commercials.server.scheduler.StationDb
import java.sql.Connection
import kotlin.random.Random

/**
 * Transforms replayed legacy data (scratch schema) into the normalized target
 * GROUP schema, both on the SAME MySQL server so the heavy steps are set-based
 * cross-schema INSERT..SELECT (placements use a window function - requires
 * MySQL 8+).
 *
 * The Oracle-side entities (customer names, ΑΦΜ, contact info, contract
 * numbers) are NOT in the legacy MySQL dumps. Recovery order:
 *   1. real names harvested from commercials_calendar(_final) flattened rows
 *   2. contact info from the legacy `cus` supplement table
 *   3. everything still missing gets DETERMINISTIC FAKE data, flagged with
 *      synthetic=TRUE so a future ERP import can find and replace it.
 *
 * ONE DUMP ⇒ ONE GROUP ⇒ N STATIONS. [stationByFlow] maps each legacy `forTV`
 * value to a station of the target group, and BOTH flows migrate in this single
 * run. `forTV` used to be a FILTER - one flow per run, into its own schema -
 * which duplicated every customer of the outlet and tore contracts selling on
 * both media in half. It is now a station STAMP:
 *
 *  - customers, contracts, contract lines and the item catalog are written ONCE
 *    and shared (that is what makes contract 500 hold a TV line AND radio lines);
 *  - spots, programmes, breaks, flow comments and print audits carry station_id;
 *  - the verbatim legacy copies keep their own native forTV and are copied whole.
 *
 * A flow left out of the map is simply not migrated (its rows are counted as
 * `otherFlow` in the coverage summary).
 */
class LegacyTransformer(
    private val c: Connection,
    scratchSchema: String,
    targetSchema: String,
    private val stationByFlow: Map<Int, String>,
    private val log: (String) -> Unit,
    /**
     * (steps done, steps total, the step's label). The transform has no measurable
     * quantity of its own - only a fixed sequence - so its bar counts steps, and
     * says which one. Default no-op: the CLI prints text and wants none.
     */
    private val onStep: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    /**
     * WITHIN-step progress, for the steps big enough to deserve one (the
     * verbatim copies, the placements bulk load, the break-entity build - a
     * step bar alone sits at "18/18" for minutes on a 4M-row dump). Same
     * honesty rule as the step bar: (0, 0) means the running step offers no
     * measurable inside, and the UI must hide the sub-bar, not guess.
     */
    private val onSubProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
) {
    private val s = "`$scratchSchema`"
    private val t = "`$targetSchema`"

    /** The bare name - `information_schema` lookups cannot take the backticked form. */
    private val targetSchemaName = targetSchema

    private var stepsDone = 0

    /**
     * Announce a step: log it AND advance the progress bar.
     *
     * The total is [TOTAL_STEPS], clamped so a stale constant can never make the
     * bar exceed 100%, and run() finishes it at exactly 100% regardless - a
     * forgotten update costs a slightly-off bar mid-run, never a wrong one.
     */
    private fun step(label: String) {
        stepsDone++
        // A new step voids the previous step's sub-progress at once - a full
        // sub-bar must never linger over a step that reports none.
        onSubProgress(0, 0)
        onStep(stepsDone, maxOf(TOTAL_STEPS, stepsDone), label)
        log(label)
    }

    /**
     * The scratch-resident (forTV -> station_id) dictionary. JOINing it does two
     * jobs at once: it SCOPES the query to the mapped flows (an unmapped flow
     * has no row, so its data drops out) and it SUPPLIES the station_id to
     * stamp. It replaces the ~20 `WHERE forTV = <n>` filters of the old
     * one-flow-per-run design.
     */
    private val flowJoin = "JOIN $s.flow_station fs ON fs.forTV = m.forTV"

    /**
     * The SAME dictionary lookup as [flowJoin], spelled in the comma/WHERE style
     * the inner-join-only statements here use: [flowFrom] extends the FROM list,
     * [flowWhere] carries the predicate that pairs with it. Both fragments exist
     * because both spellings are needed - a statement that also has a LEFT JOIN
     * (migrateContracts, migrateSpots, migratePlacements) must stay in JOIN form
     * and keeps using [flowJoin]: the comma binds looser than JOIN, so an ON
     * clause could no longer see a comma-joined table.
     */
    private val flowFrom = ", $s.flow_station fs"
    private val flowWhere = "fs.forTV = m.forTV"

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
        /** Customers whose placeholder default email was recovered from the archive. */
        val emailsEnriched: Int = 0,
        /** Airtime price list rows (versioned; full history migrates). */
        val zones: Int = 0,
        val zoneFillers: Int = 0,
        /** Per-station tallies - one dump now fills several stations. */
        val stations: List<StationTally> = emptyList(),
    )

    /** What one flow's station actually received. */
    data class StationTally(
        val stationId: String,
        val forTv: Int,
        val spots: Int,
        val placements: Int,
    )

    fun run(): Summary {
        c.createStatement().use { it.execute("SET SESSION sql_mode = ''") }

        createFlowStationMap()

        step("Copying the legacy MySQL tables VERBATIM (faithful union layer)...")
        copyLegacyTables()

        step("Migrating programmes (with their operator-assigned colours)...")
        val programs = migratePrograms()

        step("Building the lee<->tra id mapping (triangular contracts)...")
        val triangular = buildLeeTraMap()

        step("Migrating customers (recovering real names where possible)...")
        val (customers, customersSynthetic, endClients) = migrateCustomers()

        // The type catalog MUST precede the product lines: lines carry
        // spot_type_id (z.mciid) and spots resolve their default line by type.
        step("Migrating the ERP item catalog (the item classes the contract lines reference)...")
        val spotTypes = migrateSpotTypes()
        log("  $spotTypes spot types (the SEN import adds each type's ERP sales item)")

        step("Migrating contracts and lines...")
        val (contracts, contractsSynthetic) = migrateContracts()
        val lines = migrateContractLines()

        step("Migrating spot catalog (triangular spots land on their END client)...")
        val spots = migrateSpots()

        // BULK-LOAD SHAPE: the read indexes come OFF before the big insert and go
        // back on after. Building an index ONCE over finished rows is far cheaper
        // than maintaining it across 4.1M inserts - measured on the real dump:
        // 77.5s with every index live, 60.0s this way (-23%).
        step("Dropping the read-only indexes on placements for the bulk load...")
        dropBulkLoadIndexes()

        step("Migrating placements (this is the big one)...")
        val placements = migratePlacements()

        // legacy_id goes back IMMEDIATELY: the SEN enricher re-points every airing
        // to its charge with `WHERE sch.id = p.legacy_id`, and without the index
        // that join is 4.1M x 4.1M. (2.6s to build - do not defer it.)
        step("Rebuilding the legacy_id index (the enricher joins airings on it)...")
        rebuildLegacyIndex()

        // The breaks are not migrated - they EMERGE. This is a report, not a
        // build step: it counts the distinct times the airings just landed on,
        // which is precisely what the grid will group them into.
        val breaks = countBreakTimes()
        log("  $breaks distinct break times emerged from the airings")

        step("Backfilling each spot's DEFAULT product line from its own airings...")
        val defaultLines = backfillSpotDefaultLines()
        log("  $defaultLines spots got a default line (multi-line documents)")

        step("Backfilling provisional contract period dates from placements (until the ERP import)...")
        val provisionalContracts = backfillProvisionalContractDates()
        log("  $provisionalContracts contracts got provisional start/end dates (dates_provisional=TRUE)")

        step("Migrating flow comments and print audit...")
        val comments = migrateFlowComments()
        val audits = migratePrintAudit()

        step("Migrating email archive (bodies capped at ${StationDb.EMAIL_BODY_RETENTION_PER_CUSTOMER}/customer, summaries for all)...")
        val (emails, emailBodiesKept) = migrateEmailHistory()

        step("Recovering customer default emails from the sent-email history...")
        val emailsEnriched = enrichCustomerEmailsFromHistory()

        step("Migrating airtime price zones (full price history)...")
        val (zones, zoneFillers) = migrateZones()

        // LAST. Nothing in the migration reads it - it exists for the app's month
        // grid - so it is built once, over finished rows, at the very end. It is
        // also the widest index on the biggest table, so it is the one that costs
        // most to carry through a bulk load.
        step("Rebuilding the month-grid index (app read path; nothing here uses it)...")
        rebuildGridIndex()

        step("Building break entities (dominant programme per break; airings attach)...")
        val breakEntities = seedBreakEntities()
        log("  $breakEntities break entities built; every airing attached to one")

        writeMeta()
        // Finish the bar honestly: whatever TOTAL_STEPS says, the run is over.
        onStep(stepsDone, stepsDone, "")

        val range = c.createStatement().use { st ->
            st.executeQuery("SELECT MIN(show_date), MAX(show_date) FROM $t.placements").use { rs ->
                rs.next(); "${rs.getString(1)} .. ${rs.getString(2)}"
            }
        }

        // Coverage accounting: prove that every schedule row in the dump is
        // either migrated, belongs to a flow the operator did NOT map, is
        // orphaned (references a spot the legacy app purged - no flow can be
        // determined), or carries a zero/invalid date.
        fun countOne(sql: String): Long = c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }
        }
        val dumpTotal = countOne("SELECT COUNT(*) FROM $s.schedule")
        val orphaned = countOne(
            "SELECT COUNT(*) FROM $s.schedule sch WHERE NOT EXISTS (SELECT 1 FROM $s.messages m WHERE m.id = sch.messageID)"
        )
        val zeroDate = countOne(
            """
            SELECT COUNT(*) FROM $s.schedule sch, $s.messages m$flowFrom
            WHERE m.id = sch.messageID
              AND $flowWhere
              AND sch.showDate < '1900-01-01'
            """.trimIndent()
        )
        val otherFlow = dumpTotal - orphaned - placements - zeroDate

        log("Coverage: dump=$dumpTotal placements -> migrated=$placements, unmappedFlow=$otherFlow, orphaned=$orphaned, invalidDate=$zeroDate")

        val tallies = stationTallies()
        tallies.forEach { log("  ${it.stationId} (forTV=${it.forTv}): ${it.spots} spots, ${it.placements} placements") }

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
            emailsEnriched = emailsEnriched,
            zones = zones,
            zoneFillers = zoneFillers,
            stations = tallies,
        )
    }

    /**
     * Materializes [stationByFlow] in the scratch schema so every step can JOIN
     * it (see [flowJoin]). Scratch-resident, so it is dropped with the scratch.
     */
    private fun createFlowStationMap() {
        require(stationByFlow.isNotEmpty()) { "No flow was mapped to a station - nothing to migrate" }
        c.createStatement().use { st ->
            st.executeUpdate("DROP TABLE IF EXISTS $s.flow_station")
            st.executeUpdate(
                "CREATE TABLE $s.flow_station (forTV INT PRIMARY KEY, station_id VARCHAR(64) NOT NULL)"
            )
        }
        c.prepareStatement("INSERT INTO $s.flow_station(forTV, station_id) VALUES(?,?)").use { ps ->
            for ((forTv, stationId) in stationByFlow) {
                ps.setInt(1, forTv)
                ps.setString(2, stationId)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        log(
            "Flow map: " + stationByFlow.entries.joinToString(", ") { (forTv, id) ->
                "forTV=$forTv (${if (forTv == 1) "TV" else "radio"}) -> station '$id'"
            }
        )
    }

    private fun stationTallies(): List<StationTally> =
        c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT fs.station_id, fs.forTV,
                       (SELECT COUNT(*) FROM $t.spots sp WHERE sp.station_id = fs.station_id),
                       (SELECT COUNT(*) FROM $t.placements p, $t.spots sp2
                        WHERE sp2.id = p.spot_id
                          AND sp2.station_id = fs.station_id)
                FROM $s.flow_station fs
                ORDER BY fs.forTV DESC
                """.trimIndent()
            ).use { rs ->
                buildList {
                    while (rs.next()) add(
                        StationTally(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))
                    )
                }
            }
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
                  AND EXISTS (
                      SELECT 1 FROM $s.messages m$flowFrom
                      WHERE $flowWhere AND m.contractID = d.docid
                  )
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
     *
     * ⚠ `programtypes` carries its OWN forTV, and its ids RESTART per flow:
     * programme 5 exists on the TV side and on the radio side, and they are
     * different shows. So the programme is per station, and every later join to
     * it must match the station as well as the legacy id - a join on legacy_id
     * alone would paste the radio station's programme names and colours onto TV
     * spots (and vice versa).
     */
    private fun migratePrograms(): Int {
        if (!tableExists("programtypes")) return 0
        var count = 0
        c.prepareStatement(
            "INSERT INTO $t.programs(station_id, legacy_id, name, color_argb, hidden) VALUES(?,?,?,?,?)"
        ).use { insert ->
            c.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT fs.station_id, pt.id, pt.descr, pt.color, pt.visible
                    FROM $s.programtypes pt, $s.flow_station fs
                    WHERE fs.forTV = pt.forTV
                    """.trimIndent()
                ).use { rs ->
                    while (rs.next()) {
                        insert.setString(1, rs.getString(1))
                        insert.setLong(2, rs.getLong(2))
                        insert.setString(3, rs.getString(3).trim())
                        val argb = colorrefToArgb(rs.getLong(4))
                        if (argb != null) insert.setInt(4, argb) else insert.setNull(4, java.sql.Types.INTEGER)
                        insert.setBoolean(5, !rs.getBoolean(5))
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
     * Break ENTITIES are built AFTER the airings, in one set-based pass
     * ([seedBreakEntities] -> BreakSeeder in :persistence): each break's
     * programme is chosen by DOMINANCE over its spots' legacy tags (majority;
     * ties to the first spot), and every airing attaches via break_id. The
     * airings keep their own per-spot program_id verbatim - even where it
     * disagrees with the break's - so the legacy reports stay identical.
     *
     * This is NOT the old `break_slots` catalog coming back: that keyed on
     * (station, HOUR, MINUTE), collapsed seconds, and made migratePlacements
     * join every airing back to it mid-copy. The copy stays a straight copy;
     * the entities are stamped afterwards, and their key is the full slot
     * (station, date, time).
     */
    /**
     * The indexes on `placements` that NOTHING in the migration reads, taken off
     * before the 4.1M-row insert and rebuilt afterwards.
     *
     * Building an index ONCE over finished rows is far cheaper than maintaining it
     * across four million inserts. Measured on the real dump:
     *   all indexes live .............. 77.5s
     *   dropped, then rebuilt ......... 51.5s + 2.6s + 5.9s = 60.0s  (-23%)
     *
     * Only these TWO can go. `uq_placement_slot`, `idx_placements_spot_cover` and
     * `fk_placements_program` each BACK a foreign key (station_id, spot_id,
     * program_id), and MySQL refuses to drop an index a constraint depends on.
     * Dropping the constraints as well would shave another ~13s, but it would
     * switch off referential checking for the entire load - not a trade worth
     * making for 13 seconds.
     *
     * Idempotent, so a re-run or a schema that never had them just skips.
     */
    private fun dropBulkLoadIndexes() {
        dropIndexIfExists("placements", "idx_placements_grid")
        dropIndexIfExists("placements", "idx_placements_legacy")
    }

    /**
     * legacy_id goes back IMMEDIATELY after the insert, not at the end: the SEN
     * enricher re-points every airing to its real charge with
     * `WHERE sch.id = p.legacy_id`, and without this index that join is 4.1M x
     * 4.1M. It costs 2.6s to build - never defer it.
     */
    private fun rebuildLegacyIndex() {
        createIndexIfMissing("placements", "idx_placements_legacy", "legacy_id")
    }

    /**
     * The month-grid index, built LAST. Nothing in the migration reads it (it
     * exists for the app's grid), and it is the widest index on the biggest table
     * - so it is exactly the one you do not want to carry through a bulk load.
     */
    private fun rebuildGridIndex() {
        createIndexIfMissing(
            "placements",
            "idx_placements_grid",
            "station_id, show_date, hidden, show_time, position, spot_id, program_id",
        )
    }

    private fun indexExists(table: String, index: String): Boolean =
        c.prepareStatement(
            """
            SELECT 1 FROM information_schema.statistics
            WHERE table_schema = ? AND table_name = ? AND index_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, targetSchemaName); ps.setString(2, table); ps.setString(3, index)
            ps.executeQuery().use { it.next() }
        }

    private fun dropIndexIfExists(table: String, index: String) {
        if (!indexExists(table, index)) return
        c.createStatement().use { it.executeUpdate("ALTER TABLE $t.$table DROP INDEX $index") }
    }

    private fun createIndexIfMissing(table: String, index: String, columns: String) {
        if (indexExists(table, index)) return
        c.createStatement().use { it.executeUpdate("CREATE INDEX $index ON $t.$table($columns)") }
    }

    private fun countBreakTimes(): Int =
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT COUNT(*) FROM (SELECT DISTINCT station_id, show_time FROM $t.placements) bt"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }

    /**
     * Break entities from the finished airings - see the "breaks" section note.
     * The rule (and the SQL) is BreakSeeder's, shared with the server's in-place
     * upgrade so the two paths can never pick different programmes. Returns the
     * station-scoped entity count for the log.
     *
     * Chunked BY YEAR purely for the sub-progress (the dominant-programme scan
     * over 4M airings is the transform's slowest tail): a break's voters all
     * share its show_date, so a year boundary can never split an election.
     */
    private fun seedBreakEntities(): Long {
        val years = mutableListOf<Int>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT DISTINCT YEAR(show_date) FROM $t.placements ORDER BY 1").use { rs ->
                while (rs.next()) years += rs.getInt(1)
            }
        }
        onSubProgress(0, years.size.toLong())
        years.forEachIndexed { i, year ->
            BreakSeeder.seed(
                c, targetSchemaName,
                from = java.time.LocalDate.of(year, 1, 1),
                untilExclusive = java.time.LocalDate.of(year + 1, 1, 1),
            )
            onSubProgress((i + 1).toLong(), years.size.toLong())
        }
        return c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $t.breaks").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    // ─────────────────────────────────────────────────────── customers ──

    /**
     * Every legacy customer id the migrated data references - across ALL the
     * mapped flows, because customers belong to the GROUP.
     *
     * This is the heart of the fix. The same ERP customer advertises on the TV
     * station and on the radio station; the old per-flow run wrote him twice,
     * into two schemas that could never be joined. The set here is a union, so
     * he lands exactly once - and `customers.code` (his zero-padded id) stays
     * unique group-wide, as its UNIQUE key now insists.
     */
    private fun referencedCustomerIds(): Set<Long> {
        val ids = sortedSetOf<Long>()
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT DISTINCT m.cusID FROM $s.messages m$flowFrom WHERE $flowWhere"
            ).use { rs -> while (rs.next()) ids += maxOf(rs.getLong(1), 0L) }
            st.executeQuery(
                """
                SELECT DISTINCT d.traid FROM $s.docref d, $s.messages m$flowFrom
                WHERE m.contractID = d.docid
                  AND $flowWhere
                  AND d.traid > 0
                """.trimIndent()
            ).use { rs -> while (rs.next()) ids += rs.getLong(1) }
            // END CLIENTS of triangular docs whose lee resolves to a tra id -
            // they need a customer row even if they never bought anything
            // directly.
            st.executeQuery(
                """
                SELECT DISTINCT map.traid FROM $s.docref d, $s.lee_tra_map map, $s.messages m$flowFrom
                WHERE map.lee = d.targetleeid
                  AND m.contractID = d.docid
                  AND $flowWhere
                  AND d.targetleeid <> d.pelatislee
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
                UPDATE $t.customers tc,
                       (SELECT traid, MIN(lee) AS lee FROM $s.lee_tra_map GROUP BY traid) m
                SET tc.legacy_lee_id = m.lee
                WHERE m.traid = tc.legacy_id
                """.trimIndent()
            )
        }

        // END CLIENTS of triangular docs whose lee has NO tra mapping: they
        // never bought directly, so MySQL knows them only as a lee id (their
        // name lives in Oracle alone). Synthesize them, keyed by lee.
        val unresolvedLees = c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT DISTINCT d.targetleeid FROM $s.docref d, $s.messages m$flowFrom
                WHERE m.contractID = d.docid
                  AND $flowWhere
                  AND d.targetleeid <> d.pelatislee AND d.targetleeid > 0
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
     * One target contract per legacy contract/document id the dump CHARGES
     * anything to. `docref` supplies real docno/dotid/customer when the
     * ERP shadow row survived; otherwise the contract is synthesized around
     * the spot's own customer.
     *
     * THREE sources, unioned - and the union is load-bearing:
     *
     *  - `messages.contractID`: each message's CURRENT default link;
     *  - `schedule.docID`: the PER-AIRING charges. This is the fix of
     *    2026-07-20: when the ERP renews a deal the legacy re-links the
     *    message to the NEW document, so a fully-consumed document can be
     *    referenced by NO message any more while its airings still charge it
     *    in `schedule`. Sourcing contracts from messages alone silently
     *    dropped 2,434 such documents on the crete dump - and their 536k
     *    airings (~20%!) then fell back to each spot's default line, i.e.
     *    the WRONG contract (ΖΩΓΡΑΦΑΚΗ doc 703 was the tell);
     *  - `z_commercials.docid`: documents the ERP line view carries - a deal
     *    sold but never aired here still deserves its row (never-aired).
     *
     * ONE row per document, ACROSS the flows: the GROUP BY folds a document
     * used by both the TV and the radio messages into a single contract. That
     * is the whole point - contract 500 keeps its TV line and its radio lines
     * together, and the ΣΥΜΒΟΛΑΙΑ views can finally answer "how much of this
     * deal has aired" without adding up two schemas.
     */
    private fun migrateContracts(): Pair<Int, Int> {
        data class Row(val docId: Long, val traid: Long?, val docNo: String?, val dotId: Int?, val fallbackCus: Long?, val isGift: Boolean)

        val rows = mutableListOf<Row>()
        c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT u.docid, MIN(d.traid), MIN(d.docno), MIN(d.dotid), MIN(u.cus), MAX(COALESCE(sl.isGift,0))
                FROM (
                    SELECT m.contractID AS docid, GREATEST(m.cusID,0) AS cus
                    FROM $s.messages m
                    $flowJoin
                    WHERE m.contractID > 0
                    UNION ALL
                    SELECT DISTINCT sch.docID, GREATEST(m.cusID,0)
                    FROM $s.schedule sch
                    JOIN $s.messages m ON m.id = sch.messageID
                    $flowJoin
                    WHERE sch.docID > 0
                    UNION ALL
                    -- NULL cus on purpose: MIN() ignores it, so a document seen
                    -- only here resolves its payer through docref alone.
                    SELECT DISTINCT z.docid, NULL FROM $s.z_commercials z WHERE z.docid > 0
                ) u
                LEFT JOIN $s.docref d ON d.docid = u.docid
                LEFT JOIN $s.sld sl ON sl.dotid = d.dotid
                GROUP BY u.docid
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    rows += Row(
                        docId = rs.getLong(1),
                        traid = rs.getLong(2).takeIf { !rs.wasNull() && it > 0 },
                        docNo = rs.getString(3)?.ifBlank { null },
                        dotId = rs.getInt(4).takeIf { !rs.wasNull() },
                        fallbackCus = rs.getLong(5).takeIf { !rs.wasNull() && it > 0 },
                        isGift = rs.getInt(6) == 1,
                    )
                }
            }
        }

        // The payer must resolve to a MIGRATED customer, or the INSERT's
        // subselect turns NULL and the whole batch dies on the NOT NULL
        // column. Docs from messages always resolve (migrateCustomers built
        // the customers from those very cusIDs); a z_commercials-only doc
        // may name a payer this dump never aired for - skip it LOUDLY.
        val knownCustomers = HashSet<Long>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT legacy_id FROM $t.customers WHERE legacy_id IS NOT NULL").use { rs ->
                while (rs.next()) knownCustomers += rs.getLong(1)
            }
        }

        var synthetic = 0
        var skippedNoPayer = 0
        var inserted = 0
        c.prepareStatement(
            """
            INSERT INTO $t.contracts(legacy_docid, number, doc_type, is_gift, customer_id, synthetic)
            VALUES(?,?,?,?,(SELECT id FROM $t.customers WHERE legacy_id = ?),?)
            """.trimIndent()
        ).use { ps ->
            for (r in rows) {
                val payer = r.traid?.takeIf { it in knownCustomers }
                    ?: r.fallbackCus?.takeIf { it in knownCustomers }
                if (payer == null) {
                    skippedNoPayer++
                    continue
                }
                val isSynthetic = r.docNo == null
                if (isSynthetic) synthetic++
                inserted++
                ps.setLong(1, r.docId)
                ps.setString(2, r.docNo ?: "LEGACY-${r.docId}")
                if (r.dotId != null) ps.setInt(3, r.dotId) else ps.setNull(3, java.sql.Types.INTEGER)
                ps.setBoolean(4, r.isGift)
                ps.setLong(5, payer)
                ps.setBoolean(6, isSynthetic)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        if (skippedNoPayer > 0) {
            log("  ⚠ $skippedNoPayer documents skipped: payer unknown to this dump (z_commercials/docref-only docs)")
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

        return inserted to synthetic
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
        val charged = c.createStatement().use { st ->
            st.executeUpdate(
                """
                -- schedule.docID+lineno is the per-airing charge key, so every
                -- (document, lineno) pair it names must exist as a LINE - or
                -- migratePlacements silently falls back to the spot's default,
                -- crediting the wrong contract. Honest NULL item at the REAL
                -- lineno; the SEN enricher stamps the item from ssd afterwards.
                INSERT IGNORE INTO $t.contract_lines(contract_id, line_no, spot_type_id)
                SELECT DISTINCT tc.id, sch.lineno, NULL
                FROM $s.schedule sch
                JOIN $s.messages m ON m.id = sch.messageID
                $flowJoin
                JOIN $t.contracts tc ON tc.legacy_docid = sch.docID
                WHERE sch.docID > 0 AND sch.lineno > 0
                """.trimIndent()
            )
        }
        val fallback = c.createStatement().use { st ->
            st.executeUpdate(
                """
                -- Fallback for documents the z_commercials view never carried:
                -- ONE synthetic line per contract, with an UNKNOWN item class.
                -- CORRECTED 2026-07-13: this used to key off messages.messageTypeID
                -- and stamp it as the line's spot_type_id - but messageTypeID is
                -- the booked PROGRAMME, not an ERP item class, so it invented
                -- product lines selling shows. Better an honest NULL item than a
                -- confident wrong one.
                INSERT INTO $t.contract_lines(contract_id, line_no, spot_type_id)
                SELECT DISTINCT tc.id, 1000, NULL
                FROM $s.messages m$flowFrom, $t.contracts tc
                WHERE $flowWhere
                  AND tc.legacy_docid = m.contractID
                  AND m.contractID > 0
                  AND NOT EXISTS (
                      SELECT 1 FROM $t.contract_lines cl WHERE cl.contract_id = tc.id
                  )
                """.trimIndent()
            )
        }
        log("  $real product lines from z_commercials + $charged charge-key lines from schedule + $fallback fallback lines (line_no >= 1000)")
        return real + charged + fallback
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
                UPDATE $t.contracts ct,
                       (
                    SELECT cl.contract_id AS cid, MIN(p.show_date) AS mn, MAX(p.show_date) AS mx
                    FROM $t.placements p, $t.spots s, $t.contract_lines cl
                    WHERE s.id = p.spot_id
                      AND cl.id = s.contract_line_id
                      AND p.hidden = FALSE
                    GROUP BY cl.contract_id
                ) agg
                SET ct.start_date = agg.mn, ct.end_date = agg.mx, ct.dates_provisional = TRUE
                WHERE agg.cid = ct.id
                """.trimIndent()
            )
        }

    // ──────────────────────────────────────────────────── spots/placements ──

    /**
     * THE FAITHFUL UNION LAYER (owner directive): the group schema CONTAINS the
     * legacy MySQL tables as VERBATIM copies - exact table names, exact column
     * names, ALL rows. The app's working tables (spots/placements/...) are
     * DERIVED from these; the copies are the inspectable source of truth inside
     * the one functional database, and the SEN side lands next to them as `sen_*`
     * tables (the enricher writes those - `sen_` because legacy `cus`/`sld`
     * collide with the ERP names).
     *
     * NOTHING is flow-filtered any more. One legacy database becomes one group
     * database, so the copies are simply the dump - and the flow-scoped ones keep
     * their own native `forTV` column, which is now genuinely faithful (the old
     * per-flow runs sliced these tables in half and left a `forTV` column with a
     * single value in it). `emailhistory` still copies WITHOUT the heavy bodies
     * (the app's email_log keeps the capped bodies; the summaries here are
     * complete).
     */
    /** The verbatim-copy list - named once, so the sub-progress total is honest. */
    private val verbatimTables = listOf(
        "messages", "schedule", "programtypes", "docref", "z_commercials", "cus", "sld",
        "calendar_excluded_docs", "commercials_calendar_final", "roh_comments",
        "roh_print_history", "zones", "zonefillers", "emailhistory",
    )

    private fun copyLegacyTables() {
        val total = verbatimTables.count { tableExists(it) }.toLong()
        var done = 0L
        fun copy(table: String, filter: String = "", columns: String = "*") {
            if (!tableExists(table)) return
            val rows = c.createStatement().use { st ->
                st.executeUpdate("DROP TABLE IF EXISTS $t.$table")
                st.executeUpdate("CREATE TABLE $t.$table LIKE $s.$table")
                try {
                    st.executeUpdate("ALTER TABLE $t.$table ENGINE=InnoDB")
                } catch (e: java.sql.SQLException) {
                    // e.g. a MyISAM-only AUTO_INCREMENT/key layout - fidelity
                    // beats the engine, keep the table exactly as dumped
                    log("  ($table stays MyISAM: ${e.message})")
                }
                st.executeUpdate("INSERT INTO $t.$table SELECT $columns FROM $s.$table $filter")
            }
            log("  %-28s %,d rows (verbatim)".format(table, rows))
            onSubProgress(++done, total)
        }
        copy("messages")
        copy("schedule")
        copy("programtypes")
        copy("docref")
        copy("z_commercials")
        copy("cus")
        copy("sld")
        copy("calendar_excluded_docs")
        copy("commercials_calendar_final")
        copy("roh_comments")
        copy("roh_print_history")
        copy("zones")
        copy("zonefillers")
        copy(
            "emailhistory",
            columns = "id, subject, emailFrom, cusID, periodRequested, entryDate, entryTime, " +
                "recipientEmailAddress, NULL, reportType",
        )
    }

    /**
     * The ERP ITEM-CLASS catalog, seeded from the item classes the contract
     * lines actually reference (`z_commercials.mciid`).
     *
     * CORRECTED 2026-07-13: this used to mirror `programtypes` - the PROGRAMME
     * catalog (ΚΛΕΨΑ, ΞΕΝΗ ΤΑΙΝΙΑ, ΠΑΠΑΔΑΚΗΣ ΧΡΙΣΤΟΦΟΡΟΣ). `mciid` lives in the
     * ERP's own id space, so joining it to `programtypes.id` matched on
     * coincidental small integers: 55% of mciids had no programtypes row, and
     * the rest paired a show with an unrelated item. The item NAME exists only
     * in the ERP export (SEN `sti.csv`), so the dump seeds the IDS and
     * [SenErpEnricher] fills name/item_code - verified 102/102 mciids resolve
     * against STI.
     */
    private fun migrateSpotTypes(): Int {
        if (!tableExists("z_commercials")) return 0
        return c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.spot_types(legacy_id, name)
                SELECT DISTINCT z.mciid, ''
                FROM $s.z_commercials z
                WHERE z.mciid > 0
                """.trimIndent()
            )
        }
    }

    /**
     * The spot belongs to its END CLIENT ("My Advert Company has a contract
     * FOR the customer Unilever, FOR WHOM the spot is scheduled"). Legacy
     * `messages.cusID` is the PAYER (== docref.traid 99.9%), so on triangular
     * docs the customer is resolved through `targetleeid` instead; the payer
     * stays reachable via the contract (contracts.customer_id = traid).
     *
     * `messageTypeID` becomes the spot's BOOKED PROGRAMME (`booked_program` +
     * `booked_program_id`), NOT its product - it points at `programtypes`, a
     * different id space from the ERP item class. The product is the contract
     * LINE's, resolved here for single-line documents and observed from the
     * spot's own airings for multi-line ones ([backfillSpotDefaultLines]).
     */
    private fun migrateSpots(): Int =
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.spots(station_id, legacy_id, customer_id, contract_line_id, description,
                                     duration_seconds, booked_program, booked_program_id, flow,
                                     hidden, force_position, memo)
                SELECT fs.station_id, m.id, COALESCE(tend.id, tcu.id), tl.id, m.descr, m.duration,
                       COALESCE(pt.descr, ''), tpr.id, 'ΡΟΗ', m.hidden, NULLIF(m.forcePosition, -1), m.memo
                FROM $s.messages m
                $flowJoin
                JOIN $t.customers tcu ON tcu.legacy_id = GREATEST(m.cusID, 0)
                LEFT JOIN $s.docref d ON d.docid = m.contractID
                                     AND d.targetleeid <> d.pelatislee AND d.targetleeid > 0
                LEFT JOIN $t.customers tend ON tend.legacy_lee_id = d.targetleeid
                LEFT JOIN $t.contracts tct ON tct.legacy_docid = m.contractID
                -- messageTypeID names the PROGRAMME the spot was booked into
                -- (it is NOT an ERP item class - see migrateSpotTypes). Both
                -- programme joins MUST also match the flow/station: programme ids
                -- restart per forTV, so joining on the id alone would label a TV
                -- spot with the radio station's show.
                LEFT JOIN $s.programtypes pt ON pt.id = m.messageTypeID AND pt.forTV = m.forTV
                LEFT JOIN $t.programs tpr ON tpr.legacy_id = m.messageTypeID
                                         AND tpr.station_id = fs.station_id
                -- Default product line. The message carries no line number, so
                -- it is only unambiguous when the document sells ONE item class
                -- (68% of docs). Multi-line docs are backfilled AFTER placements
                -- from the spot's own airings (see backfillSpotDefaultLines) -
                -- the airing's docID+lineno is the only real per-charge truth.
                LEFT JOIN $t.contract_lines tl ON tl.id = (
                    SELECT cl.id FROM $t.contract_lines cl
                    WHERE cl.contract_id = tct.id
                      AND (SELECT COUNT(*) FROM $t.contract_lines cl2
                           WHERE cl2.contract_id = tct.id) = 1
                    LIMIT 1
                )
                """.trimIndent()
            )
        }

    /**
     * A spot's DEFAULT product line, for the airings that carry no docID+lineno
     * of their own (15% of placements fall back to it).
     *
     * The legacy message has no line number, so on a multi-line document the
     * default cannot be derived from the message alone. It CAN be observed: take
     * the line the spot's own airings actually charge to, most often. That is
     * evidence, not a guess - and it runs after placements for exactly that
     * reason. Single-line documents were already resolved in [migrateSpots].
     *
     * (CORRECTED 2026-07-13: the old resolution matched the contract's line whose
     * spot_type_id equalled the spot's - both sides came from the false
     * messageTypeID/mciid conflation, so it linked by a meaningless key.)
     */
    private fun backfillSpotDefaultLines(): Int =
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                UPDATE $t.spots sp,
                       (
                    SELECT p.spot_id, p.contract_line_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY p.spot_id
                               ORDER BY COUNT(*) DESC, p.contract_line_id
                           ) AS rn
                    FROM $t.placements p
                    WHERE p.contract_line_id IS NOT NULL
                    GROUP BY p.spot_id, p.contract_line_id
                ) modal
                SET sp.contract_line_id = modal.contract_line_id
                WHERE modal.spot_id = sp.id AND modal.rn = 1
                  AND sp.contract_line_id IS NULL
                """.trimIndent()
            )
        }

    /**
     * The airings - a STRAIGHT COPY of legacy `schedule`: showDate -> show_date,
     * showTime -> show_time, showOrder -> position. No break is looked up
     * MID-COPY: break entities are stamped afterwards in one set-based pass
     * ([seedBreakEntities]), so this insert stays a pure copy. (The old
     * `break_slots` catalog used to be joined right here, per airing, to
     * recover an id it had just thrown away.)
     *
     * The spot decides the station (legacy `schedule` carries no forTV of its own),
     * and it is stamped onto the airing: a group's stations share this schema, so
     * without it the TV channel's 11:00 break and the radio station's would be one
     * break. Every station-scoped join must agree with that station:
     *
     *  - the ROW_NUMBER partition includes it, or the two stations' airings
     *    interleave and their positions inside a break come out wrong.
     *  - the PROGRAMME join matches it too (programme ids restart per flow - see
     *    migratePrograms).
     *
     * The contract-line join stays group-scoped on purpose: that is the airing's
     * actual charge, and the contract is the group's.
     *
     * Partitioning on the full `showTime` (not HOUR+MINUTE, as the break-catalog
     * join did) is also strictly more faithful: it can no longer fold two airings
     * a few seconds apart into one break.
     */
    private fun migratePlacements(): Int {
        // CHUNKED BY YEAR, for the sub-progress: this is the pipeline's biggest
        // single statement (4M+ rows), and a step bar alone just sits on it.
        // Year chunks are CORRECT by construction - the ROW_NUMBER partitions
        // are (station, date, time), and a date never spans two years - and
        // the bar advances by REAL rows (each chunk's own count), not by a
        // guess. The dump's schedule has no index leading on showDate
        // (forACTIVITIES buries it third), so the chunks get one first -
        // one-off cost, then every chunk is a range scan.
        c.createStatement().use { st ->
            try {
                st.executeUpdate("CREATE INDEX idx_mig_showdate ON $s.schedule(showDate)")
            } catch (_: java.sql.SQLException) {
                // already there - a resumed run against the same scratch
            }
        }
        val years = mutableListOf<Pair<Int, Long>>()
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT YEAR(showDate), COUNT(*) FROM $s.schedule WHERE showDate >= '1900-01-01' GROUP BY YEAR(showDate) ORDER BY 1"
            ).use { rs ->
                while (rs.next()) years += rs.getInt(1) to rs.getLong(2)
            }
        }
        val totalRows = years.sumOf { it.second }
        var doneRows = 0L
        var inserted = 0
        onSubProgress(0, totalRows)
        for ((year, rows) in years) {
            inserted += c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO $t.placements(legacy_id, station_id, spot_id, show_date, show_time, position,
                                              duration_seconds, contract_line_id, program_id, played, hidden)
                    SELECT sch.id, ts.station_id, ts.id, sch.showDate, sch.showTime,
                           ROW_NUMBER() OVER (
                               PARTITION BY ts.station_id, sch.showDate, sch.showTime
                               ORDER BY sch.showOrder, sch.id
                           ) - 1,
                           sch.durationSecs, pcl.id, tp.id, sch.played, sch.hideSchedule
                    FROM $s.schedule sch
                    JOIN $t.spots ts ON ts.legacy_id = sch.messageID
                    -- the airing's ACTUAL charge: schedule.docID + lineno name the
                    -- product line directly (NULL falls back to the spot's default)
                    LEFT JOIN $t.contracts pct ON pct.legacy_docid = sch.docID
                    LEFT JOIN $t.contract_lines pcl ON pcl.contract_id = pct.id AND pcl.line_no = sch.lineno
                    LEFT JOIN $t.programs tp ON tp.legacy_id = sch.programID
                                            AND tp.station_id = ts.station_id
                    WHERE sch.showDate >= '$year-01-01' AND sch.showDate < '${year + 1}-01-01'
                    """.trimIndent()
                )
            }
            doneRows += rows
            onSubProgress(doneRows, totalRows)
        }
        return inserted
    }

    // ───────────────────────────────────────────────── comments / audit ──

    /**
     * ≙ legacy `roh_comments`, keyed by (date, forTV) - so the station is part of
     * the key here too. Without it the two stations' comments for the same date
     * would collide, and INSERT IGNORE would quietly keep only the first.
     */
    private fun migrateFlowComments(): Int {
        if (!tableExists("roh_comments")) return 0
        return c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT IGNORE INTO $t.flow_comments(station_id, show_date, comments)
                SELECT fs.station_id, rc.startDate, rc.comments
                FROM $s.roh_comments rc, $s.flow_station fs
                WHERE fs.forTV = rc.forTV
                  AND rc.comments <> '' AND rc.startDate >= '1900-01-01'
                """.trimIndent()
            )
        }
    }

    private fun migratePrintAudit(): Int {
        if (!tableExists("roh_print_history")) return 0
        return c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.print_audit(station_id, printed_date, username, created_at)
                SELECT fs.station_id, rp.printedDate, rp.username, rp.mystamp
                FROM $s.roh_print_history rp, $s.flow_station fs
                WHERE fs.forTV = rp.forTV
                  AND rp.printedDate >= '1900-01-01'
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

    /**
     * Recovers each customer's DEFAULT email from the sent-email archive.
     *
     * The legacy `cus` table barely stored emails, so [migrateCustomers] falls
     * back to a `customer{id}@example.gr` placeholder - which is why a customer
     * who has, in fact, been emailed for years shows a fake default. But the
     * address that ACTUALLY received those reports is in `emailhistory`
     * (migrated verbatim into `email_log.recipient`), so we take the MOST RECENT
     * real recipient per customer and use it as the default.
     *
     * Rules, matching [SenErpEnricher.placeholderEmail]: fill ONLY a blank or
     * `@example.` placeholder - a real email already on the customer (from the
     * ERP enrichment or the legacy row) is never overwritten. Multi-recipient
     * sends are all but nonexistent in the archive (one borderline row in the
     * whole 1.2 GB), but handled anyway: the FIRST address wins.
     */
    private fun enrichCustomerEmailsFromHistory(): Int {
        if (!tableExists("emailhistory")) return 0
        return c.createStatement().use { st ->
            st.executeUpdate(
                """
                UPDATE $t.customers cu
                JOIN (
                    SELECT customer_code, first_addr FROM (
                        SELECT customer_code,
                               TRIM(SUBSTRING_INDEX(REPLACE(REPLACE(recipient, ',', ';'), ' ', ''), ';', 1)) AS first_addr,
                               ROW_NUMBER() OVER (
                                   PARTITION BY customer_code ORDER BY sent_at DESC, id DESC
                               ) AS rn
                        FROM $t.email_log
                        WHERE recipient <> '' AND recipient NOT LIKE '%@example.%'
                    ) ranked
                    WHERE rn = 1
                      AND first_addr LIKE '%_@_%._%'
                      AND first_addr NOT LIKE '%@example.%'
                ) h ON h.customer_code = cu.code
                SET cu.email = h.first_addr
                WHERE cu.email IS NULL OR cu.email = '' OR cu.email LIKE '%@example.%'
                """.trimIndent()
            )
        }
    }

    // ───────────────────────────────────────────────── zones (pricing) ──

    /**
     * The airtime price list lives ONLY as the faithful union copies
     * (`zones`/`zonefillers`, written verbatim with full price history by
     * [copyLegacyTables]) - there is no derived app table to fill. This just
     * reports the copied counts for the summary.
     */
    private fun migrateZones(): Pair<Int, Int> {
        fun count(table: String): Int = if (!tableExists(table)) 0 else
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM $t.$table").use { rs -> rs.next(); rs.getInt(1) }
            }
        return count("zones") to count("zonefillers")
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

    /**
     * `migrated_at` is the GROUP's (one dump, one run, one group). Each station
     * records the flow it came from, and demo_seed=false so the server never
     * fills its empty months with demo spots.
     */
    private fun writeMeta() {
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $t.group_meta(meta_key, meta_value)
                VALUES ('migrated_at', NOW())
                ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)
                """.trimIndent()
            )
            st.executeUpdate(
                """
                INSERT INTO $t.station_meta(station_id, meta_key, meta_value)
                SELECT fs.station_id, 'migrated_fortv', CAST(fs.forTV AS CHAR) FROM $s.flow_station fs
                ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)
                """.trimIndent()
            )
            st.executeUpdate(
                """
                INSERT INTO $t.station_meta(station_id, meta_key, meta_value)
                SELECT fs.station_id, 'demo_seed', 'false' FROM $s.flow_station fs
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
        /**
         * How many [step] calls [run] makes. Keep it in sync when adding a step -
         * but a stale value is survivable by construction: [step] clamps the total
         * so the bar can never exceed 100%, and [run] finishes it at exactly 100%
         * whatever this says. A drift costs a slightly-off bar mid-run, never a
         * wrong one and never a crash.
         */
        const val TOTAL_STEPS = 18

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
