package eu.anifantakis.commercials.server.migration

import eu.anifantakis.commercials.migration.runSenEnrichCli

/**
 * Standalone CLI entry point for the SEN (Oracle ERP) enrichment of an
 * already-migrated station schema:
 *
 *   java -cp server/build/libs/server.jar \
 *        eu.anifantakis.commercials.server.migration.SenEnrichToolKt \
 *        --sen-dir /path/to/SEN --schema commercials_ctv_migrated [--apply]
 */
fun main(args: Array<String>) {
    runSenEnrichCli(args)
}
