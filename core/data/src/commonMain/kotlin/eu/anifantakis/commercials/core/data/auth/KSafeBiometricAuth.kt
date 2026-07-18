package eu.anifantakis.commercials.core.data.auth

import eu.anifantakis.commercials.core.domain.auth.BiometricAuth
import eu.anifantakis.lib.ksafe.biometrics.KSafeBiometrics

/**
 * [BiometricAuth] over KSafe's static prompt API: Android BiometricPrompt,
 * Apple LAContext, macOS-JVM LocalAuthentication (Touch ID), Windows Hello,
 * WebAuthn on the browser targets. Device-credential fallback stays ON -
 * a laptop without a fingerprint reader can still gate with its PIN.
 * Koin singleton.
 */
class KSafeBiometricAuth : BiometricAuth {

    override suspend fun available(): Boolean =
        KSafeBiometrics.biometricsAvailable(allowDeviceCredentialFallback = true)

    override suspend fun verify(reason: String): Boolean =
        KSafeBiometrics.verifyBiometric(reason = reason, allowDeviceCredentialFallback = true)
}
