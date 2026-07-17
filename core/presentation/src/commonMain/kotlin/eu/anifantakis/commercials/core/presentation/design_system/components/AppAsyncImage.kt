package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import io.ktor.client.HttpClient
import kotlin.math.min

/**
 * The app's AUTHENTICATED Ktor client for FIRST-PARTY images - DIP without a
 * DI framework. Presentation declares only this slot (and its Ktor type);
 * the app's COMPOSITION ROOT - the one layer that legitimately sees the data
 * layer - provides the value, once:
 *
 *     CompositionLocalProvider(LocalAuthImageHttpClient provides api.client) { App() }
 *
 * Call sites then opt IN per image:
 *
 *     AppAsyncImage(url, httpClient = LocalAuthImageHttpClient.current, …)
 *
 * Deliberately NOT the default of [AppAsyncImage]'s `httpClient` param: an
 * ambient default would silently route THIRD-PARTY hosts through the
 * authenticated client and leak the bearer token to them - first-party call
 * sites must say so, at the call site. `static` because the client never
 * changes within an app run (no recomposition tracking needed). Unprovided
 * (previews, tests, apps with no authenticated imagery) it stays null and
 * [AppAsyncImage] falls back to Coil's default loader.
 */
val LocalAuthImageHttpClient = staticCompositionLocalOf<HttpClient?> { null }

/**
 * The server ORIGIN for first-party resource urls (e.g. "https://api.acme.com") -
 * the second transport fact, same DIP-without-DI slot as
 * [LocalAuthImageHttpClient]: the composition root provides it once from the
 * app's config. `AppDrawableRepo`'s REMOTE entries build their absolute urls
 * from it - so no screen (and no App.kt) ever hardcodes an image url; the
 * root provides WHERE the server is, the repo decides WHAT lives there.
 */
val LocalServerBaseUrl = staticCompositionLocalOf<String?> { null }

/**
 * A remote image RESOLVED: absolute url + the client to fetch it with (null =
 * Coil's default, credential-free loader). This is what `AppDrawableRepo`'s
 * remote entries return; render it with the [AppAsyncImage] `source` overload.
 * The descriptor is complete ON PURPOSE - the repo entry decides url AND
 * transport in one place, so call sites only draw.
 */
@Immutable
data class RemoteImage(
    val url: String,
    val httpClient: HttpClient? = null,
)

/**
 * Placeholder treatment for [AppAsyncImage] - ONE closed mode instead of
 * boolean soup (`showPlaceholder`/`simpleError`/`force` flags allow illegal
 * combinations; a sealed type cannot express one).
 */
sealed interface AppImagePlaceholder {
    /** Empty slot until the image lands. */
    data object None : AppImagePlaceholder

    /** The icon painter drawn as-is: its own aspect, no backdrop. */
    data class Plain(val icon: Painter) : AppImagePlaceholder

    /**
     * Brand treatment: a backdrop + the icon fitted to the slot and blown up
     * [iconScale]x, centered (an oversized crest "peeking" through the card).
     * [background] left [Color.Unspecified] resolves to the theme's
     * surfaceVariant, so the placeholder follows light/dark for free.
     */
    data class Styled(
        val icon: Painter,
        val background: Color = Color.Unspecified,
        val iconScale: Float = 1.9f,
    ) : AppImagePlaceholder
}

