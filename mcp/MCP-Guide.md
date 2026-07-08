# MCP Server — setup guide (stdio + HTTP)

How to connect the Commercials Manager MCP server to an MCP client. Two
transports, same tools and same per-station authorization:

- **stdio** — the client spawns the launcher as a subprocess. Self-contained
  (no HTTP server needed): the launcher connects straight to MySQL. Best for
  desktop/CLI apps: **Claude Code, Claude Desktop, Cursor**.
- **HTTP / SSE** — the MCP endpoint is mounted in the Ktor server at `/mcp`
  under bearer auth. Best for network/remote clients and for a **plain web chat**
  (claude.ai custom connector) — see §4.

---

## 0. Prerequisites

- **MySQL** reachable (dev: docker `local-mysql` on `localhost:3306`).
- A **bearer token** (identifies the caller; tools run scoped to its grants).
- For stdio: the launcher built once —
  ```bash
  ./gradlew :mcp-stdio:installDist
  # -> mcp-stdio/build/install/mcp-stdio/bin/mcp-stdio
  ```
- For HTTP: the server built/runnable — `./gradlew :server:run`.

### Token lifecycle (read this — it answers "do I need to run a server?")

**What a token is.** The same bearer token the app's login issues: a random
256-bit value. `POST /api/auth/login` returns it once; the server stores only its
SHA-256 hash in the `auth_tokens` table of the **central DB**. The MCP server
runs *as that user*, scoped to its station grants.

**You start a server ONCE, only to MINT the token — not to run MCP.** The stdio
launcher connects straight to MySQL and validates the token against the central
DB; it needs **no HTTP server running**. So the flow is:

```bash
# 1. (one-time) start any server that uses THIS server.yaml, so the token lands
#    in the matching central DB:
COMMERCIALS_PORT=8099 ./gradlew :server:run

# 2. log in to mint a token (super-admin su/1234 from server.yaml):
curl -s -X POST http://localhost:8099/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"su","password":"1234"}'          # -> {"token":"…"}

# 3. stop that server (Ctrl+C). The token stays valid in the DB.
```

Paste the token into the client config (§1–2). Day-to-day you only need **docker
`local-mysql` up** — no server.

**When does it change / expire?** Per the `session:` block in `server.yaml`. Dev
default is `expiration: true, days: 90, sliding: true`:
- **Sliding**: every MCP call slides the 90-day window forward, so a token you
  keep using **effectively never expires**. Only an *unused* token dies after 90 days.
- A token is also revoked by an explicit **logout** or a **password change**, or
  if the central DB is reset/re-seeded.

**Refreshing a token** = mint a new one (steps above) and update the config:
- Claude Code: `claude mcp remove commercials-manager -s user` then `claude mcp add …` with the new token.
- Claude Desktop / Cursor: edit the `COMMERCIALS_MCP_TOKEN` value and restart the app.

**Tired of tokens for a personal setup?** Set `expiration: false` in
`server.yaml`'s `session:` block → tokens never expire (revoked only by logout /
password change). Note this changes the whole app's session policy, not just MCP.

---

## 1. stdio — Claude Code (you are here)

```bash
claude mcp add commercials-manager -s user \
  -e COMMERCIALS_MCP_TOKEN=<token> \
  -e COMMERCIALS_SERVER=/ABS/PATH/poc-commercials/server.yaml \
  -- /ABS/PATH/poc-commercials/mcp-stdio/build/install/mcp-stdio/bin/mcp-stdio
```
- `-s user` = available in every project, and the token stays out of the repo.
- Verify: `claude mcp list` (look for `✔ Connected`), then `/mcp` in a **new** session.
- Remove/re-add to change env: `claude mcp remove commercials-manager -s user`.

## 2. stdio — Claude Desktop / Cursor (same JSON shape)

Claude Desktop: `~/Library/Application Support/Claude/claude_desktop_config.json`
(Cursor: `~/.cursor/mcp.json`). Add:
```json
{
  "mcpServers": {
    "commercials-manager": {
      "command": "/ABS/PATH/poc-commercials/mcp-stdio/build/install/mcp-stdio/bin/mcp-stdio",
      "env": {
        "COMMERCIALS_MCP_TOKEN": "<token>",
        "COMMERCIALS_SERVER": "/ABS/PATH/poc-commercials/server.yaml"
      }
    }
  }
}
```
Restart the app. (stdout is the protocol channel; the launcher logs to stderr.)

---

## 3. HTTP / SSE — local client against a running server

