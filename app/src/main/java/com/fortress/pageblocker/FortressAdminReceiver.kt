package com.fortress.pageblocker

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

class FortressAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Fortress Uninstall & Screen-Lock Protection Activated", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val prefs = context.getSharedPreferences("fortress_prefs", Context.MODE_PRIVATE)
        val isLocked = System.currentTimeMillis() < prefs.getLong("lock_until_epoch", 0L) ||
            prefs.getLong("remaining_lock_ms", 0L) > 0L ||
            prefs.getBoolean("hard_lock_active", false)

        if (isLocked) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val comp = ComponentName(context, FortressAdminReceiver::class.java)
                if (dpm.isAdminActive(comp)) {
                    dpm.lockNow()
                }
            } catch (_: Exception) {}
        }
        return "CRITICAL WARNING: Disabling Fortress Blocker violates active protection locks."
    }
}
