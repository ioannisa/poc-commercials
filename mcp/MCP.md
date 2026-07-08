# MCP Server — change log & reference

An MCP (Model Context Protocol) server that exposes the Commercials Manager
backend to LLM clients (Claude and any MCP client). Kotlin-native, built on the
official SDK `io.modelcontextprotocol:kotlin-sdk-server` **0.14.0**.

Two decisive design facts:

- **Both use-cases live in the backend — the Compose app is NOT involved.** The
  report engine (`reportcore.ReportEngine`, JasperReports) and the data
  (`persistence.StationDb`) are already server-side, so reports are produced
  headlessly.
- **One tool core, two transports.** Tool logic is written once in `:mcp` and
  bound to a transport by each host, with the SAME per-station grant/role checks.

This document is a review checklist: every file created or changed, why, and how
to run/verify.

---

## 1. Modules & build wiring

| File | Change |
|---|---|
| `settings.gradle.kts` | `include(":mcp")`, `include(":mcp-stdio")` |
| `gradle/libs.versions.toml` | version `mcpKotlinSdk = "0.14.0"`; libs `mcp-kotlin-sdk-server`, `ktor-server-sse` |
| `mcp/build.gradle.kts` | **NEW** — JVM lib. `api(mcp-kotlin-sdk-server)` + `api(projects.persistence)` (their types are in `:mcp`'s public API); `implementation(reportcore, mailer)` |
| `mcp-stdio/build.gradle.kts` | **NEW** — JVM `application`; `mainClass …mcp.stdio.MainKt`; `implementation(projects.mcp)` + logback; `installDist` used for the launcher |

The SDK artifact brings `ktor-server-sse` + the Ktor `mcp()` helpers
transitively; `ktor-server-sse` is also declared explicitly on `server` for the
compile-time `install(SSE)`.

---

## 2. `:mcp` — the tool core (`mcp/src/main/kotlin/eu/anifantakis/commercials/mcp/`)

All files below are **new**.

| File | Responsibility |
|---|---|
| `CommercialsMcpServer.kt` | `buildCommercialsMcpServer(caller, services): Server` — builds a per-session server, registers read + report tools, and mutation tools only when `services.mutationsEnabled`. |
| `McpCaller.kt` | Identity wrapper over `AuthUser` (same object the HTTP bearer principal carries), so authz is identical across transports. |
| `McpToolServices.kt` | The backend facade: `resolveStation` (mirrors `Security.stationAccessOrRespond`: missing→required / no-grant→No access / unhosted→Unknown), `stations`, `isCustomerScoped`/`requireCode` (CUSTOMER_VIEWER scoping), `resolveBreak`/`breakSpots`, `generatePdf`/`saveReport`, and the mutation guardrails `requireStaff`/`smtpFor`/`stationName`/`audit`. Also the top-level `mcpMutationsEnabled()` (reads `COMMERCIALS_MCP_MUTATIONS`, default-deny). |
| `ToolSupport.kt` | Infrastructure: `runTool`/`runToolBlocks` (run OFF the transport thread on `Dispatchers.IO` — JDBC is blocking — and map `McpToolException`→clean tool error, anything else→logged generic error); `Args` typed arg parsing (`string/int/long/bool/longList/longListOrNull`); `dryRun` payload; schema builders `inputSchema`/`prop`/`propArray`; `parseIsoDate`. |
| `ReadTools.kt` | The 8 query tools (see §5). |
| `ReportTools.kt` | `generate_break_report` — assembles rows, calls `ReportEngine.generatePdf`, returns a JSON summary + the PDF as an `EmbeddedResource` (`application/pdf`), and writes it to the report output dir. |
| `BreakReportAssembler.kt` | Server-side Program-Flow assembly (`CommercialRow` → `reportcore.ReportRequest`). **Duplicate** of the client's `ReportDataFactory` + `toReportPayload` — TODO to extract a shared module (see §7). Holds the backend copy of `FLOW_ROH`. |
| `MutationTools.kt` | The 4 write tools (see §5), each: `requireStaff` (NORMAL_USER) + `confirm`/dry-run + audit. |
| `ScheduleEmailAssembler.kt` | Server-side `ScheduleEmailData` assembly. **Faithful duplicate** of `server`'s `EmailRoutes.assembleScheduleEmail` (`:mcp` can't depend on `server`) — TODO to extract (see §7). Deliberately does NOT call `ensureMonthSeeded` (never fabricate demo data for a real email). |