/**
 * The design-system remote image: Coil 3 [AsyncImage] with the placeholder
 * going through Coil's PAINTER slots, not through compose state.
 *
 * Why painter slots: placeholder/error/fallback all reuse ONE painter, so the
 * loading -> loaded swap happens at the DRAW layer. The composable never
 * recomposes per image state and exactly one Image node is composed per card -
 * this is what keeps lazy rows/grids of images scrolling smoothly (the
 * subcomposing alternative, SubcomposeAsyncImage, costs a subcomposition per
 * cell).
 *
 * There is NO `forcePlaceholder` flag: pass `url = null` and Coil's `fallback`
 * slot shows the same placeholder painter, through the same layout path - the
 * forced case cannot drift from the loading/error case because they are one
 * code path.
 *
 * [contentDescription] is deliberately REQUIRED: a photo/logo is content more
 * often than decoration, so the call site must decide (pass null consciously
 * for decorative images) instead of inheriting an accidental default.
 *
 * [tint] applies a [ColorFilter.tint] to the LOADED image - meant for
 * monochrome SVG glyphs served by a backend (the reason [SvgDecoder] is
 * attached); leave null for photos.
 *
 * Sources: a remote URL, or a resource under `composeResources/files/` via
 * `Res.getUri("files/…")` (Coil 3 loads `jar:`/`file:`/http resource URIs;
 * `getUri` needs `@OptIn(ExperimentalResourceApi::class)` at the call site).
 * A TYPED LOCAL `Res.drawable.*` is NOT this component's job - that is
 * [AppImage], which draws synchronously and never touches Coil.
 *
 * [httpClient] routes the fetch through YOUR Ktor client instead of Coil's
 * default (credential-free) one - THE way to load images behind
 * authorization: pass the app's authenticated client and its `defaultRequest`
 * pipeline (bearer header, station param, …) stamps every image request.
 * Works on wasm too: the ktor3 fetcher downloads via `fetch()` with real
 * headers, never an `<img>` tag. Loaders are cached per client INSTANCE
 * ([PerClientImageLoaders]), so all images fetched through one client share
 * one loader and ONE memory cache. Leave null for public URLs - and do NOT
 * funnel third-party hosts through an authenticated client: its default
 * headers would leak the bearer token to them.
 */
@Composable
fun AppAsyncImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    placeholder: AppImagePlaceholder = AppImagePlaceholder.None,
    tint: Color? = null,
    httpClient: HttpClient? = null,
) {
    val context = LocalPlatformContext.current
    // remembered: keeps the per-client map probe (or the singleton lookup) out
    // of every recomposition - same policy as the ColorFilter below.
    val imageLoader = remember(context, httpClient) {
        if (httpClient == null) SingletonImageLoader.get(context)
        else PerClientImageLoaders.forClient(context, httpClient)
    }
    val request = remember(context, url) {
        ImageRequest.Builder(context)
            .data(url)
            // SVG sniffing is content-based, so raster URLs pass through untouched.
            .decoderFactory(SvgDecoder.Factory())
            // The painter swap already happens at the draw layer; a crossfade
            // would force an animation frame per cell while a lazy grid fills.
            .crossfade(false)
            .build()
    }

    val placeholderPainter: Painter? = when (placeholder) {
        AppImagePlaceholder.None -> null
        is AppImagePlaceholder.Plain -> placeholder.icon
        is AppImagePlaceholder.Styled -> {
            val background = placeholder.background.takeOrElse {
                MaterialTheme.colorScheme.surfaceVariant
            }
            remember(placeholder.icon, background, placeholder.iconScale) {
                StyledPlaceholderPainter(background, placeholder.icon, placeholder.iconScale)
            }
        }
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        imageLoader = imageLoader,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        // remembered: ColorFilter.tint allocates - don't re-make it per recomposition
        colorFilter = remember(tint) { tint?.let { ColorFilter.tint(it) } },
        placeholder = placeholderPainter,
        error = placeholderPainter,
        fallback = placeholderPainter,
    )
}

/**
 * The [RemoteImage] overload - the REPO-ENTRY path. An `AppDrawableRepo`
 * remote entry already decided url and transport; this just draws it:
 *
 *     AppAsyncImage(
 *         source = AppDrawableRepo.stationLogo(station.id),
 *         contentDescription = station.name,
 *     )
 *
 * `source = null` (entry unavailable - e.g. the root hasn't provided
 * [LocalServerBaseUrl], or there is nothing to show) renders the placeholder,
 * same as `url = null` on the primary overload. [httpClient] non-null
 * OVERRIDES the source's client - the escape hatch for special call sites
 * (e.g. a MockEngine-backed preview); leave null to honour the repo's choice.
 */
