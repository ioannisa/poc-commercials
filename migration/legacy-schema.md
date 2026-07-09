# Legacy Commercials Manager — Schema Analysis

Analyzed from six mysqldump backups (2026-06-29) of the original application.
This document is the reference for evolving our schema and for the eventual
data migration.

## Source overview

| Dump | Size | `schedule` rows | Notes |
|---|---|---|---|
| commercials.sql | 1.7 GB | ~4,141,000 | Main outlet; 4 extra zone-programming tables |
| commercials2.sql | 37 MB | ~282,000 | |
| commercials3.sql | 1.1 MB | ~5,100 | Partial/small retained window |
| commercials4.sql | 208 KB | ~600 | Press listings variant ("ΚΑΤΑΧΩΡΗΣΗ") |
| commercials5.sql | 1.7 MB | ~16,400 | |
| commercials6.sql | 5.6 MB | ~10,600 | |

- MySQL **5.7.26**, engines **MyISAM** (plus one InnoDB, one MEMORY), charset
  **utf8 (utf8mb3)**. Zero-dates (`0000-00-00`) are used as "not set".
- All six share one 26-table schema (the main DB adds 4 zone-programming
  tables). All belong to ΚΡΗΤΙΚΗ ΡΑΔΙΟΤΗΛΕΟΠΤΙΚΗ (Crete TV group).
- **`forTV` flag everywhere**: one legacy DB can serve BOTH a TV and a radio
  flow. One legacy DB therefore maps to **1..2 of our stations**.
- **Volume surprise**: of the 1.7 GB main dump, **1.24 GB is `emailhistory`**
  (longtext bodies of emailed customer reports). Actual scheduling data is
  ~460 MB. Migration can treat the email archive as optional/archivable.
- High `AUTO_INCREMENT` vs low row counts in small dumps ⇒ data was
  periodically purged; counters reveal lifetime volume (~1.2–1.5M placements
  each), dumps hold the retained window.

## Domain model (how the original app thinks)

```
ORACLE ERP (master data, NOT in these dumps)          MySQL (per outlet)
────────────────────────────────────────────          ─────────────────────────────
customers  (cusID/traid, name, ΑΦΜ)  ──────────────┐   cus                (contact info supplement)
contracts/documents (docid, docno, dotid, lines) ──┤   docref             (document shadow)
                                                   │   z_commercials      (doc line ↔ spot mapping)
                                                   │   sld                (doc types flagged "gift")
                                                   │   pelates_of_pelates (MEMORY! runtime cache:
                                                   │                       agency ↔ end-client per doc)
                                                   ▼
                            messages (spot catalog: descr, duration, cusID, contractID/NO, type)
                                │
                                ▼
                            schedule (THE placement log: messageID, showDate, showTime,
                                      showOrder, durationSecs, programID, docID, played)
                                │
              ┌─────────────────┼──────────────────────┐
              ▼                 ▼                      ▼
        program /         zones + zonefillers    commercials_calendar(_final),
        programtypes      + zoneprograms(+days,   media_services(_final)
        (descr, color)      parts, list)          (flattened financial/tax
                          (time bands, pricing)    reporting snapshots)
```

### Table reference

**Spot & scheduling core**
- `messages` — the spot catalog. `descr` (the "Μήνυμα" text), `duration`
  (secs), `forTV`, `messageTypeID`, `forcePosition`, `hidden`, `memo`; ERP
  links: `cusID` (customer), `contractID` (= ERP docid), `contractNO` (line).
- `schedule` — one row per placement: `messageID`→messages, `showDate` +
  `showTime` (the break), `showOrder` (position within break), `durationSecs`,
  `programID`→program, `docID` (ERP contract), `lineno`, `played` (aired
  confirmation!), `hideSchedule`, `timeOfEntrance`.
- `program` → `programtypes` — programme catalog; `programtypes.color` is a
  packed RGB int (UI colouring, ancestor of our zone colours), `visible`,
  `forTV`.

