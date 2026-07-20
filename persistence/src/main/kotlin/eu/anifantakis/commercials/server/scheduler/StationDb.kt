package eu.anifantakis.commercials.server.scheduler

import eu.anifantakis.commercials.server.stations.StationConfig
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * ONE STATION'S VIEW over its group's database ([GroupDb]).
 *
 * It owns no pool and no schema - it borrows the group's - and its whole job is
 * to scope every statement to its `station_id`. Instances are created and cached
 * by StationRegistry; the schema itself is created once per group by [GroupDb],
 * which also documents the group-vs-station split of the tables.
 *
 * ⚠ AUTHORIZATION. When each station had its own database, "spot 123 belongs to
 * this station" was true BY CONSTRUCTION - a caller with a grant on the radio
 * station physically could not reach the TV station's rows. Sharing a database
 * removes that guarantee, and several methods here take a raw id straight from
 * the client ([addPlacement], [deletePlacement], [emailLogBody],
 * [contractLineSpots]). Every one of them must re-derive the station in SQL.
 * Skipping the check does not throw - it silently drops a radio spot into a TV
 * break.
 *
 * A BREAK is not among those ids either: the client addresses it by TIME, and
 * the station is stamped on the airing from [stationId] here rather than taken
 * from the client. The break entity's `breaks.id` exists (placements FK it) but
 * never leaves the server - resolution is always (station, date, time). Both
 * stations own an 11:00 break and they are different breaks - which the
 * `station_id` on every statement below is what keeps apart.
 *
 * The exceptions are the GROUP-scoped tables, where sharing IS the feature:
 * [customerByCode] and [searchParties] deliberately see the whole group, so the
 * same customer is found from either station.
 */
class StationDb(private val group: GroupDb, private val station: StationConfig) {

    companion object {
        /**
         * Full email bodies kept per customer; older bodies are evicted
         * (summaries stay). Public: the legacy migration applies the SAME
         * cap when importing the old `emailhistory` archive.
         */
        const val EMAIL_BODY_RETENTION_PER_CUSTOMER = 10
    }

    /** The station this view is scoped to - the value of every `station_id` filter. */
    private val stationId: String = station.id

    /** A pooled connection from the GROUP's pool. */
    fun connection(): Connection = group.connection()

    // ────────────────────────────────────────────────────────── bootstrap ──

    /**
     * Registers this station in its (already created) group schema and seeds
     * its demo data.
     *
     * [seedDemo] false is for MIGRATED stations: the migration tool builds
     * breaks/catalog from the legacy dump instead of demo data, and empty months
     * must STAY empty. The choice is recorded per station in station_meta
     * (`demo_seed`) with INSERT IGNORE, so a later server bootstrap with the
     * default `true` can never flip a migrated station back to demo seeding.
     */
    fun bootstrap(seedDemo: Boolean = true) {
        connection().use { c ->
            c.prepareStatement(
                "INSERT INTO stations(id, name) VALUES(?,?) ON DUPLICATE KEY UPDATE name = VALUES(name)"
            ).use { ps ->
                ps.setString(1, stationId)
                ps.setString(2, station.name)
                ps.executeUpdate()
            }
            // INSERT IGNORE: first writer wins - a migrated station stays
            // demo_seed=false forever, whoever bootstraps it afterwards.
            c.prepareStatement(
                "INSERT IGNORE INTO station_meta(station_id, meta_key, meta_value) VALUES(?,'demo_seed',?)"
            ).use { ps ->
                ps.setString(1, stationId)
                ps.setString(2, if (seedDemo) "true" else "false")
                ps.executeUpdate()
            }

            // No breaks are seeded: there is nothing to seed. A break is what a
            // GROUP BY on the airing time returns, so a demo station's breaks
            // appear when ensureMonthSeeded invents its airings.
            if (seedDemo && isDemoSeedEnabled(c)) {
                seedGroupCatalogIfEmpty(c)
                seedStationSpotsIfEmpty(c)
            }
        }
    }

