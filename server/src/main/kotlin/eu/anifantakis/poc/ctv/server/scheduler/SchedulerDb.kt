package eu.anifantakis.poc.ctv.server.scheduler

import eu.anifantakis.poc.ctv.server.config.ServerConfigLoader
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate

object SchedulerDb {

    fun connection(): Connection {
        val cfg = ServerConfigLoader.get()
        Class.forName("com.mysql.cj.jdbc.Driver")
        return DriverManager.getConnection(cfg.mysqlJdbcUrl, cfg.mysqlUsername, cfg.mysqlPassword)
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
            seedBreaksIfEmpty(c)
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

    private fun monthHasCells(c: Connection, year: Int, month: Int): Boolean =
        c.prepareStatement(
            "SELECT 1 FROM scheduler_cells WHERE YEAR(cell_date)=? AND MONTH(cell_date)=? LIMIT 1"
        ).use { ps ->
            ps.setInt(1, year); ps.setInt(2, month)
            ps.executeQuery().use { it.next() }
        }

    fun loadMonth(year: Int, month: Int): Pair<List<CellRow>, Map<Pair<Long, LocalDate>, List<CommercialRow>>> {
        connection().use { c ->
            val cells = mutableListOf<CellRow>()
            val commercialsByKey = mutableMapOf<Pair<Long, LocalDate>, MutableList<CommercialRow>>()

            c.prepareStatement(
                """
                SELECT break_id, cell_date, spot_count, total_duration_seconds, zone_color_argb
                FROM scheduler_cells
                WHERE YEAR(cell_date)=? AND MONTH(cell_date)=?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, year); ps.setInt(2, month)
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
                WHERE YEAR(cell_date)=? AND MONTH(cell_date)=?
                ORDER BY break_id, cell_date, position
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, year); ps.setInt(2, month)
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

    /** Loads the month; seeds it from the generator if the DB has no rows for that month. */
    fun ensureMonthSeeded(year: Int, month: Int) {
        connection().use { c ->
            if (monthHasCells(c, year, month)) return
            val breaks = loadBreaks()
            val generated = generateMonth(breaks, year, month)
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
            } catch (e: Exception) {
                c.rollback(); throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

}
