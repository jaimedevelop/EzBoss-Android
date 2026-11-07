package pro.ezboss.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.auth0.android.authentication.AuthenticationException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import pro.ezboss.mobile.auth.*
import pro.ezboss.mobile.data.Account
import pro.ezboss.mobile.data.ApiClient
import pro.ezboss.mobile.data.ApiException

import pro.ezboss.mobile.ui.theme.*

private val BrandOrange = EzBossDesign.Orange
private val Canvas = EzBossDesign.Canvas
private val Ink = EzBossDesign.Ink

private data class AppDestination(val title: String, val route: String)

private val appDestinations = listOf(
    AppDestination("Dashboard", "dashboard"),
    AppDestination("Estimates", "estimates"),
    AppDestination("Projects", "projects"),
    AppDestination("Inventory", "inventory"),
    AppDestination("Collections", "collections"),
    AppDestination("Work Orders", "work-orders"),
    AppDestination("Finances", "finance"),
    AppDestination("Purchasing", "purchasing"),
    AppDestination("People", "people"),
    AppDestination("Settings", "settings"),
)

// Reserved for a future authenticated estimate detail destination.
private const val EstimateDetailRoute = "estimates/{estimateId}"

class MainActivity : ComponentActivity() {
    private val authConfig by lazy { authConfiguration() }
    private val model: AuthViewModel by viewModels { AuthViewModel.Factory(AuthRepository(applicationContext, authConfig), ApiClient(authConfig.apiBaseUrl, identityDiscovery=authConfig.provider == "better-auth")) }

    override fun onStart() {
        super.onStart()
        model.revalidate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EzBossTheme { EzBossLoadingHost(authenticated = model.state is AuthState.Authenticated, signedOut = model.state is AuthState.SignedOut || (model.state as? AuthState.Error)?.sessionExpired == true) { EzBossApp(model, this) } } }
    }
}

class AuthViewModel(private val auth: AuthRepository, private val api: ApiClient) : ViewModel() {
    var state by mutableStateOf<AuthState>(AuthState.Loading); private set
    var busy by mutableStateOf(false); private set
    private val sessionEpoch = SessionEpoch()
    var memberships by mutableStateOf<List<pro.ezboss.mobile.data.CompanyMembership>>(emptyList()); private set
    var membership by mutableStateOf<String?>(null); private set
    private fun company(): String = membership ?: throw ApiException(409, "Select a company to continue.")
    fun selectCompany(id: String) = viewModelScope.launch {
        if (busy || memberships.none { it.id == id && it.available }) return@launch
        val account = when (val current = state) {
            is AuthState.Authenticated -> current.account
            is AuthState.CompanySelection -> current.account
            else -> return@launch
        }
        busy = true; sessionEpoch.advance(); membership = null; state = AuthState.Loading
        try {
            val result = api.companies(auth.accessToken(), id)
            memberships = result.memberships; membership = result.selected
            state = if (membership == null) AuthState.CompanySelection(account) else AuthState.Authenticated(account)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state = AuthState.Error("Company access could not be loaded. Retry account access.") }
        finally { busy = false }
    }

    fun revalidate() {
        if (!busy && (state is AuthState.Authenticated || state is AuthState.CompanySelection)) restore()
    }

