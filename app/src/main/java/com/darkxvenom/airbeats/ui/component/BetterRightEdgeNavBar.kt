package com.darkxvenom.airbeats.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.darkxvenom.airbeats.R

// Signature lime-green accent for Better nav bar
private val BetterNavLime = Color(0xFFC6F24D)

/**
 * Vertical pill-shaped navigation rail/bar floating on the right edge of the screen,
 * supporting Home, Search, Explore, and Library with dynamic active state highlighting.
 */
@Composable
fun BetterRightEdgeNavBar(
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    val navItems = listOf(
        Triple(0, R.drawable.home_filled, R.drawable.home_outlined),
        Triple(1, R.drawable.search, R.drawable.search),
        Triple(2, R.drawable.explore_filled, R.drawable.explore_outlined),
        Triple(3, R.drawable.library_music_filled, R.drawable.library_music_outlined)
    )

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = Color(0xFF16161A).copy(alpha = 0.94f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            navItems.forEach { (index, activeIcon, inactiveIcon) ->
                val isSelected = selectedIndex == index
                val iconRes = if (isSelected) activeIcon else inactiveIcon
                val iconTint = if (isSelected) Color.Black else Color.White.copy(alpha = 0.75f)

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(BetterNavLime)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onItemSelected(index)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onItemSelected(index)
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
