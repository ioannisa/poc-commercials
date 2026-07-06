package eu.anifantakis.commercials.core.presentation.grids

import androidx.compose.runtime.Immutable
import kotlinx.datetime.DayOfWeek

/**
 * The scheduler grid's display labels. The grids module is a standalone leaf
 * toolkit (no dependency on the app's localization), so callers inject
 * localized labels; the defaults keep the legacy Greek look for standalone use
 * (same pattern as GridIcons: the leaf gets its own door, the app passes
 * through it).
 */
@Immutable
data class SchedulerLabels(
    val timeDay: String = "Ώρα/Μέρα",
    val totals: String = "Σύνολα",
    val dayAbbreviations: Map<DayOfWeek, String> = mapOf(
        DayOfWeek.MONDAY to "ΔΕ",
        DayOfWeek.TUESDAY to "ΤΡ",
        DayOfWeek.WEDNESDAY to "ΤΕ",
        DayOfWeek.THURSDAY to "ΠΕ",
        DayOfWeek.FRIDAY to "ΠΑ",
        DayOfWeek.SATURDAY to "ΣΑ",
        DayOfWeek.SUNDAY to "ΚΥ",
    ),
)
