package com.example.capsule

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var diagText: TextView
    private lateinit var saveMsg: TextView
    private lateinit var col: LinearLayout
    private val prefs by lazy { getSharedPreferences("capsule", MODE_PRIVATE) }
    private val accent by lazy { getColor(android.R.color.system_accent1_200) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(24))
        }
        status = TextView(this).apply { textSize = 15f }
        col.addView(status)

        val diag = card("Diagnostics")
        diagText = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE }
        diag.addView(diagText)
        button(diag, "Clear diagnostics") {
            prefs.edit().remove("diag").apply()
            diagText.text = ""
        }

        val perms = card("Permissions")
        button(perms, "1. Display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button(perms, "2. Notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button(perms, "3. Keep-alive (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button(perms, "4. App info (battery: No restrictions, Autostart)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button(perms, "5. Brightness control (for brightness gestures)") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        button(perms, "6. Do Not Disturb control (for quick settings)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        button(perms, "7. App usage access (for hiding in video and game apps)") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        button(perms, "8. Microphone (for the music visualizer)") {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 42)
        }

        val pillCard = card("Show in the pill")
        toggle(pillCard, "Music", "showMusic", true)
        toggle(pillCard, "Google Maps navigation", "showMaps", true)
        toggle(pillCard, "Other notifications", "showNotifs", true)
        toggle(pillCard, "Show charging status", "showCharging", true)
        toggle(pillCard, "Phone events (unlock, headphones, Bluetooth)", "showEvents", true)
        toggle(pillCard, "Download progress", "showDownloads", true)
        toggle(pillCard, "Quick settings grid", "qsEnabled", false)
        toggle(pillCard, "Music visualizer (uses the microphone)", "visualizer", false)
        toggle(pillCard, "Bars react to notifications and calls", "vizNotif", true)
        toggle(pillCard, "Driving mode (hide other notifications during Maps navigation)", "drivingMode", false)
        toggle(pillCard, "Haptic feedback", "haptics", true)
        toggle(pillCard, "Hide in video and game apps", "hideFullscreen", false)
        toggle(pillCard, "Debug: show raw Maps text", "debugMaps", false)
        toggle(pillCard, "Colour the title from the sending app", "appAccent", true)
        toggle(pillCard, "Charging alerts (when the charge reaches a level, or is full)", "chargeAlert", true)
        slider(pillCard, "Alert at charge (%)", "chargeAlertAt", 50, 100, 80)
        toggle(pillCard, "Night: hide the pill during these hours", "nightHide", false)
        slider(pillCard, "Night from (hour)", "nightFrom", 0, 23, 22)
        slider(pillCard, "Night to (hour)", "nightTo", 0, 23, 7)

        val gestures = card("Gestures (tap a row to change it)")
        for (key in CapsulePrefs.gestureKeys) gestureButton(gestures, key)

        val size = card("Size and position")
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val where = if (land) " (landscape)" else ""
        slider(size, "Collapsed width", "miniW", 40, 200, 100)
        slider(size, "Collapsed height", "miniH", 20, 60, 40)
        slider(size, "Expanded width", "expW", 150, 360, 340)
        slider(size, "Height: music player", "expH_music", 40, 120, 64)
        slider(size, "Height: notifications and calls", "expH_notif", 40, 120, 64)
        slider(size, "Height: charging", "expH_charge", 40, 120, 64)
        slider(size, "Vertical position$where", if (land) "topYL" else "topY", 0, 40, 6)
        slider(size, "Horizontal shift$where", if (land) "offXL" else "offX", -100, 100, 0)

        val look = card("Look")
        fontButton(look)
        themeButton(look)
        vizStyleButton(look)
        slider(look, "Text size (%)", "textScale", 70, 150, 100)
        toggle(look, "Blur behind the pill (Android 12+, test on your phone)", "blur", false)
        slider(look, "Blur strength", "blurRadius", 10, 100, 40)

        val image = card("Custom image widget")
        toggle(image, "Show the image in the pill (when nothing else is playing)", "imageWidget", false)
        button(image, "Choose image") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 7)
        }
        slider(image, "Image height (dp)", "imageHeight", 40, 220, 120)

        val privacy = card("Notifications and privacy")
        toggle(privacy, "Keep notification history on this phone (off by default)", "historyOn", false)
        val blockField = EditText(this).apply {
            hint = "Hide notifications from these apps (package names, comma separated)"
            setText(prefs.getString("blockedApps", ""))
        }
        privacy.addView(blockField)
        button(privacy, "Save hidden apps") {
            prefs.edit().putString("blockedApps", blockField.text.toString()).apply()
        }

        val custom = card("Grid and quick replies")
        val replyField = EditText(this).apply {
            hint = "Quick replies, comma separated (up to 3)"
            setText(prefs.getString("replies", "Yes,Later,Busy"))
        }
        custom.addView(replyField)
        val pinField = EditText(this).apply {
            hint = "Pinned apps, package names, comma separated (up to 4)"
            setText(prefs.getString("pinned", ""))
        }
        custom.addView(pinField)
        button(custom, "Save") {
            prefs.edit()
                .putString("replies", replyField.text.toString())
                .putString("pinned", pinField.text.toString())
                .apply()
        }

        val tools = card("Tools")
        button(tools, "Open code generator") { startActivity(Intent(this, CodeActivity::class.java)) }
        button(tools, "Save settings to a file") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "capsule-settings.json")
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 8)
        }
        button(tools, "Restore settings from a file") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/json"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 9)
        }
        saveMsg = TextView(this).apply { textSize = 13f }
        tools.addView(saveMsg)
        button(tools, "Test pill (preview a music card)") {
            prefs.edit().putLong("testPill", System.currentTimeMillis()).apply()
        }

        button(col, "Reset to defaults") {
            prefs.edit().clear().apply()
            recreate()
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(col)
        })
    }

    // A rounded section with an accent title; controls are added inside it
    private fun card(title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        box.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 12f
            setTextColor(accent)
            setPadding(0, 0, 0, dp(6))
        })
        col.addView(box)
        return box
    }

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    private fun toggle(parent: LinearLayout, label: String, key: String, default: Boolean) {
        parent.addView(Switch(this).apply {
            text = label
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        })
    }

    // Each tap moves the gesture to the next action in the list
    private fun gestureButton(parent: LinearLayout, key: String) {
        fun label(action: String) =
            "${CapsulePrefs.gestureLabels[key]}: ${CapsulePrefs.actionLabels[action] ?: action}"

        val current = prefs.getString(key, CapsulePrefs.defaultFor(key)) ?: "none"
        val b = Button(this).apply { text = label(current) }
        b.setOnClickListener {
            val cur = prefs.getString(key, CapsulePrefs.defaultFor(key)) ?: "none"
            val idx = CapsulePrefs.actions.indexOf(cur)
            val next = CapsulePrefs.actions[(idx + 1) % CapsulePrefs.actions.size]
            prefs.edit().putString(key, next).apply()
            b.text = label(next)
        }
        parent.addView(b)
    }

    private fun fontButton(parent: LinearLayout) {
        val fonts = listOf("default", "sans-serif-medium", "serif", "sans-serif-condensed", "monospace")
        val names = mapOf(
            "default" to "Default", "sans-serif-medium" to "Medium sans", "serif" to "Serif",
            "sans-serif-condensed" to "Condensed", "monospace" to "Monospace"
        )
        fun label(f: String) = "Font: ${names[f] ?: f}"
        val cur = prefs.getString("fontFamily", "default") ?: "default"
        val b = Button(this).apply { text = label(cur) }
        b.setOnClickListener {
            val c = prefs.getString("fontFamily", "default") ?: "default"
            val next = fonts[(fonts.indexOf(c) + 1) % fonts.size]
            prefs.edit().putString("fontFamily", next).apply()
            b.text = label(next)
        }
        parent.addView(b)
    }

    private fun themeButton(parent: LinearLayout) {
        val themes = listOf("black", "frosted", "accent")
        val names = mapOf("black" to "Black", "frosted" to "Translucent", "accent" to "Accent outline")
        fun label(t: String) = "Pill theme: ${names[t] ?: t}"
        val cur = prefs.getString("theme", "black") ?: "black"
        val b = Button(this).apply { text = label(cur) }
        b.setOnClickListener {
            val c = prefs.getString("theme", "black") ?: "black"
            val next = themes[(themes.indexOf(c) + 1) % themes.size]
            prefs.edit().putString("theme", next).apply()
            b.text = label(next)
        }
        parent.addView(b)
    }

    private fun vizStyleButton(parent: LinearLayout) {
        val styles = listOf("bars", "mirror", "dots", "wave")
        val names = mapOf("bars" to "Bars", "mirror" to "Mirror", "dots" to "Dots", "wave" to "Wave")
        fun label(v: String) = "Visualizer style: ${names[v] ?: v}"
        val cur = prefs.getString("vizStyle", "bars") ?: "bars"
        val b = Button(this).apply { text = label(cur) }
        b.setOnClickListener {
            val c = prefs.getString("vizStyle", "bars") ?: "bars"
            val next = styles[(styles.indexOf(c) + 1) % styles.size]
            prefs.edit().putString("vizStyle", next).apply()
            b.text = label(next)
        }
        parent.addView(b)
    }

    private fun slider(parent: LinearLayout, label: String, key: String, lo: Int, hi: Int, default: Int) {
        val start = prefs.getInt(key, default)
        val caption = TextView(this).apply { text = "$label: $start" }
        parent.addView(caption)
        parent.addView(SeekBar(this).apply {
            max = hi - lo
            progress = start - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    caption.text = "$label: ${p + lo}"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    prefs.edit().putInt(key, sb.progress + lo).apply()
                }
            })
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Save: write every setting to the chosen file (diagnostics and the test flag are left out)
        if (requestCode == 8 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                val json = JSONObject()
                for ((k, v) in prefs.all) {
                    if (k == "diag" || k == "testPill") continue
                    json.put(k, v)
                }
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toString(2).toByteArray()) }
                    ?: error("could not open the file")
            }.onSuccess {
                saveMsg.text = "Settings saved to the file."
            }.onFailure {
                saveMsg.text = "Could not save: ${it.message}"
            }
        }
        // Restore: read a saved file and apply every setting in it
        if (requestCode == 9 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                val text = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    ?: error("could not open the file")
                val json = JSONObject(text)
                val edit = prefs.edit()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "diag" || k == "testPill") continue
                    when (val v = json.get(k)) {
                        is Boolean -> edit.putBoolean(k, v)
                        is Int -> edit.putInt(k, v)
                        is Double -> edit.putFloat(k, v.toFloat())
                        is String -> edit.putString(k, v)
                    }
                }
                edit.apply()
            }.onSuccess {
                recreate()
            }.onFailure {
                saveMsg.text = "Could not restore: ${it.message}"
            }
        }
        if (requestCode == 7 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            // Keep read access to the picture after the app restarts
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.edit()
                .putString("widgetImage", uri.toString())
                .putBoolean("imageWidget", true)
                .apply()
        }
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val listener = Settings.Secure
            .getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        val keepAlive = Settings.Secure
            .getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        val brightness = Settings.System.canWrite(this)
        val dnd = getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
        val usage = getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        status.text = "Overlay: ${if (overlay) "OK" else "missing"}\n" +
            "Notification access: ${if (listener) "OK" else "missing"}\n" +
            "Keep-alive: ${if (keepAlive) "OK" else "off"}\n" +
            "Brightness control: ${if (brightness) "OK" else "off"}\n" +
            "Do Not Disturb control: ${if (dnd) "OK" else "off"}\n" +
            "App usage access: ${if (usage) "OK" else "off"}\n" +
            "Microphone: ${if (mic) "OK" else "off"}"

        diagText.text = prefs.getString("diag", null) ?: "no service events yet"

        if (overlay && listener) {
            NotificationListenerService.requestRebind(ComponentName(this, CapsuleService::class.java))
        }
    }
}
