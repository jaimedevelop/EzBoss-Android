package pro.ezboss.mobile.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CredentialGateTest {
    @Test fun logoutWaitsForRefreshPersistenceThenDeletesRotatedCredential() = runBlocking {
        val gate = CredentialGate()
        val renewed = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var stored: String? = "old"
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run {
                renewed.await()
                stored = "rotated"
                events.add("renewed")
            }
        }
        val logout = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run { stored = null; events.add("cleared") }
        }
        assertFalse(logout.isCompleted)
        renewed.complete(Unit)
        refresh.join(); logout.join()
        assertEquals(listOf("renewed", "cleared"), events)
        assertEquals(null, stored)
    }
    @Test fun cancelledSDKWaitStillFinishesBeforeLogoutDeletion() = runBlocking {
        val gate = CredentialGate()
        var callback: Continuation<Unit>? = null
        var stored: String? = "old"
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run {
                suspendCoroutine<Unit> { callback = it }
                stored = "rotated"
            }
        }
        refresh.cancel()
        val logout = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run { stored = null }
        }
        assertFalse(logout.isCompleted)
        callback!!.resume(Unit)
        refresh.join(); logout.join()
        assertEquals(null, stored)
    }

    @Test fun credentialDescriptionNeverContainsToken() {
        assertFalse(SessionCredentials("private-token-value").toString().contains("private-token-value"))
    }
}
