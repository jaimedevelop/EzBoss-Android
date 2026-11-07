package pro.ezboss.mobile.auth.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import pro.ezboss.mobile.AuthViewModel

@Composable
fun NativeIdentityScreen(model: AuthViewModel, accountError: String?) {
    var mode by remember { mutableStateOf("login") }
    var email by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }; var confirmation by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }; var backup by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }; var accepted by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    var config by remember { mutableStateOf(JSONObject()) }
    val scope=rememberCoroutineScope(); val uri=LocalUriHandler.current
    LaunchedEffect(Unit) { try { config=model.nativeRequest("/ezboss/config") } catch(e:CancellationException) { throw e } catch (_:Exception) { message="Authentication configuration is temporarily unavailable." } }
    fun change(value:String){mode=value;password="";confirmation="";code="";message=""}
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        androidx.compose.foundation.Image(painter=androidx.compose.ui.res.painterResource(pro.ezboss.mobile.R.drawable.ezboss_loading),contentDescription="EzBoss",modifier=Modifier.width(186.dp).height(56.dp))
        Text(when(mode){"signup"->"Create your account";"mfa"->"Verify your sign-in";"recover"->"Reset your password";"verify"->"Check your inbox";else->"Welcome back"},style=MaterialTheme.typography.headlineMedium,modifier=Modifier.semantics { heading() })
        if(accountError!=null)Text(accountError,color=MaterialTheme.colorScheme.error)
        if(message.isNotEmpty())Text(message,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
        if(mode=="signup")OutlinedTextField(value=name,onValueChange={name=it},label={Text("Full name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        if(mode!="mfa")OutlinedTextField(value=email,onValueChange={email=it},label={Text("Email")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,modifier=Modifier.fillMaxWidth())
        if(mode in listOf("login","signup")){
            OutlinedTextField(value=password,onValueChange={password=it},label={Text("Password")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),visualTransformation=if(showPassword)VisualTransformation.None else PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            Row { Checkbox(checked=showPassword,onCheckedChange={showPassword=it}); Text("Show password",modifier=Modifier.padding(top=12.dp)) }
        }
        if(mode=="signup"){
            OutlinedTextField(value=confirmation,onValueChange={confirmation=it},label={Text("Confirm password")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("Use 12–128 characters. Password managers and paste are supported.")
            if(config.optString("termsVersion","").isNotBlank() && !config.isNull("termsVersion")){
                TextButton(onClick={uri.openUri(config.getString("termsURL"))}){Text("Read terms")}
                TextButton(onClick={uri.openUri(config.getString("privacyURL"))}){Text("Read privacy policy")}
                Row { Checkbox(checked=accepted,onCheckedChange={accepted=it}); Text("I accept the current terms and privacy policy",modifier=Modifier.padding(top=12.dp)) }
            }else Text("Account creation will be available when the legal documents are published.")
        }
        if(mode=="mfa"){
            OutlinedTextField(value=code,onValueChange={code=it},label={Text(if(backup)"Recovery code" else "Authenticator code")},keyboardOptions=KeyboardOptions(keyboardType=if(backup)KeyboardType.Text else KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
            Row { Checkbox(checked=backup,onCheckedChange={backup=it;code=""});Text("Use a recovery code",modifier=Modifier.padding(top=12.dp)) }
        }
        Button(enabled=!pending && !model.busy && (mode!="signup" || accepted),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),onClick={
            scope.launch {
                pending=true;message=""
                try {
                    when(mode){
                        "login" -> if(!model.nativeSignIn(email,password))mode="mfa"
                        "mfa" -> model.nativeSignIn("","",code,backup)
                        "signup" -> {
                            require(password==confirmation && password.length in 12..128 && accepted)
                            model.nativeRequest("/sign-up/email",JSONObject().put("name",name).put("email",email).put("password",password).put("acceptedTermsVersion",config.getString("termsVersion")).put("acceptedPrivacyVersion",config.getString("privacyVersion")))
                            mode="verify";message="Check your email to verify the account, then sign in."
                        }
                        "verify" -> {model.nativeRequest("/send-verification-email",JSONObject().put("email",email));message="If verification is needed, an email will arrive shortly."}
                        else -> {model.nativeRequest("/request-password-reset",JSONObject().put("email",email).put("redirectTo",config.getString("appOrigin")+"/reset-password"));message="If this address can be used for recovery, an email will arrive shortly."}
                    }
                }catch(e:CancellationException){throw e}
                catch(_:Exception){message="Authentication could not finish. Check your details or use a new authenticator code and retry."}
                finally{pending=false;password="";confirmation="";code=""}
            }
        }){Text(if(pending || model.busy)"Please wait…" else when(mode){"signup"->"Create account";"mfa"->"Verify";"recover"->"Send recovery link";"verify"->"Resend verification";else->"Sign in"})}
        TextButton(enabled=!pending && !model.busy,onClick={change(if(mode=="signup" || mode=="mfa")"login" else "signup")}){Text(if(mode=="signup" || mode=="mfa")"Back to sign in" else "Create an account")}
        TextButton(enabled=!pending && !model.busy,onClick={change(if(mode=="recover" || mode=="verify")"login" else "recover")}){Text(if(mode=="recover" || mode=="verify")"Back to sign in" else "Forgot password?")}
        if(accountError!=null)TextButton(enabled=!pending && !model.busy,onClick={model.restore()}){Text("Retry account access")}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NativeCompanySetupScreen(model: AuthViewModel) {
    var first by remember { mutableStateOf("") };var last by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") };var trades by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("1") };var message by remember { mutableStateOf("") }
    var web by remember { mutableStateOf("") };var pending by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope();val uri=LocalUriHandler.current
    LaunchedEffect(Unit){try{web=model.nativeRequest("/ezboss/config").getString("appOrigin")}catch(e:CancellationException){throw e}catch(_:Exception){}}
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text("Your identity is ready",style=MaterialTheme.typography.headlineLarge)
        Text("Create a contractor company, or continue an invitation to an existing company. You can also sign out and finish later.")
        if(web.isNotBlank())TextButton(onClick={uri.openUri(web+"/employee-account")}){Text("Join an existing company with an invitation")}
        OutlinedTextField(value=first,onValueChange={first=it},label={Text("First name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=last,onValueChange={last=it},label={Text("Last name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=company,onValueChange={company=it},label={Text("Company name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=trades,onValueChange={trades=it},label={Text("Trade types, separated by commas")},modifier=Modifier.fillMaxWidth())
        Text("Employee count")
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            listOf("1","2-5","6-10","11-25","26-50","51+").forEach{value->FilterChip(selected=count==value,onClick={count=value},label={Text(value)})}
        }
        if(message.isNotEmpty())Text(message,color=MaterialTheme.colorScheme.error)
        Button(enabled=!pending && !model.busy && first.isNotBlank() && last.isNotBlank() && trades.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),onClick={scope.launch{
            pending=true
            try{val values=org.json.JSONArray();trades.split(',').map{it.trim()}.filter{it.isNotEmpty()}.forEach{values.put(it)}
                model.createNativeCompany(JSONObject().put("firstName",first).put("lastName",last).put("company",company).put("employeeCount",count).put("tradeTypes",values))
            }catch(e:CancellationException){throw e}catch(_:Exception){message="Company setup was not confirmed. Retry account access before submitting again."}finally{pending=false}
        }}){Text("Create contractor company")}
        TextButton(enabled=!pending && !model.busy,onClick={model.restore()}){Text("Retry account access")}
        TextButton(enabled=!pending && !model.busy,onClick={model.signOut()}){Text("Sign out")}
    }
}
