package eu.anifantakis.commercials.core.presentation.helper

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/*
 * Route-serializer registration WITHOUT reflection and WITHOUT hand-written
 * per-route subclass(...) lists.
 *
 * The trick: for a `@Serializable sealed` NavType hierarchy the compiler
 * plugin already generates a CLOSED serializer for the base type that knows
 * every subclass - on every target, at compile time. `sealedSubclasses`
 * reflection (JVM-only + kotlin-reflect, absent on JS/WASM, not in the
 * common KClass API) is therefore unnecessary: we bridge the back stack's
 * OPEN NavKey polymorphism onto each hierarchy's closed sealed serializer
 * through the module's polymorphic DEFAULT hooks. Adding a route to a
 * registered hierarchy needs NO registration change, ever.
 */

/** One `@Serializable sealed ... : NavKey` hierarchy and its closed serializer. */
class NavHierarchy<T : NavKey>(
    val klass: KClass<T>,
    val serializer: KSerializer<T>,
)

/** Captures a sealed NavType hierarchy for [navConfigOf]. */
inline fun <reified T : NavKey> navHierarchy(): NavHierarchy<T> =
    NavHierarchy(T::class, serializer<T>())

/**
 * A [SavedStateConfiguration] covering every given sealed hierarchy: one
 * line per FEATURE NavType (not per route). Serialization picks the first
 * hierarchy the value belongs to ([KClass.isInstance] - common API);
 * deserialization matches the hierarchy's serial name written alongside.
 */
fun navConfigOf(vararg hierarchies: NavHierarchy<out NavKey>): SavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule = SerializersModule {
            polymorphicDefaultSerializer(NavKey::class) { value ->
                @Suppress("UNCHECKED_CAST")
                hierarchies.firstOrNull { it.klass.isInstance(value) }
                    ?.serializer as SerializationStrategy<NavKey>?
            }
            polymorphicDefaultDeserializer(NavKey::class) { className ->
                @Suppress("UNCHECKED_CAST")
                hierarchies.firstOrNull { it.serializer.descriptor.serialName == className }
                    ?.serializer as DeserializationStrategy<NavKey>?
            }
        }
    }

/** A nested flow's persistence config, derived from its sealed step hierarchy. */
inline fun <reified F : NavKey> navStepConfig(): SavedStateConfiguration =
    navConfigOf(navHierarchy<F>())
