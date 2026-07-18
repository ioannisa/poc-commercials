package eu.anifantakis.commercials.core.domain.auth

/**
 * The biometric gate as the presentation layer consumes it - a DIP seam over
 * the platform prompt (KSafe biometrics in :core:data), so ViewModels stay
 * fake-testable and no feature module touches the static platform API.
 *
 * [available] is probed once and kept in state: `false` means a real prompt
 * cannot be shown (no sensor / not enrolled) and the biometric option must
 * not be offered. NOTE: JVM Linux has no prompt API and reports available +
 * verified unconditionally - the option is honest everywhere else.
 */
interface BiometricAuth {
    suspend fun available(): Boolean

    /** Shows the platform prompt; true = the user passed it. */
    suspend fun verify(reason: String): Boolean
}
