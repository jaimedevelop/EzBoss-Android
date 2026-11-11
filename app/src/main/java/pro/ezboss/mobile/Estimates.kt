package pro.ezboss.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import pro.ezboss.mobile.ui.theme.*
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pro.ezboss.mobile.data.*
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.json.JSONObject
import org.json.JSONArray

private val estimateRowsSaver = listSaver<List<EstimateListItem>, String>(
    save = { rows -> rows.map { row -> JSONArray().put(row.id).put(row.number).put(row.customer).put(row.estimateState).put(row.clientState).put(row.total).toString() } },
    restore = { saved -> saved.mapNotNull { encoded -> runCatching { JSONArray(encoded).let { EstimateListItem(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.optString(4).takeIf { state -> state.isNotBlank() && state != "null" }, it.getDouble(5)) } }.getOrNull() } },
)

private val listStates = listOf("All", "Draft", "Estimate", "Change order", "Invoice")
private val clientStates = listOf("Any", "Sent", "Viewed", "Accepted", "Denied", "On hold", "Expired", "No client status")
private fun money(value: Double) = NumberFormat.getCurrencyInstance(Locale.US).format(value)
private fun label(value: String) = value.split('-', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

@Composable fun EstimatesListScreen(model: AuthViewModel, initialFilter: String = "All", projectId: String? = null, canCreateProjectDraft: Boolean = false, onOpen: (String) -> Unit) {
    var creating by rememberSaveable(projectId) { mutableStateOf(false) }
    if (creating) {
        DraftEditorScreen(model, null, onClose = { creating = false }, onSaved = { creating = false; onOpen(it) }, projectId = projectId)
        return
    }
    var search by rememberSaveable { mutableStateOf("") }
    var estimateFilter by rememberSaveable { mutableStateOf(initialFilter) }
    var clientFilter by rememberSaveable { mutableStateOf("Any") }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var rows by rememberSaveable(stateSaver = estimateRowsSaver) { mutableStateOf(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun load(reset: Boolean) {
        val requestGeneration = generation
        val offset = if (reset) 0 else rows.size
        if (reset) { loading = true; error = null; denied = false } else loadingMore = true
        scope.launch {
            try {
                val page = model.estimatePage(search, estimateStateQuery(estimateFilter), clientStateQuery(clientFilter), offset, projectId)
                if (requestGeneration == generation) {
                    rows = if (reset) page.items else rows + page.items
                    canLoadMore = page.canLoadMore
                    error = null
                }
            } catch (e: ApiException) {
                if (requestGeneration == generation) { error = e.message; denied = e.statusCode == 403 }
            } catch (e: Exception) {
                if (requestGeneration == generation) error = e.message ?: "Could not load estimates. Check your connection and retry."
            } finally { if (requestGeneration == generation) { loading = false; loadingMore = false } }
        }
    }

    fun criteriaChanged() {
        generation++
        searchJob?.cancel()
        rows = emptyList(); canLoadMore = false; loading = true; error = null
        searchJob = scope.launch { delay(350); load(true) }
    }
    LaunchedEffect(Unit) { load(true) }

    Column(Modifier.fillMaxSize().background(EzBossDesign.Canvas)) {
        PageHeading(if (projectId == null) "Estimates" else "Project documents", "Estimates, invoices and change orders")
        if (projectId == null || canCreateProjectDraft) {
            TextButton(onClick = { creating = true }) { Text("Create estimate draft") }
        }
        Row(Modifier.fillMaxWidth().padding(EzBossDesign.PagePadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = search, onValueChange = { search = it; criteriaChanged() },
                modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Search customer", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                shape = EzBossDesign.ControlShape,
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White),
            )
            OutlinedIconButton(onClick = { filtersOpen = true }, modifier = Modifier.size(52.dp), shape = EzBossDesign.ControlShape) {
                Icon(Icons.Default.FilterList, "Filter estimates")
            }
        }
        if (estimateFilter != "All" || clientFilter != "Any") Text("${estimateFilter.takeIf { it != "All" } ?: "Any type"} · ${clientFilter.takeIf { it != "Any" } ?: "Any client status"}", Modifier.fillMaxWidth().background(Color.White).padding(start = 16.dp, bottom = 8.dp), color = EzBossDesign.Muted, fontSize = 12.sp)
        HorizontalDivider(color = EzBossDesign.Border)
        when {
            loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EzBossLoadingIndicator() }
            error != null && rows.isEmpty() -> StateMessage(if (denied) "Access denied" else "Couldn’t load estimates", error!!, if (denied) null else "Retry") { generation++; load(true) }
            rows.isEmpty() -> StateMessage(if (search.isNotBlank() || estimateFilter != "All" || clientFilter != "Any") "No matching estimates" else "No estimates yet", if (search.isNotBlank() || estimateFilter != "All" || clientFilter != "Any") "Try changing your search or filters." else "Estimates will appear here when they are available.", null) {}
            else -> LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(EzBossDesign.PagePadding), horizontalArrangement = Arrangement.spacedBy(EzBossDesign.Gap), verticalArrangement = Arrangement.spacedBy(EzBossDesign.Gap), modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { item -> EstimateCard(item) { onOpen(item.id) } }
                if (canLoadMore) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (loadingMore) EzBossLoadingIndicator(Modifier.width(100.dp), page = false)
                        else if (error != null) TextButton(onClick = { load(false) }) { Text("Couldn’t load more · Retry") }
                        else OutlinedButton(onClick = { load(false) }, shape = EzBossDesign.ControlShape) { Text("Load 25 more") }
                    }
                }
            }
        }
    }
    if (filtersOpen) AlertDialog(onDismissRequest = { filtersOpen = false }, title = { Text("Filter estimates") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Document type", fontWeight = FontWeight.SemiBold)
            listStates.forEach { value -> FilterChoice(value, estimateFilter == value) { estimateFilter = value } }
            Spacer(Modifier.height(4.dp)); Text("Client status", fontWeight = FontWeight.SemiBold)
            clientStates.forEach { value -> FilterChoice(value, clientFilter == value) { clientFilter = value } }
        }
    }, confirmButton = { TextButton(onClick = { filtersOpen = false; criteriaChanged() }) { Text("Apply") } }, dismissButton = { TextButton(onClick = { estimateFilter = "All"; clientFilter = "Any"; filtersOpen = false; criteriaChanged() }) { Text("Clear") } })
}

