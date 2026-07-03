package eu.anifantakis.commercials.server.auth

import eu.anifantakis.commercials.server.scheduler.CentralDb
import eu.anifantakis.commercials.server.stations.HostingConfig
import eu.anifantakis.commercials.server.stations.StationRegistry
import org.koin.core.annotation.Provided
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Application users + bearer tokens + per-station grants + recovery codes,
 * all in the server's CENTRAL database. Users are server-level accounts; what
 * they may touch is expressed per hosted station in `user_station_grants`.
 *
 * Passwords are stored as PBKDF2-HMAC-SHA256 hashes with a per-user random
 * salt (never plaintext, even for demo users). Tokens are 256-bit random
 * values with NO expiration by design; revocation still works because every
 * token is a DB row - logout (or a password change, which revokes ALL of the
 * user's tokens) removes it and the very next request gets 401.
 *
 * Tokens and recovery codes are stored HASHED (single SHA-256, see
 * [tokenHash]): the raw values exist only on the client, so a database leak
 * yields nothing usable as a credential. Unlike passwords, these don't need
 * slow salted hashing - they're high-entropy CSPRNG output, so there is
 * nothing to brute-force.
 *
 * THE SUPER ADMIN is config-managed (stations.yaml `superAdmin` block): its
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
        const val RECOVERY_CODE_COUNT = 6
        const val MIN_PASSWORD_LENGTH = 6

        /** Crockford-style alphabet: no I/L/O/U to avoid transcription errors. */
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ0123456789"
        val USERNAME_FORMAT = Regex("[a-zA-Z0-9._-]{3,64}")
    }

    private val secureRandom = SecureRandom()

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
                    CREATE TABLE IF NOT EXISTS recovery_codes (
                        code_hash VARCHAR(64) PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_codes_user FOREIGN KEY (user_id)
                            REFERENCES users(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
            }
            ensureIsAdminColumn(c)
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
     * Creates a new non-expiring token for the user. Only its SHA-256 is
     * stored; the returned RAW token exists nowhere but the client.
     */
    fun createToken(userId: Long): String {
        val token = randomBytes(32).toHex()
        db.connection().use { c ->
            c.prepareStatement("INSERT INTO auth_tokens(token_hash, user_id) VALUES(?,?)").use { ps ->
                ps.setString(1, tokenHash(token))
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
            val row = c.prepareStatement(
                """
                SELECT u.id, u.username, u.display_name, u.is_admin, u.password_hash, u.password_salt
                FROM auth_tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token_hash = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, tokenHash(token))
                ps.executeQuery().use { rs -> if (rs.next()) rs.toUserRow() else null }
            } ?: return null

            toAuthUser(c, row)
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
            require(!row.isAdmin) { "The super admin password is managed in stations.yaml, not via the API" }
            val matches = MessageDigest.isEqual(
                pbkdf2(currentPassword, row.saltHex.hexToBytes()),
                row.hashHex.hexToBytes()
            )
            require(matches) { "Current password is incorrect" }
            updatePassword(c, userId, newPassword)
        }
    }

    /** Admin-driven password reset (no current-password check). */
    fun resetPassword(userId: Long, newPassword: String) {
        validatePassword(newPassword)
        db.connection().use { c ->
            val row = selectUserById(c, userId) ?: throw IllegalArgumentException("Unknown user")
            require(!row.isAdmin) { "The super admin password is managed in stations.yaml, not via the API" }
            updatePassword(c, userId, newPassword)
        }
    }

    private fun updatePassword(c: Connection, userId: Long, newPassword: String) {
        val salt = randomBytes(16)
        c.prepareStatement("UPDATE users SET password_hash = ?, password_salt = ? WHERE id = ?").use { ps ->
            ps.setString(1, pbkdf2(newPassword, salt).toHex())
            ps.setString(2, salt.toHex())
            ps.setLong(3, userId)
            ps.executeUpdate()
        }
        revokeAllTokens(c, userId)
    }

    // ───────────────────────────────────────────────────── recovery codes ──

    /**
     * Replaces the user's recovery codes with [RECOVERY_CODE_COUNT] fresh
     * one-time codes and returns them RAW - this is the only moment they
     * exist in plaintext; only their SHA-256 is stored. Refused for the super
     * admin (the YAML is its recovery).
     */
    fun generateRecoveryCodes(userId: Long): List<String> {
        db.connection().use { c ->
            val row = selectUserById(c, userId) ?: throw IllegalArgumentException("Unknown user")
            require(!row.isAdmin) { "The super admin recovers via stations.yaml, not recovery codes" }

            val codes = List(RECOVERY_CODE_COUNT) { generateRecoveryCode() }
            c.autoCommit = false
            try {
                c.prepareStatement("DELETE FROM recovery_codes WHERE user_id = ?").use { ps ->
                    ps.setLong(1, userId); ps.executeUpdate()
                }
                c.prepareStatement("INSERT INTO recovery_codes(code_hash, user_id) VALUES(?,?)").use { ps ->
                    for (code in codes) {
                        ps.setString(1, tokenHash(normalizeRecoveryCode(code)))
                        ps.setLong(2, userId)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback(); throw e
            } finally {
                c.autoCommit = true
            }
            return codes
        }
    }

    /**
     * "Forgot password" flow: username + ONE unused recovery code sets a new
     * password. The code is consumed on success, and every session of the
     * user is revoked. Returns false on any mismatch (never reveals whether
     * the username or the code was wrong).
     */
    fun recoverPassword(username: String, recoveryCode: String, newPassword: String): Boolean {
        validatePassword(newPassword)
        db.connection().use { c ->
            val row = selectUserByUsername(c, username) ?: return false
            if (row.isAdmin) return false

            val codeHash = tokenHash(normalizeRecoveryCode(recoveryCode))
            val consumed = c.prepareStatement(
                "DELETE FROM recovery_codes WHERE code_hash = ? AND user_id = ?"
            ).use { ps ->
                ps.setString(1, codeHash)
                ps.setLong(2, row.id)
                ps.executeUpdate() == 1
            }
            if (!consumed) return false

            updatePassword(c, row.id, newPassword)
            return true
        }
    }

    /** 4 groups of 4 chars from a 32-char alphabet = 80 bits of entropy. */
    private fun generateRecoveryCode(): String =
        (1..16).map { CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)] }
            .joinToString("")
            .chunked(4)
            .joinToString("-")

    // ───────────────────────────────────────────────────── user management ──

    data class UserSummary(
        val id: Long,
        val username: String,
        val displayName: String,
        val isAdmin: Boolean,
        val grants: List<StationGrant>,
    )

    fun listUsers(): List<UserSummary> =
        db.connection().use { c ->
            val users = c.createStatement().use { s ->
                s.executeQuery("SELECT id, username, display_name, is_admin FROM users ORDER BY username").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                UserSummary(
                                    id = rs.getLong("id"),
                                    username = rs.getString("username"),
                                    displayName = rs.getString("display_name"),
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
    fun createUser(username: String, displayName: String, password: String, grants: List<StationGrant>): Long {
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
                    "INSERT INTO users(username, password_hash, password_salt, display_name) VALUES(?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS
                ).use { ps ->
                    ps.setString(1, username)
                    ps.setString(2, pbkdf2(password, salt).toHex())
                    ps.setString(3, salt.toHex())
                    ps.setString(4, displayName)
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
            require(!row.isAdmin) { "The super admin cannot be deleted - it is managed in stations.yaml" }
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
    )

    private fun java.sql.ResultSet.toUserRow() = UserRow(
        id = getLong("id"),
        username = getString("username"),
        displayName = getString("display_name"),
        isAdmin = getBoolean("is_admin"),
        hashHex = getString("password_hash"),
        saltHex = getString("password_salt"),
    )

    private fun selectUserByUsername(c: Connection, username: String): UserRow? =
        c.prepareStatement(
            "SELECT id, username, display_name, is_admin, password_hash, password_salt FROM users WHERE username = ?"
        ).use { ps ->
            ps.setString(1, username)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUserRow() else null }
        }

    private fun selectUserById(c: Connection, id: Long): UserRow? =
        c.prepareStatement(
            "SELECT id, username, display_name, is_admin, password_hash, password_salt FROM users WHERE id = ?"
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
        }
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
 * How bearer tokens and recovery codes are stored at rest: a single SHA-256
 * over the raw value. Fast hashing is correct here (unlike passwords): the
 * inputs are high-entropy CSPRNG output, so there is nothing to brute-force,
 * and an indexed hash lookup avoids any secret comparison in app code.
 */
internal fun tokenHash(token: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(token.encodeToByteArray())
        .joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

/** Case/dash-insensitive recovery-code matching ("ab1-2cd" == "AB12CD"). */
internal fun normalizeRecoveryCode(code: String): String =
    code.uppercase().filter { it in "ABCDEFGHJKMNPQRSTVWXYZ0123456789" }
