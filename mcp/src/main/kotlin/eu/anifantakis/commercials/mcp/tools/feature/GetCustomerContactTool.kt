package eu.anifantakis.commercials.mcp.tools.feature

import eu.anifantakis.commercials.mcp.args
import eu.anifantakis.commercials.mcp.inputSchema
import eu.anifantakis.commercials.mcp.prop
import eu.anifantakis.commercials.mcp.runTool
import eu.anifantakis.commercials.mcp.tools.McpTool
import eu.anifantakis.commercials.mcp.tools.ToolContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Look up ONE customer's contact (name + email) by client code on a station. */
object GetCustomerContactTool : McpTool {
    override val name = "get_customer_contact"
    override val description =
        "Look up a single customer's contact details (name and email) by their client code on a " +
            "station. Returns found=false when no customer has that code. A customer-scoped caller " +
            "may only look up their own code."
    override val inputSchema = inputSchema(required = listOf("station", "code")) {
        prop("station", "string", "Station id (see list_stations).")
        prop("code", "string", "The customer's client code.")
    }

    override suspend fun handle(ctx: ToolContext, req: CallToolRequest): CallToolResult =
        runTool(name) {
            val services = ctx.services
            val a = req.args
            val access = services.resolveStation(ctx.caller, a.stringOrNull("station"))
            val code = a.string("code")
            services.requireCode(access.grant, code)   // customer-scoping: own code only
            val contact = access.data.customerByCode(code)
            buildJsonObject {
                put("code", code)
                put("found", contact != null)
                contact?.let {
                    put("name", it.name)
                    it.email?.let { email -> put("email", email) }
                }
            }
        }
}
