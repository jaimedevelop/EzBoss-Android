package pro.ezboss.mobile.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class NativeAuthException(val status: Int) : Exception(if (status >= 500 || status == 429) "Authentication temporarily unavailable. Retry shortly." else "Authentication could not finish. Check your details or use a new code.")
internal data class NativeAuthResponse(val data: JSONObject, val signedToken: String?, val generation: Long) {
    override fun toString() = "NativeAuthResponse([redacted])"
}
internal class BetterAuthClient(origin: String) {
    private val base = origin.trimEnd('/')
    private val epoch = AtomicLong()
    private val cookies = mutableListOf<Cookie>()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(15, TimeUnit.SECONDS)
.build()
    init { val uri = java.net.URI(base); require(isSecureApiBaseUrl(base) && uri.path.orEmpty().isEmpty()) }
    fun cancelFlow() { synchronized(cookies) { epoch.incrementAndGet(); cookies.clear() } }
    fun current(generation: Long) = epoch.get() == generation
    suspend fun request(path: String, body: JSONObject? = null, bearer: String? = null): NativeAuthResponse = withContext(Dispatchers.IO) {
        require(path.startsWith('/') && !path.contains("..") && !path.contains('?'))
        val generation = epoch.get()
        val request = Request.Builder().url("$base/api/auth$path").header("Accept", "application/json")
        if(path.startsWith("/two-factor/")) synchronized(cookies) {
            val url="$base/api/auth$path".toHttpUrl()
            val header=cookies.filter { it.matches(url) && it.expiresAt>System.currentTimeMillis() }.joinToString("; ") { "${it.name}=${it.value}" }
            if(header.isNotBlank())request.header("Cookie",header)
        }
        bearer?.let { request.header("Authorization", "Bearer $it") }
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType())) else request.get()
        val result = try {
            http.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) throw NativeAuthException(response.code)
                val source=response.body?.source()
                source?.request(262145)
                if((source?.buffer?.size ?: 0)>262144)throw NativeAuthException(503)
                val raw=source?.readUtf8().orEmpty()
                if(path=="/get-session" && raw=="null")throw NativeAuthException(401)
                Pair(NativeAuthResponse(if (raw == "null" || raw.isBlank()) JSONObject() else JSONObject(raw), response.header("set-auth-token"), generation),Cookie.parseAll(response.request.url,response.headers))
            }
        } catch (e: NativeAuthException) { throw e }
        catch (_: java.io.IOException) { throw NativeAuthException(503) }
        catch (_: org.json.JSONException) { throw NativeAuthException(503) }
        currentCoroutineContext().ensureActive()
        synchronized(cookies) {
            check(current(generation)) { "Session changed" }
            result.second.forEach { value -> cookies.removeAll { it.name==value.name }; if(value.domain=="$base/".toHttpUrl().host)cookies.add(value) }
        }
        result.first
    }
    suspend fun validate(token: String): String {
        if (token.split('.').size != 2) throw NativeAuthException(401)
        val data = request("/get-session", bearer = token).data
        val session=data.optJSONObject("session") ?: throw NativeAuthException(503)
        val user=data.optJSONObject("user") ?: throw NativeAuthException(503)
        val userId=user.optString("id")
        val expiry=runCatching { java.time.Instant.parse(session.optString("expiresAt")).toEpochMilli() }.getOrElse { throw NativeAuthException(503) }
        if(expiry<=System.currentTimeMillis())throw NativeAuthException(401)
        if(userId.isBlank() || session.optString("id").isBlank() || session.optString("userId")!=userId)throw NativeAuthException(503)
        return userId
    }
}
