package pro.ezboss.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
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
    AppDestination("Estimates", "estimates"),
    AppDestination("Inventory", "inventory"),
    AppDestination("Collections", "collections"),
    AppDestination("Work Orders", "work-orders"),
    AppDestination("Purchasing", "purchasing"),
    AppDestination("People", "people"),
    AppDestination("Settings", "settings"),
)

// Reserved for a future authenticated estimate detail destination.
private const val EstimateDetailRoute = "estimates/{estimateId}"

class MainActivity : ComponentActivity() {
    private val authConfig by lazy { authConfiguration() }
    private val model: AuthViewModel by viewModels { AuthViewModel.Factory(AuthRepository(applicationContext, authConfig), ApiClient(authConfig.apiBaseUrl)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EzBossTheme { EzBossApp(model, this) } }
    }
}

class AuthViewModel(private val auth: AuthRepository, private val api: ApiClient) : ViewModel() {
    var state by mutableStateOf<AuthState>(AuthState.Loading); private set
    var busy by mutableStateOf(false); private set
    suspend fun estimatePage(customer: String, estimateState: String?, clientState: String?, offset: Int) = api.estimates(auth.accessToken(), customer, estimateState, clientState, offset)
    suspend fun estimate(id: String) = api.estimate(auth.accessToken(), id)

    fun restore() = viewModelScope.launch {
        state = AuthState.Loading
        try { auth.restore()?.let { initialize(it.accessToken) } ?: run { state = AuthState.SignedOut } }
        catch (e: Exception) { state = AuthState.Error(e.message ?: "Sign in is not configured.") }
    }

    fun signIn(activity: ComponentActivity) = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        try { initialize(auth.login(activity).accessToken) }
        catch (e: Exception) {
            if (e.isAuthenticationCanceled()) state = AuthState.SignedOut
            else {
                Log.e("EzBossAuth", "Auth0 sign-in failed", e)
                (e as? AuthenticationException)?.let { authError ->
                    Log.e(
                        "EzBossAuth",
                        "Auth0 response: code=${authError.getCode()}, status=${authError.statusCode}, " +
                            "description=${authError.getDescription()}",
                    )
                }
                state = AuthState.Error(e.message ?: "Sign in failed. Please try again.", retryLogin = true)
            }
        } finally { busy = false }
    }

    private suspend fun initialize(token: String) {
        try { state = AuthState.Authenticated(api.currentUser(token)) }
        catch (e: ApiException) {
            if (e.statusCode == 401) { auth.clearCredentials(); state = AuthState.Error(e.message ?: "Session expired.", true) }
            else state = AuthState.Error(e.message ?: "Could not initialize your account.")
        } catch (e: Exception) { state = AuthState.Error(e.message ?: "Could not connect to EzBoss. Check your connection and retry.") }
    }

    fun retry() = viewModelScope.launch {
        try { initialize(auth.accessToken()) }
        catch (e: Exception) { state = AuthState.Error(e.message ?: "Could not restore your session.", true) }
    }

    fun signOut(activity: ComponentActivity) = viewModelScope.launch {
        busy = true
        state = AuthState.SignedOut
        try { auth.signOut(activity); state = AuthState.SignedOut }
        catch (e: Exception) { state = AuthState.Error(e.message ?: "Could not sign out. Please try again.") }
        finally { busy = false }
    }

    class Factory(private val auth: AuthRepository, private val api: ApiClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(auth, api) as T
    }
}

@Composable private fun EzBossApp(model: AuthViewModel, activity: ComponentActivity) {
    var drawerOpen by remember { mutableStateOf(false) }
    var protectedSession by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { model.restore() }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    Surface(Modifier.fillMaxSize(), color = Canvas) {
        when (val current = model.state) {
            AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BrandOrange) }
            AuthState.SignedOut -> { drawerOpen = false; LoginScreen(model, null) { model.signIn(activity) } }
            is AuthState.Error -> {
                drawerOpen = false
                LoginScreen(model, current.message, current.sessionExpired) {
                    if (current.sessionExpired) model.signOut(activity)
                    else if (current.message == MissingAuthConfiguration().message || current.retryLogin) model.signIn(activity)
                    else model.retry()
                }
            }
            is AuthState.Authenticated -> key(protectedSession) {
        AuthenticatedShell(current.account, model, drawerOpen, { drawerOpen = !drawerOpen }, { drawerOpen = false }, model.busy) {
                    drawerOpen = false
                    protectedSession++
                    model.signOut(activity)
                }
            }
        }
    }
}

@Composable private fun LoginScreen(model: AuthViewModel, error: String?, expired: Boolean = false, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 30.dp, vertical = 24.dp), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center) {
        Text("EzBoss", color = BrandOrange, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(56.dp))
        Text("Welcome back", color = Ink, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("Sign in to manage your business.", color = EzBossDesign.Muted, style = MaterialTheme.typography.bodyLarge)
        if (error != null) {
            Spacer(Modifier.height(24.dp)); Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
        } else Spacer(Modifier.height(28.dp))
        Button(onClick = onAction, enabled = !model.busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = EzBossDesign.OrangeDark)) {
            if (model.busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(if (error == null || expired) "Sign In" else "Retry", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun AuthenticatedShell(account: Account, model: AuthViewModel, open: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit, busy: Boolean, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val selectedRoute = backStack?.destination?.route ?: appDestinations.first().route
    BackHandler(enabled = !open && navController.previousBackStackEntry != null) { navController.popBackStack() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Canvas)) {
        val drawerWidth = minOf(maxWidth * .84f, 340.dp)
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            AuthenticatedDestinationHost(navController, model)
        }
        if (open) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).background(Color(0x800E1916)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onDismiss,
            ).semantics { contentDescription = "Dismiss navigation drawer" })
            Column(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).width(drawerWidth).fillMaxHeight().imePadding().background(EzBossDesign.Navy).padding(18.dp)) {
                ProfileArea(account)
                Spacer(Modifier.height(20.dp)); HorizontalDivider(color = EzBossDesign.NavySurface)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).semantics { contentDescription = "Navigation destinations" }) {
                    appDestinations.forEach { destination ->
                        val selected = selectedRoute == destination.route || (destination.route == "estimates" && selectedRoute == EstimateDetailRoute)
                        NavigationDestinationRow(destination.title, selected) {
                            if (!selected) navController.navigate(destination.route) { launchSingleTop = true }
                            onDismiss()
                        }
                    }
                }
                HorizontalDivider(color = EzBossDesign.NavySurface); Spacer(Modifier.height(12.dp))
                TextButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFB49B))) {
                    Text(if (busy) "Signing out…" else "Sign Out", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // Keep the menu available above both the page and the open drawer.
        MenuButton(open, onToggle, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp))
    }
}

@Composable private fun AuthenticatedDestinationHost(navController: NavHostController, model: AuthViewModel) {
    NavHost(navController = navController, startDestination = appDestinations.first().route, modifier = Modifier.fillMaxSize()) {
        appDestinations.forEach { destination ->
            composable(destination.route) {
                if (destination.route == "estimates") EstimatesListScreen(model) { id -> navController.navigate("estimates/$id") }
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White, containerColor = background),
    ) {
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

@Composable private fun MenuButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(56.dp).semantics { contentDescription = if (open) "Close navigation drawer" else "Open navigation drawer" },
        containerColor = Color.White,
        contentColor = BrandOrange,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 8.dp),
    ) {
        Icon(if (open) Icons.Filled.Close else Icons.Filled.Menu, contentDescription = null)
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
