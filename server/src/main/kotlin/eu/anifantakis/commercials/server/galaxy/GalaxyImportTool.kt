package eu.anifantakis.commercials.server.galaxy

import eu.anifantakis.commercials.galaxy.runGalaxyImportCli

/**
 * Standalone CLI entry point for the Galaxy (new ERP) import into a group
 * schema. Dry-run by default; pass --apply to write.
 *
 *   java -cp server/build/libs/server.jar \
 *        eu.anifantakis.commercials.server.galaxy.GalaxyImportToolKt \
 *        --galaxy-dir ~/Downloads/ctv/ss/galaxy2 \
 *        --old-export-dir ~/Downloads/ctv/ss/galaxy/customer \
 *        --schema commercials_crete_group [--company 001] [--apply]
 */
fun main(args: Array<String>) {
    runGalaxyImportCli(args)
}
