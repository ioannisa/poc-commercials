package eu.anifantakis.poc.ctv.grids

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate

/**
 * Wrapper for LocalDate to ensure stability in Compose.
 * External classes like LocalDate cannot be annotated with @Stable,
 * so we wrap them in an @Immutable class.
 */
@Immutable
data class StableDate(val value: LocalDate) : Comparable<StableDate> {
    override fun compareTo(other: StableDate) = value.compareTo(other.value)
    override fun toString() = value.toString()
}

/**
 * Wrapper to force generic T to be considered Stable by Compose compiler.
 *
 * Problem: In generic composables like EnhancedDataGrid<T>, the type T is marked
 * as Unstable/Runtime because the compiler cannot guarantee T will always be immutable.
 *
 * Solution: Wrap T in StableItem<T> which is @Immutable, allowing the compiler to
 * skip recomposition when the wrapped value hasn't changed.
 *
 * Usage:
 * ```
 * // In itemsIndexed loop:
 * val stableItem = StableItem(item)
 * MyRow(itemWrapper = stableItem, ...)
 *
 * // In row composable:
 * fun MyRow(itemWrapper: StableItem<T>, ...) {
 *     val item = itemWrapper.value
 *     // use item...
 * }
 * ```
 */
@Immutable
data class StableItem<T>(val value: T)

fun LocalDate.toStable() = StableDate(this)
