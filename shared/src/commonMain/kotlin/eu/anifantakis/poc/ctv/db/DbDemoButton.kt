package eu.anifantakis.poc.ctv.db

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

@Composable
fun DbDemoButton(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DbUser?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Button(
        modifier = modifier,
        onClick = {
            loading = true
            error = null
            result = null
            showDialog = true
            scope.launch {
                try {
                    result = fetchDbUser()
                } catch (t: Throwable) {
                    error = t.message ?: t::class.simpleName ?: "Unknown error"
                } finally {
                    loading = false
                }
            }
        }
    ) {
        Text("Get Data")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("User from DB") },
            text = {
                when {
                    loading -> Text("Loading…")
                    error != null -> Text("Error: $error")
                    result != null -> Column {
                        Text("Username: ${result!!.username}")
                        Text("Password: ${result!!.password}")
                    }
                    else -> Text("No data")
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("OK") }
            }
        )
    }
}