@Composable private fun FilterChoice(text: String, selected: Boolean, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick); Text(text) } }

@Composable private fun EstimateCard(item: EstimateListItem, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = EzBossDesign.CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, EzBossDesign.Border), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(EzBossDesign.PagePadding), verticalArrangement = Arrangement.spacedBy(EzBossDesign.Gap)) {
            StatusBadge(label(item.estimateState), item.estimateState)
            Text(item.number, color = EzBossDesign.Link, style = MaterialTheme.typography.titleMedium)
            Text(item.customer.ifBlank { "Customer" }, color = EzBossDesign.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            HorizontalDivider()
            StatusBadge(item.clientState?.let(::label) ?: "No client status", item.clientState)
            Text(money(item.total), color = EzBossDesign.Ink, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable private fun StateMessage(title: String, message: String, action: String?, onAction: () -> Unit) { Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp); Spacer(Modifier.height(8.dp)); Text(message, color = EzBossDesign.Muted); if (action != null) { Spacer(Modifier.height(12.dp)); Button(onClick = onAction) { Text(action) } } } }

@Composable fun EstimateDetailScreen(model: AuthViewModel, id: String, canWriteEstimates: Boolean, initialClaims: Boolean, onOpenClaimJob: (Int) -> Unit, onReturn: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var approvalOpen by remember { mutableStateOf(false) }
    var approver by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var approvalDate by rememberSaveable { mutableStateOf("") }
    var acting by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var revokeWarning by remember { mutableStateOf(false) }
    var retry by remember(id) { mutableIntStateOf(0) }
    fun act(action: String, body: JSONObject = JSONObject()) { scope.launch {
        acting = true; actionMessage = null
        try {
            val result = model.documentAction(id, action, body)
            if (action == "issue-invoice") retry++
            if (action == "share-link") {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(result.getString("url"))))
            }
            if (action == "record-approval") { approver = ""; reason = ""; approvalDate = "" }
            actionMessage = when(action) { "send-email" -> "Email accepted for delivery; verify inbox receipt."; "issue-invoice" -> "Invoice created. Open it from Invoices."; "revoke-links" -> "Customer links revoked."; "record-approval" -> "Client approval recorded. Refresh to verify collection status."; else -> "Customer link prepared." }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { actionMessage = "Action was not confirmed. Refresh before retrying." }
        finally { acting = false }
    } }
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        DraftEditorScreen(model, id, onClose = { editing = false }, onSaved = { editing = false })
        return
    }
    var estimate by remember(id) { mutableStateOf<EstimateDetail?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var denied by remember(id) { mutableStateOf(false) }
    var tab by rememberSaveable(id) { mutableStateOf(if (initialClaims) "Claims" else "Estimate") }
    var loading by remember(id) { mutableStateOf(true) }
    LaunchedEffect(id, retry) {
        loading = true; error = null
        try { estimate = model.estimate(id) }
        catch (e: ApiException) { error = e.message; denied = e.statusCode == 403 }
        catch (e: Exception) { error = e.message ?: "Could not load this estimate. Retry." }
        finally { loading = false }
    }
    LaunchedEffect(id, tab, loading) {
        if (tab != "Timeline" || loading || estimate == null) return@LaunchedEffect
        while (true) {
            try {
                if (!acting) {
                    val latest = model.estimate(id)
                    if (!acting) estimate = latest
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the displayed timeline if a background refresh fails.
            }
            delay(10_000)
        }
    }
    Column(Modifier.fillMaxSize().background(EzBossDesign.Canvas)) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onReturn) { Icon(Icons.Default.ArrowBack, "Return to estimates") }; Text("Back to estimates", Modifier.clickable(onClick = onReturn).padding(8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        HorizontalDivider()
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EzBossLoadingIndicator() }
        else if (estimate == null) StateMessage(if (denied) "Access denied" else "Estimate unavailable", error ?: "This estimate may have been deleted.", if (denied) null else "Retry") { retry++ }
        else {
            val value = estimate!!
            val tabs = estimateDashboardTabs(value.estimateState == "change-order")
            ScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.label == tab }.coerceAtLeast(0), edgePadding = 0.dp, containerColor = Color.White) {
                tabs.forEach { contract ->
                    Tab(selected = tab == contract.label, onClick = { tab = contract.label }, text = {
                        Text(if (contract.label == "Estimate" && value.estimateState == "invoice") "Invoice" else contract.label)
                    })
                }
            }
            if (tab == "Estimate") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { act("send-email") }, enabled = !acting) { Text("Send / resend") }
                    TextButton(onClick = { act("share-link") }, enabled = !acting) { Text("Customer view / checkout") }
                    if (value.estimateState in listOf("draft", "estimate")) TextButton(onClick = { act("issue-invoice") }, enabled = !acting) { Text("Issue invoice") }
                    TextButton(onClick = { revokeWarning = true }, enabled = !acting) { Text("Revoke links") }
                    TextButton(onClick = { retry++ }, enabled = !acting) { Text("Refresh") }
                }
                if (value.collectionHold) {
                    Text("Collection is on hold pending approval of revision ${value.currentRevision}.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                }
                if (value.estimateState != "invoice" && (value.collectionHold || value.clientState != "accepted")) TextButton(onClick = { approvalOpen = true }, enabled = !acting) { Text("Record off-app client approval") }
                TextButton(onClick = { editing = true }) { Text("Edit document") }
            }
            actionMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
            if (tab == "Client View") TextButton(onClick = { act("share-link") }, enabled = !acting) { Text("Open customer view / checkout") }
            when (tab) {
                "Estimate" -> EstimateInformation(value)
                "Client View" -> ClientPreview(value)
                "Claims" -> EstimateClaimsTab(model, "estimates/${value.id}/claims", canWriteEstimates, onOpenJob = onOpenClaimJob)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailSection(tab) {
                        when (tab) {
                            "History" -> if (value.history.isEmpty()) Text("No revision history yet.") else value.history.forEach { event ->
                                Text(event.changes)
                                Text("${event.actor} · ${event.date}", color = EzBossDesign.Muted, fontSize = 12.sp)
                            }
                            "Timeline" -> {
                                Text("Viewed by Customer", fontWeight = FontWeight.SemiBold)
                                Text(if (value.customerViews.any { it.matchesCurrent == true }) "Current version viewed" else "Current version awaiting a view",
                                    color = MaterialTheme.colorScheme.primary)
                                if (value.customerViews.isEmpty()) Text("No customer views recorded yet.", color = EzBossDesign.Muted)
                                val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
                                value.customerViews.forEach { view ->
                                    HorizontalDivider()
                                    Text(runCatching { formatter.format(Instant.parse(view.viewedAt)) }.getOrDefault(view.viewedAt))
                                    Text(when (view.matchesCurrent) {
                                        true -> "Current version"
                                        false -> "Earlier version"
                                        null -> "Version unavailable"
                                    }, color = EzBossDesign.Muted, fontSize = 12.sp)
                                }
                                if (value.validUntil.isNotBlank()) Text("Valid until ${value.validUntil}")
                                Text("Milestone scheduling is available in the web app.")
                            }
                            else -> {
                                Text("Manage ${tab.lowercase()} for this document in the web app.")
                                if (tab == "Payments") TextButton(onClick = { act("share-link") }, enabled = !acting) { Text("Open customer checkout") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (approvalOpen) AlertDialog(onDismissRequest = { approvalOpen = false }, title = { Text("Record client approval") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(approver, { approver = it }, label = { Text("Client approver name") })
            OutlinedTextField(reason, { reason = it }, label = { Text("Edit reason and approval evidence") })
            OutlinedTextField(approvalDate, { approvalDate = it }, label = { Text("Approval date (YYYY-MM-DD)") })
        }
    }, confirmButton = { TextButton(onClick = {
        approvalOpen = false
        act("record-approval", JSONObject().put("expectedRevision", estimate?.currentRevision).put("approverName", approver).put("reason", reason).put("approvalDate", approvalDate))
    }, enabled = approver.isNotBlank() && reason.isNotBlank() && approvalDate.isNotBlank()) { Text("Record approval") } }, dismissButton = { TextButton(onClick = { approvalOpen = false }) { Text("Cancel") } })
    if (revokeWarning) AlertDialog(onDismissRequest = { revokeWarning = false }, title = { Text("Revoke customer links?") }, confirmButton = { TextButton(onClick = { revokeWarning = false; act("revoke-links") }) { Text("Revoke links") } }, dismissButton = { TextButton(onClick = { revokeWarning = false }) { Text("Cancel") } })
}

private data class ClaimJobRow(
    val id: Int,
    val documentId: Int?,
    val title: String,
    val details: String,
    val claimCount: Int,
    val lastClaimAt: String,
) {
    constructor(row: JSONObject) : this(
        row.getInt("jobId"),
        row.optInt("documentId").takeIf { it > 0 },
        row.optString("projectName").ifBlank { row.getString("customerName") },
        listOf(row.optString("customerName").takeIf { row.optString("projectName").isNotBlank() }.orEmpty(),
            row.optString("siteAddress"), row.optString("estimateNumber"), row.optString("invoiceNumber"))
            .filter { it.isNotBlank() }.joinToString(" · "),
        row.optInt("claimCount"),
        row.optString("lastClaimAt"),
    )
}

private fun claimLoggedDate(value: String): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    return runCatching { formatter.format(Instant.parse(value)) }.getOrDefault(value.take(10))
}

@Composable fun ClaimsWorkspace(model: AuthViewModel, canWrite: Boolean, initialJob: Int, onOpenEstimate: (Int) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var openedJob by rememberSaveable(initialJob) { mutableIntStateOf(initialJob) }
    var selectedEstimate by remember { mutableStateOf<ClaimJobRow?>(null) }
    var rows by remember { mutableStateOf<List<ClaimJobRow>?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }

    fun showPicker(value: Boolean) {
        picking = value
        search = ""
        offset = 0
        rows = null
    }

    LaunchedEffect(picking, search, offset, retry, openedJob, selectedEstimate) {
        if (openedJob > 0 || selectedEstimate != null) return@LaunchedEffect
        rows = null
        error = null
        try {
            if (search.isNotBlank()) delay(250)
            val encoded = android.net.Uri.encode(search)
            val path = if (picking) "claims/estimates" else "claims/jobs"
            val result = model.operation("$path?search=$encoded&offset=$offset") as JSONObject
            val jobs = result.getJSONArray("jobs")
            rows = (0 until jobs.length()).map { ClaimJobRow(jobs.getJSONObject(it)) }
            hasMore = result.getBoolean("hasMore")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = (failure as? ApiException)?.message ?: "Could not load jobs."
        }
    }

    BackHandler(enabled = picking || (initialJob == 0 && openedJob > 0)) {
        when {
            openedJob > 0 -> openedJob = 0
            selectedEstimate != null -> selectedEstimate = null
            else -> showPicker(false)
        }
    }

    Column(Modifier.fillMaxSize().background(EzBossDesign.Canvas)) {
        PageHeading("Claims", "Find a job’s callback and repair history.")
        if (canWrite && !picking && openedJob == 0) {
            TextButton(onClick = { showPicker(true) }) { Text("New Claim") }
        }
        when {
            openedJob > 0 -> {
                TextButton(onClick = { openedJob = 0 }) { Text("Back to claims") }
                EstimateClaimsTab(model, "claims/jobs/$openedJob", canWrite, onOpenEstimate = onOpenEstimate)
            }
            selectedEstimate != null && canWrite -> {
                val choice = selectedEstimate!!
                TextButton(onClick = { selectedEstimate = null }) { Text("Choose a different job") }
                Text(choice.title, Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.SemiBold)
                Text(choice.details, Modifier.padding(horizontal = 16.dp), color = EzBossDesign.Muted)
                EstimateClaimsTab(model, "estimates/${choice.documentId}/claims", canWrite, startAdding = true, onSaved = { id ->
                    selectedEstimate = null
                    showPicker(false)
                    openedJob = id
                })
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(EzBossDesign.PagePadding),
                verticalArrangement = Arrangement.spacedBy(EzBossDesign.Gap)) {
                if (picking) TextButton(onClick = { showPicker(false) }) { Text("Back to claims") }
                OutlinedTextField(search, { search = it; offset = 0 }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (picking) "Find an estimate for this job" else "Search claims") },
                    placeholder = { Text("Client, address, project, or document number") })
                when {
                    error != null -> {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { retry++ }) { Text("Retry") }
                    }
                    rows == null -> EzBossLoadingIndicator()
                    else -> {
                        if (rows.orEmpty().isEmpty()) {
                            Text(if (picking) "No active estimates match." else if (search.isNotBlank()) "No matching claim histories." else "No claims yet.")
                        }
                        rows.orEmpty().forEach { row ->
                            OutlinedCard(onClick = {
                                if (picking) selectedEstimate = row else openedJob = row.id
                            }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(EzBossDesign.PagePadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.title, fontWeight = FontWeight.SemiBold)
                                    Text(row.details, color = EzBossDesign.Muted)
                                    if (row.lastClaimAt.isNotEmpty()) {
                                        val noun = if (row.claimCount == 1) "callback" else "callbacks"
                                        Text("${row.claimCount} $noun · Last logged ${claimLoggedDate(row.lastClaimAt)}", color = EzBossDesign.Muted)
                                    }
                                }
                            }
                        }
                        Row {
                            if (offset > 0) TextButton(onClick = { offset -= 25 }) { Text("Previous jobs") }
                            if (hasMore) TextButton(onClick = { offset += 25 }) { Text("Next jobs") }
                        }
                    }
                }
            }
        }
    }
}

