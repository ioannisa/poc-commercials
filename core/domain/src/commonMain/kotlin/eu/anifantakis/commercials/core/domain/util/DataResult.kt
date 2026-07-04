package eu.anifantakis.commercials.core.domain.util

/**
 * The single result wrapper used across all layers (kmp-developer
 * convention). The failure case is `Failure` - never clashes with the
 * [Error] marker - and the type is `DataResult` - never clashes with
 * `kotlin.Result`. Expected failures are RETURNED, never thrown; consume
 * with an exhaustive `when`.
 */
sealed interface DataResult<out D, out E : Error> {
    data class Success<out D>(val data: D) : DataResult<D, Nothing>
    data class Failure<out E : Error>(val error: E) : DataResult<Nothing, E>
}

inline fun <T, E : Error, R> DataResult<T, E>.map(map: (T) -> R): DataResult<R, E> {
    return when (this) {
        is DataResult.Success -> DataResult.Success(map(data))
        is DataResult.Failure -> DataResult.Failure(error)
    }
}

inline fun <T, E : Error> DataResult<T, E>.onSuccess(action: (T) -> Unit): DataResult<T, E> {
    if (this is DataResult.Success) action(data)
    return this
}

inline fun <T, E : Error> DataResult<T, E>.onFailure(action: (E) -> Unit): DataResult<T, E> {
    if (this is DataResult.Failure) action(error)
    return this
}

typealias EmptyDataResult<E> = DataResult<Unit, E>

fun <T, E : Error> DataResult<T, E>.asEmptyDataResult(): EmptyDataResult<E> {
    return map { }
}
