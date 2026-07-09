package eu.anifantakis.commercials.mcp.tools

import eu.anifantakis.commercials.mcp.McpCaller
import eu.anifantakis.commercials.mcp.McpToolServices
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema

/**
 * Everything a tool handler needs at call time: who is calling, and the shared
 * backend services (station resolution, authorization, gateways). One instance
 * is built per MCP session and passed to every tool.
 */
class ToolContext(val caller: McpCaller, val services: McpToolServices)

/**
 * ONE MCP tool = ONE self-contained unit: its name, description, input schema,
 * whether it mutates, and its handler (which owns that tool's argument parsing,
 * orchestration and JSON shaping). Cross-cutting rules (authz, shared reads)
 * stay in [McpToolServices].
 *
 * Placement: a single-file tool is one file in `tools/feature/`; a tool that
 * needs helper files of its own gets a subfolder there (e.g.
 * `tools/feature/generate_break_report/`, which co-locates its assembler). To
 * add a functionality: drop the file/folder in, then add the object to
 * [ALL_MCP_TOOLS] — nothing else changes, the server discovers it from the
 * registry and grant-scopes every call.
 */
interface McpTool {
    val name: String
    val description: String

    /** Defaults to a no-argument tool. */
    val inputSchema: ToolSchema get() = ToolSchema()

    /** Write tools are registered only when [McpToolServices.mutationsEnabled]. */
    val mutating: Boolean get() = false

    suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult
}
