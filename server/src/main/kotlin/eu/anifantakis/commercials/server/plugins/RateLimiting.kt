package eu.anifantakis.commercials.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.seconds

/**
 * Per-IP throttling for the endpoints an internet exposure turns into attack
 * surface. Two tiers, both keyed on the client IP (`origin.remoteHost`, which
 * honours XForwardedHeaders when `behindReverseProxy` enables it):
 *
 * - [CREDENTIALS_RATE_LIMIT] - password-guessing targets (login, the OAuth
 *   authorize form, forgot/reset). 10/min: a human retries a few times, a
 *   credential stuffer needs thousands. Combined with the 100k-iteration
 *   PBKDF2 cost per attempt this is the brute-force posture; the escalating
 *   lockout stays specific to reset codes (its state is per-user in
 *   password_resets and does not transfer cleanly).
 *
 * - [OAUTH_PROTOCOL_RATE_LIMIT] - the OAuth machinery (register, token,
 *   revoke). Not credential-guessing targets (codes/tokens are 256-bit
 *   CSPRNG values), but open endpoints worth bounding - DCR spam floods the
 *   client table, token hammering burns CPU. 60/min: loose enough that an
 *   office of connectors refreshing behind one NAT never 429s (Claude treats
 *   slow/failing token endpoints as connect failures), tight enough to blunt
 *   abuse.
 *
 * Ktor answers 429 with Retry-After automatically.
 */
val CREDENTIALS_RATE_LIMIT = RateLimitName("credentials")
val OAUTH_PROTOCOL_RATE_LIMIT = RateLimitName("oauth-protocol")

fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(CREDENTIALS_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(OAUTH_PROTOCOL_RATE_LIMIT) {
            rateLimiter(limit = 60, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
}