private data class EstimateClaim(
    val id: String,
    val name: String,
    val location: String,
    val issueDescription: String,
    val notes: String,
    val createdAt: String,
) {
    constructor(row: JSONObject) : this(
        row.getString("id"), row.getString("name"), row.getString("location"),
        row.getString("issueDescription"), row.getString("notes"), row.getString("createdAt"),
    )
}

@Composable private fun EstimateClaimsTab(
    model: AuthViewModel,
    path: String,
    canWrite: Boolean,
    startAdding: Boolean = false,
    onSaved: ((Int) -> Unit)? = null,
    onOpenJob: ((Int) -> Unit)? = null,
    onOpenEstimate: ((Int) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var claims by remember(path) { mutableStateOf<List<EstimateClaim>?>(null) }
    var job by remember(path) { mutableStateOf<ClaimJobRow?>(null) }
    var jobId by remember(path) { mutableIntStateOf(0) }
    var archived by remember(path) { mutableStateOf(true) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var reload by remember(path) { mutableIntStateOf(0) }
    var draftId by rememberSaveable(path) { mutableStateOf(if (startAdding) java.util.UUID.randomUUID().toString() else null) }
    var name by rememberSaveable(path) { mutableStateOf("") }
    var location by rememberSaveable(path) { mutableStateOf("") }
    var issueDescription by rememberSaveable(path) { mutableStateOf("") }
    var notes by rememberSaveable(path) { mutableStateOf("") }
    var saving by remember(path) { mutableStateOf(false) }
    var saveError by remember(path) { mutableStateOf<String?>(null) }

    LaunchedEffect(path, reload) {
        loadError = null
        try {
            val result = model.operation(path) as JSONObject
            val rows = result.getJSONArray("claims")
            claims = (0 until rows.length()).map { EstimateClaim(rows.getJSONObject(it)) }
            archived = result.getBoolean("archived")
            jobId = result.getInt("jobId")
            job = result.optJSONObject("job")?.let(::ClaimJobRow)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            loadError = (error as? ApiException)?.message ?: "Could not load claims. Try again."
        }
    }

    fun closeForm() {
        draftId = null
        name = ""
        location = ""
        issueDescription = ""
        notes = ""
        saveError = null
    }

    fun saveClaim() {
        val id = draftId ?: return
        if (saving || !canWrite || archived) return
        if (name.isBlank() || location.isBlank() || issueDescription.isBlank()) {
            saveError = "Enter a name, location, and issue description."
            return
        }
        val body = JSONObject().put("id", id).put("name", name).put("location", location)
            .put("issueDescription", issueDescription).put("notes", notes)
        saving = true
        saveError = null
        scope.launch {
            try {
                val claim = EstimateClaim(model.operation(path, method = "POST", body = body) as JSONObject)
                claims = listOf(claim) + claims.orEmpty().filterNot { it.id == claim.id }
                closeForm()
                onSaved?.invoke(jobId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                saveError = if (error.statusCode == 409) "This claim was already saved with different details. Reopen Claims to view it."
                    else error.message
            } catch (_: Exception) {
                saveError = "Could not save the claim. Try again."
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailSection(job?.title ?: "Claims") {
            job?.let { summary ->
                Text(summary.details, color = EzBossDesign.Muted)
                summary.documentId?.let { id ->
                    if (onOpenEstimate != null) TextButton(onClick = { onOpenEstimate(id) }) { Text("Open document Claims tab") }
                }
            }
            if (!claims.isNullOrEmpty() && onOpenJob != null) {
                TextButton(onClick = { onOpenJob(jobId) }) { Text("View all claims for this job") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep a record of each return visit and repair at this job site.", Modifier.weight(1f))
                if (canWrite && !archived) {
                    FilledIconButton(
                        onClick = { draftId = java.util.UUID.randomUUID().toString() },
                        enabled = draftId == null,
                    ) { Icon(Icons.Default.Add, contentDescription = "Add claim") }
                }
            }
        }
        if (draftId != null && canWrite && !archived) {
            DetailSection("Add claim") {
                OutlinedTextField(name, { if (it.length <= 200) name = it }, Modifier.fillMaxWidth(),
                    enabled = !saving, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(location, { if (it.length <= 500) location = it }, Modifier.fillMaxWidth(),
                    enabled = !saving, label = { Text("Location") }, placeholder = { Text("Where on the job site?") })
                OutlinedTextField(issueDescription, { if (it.length <= 5000) issueDescription = it }, Modifier.fillMaxWidth(),
                    enabled = !saving, label = { Text("Issue description") }, placeholder = { Text("What went wrong on this visit?") }, minLines = 2)
                OutlinedTextField(notes, { if (it.length <= 5000) notes = it }, Modifier.fillMaxWidth(),
                    enabled = !saving, label = { Text("Notes (optional)") }, placeholder = { Text("How did you fix it?") }, minLines = 2)
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveClaim() }, enabled = !saving) { Text(if (saving) "Saving…" else "Save claim") }
                    TextButton(onClick = { closeForm() }, enabled = !saving) { Text("Cancel") }
                }
            }
        }
        when {
            loadError != null -> {
                Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reload++ }) { Text("Retry") }
            }
            claims == null -> EzBossLoadingIndicator()
            claims.isNullOrEmpty() -> Text("No claims yet.", color = EzBossDesign.Muted)
            else -> {
                claims.orEmpty().forEach { claim ->
                    DetailSection(claim.name) {
                        Text("Logged ${claimLoggedDate(claim.createdAt)}", color = EzBossDesign.Muted)
                        ClaimField("Location", claim.location)
                        ClaimField("Issue description", claim.issueDescription)
                        if (claim.notes.isNotEmpty()) ClaimField("Notes", claim.notes)
                    }
                }
            }
        }
    }
}

@Composable private fun ClaimField(label: String, value: String) {
    Text(label, fontWeight = FontWeight.SemiBold)
    Text(value)
}

@Composable private fun EstimateInformation(value: EstimateDetail) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(value.invoiceNumber?.takeIf { value.estimateState == "invoice" } ?: value.number, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { StatusBadge(label(value.estimateState), value.estimateState); StatusBadge(value.clientState?.let(::label) ?: "No client status", value.clientState) }
        DetailSection("Customer / project") { Text(value.customer); if (value.email.isNotBlank()) Text(value.email); if (value.phone.isNotBlank()) Text(value.phone); if (value.address.isNotBlank()) Text(value.address); if (value.projectDescription.isNotBlank()) Text(value.projectDescription) }
        if (value.validUntil.isNotBlank()) Text("Valid until ${value.validUntil}", color = EzBossDesign.Muted)
        DetailSection("Line items") {
            if (value.lines.isEmpty()) Text("No line items")
            else value.lines.sortedBy { it.sortOrder }.forEach { line -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(line.description, fontWeight = FontWeight.Medium); Text("${line.quantity} × ${money(line.unitPrice)}", color = EzBossDesign.Muted, fontSize = 12.sp) }; Text(money(line.total), fontWeight = FontWeight.SemiBold) }; HorizontalDivider() }
        }
        DetailSection("Totals") { TotalRow("Subtotal", value.subtotal); TotalRow("Tax (${value.taxRate}%)", value.tax); HorizontalDivider(); TotalRow("Total", value.total, true) }
        Text("Use Customer view / checkout to open the current customer link.", color = EzBossDesign.Muted, fontSize = 12.sp)
    }
}

