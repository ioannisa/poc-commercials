# MCP access (Model Context Protocol)

The server exposes its tools to MCP-capable AI clients (Claude Desktop, Cursor,
Zed, custom agents, …) over **SSE at `/mcp`**, authenticated with a **personal
access token (PAT)**. A session runs **as the token's user**, so every tool is
scoped by that user's per-station grants and role — a `REPORT_VIEWER` gets
read-only tools, a `CUSTOMER_VIEWER` sees only their own spots, and so on.

MCP is an open protocol: this is **not** Claude-specific. Any MCP client that
speaks SSE/HTTP can connect.

## 1. Get a token (self-service)

In the app: **Preferences → MCP / API Access → Generate token**.

- Give it a name (e.g. `My laptop`).
- The token is shown **once** — copy it now. It **never expires**; revoke it
  from the same screen when a machine is retired.
- The screen also offers **Copy config (JSON)** — the ready block below.

The token carries **your** access. To act with different restrictions, log in as
that user and mint a token from their account.

## 2. Point a client at it

The endpoint speaks the **SSE transport** and authenticates with a **static
bearer token** (the PAT). Wire a config-file client (Claude Desktop, Cursor, …)
with [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) — in Claude Desktop's
`~/Library/Application Support/Claude/claude_desktop_config.json`:

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
  HTTP first and fails with `sessionId not provided`, because this server exposes
  the classic SSE transport.
- The `Authorization:${AUTH_HEADER}` + `env` split lets `mcp-remote` inject the
  token (a bare `Bearer x` in the arg trips its header parsing on the space).

**Native remote connectors** (Claude's *Add custom connector*) currently require
an `https://` URL **and OAuth** — they do not accept a plain-HTTP host or a static
bearer header. Use `mcp-remote` for local/dev or PAT auth; a native connector
fits an HTTPS deployment that adds its own OAuth layer.

## 3. Deployment (server operators)

- **Put `/mcp` behind TLS.** The token travels as a bearer header over the
  network — terminate HTTPS at your reverse proxy (nginx, Caddy, …) in front of
  the Ktor server. `http://localhost` is fine for local development only.
- **Set the public host** so the SDK's DNS-rebinding guard admits remote clients.
  In `server.yaml`:

  ```yaml
  mcpAllowedHosts:
    - mcp.example.gr
  ```

  Leave it **unset** for local dev (defaults to `localhost`). The API is
  bearer-authenticated, so this is about reachability, not the cookie attack the
  guard mitigates.

## 4. Admin oversight

**Preferences → MCP oversight** (super admin only):

- **Kill switch** — one toggle disables *all* PATs at once (REST + `/mcp`). App
  logins keep working. Flip it off to freeze programmatic access instantly.
- **Every token** — see who owns each, when it was last used, and **revoke any**.

## Security notes

- Tokens are stored **hashed** (SHA-256); the raw value exists only on the
  client. A DB leak yields nothing usable.
- Non-expiring **by design** (a machine credential) but **revocable** — per token
  (owner or admin) or all at once (the kill switch).
- A token is a full credential for that user's API surface. Treat it like a
  password: don't commit it, don't share it, revoke it when a machine is lost.
