# Contract models: ΑΠΕΥΘΕΙΑΣ (direct) vs ΤΡΙΓΩΝΙΚΕΣ (triangular)

> **Read me (future Claude).** This file is a self-briefing. The owner will hand
> it to you in a later chat to design how the app should *operate* the two
> contract-sale models. It records the verified findings, how the legacy party
> model actually works in the migrated schema, exactly how to tell the two apart
> in the data, and **two concrete, re-queryable examples** so you can ground any
> design in real rows instead of prose. Everything below was verified live
> against `commercials_crete_group` on **2026-07-17**. See also the
> `cus-lee-tra-triangular` memory (deep migration history) and
> `migration/legacy-schema.md` (§ docref / Schema correspondence).

---

## 0. The one rule (TL;DR)

For any aired spot there are **two parties**:

- **Advertiser / διαφημιζόμενος** = the brand whose creative airs = `spots.customer_id`. This is what the Break Console / `spots_in_break` report shows in the **«Πελάτης»** column.
- **Payer / συναλλασσόμενος (trader)** = who holds the contract and pays us = `contracts.customer_id`. **Not shown** in the report today, but carried on `CommercialRow.payerCode / payerName`.

**Detection:**

| Condition | Model |
|---|---|
| `spots.customer_id` **==** `contracts.customer_id` (advertiser code == payer code) | **ΑΠΕΥΘΕΙΑΣ (direct)** — the customer bought for itself |
| `spots.customer_id` **≠** `contracts.customer_id` (codes differ) | **ΤΡΙΓΩΝΙΚΗ (triangular)** — an agency bought on behalf of the brand |
| advertiser's `customers.code` starts with **`LEE-`** | **always triangular** (a LEE-only end-client, see below) |

The **code-inequality** is the general test; the **`LEE-` prefix** is a strong extra tell but not the only shape a triangular can take (an end-client that *also* trades on its own would have a real code yet still differ from the payer on an agency-bought spot). **⚠ The «Αγορά» (market Αθήνα/Κρήτη) column is NOT a tell** — ΚΡΕΤΑ ΦΑΡΜ is «Αθήνα» yet **direct**.

---

## 1. The legacy party model — CUS / LEE / TRA

Three Oracle (SEN) tables, mirrored into the station schema as `sen_lee` / `sen_cus` (+ `sen_adr` for addresses):

- **LEE** (`sen_lee`: `LEEID, LEENAME, LEEAFM`) — the **master party** table. *Every* party (brand or agency) has a LEE row. Think "identity + ΑΦΜ". Represents **the advertised party**.
- **TRA** = **TRA**der = **συναλλασσόμενος** = the party that transacts and **pays**. Identified by a `TRAID`. In a triangular deal this is the **agency**.
- **CUS** (`sen_cus`: `TRAID, LEEID, TRACODE, ADRIDMAIN, TRASTARTDATE, …`) — the **trading account** that **links a `TRAID` to a `LEEID`** and carries the ERP customer code **`TRACODE`** (the «Κωδ. Πελ.» printed everywhere).

Consequences:

- A **direct customer** transacts for itself → it has a **CUS row whose `TRAID` points at its own `LEEID`**. It wears **both hats** (advertiser *and* payer). Its code = its `TRACODE`.
- A **triangular end-client** never transacts itself → it exists **only** in `sen_lee` (a `LEEID`, name, ΑΦΜ) with **no CUS/TRA row at all** → it has **no `TRACODE`**. The station's contract is with the *agency's* TRA, not with the brand.

Mnemonic: **LEE** = the party/brand (advertised) · **TRA** = **TRA**der (payer) · **CUS** = the account that binds a TRA to a LEE and names the code.

---

## 2. How it lands in the migrated schema (`customers` / `spots` / `contracts`)

`customers` row fields that encode all of this:

| column | meaning |
|---|---|
| `code` | `TRACODE` for traders (e.g. `30030937`, `01000012`); **`LEE-<leeid>`** for LEE-only end-clients (e.g. `LEE-3085`) |
| `legacy_id` | the **`TRAID`** — **NULL** for LEE-only end-clients (they never traded) |
| `legacy_lee_id` | the **`LEEID`** |
| `vat_number` | ΑΦΜ (from `sen_lee.LEEAFM`) |
| `synthetic` | placeholder flag; `0` = real name/VAT even when `LEE-`-coded (LOREAL is `LEE-3085` **and** `synthetic=0`) |

The airing → parties join (verbatim from `StationDb.loadMonth`, `persistence/.../StationDb.kt` ~line 820):

```sql
FROM placements p
JOIN spots s        ON s.id = p.spot_id
JOIN customers cu   ON cu.id = s.customer_id                                 -- ADVERTISER (LEE)
LEFT JOIN contract_lines cl ON cl.id = COALESCE(p.contract_line_id, s.contract_line_id)
LEFT JOIN contracts ct      ON ct.id = cl.contract_id
LEFT JOIN customers pay     ON pay.id = ct.customer_id                       -- PAYER (TRA)
```

The read chain surfaces both parties as `CommercialRow` (`persistence/.../SchedulerSeed.kt`):
`clientCode/clientName` = advertiser (`cu`), `payerCode/payerName` = payer (`pay`), `contract` = the number. The Break Console prints only the advertiser + contract number; the payer is available but unshown.

Migration provenance (how the advertiser gets onto `spots.customer_id`): `docref.targetleeid` = the triangular end-client the agency's contract targets; `docref.traid` = the trader/payer. The migration resolves `targetleeid` (+ a lee↔tra map) onto `spots.customer_id`; unresolved end-clients get code `LEE-<id>`. Full detail in `cus-lee-tra-triangular` memory.

---

## 3. Worked example — **ΤΡΙΓΩΝΙΚΗ** (LOREAL via TEMPO OMD)

Break **CRETE TV · Παρασκευή 17/07/2026 · 16:00** (Ζώνη DEFAULT, programme «ΚΑΛΟ ΜΕΣΗΜΕΡΙ / Χριστιάνα Σκούρα»), row 1. Spot `spots.id = 54470` «LOREAL Elvive Glycolic Gloss 07/07-22/07», contract **645**.

**Advertiser — LOREAL (LEE-only, no TRACODE):**

| field | value |
|---|---|
| `customers.id` | 2919 |
| `customers.code` | **`LEE-3085`** |
| name | LOREAL HELLAS A.E |
| `vat_number` (ΑΦΜ) | 094026261 |
| `legacy_id` (TRAID) | **NULL** |
| `legacy_lee_id` (LEEID) | 3085 |
| `sen_lee[3085]` | LOREAL HELLAS A.E / 094026261 |
| `sen_cus WHERE LEEID=3085` | **∅ empty** ← proof it never traded |

**Payer — TEMPO OMD (a full trader):**

| field | value |
|---|---|
| `customers.id` | 7 |
| `customers.code` (TRACODE) | **`01000012`** |
| name | TEMPO OMD HELLAS AE |
| ΑΦΜ | 094352306 |
| `legacy_id` (TRAID) | 9 |
| `legacy_lee_id` (LEEID) | 10 |
| `sen_cus` | TRAID 9 ↔ LEEID 10, TRACODE 01000012 |

So: **we hold contract 645 with the agency TEMPO OMD**, which buys spots on behalf of LOREAL; LOREAL is only the advertised brand and has **no customer code of its own** (only `LEE-3085`). Advertiser code (`LEE-3085`) ≠ payer code (`01000012`) → triangular.

---

## 4. Worked example — **ΑΠΕΥΘΕΙΑΣ** (ΤΙΓΚΙΡΗΣ ΔΑΥΙΔ)

Same break, row 2, contract **525**. The customer came to us directly, so it wears **both hats** — the CUS row links its TRAID to **its own** LEEID:

