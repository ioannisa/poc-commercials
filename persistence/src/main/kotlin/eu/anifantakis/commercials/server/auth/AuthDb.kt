package eu.anifantakis.commercials.server.auth

import eu.anifantakis.commercials.server.scheduler.CentralDb
import eu.anifantakis.commercials.server.stations.HostingConfig
import eu.anifantakis.commercials.server.stations.SessionConfig
import eu.anifantakis.commercials.server.stations.StationRegistry
import org.koin.core.annotation.Provided
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** How long an emailed reset code stays valid. Public so a route can tell the user. */
const val PASSWORD_RESET_TTL_SECONDS = 15L * 60

/**
 * Application users + bearer tokens + per-station grants + email password-reset
 * codes, all in the server's CENTRAL database. Users are server-level accounts;
 * what they may touch is expressed per hosted station in `user_station_grants`.
 *
 * Password recovery is by emailed 6-digit code (self-service) or an admin reset
 * that mints a temp password and forces a change at next login; there are no
 * user-held recovery codes.
 *
 * Passwords are stored as PBKDF2-HMAC-SHA256 hashes with a per-user random
 * salt (never plaintext, even for demo users). Tokens are 256-bit random
 * values whose lifetime is governed by the server.yaml `session:` block
 * (see [SessionConfig]): never-expire, a fixed window, or a sliding idle
 * timeout. Revocation is independent of expiry - every token is a DB row, so
 * logout (or a password change, which revokes ALL of the user's tokens)
 * removes it and the very next request gets 401.
 *
 * Tokens and reset codes are stored HASHED (single SHA-256, see [tokenHash]):
 * the raw token exists only on the client, so a database leak yields nothing
 * usable as a credential. A 256-bit token needs no slow salted hashing (it is
 * high-entropy CSPRNG output); the 6-digit reset code is guarded instead by an
 * online rate-limit + short TTL + single use.
 *
 * THE SUPER ADMIN is config-managed (server.yaml `superAdmin` block): its
 * plaintext lives only in that file; bootstrap upserts a users row holding
 * just the PBKDF2 hash and re-syncs it every boot (editing the YAML password
 * + restart = rotation, with all its sessions revoked). It cannot be deleted,
 * demoted, or have its password/recovery managed through the API.
 *
 * Koin singleton. HostingConfig is @Provided (classic-DSL file-loading
 * definition, invisible to the compile-time graph checker).
 */
