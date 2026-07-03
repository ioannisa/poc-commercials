package eu.anifantakis.commercials.server.migration

import eu.anifantakis.commercials.migration.MigrationHost
import eu.anifantakis.commercials.migration.runMigrationCli
import eu.anifantakis.commercials.server.scheduler.StationDb
import eu.anifantakis.commercials.server.stations.StationConfig
import eu.anifantakis.commercials.server.stations.StationRegistry

/**
 * The server's side of the :migration module seam: how the migration gets a
 * station schema created (single-sourced DDL in [StationDb]) and, when a
 * registry is available, hosted live without a restart.
 *
 * Two constructions:
 * - Koin singleton with the live [StationRegistry] (the Migration screen) -
 *   migrated stations are served immediately.
 * - registry-less (the standalone CLI below, where no server is running) -
 *   hostStation reports false and the CLI tells the operator to restart.
 */
class ServerMigrationHost(private val registry: StationRegistry) : MigrationHost by StandaloneMigrationHost() {

    override fun isStationHosted(id: String): Boolean = registry.config(id) != null

    override fun hostStation(id: String, name: String, jdbcUrl: String, username: String, password: String): Boolean {
        registry.add(StationConfig(id = id, name = name, jdbcUrl = jdbcUrl, username = username, password = password))
        return true
    }
}

/** Schema creation only - for the CLI, which runs without a live server. */
class StandaloneMigrationHost : MigrationHost {

    override fun prepareStationSchema(jdbcUrl: String, username: String, password: String) {
        val schema = jdbcUrl.substringAfterLast('/').substringBefore('?')
        val db = StationDb(
            StationConfig(id = schema, name = schema, jdbcUrl = jdbcUrl, username = username, password = password),
            maxPoolSize = 2
        )
        try {
            db.bootstrap(seedDemo = false)
        } finally {
            db.close()
        }
    }

    override fun isStationHosted(id: String): Boolean = false

    override fun hostStation(id: String, name: String, jdbcUrl: String, username: String, password: String): Boolean = false
}

/**
 * Standalone CLI entry point - kept at this historical FQN so the documented
 * command keeps working unchanged:
 *
 *   java -cp server/build/libs/server.jar \
 *        eu.anifantakis.commercials.server.migration.MigrationToolKt --dump ...
 */
fun main(args: Array<String>) {
    runMigrationCli(args, StandaloneMigrationHost())
}
