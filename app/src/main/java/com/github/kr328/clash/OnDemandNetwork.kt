package com.github.kr328.clash

internal enum class NetworkDecision { Home, Away, UnknownWifi, Waiting }

internal fun classifyNetwork(
    wifi: Boolean,
    cellular: Boolean,
    ssid: String?,
    homeNetworks: Set<String>
): NetworkDecision = when {
    wifi && ssid.isNullOrEmpty() -> NetworkDecision.UnknownWifi
    wifi && ssid?.removeSurrounding("\"") in homeNetworks -> NetworkDecision.Home
    wifi || cellular -> NetworkDecision.Away
    else -> NetworkDecision.Waiting
}
