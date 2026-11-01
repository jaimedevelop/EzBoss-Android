package pro.ezboss.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap

internal fun operationRows(value: Any): List<JSONObject> {
    val array = if (value is JSONArray) value else (value as? JSONObject)?.let { objectValue ->
        listOf("items", "jobs", "workOrders", "purchaseOrders", "collections", "laborItems").firstNotNullOfOrNull { objectValue.optJSONArray(it) }
    } ?: throw IllegalArgumentException("Unexpected list response")
    return (0 until array.length()).map { array.getJSONObject(it) }
}
private fun JSONObject.label(): String = listOf("name", "woNumber", "poNumber", "id", "workOrderId").map { optString(it) }.firstOrNull { it.isNotBlank() && it != "null" } ?: "Item"
private fun JSONObject.identifier(): String = optString("id").takeIf { it.isNotBlank() } ?: optString("workOrderId")
private fun JSONObject.rows(key: String) = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } } ?: emptyList()

@Composable
fun OperationsScreen(model: AuthViewModel, destination: String) {
    var kind by remember(destination) { mutableStateOf(if (destination == "inventory") "products" else destination) }
    val path = when { destination == "inventory" -> "inventory/$kind"; kind == "assigned" -> "employee/work-orders"; destination == "purchasing" -> "purchase-orders"; else -> destination }
    var rows by remember(path) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember(path) { mutableStateOf<String?>(null) }
    var creating by remember(path) { mutableStateOf(false) }
    var aiOpen by remember { mutableStateOf(false) }
    var search by remember(path) { mutableStateOf("") }
    var busy by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var page by remember(path) { mutableIntStateOf(0) }
    var more by remember(path) { mutableStateOf(false) }
    var reload by remember(path) { mutableIntStateOf(0) }
    LaunchedEffect(path, reload, page) {
        busy = true; error = null
        try { val next = operationRows(model.operation(path+(if(path=="purchase-orders") "?limit=25&offset=${page*25}" else ""))); rows = if(page==0) next else (rows+next).distinctBy{it.identifier()}; more=path=="purchase-orders"&&next.size==25 } catch (e: Exception) { rows=emptyList();more=false;error = e.message ?: "Could not load. Retry." } finally { busy = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(destination.replace('-', ' ').replaceFirstChar { it.uppercase() },style=MaterialTheme.typography.headlineMedium)
        if (destination == "inventory") Column { listOf("products","labor","tools","equipment").chunked(2).forEach { group -> Row { group.forEach { value -> TextButton(onClick={ kind = value }) { Text(value) } } } } }
        if (destination == "work-orders") Row { TextButton(onClick={kind="work-orders"}) { Text("Manage jobs") }; TextButton(onClick={kind="assigned"}) { Text("My assigned jobs") } }
        if(destination=="collections")TextButton(onClick={aiOpen=true}){Text("AI collection assistant")}
        when {
            aiOpen -> NativeAICollection(model){aiOpen=false;reload++}
            selected != null -> OperationDetail(model,path,selected!!,kind) { selected=null;reload++ }
            creating -> OperationEditor(model,path,kind,null) { creating=false;reload++ }
            else -> {
                OutlinedTextField(search,{search=it},label={Text("Search")},modifier=Modifier.fillMaxWidth())
                if (busy) CircularProgressIndicator()
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                TextButton(onClick={page=0;reload++},enabled=!busy) { Text("Refresh / retry") }
                if (destination in listOf("inventory","collections","purchasing")) Button(onClick={creating=true}) { Text("Create") }
                if (rows.isEmpty() && !busy && error == null) Text("No items in this company.")
                if(more)TextButton(onClick={page++},enabled=!busy){Text("Load more")}
                rows.filter { it.label().contains(search,ignoreCase=true) }.forEach { row ->
                    OutlinedButton(onClick={selected=row.identifier()},modifier=Modifier.fillMaxWidth()) { Text(row.label()+"  "+row.optString("status","")) }
                }
            }
        }
    }
}

@Composable private fun OperationEditor(model: AuthViewModel,path: String,kind: String,row: JSONObject?,done:()->Unit) {
    var name by remember { mutableStateOf(row?.optString("name") ?: "") }
    var description by remember { mutableStateOf(row?.optString("description") ?: "") }
    var price by remember { mutableStateOf((if(kind=="labor") row?.optJSONArray("flatRates")?.optJSONObject(0)?.optString("rate","0") else row?.optString(if(kind in listOf("tools","equipment")) "minimumCustomerCharge" else "unitPrice", "0")) ?: "0") }
    var hierarchy by remember { mutableStateOf(listOf("trade","section","category","subcategory","type").associateWith { row?.optString(it+"Id")?.takeIf { value->value!="null" } ?: "" }) }
    var vendor by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var referenceKind by remember { mutableStateOf("product") }
    var reference by remember { mutableStateOf<JSONObject?>(null) }
    var inventory by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val requestId = remember { UUID.randomUUID().toString() }
    val scope=rememberCoroutineScope()
    val purchasing=path=="purchase-orders"
    LaunchedEffect(referenceKind) {
        if(purchasing && referenceKind!="manual") {
            reference=null
            try { inventory=operationRows(model.operation("inventory/"+when(referenceKind){"product"->"products";"tool"->"tools";else->"equipment"})) } catch(e:Exception) { error=e.message }
        }
    }
    if(error!=null) Text(error!!,color=MaterialTheme.colorScheme.error)
    if(purchasing) {
        OutlinedTextField(vendor,{vendor=it},label={Text("Supplier")})
        Column { listOf("product","tool","equipment","manual").chunked(2).forEach { group -> Row { group.forEach { type -> TextButton(onClick={referenceKind=type}) { Text(type) } } } } }
        if(referenceKind!="manual") {
            Text("Selected: "+(reference?.label() ?: "Select an inventory item"))
            inventory.forEach { item -> TextButton(onClick={reference=item}) { Text(item.label()) } }
        }
        OutlinedTextField(quantity,{quantity=it},label={Text("Quantity")})
    }
    if(!purchasing || referenceKind=="manual") OutlinedTextField(name,{name=it},label={Text("Name")})
    OutlinedTextField(description,{description=it},label={Text("Description")})
    if(path.startsWith("inventory/")) {
        val levels=listOf("trade","section","category","subcategory","type").take(if(kind=="labor")3 else if(kind=="products")5 else 4)
        levels.forEachIndexed { index,level ->
            InventoryHierarchyField(model,kind,level,if(index==0) "" else hierarchy[levels[index-1]] ?: "",hierarchy[level] ?: "") { value ->
                hierarchy=hierarchy.toMutableMap().also { values -> values[level]=value;levels.drop(index+1).forEach{values[it]=""} }
            }
        }
    }
    if(kind in listOf("products","labor","tools","equipment") || (purchasing && referenceKind=="manual")) OutlinedTextField(price,{price=it},label={Text("Unit price")})
    Row {
        TextButton(onClick=done,enabled=!busy) { Text("Cancel") }
        Button(enabled=!busy,onClick={scope.launch {
            busy=true;error=null
            try {
                var body=JSONObject().put("name",name).put("description",description)
                if(path=="collections") body.put("category","General")
                hierarchy.forEach { (level,id) -> id.toIntOrNull()?.let { body.put(level+"Id",it) } }
                if(kind=="labor") {
                    val rates=JSONArray(row?.optJSONArray("flatRates")?.toString() ?: "[]")
                    if(rates.length()==0)rates.put(JSONObject().put("name","Standard").put("rate",price.toBigDecimal()))
                    else rates.getJSONObject(0).put("rate",price.toBigDecimal())
                    body.put("flatRates",rates)
                }
                if(kind=="products") body.put("unitPrice",price.toBigDecimal().also { require(it.signum()>=0) })
                if(kind=="tools") body.put("minimumCustomerCharge",price.toBigDecimal().also { require(it.signum()>=0) })
                if(kind=="equipment") { body.put("minimumCustomerCharge",price.toBigDecimal().also { require(it.signum()>=0) });if(row==null)body.put("equipmentType","owned").put("status","available") }
                if(purchasing) {
                    val q=quantity.toDouble();require(q.isFinite()&&q>0)
                    val line=JSONObject().put("itemType",referenceKind).put("quantity",q)
                    if(referenceKind=="manual")line.put("name",name).put("unitPrice",price.toBigDecimal())
                    else line.put("itemId",requireNotNull(reference).getInt("id"))
                    body=JSONObject().put("vendor",vendor).put("notes",description).put("idempotencyKey",requestId).put("lineItems",JSONArray().put(line))
                }
                model.operation(path+(row?.let { "/${it.identifier()}" } ?: ""),if(row==null) "POST" else "PATCH",body)
                done()
            }catch(e:Exception){error=e.message ?: "Save not confirmed. Your input is preserved."}finally{busy=false}
        }}){Text("Save")}
    }
}

@Composable private fun OperationDetail(model:AuthViewModel,path:String,id:String,kind:String,back:()->Unit) {
    var row by remember(id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var selectionOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var delta by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("0") }
    var supplier by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var reload by remember { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()
    val assigned=path.startsWith("employee/")
    LaunchedEffect(id,reload) {
        busy=true
        try { val result=model.operation("$path/$id") as JSONObject;row=result.optJSONObject("job") ?: result;error=null }catch(e:Exception){row=null;error=e.message}finally{busy=false}
    }
    fun action(command:String,body:JSONObject=JSONObject(),method:String="POST") {scope.launch {
        busy=true;error=null
        try {model.operation("$path/$id"+(if(command.isBlank()) "" else "/$command"),method,body);requestId=UUID.randomUUID().toString();if(method=="DELETE")back() else reload++}catch(e:Exception){error=e.message}finally{busy=false}
    }}
    TextButton(onClick=back,enabled=!busy){Text("Back")}
    if(busy)CircularProgressIndicator()
    error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    TextButton(onClick={reload++},enabled=!busy){Text("Refresh / retry")}
    if(editing){OperationEditor(model,path,kind,row){editing=false;reload++};return}
    row?.let { current ->
        if(managing){WorkOrderManagement(model,current){managing=false;reload++};return}
        if(selectionOpen){CollectionSelection(model,current){selectionOpen=false;reload++};return}
        Text(current.label(),style=MaterialTheme.typography.titleLarge)
        if(path=="collections") {
            TextButton(onClick={selectionOpen=true},enabled=!busy){Text("Add / change inventory selection")}
            current.rows("itemSelections").forEach{item->Text(item.optString("itemName")+" × "+item.optString("quantity"))}
        }
        listOf("description","status","onHand","assigned","total","actualTotal","serviceAddress","needsManagerReview","estimateUpdatedAt").forEach { key -> current.optString(key).takeIf{it.isNotBlank()&&it!="null"}?.let{Text("$key: $it")} }
        if(path.startsWith("inventory/")||path=="collections") {
            TextButton(onClick={editing=true},enabled=!busy){Text("Edit")}
            TextButton(onClick={confirmDelete=true},enabled=!busy){Text("Delete")}
        }
        if(kind=="products") {
            OutlinedTextField(delta,{delta=it},label={Text("Stock change (+ or −)")})
            Button(enabled=!busy,onClick={val n=delta.toDoubleOrNull();if(n==null||!n.isFinite()||n==0.0)error="Enter a nonzero stock change" else action("adjust-stock",JSONObject().put("requestId",requestId).put("onHandChange",n))}){Text("Apply stock change")}
        }
        if(path=="purchase-orders") {
            if(current.optString("status")=="draft") Button(enabled=!busy,onClick={action("submit",JSONObject().put("version",current.optInt("version")))}){Text("Submit order")}
            if(current.optString("status") in listOf("draft","ordered","partially_received")) TextButton(enabled=!busy,onClick={action("cancel",JSONObject().put("version",current.optInt("version")))}){Text("Cancel order")}
            OutlinedTextField(supplier,{supplier=it},label={Text("Actual supplier")})
            OutlinedTextField(qty,{qty=it},label={Text("Quantity received")})
            OutlinedTextField(price,{price=it},label={Text("Actual unit price")})
            current.rows("lineItems").forEach { line ->
                Text(line.optString("nameSnapshot")+": ordered "+line.optString("quantity")+" / received "+line.optString("quantityReceived"))
                if(current.optString("status") in listOf("ordered","partially_received")) Button(enabled=!busy,onClick={
                    val q=qty.toDoubleOrNull();val p=price.toDoubleOrNull()
                    if(q==null||!q.isFinite()||q<=0||p==null||!p.isFinite()||p<0)error="Enter valid receipt amounts"
                    else action("receipts",JSONObject().put("idempotencyKey",requestId).put("supplier",supplier).put("items",JSONArray().put(JSONObject().put("itemId",line.getString("id")).put("quantityReceived",q).put("actualUnitPrice",p))))
                }){Text("Receive this line")}
            }
        }
        if(path=="work-orders") TextButton(onClick={managing=true},enabled=!busy){Text("Manage assignments and schedule")}
        if(path.endsWith("work-orders")) {
            current.rows("appointments").forEach{day->Text(day.optString("scheduledDate")+" — "+day.optString("timezone")+" — "+day.optString("status"))}
            current.rows("tasks").forEach { task ->
                Text(task.label());Text(task.optString("description"))
                TaskPhoto(model,"$path/$id/tasks/${task.identifier()}/photos"){reload++}
                val uriHandler=androidx.compose.ui.platform.LocalUriHandler.current
                task.rows("media").forEach{photo->
                    if(photo.optString("url").startsWith("https://"))TextButton(onClick={uriHandler.openUri(photo.getString("url"))}){Text("View private task photo")}
                    else Text("Photo unavailable. Refresh its access or complete private-storage migration.")
                }
                Button(enabled=!busy&&!task.optBoolean("isCompleted"),onClick={
                    if(assigned)action("tasks/${task.identifier()}/complete")
                    else {
                        val tasks=JSONArray((current.optJSONArray("tasks") ?: JSONArray()).toString())
                        (0 until tasks.length()).map{tasks.getJSONObject(it)}.first{it.identifier()==task.identifier()}.put("isCompleted",true)
                        action("",JSONObject().put("version",current.optInt("version")).put("tasks",tasks),"PATCH")
                    }
                }){Text(if(task.optBoolean("isCompleted")) "Completed" else "Complete task")}
            }
            if(assigned)listOf("clock-in","start-break","end-break","start-lunch","end-lunch","clock-out").forEach { command -> TextButton(enabled=!busy,onClick={action("attendance/$command")}){Text(command.replace('-',' '))} }
            else if(current.optString("status")=="review") Button(enabled=!busy,onClick={action("approval",JSONObject().put("version",current.optInt("version")))}){Text("Approve work order")}
        }
    }
    if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Delete this item?")},confirmButton={TextButton(onClick={confirmDelete=false;action("",method="DELETE")}){Text("Delete")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("Cancel")}})
}

@Composable private fun TaskPhoto(model:AuthViewModel,path:String,confirmed:()->Unit) {
    var pending by remember(path){mutableStateOf<ByteArray?>(null)}
    var requestId by remember(path){mutableStateOf(UUID.randomUUID().toString())}
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var capture by remember {mutableStateOf<java.io.File?>(null)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    DisposableEffect(path){onDispose{capture?.delete()}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file=capture
        try {
            if(!success||file==null)message="No photo captured. Check camera access and retry."
            else {
                val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeFile(file.absolutePath,options)
                var sample=1;while(maxOf(options.outWidth,options.outHeight)/sample>2000)sample*=2
                val bitmap=android.graphics.BitmapFactory.decodeFile(file.absolutePath,android.graphics.BitmapFactory.Options().apply{inSampleSize=sample}) ?: throw IllegalArgumentException("Unreadable photo")
                val bytes=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,85,bytes);bitmap.recycle()
                require(bytes.size()<=5*1024*1024)
                pending=bytes.toByteArray();requestId=UUID.randomUUID().toString();message=null
            }
        }catch(e:Exception){message="Photo could not be prepared. Retake it."}finally{file?.delete();capture=null}
    }
    message?.let {Text(it)}
    if(pending==null) TextButton(onClick={
        try {
            val directory=java.io.File(context.cacheDir,"task-photos").apply{mkdirs()}
            val file=java.io.File.createTempFile("task-",".jpg",directory);capture=file
            camera.launch(androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".taskphotos",file))
        }catch(e:Exception){capture?.delete();capture=null;message="Camera unavailable. Check camera access and retry."}
    },enabled=!busy){Text("Take private task photo")}
    else {
        Text("Photo ready for private upload")
        Button(enabled=!busy,onClick={scope.launch {
            busy=true
            try {model.taskPhoto(path,pending!!,requestId);pending=null;message="Private photo saved.";confirmed()}catch(e:Exception){message="Upload not confirmed. Photo retained; retry uses the same request ID."}finally{busy=false}
        }}){Text("Upload / retry photo")}
        TextButton(enabled=!busy,onClick={pending=null}){Text("Discard photo")}
    }
}

@Composable internal fun CollectionImportPicker(model:AuthViewModel,close:()->Unit,add:(JSONArray)->Unit) {
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var preview by remember { mutableStateOf<JSONArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(reload){busy=true;try{rows=operationRows(model.operation("collections"));error=null}catch(e:Exception){error=e.message}finally{busy=false}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Import collection",style=MaterialTheme.typography.titleLarge)
        TextButton(onClick=close,enabled=!busy){Text("Back to draft")}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(busy)CircularProgressIndicator()
        if(preview!=null){
            (0 until preview!!.length()).forEach { i -> val line=preview!!.getJSONObject(i);Text(line.optString("name")+" — "+line.optString("quantity")+" × "+line.optString("unitPrice")) }
            Button(enabled=!busy&&preview!!.length()>0,onClick={add(preview!!)}){Text("Add reviewed lines to draft")}
            TextButton(onClick={preview=null}){Text("Choose another collection")}
        }else {
            TextButton(onClick={reload++},enabled=!busy){Text("Refresh / retry")}
            rows.forEach { row -> TextButton(enabled=!busy,onClick={scope.launch{busy=true;error=null;try{preview=(model.operation("collections/${row.identifier()}/import-preview") as JSONObject).getJSONArray("lineItems")}catch(e:Exception){error=e.message}finally{busy=false}}}){Text(row.label())} }
        }
    }
}

@Composable private fun CollectionSelection(model:AuthViewModel,collection:JSONObject,done:()->Unit) {
    var kind by remember{mutableStateOf("products")}
    var quantity by remember{mutableStateOf("1")}
    var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var selected by remember{mutableStateOf<JSONObject?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(kind){rows=emptyList();selected=null;try{rows=operationRows(model.operation("inventory/$kind"))}catch(e:Exception){error=e.message}}
    Text("Collection items",style=MaterialTheme.typography.titleLarge)
    error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    Column {listOf("products","labor","tools","equipment").chunked(2).forEach{group->Row {group.forEach{type->TextButton(onClick={kind=type},enabled=!busy){Text(type)}}}}}
    Text("Selected: "+(selected?.label() ?: "Select inventory"))
    rows.forEach{row->TextButton(onClick={selected=row},enabled=!busy){Text(row.label())}}
    OutlinedTextField(quantity,{quantity=it},label={Text("Whole quantity (0 removes selection)")})
    Button(enabled=!busy,onClick={scope.launch{
        busy=true;error=null
        try{val q=quantity.toInt();require(q>=0);val item=requireNotNull(selected)
            model.operation("collections/${collection.identifier()}/native-selection","PUT",JSONObject().put("contentType",kind).put("itemId",item.getInt("id")).put("quantity",q).put("expectedUpdatedAt",collection.getString("updatedAt")));done()
        }catch(e:Exception){error=e.message ?: "Selection not confirmed. Reload before retrying."}finally{busy=false}
    }}){Text("Save selection")}
    TextButton(onClick=done,enabled=!busy){Text("Back")}
}

@Composable private fun WorkOrderManagement(model:AuthViewModel,order:JSONObject,done:()->Unit) {
    var employees by remember {mutableStateOf<List<JSONObject>>(emptyList())}
    var workers by remember {mutableStateOf<List<JSONObject>>(emptyList())}
    var date by remember {mutableStateOf("")}
    var zone by remember {mutableStateOf(java.util.TimeZone.getDefault().id)}
    var reason by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(order.identifier()) {
        busy=true
        try {
            val people=model.operation("employees") as JSONObject
            employees=people.keys().asSequence().flatMap {key->people.rows(key).asSequence()}.toList()
            workers=operationRows(model.operation("work-orders/${order.identifier()}/workers"))
        } catch(e:Exception){error=e.message}finally{busy=false}
    }
    fun command(path:String,body:JSONObject=JSONObject(),method:String="POST"){scope.launch{
        busy=true;error=null
        try{model.operation("work-orders/${order.identifier()}/$path",method,body);done()}catch(e:Exception){error=e.message}finally{busy=false}
    }}
    Text("Job management",style=MaterialTheme.typography.titleLarge)
    error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    TextButton(onClick=done,enabled=!busy){Text("Back")}
    OutlinedTextField(date,{date=it},label={Text("Date YYYY-MM-DD")},enabled=!busy)
    OutlinedTextField(zone,{zone=it},label={Text("Timezone")},enabled=!busy)
    OutlinedTextField(reason,{reason=it},label={Text("Scheduling reason")},enabled=!busy)
    Button(enabled=!busy,onClick={command("schedule",JSONObject().put("version",order.optInt("version")).put("action","save").put("reason",reason).put("appointment",JSONObject().put("scheduledDate",date).put("timezone",zone).put("shifts",JSONArray().apply{workers.forEach{worker->put(JSONObject().put("assignmentId",worker.identifier()).put("scheduledDate",date))}})))}){Text("Save job day")}
    Text("Assign employee",style=MaterialTheme.typography.titleMedium)
    employees.forEach{employee->TextButton(enabled=!busy,onClick={command("workers",JSONObject().put("employeeIds",JSONArray().put(employee.getInt("id"))))}){Text(employee.label())}}
    Text("Assigned employees and tasks",style=MaterialTheme.typography.titleMedium)
    workers.forEach{worker->
        Text(worker.optString("displayName"))
        order.rows("tasks").forEach{task->
            val ids=worker.optJSONArray("assignedTaskIds") ?: JSONArray()
            val assigned=(0 until ids.length()).any{ids.optString(it)==task.identifier()}
            TextButton(enabled=!busy,onClick={command("workers/${worker.identifier()}/tasks",JSONObject().put(if(assigned)"removeTaskId" else "addTaskId",task.identifier()),"PATCH")}){Text((if(assigned)"Unassign " else "Assign ")+task.label())}
        }
        TextButton(enabled=!busy,onClick={command("workers/${worker.identifier()}",method="DELETE")}){Text("Remove employee from job")}
    }
}

@Composable private fun InventoryHierarchyField(model:AuthViewModel,kind:String,level:String,parent:String,selected:String,change:(String)->Unit) {
    var rows by remember {mutableStateOf<List<JSONObject>>(emptyList())}
    var error by remember {mutableStateOf<String?>(null)}
    var reload by remember {mutableIntStateOf(0)}
    var expanded by remember {mutableStateOf(false)}
    LaunchedEffect(parent,reload) {
        rows=emptyList();error=null
        if(level=="trade"||parent.isNotBlank())try{
            val type=when(kind){"products"->"product";"tools"->"tool";else->kind}
            rows=operationRows(model.operation("inventory/categories/hierarchy/$level?itemType=$type"+(if(parent.isBlank())"" else "&parentId=$parent")))
        }catch(e:Exception){error=e.message}
    }
    Column {
        TextButton(enabled=level=="trade"||parent.isNotBlank(),onClick={expanded=!expanded}){Text(level.replaceFirstChar{it.uppercase()}+": "+(rows.firstOrNull{it.identifier()==selected}?.label() ?: "Unspecified"))}
        if(expanded){rows.forEach { row -> TextButton(onClick={change(row.identifier());expanded=false}){Text(row.label())} }}
        if(error!=null)TextButton(onClick={reload++}){Text("Retry $level list")}
    }
}

@Composable private fun NativeAICollection(model:AuthViewModel,done:()->Unit) {
    var provider by remember {mutableStateOf("openai")}
    var modelName by remember {mutableStateOf("")}
    var key by remember {mutableStateOf("")}
    var customEndpoint by remember {mutableStateOf("")}
    var customId by remember {mutableStateOf(UUID.randomUUID().toString())}
    var prompt by remember {mutableStateOf("")}
    var settings by remember {mutableStateOf(JSONObject().put("provider","openai").put("modelId","").put("customProviders",JSONArray()).put("customModels",JSONArray()))}
    var version by remember {mutableIntStateOf(0)}
    var ready by remember {mutableStateOf(false)}
    var proposal by remember {mutableStateOf<JSONObject?>(null)}
    var preview by remember {mutableStateOf<List<JSONObject>>(emptyList())}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var requestId by remember {mutableStateOf(UUID.randomUUID().toString())}
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){
        try {
            val record=model.operation("collection-ai-settings") as JSONObject
            version=record.optInt("version");record.optJSONObject("settings")?.let { settings=it;provider=it.optString("provider").takeIf{p->p in listOf("openai","anthropic","google","deepseek","custom")} ?: "openai";customId=it.optString("activeCustomProviderId",customId); customEndpoint=it.rows("customProviders").firstOrNull{p->p.optString("id")==customId}?.optString("baseUrl") ?: ""; modelName=it.optString("modelId").removePrefix(if(provider=="custom")"custom:$customId:" else "$provider:") };ready=true
        }catch(e:Exception){message="Provider preferences could not be loaded. Reopen to retry."}
    }
    Text("AI collections",style=MaterialTheme.typography.titleLarge)
    TextButton(onClick=done,enabled=!busy){Text("Back")}
    Text("Uses your provider account and may incur provider charges. The key stays only on this screen and is sent over HTTPS for this request. Review suggestions before saving.")
    message?.let {Text(it)}
    listOf("openai","anthropic","google","deepseek","custom").chunked(2).forEach{group->Row{group.forEach{value->TextButton(enabled=!busy,onClick={provider=value;key=""}){Text(value)}}}}
    if(provider=="custom")OutlinedTextField(customEndpoint,{customEndpoint=it},label={Text("Custom HTTPS endpoint")},enabled=!busy)
    OutlinedTextField(modelName,{modelName=it},label={Text("Provider model name")},enabled=!busy)
    OutlinedTextField(key,{key=it},label={Text("Temporary provider key")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),enabled=!busy)
    Button(enabled=ready&&!busy,onClick={scope.launch{
        busy=true
        try{
            settings.put("provider",provider).put("modelId",(if(provider=="custom")"custom:$customId" else provider)+":$modelName")
            if(provider=="custom") {
                val existing=settings.rows("customProviders").firstOrNull{it.optString("id")==customId}
                val definition=JSONObject(existing?.toString() ?: "{}").put("id",customId).put("baseUrl",customEndpoint)
                if(!definition.has("label"))definition.put("label","Custom provider")
                val providers=settings.rows("customProviders").filter{it.optString("id")!=customId}
                settings.put("customProviders",JSONArray().apply{providers.forEach{put(it)};put(definition)}).put("activeCustomProviderId",customId)
            }
            val saved=model.operation("collection-ai-settings","PUT",JSONObject().put("version",version).put("settings",settings)) as JSONObject;version=saved.getInt("version");message="Provider preferences saved. The key was not stored."}catch(e:Exception){message="Preferences not saved. Reload if they changed elsewhere."}finally{busy=false}
    }}){Text("Save provider preferences")}
    OutlinedTextField(prompt,{prompt=it},label={Text("Describe the collection you need")},enabled=!busy)
    Button(enabled=ready&&!busy&&key.isNotBlank()&&modelName.isNotBlank()&&prompt.isNotBlank(),onClick={scope.launch{
        busy=true;message=null
        try{
            val result=if(provider=="custom")model.customCollection(customEndpoint,key,modelName,prompt,model.operation("native-collection-ai/inventory") as JSONObject)
            else model.operation("native-collection-ai/generate","POST",JSONObject().put("provider",provider).put("model",modelName).put("prompt",prompt),key) as JSONObject
            proposal=result.getJSONObject("collection");preview=result.rows("preview");requestId=UUID.randomUUID().toString()
        }catch(e:Exception){message="Generation did not complete. Your prompt and previous review are preserved. Check provider/model and retry."}finally{busy=false}
    }}){Text("Generate suggestions")}
    if(busy)CircularProgressIndicator()
    proposal?.let { collection ->
        Text("Review collection",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(collection.optString("name"),{proposal=JSONObject(collection.toString()).put("name",it)},label={Text("Name")},enabled=!busy)
        OutlinedTextField(collection.optString("description"),{proposal=JSONObject(collection.toString()).put("description",it)},label={Text("Description")},enabled=!busy)
        preview.forEach{item->Text(item.optString("name")+" × "+item.optString("quantity"))}
        Button(enabled=!busy,onClick={scope.launch{
            busy=true
            try{model.operation("native-collection-ai/save","POST",JSONObject().put("requestId",requestId).put("collection",collection));proposal=null;preview=emptyList();message="Collection saved. Open Collections to edit quantities or import into a draft."}catch(e:Exception){message="Save not confirmed. Review preserved; retry uses the same save request."}finally{busy=false}
        }}){Text("Save reviewed collection")}
    }
}