    private suspend fun <T> authenticatedRequest(accountOnly: Boolean = false, request: suspend (String) -> T): T {
        if (state !is AuthState.Authenticated && !(accountOnly && state is AuthState.CompanySelection)) throw ApiException(401, "Sign in required.")
        if (busy || authenticationUnavailable) throw ApiException(503, "Account access is being checked. Retry shortly.")
        val epoch = sessionEpoch.current
        val token = try { auth.accessToken() }
        catch (e: CancellationException) { throw e }
        catch (e: NativeAuthException) {
            if(e.status>=500 || e.status==429) {
                sessionEpoch.requireCurrent(epoch); authenticationUnavailable=true
                throw ApiException(503,"Authentication temporarily unavailable. Retry shortly.")
            }
            sessionEpoch.requireCurrent(epoch)
            sessionEpoch.advance(); state=AuthState.Error("Sign in again to continue.",retryLogin=true); auth.clearCredentials(); throw e
        }
        catch (e: Exception) {
            sessionEpoch.requireCurrent(epoch)
            sessionEpoch.advance()
            state = AuthState.Error("Your session could not be restored. Please sign in again.", retryLogin = true)
            auth.clearCredentials()
            throw e
        }
        sessionEpoch.requireCurrent(epoch)
        try {
            val result = request(token)
            sessionEpoch.requireCurrent(epoch)
            return result
        }
        catch (e: ApiException) {
            // A resource-level 403 remains a business permission denial, not a global logout.
            sessionEpoch.requireCurrent(epoch)
            if (e.statusCode == 401) {
                sessionEpoch.advance()
                state = AuthState.Error("Your session has expired. Please sign in again.", retryLogin = true)
                auth.clearCredentials()
            }
            throw e
        }
    }
    suspend fun estimatePage(customer: String, estimateState: String?, clientState: String?, offset: Int) = authenticatedRequest { api.estimates(it, company(), customer, estimateState, clientState, offset) }
    suspend fun dashboardData(path: String) = authenticatedRequest { api.dashboardData(it, path, company()) }
    suspend fun documentAction(id: String, action: String, body: org.json.JSONObject = org.json.JSONObject()) = authenticatedRequest { api.documentAction(it, company(), id, action, body) }
    suspend fun accountOperation(path: String, method: String = "GET", body: org.json.JSONObject? = null): Any {
        require(path in listOf("account/lifecycle","account/deletion-requests","account/terms-acceptance") || Regex("account/deletion-requests/[a-fA-F0-9-]{36}/cancel").matches(path))
        return authenticatedRequest(accountOnly = true) { api.operation(it, "", path, method, body) }
    }
    suspend fun operation(path: String, method: String = "GET", body: org.json.JSONObject? = null, providerKey: String? = null) = authenticatedRequest { api.operation(it, company(), path, method, body, providerKey) }
    suspend fun customCollection(endpoint: String,key: String,model: String,prompt: String,inventory: org.json.JSONObject) = authenticatedRequest { api.customCollection(endpoint,key,model,prompt,inventory) }
    suspend fun taskPhoto(path: String, jpeg: ByteArray, requestId: String) = authenticatedRequest { api.taskPhoto(it, company(), path, jpeg, requestId) }
    suspend fun draft(id: String) = authenticatedRequest { api.draft(it, company(), id) }
    suspend fun appendCompanyContacts(clientId: String, contacts: org.json.JSONArray) = authenticatedRequest {
        api.appendCompanyContacts(it, company(), clientId, contacts)
    }
    suspend fun saveDraft(draft: org.json.JSONObject) = authenticatedRequest { api.saveDraft(it, company(), draft) }
    suspend fun estimate(id: String) = authenticatedRequest { api.estimate(it, id, company()) }

