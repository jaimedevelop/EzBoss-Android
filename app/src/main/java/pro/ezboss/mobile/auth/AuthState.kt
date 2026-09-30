package pro.ezboss.mobile.auth

import pro.ezboss.mobile.data.Account

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class Authenticated(val account: Account) : AuthState
    data class Error(
        val message: String,
        val sessionExpired: Boolean = false,
        val retryLogin: Boolean = false,
    ) : AuthState
}
