package eu.anifantakis.commercials.feature.auth.domain.model

/**
 * The result of submitting an email password-reset code. Mirrors the server:
 * a wrong code (which arms an escalating lock once the free tries are spent),
 * a lockout already in effect, an expired code, or success. A wrong username /
 * absent request also reads as [Invalid] - the server never reveals which.
 */
sealed interface ResetOutcome {
    data object Success : ResetOutcome

    /** Wrong code. [retryAfterSeconds] is set once the escalating lock is armed. */
    data class Invalid(val retryAfterSeconds: Long?) : ResetOutcome

    /** A try arrived while still locked out; wait [retryAfterSeconds]. */
    data class Locked(val retryAfterSeconds: Long) : ResetOutcome

    data object Expired : ResetOutcome
}
