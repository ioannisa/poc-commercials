package eu.anifantakis.commercials.server.scheduler

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import eu.anifantakis.commercials.server.stations.GroupConfig
import java.sql.Connection
import java.sql.SQLException

/**
 * One GROUP's database: a dedicated connection pool over the group's schema
 * (its jdbcUrl/credentials from server.yaml) plus the schema itself.
 *
 * A group is a company that owns several stations (a TV channel and a radio
 * station, say). They SHARE this one database - exactly as the legacy app did,
 * where one MySQL database served both flows of an outlet and told them apart
 * with a `forTV` 0/1 column. Our `station_id` is that column.
 *
 * SCHEMA - two kinds of table:
 *
 *   GROUP-scoped (stored ONCE, shared by every station):
 *       customers ──< contracts ──< contract_lines >── spot_types
 *   because one contract genuinely sells on several media: Ανυφαντάκης buys
 *   1 TV spot and 2 radio spots on the SAME contract. Splitting these per
 *   station (the old model) duplicated the customer and tore the contract in
 *   half.
 *
 *   STATION-scoped (carry `station_id` ≙ legacy `forTV`):
 *       spots, programs, breaks, placements, flow_comments, print_audit, station_meta
 *
 *   A BREAK IS AN ENTITY - `breaks` (station, date, time) - and it OWNS the
 *   slot's PROGRAMME. The break's identity is still its time: the unique key is
 *   (station_id, show_date, show_time), the API keeps addressing breaks by
 *   "HH:mm", and `breaks.id` never leaves the database. What the entity adds is
 *   the programme rule the emergent model could not express:
 *
 *     - the break's `program_id` is what the grid paints - always. Per-spot
 *       programme tags never decide a cell's colour again (they used to, via a
 *       "first spot with a programme" window, and ~7,200 cells showed a
 *       minority programme because of it).
 *     - a NEW placement's `program_id` is stamped FROM the break's, dogmatically:
 *       spot and break can never disagree from here on.
 *     - LEGACY placements keep the per-spot programme the migration gave them,
 *       even where it disagrees with their break's - on purpose, so the printed
 *       reports stay byte-identical with the legacy app's.
 *     - the FIRST spot into a WHITE cell paints it: the operator's selected
 *       programme (the legacy console's "Τύποι Προγράμματος" dropdown) is
 *       required then, and only then. A white cell is a slot with no break OR
 *       an UNPAINTED one - the console's "Πρόσθεση νέου διαλείμματος" creates
 *       breaks unpainted, just to hold a ROW. Painted breaks ignore the
 *       selection entirely.
 *
 *   Seeding at upgrade time picks each break's programme by DOMINANCE: the
 *   programme with the most spots in the break wins; ties go to the first
 *   spot's programme (see BreakSeeder). That rule exists ONLY in the one-off
 *   seed - at runtime the programme is operator input, never derived.
 *
 *   `placements` therefore carries `station_id` of its own, which legacy did NOT
 *   need: it kept ONE DATABASE PER OUTLET, so its `schedule` was already a single
 *   station's. We put a whole group in one schema, so the airing must say which
 *   station it belongs to - and the row's identity (station, date, time, position)
 *   depends on it.
 *
 * The grid the client renders is a READ model DERIVED at query time from
 * placements ⋈ spots ⋈ customers ⋈ contracts - aggregates and cell colours are
 * computed, never stored. Every table carries a nullable legacy_id so the
 * migration from the original app's dumps is idempotent and cross-checkable.
 *
 * Created and cached by StationRegistry - NOT a Koin definition, since the set
 * of groups is data, not wiring. The per-station [StationDb] views borrow this
 * pool; they never own one.
 */
/** MySQL "object already exists" error codes the idempotent schema helpers tolerate. */
private const val MYSQL_ER_DUP_KEYNAME = 1061   // CREATE INDEX: index name already exists
private const val MYSQL_ER_DUP_KEY = 1022       // duplicate key on an add
private const val MYSQL_ER_FK_DUP_NAME = 1826   // ADD CONSTRAINT: FK name already exists

