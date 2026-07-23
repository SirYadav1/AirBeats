package com.darkxvenom.airbeats.ui.screens

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.darkxvenom.airbeats.ui.screens.onboarding.OnboardingScreen

@Composable
fun LoginScreen(navController: NavController) {
    OnboardingScreen(navController = navController)
}