class AuthDb(
    private val db: CentralDb,
    private val registry: StationRegistry,
    @Provided private val hosting: HostingConfig,
) {

    private companion object {
        const val PBKDF2_ITERATIONS = 100_000
        const val PBKDF2_KEY_BITS = 256
        const val MIN_PASSWORD_LENGTH = 6

        /** Email password-reset code: six digits, no letters (so it is language-
         *  and keyboard-agnostic - a Greek/English layout can't mistype it). */
        const val RESET_CODE_DIGITS = 6
        /** Wrong tries allowed with no delay before the escalating lock kicks in. */
        const val RESET_FREE_ATTEMPTS = 5
        /** First lock after the free tries are spent; each further wrong try ×3. */
        const val RESET_BASE_LOCK_SECONDS = 10L
        const val RESET_LOCK_MULTIPLIER = 3

        /** A generated temporary password (admin reset / new account). */
        const val TEMP_PASSWORD_LENGTH = 12

        /** Crockford-style alphabet: no I/L/O/U to avoid transcription errors. */
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ0123456789"
        val USERNAME_FORMAT = Regex("[a-zA-Z0-9._-]{3,64}")
    }

    private val secureRandom = SecureRandom()

    /*
     * Bearer-token lifetime, driven by the server.yaml `session:` block
     * (see [SessionConfig]):
     * - expiration off -> tokens never expire (expires_at NULL); revoked only
     *   by logout or a password change.
     * - expiration on  -> a token dies [tokenTtlSeconds] after the relevant
     *   window; with [slidingEnabled] the window is pushed forward on use
     *   (idle timeout), otherwise it is fixed from login.
     */
    private val expiryEnabled: Boolean get() = hosting.session.expiration
    private val slidingEnabled: Boolean get() = hosting.session.sliding
    private val tokenTtlSeconds: Long get() = hosting.session.days.toLong() * 24 * 60 * 60

    // ────────────────────────────────────────────────────────── bootstrap ──

    /**
     * Creates tables, seeds demo users/grants when empty, and syncs the
     * config-managed super admin.
     */
    fun bootstrap() {
        db.connection().use { c ->
            dropLegacyPlaintextTokenTable(c)
            c.createStatement().use { s ->
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(64) NOT NULL UNIQUE,
                        password_hash VARCHAR(64) NOT NULL,
                        password_salt VARCHAR(32) NOT NULL,
                        display_name VARCHAR(128) NOT NULL,
                        email VARCHAR(255) NULL,
                        must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
                        is_admin BOOLEAN NOT NULL DEFAULT FALSE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS auth_tokens (
                        token_hash VARCHAR(64) PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at TIMESTAMP NULL DEFAULT NULL,
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
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS password_resets (
                        user_id BIGINT PRIMARY KEY,
                        code_hash VARCHAR(64) NOT NULL,
                        expires_at TIMESTAMP NOT NULL,
                        wrong_count INT NOT NULL DEFAULT 0,
                        locked_until TIMESTAMP NULL DEFAULT NULL,
                        CONSTRAINT fk_resets_user FOREIGN KEY (user_id)
                            REFERENCES users(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS api_tokens (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        token_hash VARCHAR(64) NOT NULL UNIQUE,
                        user_id BIGINT NOT NULL,
                        name VARCHAR(128) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_used_at TIMESTAMP NULL DEFAULT NULL,
                        CONSTRAINT fk_apitokens_user FOREIGN KEY (user_id)
                            REFERENCES users(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
            }
            ensureIsAdminColumn(c)
            ensureExpiresAtColumn(c)
            ensureColumn(c, "users", "email", "VARCHAR(255) NULL")
            ensureColumn(c, "users", "must_change_password", "BOOLEAN NOT NULL DEFAULT FALSE")
            // Recovery codes were retired in favour of email + admin reset; drop
            // the legacy table so a leaked backup can't be replayed as a credential.
            c.createStatement().use { it.executeUpdate("DROP TABLE IF EXISTS recovery_codes") }
            seedUsersIfEmpty(c)
            seedGrantsIfEmpty(c, registry.ids)
            syncSuperAdmin(c)
        }
    }

    /**
     * Migration: earlier deployments stored RAW tokens in a `token` column.
     * Tokens are disposable (users just log in again - the client's 401
     * handler routes them there automatically), so the legacy table is simply
     * dropped and recreated with the hashed shape. Idempotent: does nothing
     * once the table has the new shape.
     */
    private fun dropLegacyPlaintextTokenTable(c: Connection) {
        if (columnExists(c, "auth_tokens", "token")) {
            c.createStatement().use { it.executeUpdate("DROP TABLE auth_tokens") }
        }
    }

    /** Migration: pre-superadmin deployments lack the is_admin column. */
    private fun ensureIsAdminColumn(c: Connection) {
        if (!columnExists(c, "users", "is_admin")) {
            c.createStatement().use {
                it.executeUpdate("ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE")
            }
        }
    }

    /**
     * Adds [column] to [table] with the given DDL type if it is missing.
     * [table]/[column]/[ddl] are internal constants, never user input.
     */
    private fun ensureColumn(c: Connection, table: String, column: String, ddl: String) {
        if (!columnExists(c, table, column)) {
            c.createStatement().use {
                it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $ddl")
            }
        }
    }

    /**
     * Migration: pre-expiry deployments lack the expires_at column; an early
     * expiry build may have added it NOT NULL. Converge on nullable (NULL =
     * "never expires"): add it if missing, or relax an existing NOT NULL.
     * Existing sessions get a fresh window under an expiring policy, or NULL
     * under a no-expiry policy - never evicted on deploy.
     */
    private fun ensureExpiresAtColumn(c: Connection) {
        if (!columnExists(c, "auth_tokens", "expires_at")) {
            c.createStatement().use {
                it.executeUpdate("ALTER TABLE auth_tokens ADD COLUMN expires_at TIMESTAMP NULL DEFAULT NULL")
            }
            if (expiryEnabled) {
                c.prepareStatement("UPDATE auth_tokens SET expires_at = DATE_ADD(NOW(), INTERVAL ? SECOND)").use { ps ->
                    ps.setLong(1, tokenTtlSeconds)
                    ps.executeUpdate()
                }
            }
        } else if (!columnIsNullable(c, "auth_tokens", "expires_at")) {
            c.createStatement().use {
                it.executeUpdate("ALTER TABLE auth_tokens MODIFY COLUMN expires_at TIMESTAMP NULL DEFAULT NULL")
            }
        }
    }

    private fun columnIsNullable(c: Connection, table: String, column: String): Boolean =
        c.prepareStatement(
            """
            SELECT is_nullable FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, column)
            ps.executeQuery().use { if (it.next()) it.getString(1) == "YES" else false }
        }

    private fun columnExists(c: Connection, table: String, column: String): Boolean =
        c.prepareStatement(
            """
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, column)
            ps.executeQuery().use { it.next() }
        }

    /**
     * Upserts the users row for the YAML-managed super admin. The DB holds
     * only the PBKDF2 hash; if the YAML password changed since last boot, the
     * hash is rewritten and every session of the account is revoked.
     */
    private fun syncSuperAdmin(c: Connection) {
        val admin = hosting.admin

        data class Row(val id: Long, val hashHex: String, val saltHex: String, val isAdmin: Boolean, val displayName: String)

        val existing = c.prepareStatement(
            "SELECT id, password_hash, password_salt, is_admin, display_name FROM users WHERE username = ?"
        ).use { ps ->
            ps.setString(1, admin.username)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else Row(
                    rs.getLong("id"), rs.getString("password_hash"), rs.getString("password_salt"),
                    rs.getBoolean("is_admin"), rs.getString("display_name")
                )
            }
        }

        if (existing == null) {
            val salt = randomBytes(16)
            c.prepareStatement(
                "INSERT INTO users(username, password_hash, password_salt, display_name, is_admin) VALUES(?,?,?,?,TRUE)"
            ).use { ps ->
                ps.setString(1, admin.username)
                ps.setString(2, pbkdf2(admin.password, salt).toHex())
                ps.setString(3, salt.toHex())
                ps.setString(4, admin.displayName)
                ps.executeUpdate()
            }
            return
        }

        val passwordMatches = MessageDigest.isEqual(
            pbkdf2(admin.password, existing.saltHex.hexToBytes()),
            existing.hashHex.hexToBytes()
        )
        if (!passwordMatches) {
            val salt = randomBytes(16)
            c.prepareStatement("UPDATE users SET password_hash = ?, password_salt = ? WHERE id = ?").use { ps ->
                ps.setString(1, pbkdf2(admin.password, salt).toHex())
                ps.setString(2, salt.toHex())
                ps.setLong(3, existing.id)
                ps.executeUpdate()
            }
            // Rotation via YAML edit kills existing sessions of this account
            c.prepareStatement("DELETE FROM auth_tokens WHERE user_id = ?").use { ps ->
                ps.setLong(1, existing.id); ps.executeUpdate()
            }
        }
        if (!existing.isAdmin || existing.displayName != admin.displayName) {
            c.prepareStatement("UPDATE users SET is_admin = TRUE, display_name = ? WHERE id = ?").use { ps ->
                ps.setString(1, admin.displayName)
                ps.setLong(2, existing.id)
                ps.executeUpdate()
            }
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

    // ─────────────────────────────────────────────────────── login/tokens ──

    /** Returns the user (with grants) if username+password are valid, null otherwise. */
    fun verifyCredentials(username: String, password: String): AuthUser? {
        return db.connection().use { c ->
            val row = selectUserByUsername(c, username) ?: return null

            val candidate = pbkdf2(password, row.saltHex.hexToBytes())
            // Constant-time comparison - never compare password hashes with ==
            if (!MessageDigest.isEqual(candidate, row.hashHex.hexToBytes())) return null

            toAuthUser(c, row)
        }
    }

    /**
     * Creates a new token for the user, with the window the `session:` policy
     * calls for (NULL under a no-expiry policy). Only its SHA-256 is stored;
     * the returned RAW token exists nowhere but the client.
     */
    fun createToken(userId: Long): String {
        val token = randomBytes(32).toHex()
        db.connection().use { c ->
            if (expiryEnabled) {
                c.prepareStatement(
                    "INSERT INTO auth_tokens(token_hash, user_id, expires_at) " +
                        "VALUES(?, ?, DATE_ADD(NOW(), INTERVAL ? SECOND))"
                ).use { ps ->
                    ps.setString(1, tokenHash(token))
                    ps.setLong(2, userId)
                    ps.setLong(3, tokenTtlSeconds)
                    ps.executeUpdate()
                }
            } else {
                // No-expiry policy: NULL window (valid until logout / password change).
                c.prepareStatement("INSERT INTO auth_tokens(token_hash, user_id, expires_at) VALUES(?, ?, NULL)").use { ps ->
                    ps.setString(1, tokenHash(token))
                    ps.setLong(2, userId)
                    ps.executeUpdate()
                }
            }
        }
        return token
    }

    /**
     * Resolves a bearer token to its user (with grants), or null if
     * unknown/revoked/EXPIRED. On a valid hit the sliding window is pushed
     * forward - but only once the token is past its half-life, so an active
     * session costs at most one write per [tokenTtlSeconds]/2, not one per
     * request.
     */
    fun findUserByToken(token: String): AuthUser? {
        if (token.isBlank()) return null
        val hash = tokenHash(token)
        return db.connection().use { c ->
            // A short-lived SESSION token (auth_tokens, expiry + sliding window),
            // or a PERSONAL ACCESS TOKEN (api_tokens: non-expiring, revocable, for
            // MCP / programmatic clients). Both resolve to the same user, so the
            // same per-station grants and role apply either way.
            findBySessionToken(c, hash) ?: findByApiToken(c, hash)
        }
    }

    /** Session token: honours the expiry policy and slides the window on use. */
    private fun findBySessionToken(c: Connection, hash: String): AuthUser? {
        // No-expiry policy ignores the window entirely; an expiring policy rejects
        // lapsed tokens (a NULL window never lapses).
        val expiryClause = if (expiryEnabled) " AND (t.expires_at IS NULL OR t.expires_at > NOW())" else ""
        val row = c.prepareStatement(
            """
            SELECT u.id, u.username, u.display_name, u.is_admin, u.password_hash, u.password_salt,
                   u.must_change_password
            FROM auth_tokens t, users u
            WHERE u.id = t.user_id
              AND t.token_hash = ?$expiryClause
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUserRow() else null }
        } ?: return null

        // Sliding renewal, throttled to past-half-life (a freshly-renewed or NULL
        // window fails the WHERE and the statement is a no-op).
        if (expiryEnabled && slidingEnabled) {
            c.prepareStatement(
                "UPDATE auth_tokens SET expires_at = DATE_ADD(NOW(), INTERVAL ? SECOND) " +
                    "WHERE token_hash = ? AND expires_at IS NOT NULL " +
                    "AND expires_at < DATE_ADD(NOW(), INTERVAL ? SECOND)"
            ).use { ps ->
                ps.setLong(1, tokenTtlSeconds)
                ps.setString(2, hash)
                ps.setLong(3, tokenTtlSeconds / 2)
                ps.executeUpdate()
            }
        }
        return toAuthUser(c, row)
    }

    /**
     * Personal access token: NEVER expires (a machine credential); revoked only
     * by deleting its row. Stamps last_used_at, throttled to at most one write a
     * minute so a chatty MCP client does not hammer the row.
     */
    private fun findByApiToken(c: Connection, hash: String): AuthUser? {
        val row = c.prepareStatement(
            """
            SELECT u.id, u.username, u.display_name, u.is_admin, u.password_hash, u.password_salt,
                   u.must_change_password
            FROM api_tokens a, users u
            WHERE u.id = a.user_id AND a.token_hash = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUserRow() else null }
        } ?: return null

        c.prepareStatement(
            "UPDATE api_tokens SET last_used_at = NOW() WHERE token_hash = ? " +
                "AND (last_used_at IS NULL OR last_used_at < DATE_SUB(NOW(), INTERVAL 60 SECOND))"
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeUpdate()
        }
        return toAuthUser(c, row)
    }

    /**
     * Seconds of life left in [token], or null when it never lapses (no-expiry
     * policy, or a NULL window). The client's keep-alive uses this to pick its
     * heartbeat interval instead of hard-coding one that a `session:` edit
     * would silently invalidate.
     *
     * Returns null for an unknown or already-lapsed token too - the caller only
     * ever reaches this AFTER the bearer auth accepted the token, so those
     * cases are unreachable here, and a null reads correctly as "nothing to
     * keep alive" either way.
     */
    fun tokenExpiresInSeconds(token: String): Long? {
        if (!expiryEnabled || token.isBlank()) return null
        return db.connection().use { c ->
            c.prepareStatement(
                "SELECT TIMESTAMPDIFF(SECOND, NOW(), expires_at) FROM auth_tokens " +
                    "WHERE token_hash = ? AND expires_at IS NOT NULL"
            ).use { ps ->
                ps.setString(1, tokenHash(token))
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() && it > 0 } else null
                }
            }
        }
    }

    /** Revokes a token (logout). */
    fun deleteToken(token: String) {
        db.connection().use { c ->
            c.prepareStatement("DELETE FROM auth_tokens WHERE token_hash = ?").use { ps ->
                ps.setString(1, tokenHash(token))
                ps.executeUpdate()
            }
        }
    }

    /** Revokes every session of a user - called on any password change. */
    private fun revokeAllTokens(c: Connection, userId: Long) {
        c.prepareStatement("DELETE FROM auth_tokens WHERE user_id = ?").use { ps ->
            ps.setLong(1, userId)
            ps.executeUpdate()
        }
    }

    // ──────────────────────────────────────────────── password lifecycle ──

    /**
     * Self-service password change. Verifies [currentPassword] first; on
     * success all the user's sessions are revoked (they log in again with the
     * new password). Refused for the YAML-managed super admin.
     */
    fun changePassword(userId: Long, currentPassword: String, newPassword: String) {
        validatePassword(newPassword)
        db.connection().use { c ->
            val row = selectUserById(c, userId) ?: throw IllegalArgumentException("Unknown user")
            require(!row.isAdmin) { "The super admin password is managed in server.yaml, not via the API" }
            val matches = MessageDigest.isEqual(
                pbkdf2(currentPassword, row.saltHex.hexToBytes()),
                row.hashHex.hexToBytes()
            )
            require(matches) { "Current password is incorrect" }
            updatePassword(c, userId, newPassword)
        }
    }

    /**
     * A user-chosen password change/reset: clears must_change_password (they
     * picked it themselves) and revokes all sessions (log in again).
     */
    private fun updatePassword(c: Connection, userId: Long, newPassword: String) {
        setPassword(c, userId, newPassword, mustChange = false)
        revokeAllTokens(c, userId)
    }

    /**
     * Writes a fresh PBKDF2 hash + salt and sets must_change_password. Does NOT
     * revoke tokens - callers decide (a change/reset revokes; a flag flip need
     * not). [mustChange] = true for an admin reset or a new account: the temp
     * password only gets the user to the forced change-password screen.
     */
    private fun setPassword(c: Connection, userId: Long, newPassword: String, mustChange: Boolean) {
        val salt = randomBytes(16)
        c.prepareStatement(
            "UPDATE users SET password_hash = ?, password_salt = ?, must_change_password = ? WHERE id = ?"
        ).use { ps ->
            ps.setString(1, pbkdf2(newPassword, salt).toHex())
            ps.setString(2, salt.toHex())
            ps.setBoolean(3, mustChange)
            ps.setLong(4, userId)
            ps.executeUpdate()
        }
    }

    // ─────────────────────────────────────────────── password reset (email) ──

    /** The raw reset code (to email) plus the address to send it to. */
    data class ResetRequest(val code: String, val email: String)

    /** The outcome of submitting a reset code. */
    sealed interface ResetOutcome {
        data object Success : ResetOutcome
        /** Wrong code. [retryAfterSeconds] is non-null once the escalating lock armed. */
        data class InvalidCode(val retryAfterSeconds: Long?) : ResetOutcome
        /** A try arrived while still locked out; wait [retryAfterSeconds]. */
        data class Locked(val retryAfterSeconds: Long) : ResetOutcome
        data object Expired : ResetOutcome
        data object NoRequest : ResetOutcome
    }

    /** A generated temporary password, the account it is for, and where to email it (if any). */
    data class TempPassword(val password: String, val username: String, val email: String?)

    /**
     * Starts a "forgot password" flow: mints a fresh [RESET_CODE_DIGITS]-digit
     * numeric code, stores its hash with a [RESET_CODE_TTL_SECONDS] expiry
     * (replacing any earlier request and clearing its lockout), and returns the
     * RAW code + the user's email so the caller can send it. Returns null - and
     * nothing is emailed - when the username is unknown, is the super admin, or
     * has no email on file. The ROUTE answers identically either way, so this
     * never reveals whether an account exists.
     */
    fun createPasswordReset(username: String): ResetRequest? {
        db.connection().use { c ->
            val row = selectUserByUsername(c, username) ?: return null
            if (row.isAdmin) return null
            val email = selectEmail(c, row.id)?.takeIf { it.isNotBlank() } ?: return null

            val code = generateNumericCode(RESET_CODE_DIGITS)
            c.prepareStatement(
                """
                INSERT INTO password_resets(user_id, code_hash, expires_at, wrong_count, locked_until)
                VALUES(?, ?, DATE_ADD(NOW(), INTERVAL ? SECOND), 0, NULL) AS new
                ON DUPLICATE KEY UPDATE code_hash = new.code_hash,
                    expires_at = new.expires_at, wrong_count = 0, locked_until = NULL
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, row.id)
                ps.setString(2, tokenHash(code))
                ps.setLong(3, PASSWORD_RESET_TTL_SECONDS)
                ps.executeUpdate()
            }
            return ResetRequest(code, email)
        }
    }

    /**
     * Completes a "forgot password" flow with the emailed code, enforcing the
     * escalating lockout: [RESET_FREE_ATTEMPTS] wrong tries are free, then each
     * further wrong try locks for
     * RESET_BASE_LOCK_SECONDS × RESET_LOCK_MULTIPLIER^n (10s, 30s, 90s…). On
     * success the password is set, the flag cleared, the request consumed and
     * every session revoked. A wrong username/expired/absent request all read as
     * generic failures - never revealing which.
     */
    fun resetPasswordWithCode(username: String, code: String, newPassword: String): ResetOutcome {
        validatePassword(newPassword)
        db.connection().use { c ->
            val row = selectUserByUsername(c, username) ?: return ResetOutcome.NoRequest
            if (row.isAdmin) return ResetOutcome.NoRequest

            data class Reset(val codeHash: String, val expired: Boolean, val wrongCount: Int, val lockRemaining: Long)
            val reset = c.prepareStatement(
                """
                SELECT code_hash,
                       (expires_at <= NOW()) AS expired,
                       wrong_count,
                       GREATEST(COALESCE(TIMESTAMPDIFF(SECOND, NOW(), locked_until), 0), 0) AS lock_remaining
                FROM password_resets WHERE user_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, row.id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else Reset(
                        rs.getString("code_hash"),
                        rs.getBoolean("expired"),
                        rs.getInt("wrong_count"),
                        rs.getLong("lock_remaining"),
                    )
                }
            } ?: return ResetOutcome.NoRequest

            if (reset.lockRemaining > 0) return ResetOutcome.Locked(reset.lockRemaining)
            if (reset.expired) {
                deletePasswordReset(c, row.id)
                return ResetOutcome.Expired
            }

            // Constant-time compare of the stored vs submitted code hash.
            val matches = MessageDigest.isEqual(
                tokenHash(code).encodeToByteArray(),
                reset.codeHash.encodeToByteArray(),
            )
            if (matches) {
                updatePassword(c, row.id, newPassword)
                deletePasswordReset(c, row.id)
                return ResetOutcome.Success
            }

            // Wrong code: bump the counter and, once the free tries are spent,
            // arm the escalating lock (served BEFORE the next attempt).
            val wrong = reset.wrongCount + 1
            val lockSeconds = if (wrong > RESET_FREE_ATTEMPTS) {
                RESET_BASE_LOCK_SECONDS * intPow(RESET_LOCK_MULTIPLIER, wrong - RESET_FREE_ATTEMPTS - 1)
            } else 0L
            c.prepareStatement(
                "UPDATE password_resets SET wrong_count = ?, " +
                    "locked_until = CASE WHEN ? > 0 THEN DATE_ADD(NOW(), INTERVAL ? SECOND) ELSE NULL END " +
                    "WHERE user_id = ?"
            ).use { ps ->
                ps.setInt(1, wrong)
                ps.setLong(2, lockSeconds)
                ps.setLong(3, lockSeconds)
                ps.setLong(4, row.id)
                ps.executeUpdate()
            }
            return ResetOutcome.InvalidCode(lockSeconds.takeIf { it > 0 })
        }
    }

    /**
     * Admin/super-admin reset: sets a random TEMP password (returned RAW for the
     * admin to see/copy and to email), forces a change at next login, clears any
     * reset lockout, and revokes all sessions. This is the mail-server-independent
     * path - the temp password is ALWAYS returned to the admin, whether or not the
     * mail goes out. Refused for the super admin (managed in server.yaml).
     */
    fun adminResetPassword(userId: Long): TempPassword {
        db.connection().use { c ->
            val row = selectUserById(c, userId) ?: throw IllegalArgumentException("Unknown user")
            require(!row.isAdmin) { "The super admin password is managed in server.yaml, not via the API" }
            val temp = generateTempPassword()
            setPassword(c, userId, temp, mustChange = true)
            deletePasswordReset(c, userId)
            revokeAllTokens(c, userId)
            return TempPassword(temp, row.username, selectEmail(c, userId)?.takeIf { it.isNotBlank() })
        }
    }

    private fun selectEmail(c: Connection, userId: Long): String? =
        c.prepareStatement("SELECT email FROM users WHERE id = ?").use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("email") else null }
        }

    private fun deletePasswordReset(c: Connection, userId: Long) {
        c.prepareStatement("DELETE FROM password_resets WHERE user_id = ?").use { ps ->
            ps.setLong(1, userId); ps.executeUpdate()
        }
    }

    private fun generateNumericCode(digits: Int): String =
        (1..digits).joinToString("") { secureRandom.nextInt(10).toString() }

    private fun generateTempPassword(): String =
        (1..TEMP_PASSWORD_LENGTH)
            .map { CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)] }
            .joinToString("")

    /** Integer power for the lock escalation (avoids Double rounding). */
    private fun intPow(base: Int, exp: Int): Long {
        var r = 1L
        repeat(exp) { r *= base }
        return r
    }

    // ─────────────────────────────────────────── API tokens (MCP / programmatic) ──

    /** A personal access token as its owner/admin lists it - never the secret itself. */
    data class ApiTokenInfo(
        val id: Long,
        val name: String,
        val username: String,
        val createdAt: String,
        val lastUsedAt: String?,
    )

    /**
     * Mints a NON-EXPIRING personal access token for [userId] and returns it RAW,
     * once - only its SHA-256 is stored. It authenticates AS that user, so the same
     * per-station grants and role apply (an MCP client inherits the user's exact
     * restrictions). Revoked only by [revokeApiToken] / [revokeApiTokenById].
     */
    fun createApiToken(userId: Long, name: String): String {
        require(name.isNotBlank()) { "Token name must not be blank" }
        val token = randomBytes(32).toHex()
        db.connection().use { c ->
            selectUserById(c, userId) ?: throw IllegalArgumentException("Unknown user")
            c.prepareStatement("INSERT INTO api_tokens(token_hash, user_id, name) VALUES(?,?,?)").use { ps ->
                ps.setString(1, tokenHash(token))
                ps.setLong(2, userId)
                ps.setString(3, name.trim())
                ps.executeUpdate()
            }
        }
        return token
    }

    /** The caller's OWN tokens (self-service list). */
    fun listApiTokens(userId: Long): List<ApiTokenInfo> =
        db.connection().use { c ->
            c.prepareStatement(
                "SELECT a.id, a.name, u.username, a.created_at, a.last_used_at " +
                    "FROM api_tokens a JOIN users u ON u.id = a.user_id " +
                    "WHERE a.user_id = ? ORDER BY a.created_at DESC"
            ).use { ps ->
                ps.setLong(1, userId)
                ps.executeQuery().use { rs -> rs.toApiTokenList() }
            }
        }

    /** Every token across all users (admin oversight). */
    fun listAllApiTokens(): List<ApiTokenInfo> =
        db.connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT a.id, a.name, u.username, a.created_at, a.last_used_at " +
                        "FROM api_tokens a JOIN users u ON u.id = a.user_id ORDER BY a.created_at DESC"
                ).use { rs -> rs.toApiTokenList() }
            }
        }

    /** Revokes one of the CALLER's own tokens (ownership enforced). True if it existed. */
    fun revokeApiToken(userId: Long, tokenId: Long): Boolean =
        db.connection().use { c ->
            c.prepareStatement("DELETE FROM api_tokens WHERE id = ? AND user_id = ?").use { ps ->
                ps.setLong(1, tokenId); ps.setLong(2, userId)
                ps.executeUpdate() == 1
            }
        }

    /** Admin revoke of ANY token by id. True if it existed. */
    fun revokeApiTokenById(tokenId: Long): Boolean =
        db.connection().use { c ->
            c.prepareStatement("DELETE FROM api_tokens WHERE id = ?").use { ps ->
                ps.setLong(1, tokenId)
                ps.executeUpdate() == 1
            }
        }

    private fun java.sql.ResultSet.toApiTokenList(): List<ApiTokenInfo> = buildList {
        while (next()) {
            add(
                ApiTokenInfo(
                    id = getLong("id"),
                    name = getString("name"),
                    username = getString("username"),
                    createdAt = getString("created_at"),
                    lastUsedAt = getString("last_used_at"),
                )
            )
        }
    }

    // ───────────────────────────────────────────────────── user management ──

    /**
     * Revokes every user's access to a station - part of deleting a hosted
     * database ("safe delete"). Idempotent; returns the number of grants removed.
     */
    fun deleteGrantsForStation(stationId: String): Int =
        db.connection().use { c ->
            c.prepareStatement("DELETE FROM user_station_grants WHERE station_id = ?").use { ps ->
                ps.setString(1, stationId)
                ps.executeUpdate()
            }
        }

    data class UserSummary(
        val id: Long,
        val username: String,
        val displayName: String,
        val email: String?,
        val isAdmin: Boolean,
        val grants: List<StationGrant>,
    )

    fun listUsers(): List<UserSummary> =
        db.connection().use { c ->
            val users = c.createStatement().use { s ->
                s.executeQuery("SELECT id, username, display_name, email, is_admin FROM users ORDER BY username").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                UserSummary(
                                    id = rs.getLong("id"),
                                    username = rs.getString("username"),
                                    displayName = rs.getString("display_name"),
                                    email = rs.getString("email"),
                                    isAdmin = rs.getBoolean("is_admin"),
                                    grants = emptyList(),
                                )
                            )
                        }
                    }
                }
            }
            users.map { it.copy(grants = loadGrants(c, it.id)) }
        }

    /**
     * Creates a user with the given grants and returns its id. The password
     * goes through the same PBKDF2 path as everyone else's.
     * @throws IllegalArgumentException for invalid input or duplicate username
     */
    fun createUser(
        username: String,
        displayName: String,
        password: String,
        grants: List<StationGrant>,
        email: String? = null,
        mustChangePassword: Boolean = false,
    ): Long {
        require(username.matches(USERNAME_FORMAT)) {
            "Username must be 3-64 chars of letters, digits, '.', '_' or '-'"
        }
        validatePassword(password)
        require(displayName.isNotBlank()) { "Display name must not be blank" }
        validateGrants(grants)

        db.connection().use { c ->
            val exists = c.prepareStatement("SELECT 1 FROM users WHERE username = ?").use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { it.next() }
            }
            require(!exists) { "Username '$username' is already taken" }

            c.autoCommit = false
            try {
                val salt = randomBytes(16)
                val userId = c.prepareStatement(
                    "INSERT INTO users(username, password_hash, password_salt, display_name, email, must_change_password) " +
                        "VALUES(?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS
                ).use { ps ->
                    ps.setString(1, username)
                    ps.setString(2, pbkdf2(password, salt).toHex())
                    ps.setString(3, salt.toHex())
                    ps.setString(4, displayName)
                    ps.setString(5, email?.takeIf { it.isNotBlank() })
                    ps.setBoolean(6, mustChangePassword)
                    ps.executeUpdate()
                    ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
                }
                insertGrants(c, userId, grants)
                c.commit()
                return userId
            } catch (e: Exception) {
                c.rollback(); throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    /** A newly created user: id + the RAW temp password (shown once) + email (if set). */
    data class CreatedUser(val id: Long, val tempPassword: String, val email: String?)

    /**
     * Admin create: makes a user with a generated TEMP password (must be changed
     * at first login) and returns it RAW so the admin can relay it and it can be
     * emailed. Delegates to [createUser] for all validation.
     */
    fun createUserWithTempPassword(
        username: String,
        displayName: String,
        email: String?,
        grants: List<StationGrant>,
    ): CreatedUser {
        val temp = generateTempPassword()
        val id = createUser(username, displayName, temp, grants, email = email, mustChangePassword = true)
        return CreatedUser(id, temp, email?.takeIf { it.isNotBlank() })
    }

    /** Replaces a user's grants wholesale. Refused for the super admin (its access is implicit). */
    fun setGrants(userId: Long, grants: List<StationGrant>) {
        validateGrants(grants)
        db.connection().use { c ->
            val row = selectUserById(c, userId) ?: throw IllegalArgumentException("Unknown user")
            require(!row.isAdmin) { "The super admin has implicit access to every station" }
            c.autoCommit = false
            try {
                c.prepareStatement("DELETE FROM user_station_grants WHERE user_id = ?").use { ps ->
                    ps.setLong(1, userId); ps.executeUpdate()
                }
                insertGrants(c, userId, grants)
                c.commit()
            } catch (e: Exception) {
                c.rollback(); throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    /** Deletes a user (tokens/grants/codes cascade). Refused for the super admin. */
    fun deleteUser(userId: Long) {
        db.connection().use { c ->
            val row = selectUserById(c, userId) ?: throw IllegalArgumentException("Unknown user")
            require(!row.isAdmin) { "The super admin cannot be deleted - it is managed in server.yaml" }
            c.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setLong(1, userId)
                ps.executeUpdate()
            }
        }
    }

    private fun insertGrants(c: Connection, userId: Long, grants: List<StationGrant>) {
        if (grants.isEmpty()) return
        c.prepareStatement(
            "INSERT INTO user_station_grants(user_id, station_id, role, client_code) VALUES(?,?,?,?)"
        ).use { ps ->
            for (g in grants) {
                ps.setLong(1, userId)
                ps.setString(2, g.stationId)
                ps.setString(3, g.role.name)
                ps.setString(4, g.clientCode)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun validateGrants(grants: List<StationGrant>) {
        val hosted = registry.ids.toSet()
        val unknown = grants.map { it.stationId }.filterNot { it in hosted }
        require(unknown.isEmpty()) { "Unknown station(s) in grants: $unknown (hosted: $hosted)" }
        val duplicated = grants.groupBy { it.stationId }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "Duplicate grants for station(s): $duplicated" }
        grants.forEach {
            require(it.role != UserRole.CUSTOMER_VIEWER || !it.clientCode.isNullOrBlank()) {
                "CUSTOMER_VIEWER grant on '${it.stationId}' requires a clientCode"
            }
        }
    }

    private fun validatePassword(password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "Password must be at least $MIN_PASSWORD_LENGTH characters"
        }
    }

    // ─────────────────────────────────────────────────────────── plumbing ──

    private data class UserRow(
        val id: Long,
        val username: String,
        val displayName: String,
        val isAdmin: Boolean,
        val hashHex: String,
        val saltHex: String,
        val mustChangePassword: Boolean,
    )

    private fun java.sql.ResultSet.toUserRow() = UserRow(
        id = getLong("id"),
        username = getString("username"),
        displayName = getString("display_name"),
        isAdmin = getBoolean("is_admin"),
        hashHex = getString("password_hash"),
        saltHex = getString("password_salt"),
        mustChangePassword = getBoolean("must_change_password"),
    )

    private fun selectUserByUsername(c: Connection, username: String): UserRow? =
        c.prepareStatement(
            "SELECT id, username, display_name, is_admin, password_hash, password_salt, must_change_password " +
                "FROM users WHERE username = ?"
        ).use { ps ->
            ps.setString(1, username)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUserRow() else null }
        }

    private fun selectUserById(c: Connection, id: Long): UserRow? =
        c.prepareStatement(
            "SELECT id, username, display_name, is_admin, password_hash, password_salt, must_change_password " +
                "FROM users WHERE id = ?"
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUserRow() else null }
        }

    /**
     * The super admin's station access is implicit and synthesized fresh on
     * every principal load: NORMAL_USER on every currently hosted station -
     * adding a station to the YAML grants it automatically.
     */
    private fun toAuthUser(c: Connection, row: UserRow) = AuthUser(
        id = row.id,
        username = row.username,
        displayName = row.displayName,
        isAdmin = row.isAdmin,
        grants = if (row.isAdmin) {
            registry.ids.map { StationGrant(it, UserRole.NORMAL_USER, null) }
        } else {
            loadGrants(c, row.id)
        },
        mustChangePassword = row.mustChangePassword,
    )

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

/**
 * How bearer tokens and email reset codes are stored at rest: a single SHA-256
 * over the raw value. For a 256-bit token, fast hashing is correct - the input
 * is high-entropy CSPRNG output, so a DB leak yields nothing brute-forceable.
 * The 6-digit reset code is low-entropy by design; hashing it at rest is only
 * defence-in-depth - its real protection is the online rate-limit + 15-minute
 * TTL + single use in [AuthDb.resetPasswordWithCode], not hash strength.
 */
internal fun tokenHash(token: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(token.encodeToByteArray())
        .joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
