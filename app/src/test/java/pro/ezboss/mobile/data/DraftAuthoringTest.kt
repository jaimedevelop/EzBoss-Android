package pro.ezboss.mobile.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DraftAuthoringTest {
    @Test fun signedAdjustmentRemainsSigned() {
        val draft=JSONObject("""{"customerName":"TEST","taxRate":0,"lineItems":[{"type":"custom","quantity":1,"unitPrice":-20,"adjustmentKind":"group"}]}""")
        val line=draftPayload(draft).getJSONArray("lineItems").getJSONObject(0)
        assertEquals(-20.0,line.getDouble("total"),0.001)
        assertEquals("group",line.getString("adjustmentKind"))
    }
    @Test fun roundsManualLinesAndPreservesMetadata() {
        val input = JSONObject("""{"customerName":"TEST","taxRate":"8.25","lineItems":[{"quantity":"3","unitPrice":"0.335","type":"manual","notes":"keep"}]}""")
        val body = draftPayload(input)
        assertEquals(1.02, body.getJSONArray("lineItems").getJSONObject(0).getDouble("total"), 0.0)
        assertEquals("keep", body.getJSONArray("lineItems").getJSONObject(0).getString("notes"))
        assertTrue(body.getBoolean("draftOnly"))
        assertFalse(input.getJSONArray("lineItems").getJSONObject(0).has("total"))
    }
    @Test fun excludesIdentityAndRetainsConcurrencyToken() {
        val body = draftPayload(JSONObject("""{"id":7,"customerName":"TEST","taxRate":0,"lineItems":[],"expectedUpdatedAt":"version","draftOnly":true,"invoiceNumber":"do-not-write"}"""))
        assertEquals("version", body.getString("expectedUpdatedAt"))
        assertFalse(body.has("id")); assertFalse(body.has("invoiceNumber")); assertFalse(body.has("estimateState"))
    }
    @Test fun rejectsInvalidAmounts() {
        for (price in listOf("-1", "invalid")) {
            try {
                draftPayload(JSONObject("""{"customerName":"TEST","taxRate":0,"lineItems":[{"quantity":1,"unitPrice":"$price"}]}"""))
                fail("Expected invalid price rejection")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun sentEditorUsesRevisionPolicyWithoutDowngradingDocument() {
        val body = draftPayload(JSONObject("""{"id":7,"estimateState":"estimate","customerName":"TEST","taxRate":0,"lineItems":[],"expectedUpdatedAt":"version"}"""))
        assertFalse(body.getBoolean("draftOnly"))
        assertTrue(body.getBoolean("nativeAuthoring"))
        assertFalse(body.has("estimateState"))
        assertEquals("version", body.getString("expectedUpdatedAt"))
    }

}
