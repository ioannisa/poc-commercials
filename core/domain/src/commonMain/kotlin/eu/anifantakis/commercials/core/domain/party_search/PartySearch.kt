package eu.anifantakis.commercials.core.domain.party_search

import eu.anifantakis.commercials.core.domain.util.DataError
import eu.anifantakis.commercials.core.domain.util.DataResult

/**
 * Which side of a (possibly "triangular") deal a search targets - see
 * migration/legacy-schema.md for the CUS/TRA model.
 */
enum class PartyKind(val wire: String) {
    /** The spot's owner - the END client (legacy cusID/targetleeid). */
    CUSTOMER("customer"),

    /** The contract's payer (legacy traid) - an agency in triangular deals. */
    TRADER("trader"),
}

/** A customer or trader with airings, as returned by the party search. */
data class Party(
    val code: String,
    val name: String,
    val email: String? = null,
    val vatNumber: String? = null,
    val phone: String? = null,
    val spotCount: Int = 0,
    val placementCount: Int = 0,
)

/**
 * Substring search (server-side `%query%`, min 3 chars, caller debounces)
 * over all parties with airings on the selected station. In `core` because
 * BOTH the timetable's spot finder and the schedule-email dialog drive it -
 * features may not depend on each other.
 */
interface PartySearchRepository {
    suspend fun search(query: String, kind: PartyKind): DataResult<List<Party>, DataError.Network>
}