class GroupDb(val config: GroupConfig, maxPoolSize: Int) {

    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            driverClassName = "com.mysql.cj.jdbc.Driver"
            // ONE pool per group database, shared by all its stations - so the
            // ceiling is resolved per group (server.yaml override / file
            // default / built-in), not per station.
            maximumPoolSize = maxPoolSize
            minimumIdle = 1
            connectionTimeout = 10_000
            // Do not fail-fast at pool construction: connections are validated
            // on first use, so an unreachable MySQL (or a test environment
            // without one) doesn't crash instance creation.
            initializationFailTimeout = -1
            poolName = "group-${config.id}"
        }
    )

    /** A pooled connection; closing it (e.g. via `.use {}`) returns it to the pool. */
    fun connection(): Connection = dataSource.getConnection()

    fun close() {
        dataSource.close()
    }

    // ────────────────────────────────────────────────────────── bootstrap ──

    /**
     * Creates the group schema. Runs ONCE per group (StationRegistry creates a
     * GroupDb once), and is idempotent anyway - every statement is
     * CREATE TABLE IF NOT EXISTS / ensureColumn / ensureIndex.
     */
    fun bootstrap() {
        connection().use { c ->
            dropLegacyDemoTables(c)
            createTables(c)
            syncStations(c)
            upgradeFromPerStationSchema(c)
            upgradeToEmergentBreaks(c)
            ensureColumns(c)
            upgradeToBreakEntities(c)
        }
    }

    private fun createTables(c: Connection) {
        c.createStatement().use { s ->
            // The group's stations, as server.yaml declares them. Kept in the
            // database so a dumped group schema is self-describing, and so the
            // station_id columns below have something to point at.
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS stations (
                    id VARCHAR(64) PRIMARY KEY,
                    name VARCHAR(64) NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // Group-level flags (migrated_at, source dump). Its per-station
            // twin is `station_meta`.
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS group_meta (
                    meta_key VARCHAR(64) PRIMARY KEY,
                    meta_value VARCHAR(255) NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // GROUP-scoped: one row per ERP customer, shared by every station.
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
                    -- Main address (SEN gap-fill: the legacy app showed it live
                    -- from the ERP's ADR table - "Κύρια Διεύθυνση"; the legacy
                    -- MySQL never stored it).
                    address_street VARCHAR(160) NULL,
                    address_zip VARCHAR(16) NULL,
                    address_city VARCHAR(64) NULL,
                    notes TEXT NULL,
                    synthetic BOOLEAN NOT NULL DEFAULT FALSE,
                    -- Galaxy (the client's NEW ERP) linkage: TRADER.GXID (the
                    -- identity that carries the VAT, covering advertisers AND
                    -- agencies). NULL until the Galaxy import stamps it - matched
                    -- code-first (Galaxy inherited the legacy TRACODEs), then by
                    -- zero-padded VAT. UNIQUE: one Galaxy trader = one row here.
                    galaxy_id VARCHAR(36) NULL,
                    -- UNIQUE, not a plain index: a legacy customer must exist ONCE
                    -- per group. Migrating a dump's second flow into a populated
                    -- group would otherwise silently duplicate every customer -
                    -- the exact bug the group model exists to kill.
                    UNIQUE KEY uq_customers_legacy (legacy_id),
                    UNIQUE KEY uq_customers_galaxy (galaxy_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // GROUP-scoped: a contract is the DEAL, not a station's copy of it.
            // Its lines may sell on different media (a TV line and two radio
            // lines under one number) - which station each line ends up on is
            // decided by the spots hanging off it.
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
                    -- Contract period + renewal. PROVISIONAL until the Oracle ERP
                    -- import supplies real values: the migration derives start/end
                    -- from placements and sets dates_provisional=TRUE; renewed_at
                    -- has no source yet and stays NULL.
                    start_date DATE NULL,
                    end_date DATE NULL,
                    renewed_at DATE NULL,
                    dates_provisional BOOLEAN NOT NULL DEFAULT FALSE,
                    synthetic BOOLEAN NOT NULL DEFAULT FALSE,
                    -- Galaxy linkage: COMMERCIALENTRY.GXID plus the document's
                    -- sequential number (the one a user quotes on the phone).
                    galaxy_id VARCHAR(36) NULL,
                    galaxy_number BIGINT NULL,
                    -- Natural Galaxy document key "companyid:doccode:docnumber" -
                    -- the flat export carries no GXID yet (GALAXY-MATCHER.md §9.1),
                    -- so the importer upserts on this until the final delivery
                    -- adds the real UUID (which will land in galaxy_id).
                    galaxy_doc_key VARCHAR(64) NULL,
                    KEY idx_contracts_number (number),
                    -- UNIQUE for the same reason as customers.legacy_id above.
                    UNIQUE KEY uq_contracts_legacy (legacy_docid),
                    UNIQUE KEY uq_contracts_galaxy (galaxy_id),
                    UNIQUE KEY uq_contracts_galaxy_key (galaxy_doc_key),
                    KEY idx_contracts_galaxy_number (galaxy_number),
                    CONSTRAINT fk_contracts_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
                    CONSTRAINT fk_contracts_agency FOREIGN KEY (agency_id) REFERENCES customers(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            s.executeUpdate(
                """
                -- Contract PRODUCT lines (≙ legacy z_commercials - the owner's
                -- Oracle view over the ERP doc lines, materialized in the dumps):
                -- one row per (document, line), each line selling ONE item class
                -- (spot_type_id ≙ z.mciid). line_no >= 1000 marks a fallback line
                -- synthesized for a (doc, type) pair the view did not carry.
                --
                -- GROUP-scoped, and carries NO station_id: legacy z_commercials
                -- had no forTV either. A line's MEDIUM is implied by the spots
                -- charged to it - which is why one contract can hold a TV line
                -- and a radio line side by side.
                CREATE TABLE IF NOT EXISTS contract_lines (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    contract_id BIGINT NOT NULL,
                    line_no INT NOT NULL,
                    spot_type_id BIGINT NULL,
                    desired_qty INT NOT NULL DEFAULT 0,
                    agel_val DECIMAL(10,6) NOT NULL DEFAULT 0,
                    eidikos_val DECIMAL(10,6) NOT NULL DEFAULT 0,
                    zone_val DECIMAL(10,2) NOT NULL DEFAULT 0,
                    -- Galaxy linkage: CommercEntryLines.GXID. NULL until stamped.
                    galaxy_id VARCHAR(36) NULL,
                    -- Natural Galaxy line key "companyid:doccode:docnumber:ordinal"
                    -- (see contracts.galaxy_doc_key - same no-GXID-yet story).
                    galaxy_line_key VARCHAR(80) NULL,
                    UNIQUE KEY uq_contract_line (contract_id, line_no),
                    UNIQUE KEY uq_lines_galaxy (galaxy_id),
                    UNIQUE KEY uq_lines_galaxy_key (galaxy_line_key),
                    CONSTRAINT fk_lines_contract FOREIGN KEY (contract_id)
                        REFERENCES contracts(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            s.executeUpdate(
                """
                -- The ERP ITEM-CLASS catalog, keyed by the ERP's MCIID. GROUP-scoped:
                -- the ERP sells the same item classes to every station of the group.
                --
                -- This is NOT the programme catalog. `z_commercials.mciid` lives in a
                -- DIFFERENT id space from legacy `programtypes.id` (ΚΛΕΨΑ, ΞΕΝΗ ΤΑΙΝΙΑ:
                -- shows), and joining one to the other's catalog compiles, runs, and
                -- returns confident nonsense: 55% of mciids have no programtypes row at
                -- all, and the colliding ones paired a news bulletin with a telephone.
                -- The programme lives on `spots.booked_program` / `programs` instead.
                --
                -- The item NAME only exists in the ERP (SEN `sti.csv`), so a dump-only
                -- migration seeds the ids and the SEN enricher fills name/item_code.
                CREATE TABLE IF NOT EXISTS spot_types (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    -- the ERP item class id (STI.MCIID == z_commercials.mciid)
                    legacy_id BIGINT NULL,
                    -- STI.ITMNAME, e.g. 'Διαφ. TV Κρήτη Σ73.002'; the gift
                    -- marker ('Δ Ω Ρ Α') is part of the name. '' until the
                    -- SEN import runs.
                    name VARCHAR(160) NOT NULL DEFAULT '',
                    -- STI.CODCODE, e.g. 'Σ101'
                    item_code VARCHAR(32) NULL,
                    -- Galaxy linkage: ITEM.GXID. The 73.xxx item codes bridge the
                    -- two catalogs (Galaxy inherited the legacy sales items).
                    galaxy_id VARCHAR(36) NULL,
                    UNIQUE KEY uq_spot_types_legacy (legacy_id),
                    UNIQUE KEY uq_spot_types_galaxy (galaxy_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // STATION-scoped (≙ legacy `messages`, which carried forTV).
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS spots (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    station_id VARCHAR(64) NOT NULL,
                    legacy_id BIGINT NULL,
                    customer_id BIGINT NOT NULL,
                    contract_line_id BIGINT NULL,
                    description VARCHAR(255) NOT NULL,
                    duration_seconds INT NOT NULL,
                    -- The PROGRAMME the spot was BOOKED into (legacy
                    -- messages.messageTypeID -> programtypes). It is a show
                    -- ("ΚΛΕΨΑ", "ΞΕΝΗ ΤΑΙΝΙΑ"). The spot's ERP ITEM comes from its
                    -- contract LINE (contract_lines.spot_type_id -> spot_types),
                    -- and the airing's own line wins over the spot's default.
                    booked_program VARCHAR(128) NOT NULL DEFAULT '',
                    booked_program_id BIGINT NULL,
                    flow VARCHAR(32) NOT NULL DEFAULT '',
                    hidden BOOLEAN NOT NULL DEFAULT FALSE,
                    force_position INT NULL,
                    memo TEXT NULL,
                    KEY idx_spots_station (station_id),
                    -- UNIQUE: legacy `messages.id` is unique across the whole dump
                    -- (both flows), so a spot must land exactly once. It also stops
                    -- the placement join from fanning out.
                    UNIQUE KEY uq_spots_legacy (legacy_id),
                    CONSTRAINT fk_spots_station FOREIGN KEY (station_id) REFERENCES stations(id),
                    CONSTRAINT fk_spots_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
                    CONSTRAINT fk_spots_line FOREIGN KEY (contract_line_id) REFERENCES contract_lines(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // STATION-scoped (≙ legacy `programtypes`, which carried forTV).
            // legacy_id REPEATS across flows - programme 5 exists on both the TV
            // and the radio side and means different shows - so the unique key
            // and every join must include the station.
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS programs (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    station_id VARCHAR(64) NOT NULL,
                    legacy_id BIGINT NULL,
                    name VARCHAR(128) NOT NULL,
                    color_argb INT NULL,
                    hidden BOOLEAN NOT NULL DEFAULT FALSE,
                    UNIQUE KEY uq_programs_station_legacy (station_id, legacy_id),
                    CONSTRAINT fk_programs_station FOREIGN KEY (station_id) REFERENCES stations(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // The BREAK entity (see the class doc). Its identity is its slot -
            // (station, date, time), which is the unique key - and its one piece
            // of OWNED state is the programme the operator gave it. `id` exists
            // for the placements FK and never leaves the database: the API talks
            // "HH:mm", exactly as before.
            //
            // program_id NULL = an UNPAINTED break: the seeded past where no
            // spot carried a programme, and operator-created rows ("Πρόσθεση
            // νέου διαλείμματος") waiting for their first spot to paint them.
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS breaks (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    station_id VARCHAR(64) NOT NULL,
                    show_date DATE NOT NULL,
                    show_time TIME NOT NULL,
                    program_id BIGINT NULL,
                    UNIQUE KEY uq_breaks_slot (station_id, show_date, show_time),
                    CONSTRAINT fk_breaks_station FOREIGN KEY (station_id) REFERENCES stations(id),
                    CONSTRAINT fk_breaks_program FOREIGN KEY (program_id) REFERENCES programs(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // ≙ legacy `schedule`: an airing AT A TIME. `show_time` mirrors its
            // `showTime` column. `break_id` points at the airing's break; the
            // slot tuple (station, date, time) stays on the row too - it is the
            // airing's IDENTITY (the unique key below) and what the grid's
            // covering index reads, so the FK adds integrity without rerouting
            // any query through a join.
            //
            // `station_id` is the one column legacy did not have, and it is not a
            // denormalization: legacy kept one database per outlet, so its
            // schedule was implicitly single-station. A group's stations share
            // this schema, so without it the 11:00 break of the TV channel and of
            // the radio station would be the same break.
            //
            // The unique key IS the airing's identity - (station, date, time,
            // position) - and it makes concurrent adds to the same break collide
            // in the database rather than silently interleave (see addPlacement).
            // It also serves the grid's read path: `station_id, show_date` is its
            // leftmost prefix, and `show_time` follows for the GROUP BY.
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS placements (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    legacy_id BIGINT NULL,
                    station_id VARCHAR(64) NOT NULL,
                    spot_id BIGINT NOT NULL,
                    show_date DATE NOT NULL,
                    show_time TIME NOT NULL,
                    position INT NOT NULL,
                    duration_seconds INT NOT NULL,
                    -- The ACTUAL charge of this airing (≙ legacy schedule.docID +
                    -- lineno): the same spot airs under different contracts/
                    -- products over time; the spot's own line is only its
                    -- CURRENT default. NULL -> displays fall back to the spot's.
                    contract_line_id BIGINT NULL,
                    -- The airing's programme TAG. On rows the operator creates it
                    -- EQUALS the break's programme (stamped at insert - see
                    -- StationDb.addPlacement); on migrated rows it is the legacy
                    -- per-spot tag, kept verbatim so old reports don't shift.
                    -- Displays never read it for the CELL - that is the break's.
                    program_id BIGINT NULL,
                    break_id BIGINT NULL,
                    played BOOLEAN NOT NULL DEFAULT FALSE,
                    hidden BOOLEAN NOT NULL DEFAULT FALSE,
                    UNIQUE KEY uq_placement_slot (station_id, show_date, show_time, position),
                    KEY idx_placements_legacy (legacy_id),
                    -- COVERING, and measured. Every "what did this party air?"
                    -- query (searchParties, partyActivity, partyContractLines,
                    -- contractStatus, contractLineSpots) reaches the airings by
                    -- spot_id and then needs exactly `hidden` and `show_date`.
                    -- With spot_id alone, InnoDB found the rows in the index and
                    -- then made one PRIMARY-key lookup per row to read those two
                    -- columns - 115,785 of them for the heaviest customer. Adding
                    -- them here answers those queries from the index alone:
                    -- partyActivity 213 -> 69ms, contractStatus 4x, the trader
                    -- search ~2x. It also SERVES the fk_placements_spot foreign
                    -- key (spot_id is leftmost), so it replaces the narrow index
                    -- InnoDB would otherwise create rather than adding to it.
                    KEY idx_placements_spot_cover (spot_id, hidden, show_date),
                    -- COVERING, for the MONTH GRID - the hottest read in the app.
                    --
                    -- `uq_placement_slot` already finds the month's airings, but it
                    -- carries only (station, date, time, position): the grid also
                    -- needs each airing's spot, its hidden flag and its programme,
                    -- and InnoDB had to open the actual row to read those - roughly
                    -- 12,000 random primary-key lookups for one month. Putting the
                    -- three columns IN the index answers the query from the index
                    -- alone: the placements scan drops 19ms -> 2.3ms, the endpoint
                    -- 45ms -> 34ms.
                    --
                    -- It is not free, and the trade was made deliberately: +234 MB
                    -- on the biggest table in the schema, and one more 7-column
                    -- index to maintain on every write - `position` is in it, so a
                    -- reorder rewrites it too, and a 4.1M-row migration feels it
                    -- most. Measured on a warm buffer pool; on a cold one the
                    -- lookups it removes cost far more, which is what tipped it.
                    KEY idx_placements_grid (station_id, show_date, hidden, show_time, position, spot_id, program_id),
                    KEY idx_placements_break (break_id),
                    CONSTRAINT fk_placements_spot FOREIGN KEY (spot_id) REFERENCES spots(id),
                    CONSTRAINT fk_placements_station FOREIGN KEY (station_id) REFERENCES stations(id),
                    CONSTRAINT fk_placements_program FOREIGN KEY (program_id) REFERENCES programs(id),
                    CONSTRAINT fk_placements_break FOREIGN KEY (break_id) REFERENCES breaks(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // NO index on show_date alone. It existed when the unique key led with
            // break_id and a month scan could not use it - but the key now leads
            // with (station_id, show_date), and EVERY query that filters by date
            // also filters by station. Dropping it changed no query's timing at
            // all (measured across the whole suite) and returned ~100 MB, plus the
            // write cost of maintaining it on the biggest table in the schema.
            // Airtime price zones: NO app-layer tables here. The FAITHFUL UNION
            // copies own the legacy names (`zones`/`zonefillers`, written verbatim
            // by the migration - they carry their own forTV). Demo groups simply
            // have no price data.

            // ≙ legacy `roh_comments`, keyed by (date, forTV) - so the PK must
            // carry the station, or the radio station's comment for a date
            // silently overwrites (or is IGNOREd against) the TV one.
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS flow_comments (
                    station_id VARCHAR(64) NOT NULL,
                    show_date DATE NOT NULL,
                    comments TEXT NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (station_id, show_date),
                    CONSTRAINT fk_flow_comments_station FOREIGN KEY (station_id) REFERENCES stations(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // ≙ legacy `emailhistory`: the audit archive of customer schedule
            // emails. Summary rows are kept forever; the full HTML body is capped
            // per customer (see logEmail) so the archive can't balloon like the
            // legacy 1.2 GB email store.
            //
            // station_id is NULLABLE on purpose: legacy `emailhistory` has no
            // forTV, so imported rows genuinely belong to the GROUP and must not
            // be attributed to a station we'd only be guessing. New sends stamp it.
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS email_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    station_id VARCHAR(64) NULL,
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
                    KEY idx_email_log_station (station_id, sent_at),
                    KEY idx_email_log_sent (sent_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // ≙ legacy `roh_print_history`, which carried forTV.
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS print_audit (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    station_id VARCHAR(64) NOT NULL,
                    printed_date DATE NOT NULL,
                    username VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    KEY idx_print_audit_date (station_id, printed_date),
                    CONSTRAINT fk_print_audit_station FOREIGN KEY (station_id) REFERENCES stations(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            s.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS station_meta (
                    station_id VARCHAR(64) NOT NULL,
                    meta_key VARCHAR(64) NOT NULL,
                    meta_value VARCHAR(255) NOT NULL,
                    PRIMARY KEY (station_id, meta_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.trimIndent()
            )
            // raw ERP doc ids awaiting the ERP import (see LegacyTransformer)
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS erp_excluded_docs (erp_docid BIGINT PRIMARY KEY) ENGINE=InnoDB"
            )
        }
    }

    /**
     * Idempotent ALTERs for schemas created by an older build. Fresh schemas
     * already have every column from [createTables] and skip all of these.
     */
    private fun ensureColumns(c: Connection) {
        // ── placements indexes (all three decisions are MEASURED - see the
        //    CREATE TABLE above, and re-measure before changing any of them) ──
        //
        // Station-scoped month scans ride the unique key (station_id, show_date,
        // show_time, position), which is also what the grid's GROUP BY on
        // show_time reads. Nothing more is needed for the grid.
        //
        // The party/finder family reaches airings by spot_id and needs `hidden`
        // and `show_date` off them: this makes that index-only.
        ensureIndex(c, "placements", "idx_placements_spot_cover", "spot_id, hidden, show_date")
        // The MONTH GRID, index-only (see the CREATE TABLE note for the trade).
        ensureIndex(
            c, "placements", "idx_placements_grid",
            "station_id, show_date, hidden, show_time, position, spot_id, program_id"
        )
        // Retired: an index on show_date ALONE. Every query that filters by date
        // filters by station too, so the unique key already serves them; dropping
        // it regressed nothing and freed ~100 MB on the biggest table.
        dropIndexIfExists(c, "placements", "idx_placements_date")
        // Retired: the narrow (spot_id) index InnoDB auto-created for
        // fk_placements_spot. idx_placements_spot_cover leads with spot_id, so it
        // serves the foreign key on its own - keeping both would be one redundant
        // index on 4M rows. (MySQL drops the auto-created one itself once the
        // wider index exists; this is here for schemas where it lingers.)
        if (indexExists(c, "placements", "idx_placements_spot_cover")) {
            dropIndexIfExists(c, "placements", "fk_placements_spot")
        }
        ensureColumn(c, "customers", "synthetic", "BOOLEAN NOT NULL DEFAULT FALSE")
        ensureColumn(c, "contracts", "synthetic", "BOOLEAN NOT NULL DEFAULT FALSE")
        // legacy calendar_excluded_docs: contracts whose spots stay OFF printed reports
        ensureColumn(c, "contracts", "exclude_from_reports", "BOOLEAN NOT NULL DEFAULT FALSE")
        // ERP LEE id (second legacy id series): links end clients of triangular
        // contracts - see migration/legacy-schema.md, docref.
        ensureColumn(c, "customers", "legacy_lee_id", "BIGINT NULL")
        ensureIndex(c, "customers", "idx_customers_lee_legacy", "legacy_lee_id")
        ensureColumn(c, "placements", "program_id", "BIGINT NULL")
        // Contract period/renewal dates - see the CREATE TABLE note. Provisional
        // until the ERP import; the migration backfills start/end.
        ensureColumn(c, "contracts", "start_date", "DATE NULL")
        ensureColumn(c, "contracts", "end_date", "DATE NULL")
        ensureColumn(c, "contracts", "renewed_at", "DATE NULL")
        ensureColumn(c, "contracts", "dates_provisional", "BOOLEAN NOT NULL DEFAULT FALSE")
        // Galaxy (new ERP) linkage - see the CREATE TABLE notes. UUIDs are NULL
        // until the Galaxy import stamps them. UNIQUE indexes are safe on the
        // existing all-NULL data (MySQL allows repeated NULLs).
        ensureColumn(c, "customers", "galaxy_id", "VARCHAR(36) NULL")
        ensureIndex(c, "customers", "uq_customers_galaxy", "galaxy_id", unique = true)
        ensureColumn(c, "contracts", "galaxy_id", "VARCHAR(36) NULL")
        ensureColumn(c, "contracts", "galaxy_number", "BIGINT NULL")
        ensureIndex(c, "contracts", "uq_contracts_galaxy", "galaxy_id", unique = true)
        ensureIndex(c, "contracts", "idx_contracts_galaxy_number", "galaxy_number")
        ensureColumn(c, "contract_lines", "galaxy_id", "VARCHAR(36) NULL")
        ensureIndex(c, "contract_lines", "uq_lines_galaxy", "galaxy_id", unique = true)
        ensureColumn(c, "spot_types", "galaxy_id", "VARCHAR(36) NULL")
        ensureIndex(c, "spot_types", "uq_spot_types_galaxy", "galaxy_id", unique = true)
        // Natural Galaxy doc/line keys (no GXID in the flat export yet - see
        // the CREATE TABLE notes and GALAXY-MATCHER.md §9.1).
        ensureColumn(c, "contracts", "galaxy_doc_key", "VARCHAR(64) NULL")
        ensureIndex(c, "contracts", "uq_contracts_galaxy_key", "galaxy_doc_key", unique = true)
        ensureColumn(c, "contract_lines", "galaxy_line_key", "VARCHAR(80) NULL")
        ensureIndex(c, "contract_lines", "uq_lines_galaxy_key", "galaxy_line_key", unique = true)
        // The BOOKED PROGRAMME (see the spots CREATE TABLE note). The dead
        // `spots.spot_type_id` is deliberately NOT ensured any more - a spot's
        // product is its contract LINE's, and leaving a plausible-looking column
        // on the table is how the false join gets rebuilt by accident.
        ensureColumn(c, "spots", "booked_program", "VARCHAR(128) NOT NULL DEFAULT ''")
        ensureColumn(c, "spots", "booked_program_id", "BIGINT NULL")
        // Product lines + per-airing charge (see the CREATE TABLE notes).
        ensureColumn(c, "contract_lines", "spot_type_id", "BIGINT NULL")
        ensureColumn(c, "placements", "contract_line_id", "BIGINT NULL")
        // The finder's per-line Αναλωμένα aggregate by CHARGE line - without
        // this, "which airings charge line X" is a 4M-row scan per click.
        ensureIndex(c, "placements", "idx_placements_line", "contract_line_id")
        // Main address (see the customers CREATE TABLE note).
        ensureColumn(c, "customers", "address_street", "VARCHAR(160) NULL")
        ensureColumn(c, "customers", "address_zip", "VARCHAR(16) NULL")
        ensureColumn(c, "customers", "address_city", "VARCHAR(64) NULL")
    }

    /**
     * Upgrades a schema written by the PER-STATION build (no station_id
     * anywhere) into a group schema.
     *
     * It is only safe when the group has exactly ONE station, because that is
     * what such a database was: one station's data, undifferentiated. Every row
     * in it belongs to that station, so stamping them all is exact. A group with
     * several stations pointed at a pre-group schema cannot be repaired by
     * guessing - the flow information was thrown away at migration time - so we
     * refuse to boot and say so.
     */
    private fun upgradeFromPerStationSchema(c: Connection) {
        if (!tableExists(c, "break_slots")) return
        // The tell: ANY of the station_id columns is still missing. Testing all
        // of them (rather than just the first one it would add) is what makes a
        // half-finished upgrade resume instead of silently short-circuiting on
        // the next boot - which is exactly how this went wrong the first time.
        val stamped = listOf("break_slots", "spots", "programs", "print_audit", "flow_comments", "station_meta")
        if (stamped.all { !tableExists(c, it) || columnExists(c, it, "station_id") }) return

        val stations = config.stations
        require(stations.size == 1) {
            "Group '${config.id}' points at a pre-group schema (${databaseName(c)}), written when each " +
                "station had its OWN database, but it declares ${stations.size} stations " +
                "(${stations.joinToString { it.id }}). Rows there carry no station, and the legacy " +
                "forTV flag that could tell them apart was dropped at migration time. Migrate the " +
                "original dump into a FRESH group schema instead (both flows in one run)."
        }
        val station = stations.single()

        // The station must exist before anything can point at it.
        c.prepareStatement("INSERT IGNORE INTO stations(id, name) VALUES(?,?)").use { ps ->
            ps.setString(1, station.id)
            ps.setString(2, station.name)
            ps.executeUpdate()
        }
        for (table in stamped) {
            if (!tableExists(c, table)) continue
            ensureColumn(c, table, "station_id", "VARCHAR(64) NOT NULL DEFAULT ''")
            c.prepareStatement("UPDATE $table SET station_id = ? WHERE station_id = ''").use { ps ->
                ps.setString(1, station.id)
                ps.executeUpdate()
            }
        }
        // email_log's station_id is NULLABLE: the pre-group rows are this
        // station's own sends mixed with the imported legacy archive, which has
        // no flow at all - and they are no longer distinguishable. Leave them
        // NULL ("belongs to the group"), which is how they read.
        ensureColumn(c, "email_log", "station_id", "VARCHAR(64) NULL")

        // break_slots is deliberately NOT repaired here (it used to have its id
        // converted to AUTO_INCREMENT and its unique key rebuilt): the very next
        // bootstrap step drops the table. All this pass owes it is `station_id`,
        // stamped above - upgradeToEmergentBreaks reads it to backfill the
        // airings' station before the catalog goes.
        c.createStatement().use { s ->
            ensureIndex(c, "spots", "idx_spots_station", "station_id")
            ensureIndex(c, "programs", "uq_programs_station_legacy", "station_id, legacy_id", unique = true)

            // flow_comments + station_meta: the station must JOIN the primary key,
            // or the group's second station could never have a row of its own.
            if (tableExists(c, "flow_comments") && !isInPrimaryKey(c, "flow_comments", "station_id")) {
                s.executeUpdate("ALTER TABLE flow_comments DROP PRIMARY KEY, ADD PRIMARY KEY (station_id, show_date)")
            }
            if (tableExists(c, "station_meta") && !isInPrimaryKey(c, "station_meta", "station_id")) {
                s.executeUpdate("ALTER TABLE station_meta DROP PRIMARY KEY, ADD PRIMARY KEY (station_id, meta_key)")
            }
        }
    }

    /**
     * Retires the `break_slots` catalog, in place, for a schema an older build
     * wrote. Idempotent: the tell is the `break_slots` TABLE still existing -
     * it is dropped as this upgrade's LAST step, so its presence means the
     * upgrade has not finished and a half-run resumes. The tell used to be
     * `placements.break_id`, and that is no longer distinctive: the break
     * ENTITY model (upgradeToBreakEntities) reintroduces a break_id column,
     * and keying on it made this run again on every boot after that upgrade -
     * straight into the dropped catalog.
     *
     * The catalog was never anything but a materialized GROUP BY - the migration
     * built it as `SELECT DISTINCT station, HOUR(showTime), MINUTE(showTime)` and
     * then joined every airing straight back to it to recover the id it had just
     * discarded. So the backfill is exact and needs no source dump: the break's
     * (hour, minute) IS the airing's time.
     *
     * ORDER IS LOAD-BEARING, in BOTH directions, and the two constraints pull
     * opposite ways:
     *
     *  - the FOREIGN KEY on break_id must go FIRST. `uq_placement_slot` is
     *    (break_id, show_date, position), so break_id is its leftmost column and
     *    it is the index InnoDB uses to enforce that FK. Dropping it while the FK
     *    stands fails outright: "Cannot drop index 'uq_placement_slot': needed in
     *    a foreign key constraint".
     *  - the INDEX must still go BEFORE THE COLUMN. Drop break_id first and MySQL
     *    silently rewrites the index to (show_date, position) - which is not
     *    unique across a group's stations - and the ALTER dies on duplicate keys.
     *
     * So: foreign key, then index, then column.
     */
    private fun upgradeToEmergentBreaks(c: Connection) {
        if (!tableExists(c, "break_slots")) return
        if (!columnExists(c, "placements", "break_id")) return

        // Defaults only so the NOT NULL columns can land on existing rows; every
        // row is stamped by the backfill below, and a break_id is NOT NULL, so
        // none can be missed.
        ensureColumn(c, "placements", "station_id", "VARCHAR(64) NOT NULL DEFAULT ''")
        ensureColumn(c, "placements", "show_time", "TIME NOT NULL DEFAULT '00:00:00'")
        c.createStatement().use { s ->
            // Only the rows still unstamped: this walks millions of airings, and a
            // half-finished upgrade (the ALTERs below are not one transaction)
            // must resume rather than redo it.
            s.executeUpdate(
                """
                UPDATE placements p, break_slots b
                   SET p.station_id = b.station_id,
                       p.show_time  = MAKETIME(b.hour_of_day, b.minute_of_hour, 0)
                 WHERE b.id = p.break_id
                   AND p.station_id = ''
                """.trimIndent()
            )
            foreignKeyOn(c, "placements", "break_id")?.let { fk ->
                s.executeUpdate("ALTER TABLE placements DROP FOREIGN KEY `$fk`")
            }
            dropIndexIfExists(c, "placements", "uq_placement_slot")
            s.executeUpdate("ALTER TABLE placements DROP COLUMN break_id")
            // The unique key before the FK: MySQL needs an index on the
            // referencing column, and this one already leads with it - otherwise
            // it would silently invent a second, redundant one.
            ensureIndex(
                c, "placements", "uq_placement_slot",
                "station_id, show_date, show_time, position", unique = true
            )
            if (foreignKeyOn(c, "placements", "station_id") == null) {
                s.executeUpdate(
                    "ALTER TABLE placements ADD CONSTRAINT fk_placements_station " +
                        "FOREIGN KEY (station_id) REFERENCES stations(id)"
                )
            }
            s.executeUpdate("DROP TABLE break_slots")
        }
    }

    /**
     * Promotes breaks from emergent (a GROUP BY) to entities (the `breaks`
     * table), in place. Runs AFTER [ensureColumns] because the seeder reads
     * `placements.program_id`, which ensureColumns adds to older schemas.
     *
     * Idempotent and resumable at every step: the column add is guarded, the
     * seed itself is guarded by "is any placement still unattached?" - which
     * also makes it SELF-REPAIRING: a legacy re-migration that inserts
     * placements without break_id is healed on the next boot. The seeding rule
     * (dominant programme, ties to the first spot) lives in [BreakSeeder],
     * shared with the migration module - and applies ONLY here, to the past;
     * from now on a break's programme is operator input (see the class doc).
     */
    private fun upgradeToBreakEntities(c: Connection) {
        ensureColumn(c, "placements", "break_id", "BIGINT NULL")
        val unattached = c.createStatement().use { s ->
            s.executeQuery("SELECT 1 FROM placements WHERE break_id IS NULL LIMIT 1").use { it.next() }
        }
        if (unattached) BreakSeeder.seed(c)
        // The FK needs an index on break_id; create OUR name for it first or
        // InnoDB invents one.
        ensureIndex(c, "placements", "idx_placements_break", "break_id")
        if (foreignKeyOn(c, "placements", "break_id") == null) {
            // Same idempotency hardening as ensureIndex: a partially-applied
            // prior run may have added the constraint after the pre-check last
            // saw it absent - tolerate the duplicate rather than 500 the request.
            ignoringDuplicate(MYSQL_ER_FK_DUP_NAME, MYSQL_ER_DUP_KEY) {
                c.createStatement().use { s ->
                    s.executeUpdate(
                        "ALTER TABLE placements ADD CONSTRAINT fk_placements_break " +
                            "FOREIGN KEY (break_id) REFERENCES breaks(id)"
                    )
                }
            }
        }
    }

    /** server.yaml is the source of truth for the group's stations; mirror it in. */
    private fun syncStations(c: Connection) {
        c.prepareStatement(
            "INSERT INTO stations(id, name) VALUES(?,?) ON DUPLICATE KEY UPDATE name = VALUES(name)"
        ).use { ps ->
            for (station in config.stations) {
                ps.setString(1, station.id)
                ps.setString(2, station.name)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    // ─────────────────────────────────────────────────── schema helpers ──

    internal fun ensureColumn(c: Connection, table: String, column: String, definition: String) {
        if (columnExists(c, table, column)) return
        c.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition") }
    }

    internal fun ensureIndex(
        c: Connection,
        table: String,
        indexName: String,
        columns: String,
        unique: Boolean = false,
    ) {
        if (indexExists(c, table, indexName)) return
        val kind = if (unique) "UNIQUE INDEX" else "INDEX"
        // Belt-and-suspenders: the pre-check can disagree with reality - a
        // partially-applied prior migration, a fresh table's inline KEY, or
        // information_schema stats caching can all HIDE an index that exists,
        // and then this CREATE would 500 the whole request with ER_DUP_KEYNAME
        // (exactly the /api/schedule crash this guards against). Tolerate that
        // one error: a duplicate index IS the state we were ensuring.
        ignoringDuplicate(MYSQL_ER_DUP_KEYNAME) {
            c.createStatement().use { it.executeUpdate("CREATE $kind $indexName ON $table($columns)") }
        }
    }

    /**
     * Runs [block], swallowing a MySQL "object already exists" error whose code
     * is one of [duplicateCodes]. The schema helpers pre-check information_schema
     * for idempotency, but that read can lag reality - so the CREATE/ALTER is
     * ALSO made to tolerate the duplicate it may race, instead of turning a
     * harmless re-run into a 500. Any other SQLException propagates unchanged.
     */
    private fun ignoringDuplicate(vararg duplicateCodes: Int, block: () -> Unit) {
        try {
            block()
        } catch (e: SQLException) {
            if (e.errorCode !in duplicateCodes) throw e
        }
    }

    private fun indexExists(c: Connection, table: String, indexName: String): Boolean =
        c.prepareStatement(
            """
            SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, indexName)
            ps.executeQuery().use { it.next() }
        }

    private fun dropIndexIfExists(c: Connection, table: String, indexName: String) {
        if (!indexExists(c, table, indexName)) return
        c.createStatement().use { it.executeUpdate("ALTER TABLE $table DROP INDEX $indexName") }
    }

    private fun columnExists(c: Connection, table: String, column: String): Boolean =
        c.prepareStatement(
            """
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, column)
            ps.executeQuery().use { it.next() }
        }

    private fun tableExists(c: Connection, table: String): Boolean =
        c.prepareStatement(
            """
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { it.next() }
        }

    private fun isInPrimaryKey(c: Connection, table: String, column: String): Boolean =
        c.prepareStatement(
            """
            SELECT 1 FROM information_schema.key_column_usage
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
              AND constraint_name = 'PRIMARY'
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, column)
            ps.executeQuery().use { it.next() }
        }

    /** The name of the foreign key on [table].[column], if it has one. */
    private fun foreignKeyOn(c: Connection, table: String, column: String): String? =
        c.prepareStatement(
            """
            SELECT constraint_name FROM information_schema.key_column_usage
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
              AND referenced_table_name IS NOT NULL
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, column)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    private fun databaseName(c: Connection): String =
        c.createStatement().use { s ->
            s.executeQuery("SELECT DATABASE()").use { rs -> rs.next(); rs.getString(1) ?: "?" }
        }

    /**
     * Migration from the pre-normalization demo schema: the old
     * `scheduler_cells` (stored aggregates) and `commercials` (denormalized
     * rows) tables are superseded by the derived read model. Their content was
     * deterministic demo data, so they are simply dropped; months reseed on
     * demand into `placements`. Idempotent.
     */
    private fun dropLegacyDemoTables(c: Connection) {
        for (table in listOf("commercials", "scheduler_cells")) {
            if (tableExists(c, table)) {
                c.createStatement().use { it.executeUpdate("DROP TABLE $table") }
            }
        }
    }
}