    var authenticationUnavailable by mutableStateOf(false); private set
    val usesBetterAuth get() = auth.usesBetterAuth
    suspend fun nativeRequest(path: String, body: org.json.JSONObject? = null) = auth.nativeRequest(path,body)
    suspend fun nativeSignIn(email: String, password: String, code: String? = null, backup: Boolean = false): Boolean {
        check(!busy); busy=true; sessionEpoch.advance()
        try {
            val complete=auth.nativeSignIn(email,password,code,backup)
            if(complete)initialize(auth.accessToken())
            return complete
        } finally { busy=false }
    }
    suspend fun createNativeCompany(fields: org.json.JSONObject) {
        check(!busy && state is AuthState.PendingAccount); busy=true;sessionEpoch.advance()
        try { val token=auth.accessToken();api.createNativeCompany(token,fields);initialize(token) }finally{busy=false}
    }
    fun restore() = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        sessionEpoch.advance()
        if (state !is AuthState.Authenticated) state = AuthState.Loading
        try { auth.restore()?.let { initialize(it.accessToken) } ?: run { state = AuthState.SignedOut } }
        catch (e: CancellationException) { throw e }
        catch (e: NativeAuthException) {
            if((e.status>=500 || e.status==429) && (state is AuthState.Authenticated || state is AuthState.CompanySelection))authenticationUnavailable=true
            else {authenticationUnavailable=false;state = AuthState.Error(e.message ?: "Authentication unavailable", retryLogin = e.status == 401)}
        }
        catch (_: Exception) { state = AuthState.Error("Sign in is not configured. Please contact your administrator.", retryLogin = true) }
        finally { busy = false }
    }

    fun signIn(activity: ComponentActivity, createAccount: Boolean = false) = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        sessionEpoch.advance()
        state = AuthState.Loading
        auth.clearCredentials()
        try { initialize(auth.login(activity, createAccount).accessToken) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e.isAuthenticationCanceled()) state = AuthState.SignedOut
            else state = AuthState.Error("Sign in could not finish. Please try again or contact your administrator.", retryLogin = true)
        } finally { busy = false }
    }

    private suspend fun initialize(token: String) {
        try {
            val selected = if (state is AuthState.Authenticated) membership else null
            val account = api.currentUser(token)
            val companies = api.companies(token, selected)
            memberships = companies.memberships; membership = companies.selected
            authenticationUnavailable=false
            state = if (membership == null) AuthState.CompanySelection(account) else AuthState.Authenticated(account)
        }
        catch (e: ApiException) {
            if(e.statusCode>=500 && usesBetterAuth && (state is AuthState.Authenticated || state is AuthState.CompanySelection)){authenticationUnavailable=true}
            else if (e.statusCode == 409 && usesBetterAuth) { state=AuthState.PendingAccount }
            else if (e.statusCode == 401 || e.statusCode == 403) {
                auth.clearCredentials()
                authenticationUnavailable=false
                state = AuthState.Error(e.message ?: "Access was denied. Please sign in again.", retryLogin = true)
            } else state = AuthState.Error(e.message ?: "Could not initialize your account.")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state = AuthState.Error("Could not connect to EzBoss. Check your connection and retry.") }
    }

    fun retry() = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        sessionEpoch.advance()
        state = AuthState.Loading
        try { initialize(auth.accessToken()) }
        catch (e: CancellationException) { throw e }
        catch (e: NativeAuthException) { state=AuthState.Error(e.message ?: "Authentication unavailable",retryLogin=e.status==401) }
        catch (_: Exception) {
            auth.clearCredentials()
            state = AuthState.Error("Could not restore your session. Please sign in again.", retryLogin = true)
        } finally { busy = false }
    }

    fun signOut() = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        sessionEpoch.advance()
        authenticationUnavailable=false
        state = AuthState.SignedOut
        try { auth.signOut() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { state = AuthState.Error("Session removal failed. Please retry sign-out.", retryLogin = true) }
        finally { busy = false }
    }

    class Factory(private val auth: AuthRepository, private val api: ApiClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(auth, api) as T
    }
}

