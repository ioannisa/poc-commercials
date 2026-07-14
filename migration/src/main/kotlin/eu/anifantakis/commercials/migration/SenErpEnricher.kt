package eu.anifantakis.commercials.migration

import java.io.File
import java.sql.Connection
import java.time.LocalDate

/**
 * The Oracle ERP import the migration always promised ("missing ERP data is
 * faked deterministically and flagged synthetic=TRUE ... so a future ERP
 * import can find and replace them"). Enriches an ALREADY MIGRATED station
 * schema in place, idempotently, from a folder of SEN table exports
 * (one tab-delimited file per Oracle table - see [SenExports]).
 *
 * MYSQL-FIRST RULE: the migrated MySQL data defines the universe - the
 * enricher reads the keys it needs from the schema (legacy ids, lee ids, doc
 * ids) and pulls ONLY the matching export rows. Nothing is created from the
 * ERP side; SEN-only entities/documents are never imported.
 *
 * Phases (each skipped gracefully when its export file is absent):
 *  1. CUSTOMER IDENTITY - real names + VAT from LEE, resolved per customer as
 *     `legacy_lee_id -> LEE` (end clients known only by lee id), else
 *     `legacy_id -> CUS.TRAID -> CUS.LEEID -> LEE` (payer accounts).
 *     A customer that gains its real ERP identity flips synthetic -> FALSE.
 *     ERP-blank VAT NULLs out a deterministic fake - a real unknown beats a
 *     plausible lie.
 *  2. CUSTOMER CONTACTS - phone/fax/email from the entity's main address
 *     (LEE.ADRIDMAIN -> ADR). Fills ONLY blank or placeholder values
 *     (the migration's fakes are `...@example.gr/.com`); a real recovered
 *     email (actually used to send) is never overwritten.
 *  3. CONTRACTS - from SLD by `legacy_docid == SLD.DOCID` (the MySQL docref
 *     shadows the ERP ids - docno/dotid/traid agreement verified):
 *     - real period: start = TDOEKTELESISDATE, end = TDOOTHERDATE,
 *       dates_provisional -> FALSE (only when BOTH dates exist - gift
 *       contracts legitimately carry none);
 *     - agreed quantity: TDOQTYA -> the contract's single line's desired_qty
 *       (the migration's lines are one-per-contract, keyed by the legacy doc
 *       NUMBER, and carry desired_qty=0 - filled only while still 0);
 *     - is_gift corrected from the SDT document-type catalog (DOTDESCRIPTION
 *       containing "ΔΩΡ" - the legacy MySQL knew the gift flag for only 7 of
 *       the 21+ doc types in use).
 *  4. PROVISIONAL BACKFILL - placement-derived periods for what the ERP could
 *     not date (same SQL as the migration's own backfill; a station migrated
 *     before the contract-dates feature never got it).
 *
 * `ssd.csv` (document LINES) is the ERP source that MySQL `z_commercials`
 * mirrors - and the mirror is INCOMPLETE (140 of this station's documents have
 * no row in it). It therefore does two jobs here: per-line agreed quantities,
 * and RECOVERING the product lines the snapshot missed, which is why the legacy
 * console showed an item where we showed none. An earlier version of this file
 * declared it "deliberately NOT read"; that was written when contract_lines
 * were pseudo-lines, and it stopped being true when they became real ERP lines.
 *
 * `renewed_at` still has NO import source: the ERP represents renewal as a
 * NEW document (e.g. doc 789 -> doc 703), so it is derivable, not importable.
 */
