package ro.blazemessenger.app.ui.root

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ro.blazemessenger.app.R
import ro.blazemessenger.app.call.CallCoordinator
import ro.blazemessenger.app.config.BackendConfig
import ro.blazemessenger.app.data.auth.AuthRepository
import ro.blazemessenger.app.data.auth.authError
import ro.blazemessenger.app.data.prefs.SettingsRepository
import ro.blazemessenger.app.data.toAppError
import ro.blazemessenger.app.data.user.UserRepository
import ro.blazemessenger.app.domain.model.AppError
import ro.blazemessenger.app.domain.model.AppLanguage
import ro.blazemessenger.app.domain.model.LocalSettings
import ro.blazemessenger.app.domain.model.ThemeMode
import ro.blazemessenger.app.nav.DeepLinks
import ro.blazemessenger.app.ui.chat.ConversationScreen
import ro.blazemessenger.app.ui.common.asText
import ro.blazemessenger.app.ui.home.HomeScreen
import ro.blazemessenger.app.ui.home.ProfileScreen
import ro.blazemessenger.app.ui.theme.BlazeTheme

sealed interface Session {
    data object Loading : Session
    data object NeedsSetup : Session
    data object SignedOut : Session
    data class NeedsVerification(val email: String) : Session
    data object NeedsProfile : Session
    data class Ready(val uid: String) : Session
}

@HiltViewModel
class RootViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val users: UserRepository,
    private val config: BackendConfig,
    settingsRepository: SettingsRepository,
    private val coordinator: CallCoordinator,
) : ViewModel() {
    private val _session = MutableStateFlow<Session>(Session.Loading)
    val session = _session.asStateFlow()
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalSettings())

    init {
        if (!config.configured) {
            _session.value = Session.NeedsSetup
        } else {
            viewModelScope.launch {
                auth.authState().collect { user ->
                    if (user == null) {
                        _session.value = Session.SignedOut
                        return@collect
                    }
                    runCatching { auth.reload() }
                    val current = auth.currentUser
                    val profile = runCatching { users.profile(current?.uid.orEmpty()) }.getOrNull()
                    _session.value = when {
                        current == null -> Session.SignedOut
                        profile?.profileComplete != true -> Session.NeedsProfile
                        current.isEmailVerified || current.providerData.any { it.providerId == "google.com" } -> {
                            runCatching {
                                val token = FirebaseMessaging.getInstance().token.await()
                                users.saveToken(token)
                            }
                            coordinator.restore()
                            Session.Ready(current.uid)
                        }
                        else -> Session.NeedsVerification(current.email.orEmpty())
                    }
                }
            }
        }
    }
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<AppError?>(null)
        private set
    var info by mutableStateOf<Int?>(null)
        private set

    fun signIn(email: String, password: String) = launch { auth.signIn(email, password) }
    fun signUp(email: String, password: String, username: String, displayName: String) = launch {
        auth.signUp(email, password, username, displayName)
    }
    fun reset(email: String) = launch {
        auth.sendPasswordReset(email)
        info = R.string.reset_sent
    }
    fun resend() = launch {
        auth.sendVerification()
        info = R.string.verify_sent
    }
    fun refresh() = launch { auth.reload() }
    fun complete(username: String, displayName: String) = launch { auth.completeUsername(username, displayName) }
    fun google(context: android.content.Context) = launch { auth.signInWithGoogle(context) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            loading = true
            error = null
            info = null
            runCatching { block() }.onFailure { error = it.authError().let { mapped -> if (mapped is AppError.Message && mapped.text == "unknown") it.toAppError() else mapped } }
            loading = false
        }
    }
}

