# GALAXY-MATCHER — Galaxy ERP → station DB linkage

**Purpose of this file:** complete handoff of the Galaxy export analysis (performed
2026-07-10) so the next agent can build the customer/contract importer WITHOUT
re-deriving anything. Everything below was verified programmatically against the
sample export and against the live station DB — each claim carries its numbers.

**Business context:** the app is autonomous; the legacy Oracle/SEN ERP is retired
(its data was merged during migration — see `migration/legacy-schema.md`). Galaxy
(SingularLogic) is the client's NEW ERP and the **go-forward master for customers
and contracts**. We will receive periodic raw exports from it; our app schedules
the spots. Galaxy ids are UUID strings; our tables carry `galaxy_id` columns
(already in place — see §7) as the linkage.

---

## 1. The sample export

Location analyzed: `/Users/ioannisanif/Downloads/ctv/ss/galaxy/`

| File | Rows¹ | Cols | Header | Encoding | State |
|---|---|---|---|---|---|
| `COMMERCIALENTRY.txt` | 12,723 | 137 | **NO** | cp1253 | OK, but columns had to be inferred (§3) |
| `CommercEntryLines.txt` | 23,203 | 187 | yes | **ASCII — Greek destroyed** | every Greek char is a literal `?` (§6.1) |
| `customer/customer.txt` | 3,137 | 89 | yes | cp1253 | OK |
| `customer/TRADER.txt` | 4,091 | 81 | yes | cp1253 | OK |
| `customer/CUSTOMERSITE.txt` | 3,283 | 82 | yes | cp1253 | OK |
| `customer/GXTRADERSITE.txt` | 4,242 | 40 | yes | cp1253 | OK |
| `customer/ITEM.txt` | 239 | 106 | yes | cp1253 | OK |
| `customer/GXITEMCOMPANYPROP.txt` | 143 | 129 | yes | cp1253 | OK |

¹ data rows (header excluded where present). Column counts are **consistent on
every row** of every file — no reassembly needed (unlike the first SEN delivery).

### Parsing rules (all verified)

