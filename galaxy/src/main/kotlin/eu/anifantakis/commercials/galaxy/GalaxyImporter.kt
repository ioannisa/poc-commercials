package eu.anifantakis.commercials.galaxy

import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Statement
import java.sql.Types
import java.time.LocalDate

/**
 * Galaxy (new ERP) → group-schema import engine. RECONCILIATION-FIRST:
 * most Galaxy documents of company 001 already exist as `contracts` rows
 * (the legacy console kept syncing from the ERP until the dump), so the
 * importer's primary job is match-and-stamp, inserting only what is new.
 * Measured baselines and every format/semantics fact: GALAXY-MATCHER.md §9.
 *
 * Wire-up mirrors migration/SenErpEnricher: an already-open [Connection], a
 * target [schema] (every statement fully-qualifies `$schema.table`), injected
 * [log]/[onStep], `apply=false` (dry-run) computes everything and writes
 * nothing. All writes are idempotent: stamps are fill-only, inserts are keyed
 * on the UNIQUE `galaxy_doc_key`/`galaxy_line_key`/`galaxy_id` columns, and a
 * re-run reports the work as already done instead of redoing it.
 *
 * Party roles (verified §9.3): `contracts.customer_id` is ALWAYS the payer.
 * On Εντολή docs the flat export's `custcode` is the agency (payer) and the
 * «Διαφημιζόμενος/Διαφημιστής» column the advertiser; on SEN-triangular docs
 * (9110) the roles are INVERTED. The advertiser linkage is kept in the
 * `galaxy_lines` mirror table ONLY - contract-level advertiser modeling is an
 * open app question (migration/contract-models-direct-vs-triangular.md §8).
 */
