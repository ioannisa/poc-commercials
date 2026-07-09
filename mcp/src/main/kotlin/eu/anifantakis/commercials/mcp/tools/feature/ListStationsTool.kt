package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/** Discovery entrypoint: the stations (tenants) the caller can access, with role/clientCode. */
object ListStationsTool : McpTool {
    override val name = "list_stations"
    override val description =
        "List the stations (tenants) the current user can access, with their role and " +
            "(for customer accounts) client code. Call this first to discover station ids."

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            buildJsonArray {
                ctx.services.stations(ctx.caller).forEach { st ->
                    addJsonObject {
                        put("id", st.id)
                        put("name", st.name)
                        put("role", st.role)
                        st.clientCode?.let { put("clientCode", it) }
                    }
                }
            }
        }
}