package pro.ezboss.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import pro.ezboss.mobile.ui.theme.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

private val financeKeys=listOf("date","estimate","poNumber","invoice","invoiceValue","payment","paymentMethod","paymentDate","serviceAddress","jobCompletionDate","completedFor","agent")
private val projectKeys=listOf("name","startDate","estimatedEndDate","description","notes")
private val addressKeys=listOf("address","address2","city","state","zipCode")
@Composable fun BusinessRecordsScreen(model:AuthViewModel,destination:String) {
    val project=destination=="projects"
    val path=if(project) "projects" else "finance-records"
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var clients by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var values by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var clientId by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("planning") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()
    val fields=if(project)projectKeys+addressKeys else financeKeys
    LaunchedEffect(reload) {
        busy=true;error=null;rows=emptyList();selected=null
        try { rows=operationRows(model.operation(path));if(project) {val groups=model.operation("clients") as JSONObject;clients=groups.keys().asSequence().flatMap{key->val a=groups.getJSONArray(key);(0 until a.length()).map{a.getJSONObject(it)}}.toList()} }
        catch(e:Exception){error=e.message} finally{busy=false}
    }
    fun edit(row:JSONObject) {
        selected=row;message=null
        val data=if(project)row else row.optJSONObject("data") ?: JSONObject()
        values=fields.associateWith{ key->(if(project && key in addressKeys)row.optJSONObject("serviceAddress")?.optString(key) else data.optString(key)).orEmpty().takeUnless{it=="null"}.orEmpty() }
        clientId=row.optString("clientId","");status=row.optString("status","planning")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        PageHeading(if(project) "Projects" else "Finances", if(project) "Projects, clients and progress" else "Records and linked payment summaries")
        if(busy)CircularProgressIndicator()
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)};message?.let{Text(it)}
        if(selected==null) {
            TextButton(onClick={reload++},enabled=!busy){Text("Refresh / retry")}
            Button(onClick={edit(if(project)JSONObject() else JSONObject().put("id",UUID.randomUUID().toString()).put("data",JSONObject()))},enabled=!busy){Text("Create "+if(project)"project" else "manual record")}
            rows.forEach { row -> WorkspaceRecord(if(project) row.optString("name") else (row.optJSONObject("data")?.optString("invoice")?.takeIf { it.isNotBlank() } ?: row.optString("id")), row.optString("status", "")) { edit(row) } }
            if(rows.isEmpty() && !busy && error==null)Text("No records in this company.")
        } else {
            val row=selected!!
            if(project) {
                Text("Client: "+clients.firstOrNull{it.optString("id")==clientId}?.optString("name").orEmpty())
                clients.forEach{client->TextButton(onClick={clientId=client.optString("id")},enabled=!busy){Text(client.optString("name"))}}
                Text("Status: $status")
                listOf("planning","active","on-hold","completed","invoiced","cancelled").chunked(2).forEach{group->Row{group.forEach{v->TextButton(onClick={status=v},enabled=!busy){Text(v)}}}}
            } else {
                val summary=row.optJSONObject("paymentSummary")
                if(summary!=null)Text("Ledger: "+summary.optString("paid")+" / "+summary.optString("total")+" · "+summary.optString("status"))
                Text("Manual record fields do not alter the invoice payment ledger. Record payments or refunds on the document.")
            }
            fields.forEach{key->OutlinedTextField(values[key].orEmpty(),{values=values+(key to it)},label={Text(key.replace(Regex("([a-z])([A-Z])"),"$1 $2"))},modifier=Modifier.fillMaxWidth(),enabled=!busy)}
            Button(enabled=!busy,onClick={scope.launch{
                busy=true;error=null;message=null
                try {
                    if(project) {
                        val body=JSONObject();projectKeys.forEach{body.put(it,values[it].orEmpty())};body.put("clientId",clientId).put("status",status)
                        val address=JSONObject();addressKeys.forEach{address.put(it,values[it].orEmpty())};body.put("serviceAddress",address)
                        if(row.has("id"))body.put("version",row.getString("version"))
                        model.operation(path+(if(row.has("id"))"/"+row.getString("id") else ""),if(row.has("id"))"PATCH" else "POST",body)
                    } else {
                        val data=JSONObject();financeKeys.forEach{data.put(it,values[it].orEmpty())}
                        if(row.has("version"))model.operation(path+"/"+row.getString("id"),"PUT",JSONObject().put("version",row.getInt("version")).put("data",data))
                        else model.operation(path,"POST",JSONObject().put("records",JSONArray().put(JSONObject().put("id",row.getString("id")).put("data",data))))
                    }
                    message="Saved";reload++
                } catch(e:Exception){error=e.message ?: "Save was not confirmed. Reload before retrying."} finally{busy=false}
            }}){Text("Save")}
            TextButton(onClick={selected=null;reload++},enabled=!busy){Text("Discard / reload")}
        }
    }
}
