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

## 6. OPEN ITEMS — ALL 5 RESOLVED by the galaxy2 delivery (2026-07-18), see §9

1. ~~Header row for COMMERCIALENTRY~~ ✅ galaxy2 flat export has headers (§9.1).
2. ~~Greek destroyed in CommercEntryLines~~ ✅ galaxy2 is clean cp1253.
3. ~~Document issue date~~ ✅ galaxy2 `date` column, 100% filled (§9.4).
4. ~~Document-type dictionary~~ ✅ `GXCOMMENTRYTYPE.txt` delivered (§9.2).
5. ~~Role-group semantics~~ ✅ resolved empirically + verified on the known
   LOREAL/TEMPO case (§9.3).

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

---

## 9. galaxy2 delivery (2026-07-18, analyzed 2026-07-19) — SUPERSEDES §1–§3 for the importer

Location: `~/Downloads/ctv/ss/galaxy2/`. This is NOT a raw table dump — it is a
pre-joined **flat query export**, one row per document line, WITH headers:

| File | Rows | What |
|---|---|---|
| `COMMERCIALENTRY.txt` | 15,894 lines = 7,856 docs | tab-delimited flat export, 27 named columns |
| `GXCOMMENTRYTYPE.txt` | 126 | full document-type dictionary |
| `CUSTOMER.csv` / `TRADERSITE.csv` | 1,000 / 1,001 | ⚠ **TOP-1000 capped** (see 9.6) |
| `ITEMPERCOMP.csv` | 143 | items per company (matches old GXITEMCOMPANYPROP) |
| `commersialentry.csv` | 100 | semicolon sample of the txt, same header |

### 9.1 Format & parsing rules (differ from the old raw export!)

- cp1253, tab-delimited, `\r\n` row terminator; **quoted fields contain literal
  `\n` AND literal TABs** (one row splits into 31 columns on naive split) —
  the parser MUST do CSV-style quote handling (strip outer `"`, unescape `""`)
  over tab-delimited text, joining rows/fields until the closing quote.
- Money/qty format is **Greek locale** (`8.000,00` = 8000.00) — NOT the old
  ×10¹² integer format. Dates are `dd/MM/yyyy`.
- Columns: companycode/companyname/companyid, custcode/custname/custid, date,
  salesmancode/salesmanname, docnumber, doccode, Type, itemname,
  «Διαφημιζόμενος / Διαφημιστής» Code/Name, TradeNum (== docnumber), item_ID,
  item_code, item_name (== itemname), aqty, bqty, Seconds, Spot, Τheme,
  Comments, Zone, Value.
- **Zone and Τheme columns are junk** (all `'0'`); the real theme/period is
  free multi-line text embedded INSIDE `itemname` after the catalog name.
- `Spot` = number of spots, `Seconds` = duration; `aqty` semantics vary
  (≠ Spot on 70% of rows) — do not treat aqty as spot count.
- **No document GXID in this export** (only companyid/custid/item_ID UUIDs).
  Natural key = **(companyid, doccode, docnumber)** — docnumber alone is NOT
  unique. `contracts.galaxy_id`/`galaxy_number` (§7) were designed for the raw
  format; either adapt schema or request a GXID column in the final delivery.

### 9.2 Document types

Only the contract family is present — all 19 doccodes used are
`GXCOMMERCENTRYKIND=1` (no invoices; their side pre-filtered). Series: 9xxx
and 1xxx exist per company (series semantics not fully clear, non-blocking).
Row counts: Συμβόλαιο Πελάτη 6,905 + από SEN 2,535 · Εντολή Διαφήμισης 1,542 +
από SEN 1,406 · Τριγωνικό 971 + από SEN 1,377 · Κλείσιμο Εκκρεμοτήτων 789 ·
Εκλογικά 136 · Δώρα 184 · Ακύρωση 16 (negative values) · Διόρθωση 33.
⚠ dictionary GXCODEs are NOT unique across GXDOMAIN (1001 = Συμβόλαιο in
domain 1, Παραγγελία Προμηθευτή in another) — filter dictionary by domain=1 or
trust the export's `Type` column.

### 9.3 TRIANGULAR SEMANTICS — RESOLVED (verified on LOREAL/TEMPO, contract 645)

The «Διαφημιζόμενος / Διαφημιστής» column means "the OTHER party" and flips
role per document type:

| Type | `custcode` = | extra column = |
|---|---|---|
| Συμβόλαιο Πελάτη (9001/1001/9101) | the customer | empty (non-triangular) |
| Εντολή Διαφήμισης (9004/1004/9104) | **AGENCY (payer/TRA)** | **ADVERTISER (LEE)** |
| Τριγωνικό Πελάτη native (9010) | advertiser | == custcode (agency NOT shown) |
| Τριγωνικό από SEN (9110) | **ADVERTISER** | **AGENCY** — inverse of 9004! |

Verified: 9004 → cust=TEMPO OMD (01000012), adv=L'OREAL (30001582);
9110 → cust=L'OREAL, adv=TEMPO OMD. ✔

