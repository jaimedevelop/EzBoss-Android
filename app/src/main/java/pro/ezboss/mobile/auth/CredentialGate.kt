package pro.ezboss.mobile.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Serialize credential reads/rotation, login persistence, and local deletion.
internal class CredentialGate {
    private val mutex = Mutex()
    suspend fun <T> run(operation: suspend () -> T): T = mutex.withLock { operation() }
}
