package eu.anifantakis.commercials.server.scheduler

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import eu.anifantakis.commercials.server.stations.StationConfig
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate

/**
 * One station's schedule database: a dedicated connection pool over that
 * station's schema (its own jdbcUrl/credentials from stations.yaml) plus the
 * schedule queries. Every hosted station has an identical table structure;
 * instances are created and cached by StationRegistry - NOT a Koin
 * definition, since the set of stations is data, not wiring.
 */
class StationDb(private val station: StationConfig, maxPoolSize: Int) {

    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = station.jdbcUrl
            username = station.username
            password = station.password
            driverClassName = "com.mysql.cj.jdbc.Driver"
            // Several station pools coexist - ceiling resolved from
            // stations.yaml (per-station override / file default / built-in)
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

    fun bootstrap() {
        connection().use { c ->
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
                    CREATE TABLE IF NOT EXISTS scheduler_cells (
                        break_id BIGINT NOT NULL,
                        cell_date DATE NOT NULL,
                        spot_count INT NOT NULL,
                        total_duration_seconds INT NOT NULL,
                        zone_color_argb INT NOT NULL,
                        PRIMARY KEY (break_id, cell_date),
                        CONSTRAINT fk_cells_break FOREIGN KEY (break_id) REFERENCES break_slots(id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS commercials (
                        id BIGINT NOT NULL,
                        break_id BIGINT NOT NULL,
                        cell_date DATE NOT NULL,
                        position INT NOT NULL,
                        client_code VARCHAR(32) NOT NULL,
                        client_name VARCHAR(128) NOT NULL,
                        message VARCHAR(255) NOT NULL,
                        duration_seconds INT NOT NULL,
                        type VARCHAR(64) NOT NULL,
                        contract VARCHAR(32) NOT NULL,
                        flow VARCHAR(32) NOT NULL,
                        PRIMARY KEY (break_id, cell_date, position),
                        CONSTRAINT fk_commercials_cell FOREIGN KEY (break_id, cell_date)
                            REFERENCES scheduler_cells(break_id, cell_date) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
            }
            // cell_date is the SECOND column of each table's primary key, so
            // MySQL can't use those PKs for a date-only WHERE (no leftmost
            // prefix match). Without a dedicated index, loadMonth/monthHasCells
            // force a full table scan that gets worse every month of data added.
            ensureIndex(c, "scheduler_cells", "idx_scheduler_cells_date", "cell_date")
            ensureIndex(c, "commercials", "idx_commercials_date", "cell_date")
            seedBreaksIfEmpty(c)
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

    private fun monthHasCells(c: Connection, year: Int, month: Int): Boolean {
        val (start, end) = monthRange(year, month)
        return c.prepareStatement(
            "SELECT 1 FROM scheduler_cells WHERE cell_date >= ? AND cell_date < ? LIMIT 1"
        ).use { ps ->
            ps.setDate(1, java.sql.Date.valueOf(start)); ps.setDate(2, java.sql.Date.valueOf(end))
            ps.executeQuery().use { it.next() }
        }
    }

    fun loadMonth(year: Int, month: Int): Pair<List<CellRow>, Map<Pair<Long, LocalDate>, List<CommercialRow>>> {
        val (start, end) = monthRange(year, month)
        connection().use { c ->
            val cells = mutableListOf<CellRow>()
            val commercialsByKey = mutableMapOf<Pair<Long, LocalDate>, MutableList<CommercialRow>>()

            c.prepareStatement(
                """
                SELECT break_id, cell_date, spot_count, total_duration_seconds, zone_color_argb
                FROM scheduler_cells
                WHERE cell_date >= ? AND cell_date < ?
                """.trimIndent()
            ).use { ps ->
                ps.setDate(1, java.sql.Date.valueOf(start)); ps.setDate(2, java.sql.Date.valueOf(end))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val breakId = rs.getLong(1)
                        val date = rs.getDate(2).toLocalDate()
                        cells += CellRow(
                            breakId = breakId,
                            date = date,
                            spotCount = rs.getInt(3),
                            totalDurationSeconds = rs.getInt(4),
                            zoneColorArgb = rs.getInt(5),
                            commercials = emptyList()
                        )
                    }
                }
            }

            c.prepareStatement(
                """
                SELECT break_id, cell_date, position, id, client_code, client_name, message,
                       duration_seconds, type, contract, flow
                FROM commercials
                WHERE cell_date >= ? AND cell_date < ?
                ORDER BY break_id, cell_date, position
                """.trimIndent()
            ).use { ps ->
                ps.setDate(1, java.sql.Date.valueOf(start)); ps.setDate(2, java.sql.Date.valueOf(end))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val breakId = rs.getLong(1)
                        val date = rs.getDate(2).toLocalDate()
                        val list = commercialsByKey.getOrPut(breakId to date) { mutableListOf() }
                        list += CommercialRow(
                            position = rs.getInt(3),
                            id = rs.getLong(4),
                            clientCode = rs.getString(5),
                            clientName = rs.getString(6),
                            message = rs.getString(7),
                            durationSeconds = rs.getInt(8),
                            type = rs.getString(9),
                            contract = rs.getString(10),
                            flow = rs.getString(11)
                        )
                    }
                }
            }

            return cells to commercialsByKey
        }
    }

