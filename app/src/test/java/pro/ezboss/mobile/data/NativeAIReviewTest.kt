package pro.ezboss.mobile.data
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class NativeAIReviewTest {
    @Test fun reviewValidatesTypedReferencesAndHumanNames() {
        val inventory=JSONObject("""{"products":[{"id":7,"name":"TEST product"}],"tools":[]}""")
        val proposal=JSONObject("""{"name":"TEST kit","description":"Review","items":[{"contentType":"products","itemId":7,"quantity":2}]}""")
        assertEquals("TEST product",nativeAIReview(proposal,inventory).getJSONArray("preview").getJSONObject(0).getString("name"))
        proposal.getJSONArray("items").getJSONObject(0).put("contentType","tools")
        try{nativeAIReview(proposal,inventory);fail("Unknown typed reference must be rejected")}catch(_:IllegalArgumentException){}
    }
}
