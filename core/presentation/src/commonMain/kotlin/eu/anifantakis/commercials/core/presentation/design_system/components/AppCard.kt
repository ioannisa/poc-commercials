package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme
import eu.anifantakis.commercials.core.presentation.design_system.UIConst
import eu.anifantakis.commercials.core.presentation.design_system.preview.AppPreview
import androidx.compose.ui.tooling.preview.Preview

/**
 * The design-system card. Depth is the platform tell, driven entirely by
 * tokens: Android floats (elevation), iOS/macOS draw a hairline, Windows/
 * Linux/web draw a real stroke - elevation XOR border, never both by
 * accident. Under high contrast a border always appears and is never a
 * hairline.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    /** Banner/status cards tint their container (primaryContainer/errorContainer). */
    containerColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = AppTheme.visualTokens
    val highContrast = AppTheme.a11y.highContrast
    val borderWidth = when {
        highContrast -> maxOf(t.cardBorderWidth, 1.5.dp)
        else -> t.cardBorderWidth
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(t.cornerMedium),
        colors = if (containerColor != Color.Unspecified)
            CardDefaults.cardColors(containerColor = containerColor)
        else CardDefaults.cardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = t.cardElevation),
        border = if (borderWidth > 0.dp)
            BorderStroke(borderWidth, MaterialTheme.colorScheme.outlineVariant) else null,
        content = content,
    )
}

@Composable
private fun AppCardSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(UIConst.paddingSmall)) {
        // Plain surface card: the everyday list item.
        AppCard {
            Column(Modifier.padding(UIConst.paddingRegular)) {
                AppText("Crete TV", AppTextStyle.ITEM_TITLE)
                AppText("14 breaks scheduled today", AppTextStyle.NOTE)
            }
        }
        // Tinted banner card: the container color is the whole point of the param.
        AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(UIConst.paddingRegular)) {
                AppText("Contract CTV-2026-014 renewed", AppTextStyle.BODY_STRONG)
                AppText("Aegean Foods - 120 spots until 31 August", AppTextStyle.BODY)
            }
        }
        AppCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.padding(UIConst.paddingRegular)) {
                AppText("Break overbooked", AppTextStyle.BODY_STRONG)
                AppText("Radio 984 - the 21:00 break exceeds 180 seconds", AppTextStyle.BODY)
            }
        }
    }
}

@Preview
@Composable
private fun AppCardPreview() = AppPreview { AppCardSamples() }

// Depth is the card's whole visual identity, and elevation reads completely
// differently on the dark palette - previewing only the light one hides that.
@Preview
@Composable
private fun AppCardDarkPreview() = AppPreview(dark = true) { AppCardSamples() }
