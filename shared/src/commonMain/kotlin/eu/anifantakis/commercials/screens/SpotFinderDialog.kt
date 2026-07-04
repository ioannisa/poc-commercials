package eu.anifantakis.commercials.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.anifantakis.commercials.email.EmailCustomer
import eu.anifantakis.commercials.email.PartyKind
import eu.anifantakis.commercials.email.ScheduleEmailApi
import eu.anifantakis.commercials.finder.FinderContractLine
import eu.anifantakis.commercials.finder.FinderSpot
import eu.anifantakis.commercials.finder.SpotFinderApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Everything the finder found - hoisted OUTSIDE the dialog (remembered by
 * the timetable screen), so reopening "Εύρεση" restores the previous
 * search, contract and spot selection until the operator clears it. The
 * grid's 'a' key adds [selectedSpot]; the toolbar dropdown switches among
 * [spots] (the selected contract line's spots), like the legacy console.
 */
@Stable
class SpotFinderState {
    var kind by mutableStateOf(PartyKind.CUSTOMER)
    var searchQuery by mutableStateOf("")
    var results by mutableStateOf<List<EmailCustomer>>(emptyList())
    var selectedParty by mutableStateOf<EmailCustomer?>(null)
    var selectedKind by mutableStateOf(PartyKind.CUSTOMER)
    var lines by mutableStateOf<List<FinderContractLine>>(emptyList())
    var selectedLine by mutableStateOf<FinderContractLine?>(null)
    var spots by mutableStateOf<List<FinderSpot>>(emptyList())
    var selectedSpot by mutableStateOf<FinderSpot?>(null)

    fun clear() {
        kind = PartyKind.CUSTOMER
        searchQuery = ""
        results = emptyList()
        selectedParty = null
        selectedKind = PartyKind.CUSTOMER
        lines = emptyList()
        selectedLine = null
        spots = emptyList()
        selectedSpot = null
    }
}

/**
 * The legacy "Εύρεση" Details Console, kept close to the original layout:
 * three stacked table sections - ΠΕΛΑΤΗΣ (search + the matches as a table
 * with Κωδικός/Επωνυμία/ΑΦΜ/Τηλέφωνο), ΣΥΜΒΟΛΑΙΑ ΠΕΛΑΤΗ (the contracts'
 * product lines; ERP product identity pending), ΜΗΝΥΜΑΤΑ (the line's spots
 * with Χρόνος/Αναλωμένα Spots/Secs) - and Επιλογή/Άκυρο bottom-right.
 * "Επιλογή" arms the grid's 'a' key with the selected spot.
 */
@Composable
fun SpotFinderDialog(
    state: SpotFinderState,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val searchApi = koinInject<ScheduleEmailApi>()
    val finderApi = koinInject<SpotFinderApi>()

    var searching by remember { mutableStateOf(false) }
    var loadingLines by remember { mutableStateOf(false) }
    var loadingSpots by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 600ms debounce, min 3 chars - same behaviour as the email dialog
    LaunchedEffect(state.searchQuery, state.kind) {
        val q = state.searchQuery.trim()
        if (q.length < 3) {
            state.results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(600)
        searching = true
        searchApi.search(q, state.kind)
            .onSuccess { state.results = it }
            .onFailure { error = it.message }
        searching = false
    }

    fun selectLine(line: FinderContractLine) {
        state.selectedLine = line
        state.spots = emptyList()
        state.selectedSpot = null
        loadingSpots = true
        scope.launch {
            finderApi.spots(line.lineId)
                .onSuccess { state.spots = it }
                .onFailure { error = it.message }
            loadingSpots = false
        }
    }

    fun selectParty(c: EmailCustomer) {
        state.selectedParty = c
        state.selectedKind = state.kind
        state.searchQuery = ""
        state.results = emptyList()
        state.lines = emptyList()
        state.selectedLine = null
        state.spots = emptyList()
        state.selectedSpot = null
        loadingLines = true
        scope.launch {
            finderApi.contracts(c.code, state.selectedKind)
                .onSuccess { state.lines = it }
                .onFailure { error = it.message }
            loadingLines = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                        selected = state.kind == PartyKind.CUSTOMER,
                        onClick = { state.kind = PartyKind.CUSTOMER }
                    )
                    Text(
                        "Πελάτες", fontSize = 13.sp,
                        modifier = Modifier.clickable { state.kind = PartyKind.CUSTOMER }
                    )
                    Spacer(Modifier.width(12.dp))
                    RadioButton(
                        selected = state.kind == PartyKind.TRADER,
                        onClick = { state.kind = PartyKind.TRADER }
                    )
                    Text(
                        "Διαφημιστές", fontSize = 13.sp,
                        modifier = Modifier.clickable { state.kind = PartyKind.TRADER }
                    )
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        value = state.searchQuery, onValueChange = { state.searchQuery = it },
                        label = { Text("Εύρεση (3+ χαρακτήρες)", fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.weight(1f),
                        trailingIcon = {
                            if (searching) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        }
                    )
                }
                HeaderRow(
                    "Κωδικός" to 0.14f, "Επωνυμία" to 0.44f,
                    "ΑΦΜ" to 0.14f, "Τηλέφωνο" to 0.14f, "Σποτ" to 0.14f,
                )
                // While a search runs the matches fill the table; the
                // chosen party stays pinned as its single (highlighted) row.
                val partyRows = state.results.ifEmpty { listOfNotNull(state.selectedParty) }
                LazyColumn(Modifier.fillMaxWidth().weight(0.24f)) {
                    items(partyRows, key = { it.code }) { c ->
                        val isSel = c.code == state.selectedParty?.code && state.results.isEmpty()
                        TableRow(
                            selected = isSel,
                            onClick = { selectParty(c) },
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
                if (loadingLines) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                LazyColumn(Modifier.fillMaxWidth().weight(0.3f)) {
                    items(state.lines, key = { it.lineId }) { line ->
                        TableRow(
                            selected = line.lineId == state.selectedLine?.lineId,
                            onClick = { selectLine(line) },
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
                if (loadingSpots) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                LazyColumn(Modifier.fillMaxWidth().weight(0.3f)) {
                    items(state.spots, key = { it.spotId }) { spot ->
                        TableRow(
                            selected = spot.spotId == state.selectedSpot?.spotId,
                            onClick = { state.selectedSpot = spot },
                            spot.description to 0.52f,
                            "${spot.durationSeconds}" to 0.16f,
                            "${spot.placements}" to 0.16f,
                            "${spot.totalSeconds}" to 0.16f,
                        )
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }

                // ═══ Επιλογή / Άκυρο (bottom-right, like the original) ══
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { state.clear() }) { Text("Καθαρισμός") }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = state.selectedSpot != null, onClick = onDismiss) {
                        Text("Επιλογή", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onDismiss) { Text("Άκυρο") }
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
