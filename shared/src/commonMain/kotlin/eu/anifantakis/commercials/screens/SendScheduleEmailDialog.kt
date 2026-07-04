package eu.anifantakis.commercials.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.anifantakis.commercials.email.EmailActivityMonth
import eu.anifantakis.commercials.email.EmailCustomer
import eu.anifantakis.commercials.email.EmailHtmlPreview
import eu.anifantakis.commercials.email.EmailLogEntry
import eu.anifantakis.commercials.email.EmailSpot
import eu.anifantakis.commercials.core.domain.party_search.PartyKind
import eu.anifantakis.commercials.email.ScheduleEmailApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Staff action: email a party their month's schedule. ONE email is sent,
 * with one grid per spot (creative) so it's clear which spots aired when.
 *
 * Flow: the operator finds the party by SEARCH (thousands of customers;
 * substring match, debounced; the kind toggle picks the spots' CUSTOMER -
 * legacy cusID - or the contracts' paying TRADER - legacy traid, agencies
 * in "triangular" deals). Under the party: the YEARS it has airings, and
 * next to them the active MONTHS of the chosen year with counts. Picking a
 * month loads that month's spots; "Προεπισκόπιση" opens the exact email
 * HTML in a webview whose bottom bar carries the send button.
 */
@Composable
fun SendScheduleEmailDialog(
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val api = koinInject<ScheduleEmailApi>()

    var kind by remember { mutableStateOf(PartyKind.CUSTOMER) }
    var searchQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<EmailCustomer>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<EmailCustomer?>(null) }
    // The kind the selection was made under - toggling the radios afterwards
    // must not silently reinterpret an already selected party.
    var selectedKind by remember { mutableStateOf(PartyKind.CUSTOMER) }

    var activity by remember { mutableStateOf<List<EmailActivityMonth>>(emptyList()) }
    var loadingActivity by remember { mutableStateOf(false) }
    var selYear by remember { mutableStateOf<Int?>(null) }
    var selMonth by remember { mutableStateOf<Int?>(null) }

    var spots by remember { mutableStateOf<List<EmailSpot>>(emptyList()) }
    val includedSpots = remember { mutableStateMapOf<Long, Boolean>() }
    var recipient by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<EmailLogEntry>>(emptyList()) }

    var previewHtml by remember { mutableStateOf<String?>(null) }
    var loadingPreview by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }

    // Debounced search: retyping (or toggling the kind) restarts the effect,
    // cancelling the pending delay - the request fires 600ms after the last
    // keystroke, and never under 3 characters.
    LaunchedEffect(searchQuery, kind) {
        val q = searchQuery.trim()
        if (q.length < 3) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(600)
        searching = true
        api.search(q, kind)
            .onSuccess { results = it }
            .onFailure { error = it.message }
        searching = false
    }

    fun clearMonth() {
        selMonth = null
        spots = emptyList()
        includedSpots.clear()
    }

    fun selectMonth(year: Int, month: Int) {
        selMonth = month
        spots = emptyList()
        includedSpots.clear()
        scope.launch {
            api.spots(year, month, selected!!.code, selectedKind)
                .onSuccess { list -> spots = list; list.forEach { includedSpots[it.spotId] = true } }
                .onFailure { error = it.message }
        }
    }

    fun selectParty(c: EmailCustomer) {
        selected = c
        selectedKind = kind
        searchQuery = ""
        results = emptyList()
        recipient = c.email.orEmpty()
        activity = emptyList()
        selYear = null
        clearMonth()
        history = emptyList()
        loadingActivity = true
        scope.launch {
            api.activity(c.code, selectedKind)
                .onSuccess { list ->
                    activity = list
                    selYear = list.firstOrNull()?.year
                }
                .onFailure { error = it.message }
            loadingActivity = false
        }
        scope.launch {
            api.history(limit = 8, clientCode = c.code).onSuccess { history = it }
        }
    }

    fun switchKind(k: PartyKind) {
        if (kind != k) {
            kind = k
            results = emptyList()
        }
    }

    val chosenSpotIds = spots.map { it.spotId }.filter { includedSpots[it] == true }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Αποστολή προγραμματισμού") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done != null) {
                    Text(done!!, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    return@Column
                }

                // party search: customers (spot owners) or traders (contract
                // payers - agencies in triangular deals)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = kind == PartyKind.CUSTOMER,
                        onClick = { switchKind(PartyKind.CUSTOMER) },
                        enabled = !busy
                    )
                    Text(
                        "Πελάτες", fontSize = 13.sp,
                        modifier = Modifier.clickable(enabled = !busy) { switchKind(PartyKind.CUSTOMER) }
                    )
                    Spacer(Modifier.width(12.dp))
                    RadioButton(
                        selected = kind == PartyKind.TRADER,
                        onClick = { switchKind(PartyKind.TRADER) },
                        enabled = !busy
                    )
                    Text(
                        "Διαφημιστές", fontSize = 13.sp,
                        modifier = Modifier.clickable(enabled = !busy) { switchKind(PartyKind.TRADER) }
                    )
                }
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    label = {
                        Text(
                            if (kind == PartyKind.CUSTOMER) "Αναζήτηση πελάτη (3+ χαρακτήρες)"
                            else "Αναζήτηση διαφημιστή (3+ χαρακτήρες)"
                        )
                    },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (searching) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    }
                )
                if (results.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                        items(results, key = { it.code }) { c ->
                            Text(
                                "${c.name} — ${c.spotCount} σποτ, ${c.placementCount} μεταδόσεις",
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) { selectParty(c) }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                } else if (searchQuery.trim().length >= 3 && !searching) {
                    Text(
                        "Κανένα αποτέλεσμα",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                selected?.let { sel ->
                    Text(
                        (if (selectedKind == PartyKind.TRADER) "Διαφημιστής: " else "Πελάτης: ") + sel.name,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )

                    // year -> month drill-down over the party's airings
                    if (loadingActivity) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else if (activity.isEmpty()) {
                        Text("Καμία κίνηση", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(Modifier.fillMaxWidth().height(140.dp)) {
                            LazyColumn(Modifier.weight(0.35f).fillMaxHeight()) {
                                items(activity.map { it.year }.distinct(), key = { it }) { y ->
                                    val isSel = y == selYear
                                    Text(
                                        "$y",
                                        fontSize = 13.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !busy) { selYear = y; clearMonth() }
                                            .padding(vertical = 6.dp)
                                    )
                                }
                            }
                            LazyColumn(Modifier.weight(0.65f).fillMaxHeight()) {
                                items(
                                    activity.filter { it.year == selYear },
                                    key = { it.year * 100 + it.month }
                                ) { am ->
                                    val isSel = am.month == selMonth
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !busy) { selectMonth(am.year, am.month) }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            greekMonthName(am.month),
                                            fontSize = 13.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "${am.placements}", fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (selected != null && selMonth != null) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = recipient, onValueChange = { recipient = it },
                        label = { Text("Παραλήπτης (email)") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
                    )

                    // spot checklist - each becomes a table in the one email
                    Text(
                        "Σποτ που θα περιληφθούν (${chosenSpotIds.size}/${spots.size})",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                        items(spots, key = { it.spotId }) { spot ->
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                    includedSpots[spot.spotId] = !(includedSpots[spot.spotId] ?: true)
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = includedSpots[spot.spotId] ?: true,
                                    onCheckedChange = { includedSpots[spot.spotId] = it },
                                    enabled = !busy
                                )
                                Text(spot.description, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text("${spot.placements}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = note, onValueChange = { note = it },
                        label = { Text("Προσωπικό μήνυμα (προαιρετικό)") },
                        enabled = !busy, modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Θα σταλεί ΕΝΑ email με ${chosenSpotIds.size} πίνακες (ένας ανά σποτ).",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // audit trail: prior sends to this party (anti double-send)
                if (selected != null && history.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Ιστορικό αποστολών", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 96.dp)) {
                        items(history, key = { it.id }) { h ->
                            val failed = h.status != "SENT"
                            Text(
                                "${h.sentAt.take(16)} · ${monthShort(h.month)} ${h.year} · ${h.recipient} · ${h.sentBy}" +
                                    if (failed) " · ΑΠΕΤΥΧΕ" else "",
                                fontSize = 11.sp,
                                color = if (failed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            if (done != null) {
                TextButton(onClick = onDismiss) { Text("Κλείσιμο") }
            } else {
                TextButton(
                    enabled = !busy && !loadingPreview && selected != null && selYear != null &&
                        selMonth != null && recipient.isNotBlank() && chosenSpotIds.isNotEmpty(),
                    onClick = {
                        loadingPreview = true; error = null
                        scope.launch {
                            api.previewHtml(selYear!!, selMonth!!, selected!!.code, selectedKind, chosenSpotIds, note)
                                .onSuccess { previewHtml = it }
                                .onFailure { error = it.message }
                            loadingPreview = false
                        }
                    }
                ) {
                    if (loadingPreview) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text("Προεπισκόπιση")
                }
            }
        },
        dismissButton = {
            if (done == null) TextButton(enabled = !busy, onClick = onDismiss) { Text("Άκυρο") }
        }
    )

    // Fullscreen-ish preview: the report as the customer will receive it,
    // with the actual send action at the bottom - what you see is what goes.
    previewHtml?.let { html ->
        Dialog(
            onDismissRequest = { if (!busy) previewHtml = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Προεπισκόπιση — ${greekMonthName(selMonth ?: 0)} ${selYear ?: ""} — ${selected?.name ?: ""}",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    EmailHtmlPreview(html, Modifier.weight(1f).fillMaxWidth())
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val err = error
                        if (err != null) {
                            Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        } else {
                            Text(
                                "Προς: $recipient",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        TextButton(enabled = !busy, onClick = { previewHtml = null }) { Text("Πίσω") }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true; error = null
                                scope.launch {
                                    api.send(selYear!!, selMonth!!, selected!!.code, selectedKind, chosenSpotIds, recipient, note)
                                        .onSuccess { done = it; previewHtml = null }
                                        .onFailure { error = it.message }
                                    busy = false
                                }
                            }
                        ) {
                            if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            else Text("Αποστολή", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun greekMonthName(m: Int): String = listOf(
    "Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος", "Μάιος", "Ιούνιος",
    "Ιούλιος", "Αύγουστος", "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος",
).getOrElse(m - 1) { m.toString() }

private fun monthShort(m: Int): String = listOf(
    "Ιαν", "Φεβ", "Μαρ", "Απρ", "Μαϊ", "Ιουν", "Ιουλ", "Αυγ", "Σεπ", "Οκτ", "Νοε", "Δεκ"
).getOrElse(m - 1) { m.toString() }
