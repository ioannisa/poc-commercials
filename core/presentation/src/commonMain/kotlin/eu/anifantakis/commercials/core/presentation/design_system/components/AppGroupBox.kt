package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

/**
 * The legacy Windows "GroupBox", drawn faithfully: a thin outline whose TOP
 * border the caption sits ON (the border has a gap under the text), and a
 * transparent body - the box shares the toolbar's background, exactly like the
 * original console's "Προβολή κάθε" / "Break για…" / "Προβολή Βάσει…" frames.
 * This is the shared primitive every grouped control is built on
 * (radio/checkbox columns, pending placeholders).
 *
 * The caption is not a layout child - it is MEASURED (TextMeasurer) and DRAWN
 * (drawText) inside the same drawBehind pass as the frame. That keeps the
 * component a plain Column: caller modifiers like fillMaxHeight stretch the
 * frame correctly, and [verticalArrangement] can center content in a stretched
 * box without spacer tricks. RTL flips the caption to the top-right corner.
 */
@Composable
fun AppGroupBox(
    title: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Optional body fill; default transparent (the legacy look). */
    containerColor: Color = Color.Unspecified,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = AppTheme.visualTokens
    val borderColor = MaterialTheme.colorScheme.outline
    val strokeWidth = if (AppTheme.a11y.highContrast) maxOf(t.controlBorderWidth, 1.5.dp) else maxOf(t.controlBorderWidth, 1.dp)
    val corner = t.cornerSmall
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else UIConst.grayOutColor(MaterialTheme.colorScheme.onSurfaceVariant)

    // Caption measured up front - its height decides where the frame's top
    // edge sits (halfway through the text) and how far content starts below.
    val measurer = rememberTextMeasurer()
    val titleStyle = AppTheme.typography.material.labelSmall.copy(fontWeight = FontWeight.SemiBold)
    val titleLayout: TextLayoutResult? = if (title.isNullOrEmpty()) null else {
        remember(title, titleStyle, measurer) { measurer.measure(AnnotatedString(title), titleStyle) }
    }
    val density = LocalDensity.current
    val titleHeightDp = with(density) { (titleLayout?.size?.height ?: 0).toDp() }
    val contentTopPad = if (titleLayout != null) titleHeightDp + UIConst.paddingHairline else UIConst.paddingExtraSmall
    // The caption is DRAWN, not laid out - without this floor a narrow box
    // would let a long caption spill past its frame into the neighbour.
    val captionMinWidth = with(density) { (titleLayout?.size?.width ?: 0).toDp() } +
        (UIConst.paddingSmall + 3.dp) * 2

    Column(
        modifier = modifier
            .widthIn(min = captionMinWidth)
            .drawBehind {
                drawGroupBox(
                    titleLayout = titleLayout,
                    titleColor = titleColor,
                    borderColor = borderColor,
                    fill = containerColor,
                    strokePx = strokeWidth.toPx(),
                    cornerPx = corner.toPx(),
                    titleInsetPx = UIConst.paddingSmall.toPx(),
                    gapPadPx = 3.dp.toPx(),
                )
            }
            .padding(
                start = UIConst.paddingSmall,
                end = UIConst.paddingSmall,
                top = contentTopPad,
                bottom = UIConst.paddingExtraSmall,
            ),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/**
 * A stub for a legacy control we have NOT migrated yet: the same frame with a
 * muted gray body and the word "pending" - so the top toolbar shows the FULL
 * legacy structure while making unfinished areas unmistakable. Content centers
 * itself when a stacked slot stretches the box.
 */
@Composable
fun AppPendingBox(
    title: String,
    modifier: Modifier = Modifier,
    minWidth: Dp = 116.dp,
    minHeight: Dp = 20.dp,
) {
    AppGroupBox(
        title = title,
        enabled = false,
        containerColor = UIConst.grayOutColor(MaterialTheme.colorScheme.surfaceVariant, 0.15f),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.widthIn(min = minWidth).heightIn(min = minHeight),
            contentAlignment = Alignment.Center,
        ) {
            AppText(
                "pending",
                AppTextStyle.NOTE,
                color = UIConst.grayOutColor(MaterialTheme.colorScheme.onSurfaceVariant, 0.2f),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

/**
 * Frame + caption in one pass. The outline runs along the stroke centerline;
 * its top edge sits at half the caption height so the text straddles the line,
 * and the top edge simply is not drawn under the caption (gap between
 * [gapPadPx]-padded caption bounds). RTL mirrors the caption to the right.
 * A body [fill] (pending boxes) paints under the frame area first.
 */
private fun DrawScope.drawGroupBox(
    titleLayout: TextLayoutResult?,
    titleColor: Color,
    borderColor: Color,
    fill: Color,
    strokePx: Float,
    cornerPx: Float,
    titleInsetPx: Float,
    gapPadPx: Float,
) {
    val titleH = titleLayout?.size?.height?.toFloat() ?: 0f
    val titleW = titleLayout?.size?.width?.toFloat() ?: 0f
    val half = strokePx / 2f
    val left = half
    val top = (titleH / 2f).coerceAtLeast(half)
    val right = size.width - half
    val bottom = size.height - half
    if (right <= left || bottom <= top) return
    val r = cornerPx.coerceAtMost(minOf(right - left, bottom - top) / 2f).coerceAtLeast(0f)

    if (fill != Color.Unspecified) {
        drawRoundRect(
            color = fill,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(r, r),
        )
    }

    if (titleLayout == null) {
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = strokePx),
        )
        return
    }

    // Caption x: inset from the leading edge (mirrored under RTL).
    val titleX = when (layoutDirection) {
        LayoutDirection.Ltr -> titleInsetPx
        LayoutDirection.Rtl -> (size.width - titleInsetPx - titleW).coerceAtLeast(0f)
    }
    // Gap window on the top edge, clamped so a very wide caption can't invert
    // it or eat the corners.
    val minX = left + r
    val maxX = (right - r).coerceAtLeast(minX)
    val gapStart = (titleX - gapPadPx).coerceIn(minX, maxX)
    val gapEnd = (titleX + titleW + gapPadPx).coerceIn(gapStart, maxX)

    val path = Path().apply {
        // top edge: right of the gap → top-right corner
        moveTo(gapEnd, top)
        lineTo(right - r, top)
        arcTo(Rect(right - 2 * r, top, right, top + 2 * r), -90f, 90f, false)
        // right edge → bottom-right corner
        lineTo(right, bottom - r)
        arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false)
        // bottom edge → bottom-left corner
        lineTo(left + r, bottom)
        arcTo(Rect(left, bottom - 2 * r, left + 2 * r, bottom), 90f, 90f, false)
        // left edge → top-left corner
        lineTo(left, top + r)
        arcTo(Rect(left, top, left + 2 * r, top + 2 * r), 180f, 90f, false)
        // top edge: top-left corner → left of the gap
        lineTo(gapStart, top)
    }
    drawPath(path, borderColor, style = Stroke(width = strokePx))

    drawText(titleLayout, color = titleColor, topLeft = Offset(titleX, 0f))
}

// The caption-on-border look is the whole point, and a hairline reads
// completely differently on the dark palette - so both are previewed.
@Composable
private fun AppGroupBoxSamples() {
    Row(horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        AppGroupBox(title = "View every") {
            AppText("○ 1 Hour", AppTextStyle.NOTE)
            AppText("● Half Hour", AppTextStyle.NOTE)
            AppText("○ Break", AppTextStyle.NOTE)
        }
        AppPendingBox(title = "Show based on…")
        AppGroupBox(title = null) {
            AppText("Untitled frame", AppTextStyle.NOTE)
        }
    }
}

@Preview
@Composable
private fun AppGroupBoxPreview() = AppPreview { AppGroupBoxSamples() }

@Preview
@Composable
private fun AppGroupBoxDarkPreview() = AppPreview(dark = true) { AppGroupBoxSamples() }
