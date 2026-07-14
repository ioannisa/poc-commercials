package eu.anifantakis.commercials.server.scheduler

import eu.anifantakis.commercials.server.stations.StationConfig
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.time.DayOfWeek
import java.time.LocalDate

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
 * the client ([addPlacement], [reorderPlacements], [deletePlacement],
 * [emailLogBody], [contractLineSpots]). Every one of them must re-derive the
 * station in SQL. Skipping the check does not throw - it silently drops a radio
 * spot into a TV break.
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

            if (seedDemo && isDemoSeedEnabled(c)) {
                seedGroupCatalogIfEmpty(c)
                seedBreaksIfEmpty(c)
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

    private fun seedBreaksIfEmpty(c: Connection) {
        val count = c.prepareStatement("SELECT COUNT(*) FROM break_slots WHERE station_id = ?").use { ps ->
            ps.setString(1, stationId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        if (count > 0) return
        c.autoCommit = false
        try {
            // No explicit id: break ids are per-station now, so the DB assigns them.
            c.prepareStatement(
                "INSERT INTO break_slots(station_id, hour_of_day, minute_of_hour, label, zone) VALUES(?,?,?,?,?)"
            ).use { ps ->
                for (b in generateBreaks()) {
                    ps.setString(1, stationId)
                    ps.setInt(2, b.hour)
                    ps.setInt(3, b.minute)
                    ps.setString(4, b.label)
                    ps.setString(5, b.zone.name)
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
            FROM contract_lines cl
            JOIN contracts ct ON ct.id = cl.contract_id
            WHERE cl.line_no = ? AND ct.number LIKE 'DEMO-%'
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

    /** Quick footprint of THIS station's data (Databases admin screen). */
    fun placementStats(): PlacementStats =
        connection().use { c ->
            c.prepareStatement(
                """
                SELECT COUNT(*), MIN(p.show_date), MAX(p.show_date)
                FROM placements p
                JOIN spots s ON s.id = p.spot_id
                WHERE s.station_id = ?
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
                FROM customers cu
                ${partyJoin(byTrader, stationScoped = false)}
                JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                WHERE (cu.name LIKE ? OR cu.code LIKE ?)
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
                FROM customers cu
                ${partyJoin(byTrader, stationScoped = true)}
                JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                WHERE cu.code = ?
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
        // The line's spots are LEFT-joined (a spot-less line must survive), so
        // the party filter cannot hang off them on the trader path - it hangs
        // off the contract. On the customer path a spot-less line has no
        // customer of its own, so it falls back to the contract's customer.
        val partyFilter =
            if (byTrader) "JOIN customers cu ON cu.id = ct.customer_id AND cu.code = ?"
            else "JOIN customers cu ON cu.code = ?"
        val spotJoin =
            if (byTrader) "LEFT JOIN spots s ON s.contract_line_id = cl.id AND s.hidden = FALSE AND s.station_id = ?"
            else "LEFT JOIN spots s ON s.contract_line_id = cl.id AND s.hidden = FALSE AND s.station_id = ? AND s.customer_id = cu.id"
        val keep =
            if (byTrader) "s.id IS NOT NULL OR NOT EXISTS (SELECT 1 FROM spots x WHERE x.contract_line_id = cl.id)"
            else "s.id IS NOT NULL OR (NOT EXISTS (SELECT 1 FROM spots x WHERE x.contract_line_id = cl.id) AND ct.customer_id = cu.id)"

        return connection().use { c ->
            c.prepareStatement(
                """
                SELECT cl.id AS line_id, ct.number, ct.is_gift, ct.entry_date, cl.line_no, cl.desired_qty,
                       COUNT(DISTINCT s.id) AS spot_count, COUNT(p.id) AS placements,
                       -- the SPOT's duration, not the airing's (see durationSeconds
                       -- on CommercialRow). The CASE is what the LEFT JOIN needs: a
                       -- spot with no airings must contribute 0, not its length.
                       COALESCE(SUM(CASE WHEN p.id IS NOT NULL THEN s.duration_seconds END), 0) AS total_secs
                FROM contract_lines cl
                JOIN contracts ct ON ct.id = cl.contract_id
                $partyFilter
                $spotJoin
                LEFT JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                WHERE ($keep)
                GROUP BY cl.id, ct.number, ct.is_gift, ct.entry_date, cl.line_no, cl.desired_qty
                ORDER BY ct.number, cl.line_no
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, code)
                ps.setString(2, stationId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            ContractLineRow(
                                lineId = rs.getLong("line_id"),
                                contractNumber = rs.getString("number"),
                                isGift = rs.getBoolean("is_gift"),
                                lineNo = rs.getInt("line_no"),
                                desiredQty = rs.getInt("desired_qty"),
                                spotCount = rs.getInt("spot_count"),
                                placements = rs.getInt("placements"),
                                totalSeconds = rs.getLong("total_secs"),
                                entryDate = rs.getString("entry_date"),
                            )
                        )
                    }
                }
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
                LEFT JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                WHERE s.contract_line_id = ? AND s.hidden = FALSE AND s.station_id = ?
                GROUP BY s.id, s.description, s.duration_seconds
                ORDER BY s.description, s.id
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, lineId)
                ps.setString(2, stationId)
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
     * Appends [spotId] at the END of the (break, date) cell - next free
     * position, duration copied from the spot (like the legacy add). The
     * (break_id, show_date, position) unique key turns concurrent adds into
     * a retry with the next position. Returns the new placement as the same
     * row shape the month grid serves, or null if the spot or the break does
     * not exist ON THIS STATION.
     *
     * BOTH ids are re-checked against the station. They arrive from the client,
     * and the group's stations share a database: without the checks, a caller
     * granted only the radio station could air a radio spot in a TV break, and
     * MySQL would happily accept it (the foreign keys are satisfied).
     */
    fun addPlacement(spotId: Long, breakId: Long, date: LocalDate): CommercialRow? {
        connection().use { c ->
            val duration = c.prepareStatement(
                "SELECT duration_seconds FROM spots WHERE id = ? AND hidden = FALSE AND station_id = ?"
            ).use { ps ->
                ps.setLong(1, spotId)
                ps.setString(2, stationId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else return null }
            }
            val breakIsOurs = c.prepareStatement(
                "SELECT 1 FROM break_slots WHERE id = ? AND station_id = ?"
            ).use { ps ->
                ps.setLong(1, breakId)
                ps.setString(2, stationId)
                ps.executeQuery().use { it.next() }
            }
            if (!breakIsOurs) return null

            repeat(3) {
                val nextPos = c.prepareStatement(
                    "SELECT COALESCE(MAX(position) + 1, 0) FROM placements WHERE break_id = ? AND show_date = ?"
                ).use { ps ->
                    ps.setLong(1, breakId)
                    ps.setDate(2, java.sql.Date.valueOf(date))
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
                try {
                    val id = c.prepareStatement(
                        """
                        INSERT INTO placements(spot_id, break_id, show_date, position, duration_seconds)
                        VALUES(?,?,?,?,?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS
                    ).use { ps ->
                        ps.setLong(1, spotId)
                        ps.setLong(2, breakId)
                        ps.setDate(3, java.sql.Date.valueOf(date))
                        ps.setInt(4, nextPos)
                        ps.setInt(5, duration)
                        ps.executeUpdate()
                        ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
                    }
                    return placementRow(c, id)
                } catch (e: SQLException) {
                    if (!isDuplicateKeyViolation(e)) throw e
                    // someone else took the position - recompute and retry
                }
            }
            throw SQLException("Could not allocate a position in break $breakId / $date after 3 attempts")
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
     * mid-renumber under the (break, date, position) unique key, so all rows
     * are first bumped far away, in one transaction.
     *
     * The break is re-checked against the station first: it is a client-supplied
     * id into a database this station shares with its siblings.
     */
    fun reorderPlacements(breakId: Long, date: LocalDate, orderedIds: List<Long>): Boolean =
        connection().use { c ->
            val breakIsOurs = c.prepareStatement(
                "SELECT 1 FROM break_slots WHERE id = ? AND station_id = ?"
            ).use { ps ->
                ps.setLong(1, breakId)
                ps.setString(2, stationId)
                ps.executeQuery().use { it.next() }
            }
            if (!breakIsOurs) return false

            val current = c.prepareStatement(
                "SELECT id FROM placements WHERE break_id = ? AND show_date = ?"
            ).use { ps ->
                ps.setLong(1, breakId)
                ps.setDate(2, java.sql.Date.valueOf(date))
                ps.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(rs.getLong(1)) }
                }
            }
            if (orderedIds.size != current.size || orderedIds.toSet() != current) return false

            c.autoCommit = false
            try {
                c.prepareStatement(
                    "UPDATE placements SET position = position + 100000 WHERE break_id = ? AND show_date = ?"
                ).use { ps ->
                    ps.setLong(1, breakId)
                    ps.setDate(2, java.sql.Date.valueOf(date))
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
     * True if the placement existed ON THIS STATION and is gone now. The join is
     * the authorization: a placement id belonging to a sibling station simply
     * does not match, so the delete is a no-op rather than a cross-station wipe.
     */
    fun deletePlacement(placementId: Long): Boolean =
        connection().use { c ->
            c.prepareStatement(
                """
                DELETE p FROM placements p
                JOIN spots s ON s.id = p.spot_id
                WHERE p.id = ? AND s.station_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, placementId)
                ps.setString(2, stationId)
                ps.executeUpdate() > 0
            }
        }

    /**
     * The `customers cu` -> `spots s` join path for the two party kinds.
     * [stationScoped] adds this station's filter to the spot - and takes a bound
     * parameter, which must be the FIRST one the caller sets.
     */
    private fun partyJoin(byTrader: Boolean, stationScoped: Boolean): String {
        val stationFilter = if (stationScoped) "AND s.station_id = ?" else ""
        return if (byTrader)
            """
            JOIN contracts ct       ON ct.customer_id = cu.id
            JOIN contract_lines cl  ON cl.contract_id = ct.id
            JOIN spots s            ON s.contract_line_id = cl.id AND s.hidden = FALSE $stationFilter
            """.trimIndent()
        else
            "JOIN spots s ON s.customer_id = cu.id AND s.hidden = FALSE $stationFilter"
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
     * This station's breaks. Ordered by TIME, not by id: ids are assigned by the
     * database now (they used to be a computed 1..96, which happened to be in
     * time order and would silently stop being so).
     */
    fun loadBreaks(): List<BreakSlotRow> =
        connection().use { c ->
            c.prepareStatement(
                """
                SELECT id, hour_of_day, minute_of_hour, label, zone
                FROM break_slots WHERE station_id = ?
                ORDER BY hour_of_day, minute_of_hour
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, stationId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<BreakSlotRow>()
                    while (rs.next()) {
                        out += BreakSlotRow(
                            id = rs.getLong(1),
                            hour = rs.getInt(2),
                            minute = rs.getInt(3),
                            label = rs.getString(4),
                            zone = BreakZone.valueOf(rs.getString(5))
                        )
                    }
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
            SELECT 1 FROM placements p
            JOIN spots s ON s.id = p.spot_id
            WHERE s.station_id = ? AND p.show_date >= ? AND p.show_date < ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, stationId)
            ps.setDate(2, java.sql.Date.valueOf(start)); ps.setDate(3, java.sql.Date.valueOf(end))
            ps.executeQuery().use { it.next() }
        }
    }

    /**
     * The month's grid, DERIVED from the normalized write model: one joined
     * query over placements ⋈ spots ⋈ customers ⋈ contracts, then per-cell
     * aggregates (spot count, total duration) and colours computed here.
     * Returns the same shapes the API always served - the client is unaware
     * of the schema evolution.
     */
    fun loadMonth(year: Int, month: Int): Pair<List<CellRow>, Map<Pair<Long, LocalDate>, List<CommercialRow>>> {
        val (start, end) = monthRange(year, month)
        val zoneByBreak = loadBreaks().associate { it.id to it.zone }

        val commercialsByKey = linkedMapOf<Pair<Long, LocalDate>, MutableList<CommercialRow>>()
        // Programme identity per cell (legacy `programtypes` semantics): the
        // break belongs to the programme airing at that slot, so the first
        // placement's programme name AND colour are the cell's.
        val programColorByKey = mutableMapOf<Pair<Long, LocalDate>, Int>()
        val programNameByKey = mutableMapOf<Pair<Long, LocalDate>, String>()

        connection().use { c ->
            c.prepareStatement(
                """
                SELECT p.id, p.spot_id, p.break_id, p.show_date, p.position, s.duration_seconds,
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
                WHERE s.station_id = ?
                  AND p.show_date >= ? AND p.show_date < ?
                  AND p.hidden = FALSE AND s.hidden = FALSE
                ORDER BY p.break_id, p.show_date, p.position
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, stationId)
                ps.setDate(2, java.sql.Date.valueOf(start)); ps.setDate(3, java.sql.Date.valueOf(end))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val breakId = rs.getLong("break_id")
                        val date = rs.getDate("show_date").toLocalDate()
                        val isGift = rs.getBoolean("is_gift")
                        val programColor = rs.getInt("program_color").takeIf { !rs.wasNull() }
                        val programName = rs.getString("program_name")
                        if (programColor != null) {
                            programColorByKey.putIfAbsent(breakId to date, programColor)
                        }
                        if (programName != null) {
                            programNameByKey.putIfAbsent(breakId to date, programName)
                        }
                        val list = commercialsByKey.getOrPut(breakId to date) { mutableListOf() }
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

        val cells = commercialsByKey.map { (key, commercials) ->
            val (breakId, date) = key
            val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            CellRow(
                breakId = breakId,
                date = date,
                spotCount = commercials.size,
                totalDurationSeconds = commercials.sumOf { it.durationSeconds },
                // The PROGRAMME's colour wins (operator-assigned identity,
                // migrated from the legacy app); the zone/weekend/density
                // rules are the demo fallback for placements without one.
                zoneColorArgb = programColorByKey[key] ?: cellColorArgb(
                    zone = zoneByBreak[breakId] ?: BreakZone.DEFAULT,
                    isWeekend = isWeekend,
                    spotCount = commercials.size
                ),
                programName = programNameByKey[key],
                commercials = emptyList()
            )
        }

        return cells to commercialsByKey
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
     * first, this request's INSERT hits the (break_id, show_date, position)
     * unique key and we treat that as "already seeded by someone else". This
     * is also correct across multiple server instances.
     */
    fun ensureMonthSeeded(year: Int, month: Int) {
        connection().use { c ->
            // Migrated stations hold REAL data: months without placements are
            // genuinely empty and must never be filled with demo spots.
            if (!isDemoSeedEnabled(c)) return
            if (monthHasPlacements(c, year, month)) return

            val breaks = loadBreaks()
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
                breaks, spots, year, month, stationSeed = stationId.hashCode()
            )
            if (generated.isEmpty()) return

            c.autoCommit = false
            try {
                c.prepareStatement(
                    """
                    INSERT INTO placements(spot_id, break_id, show_date, position, duration_seconds)
                    VALUES(?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    for (p in generated) {
                        ps.setLong(1, p.spotId)
                        ps.setLong(2, p.breakId)
                        ps.setDate(3, java.sql.Date.valueOf(p.date))
                        ps.setInt(4, p.position)
                        ps.setInt(5, p.durationSeconds)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
            } catch (e: SQLException) {
                c.rollback()
                // Another request already seeded this exact month concurrently
                // (generation is deterministic, so the rows collide) - nothing
                // to do. Anything else is a real failure.
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
