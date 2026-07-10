package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import eu.anifantakis.commercials.core.presentation.design_system.AppTheme

/**
 * The app's semantic text roles (dealer-totem pattern): screens pick a ROLE,
 * never a size - the type system (and the user's font-size preference)
 * decides how the role renders. Add a role when a real recurring need
 * appears; resist one-off styles.
 */
enum class AppTextStyle {
    /** Screen header next to the back arrow. */
    SCREEN_TITLE,
    /** Card/section/dialog headers. */
    SECTION_TITLE,
    /** Prominent title of a list item / banner (station name, user name). */
    ITEM_TITLE,
    /** Emphasised inline text (bold body). */
    BODY_STRONG,
    /** The default reading text of the app. */
    BODY,
    /** Secondary/explanatory text under fields and tables. */
    NOTE,
    /** The smallest text (chips, footnotes, dense meta). */
    TINY,
    /** Column header of a dense data table (finder console, placements). */
    TABLE_HEADER,
    /** Cell of a dense data table. */
    TABLE_CELL,
    /** Emphasised (e.g. selected-row) cell of a dense data table. */
    TABLE_CELL_STRONG,
    /** Console/log lines - monospaced, dense. */
    LOG_LINE,
    /** Monospaced emphasis (recovery codes, tokens, ids). */
    MONO,
    /** Big number of a stat header. */
    STAT_VALUE,
    /** The label beside/above a stat value. */
    STAT_LABEL,
    /** Inline error/validation text. */
    ERROR_NOTE,
    /** Title slot of an AlertDialog (keeps the Material dialog-title look). */
    DIALOG_TITLE,
    /**
     * Label inside a Button/TextButton/DropdownMenuItem: the component's own
     * content color passes through (enabled/disabled/error states keep
     * working), the size is the Material button label - scaled.
     */
    BUTTON,
    /** Emphasised (bold) button label - the dialog's primary action. */
    BUTTON_STRONG,
    /**
     * Label/placeholder slot of a TextField: inherits BOTH the slot's
     * animated style (resting <-> floating) and its state color
     * (focused/unfocused/error) - AppText only routes it through the system.
     */
    FIELD_LABEL,
}

@Composable
private fun resolveAppTextStyle(style: AppTextStyle): Pair<TextStyle, Color> {
    val t = AppTheme.typography
    val actualStyle: TextStyle = when (style) {
        AppTextStyle.SCREEN_TITLE -> t.screenTitle
        AppTextStyle.SECTION_TITLE -> t.sectionTitle
        AppTextStyle.ITEM_TITLE -> t.material.titleMedium
        AppTextStyle.BODY_STRONG -> t.material.bodyMedium.copy(fontWeight = FontWeight.Bold)
        AppTextStyle.BODY -> t.material.bodyMedium
        AppTextStyle.NOTE -> t.material.bodySmall
        AppTextStyle.TINY -> t.material.labelSmall
        AppTextStyle.TABLE_HEADER -> t.material.labelSmall.copy(fontWeight = FontWeight.Bold)
        AppTextStyle.TABLE_CELL -> t.material.bodySmall
        AppTextStyle.TABLE_CELL_STRONG -> t.material.bodySmall.copy(fontWeight = FontWeight.Bold)
        AppTextStyle.LOG_LINE -> t.logLine
        AppTextStyle.MONO -> t.mono
        AppTextStyle.STAT_VALUE -> t.statValue
        AppTextStyle.STAT_LABEL -> t.statLabel
        AppTextStyle.ERROR_NOTE -> t.material.bodySmall
        AppTextStyle.DIALOG_TITLE -> t.material.headlineSmall
        AppTextStyle.BUTTON -> t.material.labelLarge
        AppTextStyle.BUTTON_STRONG -> t.material.labelLarge.copy(fontWeight = FontWeight.Bold)
        AppTextStyle.FIELD_LABEL -> LocalTextStyle.current
    }
    val defaultColor: Color = when (style) {
        AppTextStyle.SCREEN_TITLE,
        AppTextStyle.SECTION_TITLE,
        AppTextStyle.ITEM_TITLE,
        AppTextStyle.BODY_STRONG,
        AppTextStyle.BODY,
        AppTextStyle.TABLE_HEADER,
        AppTextStyle.TABLE_CELL,
        AppTextStyle.TABLE_CELL_STRONG,
        AppTextStyle.LOG_LINE,
        AppTextStyle.MONO,
        AppTextStyle.STAT_VALUE,
        AppTextStyle.DIALOG_TITLE -> MaterialTheme.colorScheme.onSurface

        AppTextStyle.NOTE,
        AppTextStyle.TINY,
        AppTextStyle.STAT_LABEL -> MaterialTheme.colorScheme.onSurfaceVariant

        AppTextStyle.ERROR_NOTE -> MaterialTheme.colorScheme.error

        // Slot roles: the enclosing component decides (button states,
        // focused-label color) - never override with a theme constant.
        AppTextStyle.BUTTON,
        AppTextStyle.BUTTON_STRONG,
        AppTextStyle.FIELD_LABEL -> LocalContentColor.current
    }
    return actualStyle to defaultColor
}

@Composable
fun AppText(
    text: String,
    style: AppTextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration = TextDecoration.None,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val (resolvedStyle, defaultColor) = resolveAppTextStyle(style)
    Text(
        text = text,
        style = resolvedStyle,
        modifier = modifier,
        color = if (color != Color.Unspecified) color else defaultColor,
        textAlign = textAlign,
        textDecoration = textDecoration,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun AppText(
    text: AnnotatedString,
    style: AppTextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val (resolvedStyle, defaultColor) = resolveAppTextStyle(style)
    Text(
        text = text,
        style = resolvedStyle,
        modifier = modifier,
        color = if (color != Color.Unspecified) color else defaultColor,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )
}
