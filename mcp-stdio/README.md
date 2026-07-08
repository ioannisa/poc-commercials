# Commercials Manager — MCP stdio server

Serves the Commercials Manager MCP tools over **stdio**, for MCP clients that
spawn a helper process (Claude Desktop, the MCP Inspector in stdio mode, CLI
tooling). It boots the same persistence/auth stack as the Ktor server and runs
under a real bearer-token identity, so the same per‑station grant/role checks
apply as over HTTP.

For network/multi-user access use the HTTP transport instead: the server mounts
MCP at `/mcp` (SSE, under bearer auth) — see `server/.../plugins/Mcp.kt`.

## Build

```bash
./gradlew :mcp-stdio:installDist
# launcher: mcp-stdio/build/install/mcp-stdio/bin/mcp-stdio
```

## Configuration (environment)

| Variable | Required | Meaning |
|---|---|---|
| `COMMERCIALS_MCP_TOKEN` | yes | A valid bearer token (from `POST /api/auth/login`). The tools run as that user, scoped to its station grants. |
| `COMMERCIALS_SERVER` | yes* | Absolute path to `server.yaml` (central DB + stations). *Falls back to `./server.yaml` in the working directory. |

The token identifies the caller; a `NORMAL_USER` grant is needed for mutations,
and `CUSTOMER_VIEWER` callers only ever see their own client code.

## Claude Desktop

Add to `claude_desktop_config.json` (`mcpServers`):

```json
{
  "mcpServers": {
    "commercials-manager": {
      "command": "/ABS/PATH/poc-commercials/mcp-stdio/build/install/mcp-stdio/bin/mcp-stdio",
      "env": {
        "COMMERCIALS_MCP_TOKEN": "<a bearer token from /api/auth/login>",
        "COMMERCIALS_SERVER": "/ABS/PATH/poc-commercials/server.yaml"
      }
    }
  }
}
```

Notes:
- stdout is the MCP protocol channel; all logging goes to **stderr** (see
  `src/main/resources/logback.xml`), and `System.out` is redirected to stderr so
  a stray print can never corrupt the stream.
- The central DB (and, lazily, each station's pool) must be reachable from wherever
  the launcher runs.
