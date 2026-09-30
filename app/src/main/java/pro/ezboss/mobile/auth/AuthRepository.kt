package pro.ezboss.mobile.auth

import android.app.Activity
import android.content.Context
import com.auth0.android.callback.Callback
import com.auth0.android.Auth0
import com.auth0.android.authentication.AuthenticationAPIClient
import com.auth0.android.authentication.AuthenticationException
import com.auth0.android.authentication.storage.SecureCredentialsManager
import com.auth0.android.authentication.storage.SharedPreferencesStorage
import com.auth0.android.provider.WebAuthProvider
import com.auth0.android.result.Credentials
import kotlinx.coroutines.suspendCancellableCoroutine
import pro.ezboss.mobile.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MissingAuthConfiguration : IllegalStateException("Sign in is not configured. Please contact your administrator.")

data class AuthConfiguration(val domain: String, val clientId: String, val audience: String, val apiBaseUrl: String) {
    val ready get() = domain.isNotBlank() && clientId.isNotBlank() && audience.isNotBlank() && apiBaseUrl.isNotBlank()
}

data class SessionCredentials(val accessToken: String)

class AuthRepository(context: Context, private val config: AuthConfiguration) {
    private val account = if (config.ready) Auth0.getInstance(config.clientId, config.domain) else null
    private val manager: SecureCredentialsManager? = account?.let {
        SecureCredentialsManager(AuthenticationAPIClient(it), context.applicationContext, SharedPreferencesStorage(context.applicationContext))
    }

    private fun requireConfigured() {
        if (manager == null) throw MissingAuthConfiguration()
    }

    suspend fun login(activity: Activity): SessionCredentials {
        requireConfigured()
        val credentials = suspendCancellableCoroutine<Credentials> { continuation ->
            WebAuthProvider.login(account!!)
                .withScheme("pro.ezboss.mobile")
                .withAudience(config.audience)
                .withScope("openid profile email offline_access")
                .start(activity, object : Callback<Credentials, AuthenticationException> {
                    override fun onSuccess(result: Credentials) {
                        if (continuation.isActive) continuation.resume(result)
                    }
                    override fun onFailure(error: AuthenticationException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                })
        }
        manager!!.saveCredentials(credentials)
        return SessionCredentials(credentials.accessToken)
    }

    suspend fun restore(): SessionCredentials? {
        requireConfigured()
        if (!manager!!.hasValidCredentials()) return null
        return try { SessionCredentials(manager.awaitCredentials().accessToken) }
        catch (_: Exception) { manager.clearCredentials(); null }
    }

    suspend fun accessToken(): String {
        requireConfigured()
        return manager!!.awaitCredentials().accessToken
    }

    fun clearCredentials() { manager?.clearCredentials() }

    suspend fun signOut(activity: Activity) {
        if (manager == null || account == null) throw MissingAuthConfiguration()
        // Clear local tokens even if the browser logout is cancelled or unavailable.
        manager.clearCredentials()
        suspendCancellableCoroutine<Unit> { continuation ->
            WebAuthProvider.logout(account)
                .withScheme("pro.ezboss.mobile")
                .start(activity, object : Callback<Void?, com.auth0.android.authentication.AuthenticationException> {
                override fun onSuccess(result: Void?) { if (continuation.isActive) continuation.resume(Unit) }
                override fun onFailure(error: com.auth0.android.authentication.AuthenticationException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            })
        }
    }
}

fun authConfiguration() = AuthConfiguration(BuildConfig.AUTH0_DOMAIN, BuildConfig.AUTH0_CLIENT_ID, BuildConfig.AUTH0_AUDIENCE, BuildConfig.API_BASE_URL)
