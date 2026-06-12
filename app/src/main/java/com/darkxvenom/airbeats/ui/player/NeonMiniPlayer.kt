package com.darkxvenom.airbeats.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.darkxvenom.airbeats.LocalPlayerConnection
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.extensions.togglePlayPause
import com.darkxvenom.airbeats.playback.PlayerConnection
import com.darkxvenom.airbeats.ui.screens.NeonPurple
import com.darkxvenom.airbeats.ui.utils.highQualityThumbnail

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.darkxvenom.airbeats.ui.component.bottomSheetDraggable
import com.darkxvenom.airbeats.ui.component.BottomSheetState

@Composable
fun NeonMiniPlayer(
    modifier: Modifier = Modifier,
    state: BottomSheetState
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val position by playerConnection.currentPosition.collectAsState()
    val duration by playerConnection.duration.collectAsState()
    
    val isDarkTheme = MaterialTheme.colorScheme.background.red < 0.5f
    val bgColor = if (isDarkTheme) Color(0xFF1E1E24) else Color(0xFFF5F5F5)
    val textColor = if (isDarkTheme) Color.White else Color.Black

    if (mediaMetadata == null) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(com.darkxvenom.airbeats.constants.MiniPlayerHeight)
            .background(bgColor) // Use adaptive background
            .bottomSheetDraggable(state)
            .pointerInput(Unit) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> 
                        totalDrag += dragAmount
                    },
                    onDragEnd = {
                        if (totalDrag < -50f) {
                            playerConnection.player.seekToNext()
                        } else if (totalDrag > 50f) {
                            playerConnection.player.seekToPrevious()
                        }
                    }
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = mediaMetadata?.thumbnailUrl?.highQualityThumbnail(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mediaMetadata?.title ?: "Unknown",
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mediaMetadata?.artists?.joinToString { it.name } ?: "Unknown Artist",
                        color = if (isDarkTheme) Color.LightGray else Color.DarkGray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                IconButton(onClick = { playerConnection.player.togglePlayPause() }) {
                    Icon(
                        painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription = "Play/Pause",
                        tint = textColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                IconButton(onClick = { playerConnection.player.seekToNext() }) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = "Next",
                        tint = textColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            
            // Progress Bar
            val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.Gray.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }
        }
    }
}
