package eu.anifantakis.commercials.server.auth

import eu.anifantakis.commercials.server.scheduler.CentralDb
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection

/**
 * OAuth 2.1 Authorization Server persistence: dynamically registered clients
 * (RFC 7591), single-use authorization codes (PKCE S256), and rotating
 * access/refresh token pairs.
 *
 * Lives beside [AuthDb] (same package - shares [tokenHash]) but in its own
 * class: OAuth adds three tables and a dozen operations, and AuthDb is big
 * enough. Only the bearer-RESOLUTION step (`findByOAuthToken`) stays inside
 * [AuthDb.findUserByToken], because that is where the token-to-AuthUser chain
 * and the `mcp_enabled` kill switch already live.
 *
 * Storage model mirrors the rest of the codebase: every secret is 256-bit
 * CSPRNG output handed to the caller ONCE and stored only as a SHA-256 hash;
 * every credential is a DB row, so revocation is row deletion - there is no
 * `revoked` flag, and `ON DELETE CASCADE` gives user-deletion cleanup for
 * free.
 *
 * Client churn: claude.ai registers a NEW client on every fresh connection
 * (documented behaviour), so [deleteExpired] prunes clients that never
 * completed an authorization - it runs at boot and on each registration.
 */
class OAuthDb(private val db: CentralDb) {

    private companion object {
        /** Authorization codes are one redirect round-trip long. */
        const val CODE_TTL_SECONDS = 120L

        /** Access tokens are short-lived; clients refresh proactively. */
        const val ACCESS_TTL_SECONDS = 3_600L

        /**
         * Refresh window, re-issued whole on every rotation - effectively a
         * 90-day IDLE timeout, matching the app's sliding-session philosophy.
         */
        const val REFRESH_TTL_DAYS = 90L

        /** A registered client with no live tokens is dropped after this. */
        const val CLIENT_PRUNE_DAYS = 30L

        /** redirect_uris column separator (a URI can never contain a newline). */
        const val REDIRECT_URI_SEPARATOR = "\n"

        /** Consent-OTP: the emailed 6-digit code's lifetime and attempt budget. */
        const val CONSENT_OTP_TTL_SECONDS = 600L
        const val CONSENT_OTP_DIGITS = 6
        const val CONSENT_OTP_MAX_ATTEMPTS = 5
    }

    /** Minutes a consent OTP lives - public so pages/emails can tell the user. */
    val consentOtpTtlMinutes: Int get() = (CONSENT_OTP_TTL_SECONDS / 60).toInt()

    private val secureRandom = SecureRandom()

    // ────────────────────────────────────────────────────────── bootstrap ──

