package eu.anifantakis.commercials.feature.timetable.presentation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import eu.anifantakis.commercials.core.presentation.helper.navConfigOf
import eu.anifantakis.commercials.core.presentation.helper.navHierarchy
import eu.anifantakis.commercials.feature.timetable.presentation.screens.TEST_DATE
import kotlinx.serialization.PolymorphicSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the reflection-free registration actually ROUND-TRIPS through the
 * savedstate machinery (what rememberNavBackStack does on process death):
 * open NavKey polymorphism bridged onto the sealed hierarchy's closed
 * generated serializer via the module's polymorphic default hooks. A compile
 * pass alone cannot prove this - the old failure mode was a runtime
 * SerializationException on iOS/web.
 */
class NavStepConfigTest {

    private val config = navConfigOf(navHierarchy<TimetableStepNavType>())

    private fun roundTrip(route: NavKey): NavKey {
        val saved = encodeToSavedState(PolymorphicSerializer(NavKey::class), route, config)
        return decodeFromSavedState(PolymorphicSerializer(NavKey::class), saved, config)
    }

    @Test
    fun dataObjectRouteSurvivesTheRoundTrip() {
        assertEquals(TimetableStepNavType.Grid, roundTrip(TimetableStepNavType.Grid))
    }

    @Test
    fun dataClassRouteWithArgumentsSurvivesTheRoundTrip() {
        val route = TimetableStepNavType.CommercialDetail(
            breakId = 7,
            breakTime = "21:45",
            date = TEST_DATE,
            spotCount = 12,
        )
        assertEquals(route, roundTrip(route))
    }

    @Test
    fun multiHierarchyConfigPicksTheRightSealedSerializer() {
        val config = navConfigOf(
            navHierarchy<TimetableNavType>(),
            navHierarchy<TimetableStepNavType>(),
        )
        val flow: NavKey = TimetableNavType.TimetableFlow
        val step: NavKey = TimetableStepNavType.Grid

        val savedFlow = encodeToSavedState(PolymorphicSerializer(NavKey::class), flow, config)
        val savedStep = encodeToSavedState(PolymorphicSerializer(NavKey::class), step, config)

        assertEquals(flow, decodeFromSavedState(PolymorphicSerializer(NavKey::class), savedFlow, config))
        assertEquals(step, decodeFromSavedState(PolymorphicSerializer(NavKey::class), savedStep, config))
    }
}
