package com.simpleblock.filter

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnService()
            } else {
                statusText.text = "Permission denied — filter not started."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val toggleButton = findViewById<Button>(R.id.toggleButton)

        updateUi()

        toggleButton.setOnClickListener {
            if (DnsFilterVpnService.isRunning) {
                stopVpnService()
            } else {
                requestVpnPermissionAndStart()
            }
            toggleButton.postDelayed({ updateUi() }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun requestVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, DnsFilterVpnService::class.java)
        startForegroundService(intent)
        getSharedPreferences("filter_prefs", MODE_PRIVATE).edit()
            .putBoolean("filter_enabled", true).apply()
        statusText.postDelayed({ updateUi() }, 500)
    }

    private fun stopVpnService() {
        val intent = Intent(this, DnsFilterVpnService::class.java).apply {
            action = DnsFilterVpnService.ACTION_STOP
        }
        startService(intent)
        getSharedPreferences("filter_prefs", MODE_PRIVATE).edit()
            .putBoolean("filter_enabled", false).apply()
        statusText.postDelayed({ updateUi() }, 500)
    }

    private fun updateUi() {
        val toggleButton = findViewById<Button>(R.id.toggleButton)
        if (DnsFilterVpnService.isRunning) {
            statusText.text = "Filter is ON"
            toggleButton.text = "Turn Off"
        } else {
            statusText.text = "Filter is OFF"
            toggleButton.text = "Turn On"
        }
    }
}
