package eu.anifantakis.commercials.migration

/**
 * What the migration needs FROM its host application - the only seam between
 * this module and the server. The server implements it over its own
 * StationDb/StationRegistry, which keeps the station DDL single-sourced
 * there and this module free of any server dependency.
 */
interface MigrationHost {

    /**
     * Creates the normalized station tables in [jdbcUrl]'s schema WITHOUT
     * demo seeding (and permanently marks the station so empty months are
     * never demo-filled later). Idempotent.
     */
    fun prepareStationSchema(jdbcUrl: String, username: String, password: String)

    /** True when a station with this id is currently hosted (id collision guard). */
    fun isStationHosted(id: String): Boolean

    /**
     * Hosts the migrated station live NOW, without a restart. Returns false
     * when the host can't (e.g. the standalone CLI has no running server) -
     * the caller then tells the operator a restart is needed.
     */
    fun hostStation(id: String, name: String, jdbcUrl: String, username: String, password: String): Boolean
}
