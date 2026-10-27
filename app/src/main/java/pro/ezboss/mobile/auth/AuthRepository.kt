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
    private val credentialGate = CredentialGate()
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
                .withScheme(BuildConfig.APPLICATION_ID)
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
        currentCoroutineContext().ensureActive()
        credentialGate.run {
            currentCoroutineContext().ensureActive()
            manager!!.saveCredentials(credentials)
        }
        return SessionCredentials(credentials.accessToken)
    }

    suspend fun restore(): SessionCredentials? = credentialGate.run {
        requireConfigured()
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
        readCredentials().accessToken
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
        credentialGate.run { manager?.clearCredentials(); Unit }
    }

    suspend fun signOut(activity: Activity) {
        if (manager == null || account == null) throw MissingAuthConfiguration()
        // Clear local tokens even if the browser logout is cancelled or unavailable.
        clearCredentials()
        suspendCancellableCoroutine<Unit> { continuation ->
            WebAuthProvider.logout(account)
                .withScheme(BuildConfig.APPLICATION_ID)
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
