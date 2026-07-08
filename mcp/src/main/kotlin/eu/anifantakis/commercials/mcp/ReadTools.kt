package eu.anifantakis.commercials.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Registers the read-only query tools. Each reuses an existing [StationDb][eu.anifantakis.commercials.server.scheduler.StationDb]
 * read method as-is (no new SQL) and is scoped to [caller]'s grant on the
 * requested station. Customer-scoped callers (CUSTOMER_VIEWER) only ever see
 * their own client's rows.
 */
internal fun Server.registerReadTools(caller: McpCaller, services: McpToolServices) {

    addTool(
        name = "list_stations",
        description = "List the stations (tenants) the current user can access, with their role and " +
            "(for customer accounts) client code. Call this first to discover station ids.",
    ) { _ ->
        runTool("list_stations") {
            buildJsonArray {
                services.stations(caller).forEach { st ->
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

    addTool(
        name = "search_parties",
        description = "Search customers/traders on a station by name or client code (substring), busiest " +
            "first, with all-time spot and placement counts. Set byTrader=true to search contract-paying " +
            "parties (agencies) instead of spot owners.",
        inputSchema = inputSchema(required = listOf("station", "query")) {
            prop("station", "string", "Station id (see list_stations).")
            prop("query", "string", "Name or client-code substring.")
            prop("byTrader", "boolean", "Search contract payers (traders) instead of spot owners. Default false.")
        },
    ) { req ->
        runTool("search_parties") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            var rows = access.db.searchParties(a.string("query"), a.bool("byTrader", false))
            if (services.isCustomerScoped(access.grant)) {
                rows = rows.filter { it.code == access.grant.clientCode }
            }
            buildJsonArray {
                rows.forEach { p ->
                    addJsonObject {
                        put("code", p.code)
                        put("name", p.name)
                        p.email?.let { put("email", it) }
                        p.vatNumber?.let { put("vatNumber", it) }
                        p.phone?.let { put("phone", it) }
                        put("spotCount", p.spotCount)
                        put("placementCount", p.placementCount)
                    }
                }
            }
        }
    }

    addTool(
        name = "party_activity",
        description = "The months a party has airings on a station, newest first, with placement counts. " +
            "Use to answer recency questions like 'when did customer X last air'.",
        inputSchema = inputSchema(required = listOf("station", "code")) {
            prop("station", "string", "Station id.")
            prop("code", "string", "Client code (Κωδ. Πελ.).")
            prop("byTrader", "boolean", "Treat code as a contract payer (trader). Default false.")
        },
    ) { req ->
        runTool("party_activity") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            val code = a.string("code")
            services.requireCode(access.grant, code)
            val months = access.db.partyActivity(code, a.bool("byTrader", false))
            buildJsonObject {
                put("code", code)
                put("monthsWithActivity", months.size)
                months.firstOrNull()?.let { put("lastAired", "%04d-%02d".format(it.year, it.month)) }
                put("activity", buildJsonArray {
                    months.forEach { m ->
                        addJsonObject {
                            put("year", m.year)
                            put("month", m.month)
                            put("placements", m.placements)
                        }
                    }
                })
            }
        }
    }

    addTool(
        name = "party_contracts",
        description = "The contract lines of a party on a station: contract number, line number, gift flag, " +
            "entry date, and spot/placement/aired-seconds stats.",
        inputSchema = inputSchema(required = listOf("station", "code")) {
            prop("station", "string", "Station id.")
            prop("code", "string", "Client code (Κωδ. Πελ.).")
            prop("byTrader", "boolean", "Treat code as a contract payer (trader). Default false.")
        },
    ) { req ->
        runTool("party_contracts") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            val code = a.string("code")
            services.requireCode(access.grant, code)
            val lines = access.db.partyContractLines(code, a.bool("byTrader", false))
            buildJsonArray {
                lines.forEach { l ->
                    addJsonObject {
                        put("lineId", l.lineId)
                        put("contractNumber", l.contractNumber)
                        put("lineNo", l.lineNo)
                        put("isGift", l.isGift)
                        put("desiredQty", l.desiredQty)
                        put("spotCount", l.spotCount)
                        put("placements", l.placements)
                        put("totalSeconds", l.totalSeconds)
                        l.entryDate?.let { put("entryDate", it) }
                    }
                }
            }
        }
    }

    addTool(
        name = "contract_spots",
        description = "The spots (creatives) on a contract line (lineId comes from party_contracts): " +
            "description, duration, placement count, aired seconds.",
        inputSchema = inputSchema(required = listOf("station", "lineId")) {
            prop("station", "string", "Station id.")
            prop("lineId", "integer", "Contract line id (from party_contracts).")
        },
    ) { req ->
        runTool("contract_spots") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            if (services.isCustomerScoped(access.grant)) {
                throw McpToolException(
                    "Customer-scoped access cannot browse contract lines by id; " +
                        "use party_contracts for your own client code."
                )
            }
            val spots = access.db.contractLineSpots(a.long("lineId"))
            buildJsonArray {
                spots.forEach { s ->
                    addJsonObject {
                        put("spotId", s.spotId)
                        put("description", s.description)
                        put("durationSeconds", s.durationSeconds)
                        put("placements", s.placements)
                        put("totalSeconds", s.totalSeconds)
                    }
                }
            }
        }
    }

    addTool(
        name = "contract_status",
        description = "Contract period + renewal status for a party on a station, with each contract's aired " +
            "range. IMPORTANT: start/end dates are PROVISIONAL (derived from airings) until an ERP import supplies " +
            "real values - each row carries 'datesProvisional', and 'renewedAt' has no source yet. To answer " +
            "'how long since customer X renewed', use 'lastAired' (activity recency), not the provisional dates.",
        inputSchema = inputSchema(required = listOf("station", "code")) {
            prop("station", "string", "Station id.")
            prop("code", "string", "Client code (Κωδ. Πελ.).")
            prop("byTrader", "boolean", "Treat code as a contract payer (trader). Default false.")
        },
    ) { req ->
        runTool("contract_status") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            val code = a.string("code")
            services.requireCode(access.grant, code)
            val rows = access.db.contractStatus(code, a.bool("byTrader", false))
            buildJsonObject {
                put("code", code)
                put("contractCount", rows.size)
                put("datesAreProvisional", rows.any { it.datesProvisional })
                rows.mapNotNull { it.lastAired }.maxOrNull()?.let { put("lastAired", it) }
                put(
                    "note",
                    "Period dates are provisional (placement-derived) until the Oracle ERP import; " +
                        "renewedAt is not yet sourced - use lastAired for renewal recency.",
                )
                put("contracts", buildJsonArray {
                    rows.forEach { r ->
                        addJsonObject {
                            put("contractNumber", r.contractNumber)
                            put("isGift", r.isGift)
                            r.startDate?.let { put("startDate", it) }
                            r.endDate?.let { put("endDate", it) }
                            r.renewedAt?.let { put("renewedAt", it) }
                            put("datesProvisional", r.datesProvisional)
                            r.firstAired?.let { put("firstAired", it) }
                            r.lastAired?.let { put("lastAired", it) }
                            put("placements", r.placements)
                        }
                    }
                })
            }
        }
    }

    addTool(
        name = "spots_in_break",
        description = "The spots scheduled in a specific break on a specific date, in air order. " +
            "time is the break label 'HH:mm' (e.g. '17:30'); date is 'YYYY-MM-DD'.",
        inputSchema = inputSchema(required = listOf("station", "date", "time")) {
            prop("station", "string", "Station id.")
            prop("date", "string", "Date as YYYY-MM-DD.")
            prop("time", "string", "Break time label HH:mm (e.g. 17:30).")
        },
    ) { req ->
        runTool("spots_in_break") {
            val a = req.args
            val access = services.resolveStation(caller, a.stringOrNull("station"))
            val date = parseIsoDate(a.string("date"))
            val time = a.string("time").trim()
            val spots = services.breakSpots(access, date, time)
            buildJsonObject {
                put("date", date.toString())
                put("break", time)
                put("spotCount", spots.size)
                put("totalDurationSeconds", spots.sumOf { it.durationSeconds })
                put("spots", buildJsonArray {
                    spots.forEach { s ->
                        addJsonObject {
                            put("position", s.position)
                            put("clientCode", s.clientCode)
                            put("clientName", s.clientName)
                            put("message", s.message)
                            put("durationSeconds", s.durationSeconds)
                            put("type", s.type)
                            put("contract", s.contract)
                            put("flow", s.flow)
                            s.programName?.let { put("programName", it) }
                        }
                    }
                })
            }
        }
    }

    addTool(
        name = "station_footprint",
        description = "Quick footprint of a station's scheduling data: total placements and the " +
            "earliest/latest air dates.",
        inputSchema = inputSchema(required = listOf("station")) {
            prop("station", "string", "Station id.")
        },
    ) { req ->
        runTool("station_footprint") {
            val access = services.resolveStation(caller, req.args.stringOrNull("station"))
            val stats = access.db.placementStats()
            buildJsonObject {
                put("placements", stats.placements)
                stats.minDate?.let { put("firstAirDate", it) }
                stats.maxDate?.let { put("lastAirDate", it) }
            }
        }
    }
}
