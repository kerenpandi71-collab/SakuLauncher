package com.sakuhome.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class SakuBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("saku_dock_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("start_on_boot", true) || !prefs.getBoolean("overlay_enabled", false) || !Settings.canDrawOverlays(context)) return
        val serviceIntent = Intent(context, FloatingDockService::class.java)
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent) else context.startService(serviceIntent) } catch (_: Exception) { }
    }
}