⚠ **DOUBLE-COUNT TRAP**: native flow issues TWO documents per campaign —
Εντολή (9004, to agency) + Τριγωνικό (9010, to advertiser) with IDENTICAL
values (791/971 9010-rows are exact copies of a 9004 row; yearly sums match to
the cent). Import ONLY the 9004 leg (it carries both roles); use 9010 for
cross-check. 9110 (SEN) has no twin — import it directly.

### 9.4 Dates

`date` = document issue date, 100% filled, range 2022-01-01..2026-12-31.
The 308 future-dated rows are legitimately pre-issued monthly docs (e.g. ΔΕΗ
internet per month) — import as-is.

### 9.5 Matching waterfall (measured vs `commercials_crete_group`, 3,170 customers)

1,374 party codes referenced by documents (20,946 refs):

| Step | Codes | % of refs |
|---|---|---|
| direct code match | 649 | 64.3% |
| VAT match (LPAD-9, unique both sides) | 311 | +18.9% → **83.2%** |
| VAT ambiguous → review list | 3 | 30000051, 30030747, 30031088 (→ LEE-5983 dup) |
| NEW customers, TIN known → INSERT | 405 | |
| NEW, no TIN info | 6 | micro-customers, 46 refs |

### 9.6 The 1000-row cap and its rescue

`CUSTOMER.csv`/`TRADERSITE.csv` are TOP-1000 cuts. **Fully rescued by the OLD
raw export** (`~/Downloads/ctv/ss/galaxy/customer/`): all 1,362 custids of the
flat export exist in old `customer.txt`; TINs available for 1,306/1,361 codes
via the TRADER join. Importer should use OLD export as the party dictionary +
new CUSTOMER.csv as secondary for Galaxy-born customers. Request uncapped
exports for the final delivery.

### 9.7 Items

Two code worlds: `Σxxx` (SEN codes — companies 001/003/004) and `73xxx`
(Galaxy-native — company 002 press). 53/83 flat-export item codes match our
`spot_types` after digit-normalization (strip `Σ`, dots); the unmatched are
almost all press items (καταχωρήσεις, ένθετα, αγγελίες) — out of scope.

## 10. Company ↔ legacy dump ↔ group DB mapping (RESOLVED 2026-07-19)

Fingerprinted from `~/Downloads/commercials/commercials/*.sql` (`generic`
station label, `messages` content, activity dates):

| Galaxy company | Legacy dump | Identity | Target DB |
|---|---|---|---|
| **001 ΙΚΑΡΟΣ ΡΑΔΙΟΤΗΛΕΟΠΤΙΚΕΣ** (10,042 rows, ALL «από SEN» docs) | commercials.sql (1.8 GB; forTV 1=TV/0=radio) | ΚρήτηTV + Radio984 | `commercials_crete_group` (migrated) |
| **003 ΚΡΗΤΙΚΗ ΡΑΔΙΟΤΗΛΕΟΡΑΣΗ ΑΕ** (607) | commercials2.sql (38 MB, live) | **CHANNEL 4** («CHANNEL 4u», ΑΦΜ 094259345) | `commercials_channel4` (skeleton, 7 customers) |
| **004 ΣΗΤΕΙΑ TV** (353) | commercials6.sql (5.8 MB, live) | SITIA TV | no group DB yet |
| **002 ΚΥΚΛΟΣ ΑΕ** (4,892, press/internet 73xxx) | (commercials4.sql is its 2010 election-listings cousin) | newspaper — no scheduler | **OUT OF SCOPE** |
| — | commercials3.sql | 2010(/2014) ELECTION spots TV+radio — dead archive | skip |
| — | commercials5.sql | ΚρήτηTV self-promo/trailers DB — not commercial | skip |

⚠ Naming trap: «ΚΡΗΤΙΚΗ ΡΑΔΙΟΤΗΛΕΟΡΑΣΗ» = Channel 4, NOT ΚρήτηTV (that's
ΙΚΑΡΟΣ). The `generic` company block is copy-pasted garbage across dumps
(SITIA TV's dump carries ΚΡΗΤΙΚΗ's block); only the last-column station label
is trustworthy.

**Importer scope**: filter flat export by `companyid`; import 001→crete_group,
003→channel4, 004→(future sitia). Keep it a SEPARATE subsystem from the legacy
`migration/` (user decision 2026-07-19) — legacy migration = dumps+SEN→our DB;
Galaxy import = ongoing ERP sync.

### 10.1 Galaxy/SEN coverage per company (measured 2026-07-19)

