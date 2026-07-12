package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.platform.InputCapabilities

/**
 * The interaction POLICY components actually consume - derived from the
 * startup [InputCapabilities] plus the user's (resolved) density preference.
 * This third layer is what keeps hit-sizing out of both the visual tokens
 * (which describe the OS look) and the raw hardware facts.
 *
 * Read it via `AppTheme.interaction`. Never branch on platform or raw
 * capabilities for interaction decisions; per-gesture decisions use the
 * live `PointerType` of the pointer event itself.
 */
@Immutable
data class InteractionMetrics(
    /**
     * Floor for the interactive layout box of small controls (fed into
     * [androidx.compose.material3.LocalMinimumInteractiveComponentSize] and
     * applied explicitly by AppButton). Expands the box around the visual -
     * it never changes the visual size itself.
     */
    val minimumTargetSize: Dp,
    /** Hover affordances (tooltips on hover, hover highlights) are useful. */
    val supportsHover: Boolean,
    /** Touch gestures (long-press menus, pull-to-refresh) are available. */
    val supportsTouchGestures: Boolean,
    /** Pull-to-refresh gesture; pointer sessions get a visible Refresh affordance instead. */
    val pullToRefresh: Boolean,
)

val LocalInteractionMetrics = compositionLocalOf<InteractionMetrics> {
    error("No InteractionMetrics provided - wrap the content in CommercialsTheme.")
}

/**
 * The USER's density choice - ordinary UI configuration, persisted in KSafe
 * Plain mode beside the font-size step. AUTO is a preference, not a layout
 * state: it must be resolved (once, statically) before anything consumes it.
 */
enum class DensityPreference {
    AUTO,
    COMPACT,
    TOUCH_FRIENDLY;

    companion object {
        val DEFAULT = AUTO
        fun fromOrdinal(value: Int): DensityPreference = entries.getOrElse(value) { DEFAULT }
    }
}

/**
 * The RESOLVED density - deliberately a separate type with no AUTO entry, so
 * AUTO-resolution logic structurally cannot leak into a component.
 */
internal enum class EffectiveDensity { COMPACT, TOUCH_FRIENDLY }

/**
 * Resolves AUTO once, statically, for the whole session. It must NOT track
 * the live pointer: re-resolving on every mouse/finger alternation would
 * reflow the entire UI mid-task (controls jumping, scroll position moving).
 * Live input responsiveness belongs at the gesture layer via `PointerType`.
 */
internal fun resolveDensity(
    preference: DensityPreference,
    input: InputCapabilities,
): EffectiveDensity = when (preference) {
    DensityPreference.COMPACT -> EffectiveDensity.COMPACT
    DensityPreference.TOUCH_FRIENDLY -> EffectiveDensity.TOUCH_FRIENDLY
    // A touchscreen laptop is a laptop: any fine pointer wins on hybrids.
    DensityPreference.AUTO ->
        if (input.hasFinePointer) EffectiveDensity.COMPACT
        else EffectiveDensity.TOUCH_FRIENDLY
}

/**
 * 48dp is the M3 accessibility recommendation and also satisfies Apple's
 * 44pt; 32dp matches desktop-class control conventions.
 */
internal fun deriveInteractionMetrics(
    input: InputCapabilities,
    density: EffectiveDensity,
): InteractionMetrics = InteractionMetrics(
    minimumTargetSize = when (density) {
        EffectiveDensity.TOUCH_FRIENDLY -> 48.dp
        EffectiveDensity.COMPACT -> 32.dp
    },
    supportsHover = input.canHover,
    supportsTouchGestures = input.hasCoarsePointer,
    pullToRefresh = input.hasCoarsePointer,
)
