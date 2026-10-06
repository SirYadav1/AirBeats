package com.darkxvenom.airbeats.ui.screens.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.darkxvenom.airbeats.ui.component.NamePreferenceManager
import com.darkxvenom.airbeats.ui.component.AvatarSelector
import com.darkxvenom.airbeats.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.darkxvenom.airbeats.utils.AutoBackupManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestProfileSetupScreen(navController: NavController) {
    val context = LocalContext.current
    val namePrefManager = remember { NamePreferenceManager(context) }
    var name by remember { mutableStateOf("") }
    var showRestoreOptionsDialog by remember { mutableStateOf(false) }
    var isRestoringBackup by remember { mutableStateOf(false) }
    var restoringStatusText by remember { mutableStateOf("Restoring backup...") }
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val restoreStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isRestoringBackup = true
            restoringStatusText = "Restoring from storage..."
            coroutineScope.launch {
                val restored = withContext(Dispatchers.IO) {
                    AutoBackupManager.restoreFromUri(context, uri, shouldRestart = true)
                }
                if (!restored) {
                    isRestoringBackup = false
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.restore_failed),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF0F0F14) else Color(0xFFF6F7FB)
    val cardBg = if (isDark) Color(0xFF181822) else Color(0xFFFFFFFF)
    val textColor = if (isDark) Color(0xFFEEEEF2) else Color(0xFF111118)
    val subTextColor = if (isDark) Color(0xFFA0A0B2) else Color(0xFF6B6B80)
    val inputBg = if (isDark) Color(0xFF22222E) else Color(0xFFEEEEF4)
    val primaryColor = MaterialTheme.colorScheme.primary

    val trimmedName = name.trim()
    val isNameValid = trimmedName.isNotEmpty() && trimmedName.length <= 16

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.weight(1f, fill = false))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp)),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                shape = RoundedCornerShape(32.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Logo Badge
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        primaryColor.copy(alpha = 0.25f),
                                        primaryColor.copy(alpha = 0.08f)
                                    )
                                )
                            )
                            .border(1.5.dp, primaryColor.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.airbeats_monochrome),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.set_up_profile),
                        color = textColor,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Customize your avatar and choose a name to get started with AirBeats.",
                        color = subTextColor,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Avatar Selector
                    AvatarSelector()

                    Spacer(modifier = Modifier.height(20.dp))

                    // Name Input Field
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            if (it.length <= 16) {
                                name = it
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.what_should_we_call_you),
                                color = if (name.isNotEmpty()) primaryColor else subTextColor,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        placeholder = {
                            Text(
                                text = "Enter your name...",
                                color = subTextColor.copy(alpha = 0.6f)
                            )
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            focusedContainerColor = inputBg,
                            unfocusedContainerColor = inputBg,
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = primaryColor.copy(alpha = 0.4f),
                            cursorColor = primaryColor
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (isNameValid) {
                                    keyboardController?.hide()
                                    coroutineScope.launch {
                                        namePrefManager.saveUserName(trimmedName)
                                        navController.navigate("home") {
                                            popUpTo("onboarding") { inclusive = true }
                                        }
                                    }
                                }
                            }
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "${name.length} / 16",
                            fontSize = 12.sp,
                            color = subTextColor
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Continue Button
                    Button(
                        onClick = {
                            if (isNameValid) {
                                keyboardController?.hide()
                                coroutineScope.launch {
                                    namePrefManager.saveUserName(trimmedName)
                                    navController.navigate("home") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                }
                            }
                        },
                        enabled = isNameValid && !isRestoringBackup,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = Color.White,
                            disabledContainerColor = primaryColor.copy(alpha = 0.35f),
                            disabledContentColor = Color.White.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 1.dp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(
                            text = "Continue",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    TextButton(
                        onClick = {
                            showRestoreOptionsDialog = true
                        },
                        enabled = !isRestoringBackup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isRestoringBackup) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = primaryColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = restoringStatusText,
                                color = primaryColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.restore),
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Restore Existing Backup",
                                color = primaryColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f, fill = false))
        }

        if (showRestoreOptionsDialog) {
            AlertDialog(
                onDismissRequest = { showRestoreOptionsDialog = false },
                title = null,
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header badge
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(primaryColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.restore),
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = stringResource(R.string.backup_restore),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            ),
                            color = textColor,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Choose how you want to restore your data and settings.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            color = subTextColor,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Option 1: Restore from Documents / AirBeats
                        Surface(
                            onClick = {
                                showRestoreOptionsDialog = false
                                isRestoringBackup = true
                                restoringStatusText = "Restoring from Documents/AirBeats..."
                                coroutineScope.launch {
                                    val restored = withContext(Dispatchers.IO) {
                                        AutoBackupManager.restoreFromStorageBackup(context, shouldRestart = true)
                                    }
                                    if (!restored) {
                                        isRestoringBackup = false
                                        android.widget.Toast.makeText(
                                            context,
                                            "No backup found in Documents/AirBeats. Please choose 'Pick a Backup File' instead.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = inputBg,
                            border = BorderStroke(1.dp, if (isDark) Color(0xFF2C2C3A) else Color(0xFFE2E4EE)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(primaryColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.save_to_storage),
                                        contentDescription = null,
                                        tint = primaryColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Restore from Documents / AirBeats",
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp
                                        ),
                                        color = textColor
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Restore latest backup saved in Documents/AirBeats",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 12.sp
                                        ),
                                        color = subTextColor
                                    )
                                }

                                Icon(
                                    painter = painterResource(R.drawable.chevron_right),
                                    contentDescription = null,
                                    tint = subTextColor.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Option 2: Pick a Backup File
                        Surface(
                            onClick = {
                                showRestoreOptionsDialog = false
                                restoreStorageLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = inputBg,
                            border = BorderStroke(1.dp, if (isDark) Color(0xFF2C2C3A) else Color(0xFFE2E4EE)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(primaryColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.folder),
                                        contentDescription = null,
                                        tint = primaryColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Pick a Backup File",
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp
                                        ),
                                        color = textColor
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Choose a .backup or .zip file from device storage",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 12.sp
                                        ),
                                        color = subTextColor
                                    )
                                }

                                Icon(
                                    painter = painterResource(R.drawable.chevron_right),
                                    contentDescription = null,
                                    tint = subTextColor.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = { showRestoreOptionsDialog = false },
                        modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            color = subTextColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                containerColor = cardBg,
                shape = RoundedCornerShape(28.dp)
            )
        }
    }
}
