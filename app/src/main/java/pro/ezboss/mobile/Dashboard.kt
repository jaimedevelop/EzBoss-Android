package pro.ezboss.mobile

import pro.ezboss.mobile.data.dashboardGreetingName

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import pro.ezboss.mobile.data.Account
import pro.ezboss.mobile.ui.theme.*
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Currency

private fun JSONArray.rows() = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.text(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }.orEmpty()
private fun money(value: Any?, currency: String = "USD"): String = runCatching {
    val amount = value?.toString()?.toBigDecimalOrNull() ?: return "Unavailable"
    NumberFormat.getCurrencyInstance().apply { this.currency = Currency.getInstance(currency) }.format(amount)
}.getOrDefault("Unavailable")
internal fun dashboardDate(value: String, zone: ZoneId): LocalDate? = runCatching {
    if (value.length == 10) LocalDate.parse(value) else OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDate()
}.getOrNull()

@Composable fun DashboardScreen(account: Account, model: AuthViewModel, openDocument: (String) -> Unit, navigate: (String) -> Unit) {
    var refresh by rememberSaveable { mutableIntStateOf(0) }
    var days by rememberSaveable { mutableStateOf("Last 7 days") }
    var clients by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var clientsUnavailable by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        try {
            val grouped = model.dashboardData("clients") as JSONObject
            clients = grouped.keys().asSequence().flatMap { grouped.optJSONArray(it)?.rows().orEmpty().asSequence() }.toList()
            clientsUnavailable = false
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { clients = emptyList(); clientsUnavailable = true }
    }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var profileError by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        try { profile = model.dashboardData("profile") as JSONObject; profileError = false }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { profileError = true }
    }
    val zone = runCatching { ZoneId.of(profile?.text("timezone")) }.getOrDefault(ZoneId.systemDefault())
    val currency = profile?.text("currency")?.ifBlank { "USD" } ?: "USD"
    var today by remember { mutableStateOf(LocalDate.now(zone)) }
    LaunchedEffect(zone) { today = LocalDate.now(zone); while (true) { kotlinx.coroutines.delay(30_000); today = LocalDate.now(zone) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().background(EzBossDesign.HeaderBrush).padding(start = 84.dp, top = 24.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Welcome back, ${dashboardGreetingName(profile?.opt("firstName"), account.displayName)}!", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text("Here's what's happening with your business today.", color = Color.White)
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), color = Color.White)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { refresh++ }) { Text("Refresh dashboard") }
            if (profileError) Text("Business preferences unavailable. Using device timezone and USD.", style = MaterialTheme.typography.bodySmall)
            DashboardCard("Estimates & Invoices") {
                if (clientsUnavailable) Text("Billing addresses unavailable: linked client data could not be accessed.", style = MaterialTheme.typography.bodySmall)
                PeriodPicker(days, listOf("Last 7 days", "Last 30 days", "Last 90 days")) { days = it }
                DashboardRequest(model, "estimates", refresh) { response ->
                    val rows = (response as JSONArray).rows()
                    val start = today.minusDays(when(days) { "Last 30 days" -> 29; "Last 90 days" -> 89; else -> 6 })
                    listOf("estimate", "invoice").forEach { kind ->
                        Text(if (kind == "estimate") "Recent Estimates" else "Recent Invoices", style = MaterialTheme.typography.titleMedium, color = EzBossDesign.OrangeDark)
                        val candidates = rows.filter { it.text("estimateState") == kind && (kind == "invoice" || it.text("clientState") in listOf("sent", "viewed", "accepted", "denied", "on-hold", "expired")) }
                        val missing = candidates.count { dashboardDate(it.text("sentDate"), zone) == null }
                        if (missing > 0) Text("$missing documents have no known sent date and are excluded from this range.", style = MaterialTheme.typography.bodySmall)
                        val selected = candidates.filter { row -> dashboardDate(row.text("sentDate"), zone)?.let { !it.isBefore(start) && !it.isAfter(today) } ?: false }
                            .sortedWith(compareByDescending<JSONObject> { dashboardDate(it.text("sentDate"), zone) }.thenByDescending { it.text("sentDate") }.thenByDescending { it.optLong("id") }).distinctBy { it.text("id") }.take(5)
                        if (selected.isEmpty()) Text("No sent ${kind}s in the ${days.lowercase()}.", color = EzBossDesign.Muted)
                        selected.forEach { row ->
                            val balance = if (kind == "invoice") dashboardBalance(row, rows) else null
                            val status = if (kind == "invoice") when { balance == null -> "Payment status unavailable"; balance.signum() == 0 -> "Paid"; else -> "Unpaid" } else when(row.text("clientState")) { "accepted" -> "Approved"; "denied" -> "Denied"; else -> "Sent" }
                            Card(onClick = { openDocument(row.text("id")) }, colors = CardDefaults.cardColors(containerColor = EzBossDesign.Canvas), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(row.text(if (kind == "invoice") "invoiceNumber" else "estimateNumber").ifBlank { row.text("estimateNumber").ifBlank { "Number unavailable" } }, color = EzBossDesign.OrangeDark, style = MaterialTheme.typography.titleMedium)
                                    StatusBadge(status, if (status == "Paid") "accepted" else row.text("clientState"))
                                    Text(row.text("customerName").ifBlank { "Client name unavailable" })
                                    Text("${if (kind == "invoice") "Amount owed" else "Selling price"}: ${money(if (kind == "invoice") balance else row.opt("total"), currency)}")
                                    val client = clients.find { it.text("id") == row.text("customerId") }
                                    Text("Billing address: ${client?.let { c -> listOf("billingAddress", "billingAddress2", "billingCity", "billingState", "billingZipCode").map { c.text(it) }.filter { it.isNotBlank() }.joinToString(", ") }?.ifBlank { "Unavailable" } ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                                    Text("Sent: ${row.text("sentDate")}", style = MaterialTheme.typography.bodySmall)
                                    Text("Expiration: ${row.text("validUntil").ifBlank { "No expiration" }}", style = MaterialTheme.typography.bodySmall)
                                    Text("Service address: ${listOf("serviceAddress", "serviceAddress2", "serviceCity", "serviceState", "serviceZipCode").map { row.text(it) }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Unavailable" }}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        TextButton(onClick = { navigate("estimates?type=$kind") }) { Text("View all ${kind}s") }
                        HorizontalDivider()
                    }
                }
            }
            ScheduleCard(model, refresh, navigate)
            Text("Accounting", style = MaterialTheme.typography.titleLarge)
            AccountingCard("Pending Accounts", "pending", model, refresh, zone, openDocument)
            AccountingCard("Your Accumulated", "accumulated", model, refresh, zone, openDocument)
            PersonnelCard(model, refresh)
        }
    }
}

