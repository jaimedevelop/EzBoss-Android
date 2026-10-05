package pro.ezboss.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Account(val id: String, val auth0Id: String, val email: String, val displayName: String?, val roleName: String?, val isSuperuser: Boolean, val pageKeys: List<String>, val featureKeys: List<String>)
class ApiException(val statusCode: Int, message: String) : IOException(message)

class ApiClient(baseUrl: String) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun currentUser(token: String): Account = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw IllegalStateException("API base URL is not configured.")
        val request = Request.Builder().url("$baseUrl/users/me").header("Authorization", "Bearer $token").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                401 -> "Your session has expired. Please sign in again."
                404 -> "Your EzBoss account is not initialized. Contact your administrator."
                else -> "Could not load your EzBoss account (${response.code}). Please retry."
            })
            val json = JSONObject(response.body?.string() ?: throw IOException("Empty account response"))
            Account(json.opt("id").toString(), json.optString("auth0Id"), json.optString("email"),
                json.optString("displayName").takeIf(String::isNotBlank), json.optString("roleName").takeIf(String::isNotBlank),
                json.optBoolean("isSuperuser"), json.stringKeys("pageKeys"), json.stringKeys("featureKeys"))
        }
    }

    suspend fun estimates(token: String, customerName: String, estimateState: String?, clientState: String?, offset: Int, limit: Int = 25): EstimatePage = withContext(Dispatchers.IO) {
        val query = buildList {
            add("limit=$limit"); add("offset=$offset")
            if (customerName.isNotBlank()) add("customerName=${encode(customerName.trim())}")
            estimateState?.let { add("estimateState=${encode(it)}") }
            clientState?.let { add("clientState=${encode(it)}") }
        }.joinToString("&")
        val rows = get("$baseUrl/estimates?$query", token) as? JSONArray ?: throw IOException("Unexpected estimates response")
        EstimatePage((0 until rows.length()).map { mapEstimateListItem(rows.getJSONObject(it)) }, rows.length() == limit)
    }

    suspend fun estimate(token: String, id: String): EstimateDetail = withContext(Dispatchers.IO) {
        val json = get("$baseUrl/estimates/${encode(id)}", token) as? JSONObject ?: throw IOException("Unexpected estimate response")
        estimateDetail(json)
    }

    suspend fun dashboardData(token: String, path: String): Any = withContext(Dispatchers.IO) { get("$baseUrl/$path", token) }

    private fun get(url: String, token: String): Any = run {
        if (baseUrl.isBlank()) throw IllegalStateException("API base URL is not configured.")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                401 -> "Your session has expired. Please sign in again."
                403 -> "You don’t have permission to view this data."
                404 -> "This data is no longer available."
                else -> "Could not load data (${response.code}). Please retry."
            })
            val body = response.body?.string() ?: throw IOException("Empty response")
            if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        }
    }

    private fun estimateDetail(row: JSONObject): EstimateDetail {
        val settings = row.optJSONObject("clientViewSettings")
        val lineArray = row.optJSONArray("lineItems") ?: JSONArray()
        val groupArray = row.optJSONArray("groups") ?: JSONArray()
        val lines = (0 until lineArray.length()).map { i -> lineArray.getJSONObject(i).let { item ->
            EstimateLine(item.opt("id").toString(), item.optString("description"), item.optDouble("quantity", 0.0), item.optDouble("unitPrice", 0.0), item.optDouble("total", 0.0), item.optString("type"), item.opt("groupId").toString(), item.optInt("sortOrder", 0))
        } }
        val groups = (0 until groupArray.length()).map { i -> groupArray.getJSONObject(i).let { EstimateGroup(it.opt("id").toString(), it.optString("name"), it.optString("description"), it.optBoolean("showPrice", true)) } }
        val hidden = settings?.optJSONArray("hiddenLineItems")?.let { a -> (0 until a.length()).map { a.opt(it).toString() }.toSet() } ?: emptySet()
        return EstimateDetail(
            id = row.opt("id").toString(), number = row.optString("estimateNumber"), invoiceNumber = row.optString("invoiceNumber").takeIf { it.isNotBlank() },
            customer = row.optString("customerName"), email = row.optString("customerEmail"), phone = row.optString("customerPhone"),
            address = listOf(row.optString("serviceAddress"), row.optString("serviceAddress2"), listOf(row.optString("serviceCity"), row.optString("serviceState"), row.optString("serviceZipCode")).filter(String::isNotBlank).joinToString(", ")).filter(String::isNotBlank).joinToString("\n"),
            projectDescription = row.optString("projectDescription"), estimateState = row.optString("estimateState"), clientState = row.optString("clientState").takeIf { it.isNotBlank() && it != "null" },
            subtotal = row.optDouble("subtotal", 0.0), discount = row.optDouble("discount", 0.0), discountType = row.optString("discountType", "fixed"), tax = row.optDouble("tax", 0.0), taxRate = row.optDouble("taxRate", 0.0), total = row.optDouble("total", 0.0), validUntil = row.optString("validUntil"),
            contractor = row.optString("contractorCompany"), contractorAddress = row.optString("contractorCompanyAddress"), contractorPhone = row.optString("contractorCompanyPhone"), contractorEmail = row.optString("contractorCompanyEmail"), contractorWebsite = row.optString("contractorCompanyWebsite"),
            lines = lines, groups = groups, displayMode = settings?.optString("displayMode", "list") ?: "list", hidden = hidden,
            showEstimate = settings?.optBoolean("showEstimateTab", true) ?: true, showItemPrices = settings?.optBoolean("showItemPrices", true) ?: true, showGroupPrices = settings?.optBoolean("showGroupPrices", true) ?: true,
            showSubtotal = settings?.optBoolean("showSubtotal", true) ?: true, showTax = settings?.optBoolean("showTax", true) ?: true, showTotal = settings?.optBoolean("showTotal", true) ?: true,
        )
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun JSONObject.stringKeys(name: String): List<String> {
        val value = opt(name) ?: return emptyList()
        if (value == "*") return listOf("*")
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }
}

