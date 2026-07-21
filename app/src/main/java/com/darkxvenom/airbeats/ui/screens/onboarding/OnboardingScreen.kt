package com.darkxvenom.airbeats.ui.screens.onboarding

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.darkxvenom.airbeats.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.darkxvenom.airbeats.ui.component.AvatarPreferenceManager
import com.darkxvenom.airbeats.ui.component.AvatarSelection
import com.darkxvenom.airbeats.ui.component.NamePreferenceManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class SyncState {
    IDLE,
    CHECKING,
    RESTORING,
    RESTORED,
    CREATING_BACKUP,
    NEW_USER
}

@Composable
fun OnboardingScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val namePrefManager = remember { NamePreferenceManager(context) }
    val avatarPrefManager = remember { AvatarPreferenceManager(context) }
    val backupViewModel: com.darkxvenom.airbeats.viewmodels.BackupRestoreViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    
    var syncState by remember { mutableStateOf(SyncState.IDLE) }
    var currentUserName by remember { mutableStateOf("") }
    var currentUserEmail by remember { mutableStateOf("") }
    var featureStep by remember { mutableStateOf(0) }
    var isGoogleSignInOpen by remember { mutableStateOf(false) }

    fun generatedAvatarUrl(name: String, email: String): String {
        val seed = name.takeIf { it.isNotBlank() } ?: email
        val encodedSeed = URLEncoder.encode(seed, StandardCharsets.UTF_8.toString())
        return "https://api.dicebear.com/9.x/initials/svg?seed=$encodedSeed&backgroundType=gradientLinear"
    }

    fun displayNameFromEmail(email: String): String {
        return email
            .substringBefore("@")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
            .ifBlank { "Friend" }
    }

    fun saveGoogleProfile(name: String, email: String, photoUrl: String?) {
        coroutineScope.launch {
            if (!namePrefManager.canUseGoogleEmail(email)) {
                val lockedEmail = namePrefManager.previousGoogleEmail.first().ifBlank { "your previous email" }
                isGoogleSignInOpen = false
                syncState = SyncState.IDLE
                Toast.makeText(context, namePrefManager.lockedEmailMessage(lockedEmail), Toast.LENGTH_LONG).show()
                return@launch
            }

            currentUserName = name
            currentUserEmail = email
            syncState = SyncState.CHECKING
            namePrefManager.saveUserName(name)
            namePrefManager.rememberGoogleLoginEmail(email)
            if (!photoUrl.isNullOrBlank()) {
                avatarPrefManager.saveAvatarSelection(
                    AvatarSelection.Custom(uri = photoUrl, cloudUrl = photoUrl)
                )
            } else {
                avatarPrefManager.saveAvatarSelection(
                    AvatarSelection.DiceBear(generatedAvatarUrl(name, email))
                )
            }

            val backupClient = com.darkxvenom.airbeats.utils.CloudBackupClient()
            if (backupClient.checkBackupExists(email)) {
                syncState = SyncState.RESTORING
                when (backupViewModel.restoreFromDrive(context, email)) {
                    is com.darkxvenom.airbeats.utils.DriveResult.Success -> {
                        syncState = SyncState.RESTORED
                        delay(1500)
                        context.stopService(android.content.Intent(context, com.darkxvenom.airbeats.playback.MusicService::class.java))
                        context.startActivity(
                            android.content.Intent(context, com.darkxvenom.airbeats.MainActivity::class.java).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        )
                        Runtime.getRuntime().exit(0)
                    }
                    else -> {
                        Toast.makeText(context, "Cloud restore failed. Creating a fresh backup.", Toast.LENGTH_SHORT).show()
                        syncState = SyncState.CREATING_BACKUP
                        backupViewModel.backupToDrive(context, email, name)
                        syncState = SyncState.NEW_USER
                    }
                }
            } else {
                syncState = SyncState.CREATING_BACKUP
                backupViewModel.backupToDrive(context, email, name)
                syncState = SyncState.NEW_USER
            }
        }
    }

    fun continueToHome() {
        coroutineScope.launch {
            syncState = SyncState.IDLE
            navController.navigate("home") {
                popUpTo("onboarding") {
                    inclusive = true
                }
            }
        }
    }

    fun showSignInError(message: String) {
        isGoogleSignInOpen = false
        syncState = SyncState.IDLE
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun googleSignInErrorMessage(error: ApiException): String {
        return when (error.statusCode) {
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Google sign in was cancelled."
            GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS -> "Google sign in is already open."
            GoogleSignInStatusCodes.SIGN_IN_FAILED -> "Google sign in failed. Check the Android OAuth client package and SHA-1."
            else -> "Google sign in failed (${error.statusCode}): ${error.message.orEmpty()}"
        }
    }

    val googleSignInClient = remember {
        com.darkxvenom.airbeats.utils.GoogleAuthManager(context).getSignInClient()
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val email = account.email.orEmpty()
            if (email.isBlank()) {
                showSignInError("Google did not return an email for this account.")
                return@rememberLauncherForActivityResult
            }
            val name = account.displayName
                ?.takeIf { it.isNotBlank() }
                ?: account.givenName
                ?: displayNameFromEmail(email)

            isGoogleSignInOpen = false
            saveGoogleProfile(name, email, account.photoUrl?.toString())
        } catch (e: ApiException) {
            e.printStackTrace()
            showSignInError(googleSignInErrorMessage(e))
        } catch (e: Exception) {
            e.printStackTrace()
            showSignInError("Google sign in failed: ${e.message}")
        }
    }

    val onGoogleSignInClick: () -> Unit = {
        if (!isGoogleSignInOpen) {
            isGoogleSignInOpen = true
            googleSignInClient.revokeAccess().addOnCompleteListener {
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background Image
        Image(
            painter = painterResource(id = R.drawable.hero_bg),
            contentDescription = "Background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Blurred Background Masked
        Image(
            painter = painterResource(id = R.drawable.hero_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(radius = 24.dp)
                .drawWithContent {
                    val colors = listOf(Color.Transparent, Color.Black, Color.Black)
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = colors,
                            startY = this.size.height * 0.4f,
                            endY = this.size.height * 0.8f
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        )

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0x800D0D1A), Color(0xFF0D0D1A), Color(0xFF0D0D1A)),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )

        // Main Content Area
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            Crossfade(targetState = syncState, label = "OnboardingState") { state ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (state) {
                        SyncState.IDLE -> {
                            Text(
                                text = "Let get started",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = "Sign up or log in to see what's happening\nnear you",
                                color = Color.LightGray,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 48.dp)
                            )
                            Button(
                                onClick = onGoogleSignInClick,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA259FF)),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Icon(painter = painterResource(id = R.drawable.google), contentDescription = "Google", tint = Color.Unspecified, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.continue_with_google), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { navController.navigate("guest_profile_setup") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232336)),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Text(stringResource(R.string.continue_as_guest), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(modifier = Modifier.height(48.dp))
                            Text(stringResource(R.string.onboarding_terms), color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }

                        SyncState.CHECKING -> {
                            CircularProgressIndicator(color = Color(0xFFA259FF), modifier = Modifier.padding(bottom = 24.dp))
                            Text(
                                text = "Looking for your cloud backup...",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Text(stringResource(R.string.please_wait), color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 120.dp))
                        }

                        SyncState.RESTORING -> {
                            CircularProgressIndicator(color = Color(0xFFA259FF), modifier = Modifier.padding(bottom = 24.dp))
                            Text(
                                text = "Restoring your cloud backup...",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Text(stringResource(R.string.please_wait), color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 120.dp))
                        }

                        SyncState.RESTORED -> {
                            Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = "Success", tint = Color(0xFFA259FF), modifier = Modifier.size(64.dp).padding(bottom = 16.dp))
                            Text(
                                text = "Hi $currentUserName,",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = "Welcome again back to Airbeats",
                                color = Color.LightGray,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 120.dp)
                            )
                        }

                        SyncState.CREATING_BACKUP -> {
                            CircularProgressIndicator(color = Color(0xFFA259FF), modifier = Modifier.padding(bottom = 24.dp))
                            Text(
                                text = "Creating your cloud backup...",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Text(stringResource(R.string.uploading_first_backup), color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 120.dp))
                        }

                        SyncState.NEW_USER -> {
                            val featureTitles = listOf("Discover Music", "Create Playlists", "Offline Mode")
                            val featureDesc = listOf("Find millions of songs matching your mood.", "Curate your perfect listening experience.", "Download your favorites and listen anywhere.")
                            
                            Text(
                                text = if (featureStep == 0) "Hi $currentUserName, welcome!" else featureTitles[featureStep - 1],
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = if (featureStep == 0) "Looking like you are new to Airbeats" else featureDesc[featureStep - 1],
                                color = Color.LightGray,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 48.dp)
                            )
                            Button(
                                onClick = {
                                    if (featureStep < featureTitles.size) featureStep++ else continueToHome()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA259FF)),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Text(if (featureStep == featureTitles.size) "Get Started" else "Next", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(48.dp))
                        }
                    }
                }
            }
        }
    }
}