@Composable private fun EzBossApp(model: AuthViewModel, activity: ComponentActivity) {
    var drawerOpen by remember { mutableStateOf(false) }
    var accountRequests by remember { mutableStateOf(false) }
    LaunchedEffect(model.state) { if(model.state !is AuthState.CompanySelection)accountRequests=false }
    var protectedSession by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { model.restore() }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    if(model.authenticationUnavailable)AlertDialog(onDismissRequest={},title={Text("Authentication is temporarily unavailable")},text={Text("Your session and open work are kept. Retry before continuing.")},confirmButton={TextButton(enabled=!model.busy,onClick={model.restore()}){Text("Retry account access")}},dismissButton={TextButton(enabled=!model.busy,onClick={model.signOut()}){Text("Sign out")}})
    Surface(Modifier.fillMaxSize(), color = Canvas) {
        when (val current = model.state) {
            AuthState.PendingAccount -> pro.ezboss.mobile.auth.ui.NativeCompanySetupScreen(model)
            AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EzBossLoadingIndicator(color = BrandOrange, page = false) }
            AuthState.SignedOut -> { drawerOpen = false; LoginScreen(model, null, onCreateAccount = { model.signIn(activity, createAccount = true) }) { model.signIn(activity) } }
            is AuthState.Error -> {
                drawerOpen = false
                LoginScreen(model, current.message, current.sessionExpired, onCreateAccount = { model.signIn(activity, createAccount = true) }) {
                    if (current.sessionExpired) model.signOut()
                    else if (current.message == MissingAuthConfiguration().message || current.retryLogin) model.signIn(activity)
                    else model.retry()
                }
            }
            is AuthState.CompanySelection -> if(accountRequests) AccountLifecycleScreen(model) { accountRequests=false } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("Select a company", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick={accountRequests=true}){Text("Account requests and terms")}
                if (model.memberships.isEmpty()) Text("No company is linked. Contact your administrator.")
                model.memberships.forEach { company -> Button(onClick = { model.selectCompany(company.id) }, enabled = company.available && !model.busy) { Text(company.name) } }
                Button(onClick = { model.signOut() }) { Text("Sign out") }
            }
            is AuthState.Authenticated -> key(protectedSession, model.membership) {
        AuthenticatedShell(current.account, model, drawerOpen, { drawerOpen = true }, { drawerOpen = false }, model.busy) {
                    drawerOpen = false
                    protectedSession++
                    model.signOut()
                }
            }
        }
    }
}

