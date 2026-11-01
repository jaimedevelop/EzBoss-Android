package pro.ezboss.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private val profileFields = listOf("firstName", "lastName", "phone", "company", "companyAddress", "currency", "timezone")
private val clientFields = listOf("name", "email", "phoneMobile", "companyName", "billingAddress", "billingCity", "billingState", "billingZipCode", "notes")
private val employeeFields = listOf("name", "email", "phoneMobile", "employeeRole", "hireDate", "address", "city", "state", "zipCode", "emergencyContactName", "emergencyContactPhone", "notes")
private fun fieldTitle(key: String) = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

@Composable fun PeopleSettingsScreen(model: AuthViewModel, settings: Boolean) {
    var invitations by remember { mutableStateOf(false) }
    if(!settings && invitations) { NativeInvitations(model) { invitations=false };return }
    var lifecycle by remember { mutableStateOf(false) }
    if(settings && lifecycle) { AccountLifecycleScreen(model) { lifecycle=false };return }
    var path by remember { mutableStateOf(if(settings) "profile" else "clients") }
    var rows by remember(path) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var editing by remember(path) { mutableStateOf<JSONObject?>(null) }
    var values by remember(path) { mutableStateOf<Map<String,String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var message by remember(path) { mutableStateOf<String?>(null) }
    var search by remember(path) { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()
    val fields=if(path=="account/employee-profile") listOf("name","phoneMobile","phoneOther","address","address2","city","state","zipCode","emergencyContactName","emergencyContactPhone") else if(path=="profile") profileFields else if(path=="employees") employeeFields else clientFields
    LaunchedEffect(path,reload) {
        busy=true;error=null; rows=emptyList();editing=null
        try {
            val result=model.operation(path)
            if(settings) {
                val row=if(path=="account/employee-profile") (result as JSONObject).getJSONObject("profile") else result as JSONObject;editing=row
                values=fields.associateWith { row.optString(it).takeUnless { v->v=="null" }.orEmpty() }
            } else if(result is JSONObject) {
                rows=result.keys().asSequence().flatMap { key -> val list=result.optJSONArray(key); (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) } }.toList()
            }
        } catch(e:Exception) { error=e.message ?: "Unable to load. Retry." } finally { busy=false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(if(settings) "Profile and preferences" else "People",style=MaterialTheme.typography.headlineMedium)
        if(settings) Row { TextButton(onClick={path="profile"}){Text("Company profile")};TextButton(onClick={path="account/employee-profile"}){Text("My employee profile")} }
        if(settings) TextButton(onClick={lifecycle=true}){Text("Account requests and terms")}
        if(!settings) TextButton(onClick={invitations=true}){Text("Company invitations")}
        if(!settings) Row { TextButton(onClick={path="clients"}){Text("Clients")}; TextButton(onClick={path="employees"}){Text("Employees")} }
        if(busy) CircularProgressIndicator()
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        message?.let { Text(it) }
        if(editing!=null) {
            fields.forEach { key -> OutlinedTextField(values[key].orEmpty(),{value->values=values+(key to value)},label={Text(fieldTitle(key))},modifier=Modifier.fillMaxWidth(),enabled=!busy) }
            if(path=="employees" && editing!!.has("id")) {
                Text("Account access: "+if(editing!!.optBoolean("isActive",true)) "Active" else "Suspended")
                TextButton(enabled=!busy,onClick={scope.launch {
                    busy=true;error=null
                    try { model.operation("employees/"+editing!!.get("id"),"PATCH",JSONObject().put("isActive",!editing!!.optBoolean("isActive",true)));message="Account access updated";reload++ } catch(e:Exception){error=e.message} finally{busy=false}
                }}){Text(if(editing!!.optBoolean("isActive",true)) "Suspend employee" else "Reactivate employee")}
            }
            Button(enabled=!busy,onClick={scope.launch {
                busy=true;error=null;message=null
                try {
                    val body=JSONObject();fields.filter { values[it].orEmpty()!=editing!!.optString(it).takeUnless{v->v=="null"}.orEmpty() || !editing!!.has("id") }.forEach { body.put(it,values[it].orEmpty()) }
                    val id=editing!!.optString("id").takeIf{it.isNotBlank()}
                    model.operation(path+(if(!settings && id!=null) "/$id" else ""),if(settings || id!=null) "PATCH" else "POST",body)
                    message="Saved";reload++
                } catch(e:Exception){error=e.message ?: "Save was not confirmed. Check before retrying."} finally{busy=false}
            }}){Text("Save")}
            TextButton(onClick={editing=null;values=emptyMap();reload++},enabled=!busy){Text("Discard / reload")}
        } else {
            TextButton(onClick={reload++},enabled=!busy){Text("Refresh / retry")}
            if(!settings) {
                Button(onClick={editing=JSONObject();values=emptyMap();message=null},enabled=!busy){Text("Create "+if(path=="clients") "client" else "employee")}
                OutlinedTextField(search,{search=it},label={Text("Search")},modifier=Modifier.fillMaxWidth())
                rows.filter{it.optString("name").contains(search,true)}.forEach { row ->
                    OutlinedButton(onClick={editing=row;values=fields.associateWith { row.optString(it).takeUnless{v->v=="null"}.orEmpty() };message=null},modifier=Modifier.fillMaxWidth()){Text(row.optString("name"))}
                }
                if(rows.isEmpty() && !busy && error==null)Text("No people in this company.")
            }
        }
    }
}


@Composable fun AccountLifecycleScreen(model: AuthViewModel, back:()->Unit) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var confirmation by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()
    suspend fun action(path:String,body:JSONObject) {
        busy=true;error=null
        try {model.accountOperation(path,"POST",body);confirmation="";requestId=java.util.UUID.randomUUID().toString();reload++} catch(e:Exception){error=e.message ?: "Request was not confirmed. Refresh before retrying."} finally{busy=false}
    }
    LaunchedEffect(reload) {
        busy=true;error=null
        try {data=model.accountOperation("account/lifecycle") as JSONObject} catch(e:Exception){data=null;error=e.message} finally{busy=false}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick=back){Text("Back")}
        Text("Account requests and terms",style=MaterialTheme.typography.headlineMedium)
        if(busy)CircularProgressIndicator()
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        TextButton(onClick={reload++},enabled=!busy){Text("Refresh / retry")}
        val document=data?.optJSONObject("document")
        val context=androidx.compose.ui.platform.LocalContext.current
        if(document!=null) {
            Text("Terms version: "+document.optString("version"))
            TextButton(onClick={val url=android.net.Uri.parse(document.optString("url"));if(url.scheme=="https")context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,url))}){Text("Read terms")}
            if(document.optBoolean("accepted"))Text("Accepted") else Button(enabled=!busy,onClick={scope.launch{action("account/terms-acceptance",JSONObject().put("version",document.getString("version")).put("sha256",document.getString("sha256")).put("accepted",true))}}){Text("I have read and accept these terms")}
        } else Text("Reviewed terms have not been published yet.")
        Text(data?.optString("notice") ?: "Loading account request information…")
        val requests=data?.optJSONArray("requests")
        (0 until (requests?.length() ?: 0)).forEach { index -> val row=requests!!.getJSONObject(index)
            Text(row.optString("status")+" · "+row.optString("createdAt"));Text(row.optString("reviewMessage"))
            if(row.optString("status") in listOf("requested","under_review","action_required"))TextButton(enabled=!busy,onClick={scope.launch{action("account/deletion-requests/"+row.getString("id")+"/cancel",JSONObject().put("version",row.getInt("version")))}}){Text("Cancel request")}
        }
        OutlinedTextField(confirmation,{confirmation=it},label={Text("Type REQUEST DELETION")},modifier=Modifier.fillMaxWidth())
        Button(enabled=!busy && data!=null && confirmation=="REQUEST DELETION",onClick={scope.launch{action("account/deletion-requests",JSONObject().put("requestId",requestId).put("confirmation",confirmation))}}){Text("Submit for review")}
    }
}


