package pro.ezboss.mobile.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EstimateMappingTest {
    @Test fun mapsDistinctDocumentAndClientStatesAndInvoiceNumber() {
        val estimate = mapEstimateListItem(JSONObject("""{"id":9,"estimateNumber":"EST-9","estimateState":"estimate","clientState":"accepted","customerName":"Long Customer Name","total":"125.50"}"""))
        assertEquals("EST-9", estimate.number)
        assertEquals("estimate", estimate.estimateState)
        assertEquals("accepted", estimate.clientState)
        assertEquals("Long Customer Name", estimate.customer)
        assertEquals(125.5, estimate.total, 0.001)

        val invoice = mapEstimateListItem(JSONObject("""{"id":10,"estimateNumber":"EST-10","invoiceNumber":"INV-10","estimateState":"invoice","clientState":null,"total":90}"""))
        assertEquals("INV-10", invoice.number)
        assertNull(invoice.clientState)
    }
}
