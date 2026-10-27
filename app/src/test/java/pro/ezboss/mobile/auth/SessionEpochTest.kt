package pro.ezboss.mobile.auth

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionEpochTest {
    @Test fun currentRequestsCanComplete() {
        val session = SessionEpoch()
        session.requireCurrent(session.current)
    }
    @Test fun logoutAndAccountSwitchRejectOldResponsesAndFailures() {
        val session = SessionEpoch()
        val old = session.current
        session.advance()
        session.advance()
        assertThrows(CancellationException::class.java) { session.requireCurrent(old) }
        assertEquals(2L, session.current)
        session.requireCurrent(session.current)
    }
}