**Zones & pricing (airtime commercial policy)**
- `zones` — time bands with `fromTime`/`endTime`, `code`, `price`,
  `fromDate` (price validity), `fillerID`, `dimosio` (state/public ads flag).
- `zonefillers` — filler content w/ price.
- `zoneprograms`, `zoneprogramslist` (+ main-DB-only `zoneprogramdays`,
  `zoneprogramparts`, `zoneprogramslist_cat_cyta`, `zoneprogramslist_cat_law`)
  — per-date programme grids for the zones.

**ERP shadow (the Oracle boundary)**
- `docref` — contract/doc shadow: `docid` PK, `docno`, `dotid` (doc type),
  `traid` (customer/trader id — same series as `cusID`), `targetleeid`,
  `pelatislee`.

  **Triangular-contract semantics (verified 2026-07-04 on the main dump):**
  LEE and TRA are two id series for the same entities (lee↔tra mapping from
  non-triangular docs is ~1:1 — 2,711 lees / 2,719 pairs).
  - `pelatislee` = the PAYER's lee ("ο πελάτης μας"): maps to the doc's own
    `traid` in 5,270/5,271 checkable docs.
  - `targetleeid` = the END CLIENT's lee (the campaign's target — e.g. the
    agency pays, Unilever's spot airs).
  - `messages.cusID` equals `docref.traid` in 39,475/39,524 TV messages
    (99.9%): the legacy "customer" of a spot is the PAYER, not the end
    client. The end client exists ONLY as `targetleeid`.
  - `targetleeid <> pelatislee` marks a triangular doc: **3,004 of 12,299
    TV docs (24%)**, covering 8,236 spots / 225,609 placements (8.4%) of
    the migrated main outlet. 1,554 of those end clients also appear as
    direct customers (resolvable via the lee↔tra mapping); the other 1,450
    exist only as a lee id — their names live in Oracle alone.

  The current migration drops `targetleeid` entirely (contracts get
  `customer_id` = traid, `agency_id` stays NULL), so the end-client notion
  of triangular deals is NOT carried over — see "What our schema must
  grow", item 1.
