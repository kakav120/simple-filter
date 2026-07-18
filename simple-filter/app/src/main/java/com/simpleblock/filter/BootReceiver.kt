package com.simpleblock.filter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val wasOn = context.getSharedPreferences("filter_prefs", Context.MODE_PRIVATE)
            .getBoolean("filter_enabled", false)
        if (wasOn) {
            val svcIntent = Intent(context, DnsFilterVpnService::class.java)
            context.startForegroundService(svcIntent)
        }
    }
}