- Tab-delimited, `\r\n` line endings, the literal string `NULL` means SQL NULL.
- Decode **cp1253** (Windows Greek). `ISO-8859-7` is close but cp1253 matched all
  samples («Απ' Ευθείας», «Κοκοσάλη Ελένη», «Φ.Α.Ε. ΑΘΗΝΩΝ» decode cleanly).
- **Money/decimal format:** decimal(?,12) exported with DOTS AS THOUSANDS
  separators and no decimal comma. Parse: strip dots → integer → ÷ 10¹².
  - `1.000.000.000.000` → 1.0 (a currency rate)
  - `6.168.300.000.000.000` → 6,168.30 €
  - Cross-checked against a real line: qty 870 × 0.30 €/spot = 261.00 € net. ✔
- Quoting: embedded double quotes appear CSV-style doubled inside quoted fields
  (`"Διαφημίσεις ""Καλό Μεσημέρι""  Τ"`) — strip outer quotes, unescape `""`.

---

## 2. Entity model and verified joins

```
TRADER (identity: name, ΑΦΜ)                    ← the real-world party
  ├─ customer        (GXTRDRID → TRADER.GXID, 100%)   commercial role + CODE
  │    └─ CUSTOMERSITE (GXCUSTID → customer.GXID 100%,
  │                     GXTRDSID → GXTRADERSITE.GXID 100%)
  └─ GXTRADERSITE    (GXTRDRID → TRADER.GXID, 100%)   addresses (many per trader)

ITEM (product catalog, 239)
  └─ GXITEMCOMPANYPROP (GXITEMID → ITEM.GXID, 100%)   per-company code = 73.xxx

COMMERCIALENTRY (document header, 12,723)
  └─ CommercEntryLines (GXCENTID → CE.f0, 100%;
                        GXTENTID → CE.f2, 100%;        ← TRADEENTRY id, table NOT exported
                        GXITEMID → ITEM.GXID, 97.7%;
                        GXITCPID → GXITEMCOMPANYPROP.GXID, 97.7%)
```

The 2.3% of lines with no ITEM match reference items absent from this export cut
(all have `GXLINETYPE = 1` like the rest); treat as "unknown item" on import, do
not fail.

Mapping to our tables: `TRADER/customer` → `customers` · `COMMERCIALENTRY` →
`contracts` · `CommercEntryLines` → `contract_lines` · `ITEM` → `spot_types` ·
`GXTRADERSITE` → the address columns / future contact table.

---

## 3. COMMERCIALENTRY column map (INFERRED — no header in the file)

⚠️ The file has no header row. The map below was inferred by joining every column
against the other files' id sets and by value profiling on all 12,723 rows.
**A header (or column list) has been requested from the client — reconfirm this
map when it arrives before hard-coding indexes.**

| Col | Meaning | Evidence |
|---|---|---|
| f0 | **GXID** (PK) | lines.GXCENTID hits it 100% |
| f1 | revision number (likely GXREVNUM) | small ints; distribution `1×7568, 3×3912, 2×836, 4×207, 5×145, …13×2` |
| f2 | **TRADEENTRY GXID** | unique per row; lines.GXTENTID hits it 100%; the TRADEENTRY table itself is NOT in the export |
| f3–f6 | constant UUIDs (company / branch / fiscal setup) | identical on every row |
| f7 | 40 distinct UUIDs — series? | top: B5EA300E×1889, 3E336B28×1702 |
| f8 | **document TYPE (10 distinct)** — the filter we need | dominant 7EA8BC3E×10,756; dictionary requested (§6.4) |
| f9 | 21 distinct UUIDs (journal/folder?) | dominant 7D83C30B×8,495 |
| f10–f13 | **role group A**: TRADER / TRADERSITE / customer / CUSTOMERSITE ids | each column hits its table 100% |
| f16,f17 | sparse UUID pair (~42 non-null) | ignore for now |
| f20, f31 | currency? (2 distinct, A0D5D45E×12,630) | |
| f21–f24 | **role group B** (same four tables, 100%) | |
| f27,f28, f38,f39 | repeats of the sparse 36-distinct pair | |
| f32–f35 | **role group C** (same four tables, 100%) | |
| f42,f43 | 3 distinct UUIDs (payment terms?) | dominant D69BF9D8×12,457 |
| f46,f47 | constant `1.0` | currency rates |
| f48–f91 | money columns (×10¹² format, §1) | f49/f50 look like net value; f72/f73/f88 totals (equal in samples) |
| f78 | DATE, **10,298/12,723 non-null**, range 2002-02-01..**2026-12-31** | future dates ⇒ looks like period-end/validity, **NOT issue date** |
| f92–f111 | bill-to SNAPSHOT (text) | f92 dept («ΛΟΓΙΣΤΗΡΙΟΥ»), f93 ΑΦΜ **as number — leading zero lost** (`94502024` = `094502024`), f94 phone, f95 country, f96/f97 region/city, f98 street, f99 number, f100 postal, f108 ΔΟΥ name («Φ.Α.Ε. ΑΘΗΝΩΝ»), f109 ΔΟΥ id (16 distinct), f111 email |
| f113 | DATE, only 25 non-null (2025-06..2026-06) | ignore |
| f121 | 9 distinct UUIDs | unknown |
| f124 | **sequential document number** — 12,723/12,723 distinct ints (22590, 22591, …) | → `contracts.galaxy_number`; what a user quotes on the phone |
| f127 | phone 2 | |

**No document ISSUE DATE was found with full coverage.** It almost certainly
lives in TRADEENTRY (f2 points there; table not exported). Requested (§6.3).

---

## 4. Triangular contracts (τριγωνικά) — the three role groups

Legacy semantics (see memory + `migration/legacy-schema.md`): LEE = ο πελάτης
(advertiser whose spots air), TRA/CUS = ο συναλλασσόμενος (payer, often a media
agency; one agency holds many contracts, each for a different client).

Galaxy natively carries **three** party-role groups per document (A=f10–13,
B=f21–24, C=f32–35). They genuinely differ:

- A == B on 8,379/12,723 (differs on **34%** — the triangular cases)
- A == C on 11,677/12,723
- B == C on 7,415/12,723

Live example seen: a document whose bill-to snapshot is U Media (media agency,
`c.angelikaki@umedia.gr`) — agency pays, someone else airs.

**OPEN:** which group is the payer (TRA) and which the advertiser (LEE) is a
question for the client/Galaxy consultant (§6.5). Do NOT guess in the importer;
`contracts.customer_id` (advertiser) vs `contracts.agency_id` (payer) must map
to the right groups.

---

## 5. Matching results (Galaxy ↔ `commercials_ctv_v2`, measured 2026-07-10)

Our side at time of analysis: 2,669 `customers` rows, 2,511 with `vat_number`,
2,486 unique VATs. Codes are legacy TRACODEs (`01000001`, `30030838`, 8-digit).

### 5.1 KEY DISCOVERY: Galaxy inherited the legacy code space

`customer.GXCODE` values are the SAME TRACODEs (`30000913`, `30003604`, …).
Galaxy was seeded from the old ERP. Therefore matching is **code-first**, not
VAT-first (the original plan assumed VAT-only).

### 5.2 Measured coverage

- Direct `GXCODE` ∩ our `customers.code`: **680** (measured against the 2,511
  VAT-bearing rows only, so the true all-rows figure is ≥680).
- VAT (`TRADER.GXTIN` ∩ our `vat_number`): **474** (19.1% of ours); of the
  matches, 4 VATs map to >1 of our rows (manual list), 0 Galaxy-side dupes.
- TIN quality: 3,670/4,091 traders have a TIN; only 1,849 are clean 9-digit —
  **8-digit TINs exist with the leading zero stripped** (e.g. `97690560` =
  `097690560`). **Normalize with LPAD(tin, 9, '0') before comparing** — this
  will raise the match rate above the numbers here.
- **The number that matters** — traders actually appearing in the 12.7k
  documents: 966 distinct. Of those: **521 (54%) match by code, +105 by raw
  (un-padded) VAT ≈ 65% combined**, 340 unmatched. The unmatched split into:
  genuinely new Galaxy-born customers (no legacy code — INSERT them), codes
  present in Galaxy but absent from our DB (e.g. `30030786` ΟΜΑΔΑ ΝΑΜΑ —
  created in SEN after our migration snapshot), and zero-pad VAT cases.
- Trader codes 1–9 are odd rows («Απ' Ευθείας», «Αθήνα», person names, no TIN)
  — look like legacy salesman/channel entries, not real customers.

### 5.3 Matching algorithm for the importer

1. `customer.GXCODE == customers.code` → stamp `customers.galaxy_id = TRADER.GXID`.
2. Else `LPAD(TRADER.GXTIN,9,'0') == customers.vat_number` (require exactly one
   candidate on BOTH sides; ambiguous → review list, never auto-stamp).
3. Else if the trader appears in documents → INSERT a new customer
   (`code = GXCODE` if present else generate, `galaxy_id = TRADER.GXID`,
   name/VAT/address from TRADER + primary GXTRADERSITE).
4. Emit a review report for: ambiguous VATs, code-collisions with different
   names, the 4 known multi-row VATs.

### 5.4 Items bridge

`GXITEMCOMPANYPROP.GXCODE` is the legacy item code space: `73.001 Διαφημίσεις
τηλεόρασης Τ`, `73.140 Internet`, `71.000 Παραγωγή…` — the same `Σ73.xxx` family
as our `spot_types.item_code` / `sales_item` (filled from SEN STI). Lines carry
`GXITEMCODE` (company code), `GXITEMID`, `GXITCPID`. Bridge:
`spot_types.item_code ↔ ITCP.GXCODE` (normalize: our values sometimes prefix
`Σ`); stamp `spot_types.galaxy_id = ITEM.GXID`. 239 catalog items / 143 with
company codes — small enough to hand-review the mapping table once.

### 5.5 Useful line columns for contract_lines

- `GXAQTY` = **number of spots** (verified against price×qty=net).
- `GXPRICE`, `GXNETVALUE`, `GXTOTALVALUE` — money (×10¹² format).
- `GXEXECUTIONDATE` — 16,945/23,203 non-null, 2002..2026; the direct analog of
  SEN `TDOEKTELESISDATE` (period). `GXDATEFROM`/`GXDATETO` are **empty** in this
  cut — period END source unresolved (maybe header f78; ask, §6).
- `GXFLOATFIELD1/2` — 21,859 non-null; values like 20/29/30 → spot DURATIONS (s).
- `GXSTRINGFIELD1` (14,350 non-null) — destroyed by encoding (§6.1); suspected
  zone/programme text. `GXCOMMENTS` (5,265) and `GXJUSTIFICATION` also destroyed.
- `GXLINETYPE` = `1` on every row of this cut.

---

## 6. OPEN ITEMS — requested from the client, blockers marked

1. **Header row (or column list) for COMMERCIALENTRY** — §3 is inferred.
   *Blocker for a robust importer.*
2. **Re-export `CommercEntryLines` with Greek intact** (cp1253 or UTF-8). The
   current file replaced every Greek char with literal `?`. Item names are
   recoverable via the ITEM join; free text (comments, GXSTRINGFIELD1) is not.
3. **TRADEENTRY export** (or at least document issue date + series/number
   columns) — issue date is NOT in the current export; f2 references TRADEENTRY.
4. **Document-type dictionary** — the 10 UUIDs of f8 (which types are
   contracts/orders vs invoices vs credit notes) so the importer can filter.
   *Blocker: without it we cannot tell συμβόλαια from τιμολόγια.*
5. **Role-group semantics** — which of A/B/C is payer (→ `agency_id`) vs
   advertiser (→ `customer_id`). *Blocker for triangular correctness.*

---

## 7. Schema groundwork ALREADY DONE (do not redo)

In `persistence/src/main/kotlin/eu/anifantakis/commercials/server/scheduler/StationDb.kt`
(both the `CREATE TABLE`s for fresh schemas AND guarded `ensureColumn`/
`ensureIndex` evolution for existing ones; `ensureIndex` gained `unique: Boolean`):

| Table | Column(s) | Index | Maps to |
|---|---|---|---|
| `customers` | `galaxy_id VARCHAR(36)` (pre-existed) | **UNIQUE** `uq_customers_galaxy` (new) | `TRADER.GXID` |
| `contracts` | `galaxy_id` + `galaxy_number BIGINT` (new) | UNIQUE `uq_contracts_galaxy`; plain `idx_contracts_galaxy_number` | `COMMERCIALENTRY.GXID` + f124 |
| `contract_lines` | `galaxy_id` (new) | UNIQUE `uq_lines_galaxy` | `CommercEntryLines.GXID` |
| `spot_types` | `galaxy_id` (new) | UNIQUE `uq_spot_types_galaxy` | `ITEM.GXID` |

Design decisions: `galaxy_id` is UNIQUE so the importer upserts keyed on it and
double-stamping fails loudly (MySQL unique allows unlimited NULLs — verified,
including duplicate-UUID rejection with ERROR 1062). `galaxy_number` is a
human-lookup field, deliberately non-unique. `customers.galaxy_id` stores the
**TRADER** id, not the `customer` wrapper id — the trader is the identity with
the VAT, and one column covers advertisers AND agencies (`agencies` is not a
separate table; `contracts.agency_id` FKs `customers`).

The columns were ALSO applied manually to the live `commercials_ctv_v2` (verified
present) — the next server bootstrap will no-op through the guards. No restart
was performed (never touch the dev server on :8080).

## 8. Operational notes for the next agent

- MySQL: docker container `local-mysql`, `root`/`rootpass123`, station schema
  `commercials_ctv_v2`, always `--default-character-set=utf8mb4`.
- Parser precedent to imitate: `migration/.../SenExports.kt` (tab-delimited ERP
  export parsing, greekUpper accent-stripping — Kotlin `uppercase()` keeps the
  tonos!, header detection) and `SenErpEnricher.kt` (idempotent enrich phases,
  CLI in `SenEnrichCli.kt`). The Galaxy importer should follow that shape:
  pure parser + idempotent apply phases + CLI with `--apply` dry-run default.
- The export is a **sample cut**; row counts and match rates will drift. The
  ANALYSIS METHOD is reproducible: load cp1253 / split CRLF+tabs / literal NULL,
  join id-sets across files, profile columns. Re-run it on the final delivery,
  especially §3 once headers arrive.
- The analysis scripts were throwaway python (this file preserves all results);
  sample data stays in `~/Downloads/ctv/ss/galaxy/` — do not move or commit it.
