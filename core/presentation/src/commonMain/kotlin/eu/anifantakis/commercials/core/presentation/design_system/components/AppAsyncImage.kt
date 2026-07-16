package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import eu.anifantakis.commercials.core.presentation.design_system.AppDrawableRepo
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import kotlin.math.min

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
) {
    val context = LocalPlatformContext.current
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
