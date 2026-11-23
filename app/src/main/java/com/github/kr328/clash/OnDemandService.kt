package com.github.kr328.clash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService

@RequiresApi(Build.VERSION_CODES.S)
class OnDemandService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity by lazy { requireNotNull(getSystemService(ConnectivityManager::class.java)) }
    private val notifications by lazy { requireNotNull(getSystemService(NotificationManager::class.java)) }
    private val settings by lazy { OnDemandSettings(this) }
    private var observed: Network? = null
    private var capabilities: NetworkCapabilities? = null
    private var decision: Decision? = null
    private var registered = false

    private enum class Decision(val message: Int) {
        Home(DesignR.string.on_demand_at_home),
        Away(DesignR.string.on_demand_away),
        UnknownWifi(DesignR.string.on_demand_unknown_wifi),
        Waiting(DesignR.string.on_demand_waiting)
    }

    private val callback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
        override fun onAvailable(network: Network) {
            observed = network
            update(null)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (network == observed) update(networkCapabilities)
        }

        override fun onLost(network: Network) {
            if (network == observed) {
                observed = null
                update(null)
            }
        }
    }

    private fun update(value: NetworkCapabilities?) {
        capabilities = value
        val next = when {
            value == null -> Decision.Waiting
            value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val raw = (value.transportInfo as? WifiInfo)?.ssid
                when {
                    raw == null || raw == WifiManager.UNKNOWN_SSID -> Decision.UnknownWifi
                    raw.removeSurrounding("\"") in settings.homeNetworks -> Decision.Home
                    else -> Decision.Away
                }
            }
            value.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Decision.Away
            else -> Decision.Waiting
        }
        if (next == decision) return
        decision = next
        handler.removeCallbacks(applyDecision)
        handler.removeCallbacks(verify)
        handler.postDelayed(applyDecision, 1500)
    }

    private val applyDecision = Runnable { reconcile(verifyLater = true) }
    private val verify = Runnable { reconcile(verifyLater = false) }

    private fun reconcile(verifyLater: Boolean) {
        if (!settings.enabled) return
        val current = decision ?: return
        var message = current.message
        val shouldConnect = current == Decision.Away
        if (current == Decision.Home || shouldConnect) {
            try {
                if (!UiStore(this).enableVpn) {
                    message = DesignR.string.on_demand_vpn_mode
                } else if (StatusClient(this).isRunning() != shouldConnect) {
                    if (shouldConnect && startClashService() != null) {
                        message = DesignR.string.on_demand_open_settings
                    } else {
                        if (!shouldConnect) stopClashService()
                        if (verifyLater) handler.postDelayed(verify, 4000)
                        else message = DesignR.string.on_demand_open_settings
                    }
                }
            } catch (_: RuntimeException) {
                message = DesignR.string.on_demand_open_settings
            }
        }
        notifications.notify(R.id.nf_on_demand, notification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(DesignR.string.on_demand), NotificationManager.IMPORTANCE_LOW)
        )
        if (!settings.enabled || !canReadNetworkName()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(R.id.nf_on_demand, notification(DesignR.string.on_demand_waiting),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            if (!registered) {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
                registered = true
            }
            running = true
            decision = null
            update(capabilities)
        } catch (_: RuntimeException) {
            notifications.notify(R.id.nf_on_demand_error, notification(DesignR.string.on_demand_open_settings))
            stopSelf()
            return START_NOT_STICKY
        }
        notifications.cancel(R.id.nf_on_demand_error)
        return START_STICKY
    }

    private fun notification(message: Int) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_toggle_on)
        .setContentTitle(getString(DesignR.string.on_demand))
        .setContentText(getString(message))
        .setStyle(NotificationCompat.BigTextStyle().bigText(getString(message)))
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, OnDemandSettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (registered) connectivity.unregisterNetworkCallback(callback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "network_on_demand"
        var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OnDemandService::class.java))
        }
    }
}