@Composable private fun ClientPreview(value: EstimateDetail) {
    if (!value.showEstimate) { StateMessage("Estimate hidden from customer", "Client View settings disable the estimate section.", null) {}; return }
    val visibleLines = value.lines.filterNot { it.id in value.hidden }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().background(Color.White, EzBossDesign.CardShape).border(1.dp, EzBossDesign.Border, EzBossDesign.CardShape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (value.estimateState == "invoice") "Invoice" else if (value.estimateState == "change-order") "Change Order" else "Estimate", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("#${value.invoiceNumber.takeIf { value.estimateState == "invoice" } ?: value.number}", color = EzBossDesign.Muted)
            Text("Date: ${value.documentDate ?: "—"}", color = EzBossDesign.Muted)
            if (value.contractor.isNotBlank()) Text(value.contractor, fontWeight = FontWeight.SemiBold)
            listOf(value.contractorAddress, value.contractorPhone, value.contractorEmail, value.contractorWebsite).filter(String::isNotBlank).forEach { Text(it, fontSize = 12.sp, color = EzBossDesign.Muted) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Prepared for", fontSize = 11.sp, color = EzBossDesign.Muted); Text(value.customer, fontWeight = FontWeight.SemiBold)
            if (value.address.isNotBlank()) Text(value.address, fontSize = 13.sp)
        }
        if (value.projectDescription.isNotBlank()) Text(value.projectDescription, Modifier.fillMaxWidth().background(Color.White).padding(16.dp))
        val grouped = when (value.displayMode) {
            "byType" -> visibleLines.groupBy { when (it.type) { "product" -> "Materials"; "labor" -> "Labor"; "tool", "equipment" -> "Tools & Equipment"; else -> "Other" } }.mapValues { (_, lines) ->
                val allowedPrices = lines.filter { line -> value.showItemPrices && value.showGroupPrices && value.groups.firstOrNull { it.id == line.groupId }?.showPrice != false }
                listOf(EstimateLine("summary", "${lines.size} items", 1.0, allowedPrices.sumOf { it.total }, allowedPrices.sumOf { it.total }, "", if (allowedPrices.isEmpty()) "hidden-price" else "", 0))
            }
            "byGroup" -> visibleLines.groupBy { line -> value.groups.firstOrNull { it.id == line.groupId }?.name ?: "General" }
            else -> mapOf("Estimate details" to visibleLines)
        }
        grouped.forEach { (groupName, lines) -> DetailSection(groupName) { lines.forEach { line ->
            val itemGroup = value.groups.firstOrNull { it.id == line.groupId }
            val mayShowItemPrices = value.showItemPrices && (value.displayMode != "byGroup" || (value.showGroupPrices && itemGroup?.showPrice != false))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(line.description); if (mayShowItemPrices && value.displayMode != "byType") Text("${line.quantity} × ${money(line.unitPrice)}", fontSize = 11.sp, color = EzBossDesign.Muted) }; if (mayShowItemPrices) Text(money(line.total), fontWeight = FontWeight.Medium) }; HorizontalDivider()
        } } }
        val shownSubtotal = visibleLines.sumOf { it.total }
        val shownDiscount = if (value.discountType == "percentage") shownSubtotal * value.discount / 100.0 else value.discount
        val shownTax = (shownSubtotal - shownDiscount) * value.taxRate / 100.0
        val shownTotal = shownSubtotal - shownDiscount + shownTax
        DetailSection("Summary") { if (value.showSubtotal) TotalRow("Subtotal", shownSubtotal); if (value.showTax) TotalRow("Tax", shownTax); if (value.showTotal) { HorizontalDivider(); TotalRow("Total", shownTotal, true) } }
        Text("Internal preview · customer actions are disabled", Modifier.align(Alignment.CenterHorizontally), color = EzBossDesign.Muted, fontSize = 12.sp)
    }
}

