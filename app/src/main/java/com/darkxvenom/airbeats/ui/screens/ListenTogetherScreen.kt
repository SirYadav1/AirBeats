package com.darkxvenom.airbeats.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.darkxvenom.airbeats.LocalPlayerAwareWindowInsets
import com.darkxvenom.airbeats.LocalPlayerConnection
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.ui.component.ScreenAdaptiveBackground
import com.darkxvenom.airbeats.ui.component.SettingsGlassCard
import com.darkxvenom.airbeats.ui.component.SettingsTopAppBar
import com.darkxvenom.airbeats.utils.LanTogetherClient
import com.darkxvenom.airbeats.utils.LanTogetherServer
import com.darkxvenom.airbeats.utils.ListenTogetherClient
import com.darkxvenom.airbeats.utils.ListenTogetherConnectionMode
import com.darkxvenom.airbeats.utils.ListenTogetherPlaybackState
import com.darkxvenom.airbeats.utils.ListenTogetherSession
import com.darkxvenom.airbeats.utils.ListenTogetherStore
import com.darkxvenom.airbeats.utils.ListenTogetherSync
import com.darkxvenom.airbeats.utils.joinByBullet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val playerConnection = LocalPlayerConnection.current
    val mediaMetadata by playerConnection?.mediaMetadata?.collectAsState(initial = null)
        ?: remember { mutableStateOf(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) }
    val currentPosition by playerConnection?.currentPosition?.collectAsState(initial = 0L)
        ?: remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    var selectedMode by remember { mutableStateOf(ListenTogetherConnectionMode.ONLINE) }
    var displayName by remember { mutableStateOf(ListenTogetherStore.defaultName()) }
    var joinCode by remember { mutableStateOf("") }
    var lanHostInput by remember { mutableStateOf("") }
    var lanPortInput by remember { mutableStateOf("8765") }
    var session by remember { mutableStateOf<ListenTogetherSession?>(null) }
    var isHost by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isScanningLan by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val syncedSession by ListenTogetherSync.session.collectAsState()
    val syncedIsHost by ListenTogetherSync.isHost.collectAsState()
    val currentConnectionMode by ListenTogetherSync.connectionMode.collectAsState()
    val syncMessage by ListenTogetherSync.message.collectAsState()

    var localIp by remember { mutableStateOf<String?>(null) }

    val sessionCreatedMessage = stringResource(R.string.session_created)
    val joinedSessionMessage = stringResource(R.string.joined_session)
    val leftSessionMessage = stringResource(R.string.left_session)
    val listenTogetherCodeLabel = stringResource(R.string.listen_together_code)

    LaunchedEffect(Unit) {
        localIp = LanTogetherServer.getLocalIpAddress(context)
        val savedSession = ListenTogetherStore.load(context) ?: return@LaunchedEffect
        displayName = savedSession.displayName
        isHost = savedSession.isHost
        if (savedSession.isLan) {
            selectedMode = ListenTogetherConnectionMode.LAN
            lanHostInput = savedSession.hostAddress ?: savedSession.code
        } else {
            selectedMode = ListenTogetherConnectionMode.ONLINE
            joinCode = savedSession.code
        }
    }

    LaunchedEffect(syncedSession, syncedIsHost) {
        syncedSession?.let {
            session = it
            isHost = syncedIsHost
        }
    }

    LaunchedEffect(currentConnectionMode) {
        if (session != null) {
            selectedMode = currentConnectionMode
        }
    }

    LaunchedEffect(syncMessage) {
        syncMessage?.let { message = it }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenAdaptiveBackground(
            artworkUrl = mediaMetadata?.thumbnailUrl
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                SettingsTopAppBar(
                    title = stringResource(R.string.listen_together),
                    navController = navController,
                    scrollBehavior = scrollBehavior
                )
            }
        ) { paddingValues ->
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Mode Selector: Online vs LAN / Wi-Fi
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = selectedMode == ListenTogetherConnectionMode.ONLINE,
                        onClick = { selectedMode = ListenTogetherConnectionMode.ONLINE },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.globe_search),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text(stringResource(R.string.together_online))
                    }

                    SegmentedButton(
                        selected = selectedMode == ListenTogetherConnectionMode.LAN,
                        onClick = {
                            selectedMode = ListenTogetherConnectionMode.LAN
                            if (localIp.isNullOrBlank()) {
                                localIp = LanTogetherServer.getLocalIpAddress(context)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_wifi),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text(stringResource(R.string.together_lan))
                    }
                }

                Text(
                    text = if (selectedMode == ListenTogetherConnectionMode.LAN) {
                        stringResource(R.string.together_lan_description)
                    } else {
                        stringResource(R.string.listen_together_description)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                // Active Session Card
                CurrentSessionCard(
                    session = session,
                    isHost = isHost,
                    isLan = currentConnectionMode == ListenTogetherConnectionMode.LAN,
                    songTitle = mediaMetadata?.title ?: session?.state?.title,
                    subtitle = mediaMetadata?.artists?.joinToString { it.name }
                        ?: session?.state?.artists?.joinToString(),
                )

                if (selectedMode == ListenTogetherConnectionMode.ONLINE) {
                    // ONLINE: Host Card
                    SettingsGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(18.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.create_session),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                            )

                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = { Text(stringResource(R.string.display_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    enabled = !isLoading && mediaMetadata != null,
                                    onClick = {
                                        val metadata = mediaMetadata ?: return@Button
                                        isLoading = true
                                        scope.launch {
                                            try {
                                                session = ListenTogetherClient.createSession(
                                                    displayName = displayName,
                                                    state = ListenTogetherPlaybackState(
                                                        songId = metadata.id,
                                                        title = metadata.title,
                                                        artists = metadata.artists.map { it.name },
                                                        thumbnailUrl = metadata.thumbnailUrl,
                                                        positionMs = currentPosition,
                                                        isPlaying = isPlaying,
                                                    )
                                                )
                                                isHost = true
                                                session?.let {
                                                    ListenTogetherSync.adoptSession(
                                                        context = context,
                                                        session = it,
                                                        displayName = displayName,
                                                        isHost = true,
                                                        isLan = false
                                                    )
                                                }
                                                message = sessionCreatedMessage
                                            } catch (e: Exception) {
                                                message = e.message
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.create_session))
                                }

                                OutlinedButton(
                                    enabled = session?.joinUrl?.isNotBlank() == true,
                                    onClick = {
                                        val shareText = session?.joinUrl?.takeIf { it.isNotBlank() } ?: session?.code.orEmpty()
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                                },
                                                null
                                            )
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.share))
                                }
                            }
                        }
                    }

                    // ONLINE: Join Card
                    SettingsGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(18.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.join_session),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                            )

                            OutlinedTextField(
                                value = joinCode,
                                onValueChange = { joinCode = it.uppercase() },
                                label = { Text(stringResource(R.string.session_code)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                enabled = !isLoading && joinCode.isNotBlank(),
                                onClick = {
                                    isLoading = true
                                    scope.launch {
                                        try {
                                            session = ListenTogetherClient.joinSession(joinCode, displayName)
                                            isHost = false
                                            session?.let {
                                                ListenTogetherSync.adoptSession(
                                                    context = context,
                                                    session = it,
                                                    displayName = displayName,
                                                    isHost = false,
                                                    isLan = false
                                                )
                                            }
                                            message = joinedSessionMessage
                                        } catch (e: Exception) {
                                            message = e.message
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.join_session))
                            }
                        }
                    }
                } else {
                    // LAN / Wi-Fi: Host Card
                    SettingsGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(18.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.together_host_lan),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                )

                                localIp?.let { ip ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = ip,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = { Text(stringResource(R.string.display_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = lanPortInput,
                                onValueChange = { lanPortInput = it.filter { ch -> ch.isDigit() }.take(5) },
                                label = { Text("Port (default 8765)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    enabled = !isLoading && mediaMetadata != null,
                                    onClick = {
                                        val port = lanPortInput.toIntOrNull() ?: 8765
                                        isLoading = true
                                        ListenTogetherSync.setDisplayName(displayName)
                                        ListenTogetherSync.startLanHost(port)
                                        isLoading = false
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.together_host_lan))
                                }

                                OutlinedButton(
                                    enabled = session?.joinUrl?.isNotBlank() == true,
                                    onClick = {
                                        val shareText = "http://${session?.code}"
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                                },
                                                null
                                            )
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.share))
                                }
                            }
                        }
                    }

                    // LAN / Wi-Fi: Join Card
                    SettingsGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(18.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.together_join_lan),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                            )

                            OutlinedTextField(
                                value = lanHostInput,
                                onValueChange = { lanHostInput = it },
                                label = { Text(stringResource(R.string.together_host_ip)) },
                                placeholder = { Text(stringResource(R.string.together_host_ip_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    enabled = !isScanningLan,
                                    onClick = {
                                        isScanningLan = true
                                        scope.launch {
                                            try {
                                                val ip = localIp ?: LanTogetherServer.getLocalIpAddress(context)
                                                if (ip.isNullOrBlank()) {
                                                    message = "Connect to Wi-Fi first"
                                                    return@launch
                                                }
                                                message = context.getString(R.string.together_searching_lan)
                                                val found = LanTogetherClient.scanLocalNetwork(ip)
                                                if (found.isNotEmpty()) {
                                                    lanHostInput = found.first()
                                                    message = context.getString(R.string.together_found_host, found.first())
                                                } else {
                                                    message = context.getString(R.string.together_no_hosts_found)
                                                }
                                            } catch (e: Exception) {
                                                message = e.message
                                            } finally {
                                                isScanningLan = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isScanningLan) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text(stringResource(R.string.together_scan_lan))
                                    }
                                }

                                Button(
                                    enabled = !isLoading && lanHostInput.isNotBlank(),
                                    onClick = {
                                        isLoading = true
                                        ListenTogetherSync.setDisplayName(displayName)
                                        ListenTogetherSync.joinLanSession(lanHostInput)
                                        isLoading = false
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.together_join_lan))
                                }
                            }
                        }
                    }
                }

                // Active Session Controls
                session?.let { activeSession ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(activeSession.code))
                                message = context.getString(R.string.session_code_copied)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.copy_session_code))
                        }
                        OutlinedButton(
                            onClick = {
                                ListenTogetherSync.leaveSession()
                                session = null
                                isHost = false
                                message = leftSessionMessage
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.leave_session))
                        }
                    }

                    if (isLan) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://${activeSession.code}"))
                                    context.startActivity(browserIntent)
                                }.onFailure {
                                    message = "Could not open browser: ${it.message}"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Web Player (http://${activeSession.code})")
                        }
                    }

                    ParticipantsSection(activeSession)
                }

                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ParticipantsSection(session: ListenTogetherSession) {
    SettingsGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = stringResource(R.string.session_users),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
            )
            session.participantList.forEach { participant ->
                Text(
                    text = if (participant.isHost) {
                        stringResource(R.string.created_by_name, participant.name)
                    } else {
                        participant.name
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CurrentSessionCard(
    session: ListenTogetherSession?,
    isHost: Boolean,
    isLan: Boolean,
    songTitle: String?,
    subtitle: String?,
) {
    SettingsGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (session == null) {
                        stringResource(R.string.no_active_session)
                    } else if (isHost) {
                        stringResource(R.string.hosting_session, session.code)
                    } else {
                        stringResource(R.string.joined_session_with_code, session.code)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (session != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isLan) "LAN / Wi-Fi" else "Online",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Text(
                text = songTitle ?: stringResource(R.string.play_song_first),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = joinByBullet(it, session?.participants?.let { count -> "$count listeners" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            session?.joinUrl?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isLan && session != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Web Browser: http://${session.code}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
