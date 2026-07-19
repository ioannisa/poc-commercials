package eu.anifantakis.commercials.server.scheduler

import java.sql.Connection

/**
 * Seeds `breaks` rows from existing `placements` and attaches every unattached
 * placement to its break. The ONE place the "which programme does an inherited
 * break get?" rule lives - called from GroupDb's in-place upgrade AND from the
 * legacy migration (LegacyTransformer), which inserts placements without
 * break_id and then runs this.
 *
 * THE RULE (seed-time only - at runtime a break's programme is operator input):
 *
 *   1. DOMINANT programme wins: the programme tagged on the most spots of the
 *      break. Measured on the real data, a clear majority exists in 98.5% of
 *      the ~39k breaks whose spots disagree - the disagreements are per-spot
 *      tagging noise (single stray spots), not genuinely shared breaks.
 *   2. Ties go to the FIRST spot's programme: among equally-counted programmes,
 *      the one appearing at the lowest position. This resolves 97% of the
 *      1,604 ties exactly as "the programme of the first spot", and the rest
 *      deterministically (lowest position among the tied).
 *   3. A programme beats no-programme: NULL tags only win when every spot of
 *      the break is untagged - then the break has no programme to inherit.
 *
 *   Hidden placements still get a break (every airing must), but only VISIBLE
 *   ones vote: a deleted spot must not decide a live cell's programme.
 *
 * Both statements are idempotent - INSERT IGNORE against the break's unique
 * slot key, UPDATE only where break_id IS NULL - so callers may re-run freely;
 * GroupDb additionally guards the call so a routine boot doesn't rescan 4M rows.
 *
 * The LEGACY placements' own program_id is deliberately NOT rewritten to the
 * break's: the per-spot tags keep the printed reports byte-identical with the
 * legacy app. Only rows created from now on carry the dogmatic
 * "spot.program_id = break.program_id" (see StationDb.addPlacement).
 */
object BreakSeeder {

    /**
     * [schema] qualifies the tables for callers whose connection sits on a
     * different default schema (the migration works cross-schema); null means
     * "the connection's own" (the server's in-place upgrade).
     */
    fun seed(c: Connection, schema: String? = null) {
        val q = schema?.let { "`$it`." } ?: ""
        c.createStatement().use { s ->
            // One (station, date, time) group per break; rank each group's
            // programmes by (has one at all, visible-spot count, first
            // appearance) and keep the winner.
            s.executeUpdate(
                """
                INSERT IGNORE INTO ${q}breaks(station_id, show_date, show_time, program_id)
                SELECT station_id, show_date, show_time, program_id
                FROM (
                    SELECT g.station_id, g.show_date, g.show_time, g.program_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY g.station_id, g.show_date, g.show_time
                               ORDER BY (g.program_id IS NULL), g.visible_spots DESC, g.first_position
                           ) AS rn
                    FROM (
                        SELECT p.station_id, p.show_date, p.show_time, p.program_id,
                               -- A vote is a VISIBLE airing of a VISIBLE spot -
                               -- the exact filter the grid applies. Hidden rows
                               -- neither vote nor break ties ("the first
                               -- spot's programme" means the first visible
                               -- one); the fallback only places programmes
                               -- with nothing visible at all, which lose every
                               -- tie on visible_spots anyway.
                               SUM(p.hidden = FALSE AND s.hidden = FALSE) AS visible_spots,
                               COALESCE(
                                   MIN(CASE WHEN p.hidden = FALSE AND s.hidden = FALSE
                                            THEN p.position END),
                                   MIN(p.position)
                               ) AS first_position
                        FROM ${q}placements p
                        JOIN ${q}spots s ON s.id = p.spot_id
                        GROUP BY p.station_id, p.show_date, p.show_time, p.program_id
                    ) g
                ) ranked
                WHERE rn = 1
                """.trimIndent()
            )
            s.executeUpdate(
                """
                UPDATE ${q}placements p
                JOIN ${q}breaks b ON b.station_id = p.station_id
                                 AND b.show_date = p.show_date
                                 AND b.show_time = p.show_time
                SET p.break_id = b.id
                WHERE p.break_id IS NULL
                """.trimIndent()
            )
        }
    }
}
