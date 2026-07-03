package eu.anifantakis.poc.ctv.server.auth

import eu.anifantakis.poc.ctv.server.scheduler.SchedulerDb
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Users + bearer tokens.
 *
 * Passwords are stored as PBKDF2-HMAC-SHA256 hashes with a per-user random
 * salt (never plaintext, even for demo users). Tokens are 256-bit random
 * values with NO expiration by design; revocation still works because every
 * token is a DB row - logout (or an admin DELETE) removes it and the very
 * next request with that token gets 401.
 */
object AuthDb {

    private const val PBKDF2_ITERATIONS = 100_000
    private const val PBKDF2_KEY_BITS = 256

    private val secureRandom = SecureRandom()

    fun bootstrap() {
        SchedulerDb.connection().use { c ->
            c.createStatement().use { s ->
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(64) NOT NULL UNIQUE,
                        password_hash VARCHAR(64) NOT NULL,
                        password_salt VARCHAR(32) NOT NULL,
                        role VARCHAR(32) NOT NULL,
                        client_code VARCHAR(32) NULL,
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
            }
            seedUsersIfEmpty(c)
        }
    }

    /** Three demo logins, one per access layer. */
    private fun seedUsersIfEmpty(c: Connection) {
        val count = c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM users").use { rs -> rs.next(); rs.getInt(1) }
        }
        if (count > 0) return

        data class Seed(
            val username: String, val password: String, val role: UserRole,
            val clientCode: String?, val displayName: String
        )

        val seeds = listOf(
            Seed("admin", "admin123", UserRole.NORMAL_USER, null, "Normal User"),
            Seed("viewer", "viewer123", UserRole.REPORT_VIEWER, null, "Report Viewer"),
            // Client code from the seeded demo data: ΚΡΗΤΗ ΞΕΝΟΔΟΧΕΙΑ Α.Ε
            Seed("customer", "customer123", UserRole.CUSTOMER_VIEWER, "30004521", "Customer Viewer"),
        )

        c.prepareStatement(
            """
            INSERT INTO users(username, password_hash, password_salt, role, client_code, display_name)
            VALUES(?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            for (seed in seeds) {
                val salt = randomBytes(16)
                ps.setString(1, seed.username)
                ps.setString(2, pbkdf2(seed.password, salt).toHex())
                ps.setString(3, salt.toHex())
                ps.setString(4, seed.role.name)
                ps.setString(5, seed.clientCode)
                ps.setString(6, seed.displayName)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    /** Returns the user if username+password are valid, null otherwise. */
    fun verifyCredentials(username: String, password: String): AuthUser? {
        data class Row(val user: AuthUser, val hashHex: String, val saltHex: String)

        val row = SchedulerDb.connection().use { c ->
            c.prepareStatement(
                """
                SELECT id, username, display_name, role, client_code, password_hash, password_salt
                FROM users WHERE username = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else Row(
                        user = rs.toAuthUser(),
                        hashHex = rs.getString("password_hash"),
                        saltHex = rs.getString("password_salt")
                    )
                }
            }
        } ?: return null

        val candidate = pbkdf2(password, row.saltHex.hexToBytes())
        // Constant-time comparison - never compare password hashes with ==
        val matches = MessageDigest.isEqual(candidate, row.hashHex.hexToBytes())
        return if (matches) row.user else null
    }

    /** Creates and stores a new non-expiring token for the user. */
    fun createToken(userId: Long): String {
        val token = randomBytes(32).toHex()
        SchedulerDb.connection().use { c ->
            c.prepareStatement("INSERT INTO auth_tokens(token, user_id) VALUES(?,?)").use { ps ->
                ps.setString(1, token)
                ps.setLong(2, userId)
                ps.executeUpdate()
            }
        }
        return token
    }

    /** Resolves a bearer token to its user, or null if the token is unknown/revoked. */
    fun findUserByToken(token: String): AuthUser? {
        if (token.isBlank()) return null
        return SchedulerDb.connection().use { c ->
            c.prepareStatement(
                """
                SELECT u.id, u.username, u.display_name, u.role, u.client_code
                FROM auth_tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, token)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toAuthUser() else null }
            }
        }
    }

    /** Revokes a token (logout). */
    fun deleteToken(token: String) {
        SchedulerDb.connection().use { c ->
            c.prepareStatement("DELETE FROM auth_tokens WHERE token = ?").use { ps ->
                ps.setString(1, token)
                ps.executeUpdate()
            }
        }
    }

    private fun java.sql.ResultSet.toAuthUser() = AuthUser(
        id = getLong("id"),
        username = getString("username"),
        displayName = getString("display_name"),
        role = UserRole.valueOf(getString("role")),
        clientCode = getString("client_code")
    )

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
