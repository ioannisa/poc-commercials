package eu.anifantakis.commercials.server.scheduler

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import eu.anifantakis.commercials.server.stations.DEFAULT_CENTRAL_MAX_POOL
import eu.anifantakis.commercials.server.stations.HostingConfig
import eu.anifantakis.commercials.server.stations.resolveMaxPoolSize
import org.koin.core.annotation.Provided
import java.sql.Connection

/**
 * The server's OWN standalone schema (the mandatory `central` block of
 * stations.yaml, e.g. `commercials_central`): application users, tokens, and
 * per-station grants live here - and ONLY here. Station schedule data lives
 * in the per-station pools instead (see StationDb / StationRegistry), and the
 * config loader rejects layouts where a station points at this schema.
 */
// HostingConfig is @Provided: it comes from a file-loading factory registered
// with a classic-DSL definition the compile-time checker can't index.
class CentralDb(@Provided hosting: HostingConfig) {

    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = hosting.central.jdbcUrl
            username = hosting.central.username
            password = hosting.central.password
            driverClassName = "com.mysql.cj.jdbc.Driver"
            // Central is on the hot path (a token lookup per authenticated
            // request), so its ceiling is resolved independently: central
            // override / file default / built-in (10).
            maximumPoolSize = resolveMaxPoolSize(
                hosting.central.maxPoolSize, hosting.maxPoolSize, DEFAULT_CENTRAL_MAX_POOL
            )
            minimumIdle = 1
            connectionTimeout = 10_000
            // Do not fail-fast at pool construction: connections are
            // validated on first use, so an unreachable MySQL (or a test
            // environment without one) doesn't crash instance creation
            initializationFailTimeout = -1
            poolName = "central-db"
        }
    )

    /** A pooled connection; closing it (e.g. via `.use {}`) returns it to the pool. */
    fun connection(): Connection = dataSource.getConnection()

    fun close() {
        dataSource.close()
    }
}
