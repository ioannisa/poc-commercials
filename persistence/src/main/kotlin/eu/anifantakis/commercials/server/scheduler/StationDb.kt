package eu.anifantakis.commercials.server.scheduler

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import eu.anifantakis.commercials.server.stations.StationConfig
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One station's database: a dedicated connection pool over that station's
 * schema (its own jdbcUrl/credentials from server.yaml) plus the queries.
 * Instances are created and cached by StationRegistry - NOT a Koin
 * definition, since the set of stations is data, not wiring.
 *
 * SCHEMA (normalized, migration-ready - see migration/legacy-schema.md):
 *
 *   customers ──< contracts ──< contract_lines
 *       │                            │
 *       └────────< spots >───────────┘        (the catalog, ≙ legacy `messages`)
 *                    │
 *                    └──< placements          (the WRITE model, ≙ legacy `schedule`)
 *
 *   break_slots                               (station airtime grid config)
 *   flow_comments, print_audit                (≙ legacy roh_comments / roh_print_history)
 *
 * The grid the client renders (cells + their commercials) is a READ model
 * DERIVED at query time from placements ⋈ spots ⋈ customers ⋈ contracts -
 * aggregates and cell colours are computed, never stored. Every table carries
 * a nullable legacy_id so the future migration from the original app's dumps
 * is idempotent and cross-checkable.
 */
class StationDb(private val station: StationConfig, maxPoolSize: Int) {

