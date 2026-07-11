package com.darkxvenom.airbeats.ui.screens.onboarding

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.darkxvenom.airbeats.ui.component.NamePreferenceManager
import com.darkxvenom.airbeats.utils.DriveResult
import com.darkxvenom.airbeats.utils.GoogleAuthManager
import com.darkxvenom.airbeats.viewmodels.BackupRestoreViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(
    navController: NavController,
    backupRestoreViewModel: BackupRestoreViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val namePrefManager = remember { NamePreferenceManager(context) }
    
    // Store user data in case we need to retry after getting permissions
    var currentUserEmail by remember { mutableStateOf<String?>(null) }
    var currentUserName by remember { mutableStateOf<String?>(null) }

    val drivePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val email = currentUserEmail
            val name = currentUserName
            if (email != null && name != null) {
                // Retry backup/restore process
                coroutineScope.launch {
                    val restoredResult = backupRestoreViewModel.restoreFromDrive(context, email)
                    if (restoredResult is DriveResult.Success && restoredResult.data) {
                        withContext(Dispatchers.Main) {
                            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            context.startActivity(intent)
                        }
                    } else if (restoredResult is DriveResult.Error || (restoredResult is DriveResult.Success && !restoredResult.data)) {
                        namePrefManager.saveUserName(name)
                        backupRestoreViewModel.backupToDrive(context, email)
                        withContext(Dispatchers.Main) {
                            navController.navigate("home") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        }
                    }
                }
            }
        } else {
            Toast.makeText(context, "Google Drive permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    val onGoogleSignInClick: () -> Unit = {
        coroutineScope.launch {
            try {
                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(GoogleAuthManager.WEB_CLIENT_ID)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                
                if (credential is androidx.credentials.CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val email = googleIdTokenCredential.id
                    val name = googleIdTokenCredential.displayName ?: "Google User"
                    
                    currentUserEmail = email
                    currentUserName = name

                    val restoredResult = backupRestoreViewModel.restoreFromDrive(context, email)
                    
                    if (restoredResult is DriveResult.NeedsPermission) {
                        drivePermissionLauncher.launch(restoredResult.intent)
                    } else if (restoredResult is DriveResult.Success && restoredResult.data) {
                        withContext(Dispatchers.Main) {
                            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            context.startActivity(intent)
                        }
                    } else {
                        // Error or false (not restored) -> Do initial backup
                        namePrefManager.saveUserName(name)
                        backupRestoreViewModel.backupToDrive(context, email)
                        withContext(Dispatchers.Main) {
                            navController.navigate("home") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "stringAnim")
    val stringWave by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "wave"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE3F2FD),
                        Color(0xFFBBDEFB),
                        Color(0xFF90CAF9)
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val startY = height * 0.4f

            val path = Path()
            path.moveTo(width * 0.1f, startY)
            
            // Draw a wobbly string between two imaginary people
            val control1X = width * 0.3f
            val control1Y = startY + kotlin.math.sin(stringWave) * 100f
            val control2X = width * 0.7f
            val control2Y = startY + kotlin.math.sin(stringWave + 1f) * 100f
            val endX = width * 0.9f
            
            path.cubicTo(control1X, control1Y, control2X, control2Y, endX, startY)
            
            drawPath(
                path = path,
                color = Color(0xFF1976D2).copy(alpha = 0.8f),
                style = Stroke(width = 4.dp.toPx())
            )
            
            // Draw simple stick figures holding the string
            drawCircle(Color(0xFF333333), radius = 15f, center = Offset(width * 0.08f, startY - 30f))
            drawLine(Color(0xFF333333), Offset(width * 0.08f, startY - 15f), Offset(width * 0.08f, startY + 40f), strokeWidth = 5f)
            drawLine(Color(0xFF333333), Offset(width * 0.08f, startY), Offset(width * 0.1f, startY), strokeWidth = 5f)
            
            drawCircle(Color(0xFF333333), radius = 15f, center = Offset(width * 0.92f, startY - 30f))
            drawLine(Color(0xFF333333), Offset(width * 0.92f, startY - 15f), Offset(width * 0.92f, startY + 40f), strokeWidth = 5f)
            drawLine(Color(0xFF333333), Offset(width * 0.92f, startY), Offset(width * 0.9f, startY), strokeWidth = 5f)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = "Welcome to AirBeats",
                color = Color(0xFF111111),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                text = "Sync your music across devices seamlessly",
                color = Color(0xFF333333),
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Button(
                onClick = onGoogleSignInClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(24.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Person,
                    contentDescription = "Google",
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Continue with Google",
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { navController.navigate("guest_profile_setup") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1976D2),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Continue as Guest",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
