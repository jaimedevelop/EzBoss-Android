package pro.ezboss.mobile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import pro.ezboss.mobile.ui.theme.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pro.ezboss.mobile.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

private val financeKeys=listOf("date","estimate","poNumber","invoice","invoiceValue","payment","paymentMethod","paymentDate","serviceAddress","jobCompletionDate","completedFor","agent")
private val projectKeys=listOf("name","projectType","startDate","estimatedEndDate","actualEndDate","description","notes")
private val addressKeys=listOf("address","address2","city","state","zipCode")
private val projectStatuses = listOf("planning", "active", "on-hold", "completed", "invoiced", "cancelled")

@Composable fun BusinessRecordsScreen(model: AuthViewModel, destination: String, canWriteProjects: Boolean, canDeleteProjects: Boolean,
    onLinkedRecords: (projectId: String, section: String, archived: Boolean) -> Unit) {
    val project=destination=="projects"
    val path=if(project) "projects" else "finance-records"
    var creationRequestId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var appliedArchive by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var appliedSearch by rememberSaveable { mutableStateOf("") }
    var appliedFilter by rememberSaveable { mutableStateOf("all") }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    val scrollState = rememberScrollState()
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var clients by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var values by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var clientId by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("planning") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()
    val fields=if (project) projectKeys + addressKeys + listOf("completionPercentage", "tags") else financeKeys
    LaunchedEffect(reload, page, detailId) {
        busy = true
        error = null
        selected = null
        val id = detailId
        try {
            if (id != null) {
                detail = null
                detail = model.operation("projects/${Uri.encode(id)}") as JSONObject
            } else if (project) {
                rows = emptyList()
                val query = Uri.Builder().appendQueryParameter("page", page.toString())
                    .appendQueryParameter("search", appliedSearch)
                if (appliedFilter != "all") query.appendQueryParameter("status", appliedFilter)
                if (appliedArchive) query.appendQueryParameter("archived", "true")
                val payload = model.operation("projects/list${query.build()}") as JSONObject
                rows = operationRows(payload)
                hasMore = payload.getBoolean("hasMore")
            } else {
                rows = operationRows(model.operation(path))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (id == null) { rows = emptyList(); hasMore = false }
            error = if (id != null && (e as? ApiException)?.statusCode == 404) "Project not found in this company."
                else e.message ?: "Could not load. Retry."
        } finally {
            busy = false
        }
    }
    LaunchedEffect(detailId, selected != null, page) { scrollState.scrollTo(0) }
    fun selectClient(client: JSONObject, forNewProject: Boolean) {
        clientId = client.optString("id")
        if (!forNewProject) return
        val prefix = if (client.optBoolean("billingEqualToService", true)) "billing" else "service"
        val suffixes = listOf("Address", "Address2", "City", "State", "ZipCode")
        values = values + addressKeys.zip(suffixes).associate { (field, suffix) ->
            field to client.optString(prefix + suffix).takeUnless { it == "null" }.orEmpty()
        }
    }
    fun edit(row:JSONObject) {
        if (project && !row.has("id")) creationRequestId = UUID.randomUUID().toString()
        selected=row;message=null
        val data=if(project)row else row.optJSONObject("data") ?: JSONObject()
        values=fields.associateWith{ key->(if(project && key in addressKeys)row.optJSONObject("serviceAddress")?.optString(key) else data.optString(key)).orEmpty().takeUnless{it=="null"}.orEmpty() }
        if (project) {
            values = values + ("tags" to projectTags(row).joinToString("\n"))
            if (!row.has("id")) values = values + ("completionPercentage" to "0")
        }
        clientId=row.optString("clientId","");status=row.optString("status","planning")
    }
    fun openProjectEditor(row: JSONObject) = scope.launch {
        busy = true
        error = null
        try {
            val groups = model.operation("clients") as JSONObject
            clients = groups.keys().asSequence().flatMap { key ->
                val group = groups.getJSONArray(key)
                (0 until group.length()).map { group.getJSONObject(it) }
            }.toList()
            edit(row)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load clients. Retry."
        } finally {
            busy = false
        }
    }
    fun closeDetail() { detail = null; detailId = null; message = null }
    fun changeLifecycle(row: JSONObject, archived: Boolean?) = scope.launch {
        if (busy) return@launch
        busy = true
        error = null
        message = null
        try {
            val id = Uri.encode(row.getString("id"))
            val body = JSONObject().put("version", row.getString("version"))
            if (archived == null) {
                model.operation("projects/$id", "DELETE", body)
                closeDetail()
                message = "Project deleted."
            } else {
                model.operation("projects/$id/archive", "POST", body.put("archived", archived))
                message = if (archived) "Project archived." else "Project restored."
            }
            reload++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = (e as? ApiException)?.takeIf { it.statusCode == 409 }
                ?.let { "The project changed or has linked records. Reload it before trying again." }
                ?: e.message ?: "The project action could not be confirmed."
        } finally {
            busy = false
        }
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Permanently delete this project?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    detail?.let { changeLifecycle(it, null) }
                }) { Text("Delete project") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
    BackHandler(enabled = detailId != null || (project && selected != null)) {
        if (!busy) {
            if (selected != null) reload++ else closeDetail()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        PageHeading(if(project) "Projects" else "Finances", if(project) "Projects, clients and progress" else "Records and linked payment summaries")
        if(busy)EzBossLoadingIndicator()
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)};message?.let{Text(it)}
        if (selected == null && detailId != null) {
            TextButton(onClick = { closeDetail() }, enabled = !busy) { Text("Back to projects") }
            detail?.let { row ->
                Text("Overview", style = MaterialTheme.typography.titleLarge)
                Text(row.optString("name"), style = MaterialTheme.typography.titleMedium)
                Text("${row.optString("projectNumber")} · ${row.optString("status")}")
                Text("Client: ${row.optString("clientName")}")
                Text("Start: ${row.optString("startDate")}")
                Text("Estimated end: ${row.optString("estimatedEndDate")}")
                if (!row.isNull("actualEndDate")) Text("Actual end: ${row.optString("actualEndDate")}")
                row.optString("projectType").takeIf { it.isNotBlank() && it != "null" }?.let { Text("Project type: $it") }
                val tags = projectTags(row)
                if (tags.isNotEmpty()) Text("Tags: " + tags.joinToString(", "))
                Text("Progress: ${row.optString("completionPercentage")}%")
                projectMoney(row, "currentBudget")?.let { Text("Budget: $it") }
                projectMoney(row, "actualCost")?.let { Text("Spent: $it") }
                row.optJSONObject("serviceAddress")?.let { address ->
                    Text("Site: " + addressKeys.map { address.optString(it) }.filter { it.isNotBlank() }.joinToString(", "))
                }
                listOf("description", "notes").forEach { key ->
                    row.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { Text(it) }
                }
                val archived = !row.isNull("archivedAt")
                TextButton(onClick = { onLinkedRecords(row.getString("id"), "documents", archived) }, enabled = !busy) { Text("Documents") }
                TextButton(onClick = { onLinkedRecords(row.getString("id"), "work-orders", archived) }, enabled = !busy) { Text("Work orders") }
                TextButton(onClick = { onLinkedRecords(row.getString("id"), "purchasing", archived) }, enabled = !busy) { Text("Purchase orders") }
                if (archived) Text("Archived")
                if (canWriteProjects && !archived) Button(onClick = { openProjectEditor(row) }, enabled = !busy) { Text("Edit project") }
                if (canWriteProjects) TextButton(onClick = { changeLifecycle(row, !archived) }, enabled = !busy) {
                    Text(if (archived) "Restore project" else "Archive project")
                }
                if (canDeleteProjects) TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Delete project") }
                if (error != null) TextButton(onClick = { reload++ }, enabled = !busy) { Text("Reload project") }
            }
            if (!busy && detail == null) TextButton(onClick = { reload++ }) { Text("Retry") }
        } else if (selected == null && project) {
            OutlinedTextField(search, { search = it }, label = { Text("Search by name, number, or client") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            var statusesExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { statusesExpanded = true }) {
                    Text(if (filter == "all") "All statuses" else filter)
                }
                DropdownMenu(expanded = statusesExpanded, onDismissRequest = { statusesExpanded = false }) {
                    (listOf("all") + projectStatuses).forEach { value ->
                        DropdownMenuItem(text = { Text(if (value == "all") "All statuses" else value) }, onClick = {
                            filter = value
                            statusesExpanded = false
                        })
                    }
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = showArchived, onCheckedChange = { showArchived = it })
                Text("Archived projects")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    appliedSearch = search
                    appliedFilter = filter
                    appliedArchive = showArchived
                    page = 1
                    reload++
                }, enabled = !busy && search.length <= 500) { Text("Apply filters") }
                TextButton(onClick = { reload++ }, enabled = !busy) { Text("Refresh / retry") }
            }
            if (canWriteProjects) Button(onClick = { openProjectEditor(JSONObject()) }, enabled = !busy) { Text("Create project") }
            rows.forEach { row ->
                WorkspaceRecord(row.optString("name"), listOf("projectNumber", "clientName", "status").joinToString(" · ") { row.optString(it) }) {
                    if (!busy) { message = null; detailId = row.getString("id") }
                }
            }
            if (rows.isEmpty() && !busy && error == null) Text("No projects found.")
            if (page > 1 || hasMore) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { page-- }, enabled = !busy && page > 1) { Text("Previous") }
                Text("Page $page")
                TextButton(onClick = { page++ }, enabled = !busy && hasMore) { Text("Next") }
            }
        } else if (selected == null) {
            TextButton(onClick = { reload++ }, enabled = !busy) { Text("Refresh / retry") }
            Button(onClick = { edit(JSONObject().put("id", UUID.randomUUID().toString()).put("data", JSONObject())) }, enabled = !busy) {
                Text("Create manual record")
            }
            rows.forEach { row ->
                WorkspaceRecord(row.optJSONObject("data")?.optString("invoice")?.takeIf { it.isNotBlank() } ?: row.optString("id"), row.optString("status", "")) { edit(row) }
            }
            if (rows.isEmpty() && !busy && error == null) Text("No records in this company.")
        } else {
            val row=selected!!
            if(project) {
                Text("Client: "+clients.firstOrNull{it.optString("id")==clientId}?.optString("name").orEmpty())
                clients.forEach{client->TextButton(onClick={selectClient(client, !row.has("id"))},enabled=!busy){Text(client.optString("name"))}}
                Text("Status: $status")
                projectStatuses.chunked(2).forEach{group->Row{group.forEach{v->TextButton(onClick={status=v},enabled=!busy){Text(v)}}}}
            } else {
                val summary=row.optJSONObject("paymentSummary")
                if(summary!=null)Text("Ledger: "+summary.optString("paid")+" / "+summary.optString("total")+" · "+summary.optString("status"))
                Text("Manual record fields do not alter the invoice payment ledger. Record payments or refunds on the document.")
            }
            fields.forEach{key->OutlinedTextField(values[key].orEmpty(),{values=values+(key to it)},label={Text(if (key == "tags") "Tags (one per line)" else key.replace(Regex("([a-z])([A-Z])"),"$1 $2"))},modifier=Modifier.fillMaxWidth(),enabled=!busy)}
            Button(enabled=!busy,onClick={scope.launch{
                busy=true;error=null;message=null
                try {
                    if(project) {
                        val body=JSONObject();projectKeys.forEach{body.put(it,values[it].orEmpty())};body.put("clientId",clientId).put("status",status)
                        val address=JSONObject();addressKeys.forEach{address.put(it,values[it].orEmpty())};body.put("serviceAddress",address)
                        val actualEnd = values["actualEndDate"].orEmpty().trim()
                        body.put("actualEndDate", actualEnd.ifEmpty { null } ?: JSONObject.NULL)
                        val completion = values["completionPercentage"]?.toDoubleOrNull()
                        if (completion == null || completion !in 0.0..100.0) {
                            error = "Enter a completion percentage from 0 to 100."
                            return@launch
                        }
                        body.put("completionPercentage", completion)
                        val tagText = values["tags"].orEmpty()
                        if (tagText != projectTags(row).joinToString("\n")) {
                            body.put("tags", JSONArray(tagText.lines().map { it.trim() }.filter { it.isNotEmpty() }))
                        }
                        if (row.has("id")) body.put("version", row.getString("version"))
                        else body.put("creationRequestId", creationRequestId)
                        model.operation(path+(if(row.has("id"))"/"+row.getString("id") else ""),if(row.has("id"))"PATCH" else "POST",body)
                    } else {
                        val data=JSONObject();financeKeys.forEach{data.put(it,values[it].orEmpty())}
                        if(row.has("version"))model.operation(path+"/"+row.getString("id"),"PUT",JSONObject().put("version",row.getInt("version")).put("data",data))
                        else model.operation(path,"POST",JSONObject().put("records",JSONArray().put(JSONObject().put("id",row.getString("id")).put("data",data))))
                    }
                    if (project && !row.has("id")) {
                        appliedArchive = false
                        showArchived = false
                        page = 1
                    }
                    message="Saved";reload++
                } catch (e: CancellationException) {
                    throw e
                } catch(e:Exception){error=e.message ?: "Save was not confirmed. Reload before retrying."} finally{busy=false}
            }}){Text("Save")}
            TextButton(onClick={selected=null;reload++},enabled=!busy){Text("Discard / reload")}
        }
    }
}

private fun projectMoney(row: JSONObject, key: String): String? =
    (row.opt(key) as? Number)?.let { NumberFormat.getCurrencyInstance(Locale.US).format(it) }

private fun projectTags(row: JSONObject): List<String> = row.optJSONArray("tags")?.let { tags ->
    (0 until tags.length()).map { tags.getString(it) }
}.orEmpty()
