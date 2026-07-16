package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * The design-system LOCAL image: a typed `Res.drawable.*`, drawn directly.
 * Remote URLs (and `files/` resources via `Res.getUri`) are [AppAsyncImage]'s
 * job - the split keeps "Async" honest: nothing here is asynchronous.
 *
 * Deliberately THIN - and the thinness IS the optimization. Everything the
 * remote component needs machinery for does not exist on this path:
 * - `painterResource` already decodes ONCE and caches per resource+density;
 *   routing through Coil would ADD an image request, a memory-cache lookup and
 *   a decode for bytes sitting on the classpath.
 * - There is no loading/error state, so placeholder plumbing would be dead
 *   weight; `Image` with a stable painter skips recomposition entirely.
 *
 * The one honest caveat: `painterResource` decodes a raster at FULL size. For
 * a genuinely large local photo where memory matters, ship it under
 * `composeResources/files/` and load it with [AppAsyncImage] +
 * `Res.getUri("files/…")` - Coil downsamples to the measured layout size.
 * For icons, logos and illustrations (the normal case) direct drawing wins.
 *
 * Defaults differ from [AppAsyncImage] ON PURPOSE: local assets are logos and
 * illustrations, so [contentScale] is Fit (never crop a logo); remote photos
 * fill cards, so there it is Crop.
 */
@Composable
fun AppImage(
    resource: DrawableResource,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    tint: Color? = null,
) {
    Image(
        painter = painterResource(resource),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        // remembered: ColorFilter.tint allocates - don't re-make it per recomposition
        colorFilter = remember(tint) { tint?.let { ColorFilter.tint(it) } },
    )
}