@Composable
fun AppAsyncImage(
    source: RemoteImage?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    placeholder: AppImagePlaceholder = AppImagePlaceholder.None,
    tint: Color? = null,
    httpClient: HttpClient? = null,
) = AppAsyncImage(
    url = source?.url,
    contentDescription = contentDescription,
    modifier = modifier,
    contentScale = contentScale,
    alignment = alignment,
    placeholder = placeholder,
    tint = tint,
    httpClient = httpClient ?: source?.httpClient,
)

/**
 * One [ImageLoader] per [HttpClient] INSTANCE, created lazily and reused for
 * the app's lifetime - every call site passing the same client shares one
 * loader and therefore ONE memory cache (a loader per call site would refetch
 * and re-decode the same image once per screen).
 *
 * Keyed by IDENTITY on purpose: Ktor's [HttpClient] is final and does not
 * override equals - "three clients for three backends" means three INSTANCES
 * of one class, so a class-keyed registry would collapse them into one entry.
 *
 * Only ever touched from composition (the UI thread), which is what makes the
 * unsynchronized map safe. Entries are never evicted - correct while clients
 * are app-lifetime singletons (ours are); never pass throwaway clients built
 * per call, each would pin a loader forever.
 */
private object PerClientImageLoaders {
    private val loaders = mutableMapOf<HttpClient, ImageLoader>()

    // opt-in: the fixed-client KtorNetworkFetcherFactory overload is
    // @ExperimentalCoilApi in Coil 3.5 (the no-arg one is not)
    @OptIn(ExperimentalCoilApi::class)
    fun forClient(context: PlatformContext, client: HttpClient): ImageLoader =
        loaders.getOrPut(client) {
            ImageLoader.Builder(context)
                .components { add(KtorNetworkFetcherFactory(httpClient = client)) }
                .build()
        }
}

/**
 * The [AppImagePlaceholder.Styled] treatment as a plain [Painter]: backdrop +
 * icon fitted to the drawn size and scaled up, centered.
 *
 * [intrinsicSize] stays [Size.Unspecified] ON PURPOSE: the image's
 * [ContentScale] (e.g. Crop) is applied against the painter's intrinsics, so
 * an unspecified size means the placeholder always fills the slot exactly and
 * is never distorted by whatever scale the REAL image needs.
 */
private class StyledPlaceholderPainter(
    private val background: Color,
    private val icon: Painter,
    private val iconScale: Float,
) : Painter() {

    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        drawRect(color = background)

        val intrinsic = icon.intrinsicSize
        val iconSize = if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
            // Fit the icon inside the slot, then blow it up: 'fit' preserves the
            // aspect ratio, iconScale produces the oversized-crest look.
            val fit = min(size.width / intrinsic.width, size.height / intrinsic.height)
            Size(intrinsic.width * fit * iconScale, intrinsic.height * fit * iconScale)
        } else {
            size
        }
        translate(
            left = (size.width - iconSize.width) / 2f,
            top = (size.height - iconSize.height) / 2f,
        ) {
            with(icon) { draw(size = iconSize) }
        }
    }
}

// url = null exercises the fallback slot - the SAME painter the loading/error
// states use, so this preview IS the placeholder look, no network needed.
@Preview
@Composable
private fun AppAsyncImagePlaceholderPreview() = AppPreview {
    Row(
        horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppAsyncImage(
            url = null,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            placeholder = AppImagePlaceholder.Styled(
                icon = rememberVectorPainter(AppDrawableRepo.timer),
            ),
        )
        AppAsyncImage(
            url = null,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            placeholder = AppImagePlaceholder.Plain(
                icon = rememberVectorPainter(AppDrawableRepo.description),
            ),
        )
    }
}
