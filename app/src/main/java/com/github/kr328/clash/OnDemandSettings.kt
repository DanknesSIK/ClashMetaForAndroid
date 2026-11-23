package com.github.kr328.clash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

internal class OnDemandSettings(context: Context) {
    private val prefs = context.getSharedPreferences("network_on_demand", Context.MODE_PRIVATE)

    val enabled get() = prefs.getBoolean("enabled", false)
    val homeNetworks: Set<String> get() = prefs.getStringSet("home_networks", emptySet()).orEmpty()

    fun save(enabled: Boolean, names: Set<String>) {
        prefs.edit().putBoolean("enabled", enabled).putStringSet("home_networks", names).apply()
    }
}

internal fun Context.canReadNetworkName() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
    PackageManager.PERMISSION_GRANTED && getSystemService(LocationManager::class.java)?.isLocationEnabled == true
