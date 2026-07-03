package eu.anifantakis.commercials.server.migration

import java.io.BufferedReader
import java.io.File
import java.sql.Connection

/**
 * The legacy tables the migration actually reads. Everything else in the
 * dump is skipped during replay - notably `emailhistory`, which is ~70% of
 * the main dump's bytes (longtext email bodies) and irrelevant to scheduling.
 */
val LEGACY_TABLES_OF_INTEREST = setOf(
    "messages",                    // spot catalog
    "schedule",                    // placements
    "docref",                      // ERP contract/document shadow
    "z_commercials",               // ERP doc lines <-> spots
    "sld",                         // doc types flagged gift
    "cus",                         // customer contact supplement
    "programtypes",                // spot/programme types
    "commercials_calendar",        // flattened rows WITH customer names + ΑΦΜ
    "commercials_calendar_final",  // (used to RECOVER real customer names)
    "roh_comments",                // per-day flow comments
    "roh_print_history",           // flow print audit
)

/**
 * Streams a mysqldump file into [scratchSchema], replaying only the
 * statements that matter: DROP/CREATE TABLE and INSERT INTO for
 * [LEGACY_TABLES_OF_INTEREST]. Runs with sql_mode='' because legacy MyISAM
 * data is full of `0000-00-00` dates that MySQL 8 strict mode rejects.
 *
 * mysqldump format facts this relies on: every INSERT is a single (possibly
 * megabyte-long) line ending in `;`, and CREATE TABLE blocks span multiple
 * lines up to a line ending in `;`.
 */
class DumpReplayer(
    private val connection: Connection,
    private val scratchSchema: String,
    private val log: (String) -> Unit,
) {

    fun replay(dumpFile: File): Map<String, Long> {
        val rowsPerTable = linkedMapOf<String, Long>()
        connection.createStatement().use { s ->
            s.execute("SET SESSION sql_mode = ''")
            s.execute("SET SESSION foreign_key_checks = 0")
            s.execute("USE `$scratchSchema`")
        }

        var bytesRead = 0L
        var lastReportedMb = 0L
        val totalMb = dumpFile.length() / 1_048_576

        dumpFile.bufferedReader(Charsets.UTF_8).use { reader ->
            val stmt = connection.createStatement()
            stmt.setEscapeProcessing(false)
            stmt.use { s ->
                while (true) {
                    val line = reader.readLine() ?: break
                    bytesRead += line.length + 1L

                    val mb = bytesRead / 1_048_576
                    if (mb >= lastReportedMb + 100) {
                        lastReportedMb = mb
                        log("  ... replayed $mb/$totalMb MB")
                    }

                    when {
                        line.startsWith("INSERT INTO `") -> {
                            val table = line.substringAfter("INSERT INTO `").substringBefore("`")
                            if (table in LEGACY_TABLES_OF_INTEREST) {
                                val count = s.executeUpdate(line.removeSuffix(";"))
                                rowsPerTable.merge(table, count.toLong(), Long::plus)
                            }
                        }

                        line.startsWith("DROP TABLE IF EXISTS `") -> {
                            val table = line.substringAfter("DROP TABLE IF EXISTS `").substringBefore("`")
                            if (table in LEGACY_TABLES_OF_INTEREST) {
                                s.executeUpdate(line.removeSuffix(";"))
                            }
                        }

                        line.startsWith("CREATE TABLE `") -> {
                            val table = line.substringAfter("CREATE TABLE `").substringBefore("`")
                            val block = StringBuilder(line)
                            var l = line
                            while (!l.trimEnd().endsWith(";")) {
                                l = reader.readLine() ?: break
                                bytesRead += l.length + 1L
                                block.append('\n').append(l)
                            }
                            if (table in LEGACY_TABLES_OF_INTEREST) {
                                // MEMORY tables (pelates_of_pelates) aren't in our
                                // list; MyISAM replays fine on MySQL 8.
                                s.executeUpdate(block.toString().trimEnd().removeSuffix(";"))
                                rowsPerTable.putIfAbsent(table, 0L)
                            }
                        }

                        // Everything else (SET/LOCK/comments/conditional
                        // /*!...*/ statements) is irrelevant for a scratch
                        // replay over JDBC - skip.
                    }
                }
            }
        }
        return rowsPerTable
    }
}
