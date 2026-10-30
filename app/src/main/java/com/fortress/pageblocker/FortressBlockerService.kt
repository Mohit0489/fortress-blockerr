package com.fortress.pageblocker

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Calendar
import java.util.concurrent.Executors

class FortressBlockerService : AccessibilityService() {

    private var lastActionTime = 0L
    private var lastViolationTime = 0L
    private var lastTreeScanTime = 0L
    private var lastScreenshotTime = 0L
    private var consecutiveHits = 0

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var blackoutOverlay: FrameLayout? = null
    private var isOverlayVisible = false

    // 1. Multi-Script Adult Sites, Explicit Terms, Hinglish, Hindi Devanagari, Foreign Scripts, File Hosts & Telegram Bots
    private val builtInAdultAndUrlKeywords = listOf(
        // Global Adult Sites
        "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "spankbang",
        "eporner", "hqporner", "brazzers", "onlyfans", "chaturbate", "stripchat",
        // Explicit Terms & TLDs
        "porn", "xxx", "nsfw", "nude", "naked", "erotic", "hentai", "rule34", "camgirl",
        ".xxx", ".porn", ".sex", ".adult", ".cam", "incognito", "secret mode", "tor browser",
        // Hinglish Keywords
        "chudai", "chodna", "bhabhi sex", "desi mms", "desi sex", "suhagraat",
        "nangi", "nanga", "randi", "lund", "chut", "boobs", "gaand", "palang tod",
        "ullu app", "altbalaji", "antarvasna", "mastram", "kamasutra",
        // Hindi Devanagari
        "पोर्न", "सेक्स", "अश्लील", "चुदाई", "नग्न", "नंगी", "नंगा", "कामुक",
        "सुहागरात", "भाभी सेक्स", "देसी एमएमएस", "रंडी", "चूत", "लंड", "अंतरवासना",
        // Foreign Scripts (Arabic/Urdu, Russian, Japanese)
        "سكس", "إباحية", "فحش", "порно", "секс", "エロ", "無修正",
        // Chat File Hosts & Shorteners
        "terabox.com", "teraboxapp", "1024tera", "mdisk.me", "diskwala", "mega.nz", "bit.ly", "tinyurl",
        // Telegram NSFW Patterns
        "t.me/+", "t.me/joinchat", "nsfw_bot", "porn_bot", "strip_bot", "nude_bot", "18+_bot"
    )

    // 2. Unfiltered Search Engines & Video Portals
    private val blockedSearchEngines = listOf(
        "yandex.", "baidu.com", "search.yahoo.", "search.brave.com",
        "startpage.com", "qwant.com", "swisscows.", "mojeek.com",
        "dogpile.com", "metacrawler.com", "searx", "duckduckgo.com/?q=!g",
        "vk.com/video", "dailymotion.com", "ok.ru/video", "motherless.com"
    )

    // 3. App Store Anti-Bypass Keywords
    private val blockedStoreKeywords = listOf(
        "vpn", "proxy", "tor browser", "private browser", "incognito browser",
        "opera mini", "uc browser", "aloha browser", "puffin", "1.1.1.1", "psiphon"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        initBlackoutOverlay()
    }