- `z_commercials` — ERP doc lines ↔ spots (`docid`, `lineno`, `mciid`, `traid`).
- `sld` — `dotid` → `isGift` (gift/free spots per doc type; our demo's "ΔΩΡΑ").
- `pelates_of_pelates` — ENGINE=MEMORY runtime cache from Oracle: per `docid`,
  agency (`cus1name`/`cus1afm`) and end client (`cus2name`/`cus2afm`).
  **Always empty in dumps** — proof customers/contracts are Oracle-mastered.
- `calendar_excluded_docs` — docids excluded from calendar reporting.

**Financial/tax reporting snapshots**
- `commercials_calendar` / `_final` — flattened per-spot reporting rows with
  monetary values: `agelVal` (αγγελιόσημο ad-duty), `eidikosVal` (special
  tax), `zoneVal` (zone price), agency+client (`cusID1/afm1`, `cusID2/afm2`),
  and bank-deposit fields (`ag_*`, `ef_*`: bank/branch/deposit number/amount).
  `_final` = the confirmed (aired) version.
- `media_services` / `_final` — same idea without financial columns.

**Customer communication**
- `cus` — NOT the customer master; a contact supplement keyed by ERP `cusID`
  (email/fax/phone, mostly sparse) used for report distribution.
- `customermessages` (+`log`) — per customer/month/outlet report texts
  (message1..10 longtext) and the letters actually sent.
- `emailhistory` / `emailsetup` — sent report archive (the 1.2 GB!) + SMTP.

**App plumbing**
- `usr` — app users (username PK, role tinyint, **no password column** — auth
  was external/trivial). Superseded by our central auth.
- `generic` — station letterhead identity (titles used on reports).
- `roh_comments` — free-text comments per (date, forTV) on the daily ΡΟΗ
  (program flow) — feature to port: our flow report has no comments yet.
- `roh_print_history` — audit of ΡΟΗ printouts (who/when per date).

## Mapping to OUR current schema

| Legacy | Ours today | Notes |
|---|---|---|
| one DB (× forTV) | station schema | 1 legacy DB → 1–2 stations |
| `schedule` row | `commercials` row (+ aggregated `scheduler_cells`) | ours is denormalized snapshot; theirs normalized log |
| `schedule.showDate/showTime` | `scheduler_cells.cell_date` + `break_slots` | breaks = distinct showTimes |
| `schedule.showOrder` | `commercials.position` | |
| `messages.descr/duration` | `commercials.message/duration_seconds` | we inline; they reference catalog |
| `messages.cusID` + ERP name | `commercials.client_code/client_name` | our client_code ≈ their cusID/traid |
| `programtypes.descr` | `commercials.type` | |
| `sld.isGift` via doc | `commercials.contract` ("ΔΩΡΑ") | |
| `zones`/`programtypes.color` | `break_slots.zone`/`zone_color_argb` | |
| `usr` | central `users` + grants | ours is strictly better |
| `roh_print_history` | (we print, don't audit) | easy add later |

## What OUR schema must grow (to be autonomous)

> **Status (2026-07-03): implemented** (items 1–5 and 7) in
> `server/.../scheduler/StationDb.kt`. The grid the API serves is now DERIVED
> at read time from `placements ⋈ spots ⋈ customers ⋈ contracts`; the old
> `scheduler_cells`/`commercials` demo tables are dropped at bootstrap. Every
> table carries `legacy_id` for the future migration. `flow_comments` /
> `print_audit` tables exist but have no API yet.
>
> **Update (2026-07-04):** triangular contracts now migrate correctly -
> spots land on their END client (resolved via targetleeid and the lee↔tra
> map; unresolved end clients become synthetic customers keyed by
> `customers.legacy_lee_id`, code `LEE-<id>`), while the contract keeps the
> PAYER. The legacy `emailhistory` archive migrates into `email_log`
> (summaries for every send; bodies capped at
> `StationDb.EMAIL_BODY_RETENTION_PER_CUSTOMER` per customer - same policy
> as the live sender). Item 6 partially done: `zones` + `zone_fillers`
> tables exist and the FULL versioned price history migrates; pricing
> FEATURES remain future work. Deferred by decision: `usr` (central auth
> already covers users), `generic` (letterhead), `zoneprograms*`,
> `customermessages(log)`; `calendar_excluded_docs` pending decision;
> `media_services` awaiting confirmation the workflow is dead.

The point of the new app: absorb the Oracle-side entities so each station DB
is self-sufficient. Per station schema:

1. **`customers`** — id (preserve legacy `cusID`/`traid` as `legacy_id`),
   name, ΑΦΜ (VAT), contact info (absorbing `cus`), agency flag/links
   (agency ↔ end-client pairs from `pelates_of_pelates`).
2. **`contracts`** — id (`legacy docid`), number (`docno`), type (`dotid`,
   with `is_gift`), customer + optional agency, entry date, status.
3. **`contract_lines`** — per contract line (`lineno`): desired quantity,
   values (`agelVal`/`eidikosVal`/zone pricing hooks).
4. **`spots`** (≙ `messages`) — catalog: description, duration, type,
   customer, contract line, hidden/forcePosition.
5. **`placements`** (≙ `schedule`) — spot → date/time/order + `played` flag;
   our `scheduler_cells`/`commercials` become a *derived/materialized* view of
   this (or are replaced by it) — today's tables are the read-model, this is
   the write-model.
6. **`zones` + pricing** — if commercial policy features are wanted.
7. Small wins: `flow_comments` (≙ roh_comments), `print_audit`
   (≙ roh_print_history).

## Migration notes (for when we build it)

- **Order**: customers ← contracts ← lines ← spots ← placements; keep
  `legacy_id` columns everywhere for idempotent re-runs and cross-checks.
- **forTV split**: migrating one legacy DB writes into 1–2 target stations.
- **Charset**: utf8mb3 → utf8mb4 is safe upward; watch `0000-00-00` dates
  (convert to NULL; MySQL 8 strict mode rejects them).
- **MEMORY table** (`pelates_of_pelates`) dumps empty — agency/client pairs
  must come from Oracle exports or the flattened `commercials_calendar*` rows
  (which carry cusID1/2 + names + ΑΦΜ — a usable fallback source!).
- **Aggregates**: our `scheduler_cells` per-cell totals are recomputable from
  placements — recompute, don't migrate.
- **`emailhistory` (1.2 GB)**: archive-only; migrate on demand or never.
- **Volume**: ~4.1M placements on the main DB — trivial for InnoDB with our
  existing `cell_date` indexing strategy, but the migration tool must stream
  (the dumps' multi-row INSERTs are already ideal for LOAD-style replay into
  a scratch schema, then transform via SQL).

## Schema correspondence — our DB derives from the dumps (2026-07-09)

THE RULE (owner's directive): our station schema IS an evolution of the
legacy MySQL structure. The SEN (Oracle ERP) exports only FILL what the
legacy app fetched live from the ERP (customers, contracts, contract
products) — they never reshape the model. When adding anything, find the
legacy table first and mirror it.

| Legacy MySQL            | Ours              | Notes |
|-------------------------|-------------------|-------|
| `messages`              | `spots`           | 1:1. `messageTypeID` → `spot_type_id` (REFERENCE, never frozen text); `contractID`+`contractNO` → `contract_line_id`; `cusID` → `customer_id` (end client on triangular). |
| `schedule`              | `placements`      | 1:1. showDate/showTime/showOrder → break_id+position (`break_slots` materializes the time grid the legacy app derived). |
| `programtypes`          | `spot_types` + `programs` | The legacy catalog is the ERP MCI class list (messages.messageTypeID AND schedule.programID both point at it). `spot_types.sales_item` = the ERP item name (STI, 1:1 by MCIID) — the SEN gap-fill. ⚠ TWO mirrors of one legacy table: candidate for merging into ONE catalog. |
| `docref`                | `contracts`       | docid → legacy_docid, docno → number, dotid → doc_type, traid → customer_id. SEN fills periods/qty/gift. |
| `z_commercials`         | ⚠ (contract_lines) | z_commercials IS the contract-PRODUCTS table (doc lines, mciid = item class — 1:1 with SSD lines). Our `contract_lines` is a pseudo-mirror (one line per contract keyed by the doc NUMBER from messages.contractNO). Deviation to fix: contract_lines should mirror z_commercials (contract, lineno, spot_type_id) and a spot's product = (contract, spot_type) at query time — needs re-migration. |
| `sld`                   | `contracts.is_gift` | Absorbed (dotid → gift); SDT completes the 21 doc types in use. |
| `cus`                   | `customers` contact fields | Absorbed; customers themselves are the SEN gap-fill (legacy had NO customer names). |
| `calendar_excluded_docs`| `contracts.exclude_from_reports` + `erp_excluded_docs` | Absorbed + holding table. |
| `roh_comments`          | `flow_comments`   | 1:1. |
| `roh_print_history`     | `print_audit`     | 1:1. |
| `emailhistory`          | `email_log`       | 1:1 (bodies capped per customer). |
| `zones`, `zonefillers`  | `zones`, `zone_fillers` | 1:1, full price history. |
| `usr`, `emailsetup`, `generic` | central auth DB / server.yaml | Replaced by hosting-level equivalents. |
| `commercials_calendar(_final)` | — (partly absorbed) | The ΗΜΕΡΟΛΟΓΙΟ flattened export; its agelVal/eidikosVal/zoneVal live on contract_lines. Also a fallback source of agency/client pairs + ΑΦΜ. |
| `zoneprograms(+days/parts/list*)` | — NOT migrated | The daily programme guide (EPG: showdate, timefrom, progid → programtypes) — 43 MB of real data, biggest unmigrated feature. |
| `customermessages(+log)` | — NOT migrated | Per (customer, year, month) message texts (schedule-email era) + log. |
| `media_services(_final)` | — NOT migrated | Flattened per-airing export (media-services feed). |
| `pelates_of_pelates`    | — (empty)         | MEMORY table, dumps empty. |

### Column-level correspondence (verified 2026-07-09, v2)

- `messages` → `spots`: id→legacy_id, messageTypeID→spot_type_id (catalog ref),
  contractID→(contract via product line), contractNO→(the DOC number - superseded
  by the real line resolution), cusID→customer_id (end client on triangular),
  forcePosition→force_position (NULL for -1), hidden, descr→description,
  duration→duration_seconds, memo. Dropped deliberately: lastaction (audit ts).
- `schedule` → `placements`: id→legacy_id, messageID→spot_id, **docID+lineno→
  contract_line_id (the airing's ACTUAL charge - the same spot airs under
  different contracts/products; verified populated on 100% of sampled rows)**,
  programID→program_id, showDate→show_date, showTime→break_id (via the
  materialized grid), showOrder→position (renumbered per cell), durationSecs,
  played, hideSchedule→hidden. Dropped: lastaction, timeOfEntrance.
- `z_commercials` → `contract_lines`: docid→contract_id, lineno→line_no,
  mciid→spot_type_id; docnumber/traid are redundant (live on the contract).
  Fallback lines for uncovered (doc, type) pairs use line_no = 1000 + type id.
- `programtypes` → `spot_types` (id→legacy_id, descr→name; + SEN sales_item/
  item_code) and `programs` (colour/visible view for placements.program_id) -
  the dual mirror is the one REMAINING soft deviation, scheduled for merge.
- `docref` → `contracts`: docid→legacy_docid, docno→number, dotid→doc_type,
  traid→customer_id, targetleeid/pelatislee→triangular resolution.
- `emailhistory` → `email_log`: cusID→customer_code (ERP TRACODEs after
  enrichment), recipientEmailAddress→recipient, body→body_html (capped),
  subject, entryDate/Time→created_at, periodRequested→year/month. Dropped:
  emailFrom, reportType.
- `roh_comments`/`roh_print_history`/`zones`/`zonefillers`/`cus`/`sld`/
  `calendar_excluded_docs`: absorbed as listed in the table above, all columns
  either mapped or deliberately dropped (timestamps).

## Faithful UNION layer (owner directive 2026-07-10 — supersedes the mapping-only view)

The station schema CONTAINS both sources as verbatim copies ("ΠΙΣΤΑ
ΑΝΤΙΓΡΑΦΑ" — exact table and column names), combined into the one
functional database:

- **Legacy MySQL side** (transformer `copyLegacyTables()`, CREATE LIKE +
  INSERT): `messages`, `schedule`, `programtypes`, `docref`, `z_commercials`,
  `cus`, `sld`, `calendar_excluded_docs`, `commercials_calendar_final`,
  `roh_comments`, `roh_print_history`, `zones`, `zonefillers` — flow-scoped
  tables filtered to the station's flow; `emailhistory` copied without the
  heavy bodies (the app's email_log holds the capped ones).
- **Oracle/SEN side** (enricher `materializeSenTables()`): `sen_lee`,
  `sen_cus`, `sen_adr` (ALL addresses per entity), `sen_sld`, `sen_ssd`,
  `sen_sdt`, `sen_sti` — ERP column names, essential fields, MySQL-first
  filtered rows. The `sen_` prefix exists ONLY because legacy `cus`/`sld`
  collide with the ERP names.
- The app's normalized tables (spots/placements/customers/contracts/…)
  remain the WORKING layer, derived from the copies at migration time; the
  agreed direction is to converge the app onto the faithful tables and
  retire the renamed layer.
- Triangular contracts read straight from the union: `docref.targetleeid`/
  `pelatislee`, `sen_sld.TRAIDPRINCIPAL`/`DOCIDTRIANGLE`, and the LEE
  (πελάτης) vs TRA/CUS (συναλλασσόμενος) split across `sen_lee`/`sen_cus`.