@Composable private fun NativeInvitations(model:AuthViewModel,back:()->Unit) {
    var positions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var invitations by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var email by remember { mutableStateOf("") }
    var position by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()
    fun JSONObject.list(key:String)=optJSONArray(key)?.let{a->(0 until a.length()).map{a.getJSONObject(it)}} ?: emptyList()
    suspend fun action(path:String,body:JSONObject) {
        busy=true;error=null;message=null
        try {
            val result=model.operation(path,"POST",body) as JSONObject
            message=if(result.optBoolean("replayed"))"Existing request found. Check delivery status; resend if needed." else "Request recorded. Check delivery status."
            email="";requestId=java.util.UUID.randomUUID().toString();reload++
        }catch(e:Exception){error=e.message ?: "Delivery was not confirmed. Refresh status before retrying."}finally{busy=false}
    }
    LaunchedEffect(reload) {
        busy=true;error=null;invitations=emptyList();positions=emptyList()
        try {
            positions=(model.operation("account/positions") as JSONObject).list("positions")
            invitations=(model.operation("employee-account-invitations") as JSONObject).list("invitations")
        }catch(e:Exception){error=e.message}finally{busy=false}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick=back){Text("Back")};Text("Company invitations",style=MaterialTheme.typography.headlineMedium)
        if(busy)CircularProgressIndicator();error?.let{Text(it,color=MaterialTheme.colorScheme.error)};message?.let{Text(it)}
        TextButton(onClick={reload++},enabled=!busy){Text("Refresh / retry")}
        OutlinedTextField(email,{email=it},label={Text("Employee email")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
        Text("Position: "+positions.firstOrNull{it.optString("id")==position}?.optString("name").orEmpty())
        positions.forEach{row->TextButton(enabled=!busy,onClick={position=row.getString("id")}){Text(row.getString("name"))}}
        Button(enabled=!busy && email.isNotBlank() && position.isNotBlank(),onClick={scope.launch{action("employee-account-invitations",JSONObject().put("requestId",requestId).put("email",email.trim()).put("positionId",position).put("delivery","email"))}}){Text("Send invitation email")}
        invitations.forEach{row->
            Text(row.optString("email"));Text("Delivery: "+row.optString("deliveryStatus")+" · "+row.optString("status"))
            if(row.isNull("acceptedAt") && row.isNull("revokedAt")) {
                TextButton(enabled=!busy,onClick={scope.launch{action("employee-account-invitations/"+row.getString("id")+"/resend",JSONObject())}}){Text("Resend invitation")}
                TextButton(enabled=!busy,onClick={scope.launch{action("employee-account-invitations/"+row.getString("id")+"/revoke",JSONObject())}}){Text("Revoke invitation")}
            }
        }
    }
}