@Composable private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxWidth().background(Color.White, EzBossDesign.CardShape).border(1.dp, EzBossDesign.Border, EzBossDesign.CardShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp); content() }) }
@Composable private fun TotalRow(title: String, amount: Double, strong: Boolean = false) { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal); Text(money(amount), fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium) } }


@Composable private fun DraftEditorScreen(model: AuthViewModel, id: String?, onClose: () -> Unit, onSaved: (String) -> Unit, projectId: String? = null) {
    var encoded by rememberSaveable(id, projectId) { mutableStateOf(JSONObject().put("creationRequestId", java.util.UUID.randomUUID().toString()).put("customerName", "").put("taxRate", 0).put("lineItems", JSONArray()).toString()) }
    var ready by rememberSaveable(id, projectId) { mutableStateOf(id == null && projectId == null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadWarning by remember { mutableStateOf(false) }
    var customersOpen by remember { mutableStateOf(false) }
    var importingCollection by remember { mutableStateOf(false) }
    var addingCompanyContact by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    fun load() { scope.launch {
        busy = true; error = null
        try {
            if (id != null) {
                encoded = model.draft(id).toString()
            } else if (projectId != null) {
                val project = model.operation("projects/${android.net.Uri.encode(projectId)}") as JSONObject
                check(project.isNull("archivedAt"))
                val site = project.optJSONObject("serviceAddress") ?: JSONObject()
                encoded = JSONObject(encoded)
                    .put("projectId", projectId)
                    .put("customerId", project.getString("clientId"))
                    .put("customerName", project.optString("clientName", ""))
                    .put("customerEmail", project.optString("clientEmail", ""))
                    .put("customerPhone", project.optString("clientPhone", ""))
                    .put("serviceAddress", site.optString("address", ""))
                    .put("serviceAddress2", site.optString("address2", ""))
                    .put("serviceCity", site.optString("city", ""))
                    .put("serviceState", site.optString("state", ""))
                    .put("serviceZipCode", site.optString("zipCode", ""))
                    .toString()
            }
            ready = true
        }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "Could not load draft. Check your access and connection, then retry." }
        finally { busy = false }
    } }
    LaunchedEffect(id, projectId) { if (!ready) load() }
    BackHandler {
        if (importingCollection) importingCollection = false
        else if (!busy || !ready) onClose()
    }
    if(importingCollection){
        CollectionImportPicker(model,{importingCollection=false}){ imported ->
            val draft=JSONObject(encoded);val lines=draft.optJSONArray("lineItems") ?: JSONArray()
            for(i in 0 until imported.length())lines.put(imported.getJSONObject(i))
            draft.put("lineItems",lines);encoded=draft.toString();importingCollection=false
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onClose, enabled = !busy || !ready) { Text("Back to documents") }
        Text(if (id == null) "Estimate draft" else "Edit document", style = MaterialTheme.typography.headlineSmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) EzBossLoadingIndicator()
        if (projectId != null && !ready && error != null) {
            TextButton(onClick = { load() }, enabled = !busy) { Text("Retry loading project") }
        }
        if (ready) {
            val draft = JSONObject(encoded)
            fun field(key: String, value: String) { draft.put(key, value); encoded = draft.toString() }
            TextButton(onClick = { customersOpen = true }, enabled = !busy) { Text("Select saved client or contact") }
            val snapshot = draft.optJSONObject("clientSnapshot")
            val additions = draft.optJSONArray("companyContactAdditions")
            if (snapshot?.optString("billTo") == "company" && !draft.isNull("customerId") &&
                draft.optString("estimateState") != "change-order") {
                TextButton(onClick = { addingCompanyContact = true }, enabled = !busy) { Text("Add company contact") }
                if (additions != null) {
                    Text("New contacts will be added to the company when you save.")
                    repeat(additions.length()) { index ->
                        val contact = additions.getJSONObject(index)
                        val label = listOf("name", "email", "phone", "note").firstNotNullOfOrNull {
                            contact.optString(it).takeIf(String::isNotBlank)
                        }
                        TextButton(onClick = {
                            val contacts = snapshot.getJSONArray("additionalContacts")
                            contacts.remove(contacts.length() - additions.length() + index)
                            additions.remove(index)
                            if (additions.length() == 0) draft.remove("companyContactAdditions")
                            encoded = draft.toString()
                        }, enabled = !busy) { Text("Remove new contact: $label") }
                    }
                }
            }
            OutlinedTextField(draft.optString("customerName"), { field("customerName", it) }, label = { Text("Customer name") }, enabled = !busy)
            OutlinedTextField(draft.optString("customerEmail", ""), { field("customerEmail", it) }, label = { Text("Email (optional)") }, enabled = !busy)
            OutlinedTextField(draft.optString("projectDescription", ""), { field("projectDescription", it) }, label = { Text("Scope description") }, enabled = !busy)
            OutlinedTextField(draft.opt("taxRate").toString(), { field("taxRate", it) }, label = { Text("Tax percent") }, enabled = !busy)
            val lines = draft.optJSONArray("lineItems") ?: JSONArray()
            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                listOf("description" to "Description", "quantity" to "Quantity", "unitPrice" to "Unit price").forEach { (key, title) ->
                    OutlinedTextField(line.opt(key)?.toString() ?: "", { line.put(key, it); encoded = draft.toString() }, label = { Text(title) }, enabled = !busy)
                }
                TextButton(onClick = { lines.remove(i); encoded = draft.toString() }, enabled = !busy) { Text("Remove line") }
            }
            TextButton(onClick = { lines.put(JSONObject().put("description","Discount / surcharge").put("type","custom").put("quantity",1).put("unitPrice",0).put("adjustmentKind","group")); draft.put("lineItems",lines); encoded=draft.toString() }, enabled=!busy) { Text("Add discount / surcharge (negative for discount)") }
            TextButton(onClick = { importingCollection = true }, enabled = !busy) { Text("Import collection") }
            TextButton(onClick = { lines.put(JSONObject().put("description", "").put("quantity", 1).put("unitPrice", 0).put("type", "manual")); draft.put("lineItems", lines); encoded = draft.toString() }, enabled = !busy) { Text("Add manual line") }
            Button(onClick = { scope.launch {
                busy = true; error = null
                try {
                    val saved = model.saveDraft(JSONObject(encoded))
                    encoded = saved.toString()
                    saved.optJSONObject("companyContactsAdded")?.let { added ->
                        val company = added.getString("companyName")
                        val count = added.getInt("count")
                        val message = if (count == 1) "Contact added to “$company”." else "$count contacts added to “$company”."
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                    onSaved(saved.get("id").toString())
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: ApiException) { error = e.message }
                catch (e: IllegalArgumentException) { error = e.message }
                catch (e: Exception) { error = "Save was not confirmed. Edits are preserved. Reopen before retrying an uncertain create." }
                finally { busy = false }
            } }, enabled = !busy) { Text("Save document") }
        }
        if (id != null) TextButton(onClick = { reloadWarning = true }, enabled = !busy) { Text("Reload stored draft") }
    }
    if (addingCompanyContact) CompanyContactDialog(onClose = { addingCompanyContact = false }) { contact ->
        val draft = JSONObject(encoded)
        val additions = draft.optJSONArray("companyContactAdditions") ?: JSONArray()
        additions.put(contact)
        val result = model.appendCompanyContacts(draft.getString("customerId"), additions)
        val snapshot = draft.getJSONObject("clientSnapshot")
        val contacts = snapshot.optJSONArray("additionalContacts") ?: JSONArray()
        snapshot.put("additionalContacts", contacts.put(contact))
        draft.remove("companyContactAdditions")
        encoded = draft.toString()
        result.optJSONObject("companyContactsAdded")?.let { added ->
            val company = added.getString("companyName")
            val count = added.getInt("count")
            val message = if (count == 1) "Contact added to “$company”." else "$count contacts added to “$company”."
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
        addingCompanyContact = false
    }
    if (customersOpen) CustomerPickerDialog(model, onClose = { customersOpen = false }) { customer, contact ->
        val draft = JSONObject(encoded)
        val customerId = customer.get("id").toString()
        if (draft.optString("customerId") != customerId) draft.remove("companyContactAdditions")
        val snapshot = JSONObject(customer.toString())
        draft.optJSONArray("companyContactAdditions")?.let { additions ->
            val contacts = snapshot.optJSONArray("additionalContacts") ?: JSONArray()
            repeat(additions.length()) { contacts.put(additions.getJSONObject(it)) }
            snapshot.put("additionalContacts", contacts)
        }
        draft.put("customerId", customerId).put("clientSnapshot", snapshot)
            .put("customerName", customer.optString("name", customer.optString("companyName")))
            .put("customerEmail", contact?.optString("email") ?: customer.optString("email", ""))
            .put("customerPhone", contact?.optString("phone") ?: customer.optString("phoneMobile", ""))
        listOf("serviceAddress", "serviceAddress2", "serviceCity", "serviceState", "serviceZipCode").forEach { field ->
            draft.put(field, customer.opt(if (customer.optBoolean("billingEqualToService", true)) field.replace("service", "billing") else field))
        }
        encoded = draft.toString(); customersOpen = false
    }
    if (reloadWarning) AlertDialog(onDismissRequest = { reloadWarning = false }, title = { Text("Discard local edits?") }, confirmButton = { TextButton(onClick = { reloadWarning = false; load() }) { Text("Discard and reload") } }, dismissButton = { TextButton(onClick = { reloadWarning = false }) { Text("Keep editing") } })
}


@Composable private fun CompanyContactDialog(onClose: () -> Unit, onAdd: suspend (JSONObject) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val invalidEmail = email.isNotBlank() && !Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email.trim())
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Add company contact") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text("Name") })
                OutlinedTextField(phone, { phone = it }, enabled = !busy, label = { Text("Phone") })
                OutlinedTextField(email, { email = it }, enabled = !busy, label = { Text("Email") }, isError = invalidEmail)
                OutlinedTextField(note, { note = it }, enabled = !busy, label = { Text("Note") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !invalidEmail && listOf(name, phone, email, note).any(String::isNotBlank),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try { onAdd(JSONObject().put("name", name.trim()).put("phone", phone.trim()).put("email", email.trim()).put("note", note.trim())) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not confirm the company contact save. Retry to confirm." }
                        finally { busy = false }
                    }
                },
            ) { Text(if (busy) "Saving…" else "Add") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable private fun CustomerPickerDialog(model: AuthViewModel, onClose: () -> Unit, select: (JSONObject, JSONObject?) -> Unit) {
    var search by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var more by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load(reset: Boolean) { scope.launch {
        busy = true; error = null
        if (reset) { rows = emptyList(); more = false }
        try {
            val page = model.dashboardData("clients?searchTerm=${java.net.URLEncoder.encode(search, "UTF-8")}&pageSize=50&offset=${rows.size}") as JSONObject
            val data = page.getJSONArray("clients")
            rows = rows + (0 until data.length()).map { data.getJSONObject(it) }.filter { next -> rows.none { it.get("id") == next.get("id") } }
            more = page.optBoolean("hasMore")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "Clients could not be loaded. Check access and connection, then retry." }
        finally { busy = false }
    } }
    LaunchedEffect(Unit) { load(true) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Select client") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(search, { search = it }, label = { Text("Client name") }, enabled = !busy)
            TextButton(onClick = { load(true) }, enabled = !busy) { Text("Search / retry") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) EzBossLoadingIndicator()
            rows.forEach { customer ->
                val name = customer.optString("name", "Customer")
                TextButton(onClick = { select(customer, null) }) { Text(name) }
                val contacts = customer.optJSONArray("additionalContacts") ?: JSONArray()
                for (i in 0 until contacts.length()) {
                    val contact = contacts.getJSONObject(i)
                    TextButton(onClick = { select(customer, contact) }) { Text("$name — ${contact.optString("name", "Contact")}") }
                }
            }
            if (more) TextButton(onClick = { load(false) }, enabled = !busy) { Text("Load more") }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}