@Composable private fun LoginScreen(model: AuthViewModel, error: String?, expired: Boolean = false, onCreateAccount: () -> Unit, onAction: () -> Unit) {
    if (model.usesBetterAuth) { pro.ezboss.mobile.auth.ui.NativeIdentityScreen(model,error); return }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFF5F5F1)).safeDrawingPadding()) {
        val viewportHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = viewportHeight).padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 24.dp)) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ezboss_loading),
                    contentDescription = "EzBoss",
                    modifier = Modifier.width(186.dp).height(56.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
                Spacer(Modifier.height(32.dp))
                Text("BUILT FOR THE WAY CONTRACTORS WORK", color = Color(0xFF202321), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(20.dp))
                Text("Welcome to EzBoss", color = Color(0xFF636861), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(20.dp))
                Text("Less tedious estimates.", color = Color(0xFF202321), fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-1.4).sp, fontWeight = FontWeight.ExtraBold)
                Text("More building.", color = Color(0xFFED5909), fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-1.4).sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(24.dp))
                Text("Put your imported data to work. Turn the materials and labor you already have into reusable collections—and your next estimate into a much shorter to-do.", color = Color(0xFF636861), style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp)
                Spacer(Modifier.height(24.dp))
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                    Spacer(Modifier.height(20.dp))
                }
                Button(onClick = onAction, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFED5909))) {
                    if (model.busy) EzBossLoadingIndicator(Modifier.width(100.dp), color = Color.White, strokeWidth = 2.dp, page = false)
                    else Text(if (error == null || expired) "Sign In" else "Retry", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onCreateAccount, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF202321))) {
                    Text("Create Account", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable private fun AuthenticatedShell(account: Account, model: AuthViewModel, open: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit, busy: Boolean, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    var allowedPages by remember(model.membership) { mutableStateOf<Set<String>?>(null) }
    var accessError by remember(model.membership) { mutableStateOf(false) }
    var accessRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(model.membership, accessRetry) {
        allowedPages = null; accessError = false
        try {
            val result = model.operation("account/me?membershipId=${model.membership}") as org.json.JSONObject
            val effective = result.getJSONObject("effectivePermissions")
            val pages = effective.getJSONObject("pages")
            val capabilities = effective.getJSONObject("capabilities")
            allowedPages = pages.keys().asSequence().filter { pages.getJSONObject(it).optBoolean("allowed") }.toSet().filter { key ->
                key !in listOf("inventory", "collections", "purchasing") || capabilities.optJSONObject("read:$key")?.optBoolean("allowed") == true
            }.toSet().let { allowed ->
                if (listOf("inventory-products", "inventory-tools", "inventory-equipment").all { it in allowed } && capabilities.optJSONObject("read:inventory")?.optBoolean("allowed") == true) allowed
                else allowed - "purchasing"
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { accessError = true }
    }
    fun canOpen(route: String): Boolean = when(route.substringBefore("?")) {
        "settings" -> true
        "people" -> allowedPages.orEmpty().any { it in listOf("clients", "employees") }
        "finance" -> "finances" in allowedPages.orEmpty()
        else -> route.substringBefore("?") in allowedPages.orEmpty()
    }
    val backStack by navController.currentBackStackEntryAsState()
    val selectedRoute = backStack?.destination?.route ?: appDestinations.first().route
    BackHandler(enabled = !open && navController.previousBackStackEntry != null) { navController.popBackStack() }
    val openingDistance = with(LocalDensity.current) { 48.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Canvas).semantics {
        customActions = listOf(CustomAccessibilityAction(if (open) "Close navigation drawer" else "Open navigation drawer") {
            if (open) onDismiss() else onOpen()
            true
        })
    }) {
        val drawerWidth = minOf(maxWidth * .84f, 340.dp)
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = Color.White, tonalElevation = 0.dp) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedRoute == EstimateDetailRoute) {
                            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to documents") }
                        } else {
                            IconButton(onClick = onOpen) { Icon(Icons.Outlined.Menu, "Open navigation") }
                        }
                        Text(model.memberships.firstOrNull { it.id == model.membership }?.name ?: "EzBoss", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Box(Modifier.weight(1f)) {
                    AuthenticatedDestinationHost(navController, model, account, ::canOpen, allowedPages != null, accessError) { accessRetry++ }

                }
            }
        }
        if (open) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).background(Color(0x800E1916)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onDismiss,
            ).semantics { contentDescription = "Dismiss navigation drawer" })
            Column(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).width(drawerWidth).fillMaxHeight().imePadding().background(EzBossDesign.Navy).padding(18.dp)) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close navigation", tint = Color.White) }
                ProfileArea(account)
                Spacer(Modifier.height(20.dp)); HorizontalDivider(color = EzBossDesign.NavySurface)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).semantics { contentDescription = "Navigation destinations" }) {
                    appDestinations.filter { canOpen(it.route) }.forEach { destination ->
                        val selected = selectedRoute.substringBefore("?") == destination.route || (destination.route == "estimates" && selectedRoute == EstimateDetailRoute)
                        NavigationDestinationRow(destination.title, selected) {
                            if (!selected) navController.navigate(destination.route) { launchSingleTop = true }
                            onDismiss()
                        }
                    }
                }
                model.memberships.filter { it.available }.forEach { company ->
                    TextButton(onClick = { onDismiss(); model.selectCompany(company.id) }, enabled = !busy && company.id != model.membership) { Text(company.name) }
                }
                HorizontalDivider(color = EzBossDesign.NavySurface); Spacer(Modifier.height(12.dp))
                TextButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFB49B))) {
                    Text(if (busy) "Signing out…" else "Sign Out", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (!open) {
            // Reserve only the left edge; scrolling and gestures elsewhere stay with the page.
            Box(Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing)
                .width(24.dp).fillMaxHeight().systemGestureExclusion()
                .pointerInput(openingDistance, onOpen) {
                    var distance = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { distance = 0f },
                        onDragEnd = { distance = 0f },
                        onDragCancel = { distance = 0f },
                    ) { change, dragAmount ->
                        distance = (distance + dragAmount).coerceAtLeast(0f)
                        if (distance >= openingDistance) {
                            change.consume()
                            onOpen()
                        }
                    }
                })
        }
    }
}