    /** Creates the OAuth tables (idempotent) and sweeps expired rows. */
    fun bootstrap() {
        db.connection().use { c ->
            c.createStatement().use { s ->
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS oauth_clients (
                        client_id VARCHAR(64) PRIMARY KEY,
                        client_secret_hash VARCHAR(64) NULL,
                        client_name VARCHAR(255) NOT NULL,
                        redirect_uris TEXT NOT NULL,
                        token_endpoint_auth_method VARCHAR(32) NOT NULL DEFAULT 'none',
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_used_at TIMESTAMP NULL DEFAULT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS oauth_codes (
                        code_hash VARCHAR(64) PRIMARY KEY,
                        client_id VARCHAR(64) NOT NULL,
                        user_id BIGINT NOT NULL,
                        redirect_uri VARCHAR(512) NOT NULL,
                        code_challenge VARCHAR(128) NOT NULL,
                        code_challenge_method VARCHAR(8) NOT NULL DEFAULT 'S256',
                        resource VARCHAR(512) NULL,
                        scope VARCHAR(255) NULL,
                        expires_at TIMESTAMP NOT NULL,
                        CONSTRAINT fk_oauth_codes_client FOREIGN KEY (client_id)
                            REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
                        CONSTRAINT fk_oauth_codes_user FOREIGN KEY (user_id)
                            REFERENCES users(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS oauth_tokens (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        access_token_hash VARCHAR(64) NOT NULL UNIQUE,
                        refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
                        user_id BIGINT NOT NULL,
                        client_id VARCHAR(64) NOT NULL,
                        resource VARCHAR(512) NULL,
                        scope VARCHAR(255) NULL,
                        access_expires_at TIMESTAMP NOT NULL,
                        refresh_expires_at TIMESTAMP NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_used_at TIMESTAMP NULL DEFAULT NULL,
                        CONSTRAINT fk_oauth_tokens_client FOREIGN KEY (client_id)
                            REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
                        CONSTRAINT fk_oauth_tokens_user FOREIGN KEY (user_id)
                            REFERENCES users(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS oauth_consent_otp (
                        consent_hash VARCHAR(64) PRIMARY KEY,
                        client_id VARCHAR(64) NOT NULL,
                        user_id BIGINT NOT NULL,
                        redirect_uri VARCHAR(512) NOT NULL,
                        code_challenge VARCHAR(128) NOT NULL,
                        code_challenge_method VARCHAR(8) NOT NULL DEFAULT 'S256',
                        resource VARCHAR(512) NULL,
                        scope VARCHAR(255) NULL,
                        state VARCHAR(512) NULL,
                        connected_account VARCHAR(255) NOT NULL,
                        consent_ip VARCHAR(64) NULL,
                        consent_user_agent VARCHAR(255) NULL,
                        otp_hash VARCHAR(64) NOT NULL,
                        wrong_count INT NOT NULL DEFAULT 0,
                        expires_at TIMESTAMP NOT NULL,
                        CONSTRAINT fk_oauth_consent_client FOREIGN KEY (client_id)
                            REFERENCES oauth_clients(client_id) ON DELETE CASCADE,
                        CONSTRAINT fk_oauth_consent_user FOREIGN KEY (user_id)
                            REFERENCES users(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
            }
            ensureConsentColumns(c)
            deleteExpired(c)
        }
    }

    /**
     * Migration: consent-time audit metadata. The self-declared account
     * identity ("who is connecting" - e.g. the Claude account's e-mail) plus
     * the IP and browser that submitted the consent form. Carried code -> token
     * pair -> every rotation, so the grant keeps them for its whole life.
     */
    private fun ensureConsentColumns(c: Connection) {
        for (table in listOf("oauth_codes", "oauth_tokens")) {
            ensureColumn(c, table, "connected_account", "VARCHAR(255) NULL DEFAULT NULL")
            ensureColumn(c, table, "consent_ip", "VARCHAR(64) NULL DEFAULT NULL")
            ensureColumn(c, table, "consent_user_agent", "VARCHAR(255) NULL DEFAULT NULL")
        }
        // Approval gates: DEFAULT TRUE grandfathers pre-migration grants as
        // active. approve_token_hash backs the single-use email-approval link.
        ensureColumn(c, "oauth_tokens", "user_approved", "BOOLEAN NOT NULL DEFAULT TRUE")
        ensureColumn(c, "oauth_tokens", "admin_approved", "BOOLEAN NOT NULL DEFAULT TRUE")
        ensureColumn(c, "oauth_tokens", "approve_token_hash", "VARCHAR(64) NULL DEFAULT NULL")
    }

    /** Adds [column] if missing. [table]/[column]/[ddl] are internal constants, never user input. */
    private fun ensureColumn(c: Connection, table: String, column: String, ddl: String) {
        val exists = c.prepareStatement(
            """
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table); ps.setString(2, column)
            ps.executeQuery().use { it.next() }
        }
        if (!exists) {
            c.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $ddl") }
        }
    }

    // ──────────────────────────────────────────────── client registration ──

    /** A freshly registered client. [clientSecret] is raw - shown once, hashed at rest. */
    data class RegisteredClient(val clientId: String, val clientSecret: String)

    /** A stored client, as the authorize/token endpoints need it. */
    data class OAuthClient(
        val clientId: String,
        val secretHash: String?,
        val clientName: String,
        val redirectUris: List<String>,
        val tokenEndpointAuthMethod: String,
    )

    /**
     * RFC 7591 registration. A `client_secret` is ALWAYS minted and returned,
     * even for `token_endpoint_auth_method: "none"`: M365 Copilot's DCR
     * hard-requires one and Perplexity has a documented failure without it,
     * while well-behaved public clients simply ignore the extra field. The
     * token endpoint enforces it only for the secret-bearing methods.
     *
     * Redirect URIs are validated by the CALLER (route layer) - this stores.
     */
    fun registerClient(
        clientName: String,
        redirectUris: List<String>,
        tokenEndpointAuthMethod: String,
    ): RegisteredClient {
        require(redirectUris.isNotEmpty()) { "redirect_uris must not be empty" }
        val clientId = randomBytes(16).toHex()
        val clientSecret = randomBytes(32).toHex()
        db.connection().use { c ->
            deleteExpired(c)   // claude.ai churns one client per connection - keep the table bounded
            c.prepareStatement(
                "INSERT INTO oauth_clients(client_id, client_secret_hash, client_name, redirect_uris, " +
                    "token_endpoint_auth_method) VALUES(?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, tokenHash(clientSecret))
                ps.setString(3, clientName)
                ps.setString(4, redirectUris.joinToString(REDIRECT_URI_SEPARATOR))
                ps.setString(5, tokenEndpointAuthMethod)
                ps.executeUpdate()
            }
        }
        return RegisteredClient(clientId, clientSecret)
    }

    /**
     * Constant-time check of a presented `client_secret` against [client]'s
     * stored hash. Lives here (not the route layer) because [tokenHash] is
     * persistence-internal - hashing stays in one module.
     */
    fun clientSecretMatches(client: OAuthClient, presentedSecret: String): Boolean {
        val stored = client.secretHash ?: return false
        return MessageDigest.isEqual(
            tokenHash(presentedSecret).encodeToByteArray(),
            stored.encodeToByteArray(),
        )
    }

    fun findClient(clientId: String): OAuthClient? =
        db.connection().use { c ->
            c.prepareStatement(
                "SELECT client_id, client_secret_hash, client_name, redirect_uris, token_endpoint_auth_method " +
                    "FROM oauth_clients WHERE client_id = ?"
            ).use { ps ->
                ps.setString(1, clientId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    OAuthClient(
                        clientId = rs.getString("client_id"),
                        secretHash = rs.getString("client_secret_hash"),
                        clientName = rs.getString("client_name"),
                        redirectUris = rs.getString("redirect_uris").split(REDIRECT_URI_SEPARATOR),
                        tokenEndpointAuthMethod = rs.getString("token_endpoint_auth_method"),
                    )
                }
            }
        }

    // ─────────────────────────────────────────────── authorization codes ──

    /** Everything the token endpoint must re-verify about a redeemed code. */
    data class RedeemedCode(
        val userId: Long,
        val clientId: String,
        val redirectUri: String,
        val codeChallenge: String,
        val codeChallengeMethod: String,
        val resource: String?,
        val scope: String?,
        val connectedAccount: String?,
        val consentIp: String?,
        val consentUserAgent: String?,
    )

    /** Mints a single-use authorization code (raw, returned once, hashed at rest). */
    fun createCode(
        clientId: String,
        userId: Long,
        redirectUri: String,
        codeChallenge: String,
        codeChallengeMethod: String,
        resource: String?,
        scope: String?,
        connectedAccount: String?,
        consentIp: String?,
        consentUserAgent: String?,
    ): String {
        val code = randomBytes(32).toHex()
        db.connection().use { c ->
            c.prepareStatement(
                "INSERT INTO oauth_codes(code_hash, client_id, user_id, redirect_uri, code_challenge, " +
                    "code_challenge_method, resource, scope, connected_account, consent_ip, consent_user_agent, " +
                    "expires_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, DATE_ADD(NOW(), INTERVAL ? SECOND))"
            ).use { ps ->
                ps.setString(1, tokenHash(code))
                ps.setString(2, clientId)
                ps.setLong(3, userId)
                ps.setString(4, redirectUri)
                ps.setString(5, codeChallenge)
                ps.setString(6, codeChallengeMethod)
                ps.setString(7, resource)
                ps.setString(8, scope)
                ps.setString(9, connectedAccount)
                ps.setString(10, consentIp)
                ps.setString(11, consentUserAgent)
                ps.setLong(12, CODE_TTL_SECONDS)
                ps.executeUpdate()
            }
        }
        return code
    }

    /**
     * Redeems [code] - SINGLE-USE and race-safe: the row is SELECTed, then
     * DELETEd by primary key, and the caller proceeds only when the DELETE
     * removed exactly one row. Two concurrent redemptions of the same code
     * both SELECT it, but only one DELETE wins; the loser gets null
     * (`invalid_grant`), exactly like an expired or unknown code.
     */
    fun redeemCode(code: String): RedeemedCode? =
        db.connection().use { c ->
            val hash = tokenHash(code)
            val row = c.prepareStatement(
                "SELECT user_id, client_id, redirect_uri, code_challenge, code_challenge_method, resource, scope, " +
                    "connected_account, consent_ip, consent_user_agent " +
                    "FROM oauth_codes WHERE code_hash = ? AND expires_at > NOW()"
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    RedeemedCode(
                        userId = rs.getLong("user_id"),
                        clientId = rs.getString("client_id"),
                        redirectUri = rs.getString("redirect_uri"),
                        codeChallenge = rs.getString("code_challenge"),
                        codeChallengeMethod = rs.getString("code_challenge_method"),
                        resource = rs.getString("resource"),
                        scope = rs.getString("scope"),
                        connectedAccount = rs.getString("connected_account"),
                        consentIp = rs.getString("consent_ip"),
                        consentUserAgent = rs.getString("consent_user_agent"),
                    )
                }
            } ?: return@use null
            val deleted = c.prepareStatement("DELETE FROM oauth_codes WHERE code_hash = ?").use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
            if (deleted == 1) row else null
        }

    // ───────────────────────────────────────────────────── consent OTP ──

    /** A minted consent-verification step: the page carries [consentId], the e-mail carries [otp]. */
    data class ConsentOtp(val consentId: String, val otp: String)

    /** Everything an OTP-verified consent needs to mint the authorization code. */
    data class VerifiedConsent(
        val clientId: String,
        val userId: Long,
        val redirectUri: String,
        val codeChallenge: String,
        val codeChallengeMethod: String,
        val resource: String?,
        val scope: String?,
        val state: String?,
        val connectedAccount: String,
        val consentIp: String?,
        val consentUserAgent: String?,
    )

    sealed interface ConsentOtpResult {
        data class Verified(val consent: VerifiedConsent) : ConsentOtpResult
        /** Wrong code; carries what the retry page needs to re-render. */
        data class WrongCode(
            val attemptsLeft: Int,
            val clientName: String,
            val connectedAccount: String,
        ) : ConsentOtpResult
        /** Expired, unknown, or attempt budget exhausted - restart the flow. */
        data object Gone : ConsentOtpResult
    }

    /**
     * Parks a credential-verified consent behind an emailed OTP: stores every
     * authorize param plus the hashed 6-digit code, and returns the RAW
     * consent id (page hidden field) + RAW code (for the caller to e-mail to
     * the DECLARED address). The authorization code is minted only by
     * [redeemConsentOtp].
     */
    fun createConsentOtp(
        clientId: String,
        userId: Long,
        redirectUri: String,
        codeChallenge: String,
        codeChallengeMethod: String,
        resource: String?,
        scope: String?,
        state: String?,
        connectedAccount: String,
        consentIp: String?,
        consentUserAgent: String?,
    ): ConsentOtp {
        val consentId = randomBytes(32).toHex()
        val otp = (1..CONSENT_OTP_DIGITS).joinToString("") { secureRandom.nextInt(10).toString() }
        db.connection().use { c ->
            c.prepareStatement(
                "INSERT INTO oauth_consent_otp(consent_hash, client_id, user_id, redirect_uri, code_challenge, " +
                    "code_challenge_method, resource, scope, state, connected_account, consent_ip, " +
                    "consent_user_agent, otp_hash, wrong_count, expires_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, DATE_ADD(NOW(), INTERVAL ? SECOND))"
            ).use { ps ->
                ps.setString(1, tokenHash(consentId))
                ps.setString(2, clientId)
                ps.setLong(3, userId)
                ps.setString(4, redirectUri)
                ps.setString(5, codeChallenge)
                ps.setString(6, codeChallengeMethod)
                ps.setString(7, resource)
                ps.setString(8, scope)
                ps.setString(9, state)
                ps.setString(10, connectedAccount)
                ps.setString(11, consentIp)
                ps.setString(12, consentUserAgent)
                ps.setString(13, tokenHash(otp))
                ps.setLong(14, CONSENT_OTP_TTL_SECONDS)
                ps.executeUpdate()
            }
        }
        return ConsentOtp(consentId, otp)
    }

    /**
     * Verifies the emailed code. Success DELETEs the row (single-use, race-safe
     * like [redeemCode]); a wrong code burns one of [CONSENT_OTP_MAX_ATTEMPTS]
     * tries and the last one deletes the row, so a stolen consent id cannot be
     * brute-forced past the budget.
     */
    fun redeemConsentOtp(consentId: String, otp: String): ConsentOtpResult =
        db.connection().use { c ->
            val hash = tokenHash(consentId)
            data class Row(
                val otpHash: String,
                val wrongCount: Int,
                val clientName: String,
                val consent: VerifiedConsent,
            )
            val row = c.prepareStatement(
                "SELECT o.otp_hash, o.wrong_count, cl.client_name, o.client_id, o.user_id, o.redirect_uri, " +
                    "o.code_challenge, o.code_challenge_method, o.resource, o.scope, o.state, " +
                    "o.connected_account, o.consent_ip, o.consent_user_agent " +
                    "FROM oauth_consent_otp o JOIN oauth_clients cl ON cl.client_id = o.client_id " +
                    "WHERE o.consent_hash = ? AND o.expires_at > NOW()"
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    Row(
                        otpHash = rs.getString("otp_hash"),
                        wrongCount = rs.getInt("wrong_count"),
                        clientName = rs.getString("client_name"),
                        consent = VerifiedConsent(
                            clientId = rs.getString("client_id"),
                            userId = rs.getLong("user_id"),
                            redirectUri = rs.getString("redirect_uri"),
                            codeChallenge = rs.getString("code_challenge"),
                            codeChallengeMethod = rs.getString("code_challenge_method"),
                            resource = rs.getString("resource"),
                            scope = rs.getString("scope"),
                            state = rs.getString("state"),
                            connectedAccount = rs.getString("connected_account"),
                            consentIp = rs.getString("consent_ip"),
                            consentUserAgent = rs.getString("consent_user_agent"),
                        ),
                    )
                }
            } ?: return@use ConsentOtpResult.Gone

            if (row.otpHash != tokenHash(otp)) {
                val burned = row.wrongCount + 1
                if (burned >= CONSENT_OTP_MAX_ATTEMPTS) {
                    c.prepareStatement("DELETE FROM oauth_consent_otp WHERE consent_hash = ?").use { ps ->
                        ps.setString(1, hash); ps.executeUpdate()
                    }
                    return@use ConsentOtpResult.Gone
                }
                c.prepareStatement("UPDATE oauth_consent_otp SET wrong_count = ? WHERE consent_hash = ?").use { ps ->
                    ps.setInt(1, burned); ps.setString(2, hash); ps.executeUpdate()
                }
                return@use ConsentOtpResult.WrongCode(
                    attemptsLeft = CONSENT_OTP_MAX_ATTEMPTS - burned,
                    clientName = row.clientName,
                    connectedAccount = row.consent.connectedAccount,
                )
            }

            val deleted = c.prepareStatement("DELETE FROM oauth_consent_otp WHERE consent_hash = ?").use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
            if (deleted == 1) ConsentOtpResult.Verified(row.consent) else ConsentOtpResult.Gone
        }

    // ──────────────────────────────────────────────────────────── tokens ──

    /** A freshly issued pair. Raw values, returned once, hashed at rest. */
    data class IssuedTokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Long,
        val scope: String?,
        /** RAW single-use e-mail-approval token, set only when user approval is pending. */
        val approveToken: String? = null,
    )

    /**
     * Issues a fresh access+refresh pair for [userId] under [clientId]. When an
     * approval is required the pair is minted UNUSABLE (the bearer chain skips
     * unapproved grants) - the OAuth protocol itself still completes, so the
     * connector connects and its tools start working the moment the grant is
     * approved. [IssuedTokens.approveToken] backs the e-mail approval link.
     */
    fun issueTokens(
        userId: Long,
        clientId: String,
        resource: String?,
        scope: String?,
        connectedAccount: String? = null,
        consentIp: String? = null,
        consentUserAgent: String? = null,
        requireUserApproval: Boolean = false,
        requireAdminApproval: Boolean = false,
    ): IssuedTokens =
        db.connection().use { c ->
            val approveToken = if (requireUserApproval) randomBytes(32).toHex() else null
            val issued = insertTokenPair(
                c, userId, clientId, resource, scope, connectedAccount, consentIp, consentUserAgent,
                createdAt = null,
                userApproved = !requireUserApproval,
                adminApproved = !requireAdminApproval,
                approveTokenHash = approveToken?.let(::tokenHash),
            )
            // The client completed an authorization - it is live, exempt it from pruning.
            c.prepareStatement("UPDATE oauth_clients SET last_used_at = NOW() WHERE client_id = ?").use { ps ->
                ps.setString(1, clientId)
                ps.executeUpdate()
            }
            issued.copy(approveToken = approveToken)
        }

    /**
     * Refresh-token ROTATION (OAuth 2.1 mandates it for public clients): the
     * presented token must match [clientId] and be unexpired; its row is
     * DELETEd and a fresh pair INSERTed. Race-safe like [redeemCode]: only the
     * caller whose DELETE removed the row gets the new pair - a replayed
     * (already-rotated) refresh token finds nothing and gets null, which the
     * route answers with `invalid_grant` (the exact code Claude keys re-auth
     * off).
     */
    fun rotateRefreshToken(refreshToken: String, clientId: String): IssuedTokens? =
        db.connection().use { c ->
            val hash = tokenHash(refreshToken)
            data class Old(
                val userId: Long,
                val resource: String?,
                val scope: String?,
                val connectedAccount: String?,
                val consentIp: String?,
                val consentUserAgent: String?,
                val createdAt: String?,
                val userApproved: Boolean,
                val adminApproved: Boolean,
                val approveTokenHash: String?,
            )
            val old = c.prepareStatement(
                "SELECT user_id, resource, scope, connected_account, consent_ip, consent_user_agent, created_at, " +
                    "user_approved, admin_approved, approve_token_hash " +
                    "FROM oauth_tokens " +
                    "WHERE refresh_token_hash = ? AND client_id = ? AND refresh_expires_at > NOW()"
            ).use { ps ->
                ps.setString(1, hash)
                ps.setString(2, clientId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) Old(
                        rs.getLong("user_id"), rs.getString("resource"), rs.getString("scope"),
                        rs.getString("connected_account"), rs.getString("consent_ip"),
                        rs.getString("consent_user_agent"), rs.getString("created_at"),
                        rs.getBoolean("user_approved"), rs.getBoolean("admin_approved"),
                        rs.getString("approve_token_hash"),
                    )
                    else null
                }
            } ?: return@use null
            val deleted = c.prepareStatement("DELETE FROM oauth_tokens WHERE refresh_token_hash = ?").use { ps ->
                ps.setString(1, hash)
                ps.executeUpdate()
            }
            if (deleted != 1) return@use null
            // Carry the consent metadata, approval state AND the original
            // created_at through the rotation - the grant is the same
            // connection, only the pair is new. (A pending grant DOES rotate:
            // the protocol refresh is client-authenticated and never passes
            // through the approval gate, and the e-mail link must stay valid.)
            insertTokenPair(
                c, old.userId, clientId, old.resource, old.scope,
                old.connectedAccount, old.consentIp, old.consentUserAgent, old.createdAt,
                old.userApproved, old.adminApproved, old.approveTokenHash,
            )
        }

    /**
     * RFC 7009 revocation: drops the pair matching either hash, scoped to the
     * presenting client. Deliberately reports nothing - the RFC says the
     * endpoint answers 200 whether or not the token existed.
     */
    fun revokeToken(token: String, clientId: String) {
        db.connection().use { c ->
            val hash = tokenHash(token)
            c.prepareStatement(
                "DELETE FROM oauth_tokens WHERE client_id = ? AND (access_token_hash = ? OR refresh_token_hash = ?)"
            ).use { ps ->
                ps.setString(1, clientId)
                ps.setString(2, hash)
                ps.setString(3, hash)
                ps.executeUpdate()
            }
        }
    }

    private fun insertTokenPair(
        c: Connection,
        userId: Long,
        clientId: String,
        resource: String?,
        scope: String?,
        connectedAccount: String?,
        consentIp: String?,
        consentUserAgent: String?,
        createdAt: String? = null,
        userApproved: Boolean = true,
        adminApproved: Boolean = true,
        approveTokenHash: String? = null,
    ): IssuedTokens {
        val accessToken = randomBytes(32).toHex()
        val refreshToken = randomBytes(32).toHex()
        c.prepareStatement(
            "INSERT INTO oauth_tokens(access_token_hash, refresh_token_hash, user_id, client_id, resource, scope, " +
                "connected_account, consent_ip, consent_user_agent, created_at, " +
                "user_approved, admin_approved, approve_token_hash, " +
                "access_expires_at, refresh_expires_at) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, NOW()), ?, ?, ?, " +
                "DATE_ADD(NOW(), INTERVAL ? SECOND), DATE_ADD(NOW(), INTERVAL ? DAY))"
        ).use { ps ->
            ps.setString(1, tokenHash(accessToken))
            ps.setString(2, tokenHash(refreshToken))
            ps.setLong(3, userId)
            ps.setString(4, clientId)
            ps.setString(5, resource)
            ps.setString(6, scope)
            ps.setString(7, connectedAccount)
            ps.setString(8, consentIp)
            ps.setString(9, consentUserAgent)
            ps.setString(10, createdAt)
            ps.setBoolean(11, userApproved)
            ps.setBoolean(12, adminApproved)
            ps.setString(13, approveTokenHash)
            ps.setLong(14, ACCESS_TTL_SECONDS)
            ps.setLong(15, REFRESH_TTL_DAYS)
            ps.executeUpdate()
        }
        return IssuedTokens(accessToken, refreshToken, ACCESS_TTL_SECONDS, scope)
    }

    // ─────────────────────────────────────────────────────── housekeeping ──

    /**
     * Sweeps: expired codes, pairs whose refresh window lapsed, and clients
     * that are [CLIENT_PRUNE_DAYS] old with no tokens (the claude.ai
     * one-client-per-connection churn). A client with a live refresh token is
     * never pruned.
     */
    fun deleteExpired() {
        db.connection().use { deleteExpired(it) }
    }

    private fun deleteExpired(c: Connection) {
        c.createStatement().use { s ->
            s.executeUpdate("DELETE FROM oauth_codes WHERE expires_at <= NOW()")
            s.executeUpdate("DELETE FROM oauth_consent_otp WHERE expires_at <= NOW()")
            s.executeUpdate("DELETE FROM oauth_tokens WHERE refresh_expires_at <= NOW()")
            s.executeUpdate(
                "DELETE FROM oauth_clients WHERE created_at < DATE_SUB(NOW(), INTERVAL $CLIENT_PRUNE_DAYS DAY) " +
                    "AND client_id NOT IN (SELECT DISTINCT client_id FROM oauth_tokens)"
            )
        }
    }

    // ──────────────────────────────────────────────────── admin oversight ──

    /** One OAuth grant, as the admin oversight endpoints list it. */
    data class OAuthTokenInfo(
        val id: Long,
        val userId: Long,
        val username: String,
        val clientName: String,
        val createdAt: String,
        val lastUsedAt: String?,
        val refreshExpiresAt: String,
        /** Self-declared at consent ("who is connecting" - e.g. the Claude account's e-mail). */
        val connectedAccount: String?,
        /** IP + browser that submitted the consent form (audit; NULL on pre-migration grants). */
        val consentIp: String?,
        val consentUserAgent: String?,
        /** Approval gates - the grant works only when BOTH are true. */
        val userApproved: Boolean,
        val adminApproved: Boolean,
    )

    /** Every live OAuth grant across all users (admin oversight). */
    fun listAllTokens(): List<OAuthTokenInfo> = queryTokens(userId = null)

    /** The caller's OWN OAuth grants (self-service "My AI connections"). */
    fun listTokensForUser(userId: Long): List<OAuthTokenInfo> = queryTokens(userId)

    private fun queryTokens(userId: Long?): List<OAuthTokenInfo> =
        db.connection().use { c ->
            // The expiry sweep only runs at boot, so the read path must exclude
            // lapsed grants itself or they'd show as "connected" until a restart.
            val userFilter = if (userId != null) "AND t.user_id = ? " else ""
            c.prepareStatement(
                "SELECT t.id, t.user_id, u.username, cl.client_name, t.created_at, t.last_used_at, " +
                    "t.refresh_expires_at, t.connected_account, t.consent_ip, t.consent_user_agent, " +
                    "t.user_approved, t.admin_approved " +
                    "FROM oauth_tokens t " +
                    "JOIN users u ON u.id = t.user_id " +
                    "JOIN oauth_clients cl ON cl.client_id = t.client_id " +
                    "WHERE t.refresh_expires_at > NOW() " +
                    userFilter +
                    "ORDER BY t.created_at DESC"
            ).use { ps ->
                if (userId != null) ps.setLong(1, userId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                OAuthTokenInfo(
                                    id = rs.getLong("id"),
                                    userId = rs.getLong("user_id"),
                                    username = rs.getString("username"),
                                    clientName = rs.getString("client_name"),
                                    createdAt = rs.getString("created_at"),
                                    lastUsedAt = rs.getString("last_used_at"),
                                    refreshExpiresAt = rs.getString("refresh_expires_at"),
                                    connectedAccount = rs.getString("connected_account"),
                                    consentIp = rs.getString("consent_ip"),
                                    consentUserAgent = rs.getString("consent_user_agent"),
                                    userApproved = rs.getBoolean("user_approved"),
                                    adminApproved = rs.getBoolean("admin_approved"),
                                )
                            )
                        }
                    }
                }
            }
        }

    /**
     * Redeems the single-use e-mail approval link: clears the USER gate and
     * burns the token. True when a pending grant matched.
     */
    fun approveByEmailToken(token: String): Boolean =
        db.connection().use { c ->
            c.prepareStatement(
                "UPDATE oauth_tokens SET user_approved = TRUE, approve_token_hash = NULL " +
                    "WHERE approve_token_hash = ?"
            ).use { ps ->
                ps.setString(1, tokenHash(token))
                ps.executeUpdate() == 1
            }
        }

    /**
     * Super-admin approval: clears BOTH gates (the admin outranks the e-mail
     * link - the point is unblocking a grant, e.g. when the mail never
     * arrived). True if the grant existed.
     */
    fun adminApproveById(id: Long): Boolean =
        db.connection().use { c ->
            c.prepareStatement(
                "UPDATE oauth_tokens SET user_approved = TRUE, admin_approved = TRUE, approve_token_hash = NULL " +
                    "WHERE id = ?"
            ).use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate() == 1
            }
        }

    /** Admin revoke of ANY grant by id. True if it existed. */
    fun revokeTokenById(id: Long): Boolean =
        db.connection().use { c ->
            c.prepareStatement("DELETE FROM oauth_tokens WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate() == 1
            }
        }

    /** Self-service revoke of one of the caller's OWN grants (ownership enforced). True if it existed. */
    fun revokeTokenByIdForUser(id: Long, userId: Long): Boolean =
        db.connection().use { c ->
            c.prepareStatement("DELETE FROM oauth_tokens WHERE id = ? AND user_id = ?").use { ps ->
                ps.setLong(1, id)
                ps.setLong(2, userId)
                ps.executeUpdate() == 1
            }
        }

    /** Count of live OAuth grants (admin status readout). */
    fun oauthTokenCount(): Int =
        db.connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM oauth_tokens WHERE refresh_expires_at > NOW()")
                    .use { rs -> rs.next(); rs.getInt(1) }
            }
        }

    // ─────────────────────────────────────────────────────────── helpers ──

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
