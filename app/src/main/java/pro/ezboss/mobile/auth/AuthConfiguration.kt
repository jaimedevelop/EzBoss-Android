package pro.ezboss.mobile.auth

import java.net.URI

data class AuthConfiguration(val domain: String, val clientId: String, val audience: String, val apiBaseUrl: String) {
    val invalidFields: List<String> get() = buildList {
        if (!isPublicValue(domain) || !domain.matches(Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}"))) add("auth0Domain")
        if (!isPublicValue(clientId) || !clientId.matches(Regex("[A-Za-z0-9_-]+"))) add("auth0ClientId")
        if (!isPublicValue(audience)) add("auth0Audience")
        if (!isPublicValue(apiBaseUrl) || !isSecureApiBaseUrl(apiBaseUrl)) add("apiBaseUrl")
    }
    val ready get() = invalidFields.isEmpty()

    fun callbackUrl(applicationId: String): String {
        check(ready) { "Sign-in configuration is invalid." }
        require(applicationId.matches(Regex("[a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+")))
        return "$applicationId://$domain/android/$applicationId/callback"
    }
}

internal fun isPublicValue(value: String): Boolean = value.isNotBlank() &&
    value.none(Char::isWhitespace) && !value.contains("YOUR_", ignoreCase = true) &&
    !value.contains("CHANGE_ME", ignoreCase = true) && !value.contains("$(")

internal fun isSecureApiBaseUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)
}.getOrDefault(false)
