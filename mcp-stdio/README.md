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

### …and then RESTART THE CLIENT. The build alone changes nothing.

The MCP client (Claude Code, Claude Desktop, …) spawns this launcher **once, when
its session starts**, and keeps that process for the whole session. Rebuilding
while it is running does not touch it: the tools keep answering from the code
that was on disk when the session began.

That failure is silent, and it is worse than it sounds:

- `./gradlew clean` deletes `build/install/`, but the **running JVM keeps going**
  from the deleted-but-still-open jars (POSIX keeps an unlinked file alive while a
  process holds it open). No error, no warning - it simply serves old code.
- The process read `server.yaml` at ITS start, so it also stays pinned to whatever
  **schema** that file named back then, even after you point the file elsewhere.

Both bit us on 2026-07-13: a stdio process from a session two days earlier was
still answering, out of jars that no longer existed, against a schema that had
since been replaced - so `spots_in_break` cheerfully returned the pre-fix values
and looked exactly like three fresh bugs in the MCP tools.

**To actually pick up a change:**

```bash
./gradlew :mcp-stdio:installDist
pkill -f 'mcp-stdio/build/install'     # kill any process left from an old session
# then restart the MCP client so it spawns a fresh one
```

Sanity check before you go bug-hunting - if this start time predates your change,
you are talking to a ghost:

```bash
ps -eo pid,lstart,command | grep '[m]cp-stdio/build/install'
```

Note that the module has **no data code of its own**: every read goes through
`:persistence` (`StationDb`), which is why a fix there fixes the MCP too - and why
a stale process here shows *data* bugs that exist nowhere in the source.

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
