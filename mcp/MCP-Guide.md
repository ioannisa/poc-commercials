# MCP Server — setup guide (moved)

> **This guide is obsolete.** It described the `stdio` transport (the `:mcp-stdio`
> launcher), which was **removed in Phase 5 (2026-07-16)**. The commands it used
> to contain point at a module that no longer builds.

The MCP server now has **one transport: SSE at `/mcp`** in the Ktor `server`,
authenticated with a per-user **personal access token (PAT)**.

**Current setup and deployment guide → [`docs/MCP.md`](../docs/MCP.md).**

In short:

1. Mint a PAT: **Preferences → MCP / API Access → Generate token** (shown once).
2. Point your client at `https://<server-host>/mcp` with `Authorization: Bearer <token>`.
   A stdio-only client (Claude Desktop's config file, Cursor, …) bridges to SSE
   via [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) with
   `--transport sse-only` — full block in `docs/MCP.md`.
3. Admin oversight (kill switch + revoke any token): **Preferences → MCP oversight**.

For the subsystem's internal architecture and build record, see
[`MCP.md`](./MCP.md) in this directory.
