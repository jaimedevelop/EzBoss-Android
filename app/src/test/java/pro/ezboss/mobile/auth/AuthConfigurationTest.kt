package pro.ezboss.mobile.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthConfigurationTest {
    @Test fun requiresAllRuntimeConfiguration() {
        assertFalse(AuthConfiguration("", "native-id", "audience", "https://api.example").ready)
        assertFalse(AuthConfiguration("tenant.example", "", "audience", "https://api.example").ready)
        assertFalse(AuthConfiguration("tenant.example", "native-id", "", "https://api.example").ready)
        assertFalse(AuthConfiguration("tenant.example", "native-id", "audience", "").ready)
        assertTrue(AuthConfiguration("tenant.example", "native-id", "audience", "https://api.example").ready)
    }
}
