package com.darkxvenom.airbeats.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

fun isInternetAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return false
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val networkCapabilities =
        connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

    val hasInternetCapability =
        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val hasValidTransport =
        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    return hasInternetCapability && hasValidTransport
}