Galaxy is ONE multi-company installation with a SHARED party registry
(003/004 party codes overlap 001's by ~50%). Per-company depth:
001 → 4,283 docs/887 parties incl. 5,420 «από SEN» rows; 003 Channel4 →
303 docs/55 parties, **ZERO SEN history**; 004 Sitia → 190 docs/56 parties,
zero SEN; 002 press → 3,079 docs. **Only company 001's SEN history was
migrated into Galaxy** — Channel4/Sitia trails start 2022.

**SEN is single-company for us**: our exports (`~/Downloads/ctv/ss/SEN/`)
have `CMPID=1` on every cus row. PROOF Channel4 used a SEPARATE SEN
instance: its legacy `docref` docids (1,745) looked up in our sld.csv →
1,184 collide with DIFFERENT 2004-era documents, 561 absent, 1 coincidental
match. Same SEN product/dictionaries (dotid 450=ΣΥΜΒΟΛΑΙΟ etc.), different
id space and data. The «ΕΚΛΟΓΩΝ - ΝΕΑ ΚΡΗΤΗ» doc type in our sen_sdt is
cloned parametrisation, not newspaper data.

⇒ Full picture per group = TWO sources: crete_group (legacy migration + SEN
enrichment + Galaxy 2022+) · channel4/sitia (their legacy dump migration +
Galaxy 2022+, NO SEN step — we don't hold their SEN instances' exports).

**Remaining open (non-blocking)**: see §11 — the full request list for the
final delivery.

## 12. IMPORTER IMPLEMENTED (2026-07-19) — module `:galaxy`

Separate subsystem from `migration/` (user decision). Kotlin/JVM module
`galaxy/` (`GalaxyExports.kt` quote-aware parser · `GalaxyImporter.kt`
reconcile/upsert engine · `GalaxyImportCli.kt`), server shim
`server/.../galaxy/GalaxyImportTool.kt`, Gradle task:

```
./gradlew :server:galaxyImportCli --args="\
  --galaxy-dir ~/Downloads/ctv/ss/galaxy2 \
  --old-export-dir ~/Downloads/ctv/ss/galaxy/customer \
  --schema commercials_crete_group --company 001 \
  --user root --password rootpass123 [--apply]"
```

Dry-run default; `--apply` first runs `GroupDb.bootstrap()` (adds the new
`contracts.galaxy_doc_key` / `contract_lines.galaxy_line_key` columns —
schema single-sourced in persistence/GroupDb.kt). Engine: party waterfall
(code → LPAD-9 VAT → insert; multi-claims of one row go to review unstamped),
item digit-bridge, 9004↔9010 twin skip, contract reconciliation on
(number, payer, doc-family, YEAR) with fixed-point claiming, `galaxy_*`
mirror tables (advertiser linkage lives in `galaxy_lines`), review CSV.

**Verified on a clone (`commercials_galaxy_test`, company 001)**: 887 parties
→ 578 code + 269 VAT + 38 inserted + 2 ambiguous + 41 multi-claim reviews;
53 spot_types stamped + 2 inserted; 197 twin docs skipped (651 lines), 111
untwinned flagged; 4,084 docs → **2,474 matched & stamped + 1,545 inserted
(565 off-reports) + 65 ambiguous**. Re-run = ZERO writes (idempotent).
LOREAL/TEMPO check: galaxy_lines payer_customer_id=7 (TEMPO),
advertiser_customer_id=2919 (LEE-3085) ✔ — the LEE↔Galaxy advertiser
reconciliation the legacy migration never had.

NOT yet applied to the live `commercials_crete_group` — awaiting owner
review of the dry-run report/review CSV. Follow-ups: admin screen (mirror
MigrationRoutes), channel4/sitia runs, GXID upgrade per §11.

## 11. Instructions for the FINAL delivery (to hand to the Galaxy consultant)

Drafted 2026-07-19. Nothing here blocks development — the importer starts on
the current sample (§11.4).

**A. Required**
1. **Uncapped CUSTOMER/TRADERSITE exports** — current files are TOP-1000 cuts
   (CUSTOMER.csv exactly 1,000 rows; old raw export had 4,091 traders). Need
   ALL traders/customers + ALL sites/addresses.
2. **Document GXID (and ideally line GXID) columns in the flat export** —
   currently no document UUID; needed for robust idempotent upsert. Until
   then we key on (companyid, doccode, docnumber, line-ordinal).
3. **Format stability for periodic deliveries** — same columns/order, header
   row always, tab-delimited, cp1253 (UTF-8 welcome), full snapshot each time.

**B. Useful**
4. Link column Τριγωνικό (9010) ↔ its Εντολή (9004) twin; and in 9010 emit
   the AGENCY in the «Διαφημιζόμενος/Διαφημιστής» column (as 9110 does)
   instead of repeating the customer.
5. Cancellation reference on 1030 «Ακύρωση» docs (which doc is cancelled).
6. Real Τheme/Zone values in their columns (currently '0'; the actual text is
   free-form inside itemname).

**C. Questions (answers only, no export changes)**
7. What distinguishes 9xxx from 1xxx doc series within a company?
8. Exact semantics of `aqty` (≠ Spot count on ~70% of lines).
9. Confirm 2022-01-01 = Galaxy go-live / full history; and that SEN history
   was migrated only for ΙΚΑΡΟΣ (deliberately not for ΚΡΗΤΙΚΗ/ΣΗΤΕΙΑ).

### 11.4 Why development starts NOW despite the cap

The 1000-row cap is fully bridged by the OLD raw export (§9.6): every custid
referenced by the flat export exists in old `customer.txt`, with TINs for
1,306/1,361 codes via TRADER. Importer architecture: party dictionary =
OLD export (primary) + capped CUSTOMER.csv (secondary, Galaxy-born rows);
swap to the uncapped file when it arrives — source changes, logic doesn't.