    /**
     * Loads the month; seeds it from the generator if the DB has no rows for
     * that month.
     *
     * Concurrency: two requests for the same unseeded month can both pass the
     * `monthHasCells` check before either commits (check-then-act race - each
     * request uses its own connection, so there's no in-process serialization
     * point). Since generation is deterministic (RNG seeded from station +
     * year/month), both requests would generate byte-for-byte identical rows;
     * if a concurrent insert already committed the same rows first, this
     * request's INSERT hits a duplicate primary key and we treat that as
     * "already seeded by someone else" rather than a failure. This is also
     * correct across multiple server instances, unlike an in-process lock.
     */
    fun ensureMonthSeeded(year: Int, month: Int) {
        connection().use { c ->
            if (monthHasCells(c, year, month)) return
            val breaks = loadBreaks()
            // Seed differs per station so hosted schemas hold visibly
            // different demo data
            val generated = generateMonth(breaks, year, month, stationSeed = station.id.hashCode())
            c.autoCommit = false
            try {
                c.prepareStatement(
                    """
                    INSERT INTO scheduler_cells(break_id, cell_date, spot_count, total_duration_seconds, zone_color_argb)
                    VALUES(?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    for (cell in generated) {
                        ps.setLong(1, cell.breakId)
                        ps.setDate(2, java.sql.Date.valueOf(cell.date))
                        ps.setInt(3, cell.spotCount)
                        ps.setInt(4, cell.totalDurationSeconds)
                        ps.setInt(5, cell.zoneColorArgb)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.prepareStatement(
                    """
                    INSERT INTO commercials(id, break_id, cell_date, position, client_code, client_name,
                                            message, duration_seconds, type, contract, flow)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    for (cell in generated) {
                        for (com in cell.commercials) {
                            ps.setLong(1, com.id)
                            ps.setLong(2, cell.breakId)
                            ps.setDate(3, java.sql.Date.valueOf(cell.date))
                            ps.setInt(4, com.position)
                            ps.setString(5, com.clientCode)
                            ps.setString(6, com.clientName)
                            ps.setString(7, com.message)
                            ps.setInt(8, com.durationSeconds)
                            ps.setString(9, com.type)
                            ps.setString(10, com.contract)
                            ps.setString(11, com.flow)
                            ps.addBatch()
                        }
                    }
                    ps.executeBatch()
                }
                c.commit()
            } catch (e: SQLException) {
                c.rollback()
                // Another request already seeded this exact month concurrently
                // (generation is deterministic, so the rows collide byte-for-byte) -
                // nothing to do. Anything else is a real failure.
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
