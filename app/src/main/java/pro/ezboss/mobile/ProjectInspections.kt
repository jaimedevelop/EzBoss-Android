package pro.ezboss.mobile

import android.app.DatePickerDialog
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pro.ezboss.mobile.ui.theme.WorkspaceRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Composable
fun ProjectInspectionsScreen(
    model: AuthViewModel, projectId: String, canAdd: Boolean,
    onOpenWorkOrder: (String) -> Unit, onOpenNotes: (String) -> Unit,
) {
    val path = "projects/${Uri.encode(projectId)}/inspections"
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val workOrders = rememberProjectWorkOrders(model, projectId)
    var rows by remember(path) { mutableStateOf<List<JSONObject>?>(null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var saveError by remember(path) { mutableStateOf<String?>(null) }
    var revision by remember(path) { mutableIntStateOf(0) }
    var saving by remember(path) { mutableStateOf(false) }
    var requestId by rememberSaveable(path) { mutableStateOf<String?>(null) }
    var location by rememberSaveable(path) { mutableStateOf("") }
    var date by rememberSaveable(path) { mutableStateOf("") }
    var requiredItems by rememberSaveable(path) { mutableStateOf("") }
    var editingId by rememberSaveable(path) { mutableStateOf<String?>(null) }
    val validDraft = requiredItems.length <= 4000 && (editingId != null || location.trim().length in 1..250)

    LaunchedEffect(path, revision) {
        loadError = null
        try {
            rows = operationRows(model.operation(path))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            loadError = error.message ?: "Could not load inspections."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Inspections", style = MaterialTheme.typography.headlineSmall) }
        if (canAdd && rows != null) {
            item {
                val id = requestId
                if (id == null) {
                    Button(onClick = {
                        editingId = null
                        requiredItems = ""
                        location = ""
                        date = LocalDate.now().toString()
                        saveError = null
                        requestId = UUID.randomUUID().toString()
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add Upcoming Inspection")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = location, onValueChange = { location = it },
                            label = { Text("Location") }, singleLine = true, enabled = !saving && editingId == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(enabled = !saving && editingId == null, onClick = {
                            val selected = LocalDate.parse(date)
                            DatePickerDialog(context, { _, year, month, day ->
                                date = LocalDate.of(year, month + 1, day).toString()
                            }, selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
                        }) { Text("Date: $date") }
                        OutlinedTextField(
                            value = requiredItems, onValueChange = { requiredItems = it },
                            label = { Text("Required Items") }, minLines = 3, maxLines = 8, enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = validDraft && !saving, onClick = {
                                if (saving) return@Button
                                saving = true
                                saveError = null
                                val inspectionId = editingId
                                val endpoint = if (inspectionId == null) path else "$path/${Uri.encode(inspectionId)}"
                                val body = JSONObject().put("requiredItems", requiredItems)
                                if (inspectionId == null) {
                                    body.put("id", id).put("location", location.trim()).put("scheduledDate", date)
                                }
                                scope.launch {
                                    try {
                                        val inspection = model.operation(endpoint, if (inspectionId == null) "POST" else "PATCH", body) as JSONObject
                                        rows = (rows.orEmpty().filter { it.getString("id") != inspection.getString("id") } + inspection)
                                            .sortedWith(compareBy({ it.getString("scheduledDate") }, { it.getString("id") }))
                                        requestId = null
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        saveError = error.message ?: "Could not save the inspection."
                                    } finally {
                                        saving = false
                                    }
                                }
                            }) {
                                Text(when {
                                    saving -> "Saving…"
                                    editingId != null -> "Save Required Items"
                                    else -> "Save Inspection"
                                })
                            }
                            TextButton(enabled = !saving, onClick = { requestId = null }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
        val inspections = rows
        val error = loadError
        when {
            error != null -> item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { revision++ }) { Text("Retry") }
            }
            inspections == null -> item { CircularProgressIndicator() }
            inspections.isEmpty() -> item { Text("No inspections scheduled.") }
            else -> items(inspections, key = { it.getString("id") }) { inspection ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(inspection.getString("location"), style = MaterialTheme.typography.titleMedium)
                        Text(inspection.getString("scheduledDate"), style = MaterialTheme.typography.bodyMedium)
                        val items = inspection.optString("requiredItems")
                        if (items.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Required Items", style = MaterialTheme.typography.titleSmall)
                            Text(items)
                        }
                        if (canAdd && requestId == null) {
                            TextButton(onClick = {
                                val id = inspection.getString("id")
                                editingId = id
                                requestId = id
                                location = inspection.getString("location")
                                date = inspection.getString("scheduledDate")
                                requiredItems = items
                                saveError = null
                                scope.launch { listState.scrollToItem(0) }
                            }) { Text(if (items.isEmpty()) "Add Required Items" else "Edit Required Items") }
                        }
                        TextButton(onClick = { onOpenNotes(inspection.getString("id")) }) { Text("Update Notes") }
                    }
                }
            }
        }
        projectWorkOrders(workOrders, onOpenWorkOrder)
    }
}

@Composable
fun ProjectUpdateNotesScreen(
    model: AuthViewModel, path: String, title: String, canAdd: Boolean,
    punchList: Boolean = false, projectId: String? = null, onOpenWorkOrder: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val workOrders = if (projectId != null) rememberProjectWorkOrders(model, projectId) else null
    var rows by remember(path) { mutableStateOf<List<JSONObject>?>(null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var saveError by remember(path) { mutableStateOf<String?>(null) }
    var statusError by remember(path) { mutableStateOf<String?>(null) }
    var addingItem by rememberSaveable(path) { mutableStateOf(false) }
    var revision by remember(path) { mutableIntStateOf(0) }
    var saving by remember(path) { mutableStateOf(false) }
    var requestId by rememberSaveable(path) { mutableStateOf<String?>(null) }
    var note by rememberSaveable(path) { mutableStateOf("") }

    LaunchedEffect(path, revision) {
        loadError = null
        try {
            rows = operationRows(model.operation(path))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            loadError = error.message ?: if (punchList) "Could not load the punch-list." else "Could not load update notes."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(title, style = MaterialTheme.typography.headlineSmall) }
        val notes = rows
        val error = loadError
        when {
            error != null -> item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { revision++ }) { Text("Retry") }
            }
            notes == null -> item { CircularProgressIndicator() }
            else -> {
                if (punchList) item {
                    val prepItems = notes.filter { it.has("completed") }
                    Text(if (prepItems.isEmpty()) "No prep items yet."
                        else "${prepItems.count { !it.getBoolean("completed") }} of ${prepItems.size} items open")
                    statusError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                if (canAdd) item {
                    val id = requestId
                    if (id == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (punchList) Button(enabled = !saving, onClick = {
                                addingItem = true
                                requestId = UUID.randomUUID().toString()
                                note = ""
                                saveError = null
                            }) { Text("Add Prep Item") }
                            TextButton(enabled = !saving, onClick = {
                                addingItem = false
                                requestId = UUID.randomUUID().toString()
                                note = ""
                                saveError = null
                            }) { Text("Add Update Note") }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = note, onValueChange = { note = it }, label = { Text(if (addingItem) "What needs to be done before inspection?" else "Update note") },
                                minLines = 3, maxLines = 8, enabled = !saving, modifier = Modifier.fillMaxWidth(),
                            )
                            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !saving && note.isNotBlank() && note.length <= 4000, onClick = {
                                    if (saving) return@Button
                                    saving = true
                                    saveError = null
                                    val body = JSONObject().put("id", id).put("text", note)
                                    if (addingItem) body.put("kind", "item")
                                    scope.launch {
                                        try {
                                            val saved = model.operation(path, "POST", body) as JSONObject
                                            rows = listOf(saved) + rows.orEmpty().filter { it.getString("id") != saved.getString("id") }
                                            requestId = null
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: Exception) {
                                            saveError = error.message ?: if (addingItem) "Could not save the punch-list item." else "Could not save the update note."
                                        } finally {
                                            saving = false
                                        }
                                    }
                                }) { Text(if (saving) "Saving…" else if (addingItem) "Save Item" else "Save Note") }
                                TextButton(enabled = !saving, onClick = { requestId = null }) { Text("Cancel") }
                            }
                        }
                    }
                }
                if (!punchList && notes.isEmpty()) item { Text("No update notes yet.") }
                items(notes, key = { it.getString("id") }) { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(row.getString("text"))
                            if (punchList && row.has("completed")) {
                                val completed = row.getBoolean("completed")
                                Text(if (completed) "Ready" else "Open", style = MaterialTheme.typography.bodySmall)
                                if (canAdd) TextButton(enabled = !saving, onClick = {
                                    if (saving) return@TextButton
                                    saving = true
                                    statusError = null
                                    scope.launch {
                                        try {
                                            val itemId = row.getString("id")
                                            val body = JSONObject().put("completed", !completed)
                                            val updated = model.operation("$path/$itemId", "PATCH", body) as JSONObject
                                            rows = rows?.map { if (it.getString("id") == itemId) updated else it }
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: Exception) {
                                            statusError = error.message ?: "Could not update the item."
                                        } finally {
                                            saving = false
                                        }
                                    }
                                }) { Text(if (completed) "Reopen" else "Mark Ready") }
                            } else {
                                val createdAt = row.getString("createdAt")
                                val date = runCatching { Instant.parse(createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toString() }
                                    .getOrDefault(createdAt.take(10))
                                Text(if (punchList) "Update note · $date" else date, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (workOrders != null) projectWorkOrders(workOrders, onOpenWorkOrder)
    }
}

private class ProjectWorkOrders {
    var rows by mutableStateOf<List<JSONObject>?>(null)
    var error by mutableStateOf<String?>(null)
    var revision by mutableIntStateOf(0)
}

@Composable
private fun rememberProjectWorkOrders(model: AuthViewModel, projectId: String): ProjectWorkOrders {
    val state = remember(projectId) { ProjectWorkOrders() }
    LaunchedEffect(projectId, state.revision) {
        state.error = null
        val query = Uri.Builder().appendQueryParameter("projectId", projectId).build()
        try {
            state.rows = operationRows(model.operation("work-orders$query"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.error = error.message ?: "Could not load work orders."
        }
    }
    return state
}

private fun LazyListScope.projectWorkOrders(state: ProjectWorkOrders, onOpen: (String) -> Unit) {
    item { Text("Work Orders", style = MaterialTheme.typography.titleLarge) }
    val error = state.error
    val rows = state.rows
    when {
        error != null -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { state.revision++ }) { Text("Retry") }
        }
        rows == null -> item { CircularProgressIndicator() }
        rows.isEmpty() -> item { Text("No linked work orders.") }
        else -> items(rows, key = { "work-order-" + it.getString("id") }) { row ->
            val subtitle = listOf(row.optString("customerName"), row.optString("status"))
                .filter { it.isNotBlank() }.joinToString(" · ")
            WorkspaceRecord(row.getString("woNumber"), subtitle) { onOpen(row.getString("id")) }
        }
    }
}