@Composable private fun AuthenticatedDestinationHost(navController: NavHostController, model: AuthViewModel, account: Account, canOpen: (String) -> Boolean, accessLoaded: Boolean, accessError: Boolean, retryAccess: () -> Unit) {
    NavHost(navController = navController, startDestination = appDestinations.first().route, modifier = Modifier.fillMaxSize()) {
        appDestinations.forEach { destination ->
            composable(if (destination.route == "estimates") "estimates?type={type}" else destination.route, arguments = if (destination.route == "estimates") listOf(navArgument("type") { type = NavType.StringType; defaultValue = "all" }) else emptyList()) { entry ->
                if (!canOpen(destination.route)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(if(accessError) "Workspace access could not be loaded." else if(!accessLoaded) "Loading workspace access…" else "This page is unavailable for your account.")
                        if(!accessLoaded && !accessError) EzBossLoadingIndicator() else TextButton(onClick = retryAccess) { Text("Retry workspace access") }
                    }
                }
                else if (destination.route == "dashboard") DashboardScreen(account, model, { navController.navigate("estimates/$it") }, { navController.navigate(it) { launchSingleTop = true } }, canOpen)
                else if (destination.route == "estimates") EstimatesListScreen(model, when(entry.arguments?.getString("type")) { "estimate" -> "Estimate"; "invoice" -> "Invoice"; else -> "All" }) { id -> navController.navigate("estimates/$id") }
                else if (destination.route in listOf("inventory", "collections", "work-orders", "purchasing")) OperationsScreen(model, destination.route, canOpen)
                else if (destination.route in listOf("projects","finance")) BusinessRecordsScreen(model,destination.route)
                else if (destination.route == "people") PeopleSettingsScreen(model, false)
                else if (destination.route == "settings") PeopleSettingsScreen(model, true)
                else PlaceholderDestination(destination.title)
            }
        }
        composable("estimates/{estimateId}") { entry ->
            val id = entry.arguments?.getString("estimateId").orEmpty()
            EstimateDetailScreen(model, id) { navController.popBackStack() }
        }
    }
}

@Composable private fun NavigationDestinationRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) BrandOrange else Color.Transparent
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = 48.dp).semantics { this.selected = selected },
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White, containerColor = background),
    ) {
        Icon(when(title) {
            "Dashboard" -> Icons.Outlined.Dashboard
            "Estimates" -> Icons.Outlined.Description
            "Projects" -> Icons.Outlined.Business
            "Inventory" -> Icons.Outlined.Inventory2
            "Collections" -> Icons.Outlined.CollectionsBookmark
            "Work Orders" -> Icons.Outlined.Checklist
            "Finances" -> Icons.Outlined.AccountBalanceWallet
            "Purchasing" -> Icons.Outlined.ShoppingCart
            "People" -> Icons.Outlined.People
            else -> Icons.Outlined.Settings
        }, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable private fun PlaceholderDestination(title: String) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(title, color = Ink, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("Coming soon", color = EzBossDesign.Muted, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun ProfileArea(account: Account) {
    val name = account.displayName?.takeIf(String::isNotBlank)
    val initials = name?.split(Regex("\\s+")).orEmpty().take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
        .ifBlank { account.email.firstOrNull()?.uppercaseChar()?.toString() ?: "E" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(EzBossDesign.NavySurface).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFEDD5)), contentAlignment = Alignment.Center) {
            Text(initials, color = EzBossDesign.OrangeDark, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text(name ?: account.email.ifBlank { "EzBoss user" }, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (name != null && account.email.isNotBlank()) Text(account.email, color = EzBossDesign.DrawerText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Throwable.isAuthenticationCanceled(): Boolean = this is AuthenticationException && isCanceled
