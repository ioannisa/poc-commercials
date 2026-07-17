# MCP access (Model Context Protocol)

The server exposes its tools to MCP-capable AI clients over **two transports**,
both running every session **as the authenticated user** — every tool is scoped
by that user's per-station grants and role. A `REPORT_VIEWER` gets read-only
tools, a `CUSTOMER_VIEWER` sees only their own spots, and so on.

| Endpoint | Transport | Auth | For |
|---|---|---|---|
| `/mcp/http` | **Streamable HTTP** | **OAuth 2.1** (built-in) or a PAT | Native connectors (claude.ai, ChatGPT, Gemini, …), IDEs, headless clients |
| `/mcp` | classic SSE | PAT via `mcp-remote` | Legacy `mcp-remote` bridges |

MCP is an open protocol: this is **not** Claude-specific. Any MCP client can
connect.

## 1. Native connectors (OAuth) — the easy path

With the server deployed on a public HTTPS origin (see §4), any native MCP
connector connects with **one URL and a login** — no tokens, no config files:

> **`https://<server-host>/mcp/http`**

- **claude.ai / Claude Desktop / Claude mobile**: Settings → Connectors →
  *Add custom connector* → paste the URL → **Connect** → log in with your
  Commercials Manager username/password on the page that opens.
- **ChatGPT** (developer mode, Pro/Plus/Business/Enterprise): Settings →
  Connectors → add the same URL.
- **Gemini, Cursor, VS Code (Copilot agent mode), Zed, Claude Code, …**: add it
  as a remote MCP server; the client discovers the OAuth endpoints and opens the
  login page itself.

What happens underneath: the server is its own OAuth 2.1 Authorization Server
(discovery per RFC 8414/9728, dynamic client registration per RFC 7591,
authorization-code + PKCE). The login page **is** your normal app login — the
minted token carries *your* identity, so your grants and role apply unchanged.
Access lasts until you disconnect the connector, an admin revokes the grant, or
you change your password (which, as always, revokes your sessions — OAuth
grants included).

Enter the URL **without a trailing slash**, and make sure the host serves it
**without redirects** (a `301`/`308` on the MCP URL strips the `Authorization`
header on many clients — the top real-world connector failure).

### Clients without dynamic registration (operator recipe)

Some enterprise surfaces (Gemini Enterprise, Microsoft 365 federated
connectors, Copilot Studio "manual" mode, Perplexity's manual option, Cursor's
static mode) ask for a **Client ID / Client Secret + endpoint URLs** instead of
registering themselves. Mint them a client via the open registration endpoint:

```bash
curl -s -X POST https://<server-host>/oauth/register \
  -H "Content-Type: application/json" \
  -d '{"client_name": "Gemini Enterprise", "token_endpoint_auth_method": "client_secret_post",
       "redirect_uris": ["<the redirect URI that surface documents>"]}'
```

Copy `client_id` + `client_secret` from the response into their form, plus:
authorization endpoint `https://<server-host>/oauth/authorize`, token endpoint
`https://<server-host>/oauth/token`, scope `offline_access`. Known redirect
URIs: Gemini Enterprise `https://vertexaisearch.cloud.google.com/oauth-redirect`,
M365 federated `https://teams.microsoft.com/api/platform/v1.0/oAuthRedirect`,
Perplexity `https://www.perplexity.ai/rest/connections/oauth_callback`, Cursor
static `https://www.cursor.com/agents/mcp/oauth/callback` and
`http://localhost:8787/callback`.

Clients with **no OAuth at all** (JetBrains AI Assistant, API-level MCP tools
of the OpenAI/xAI/Google/Anthropic platforms) send a static header instead —
give them a **PAT** (§2) as `Authorization: Bearer <token>` against the same
`/mcp/http` URL.

## 2. Personal access tokens (headless / legacy path)

In the app: **Preferences → MCP / API Access → Generate token**.

- The token is shown **once** — copy it now. It **never expires**; revoke it
  from the same screen when a machine is retired.
- It works on **both** endpoints: as a bearer header on `/mcp/http`, or via
  `mcp-remote` on the classic `/mcp` SSE endpoint.