class GalaxyImporter(
    private val c: Connection,
    private val schema: String,
    private val log: (String) -> Unit = {},
    private val onStep: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
) {

    data class Config(
        /** galaxy2 flat-export folder (COMMERCIALENTRY.txt + dictionaries). */
        val galaxyDir: File,
        /**
         * OLD raw export's customer folder (customer.txt / TRADER.txt /
         * GXTRADERSITE.txt) - the FULL party dictionary. The galaxy2
         * CUSTOMER.csv is TOP-1000-capped (§9.6) and serves only as a
         * secondary source, so this should be provided until the uncapped
         * delivery arrives.
         */
        val oldExportDir: File?,
        /** Galaxy company to import - "001" ΙΚΑΡΟΣ → crete group. */
        val companyCode: String,
        /** Review CSV destination (written on dry-run too); null = skip. */
        val reviewOut: File?,
    )

    /** Counter block returned to the CLI/report - mutated through the phases. */
    class Summary {
        var rejectedRecords = 0
        var linesTotal = 0; var linesCompany = 0; var docsSeen = 0; var linesNoDocNumber = 0
        var partiesReferenced = 0; var partiesAlreadyStamped = 0; var partiesByCode = 0
        var partiesByVat = 0; var partiesInserted = 0; var partiesInsertedBare = 0
        var partiesAmbiguous = 0; var partiesConflict = 0
        var itemsReferenced = 0; var itemsAlreadyStamped = 0; var itemsStamped = 0
        var itemsShadowed = 0; var itemsInserted = 0
        var twinDocsSkipped = 0; var twinRowsSkipped = 0; var untwinned9010Docs = 0
        var docsExamined = 0; var docsAlreadyKeyed = 0; var docsMatched = 0
        var docsInserted = 0; var docLinesInserted = 0; var docsAmbiguous = 0
        var docsPayerUnresolved = 0; var docsExcludedFromReports = 0
        val reviews = mutableListOf<Review>()
    }

    /** One row of the human review list (also written to the review CSV). */
    data class Review(val kind: String, val key: String, val detail: String)

    // ────────────────────────────────────────────────────────────────────────
    // Internal working state
    // ────────────────────────────────────────────────────────────────────────

    /** What we know about one Galaxy party code, merged from the dictionaries. */
    /** One existing contract as a reconciliation candidate. */
    private data class ContractRow(val id: Long, val customerId: Long, val docType: Int?, val year: Int?)

    private data class Party(
        val code: String,
        val traderGxid: String?,
        val name: String?,
        val tin9: String?,
        val street: String?,
        val zip: String?,
        val phone: String?,
        val email: String?,
    )

    private data class Doc(
        val key: String,
        val docCode: String,
        val number: String,        // normalized (leading zeros stripped)
        val typeText: String,
        val family: DocFamily,
        val gift: Boolean,
        val electoral: Boolean,
        val date: LocalDate?,
        val payerCode: String?,    // resolved per §9.3 role semantics
        val advertiserCode: String?,
        val lines: List<FlatLine>,
    )

    private val db = DbSide()

    private inner class DbSide {
        /** customers: code → (id, vat9, galaxyId) */
        val custByCode = HashMap<String, Triple<Long, String?, String?>>()
        val custIdsByVat = HashMap<String, MutableList<Long>>()
        val custCodeById = HashMap<Long, String>()
        val custGxidById = HashMap<Long, String>()

        /** spot_types: digits(item_code) → ids; galaxy_id → id */
        val spotTypeByDigits = HashMap<String, MutableList<Long>>()
        val spotTypeByGxid = HashMap<String, Long>()

        /** contracts: normalized number → candidate rows; plus stamped keys */
        val contractsByNumber = HashMap<String, MutableList<ContractRow>>()
        val contractIdByGalaxyKey = HashMap<String, Long>()

        fun load() {
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, code, vat_number, galaxy_id FROM $schema.customers").use { rs ->
                    while (rs.next()) {
                        val id = rs.getLong(1)
                        val code = rs.getString(2)
                        val vat = GalaxyExports.normalizedTin(rs.getString(3))
                        custByCode[code] = Triple(id, vat, rs.getString(4))
                        custCodeById[id] = code
                        rs.getString(4)?.let { custGxidById[id] = it }
                        if (vat != null) custIdsByVat.getOrPut(vat) { mutableListOf() } += id
                    }
                }
                st.executeQuery("SELECT id, item_code, galaxy_id FROM $schema.spot_types").use { rs ->
                    while (rs.next()) {
                        val id = rs.getLong(1)
                        GalaxyExports.itemDigits(rs.getString(2))
                            ?.let { spotTypeByDigits.getOrPut(it) { mutableListOf() } += id }
                        rs.getString(3)?.let { spotTypeByGxid[it] = id }
                    }
                }
                // Older schemas predate galaxy_doc_key (added on the next
                // GroupDb.bootstrap(); the CLI runs it on --apply). A dry-run
                // against such a schema stays read-only and just sees no keys.
                val hasDocKey = st.executeQuery(
                    """
                    SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = '$schema' AND TABLE_NAME = 'contracts'
                      AND COLUMN_NAME = 'galaxy_doc_key'
                    """.trimIndent()
                ).use { rs -> rs.next() && rs.getLong(1) > 0 }
                if (!hasDocKey) log("⚠ $schema.contracts has no galaxy_doc_key yet - added on --apply (GroupDb bootstrap)")
                val keyCol = if (hasDocKey) "galaxy_doc_key" else "NULL"
                st.executeQuery(
                    "SELECT id, number, customer_id, doc_type, $keyCol, " +
                        "YEAR(COALESCE(entry_date, start_date)) FROM $schema.contracts"
                ).use { rs ->
                    while (rs.next()) {
                        val id = rs.getLong(1)
                        val num = GalaxyExports.normalizedDocNumber(rs.getString(2))
                        val docType = rs.getInt(4).takeUnless { rs.wasNull() }
                        val year = rs.getInt(6).takeUnless { rs.wasNull() }
                        if (num != null) {
                            contractsByNumber.getOrPut(num) { mutableListOf() } +=
                                ContractRow(id, rs.getLong(3), docType, year)
                        }
                        rs.getString(5)?.let { contractIdByGalaxyKey[it] = id }
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Entry point
    // ────────────────────────────────────────────────────────────────────────

    fun import(cfg: Config, apply: Boolean): Summary {
        val s = Summary()
        var stepsDone = 0
        fun step(label: String) {
            stepsDone = (stepsDone + 1).coerceAtMost(TOTAL_STEPS)
            onStep(stepsDone, TOTAL_STEPS, label)
            log("── $label")
        }

        step("Parse Galaxy exports")
        val flatTable = GalaxyExports.parse(fileIn(cfg.galaxyDir, "COMMERCIALENTRY.txt"), '\t')
        s.rejectedRecords += flatTable.rejected
        val allLines = flatTable.flatLines()
        s.linesTotal = allLines.size
        val lines = allLines.filter { it.companyCode == cfg.companyCode }
        s.linesCompany = lines.size
        log("flat export: ${s.linesTotal} lines, ${s.linesCompany} for company ${cfg.companyCode}" +
            (if (flatTable.rejected > 0) " (⚠ ${flatTable.rejected} rejected records)" else ""))
        require(lines.isNotEmpty()) {
            "No rows for company '${cfg.companyCode}' - companies present: " +
                allLines.map { it.companyCode }.distinct().sorted()
        }

        step("Load party dictionaries")
        val parties = loadParties(cfg, lines, s)

        step("Load target schema state")
        db.load()
        log("db: ${db.custByCode.size} customers, ${db.contractsByNumber.values.sumOf { it.size }} contracts, " +
            "${db.contractIdByGalaxyKey.size} already galaxy-keyed")

        step("Resolve parties (code → VAT → insert)")
        val partyCustomerId = resolveParties(lines, parties, s, apply)

        step("Resolve item catalog")
        val itemSpotType = resolveItems(lines, s, apply)

        step("Detect 9004↔9010 twins")
        val twinRows = detectTwins(lines, s)

        step("Reconcile documents")
        val docs = buildDocs(lines, twinRows, s)
        val docContractId = reconcileDocs(docs, partyCustomerId, itemSpotType, s, apply)

        step("Materialize galaxy_* mirror tables")
        if (apply) {
            materializeMirror(cfg, lines, parties, partyCustomerId, itemSpotType, twinRows, docs, docContractId)
        } else {
            log("mirror tables skipped [DRY RUN]")
        }

        step("Write review list")
        cfg.reviewOut?.let { writeReviewCsv(it, s) }

        onStep(TOTAL_STEPS, TOTAL_STEPS, "")
        return s
    }

    // ────────────────────────────────────────────────────────────────────────
    // Dictionaries
    // ────────────────────────────────────────────────────────────────────────

    private fun fileIn(dir: File, name: String): File =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: error("Missing $name in $dir")

    private fun optionalFileIn(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Merges the party dictionaries into one code → [Party] map. Primary: the
     * OLD full export (customer → TRADER join + primary GXTRADERSITE);
     * secondary: the capped galaxy2 CUSTOMER.csv (Galaxy-born parties);
     * last resort: the name as it appears on the flat lines themselves.
     */
    private fun loadParties(cfg: Config, lines: List<FlatLine>, s: Summary): Map<String, Party> {
        data class Site(val street: String?, val zip: String?, val phone: String?, val email: String?)

        val traderById = HashMap<String, Triple<String?, String?, String?>>() // gxid → (name, tin, distTitle)
        val siteByTrader = HashMap<String, Site>()
        val codeToTrader = HashMap<String, String>()

        cfg.oldExportDir?.let { dir ->
            val trader = GalaxyExports.parse(fileIn(dir, "TRADER.txt"), '\t')
            s.rejectedRecords += trader.rejected
            for (r in trader.rows) {
                val gxid = trader.value(r, "GXID")?.lowercase() ?: continue
                traderById[gxid] = Triple(
                    trader.value(r, "GXNAME"),
                    trader.value(r, "GXTIN"),
                    trader.value(r, "GXDISTTITLE"),
                )
            }
            val customer = GalaxyExports.parse(fileIn(dir, "customer.txt"), '\t')
            s.rejectedRecords += customer.rejected
            for (r in customer.rows) {
                val code = customer.value(r, "GXCODE") ?: continue
                val trdr = customer.value(r, "GXTRDRID")?.lowercase() ?: continue
                codeToTrader[code] = trdr
            }
            optionalFileIn(dir, "GXTRADERSITE.txt")?.let { f ->
                val sites = GalaxyExports.parse(f, '\t')
                s.rejectedRecords += sites.rejected
                for (r in sites.rows) {
                    val trdr = sites.value(r, "GXTRDRID")?.lowercase() ?: continue
                    val isMain = sites.value(r, "GXDESCRIPTION")?.contains("Κύριο") == true
                    if (isMain || trdr !in siteByTrader) {
                        val street = listOfNotNull(
                            sites.value(r, "GXSTREET"), sites.value(r, "GXSTREETNUMBER")
                        ).joinToString(" ").ifBlank { null }
                        siteByTrader[trdr] = Site(
                            street = street,
                            zip = sites.value(r, "GXPOSTALCODE"),
                            phone = sites.value(r, "GXPHONE1") ?: sites.value(r, "GXMOBILE"),
                            email = sites.value(r, "GXEMAIL"),
                        )
                    }
                }
            }
            log("old export: ${codeToTrader.size} customer codes, ${traderById.size} traders, ${siteByTrader.size} sites")
        } ?: log("⚠ no --old-export-dir: falling back to the TOP-1000-capped CUSTOMER.csv only")

        // Secondary: the capped galaxy2 CUSTOMER.csv (semicolon-delimited).
        val cappedByCode = HashMap<String, Triple<String?, String?, String?>>() // code → (trdrid, name, tin)
        optionalFileIn(cfg.galaxyDir, "CUSTOMER.csv")?.let { f ->
            val t = GalaxyExports.parse(f, ';')
            s.rejectedRecords += t.rejected
            for (r in t.rows) {
                val code = t.value(r, "custcode") ?: continue
                cappedByCode[code] = Triple(
                    t.value(r, "TRDRID")?.lowercase(),
                    t.value(r, "custname"),
                    t.value(r, "TIN"),
                )
            }
        }

        // Last resort: names as seen on the documents themselves.
        val nameOnLines = HashMap<String, String>()
        for (l in lines) {
            l.custName?.let { nameOnLines.putIfAbsent(l.custCode, it) }
            if (l.advCode != null && l.advName != null) nameOnLines.putIfAbsent(l.advCode, l.advName)
        }

        val referenced = buildSet {
            for (l in lines) { add(l.custCode); l.advCode?.let { add(it) } }
        }
        return referenced.associateWith { code ->
            val trdr = codeToTrader[code] ?: cappedByCode[code]?.first
            val traderRow = trdr?.let { traderById[it] }
            val site = trdr?.let { siteByTrader[it] }
            Party(
                code = code,
                traderGxid = trdr,
                name = traderRow?.first ?: cappedByCode[code]?.second ?: nameOnLines[code],
                tin9 = GalaxyExports.normalizedTin(traderRow?.second ?: cappedByCode[code]?.third),
                street = site?.street,
                zip = site?.zip,
                phone = site?.phone,
                email = site?.email,
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phase: parties
    // ────────────────────────────────────────────────────────────────────────

    /**
     * The measured waterfall (§9.5): code match → unique-VAT match → insert.
     * Returns code → customers.id for every RESOLVED party. On dry-run the
     * would-be inserts get synthetic negative ids so document reconciliation
     * can still be counted.
     */
    private fun resolveParties(
        lines: List<FlatLine>,
        parties: Map<String, Party>,
        s: Summary,
        apply: Boolean,
    ): Map<String, Long> {
        val resolved = HashMap<String, Long>()
        data class GxidStamp(val customerId: Long, val gxid: String, val code: String)
        var stamps = mutableListOf<GxidStamp>()
        val inserts = mutableListOf<Party>()

        s.partiesReferenced = parties.size
        for ((code, p) in parties) {
            val byCode = db.custByCode[code]
            if (byCode != null) {
                val (id, _, existingGxid) = byCode
                resolved[code] = id
                when {
                    existingGxid == null -> {
                        // Code match; stampable only when the dictionary knows
                        // the trader UUID (a handful of codes are name-only).
                        s.partiesByCode++
                        p.traderGxid?.let { stamps += GxidStamp(id, it, code) }
                    }
                    p.traderGxid != null && !existingGxid.equals(p.traderGxid, true) -> {
                        s.partiesConflict++
                        s.reviews += Review("party-conflict", code,
                            "customers.id=$id galaxy_id=$existingGxid but dictionary says ${p.traderGxid}")
                    }
                    else -> s.partiesAlreadyStamped++
                }
                continue
            }
            val vatHits = p.tin9?.let { db.custIdsByVat[it] }.orEmpty()
            when {
                vatHits.size == 1 -> {
                    val id = vatHits.single()
                    resolved[code] = id
                    s.partiesByVat++
                    val existing = db.custGxidById[id]
                    when {
                        p.traderGxid == null || existing.equals(p.traderGxid, true) -> Unit
                        existing == null -> stamps += GxidStamp(id, p.traderGxid, code)
                        else -> {
                            s.partiesConflict++
                            s.reviews += Review("party-conflict", code,
                                "ΑΦΜ match customers.id=$id galaxy_id=$existing but dictionary says ${p.traderGxid}")
                        }
                    }
                }
                vatHits.size > 1 -> {
                    s.partiesAmbiguous++
                    s.reviews += Review("party-ambiguous-vat", code,
                        "ΑΦΜ ${p.tin9} matches customers ${vatHits.map { db.custCodeById[it] }} - resolve by hand")
                }
                else -> inserts += p
            }
        }

        // One customers row holds ONE galaxy_id. When two Galaxy codes claim
        // the same row (a code-match and a VAT-match landing together - i.e. a
        // duplicate in the customer file), stamp NEITHER: which trader the row
        // really is takes a human eye.
        val multiClaimed = stamps.groupBy { it.customerId }
            .filterValues { claims -> claims.map { it.gxid.lowercase() }.distinct().size > 1 }
        if (multiClaimed.isNotEmpty()) {
            s.partiesConflict += multiClaimed.size
            for ((id, claims) in multiClaimed) {
                s.reviews += Review("party-multi-claim", db.custCodeById[id] ?: "$id",
                    "customers.id=$id claimed by Galaxy codes ${claims.map { it.code }} - resolve by hand")
            }
            stamps = stamps.filterNot { it.customerId in multiClaimed.keys }.toMutableList()
        }

        if (stamps.isNotEmpty()) {
            log("stamping galaxy_id on ${stamps.size} matched customers" + dry(apply))
            if (apply) {
                c.prepareStatement(
                    "UPDATE $schema.customers SET galaxy_id = ? WHERE id = ? AND galaxy_id IS NULL"
                ).use { ps ->
                    var n = 0
                    for (st in stamps) {
                        ps.setString(1, st.gxid); ps.setLong(2, st.customerId); ps.addBatch()
                        if (++n % BATCH == 0) ps.executeBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        if (inserts.isNotEmpty()) {
            log("inserting ${inserts.size} new customers (${inserts.count { it.tin9 != null }} with ΑΦΜ)" + dry(apply))
            if (apply) {
                c.prepareStatement(
                    """
                    INSERT INTO $schema.customers
                        (code, name, vat_number, phone, email, address_street, address_zip, synthetic, galaxy_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    for (p in inserts) {
                        ps.setString(1, p.code)
                        ps.setString(2, (p.name ?: p.code).take(128))
                        setNullable(ps, 3, p.tin9?.take(16))
                        setNullable(ps, 4, p.phone?.take(32))
                        setNullable(ps, 5, p.email?.take(128))
                        setNullable(ps, 6, p.street?.take(160))
                        setNullable(ps, 7, p.zip?.take(16))
                        setNullable(ps, 8, p.traderGxid)
                        ps.executeUpdate()
                        ps.generatedKeys.use { k -> if (k.next()) resolved[p.code] = k.getLong(1) }
                    }
                }
            } else {
                // Synthetic ids keep the document phase countable on dry-run.
                inserts.forEachIndexed { i, p -> resolved[p.code] = -(i + 1L) }
            }
            s.partiesInserted = inserts.count { it.name != null || it.tin9 != null }
            s.partiesInsertedBare = inserts.size - s.partiesInserted
            inserts.filter { it.name == null && it.tin9 == null }.forEach {
                s.reviews += Review("party-bare-insert", it.code, "no dictionary info - inserted with code as name")
            }
        }
        return resolved
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phase: item catalog
    // ────────────────────────────────────────────────────────────────────────

    /** Returns item GXID → spot_types.id (existing, stamped or inserted). */
    private fun resolveItems(lines: List<FlatLine>, s: Summary, apply: Boolean): Map<String, Long> {
        data class Item(val gxid: String, val code: String?, val name: String?)
        val items = lines.asSequence()
            .filter { it.itemId != null }
            .map { Item(it.itemId!!, it.itemCode, it.itemName?.lineSequence()?.first()?.trim()) }
            .distinctBy { it.gxid }
            .toList()
        s.itemsReferenced = items.size

        val resolved = HashMap<String, Long>()
        val stamps = mutableListOf<Pair<Long, String>>()
        val inserts = mutableListOf<Item>()
        val claimed = db.spotTypeByGxid.values.toMutableSet()

        for (item in items) {
            val pre = db.spotTypeByGxid[item.gxid]
            if (pre != null) { resolved[item.gxid] = pre; s.itemsAlreadyStamped++; continue }
            val digitHits = GalaxyExports.itemDigits(item.code)?.let { d -> db.spotTypeByDigits[d] }.orEmpty()
            when {
                digitHits.size == 1 -> {
                    val id = digitHits.single()
                    resolved[item.gxid] = id
                    if (id in claimed) {
                        // Two Galaxy items share one of our item codes: the row's
                        // UNIQUE galaxy_id already belongs to the first - the
                        // second still resolves for line linkage, just unstamped.
                        s.itemsShadowed++
                    } else {
                        claimed += id; stamps += id to item.gxid; s.itemsStamped++
                    }
                }
                digitHits.size > 1 -> {
                    s.reviews += Review("item-ambiguous", item.code ?: item.gxid,
                        "item code digits match ${digitHits.size} spot_types rows - resolve by hand")
                }
                else -> inserts += item
            }
        }

        if (stamps.isNotEmpty()) {
            log("stamping galaxy_id on ${stamps.size} spot_types" + dry(apply))
            if (apply) {
                c.prepareStatement(
                    "UPDATE $schema.spot_types SET galaxy_id = ? WHERE id = ? AND galaxy_id IS NULL"
                ).use { ps ->
                    for ((id, gxid) in stamps) { ps.setString(1, gxid); ps.setLong(2, id); ps.addBatch() }
                    ps.executeBatch()
                }
            }
        }
        if (inserts.isNotEmpty()) {
            log("inserting ${inserts.size} new spot_types (Galaxy-only items)" + dry(apply))
            s.itemsInserted = inserts.size
            if (apply) {
                c.prepareStatement(
                    "INSERT INTO $schema.spot_types (name, item_code, galaxy_id) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    for (it in inserts) {
                        ps.setString(1, (it.name ?: it.code ?: "").take(160))
                        setNullable(ps, 2, it.code?.take(32))
                        ps.setString(3, it.gxid)
                        ps.executeUpdate()
                        ps.generatedKeys.use { k -> if (k.next()) resolved[it.gxid] = k.getLong(1) }
                    }
                }
            }
        }
        return resolved
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phase: twins
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Native triangular flow issues the SAME money twice: an Εντολή (9004,
     * to the agency, advertiser in the extra column) and a Τριγωνικό (9010,
     * to the advertiser). 791/971 of the 9010 rows are exact copies (§9.3).
     * Returns the set of twinned 9010 [FlatLine]s (identity-based).
     */
    private fun detectTwins(lines: List<FlatLine>, s: Summary): Set<FlatLine> {
        val orderSigs = lines.asSequence()
            .filter { familyOf(it.type) == DocFamily.ORDER && it.advCode != null }
            .map { twinSignature(it.advCode, it) }
            .toHashSet()
        val twins = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FlatLine, Boolean>())
        for (l in lines) {
            if (familyOf(l.type) == DocFamily.TRIANGULAR_NATIVE && twinSignature(l.custCode, l) in orderSigs) twins += l
        }
        s.twinRowsSkipped = twins.size
        return twins
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phase: documents
    // ────────────────────────────────────────────────────────────────────────

    private fun buildDocs(lines: List<FlatLine>, twinRows: Set<FlatLine>, s: Summary): List<Doc> {
        val byKey = LinkedHashMap<String, MutableList<FlatLine>>()
        for (l in lines) {
            val key = l.docKey
            if (key == null) { s.linesNoDocNumber++; continue }
            byKey.getOrPut(key) { mutableListOf() } += l
        }
        s.docsSeen = byKey.size

        val docs = mutableListOf<Doc>()
        for ((key, docLines) in byKey) {
            val head = docLines.first()
            val family = familyOf(head.type)

            if (family == DocFamily.TRIANGULAR_NATIVE) {
                if (docLines.all { it in twinRows }) { s.twinDocsSkipped++; continue }
                s.untwinned9010Docs++
                s.reviews += Review("untwinned-9010", key,
                    "native Τριγωνικό with no exact Εντολή twin (${docLines.size} lines, " +
                        "cust=${head.custCode} ${head.custName ?: ""}) - imported flagged, verify no double count")
            }

            val typeUpper = greekUpper(head.type ?: "")
            val payer = payerOf(family, head.custCode, head.advCode)
            val advertiser = advertiserOf(family, head.custCode, head.advCode)
            docs += Doc(
                key = key,
                docCode = head.docCode,
                number = key.substringAfterLast(':'),
                typeText = head.type ?: "",
                family = family,
                gift = "ΔΩΡ" in typeUpper,
                electoral = "ΕΚΛΟΓ" in typeUpper,
                date = head.date,
                payerCode = payer,
                advertiserCode = advertiser,
                lines = docLines,
            )
        }
        return docs
    }

    /** Legacy dotid families (sen_sdt) so stamped/inserted docs stay queryable
     *  alongside the migrated ones. */
    private fun legacyDocType(d: Doc): Int? = when (d.family) {
        DocFamily.CONTRACT -> when {
            d.gift -> 474           // 105 ΣΥΜΒΟΛΑΙΟ ΠΕΛΑΤΗ (Δώρα)
            d.electoral -> 545      // 108 ΣΥΜΒΟΛΑΙΟ ΠΕΛΑΤΗ ΕΚΛΟΓΩΝ
            else -> 450             // 101 ΣΥΜΒΟΛΑΙΟ ΠΕΛΑΤΗ
        }
        DocFamily.ORDER -> if (d.gift) 494 else 451   // 106 / 104 ΕΝΤΟΛΗ ΔΙΑΦΗΜΙΣΗΣ
        DocFamily.TRIANGULAR_SEN, DocFamily.TRIANGULAR_NATIVE -> 426   // 150 ΤΡΙΓΩΝΙΚΟ ΠΕΛΑΤΗ
        DocFamily.BOOKKEEPING -> null
    }

    private val docTypeFamilies: Map<DocFamily, Set<Int>> = mapOf(
        DocFamily.CONTRACT to setOf(450, 422, 545, 547, 474, 617, 654, 454),
        DocFamily.ORDER to setOf(451, 427, 494),
        DocFamily.TRIANGULAR_SEN to setOf(426),
        DocFamily.TRIANGULAR_NATIVE to setOf(426),
        DocFamily.BOOKKEEPING to setOf(425, 448, 452),
    )

    /** Returns doc key → contract id for every matched or inserted document. */
    private fun reconcileDocs(
        docs: List<Doc>,
        partyCustomerId: Map<String, Long>,
        itemSpotType: Map<String, Long>,
        s: Summary,
        apply: Boolean,
    ): Map<String, Long> {
        val result = HashMap<String, Long>()
        data class Stamp(val contractId: Long, val doc: Doc)
        val stamps = mutableListOf<Stamp>()
        val inserts = mutableListOf<Doc>()
        // A contract can carry ONE galaxy_doc_key: once claimed (in the DB or
        // earlier in this run - doc numbers repeat across series) it stops
        // being a candidate, deterministically, so re-runs converge.
        val claimed = db.contractIdByGalaxyKey.values.toHashSet()

        val pending = mutableListOf<Pair<Doc, Long>>()   // doc + resolved payer id
        for (d in docs) {
            s.docsExamined++
            val pre = db.contractIdByGalaxyKey[d.key]
            if (pre != null) { result[d.key] = pre; s.docsAlreadyKeyed++; continue }

            val payerId = d.payerCode?.let { partyCustomerId[it] }
            if (payerId == null) {
                s.docsPayerUnresolved++
                s.reviews += Review("doc-payer-unresolved", d.key,
                    "payer '${d.payerCode}' is ambiguous/unresolved - document skipped")
                continue
            }
            pending += d to payerId
        }

        fun candidatesFor(d: Doc, payerId: Long): List<ContractRow> {
            var candidates = db.contractsByNumber[d.number].orEmpty()
                .filter { it.customerId == payerId && it.id !in claimed }
            if (candidates.size > 1) {
                val fam = docTypeFamilies[d.family].orEmpty()
                val tightened = candidates.filter { it.docType != null && it.docType in fam }
                if (tightened.size == 1) candidates = tightened
            }
            if (candidates.size > 1 && d.date != null) {
                // Legacy doc numbers restart per year («number 1» exists once a
                // year) - the document date breaks most of those ties.
                val sameYear = candidates.filter { it.year != null && it.year == d.date.year }
                if (sameYear.size == 1) candidates = sameYear
            }
            return candidates
        }

        // Fixed-point matching: claiming a contract can turn a competitor's
        // ambiguity into a unique match, so sweep until a pass changes nothing.
        // One run then reaches the same state any number of re-runs would.
        var progress = true
        while (progress) {
            progress = false
            val it = pending.iterator()
            while (it.hasNext()) {
                val (d, payerId) = it.next()
                val candidates = candidatesFor(d, payerId)
                if (candidates.size == 1) {
                    val id = candidates.single().id
                    result[d.key] = id
                    claimed += id
                    stamps += Stamp(id, d)
                    s.docsMatched++
                    it.remove()
                    progress = true
                }
            }
        }
        for ((d, payerId) in pending) {
            val candidates = candidatesFor(d, payerId)
            if (candidates.isEmpty()) {
                inserts += d
            } else {
                s.docsAmbiguous++
                s.reviews += Review("doc-ambiguous", d.key,
                    "number ${d.number} + payer ${d.payerCode} (${d.date?.year ?: "no date"}) matches contracts " +
                        candidates.map { it.id } + " - resolve by hand")
            }
        }

        if (stamps.isNotEmpty()) {
            log("stamping galaxy keys on ${stamps.size} matched contracts" + dry(apply))
            if (apply) {
                c.prepareStatement(
                    """
                    UPDATE $schema.contracts
                    SET galaxy_doc_key = ?, galaxy_number = COALESCE(galaxy_number, ?),
                        entry_date = COALESCE(entry_date, ?), is_gift = (is_gift OR ?)
                    WHERE id = ? AND galaxy_doc_key IS NULL
                    """.trimIndent()
                ).use { ps ->
                    var n = 0
                    for ((id, d) in stamps) {
                        ps.setString(1, d.key)
                        d.number.toLongOrNull()
                            ?.let { ps.setLong(2, it) } ?: ps.setNull(2, Types.BIGINT)
                        d.date?.let { ps.setDate(3, java.sql.Date.valueOf(it)) } ?: ps.setNull(3, Types.DATE)
                        ps.setBoolean(4, d.gift)
                        ps.setLong(5, id)
                        ps.addBatch()
                        if (++n % BATCH == 0) ps.executeBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        if (inserts.isNotEmpty()) {
            val excluded = inserts.count { excludeFromReports(it) }
            s.docsExcludedFromReports = excluded
            log("inserting ${inserts.size} new contracts ($excluded flagged exclude_from_reports)" + dry(apply))
            s.docsInserted = inserts.size
            s.docLinesInserted = inserts.sumOf { it.lines.size }
            if (apply) {
                val insertContract = c.prepareStatement(
                    """
                    INSERT INTO $schema.contracts
                        (number, doc_type, is_gift, exclude_from_reports, customer_id,
                         entry_date, synthetic, galaxy_doc_key, galaxy_number)
                    VALUES (?, ?, ?, ?, ?, ?, FALSE, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                )
                val insertLine = c.prepareStatement(
                    """
                    INSERT INTO $schema.contract_lines
                        (contract_id, line_no, spot_type_id, desired_qty, galaxy_line_key)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                )
                insertContract.use { pc ->
                    insertLine.use { pl ->
                        for (d in inserts) {
                            pc.setString(1, d.number)
                            legacyDocType(d)?.let { pc.setInt(2, it) } ?: pc.setNull(2, Types.INTEGER)
                            pc.setBoolean(3, d.gift)
                            pc.setBoolean(4, excludeFromReports(d))
                            pc.setLong(5, partyCustomerId.getValue(d.payerCode!!))
                            d.date?.let { pc.setDate(6, java.sql.Date.valueOf(it)) } ?: pc.setNull(6, Types.DATE)
                            pc.setString(7, d.key)
                            d.number.toLongOrNull()
                                ?.let { pc.setLong(8, it) } ?: pc.setNull(8, Types.BIGINT)
                            pc.executeUpdate()
                            val contractId = pc.generatedKeys.use { k -> k.next(); k.getLong(1) }
                            result[d.key] = contractId
                            d.lines.forEachIndexed { i, l ->
                                pl.setLong(1, contractId)
                                pl.setInt(2, i + 1)
                                l.itemId?.let { itemSpotType[it] }
                                    ?.let { pl.setLong(3, it) } ?: pl.setNull(3, Types.BIGINT)
                                pl.setInt(4, l.spots?.toInt() ?: 0)
                                pl.setString(5, "${d.key}:${i + 1}")
                                pl.addBatch()
                            }
                            pl.executeBatch()
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Bookkeeping artifacts (Κλείσιμο Εκκρεμοτήτων, Διόρθωση, Ακύρωση) and the
     * value-drifted untwinned native Τριγωνικά enter the archive but stay off
     * the reports - user decision 2026-07-19 («όλα με σήμανση»).
     */
    private fun excludeFromReports(d: Doc): Boolean =
        d.family == DocFamily.BOOKKEEPING || d.family == DocFamily.TRIANGULAR_NATIVE

    // ────────────────────────────────────────────────────────────────────────
    // Phase: mirror tables
    // ────────────────────────────────────────────────────────────────────────

    /**
     * The inspectable Galaxy archive inside the group schema (rebuild pattern,
     * like the migration's sen_* mirrors). `galaxy_lines` is also where the
     * ADVERTISER linkage lives until the app grows a contract-level model.
     */
    private fun materializeMirror(
        cfg: Config,
        lines: List<FlatLine>,
        parties: Map<String, Party>,
        partyCustomerId: Map<String, Long>,
        itemSpotType: Map<String, Long>,
        twinRows: Set<FlatLine>,
        docs: List<Doc>,
        docContractId: Map<String, Long>,
    ) {
        val docByKey = docs.associateBy { it.key }
        c.createStatement().use { st ->
            fun rebuild(name: String, ddl: String) {
                st.executeUpdate("DROP TABLE IF EXISTS $schema.$name")
                st.executeUpdate("CREATE TABLE $schema.$name ($ddl) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
            }

            rebuild(
                "galaxy_parties",
                """
                code VARCHAR(32) PRIMARY KEY,
                trader_gxid VARCHAR(36) NULL,
                name VARCHAR(160) NULL,
                tin VARCHAR(16) NULL,
                customer_id BIGINT NULL
                """.trimIndent()
            )
            c.prepareStatement(
                "INSERT INTO $schema.galaxy_parties VALUES (?, ?, ?, ?, ?)"
            ).use { ps ->
                var n = 0
                for ((code, p) in parties) {
                    ps.setString(1, code.take(32))
                    setNullable(ps, 2, p.traderGxid)
                    setNullable(ps, 3, p.name?.take(160))
                    setNullable(ps, 4, p.tin9)
                    partyCustomerId[code]?.takeIf { it > 0 }
                        ?.let { ps.setLong(5, it) } ?: ps.setNull(5, Types.BIGINT)
                    ps.addBatch()
                    if (++n % BATCH == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }

            rebuild(
                "galaxy_items",
                """
                gxid VARCHAR(36) PRIMARY KEY,
                item_code VARCHAR(32) NULL,
                name VARCHAR(160) NULL,
                spot_type_id BIGINT NULL
                """.trimIndent()
            )
            c.prepareStatement("INSERT INTO $schema.galaxy_items VALUES (?, ?, ?, ?)").use { ps ->
                val seen = HashSet<String>()
                for (l in lines) {
                    val gxid = l.itemId ?: continue
                    if (!seen.add(gxid)) continue
                    ps.setString(1, gxid)
                    setNullable(ps, 2, l.itemCode?.take(32))
                    setNullable(ps, 3, l.itemName?.lineSequence()?.first()?.trim()?.take(160))
                    itemSpotType[gxid]?.let { ps.setLong(4, it) } ?: ps.setNull(4, Types.BIGINT)
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            rebuild(
                "galaxy_lines",
                """
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                doc_key VARCHAR(64) NULL,
                doc_code VARCHAR(16) NOT NULL,
                doc_number VARCHAR(32) NULL,
                doc_type_text VARCHAR(128) NULL,
                doc_date DATE NULL,
                line_ordinal INT NOT NULL,
                cust_code VARCHAR(32) NOT NULL,
                adv_code VARCHAR(32) NULL,
                payer_code VARCHAR(32) NULL,
                advertiser_code VARCHAR(32) NULL,
                payer_customer_id BIGINT NULL,
                advertiser_customer_id BIGINT NULL,
                contract_id BIGINT NULL,
                item_gxid VARCHAR(36) NULL,
                item_code VARCHAR(32) NULL,
                item_text TEXT NULL,
                seconds DECIMAL(10,2) NULL,
                spots DECIMAL(12,2) NULL,
                value DECIMAL(14,2) NULL,
                comments TEXT NULL,
                twin_skipped BOOLEAN NOT NULL DEFAULT FALSE,
                KEY idx_galaxy_lines_doc (doc_key),
                KEY idx_galaxy_lines_contract (contract_id),
                KEY idx_galaxy_lines_advertiser (advertiser_customer_id)
                """.trimIndent()
            )
            c.prepareStatement(
                """
                INSERT INTO $schema.galaxy_lines
                    (doc_key, doc_code, doc_number, doc_type_text, doc_date, line_ordinal,
                     cust_code, adv_code, payer_code, advertiser_code,
                     payer_customer_id, advertiser_customer_id, contract_id,
                     item_gxid, item_code, item_text, seconds, spots, value, comments, twin_skipped)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                var n = 0
                val ordinal = HashMap<String, Int>()
                for (l in lines) {
                    val key = l.docKey
                    val d = key?.let { docByKey[it] }
                    ps.setString(2, l.docCode)
                    setNullable(ps, 1, key)
                    setNullable(ps, 3, l.docNumber)
                    setNullable(ps, 4, l.type?.take(128))
                    l.date?.let { ps.setDate(5, java.sql.Date.valueOf(it)) } ?: ps.setNull(5, Types.DATE)
                    ps.setInt(6, key?.let { ordinal.merge(it, 1, Int::plus) } ?: 1)
                    ps.setString(7, l.custCode.take(32))
                    setNullable(ps, 8, l.advCode?.take(32))
                    setNullable(ps, 9, d?.payerCode?.take(32))
                    setNullable(ps, 10, d?.advertiserCode?.take(32))
                    d?.payerCode?.let { partyCustomerId[it] }?.takeIf { it > 0 }
                        ?.let { ps.setLong(11, it) } ?: ps.setNull(11, Types.BIGINT)
                    d?.advertiserCode?.let { partyCustomerId[it] }?.takeIf { it > 0 }
                        ?.let { ps.setLong(12, it) } ?: ps.setNull(12, Types.BIGINT)
                    key?.let { docContractId[it] }
                        ?.let { ps.setLong(13, it) } ?: ps.setNull(13, Types.BIGINT)
                    setNullable(ps, 14, l.itemId)
                    setNullable(ps, 15, l.itemCode?.take(32))
                    setNullable(ps, 16, l.itemName)
                    setDecimal(ps, 17, l.seconds)
                    setDecimal(ps, 18, l.spots)
                    setDecimal(ps, 19, l.value)
                    setNullable(ps, 20, l.comments)
                    ps.setBoolean(21, l in twinRows)
                    ps.addBatch()
                    if (++n % BATCH == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }

            // Document-type dictionary (domain 1 = the sales/contract domain;
            // GXCODEs repeat across domains - §9.2).
            optionalFileIn(cfg.galaxyDir, "GXCOMMENTRYTYPE.txt")?.let { f ->
                val t = GalaxyExports.parse(f, '\t')
                rebuild(
                    "galaxy_doc_types",
                    "code VARCHAR(16) PRIMARY KEY, description VARCHAR(160) NULL, kind INT NULL"
                )
                c.prepareStatement("INSERT IGNORE INTO $schema.galaxy_doc_types VALUES (?, ?, ?)").use { ps ->
                    for (r in t.rows) {
                        if (t.value(r, "GXDOMAIN") != "1") continue
                        val code = t.value(r, "GXCODE") ?: continue
                        ps.setString(1, code.take(16))
                        setNullable(ps, 2, t.value(r, "GXDESCRIPTION")?.take(160))
                        t.value(r, "GXCOMMERCENTRYKIND")?.toIntOrNull()
                            ?.let { ps.setInt(3, it) } ?: ps.setNull(3, Types.INTEGER)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
        log("galaxy_* mirror tables rebuilt (${lines.size} lines archived)")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Review CSV + small helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun writeReviewCsv(file: File, s: Summary) {
        if (s.reviews.isEmpty()) {
            log("review list is empty - ${file.name} not written")
            return
        }
        // BOM so Excel opens the Greek as UTF-8.
        file.writeText(
            "\uFEFF" + "kind;key;detail\n" + s.reviews.joinToString("\n") { r ->
                listOf(r.kind, r.key, r.detail).joinToString(";") { it.replace(';', ',').replace('\n', ' ') }
            } + "\n"
        )
        log("review list: ${s.reviews.size} entries → $file")
    }

    private fun dry(apply: Boolean) = if (apply) "" else " [DRY RUN]"

    private fun setNullable(ps: java.sql.PreparedStatement, i: Int, v: String?) {
        if (v != null) ps.setString(i, v) else ps.setNull(i, Types.VARCHAR)
    }

    private fun setDecimal(ps: java.sql.PreparedStatement, i: Int, v: BigDecimal?) {
        if (v != null) ps.setBigDecimal(i, v) else ps.setNull(i, Types.DECIMAL)
    }

    companion object {
        private const val BATCH = 500
        private const val TOTAL_STEPS = 9

        /**
         * Uppercase + strip Greek accents, so `Δώρα`/`ΔΩΡΟ` both hit "ΔΩΡ".
         * Kotlin's uppercase() keeps the tonos - Greek caps drop it by
         * convention. (Same trick as migration/SenExports.greekUpper.)
         */
        internal fun greekUpper(value: String): String {
            val map = mapOf(
                'Ά' to 'Α', 'Έ' to 'Ε', 'Ή' to 'Η', 'Ί' to 'Ι',
                'Ό' to 'Ο', 'Ύ' to 'Υ', 'Ώ' to 'Ω', 'Ϊ' to 'Ι', 'Ϋ' to 'Υ',
            )
            return value.uppercase().map { map[it] ?: it }.joinToString("")
        }

        /**
         * Classifies a flat-export `Type` text into a document family
         * (§9.2/§9.3). Bookkeeping wins first: «Ακύρωση Συμβολαίο/Εντολής»
         * and «Διόρθωση ... συμβολαίων» would otherwise false-match the
         * contract/order keywords they contain.
         */
        internal fun familyOf(type: String?): DocFamily {
            val t = greekUpper(type ?: "")
            return when {
                "ΑΚΥΡΩΣ" in t || "ΚΛΕΙΣΙΜΟ" in t || "ΔΙΟΡΘΩΣ" in t -> DocFamily.BOOKKEEPING
                "ΤΡΙΓΩΝΙΚ" in t && "SEN" in t -> DocFamily.TRIANGULAR_SEN
                "ΤΡΙΓΩΝΙΚ" in t -> DocFamily.TRIANGULAR_NATIVE
                "ΕΝΤΟΛΗ" in t -> DocFamily.ORDER
                "ΣΥΜΒΟΛΑΙ" in t -> DocFamily.CONTRACT
                else -> DocFamily.BOOKKEEPING
            }
        }

        /**
         * §9.3 role semantics: `custcode` is the PAYER everywhere except the
         * SEN-triangular docs, where the «Διαφημιζόμενος/Διαφημιστής» column
         * holds the agency (the payer) and `custcode` the advertiser.
         */
        internal fun payerOf(family: DocFamily, custCode: String, advCode: String?): String =
            if (family == DocFamily.TRIANGULAR_SEN) advCode ?: custCode else custCode

        internal fun advertiserOf(family: DocFamily, custCode: String, advCode: String?): String? =
            when (family) {
                DocFamily.TRIANGULAR_SEN, DocFamily.TRIANGULAR_NATIVE -> custCode
                else -> advCode
            }

        /**
         * The 9004↔9010 twin fingerprint: same advertiser, date, duration and
         * value means the SAME money issued twice (§9.3 double-count trap).
         */
        internal fun twinSignature(who: String?, l: FlatLine): String = listOf(
            who ?: "",
            l.date?.toString() ?: "",
            l.seconds?.stripTrailingZeros()?.toPlainString() ?: "",
            l.value?.stripTrailingZeros()?.toPlainString() ?: "",
        ).joinToString("|")
    }
}

/** Galaxy document families - drives roles, twin handling and doc_type mapping. */
internal enum class DocFamily { CONTRACT, ORDER, TRIANGULAR_SEN, TRIANGULAR_NATIVE, BOOKKEEPING }
