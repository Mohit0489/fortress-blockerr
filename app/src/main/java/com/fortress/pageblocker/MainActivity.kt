package com.fortress.pageblocker

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("fortress_prefs", Context.MODE_PRIVATE)
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        scroll.addView(layout)

        val statusBanner = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 24)
        }

        fun getActiveRemainingMs(): Long {
            val lockUntil = prefs.getLong("lock_until_epoch", 0L)
            val savedRem = prefs.getLong("remaining_lock_ms", 0L)
            val wallRem = (lockUntil - System.currentTimeMillis()).coerceAtLeast(0L)
            return maxOf(wallRem, savedRem)
        }

        fun verifyUnlockPermission(onAuthorized: () -> Unit) {
            val remMs = getActiveRemainingMs()
            if (remMs > 0L) {
                val minsLeft = (remMs / 60000L) + 1L
                Toast.makeText(this, "LOCKED BY TIMER! $minsLeft mins remaining.", Toast.LENGTH_LONG).show()
                return
            }

            val savedPass = prefs.getString("master_password", "") ?: ""
            if (savedPass.isEmpty()) {
                onAuthorized()
                return
            }

            val passInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Enter Master Password"
            }
            AlertDialog.Builder(this)
                .setTitle("Password Required")
                .setView(passInput)
                .setPositiveButton("Unlock") { _, _ ->
                    if (passInput.text.toString() == savedPass) {
                        onAuthorized()
                    } else {
                        Toast.makeText(this, "Incorrect Password!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // TOP SETUP BUTTONS
        val btnSetupDns = Button(this).apply {
            text = "Step 1: Copy family.cloudflare-dns.com & Open DNS Settings"
            setOnClickListener {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("DNS", "family.cloudflare-dns.com"))
                Toast.makeText(
                    this@MainActivity,
                    "Copied family.cloudflare-dns.com! Paste into Private DNS hostname",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }

        val btnAdmin = Button(this).apply {
            text = "Step 2: Enable Device Admin (Uninstall + Screen Lock)"
            setOnClickListener {
                val comp = ComponentName(this@MainActivity, FortressAdminReceiver::class.java)
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Prevents uninstallation and enables instant hardware screen lock on violations."
                    )
                }
                startActivity(intent)
            }
        }

        val btnAcc = Button(this).apply {
            text = "Step 3: Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        // TWELVE PROTECTED SWITCHES
        fun createProtectedSwitch(label: String, key: String, defaultVal: Boolean): Switch {
            return Switch(this).apply {
                text = label
                isChecked = prefs.getBoolean(key, defaultVal)
                setOnClickListener {
                    val targetState = isChecked
                    if (!targetState) {
                        isChecked = true
                        verifyUnlockPermission {
                            isChecked = false
                            prefs.edit().putBoolean(key, false).apply()
                        }
                    } else {
                        prefs.edit().putBoolean(key, true).apply()
                    }
                }
            }
        }

        val swAdultShield = createProtectedSwitch(
            "1. Adult/URL/Bot Shield (Multi-Language Porn, TeraBox & Telegram Bots)",
            "adult_shield",
            true
        )
        val swVisualScanner = createProtectedSwitch(
            "2. Visual Scanner (Real-Time YCbCr Nudity Analysis in Chats/Media)",
            "visual_scanner",
            true
        )
        val swShortsReels = createProtectedSwitch(
            "3. Reels/Status/Telegram Shield (YT Shorts, IG/FB Reels, WA Updates, TG Search)",
            "block_shorts_reels",
            true
        )
        val swChrome = createProtectedSwitch(
            "4. SafeSearch/Chrome Gatekeeper (Block Unfiltered Engines & Incognito)",
            "block_chrome_settings",
            true
        )
        val swAllWebViews = createProtectedSwitch(
            "5. Block All In-App WebViews (Prevent Internal Browsers in Chat Apps)",
            "block_all_webviews",
            false
        )
        val swBlurRecents = createProtectedSwitch(
            "6. Recents Blur (Blackout Overview Previews & Rapid-Tap Bypasses)",
            "blur_recents",
            true
        )
        val swNightCurfew = createProtectedSwitch(
            "7. Night Curfew (11:00 PM - 6:00 AM Strict Sleep Mode)",
            "night_curfew",
            false
        )
        val swMasterShield = createProtectedSwitch(
            "8. Master System Lock (Settings, Admin, Date/Time, Dev Mode, Safe Mode, Knox)",
            "hard_lock_active",
            false
        )
        val swLockOnHit = createProtectedSwitch(
            "9. Lock Screen on Violation (Instant Hardware Screen Lock)",
            "lock_on_violation",
            true
        )
        val swMultiWindow = createProtectedSwitch(
            "10. Block Multi-Window/Pinning (Destroy Split-Screen & Pop-Up Views)",
            "block_multiwindow",
            true
        )
        val swDns = createProtectedSwitch(
            "11. Lock Private DNS/VPN (Protect family.cloudflare-dns.com)",
            "block_dns",
            true
        )
        val swSearch = createProtectedSwitch(
            "12. Block Settings Search (Prevent Searching Bypass Menus)",
            "block_settings_search",
            true
        )

        // WEBSITE BLOCKER INPUT & LIST
        val inputWebsite = EditText(this).apply {
            hint = "Website Domain to Block Everywhere (e.g., youtube.com, reddit.com)"
        }
        val websitesDisplay = TextView(this).apply {
            setPadding(0, 12, 0, 12)
        }

        // GRANULAR APP SELECTOR SPINNER & KEYWORD INPUT
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val appNames = mutableListOf("All Apps (ALL)")
        val appPackages = mutableListOf("ALL")
        for (app in installedApps) {
            appNames.add("${pm.getApplicationLabel(app)} (${app.packageName})")
            appPackages.add(app.packageName)
        }

        val appSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, appNames)
        }

        val inputKeyword = EditText(this).apply {
            hint = "Block App Sub-Page or Keyword (e.g., Settings)"
        }

        val rulesDisplay = TextView(this).apply {
            setPadding(0, 12, 0, 12)
        }

        fun refreshUI() {
            val rem = getActiveRemainingMs()
            val timerText = if (rem > 0L) {
                "TIMER: ${rem / 3600000L}h ${(rem % 3600000L) / 60000L}m left"
            } else {
                "Timer: Off"
            }
            val passSet = if ((prefs.getString("master_password", "") ?: "").isNotEmpty()) {
                "Password: SET"
            } else {
                "Password: Off"
            }
            val hits = prefs.getInt("blocked_count", 0)
            statusBanner.text = "$timerText | $passSet | Blocked Attempts: $hits"

            val sites = prefs.getStringSet("blocked_websites", emptySet()) ?: emptySet()
            websitesDisplay.text = "Websites Blocked Everywhere (Browsers + In-App WebViews):\n" +
                if (sites.isEmpty()) "• None" else sites.joinToString("\n") { "• $it" }

            val rules = prefs.getStringSet("targeted_rules", emptySet()) ?: emptySet()
            rulesDisplay.text = "Custom App + Keyword Rules:\n" +
                if (rules.isEmpty()) "• None" else rules.joinToString("\n") { "• $it" }
        }

        val btnAddWebsite = Button(this).apply {
            text = "Block Website Everywhere"
            setOnClickListener {
                val site = inputWebsite.text.toString().trim().lowercase()
                if (site.isNotEmpty()) {
                    val current = prefs.getStringSet("blocked_websites", emptySet())?.toMutableSet() ?: mutableSetOf()
                    current.add(site)
                    prefs.edit().putStringSet("blocked_websites", current).apply()
                    inputWebsite.text.clear()
                    refreshUI()
                }
            }
        }

        val btnAddRule = Button(this).apply {
            text = "Add Custom App + Keyword Rule"
            setOnClickListener {
                val kw = inputKeyword.text.toString().trim()
                val selectedPkg = appPackages[appSpinner.selectedItemPosition]
                if (kw.isNotEmpty()) {
                    val current = prefs.getStringSet("targeted_rules", emptySet())?.toMutableSet() ?: mutableSetOf()
                    current.add("$selectedPkg::$kw")
                    prefs.edit().putStringSet("targeted_rules", current).apply()
                    inputKeyword.text.clear()
                    refreshUI()
                }
            }
        }

        val btnClearRules = Button(this).apply {
            text = "Clear Custom Websites & Rules (Requires Timer Expired + Password)"
            setOnClickListener {
                verifyUnlockPermission {
                    prefs.edit().remove("targeted_rules").remove("blocked_websites").apply()
                    refreshUI()
                }
            }
        }

        // REBOOT-PROOF TIMER CONTROLS
        val timerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val inputDays = EditText(this).apply {
            hint = "Days"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val inputHours = EditText(this).apply {
            hint = "Hours"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val inputMins = EditText(this).apply {
            hint = "Mins"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timerRow.addView(inputDays)
        timerRow.addView(inputHours)
        timerRow.addView(inputMins)

        val btnStartTimer = Button(this).apply {
            text = "Start / Extend Unbreakable Timer Lock"
            setOnClickListener {
                val d = inputDays.text.toString().toLongOrNull() ?: 0L
                val h = inputHours.text.toString().toLongOrNull() ?: 0L
                val m = inputMins.text.toString().toLongOrNull() ?: 0L
                val durationMs = ((d * 24L + h) * 60L + m) * 60L * 1000L
                if (durationMs > 0L) {
                    val currentRem = getActiveRemainingMs()
                    if (durationMs > currentRem) {
                        val nowEpoch = System.currentTimeMillis()
                        val nowUptime = SystemClock.elapsedRealtime()
                        prefs.edit()
                            .putLong("lock_until_epoch", nowEpoch + durationMs)
                            .putLong("remaining_lock_ms", durationMs)
                            .putLong("last_uptime_check", nowUptime)
                            .putBoolean("hard_lock_active", true)
                            .apply()
                        swMasterShield.isChecked = true
                        inputDays.text.clear()
                        inputHours.text.clear()
                        inputMins.text.clear()
                        Toast.makeText(this@MainActivity, "Fortress Timer Locked!", Toast.LENGTH_SHORT).show()
                        refreshUI()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "New duration must be longer than current active timer!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // MASTER PASSWORD CONTROLS
        val inputNewPass = EditText(this).apply {
            hint = "Set or Change Master Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val btnSetPass = Button(this).apply {
            text = "Save Master Password"
            setOnClickListener {
                verifyUnlockPermission {
                    val p = inputNewPass.text.toString()
                    prefs.edit().putString("master_password", p).apply()
                    inputNewPass.text.clear()
                    Toast.makeText(this@MainActivity, "Master Password Updated", Toast.LENGTH_SHORT).show()
                    refreshUI()
                }
            }
        }

        // ADD ALL VIEWS IN EXACT DASHBOARD ORDER (Zero duplicate additions)
        layout.addView(statusBanner)
        layout.addView(btnSetupDns)
        layout.addView(btnAdmin)
        layout.addView(btnAcc)
        layout.addView(swAdultShield)
        layout.addView(swVisualScanner)
        layout.addView(swShortsReels)
        layout.addView(swChrome)
        layout.addView(swAllWebViews)
        layout.addView(swBlurRecents)
        layout.addView(swNightCurfew)
        layout.addView(swMasterShield)
        layout.addView(swLockOnHit)
        layout.addView(swMultiWindow)
        layout.addView(swDns)
        layout.addView(swSearch)
        layout.addView(inputWebsite)
        layout.addView(btnAddWebsite)
        layout.addView(websitesDisplay)
        layout.addView(appSpinner)
        layout.addView(inputKeyword)
        layout.addView(btnAddRule)
        layout.addView(rulesDisplay)
        layout.addView(btnClearRules)
        layout.addView(timerRow)
        layout.addView(btnStartTimer)
        layout.addView(inputNewPass)
        layout.addView(btnSetPass)

        refreshUI()
        setContentView(scroll)
    }
}
