package eu.anifantakis.commercials.server.auth

import eu.anifantakis.commercials.server.scheduler.CentralDb
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Application users + bearer tokens + per-station grants, all in the server's
 * CENTRAL database. Users are server-level accounts; what they may touch is
 * expressed per hosted station in `user_station_grants` (station id from
 * stations.yaml, role, and - for customers - their client code).
 *
 * Passwords are stored as PBKDF2-HMAC-SHA256 hashes with a per-user random
 * salt (never plaintext, even for demo users). Tokens are 256-bit random
 * values with NO expiration by design; revocation still works because every
 * token is a DB row - logout (or an admin DELETE) removes it and the very
 * next request with that token gets 401.
 *
 * Koin singleton - constructor-injected with the pooled [CentralDb].
 */
class AuthDb(private val db: CentralDb) {

    private companion object {
        const val PBKDF2_ITERATIONS = 100_000
        const val PBKDF2_KEY_BITS = 256
    }

    private val secureRandom = SecureRandom()

    /**
     * Creates tables and seeds demo users/grants when empty. [stationIds] are
     * the stations currently in stations.yaml - demo grants reference them.
     *
     * Migration note: pre-multitenancy deployments have a `users` table with
     * role/client_code columns. Those columns are simply no longer read -
     * the per-station truth lives in `user_station_grants`, which this
     * bootstrap seeds by username for existing users too.
     */
    fun bootstrap(stationIds: List<String>) {
        db.connection().use { c ->
            c.createStatement().use { s ->
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(64) NOT NULL UNIQUE,
                        password_hash VARCHAR(64) NOT NULL,
                        password_salt VARCHAR(32) NOT NULL,
                        display_name VARCHAR(128) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS auth_tokens (
                        token VARCHAR(64) PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_tokens_user FOREIGN KEY (user_id)
                            REFERENCES users(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS user_station_grants (
                        user_id BIGINT NOT NULL,
                        station_id VARCHAR(64) NOT NULL,
                        role VARCHAR(32) NOT NULL,
                        client_code VARCHAR(32) NULL,
                        PRIMARY KEY (user_id, station_id),
                        CONSTRAINT fk_grants_user FOREIGN KEY (user_id)
                            REFERENCES users(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
            }
            seedUsersIfEmpty(c)
            seedGrantsIfEmpty(c, stationIds)
        }
    }

    /** Three demo logins, one per access layer. */
    private fun seedUsersIfEmpty(c: Connection) {
        val count = c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM users").use { rs -> rs.next(); rs.getInt(1) }
        }
        if (count > 0) return

        data class Seed(val username: String, val password: String, val displayName: String)

        val seeds = listOf(
            Seed("admin", "admin123", "Normal User"),
            Seed("viewer", "viewer123", "Report Viewer"),
            Seed("customer", "customer123", "Customer Viewer"),
        )

        c.prepareStatement(
            "INSERT INTO users(username, password_hash, password_salt, display_name) VALUES(?,?,?,?)"
        ).use { ps ->
            for (seed in seeds) {
                val salt = randomBytes(16)
                ps.setString(1, seed.username)
                ps.setString(2, pbkdf2(seed.password, salt).toHex())
                ps.setString(3, salt.toHex())
                ps.setString(4, seed.displayName)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    /**
     * Demo privileges showcasing the per-station model:
     * - admin: NORMAL_USER on every hosted station (sees the station dropdown)
     * - viewer: REPORT_VIEWER on every hosted station
     * - customer: CUSTOMER_VIEWER on ONE station only (no dropdown), scoped
     *   to client 30004521 (ΚΡΗΤΗ ΞΕΝΟΔΟΧΕΙΑ Α.Ε in the demo data)
     */
    private fun seedGrantsIfEmpty(c: Connection, stationIds: List<String>) {
        if (stationIds.isEmpty()) return
        val count = c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM user_station_grants").use { rs -> rs.next(); rs.getInt(1) }
        }
        if (count > 0) return

        val idsByUsername = c.createStatement().use { s ->
            s.executeQuery("SELECT id, username FROM users").use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString("username"), rs.getLong("id"))
                }
            }
        }

        val customerStation = stationIds.firstOrNull { it == "crete-tv" } ?: stationIds.first()

        data class GrantSeed(val userId: Long, val stationId: String, val role: UserRole, val clientCode: String?)

        val grants = buildList {
            idsByUsername["admin"]?.let { id ->
                stationIds.forEach { add(GrantSeed(id, it, UserRole.NORMAL_USER, null)) }
            }
            idsByUsername["viewer"]?.let { id ->
                stationIds.forEach { add(GrantSeed(id, it, UserRole.REPORT_VIEWER, null)) }
            }
            idsByUsername["customer"]?.let { id ->
                add(GrantSeed(id, customerStation, UserRole.CUSTOMER_VIEWER, "30004521"))
            }
        }
        if (grants.isEmpty()) return

        c.prepareStatement(
            "INSERT INTO user_station_grants(user_id, station_id, role, client_code) VALUES(?,?,?,?)"
        ).use { ps ->
            for (g in grants) {
                ps.setLong(1, g.userId)
                ps.setString(2, g.stationId)
                ps.setString(3, g.role.name)
                ps.setString(4, g.clientCode)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    /** Returns the user (with grants) if username+password are valid, null otherwise. */
    fun verifyCredentials(username: String, password: String): AuthUser? {
        data class Row(val id: Long, val username: String, val displayName: String, val hashHex: String, val saltHex: String)

        return db.connection().use { c ->
            val row = c.prepareStatement(
                """
                SELECT id, username, display_name, password_hash, password_salt
                FROM users WHERE username = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else Row(
                        id = rs.getLong("id"),
                        username = rs.getString("username"),
                        displayName = rs.getString("display_name"),
                        hashHex = rs.getString("password_hash"),
                        saltHex = rs.getString("password_salt")
                    )
                }
            } ?: return null

            val candidate = pbkdf2(password, row.saltHex.hexToBytes())
            // Constant-time comparison - never compare password hashes with ==
            if (!MessageDigest.isEqual(candidate, row.hashHex.hexToBytes())) return null

            AuthUser(
                id = row.id,
                username = row.username,
                displayName = row.displayName,
                grants = loadGrants(c, row.id)
            )
        }
    }

    /** Creates and stores a new non-expiring token for the user. */
    fun createToken(userId: Long): String {
        val token = randomBytes(32).toHex()
        db.connection().use { c ->
            c.prepareStatement("INSERT INTO auth_tokens(token, user_id) VALUES(?,?)").use { ps ->
                ps.setString(1, token)
                ps.setLong(2, userId)
                ps.executeUpdate()
            }
        }
        return token
    }

    /** Resolves a bearer token to its user (with grants), or null if unknown/revoked. */
    fun findUserByToken(token: String): AuthUser? {
        if (token.isBlank()) return null
        return db.connection().use { c ->
            val base = c.prepareStatement(
                """
                SELECT u.id, u.username, u.display_name
                FROM auth_tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, token)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else Triple(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"))
                }
            } ?: return null

            AuthUser(
                id = base.first,
                username = base.second,
                displayName = base.third,
                grants = loadGrants(c, base.first)
            )
        }
    }

    /** Revokes a token (logout). */
    fun deleteToken(token: String) {
        db.connection().use { c ->
            c.prepareStatement("DELETE FROM auth_tokens WHERE token = ?").use { ps ->
                ps.setString(1, token)
                ps.executeUpdate()
            }
        }
    }

    private fun loadGrants(c: Connection, userId: Long): List<StationGrant> =
        c.prepareStatement(
            "SELECT station_id, role, client_code FROM user_station_grants WHERE user_id = ? ORDER BY station_id"
        ).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StationGrant(
                                stationId = rs.getString("station_id"),
                                role = UserRole.valueOf(rs.getString("role")),
                                clientCode = rs.getString("client_code")
                            )
                        )
                    }
                }
            }
        }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
