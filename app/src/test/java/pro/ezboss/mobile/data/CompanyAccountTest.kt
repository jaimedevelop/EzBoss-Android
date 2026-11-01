package pro.ezboss.mobile.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanyAccountTest {
    private val owner = "00000000-0000-4000-8000-000000000001"
    @Test fun ambiguityDoesNotSelectFirstCompany() {
        val result = mapCompanyAccount(JSONObject("""{"memberships":[{"id":"$owner","kind":"owner","isActive":true,"companyIsActive":true}]}"""))
        assertNull(result.selected)
        assertTrue(result.memberships.single().available)
    }
    @Test fun suspendedEmployeeIsNotSelectable() {
        val result = mapCompanyAccount(JSONObject("""{"memberships":[{"id":"$owner","kind":"employee","isActive":true,"employeeIsActive":false,"companyIsActive":true}]}"""))
        assertFalse(result.memberships.single().available)
    }
    @Test(expected = java.io.IOException::class) fun contextCannotPointOutsideMemberships() {
        mapCompanyAccount(JSONObject("""{"memberships":[],"companyContext":{"membershipId":"$owner"}}"""))
    }
    @Test(expected = java.io.IOException::class) fun suspendedContextIsRejected() {
        mapCompanyAccount(JSONObject("""{"memberships":[{"id":"$owner","kind":"owner","isActive":false,"companyIsActive":true}],"companyContext":{"membershipId":"$owner"}}"""))
    }
}
