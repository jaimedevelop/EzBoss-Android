package pro.ezboss.mobile.auth

import kotlinx.coroutines.CancellationException

// Accessed on the main thread by the auth ViewModel. Late responses from a
// removed protected screen must not deliver data or sign out a newer account.
internal class SessionEpoch {
    var current: Long = 0; private set
    fun advance() { current++ }
    fun requireCurrent(expected: Long) {
        if (expected != current) throw CancellationException("Session changed")
    }
}