@Composable
fun BlazeRoot(rootViewModel: RootViewModel = hiltViewModel()) {
    val settings by rootViewModel.settings.collectAsState()
    val dark = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    LaunchedEffect(settings.language) {
        val locales = when (settings.language) {
            AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
            AppLanguage.RO -> LocaleListCompat.forLanguageTags("ro")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
    BlazeTheme(darkTheme = dark) {
        val nav = rememberNavController()
        val session by rootViewModel.session.collectAsState()
        AppNav(nav, session, settings)
    }
}

@Composable
private fun AppNav(nav: NavHostController, session: Session, settings: LocalSettings) {
    NavHost(navController = nav, startDestination = "splash") {
        composable("splash") { SplashScreen(nav, session, settings) }
        composable("onboarding") { OnboardingScreen(onDone = { nav.navigate("gate") }) }
        composable("gate") { Gate(nav, session) }
        composable("setup") { MessageScreen(stringResource(R.string.setup_title), stringResource(R.string.setup_body)) }
        composable("auth") { AuthScreen(onForgot = { nav.navigate("reset") }) }
        composable("reset") { ResetScreen(onBack = { nav.popBackStack() }) }
        composable("verify") { VerifyScreen() }
        composable("complete") { CompleteProfileScreen() }
        composable("main") { HomeScreen(nav) }
        composable("chat/{chatId}") { ConversationScreen(onBack = { nav.popBackStack() }) }
        composable("profile") { ProfileScreen(onBack = { nav.popBackStack() }) }
    }
    SessionEffect(nav, session)
    DeepLinkEffect(nav, session)
}

@Composable
private fun SessionEffect(nav: NavHostController, session: Session) {
    val route = nav.currentBackStackEntry?.destination?.route
    LaunchedEffect(session, route) {
        if (route == null || route == "splash" || route == "onboarding" || route == "gate") return@LaunchedEffect
        when (session) {
            is Session.Ready -> {
                if (route == "auth" || route == "verify" || route == "complete" || route == "setup") {
                    nav.navigate("main") { popUpTo(0) }
                }
            }
            Session.SignedOut -> {
                if (route != "auth" && route != "reset" && route != "setup") {
                    nav.navigate("auth") { popUpTo(0) }
                }
            }
            is Session.NeedsVerification -> if (route != "verify") nav.navigate("verify") { launchSingleTop = true }
            Session.NeedsProfile -> if (route != "complete") nav.navigate("complete") { launchSingleTop = true }
            Session.NeedsSetup -> if (route != "setup") nav.navigate("setup") { popUpTo(0) }
            Session.Loading -> Unit
        }
    }
}

@Composable
private fun DeepLinkEffect(nav: NavHostController, session: Session) {
    val deepLinks = androidx.hilt.navigation.compose.hiltViewModel<DeepLinkViewModel>()
    LaunchedEffect(session) {
        if (session is Session.Ready) {
            deepLinks.links.chats.collect { id ->
                nav.navigate("chat/$id") { launchSingleTop = true }
            }
        }
    }
}

@HiltViewModel
class DeepLinkViewModel @Inject constructor(val links: DeepLinks) : ViewModel()

@Composable
private fun SplashScreen(nav: NavHostController, session: Session, settings: LocalSettings) {
    LaunchedEffect(session, settings.onboardingDone) {
        if (session is Session.Loading) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        val next = if (!settings.onboardingDone && session !is Session.Ready) "onboarding" else "gate"
        nav.navigate(next) { popUpTo("splash") { inclusive = true } }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_blaze_logo), contentDescription = stringResource(R.string.cd_logo), modifier = Modifier.height(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.splash_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Gate(nav: NavHostController, session: Session) {
    LaunchedEffect(session) {
        val target = when (session) {
            Session.NeedsSetup -> "setup"
            Session.SignedOut -> "auth"
            is Session.NeedsVerification -> "verify"
            Session.NeedsProfile -> "complete"
            is Session.Ready -> "main"
            Session.Loading -> return@LaunchedEffect
        }
        nav.navigate(target) {
            popUpTo("gate") { inclusive = true }
            launchSingleTop = true
        }
    }
    BoxProgress()
}

@Composable
private fun OnboardingScreen(onDone: () -> Unit, settingsViewModel: ro.blazemessenger.app.ui.home.SettingsViewModel = hiltViewModel()) {
    val pages = listOf(
        Triple(R.drawable.illu_chat, R.string.onboarding_title_1, R.string.onboarding_body_1),
        Triple(R.drawable.illu_call, R.string.onboarding_title_2, R.string.onboarding_body_2),
        Triple(R.drawable.illu_privacy, R.string.onboarding_title_3, R.string.onboarding_body_3),
    )
    val pager = rememberPagerState(pageCount = { pages.size })
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                settingsViewModel.finishOnboarding()
                onDone()
            }) { Text(stringResource(R.string.onboarding_skip)) }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
            val page = pages[index]
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
                Image(painterResource(page.first), contentDescription = null, modifier = Modifier.height(160.dp))
                Spacer(Modifier.height(24.dp))
                Text(stringResource(page.second), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(page.third), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val scope = rememberCoroutineScope()
        Button(onClick = {
            if (pager.currentPage < pages.lastIndex) {
                scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            } else {
                settingsViewModel.finishOnboarding()
                onDone()
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (pager.currentPage == pages.lastIndex) R.string.onboarding_start else R.string.onboarding_next))
        }
    }
}

@Composable
private fun AuthScreen(onForgot: () -> Unit, viewModel: AuthViewModel = hiltViewModel()) {
    var create by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).widthIn(max = 480.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(if (create) R.string.auth_create_title else R.string.auth_welcome),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(20.dp))
            if (create) {
                OutlinedTextField(displayName, { displayName = it }, label = { Text(stringResource(R.string.auth_display_name)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.auth_username)) }, supportingText = { Text(stringResource(R.string.auth_username_hint)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.auth_email)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                password,
                { password = it },
                label = { Text(stringResource(R.string.auth_password)) },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { if (create) Text(stringResource(R.string.auth_password_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            viewModel.error?.let { Text(it.asText(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (create) viewModel.signUp(email, password, username, displayName) else viewModel.signIn(email, password)
                },
                enabled = !viewModel.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (create) R.string.auth_submit_sign_up else R.string.auth_submit_sign_in))
            }
            OutlinedButton(onClick = { viewModel.google(context) }, enabled = !viewModel.loading, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.auth_google))
            }
            TextButton(onClick = onForgot) { Text(stringResource(R.string.auth_forgot)) }
            TextButton(onClick = { create = !create }) {
                Text(stringResource(if (create) R.string.auth_have_account else R.string.auth_need_account))
            }
            if (viewModel.loading) CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun ResetScreen(onBack: () -> Unit, viewModel: AuthViewModel = hiltViewModel()) {
    var email by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.reset_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.reset_body))
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.auth_email)) }, modifier = Modifier.fillMaxWidth())
        viewModel.info?.let { Text(stringResource(it), modifier = Modifier.padding(top = 8.dp)) }
        viewModel.error?.let { Text(it.asText(), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { viewModel.reset(email) }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.reset_send)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun VerifyScreen(viewModel: AuthViewModel = hiltViewModel(), root: RootViewModel = hiltViewModel()) {
    val session by root.session.collectAsState()
    val email = (session as? Session.NeedsVerification)?.email.orEmpty()
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.verify_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.verify_body, email))
        viewModel.info?.let { Text(stringResource(it), modifier = Modifier.padding(top = 8.dp)) }
        viewModel.error?.let { Text(it.asText(), color = MaterialTheme.colorScheme.error) }
        Button(onClick = viewModel::refresh, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.verify_refresh)) }
        OutlinedButton(onClick = viewModel::resend, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.verify_resend)) }
    }
}

@Composable
private fun CompleteProfileScreen(viewModel: AuthViewModel = hiltViewModel()) {
    var username by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.complete_profile_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.complete_profile_body), modifier = Modifier.padding(vertical = 8.dp))
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.auth_display_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.auth_username)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        viewModel.error?.let { Text(it.asText(), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { viewModel.complete(username, name) }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.continue_label)) }
    }
}

@Composable
private fun MessageScreen(title: String, body: String) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(body)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.setup_steps), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BoxProgress() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
    }
}
