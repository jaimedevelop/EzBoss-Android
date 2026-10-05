package pro.ezboss.mobile

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DashboardDateTest {
    @Test fun sentTimestampsUseBusinessTimezone() {
        assertEquals(LocalDate.of(2026, 10, 4), dashboardDate("2026-10-05T01:30:00Z", ZoneId.of("America/New_York")))
    }
    @Test fun dateOnlyValuesDoNotShiftAndInvalidDatesStayUnknown() {
        assertEquals(LocalDate.of(2026, 10, 5), dashboardDate("2026-10-05", ZoneId.of("America/Los_Angeles")))
        assertNull(dashboardDate("2026-02-30", ZoneId.of("UTC")))
        assertNull(dashboardDate("", ZoneId.of("UTC")))
    }
}
