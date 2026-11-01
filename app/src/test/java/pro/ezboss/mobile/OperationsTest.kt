package pro.ezboss.mobile
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
class OperationsTest {
    @Test fun listEnvelopesRetainNumericInventoryIds() {
        for(key in listOf("items","jobs","workOrders","purchaseOrders","collections","laborItems")) {
            val rows=operationRows(JSONObject().put(key,JSONArray().put(JSONObject().put("id",7))))
            assertEquals(7,rows.first().getInt("id"))
        }
    }
    @Test(expected=IllegalArgumentException::class) fun malformedListIsNotAnEmptySuccess() {operationRows(JSONObject().put("unexpected",JSONArray()))}
}
