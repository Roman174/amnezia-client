package org.amnezia.vpn.util.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.os.Handler
import androidx.core.content.getSystemService
import kotlinx.coroutines.delay
import org.amnezia.vpn.util.Log

private const val TAG = "NetworkState"

class NetworkState(
    context: Context,
    private val onNetworkChange: () -> Unit,
) {
    private val tracker = PhysicalNetworkTracker<Network>()
    private val handler = Handler(context.mainLooper)
    private val connectivityManager = requireNotNull(context.getSystemService<ConnectivityManager>())
    private var isListenerBound = false
    private val networkRequest = NetworkRequest.Builder()
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_VPN)
        .build()
    private val networkCallback = object : NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (isListenerBound) {
                Log.d(TAG, "Physical network available: networkId=${network.networkHandle}")
                tracker.available(network)
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            if (!isListenerBound) {
                return
            }
            val usable = networkCapabilities.hasCapability(NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NET_CAPABILITY_NOT_VPN) &&
                !networkCapabilities.hasTransport(TRANSPORT_VPN)
            val priority = when {
                networkCapabilities.hasTransport(TRANSPORT_ETHERNET) -> 3
                networkCapabilities.hasTransport(TRANSPORT_WIFI) -> 2
                networkCapabilities.hasTransport(TRANSPORT_CELLULAR) -> 1
                else -> 0
            }
            val validated = networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)
            val validationPriority = if (validated) 4 else 0
            val transport = when (priority) {
                3 -> "ethernet"
                2 -> "wifi"
                1 -> "cellular"
                else -> "other"
            }
            Log.d(TAG, "Physical network capabilities: networkId=${network.networkHandle} " +
                "transport=$transport usable=$usable validated=$validated")
            if (tracker.capabilities(network = network, usable = usable, priority = priority + validationPriority)) {
                notifyNetworkChange("capabilities")
            }
        }

        override fun onBlockedStatusChanged(
            network: Network,
            blocked: Boolean,
        ) {
            if (isListenerBound) {
                Log.d(TAG, "Physical network blocked: networkId=${network.networkHandle} blocked=$blocked")
                if (tracker.blocked(network = network, blocked = blocked)) {
                    notifyNetworkChange("blocked")
                }
            }
        }

        override fun onLost(network: Network) {
            if (isListenerBound) {
                Log.d(TAG, "Physical network lost: networkId=${network.networkHandle}")
                if (tracker.lost(network)) {
                    notifyNetworkChange("lost")
                }
            }
        }
    }

    val isOnline: Boolean
        get() = tracker.selected != null
    val network: Network?
        get() = tracker.selected
    val networkId: Long?
        get() = tracker.selected?.networkHandle
    val epoch: Long
        get() = tracker.epoch

    suspend fun bindNetworkListener() {
        if (isListenerBound) {
            return
        }
        repeat(5) { attempt ->
            isListenerBound = true
            try {
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback, handler)
                return
            } catch (e: SecurityException) {
                isListenerBound = false
                // Android 11 bug: https://issuetracker.google.com/issues/175055271
                if (e.message?.startsWith("Package android does not belong to") != true || attempt == 4) {
                    throw e
                }
                delay(1_000)
            }
        }
    }

    fun unbindNetworkListener() {
        if (!isListenerBound) {
            return
        }
        Log.d(TAG, "Unbind network listener")
        isListenerBound = false
        connectivityManager.unregisterNetworkCallback(networkCallback)
        tracker.clear()
    }

    private fun notifyNetworkChange(reason: String) {
        Log.d(TAG, "Physical network selected: reason=$reason networkId=$networkId epoch=$epoch online=$isOnline")
        onNetworkChange()
    }
}
