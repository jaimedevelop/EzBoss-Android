package pro.ezboss.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimateContractsTest {
    @Test fun estimateAndClientStatusFiltersUseSeparateApiFields() {
        assertEquals("change-order", estimateStateQuery("Change order"))
        assertEquals("accepted", clientStateQuery("Accepted"))
        assertEquals("none", clientStateQuery("No client status"))
        assertEquals(null, estimateStateQuery("Accepted"))
        assertEquals(null, clientStateQuery("Invoice"))
    }

    @Test fun tabsMatchWebOrderAndAllowSeparateSections() {
        val regular = estimateDashboardTabs(false)
        assertEquals(listOf("Estimate", "Client View", "Change Orders", "Payments", "Timeline", "Claims", "Communication", "History"), regular.map { it.label })
        assertTrue(regular.take(2).all { it.enabled })
        assertTrue(regular.all { it.enabled })
        assertFalse(estimateDashboardTabs(true).any { it.label == "Change Orders" })
    }
}