Run the server (it mounts `/mcp`), then point an SSE client at it with the token:
```bash
COMMERCIALS_PORT=8099 ./gradlew :server:run

# Claude Code over SSE (localhost passes the SDK's DNS-rebinding host check):
claude mcp add commercials-http -s user --transport sse \
  http://localhost:8099/mcp \
  --header "Authorization: Bearer <token>"
```
Or inspect interactively with the MCP Inspector:
```bash
npx @modelcontextprotocol/inspector
#   Transport: SSE   URL: http://localhost:8099/mcp
#   Header:    Authorization: Bearer <token>
```
The SSE handshake: `GET /mcp` returns `event: endpoint` with a relative
`?sessionId=…`; the client POSTs JSON-RPC there. CORS already exposes the
`Mcp-Session-Id` / `Mcp-Protocol-Version` headers.

---

## 4. Plain web chat (claude.ai) — remote custom connector

Possible for a **real deployment**, not for localhost as-is. claude.ai (Pro/Max/
Team/Enterprise) can add a *custom connector* pointing at a **public HTTPS** MCP
URL (Settings → Connectors → Add custom connector). Requirements & caveats:

1. **Public HTTPS.** Expose the server with a tunnel, e.g.:
   ```bash
   cloudflared tunnel --url http://localhost:8099 \
       --http-host-header localhost:8099
   ```
   `--http-host-header` is **required**: the `/mcp` route keeps the SDK’s
   DNS-rebinding protection with localhost defaults, so the origin must still
   see `Host: localhost:8099`. (Alternatively, make `allowedHosts` configurable
   in `server/.../plugins/Mcp.kt` and pass the public host.)
2. **Auth.** The endpoint requires `Authorization: Bearer <token>`. claude.ai’s
   connector flow is **OAuth-oriented**; a static bearer may not be settable in
   its UI. A production setup would add an OAuth front (or an auth proxy that
   injects the bearer). For quick trials, the stdio clients (§1–2) or the local
   SSE client (§3) avoid this entirely.
3. **Transport.** We expose **SSE** (`Route.mcp`). If a client requires
   Streamable HTTP, switch `configureMcp()` to `mcpStreamableHttp` (a small
   change; both ship in the SDK).

**Bottom line:** for a laptop/dev trial use stdio (Claude Code/Desktop/Cursor)
or local SSE; use the claude.ai custom connector only when the server is
deployed publicly with proper auth.

---

## 5. Enabling mutations (write tools)

Default is **read-only** (9 tools). To expose `add_placement`,
`delete_placement`, `reorder_placements`, `send_schedule_email` (13 tools), set
`COMMERCIALS_MCP_MUTATIONS=true` in the server/launcher environment:
- Claude Code: re-add with an extra `-e COMMERCIALS_MCP_MUTATIONS=true`.
- Claude Desktop/Cursor: add it to the `env` block.
- HTTP server: `COMMERCIALS_MCP_MUTATIONS=true COMMERCIALS_PORT=8099 ./gradlew :server:run`.

Mutations require NORMAL_USER and default to a **dry-run preview** — nothing is
written or sent unless the tool is called with `confirm=true`. Every performed
write is audit-logged.

---

## 6. Try it — example prompts

| Ask | Tool |
|---|---|
| "Which stations can I access?" | `list_stations` |
| "In my-sample, how many placements and over what date range?" | `station_footprint` |
| "Search parties containing 'ΝΟΒΑ' on crete-tv" | `search_parties` |
| "Show the spots in the 21:00 break on 2005-09-12 in my-sample" | `spots_in_break` |
| "Generate the printout for that break" | `generate_break_report` (PDF) |
| "How long since customer 00000025 last aired on my-sample? Any active contracts?" | `contract_status` / `party_activity` |

---

## 7. Troubleshooting

- **`COMMERCIALS_MCP_TOKEN is not a valid/active token`** — the token wasn’t
  minted against the same central DB as `COMMERCIALS_SERVER`, or it expired.
  Mint a new one (§0) from a server using this `server.yaml`.
- **Not connected / exits immediately** — MySQL unreachable, or `server.yaml`
  path wrong. Check the launcher’s stderr.
- **Only 9 tools, expected 13** — mutations are off; set
  `COMMERCIALS_MCP_MUTATIONS=true` (§5).
- **Write tool says "Requires full (NORMAL_USER) access"** — the token’s grant
  on that station isn’t NORMAL_USER.
- **claude.ai can’t reach it** — localhost isn’t public; see §4.

See `MCP.md` for the full change log and architecture.
