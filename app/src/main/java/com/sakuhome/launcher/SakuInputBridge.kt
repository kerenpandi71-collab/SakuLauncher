package com.sakuhome.launcher

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Optional privileged input path. If Shizuku is unavailable or permission is denied,
 * callers can keep using the Accessibility fallback.
 */
object SakuInputBridge {
    private const val TAG = "SakuInputBridge"
    private const val REQUEST_CODE = 7041
    @Volatile var shell: IShellService? = null
        private set
    @Volatile private var binding = false
    private var listenersAdded = false
    private var appContext: Context? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shell = IShellService.Stub.asInterface(service)
            binding = false
            Log.i(TAG, "Shizuku input service connected")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            binding = false
        }
    }

    @Synchronized
    fun ensureConnected(context: Context) {
        appContext = context.applicationContext
        if (shell?.asBinder()?.isBinderAlive == true || binding) return
        if (!listenersAdded) {
            listenersAdded = true
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) bindUserService()
            }
            Shizuku.addBinderReceivedListenerSticky { bindUserService() }
            Shizuku.addBinderDeadListener {
                shell = null
                binding = false
            }
        }
        try {
            if (!Shizuku.pingBinder()) return
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku is not ready", e)
        }
    }

    @Synchronized
    private fun bindUserService() {
        val ctx = appContext ?: return
        if (binding || shell?.asBinder()?.isBinderAlive == true) return
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            binding = true
            ShizukuBinder.bind(ComponentName(ctx, ShellUserService::class.java), connection, false, 1)
        } catch (e: Exception) {
            binding = false
            Log.w(TAG, "Could not bind Shizuku input service", e)
        }
    }
}