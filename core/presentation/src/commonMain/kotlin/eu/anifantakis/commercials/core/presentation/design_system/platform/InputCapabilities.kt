package eu.anifantakis.commercials.core.presentation.design_system.platform

import androidx.compose.runtime.Immutable

/**
 * What the input hardware can do. Independent of the OS look.
 *
 * v1 is a STARTUP SNAPSHOT, deliberately not observable - hot-plugging a
 * mouse mid-session does not re-resolve. That is acceptable precisely
 * because layout density is static for the session (see
 * [eu.anifantakis.commercials.core.presentation.design_system.resolveDensity]);
 * per-gesture behaviour still follows the live `PointerType` of each event.
 *
 * INTERNAL - components never read this. It exists only to derive
 * [eu.anifantakis.commercials.core.presentation.design_system.InteractionMetrics],
 * and it is not a CompositionLocal: putting raw capabilities in ambient
 * scope would let components route around the density policy.
 */
@Immutable
internal data class InputCapabilities(
    /** A finger-class (coarse) pointer is available. */
    val hasCoarsePointer: Boolean,
    /** A mouse/trackpad/stylus-class (fine) pointer is available. */
    val hasFinePointer: Boolean,
    /** At least one available pointer can hover. */
    val canHover: Boolean,
)

/** Resolved once at startup. Named honestly: a snapshot, not a live probe. */
internal expect fun startupInputCapabilities(): InputCapabilities