- The token carries **your** access. To act with different restrictions, log in
  as that user and mint a token from their account.

### Classic SSE via `mcp-remote` (config-file clients)

In Claude Desktop's `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
"commercials-manager": {
  "command": "npx",
  "args": [
    "-y", "mcp-remote",
    "https://<server-host>/mcp",
    "--transport", "sse-only",
    "--header", "Authorization:${AUTH_HEADER}"
  ],
  "env": { "AUTH_HEADER": "Bearer <your-token>" }
}
```

- `--transport sse-only` is **required**: without it `mcp-remote` tries Streamable
  HTTP first and fails with `sessionId not provided`, because `/mcp` exposes
  the classic SSE transport.
- The `Authorization:${AUTH_HEADER}` + `env` split lets `mcp-remote` inject the
  token (a bare `Bearer x` in the arg trips its header parsing on the space).

## 3. Local development

`publicBaseUrl: "http://localhost:8080"` in the dev `server.yaml` turns the
OAuth endpoints on locally. Local clients that run their own OAuth flow work
against plain-HTTP localhost:

- **MCP Inspector**: `npx @modelcontextprotocol/inspector`, transport
  *Streamable HTTP*, URL `http://localhost:8080/mcp/http` → it discovers,
  registers, and opens the login page.
- **Claude Code**: `claude mcp add --transport http commercials http://localhost:8080/mcp/http`
  then `/mcp` → *Authenticate*.
- **`mcp-remote` without `--header`** falls back to interactive OAuth.

The cloud connectors (claude.ai, ChatGPT, …) connect **from the vendor's
infrastructure** — they can never reach `localhost` or an intranet-only host,
OAuth or not. Public HTTPS (§4) is what unlocks them.

## 4. Deployment (server operators)

- **Terminate TLS in front** (nginx, Caddy, …). Both MCP endpoints and the
  OAuth pages carry credentials; `http://localhost` is for development only.
  Do not redirect the MCP URL (serve it directly on its final host+path).
- In `server.yaml`:

  ```yaml
  # The public origin = the OAuth issuer. Setting it mounts /oauth/* and the
  # /.well-known/* discovery documents. Unset = OAuth off (PATs still work).
  publicBaseUrl: "https://mcp.example.gr"

  # Host-header allowlist for the MCP endpoints' DNS-rebinding guard.
  mcpAllowedHosts:
    - mcp.example.gr

  # TLS terminates at the proxy: trust X-Forwarded-* so rate limiting and
  # logs see real client IPs. Never enable without a proxy.
  behindReverseProxy: true
  ```

- The connector URL needs an **IPv4 `A` record** on a globally routable address
  — claude.ai/ChatGPT connect from their cloud and skip hosts that resolve to
  private ranges.
- Auth endpoints are **per-IP rate limited** out of the box (10/min on
  login/authorize/forgot/reset, 60/min on register/token/revoke).

## 5. Admin oversight

**Preferences → MCP oversight** (super admin only):

- **Kill switch** — one toggle disables **all PATs and all OAuth grants** at
  once (REST + `/mcp` + `/mcp/http`). App logins keep working.
- **PATs** — see every workstation token, its owner, last use; revoke any.
- **OAuth grants** — `GET/DELETE /api/admin/oauth-tokens`: every
  native-connector login (user, client app, last use), each revocable
  (the connector then has to re-authenticate).

## Security notes

- Every credential (PATs, OAuth codes, access/refresh tokens, client secrets)
  is stored **hashed** (SHA-256); raw values exist only client-side. A DB leak
  yields nothing usable.
- OAuth access tokens live **1 hour**; refresh tokens **rotate** on every use
  and die after 90 idle days. Authorization codes are single-use, 120 s.
- The consent page appears on **every** connection — with open registration,
  auto-consent keyed on a client id would be an account-takeover primitive.
- A password change revokes the user's sessions **and OAuth grants**; PATs
  survive (machine credentials, revoked explicitly).
- A PAT is a full credential for that user's API surface. Treat it like a
  password: don't commit it, don't share it, revoke it when a machine is lost.