| field | value |
|---|---|
| `customers.id` | 2815 |
| `customers.code` (TRACODE) | **`30030937`** |
| name | ΤΙΓΚΙΡΗΣ ΔΑΥΙΔ |
| `vat_number` (ΑΦΜ) | 072523463 |
| `legacy_id` (TRAID) | 7841 |
| `legacy_lee_id` (LEEID) | 10994 |
| `sen_cus` | TRAID **7841** ↔ LEEID **10994**, TRACODE 30030937, ADRIDMAIN 13044, TRASTARTDATE 2026-04-17 |
| `sen_lee[10994]` | ΤΙΓΚΙΡΗΣ ΔΑΥΙΔ / 072523463 |

Advertiser (`spots.customer_id`) code == payer (`contracts.customer_id`) code == **`30030937`** → direct. Its «Κωδ. Πελ.» is a real `TRACODE`; contrast LOREAL, which has none.

### Side-by-side

| | **LOREAL** (triangular) | **ΤΙΓΚΙΡΗΣ** (direct) |
|---|---|---|
| LEEID (party/advertised) | 3085 | 10994 |
| TRAID (trader/payer) | **— none** | 7841 |
| CUS (TRAID↔LEEID) | **— none** | 7841 ↔ 10994 (self) |
| TRACODE («Κωδ. Πελ.») | **— none** | 30030937 |
| ΑΦΜ | 094026261 | 072523463 |
| `customers.code` in our DB | `LEE-3085` | `30030937` |
| who pays us | agency `01000012` TEMPO OMD | itself |

---

## 5. Reference data — the full 16:00 break (advertiser → payer)

Only row 1 is triangular; the rest are direct (`advertiser code == payer code`). Note row 9 (ΚΡΕΤΑ ΦΑΡΜ) is «Αγορά Αθήνα» yet **direct**.

| # | advertiser code | advertiser | payer code | payer | contract | model |
|---|---|---|---|---|---|---|
| 1 | `LEE-3085` | LOREAL HELLAS A.E | `01000012` | TEMPO OMD HELLAS AE | 645 | **τριγωνική** |
| 2 | `30030937` | ΤΙΓΚΙΡΗΣ ΔΑΥΙΔ | `30030937` | ΤΙΓΚΙΡΗΣ ΔΑΥΙΔ | 525 | άμεση |
| 3 | `30000445` | ΠΛΟΥΜΗ-ΠΑΡΑΓΙΟΥΔΑΚΗΣ | `30000445` | (ίδιος) | 371 | άμεση |
| 4 | `30003653` | ΚΑΦΑΝΤΑΡΗΣ Γ & ΣΙΑ Ο.Ε | `30003653` | (ίδιος) | 398 | άμεση |
| 5 | `30000180` | ΔΑΝΔΑΛΗΣ Α.Τ. & ΥΙΟΙ | `30000180` | (ίδιος) | 674 | άμεση |
| 6 | `30002651` | ΣΤΕΦΑΝΑΚΗΣ ΕΜΜΑΝΟΥΗΛ | `30002651` | (ίδιος) | 375 | άμεση |
| 7 | `30003299` | LIFE CARE HEALTH | `30003299` | (ίδιος) | 817 | άμεση |
| 8 | `30003303` | ΣΑΒΒΑΚΗΣ ΠΛΑΚΑΚΙΑ | `30003303` | (ίδιος) | 500 | άμεση |
| 9 | `30003678` | ΚΡΕΤΑ ΦΑΡΜ ΤΡΟΦΙΜΩΝ | `30003678` | (ίδιος) | 112 | άμεση |

---

## 6. Re-query recipes

DB is the docker container **`local-mysql`** (root / `rootpass123`); Crete TV lives in group schema **`commercials_crete_group`**. Prefix every query:

```
docker exec local-mysql mysql --default-character-set=utf8mb4 -uroot -prootpass123 commercials_crete_group -e "…"
```

**Advertiser → payer for a whole break:**

