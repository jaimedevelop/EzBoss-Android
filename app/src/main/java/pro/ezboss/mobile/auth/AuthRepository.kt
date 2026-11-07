package pro.ezboss.mobile.auth

import android.app.Activity
import android.content.Context
import com.auth0.android.callback.Callback
import com.auth0.android.Auth0
import com.auth0.android.authentication.AuthenticationAPIClient
import com.auth0.android.authentication.AuthenticationException
import com.auth0.android.authentication.storage.CredentialsManagerException
import com.auth0.android.authentication.storage.SecureCredentialsManager
import com.auth0.android.authentication.storage.SharedPreferencesStorage
import com.auth0.android.provider.WebAuthProvider
import com.auth0.android.result.Credentials
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import pro.ezboss.mobile.BuildConfig
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MissingAuthConfiguration : IllegalStateException("Sign in is not configured. Please contact your administrator.")

data class SessionCredentials(val accessToken: String) {
    override fun toString() = "SessionCredentials([redacted])"
}

class AuthRepository(context: Context, private val config: AuthConfiguration) {
    val usesBetterAuth get() = config.provider == "better-auth"
    private val native = if (usesBetterAuth && config.ready) BetterAuthClient(config.authOrigin) else null
    private val secure = if (usesBetterAuth) SecureCredentialStore(context.applicationContext) else null
    suspend fun nativeRequest(path: String, body: org.json.JSONObject? = null) = native!!.request(path, body).data
    suspend fun nativeSignIn(email: String, password: String, code: String? = null, backup: Boolean = false): Boolean {
        val client = native ?: throw MissingAuthConfiguration()
        if (code == null) { clearCredentials() }
        val response = if (code == null) client.request("/sign-in/email", org.json.JSONObject().put("email", email).put("password", password))
            else client.request(if (backup) "/two-factor/verify-backup-code" else "/two-factor/verify-totp", org.json.JSONObject().put("code", code).put("trustDevice", false))
        if (response.data.optBoolean("twoFactorRedirect")) return false
        val token = response.signedToken ?: throw NativeAuthException(401)
        val validatedUser=client.validate(token)
        if(response.data.optJSONObject("user")?.optString("id")!=validatedUser)throw NativeAuthException(401)
        credentialGate.run {
            currentCoroutineContext().ensureActive()
            check(client.current(response.generation)) { "Session changed" }
            secure!!.write(token)
        }
        client.cancelFlow() // Drop intermediate cookies only after complete validated storage.
        return true
    }
    private val credentialGate = CredentialGate()
    private val account = if (config.ready && !usesBetterAuth) Auth0.getInstance(config.clientId, config.domain) else null
    private val manager: SecureCredentialsManager? = account?.let {
        SecureCredentialsManager(AuthenticationAPIClient(it), context.applicationContext, SharedPreferencesStorage(context.applicationContext))
    }

    private fun requireConfigured() {
        if (!config.ready || (!usesBetterAuth && manager == null)) throw MissingAuthConfiguration()
    }

    suspend fun login(activity: Activity, createAccount: Boolean = false): SessionCredentials {
        requireConfigured()
        check(!usesBetterAuth) { "Use the native credentials form" }
        val credentials = suspendCancellableCoroutine<Credentials> { continuation ->
            WebAuthProvider.login(account!!)
                .withScheme(BuildConfig.APPLICATION_ID)
                .withAudience(config.audience)
                .withScope("openid profile email offline_access")
                .withParameters(if (createAccount) mapOf("screen_hint" to "signup", "prompt" to "login") else mapOf("prompt" to "login"))
                .start(activity, object : Callback<Credentials, AuthenticationException> {
                    override fun onSuccess(result: Credentials) {
                        if (continuation.isActive) continuation.resume(result)
                    }
                    override fun onFailure(error: AuthenticationException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                })
        }
        currentCoroutineContext().ensureActive()
        credentialGate.run {
            currentCoroutineContext().ensureActive()
            manager!!.saveCredentials(credentials)
        }
        return SessionCredentials(credentials.accessToken)
    }

    suspend fun restore(): SessionCredentials? = credentialGate.run {
        requireConfigured()
        if (usesBetterAuth) {
            val token = secure!!.read() ?: return@run null
            try { native!!.validate(token) } catch(e:NativeAuthException) { if(e.status==401)secure.clear();throw e }
            return@run SessionCredentials(token)
        }
        if (!manager!!.hasValidCredentials()) {
            manager.clearCredentials()
            return@run null
        }
        try { SessionCredentials(readCredentials().accessToken) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { manager.clearCredentials(); null }
    }

    suspend fun accessToken(): String = credentialGate.run {
        requireConfigured()
        if (usesBetterAuth) {
            val token = secure!!.read() ?: throw NativeAuthException(401)
            native!!.validate(token); token
        } else readCredentials().accessToken
    }

    // Wait for the SDK callback even if the caller is cancelled: renewal persists
    // rotated tokens before invoking it. A queued logout must clear AFTER that save.
    private suspend fun readCredentials(): Credentials = suspendCoroutine { continuation ->
        manager!!.getCredentials(null, 30, object : Callback<Credentials, CredentialsManagerException> {
            override fun onSuccess(result: Credentials) { continuation.resume(result) }
            override fun onFailure(error: CredentialsManagerException) { continuation.resumeWithException(error) }
        })
    }

    suspend fun clearCredentials() = withContext(NonCancellable) {
        credentialGate.run { native?.cancelFlow(); secure?.clear(); manager?.clearCredentials(); Unit }
    }

    // App sign-out never opens a browser. The next login explicitly authenticates.
    suspend fun signOut() {
        requireConfigured()
        if (usesBetterAuth) {
            val token = credentialGate.run { runCatching { secure!!.read() }.getOrNull() }
            clearCredentials()
            if (token != null) try { native!!.request("/sign-out", org.json.JSONObject(), token) } catch (_: NativeAuthException) { }
        } else clearCredentials()
    }
}

fun authConfiguration() = AuthConfiguration(BuildConfig.AUTH0_DOMAIN, BuildConfig.AUTH0_CLIENT_ID, BuildConfig.AUTH0_AUDIENCE, BuildConfig.API_BASE_URL, BuildConfig.AUTH_PROVIDER, BuildConfig.AUTH_ORIGIN)
