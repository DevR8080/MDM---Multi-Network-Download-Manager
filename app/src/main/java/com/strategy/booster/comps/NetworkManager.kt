package com.strategy.booster.comps

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkManager(private val ctx: Context) {
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _wifiNetwork = MutableStateFlow<Network?>(null)
    val wifiNetwork = _wifiNetwork.asStateFlow()

    private val _cellNetwork = MutableStateFlow<Network?>(null)
    val cellNetwork = _cellNetwork.asStateFlow()

    private val wifiRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private val cellRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _wifiNetwork.value = network
        }
        override fun onLost(network: Network) {
            if (_wifiNetwork.value == network) _wifiNetwork.value = null
        }
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _cellNetwork.value = network
        }
        override fun onLost(network: Network) {
            if (_cellNetwork.value == network) _cellNetwork.value = null
        }
    }

    fun activeIsCellular(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun start() {
        cm.requestNetwork(wifiRequest, wifiCallback)
        cm.requestNetwork(cellRequest, cellCallback)
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(wifiCallback) } catch (e: Exception) {}
        try { cm.unregisterNetworkCallback(cellCallback) } catch (e: Exception) {}
    }
}