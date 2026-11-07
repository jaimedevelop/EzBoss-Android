package pro.ezboss.mobile.auth

import org.junit.Assert.*
import org.junit.Test

class BetterAuthConfigurationTest {
    @Test fun typedNativeConfigurationDoesNotInventLegacySubjects() {
        val config=AuthConfiguration("","","","https://alpha.example/api","better-auth","https://alpha.example")
        assertTrue(config.ready)
        for(origin in listOf("http://alpha.example","https://user:private@alpha.example","https://alpha.example/path","https://alpha.example?code=private")) {
            val invalid=config.copy(authOrigin=origin)
            assertFalse(invalid.ready)
            assertEquals(listOf("authOrigin"),invalid.invalidFields)
        }
        assertFalse(config.copy(provider="unknown").ready)
    }
    @Test fun legacyAndNativeCredentialNamespacesRemainDistinct() {
        val legacy=AuthConfiguration("tenant.auth0.com","public-client","https://api.example","https://api.example")
        assertTrue(legacy.ready)
        assertFalse(legacy.copy(provider="better-auth").ready)
        assertEquals("SessionCredentials([redacted])",SessionCredentials("private-fixture-token").toString())
    }
}