internal fun dashboardBalance(invoice: JSONObject, rows: List<JSONObject>): java.math.BigDecimal? {
    val ledger = if (!invoice.isNull("sourceEstimateId")) rows.find { it.text("id") == invoice.text("sourceEstimateId") } else invoice
    val payments = ledger?.optJSONArray("payments") ?: return null
    val total = invoice.text("total").toBigDecimalOrNull() ?: return null
    var paid = java.math.BigDecimal.ZERO
    payments.rows().forEach {
        if (it.text("status") !in listOf("approved", "pending", "rejected")) return null
        val amount = it.text("amount").toBigDecimalOrNull() ?: return null
        if (it.text("status") == "approved") paid += amount
    }
    return (total - paid).max(java.math.BigDecimal.ZERO)
}

@Composable private fun DashboardCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = EzBossDesign.CardShape, color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, EzBossDesign.Border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); content() }
    }
}
@Composable private fun PeriodPicker(value: String, values: List<String> = listOf("Daily", "Weekly", "Monthly"), onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }) { Text(value) }; DropdownMenu(expanded, { expanded = false }) { values.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false }) } } }
}
@Composable private fun DashboardRequest(model: AuthViewModel, path: String, refresh: Int, content: @Composable (Any) -> Unit) {
    var data by remember(path, refresh) { mutableStateOf<Any?>(null) }
    var error by remember(path, refresh) { mutableStateOf<String?>(null) }
    var retry by remember(path, refresh) { mutableIntStateOf(0) }
    LaunchedEffect(path, refresh, retry) {
        data = null; error = null
        try { data = model.dashboardData(path) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Unable to load data." }
    }
    when { error != null -> { Text(error!!, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("Try again") } }; data == null -> CircularProgressIndicator(Modifier.size(24.dp)); else -> content(data!!) }
}
@Composable private fun ScheduleCard(model: AuthViewModel, refresh: Int, navigate: (String) -> Unit) {
    var period by rememberSaveable { mutableStateOf("Daily") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    DashboardCard("Schedule / Work Orders") {
        PeriodPicker(period) { period = it }
        Text("Scheduling data is unavailable. Work orders do not currently provide a scheduled date or start time.", style = MaterialTheme.typography.bodyMedium, color = EzBossDesign.Muted)
        DashboardRequest(model, "work-orders", refresh) { response ->
            val rows = (response as JSONArray).rows().filter { it.text("status") !in listOf("completed", "cancelled", "canceled") }
            TextButton(onClick = { expanded = !expanded }) { Text("Unscheduled open work orders (${rows.size})") }
            if (expanded) rows.forEach { row ->
                Text(row.text("woNumber").ifBlank { "Work order" }, style = MaterialTheme.typography.titleMedium)
                Text(listOf(row.text("customerName"), row.text("serviceAddress"), row.text("status")).filter { it.isNotBlank() }.joinToString(" · "))
                HorizontalDivider()
            }
            TextButton(onClick = { navigate("work-orders") }) { Text("View work orders") }
        }
    }
}
@Composable private fun AccountingCard(title: String, kind: String, model: AuthViewModel, refresh: Int, zone: ZoneId, openDocument: (String) -> Unit) {
    var period by rememberSaveable { mutableStateOf("Daily") }
    DashboardCard(title) {
        PeriodPicker(period) { period = it }
        DashboardRequest(model, "dashboard-accounting/$kind?period=$period&timezone=${URLEncoder.encode(zone.id, "UTF-8")}", refresh) { response ->
            val data = response as JSONObject
            val currency = data.text("currency").ifBlank { "USD" }
            Text("${data.text("start")} through ${data.text("end")} (end excluded), ${data.text("timezone")}", style = MaterialTheme.typography.bodySmall)
            if (kind == "pending") {
                val clients = data.optJSONArray("clients")?.rows().orEmpty()
                if (clients.isEmpty()) Text("No collectible balances due in this period or awaiting allocation.")
                clients.forEach { client ->
                    Text(client.text("name").ifBlank { "Client name unavailable" }, style = MaterialTheme.typography.titleMedium)
                    Text("Due this period: ${money(client.opt("dated"), currency)}")
                    Text("Undated / unallocated: ${money(client.opt("undated"), currency)}")
                    client.optJSONArray("documents")?.let { ids -> (0 until ids.length()).forEach { i -> TextButton(onClick = { openDocument(ids.get(i).toString()) }) { Text("Open document #${ids.get(i)}") } } }
                    HorizontalDivider()
                }
            } else {
                listOf("Revenue · gross approved receipts" to "revenue", "Recorded costs" to "costs", "Profit" to "profit").forEach { (label, key) -> Text("$label: ${money(data.opt(key), currency)}", style = MaterialTheme.typography.titleMedium) }
                if (data.optInt("receipts") == 0) Text("No approved receipts recorded for this period.")
            }
            Text(data.text("explanation"), style = MaterialTheme.typography.bodySmall, color = EzBossDesign.Muted)
        }
    }
}
@Composable private fun PersonnelCard(model: AuthViewModel, refresh: Int) {
    var period by rememberSaveable { mutableStateOf("Daily") }
    DashboardCard("Personnel") {
        PeriodPicker(period) { period = it }
        DashboardRequest(model, "dashboard-personnel?period=${period.lowercase()}", refresh) { response ->
            val data = response as JSONObject
            val workers = data.optJSONArray("workers")?.rows().orEmpty()
            if (workers.isEmpty()) Text("No employees or work-order workers found.")
            workers.forEach { worker ->
                Text(worker.text("name"), style = MaterialTheme.typography.titleMedium)
                Text(worker.text("email").ifBlank { worker.text("id") }, style = MaterialTheme.typography.bodySmall)
                listOf("attendance" to "Attendance", "late" to "Late", "excused" to "Excused", "noCallNoShow" to "No Call/No Show", "tasksCompleted" to "Tasks Completed", "index" to "EzBoss Index").forEach { (key, label) ->
                    val metrics = worker.optJSONObject("metrics")
                    Text("$label: ${if (metrics == null || metrics.isNull(key)) "—" else "${kotlin.math.round(metrics.optDouble(key)).toInt()}%"}")
                }
                HorizontalDivider()
            }
            Text("— means unavailable, not zero. Roster includes People employees and work-order assignments across all dates.", style = MaterialTheme.typography.bodySmall)
            var showHelp by remember { mutableStateOf(false) }
            TextButton(onClick = { showHelp = !showHelp }) { Text("About personnel metrics") }
            if (showHelp) data.optJSONObject("metricHelp")?.let { help -> help.keys().forEach { Text(help.text(it), style = MaterialTheme.typography.bodySmall) } }
        }
    }
}