    companion object {
        /**
         * Full email bodies kept per customer; older bodies are evicted
         * (summaries stay). Public: the legacy migration applies the SAME
         * cap when importing the old `emailhistory` archive.
         */
        const val EMAIL_BODY_RETENTION_PER_CUSTOMER = 10
    }

    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = station.jdbcUrl
            username = station.username
            password = station.password
            driverClassName = "com.mysql.cj.jdbc.Driver"
            // Several station pools coexist - ceiling resolved from
            // server.yaml (per-station override / file default / built-in)
            maximumPoolSize = maxPoolSize
            minimumIdle = 1
            connectionTimeout = 10_000
            // Do not fail-fast at pool construction: connections are
            // validated on first use, so an unreachable MySQL (or a test
            // environment without one) doesn't crash instance creation
            initializationFailTimeout = -1
            poolName = "station-${station.id}"
        }
    )

    /** A pooled connection; closing it (e.g. via `.use {}`) returns it to the pool. */
    fun connection(): Connection = dataSource.getConnection()

    fun close() {
        dataSource.close()
    }

    // ────────────────────────────────────────────────────────── bootstrap ──

    /**
     * Creates the schema. [seedDemo] false is for MIGRATED stations: the
     * migration tool builds breaks/catalog from the legacy dump instead of
     * demo data, and empty months must STAY empty. The choice is recorded in
     * station_meta (`demo_seed`) with INSERT IGNORE, so a later server
     * bootstrap with the default `true` can never flip a migrated station
     * back to demo seeding.
     */
    fun bootstrap(seedDemo: Boolean = true) {
        connection().use { c ->
            dropLegacyDemoTables(c)
            c.createStatement().use { s ->
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS break_slots (
                        id BIGINT PRIMARY KEY,
                        hour_of_day TINYINT NOT NULL,
                        minute_of_hour TINYINT NOT NULL,
                        label VARCHAR(8) NOT NULL,
                        zone VARCHAR(16) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS customers (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_id BIGINT NULL,
                        legacy_lee_id BIGINT NULL,
                        code VARCHAR(32) NOT NULL UNIQUE,
                        name VARCHAR(128) NOT NULL,
                        vat_number VARCHAR(16) NULL,
                        contact_person VARCHAR(64) NULL,
                        phone VARCHAR(32) NULL,
                        fax VARCHAR(32) NULL,
                        email VARCHAR(128) NULL,
                        notes TEXT NULL,
                        synthetic BOOLEAN NOT NULL DEFAULT FALSE,
                        -- Galaxy (the client's NEW ERP) linkage: its ids are UUID
                        -- strings. NULL until the Galaxy customer import matches this
                        -- customer by VAT number and stamps the UUID here.
                        galaxy_id VARCHAR(36) NULL,
                        KEY idx_customers_legacy (legacy_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS contracts (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_docid BIGINT NULL,
                        number VARCHAR(64) NOT NULL,
                        doc_type INT NULL,
                        is_gift BOOLEAN NOT NULL DEFAULT FALSE,
                        exclude_from_reports BOOLEAN NOT NULL DEFAULT FALSE,
                        customer_id BIGINT NOT NULL,
                        agency_id BIGINT NULL,
                        entry_date DATE NULL,
                        -- Contract period + renewal (Phase 6). PROVISIONAL until the
                        -- Oracle ERP import supplies real values: the migration derives
                        -- start/end from placements and sets dates_provisional=TRUE;
                        -- renewed_at has no source yet and stays NULL.
                        start_date DATE NULL,
                        end_date DATE NULL,
                        renewed_at DATE NULL,
                        dates_provisional BOOLEAN NOT NULL DEFAULT FALSE,
                        synthetic BOOLEAN NOT NULL DEFAULT FALSE,
                        KEY idx_contracts_number (number),
                        KEY idx_contracts_legacy (legacy_docid),
                        CONSTRAINT fk_contracts_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
                        CONSTRAINT fk_contracts_agency FOREIGN KEY (agency_id) REFERENCES customers(id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS contract_lines (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        contract_id BIGINT NOT NULL,
                        line_no INT NOT NULL,
                        desired_qty INT NOT NULL DEFAULT 0,
                        agel_val DECIMAL(10,6) NOT NULL DEFAULT 0,
                        eidikos_val DECIMAL(10,6) NOT NULL DEFAULT 0,
                        zone_val DECIMAL(10,2) NOT NULL DEFAULT 0,
                        UNIQUE KEY uq_contract_line (contract_id, line_no),
                        CONSTRAINT fk_lines_contract FOREIGN KEY (contract_id)
                            REFERENCES contracts(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS spots (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_id BIGINT NULL,
                        customer_id BIGINT NOT NULL,
                        contract_line_id BIGINT NULL,
                        description VARCHAR(255) NOT NULL,
                        duration_seconds INT NOT NULL,
                        spot_type VARCHAR(64) NOT NULL DEFAULT '',
                        flow VARCHAR(32) NOT NULL DEFAULT '',
                        hidden BOOLEAN NOT NULL DEFAULT FALSE,
                        force_position INT NULL,
                        memo TEXT NULL,
                        KEY idx_spots_legacy (legacy_id),
                        CONSTRAINT fk_spots_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
                        CONSTRAINT fk_spots_line FOREIGN KEY (contract_line_id) REFERENCES contract_lines(id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS programs (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_id BIGINT NULL,
                        name VARCHAR(128) NOT NULL,
                        color_argb INT NULL,
                        hidden BOOLEAN NOT NULL DEFAULT FALSE,
                        KEY idx_programs_legacy (legacy_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS placements (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_id BIGINT NULL,
                        spot_id BIGINT NOT NULL,
                        break_id BIGINT NOT NULL,
                        show_date DATE NOT NULL,
                        position INT NOT NULL,
                        duration_seconds INT NOT NULL,
                        program_id BIGINT NULL,
                        played BOOLEAN NOT NULL DEFAULT FALSE,
                        hidden BOOLEAN NOT NULL DEFAULT FALSE,
                        UNIQUE KEY uq_placement_slot (break_id, show_date, position),
                        KEY idx_placements_legacy (legacy_id),
                        CONSTRAINT fk_placements_spot FOREIGN KEY (spot_id) REFERENCES spots(id),
                        CONSTRAINT fk_placements_break FOREIGN KEY (break_id) REFERENCES break_slots(id),
                        CONSTRAINT fk_placements_program FOREIGN KEY (program_id) REFERENCES programs(id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                // Airtime commercial policy (≙ legacy zones/zonefillers):
                // the price list, VERSIONED by valid_from - the legacy app
                // kept every historical price row, and so do we.
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS zone_fillers (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_id BIGINT NULL,
                        code VARCHAR(32) NOT NULL,
                        label VARCHAR(64) NOT NULL,
                        price DECIMAL(10,2) NOT NULL DEFAULT 0,
                        valid_from DATE NULL,
                        KEY idx_zone_fillers_legacy (legacy_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS zones (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        legacy_id BIGINT NULL,
                        code VARCHAR(32) NOT NULL,
                        label VARCHAR(64) NOT NULL,
                        from_time TIME NULL,
                        end_time TIME NULL,
                        filler_from_time TIME NULL,
                        price DECIMAL(10,2) NOT NULL DEFAULT 0,
                        valid_from DATE NULL,
                        public_sector BOOLEAN NOT NULL DEFAULT FALSE,
                        filler_id BIGINT NULL,
                        KEY idx_zones_legacy (legacy_id),
                        CONSTRAINT fk_zones_filler FOREIGN KEY (filler_id) REFERENCES zone_fillers(id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS flow_comments (
                        show_date DATE PRIMARY KEY,
                        comments TEXT NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                // ≙ legacy `emailhistory`: the audit archive of customer
                // schedule emails. Summary rows are kept forever; the full
                // HTML body is capped per customer (see logEmail) so the
                // archive can't balloon like the legacy 1.2 GB email store.
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS email_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        customer_code VARCHAR(32) NOT NULL,
                        customer_name VARCHAR(128) NOT NULL,
                        recipient VARCHAR(255) NOT NULL,
                        subject VARCHAR(255) NOT NULL,
                        period_year INT NOT NULL,
                        period_month INT NOT NULL,
                        spot_count INT NOT NULL DEFAULT 0,
                        transmission_count INT NOT NULL DEFAULT 0,
                        body_html LONGTEXT NULL,
                        sent_by VARCHAR(64) NOT NULL,
                        sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        status VARCHAR(16) NOT NULL,
                        error VARCHAR(512) NULL,
                        KEY idx_email_log_customer (customer_code, period_year, period_month),
                        KEY idx_email_log_sent (sent_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS print_audit (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        printed_date DATE NOT NULL,
                        username VARCHAR(64) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        KEY idx_print_audit_date (printed_date)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS station_meta (
                        meta_key VARCHAR(64) PRIMARY KEY,
                        meta_value VARCHAR(255) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
            }
            // show_date is not the leftmost column of the placement unique
            // key, so month-range scans need their own index.
            ensureIndex(c, "placements", "idx_placements_date", "show_date")
            // Schemas created before the migration tool lack these columns.
            ensureColumn(c, "customers", "synthetic", "BOOLEAN NOT NULL DEFAULT FALSE")
            ensureColumn(c, "contracts", "synthetic", "BOOLEAN NOT NULL DEFAULT FALSE")
            // legacy calendar_excluded_docs: contracts whose spots stay OFF printed reports
            ensureColumn(c, "contracts", "exclude_from_reports", "BOOLEAN NOT NULL DEFAULT FALSE")
            // raw ERP doc ids awaiting the ERP import (see LegacyTransformer)
            c.createStatement().use {
                it.executeUpdate("CREATE TABLE IF NOT EXISTS erp_excluded_docs (erp_docid BIGINT PRIMARY KEY) ENGINE=InnoDB")
            }
            // ERP LEE id (second legacy id series): links end clients of
            // triangular contracts - see migration/legacy-schema.md, docref.
            ensureColumn(c, "customers", "legacy_lee_id", "BIGINT NULL")
            ensureIndex(c, "customers", "idx_customers_lee_legacy", "legacy_lee_id")
            // Schemas created before programme colours (the ALTER path skips
            // the FK; fresh schemas get it via CREATE TABLE above).
            ensureColumn(c, "placements", "program_id", "BIGINT NULL")
            // Contract period/renewal dates (Phase 6) - see the CREATE TABLE note.
            // Provisional until the ERP import; the migration backfills start/end.
            ensureColumn(c, "contracts", "start_date", "DATE NULL")
            ensureColumn(c, "contracts", "end_date", "DATE NULL")
            ensureColumn(c, "contracts", "renewed_at", "DATE NULL")
            ensureColumn(c, "contracts", "dates_provisional", "BOOLEAN NOT NULL DEFAULT FALSE")
            // Galaxy (new ERP) UUID - NULL until the Galaxy customer import
            // matches by VAT number (see the CREATE TABLE note).
            ensureColumn(c, "customers", "galaxy_id", "VARCHAR(36) NULL")

            // INSERT IGNORE: first writer wins - a migrated station stays
            // demo_seed=false forever, whoever bootstraps it afterwards.
            c.prepareStatement(
                "INSERT IGNORE INTO station_meta(meta_key, meta_value) VALUES('demo_seed', ?)"
            ).use { ps ->
                ps.setString(1, if (seedDemo) "true" else "false")
                ps.executeUpdate()
            }

            if (seedDemo && isDemoSeedEnabled(c)) {
                seedBreaksIfEmpty(c)
                seedCatalogIfEmpty(c)
            }
        }
    }

    private fun isDemoSeedEnabled(c: Connection): Boolean =
        c.prepareStatement("SELECT meta_value FROM station_meta WHERE meta_key = 'demo_seed'").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) == "true" else true }
        }

    private fun ensureColumn(c: Connection, table: String, column: String, definition: String) {
        val exists = c.prepareStatement(
            """
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, column)
            ps.executeQuery().use { it.next() }
        }
        if (!exists) {
            c.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition") }
        }
    }

    /**
     * Migration from the pre-normalization demo schema: the old
     * `scheduler_cells` (stored aggregates) and `commercials` (denormalized
     * rows) tables are superseded by the derived read model. Their content
     * was deterministic demo data, so they are simply dropped; months reseed
     * on demand into `placements`. Idempotent.
     */
    private fun dropLegacyDemoTables(c: Connection) {
        for (table in listOf("commercials", "scheduler_cells")) {
            val exists = c.prepareStatement(
                """
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { it.next() }
            }
            if (exists) {
                c.createStatement().use { it.executeUpdate("DROP TABLE $table") }
            }
        }
    }

    private fun ensureIndex(c: Connection, table: String, indexName: String, columns: String) {
        val exists = c.prepareStatement(
            """
            SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, indexName)
            ps.executeQuery().use { it.next() }
        }
        if (!exists) {
            c.createStatement().use { it.executeUpdate("CREATE INDEX $indexName ON $table($columns)") }
        }
    }

    private fun seedBreaksIfEmpty(c: Connection) {
        val count = c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM break_slots").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
        if (count > 0) return
        c.autoCommit = false
        try {
            c.prepareStatement(
                "INSERT INTO break_slots(id, hour_of_day, minute_of_hour, label, zone) VALUES(?,?,?,?,?)"
            ).use { ps ->
                for (b in generateBreaks()) {
                    ps.setLong(1, b.id)
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
     * Demo catalog: customers (with ΑΦΜ), one gift contract + line each, and
     * a deterministic per-station spot catalog. Runs once per schema; real
     * deployments will fill these tables via the legacy migration instead.
     */
    private fun seedCatalogIfEmpty(c: Connection) {
        val count = c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM spots").use { rs -> rs.next(); rs.getInt(1) }
        }
        if (count > 0) return

        val random = kotlin.random.Random(station.id.hashCode())
        c.autoCommit = false
        try {
            val customerIds = mutableListOf<Long>()
            val lineIdByCustomer = mutableMapOf<Long, Long>()

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
                        "INSERT INTO contract_lines(contract_id, line_no, desired_qty) VALUES(?,1,0)",
                        Statement.RETURN_GENERATED_KEYS
                    ).use { lps ->
                        lps.setLong(1, contractId)
                        lps.executeUpdate()
                        lps.generatedKeys.use { rs -> rs.next(); lineIdByCustomer[customerId] = rs.getLong(1) }
                    }
                }
            }

            c.prepareStatement(
                """
                INSERT INTO spots(customer_id, contract_line_id, description, duration_seconds, spot_type, flow)
                VALUES(?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                repeat(DEMO_SPOTS_PER_STATION) {
                    val customerId = customerIds[random.nextInt(customerIds.size)]
                    ps.setLong(1, customerId)
                    ps.setLong(2, lineIdByCustomer.getValue(customerId))
                    ps.setString(3, demoMessages[random.nextInt(demoMessages.size)])
                    ps.setInt(4, demoDurations[random.nextInt(demoDurations.size)])
                    ps.setString(5, demoSpotTypes[random.nextInt(demoSpotTypes.size)])
                    ps.setString(6, "ΡΟΗ")
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

    /** Quick footprint of this station's data (Databases admin screen). */
    fun placementStats(): PlacementStats =
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*), MIN(show_date), MAX(show_date) FROM placements").use { rs ->
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
                INSERT INTO email_log(customer_code, customer_name, recipient, subject,
                    period_year, period_month, spot_count, transmission_count, body_html,
                    sent_by, status, error)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, entry.customerCode)
                ps.setString(2, entry.customerName)
                ps.setString(3, entry.recipient)
                ps.setString(4, entry.subject)
                ps.setInt(5, entry.year)
                ps.setInt(6, entry.month)
                ps.setInt(7, entry.spotCount)
                ps.setInt(8, entry.transmissionCount)
                if (entry.bodyHtml != null) ps.setString(9, entry.bodyHtml) else ps.setNull(9, java.sql.Types.LONGVARCHAR)
                ps.setString(10, entry.sentBy)
                ps.setString(11, entry.status)
                if (entry.error != null) ps.setString(12, entry.error.take(512)) else ps.setNull(12, java.sql.Types.VARCHAR)
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

    /** Recent send history (metadata only), newest first; optionally one customer. */
    fun recentEmailLog(limit: Int, clientCode: String? = null): List<EmailLogRow> =
        connection().use { c ->
            val sql = buildString {
                append("SELECT id, customer_code, customer_name, recipient, subject, period_year, period_month, ")
                append("spot_count, transmission_count, sent_by, sent_at, status, error FROM email_log ")
                if (clientCode != null) append("WHERE customer_code = ? ")
                append("ORDER BY sent_at DESC, id DESC LIMIT ?")
            }
            c.prepareStatement(sql).use { ps ->
                var i = 1
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
            c.prepareStatement("SELECT body_html FROM email_log WHERE id = ?").use { ps ->
                ps.setLong(1, id)
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
                       COUNT(DISTINCT s.id) AS spot_count, COUNT(p.id) AS placement_count
                FROM customers cu
                ${partyJoin(byTrader)}
                JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                WHERE (cu.name LIKE ? OR cu.code LIKE ?)
                GROUP BY cu.id, cu.code, cu.name, cu.email, cu.vat_number, cu.phone
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
     * The months in which a party has airings, newest first with counts -
     * feeds the year/month drill-down after the party is picked.
     */
    fun partyActivity(code: String, byTrader: Boolean): List<ActivityMonth> =
        connection().use { c ->
            c.prepareStatement(
                """
                SELECT YEAR(p.show_date) AS y, MONTH(p.show_date) AS m, COUNT(*) AS cnt
                FROM customers cu
                ${partyJoin(byTrader)}
                JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                WHERE cu.code = ?
                GROUP BY y, m
                ORDER BY y DESC, m DESC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, code)
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
     * The party's contract lines: for a CUSTOMER the lines whose spots
     * belong to them; for a TRADER the lines of the contracts they pay.
     */
    fun partyContractLines(code: String, byTrader: Boolean): List<ContractLineRow> {
        val partyFilter =
            if (byTrader) "JOIN customers cu ON cu.id = ct.customer_id AND cu.code = ?"
            else "JOIN customers cu ON cu.id = s.customer_id AND cu.code = ?"
        return connection().use { c ->
            c.prepareStatement(
                """
                SELECT cl.id AS line_id, ct.number, ct.is_gift, ct.entry_date, cl.line_no, cl.desired_qty,
                       COUNT(DISTINCT s.id) AS spot_count, COUNT(p.id) AS placements,
                       COALESCE(SUM(p.duration_seconds), 0) AS total_secs
                FROM contract_lines cl
                JOIN contracts ct ON ct.id = cl.contract_id
                JOIN spots s ON s.contract_line_id = cl.id AND s.hidden = FALSE
                $partyFilter
                LEFT JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                GROUP BY cl.id, ct.number, ct.is_gift, ct.entry_date, cl.line_no, cl.desired_qty
                ORDER BY ct.number, cl.line_no
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, code)
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

    /** The spots (creatives) hanging off one contract line. */
    fun contractLineSpots(lineId: Long): List<LineSpotRow> =
        connection().use { c ->
            c.prepareStatement(
                """
                SELECT s.id, s.description, s.duration_seconds, COUNT(p.id) AS placements,
                       COALESCE(SUM(p.duration_seconds), 0) AS total_secs
                FROM spots s
                LEFT JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                WHERE s.contract_line_id = ? AND s.hidden = FALSE
                GROUP BY s.id, s.description, s.duration_seconds
                ORDER BY s.description, s.id
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, lineId)
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

    /**
     * Appends [spotId] at the END of the (break, date) cell - next free
     * position, duration copied from the spot (like the legacy add). The
     * (break_id, show_date, position) unique key turns concurrent adds into
     * a retry with the next position. Returns the new placement as the same
     * row shape the month grid serves, or null if the spot doesn't exist.
     */
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
     */
    fun contractStatus(code: String, byTrader: Boolean): List<ContractStatusRow> {
        val partyFilter =
            if (byTrader) "JOIN customers cu ON cu.id = ct.customer_id AND cu.code = ?"
            else "JOIN customers cu ON cu.id = s.customer_id AND cu.code = ?"
        return connection().use { c ->
            c.prepareStatement(
                """
                SELECT ct.number, ct.is_gift, ct.start_date, ct.end_date, ct.renewed_at, ct.dates_provisional,
                       MIN(p.show_date) AS first_aired, MAX(p.show_date) AS last_aired, COUNT(p.id) AS placements
                FROM contracts ct
                JOIN contract_lines cl ON cl.contract_id = ct.id
                JOIN spots s ON s.contract_line_id = cl.id AND s.hidden = FALSE
                $partyFilter
                LEFT JOIN placements p ON p.spot_id = s.id AND p.hidden = FALSE
                GROUP BY ct.id, ct.number, ct.is_gift, ct.start_date, ct.end_date, ct.renewed_at, ct.dates_provisional
                ORDER BY last_aired DESC, ct.number
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, code)
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

    fun addPlacement(spotId: Long, breakId: Long, date: LocalDate): CommercialRow? {
        connection().use { c ->
            val duration = c.prepareStatement(
                "SELECT duration_seconds FROM spots WHERE id = ? AND hidden = FALSE"
            ).use { ps ->
                ps.setLong(1, spotId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else return null }
            }

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
            SELECT p.id, p.spot_id, p.position, p.duration_seconds,
                   s.description, s.spot_type, s.flow,
                   cu.code AS client_code, cu.name AS client_name,
                   ct.number AS contract_number, ct.is_gift, ct.exclude_from_reports,
                   pay.code AS payer_code, pay.name AS payer_name,
                   pr.color_argb AS program_color, pr.name AS program_name
            FROM placements p
            JOIN spots s      ON s.id = p.spot_id
            JOIN customers cu ON cu.id = s.customer_id
            LEFT JOIN contract_lines cl ON cl.id = s.contract_line_id
            LEFT JOIN contracts ct      ON ct.id = cl.contract_id
            LEFT JOIN customers pay     ON pay.id = ct.customer_id
            LEFT JOIN programs pr       ON pr.id = p.program_id
            WHERE p.id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, placementId)
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
                    contract = if (rs.getBoolean("is_gift")) "ΔΩΡΑ" else (rs.getString("contract_number") ?: ""),
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
     */
    fun reorderPlacements(breakId: Long, date: LocalDate, orderedIds: List<Long>): Boolean =
        connection().use { c ->
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

    /** True if the placement existed and is gone now. */
    fun deletePlacement(placementId: Long): Boolean =
        connection().use { c ->
            c.prepareStatement("DELETE FROM placements WHERE id = ?").use { ps ->
                ps.setLong(1, placementId)
                ps.executeUpdate() > 0
            }
        }

    /** The `customers cu` -> `spots s` join path for the two party kinds. */
    private fun partyJoin(byTrader: Boolean): String =
        if (byTrader)
            """
            JOIN contracts ct       ON ct.customer_id = cu.id
            JOIN contract_lines cl  ON cl.contract_id = ct.id
            JOIN spots s            ON s.contract_line_id = cl.id AND s.hidden = FALSE
            """.trimIndent()
        else
            "JOIN spots s ON s.customer_id = cu.id AND s.hidden = FALSE"

    /** `%query%` with LIKE wildcards neutralized (MySQL's default escape is `\`). */
    private fun likeContains(query: String): String {
        val escaped = query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    /** Customer lookup for the schedule email (default recipient + salutation). */
    fun customerByCode(code: String): CustomerContact? =
        connection().use { c ->
            c.prepareStatement("SELECT name, email FROM customers WHERE code = ?").use { ps ->
                ps.setString(1, code)
                ps.executeQuery().use { rs ->
                    if (rs.next()) CustomerContact(rs.getString(1), rs.getString(2)?.ifBlank { null }) else null
                }
            }
        }

    fun loadBreaks(): List<BreakSlotRow> =
        connection().use { c ->
            c.prepareStatement(
                "SELECT id, hour_of_day, minute_of_hour, label, zone FROM break_slots ORDER BY id"
            ).use { ps ->
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
            "SELECT 1 FROM placements WHERE show_date >= ? AND show_date < ? LIMIT 1"
        ).use { ps ->
            ps.setDate(1, java.sql.Date.valueOf(start)); ps.setDate(2, java.sql.Date.valueOf(end))
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
                SELECT p.id, p.spot_id, p.break_id, p.show_date, p.position, p.duration_seconds,
                       s.description, s.spot_type, s.flow,
                       cu.code AS client_code, cu.name AS client_name,
                       ct.number AS contract_number, ct.is_gift, ct.exclude_from_reports,
                       pay.code AS payer_code, pay.name AS payer_name,
                       pr.color_argb AS program_color, pr.name AS program_name
                FROM placements p
                JOIN spots s      ON s.id = p.spot_id
                JOIN customers cu ON cu.id = s.customer_id
                LEFT JOIN contract_lines cl ON cl.id = s.contract_line_id
                LEFT JOIN contracts ct      ON ct.id = cl.contract_id
                LEFT JOIN customers pay     ON pay.id = ct.customer_id
                LEFT JOIN programs pr       ON pr.id = p.program_id
                WHERE p.show_date >= ? AND p.show_date < ?
                  AND p.hidden = FALSE AND s.hidden = FALSE
                ORDER BY p.break_id, p.show_date, p.position
                """.trimIndent()
            ).use { ps ->
                ps.setDate(1, java.sql.Date.valueOf(start)); ps.setDate(2, java.sql.Date.valueOf(end))
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
                            contract = if (isGift) "ΔΩΡΑ" else (rs.getString("contract_number") ?: ""),
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
     * Loads the month; seeds demo placements if the schema has none for that
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
            val spots = c.prepareStatement("SELECT id, duration_seconds FROM spots ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(SpotRef(rs.getLong(1), rs.getInt(2)))
                    }
                }
            }
            val generated = generateMonthPlacements(
                breaks, spots, year, month, stationSeed = station.id.hashCode()
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
