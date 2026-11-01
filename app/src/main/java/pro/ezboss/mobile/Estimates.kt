package pro.ezboss.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import pro.ezboss.mobile.ui.theme.*
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pro.ezboss.mobile.data.*
import java.text.NumberFormat
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

@Composable fun EstimatesListScreen(model: AuthViewModel, initialFilter: String = "All", onOpen: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        DraftEditorScreen(model, null, onClose = { creating = false }, onSaved = { creating = false; onOpen(it) })
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
                val page = model.estimatePage(search, estimateStateQuery(estimateFilter), clientStateQuery(clientFilter), offset)
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
        PageHeading("Estimates", "Manage your estimates and invoices.")
        TextButton(onClick = { creating = true }) { Text("Create estimate draft") }
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
            loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null && rows.isEmpty() -> StateMessage(if (denied) "Access denied" else "Couldn’t load estimates", error!!, if (denied) null else "Retry") { generation++; load(true) }
            rows.isEmpty() -> StateMessage(if (search.isNotBlank() || estimateFilter != "All" || clientFilter != "Any") "No matching estimates" else "No estimates yet", if (search.isNotBlank() || estimateFilter != "All" || clientFilter != "Any") "Try changing your search or filters." else "Estimates will appear here when they are available.", null) {}
            else -> LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(EzBossDesign.PagePadding), horizontalArrangement = Arrangement.spacedBy(EzBossDesign.Gap), verticalArrangement = Arrangement.spacedBy(EzBossDesign.Gap), modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { item -> EstimateCard(item) { onOpen(item.id) } }
                if (canLoadMore) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (loadingMore) CircularProgressIndicator(Modifier.size(24.dp))
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

@Composable fun EstimateDetailScreen(model: AuthViewModel, id: String, onReturn: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var approvalOpen by remember { mutableStateOf(false) }
    var approver by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var approvalDate by rememberSaveable { mutableStateOf("") }
    var acting by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var revokeWarning by remember { mutableStateOf(false) }
    fun act(action: String, body: JSONObject = JSONObject()) { scope.launch {
        acting = true; actionMessage = null
        try {
            val result = model.documentAction(id, action, body)
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
    var tab by rememberSaveable(id) { mutableStateOf("Estimate") }
    var loading by remember(id) { mutableStateOf(true) }
    var retry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, retry) {
        loading = true; error = null
        try { estimate = model.estimate(id) }
        catch (e: ApiException) { error = e.message; denied = e.statusCode == 403 }
        catch (e: Exception) { error = e.message ?: "Could not load this estimate. Retry." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().background(EzBossDesign.Canvas)) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onReturn) { Icon(Icons.Default.ArrowBack, "Return to estimates") }; Text("Back to estimates", Modifier.clickable(onClick = onReturn).padding(8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        HorizontalDivider()
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (estimate == null) StateMessage(if (denied) "Access denied" else "Estimate unavailable", error ?: "This estimate may have been deleted.", if (denied) null else "Retry") { retry++ }
        else {
            val value = estimate!!
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
            actionMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
            TextButton(onClick = { editing = true }) { Text("Edit document") }
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color.White)) {
                Row { estimateDashboardTabs(value.estimateState == "change-order").forEach { contract ->
                    val name = contract.label
                    val enabled = contract.enabled
                    Column(Modifier.clickable(enabled = enabled) { tab = name }.padding(horizontal = 12.dp, vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(name, color = if (tab == name) MaterialTheme.colorScheme.primary else EzBossDesign.Muted, fontSize = 13.sp, fontWeight = if (tab == name) FontWeight.SemiBold else FontWeight.Normal); if (!enabled) Text("Coming soon", color = EzBossDesign.Muted, fontSize = 9.sp) }
                } }
                HorizontalDivider(color = EzBossDesign.Border)
            }
            if (tab == "Estimate") EstimateInformation(value) else ClientPreview(value)
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
        DetailSection("History") { value.history.forEach { event -> Text(event.changes); Text("${event.actor} · ${event.date}", color = EzBossDesign.Muted, fontSize = 12.sp) } }
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


@Composable private fun DraftEditorScreen(model: AuthViewModel, id: String?, onClose: () -> Unit, onSaved: (String) -> Unit) {
    var encoded by rememberSaveable(id) { mutableStateOf(JSONObject().put("creationRequestId", java.util.UUID.randomUUID().toString()).put("customerName", "").put("taxRate", 0).put("lineItems", JSONArray()).toString()) }
    var ready by rememberSaveable(id) { mutableStateOf(id == null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadWarning by remember { mutableStateOf(false) }
    var customersOpen by remember { mutableStateOf(false) }
    var importingCollection by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() { scope.launch {
        busy = true; error = null
        try { if (id != null) encoded = model.draft(id).toString(); ready = true }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "Could not load draft. Check your access and connection, then retry." }
        finally { busy = false }
    } }
    LaunchedEffect(id) { if (!ready) load() }
    if(importingCollection){
        CollectionImportPicker(model,{importingCollection=false}){ imported ->
            val draft=JSONObject(encoded);val lines=draft.optJSONArray("lineItems") ?: JSONArray()
            for(i in 0 until imported.length())lines.put(imported.getJSONObject(i))
            draft.put("lineItems",lines);encoded=draft.toString();importingCollection=false
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onClose, enabled = !busy) { Text("Back to documents") }
        Text(if (id == null) "Estimate draft" else "Edit document", style = MaterialTheme.typography.headlineSmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
        if (ready) {
            val draft = JSONObject(encoded)
            fun field(key: String, value: String) { draft.put(key, value); encoded = draft.toString() }
            TextButton(onClick = { customersOpen = true }, enabled = !busy) { Text("Select saved client or contact") }
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
                try { val saved = model.saveDraft(JSONObject(encoded)); encoded = saved.toString(); onSaved(saved.get("id").toString()) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: ApiException) { error = e.message }
                catch (e: IllegalArgumentException) { error = e.message }
                catch (e: Exception) { error = "Save was not confirmed. Edits are preserved. Reopen before retrying an uncertain create." }
                finally { busy = false }
            } }, enabled = !busy) { Text("Save document") }
        }
        if (id != null) TextButton(onClick = { reloadWarning = true }, enabled = !busy) { Text("Reload stored draft") }
    }
    if (customersOpen) CustomerPickerDialog(model, onClose = { customersOpen = false }) { customer, contact ->
        val draft = JSONObject(encoded)
        draft.put("customerId", customer.get("id").toString()).put("clientSnapshot", customer)
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
            if (busy) CircularProgressIndicator()
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