```sql
SELECT (p.position+1) AS n,
       cu.code AS advertiser_code, cu.name AS advertiser,
       pay.code AS payer_code,     pay.name AS payer,
       ct.number AS contract,
       IF(cu.id = pay.id, 'direct', 'triangular') AS model
FROM placements p
JOIN spots s      ON s.id = p.spot_id
JOIN customers cu ON cu.id = s.customer_id
LEFT JOIN contract_lines cl ON cl.id = COALESCE(p.contract_line_id, s.contract_line_id)
LEFT JOIN contracts ct      ON ct.id = cl.contract_id
LEFT JOIN customers pay     ON pay.id = ct.customer_id
WHERE p.show_date='2026-07-17' AND p.show_time='16:00:00'
  AND s.station_id='crete-tv' AND p.hidden=0
ORDER BY p.position;
```

**Is a party LEE-only (triangular end-client)?** → its `sen_cus` is empty:

```sql
SELECT (SELECT COUNT(*) FROM sen_cus WHERE LEEID = c.legacy_lee_id) AS cus_rows,
       c.code, c.name, c.legacy_id AS traid, c.legacy_lee_id AS leeid
FROM customers c WHERE c.code = 'LEE-3085';        -- cus_rows = 0 ⇒ pure end-client
```

**Every triangular airing in a station** (advertiser ≠ payer):

```sql
SELECT DISTINCT cu.code AS advertiser, pay.code AS payer, pay.name AS agency
FROM placements p
JOIN spots s ON s.id=p.spot_id
JOIN customers cu ON cu.id=s.customer_id
JOIN contract_lines cl ON cl.id=COALESCE(p.contract_line_id,s.contract_line_id)
JOIN contracts ct ON ct.id=cl.contract_id
JOIN customers pay ON pay.id=ct.customer_id
WHERE s.station_id='crete-tv' AND cu.id <> pay.id;
```

---

## 7. Code / doc pointers

- Join & read: `persistence/src/main/kotlin/.../scheduler/StationDb.kt` (`loadMonth`, `placementRow`) — advertiser `cu` vs payer `pay`.
- Row model: `persistence/.../scheduler/SchedulerSeed.kt` `CommercialRow` (`clientCode/Name`, `payerCode/Name`, `salesItem`, `type`, `contract`).
- MCP: `mcp/.../tools/feature/SpotsInBreakTool.kt` (currently emits advertiser only; `payerCode/Name` NOT yet exposed).
- Break Console UI: `feature/timetable/.../commercial_detail/CommercialDetailScreen.kt`.
- Migration history / party model depth: `cus-lee-tra-triangular` memory; `migration/legacy-schema.md`.

---

## 8. Open questions for the future model work (owner will drive)

Design prompts, not decisions — surface these when we resume:

1. **Visibility.** Should the Break Console / reports / `spots_in_break` show the **payer/agency** next to the advertiser for triangular rows (data already on `CommercialRow.payerCode/Name`)? Behind a flag, or always?
2. **Contract creation per model.** When staff create/edit a contract: direct = one party (self); triangular = pick an **agency (TRA)** as payer **and** a **brand (LEE)** as advertiser. Does the UI need a two-party picker for triangular?
3. **Billing / statements.** Money is owed by the **payer (TRA)**; performance/airings belong to the **advertiser (LEE)**. Any statement/export must not conflate them (an agency pays for many brands; a brand may run through several agencies — LOREAL used TEMPO OMD, MEDIATECH, INTIMA, MEDIA CONNECTION, MAVE, ΡΟΥΜΠΙΝΗΣ over time).
4. **End-client without a TRACODE.** LEE-only brands have no «Κωδ. Πελ.». Is `LEE-<id>` an acceptable permanent code, or should an end-client be promotable to a real customer if it ever transacts directly?
5. **Customer search / scoping.** The schedule-email search already threads a customer|trader `kind`; confirm any new feature picks the correct join path (advertiser vs payer) so agencies aren't merged with brands.
