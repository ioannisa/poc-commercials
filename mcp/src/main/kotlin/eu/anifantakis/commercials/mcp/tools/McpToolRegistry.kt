package eu.anifantakis.commercials.mcp.tools

import eu.anifantakis.commercials.mcp.tools.feature.AddPlacementTool
import eu.anifantakis.commercials.mcp.tools.feature.ContractSpotsTool
import eu.anifantakis.commercials.mcp.tools.feature.ContractStatusTool
import eu.anifantakis.commercials.mcp.tools.feature.DeletePlacementTool
import eu.anifantakis.commercials.mcp.tools.feature.GetCustomerContactTool
import eu.anifantakis.commercials.mcp.tools.feature.generate_break_report.GenerateBreakReportTool
import eu.anifantakis.commercials.mcp.tools.feature.generate_day_report.GenerateDayReportTool
import eu.anifantakis.commercials.mcp.tools.feature.ListBreaksTool
import eu.anifantakis.commercials.mcp.tools.feature.ListStationsTool
import eu.anifantakis.commercials.mcp.tools.feature.PartyActivityTool
import eu.anifantakis.commercials.mcp.tools.feature.PartyContractsTool
import eu.anifantakis.commercials.mcp.tools.feature.ReorderPlacementsTool
import eu.anifantakis.commercials.mcp.tools.feature.SearchPartiesTool
import eu.anifantakis.commercials.mcp.tools.feature.SendScheduleEmailTool
import eu.anifantakis.commercials.mcp.tools.feature.SpotsInBreakTool
import eu.anifantakis.commercials.mcp.tools.feature.StationFootprintTool

/**
 * THE registry — the single place that knows every tool. The server iterates
 * this list, so registering a new functionality is: drop its file in
 * `tools/feature/` (or a subfolder there if it needs helpers of its own, e.g.
 * `tools/feature/generate_break_report/`), then add its object here. Nothing
 * else changes.
 *
 * Order is the tools/list order clients see; read tools first, then the report
 * tool, then the write tools (which the server drops when mutations are off).
 */
val ALL_MCP_TOOLS: List<McpTool> = listOf(
    // -- reads --
    ListStationsTool,
    SearchPartiesTool,
    PartyActivityTool,
    PartyContractsTool,
    ContractSpotsTool,
    ContractStatusTool,
    ListBreaksTool,
    SpotsInBreakTool,
    StationFootprintTool,
    GetCustomerContactTool,
    // -- reports --
    GenerateBreakReportTool,
    GenerateDayReportTool,
    // -- writes (mutating; registered only when mutations are enabled) --
    AddPlacementTool,
    DeletePlacementTool,
    ReorderPlacementsTool,
    SendScheduleEmailTool,
)