    private fun initBlackoutOverlay() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            blackoutOverlay = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = true
                val label = TextView(this@FortressBlockerService).apply {
                    text = "FORTRESS SHIELD ACTIVE\nAccess Restricted"
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER
                }
                addView(
                    label,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
            }
        } catch (_: Exception) {}
    }

    private fun showInstantBlackout(durationMs: Long = 950L) {
        mainHandler.post {
            try {
                if (!isOverlayVisible && blackoutOverlay != null && windowManager != null) {
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.OPAQUE
                    )
                    windowManager?.addView(blackoutOverlay, params)
                    isOverlayVisible = true
                }
                mainHandler.removeCallbacksAndMessages("HIDE_OVERLAY")
                mainHandler.postAtTime(
                    { hideInstantBlackout() },
                    "HIDE_OVERLAY",
                    SystemClock.uptimeMillis() + durationMs
                )
            } catch (_: Exception) {}
        }
    }

    private fun hideInstantBlackout() {
        mainHandler.post {
            try {
                if (isOverlayVisible && blackoutOverlay != null && windowManager != null) {
                    windowManager?.removeViewImmediate(blackoutOverlay)
                    isOverlayVisible = false
                }
            } catch (_: Exception) {
                isOverlayVisible = false
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val prefs = getSharedPreferences("fortress_prefs", Context.MODE_PRIVATE)
        val nowEpoch = System.currentTimeMillis()
        val nowUptime = SystemClock.elapsedRealtime()
        val nowMillis = SystemClock.uptimeMillis()

        // Monotonic Uptime & Reboot-Proof Timer Reconciliation
        var lockUntil = prefs.getLong("lock_until_epoch", 0L)
        val remainingMs = prefs.getLong("remaining_lock_ms", 0L)
        val lastUptime = prefs.getLong("last_uptime_check", 0L)

        if (remainingMs > 0L) {
            if (lastUptime > 0L && nowUptime >= lastUptime) {
                val delta = nowUptime - lastUptime
                val newRem = (remainingMs - delta).coerceAtLeast(0L)
                lockUntil = if (newRem > 0L) nowEpoch + newRem else 0L
                prefs.edit()
                    .putLong("remaining_lock_ms", newRem)
                    .putLong("lock_until_epoch", lockUntil)
                    .putLong("last_uptime_check", nowUptime)
                    .apply()
            } else {
                lockUntil = nowEpoch + remainingMs
                prefs.edit()
                    .putLong("lock_until_epoch", lockUntil)
                    .putLong("last_uptime_check", nowUptime)
                    .apply()
            }
        }

        val isProtectionActive = nowEpoch < lockUntil || remainingMs > 0L || prefs.getBoolean("hard_lock_active", false)

        // 1. ZERO-TOLERANCE COOLDOWN (650ms rapid-tap race condition blocker)
        if (nowUptime - lastViolationTime < 650) {
            showInstantBlackout(950L)
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // 2. INSTANT INPUT & CLIPBOARD INTERCEPTION (0ms delay on text change or click)
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            val eventText = event.text?.joinToString(" ") ?: ""
            val blockedSites = prefs.getStringSet("blocked_websites", emptySet())?.toList() ?: emptyList()
            if ((prefs.getBoolean("adult_shield", true) &&
                    (containsAny(eventText, builtInAdultAndUrlKeywords) || containsAny(eventText, blockedSearchEngines))) ||
                containsAny(eventText, blockedSites)
            ) {
                clearEditText(event.source)
                wipeClipboard()
                executePenalty(prefs, forceHome = true)
                return
            }
            inspectAndSanitizeClipboard(prefs, blockedSites)
        }

        // 80ms Debounce for heavy tree traversal to keep Exynos 1330 smooth and battery-efficient
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            if (nowMillis - lastTreeScanTime < 80) return
        }
        lastTreeScanTime = nowMillis

        // 3. MULTI-WINDOW / SPLIT-SCREEN / POP-UP / PINNED WINDOW DESTRUCTION
        if (isProtectionActive && prefs.getBoolean("block_multiwindow", true)) {
            val appWindowsCount = windows.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            if (appWindowsCount > 1) {
                executePenalty(prefs, forceHome = true)
                return
            }
        }

        val rootNode = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString() ?: rootNode.packageName?.toString() ?: return
        val className = event?.className?.toString() ?: ""
        if (pkg == packageName) return

        // 4. RECENTS OVERVIEW SHIELD
        if (prefs.getBoolean("blur_recents", true)) {
            if ((pkg == "com.sec.android.app.launcher" || pkg == "com.android.systemui") &&
                (className.contains("Recents", ignoreCase = true) ||
                    className.contains("Overview", ignoreCase = true) ||
                    scanTree(rootNode, listOf("Close all", "Clear all", "Split screen", "Open in pop-up view", "Keep open")))
            ) {
                val rules = prefs.getStringSet("targeted_rules", emptySet()) ?: emptySet()
                val recentsBlockedWords = mutableListOf("Settings", "Incognito", "Secret mode", "Porn", "Shorts", "Reels")
                for (r in rules) {
                    val parts = r.split("::", limit = 2)
                    if (parts.size == 2) recentsBlockedWords.add(parts[1])
                }
                if (nowUptime - lastViolationTime < 5000 || scanTree(rootNode, recentsBlockedWords)) {
                    executePenalty(prefs, forceHome = true)
                    return
                }
            }
        }

        // 5. NIGHT CURFEW MODE (11:00 PM [23] to 6:00 AM [6])
        if (prefs.getBoolean("night_curfew", false)) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour >= 23 || hour < 6) {
                val allowedNightPackages = listOf(
                    "com.sec.android.app.launcher",
                    "com.android.systemui",
                    "com.samsung.android.incallui",
                    "com.samsung.android.dialer",
                    "com.sec.android.app.clockpackage",
                    "com.sec.android.app.popupcalculator"
                )
                if (!allowedNightPackages.contains(pkg)) {
                    executePenalty(prefs, forceHome = true)
                    return
                }
            }
        }

        // 6. SYSTEMUI, KNOX, KEYBOARD & QUICK TILES ANTI-BYPASS GUARD
        if (isProtectionActive) {
            if (pkg == "com.android.systemui") {
                val systemUiBlocks = mutableListOf(
                    "Safe mode", "Emergency mode", "Allow USB debugging",
                    "Allow wireless debugging", "Allow access to phone data"
                )
                if (prefs.getBoolean("block_dns", true)) {
                    systemUiBlocks.addAll(listOf("VPN", "Private DNS", "Hotspot", "Mobile data", "Airplane mode"))
                }
                if (scanTree(rootNode, systemUiBlocks)) {
                    executePenalty(prefs, forceHome = true)
                    return
                }
            }
            if (pkg.contains("securefolder", ignoreCase = true) ||
                pkg.contains("knox", ignoreCase = true) ||
                pkg == "com.samsung.android.goodlock" ||
                pkg == "com.samsung.android.app.cocktailbarservice"
            ) {
                executePenalty(prefs, forceHome = true)
                return
            }
            // Protect against Samsung Keyboard (honeyboard) settings/dictionary tampering
            if (pkg == "com.samsung.android.honeyboard" &&
                scanTree(rootNode, listOf("Samsung Keyboard settings", "Predictive text", "Reset to default settings", "Clear personalized data"))
            ) {
                executePenalty(prefs, forceHome = false)
                return
            }
        }

        // 7. SYSTEM SETTINGS, ADVANCED SETTINGS, RESET & DNS SHIELD
        if (isProtectionActive) {
            if (pkg == "com.android.settings" || pkg.contains("settings") || pkg == "com.samsung.android.lool") {
                val protectedSettingsPages = mutableListOf(
                    "Fortress Blocker", "Fortress Page Blocker", "Fortress Uninstall Lock",
                    "Device admin apps", "Accessibility", "Installed apps", "Date and time",
                    "Developer options", "USB debugging", "Wireless debugging",
                    "Maintenance mode", "Pin windows", "App info", "Force stop", "Clear data",
                    "Appear on top", "Battery", "Unrestricted", "Special access",
                    "Reset", "Factory data reset", "Safe mode", "Secure Folder", "Dual Messenger"
                )
                if (prefs.getBoolean("block_dns", true)) {
                    protectedSettingsPages.addAll(
                        listOf(
                            "Private DNS", "More connection settings", "VPN",
                            "family.cloudflare-dns.com", "cleanbrowsing"
                        )
                    )
                }
                if (prefs.getBoolean("block_settings_search", true)) {
                    protectedSettingsPages.addAll(
                        listOf("search_action_bar", "Search settings", "com.android.settings.intelligence")
                    )
                }
                if (scanTree(rootNode, protectedSettingsPages)) {
                    executePenalty(prefs, forceHome = true)
                    return
                }
            }
        }

        // 8. EMBEDDED WEBVIEW DETECTION & UNIVERSAL WEBSITE BLOCKLIST
        val isEmbeddedWebViewOpen = containsWebViewOrCustomTab(rootNode, className)
        if (isEmbeddedWebViewOpen) {
            if (prefs.getBoolean("block_all_webviews", false) &&
                pkg != "com.android.chrome" && pkg != "com.sec.android.app.sbrowser"
            ) {
                executePenalty(prefs, forceHome = false)
                return
            }
            if (prefs.getBoolean("block_chrome_settings", true) && scanTree(rootNode, blockedSearchEngines)) {
                executePenalty(prefs, forceHome = false)
                return
            }
        }

        val blockedWebsites = prefs.getStringSet("blocked_websites", emptySet())?.toList() ?: emptyList()
        if (blockedWebsites.isNotEmpty() && scanTree(rootNode, blockedWebsites)) {
            executePenalty(prefs, forceHome = false)
            return
        }

        // 9. BROWSER GATEKEEPER (Chrome, Samsung Internet, Firefox, Edge, Google App)
        if (pkg == "com.android.chrome" || pkg == "com.sec.android.app.sbrowser" ||
            pkg.contains("browser") || pkg == "org.mozilla.firefox" ||
            pkg == "com.microsoft.emmx" || pkg == "com.google.android.googlequicksearchbox"
        ) {
            if (prefs.getBoolean("block_chrome_settings", true)) {
                val browserBlocks = mutableListOf(
                    "chrome://settings", "chrome://flags", "chrome://net-internals",
                    "Site settings", "Use secure DNS", "Privacy and security", "Search engine",
                    "SafeSearch", "Incognito", "Secret mode", "InPrivate",
                    "New Incognito tab", "Turn on Secret mode", "You've gone Incognito"
                )
                browserBlocks.addAll(blockedSearchEngines)
                if (scanTree(rootNode, browserBlocks)) {
                    executePenalty(prefs, forceHome = false)
                    return
                }
            }
        }

        // 10. APP STORE GUARD (Google Play Store & Samsung Galaxy Store)
        if (isProtectionActive && (pkg == "com.android.vending" || pkg == "com.sec.android.app.samsungapps")) {
            if (scanTree(rootNode, blockedStoreKeywords)) {
                executePenalty(prefs, forceHome = false)
                return
            }
        }

        // 11. MULTI-SCRIPT ADULT DICTIONARY & CHAT LINK SHIELD
        if (prefs.getBoolean("adult_shield", true)) {
            if (scanTree(rootNode, builtInAdultAndUrlKeywords)) {
                clearAllEditTexts(rootNode)
                wipeClipboard()
                executePenalty(prefs, forceHome = true)
                return
            }
        }

        // 12. SOCIAL MEDIA SHIELDS (Shorts, Reels, WA Updates, Telegram Search, FB, Snapchat, Distraction Apps)
        if (prefs.getBoolean("block_shorts_reels", true)) {
            when (pkg) {
                "com.google.android.youtube" -> {
                    if (scanTree(rootNode, listOf("reel_recycler", "reel_player_page_container", "Shorts"))) {
                        executePenalty(prefs, forceHome = false)
                        return
                    }
                }
                "com.instagram.android", "com.instagram.lite" -> {
                    if (scanTree(rootNode, listOf("clips_viewer_view_pager", "search_edit_text", "Explore"))) {
                        executePenalty(prefs, forceHome = false)
                        return
                    }
                }
                "com.whatsapp", "com.whatsapp.w4b" -> {
                    if (className.contains("StatusPlaybackActivity") ||
                        scanTree(
                            rootNode,
                            listOf("Find channels", "Explore channels", "Status privacy", "Channel updates", "Updates", "Add status")
                        )
                    ) {
                        if (className.contains("StatusPlaybackActivity") ||
                            scanTree(rootNode, listOf("Find channels", "Explore channels", "Status privacy", "Channel updates", "Add status"))
                        ) {
                            executePenalty(prefs, forceHome = false)
                            return
                        }
                    }
                }
                "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram" -> {
                    if (scanTree(
                            rootNode,
                            listOf("Global search", "Search messages in", "Channels", "Apps", "Posts", "What can this bot do?")
                        ) || hasFocusedEditTextInToolbar(rootNode)
                    ) {
                        clearAllEditTexts(rootNode)
                        executePenalty(prefs, forceHome = false)
                        return
                    }
                }
                "com.facebook.katana", "com.facebook.lite" -> {
                    if (scanTree(rootNode, listOf("Reels and short videos", "Create a reel", "Watch", "fb_shorts_viewer"))) {
                        executePenalty(prefs, forceHome = false)
                        return
                    }
                }
                "com.snapchat.android" -> {
                    if (scanTree(rootNode, listOf("Spotlight", "Discover", "sc_search", "Search Snapchat", "Trending"))) {
                        executePenalty(prefs, forceHome = false)
                        return
                    }
                }
                "com.pinterest", "com.reddit.frontpage", "com.twitter.android",
                "com.instagram.barcelona", "in.mohalla.video", "in.mohalla.sharechat", "com.eterno.shortvideos" -> {
                    executePenalty(prefs, forceHome = true)
                    return
                }
            }
        }

        // 13. VISUAL NUDITY SCANNER (Android 11+ Silent Frame Capture every 1.15s)
        if (prefs.getBoolean("visual_scanner", true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val isMediaOrChatApp = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b" ||
                pkg.contains("telegram") || pkg.contains("gallery") || pkg.contains("video") ||
                pkg.contains("myfiles") || pkg.contains("vlc") || pkg.contains("mxtech") || isEmbeddedWebViewOpen
            if (isMediaOrChatApp) {
                if (nowMillis - lastScreenshotTime > 1150) {
                    lastScreenshotTime = nowMillis
                    inspectScreenPixelsForNudity(prefs)
                }
            }
        }

        // 14. GRANULAR PER-APP & GLOBAL CUSTOM KEYWORD RULES
        val rules = prefs.getStringSet("targeted_rules", emptySet()) ?: emptySet()
        val matchingKeywords = mutableListOf<String>()
        for (rule in rules) {
            val parts = rule.split("::", limit = 2)
            if (parts.size == 2 && (parts[0] == "ALL" || parts[0].equals(pkg, ignoreCase = true))) {
                matchingKeywords.add(parts[1])
            }
        }

        if (matchingKeywords.isNotEmpty() && scanTree(rootNode, matchingKeywords)) {
            executePenalty(prefs, forceHome = false)
        }
    }

    private fun executePenalty(prefs: android.content.SharedPreferences, forceHome: Boolean) {
        val nowMillis = SystemClock.uptimeMillis()
        lastViolationTime = SystemClock.elapsedRealtime()

        // Throw up <5ms opaque Blackout Curtain for 950ms
        showInstantBlackout(950L)

        if (nowMillis - lastActionTime < 150) return

        if (nowMillis - lastActionTime < 1500) {
            consecutiveHits++
        } else {
            consecutiveHits = 1
        }
        lastActionTime = nowMillis

        val count = prefs.getInt("blocked_count", 0) + 1
        prefs.edit().putInt("blocked_count", count).apply()

        if (forceHome || consecutiveHits >= 2) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            consecutiveHits = 0
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        if (prefs.getBoolean("lock_on_violation", false)) {
            lockScreenNow()
        }
    }

    private fun containsWebViewOrCustomTab(node: AccessibilityNodeInfo?, eventClassName: String): Boolean {
        if (eventClassName.contains("WebView", ignoreCase = true) ||
            eventClassName.contains("CustomTab", ignoreCase = true) ||
            eventClassName.contains("InAppBrowser", ignoreCase = true)
        ) {
            return true
        }
        return checkNodeForWebView(node)
    }

    private fun checkNodeForWebView(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val cls = node.className?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        if (cls == "android.webkit.WebView" ||
            id.contains("webview", ignoreCase = true) ||
            id.contains("url_bar", ignoreCase = true) ||
            id.contains("custom_tab", ignoreCase = true)
        ) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = checkNodeForWebView(child)
            child.recycle()
            if (hit) return true
        }
        return false
    }

    private fun inspectAndSanitizeClipboard(prefs: android.content.SharedPreferences, blockedSites: List<String>) {
        if (!prefs.getBoolean("adult_shield", true)) return
        try {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cb.primaryClip ?: return
            if (clip.itemCount > 0) {
                val clipText = clip.getItemAt(0).text?.toString() ?: ""
                if (clipText.isNotEmpty() &&
                    (containsAny(clipText, builtInAdultAndUrlKeywords) ||
                        containsAny(clipText, blockedSearchEngines) ||
                        containsAny(clipText, blockedSites))
                ) {
                    cb.setPrimaryClip(ClipData.newPlainText("clean", ""))
                }
            }
        } catch (_: Exception) {}
    }

    private fun wipeClipboard() {
        try {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("clean", ""))
        } catch (_: Exception) {}
    }

    private fun inspectScreenPixelsForNudity(prefs: android.content.SharedPreferences) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hwBuffer = screenshot.hardwareBuffer
                    try {
                        val colorSpace = screenshot.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                        if (bitmap != null) {
                            val swBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.recycle()
                            hwBuffer.close()
                            if (swBitmap != null) {
                                val isSuspicious = analyzeBitmapSkinCluster(swBitmap)
                                swBitmap.recycle()
                                if (isSuspicious) {
                                    executePenalty(prefs, forceHome = true)
                                }
                            }
                        } else {
                            hwBuffer.close()
                        }
                    } catch (_: Exception) {
                        try {
                            hwBuffer.close()
                        } catch (_: Exception) {}
                    }
                }

                override fun onFailure(errorCode: Int) {}
            })
        } catch (_: Exception) {}
    }

    // YCbCr Skin Analysis on central viewport (20%-80% height, 10%-90% width) with 14px step
    private fun analyzeBitmapSkinCluster(bmp: Bitmap): Boolean {
        val width = bmp.width
        val height = bmp.height
        val startY = (height * 0.20).toInt()
        val endY = (height * 0.80).toInt()
        val startX = (width * 0.10).toInt()
        val endX = (width * 0.90).toInt()

        var totalSampled = 0
        var skinPixels = 0
        val step = 14

        for (y in startY until endY step step) {
            for (x in startX until endX step step) {
                val pixel = bmp.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Skip letterbox bars (RGB < 20 or RGB > 240)
                if ((r < 20 && g < 20 && b < 20) || (r > 240 && g > 240 && b > 240)) continue

                totalSampled++
                val cb = 128.0 - (0.168736 * r) - (0.331264 * g) + (0.5 * b)
                val cr = 128.0 + (0.5 * r) - (0.418688 * g) - (0.081312 * b)

                if (cr in 135.0..175.0 && cb in 80.0..125.0 && r > 80 && g > 40 && b > 20 && r > g && r > b) {
                    skinPixels++
                }
            }
        }
        return totalSampled > 150 && (skinPixels.toFloat() / totalSampled.toFloat()) > 0.58f
    }

    private fun hasFocusedEditTextInToolbar(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.className?.toString() == "android.widget.EditText" && node.isFocused) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.top in 0..420) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = hasFocusedEditTextInToolbar(child)
            child.recycle()
            if (hit) return true
        }
        return false
    }

    private fun clearAllEditTexts(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (node.className?.toString() == "android.widget.EditText") {
            clearEditText(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            clearAllEditTexts(child)
            child.recycle()
        }
    }

    private fun clearEditText(sourceNode: AccessibilityNodeInfo?) {
        if (sourceNode == null) return
        try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            sourceNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {}
    }

    private fun containsAny(input: String, keywords: List<String>): Boolean {
        for (kw in keywords) {
            if (kw.isNotBlank() && input.contains(kw, ignoreCase = true)) return true
        }
        return false
    }

    // Three-Layer Recursive Node Inspection (text, contentDescription, viewIdResourceName)
    private fun scanTree(node: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        for (kw in keywords) {
            if (kw.isBlank()) continue
            if (text.contains(kw, ignoreCase = true) ||
                desc.contains(kw, ignoreCase = true) ||
                viewId.contains(kw, ignoreCase = true)
            ) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = scanTree(child, keywords)
            child.recycle()
            if (hit) return true
        }
        return false
    }

    private fun lockScreenNow() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComp = ComponentName(this, FortressAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComp)) {
                dpm.lockNow()
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
