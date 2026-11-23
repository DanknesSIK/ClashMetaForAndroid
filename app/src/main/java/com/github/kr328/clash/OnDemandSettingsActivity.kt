package com.github.kr328.clash

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.store.ServiceStore

class OnDemandSettingsActivity : AppCompatActivity() {
    private val settings by lazy { OnDemandSettings(this) }
    private lateinit var enabled: SwitchCompat
    private lateinit var names: EditText
    private val location = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true) save()
        else message(DesignR.string.on_demand_location_needed)
    }
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) save()
        else message(DesignR.string.unable_to_start_vpn)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_on_demand)
        enabled = findViewById(R.id.on_demand_enabled)
        names = findViewById(R.id.on_demand_names)
        if (savedInstanceState == null) {
            enabled.isChecked = settings.enabled
            names.setText(settings.homeNetworks.sorted().joinToString("\n"))
        }
        findViewById<Button>(R.id.on_demand_save).setOnClickListener { save() }
    }

    private fun save() {
        val home = names.text.lines().filter { it.isNotBlank() }.toSet()
        if (!enabled.isChecked) {
            settings.save(false, home)
            stopService(Intent(this, OnDemandService::class.java))
            return finish()
        }
        if (home.isEmpty()) {
            names.error = getString(DesignR.string.on_demand_enter_name)
            names.requestFocus()
            return
        }
        if (!UiStore(this).enableVpn) return message(DesignR.string.on_demand_vpn_mode)
        if (ServiceStore(this).activeProfile == null) return message(DesignR.string.no_profile_selected)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            location.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }
        if (!canReadNetworkName()) {
            AlertDialog.Builder(this).setMessage(DesignR.string.on_demand_location_needed)
                .setPositiveButton(DesignR.string.settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }.setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        val request = VpnService.prepare(this)
        if (request != null) {
            consent.launch(request)
            return
        }
        // Save both fields together before the service reads the new policy.
        settings.save(true, home)
        OnDemandService.start(this)
        finish()
    }

    private fun message(id: Int) = Toast.makeText(this, id, Toast.LENGTH_LONG).show()
}
