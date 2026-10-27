package pro.ezboss.mobile.auth

import org.junit.Assert.*
import org.junit.Test

class AuthConfigurationTest {
    private val valid = AuthConfiguration("tenant.example", "native-id", "https://api.example", "https://staging.example/api")

    @Test fun requiresAllRuntimeConfiguration() {
        assertTrue(valid.ready)
        assertFalse(valid.copy(domain = "").ready)
        assertFalse(valid.copy(clientId = "").ready)
        assertFalse(valid.copy(audience = "").ready)
        assertFalse(valid.copy(apiBaseUrl = "").ready)
    }

    @Test fun rejectsUnsafeApiDestinations() {
        for (url in listOf("http://api.example", "https://u:p@api.example", "https://api.example?token=x", "https://api.example#fragment", "https:///", "https://api.example:99999", "https://api.example/bad path")) {
            assertFalse(url, valid.copy(apiBaseUrl = url).ready)
        }
    }

    @Test fun rejectsMalformedAndPlaceholderIdentityConfiguration() {
        for (domain in listOf("https://tenant.example", "tenant.example/path", "-tenant.example", "YOUR_TENANT.auth0.com")) {
            assertFalse(domain, valid.copy(domain = domain).ready)
        }
        assertFalse(valid.copy(clientId = "YOUR_NATIVE_ID").ready)
        assertFalse(valid.copy(audience = "$(UNRESOLVED)").ready)
    }

    @Test fun callbackUsesActualApplicationIdAndHasExactPath() {
        assertEquals("pro.ezboss.mobile://tenant.example/android/pro.ezboss.mobile/callback", valid.callbackUrl("pro.ezboss.mobile"))
        assertEquals("pro.ezboss.staging://tenant.example/android/pro.ezboss.staging/callback", valid.callbackUrl("pro.ezboss.staging"))
    }

    @Test fun diagnosticsNeverContainConfigurationValues() {
        val bad = valid.copy(apiBaseUrl = "https://user:private-password@api.example")
        assertEquals(listOf("apiBaseUrl"), bad.invalidFields)
        assertFalse(bad.invalidFields.joinToString().contains("private-password"))
    }
}
