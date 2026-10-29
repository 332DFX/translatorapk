package com.translatorapk.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class ConnectivityObserver(context: Context) {

    private val manager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val isOnline: Flow<Boolean> = callbackFlow {
        fun current(): Boolean {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(current())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(current())
            }
        }

        trySend(current())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }
        .map { it }
        .distinctUntilChanged()
}