data class EstimatePage(val items: List<EstimateListItem>, val canLoadMore: Boolean)
data class EstimateListItem(val id: String, val number: String, val customer: String, val estimateState: String, val clientState: String?, val total: Double)
data class EstimateLine(val id: String, val description: String, val quantity: Double, val unitPrice: Double, val total: Double, val type: String, val groupId: String, val sortOrder: Int)
data class EstimateGroup(val id: String, val name: String, val description: String, val showPrice: Boolean)
data class EstimateDetail(val id: String, val number: String, val invoiceNumber: String?, val customer: String, val email: String, val phone: String, val address: String, val projectDescription: String, val estimateState: String, val clientState: String?, val subtotal: Double, val discount: Double, val discountType: String, val tax: Double, val taxRate: Double, val total: Double, val validUntil: String, val contractor: String, val contractorAddress: String, val contractorPhone: String, val contractorEmail: String, val contractorWebsite: String, val lines: List<EstimateLine>, val groups: List<EstimateGroup>, val displayMode: String, val hidden: Set<String>, val showEstimate: Boolean, val showItemPrices: Boolean, val showGroupPrices: Boolean, val showSubtotal: Boolean, val showTax: Boolean, val showTotal: Boolean)

internal fun mapEstimateListItem(row: JSONObject) = EstimateListItem(
    id = row.opt("id").toString(), number = if (row.optString("estimateState") == "invoice") row.optString("invoiceNumber").ifBlank { "Number pending" } else row.optString("estimateNumber").ifBlank { "Number pending" },
    customer = row.optString("customerName", "Customer"), estimateState = row.optString("estimateState", "estimate"),
    clientState = row.optString("clientState").takeIf { it.isNotBlank() && it != "null" }, total = row.optDouble("total", 0.0),
)
