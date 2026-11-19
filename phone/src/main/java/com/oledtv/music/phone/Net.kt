package com.oledtv.music.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "Mobile data only": asks Android for a cellular network and binds the whole app process to it, so every
 * connection (server, streaming, cover art, lyrics) uses mobile data even while Wi-Fi is connected.
 * Note: a server that is only reachable on the home Wi-Fi (a 192.168.x.x address) cannot be reached this way.
 */
object NetPolicy {
    var status by mutableStateOf("")
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun apply(context: Context, mobileOnly: Boolean) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!mobileOnly) {
            release(cm)
            status = ""
            return
        }
        if (callback != null) return // already active
        status = "Connecting via mobile data…"
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                status = "Using mobile data only"
            }

            override fun onUnavailable() {
                status = "No mobile data available"
            }

            override fun onLost(network: Network) {
                // Stay bound to the lost network: requests fail instead of silently falling back to Wi-Fi.
                status = "Mobile data lost - waiting for it to return"
            }
        }
        try {
            cm.requestNetwork(request, cb)
            callback = cb
        } catch (e: Exception) {
            status = "Could not switch to mobile data: ${e.message ?: "unknown error"}"
        }
    }

    private fun release(cm: ConnectivityManager) {
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        callback = null
        cm.bindProcessToNetwork(null)
    }
}
