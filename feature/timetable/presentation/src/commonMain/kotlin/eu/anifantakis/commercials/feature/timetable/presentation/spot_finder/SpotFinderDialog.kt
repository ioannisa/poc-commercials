package eu.anifantakis.commercials.feature.timetable.presentation.spot_finder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.feature.timetable.presentation.timetable.FinderUiState
import eu.anifantakis.commercials.feature.timetable.presentation.timetable.TimetableIntent

/**
 * The legacy "Εύρεση" Details Console, kept close to the original layout:
 * three stacked table sections - ΠΕΛΑΤΗΣ (search + the matches as a table
 * with Κωδικός/Επωνυμία/ΑΦΜ/Τηλέφωνο), ΣΥΜΒΟΛΑΙΑ ΠΕΛΑΤΗ (the contracts'
 * product lines; ERP product identity pending), ΜΗΝΥΜΑΤΑ (the line's spots
 * with Χρόνος/Αναλωμένα Spots/Secs) - and Επιλογή/Άκυρο bottom-right.
 * Stateless: renders [FinderUiState], dispatches [TimetableIntent]s (the
 * debounce lives in the ViewModel). "Επιλογή" arms the grid's 'a' key.
 */
@Composable
fun SpotFinderDialog(
    finder: FinderUiState,
    onIntent: (TimetableIntent) -> Unit,
) {
    Dialog(
        onDismissRequest = { onIntent(TimetableIntent.CloseFinder) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Εύρεση — Κονσόλα Λεπτομερειών", fontSize = 15.sp, fontWeight = FontWeight.Bold)

                // ═══ ΠΕΛΑΤΗΣ ═══════════════════════════════════════════
                SectionTitle("Πελάτης")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = finder.kind == PartyKind.CUSTOMER,
                        onClick = { onIntent(TimetableIntent.FinderKindChanged(PartyKind.CUSTOMER)) }
                    )
                    Text(
                        "Πελάτες", fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            onIntent(TimetableIntent.FinderKindChanged(PartyKind.CUSTOMER))
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    RadioButton(
                        selected = finder.kind == PartyKind.TRADER,
                        onClick = { onIntent(TimetableIntent.FinderKindChanged(PartyKind.TRADER)) }
                    )
                    Text(
                        "Διαφημιστές", fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            onIntent(TimetableIntent.FinderKindChanged(PartyKind.TRADER))
                        }
                    )
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        value = finder.query,
                        onValueChange = { onIntent(TimetableIntent.FinderQueryChanged(it)) },
                        label = { Text("Εύρεση (3+ χαρακτήρες)", fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.weight(1f),
                        trailingIcon = {
                            if (finder.searching) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            }
                        }
                    )
                }
                HeaderRow(
                    "Κωδικός" to 0.14f, "Επωνυμία" to 0.44f,
                    "ΑΦΜ" to 0.14f, "Τηλέφωνο" to 0.14f, "Σποτ" to 0.14f,
                )
                // While a search runs the matches fill the table; the
                // chosen party stays pinned as its single (highlighted) row.
                val partyRows = finder.results.ifEmpty { listOfNotNull(finder.selectedParty) }
                LazyColumn(Modifier.fillMaxWidth().weight(0.24f)) {
                    items(partyRows, key = { it.code }) { c ->
                        val isSel = c.code == finder.selectedParty?.code && finder.results.isEmpty()
                        TableRow(
                            selected = isSel,
                            onClick = { onIntent(TimetableIntent.FinderPartySelected(c)) },
                            c.code to 0.14f,
                            c.name to 0.44f,
                            (c.vatNumber ?: "") to 0.14f,
                            (c.phone ?: "") to 0.14f,
                            "${c.spotCount}" to 0.14f,
                        )
                    }
                }

                // ═══ ΣΥΜΒΟΛΑΙΑ ΠΕΛΑΤΗ ══════════════════════════════════
                SectionTitle("Συμβόλαια Πελάτη — προϊόντα (απεικόνιση έως το ERP import)")
                HeaderRow(
                    "Συμβ." to 0.16f, "Γρ." to 0.06f, "Περιγραφή" to 0.34f,
                    "Spots ΑΓ." to 0.12f, "Secs ΑΓ." to 0.12f, "Ημ/νία Έκδ." to 0.20f,
                )
                if (finder.loadingLines) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                LazyColumn(Modifier.fillMaxWidth().weight(0.3f)) {
                    items(finder.lines, key = { it.lineId }) { line ->
                        TableRow(
                            selected = line.lineId == finder.selectedLine?.lineId,
                            onClick = { onIntent(TimetableIntent.FinderLineSelected(line)) },
                            line.contractNumber to 0.16f,
                            "${line.lineNo}" to 0.06f,
                            (if (line.isGift) "Διαφημίσεις  Δ Ω Ρ Α" else "Προϊόν ERP (εκκρεμεί)") to 0.34f,
                            "${line.placements}" to 0.12f,
                            "${line.totalSeconds}" to 0.12f,
                            (line.entryDate ?: "") to 0.20f,
                        )
                    }
                }

                // ═══ ΜΗΝΥΜΑΤΑ ══════════════════════════════════════════
                SectionTitle("Μηνύματα")
                HeaderRow(
                    "Περιγραφή Μηνύματος" to 0.52f, "Χρόνος (secs)" to 0.16f,
                    "Αναλωμένα Spots" to 0.16f, "Αναλωμένα Secs" to 0.16f,
                )
                if (finder.loadingSpots) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                LazyColumn(Modifier.fillMaxWidth().weight(0.3f)) {
                    items(finder.spots, key = { it.spotId }) { spot ->
                        TableRow(
                            selected = spot.spotId == finder.selectedSpot?.spotId,
                            onClick = { onIntent(TimetableIntent.FinderSpotSelected(spot)) },
                            spot.description to 0.52f,
                            "${spot.durationSeconds}" to 0.16f,
                            "${spot.placements}" to 0.16f,
                            "${spot.totalSeconds}" to 0.16f,
                        )
                    }
                }

                // ═══ Επιλογή / Άκυρο (bottom-right, like the original) ══
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onIntent(TimetableIntent.ClearFinder) }) { Text("Καθαρισμός") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = finder.selectedSpot != null,
                        onClick = { onIntent(TimetableIntent.CloseFinder) }
                    ) {
                        Text("Επιλογή", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { onIntent(TimetableIntent.CloseFinder) }) { Text("Άκυρο") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun HeaderRow(vararg columns: Pair<String, Float>) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        for ((label, weight) in columns) {
            Text(
                label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(weight)
            )
        }
    }
}

@Composable
private fun TableRow(
    selected: Boolean,
    onClick: () -> Unit,
    vararg cells: Pair<String, Float>,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for ((value, weight) in cells) {
            Text(
                value, fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(weight)
            )
        }
    }
}
