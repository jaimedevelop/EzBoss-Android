package pro.ezboss.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import pro.ezboss.mobile.auth.isSecureApiBaseUrl
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Account(val id: String, val auth0Id: String, val email: String, val displayName: String?, val roleName: String?, val isSuperuser: Boolean, val pageKeys: List<String>, val featureKeys: List<String>)
class ApiException(val statusCode: Int, message: String) : IOException(message)

class ApiClient(baseUrl: String, transport: OkHttpClient = OkHttpClient(), private val identityDiscovery: Boolean = false) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val http = transport.newBuilder().callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    private fun requireSecureConfiguration(token: String) {
        check(isSecureApiBaseUrl(baseUrl)) { "API base URL is not securely configured." }
        if (token.isBlank()) throw ApiException(401, "Your session has expired. Please sign in again.")
    }

    suspend fun currentUser(token: String): Account = withContext(Dispatchers.IO) {
        requireSecureConfiguration(token)
        if(identityDiscovery) {
            http.newCall(Request.Builder().url("$baseUrl/account/me").header("Authorization","Bearer $token").get().build()).execute().use { response ->
                if(!response.isSuccessful && response.code !in listOf(403,409))throw ApiException(response.code,"Account discovery is temporarily unavailable. Retry.")
                if(response.isSuccessful && JSONObject(response.body?.string().orEmpty()).optBoolean("pendingIdentity"))throw ApiException(409,"Complete account setup to continue.")
            }
        }
        val request = Request.Builder().url("$baseUrl/users/me").header("Authorization", "Bearer $token").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                401 -> "Your session has expired. Please sign in again."
                403 -> "Access was denied. Contact your administrator or sign in with another account."
                404 -> "Your EzBoss account is not initialized. Contact your administrator."
                else -> "Could not load your EzBoss account (${response.code}). Please retry."
            })
            val json = JSONObject(response.body?.string() ?: throw IOException("Empty account response"))
            if (json.optString("status") != "active") throw ApiException(403, "This account is inactive. Contact your administrator.")
            val accountId = json.optLong("id", 0)
            if (accountId <= 0) throw IOException("Invalid account response")
            Account(accountId.toString(), if(json.isNull("auth0Id")) "" else json.optString("auth0Id"), json.optString("email"),
                usableName(json.opt("displayName")), usableName(json.opt("roleName")),
                json.optBoolean("isSuperuser"), json.stringKeys("pageKeys"), json.stringKeys("featureKeys"))
        }
    }

    suspend fun createNativeCompany(token: String, fields: JSONObject) = withContext(Dispatchers.IO) {
        requireSecureConfiguration(token)
        val request=Request.Builder().url("$baseUrl/onboarding/contractor").header("Authorization","Bearer $token").post(fields.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response -> if(!response.isSuccessful)throw ApiException(response.code,"Company setup was not confirmed. Retry account access before submitting again.") }
    }

    suspend fun estimates(token: String, membership: String, customerName: String, estimateState: String?, clientState: String?, offset: Int, limit: Int = 25, projectId: String? = null): EstimatePage = withContext(Dispatchers.IO) {
        val query = buildList {
            add("limit=$limit"); add("offset=$offset")
            if (customerName.isNotBlank()) add("customerName=${encode(customerName.trim())}")
            estimateState?.let { add("estimateState=${encode(it)}") }
            clientState?.let { add("clientState=${encode(it)}") }
            projectId?.let { add("projectId=${encode(it)}") }
        }.joinToString("&")
        val rows = get("$baseUrl/estimates?$query", token, membership) as? JSONArray ?: throw IOException("Unexpected estimates response")
        EstimatePage((0 until rows.length()).map { mapEstimateListItem(rows.getJSONObject(it)) }, rows.length() == limit)
    }

    suspend fun estimate(token: String, id: String, membership: String): EstimateDetail = withContext(Dispatchers.IO) {
        val json = get("$baseUrl/estimates/${encode(id)}", token, membership) as? JSONObject ?: throw IOException("Unexpected estimate response")
        estimateDetail(json)
    }

    suspend fun draft(token: String, membership: String, id: String): JSONObject = withContext(Dispatchers.IO) {
        val row = get("$baseUrl/estimates/${encode(id)}", token, membership) as JSONObject
        if (row.optString("estimateState") !in listOf("draft", "estimate", "change-order", "invoice")) throw ApiException(409, "This document cannot be edited.")
        row.put("expectedUpdatedAt", row.getString("updatedAt")).put("draftOnly", row.optString("estimateState") == "draft")
    }

    suspend fun appendCompanyContacts(token: String, membership: String, clientId: String, contacts: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        requireSecureConfiguration(token)
        val body = JSONObject().put("additionalContacts", contacts)
        val request = Request.Builder().url("$baseUrl/clients/${encode(clientId)}/contacts")
            .header("Authorization", "Bearer $token").header("X-Company-Membership-Id", membership)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "Could not confirm the company contact save. Your details are preserved. Retry to confirm.")
            JSONObject(response.body?.string() ?: throw IOException("Contact save was not confirmed"))
        }
    }

    suspend fun saveDraft(token: String, membership: String, draft: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        requireSecureConfiguration(token)
        val body = draftPayload(draft)
        val id = draft.optString("id").takeIf { it.isNotBlank() && it != "null" }
        val path = id?.let { "/${encode(it)}" } ?: ""
        val request = Request.Builder().url("$baseUrl/estimates$path")
            .header("Authorization", "Bearer $token").header("X-Company-Membership-Id", membership)
            .method(if (id == null) "POST" else "PATCH", body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, when(response.code) {
                409 -> "The document changed or was sent. Edits are preserved; reload before saving."
                400 -> "Check customer, line amounts and tax rate. Your edits are preserved."
                401 -> "Your session expired. Sign in again."
                403 -> "You do not have permission to edit this document."
                else -> "Save was not confirmed. Your edits are preserved. Reopen before retrying an uncertain create."
            })
            JSONObject(response.body?.string() ?: throw IOException("Save was not confirmed")).also {
                it.put("expectedUpdatedAt", it.getString("updatedAt")).put("draftOnly", it.optString("estimateState") == "draft")
            }
        }
    }

    suspend fun documentAction(token: String, membership: String, id: String, action: String, body: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        requireSecureConfiguration(token)
        require(action in listOf("send-email", "issue-invoice", "share-link", "revoke-links", "record-approval"))
        val request = Request.Builder().url("$baseUrl/estimates/${encode(id)}/$action")
            .header("Authorization", "Bearer $token").header("X-Company-Membership-Id", membership)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "Action was not confirmed (${response.code}). Check required information and refresh before retrying.")
            if (response.code == 204) JSONObject() else JSONObject(response.body?.string() ?: throw IOException("Action was not confirmed"))
        }
    }

    suspend fun dashboardData(token: String, path: String, membership: String): Any = withContext(Dispatchers.IO) { get("$baseUrl/$path", token, membership) }

    suspend fun companies(token: String, selected: String? = null): CompanyAccount = withContext(Dispatchers.IO) {
        val suffix = selected?.let { "?membershipId=${encode(it)}" } ?: ""
        val json = get("$baseUrl/account/me$suffix", token, discovery = true) as? JSONObject ?: throw IOException("Invalid company response")
        mapCompanyAccount(json)
    }

    suspend fun operation(token: String, membership: String, path: String, method: String = "GET", body: JSONObject? = null, providerKey: String? = null): Any = withContext(Dispatchers.IO) {
        requireSecureConfiguration(token)
        val request = Request.Builder().url("$baseUrl/$path").header("Authorization", "Bearer $token").header("X-Company-Membership-Id", membership)
            .apply { if (providerKey != null) header("X-AI-Key",providerKey) }
            .method(method, if (method == "GET" || (method == "DELETE" && body == null)) null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())).build()
        val transport = if (providerKey == null) http else http.newBuilder().callTimeout(60,TimeUnit.SECONDS).build()
        transport.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, when(response.code) {
                409 -> "This item changed or the request conflicts with current state. Refresh before changing it."
                403 -> "This action is not allowed in this company."
                else -> "Action was not confirmed (${response.code}). Your input is preserved; check it and retry."
            })
            if (response.code == 204) JSONObject() else org.json.JSONTokener(response.body?.string() ?: throw IOException("Empty response")).nextValue()
        }
    }
    suspend fun customCollection(endpoint: String,key: String,model: String,prompt: String,inventory: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val uri=java.net.URI(endpoint)
        require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null)
        val url=endpoint.trimEnd('/')+(if(uri.path.lowercase().endsWith("/chat/completions")) "" else "/chat/completions")
        val instruction="Return only JSON with name, description, items. Each item has contentType (products/labor/tools/equipment), itemId (integer), quantity (positive whole number). Select only supplied IDs of the same contentType. Human review is required."
        val messages=JSONArray().put(JSONObject().put("role","system").put("content",instruction)).put(JSONObject().put("role","user").put("content",JSONObject().put("request",prompt).put("inventory",inventory).toString()))
        val body=JSONObject().put("model",model).put("max_tokens",3000).put("messages",messages)
        // Only the provider key goes to this user-selected endpoint. Never attach an EzBoss access token.
        val request=Request.Builder().url(url).header("Authorization","Bearer $key").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newBuilder().callTimeout(60,TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            require(response.isSuccessful)
            val text=response.body?.string() ?: throw IOException("No provider response")
            require(text.length<1_000_000)
            val choice=JSONObject(text).getJSONArray("choices").getJSONObject(0)
            require(choice.optString("finish_reason")!="length")
            var content=choice.getJSONObject("message").getString("content").trim()
            content=content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            nativeAIReview(JSONObject(content),inventory)
        }
    }

    suspend fun taskPhoto(token: String, membership: String, path: String, jpeg: ByteArray, requestId: String): Unit = withContext(Dispatchers.IO) {
        requireSecureConfiguration(token)
        require(jpeg.isNotEmpty() && jpeg.size <= 5 * 1024 * 1024)
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", "task.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())).build()
        val request = Request.Builder().url("$baseUrl/$path").header("Authorization","Bearer $token")
            .header("X-Company-Membership-Id",membership).header("Idempotency-Key",requestId).post(body).build()
        http.newCall(request).execute().use { response -> if (!response.isSuccessful) throw ApiException(response.code,"Photo was not confirmed. Retry the same photo to avoid duplicate metadata.") }
    }

    private fun get(url: String, token: String, membership: String? = null, discovery: Boolean = false): Any = run {
        requireSecureConfiguration(token)
        val target = url.toHttpUrlOrNull() ?: throw IOException("Invalid API request.")
        val origin = baseUrl.toHttpUrlOrNull() ?: throw IOException("Invalid API configuration.")
        check(target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port) { "API destination is not allowed." }
        val request = Request.Builder().url(target).header("Authorization", "Bearer $token").apply {
            membership?.let { header("X-Company-Membership-Id", it) }
        }.get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && !(discovery && response.code in listOf(403,409))) throw ApiException(response.code, when (response.code) {
                401 -> "Your session has expired. Please sign in again."
                409 -> "Select a company to continue."
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
            documentDate = (usableName(row.opt("createdDate")) ?: usableName(row.opt("createdAt")))?.take(10),
            customerViews = row.optJSONArray("customerViews")?.let { views ->
                (0 until views.length()).map { index ->
                    val view = views.getJSONObject(index)
                    EstimateCustomerView(view.getString("viewedAt"), view.opt("matchesCurrent") as? Boolean)
                }
            }.orEmpty(),
            currentRevision = row.optInt("currentRevision"), collectionHold = row.optBoolean("collectionHold"),
            history = row.optJSONArray("revisionsHistory")?.let { events -> (0 until events.length()).map { index -> events.getJSONObject(index).let { EstimateHistory(it.optString("date"), it.optString("changes"), usableName(it.opt("modifiedByName")) ?: "Actor recorded") } } } ?: emptyList(),
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
data class EstimateDetail(val id: String, val number: String, val invoiceNumber: String?, val customer: String, val email: String, val phone: String, val address: String, val projectDescription: String, val estimateState: String, val clientState: String?, val subtotal: Double, val discount: Double, val discountType: String, val tax: Double, val taxRate: Double, val total: Double, val validUntil: String, val contractor: String, val contractorAddress: String, val contractorPhone: String, val contractorEmail: String, val contractorWebsite: String, val lines: List<EstimateLine>, val groups: List<EstimateGroup>, val displayMode: String, val hidden: Set<String>, val showEstimate: Boolean, val showItemPrices: Boolean, val showGroupPrices: Boolean, val showSubtotal: Boolean, val showTax: Boolean, val showTotal: Boolean, val currentRevision: Int = 0, val collectionHold: Boolean = false, val history: List<EstimateHistory> = emptyList(), val documentDate: String? = null, val customerViews: List<EstimateCustomerView> = emptyList())

internal fun mapEstimateListItem(row: JSONObject) = EstimateListItem(
    id = row.opt("id").toString(), number = if (row.optString("estimateState") == "invoice") row.optString("invoiceNumber").ifBlank { "Number pending" } else row.optString("estimateNumber").ifBlank { "Number pending" },
    customer = row.optString("customerName", "Customer"), estimateState = row.optString("estimateState", "estimate"),
    clientState = row.optString("clientState").takeIf { it.isNotBlank() && it != "null" }, total = row.optDouble("total", 0.0),
)


data class CompanyMembership(val id: String, val name: String, val available: Boolean)
data class CompanyAccount(val memberships: List<CompanyMembership>, val selected: String?)
internal fun mapCompanyAccount(json: JSONObject): CompanyAccount {
    val rows = json.optJSONArray("memberships") ?: throw IOException("Missing company memberships")
    val memberships = (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val id = row.getString("id")
        require(java.util.UUID.fromString(id).toString().equals(id, ignoreCase = true))
        CompanyMembership(id, usableName(row.opt("companyName")) ?: "Company", row.optBoolean("isActive") && row.optBoolean("companyIsActive") && (row.optString("kind") == "owner" || row.optBoolean("employeeIsActive")))
    }
    val selected = json.optJSONObject("companyContext")?.getString("membershipId")
    if (selected != null && memberships.none { it.id == selected && it.available }) throw IOException("Invalid company selection")
    return CompanyAccount(memberships, selected)
}


internal fun draftPayload(draft: JSONObject): JSONObject {
    require(draft.optString("customerName").trim().isNotEmpty()) { "Customer name is required." }
    val rate = draft.opt("taxRate").toString().toBigDecimalOrNull()
    require(rate != null && rate >= java.math.BigDecimal.ZERO && rate <= java.math.BigDecimal(100)) { "Tax must be between 0 and 100." }
    val result = JSONObject()
    listOf("customerName", "customerEmail", "projectDescription", "expectedUpdatedAt", "draftOnly", "creationRequestId", "customerId", "clientSnapshot", "companyContactAdditions", "customerPhone", "serviceAddress", "serviceAddress2", "serviceCity", "serviceState", "serviceZipCode").forEach {
        if (draft.has(it)) result.put(it, draft.get(it))
    }
    result.put("nativeAuthoring", true)
    result.put("taxRate", rate).put("draftOnly", !draft.has("id") || draft.optString("estimateState") == "draft")
    if (!draft.has("id")) {
        result.put("estimateState", "draft")
        if (draft.has("projectId")) result.put("projectId", draft.getString("projectId"))
    }
    val lines = draft.optJSONArray("lineItems") ?: JSONArray()
    result.put("lineItems", JSONArray().apply {
        for (i in 0 until lines.length()) {
            val line = JSONObject(lines.getJSONObject(i).toString())
            val quantity = line.opt("quantity").toString().toBigDecimalOrNull()
            val price = line.opt("unitPrice").toString().toBigDecimalOrNull()
            require(quantity != null && quantity > java.math.BigDecimal.ZERO && price != null && (price >= java.math.BigDecimal.ZERO || (line.optString("adjustmentKind") in listOf("group","type") && quantity.compareTo(java.math.BigDecimal.ONE)==0))) { "Enter a positive quantity and a nonnegative price." }
            val storedQuantity = quantity.setScale(2, java.math.RoundingMode.HALF_UP)
            val storedPrice = price.setScale(2, java.math.RoundingMode.HALF_UP)
            require(storedQuantity > java.math.BigDecimal.ZERO) { "Quantity must be at least 0.01." }
            line.put("quantity", storedQuantity).put("unitPrice", storedPrice)
                .put("total", storedQuantity.multiply(storedPrice).setScale(2, java.math.RoundingMode.HALF_UP))
            put(line)
        }
    })
    return result
}

data class EstimateHistory(val date: String, val changes: String, val actor: String)

internal fun nativeAIReview(proposal:JSONObject,inventory:JSONObject):JSONObject {
    val name=proposal.getString("name");val description=proposal.getString("description")
    require(name.isNotBlank()&&name.length<=200&&description.length<=5000)
    val items=proposal.getJSONArray("items");require(items.length()<=200)
    val clean=JSONArray();val preview=JSONArray();val seen=mutableSetOf<String>()
    for(i in 0 until items.length()) {
        val item=items.getJSONObject(i);val kind=item.getString("contentType")
        require(kind in listOf("products","labor","tools","equipment"))
        val id=item.getInt("itemId");val quantity=item.getInt("quantity")
        require(item.getDouble("itemId")==id.toDouble()&&item.getDouble("quantity")==quantity.toDouble()&&quantity in 1..100000)
        require(seen.add("$kind:$id"))
        val rows=inventory.getJSONArray(kind);val row=(0 until rows.length()).map{rows.getJSONObject(it)}.firstOrNull{it.getInt("id")==id} ?: throw IllegalArgumentException("Unavailable typed inventory reference")
        val value=JSONObject().put("contentType",kind).put("itemId",id).put("quantity",quantity)
        clean.put(value);preview.put(JSONObject(value.toString()).put("name",row.optString("name","Inventory")))
    }
    return JSONObject().put("collection",JSONObject().put("name",name).put("description",description).put("items",clean)).put("preview",preview)
}

data class EstimateCustomerView(val viewedAt: String, val matchesCurrent: Boolean?)