    private fun isDemoSeedEnabled(c: Connection): Boolean =
        c.prepareStatement(
            "SELECT meta_value FROM station_meta WHERE station_id = ? AND meta_key = 'demo_seed'"
        ).use { ps ->
            ps.setString(1, stationId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) == "true" else true }
        }

    /**
     * The GROUP's demo catalog: customers (with ΑΦΜ) and one gift contract each,
     * carrying TWO product lines. Runs once per group - the first station of the
     * group to boot creates it, the others find it and share it, which is the
     * whole point of the model.
     *
     * The two lines exist so the demo shows the real shape: one contract selling
     * on several media. Each station's spots charge to a DIFFERENT line of the
     * same contract (see [seedStationSpotsIfEmpty]), so a demo group reproduces
     * "Ανυφαντάκης bought 1 TV spot and 2 radio spots on contract 500" without a
     * migration.
     */
    private fun seedGroupCatalogIfEmpty(c: Connection) {
        val count = c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM customers").use { rs -> rs.next(); rs.getInt(1) }
        }
        if (count > 0) return

        c.autoCommit = false
        try {
            val customerIds = mutableListOf<Long>()
            c.prepareStatement(
                "INSERT INTO customers(code, name, vat_number) VALUES(?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                for (cust in demoCustomers) {
                    ps.setString(1, cust.code)
                    ps.setString(2, cust.name)
                    ps.setString(3, cust.vat)
                    ps.executeUpdate()
                    ps.generatedKeys.use { rs -> rs.next(); customerIds += rs.getLong(1) }
                }
            }

            c.prepareStatement(
                "INSERT INTO contracts(number, is_gift, customer_id, entry_date) VALUES(?,TRUE,?,CURDATE())",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                customerIds.forEachIndexed { index, customerId ->
                    ps.setString(1, "DEMO-${1000 + index}")
                    ps.setLong(2, customerId)
                    ps.executeUpdate()
                    val contractId = ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
                    c.prepareStatement(
                        "INSERT INTO contract_lines(contract_id, line_no, desired_qty) VALUES(?,?,0)"
                    ).use { lps ->
                        for (lineNo in 1..DEMO_LINES_PER_CONTRACT) {
                            lps.setLong(1, contractId)
                            lps.setInt(2, lineNo)
                            lps.addBatch()
                        }
                        lps.executeBatch()
                    }
                }
            }
            c.commit()
        } catch (e: Exception) {
            c.rollback(); throw e
        } finally {
            c.autoCommit = true
        }
    }

    /**
     * This station's demo spot catalog, charged to the group's shared contract
     * lines. The line each station picks is its index in the group, so sibling
     * stations sell different lines of the same contract.
     *
     * The customer and line ids are READ BACK from the database rather than
     * remembered from an insert: a sibling station may have created them.
     */
    private fun seedStationSpotsIfEmpty(c: Connection) {
        val count = c.prepareStatement("SELECT COUNT(*) FROM spots WHERE station_id = ?").use { ps ->
            ps.setString(1, stationId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        if (count > 0) return

        // Which line of each demo contract is THIS station's.
        val stationIndex = group.config.stations.indexOfFirst { it.id == stationId }.coerceAtLeast(0)
        val lineNo = (stationIndex % DEMO_LINES_PER_CONTRACT) + 1

        val lineIdByCustomer = c.prepareStatement(
            """
            SELECT ct.customer_id, cl.id
            FROM contract_lines cl, contracts ct
            WHERE ct.id = cl.contract_id
              AND cl.line_no = ? AND ct.number LIKE 'DEMO-%'
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, lineNo)
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getLong(1), rs.getLong(2)) }
            }
        }
        if (lineIdByCustomer.isEmpty()) return
        val customerIds = lineIdByCustomer.keys.sorted()

        val random = kotlin.random.Random(stationId.hashCode())
        c.autoCommit = false
        try {
            c.prepareStatement(
                """
                INSERT INTO spots(station_id, customer_id, contract_line_id, description,
                                  duration_seconds, booked_program, flow)
                VALUES(?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                repeat(DEMO_SPOTS_PER_STATION) {
                    val customerId = customerIds[random.nextInt(customerIds.size)]
                    ps.setString(1, stationId)
                    ps.setLong(2, customerId)
                    ps.setLong(3, lineIdByCustomer.getValue(customerId))
                    ps.setString(4, demoMessages[random.nextInt(demoMessages.size)])
                    ps.setInt(5, demoDurations[random.nextInt(demoDurations.size)])
                    ps.setString(6, demoSpotTypes[random.nextInt(demoSpotTypes.size)])
                    ps.setString(7, "ΡΟΗ")
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
        } catch (e: Exception) {
            c.rollback(); throw e
        } finally {
            c.autoCommit = true
        }
    }

    // ─────────────────────────────────────────────────────────── queries ──

    data class PlacementStats(val placements: Long, val minDate: String?, val maxDate: String?)

    /**
     * Quick footprint of THIS station's data (Databases admin screen).
     *
     * NO JOIN. The airing carries its own `station_id`, so reaching through
     * `spots` to find out whose it was is not just redundant - it was a full
     * table scan of every placement in the group (4.1M rows) plus a primary-key
     * lookup per row, measured at **5.3 seconds**. Reading the station straight
     * off the airing turns it into a covering scan of `uq_placement_slot`
     * (station_id first), measured at **0.57s** - and the numbers are identical.
     *
     * The join was correct when it was written: `placements` had no station then,
     * only a break that had one. It became dead weight the moment the break table
     * was retired and the station moved onto the airing (see GroupDb).
     */
    fun placementStats(): PlacementStats =
        connection().use { c ->
            c.prepareStatement(
                """
                SELECT COUNT(*), MIN(show_date), MAX(show_date)
                FROM placements
                WHERE station_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, stationId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    PlacementStats(rs.getLong(1), rs.getString(2), rs.getString(3))
                }
            }
        }

    // ───────────────────────────────────────────────── email audit log ──

    data class EmailLogEntry(
        val customerCode: String,
        val customerName: String,
        val recipient: String,
        val subject: String,
        val year: Int,
        val month: Int,
        val spotCount: Int,
        val transmissionCount: Int,
        val bodyHtml: String?,
        val sentBy: String,
        val status: String,      // SENT | FAILED
        val error: String? = null,
    )

    data class EmailLogRow(
        val id: Long,
        val customerCode: String,
        val customerName: String,
        val recipient: String,
        val subject: String,
        val year: Int,
        val month: Int,
        val spotCount: Int,
        val transmissionCount: Int,
        val sentBy: String,
        val sentAt: String,
        val status: String,
        val error: String?,
    )

    /**
     * Records one send attempt (success or failure) in the audit archive.
     * Metadata (who/what/when) is kept forever; the heavy HTML BODY is capped
     * at [EMAIL_BODY_RETENTION_PER_CUSTOMER] per customer - archiving a new
     * body evicts the oldest body over that limit (its summary row survives),
     * so the archive can't grow unbounded the way the legacy `emailhistory`
     * did (1.2 GB of retained bodies).
     */
    fun logEmail(entry: EmailLogEntry): Long =
        connection().use { c ->
            val id = c.prepareStatement(
                """
                INSERT INTO email_log(station_id, customer_code, customer_name, recipient, subject,
                    period_year, period_month, spot_count, transmission_count, body_html,
                    sent_by, status, error)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, stationId)
                ps.setString(2, entry.customerCode)
                ps.setString(3, entry.customerName)
                ps.setString(4, entry.recipient)
                ps.setString(5, entry.subject)
                ps.setInt(6, entry.year)
                ps.setInt(7, entry.month)
                ps.setInt(8, entry.spotCount)
                ps.setInt(9, entry.transmissionCount)
                if (entry.bodyHtml != null) ps.setString(10, entry.bodyHtml) else ps.setNull(10, java.sql.Types.LONGVARCHAR)
                ps.setString(11, entry.sentBy)
                ps.setString(12, entry.status)
                if (entry.error != null) ps.setString(13, entry.error.take(512)) else ps.setNull(13, java.sql.Types.VARCHAR)
                ps.executeUpdate()
                ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
            }
            // Only SENT rows carry a body; evict the oldest bodies beyond the cap.
            if (entry.bodyHtml != null) pruneEmailBodies(c, entry.customerCode)
            id
        }

    /**
     * Nulls the body_html of all but the newest [EMAIL_BODY_RETENTION_PER_CUSTOMER]
     * bodied rows for a customer. The double-nested subquery is required: MySQL
     * cannot target the same table it selects from in an UPDATE unless the
     * selection is wrapped in a derived table.
     *
     * The cap is per CUSTOMER and GROUP-wide, not per station: the customer is a
     * group-level entity, and the point of the cap is to bound what the archive
     * retains for them in total.
     */
    private fun pruneEmailBodies(c: Connection, customerCode: String) {
        c.prepareStatement(
            """
            UPDATE email_log SET body_html = NULL
            WHERE customer_code = ? AND body_html IS NOT NULL AND id NOT IN (
                SELECT id FROM (
                    SELECT id FROM email_log
                    WHERE customer_code = ? AND body_html IS NOT NULL
                    ORDER BY sent_at DESC, id DESC
                    LIMIT $EMAIL_BODY_RETENTION_PER_CUSTOMER
                ) keep
            )
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, customerCode)
            ps.setString(2, customerCode)
            ps.executeUpdate()
        }
    }

    /**
     * Recent send history (metadata only), newest first; optionally one customer.
     *
     * `station_id IS NULL` rows are the imported legacy `emailhistory` archive:
     * that table carried no forTV, so those sends belong to the GROUP and are
     * shown from any of its stations rather than attributed to one by guesswork.
     */
    fun recentEmailLog(limit: Int, clientCode: String? = null): List<EmailLogRow> =
        connection().use { c ->
            val sql = buildString {
                append("SELECT id, customer_code, customer_name, recipient, subject, period_year, period_month, ")
                append("spot_count, transmission_count, sent_by, sent_at, status, error FROM email_log ")
                append("WHERE (station_id = ? OR station_id IS NULL) ")
                if (clientCode != null) append("AND customer_code = ? ")
                append("ORDER BY sent_at DESC, id DESC LIMIT ?")
            }
            c.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setString(i++, stationId)
                if (clientCode != null) ps.setString(i++, clientCode)
                ps.setInt(i, limit.coerceIn(1, 500))
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            EmailLogRow(
                                id = rs.getLong("id"),
                                customerCode = rs.getString("customer_code"),
                                customerName = rs.getString("customer_name"),
                                recipient = rs.getString("recipient"),
                                subject = rs.getString("subject"),
                                year = rs.getInt("period_year"),
                                month = rs.getInt("period_month"),
                                spotCount = rs.getInt("spot_count"),
                                transmissionCount = rs.getInt("transmission_count"),
                                sentBy = rs.getString("sent_by"),
                                sentAt = rs.getString("sent_at"),
                                status = rs.getString("status"),
                                error = rs.getString("error"),
                            )
                        )
                    }
                }
            }
        }

    /** The stored HTML body of a logged send (re-view exactly as delivered). */
    fun emailLogBody(id: Long): String? =
        connection().use { c ->
            c.prepareStatement(
                "SELECT body_html FROM email_log WHERE id = ? AND (station_id = ? OR station_id IS NULL)"
            ).use { ps ->
                ps.setLong(1, id)
                ps.setString(2, stationId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    data class CustomerContact(val name: String, val email: String?)

    data class PartyRow(
        val code: String,
        val name: String,
        val email: String?,
        val vatNumber: String?,
        val phone: String?,
        /** Main address composed for display, e.g. "ΑΓΡΙΑΝΑ ΧΕΡΣΟΝΗΣΟΥ, 70014 ΧΕΡΣΟΝΗΣΟΣ". */
        val address: String? = null,
        val spotCount: Int,
        val placementCount: Int,
    )

    /**
     * Substring search (`%query%`, case-insensitive via the ci collation)
     * over ALL parties that ever aired anything, busiest first with
     * all-time counts. Not month-scoped: the operator finds the party
     * first, then picks a year/month from [partyActivity].
     *
     * [byTrader] false searches CUSTOMERS - the party whose spot airs
     * (legacy `cusID`); true searches TRADERS - the party paying the
     * contract (legacy `traid`). Usually the same company, but in
     * "triangular" deals an agency holds the contract for another company's
     * spots, so the two sets differ. Both live in `customers`; the
     * distinction is the join path.
     *
     * GROUP-WIDE ON PURPOSE - the one read here that is not station-scoped.
     * Customers belong to the group, so a customer who advertises only on the
     * sibling station is still found from this one (that IS the feature: the
     * same Ανυφαντάκης, whichever station you are working on). What he has
     * actually DONE here - [partyActivity], [partyContractLines],
     * [contractLineSpots], [contractStatus] - is station-scoped, so a party
     * with nothing on this station simply shows an empty activity list.
     */
    fun searchParties(
        query: String,
        byTrader: Boolean,
        limit: Int = 25,
    ): List<PartyRow> =
        connection().use { c ->
            c.prepareStatement(
                """
                SELECT cu.code, cu.name, cu.email, cu.vat_number, cu.phone,
                       TRIM(BOTH ', ' FROM CONCAT_WS(', ', cu.address_street,
                            NULLIF(TRIM(CONCAT_WS(' ', cu.address_zip, cu.address_city)), ''))) AS address,
                       COUNT(DISTINCT s.id) AS spot_count, COUNT(p.id) AS placement_count
                FROM customers cu${partyFrom(byTrader)}, placements p
                WHERE ${partyWhere(byTrader, stationScoped = false)}
                  AND p.spot_id = s.id AND p.hidden = FALSE
                  AND (cu.name LIKE ? OR cu.code LIKE ?)
                GROUP BY cu.id, cu.code, cu.name, cu.email, cu.vat_number, cu.phone,
                         cu.address_street, cu.address_zip, cu.address_city
                ORDER BY placement_count DESC, cu.name
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                val pattern = likeContains(query)
                ps.setString(1, pattern)
                ps.setString(2, pattern)
                ps.setInt(3, limit.coerceIn(1, 100))
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            PartyRow(
                                code = rs.getString("code"),
                                name = rs.getString("name"),
                                email = rs.getString("email")?.ifBlank { null },
                                vatNumber = rs.getString("vat_number")?.ifBlank { null },
                                phone = rs.getString("phone")?.ifBlank { null },
                                address = rs.getString("address")?.ifBlank { null },
                                spotCount = rs.getInt("spot_count"),
                                placementCount = rs.getInt("placement_count"),
                            )
                        )
                    }
                }
            }
        }

    data class ActivityMonth(val year: Int, val month: Int, val placements: Int)

    /**
     * The months in which a party has airings ON THIS STATION, newest first with
     * counts - feeds the year/month drill-down after the party is picked.
     */
    fun partyActivity(code: String, byTrader: Boolean): List<ActivityMonth> =
        connection().use { c ->
            c.prepareStatement(
                """
                SELECT YEAR(p.show_date) AS y, MONTH(p.show_date) AS m, COUNT(*) AS cnt
                FROM customers cu${partyFrom(byTrader)}, placements p
                WHERE ${partyWhere(byTrader, stationScoped = true)}
                  AND p.spot_id = s.id AND p.hidden = FALSE
                  AND cu.code = ?
                GROUP BY y, m
                ORDER BY y DESC, m DESC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, stationId)
                ps.setString(2, code)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(ActivityMonth(rs.getInt("y"), rs.getInt("m"), rs.getInt("cnt")))
                    }
                }
            }
        }

    // ──────────────────────────────────────── spot finder (Εύρεση) ──

    /**
     * One row per contract LINE ("product") - what the legacy Details
     * Console listed under ΣΥΜΒΟΛΑΙΑ ΠΕΛΑΤΗ. Product identity (είδος,
     * pricing) lives in the ERP and is not migrated yet, so the line is
     * presented by its contract number + line number, with computable
     * stats (spots, aired placements, aired seconds).
     */
    data class ContractLineRow(
        val lineId: Long,
        val contractNumber: String,
        val isGift: Boolean,
        val lineNo: Int,
        val desiredQty: Int,
        val spotCount: Int,
        val placements: Int,
        val totalSeconds: Long,
        val entryDate: String?,
        /**
         * The CONTRACT's period. Load-bearing for disambiguation: legacy doc
         * numbers repeat (KRIVEK holds TWO gift contracts both numbered «18»,
         * one 2023-11, one 2026-02), so number + line alone can render two
         * indistinguishable finder rows - the period is what tells them apart.
         */
        val startDate: String?,
        val endDate: String?,
    )

    /**
     * The party's contract lines: for a CUSTOMER the lines whose spots belong to
     * them; for a TRADER the lines of the contracts they pay.
     *
     * STATION-SCOPED, and this is where the group model becomes visible. A
     * contract can hold a TV line and two radio lines; scheduling on Crete TV
     * must show the TV one. So a line qualifies when it has spots ON THIS
     * STATION - plus, as an escape hatch, when it has no spots at ALL: a line
     * that has been sold but has no creative yet belongs to no medium, and
     * hiding it everywhere would make it unreachable.
     */
    fun partyContractLines(code: String, byTrader: Boolean): List<ContractLineRow> {
        // ANCHORED ON THE PARTY, in both arms - that is the whole performance
        // story of this query. It used to drive off `contract_lines` with the
        // party filter and a correlated NOT EXISTS in the WHERE, so every click
        // scanned all 31k lines (~100ms flat, whoever was selected). Each arm
        // now starts from an indexed party column:
        //
        //  - arm 1, the lines that HAVE a spot here: the customer path enters
        //    through spots.customer_id, the trader path through
        //    contracts.customer_id (the payer - the triangular split);
        //  - arm 2, the escape hatch for a line SOLD but with no creative yet:
        //    the party's own contracts, so the NOT EXISTS runs over a handful
        //    of lines instead of the table. Identical on both paths - a
        //    spot-less line has no customer but its contract does.
        //
        // The arms are disjoint by construction (has spots / has none), so
        // UNION ALL needs no dedup pass.
        val withSpots =
            if (byTrader)
                """
                SELECT cl.id AS line_id, ct.number, ct.is_gift, ct.entry_date, ct.start_date, ct.end_date,
                       cl.line_no, cl.desired_qty, COUNT(DISTINCT s.id) AS spot_count
                FROM customers cu
                JOIN contracts ct ON ct.customer_id = cu.id
                JOIN contract_lines cl ON cl.contract_id = ct.id
                JOIN spots s ON s.contract_line_id = cl.id AND s.hidden = FALSE AND s.station_id = ?
                WHERE cu.code = ?
                """.trimIndent()
            else
                """
                SELECT cl.id AS line_id, ct.number, ct.is_gift, ct.entry_date, ct.start_date, ct.end_date,
                       cl.line_no, cl.desired_qty, COUNT(DISTINCT s.id) AS spot_count
                FROM customers cu
                JOIN spots s ON s.customer_id = cu.id AND s.hidden = FALSE AND s.station_id = ?
                JOIN contract_lines cl ON cl.id = s.contract_line_id
                JOIN contracts ct ON ct.id = cl.contract_id
                WHERE cu.code = ?
                """.trimIndent()

        return connection().use { c ->
            // ── phase 1: the party's LINES (cheap - no placements touched) ──
            data class Bare(
                val lineId: Long, val number: String, val isGift: Boolean, val lineNo: Int,
                val desiredQty: Int, val spotCount: Int,
                val entryDate: String?, val startDate: String?, val endDate: String?,
            )
            val bare = c.prepareStatement(
                """
                SELECT * FROM (
                    $withSpots
                    GROUP BY cl.id, ct.number, ct.is_gift, ct.entry_date, ct.start_date, ct.end_date,
                             cl.line_no, cl.desired_qty
                    UNION ALL
                    SELECT cl.id, ct.number, ct.is_gift, ct.entry_date, ct.start_date, ct.end_date,
                           cl.line_no, cl.desired_qty, 0
                    FROM customers cu
                    JOIN contracts ct ON ct.customer_id = cu.id
                    JOIN contract_lines cl ON cl.contract_id = ct.id
                    WHERE cu.code = ?
                      AND NOT EXISTS (SELECT 1 FROM spots x WHERE x.contract_line_id = cl.id)
                ) u
                ORDER BY number, line_no
                """.trimIndent()
            ).use { ps ->
                // Bind order follows the TEXT: the station sits in arm 1's spot
                // join (before its WHERE) on both paths, then the two codes.
                ps.setString(1, stationId)
                ps.setString(2, code)
                ps.setString(3, code)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            Bare(
                                lineId = rs.getLong("line_id"),
                                number = rs.getString("number"),
                                isGift = rs.getBoolean("is_gift"),
                                lineNo = rs.getInt("line_no"),
                                desiredQty = rs.getInt("desired_qty"),
                                spotCount = rs.getInt("spot_count"),
                                entryDate = rs.getString("entry_date"),
                                startDate = rs.getString("start_date"),
                                endDate = rs.getString("end_date"),
                            )
                        )
                    }
                }
            }
            if (bare.isEmpty()) return@use emptyList()

            // ── phase 2: Αναλωμένα by the airing's CHARGE line, for JUST these
            // lines. Same COALESCE(p.contract_line_id, s.contract_line_id) read
            // path as the Break Console's Σύμβαση column and «Προβολή Βάσει
            // Συμβολαίου» - but split into its two index-friendly arms: the
            // explicit charges range-scan idx_placements_line, the default-line
            // fallback drives spots(fk_spots_line) -> placements(spot_id). The
            // first cut aggregated the WHOLE station in one derived table -
            // 4M rows and ~6s on every finder click. (The spot's duration, not
            // the airing's, per the owner's parity decision.)
            val ids = bare.map { it.lineId }
            val qs = ids.joinToString(",") { "?" }
            data class Consumed(val placements: Int, val totalSeconds: Long)
            val consumed = HashMap<Long, Consumed>()
            c.prepareStatement(
                """
                SELECT line_id, SUM(cnt) AS placements, SUM(secs) AS total_secs FROM (
                    SELECT p.contract_line_id AS line_id, COUNT(*) AS cnt, SUM(s.duration_seconds) AS secs
                    FROM placements p
                    JOIN spots s ON s.id = p.spot_id AND s.hidden = FALSE
                    WHERE p.contract_line_id IN ($qs) AND p.hidden = FALSE AND p.station_id = ?
                    GROUP BY p.contract_line_id
                    UNION ALL
                    SELECT s.contract_line_id, COUNT(*), SUM(s.duration_seconds)
                    FROM spots s
                    JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE AND p.contract_line_id IS NULL
                    WHERE s.contract_line_id IN ($qs) AND s.hidden = FALSE AND s.station_id = ?
                    GROUP BY s.contract_line_id
                ) u GROUP BY line_id
                """.trimIndent()
            ).use { ps ->
                var i = 1
                ids.forEach { ps.setLong(i++, it) }
                ps.setString(i++, stationId)
                ids.forEach { ps.setLong(i++, it) }
                ps.setString(i, stationId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        consumed[rs.getLong("line_id")] =
                            Consumed(rs.getInt("placements"), rs.getLong("total_secs"))
                    }
                }
            }

            bare.map { b ->
                val chg = consumed[b.lineId]
                ContractLineRow(
                    lineId = b.lineId,
                    contractNumber = b.number,
                    isGift = b.isGift,
                    lineNo = b.lineNo,
                    desiredQty = b.desiredQty,
                    spotCount = b.spotCount,
                    placements = chg?.placements ?: 0,
                    totalSeconds = chg?.totalSeconds ?: 0,
                    entryDate = b.entryDate,
                    startDate = b.startDate,
                    endDate = b.endDate,
                )
            }
        }
    }

    data class LineSpotRow(
        val spotId: Long,
        val description: String,
        val durationSeconds: Int,
        val placements: Int,
        /** Aired seconds (Αναλωμένα Secs in the legacy console). */
        val totalSeconds: Long,
    )

    /**
     * The spots (creatives) hanging off one contract line, ON THIS STATION.
     *
     * The station filter is not cosmetic: a shared line legitimately holds the
     * TV spot AND the radio spots, and the caller places whatever this returns
     * into one of THIS station's breaks.
     */
    fun contractLineSpots(lineId: Long): List<LineSpotRow> =
        connection().use { c ->
            c.prepareStatement(
                """
                SELECT s.id, s.description, s.duration_seconds, COUNT(p.id) AS placements,
                       -- the SPOT's duration, not the airing's (see durationSeconds
                       -- on CommercialRow); 0 for a spot that never aired.
                       COALESCE(SUM(CASE WHEN p.id IS NOT NULL THEN s.duration_seconds END), 0) AS total_secs
                FROM spots s
                -- Only the airings CHARGED to this line count as its Αναλωμένα:
                -- a spot re-links across contracts, and its default line must
                -- not absorb what it aired for another one (same charge-line
                -- doctrine as partyContractLines above).
                LEFT JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                                      AND COALESCE(p.contract_line_id, s.contract_line_id) = ?
                WHERE s.contract_line_id = ? AND s.hidden = FALSE AND s.station_id = ?
                GROUP BY s.id, s.description, s.duration_seconds
                ORDER BY s.description, s.id
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, lineId)
                ps.setLong(2, lineId)
                ps.setString(3, stationId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            LineSpotRow(
                                spotId = rs.getLong("id"),
                                description = rs.getString("description"),
                                durationSeconds = rs.getInt("duration_seconds"),
                                placements = rs.getInt("placements"),
                                totalSeconds = rs.getLong("total_secs"),
                            )
                        )
                    }
                }
            }
        }

    data class ContractStatusRow(
        val contractNumber: String,
        val isGift: Boolean,
        val startDate: String?,
        val endDate: String?,
        val renewedAt: String?,
        /** True when start/end are placement-derived placeholders (pre-ERP import). */
        val datesProvisional: Boolean,
        val firstAired: String?,
        val lastAired: String?,
        val placements: Int,
    )

    /**
     * Per-contract status for a party: the (currently PROVISIONAL) period +
     * renewal dates plus the aired range computed from placements. Until the
     * Oracle ERP import lands, start/end are placement-derived and flagged
     * [ContractStatusRow.datesProvisional]; [ContractStatusRow.renewedAt] has no
     * source yet, so contract-renewal recency must be read from [ContractStatusRow.lastAired].
     *
     * Note the two halves now have different scopes, and that is deliberate: the
     * contract's PERIOD is the deal's (group-wide, the same on every station),
     * while first/last-aired and the placement count are THIS station's.
     */
    fun contractStatus(code: String, byTrader: Boolean): List<ContractStatusRow> {
        // Params in SQL order: trader path filters on the contract's customer
        // first, customer path reaches the customer through the spot.
        val partyJoins =
            if (byTrader)
                """
                JOIN customers cu ON cu.id = ct.customer_id AND cu.code = ?
                JOIN contract_lines cl ON cl.contract_id = ct.id
                JOIN spots s ON s.contract_line_id = cl.id AND s.hidden = FALSE AND s.station_id = ?
                """.trimIndent()
            else
                """
                JOIN contract_lines cl ON cl.contract_id = ct.id
                JOIN spots s ON s.contract_line_id = cl.id AND s.hidden = FALSE AND s.station_id = ?
                JOIN customers cu ON cu.id = s.customer_id AND cu.code = ?
                """.trimIndent()
        val params = if (byTrader) listOf(code, stationId) else listOf(stationId, code)

        return connection().use { c ->
            c.prepareStatement(
                """
                SELECT ct.number, ct.is_gift, ct.start_date, ct.end_date, ct.renewed_at, ct.dates_provisional,
                       MIN(p.show_date) AS first_aired, MAX(p.show_date) AS last_aired, COUNT(p.id) AS placements
                FROM contracts ct
                $partyJoins
                LEFT JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                GROUP BY ct.id, ct.number, ct.is_gift, ct.start_date, ct.end_date, ct.renewed_at, ct.dates_provisional
                ORDER BY last_aired DESC, ct.number
                """.trimIndent()
            ).use { ps ->
                params.forEachIndexed { i, p -> ps.setString(i + 1, p) }
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            ContractStatusRow(
                                contractNumber = rs.getString("number"),
                                isGift = rs.getBoolean("is_gift"),
                                startDate = rs.getString("start_date"),
                                endDate = rs.getString("end_date"),
                                renewedAt = rs.getString("renewed_at"),
                                datesProvisional = rs.getBoolean("dates_provisional"),
                                firstAired = rs.getString("first_aired"),
                                lastAired = rs.getString("last_aired"),
                                placements = rs.getInt("placements"),
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Appends [spotId] at the END of the (time, date) cell - next free position,
     * duration copied from the spot (like the legacy add). The (station_id,
     * show_date, show_time, position) unique key turns concurrent adds into a
     * retry with the next position.
     *
     * THE BREAK comes first. An airing belongs to a break now, so the slot's
     * break is fetched - or created - and the programme rule is about PAINT:
     *
     *  - a PAINTED break (program_id set) simply receives the spot, and stamps
     *    its OWN programme on it. The dogma: `spot.program_id ≡
     *    break.program_id` on every new row. [programId] is deliberately
     *    IGNORED here - adding to a painted cell never repaints it, exactly
     *    like the legacy console.
     *  - a WHITE cell - no break at the slot, OR a break with NO programme
     *    (the console's "Πρόσθεση νέου διαλείμματος" creates them unpainted;
     *    3 migrated breaks are too) - needs [programId] (the operator's
     *    selected "Τύπος Προγράμματος"): the FIRST spot into a white cell is
     *    what paints it. Without a selection the add is refused
     *    ([AddPlacementResult.ProgramRequired]). Racing painters are settled
     *    in SQL: the break's unique slot key for creation, `WHERE program_id
     *    IS NULL` for adoption - then the break is re-read and whoever won is
     *    inherited.
     *
     * The SPOT is re-checked against the station - it arrives from the client
     * and the group's stations share a database - and so is the programme, for
     * the same reason.
     */
    fun addPlacement(
        spotId: Long,
        time: LocalTime,
        date: LocalDate,
        programId: Long? = null,
    ): AddPlacementResult {
        connection().use { c ->
            val duration = c.prepareStatement(
                "SELECT duration_seconds FROM spots WHERE id = ? AND hidden = FALSE AND station_id = ?"
            ).use { ps ->
                ps.setLong(1, spotId)
                ps.setString(2, stationId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else return AddPlacementResult.UnknownSpot
                }
            }

            var brk = breakAt(c, time, date)
            if (brk == null || brk.programId == null) {
                // WHITE cell: the first spot paints it - a selection is required.
                if (programId == null) return AddPlacementResult.ProgramRequired
                if (!programExists(c, programId)) return AddPlacementResult.UnknownProgram
                if (brk == null) {
                    c.prepareStatement(
                        "INSERT IGNORE INTO breaks(station_id, show_date, show_time, program_id) VALUES(?,?,?,?)"
                    ).use { ps ->
                        ps.setString(1, stationId)
                        ps.setDate(2, java.sql.Date.valueOf(date))
                        ps.setTime(3, java.sql.Time.valueOf(time))
                        ps.setLong(4, programId)
                        ps.executeUpdate()
                    }
                } else {
                    // Adopt-if-still-unpainted: a concurrent first add may have
                    // painted it already, and then THEIRS stands.
                    c.prepareStatement(
                        "UPDATE breaks SET program_id = ? WHERE id = ? AND program_id IS NULL"
                    ).use { ps ->
                        ps.setLong(1, programId)
                        ps.setLong(2, brk.id)
                        ps.executeUpdate()
                    }
                }
                // Re-read rather than trust our write: whoever won the race,
                // their programme is the break's - and the spot inherits THAT.
                brk = breakAt(c, time, date)
                    ?: throw SQLException("Break $time/$date vanished between create and read")
            }

            repeat(3) {
                val nextPos = c.prepareStatement(
                    """
                    SELECT COALESCE(MAX(position) + 1, 0) FROM placements
                    WHERE station_id = ? AND show_date = ? AND show_time = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, stationId)
                    ps.setDate(2, java.sql.Date.valueOf(date))
                    ps.setTime(3, java.sql.Time.valueOf(time))
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
                try {
                    val id = c.prepareStatement(
                        """
                        INSERT INTO placements(station_id, spot_id, show_date, show_time, position,
                                               duration_seconds, break_id, program_id)
                        VALUES(?,?,?,?,?,?,?,?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS
                    ).use { ps ->
                        ps.setString(1, stationId)
                        ps.setLong(2, spotId)
                        ps.setDate(3, java.sql.Date.valueOf(date))
                        ps.setTime(4, java.sql.Time.valueOf(time))
                        ps.setInt(5, nextPos)
                        ps.setInt(6, duration)
                        ps.setLong(7, brk.id)
                        // The dogma stamp: the break's programme, never the
                        // caller's - and NULL when a seeded break has none.
                        brk.programId?.let { ps.setLong(8, it) } ?: ps.setNull(8, java.sql.Types.BIGINT)
                        ps.executeUpdate()
                        ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
                    }
                    val row = placementRow(c, id) ?: return AddPlacementResult.UnknownSpot
                    return AddPlacementResult.Added(row)
                } catch (e: SQLException) {
                    if (!isDuplicateKeyViolation(e)) throw e
                    // someone else took the position - recompute and retry
                }
            }
            throw SQLException("Could not allocate a position in the $time break / $date after 3 attempts")
        }
    }

    /** The break at a slot, if the slot has one. */
    private fun breakAt(c: Connection, time: LocalTime, date: LocalDate): BreakRow? =
        c.prepareStatement(
            "SELECT id, program_id FROM breaks WHERE station_id = ? AND show_date = ? AND show_time = ?"
        ).use { ps ->
            ps.setString(1, stationId)
            ps.setDate(2, java.sql.Date.valueOf(date))
            ps.setTime(3, java.sql.Time.valueOf(time))
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                BreakRow(
                    id = rs.getLong("id"),
                    programId = rs.getLong("program_id").takeIf { !rs.wasNull() },
                )
            }
        }

    /** True if the programme exists, visible, ON THIS STATION (client-supplied id). */
    private fun programExists(c: Connection, programId: Long): Boolean =
        c.prepareStatement(
            "SELECT 1 FROM programs WHERE id = ? AND station_id = ? AND hidden = FALSE"
        ).use { ps ->
            ps.setLong(1, programId)
            ps.setString(2, stationId)
            ps.executeQuery().use { it.next() }
        }

    // ─────────────────────────────── programme catalog (Τύποι Προγράμματος) ──

    /** The station's visible programmes, for the console's dropdown. */
    fun listPrograms(): List<ProgramRow> =
        connection().use { c ->
            c.prepareStatement(
                "SELECT id, name, color_argb FROM programs WHERE station_id = ? AND hidden = FALSE ORDER BY name"
            ).use { ps ->
                ps.setString(1, stationId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            ProgramRow(
                                id = rs.getLong("id"),
                                name = rs.getString("name"),
                                colorArgb = rs.getInt("color_argb").takeIf { !rs.wasNull() },
                            )
                        )
                    }
                }
            }
        }

    /** ΠΡΟΣΘ: a new programme on this station (no legacy_id - it is app-born). */
    fun createProgram(name: String, colorArgb: Int?): ProgramRow =
        connection().use { c ->
            val id = c.prepareStatement(
                "INSERT INTO programs(station_id, name, color_argb, hidden) VALUES(?,?,?,FALSE)",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, stationId)
                ps.setString(2, name)
                colorArgb?.let { ps.setInt(3, it) } ?: ps.setNull(3, java.sql.Types.INTEGER)
                ps.executeUpdate()
                ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
            }
            ProgramRow(id = id, name = name, colorArgb = colorArgb)
        }

    /**
     * ΔΙΟΡΘ / Χρώμα: rename and/or recolor. Nulls keep the current value.
     * Recoloring repaints every cell whose break carries the programme - the
     * colour is data ON the programme, not on the cells.
     */
    fun updateProgram(id: Long, name: String? = null, colorArgb: Int? = null): Boolean =
        connection().use { c ->
            c.prepareStatement(
                """
                UPDATE programs SET name = COALESCE(?, name), color_argb = COALESCE(?, color_argb)
                WHERE id = ? AND station_id = ? AND hidden = FALSE
                """.trimIndent()
            ).use { ps ->
                name?.let { ps.setString(1, it) } ?: ps.setNull(1, java.sql.Types.VARCHAR)
                colorArgb?.let { ps.setInt(2, it) } ?: ps.setNull(2, java.sql.Types.INTEGER)
                ps.setLong(3, id)
                ps.setString(4, stationId)
                ps.executeUpdate() > 0
            }
        }

    /**
     * ΑΦΑΙΡ: soft-delete - the programme leaves the dropdown but keeps its row,
     * so breaks (and legacy spots) pointing at it keep their paint and their
     * history. Nothing ever hard-deletes a programme.
     */
    fun hideProgram(id: Long): Boolean =
        connection().use { c ->
            c.prepareStatement(
                "UPDATE programs SET hidden = TRUE WHERE id = ? AND station_id = ? AND hidden = FALSE"
            ).use { ps ->
                ps.setLong(1, id)
                ps.setString(2, stationId)
                ps.executeUpdate() > 0
            }
        }

    /** One placement in the month-grid row shape (same joins as loadMonth). */
    private fun placementRow(c: Connection, placementId: Long): CommercialRow? =
        c.prepareStatement(
            """
            SELECT p.id, p.spot_id, p.position, s.duration_seconds,
                   s.description, s.booked_program AS spot_type, s.flow,
                   sty.name AS sales_item,
                   cu.code AS client_code, cu.name AS client_name,
                   ct.number AS contract_number, ct.is_gift, ct.exclude_from_reports,
                   pay.code AS payer_code, pay.name AS payer_name,
                   pr.color_argb AS program_color, pr.name AS program_name
            FROM placements p
            JOIN spots s      ON s.id = p.spot_id
            JOIN customers cu ON cu.id = s.customer_id
            -- the airing's ACTUAL charge (legacy schedule.docID+lineno) wins;
            -- the spot's own line is only its current default
            LEFT JOIN contract_lines cl ON cl.id = COALESCE(p.contract_line_id, s.contract_line_id)
            LEFT JOIN contracts ct      ON ct.id = cl.contract_id
            -- The ERP item comes from THAT SAME line - the charge and the item
            -- it charges for must never disagree. (It used to hang off the
            -- SPOT's own type, which was the booked programme, not an item.)
            LEFT JOIN spot_types sty    ON sty.id = cl.spot_type_id
            LEFT JOIN customers pay     ON pay.id = ct.customer_id
            LEFT JOIN programs pr       ON pr.id = p.program_id
            WHERE p.id = ? AND s.station_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, placementId)
            ps.setString(2, stationId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val programColor = rs.getInt("program_color").takeIf { !rs.wasNull() }
                CommercialRow(
                    id = rs.getLong("id"),
                    spotId = rs.getLong("spot_id"),
                    position = rs.getInt("position"),
                    clientCode = rs.getString("client_code"),
                    clientName = rs.getString("client_name"),
                    message = rs.getString("description"),
                    durationSeconds = rs.getInt("duration_seconds"),
                    type = rs.getString("spot_type"),
                    salesItem = rs.getString("sales_item"),
                    contract = rs.getString("contract_number") ?: "",
                    isGift = rs.getBoolean("is_gift"),
                    excludeFromReports = rs.getBoolean("exclude_from_reports"),
                    flow = rs.getString("flow"),
                    programName = rs.getString("program_name"),
                    programColorArgb = programColor,
                    payerCode = rs.getString("payer_code"),
                    payerName = rs.getString("payer_name"),
                )
            }
        }

    /**
     * Rewrites a cell's ordering: [orderedIds] must be a permutation of the
     * cell's CURRENT placement ids (returns false otherwise - the client's
     * view was stale). New positions are the list indexes. Positions collide
     * mid-renumber under the (station, date, time, position) unique key, so all
     * rows are first bumped far away, in one transaction.
     *
     * The cell is addressed by its TIME, and every statement carries this
     * station's filter - so a caller granted only the radio station cannot reach
     * the TV station's 11:00 cell, even though both exist and share a database.
     * (The break used to be re-checked as a client-supplied id; the station is
     * simply never taken from the client now.)
     */
    fun reorderPlacements(time: LocalTime, date: LocalDate, orderedIds: List<Long>): Boolean =
        connection().use { c ->
            val current = c.prepareStatement(
                """
                SELECT id FROM placements
                WHERE station_id = ? AND show_date = ? AND show_time = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, stationId)
                ps.setDate(2, java.sql.Date.valueOf(date))
                ps.setTime(3, java.sql.Time.valueOf(time))
                ps.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(rs.getLong(1)) }
                }
            }
            if (current.isEmpty()) return false
            if (orderedIds.size != current.size || orderedIds.toSet() != current) return false

            c.autoCommit = false
            try {
                c.prepareStatement(
                    """
                    UPDATE placements SET position = position + 100000
                    WHERE station_id = ? AND show_date = ? AND show_time = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, stationId)
                    ps.setDate(2, java.sql.Date.valueOf(date))
                    ps.setTime(3, java.sql.Time.valueOf(time))
                    ps.executeUpdate()
                }
                c.prepareStatement("UPDATE placements SET position = ? WHERE id = ?").use { ps ->
                    orderedIds.forEachIndexed { index, id ->
                        ps.setInt(1, index)
                        ps.setLong(2, id)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
                true
            } catch (e: Exception) {
                c.rollback(); throw e
            } finally {
                c.autoCommit = true
            }
        }

    /**
     * True if the placement existed ON THIS STATION and is gone now. The
     * station_id filter is the authorization: a placement id belonging to a
     * sibling station simply does not match, so the delete is a no-op rather than
     * a cross-station wipe. (It used to need a join through `spots` to establish
     * that; the airing now says which station it aired on.)
     *
     * RETIRES THE BREAK once its last airing is gone: a slot that no longer
     * holds a spot goes back to being a white cell, so the next first spot
     * founds a FRESH break with the operator's chosen programme (rather than
     * silently inheriting a stale one). A break with hidden airings still FKs
     * them, so the delete only fires when nothing references the break at all.
     */
    fun deletePlacement(placementId: Long): Boolean =
        connection().use { c ->
            // Capture the break BEFORE deleting, to retire it if this was its
            // last airing.
            val breakId = c.prepareStatement(
                "SELECT break_id FROM placements WHERE id = ? AND station_id = ?"
            ).use { ps ->
                ps.setLong(1, placementId)
                ps.setString(2, stationId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return false
                    rs.getLong("break_id").takeIf { !rs.wasNull() }
                }
            }
            val deleted = c.prepareStatement(
                "DELETE FROM placements WHERE id = ? AND station_id = ?"
            ).use { ps ->
                ps.setLong(1, placementId)
                ps.setString(2, stationId)
                ps.executeUpdate() > 0
            }
            if (deleted && breakId != null) {
                c.prepareStatement(
                    "DELETE FROM breaks WHERE id = ? AND NOT EXISTS " +
                        "(SELECT 1 FROM placements WHERE break_id = ?)"
                ).use { ps ->
                    ps.setLong(1, breakId)
                    ps.setLong(2, breakId)
                    ps.executeUpdate()
                }
            }
            deleted
        }

    /**
     * The `customers cu` -> `spots s` join path for the two party kinds, as a
     * matching FROM/WHERE pair: [partyFrom] extends the caller's FROM list,
     * [partyWhere] carries the predicates that pair with it and must open the
     * caller's WHERE (its optional station parameter is then bound first).
     *
     * [stationScoped] adds this station's filter to the spot - and takes a bound
     * parameter, which must be the FIRST one the caller sets.
     */
    private fun partyFrom(byTrader: Boolean): String =
        if (byTrader) ", contracts ct, contract_lines cl, spots s"
        else ", spots s"

    private fun partyWhere(byTrader: Boolean, stationScoped: Boolean): String {
        val stationFilter = if (stationScoped) "AND s.station_id = ?" else ""
        return if (byTrader)
            """
            ct.customer_id = cu.id
              AND cl.contract_id = ct.id
              AND s.contract_line_id = cl.id AND s.hidden = FALSE $stationFilter
            """.trimIndent()
        else
            "s.customer_id = cu.id AND s.hidden = FALSE $stationFilter"
    }

    /** `%query%` with LIKE wildcards neutralized (MySQL's default escape is `\`). */
    private fun likeContains(query: String): String {
        val escaped = query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    /**
     * Customer lookup for the schedule email (default recipient + salutation).
     * GROUP-scoped, like [searchParties]: the customer is the group's.
     */
    fun customerByCode(code: String): CustomerContact? =
        connection().use { c ->
            c.prepareStatement("SELECT name, email FROM customers WHERE code = ?").use { ps ->
                ps.setString(1, code)
                ps.executeQuery().use { rs ->
                    if (rs.next()) CustomerContact(rs.getString(1), rs.getString(2)?.ifBlank { null }) else null
                }
            }
        }

    /**
     * THE BREAK TIMES of a period: the DISTINCT times of its VISIBLE airings.
     *
     * Only occupied times - a spotless break has no row (an operator-created
     * empty break is a transient CLIENT row, never persisted; see
     * [addPlacement]/deletePlacement). They are per PERIOD, not per
     * station-forever: a time used once in 2003 does not haunt the 2026 grid,
     * and putting a spot at an unused time creates its break on the fly.
     *
     * Hidden airings and hidden spots are excluded, so a time cannot appear
     * with nothing visible in it - the same filter [loadMonth] applies to the
     * cells.
     */
    fun breakTimes(date: LocalDate): List<BreakTimeRow> =
        breakTimesIn(date, date.plusDays(1))

    fun breakTimes(year: Int, month: Int): List<BreakTimeRow> {
        val (start, end) = monthRange(year, month)
        return breakTimesIn(start, end)
    }

    /**
     * The month grid's ROWS, in the caller's view mode: the scaffold this station
     * draws empty rows for, unioned with the month's real breaks. See [gridRows] -
     * the whole rule is there, and it is a pure function; this only supplies the
     * two inputs it cannot know (what aired, and where this station's day starts).
     */
    fun gridRows(year: Int, month: Int, mode: GridViewMode): List<BreakTimeRow> =
        gridRows(
            breakTimes = breakTimes(year, month).map { it.time },
            mode = mode,
            emptyRowsFrom = emptyRowsFrom,
        )

    /**
     * The same rows, from times the caller ALREADY has - no second scan.
     *
     * The month's cells are the month's breaks: [loadMonthCells] groups the airings
     * by time, so its keys ARE the distinct times [breakTimes] would go and find
     * again. Handing them straight over means one scan of the month instead of two,
     * and lets the grid be served in ONE response instead of the /breaks + /schedule
     * pair the client used to fetch back-to-back.
     *
     * It also fixes a quiet inconsistency for a CUSTOMER_VIEWER: the cells are
     * filtered to their spots, so rows derived from them are too - where the
     * separate [breakTimes] scan handed them every station row, including breaks
     * they have nothing in.
     */
    fun gridRowsFrom(times: List<LocalTime>, mode: GridViewMode): List<BreakTimeRow> =
        gridRows(breakTimes = times, mode = mode, emptyRowsFrom = emptyRowsFrom)

    /**
     * Where this station's printed grid starts (server.yaml). A malformed value
     * is a configuration error worth failing loudly on, not silently rounding to
     * midnight - it would print fourteen blank night rows and look like a bug in
     * the grid.
     */
    private val emptyRowsFrom: LocalTime by lazy {
        runCatching { LocalTime.parse(station.emptyRowsFrom) }.getOrElse {
            throw IllegalArgumentException(
                "Station '${station.id}': emptyRowsFrom='${station.emptyRowsFrom}' is not a valid HH:mm time."
            )
        }
    }

    private fun breakTimesIn(start: LocalDate, endExclusive: LocalDate): List<BreakTimeRow> =
        connection().use { c ->
            c.prepareStatement(
                """
                SELECT DISTINCT p.show_time
                  FROM placements p, spots s
                 WHERE s.id = p.spot_id
                   AND p.station_id = ?
                   AND p.show_date >= ? AND p.show_date < ?
                   AND p.hidden = FALSE AND s.hidden = FALSE
                 ORDER BY p.show_time
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, stationId)
                ps.setDate(2, java.sql.Date.valueOf(start))
                ps.setDate(3, java.sql.Date.valueOf(endExclusive))
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<BreakTimeRow>()
                    while (rs.next()) out += BreakTimeRow(rs.getTime(1).toLocalTime())
                    out
                }
            }
        }

    /** [start, end) - first day of the month to first day of the next month. */
    private fun monthRange(year: Int, month: Int): Pair<LocalDate, LocalDate> {
        val start = LocalDate.of(year, month, 1)
        return start to start.plusMonths(1)
    }

    private fun monthHasPlacements(c: Connection, year: Int, month: Int): Boolean {
        val (start, end) = monthRange(year, month)
        return c.prepareStatement(
            """
            SELECT 1 FROM placements
             WHERE station_id = ? AND show_date >= ? AND show_date < ?
             LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, stationId)
            ps.setDate(2, java.sql.Date.valueOf(start)); ps.setDate(3, java.sql.Date.valueOf(end))
            ps.executeQuery().use { it.next() }
        }
    }

    /**
     * WHOSE airings a month-grid cell counts - the "Προβολή Βάσει…" scope, and
     * the CUSTOMER_VIEWER grant (which is [ByCustomer] applied by the route,
     * not by choice). Null = everything.
     */
    sealed interface CellFilter {
        /** Cells whose BREAK carries the programme (the break owns its paint). */
        data class ByProgram(val programId: Long) : CellFilter
        /** The spots OWNED by this customer code (legacy cusID). */
        data class ByCustomer(val code: String) : CellFilter
        /** The spots under contracts PAID by this code (legacy traid - agencies). */
        data class ByTrader(val code: String) : CellFilter
        /**
         * The selected line's WHOLE contract - every line of that ONE deal.
         * Same-numbered contracts of the same payer exist (legacy numbering
         * repeats) and stay OUT: they are different deals; the finder's
         * period column disambiguates them at selection time.
         */
        data class ByContract(val lineId: Long) : CellFilter
        /** One spot (the finder's armed message). */
        data class BySpot(val spotId: Long) : CellFilter
    }

    /**
     * THE MONTH GRID - the little boxes, and nothing else.
     *
     * A box shows a spot COUNT and a total DURATION, so that is all this fetches:
     * one aggregated row per (time, date), computed in the database. It does NOT
     * carry the airings themselves.
     *
     * That distinction is the whole performance story of this screen. The grid
     * used to be served by [loadMonth], which returns every airing in full -
     * 13,009 rows and 7.79 MB of JSON for a busy month, to draw 1,295 boxes. This
     * returns 1,295 rows, needs 2 joins instead of 7, and measured 53ms against
     * 98ms. The airings are fetched only when something actually wants them:
     * opening a break, or printing (see [loadCommercials]).
     *
     * [filter] must be applied HERE, inside the aggregate: with no airings coming
     * back there is nothing left to filter in Kotlin - recomputing counts from
     * survivors is exactly the CUSTOMER_VIEWER lesson (the route used to filter
     * airings and recount; a customer would otherwise see everybody's numbers).
     * A placement-scoped filter also decides WHICH cells exist at all: a break
     * with none of the filtered spots simply has no row, so the grid shows the
     * operator only where the selection actually airs.
     */
    fun loadMonthCells(year: Int, month: Int, filter: CellFilter? = null): List<CellRow> {
        val (start, end) = monthRange(year, month)
        // The cell's programme is THE BREAK'S - the one column the break entity
        // owns (see GroupDb). The old "first placement that has one" windows are
        // gone: per-spot tags still exist (reports read them) but never decide a
        // cell's paint again.
        val breakRooted = filter == null || filter is CellFilter.ByProgram
        val sql = if (breakRooted)
            // Staff view: the break owns the PAINT (its programme), but a CELL
            // exists only where a VISIBLE spot airs - HAVING drops empty and
            // hidden-only breaks so a spotless break never shows a row. (That is
            // why "add break" is a transient CLIENT row, not a persisted empty
            // break: an empty break has no cell, and deleting the last spot
            // retires its break - see deletePlacement.) The airings leg joins by
            // the slot tuple, NOT break_id: (station, date, hidden, time) is the
            // leftmost prefix of idx_placements_grid.
            //
            // ByProgram stays on this shape: the programme is the BREAK's, so the
            // filter picks whole cells (their counts intact), never single spots.
            """
            SELECT b.show_time, b.show_date,
                   COUNT(s.id) AS spot_count,
                   COALESCE(SUM(s.duration_seconds), 0) AS total_secs,
                   MAX(pr.color_argb) AS program_color,
                   MAX(pr.name) AS program_name,
                   MAX(pr.id) AS program_id
            FROM breaks b
            LEFT JOIN placements p ON p.station_id = b.station_id
                                  AND p.show_date = b.show_date
                                  AND p.show_time = b.show_time
                                  AND p.hidden = FALSE
            LEFT JOIN spots s ON s.id = p.spot_id AND s.hidden = FALSE
            LEFT JOIN programs pr ON pr.id = b.program_id
            WHERE b.station_id = ?
              AND b.show_date >= ? AND b.show_date < ?
              ${if (filter is CellFilter.ByProgram) "AND b.program_id = ?" else ""}
            GROUP BY b.show_date, b.show_time
            HAVING COUNT(s.id) > 0
            """.trimIndent()
        else
            // Placement-scoped filters (customer/trader/contract/spot): the
            // MATCHING spots decide which cells exist and what the counts say
            // (a break with none of them is not a cell), but the paint is still
            // the break's programme - whoever is looking.
            """
            SELECT p.show_time, p.show_date,
                   COUNT(*) AS spot_count,
                   SUM(s.duration_seconds) AS total_secs,
                   MAX(pr.color_argb) AS program_color,
                   MAX(pr.name) AS program_name,
                   MAX(pr.id) AS program_id
            FROM placements p
            JOIN spots s ON s.id = p.spot_id
            ${when (filter) {
                is CellFilter.ByCustomer -> "JOIN customers cu ON cu.id = s.customer_id AND cu.code = ?"
                // Contract-scoped filters must follow the AIRING'S CHARGE line -
                // COALESCE(p.contract_line_id, s.contract_line_id), the same read
                // path the Break Console's Σύμβαση column uses. The spot's default
                // alone is NOT it: 400k of this station's airings charge to a
                // different line than their spot's default (a spot re-links
                // across contracts), and filtering by the default would show a
                // contract in the console and an empty grid for it.
                is CellFilter.ByTrader ->
                    """JOIN contract_lines cl ON cl.id = COALESCE(p.contract_line_id, s.contract_line_id)
            JOIN contracts ct ON ct.id = cl.contract_id
            JOIN customers cu ON cu.id = ct.customer_id AND cu.code = ?"""
                is CellFilter.ByContract ->
                    // The selected LINE addresses its ONE contract; every
                    // sibling line of it counts - «Συμβολαίου», not «Είδους».
                    // Deliberately NOT widened to same-number siblings (owner
                    // decision, 2026-07-20): legacy doc numbers repeat per
                    // customer (KRIVEK holds a 2023 AND a 2026 gift doc, both
                    // «18»), but those ARE different deals - the finder's
                    // period column is what tells them apart at selection time.
                    """JOIN contract_lines cl ON cl.id = COALESCE(p.contract_line_id, s.contract_line_id)
            JOIN contract_lines sel ON sel.id = ? AND sel.contract_id = cl.contract_id"""
                else -> ""
            }}
            LEFT JOIN breaks b ON b.station_id = p.station_id
                              AND b.show_date = p.show_date
                              AND b.show_time = p.show_time
            LEFT JOIN programs pr ON pr.id = b.program_id
            WHERE p.station_id = ?
              AND p.show_date >= ? AND p.show_date < ?
              AND p.hidden = FALSE AND s.hidden = FALSE
              ${if (filter is CellFilter.BySpot) "AND p.spot_id = ?" else ""}
            GROUP BY p.show_date, p.show_time
            """.trimIndent()
        val cells = mutableListOf<CellRow>()
        connection().use { c ->
            c.prepareStatement(sql).use { ps ->
                // ORDER MATTERS: the join-leg `?`s (customer/trader code, the
                // selected line) sit BEFORE the WHERE - bind them first or the
                // station id lands on them.
                var i = 1
                when (filter) {
                    is CellFilter.ByCustomer -> ps.setString(i++, filter.code)
                    is CellFilter.ByTrader -> ps.setString(i++, filter.code)
                    is CellFilter.ByContract -> ps.setLong(i++, filter.lineId)
                    else -> Unit
                }
                ps.setString(i++, stationId)
                ps.setDate(i++, java.sql.Date.valueOf(start))
                ps.setDate(i++, java.sql.Date.valueOf(end))
                when (filter) {
                    is CellFilter.ByProgram -> ps.setLong(i, filter.programId)
                    is CellFilter.BySpot -> ps.setLong(i, filter.spotId)
                    else -> Unit
                }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val time = rs.getTime("show_time").toLocalTime()
                        val date = rs.getDate("show_date").toLocalDate()
                        val spotCount = rs.getInt("spot_count")
                        val programColor = rs.getInt("program_color").takeIf { !rs.wasNull() }
                        val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
                        cells += CellRow(
                            time = time,
                            date = date,
                            spotCount = spotCount,
                            totalDurationSeconds = rs.getInt("total_secs"),
                            // The BREAK's programme colour wins; the
                            // zone/weekend/density rules are the fallback.
                            zoneColorArgb = programColor ?: cellColorArgb(
                                zone = zoneOf(time.hour),
                                isWeekend = isWeekend,
                                spotCount = spotCount,
                            ),
                            programName = rs.getString("program_name"),
                            programId = rs.getLong("program_id").takeIf { !rs.wasNull() },
                            commercials = emptyList(),
                        )
                    }
                }
            }
        }
        return cells
    }

    /**
     * The month's grid WITH every airing in it - the heavy read.
     *
     * Kept for the consumers that genuinely need the airings across a whole month
     * (the schedule email, the MCP tools, the printed reports). The GRID must not
     * use it: see [loadMonthCells]. The cells it returns are [loadMonthCells]'s,
     * so the two can never disagree about a count or a colour.
     */
    fun loadMonth(year: Int, month: Int): Pair<List<CellRow>, Map<Pair<LocalTime, LocalDate>, List<CommercialRow>>> =
        loadMonthCells(year, month) to loadCommercials(year, month)

    /**
     * The airings themselves, for a slice of the month: the whole month (both
     * [date] and [time] null), one day ([date]), one break across the month
     * ([time]), or a single cell (both).
     *
     * One query serves all four because they are the same question at different
     * resolutions - which is also every place the client actually needs an airing:
     * opening a break, printing a day, printing a break's month, printing a month.
     */
    fun loadCommercials(
        year: Int,
        month: Int,
        date: LocalDate? = null,
        time: LocalTime? = null,
    ): Map<Pair<LocalTime, LocalDate>, List<CommercialRow>> {
        val (start, end) = monthRange(year, month)

        val commercialsByKey = linkedMapOf<Pair<LocalTime, LocalDate>, MutableList<CommercialRow>>()
        // The slice. Narrowing to a day or a single break is what keeps the Break
        // Console and the day report from paying for a whole month of airings.
        val dateFilter = if (date != null) "AND p.show_date = ?" else ""
        val timeFilter = if (time != null) "AND p.show_time = ?" else ""

        connection().use { c ->
            c.prepareStatement(
                """
                SELECT p.id, p.spot_id, p.show_time, p.show_date, p.position, s.duration_seconds,
                       s.description, s.booked_program AS spot_type, s.flow,
                       sty.name AS sales_item,
                       cu.code AS client_code, cu.name AS client_name,
                       ct.number AS contract_number, ct.is_gift, ct.exclude_from_reports,
                       pay.code AS payer_code, pay.name AS payer_name,
                       pr.color_argb AS program_color, pr.name AS program_name
                FROM placements p
                JOIN spots s      ON s.id = p.spot_id
                JOIN customers cu ON cu.id = s.customer_id
                -- the airing's ACTUAL charge (legacy schedule.docID+lineno) wins;
                -- the spot's own line is only its current default
                LEFT JOIN contract_lines cl ON cl.id = COALESCE(p.contract_line_id, s.contract_line_id)
                LEFT JOIN contracts ct      ON ct.id = cl.contract_id
                -- The ERP item comes from THAT SAME line (see placementRow).
                LEFT JOIN spot_types sty    ON sty.id = cl.spot_type_id
                LEFT JOIN customers pay     ON pay.id = ct.customer_id
                LEFT JOIN programs pr       ON pr.id = p.program_id
                WHERE p.station_id = ?
                  AND p.show_date >= ? AND p.show_date < ?
                  AND p.hidden = FALSE AND s.hidden = FALSE
                  $dateFilter
                  $timeFilter
                ORDER BY p.show_time, p.show_date, p.position
                """.trimIndent()
            ).use { ps ->
                var i = 1
                ps.setString(i++, stationId)
                ps.setDate(i++, java.sql.Date.valueOf(start))
                ps.setDate(i++, java.sql.Date.valueOf(end))
                if (date != null) ps.setDate(i++, java.sql.Date.valueOf(date))
                if (time != null) ps.setTime(i, java.sql.Time.valueOf(time))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val rowTime = rs.getTime("show_time").toLocalTime()
                        val rowDate = rs.getDate("show_date").toLocalDate()
                        val isGift = rs.getBoolean("is_gift")
                        val programColor = rs.getInt("program_color").takeIf { !rs.wasNull() }
                        val programName = rs.getString("program_name")
                        val list = commercialsByKey.getOrPut(rowTime to rowDate) { mutableListOf() }
                        list += CommercialRow(
                            id = rs.getLong("id"),
                            spotId = rs.getLong("spot_id"),
                            position = rs.getInt("position"),
                            clientCode = rs.getString("client_code"),
                            clientName = rs.getString("client_name"),
                            message = rs.getString("description"),
                            durationSeconds = rs.getInt("duration_seconds"),
                            type = rs.getString("spot_type"),
                            salesItem = rs.getString("sales_item"),
                            contract = rs.getString("contract_number") ?: "",
                            isGift = isGift,
                            excludeFromReports = rs.getBoolean("exclude_from_reports"),
                            flow = rs.getString("flow"),
                            programName = programName,
                            programColorArgb = programColor,
                            payerCode = rs.getString("payer_code"),
                            payerName = rs.getString("payer_name"),
                        )
                    }
                }
            }
        }

        // The cells are NOT rebuilt here. They are [loadMonthCells]'s job, and
        // having a second place compute a spot count or pick a cell's colour is
        // exactly how the grid and the reports would start disagreeing.
        return commercialsByKey
    }

    /**
     * Loads the month; seeds demo placements if the station has none for that
     * month.
     *
     * Concurrency: two requests for the same unseeded month can both pass the
     * `monthHasPlacements` check before either commits (check-then-act race -
     * each request uses its own connection, so there's no in-process
     * serialization point). Since generation is deterministic (RNG seeded
     * from station + year/month over a deterministic catalog), both requests
     * would generate identical rows; if a concurrent insert already committed
     * first, this request's INSERT hits the (station_id, show_date, show_time,
     * position) unique key and we treat that as "already seeded by someone
     * else". This is also correct across multiple server instances.
     *
     * A demo station's breaks are seeded FROM its invented airings (BreakSeeder,
     * right after the placements land): every airing must belong to a break row
     * now. Their programme stays NULL - demo placements carry none - so demo
     * cells keep their zone colours.
     */
    fun ensureMonthSeeded(year: Int, month: Int) {
        connection().use { c ->
            // Migrated stations hold REAL data: months without placements are
            // genuinely empty and must never be filled with demo spots.
            if (!isDemoSeedEnabled(c)) return
            if (monthHasPlacements(c, year, month)) return

            val spots = c.prepareStatement(
                "SELECT id, duration_seconds FROM spots WHERE station_id = ? ORDER BY id"
            ).use { ps ->
                ps.setString(1, stationId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(SpotRef(rs.getLong(1), rs.getInt(2)))
                    }
                }
            }
            val generated = generateMonthPlacements(
                demoBreakTimes(), spots, year, month, stationSeed = stationId.hashCode()
            )
            if (generated.isEmpty()) return

            c.autoCommit = false
            try {
                c.prepareStatement(
                    """
                    INSERT INTO placements(station_id, spot_id, show_date, show_time, position, duration_seconds)
                    VALUES(?,?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    for (p in generated) {
                        ps.setString(1, stationId)
                        ps.setLong(2, p.spotId)
                        ps.setDate(3, java.sql.Date.valueOf(p.date))
                        ps.setTime(4, java.sql.Time.valueOf(p.time))
                        ps.setInt(5, p.position)
                        ps.setInt(6, p.durationSeconds)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
                // The invented airings need their break rows too (see the KDoc).
                BreakSeeder.seed(c)
                c.commit()
            } catch (e: SQLException) {
                c.rollback()
                // Another request already seeded this exact month concurrently
                // (generation is deterministic, so the rows collide) - nothing
                // to do, breaks included: the winner seeds them. Anything else
                // is a real failure.
                if (!isDuplicateKeyViolation(e)) throw e
            } catch (e: Exception) {
                c.rollback(); throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    /**
     * True if [e] (or any exception chained via `getNextException`, which is
     * how JDBC batch failures report the real per-row cause) is a duplicate
     * primary key violation. Checked by SQLState/vendor code rather than a
     * concrete exception type, since JDBC drivers vary in whether a failed
     * batch surfaces as `BatchUpdateException`, `SQLIntegrityConstraintViolationException`,
     * or a plain `SQLException` wrapping the real one.
     */
    private fun isDuplicateKeyViolation(e: SQLException): Boolean {
        var current: SQLException? = e
        while (current != null) {
            if (current.errorCode == 1062 || current.sqlState?.startsWith("23") == true) return true
            current = current.nextException
        }
        return false
    }
}

/** A break's owned state: its internal id (never leaves the server) + programme. */
private data class BreakRow(val id: Long, val programId: Long?)

/** One programme of the station's catalog (the "Τύποι Προγράμματος" dropdown). */
data class ProgramRow(val id: Long, val name: String, val colorArgb: Int?)

/** Outcome of [StationDb.addPlacement]; the routes map each case to a status. */
sealed interface AddPlacementResult {
    /** The airing landed - [row] is the same row shape the month grid serves. */
    data class Added(val row: CommercialRow) : AddPlacementResult

    /** The spot does not exist (visible) on this station. */
    data object UnknownSpot : AddPlacementResult

    /**
     * The cell is WHITE (no break at the slot, or an unpainted one) and no
     * programme came along to paint it - the operator must pick a "Τύπος
     * Προγράμματος" before placing the first spot.
     */
    data object ProgramRequired : AddPlacementResult

    /** The supplied programme does not exist (visible) on this station. */
    data object UnknownProgram : AddPlacementResult
}
