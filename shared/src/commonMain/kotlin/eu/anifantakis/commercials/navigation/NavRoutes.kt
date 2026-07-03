package eu.anifantakis.commercials.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
sealed interface CommercialNavRoute : NavKey {

    @Serializable
    data object Login : CommercialNavRoute, NavKey

    @Serializable
    data object Timetable : CommercialNavRoute, NavKey

    /** Super-admin user management. */
    @Serializable
    data object UserManagement : CommercialNavRoute, NavKey

    @Serializable
    data class CommercialDetail(
        val breakId: Long,
        val breakTime: String,
        val date: LocalDate,
        val spotCount: Int
    ) : CommercialNavRoute, NavKey
}
