package pro.ezboss.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ApiIdentityTest {
    @Test fun bearerIdentityRequestPreservesApiPrefix() = runBlocking {
        var seen = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            seen = true
            assertEquals("https://staging.example/api/users/me", chain.request().url.toString())
            assertEquals("Bearer fixture-token", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":42,"status":"active","displayName":"Owner"}""".toResponseBody()).build()
        }.build()
        assertEquals("42", ApiClient("https://staging.example/api/", http).currentUser("fixture-token").id)
        assertTrue(seen)
    }

    @Test fun inactiveOrMissingStatusDeniesIdentity() = runBlocking {
        for (body in listOf("""{"id":42,"status":"suspended"}""", """{"id":42}""")) {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody()).build()
            }.build()
            try { ApiClient("https://staging.example", http).currentUser("fixture-token"); fail("Expected denial") }
            catch (error: ApiException) { assertEquals(403, error.statusCode) }
        }
    }

    @Test fun malformedIdentityNeverOpensProtectedNavigation() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"status":"active"}""".toResponseBody()).build()
        }.build()
        try { ApiClient("https://staging.example", http).currentUser("fixture-token"); fail("Expected malformed identity rejection") }
        catch (_: java.io.IOException) {}
    }

    @Test fun rejectsUnsafeConfigurationBeforeTransport() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { throw AssertionError("Transport must not run") }.build()
        try { ApiClient("http://staging.example", http).currentUser("fixture-token"); fail("Expected invalid configuration") }
        catch (_: IllegalStateException) {}
        try { ApiClient("https://staging.example", http).currentUser(""); fail("Expected session error") }
        catch (error: ApiException) { assertEquals(401, error.statusCode) }
    }

    @Test fun apiErrorsNeverExposeServerBodies() = runBlocking {
        for (status in listOf(401, 403, 404, 503)) {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Error")
                    .body("private-provider-payload".toResponseBody()).build()
            }.build()
            try { ApiClient("https://staging.example", http).currentUser("fixture-token"); fail("Expected API error") }
            catch (error: ApiException) {
                assertEquals(status, error.statusCode)
                assertFalse(error.message.orEmpty().contains("private-provider-payload"))
            }
        }
    }
}
