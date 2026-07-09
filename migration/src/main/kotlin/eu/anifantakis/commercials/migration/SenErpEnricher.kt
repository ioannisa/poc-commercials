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
 * Deliberately NOT read: `ssd.csv` (document LINES). Our contract_lines are
 * 1:1 pseudo-lines (line_no = the legacy doc number, from messages.contractNO),
 * so per-ERP-line data has nowhere to land; the doc-level totals (TDOQTYA)
 * cover the agreed-quantity need. If contract_lines are ever restructured to
 * real ERP lines, `ssd.csv` (DOCID+LINENO+qty) joined through MySQL
 * `z_commercials` (mciid -> docid+lineno) is the recipe.
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
        var spotsItemSet: Int = 0,
        var spotsItemViaLines: Int = 0,
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
        val neededAdrIds = lee?.rows?.mapNotNull { lee.value(it, "ADRIDMAIN").takeIf(String::isNotEmpty) }?.toSet().orEmpty()
        val adr = parse(senDir, "adr", keyColumn = "ADRID", keys = neededAdrIds, s = s)
        val sdt = parse(senDir, "sdt", s = s) // 87-row catalog, no filter needed
        val sld = parse(senDir, "sld", keyColumn = "DOCID", keys = neededDocIds, s = s)
        val ssd = parse(senDir, "ssd", keyColumn = "DOCID", keys = neededDocIds, s = s)
        val sti = parse(senDir, "sti", s = s) // 224-row item catalog

        if (lee != null) enrichCustomers(lee, traidToLeeId, cus, adr, apply, s)
        else log("lee.csv absent - customer identity/contact phases skipped")
        if (sld != null) enrichContracts(sld, sdt, apply, s)
        else log("sld.csv absent - contract phase skipped")
        if (ssd != null && sti != null) enrichSpotItems(ssd, sti, legacyScratchSchema, apply, s)
        else log("ssd.csv/sti.csv absent - sales-item phase skipped")
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
            val newCode: String?, val oldCode: String,
        )

        fun placeholderEmail(v: String?) = v.isNullOrBlank() || v.contains("@example.")

        val updates = ArrayList<Update>()
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, legacy_id, legacy_lee_id, code, name, vat_number, phone, fax, email FROM $schema.customers"
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

                    // contacts from the entity's MAIN address, fill-only semantics
                    val adrRow = adrById[lee.value(leeRow, "ADRIDMAIN")]
                    val erpPhone = adrRow?.let { adr!!.value(it, "ADRPHONE1").takeIf(String::isNotBlank) }
                    val erpFax = adrRow?.let { adr!!.value(it, "ADRFAX").takeIf(String::isNotBlank) }
                    val erpEmail = adrRow?.let { adr!!.value(it, "ADREMAIL").takeIf(String::isNotBlank) }
                    val newPhone = if (rs.getString("phone").isNullOrBlank() && erpPhone != null) erpPhone else null
                    val newFax = if (rs.getString("fax").isNullOrBlank() && erpFax != null) erpFax else null
                    val newEmail = if (placeholderEmail(rs.getString("email")) && erpEmail != null) erpEmail else null

                    val oldName = rs.getString("name").orEmpty()
                    val oldVat = rs.getString("vat_number")
                    val identityChanged = erpName != oldName || erpVat != oldVat
                    val contactsChanged = newPhone != null || newFax != null || newEmail != null
                    if (!identityChanged && !contactsChanged && newCode == null) {
                        s.customersUnchanged++
                        continue
                    }
                    if (erpName != oldName) s.customersRenamed++
                    if (erpVat != oldVat && erpVat != null) s.customersVatSet++
                    if (contactsChanged) s.contactsFilled++
                    if (newCode != null) s.customersRecoded++
                    updates += Update(rs.getLong("id"), erpName, erpVat, newPhone, newFax, newEmail, newCode, oldCode)
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
                    phone = COALESCE(?, phone), fax = COALESCE(?, fax), email = COALESCE(?, email)
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                var batched = 0
                for (u in updates) {
                    ps.setString(1, u.name)
                    ps.setString(2, u.vat)
                    ps.setString(3, if (u.newCode != null) u.newCode else null)
                    ps.setString(4, u.phone)
                    ps.setString(5, u.fax)
                    ps.setString(6, u.email)
                    ps.setLong(7, u.id)
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
     * Stamps each spot with the SALES item of its ERP contract line
     * (STI.ITMNAME - e.g. 'Διαφ. TV Κρήτη Σ73.002', or 'Διαφημίσεις
     * τηλεόρασης Δ Ω Ρ Α' whose name itself carries the gift marker), which
     * is exactly what the legacy Break Console shows as Τύπος.
     *
     * Resolution per spot: legacy `messages.messageTypeID` IS the ERP's item
     * class (MCIID - verified: a doc's "ΣΦΗΝΕΣ ΔΕΛΤΙΟΥ" message carries
     * typeID 301 and lands exactly on the doc's MCIID-301 SSD line), so
     * `(document, messageTypeID)` names the SSD line whose CODCODE names the
     * STI item. Per-message typeIDs come from the replayed dump when
     * [scratch] is available (the migration pipeline passes its scratch
     * schema); without it, a spot still resolves when its document sells a
     * SINGLE distinct item (the majority) - multi-item documents are left
     * untouched.
     */
    private fun enrichSpotItems(ssd: SenTable, sti: SenTable, scratch: String?, apply: Boolean, s: Summary) {
        val itemByCode = sti.rows.associate {
            sti.value(it, "CODCODE") to sti.value(it, "ITMNAME").replace(Regex("\\s+"), " ").trim()
        }

        // (docid, item class) -> the lines' item codes; docid -> its distinct item codes
        val codesByDocMci = HashMap<String, MutableSet<String>>()
        val codesByDoc = HashMap<String, MutableSet<String>>()
        for (row in ssd.rows) {
            val doc = ssd.value(row, "DOCID")
            val code = ssd.value(row, "CODCODE")
            if (doc.isEmpty() || code.isEmpty()) continue
            codesByDocMci.getOrPut(doc + " " + ssd.value(row, "MCIID")) { mutableSetOf() } += code
            codesByDoc.getOrPut(doc) { mutableSetOf() } += code
        }

        // message id -> its item class (MCIID), from the replayed legacy dump
        val mciByMessage = HashMap<Long, String>()
        if (scratch != null) {
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, messageTypeID FROM $scratch.messages").use { rs ->
                    while (rs.next()) mciByMessage[rs.getLong(1)] = rs.getString(2)
                }
            }
            log("sales items: ${mciByMessage.size} message item classes from $scratch.messages")
        }

        data class Update(val spotId: Long, val item: String)

        val updates = ArrayList<Update>()
        c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT sp.id, sp.legacy_id, sp.sales_item, ct.legacy_docid
                FROM $schema.spots sp
                JOIN $schema.contract_lines cl ON cl.id = sp.contract_line_id
                JOIN $schema.contracts ct ON ct.id = cl.contract_id
                WHERE ct.legacy_docid IS NOT NULL
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    val docId = rs.getLong("legacy_docid").toString()
                    val viaLine = rs.getLong("legacy_id").let { if (rs.wasNull()) null else mciByMessage[it] }
                        ?.let { mci -> codesByDocMci[docId + " " + mci]?.singleOrNull() }
                    val code = viaLine ?: codesByDoc[docId]?.singleOrNull() ?: continue
                    val item = itemByCode[code] ?: continue
                    if (item.isEmpty() || item == rs.getString("sales_item")) continue
                    if (viaLine != null) s.spotsItemViaLines++
                    updates += Update(rs.getLong("id"), item)
                }
            }
        }
        s.spotsItemSet = updates.size

        if (apply && updates.isNotEmpty()) {
            c.prepareStatement("UPDATE $schema.spots SET sales_item = ? WHERE id = ?").use { ps ->
                var batched = 0
                for (u in updates) {
                    ps.setString(1, u.item)
                    ps.setLong(2, u.spotId)
                    ps.addBatch()
                    if (++batched % 500 == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
        }
        log(
            "sales items: ${s.spotsItemSet} spots stamped with their contract line's item " +
                "(${s.spotsItemViaLines} via the exact line link, the rest via single-item documents)" +
                if (apply) "" else "  [DRY RUN]"
        )
    }

    // ── phase 3: contracts (period + agreed qty + gift flag) ────────────────

    private fun enrichContracts(sld: SenTable, sdt: SenTable?, apply: Boolean, s: Summary) {
        data class ErpDoc(val start: LocalDate?, val end: LocalDate?, val qtyA: Long?, val gift: Boolean?)

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
                qtyA = sld.value(row, "TDOQTYA").replace(",", ".").toDoubleOrNull()?.toLong()?.takeIf { it > 0 },
                gift = giftByDotId[sld.value(row, "DOTID")],
            )
        }

        data class Update(val id: Long, val start: LocalDate?, val end: LocalDate?, val gift: Boolean?, val qty: Long?)

        val updates = ArrayList<Update>()
        c.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT ct.id, ct.legacy_docid, ct.start_date, ct.end_date, ct.dates_provisional, ct.is_gift,
                       (SELECT MAX(cl.desired_qty) FROM $schema.contract_lines cl WHERE cl.contract_id = ct.id) AS line_qty
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
                    val qtyFill = doc.qtyA?.takeIf { rs.getLong("line_qty") == 0L }

                    if (!periodChanged && giftFix == null && qtyFill == null) continue
                    if (periodChanged) s.contractsDated++
                    if (giftFix != null) s.contractsGiftFixed++
                    if (qtyFill != null) s.contractsQtySet++
                    updates += Update(
                        id = rs.getLong("id"),
                        start = if (periodChanged) period!!.start else null,
                        end = if (periodChanged) period!!.end else null,
                        gift = giftFix,
                        qty = qtyFill,
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
            c.prepareStatement(
                "UPDATE $schema.contract_lines SET desired_qty = ? WHERE contract_id = ? AND (desired_qty = 0 OR desired_qty IS NULL)"
            ).use { ps ->
                var batched = 0
                for (u in updates) {
                    if (u.qty == null) continue
                    ps.setLong(1, u.qty)
                    ps.setLong(2, u.id)
                    ps.addBatch()
                    if (++batched % 500 == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
        }
        log(
            "contracts: ${s.contractsExamined} with a legacy doc id - ${s.contractsDated} get their real ERP " +
                "period, ${s.contractsQtySet} their agreed quantity, ${s.contractsGiftFixed} a corrected gift flag " +
                "(${s.contractsNoErpDates} in the ERP without dates, ${s.contractsNotInSld} not in the export)" +
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
            UPDATE $schema.contracts ct
            JOIN (
                SELECT cl.contract_id AS cid, MIN(p.show_date) AS mn, MAX(p.show_date) AS mx
                FROM $schema.placements p
                JOIN $schema.spots sp ON sp.id = p.spot_id
                JOIN $schema.contract_lines cl ON cl.id = sp.contract_line_id
                WHERE p.hidden = FALSE
                GROUP BY cl.contract_id
            ) agg ON agg.cid = ct.id
            SET ct.start_date = agg.mn, ct.end_date = agg.mx, ct.dates_provisional = TRUE
            WHERE ct.start_date IS NULL
            """.trimIndent()
        s.contractsBackfilled = if (apply) {
            c.createStatement().use { it.executeUpdate(sql) }
        } else {
            c.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT COUNT(DISTINCT cl.contract_id)
                    FROM $schema.contracts ct
                    JOIN $schema.contract_lines cl ON cl.contract_id = ct.id
                    JOIN $schema.spots sp ON sp.contract_line_id = cl.id
                    JOIN $schema.placements p ON p.spot_id = sp.id AND p.hidden = FALSE
                    WHERE ct.start_date IS NULL
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