### Tests (`mcp/src/test/kotlin/…/mcp/`) — 18 tests

| File | Covers |
|---|---|
| `McpTestSupport.kt` | DB-free fixtures (`caller`, `grant`, `station`, `registryOf`). |
| `McpToolServicesTest.kt` (6) | station listing + `resolveStation` error paths + `requireCode` scoping, no live DB. |
| `ToolSupportTest.kt` (5) | `Args` scalar + array parsing, `runTool` success/error mapping. |
| `BreakReportAssemblerTest.kt` (4) | template param/field names, per-row totals, `FLOW_ROH`→notes, `excludeFromReports`. |
| `MutationGuardTest.kt` (3) | kill switch tool-count (9 vs 13), `requireStaff` rejects non-NORMAL_USER. |

---

## 3. `:mcp-stdio` — the stdio entrypoint

| File | Change |
|---|---|
| `mcp-stdio/src/main/kotlin/…/mcp/stdio/Main.kt` | **NEW** — boots the persistence/auth stack directly (no Ktor/Koin): `loadHostingConfig()` → `StationRegistry` + `CentralDb` + `AuthDb`, `authDb.bootstrap()`, resolves the caller from `COMMERCIALS_MCP_TOKEN` via `findUserByToken`, then `StdioServerTransport` + `server.createSession()`. Redirects `System.out`→stderr and keeps the real stdout only for the MCP channel. |
| `mcp-stdio/src/main/resources/logback.xml` | **NEW** — all logging to `System.err` (stdout is the protocol channel). |
| `mcp-stdio/README.md` | **NEW** — build (`installDist`), env vars, and the Claude Desktop `mcpServers` snippet. |

---

## 4. Changes to existing modules

### `server` (HTTP transport)
| File | Change |
|---|---|
| `server/build.gradle.kts` | `implementation(projects.mcp)` + `implementation(libs.ktor.server.sse)` |
| `server/.../plugins/Mcp.kt` | **NEW** `configureMcp()` — `install(SSE)` + `authenticate(AUTH_BEARER) { mcp("/mcp") { buildCommercialsMcpServer(McpCaller.of(call.authUser()), services) } }`. Per-session, grant-scoped. |
| `server/.../plugins/CORS.kt` | allow/expose `Mcp-Session-Id`, `Mcp-Protocol-Version`. |
| `server/.../di/ServerModule.kt` | `single<McpToolServices> { McpToolServices(registry = get(), mutationsEnabled = mcpMutationsEnabled()) }`. |
| `server/.../Application.kt` | call `configureMcp()` after `configureRouting()`. |

### `persistence` (contract dates + read)
| File | Change |
|---|---|
| `persistence/.../scheduler/StationDb.kt` | `contracts` table: added `start_date` / `end_date` / `renewed_at` / `dates_provisional` (in CREATE TABLE **and** via guarded `ensureColumn`, mirroring the existing evolution pattern). Added `contractStatus(code, byTrader)` + `ContractStatusRow` (per-contract dates + aired range). |

### `migration` (provisional backfill)
| File | Change |
|---|---|
| `migration/.../LegacyTransformer.kt` | `run()` calls `backfillProvisionalContractDates()` after `migratePlacements()`; the new method derives each contract's `start_date`/`end_date` from `MIN/MAX(placements.show_date)` and sets `dates_provisional=TRUE`. TODO: replaced by a real Oracle ERP import. |

---

## 5. Tools (13)

**Query (8)** — each reuses an existing `StationDb` read; CUSTOMER_VIEWER callers only see their own client code:
`list_stations`, `search_parties`, `party_activity`, `party_contracts`,
`contract_spots`, `contract_status`, `spots_in_break`, `station_footprint`.

**Report (1):** `generate_break_report` — headless Program-Flow PDF for one break.

**Mutation (4)** — only when mutations are enabled; NORMAL_USER + `confirm`/dry-run + audit:
`add_placement`, `delete_placement`, `reorder_placements`, `send_schedule_email`.

