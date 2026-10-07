package pro.ezboss.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountNamesTest {
    @Test fun rejectsMissingAndNullNames() {
        listOf(null, "", "  ", "null", " Null ", Any()).forEach { assertNull(usableName(it)) }
        assertEquals("Joaquin", usableName(" Joaquin "))
    }

    @Test fun prefersContractorProfileAndFallsBackToAccountName() {
        assertEquals("Joaquin", dashboardGreetingName(" Joaquin ", null))
        assertEquals("Jaime", dashboardGreetingName("Jaime", "Different Name"))
        assertEquals("Contractor", dashboardGreetingName(null, " Contractor   Account "))
        assertEquals("Jaime", dashboardGreetingName("null", "Jaime Smith"))
        assertEquals("User", dashboardGreetingName(null, "Null"))
        assertEquals("User", dashboardGreetingName(null, "  "))
    }
}
