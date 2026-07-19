package eu.anifantakis.commercials.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.condition
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize

/**
 * Compresses responses. This is the single biggest thing we do for the MONTH
 * GRID, and it is not a database optimization at all.
 *
 * The grid asks for a whole month at once: `GET /api/schedule` for the busiest
 * month of the busiest station returns 1,295 cells carrying 13,009 commercials -
 * **7.79 MB of JSON**, to draw little boxes that show a spot count and a
 * duration. Measured, that payload gzips to **329 KB - 4.2% of the original, a
 * 23.7x reduction** - because it is thousands of repetitions of the same Greek
 * customer names, contract numbers and product names.
 *
 * On localhost the difference is invisible (the query itself is ~100ms and the
 * bytes never leave the machine), which is exactly why it went unnoticed. Over a
 * real link it IS the screen: 7.79 MB on a 10 Mbit/s connection is six seconds
 * of staring at nothing; 329 KB is a quarter of one.
 *
 * `minimumSize` keeps us from paying the CPU to compress a 200-byte error body.
 * Two subtrees are deliberately EXCLUDED:
 * - `/mcp` (SSE): compression buffers, and buffering a server-sent-event
 *   stream defeats the point of streaming it.
 * - `/downloads` (desktop installers): dmg/msi/deb are already-compressed
 *   containers - gzip over a ~150 MB stream costs real CPU and saves nothing.
 */
fun Application.configureCompression() {
    install(Compression) {
        gzip {
            priority = 1.0
            minimumSize(1024)
            // The exclusions live on the encoder (Ktor has none at the plugin level).
            condition { !request.local.uri.startsWith("/mcp") && !request.local.uri.startsWith("/downloads") }
        }
        deflate {
            priority = 0.9
            minimumSize(1024)
            condition { !request.local.uri.startsWith("/mcp") && !request.local.uri.startsWith("/downloads") }
        }
    }
}