### Environment knobs
| Var | Where | Meaning |
|---|---|---|
| `COMMERCIALS_MCP_MUTATIONS` | server + stdio | **Default-DENY.** When unset/false the 4 write tools are not registered at all (read-only server). Truthy values: `1/true/yes/on`. |
| `COMMERCIALS_MCP_TOKEN` | stdio only | The bearer token the stdio server runs as. |
| `COMMERCIALS_SERVER` | stdio only | Path to `server.yaml` (else `./server.yaml`). |

### Authorization (identical over HTTP & stdio)
- Station resolved from the caller's grants (`resolveStation`), reads allowed for any role.
- CUSTOMER_VIEWER: reads scoped to their own client code; `contract_spots` (keyed by line id) is refused.
- Mutations: `requireStaff` → NORMAL_USER only.

---

## 6. Contract dates (Phase 6) — the honest design

The legacy MySQL is Oracle-ERP-mastered and never carried contract periods, so
`start_date`/`end_date` are **PROVISIONAL** (derived from airings, flagged
`dates_provisional=TRUE`) until a real ERP import overwrites them; `renewed_at`
has no source yet and stays NULL.

`contract_status` therefore returns the provisional dates (clearly flagged) **and**
the aired range; "how long since customer X renewed" is answered from `lastAired`
(activity recency), never from fake dates.

---

## 7. Known debt (marked with TODOs in code)

- `BreakReportAssembler` and `ScheduleEmailAssembler` duplicate the client's
  report assembly and `EmailRoutes.assembleScheduleEmail` respectively (because
  `:mcp` cannot depend on the client/server). Future: extract a shared
  `:schedule-email` / reports-model module (deps: persistence + mailer) consumed
  by the REST route AND `:mcp`.
- `send_schedule_email confirm=true` (a real SMTP send) is code-reviewed but was
  not exercised live (needs configured SMTP).

---

## 8. Build & run

```bash
# Build everything MCP-related
./gradlew :mcp:build :mcp-stdio:installDist :server:build

# HTTP transport — mounted in the server at /mcp (SSE, bearer auth)
COMMERCIALS_PORT=8099 [COMMERCIALS_MCP_MUTATIONS=true] ./gradlew :server:run
#   handshake: GET /mcp (Authorization: Bearer <token>, Accept: text/event-stream)
#   -> `event: endpoint` gives a relative `?sessionId=...`; POST JSON-RPC there.

# stdio transport — for Claude Desktop / CLI (see mcp-stdio/README.md)
COMMERCIALS_MCP_TOKEN=<token> COMMERCIALS_SERVER=$PWD/server.yaml \
  mcp-stdio/build/install/mcp-stdio/bin/mcp-stdio
```

Get a token via `POST /api/auth/login` (e.g. super-admin `su`/`1234` from `server.yaml`).

---

## 9. Verification performed (all live against the 8099 test server + docker `local-mysql`)

- **HTTP:** `initialize` → serverInfo; `tools/list`; unauth `/mcp` → 401;
  `list_stations` (grant-scoped); `station_footprint(my-sample)` → 2,671,670
  placements (real DB); `generate_break_report` → 57 KB `%PDF-` (embedded + on disk).
- **stdio:** spawned the launcher, MCP handshake over stdin/stdout, logs on stderr,
  same read results, clean shutdown on EOF.
- **Mutations:** kill switch (13 tools on / 9 off); `add_placement` dry-run (no
  write) → confirm (placement #33480) → `spots_in_break`=1 → `delete_placement` →
  `spots_in_break`=0; `send_schedule_email` dry-run rendered ≈1.576 MB HTML,
  byte-identical to the existing REST preview, and did NOT send.
- **Contract dates:** columns added via bootstrap; backfill SQL → 7 provisional
  contracts on crete-tv; `contract_status` showed the provisional path (crete-tv)
  and the recency fallback (my-sample: `lastAired` populated, dates null).
- **Test suites:** `:mcp:test` (18), `:server:test` (Koin graph), `:shared:jvmTest`
  (ArchitectureTest + KoinGraph) all green.
