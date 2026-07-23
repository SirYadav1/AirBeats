package com.darkxvenom.airbeats.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.darkxvenom.airbeats.innertube.YouTube
import com.darkxvenom.airbeats.innertube.utils.parseCookieString
import com.darkxvenom.airbeats.LocalPlayerAwareWindowInsets
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.constants.AccountChannelHandleKey
import com.darkxvenom.airbeats.constants.AccountEmailKey
import com.darkxvenom.airbeats.constants.AccountNameKey
import com.darkxvenom.airbeats.constants.InnerTubeCookieKey
import com.darkxvenom.airbeats.constants.VisitorDataKey
import com.darkxvenom.airbeats.ui.component.IconButton
import com.darkxvenom.airbeats.ui.utils.backToMain
import com.darkxvenom.airbeats.utils.rememberPreference
import com.darkxvenom.airbeats.utils.reportException
import com.darkxvenom.airbeats.utils.AuthApiClient
import com.darkxvenom.airbeats.utils.AuthResult
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import timber.log.Timber

private const val YOUTUBE_MUSIC_URL = "https://music.youtube.com"
private const val MAX_RETRY_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 1000L

enum class LoginUiState {
    EMAIL_LOGIN, EMAIL_SIGNUP, GOOGLE_WEBVIEW
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, DelicateCoroutinesApi::class)
@Composable
fun LoginScreen(navController: NavController) {
    var visitorData by rememberPreference(VisitorDataKey, "")
    var innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    var accountName by rememberPreference(AccountNameKey, "")
    var accountEmail by rememberPreference(AccountEmailKey, "")
    var accountChannelHandle by rememberPreference(AccountChannelHandleKey, "")

    val context = LocalContext.current
    val backupViewModel: com.darkxvenom.airbeats.viewmodels.BackupRestoreViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val authClient = remember { AuthApiClient() }

    var uiState by remember { mutableStateOf(LoginUiState.EMAIL_LOGIN) }
    var isLoadingAccountInfo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (innerTubeCookie.isNotBlank()) {
            Toast.makeText(context, "You are already logged in with Google. Please clear data or reinstall the app to use Email login.", Toast.LENGTH_LONG).show()
            navController.backToMain()
        }
    }

    suspend fun fetchAccountInfoWithRetry(retryCount: Int = 0) {
        try {
            YouTube.accountInfo().onSuccess { accountInfo ->
                val name = accountInfo.name.takeIf { it.isNotBlank() } ?: ""
                val email = accountInfo.email?.takeIf { it.isNotBlank() } ?: ""
                val handle = accountInfo.channelHandle?.takeIf { it.isNotBlank() } ?: ""

                if (name.isNotEmpty()) {
                    accountName = name
                    accountEmail = email
                    accountChannelHandle = handle
                    Timber.tag("WebView").d("Account info retrieved successfully: $name, $email, $handle")
                    isLoadingAccountInfo = false

                    if (email.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            val backupClient = com.darkxvenom.airbeats.utils.CloudBackupClient()
                            if (backupClient.checkBackupExists(email)) {
                                val result = backupViewModel.restoreFromDrive(context, email)
                                if (result is com.darkxvenom.airbeats.utils.DriveResult.Success) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Cloud backup restored successfully", Toast.LENGTH_SHORT).show()
                                        context.stopService(android.content.Intent(context, com.darkxvenom.airbeats.playback.MusicService::class.java))
                                        context.filesDir.resolve(com.darkxvenom.airbeats.playback.MusicService.PERSISTENT_QUEUE_FILE).delete()
                                        context.startActivity(android.content.Intent(context, com.darkxvenom.airbeats.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                                        kotlin.system.exitProcess(0)
                                    }
                                }
                            } else {
                                backupViewModel.backupToDrive(context, email)
                                withContext(Dispatchers.Main) {
                                    navController.backToMain()
                                }
                            }
                        }
                    } else {
                        navController.backToMain()
                    }
                } else {
                    if (retryCount < MAX_RETRY_ATTEMPTS) {
                        Timber.tag("WebView").w("Account name is empty, retrying... Attempt ${retryCount + 1}")
                        delay(RETRY_DELAY_MS)
                        fetchAccountInfoWithRetry(retryCount + 1)
                    } else {
                        isLoadingAccountInfo = false
                        navController.backToMain()
                    }
                }
            }.onFailure { exception ->
                if (retryCount < MAX_RETRY_ATTEMPTS) {
                    delay(RETRY_DELAY_MS)
                    fetchAccountInfoWithRetry(retryCount + 1)
                } else {
                    reportException(exception)
                    isLoadingAccountInfo = false
                }
            }
        } catch (e: Exception) {
            reportException(e)
            isLoadingAccountInfo = false
        }
    }

    if (uiState == LoginUiState.GOOGLE_WEBVIEW) {
        var webView: WebView? = null
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(if (isLoadingAccountInfo) stringResource(R.string.login) + " - Loading..." else stringResource(R.string.login)) },
                navigationIcon = {
                    IconButton(onClick = { uiState = LoginUiState.EMAIL_LOGIN }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                }
            )
            AndroidView(
                modifier = Modifier
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                    .fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (url != null && url.startsWith(YOUTUBE_MUSIC_URL)) {
                                    val youTubeCookieString = CookieManager.getInstance().getCookie(url)
                                    val parsedCookies = parseCookieString(youTubeCookieString)
                                    if ("SAPISID" in parsedCookies) {
                                        innerTubeCookie = youTubeCookieString
                                        isLoadingAccountInfo = true
                                        GlobalScope.launch {
                                            delay(500)
                                            fetchAccountInfoWithRetry()
                                        }
                                        loadUrl("javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)")
                                    } else {
                                        innerTubeCookie = ""
                                    }
                                }
                            }
                        }
                        settings.apply {
                            javaScriptEnabled = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onRetrieveVisitorData(newVisitorData: String?) {
                                    if (innerTubeCookie.isNotEmpty() && !newVisitorData.isNullOrBlank()) {
                                        visitorData = newVisitorData
                                    }
                                }
                            },
                            "Android"
                        )
                        webView = this
                        loadUrl("https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=$YOUTUBE_MUSIC_URL")
                    }
                }
            )
        }
        BackHandler(enabled = webView?.canGoBack() == true) {
            webView?.goBack()
        }
        return
    }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    fun processEmailAuth(isSignup: Boolean) {
        if (email.isBlank() || password.isBlank() || (isSignup && name.isBlank())) {
            Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }
        isProcessing = true
        scope.launch {
            val result = if (isSignup) {
                authClient.signup(name, email, password)
            } else {
                authClient.login(email, password)
            }
            when (result) {
                is AuthResult.Success -> {
                    accountEmail = result.user.email
                    accountName = result.user.name
                    withContext(Dispatchers.IO) {
                        val backupClient = com.darkxvenom.airbeats.utils.CloudBackupClient()
                        if (isSignup || !backupClient.checkBackupExists(result.user.email)) {
                            backupViewModel.backupToDrive(context, result.user.email)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Account created and backup saved!", Toast.LENGTH_SHORT).show()
                                navController.backToMain()
                            }
                        } else {
                            val driveResult = backupViewModel.restoreFromDrive(context, result.user.email)
                            if (driveResult is com.darkxvenom.airbeats.utils.DriveResult.Success) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Cloud backup restored successfully", Toast.LENGTH_SHORT).show()
                                    context.stopService(android.content.Intent(context, com.darkxvenom.airbeats.playback.MusicService::class.java))
                                    context.filesDir.resolve(com.darkxvenom.airbeats.playback.MusicService.PERSISTENT_QUEUE_FILE).delete()
                                    context.startActivity(android.content.Intent(context, com.darkxvenom.airbeats.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                                    kotlin.system.exitProcess(0)
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Failed to restore backup", Toast.LENGTH_SHORT).show()
                                    navController.backToMain()
                                }
                            }
                        }
                    }
                }
                is AuthResult.Error -> {
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    isProcessing = false
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.login)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (uiState == LoginUiState.EMAIL_SIGNUP) "Create Account" else "Welcome Back",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            if (uiState == LoginUiState.EMAIL_SIGNUP) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                singleLine = true
            )

            Button(
                onClick = { processEmailAuth(uiState == LoginUiState.EMAIL_SIGNUP) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !isProcessing
            ) {
                Text(if (uiState == LoginUiState.EMAIL_SIGNUP) "Sign Up" else "Login")
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = {
                uiState = if (uiState == LoginUiState.EMAIL_LOGIN) LoginUiState.EMAIL_SIGNUP else LoginUiState.EMAIL_LOGIN
            }) {
                Text(if (uiState == LoginUiState.EMAIL_LOGIN) "Don't have an account? Sign up" else "Already have an account? Login")
            }

            Divider(modifier = Modifier.padding(vertical = 24.dp))

            OutlinedButton(
                onClick = { uiState = LoginUiState.GOOGLE_WEBVIEW },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Text("Login with Google")
            }

            TextButton(onClick = { navController.backToMain() }) {
                Text("Continue as Guest")
            }
        }
    }
}
