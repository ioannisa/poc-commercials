package eu.anifantakis.commercials.server.migration

import eu.anifantakis.commercials.migration.runMigrationCli

/**
 * Standalone CLI entry point - kept at this historical FQN so the documented
 * command keeps working unchanged:
 *
 *   java -cp server/build/libs/server.jar \
 *        eu.anifantakis.commercials.server.migration.MigrationToolKt --dump ...
 */
fun main(args: Array<String>) {
    runMigrationCli(args)
}
