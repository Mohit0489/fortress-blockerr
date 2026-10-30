package com.fortress.pageblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val prefs = context.getSharedPreferences("fortress_prefs", Context.MODE_PRIVATE)
            val remainingMs = prefs.getLong("remaining_lock_ms", 0L)
            if (remainingMs > 0L) {
                val nowEpoch = System.currentTimeMillis()
                val nowUptime = SystemClock.elapsedRealtime()
                val restoredTarget = nowEpoch + remainingMs

                prefs.edit()
                    .putLong("lock_until_epoch", restoredTarget)
                    .putLong("last_uptime_check", nowUptime)
                    .putBoolean("hard_lock_active", true)
                    .apply()
            }
        }
    }
}
