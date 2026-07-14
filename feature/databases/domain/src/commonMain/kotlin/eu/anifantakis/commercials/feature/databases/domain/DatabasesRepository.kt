package eu.anifantakis.commercials.feature.databases.domain

import eu.anifantakis.commercials.core.domain.util.DataResult
import eu.anifantakis.commercials.core.domain.util.RemoteError

/** One hosted station, as listed on the Databases screen. */
data class HostedStation(
    val id: String,
    val name: String,
    /** host:port/schema - the GROUP's database, shared with [siblings]. */
    val database: String,
    val reachable: Boolean = true,
    val placements: Long? = null,
    val dateRange: String? = null,
    val groupId: String = "",
    val groupName: String = "",
    /** The other stations in the same database - what dropping the group also destroys. */
    val siblings: List<String> = emptyList(),
)

/**
 * How much of a station to delete.
 *
 * A station does NOT own a database - its GROUP does, and its siblings live in
 * the same one. So there is no "drop this station's database": either you delete
 * the station's rows ([PURGE]) or you drop the whole group ([DROP_GROUP]) and
 * every station in it with it.
 */
enum class DeleteMode(val wire: String) {
    /** Unhost only: yaml entry, user grants, live registry. The data stays. */
    SAFE("safe"),

    /**
     * Also DELETE this station's rows (spots, breaks, programmes, airings...).
     * The group's shared customers and contracts, and its other stations, survive.
     */
    PURGE("purge"),

    /**
     * DROP the whole group DATABASE. Every station in the group dies with it -
     * confirm with the GROUP id, not the station's.
     */
    DROP_GROUP("drop-group"),
}

data class StationDeletion(
    val status: String,
    val grantsRemoved: Int,
    val yamlEntryRemoved: Boolean,
    val databaseDropped: Boolean,
    /** Rows deleted from the group database by a purge. */
    val rowsPurged: Long = 0,
    /** Stations that went with a dropped group. */
    val stationsRemoved: List<String> = emptyList(),
)

/** Super-admin management of hosted station databases. */
interface DatabasesRepository {

    suspend fun listStations(): DataResult<List<HostedStation>, RemoteError>

    /**
     * [confirmId] is the typed confirmation: the STATION id for [DeleteMode.SAFE]
     * and [DeleteMode.PURGE], the GROUP id for [DeleteMode.DROP_GROUP] - a
     * different word for a different decision.
     */
    suspend fun deleteStation(
        id: String,
        mode: DeleteMode,
        confirmId: String,
    ): DataResult<StationDeletion, RemoteError>
}
