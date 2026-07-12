package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme

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
        elevation = CardDefaults.cardElevation(defaultElevation = t.cardElevation),
        border = if (borderWidth > 0.dp)
            BorderStroke(borderWidth, MaterialTheme.colorScheme.outlineVariant) else null,
        content = content,
    )
}
