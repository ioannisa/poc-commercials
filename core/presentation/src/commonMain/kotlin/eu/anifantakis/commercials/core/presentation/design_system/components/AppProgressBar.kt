package eu.anifantakis.commercials.core.presentation.design_system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/**
 * A progress bar that refuses to lie.
 *
 * [fraction] null ⇒ the caller has NO honest measure of how far along it is, and
 * the bar renders INDETERMINATE. That is the whole point of the type: a made-up
 * percentage ("it's probably about half way") is worse than an honest "working",
 * because the operator plans around it - and a bar that sits at 90% for four
 * minutes destroys their trust in every bar you ever show them again.
 *
 * [caption] says WHAT is happening (the step, the file); [detail] says how far in
 * the phase's own unit ("1204/1707 MB", "9/16"). Both optional.
 */
@Composable
fun AppProgressBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    detail: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            val safe = fraction.coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { safe },
                modifier = Modifier
                    .fillMaxWidth()
                    // The percentage is what a screen reader must hear - the bar
                    // itself is the one thing here with no text to fall back on.
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(safe, 0f..1f)
                    },
            )
        }
        if (caption != null || detail != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    caption.orEmpty(),
                    AppTextStyle.TINY,
                    modifier = Modifier.weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    AppText(
                        detail,
                        AppTextStyle.TINY,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
