package eu.anifantakis.commercials.core.presentation.design_system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButton
import eu.anifantakis.commercials.core.presentation.design_system.components.AppButtonVariant
import eu.anifantakis.commercials.core.presentation.design_system.components.AppGroupBox
import eu.anifantakis.commercials.core.presentation.design_system.components.AppPendingBox
import eu.anifantakis.commercials.core.presentation.design_system.components.AppRadioColumn
import eu.anifantakis.commercials.core.presentation.design_system.components.AppSelectionOption
import eu.anifantakis.commercials.core.presentation.design_system.components.AppText
import eu.anifantakis.commercials.core.presentation.design_system.components.AppTextStyle
import eu.anifantakis.commercials.core.presentation.string_resources.LocalizationProvider

/**
 * A dev-only, login-free stand-in for the Timetable header's legacy grouped-box
 * toolbar. It mirrors the real layout with the SAME design-system components
 * and literal strings, so the box frames, density and spacing can be rendered
 * and eyeballed in isolation - the real header sits behind auth.
 *
 * Current shape: a LEFT column (Μηνύματα box - with a WIDE message dropdown -
 * over the month selector; the report toolbar is hidden) and everything else to
 * its RIGHT - the box band FILLED to the full height (no gap), the station
 * selector-over-logo right after it, and account (top) + hints (bottom) at the
 * far right.
 *
 * Rendered both by the PlatformShowcase section and by the offscreen
 * `renderToolbar` gradle task (which writes a PNG for review).
 */
@Composable
fun LegacyToolbarMock() {
    var mode by remember { mutableStateOf("break") }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = UIConst.paddingSmall, vertical = UIConst.paddingExtraSmall),
            horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
            verticalAlignment = Alignment.Top,
        ) {
            // LEFT column: Μηνύματα over the month selector (print hidden).
            Column(
                modifier = Modifier.fillMaxHeight().width(IntrinsicSize.Max),
                verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall),
            ) {
                AppGroupBox(
                    title = "Μηνύματα",
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    AppText("Επωνυμία: —", AppTextStyle.NOTE, maxLines = 1)
                    AppText("Συμβόλαιο: —", AppTextStyle.NOTE, maxLines = 1)
                    AppText("Προϊόν: —", AppTextStyle.NOTE, maxLines = 1)
                    Spacer(Modifier.height(UIConst.paddingExtraSmall))
                    // Εύρεση + the WIDE dropdown on ONE row (no extra height).
                    Row(
                        modifier = Modifier.fillMaxWidth().widthIn(min = 460.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppButton(text = "Εύρεση", onClick = {}, variant = AppButtonVariant.SECONDARY)
                        Spacer(Modifier.width(UIConst.paddingSmall))
                        Box(modifier = Modifier.weight(1f)) {
                            AppButton(onClick = {}, enabled = false, fillMaxWidth = true) {
                                AppText("— κανένα σποτ —", AppTextStyle.NOTE, modifier = Modifier.weight(1f))
                                AppText("▾", AppTextStyle.NOTE)
                            }
                        }
                    }
                }
                // Month selector - the legacy's ◀ Δεκέμβριος 2025 ▶ strip.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText("←", AppTextStyle.SCREEN_TITLE)
                    AppText(
                        "Ιούλιος 2026",
                        AppTextStyle.SCREEN_TITLE,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    AppText("→", AppTextStyle.SCREEN_TITLE)
                }
            }

            // RIGHT side: box band filled to full height, account top-right,
            // hints bottom-right.
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(UIConst.paddingSmall),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight().width(IntrinsicSize.Max),
                        verticalArrangement = Arrangement.spacedBy(UIConst.paddingExtraSmall),
                    ) {
                        AppPendingBox(title = "Πρόσθεση νέου διαλείματος", modifier = Modifier.weight(1f).fillMaxWidth())
                        AppPendingBox(title = "Τύποι Προγράμματος", modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                    AppRadioColumn(
                        title = "Προβολή κάθε",
                        options = listOf(
                            AppSelectionOption("hour", "1 Ώρα"),
                            AppSelectionOption("half", "Μισή Ώρα"),
                            AppSelectionOption("break", "Διάλειμμα"),
                        ),
                        selected = mode,
                        onSelect = { mode = it },
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    )
                    AppPendingBox(title = "Προβολή Βάσει…", modifier = Modifier.fillMaxHeight())
                    // Station selector OVER its logo, right after the boxes.
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppText("Crete TV ▾", AppTextStyle.SECTION_TITLE, color = MaterialTheme.colorScheme.primary)
                        AppText("KPHTH TV", AppTextStyle.ITEM_TITLE)
                    }
                }

                Spacer(Modifier.weight(1f))

                // Far-right: account (top) + hints (bottom).
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    horizontalAlignment = Alignment.End,
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        AppText("Super Administrator", AppTextStyle.BODY_STRONG)
                        AppText("Υπερδιαχειριστής", AppTextStyle.TINY)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        AppText("Βέλη: Πλοήγηση | Enter: Άνοιγμα | Α: Προσθήκη | R: Αφαίρεση", AppTextStyle.TINY)
                        AppText("Κάντε κλικ στο πλέγμα για εστίαση, μετά χρησιμοποιήστε το πληκτρολόγιο", AppTextStyle.TINY)
                    }
                }
            }
        }
    }
}

/**
 * Fully self-contained wrapper (theme + localization + the header's toolbar
 * surface) for the offscreen renderer.
 */
@Composable
fun LegacyToolbarLab(dark: Boolean = false) {
    CommercialsTheme(darkTheme = dark) {
        LocalizationProvider {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
                LegacyToolbarMock()
            }
        }
    }
}
