# Galaxy ERP Import — Operations Guide

The Galaxy (SingularLogic) importer syncs the client's NEW ERP into the group
databases. It is a SEPARATE subsystem from the legacy migration (`migration/`):
module `:galaxy`, analysis & format contract in `GALAXY-MATCHER.md` (root).

**The bridge it maintains:**
- `customers.galaxy_id` ← Galaxy `TRADER.GXID` (advertisers AND agencies)
- `contracts.galaxy_doc_key` ← `company:doccode:docnumber` (+ `galaxy_number`;
  `galaxy_id` stays reserved for the real GXID of the final delivery)
- `contract_lines.galaxy_line_key` ← doc key + line ordinal (new contracts only)
- `spot_types.galaxy_id` ← Galaxy `ITEM` id (Σxxx ↔ 73xxx digit bridge)
- `galaxy_lines / galaxy_parties / galaxy_items / galaxy_doc_types` mirror
  tables — the inspectable Galaxy archive; the ADVERTISER↔contract linkage of
  triangular deals lives in `galaxy_lines` (payer_customer_id /
  advertiser_customer_id).

---

## Gradle commands

### Dry-run (default — writes NOTHING, prints the report + review CSV)

```bash
./gradlew :server:galaxyImportCli --args="\
  --galaxy-dir ~/Downloads/ctv/ss/galaxy2 \
  --old-export-dir ~/Downloads/ctv/ss/galaxy/customer \
  --schema commercials_crete_group --company 001 \
  --user root --password rootpass123 \
  --review-out ~/galaxy-review.csv"
```

### Apply (writes; runs GroupDb.bootstrap() first to add missing columns)

Same command + `--apply` at the end.

### Options

| Flag | Default | Meaning |
|---|---|---|
| `--galaxy-dir` | (required) | the flat delivery folder (`COMMERCIALENTRY.txt`, `CUSTOMER.csv`, `GXCOMMENTRYTYPE.txt`, …) |
| `--old-export-dir` | none (⚠) | OLD raw export's `customer/` folder — the FULL party dictionary; needed until the uncapped CUSTOMER delivery arrives (§9.6) |
| `--schema` | (required) | target group schema |
| `--company` | `001` | Galaxy company: `001` ΙΚΑΡΟΣ→crete_group, `003` Channel 4→channel4, `004` Σητεία; `002` = press, out of scope |
| `--host/--port/--user/--password` | localhost/3306/root/prompt | MySQL (dev docker `local-mysql`) |
| `--review-out` | `galaxy-review.csv` | the review list (`~/` works) |
| `--apply` | off | actually write |

`./gradlew :galaxy:test` — unit tests (parser + matching logic).

### Rehearsal on a clone (how the importer was verified)

```bash
docker exec local-mysql mysql -uroot -prootpass123 -e \
  "DROP DATABASE IF EXISTS commercials_galaxy_test; CREATE DATABASE commercials_galaxy_test DEFAULT CHARACTER SET utf8mb4"
docker exec local-mysql sh -c "mysqldump -uroot -prootpass123 commercials_crete_group \
  customers contracts contract_lines spot_types 2>/dev/null | \
  mysql -uroot -prootpass123 --default-character-set=utf8mb4 commercials_galaxy_test"
# then run the CLI with --schema commercials_galaxy_test --apply
```

Re-running `--apply` is idempotent: the second pass performs zero writes.

---

## Review CSV — what each kind needs

Nothing in the review BLOCKS applying; the importer never guesses.

| kind | meaning | action |
|---|---|---|
| `untwinned-9010` | native Τριγωνικό without an exact Εντολή twin; imported with `exclude_from_reports=TRUE` (no double counting) | optional |
| `doc-ambiguous` | doc matches >1 contracts even after payer/family/year tie-breaks (numbers repeat within a year); NOT imported | resolve by hand or wait for GXID |
| `party-multi-claim` | two Galaxy codes → one customers row (duplicate registry entries); row left unstamped, documents still resolve correctly | pick the primary code, one UPDATE |
| `party-ambiguous-vat` | ΑΦΜ matches >1 of our customers (incl. LEE- duplicates) | resolve by hand |

## Supervision queries

```sql
-- how much of the bridge is in place
SELECT COUNT(*) FROM customers  WHERE galaxy_id IS NOT NULL;
SELECT COUNT(*) FROM contracts  WHERE galaxy_doc_key IS NOT NULL;
SELECT COUNT(*) FROM spot_types WHERE galaxy_id IS NOT NULL;

-- triangular deals with the advertiser linked (new capability)
SELECT gl.doc_key, pay.name AS payer, adv.name AS advertiser, gl.value
FROM galaxy_lines gl
JOIN customers pay ON pay.id = gl.payer_customer_id
JOIN customers adv ON adv.id = gl.advertiser_customer_id
WHERE gl.payer_customer_id <> gl.advertiser_customer_id;

-- Galaxy docs that entered as NEW contracts (not present in the legacy data)
SELECT number, entry_date, galaxy_doc_key FROM contracts
WHERE galaxy_doc_key IS NOT NULL AND legacy_docid IS NULL;
```

---

## Roadmap / status

1. ✅ Analysis + format verification (`GALAXY-MATCHER.md` §9–§11)
2. ✅ Importer (`:galaxy`) built, unit-tested, rehearsed on a clone —
   887 parties / 4,084 docs → 2,474 matched + 1,545 new, idempotent re-run
3. ⏳ **Owner reviews `galaxy-review.csv` → `--apply` on the LIVE
   `commercials_crete_group`** ← we are here
4. ⬜ Galaxy Bridge screen in the super admin app (service + `/api/admin/galaxy`
   routes + KMP screen, modeled on the Migration Console)
5. ⬜ Channel 4 (`--company 003` → channel4 group) and Σητεία (`004`) once
   their groups are migrated
6. ⬜ Final delivery upgrades: document GXID → `galaxy_id`, uncapped
   CUSTOMER/TRADERSITE (drops the `--old-export-dir` requirement) — §11