class SenErpEnricher(
    private val c: Connection,
    private val schema: String,
    private val log: (String) -> Unit = {},
) {

    data class Summary(
        var customersExamined: Int = 0,
        var customersResolvedViaLee: Int = 0,
        var customersResolvedViaCus: Int = 0,
        var customersUnresolved: Int = 0,
        var customersRenamed: Int = 0,
        var customersVatSet: Int = 0,
        var customersRecoded: Int = 0,
        var customersUnchanged: Int = 0,
        var contactsFilled: Int = 0,
        var emailLogRecoded: Int = 0,
        var contractsExamined: Int = 0,
        var contractsDated: Int = 0,
        var contractsQtySet: Int = 0,
        var contractsGiftFixed: Int = 0,
        var contractsNoErpDates: Int = 0,
        var contractsNotInSld: Int = 0,
        var contractsBackfilled: Int = 0,
        var spotTypesEnriched: Int = 0,
        var spotsTypeLinked: Int = 0,
        var rejectedRecords: Int = 0,
    )

    /**
     * Runs every phase the [senDir] exports allow. [apply]=false is a full
     * dry-run: counts, no writes.
     *
     * [legacyScratchSchema] (optional) names a schema holding the legacy
     * MySQL `z_commercials` table (the dump's message->ERP-line links). The
     * migration pipeline passes its scratch schema (enrichment runs before
     * the scratch drop), giving EXACT per-spot sales items; without it,
     * spots on single-item documents still resolve (the majority), spots on
     * multi-item documents are left alone.
     */
    fun enrich(senDir: File, apply: Boolean, legacyScratchSchema: String? = null): Summary {
        require(senDir.isDirectory) { "SEN export folder not found: $senDir" }
        val s = Summary()

        // ── MySQL first: what does the migrated schema need? ────────────────
        val neededLegacyIds = mutableSetOf<String>()
        val neededLeeIds = mutableSetOf<String>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT legacy_id, legacy_lee_id FROM $schema.customers").use { rs ->
                while (rs.next()) {
                    rs.getLong(1).let { if (!rs.wasNull()) neededLegacyIds += it.toString() }
                    rs.getLong(2).let { if (!rs.wasNull()) neededLeeIds += it.toString() }
                }
            }
        }
        val neededDocIds = mutableSetOf<String>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT legacy_docid FROM $schema.contracts WHERE legacy_docid IS NOT NULL").use { rs ->
                while (rs.next()) neededDocIds += rs.getLong(1).toString()
            }
        }
        log("needed from MySQL: ${neededLegacyIds.size} trader ids, ${neededLeeIds.size} lee ids, ${neededDocIds.size} doc ids")

        // ── targeted SEN reads (only rows the schema asks for) ──────────────
        val cus = parse(senDir, "cus", keyColumn = "TRAID", keys = neededLegacyIds, s = s)
        val traidToLeeId = cus?.rows?.associate { cus.value(it, "TRAID") to cus.value(it, "LEEID") }.orEmpty()
        val allLeeIds = neededLeeIds + traidToLeeId.values.filter { it.isNotEmpty() }

        val lee = parse(senDir, "lee", keyColumn = "LEEID", keys = allLeeIds, s = s)
        // ALL addresses of the referenced entities (a party can have many)
        val adr = parse(senDir, "adr", keyColumn = "LEEID", keys = allLeeIds, s = s)
        val sdt = parse(senDir, "sdt", s = s) // 87-row catalog, no filter needed
        val sld = parse(senDir, "sld", keyColumn = "DOCID", keys = neededDocIds, s = s)
        val ssd = parse(senDir, "ssd", keyColumn = "DOCID", keys = neededDocIds, s = s)
        val sti = parse(senDir, "sti", s = s) // 224-row item catalog

        materializeSenTables(lee, cus, adr, sld, ssd, sdt, sti, apply, s)

        if (lee != null) enrichCustomers(lee, traidToLeeId, cus, adr, apply, s)
        else log("lee.csv absent - customer identity/contact phases skipped")
        if (sld != null) enrichContracts(sld, sdt, apply, s)
        else log("sld.csv absent - contract phase skipped")
        // BEFORE the catalog phase: the recovered lines may reference item
        // classes no z_commercials row ever did, and they must gain their
        // STI name/code in the same run.
        if (ssd != null) recoverMissingContractLines(apply, s)
        if (ssd != null) enrichLineQuantities(ssd, apply, s)
        else log("ssd.csv absent - product lines + quantities skipped")
        if (sti != null) enrichSpotTypeCatalog(sti, legacyScratchSchema, apply, s)
        else log("sti.csv absent - spot-type catalog phase skipped")
        backfillProvisional(apply, s)
        return s
    }

    private fun parse(dir: File, table: String, keyColumn: String? = null, keys: Set<String>? = null, s: Summary): SenTable? {
        val file = File(dir, "$table.csv").takeIf { it.isFile }
            ?: dir.listFiles { f -> f.name.startsWith(table) && (f.name.endsWith(".tsv") || f.name.endsWith(".csv")) }
                ?.maxByOrNull { it.length() }
            ?: return null
        val sidecar = File(dir, "$table.headers.txt").takeIf { it.isFile }
            ?.readLines()?.filter { it.isNotBlank() }?.map { it.substringAfter('\t').trim() }
        val t = SenExports.parse(file, sidecar, keyColumn, keys)
        s.rejectedRecords += t.rejected
        log("$table: kept ${t.rows.size} of the export's records (${file.name}${if (t.rejected > 0) ", ${t.rejected} rejected" else ""})")
        return t
    }

    // ── phase 0: the SEN side of the faithful union layer ───────────────────

    /**
     * Materializes the Oracle/SEN tables INSIDE the station schema as
     * `sen_*` tables (faithful union layer, owner directive): exact ERP
     * column names, the essential fields, MySQL-first filtered rows (only
     * entities/documents the legacy data references - catalogs whole).
     * `sen_` prefix because legacy `cus`/`sld` collide with the ERP names.
     * Rebuilt on every run (derived data): the app's tables stay the working
     * layer; these are the inspectable ERP truth next to the legacy copies.
     */
    private fun materializeSenTables(
        lee: SenTable?, cus: SenTable?, adr: SenTable?, sld: SenTable?,
        ssd: SenTable?, sdt: SenTable?, sti: SenTable?, apply: Boolean, s: Summary,
    ) {
        if (!apply) {
            log("sen tables: would materialize sen_lee/sen_cus/sen_adr/sen_sld/sen_ssd/sen_sdt/sen_sti  [DRY RUN]")
            return
        }

        fun rebuild(
            name: String,
            ddlColumns: String,
            params: Int,
            t: SenTable?,
            insert: (java.sql.PreparedStatement, List<String>) -> Unit,
        ) {
            if (t == null) return
            c.createStatement().use { st ->
                st.executeUpdate("DROP TABLE IF EXISTS $schema.$name")
                st.executeUpdate("CREATE TABLE $schema.$name ($ddlColumns) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
            }
            c.prepareStatement(
                "INSERT INTO $schema.$name VALUES (${List(params) { "?" }.joinToString(",")})"
            ).use { ps ->
                var batched = 0
                for (row in t.rows) {
                    insert(ps, row)
                    ps.addBatch()
                    if (++batched % 500 == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
            log("sen tables: $name <- ${t.rows.size} rows")
        }

        fun str(t: SenTable, row: List<String>, col: String): String? =
            t.value(row, col).takeIf { it.isNotBlank() }
        fun lng(t: SenTable, row: List<String>, col: String): Long? =
            t.value(row, col).toLongOrNull()
        fun dec(t: SenTable, row: List<String>, col: String): Double? =
            t.value(row, col).replace(",", ".").toDoubleOrNull()
        fun dt(t: SenTable, row: List<String>, col: String): java.sql.Date? =
            SenExports.parseDate(t.value(row, col))?.let(java.sql.Date::valueOf)

        rebuild(
            "sen_lee",
            "LEEID BIGINT PRIMARY KEY, LEENAME VARCHAR(255), LEEAFM VARCHAR(16), TXOCODE VARCHAR(16), " +
                "LEEADT VARCHAR(32), ADRIDMAIN BIGINT, LEECODE VARCHAR(32)",
            7, lee,
        ) { ps, r ->
            lee!!
            ps.setObject(1, lng(lee, r, "LEEID")); ps.setString(2, str(lee, r, "LEENAME"))
            ps.setString(3, str(lee, r, "LEEAFM")); ps.setString(4, str(lee, r, "TXOCODE"))
            ps.setString(5, str(lee, r, "LEEADT")); ps.setObject(6, lng(lee, r, "ADRIDMAIN"))
            ps.setString(7, str(lee, r, "LEECODE"))
        }
        rebuild(
            "sen_cus",
            "TRAID BIGINT PRIMARY KEY, LEEID BIGINT, TRACODE VARCHAR(32), ADRIDMAIN BIGINT, " +
                "ADRIDDEST BIGINT, TRASTARTDATE DATE",
            6, cus,
        ) { ps, r ->
            cus!!
            ps.setObject(1, lng(cus, r, "TRAID")); ps.setObject(2, lng(cus, r, "LEEID"))
            ps.setString(3, str(cus, r, "TRACODE")); ps.setObject(4, lng(cus, r, "ADRIDMAIN"))
            ps.setObject(5, lng(cus, r, "ADRIDDEST")); ps.setDate(6, dt(cus, r, "TRASTARTDATE"))
        }
        rebuild(
            "sen_adr",
            "ADRID BIGINT PRIMARY KEY, LEEID BIGINT, ADRDESCRIPTION VARCHAR(128), ADRSTREET VARCHAR(255), " +
                "ADRNUMBER VARCHAR(16), ADRZIPCODE VARCHAR(16), ADRDISTRICT VARCHAR(64), ADRCITY VARCHAR(64), " +
                "ADRPHONE1 VARCHAR(32), ADRPHONE2 VARCHAR(32), ADRPHONE3 VARCHAR(32), ADRFAX VARCHAR(32), " +
                "ADREMAIL VARCHAR(128), ISOFFICE VARCHAR(8), KEY idx_sen_adr_lee (LEEID)",
            14, adr,
        ) { ps, r ->
            adr!!
            ps.setObject(1, lng(adr, r, "ADRID")); ps.setObject(2, lng(adr, r, "LEEID"))
            ps.setString(3, str(adr, r, "ADRDESCRIPTION")); ps.setString(4, str(adr, r, "ADRSTREET"))
            ps.setString(5, str(adr, r, "ADRNUMBER")); ps.setString(6, str(adr, r, "ADRZIPCODE"))
            ps.setString(7, str(adr, r, "ADRDISTRICT")); ps.setString(8, str(adr, r, "ADRCITY"))
            ps.setString(9, str(adr, r, "ADRPHONE1")); ps.setString(10, str(adr, r, "ADRPHONE2"))
            ps.setString(11, str(adr, r, "ADRPHONE3")); ps.setString(12, str(adr, r, "ADRFAX"))
            ps.setString(13, str(adr, r, "ADREMAIL")); ps.setString(14, str(adr, r, "ISOFFICE"))
        }
        rebuild(
            "sen_sld",
            "DOCID BIGINT PRIMARY KEY, DOTID BIGINT, DOCNUMBER VARCHAR(32), DOCEKDOSISDATE DATE, " +
                "TDOEKTELESISDATE DATE, TDOOTHERDATE DATE, TRAID BIGINT, TDOLEEAFM VARCHAR(16), " +
                "TDOQTYA DECIMAL(12,2), TDOQTYB DECIMAL(12,2), TDOGROSSVALUE DECIMAL(14,2), " +
                "DOCIDTRIANGLE BIGINT, TRAIDPRINCIPAL BIGINT, DOCGUID VARCHAR(40), KEY idx_sen_sld_traid (TRAID)",
            14, sld,
        ) { ps, r ->
            sld!!
            ps.setObject(1, lng(sld, r, "DOCID")); ps.setObject(2, lng(sld, r, "DOTID"))
            ps.setString(3, str(sld, r, "DOCNUMBER")); ps.setDate(4, dt(sld, r, "DOCEKDOSISDATE"))
            ps.setDate(5, dt(sld, r, "TDOEKTELESISDATE")); ps.setDate(6, dt(sld, r, "TDOOTHERDATE"))
            ps.setObject(7, lng(sld, r, "TRAID")); ps.setString(8, str(sld, r, "TDOLEEAFM"))
            ps.setObject(9, dec(sld, r, "TDOQTYA")); ps.setObject(10, dec(sld, r, "TDOQTYB"))
            ps.setObject(11, dec(sld, r, "TDOGROSSVALUE")); ps.setObject(12, lng(sld, r, "DOCIDTRIANGLE"))
            ps.setObject(13, lng(sld, r, "TRAIDPRINCIPAL")); ps.setString(14, str(sld, r, "DOCGUID"))
        }
        rebuild(
            "sen_ssd",
            "DOCID BIGINT, LINENO INT, MCIID BIGINT, CODCODE VARCHAR(32), STDDESCRIPTION VARCHAR(255), " +
                "STDQTYA DECIMAL(12,2), STDQTYB DECIMAL(12,2), SSDEXECDATE DATE, PRIMARY KEY (DOCID, LINENO)",
            8, ssd,
        ) { ps, r ->
            ssd!!
            ps.setObject(1, lng(ssd, r, "DOCID")); ps.setObject(2, lng(ssd, r, "LINENO"))
            ps.setObject(3, lng(ssd, r, "MCIID")); ps.setString(4, str(ssd, r, "CODCODE"))
            ps.setString(5, str(ssd, r, "STDDESCRIPTION")); ps.setObject(6, dec(ssd, r, "STDQTYA"))
            ps.setObject(7, dec(ssd, r, "STDQTYB")); ps.setDate(8, dt(ssd, r, "SSDEXECDATE"))
        }
        rebuild(
            "sen_sdt",
            "DOTID BIGINT PRIMARY KEY, DOTCODE VARCHAR(16), DOTDESCRIPTION VARCHAR(128)",
            3, sdt,
        ) { ps, r ->
            sdt!!
            ps.setObject(1, lng(sdt, r, "DOTID")); ps.setString(2, str(sdt, r, "DOTCODE"))
            ps.setString(3, str(sdt, r, "DOTDESCRIPTION"))
        }
        rebuild(
            "sen_sti",
            "MCIID BIGINT NULL, ITMNAME VARCHAR(255), CODCODE VARCHAR(32), DMIID BIGINT, KEY idx_sen_sti_mci (MCIID)",
            4, sti,
        ) { ps, r ->
            sti!!
            ps.setObject(1, lng(sti, r, "MCIID")); ps.setString(2, str(sti, r, "ITMNAME"))
            ps.setString(3, str(sti, r, "CODCODE")); ps.setObject(4, lng(sti, r, "DMIID"))
        }
    }

    // ── phase 1+2: customer identity + contacts ─────────────────────────────

    private fun enrichCustomers(
        lee: SenTable,
        traidToLeeId: Map<String, String>,
        cus: SenTable?,
        adr: SenTable?,
        apply: Boolean,
        s: Summary,
    ) {
        val leeById = lee.rows.associateBy { lee.value(it, "LEEID") }
        val adrById = adr?.rows?.associateBy { adr.value(it, "ADRID") }.orEmpty()
        // TRAID -> the ERP customer code (Κωδ. Πελ. as the operators know it)
        val tracodeByTraid = cus?.rows?.associate { cus.value(it, "TRAID") to cus.value(it, "TRACODE") }.orEmpty()

        data class Update(
            val id: Long, val name: String, val vat: String?,
            val phone: String?, val fax: String?, val email: String?,
            val street: String?, val zip: String?, val city: String?,
            val newCode: String?, val oldCode: String,
        )

        fun placeholderEmail(v: String?) = v.isNullOrBlank() || v.contains("@example.")

        val updates = ArrayList<Update>()
        c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT id, legacy_id, legacy_lee_id, code, name, vat_number, phone, fax, email,
                       address_street, address_zip, address_city
                FROM $schema.customers
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    s.customersExamined++
                    val legacyId = rs.getLong("legacy_id").let { if (rs.wasNull()) null else it }
                    val legacyLeeId = rs.getLong("legacy_lee_id").let { if (rs.wasNull()) null else it }

                    val leeRow = when {
                        leeById.containsKey(legacyLeeId?.toString()) -> {
                            s.customersResolvedViaLee++; leeById[legacyLeeId.toString()]
                        }
                        leeById.containsKey(traidToLeeId[legacyId?.toString()]) -> {
                            s.customersResolvedViaCus++; leeById[traidToLeeId[legacyId.toString()]]
                        }
                        else -> {
                            s.customersUnresolved++; null
                        }
                    } ?: continue

                    val erpName = lee.value(leeRow, "LEENAME").replace(Regex("\\s+"), " ").trim()
                    val erpVat = lee.value(leeRow, "LEEAFM").takeIf { it.isNotBlank() }
                    if (erpName.isEmpty()) continue

                    // the ERP account code replaces the migration's LPAD(traid)
                    // placeholder - it is what the legacy Break Console displays
                    val oldCode = rs.getString("code").orEmpty()
                    val newCode = tracodeByTraid[legacyId?.toString()]
                        ?.takeIf { it.isNotBlank() && it != oldCode }

                    // contacts from the entity's MAIN address: phone/fax/email are
                    // fill-only (recovered real values win); the ADDRESS itself has
                    // no local source - the ERP is authoritative, sync it outright
                    val adrRow = adrById[lee.value(leeRow, "ADRIDMAIN")]
                    val erpPhone = adrRow?.let { adr!!.value(it, "ADRPHONE1").takeIf(String::isNotBlank) }
                    val erpFax = adrRow?.let { adr!!.value(it, "ADRFAX").takeIf(String::isNotBlank) }
                    val erpEmail = adrRow?.let { adr!!.value(it, "ADREMAIL").takeIf(String::isNotBlank) }
                    val newPhone = if (rs.getString("phone").isNullOrBlank() && erpPhone != null) erpPhone else null
                    val newFax = if (rs.getString("fax").isNullOrBlank() && erpFax != null) erpFax else null
                    val newEmail = if (placeholderEmail(rs.getString("email")) && erpEmail != null) erpEmail else null

                    fun addressPart(column: String, max: Int): String? = adrRow
                        ?.let { adr!!.value(it, column).replace(Regex("\\s+"), " ").trim() }
                        ?.takeIf { it.isNotBlank() }?.take(max)
                    val erpStreet = adrRow?.let {
                        listOfNotNull(addressPart("ADRSTREET", 140), addressPart("ADRNUMBER", 16))
                            .joinToString(" ").takeIf(String::isNotBlank)?.take(160)
                    }
                    val erpZip = addressPart("ADRZIPCODE", 16)
                    val erpCity = adrRow?.let {
                        (addressPart("ADRCITY", 64) ?: addressPart("ADRDISTRICT", 64))?.take(64)
                    }
                    val addressChanged = adrRow != null && (
                        erpStreet != rs.getString("address_street") ||
                            erpZip != rs.getString("address_zip") ||
                            erpCity != rs.getString("address_city")
                        )

                    val oldName = rs.getString("name").orEmpty()
                    val oldVat = rs.getString("vat_number")
                    val identityChanged = erpName != oldName || erpVat != oldVat
                    val contactsChanged = newPhone != null || newFax != null || newEmail != null
                    if (!identityChanged && !contactsChanged && !addressChanged && newCode == null) {
                        s.customersUnchanged++
                        continue
                    }
                    if (erpName != oldName) s.customersRenamed++
                    if (erpVat != oldVat && erpVat != null) s.customersVatSet++
                    if (contactsChanged || addressChanged) s.contactsFilled++
                    if (newCode != null) s.customersRecoded++
                    updates += Update(
                        rs.getLong("id"), erpName, erpVat, newPhone, newFax, newEmail,
                        erpStreet, erpZip, erpCity, newCode, oldCode,
                    )
                }
            }
        }

        if (apply && updates.isNotEmpty()) {
            val recodes = updates.filter { it.newCode != null }
            // Codes are UNIQUE and the new set can overlap the old (an ERP code
            // may equal ANOTHER customer's placeholder), so recode in two
            // passes: park on a collision-free temp code, then set the finals.
            if (recodes.isNotEmpty()) {
                c.prepareStatement("UPDATE $schema.customers SET code = CONCAT('~', id) WHERE id = ?").use { ps ->
                    for (u in recodes) { ps.setLong(1, u.id); ps.addBatch() }
                    ps.executeBatch()
                }
            }
            c.prepareStatement(
                """
                UPDATE $schema.customers
                SET name = ?, vat_number = ?, synthetic = FALSE, code = COALESCE(?, code),
                    phone = COALESCE(?, phone), fax = COALESCE(?, fax), email = COALESCE(?, email),
                    address_street = IF(?, ?, address_street),
                    address_zip = IF(?, ?, address_zip),
                    address_city = IF(?, ?, address_city)
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                var batched = 0
                for (u in updates) {
                    // the ERP is authoritative for the address ONLY when the
                    // entity's main ADR row was in the export (sync flag)
                    val syncAddress = u.street != null || u.zip != null || u.city != null
                    ps.setString(1, u.name)
                    ps.setString(2, u.vat)
                    ps.setString(3, if (u.newCode != null) u.newCode else null)
                    ps.setString(4, u.phone)
                    ps.setString(5, u.fax)
                    ps.setString(6, u.email)
                    ps.setBoolean(7, syncAddress); ps.setString(8, u.street)
                    ps.setBoolean(9, syncAddress); ps.setString(10, u.zip)
                    ps.setBoolean(11, syncAddress); ps.setString(12, u.city)
                    ps.setLong(13, u.id)
                    ps.addBatch()
                    if (++batched % 500 == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
            // COALESCE(?, code) left the parked '~id' codes in place for rows
            // whose newCode is null - impossible by construction (only recodes
            // were parked), so every parked row just received its final code.
            // The email archive references customers BY CODE - remap it.
            if (recodes.isNotEmpty()) {
                c.prepareStatement("UPDATE $schema.email_log SET customer_code = ? WHERE customer_code = ?").use { ps ->
                    var remapped = 0
                    for (u in recodes) {
                        ps.setString(1, u.newCode)
                        ps.setString(2, u.oldCode)
                        ps.addBatch()
                        if (++remapped % 500 == 0) ps.executeBatch()
                    }
                    ps.executeBatch()
                }
                s.emailLogRecoded = recodes.size
            }
        }
        log(
            "customers: ${s.customersExamined} examined, " +
                "${s.customersResolvedViaLee} via lee id + ${s.customersResolvedViaCus} via trader account, " +
                "${s.customersRenamed} renamed, ${s.customersVatSet} got a real VAT, " +
                "${s.customersRecoded} got their ERP account code, ${s.contactsFilled} got missing contact details " +
                "(${s.customersUnchanged} already correct, ${s.customersUnresolved} not in the export)" +
                if (apply) "" else "  [DRY RUN]"
        )
    }

    // ── phase 2b: per-spot sales items (the Break Console's Τύπος) ──────────

    /**
     * Enriches the ERP ITEM catalog (`spot_types`): each item class gets its
     * `name` (STI.ITMNAME - e.g. 'Διαφ. TV Κρήτη Σ73.002', or 'Διαφημίσεις
     * τηλεόρασης Δ Ω Ρ Α' whose name itself carries the gift marker) and its
     * `item_code` (STI.CODCODE) - exactly what the legacy Break Console shows
     * as Τύπος. The join is `spot_types.legacy_id == STI.MCIID`, and the rows
     * were seeded from the item classes the contract lines actually reference
     * (`z_commercials.mciid`).
     *
     * ⚠ This is NOT a mirror of legacy `programtypes`. That is the PROGRAMME
     * catalog (ΚΛΕΨΑ, ΞΕΝΗ ΤΑΙΝΙΑ), it lives in `programs`, and its ids are a
     * DIFFERENT ID SPACE from MCIID. An earlier version of this doc claimed
     * `messages.messageTypeID` IS the item class; it is not, and the resulting
     * join sold television shows as ERP products. See LegacyTransformer
     * .migrateSpotTypes.
     *
     * Spots themselves are NEVER stamped - a spot's item is a REFERENCE (its
     * contract LINE's `spot_type_id`), so relinking a spot to another contract
     * or line updates every display, like the legacy model. For a station
     * migrated BEFORE the catalog existed, [scratch] (the replayed dump)
     * supplies what the migration would have written, seeded from
     * `z_commercials`.
     */
    private fun enrichSpotTypeCatalog(sti: SenTable, scratch: String?, apply: Boolean, s: Summary) {
        // adoption path: a pre-catalog station rebuilds what migration now writes
        if (scratch != null && apply) {
            // The ERP item-class catalog is seeded from the item classes the
            // contract lines reference (z_commercials.mciid) - NOT from
            // programtypes, which is the PROGRAMME catalog (see LegacyTransformer
            // .migrateSpotTypes; mciid and programtypes.id are different id spaces).
            val inserted = c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO $schema.spot_types(legacy_id, name)
                    SELECT DISTINCT z.mciid, '' FROM $scratch.z_commercials z
                    WHERE z.mciid > 0
                      AND NOT EXISTS (SELECT 1 FROM $schema.spot_types x WHERE x.legacy_id = z.mciid)
                    """.trimIndent()
                )
            }
            // messages.messageTypeID is the PROGRAMME the spot was booked into.
            // The programme join must match the SPOT'S STATION: `programtypes`
            // ids restart per forTV, so programme 5 exists on both the TV and the
            // radio side and means different shows. Joining on legacy_id alone
            // would link a TV spot to whichever of the two rows MySQL reached
            // first.
            val linked = c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    UPDATE $schema.spots sp, $scratch.messages m, $schema.programs pr
                    SET sp.booked_program_id = pr.id
                    WHERE m.id = sp.legacy_id
                      AND pr.legacy_id = m.messageTypeID
                      AND pr.station_id = sp.station_id
                      AND sp.booked_program_id IS NULL
                    """.trimIndent()
                )
            }
            s.spotsTypeLinked = linked
            log("spot types: $inserted item classes adopted from $scratch.z_commercials, $linked spots linked to their booked programme")
        }

        data class Update(val typeId: Long, val item: String, val code: String?)

        // STI by item class: MCIID -> (ITMNAME, CODCODE), verified 1:1
        val byMci = sti.rows
            .filter { sti.value(it, "MCIID").isNotEmpty() }
            .associateBy { sti.value(it, "MCIID") }

        val updates = ArrayList<Update>()
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, legacy_id, name, item_code FROM $schema.spot_types WHERE legacy_id IS NOT NULL"
            ).use { rs ->
                while (rs.next()) {
                    val row = byMci[rs.getLong("legacy_id").toString()] ?: continue
                    val item = sti.value(row, "ITMNAME").replace(Regex("\\s+"), " ").trim()
                    val code = sti.value(row, "CODCODE").takeIf { it.isNotBlank() }
                    if (item.isEmpty()) continue
                    if (item == rs.getString("name") && code == rs.getString("item_code")) continue
                    updates += Update(rs.getLong("id"), item, code)
                }
            }
        }
        s.spotTypesEnriched = updates.size

        if (apply && updates.isNotEmpty()) {
            c.prepareStatement("UPDATE $schema.spot_types SET name = ?, item_code = ? WHERE id = ?").use { ps ->
                for (u in updates) {
                    ps.setString(1, u.item)
                    ps.setString(2, u.code)
                    ps.setLong(3, u.typeId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        log(
            "spot types: ${s.spotTypesEnriched} catalog entries got their ERP sales item (1:1 by item class)" +
                if (apply) "" else "  [DRY RUN]"
        )
    }

    // ── phase 3: contracts (period + agreed qty + gift flag) ────────────────

    private fun enrichContracts(sld: SenTable, sdt: SenTable?, apply: Boolean, s: Summary) {
        data class ErpDoc(val start: LocalDate?, val end: LocalDate?, val gift: Boolean?)

        // SDT catalog: doc type id -> is it a gift type? ("ΔΩΡ" in the
        // description - accent-stripped, or contains("ΔΩΡ") misses "(Δώρα)")
        val giftByDotId = sdt?.rows?.associate { row ->
            sdt.value(row, "DOTID") to SenExports.greekUpper(sdt.value(row, "DOTDESCRIPTION")).contains("ΔΩΡ")
        }.orEmpty()

        val docs = HashMap<String, ErpDoc>(sld.rows.size)
        for (row in sld.rows) {
            val docId = sld.value(row, "DOCID")
            if (docId.isEmpty()) continue
            docs[docId] = ErpDoc(
                start = SenExports.parseDate(sld.value(row, "TDOEKTELESISDATE")),
                end = SenExports.parseDate(sld.value(row, "TDOOTHERDATE")),
                gift = giftByDotId[sld.value(row, "DOTID")],
            )
        }

        data class Update(val id: Long, val start: LocalDate?, val end: LocalDate?, val gift: Boolean?)

        val updates = ArrayList<Update>()
        c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT ct.id, ct.legacy_docid, ct.start_date, ct.end_date, ct.dates_provisional, ct.is_gift
                FROM $schema.contracts ct WHERE ct.legacy_docid IS NOT NULL
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    s.contractsExamined++
                    val doc = docs[rs.getLong("legacy_docid").toString()]
                    if (doc == null) {
                        s.contractsNotInSld++
                        continue
                    }

                    val period = if (doc.start != null && doc.end != null) doc else null
                    val periodChanged = period != null && (
                        rs.getDate("start_date")?.toLocalDate() != period.start ||
                            rs.getDate("end_date")?.toLocalDate() != period.end ||
                            rs.getBoolean("dates_provisional")
                        )
                    if (period == null) s.contractsNoErpDates++

                    val giftFix = doc.gift?.takeIf { it != rs.getBoolean("is_gift") }

                    if (!periodChanged && giftFix == null) continue
                    if (periodChanged) s.contractsDated++
                    if (giftFix != null) s.contractsGiftFixed++
                    updates += Update(
                        id = rs.getLong("id"),
                        start = if (periodChanged) period!!.start else null,
                        end = if (periodChanged) period!!.end else null,
                        gift = giftFix,
                    )
                }
            }
        }

        if (apply && updates.isNotEmpty()) {
            c.prepareStatement(
                """
                UPDATE $schema.contracts
                SET start_date = COALESCE(?, start_date),
                    end_date = COALESCE(?, end_date),
                    dates_provisional = IF(? IS NULL, dates_provisional, FALSE),
                    is_gift = COALESCE(?, is_gift)
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                var batched = 0
                for (u in updates) {
                    ps.setDate(1, u.start?.let(java.sql.Date::valueOf))
                    ps.setDate(2, u.end?.let(java.sql.Date::valueOf))
                    ps.setDate(3, u.start?.let(java.sql.Date::valueOf))
                    ps.setObject(4, u.gift, java.sql.Types.BOOLEAN)
                    ps.setLong(5, u.id)
                    ps.addBatch()
                    if (++batched % 500 == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
        }
        log(
            "contracts: ${s.contractsExamined} with a legacy doc id - ${s.contractsDated} get their real ERP " +
                "period, ${s.contractsGiftFixed} a corrected gift flag " +
                "(${s.contractsNoErpDates} in the ERP without dates, ${s.contractsNotInSld} not in the export)" +
                if (apply) "" else "  [DRY RUN]"
        )
    }

    // ── phase 3a: product lines the z_commercials snapshot never carried ─────

    /**
     * `z_commercials` is the owner's Oracle VIEW over the ERP document lines,
     * and it is INCOMPLETE: 140 of this station's documents have no row in it,
     * so the migration gave them a synthetic line (line_no 1000) with an
     * honest NULL item. The legacy Break Console still showed their item -
     * because it queried the live ERP, not the snapshot.
     *
     * `ssd.csv` IS that live source (it is what z_commercials mirrors), and it
     * covers 139 of the 140. So recover the real lines from it:
     *
     *  1. seed any item class the recovered lines reference but the catalog
     *     lacks (the STI phase, which runs next, gives it name + code);
     *  2. insert the real (document, lineno) lines;
     *  3. re-point each AIRING at the line it actually charges to - the
     *     verbatim `schedule` copy still carries docID+lineno, so this is the
     *     same hard key the transformer used, not a guess;
     *  4. re-point each SPOT's default line at the one its own airings charge
     *     to most often (single-line documents resolve directly);
     *  5. drop the synthetic lines nothing references any more.
     *
     * Idempotent: every step is a NOT EXISTS insert or a targeted re-point, so
     * re-running the enricher changes nothing.
     */
    private fun recoverMissingContractLines(apply: Boolean, s: Summary) {
        // Which contracts are still stuck on a synthetic line?
        val stuck = c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT COUNT(DISTINCT ct.id) FROM $schema.contracts ct, $schema.contract_lines cl, $schema.sen_ssd ssd
                WHERE cl.contract_id = ct.id AND cl.line_no >= 1000
                  AND ssd.DOCID = ct.legacy_docid
                """.trimIndent()
            ).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        if (stuck == 0) {
            log("  product lines: no document is missing its ERP lines")
            return
        }
        if (!apply) {
            log("  product lines: would recover real lines for $stuck documents from ssd  [DRY RUN]")
            return
        }

        // 1. item classes the recovered lines need but the catalog lacks
        val newTypes = c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $schema.spot_types(legacy_id, name)
                SELECT DISTINCT ssd.MCIID, ''
                FROM $schema.sen_ssd ssd
                WHERE ssd.MCIID > 0
                  AND NOT EXISTS (
                      SELECT 1 FROM $schema.spot_types sty WHERE sty.legacy_id = ssd.MCIID
                  )
                """.trimIndent()
            )
        }

        // 2. the real lines
        val added = c.createStatement().use { st ->
            st.executeUpdate(
                """
                INSERT INTO $schema.contract_lines(contract_id, line_no, spot_type_id)
                SELECT DISTINCT ct.id, ssd.LINENO, sty.id
                FROM $schema.sen_ssd ssd
                JOIN $schema.contracts ct  ON ct.legacy_docid = ssd.DOCID
                LEFT JOIN $schema.spot_types sty ON sty.legacy_id = ssd.MCIID
                WHERE NOT EXISTS (
                    SELECT 1 FROM $schema.contract_lines cl
                    WHERE cl.contract_id = ct.id AND cl.line_no = ssd.LINENO
                )
                """.trimIndent()
            )
        }

        // 3. the airing's ACTUAL charge - same hard key the transformer used
        val airings = c.createStatement().use { st ->
            st.executeUpdate(
                """
                UPDATE $schema.placements p, $schema.schedule sch, $schema.contracts ct, $schema.contract_lines cl
                SET p.contract_line_id = cl.id
                WHERE sch.id = p.legacy_id
                  AND ct.legacy_docid = sch.docID
                  AND cl.contract_id = ct.id AND cl.line_no = sch.lineno
                  AND p.contract_line_id IS NULL
                """.trimIndent()
            )
        }

        // 4. each spot's default line: the one its own airings charge to most
        val spots = c.createStatement().use { st ->
            st.executeUpdate(
                """
                UPDATE $schema.spots sp, $schema.contract_lines old,
                       (
                    SELECT p.spot_id, p.contract_line_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY p.spot_id
                               ORDER BY COUNT(*) DESC, p.contract_line_id
                           ) AS rn
                    FROM $schema.placements p, $schema.contract_lines cl
                    WHERE cl.id = p.contract_line_id
                      AND cl.line_no < 1000
                    GROUP BY p.spot_id, p.contract_line_id
                ) modal
                SET sp.contract_line_id = modal.contract_line_id
                WHERE old.id = sp.contract_line_id
                  AND modal.spot_id = sp.id AND modal.rn = 1
                  AND old.line_no >= 1000
                """.trimIndent()
            )
        }

        // 5. synthetic lines nothing references any more
        val dropped = c.createStatement().use { st ->
            st.executeUpdate(
                """
                DELETE cl FROM $schema.contract_lines cl
                WHERE cl.line_no >= 1000
                  AND NOT EXISTS (SELECT 1 FROM $schema.spots sp      WHERE sp.contract_line_id = cl.id)
                  AND NOT EXISTS (SELECT 1 FROM $schema.placements p  WHERE p.contract_line_id  = cl.id)
                """.trimIndent()
            )
        }
        log(
            "  product lines: recovered $added real lines for $stuck documents the z_commercials " +
                "snapshot missed (+$newTypes new item classes); re-pointed $airings airings and " +
                "$spots spots; dropped $dropped synthetic lines"
        )
    }

    // ── phase 3b: per-product-line agreed quantities ─────────────────────────

    /**
     * The agreed quantity of each PRODUCT LINE, from the ERP's own line rows
     * (SSD STDQTYA), joined the faithful way: `(document, lineno)` - our
     * contract_lines mirror the legacy z_commercials view, so real lines
     * match directly. Fallback lines (line_no >= 1000) have no ERP row and
     * keep 0.
     */
    private fun enrichLineQuantities(ssd: SenTable, apply: Boolean, s: Summary) {
        val qtyByDocLine = HashMap<String, Long>(ssd.rows.size)
        for (row in ssd.rows) {
            val doc = ssd.value(row, "DOCID")
            val line = ssd.value(row, "LINENO")
            if (doc.isEmpty() || line.isEmpty()) continue
            val qty = ssd.value(row, "STDQTYA").replace(",", ".").toDoubleOrNull()?.toLong() ?: continue
            if (qty > 0) qtyByDocLine[doc + " " + line] = qty
        }

        data class Update(val lineId: Long, val qty: Long)

        val updates = ArrayList<Update>()
        c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT cl.id, cl.line_no, cl.desired_qty, ct.legacy_docid
                FROM $schema.contract_lines cl, $schema.contracts ct
                WHERE ct.id = cl.contract_id
                  AND ct.legacy_docid IS NOT NULL AND cl.line_no < 1000
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    val qty = qtyByDocLine[rs.getLong("legacy_docid").toString() + " " + rs.getInt("line_no")]
                        ?: continue
                    if (qty == rs.getLong("desired_qty")) continue
                    updates += Update(rs.getLong("id"), qty)
                }
            }
        }
        s.contractsQtySet = updates.size

        if (apply && updates.isNotEmpty()) {
            c.prepareStatement("UPDATE $schema.contract_lines SET desired_qty = ? WHERE id = ?").use { ps ->
                var batched = 0
                for (u in updates) {
                    ps.setLong(1, u.qty)
                    ps.setLong(2, u.lineId)
                    ps.addBatch()
                    if (++batched % 500 == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
        }
        log(
            "product lines: ${s.contractsQtySet} lines got their agreed quantity from the ERP line rows" +
                if (apply) "" else "  [DRY RUN]"
        )
    }

    // ── phase 4: provisional backfill for what the ERP could not date ───────

    /**
     * The same placement-derived period backfill the migration performs
     * (LegacyTransformer.backfillProvisionalContractDates) - repeated here
     * because a station migrated BEFORE the contract-dates feature gained the
     * columns via guarded ALTER (NULL dates, flag FALSE) and never got the
     * backfill. Restricted to contracts the ERP did NOT date (`start_date IS
     * NULL`), so it can never overwrite a real period; contracts that never
     * aired keep NULL dates, exactly like the migration.
     */
    private fun backfillProvisional(apply: Boolean, s: Summary) {
        val sql =
            """
            UPDATE $schema.contracts ct,
                   (
                SELECT cl.contract_id AS cid, MIN(p.show_date) AS mn, MAX(p.show_date) AS mx
                FROM $schema.placements p, $schema.spots sp, $schema.contract_lines cl
                WHERE sp.id = p.spot_id
                  AND cl.id = sp.contract_line_id
                  AND p.hidden = FALSE
                GROUP BY cl.contract_id
            ) agg
            SET ct.start_date = agg.mn, ct.end_date = agg.mx, ct.dates_provisional = TRUE
            WHERE agg.cid = ct.id
              AND ct.start_date IS NULL
            """.trimIndent()
        s.contractsBackfilled = if (apply) {
            c.createStatement().use { it.executeUpdate(sql) }
        } else {
            c.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT COUNT(DISTINCT cl.contract_id)
                    FROM $schema.contracts ct, $schema.contract_lines cl, $schema.spots sp, $schema.placements p
                    WHERE cl.contract_id = ct.id
                      AND sp.contract_line_id = cl.id
                      AND p.spot_id = sp.id AND p.hidden = FALSE
                      AND ct.start_date IS NULL
                    """.trimIndent()
                ).use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        log(
            "provisional backfill: ${s.contractsBackfilled} ERP-dateless contracts get placement-derived " +
                "periods (flagged provisional)" + if (apply) "" else "  [DRY RUN]"
        )
    }
}
