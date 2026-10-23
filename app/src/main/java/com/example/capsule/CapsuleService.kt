package com.example.capsule

import android.animation.ValueAnimator
import android.Manifest
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceInfo
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.provider.AlarmClock
import android.view.HapticFeedbackConstants
import android.bluetooth.BluetoothDevice
import android.net.Uri
import android.widget.GridLayout
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.os.Build
import android.os.SystemClock
import android.widget.ProgressBar
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.abs

class CapsuleService : NotificationListenerService() {

    private lateinit var uiCtx: Context
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams

    private var root: View? = null          // transparent window view (includes the tap margin)
    private var pill: LinearLayout? = null  // the black pill inside it

    private lateinit var miniText: TextView
    private lateinit var icon: ImageView
    private lateinit var textCol: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var qsGrid: GridLayout
    private var qsButtons: Map<String, TextView> = emptyMap()
    private var imageOpen = false       // opened by the image widget gesture
    private var qsOpen = false          // opened by the quick settings gesture
    private var lastQs = false
    private var sizeAnim: ValueAnimator? = null
    // One easing curve for every size change: a quick start and a long, soft landing
    private val easeOut = PathInterpolator(0.2f, 0.9f, 0.2f, 1f)
    private val QS_COLS = 4
    private var stopwatchStart = 0L      // elapsedRealtime when running, 0 when paused
    private var stopwatchBase = 0L       // time already counted before the last pause
    private var lastTempC = 0.0
    private var hiddenByApp = false
    private var tickCount = 0
    @Volatile private var micRunning = false
    private var ambientRunning = false   // headphones: generated motion, since the mic can't hear them
    private var vizLoop = false
    private val smoothed = FloatArray(5)
    private val mirrorWeights = floatArrayOf(0.5f, 0.8f, 1f, 0.8f, 0.5f)
    private lateinit var widgetImage: ImageView
    private var widgetBitmap: Bitmap? = null
    private val barLevels = FloatArray(5)   // 0..1 level per visualizer bar, written by the mic thread
    private var lastInner = -1
    private var chargeAlerted = false
    private var fullAlerted = false
    private var boostAt = 0L             // when the last notification or event arrived
    private lateinit var barsRow: LinearLayout
    private var bars: List<View> = emptyList()
    private lateinit var btnAnswer: ImageButton
    private lateinit var btnDecline: ImageButton
    private var chips: List<TextView> = emptyList()

    private var miniW = 0
    private var miniH = 0
    private var expW = 0
    private var expH = 0

    private var expanded = false
    private var showingNotif = false
    private var notifIntent: PendingIntent? = null
    private var notifKey: String? = null
    private var downloadKey: String? = null
    private val mapsKeys = mutableSetOf<String>()   // Maps notifications currently shown (navigation active when not empty)
    private var replyAction: Notification.Action? = null

    // Incoming or ongoing call (notification based)
    private var callActive = false
    private var callKey: String? = null
    private var callAnswer: Notification.Action? = null
    private var callHangup: Notification.Action? = null
    private var callTitle = ""
    private var callSubtitle = ""

    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastMapsText = ""
    private var torchOn = false

    // Charging state, filled by the battery receiver
    private var charging = false
    private var chargePct = 0
    private var chargeDetail = ""

    private val prefs by lazy { getSharedPreferences("capsule", MODE_PRIVATE) }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "diag") return@OnSharedPreferenceChangeListener
        if (key == "testPill") {
            handler.post { runTestPill() }
            return@OnSharedPreferenceChangeListener
        }
        handler.post {
            if (root == null) return@post
            root?.let { v -> runCatching { wm.removeView(v) } }
            root = null
            pill = null
            expanded = callActive
            buildOverlay()
            msm?.let { pickController(it.getActiveSessions(ComponentName(this, CapsuleService::class.java))) }
            refreshMedia()
        }
    }

    private val collapse = Runnable {
        if (callActive) {
            refreshMedia()   // a call keeps the pill open
        } else {
            showingNotif = false
            notifIntent = null
            notifKey = null
            replyAction = null
            qsOpen = false
            imageOpen = false
            applyNotifAccent(0)
            progress.visibility = View.GONE
            refreshMedia()
            setExpanded(false)
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshMedia()
            if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING && !showingNotif) {
                peek(3000)
            }
        }
        override fun onSessionDestroyed() = pickController(null)
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    // Gestures: tap, double tap, long press, and swipes. Each one runs the action chosen in settings.
    private val gestures by lazy { GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // A tap on a notification opens it, as before
            if (showingNotif && notifIntent != null && !callActive) {
                runCatching { notifIntent?.send() }
                handler.removeCallbacks(collapse)
                collapse.run()
            } else {
                dispatchGesture("g_tap")
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            dispatchGesture("g_double")
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            dispatchGesture("g_long")
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (abs(dx) < dp(30) && abs(dy) < dp(30)) return false
            val key = if (abs(dx) > abs(dy)) {
                if (dx > 0) "g_right" else "g_left"
            } else {
                if (dy > 0) "g_down" else "g_up"
            }
            dispatchGesture(key)
            return true
        }
    }) }

    // ---------- diagnostics (shown in the app's settings screen) ----------

    private fun diag(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val old = prefs.getString("diag", "") ?: ""
        val lines = (old.split("\n").filter { it.isNotBlank() } + "$time $msg").takeLast(10)
        prefs.edit().putString("diag", lines.joinToString("\n")).apply()
    }

    // ---------- lifecycle ----------

    override fun onListenerConnected() {
        diag("listener connected")
        if (root != null) return
        if (!Settings.canDrawOverlays(this)) {
            diag("overlay permission missing, not started")
            return
        }
        try {
            buildOverlay()
        } catch (e: Exception) {
            diag("overlay FAILED: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        diag("overlay added: ${lp.width}x${lp.height} at y=${lp.y}")
        powerSupplyCurrents().take(5).forEach { diag("psy $it") }
        registerBattery()
        registerEvents()
        handler.post(tickRunnable)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        val cn = ComponentName(this, CapsuleService::class.java)
        m.addOnActiveSessionsChangedListener(sessionsListener, cn)
        pickController(m.getActiveSessions(cn))
    }

    override fun onListenerDisconnected() {
        diag("listener disconnected")
        teardown()
        NotificationListenerService.requestRebind(ComponentName(this, CapsuleService::class.java))
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(eventReceiver) }
        stopMic()
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        root?.let { v -> runCatching { wm.removeView(v) } }
        root = null
        pill = null
        expanded = false
        showingNotif = false
        callActive = false
        callKey = null
    }

    // ---------- sizes and overlay ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Extra invisible touch margin around the pill, so taps near its edges register
    private fun touchPad() = dp(10)

    private fun buildOverlay() {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        uiCtx = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        wm = uiCtx.getSystemService(WindowManager::class.java)

        val pad = touchPad()
        val screenW = uiCtx.resources.displayMetrics.widthPixels
        val cut = wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }

        miniW = if (cut != null) cut.width() + dp(20) else dp(96)
        miniH = maxOf(if (cut != null) cut.height() + dp(8) else dp(34), dp(40))
        var topY = if (cut != null) (cut.top - dp(4)).coerceAtLeast(0) else dp(6)
        expW = minOf(screenW - dp(24), dp(340))
        expH = dp(64)

        // Saved settings override the automatic sizes
        if (prefs.contains("miniW")) miniW = dp(prefs.getInt("miniW", 100))
        if (prefs.contains("miniH")) miniH = dp(prefs.getInt("miniH", 40))
        if (prefs.contains("expW")) expW = minOf(screenW - dp(24), dp(prefs.getInt("expW", 340)))
        if (prefs.contains("expH")) expH = dp(prefs.getInt("expH", 64))
        val land = uiCtx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val topKey = if (land) "topYL" else "topY"
        val offKey = if (land) "offXL" else "offX"
        if (prefs.contains(topKey)) topY = dp(prefs.getInt(topKey, 6))
        val offX = dp(prefs.getInt(offKey, 0))

        val accent = uiCtx.getColor(android.R.color.system_accent1_200)

        val row = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = themeBackground()
            setOnTouchListener { _, e -> onPillTouch(e) }
        }

        miniText = TextView(uiCtx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }
        icon = ImageView(uiCtx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), 0x55FFFFFF)
            }
        }
        title = TextView(uiCtx).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = TextView(uiCtx).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        progress = ProgressBar(uiCtx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
                .apply { topMargin = dp(4) }
        }
        textCol = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10); marginEnd = dp(6) }
            addView(title)
            addView(subtitle)
            addView(progress)
        }

        btnPrev = control(android.R.drawable.ic_media_previous, accent) {
            controller?.transportControls?.skipToPrevious()
        }
        btnPlay = control(android.R.drawable.ic_media_play, accent) { togglePlay() }
        btnNext = control(android.R.drawable.ic_media_next, accent) {
            controller?.transportControls?.skipToNext()
        }
        btnAnswer = control(android.R.drawable.ic_menu_call, Color.parseColor("#34C759")) {
            runCatching { callAnswer?.actionIntent?.send() }
        }
        btnDecline = control(android.R.drawable.ic_menu_close_clear_cancel, Color.parseColor("#FF3B30")) {
            runCatching { callHangup?.actionIntent?.send() }
        }

        // Quick reply chips for message notifications that accept a reply
        val replies = (prefs.getString("replies", "Yes,Later,Busy") ?: "Yes,Later,Busy")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(3)
        chips = replies.map { text ->
            TextView(uiCtx).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
                isSingleLine = true
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    setColor(0xFF333333.toInt())
                    cornerRadius = dp(14).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
                setOnClickListener { sendReply(text) }
            }
        }

        widgetImage = ImageView(uiCtx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF111111.toInt())
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        }
        widgetBitmap = if (prefs.getBoolean("imageWidget", false)) loadWidgetImage() else null
        widgetImage.setImageBitmap(widgetBitmap)
        qsGrid = GridLayout(uiCtx).apply {
            columnCount = QS_COLS
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { setMargins(dp(10), dp(8), dp(10), dp(8)) }
        }
        qsButtons = CapsulePrefs.qsItems.associate { (id, label) ->
            val tv = TextView(uiCtx).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(dp(4), dp(6), dp(4), dp(6))
                background = GradientDrawable().apply {
                    setColor(0xFF333333.toInt())
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    height = dp(40)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener { qsTap(id) }
                setOnLongClickListener {
                    if (id == "stopwatch") {
                        stopwatchStart = 0L
                        stopwatchBase = 0L
                        applyVisibility()
                    }
                    id == "stopwatch"
                }
            }
            qsGrid.addView(tv)
            id to tv
        }
        qsButtons = qsButtons + pinnedPackages().associate { pkg ->
            val id = "app:$pkg"
            id to makeTile(id, appLabel(pkg))
        }
        barsRow = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8); marginEnd = dp(10) }
            visibility = View.GONE
        }
        bars = List(5) {
            View(uiCtx).apply {
                background = GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(2).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(6)).apply { marginStart = dp(2) }
            }.also { barsRow.addView(it) }
        }
        row.addView(miniText)
        row.addView(icon)
        row.addView(textCol)
        row.addView(btnPrev)
        row.addView(btnPlay)
        row.addView(btnNext)
        row.addView(barsRow)
        row.addView(qsGrid)
        row.addView(widgetImage)
        chips.forEach { row.addView(it) }
        row.addView(btnDecline)
        row.addView(btnAnswer)

        applyTextStyle()
        val wrap = FrameLayout(uiCtx).apply {
            setOnTouchListener { _, e -> onPillTouch(e) }
            addView(
                row,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { setMargins(pad, pad, pad, pad) }
            )
        }

        lp = WindowManager.LayoutParams(
            miniW + 2 * pad, miniH + 2 * pad,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = offX
            y = (topY - pad).coerceAtLeast(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (expanded) {
            lp.width = expW + 2 * pad
            lp.height = targetInnerH() + 2 * pad
        }

        if (prefs.getBoolean("blur", false)) applyBlur()
        pill = row
        root = wrap
        applyVisibility()
        wm.addView(wrap, lp)
    }

    private fun applyTextStyle() {
        val k = prefs.getInt("textScale", 100) / 100f
        val face = Typeface.create(prefs.getString("fontFamily", "default") ?: "default", Typeface.NORMAL)
        for (tv in listOf(miniText, title, subtitle) + chips) tv.typeface = face
        miniText.textSize = 13f * k
        title.textSize = 14f * k
        subtitle.textSize = 12f * k
        chips.forEach { it.textSize = 13f * k }
    }

    private fun themeBackground() = GradientDrawable().apply {
        cornerRadius = dp(40).toFloat()
        if (prefs.getBoolean("blur", false)) {
            // Semi-transparent so the blurred background shows through
            setColor(0x59000000)
            setStroke(dp(1), 0x33FFFFFF)
            return@apply
        }
        when (prefs.getString("theme", "black")) {
            "frosted" -> {
                setColor(0xD9222226.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            "accent" -> {
                setColor(0xF0000000.toInt())
                setStroke(dp(2), uiCtx.getColor(android.R.color.system_accent1_200))
            }
            else -> setColor(Color.BLACK)
        }
    }

    // Background blur for the pill window (Android 12+). The window is only asked for blur;
    // Android decides whether it shows, so the result is written to Diagnostics.
    private fun applyBlur() {
        val radius = prefs.getInt("blurRadius", 40)
        if (Build.VERSION.SDK_INT < 31) {
            diag("blur: needs Android 12 or newer")
            return
        }
        runCatching {
            val m = WindowManager.LayoutParams::class.java.getMethod(
                "setBlurBehindRadius", Int::class.javaPrimitiveType
            )
            m.invoke(lp, radius)
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }.onSuccess {
            diag("blur: requested, radius $radius")
        }.onFailure {
            diag("blur: not available here (${it.javaClass.simpleName})")
        }
    }

    private fun widgetActive(): Boolean {
        if (widgetBitmap == null || !expanded || callActive || showingNotif) return false
        if (imageOpen) return true
        val playing = showMusic() && controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        return prefs.getBoolean("imageWidget", false) && !playing
    }

    // Loads the chosen picture, scaled down so it stays light in memory
    private fun loadWidgetImage(): Bitmap? {
        val uri = prefs.getString("widgetImage", null) ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    // Package names the user hid from the pill and from history
    private fun blockedApps(): Set<String> =
        (prefs.getString("blockedApps", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun pinnedPackages(): List<String> =
        (prefs.getString("pinned", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(4)

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun makeTile(id: String, label: String): TextView {
        val tv = TextView(uiCtx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = GradientDrawable().apply {
                setColor(0xFF333333.toInt())
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = dp(40)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            setOnClickListener { qsTap(id) }
        }
        qsGrid.addView(tv)
        return tv
    }

    // Night hours (for example 22 to 7): true while the pill should be hidden
    private fun inNight(): Boolean {
        if (!prefs.getBoolean("nightHide", false)) return false
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val from = prefs.getInt("nightFrom", 22)
        val to = prefs.getInt("nightTo", 7)
        return if (from <= to) h in from until to else h >= from || h < to
    }

    // The app that sent a notification sets the title colour; without one, the title stays white
    private fun applyNotifAccent(color: Int) {
        val useApp = prefs.getBoolean("appAccent", true) && color != 0
        title.setTextColor(if (useApp) color else Color.WHITE)
    }

    // Keeps the last ten notifications, shown in the History screen
    private fun recordHistory(pkg: String, t: String, body: String) {
        val h = getSharedPreferences("history", MODE_PRIVATE)
        val time = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val lines = (h.getString("list", "") ?: "").split("\n").filter { it.isNotBlank() }
        val entry = "$time|$pkg|${t.replace("\n", " ")}|${body.replace("\n", " ")}"
        h.edit().putString("list", (listOf(entry) + lines).take(10).joinToString("\n")).apply()
    }

    private fun haptic() {
        if (!prefs.getBoolean("haptics", true)) return
        root?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun startTimer() {
        launch(Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, 300)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Capsule timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })
    }

    private fun stopwatchElapsed(): Long =
        stopwatchBase + (if (stopwatchStart > 0) SystemClock.elapsedRealtime() - stopwatchStart else 0L)

    private fun toggleStopwatch() {
        if (stopwatchStart > 0) {
            stopwatchBase = stopwatchElapsed()
            stopwatchStart = 0L
        } else {
            stopwatchStart = SystemClock.elapsedRealtime()
        }
        applyVisibility()
    }

    // Hides the pill while a video or game app is in front (needs usage access). Calls still show.
    private fun checkFullscreen() {
        val want = (prefs.getBoolean("hideFullscreen", false) && !callActive && isFullscreenApp()) ||
            (!callActive && inNight())
        if (want != hiddenByApp) {
            hiddenByApp = want
            root?.visibility = if (want) View.GONE else View.VISIBLE
        }
    }

    private fun isFullscreenApp(): Boolean {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return false
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        val pkg = last ?: return false
        if (pkg == packageName) return false
        return runCatching {
            val cat = packageManager.getApplicationInfo(pkg, 0).category
            cat == ApplicationInfo.CATEGORY_VIDEO || cat == ApplicationInfo.CATEGORY_GAME
        }.getOrDefault(false)
    }

    // Rebuilds the pill, used when the phone rotates so the landscape position applies
    private fun rebuildOverlay() {
        if (root == null) return
        root?.let { v -> runCatching { wm.removeView(v) } }
        root = null
        pill = null
        expanded = callActive
        hiddenByApp = false
        buildOverlay()
        msm?.let { pickController(it.getActiveSessions(ComponentName(this, CapsuleService::class.java))) }
        refreshMedia()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post { rebuildOverlay() }
    }

    private fun control(res: Int, tint: Int, onClick: () -> Unit) = ImageButton(uiCtx).apply {
        setImageResource(res)
        setColorFilter(tint)
        background = null
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setOnClickListener {
            onClick()
            peek(4000)
        }
    }

    // ---------- touch and gestures ----------

    private fun onPillTouch(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_OUTSIDE) {
            // Tap anywhere outside the window while expanded: collapse
            if (expanded && !callActive) {
                handler.removeCallbacks(collapse)
                collapse.run()
            }
            return false
        }
        return gestures.onTouchEvent(e)
    }

    private fun dispatchGesture(key: String) {
        haptic()
        val action = prefs.getString(key, CapsulePrefs.defaultFor(key)) ?: "none"
        runAction(action)
    }

    private fun runAction(action: String) {
        when (action) {
            "expand" -> if (expanded) {
                handler.removeCallbacks(collapse)
                collapse.run()
            } else {
                peek(6000)
            }
            "play_pause" -> togglePlay()
            "next" -> controller?.transportControls?.skipToNext()
            "previous" -> controller?.transportControls?.skipToPrevious()
            "flashlight" -> toggleTorch()
            "volume_up" -> audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "brightness_up" -> adjustBrightness(25)
            "brightness_down" -> adjustBrightness(-25)
            "open_maps" -> launch(packageManager.getLaunchIntentForPackage(CapsulePrefs.MAPS_PKG))
            "open_camera" -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            "open_dialer" -> launch(Intent(Intent.ACTION_DIAL))
            "image_widget" -> {
                if (widgetBitmap != null) {
                    imageOpen = !imageOpen
                    if (imageOpen) qsOpen = false
                    handler.removeCallbacks(collapse)
                    setExpanded(true)
                    applyVisibility()
                }
            }
            "open_codes" -> launch(Intent(this, CodeActivity::class.java))
            "clock" -> showClock()
            "quick_settings" -> {
                qsOpen = !qsOpen
                if (qsOpen) imageOpen = false
                handler.removeCallbacks(collapse)
                setExpanded(true)
                applyVisibility()
            }
        }
    }

    private fun audio() = getSystemService(AudioManager::class.java)

    private fun launch(i: Intent?) {
        if (i == null) return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    private fun toggleTorch() {
        val cam = getSystemService(CameraManager::class.java)
        runCatching {
            val id = cam.cameraIdList.firstOrNull {
                cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            cam.setTorchMode(id, torchOn)
        }
    }

    private fun adjustBrightness(delta: Int) {
        if (!Settings.System.canWrite(this)) return   // needs the brightness permission in settings
        runCatching {
            val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (cur + delta).coerceIn(0, 255))
        }
    }

    // ---------- expand / collapse ----------

    private fun peek(ms: Long) {
        setExpanded(true)
        handler.removeCallbacks(collapse)
        if (!callActive) handler.postDelayed(collapse, ms)
    }

    private fun setExpanded(e: Boolean) {
        val r = root ?: return
        if (e == expanded) {
            applyVisibility()
            return
        }
        expanded = e
        applyVisibility()
        if (e) staggerIn()

        animateSize()
    }

    // Each visible part of the pill slides up and fades in, one after another
    private fun staggerIn() {
        val items = listOf(icon, textCol, btnPrev, btnPlay, btnNext, barsRow, btnAnswer, btnDecline) + chips
        items.forEachIndexed { i, v ->
            if (v.visibility != View.VISIBLE) return@forEachIndexed
            v.alpha = 0f
            v.translationY = dp(6).toFloat()
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(60L + i * 35L)
                .setDuration(240)
                .setInterpolator(easeOut)
                .start()
        }
    }

    // Expanded height depends on what the pill is showing: grid, music, notification or call, or charging
    private fun targetInnerH(): Int {
        if (qsActive()) {
            val rows = (CapsulePrefs.qsItems.size + pinnedPackages().size + QS_COLS - 1) / QS_COLS
            return rows * dp(46) + dp(16)
        }
        if (widgetActive()) return dp(prefs.getInt("imageHeight", 120))
        val key = when {
            callActive || showingNotif -> "expH_notif"
            controller != null && showMusic() -> "expH_music"
            else -> "expH_charge"
        }
        return dp(prefs.getInt(key, 64))
    }

    private fun animateSize() {
        val r = root ?: return
        val pad = touchPad()
        val fromW = lp.width
        val fromH = lp.height
        val toW = (if (expanded) expW else miniW) + 2 * pad
        val toH = (if (expanded) targetInnerH() else miniH) + 2 * pad
        sizeAnim?.cancel()
        sizeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = easeOut
            addUpdateListener {
                val f = it.animatedFraction
                lp.width = (fromW + (toW - fromW) * f).toInt()
                lp.height = (fromH + (toH - fromH) * f).toInt()
                (pill?.background as? GradientDrawable)?.cornerRadius = minOf((lp.height - 2 * pad) / 2f, dp(40).toFloat())
                if (r.isAttachedToWindow) wm.updateViewLayout(r, lp)
            }
            start()
        }
    }

    // ---------- microphone visualizer ----------
    // Reads the microphone only to measure sound levels. Audio is never saved or sent anywhere.
    // It reacts to sound the microphone can hear, such as music from the speaker. Headphones won't drive it.

    private fun syncMic() {
        val wants = prefs.getBoolean("visualizer", false) && pill != null && !callActive && !showingNotif &&
            showMusic() && controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (!wants) {
            if (micRunning || ambientRunning) {
                stopMic()
                applyVisibility()
            }
            return
        }
        if (headphonesConnected()) {
            if (micRunning) stopMic()
            if (!ambientRunning) {
                ambientRunning = true
                startViz()
                applyVisibility()
            }
        } else {
            ambientRunning = false
            if (!micRunning) {
                startMic()
                applyVisibility()
            }
        }
    }

    // A notification, call, or phone event makes the bars jump; the jump fades out over about a second
    private fun boostViz() {
        if (!prefs.getBoolean("vizNotif", true)) return
        boostAt = SystemClock.uptimeMillis()
    }

    private fun startViz() {
        if (vizLoop) return
        vizLoop = true
        handler.post(vizRunnable)
    }

    // Earphones, wired headsets, or Bluetooth audio: the microphone can't pick up their sound
    private fun headphonesConnected(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun stopMic() {
        micRunning = false
        ambientRunning = false
    }

    private fun startMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        micRunning = true
        Thread {
            val rate = 44100
            val n = 1024
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), n * 2)
                )
            } catch (e: Exception) {
                micRunning = false
                return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                micRunning = false
                rec.release()
                return@Thread
            }
            val buf = ShortArray(n)
            val freqs = doubleArrayOf(120.0, 300.0, 700.0, 1600.0, 3500.0)   // one per bar: bass to treble
            var peak = 1.0
            try {
                rec.startRecording()
                while (micRunning) {
                    val read = rec.read(buf, 0, n)
                    if (read <= 0) continue
                    val power = DoubleArray(5) { i -> Math.sqrt(maxOf(0.0, goertzel(buf, read, freqs[i], rate.toDouble()))) }
                    peak = maxOf(power.maxOrNull() ?: 0.0, peak * 0.98)
                    for (i in 0 until 5) barLevels[i] = (power[i] / peak).toFloat().coerceIn(0f, 1f)
                }
            } catch (e: Exception) {
                micRunning = false
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }.start()
        startViz()
    }

    // Power at one frequency (Goertzel algorithm): cheap enough to run on a phone many times a second
    private fun goertzel(buf: ShortArray, n: Int, freq: Double, rate: Double): Double {
        val k = Math.round(n * freq / rate).toDouble()
        val coeff = 2.0 * Math.cos(2.0 * Math.PI * k / n)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s0 = buf[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private val vizRunnable = object : Runnable {
        override fun run() {
            if ((!micRunning && !ambientRunning) || pill == null) {
                vizLoop = false
                return
            }
            if (!micRunning) {
                // Earphones: generated motion, not the real sound
                val t = SystemClock.uptimeMillis() / 1000.0
                for (i in 0 until 5) {
                    val v = 0.5 + 0.25 * Math.sin(t * (2.1 + i * 0.7) + i * 1.3) +
                        0.25 * Math.sin(t * (4.3 + i * 1.1) + i)
                    barLevels[i] = v.toFloat().coerceIn(0f, 1f)
                }
            }
            val style = prefs.getString("vizStyle", "bars") ?: "bars"
            val fade = (1f - (SystemClock.uptimeMillis() - boostAt) / 900f).coerceIn(0f, 1f)
            for ((i, b) in bars.withIndex()) {
                val lv = minOf(1f, barLevels[i] + fade * mirrorWeights[i])
                smoothed[i] += (lv - smoothed[i]) * 0.3f
                val (w, h) = when (style) {
                    "mirror" -> dp(3) to dp(3) + (lv * mirrorWeights[i] * dp(16)).toInt()
                    "dots" -> dp(6) to dp(6)
                    "wave" -> dp(3) to dp(3) + (smoothed[i] * dp(16)).toInt()
                    else -> dp(3) to dp(3) + (lv * dp(14)).toInt()
                }
                b.alpha = if (style == "dots") 0.25f + 0.75f * lv else 1f
                b.layoutParams = LinearLayout.LayoutParams(w, h).apply { marginStart = dp(2) }
                b.requestLayout()
            }
            handler.postDelayed(this, 80)
        }
    }

    // ---------- quick settings grid ----------

    // Grid shows when it was opened by gesture, or by default when nothing else is playing or showing
    private fun qsActive(): Boolean {
        if (!prefs.getBoolean("qsEnabled", false) || !expanded || callActive || showingNotif) return false
        if (imageOpen) return false
        val playing = showMusic() && controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        return qsOpen || !playing
    }

    private fun qsTap(id: String) {
        haptic()
        if (id.startsWith("app:")) {
            launch(packageManager.getLaunchIntentForPackage(id.removePrefix("app:")))
            return
        }
        when (id) {
            "torch" -> toggleTorch()
            "vol_up" -> audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "vol_down" -> audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "bright_up" -> adjustBrightness(25)
            "bright_down" -> adjustBrightness(-25)
            "rotate" -> toggleRotate()
            "dnd" -> toggleDnd()
            "wifi" -> launch(Intent(Settings.Panel.ACTION_WIFI))
            "bluetooth" -> launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "data" -> launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            "airplane" -> launch(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            "hotspot" -> launch(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            "location" -> launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "timer" -> startTimer()
            "stopwatch" -> toggleStopwatch()
            "codes" -> launch(Intent(this, CodeActivity::class.java))
            "history" -> launch(Intent(this, HistoryActivity::class.java))
        }
        refreshQsStates()
    }

    private fun toggleRotate() {
        if (!Settings.System.canWrite(this)) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        runCatching {
            val cur = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (cur == 1) 0 else 1)
        }
    }

    private fun toggleDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        runCatching {
            nm.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_NONE
            )
        }
    }

    // Colours the buttons that are on, using the system accent
    private fun refreshQsStates() {
        if (qsButtons.isEmpty()) return
        val rotateOn = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        }.getOrDefault(false)
        val dndOn = runCatching {
            getSystemService(NotificationManager::class.java).currentInterruptionFilter !=
                NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
        // Widgets show live values inside their grid buttons
        val now = java.util.Date()
        qsButtons["clock"]?.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
        qsButtons["date"]?.text = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(now)
        qsButtons["battery"]?.text = "$chargePct%"
        val on = mapOf("torch" to torchOn, "rotate" to rotateOn, "dnd" to dndOn)
        val accent = uiCtx.getColor(android.R.color.system_accent1_200)
        qsButtons.forEach { (id, tv) ->
            val active = on[id] == true
            (tv.background as GradientDrawable).setColor(if (active) accent else 0xFF333333.toInt())
            tv.setTextColor(if (active) Color.BLACK else Color.WHITE)
        }
    }

    // ---------- what the pill shows ----------

    private fun showCharging() = prefs.getBoolean("showCharging", true)
    private fun showMusic() = prefs.getBoolean("showMusic", true)

    private fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE

    private fun applyVisibility() {
        if (pill == null) return
        val qsOn = qsActive()
        val inner = targetInnerH()
        if (qsOn != lastQs || inner != lastInner) {
            lastQs = qsOn
            lastInner = inner
            animateSize()
        }
        qsGrid.visibility = vis(qsOn)
        if (qsOn) {
            for (v in listOf<View>(miniText, icon, textCol, btnPrev, btnPlay, btnNext, barsRow, btnAnswer, btnDecline) + chips) {
                v.visibility = View.GONE
            }
            refreshQsStates()
            return
        }
        val widgetOn = widgetActive()
        widgetImage.visibility = vis(widgetOn)
        if (widgetOn) {
            for (v in listOf<View>(miniText, icon, textCol, btnPrev, btnPlay, btnNext, barsRow, btnAnswer, btnDecline) + chips) {
                v.visibility = View.GONE
            }
            return
        }
        icon.visibility = vis(expanded)
        textCol.visibility = vis(expanded)

        val swOn = stopwatchStart > 0
        val chargeMini = !callActive && !showingNotif && controller == null && charging && showCharging()
        miniText.visibility = vis(!expanded && (callActive || swOn || chargeMini))
        miniText.text = when {
            callActive -> "Incoming"
            swOn -> "SW " + fmt(stopwatchElapsed())
            else -> "\u26A1 $chargePct%"
        }
        miniText.setTextColor(if (!callActive && !swOn && lastTempC >= 42.0) 0xFFFF9F0A.toInt() else Color.WHITE)

        val mediaOn = expanded && !callActive && !showingNotif && controller != null && showMusic()
        btnPrev.visibility = vis(mediaOn)
        btnPlay.visibility = vis(mediaOn)
        btnNext.visibility = vis(mediaOn)
        barsRow.visibility = vis(mediaOn && (micRunning || ambientRunning))

        val replyOn = expanded && !callActive && showingNotif && replyAction != null
        chips.forEach { it.visibility = vis(replyOn) }

        btnAnswer.visibility = vis(expanded && callActive && callAnswer != null)
        btnDecline.visibility = vis(expanded && callActive && callHangup != null)
    }

    private fun fmt(ms: Long): String {
        val sec = ms / 1000
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    // Moves the progress bar and shows "elapsed / total" while music plays
    private fun updateProgress() {
        if (showingNotif || callActive) return
        val c = controller ?: return
        val md = c.metadata
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: 0L
        if (playing && st != null) {
            val elapsed = SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
            pos += (elapsed * st.playbackSpeed).toLong()
        }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        if (dur > 0) {
            pos = pos.coerceIn(0L, dur)
            progress.visibility = View.VISIBLE
            progress.progress = (pos * 1000 / dur).toInt()
            subtitle.text = if (artist.isEmpty()) "${fmt(pos)} / ${fmt(dur)}"
                else "$artist \u00B7 ${fmt(pos)} / ${fmt(dur)}"
        } else {
            progress.visibility = View.GONE
        }
    }

    // Visualizer style bars. They move while music plays; they don't react to the actual sound.
    private fun animateBars() {
        for (b in bars) {
            val h = dp(4) + kotlin.random.Random.nextInt(dp(14))
            b.layoutParams = LinearLayout.LayoutParams(dp(3), h).apply { marginStart = dp(2) }
            b.requestLayout()
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (pill != null) {
                updateProgress()
                if (stopwatchStart > 0 || lastQs) applyVisibility()
                syncMic()
                if (++tickCount % 4 == 0) checkFullscreen()
                handler.postDelayed(this, 500)
            }
        }
    }

    private fun runTestPill() {
        if (pill == null) return
        title.text = "Test song"
        subtitle.text = "Test artist"
        icon.setImageDrawable(appIcon(packageName))
        showingNotif = true
        notifIntent = null
        replyAction = null
        setExpanded(true)
        handler.removeCallbacks(collapse)
        if (!callActive) handler.postDelayed(collapse, 6000)
    }

    private fun refreshMedia() {
        if (pill == null) return
        if (callActive) {
            title.text = callTitle
            subtitle.text = callSubtitle
        } else if (!showingNotif) {
            renderMedia()
        }
        applyVisibility()
    }

    private fun renderMedia() {
        val c = if (showMusic()) controller else null
        if (c == null) {
            if (charging && showCharging()) {
                title.text = "$chargePct% \u00B7 Charging"
                title.setTextColor(if (lastTempC >= 42.0) 0xFFFF9F0A.toInt() else Color.WHITE)
                subtitle.text = chargeDetail
            } else {
                title.text = ""
                subtitle.text = ""
            }
            icon.setImageDrawable(null)
        } else {
            val md = c.metadata
            title.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
            subtitle.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (art != null) {
                icon.setImageBitmap(art)
            } else {
                icon.setImageDrawable(appIcon(c.packageName))
            }
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            btnPlay.setImageResource(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        }
    }

    private fun pickController(list: List<MediaController>?) {
        controller?.unregisterCallback(controllerCallback)
        val next = if (!showMusic()) null else (
            list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list?.firstOrNull()
            )
        controller = next
        next?.registerCallback(controllerCallback)
        refreshMedia()
        if (next == null && !showingNotif && !callActive) setExpanded(false)
    }

    private fun appIcon(pkg: String) =
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()

    // ---------- calls ----------

    private fun showCall(sbn: StatusBarNotification) {
        boostViz()
        val n = sbn.notification
        val actions: Array<Notification.Action> = n.actions ?: emptyArray()
        callKey = sbn.key
        callActive = true
        if (actions.size >= 2) {
            callAnswer = actions[0]
            callHangup = actions[1]
        } else {
            callAnswer = null
            callHangup = actions.firstOrNull()
        }
        callTitle = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Call"
        callSubtitle = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "Incoming call"
        notifIntent = n.contentIntent
        showingNotif = false
        icon.setImageDrawable(appIcon(sbn.packageName))
        handler.removeCallbacks(collapse)
        setExpanded(true)
        refreshMedia()
    }

    // ---------- notifications ----------

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (root == null) {
            diag("notif from ${sbn.packageName} ignored: overlay not running")
            return
        }
        if (sbn.packageName == packageName) return
        if (sbn.packageName in blockedApps()) return
        val n = sbn.notification
        boostViz()

        // Calls come first and always show
        if (n.category == Notification.CATEGORY_CALL) {
            handler.post { showCall(sbn) }
            return
        }

        val isMaps = sbn.packageName == CapsulePrefs.MAPS_PKG
        if (isMaps) mapsKeys.add(sbn.key)
        // Driving mode: while Maps is navigating, other notifications stay hidden
        if (!isMaps && mapsKeys.isNotEmpty() && prefs.getBoolean("drivingMode", false)) return

        // Download and upload progress (notifications that report a percentage)
        if (n.category == Notification.CATEGORY_PROGRESS && prefs.getBoolean("showDownloads", true)) {
            val max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val cur = n.extras.getInt(Notification.EXTRA_PROGRESS, 0)
            val indet = n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
            val name = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Download"
            val pct = if (max > 0) cur * 100 / max else -1
            handler.post { showDownload(sbn.key, name, pct, indet) }
            return
        }

        if (sbn.isOngoing && !isMaps) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (isMaps && !prefs.getBoolean("showMaps", true)) return
        if (!isMaps && !prefs.getBoolean("showNotifs", true)) return

        val t = n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: return
        val body = n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""

        val debugMaps = prefs.getBoolean("debugMaps", false)
        if (isMaps) {
            val key = "$t|$body"
            if (key == lastMapsText && !debugMaps) return
            lastMapsText = key
            if (debugMaps) android.util.Log.d("CapsuleMaps", "title=[$t] body=[$body]")
        }

        if (prefs.getBoolean("historyOn", false)) recordHistory(sbn.packageName, t.toString(), body.toString())
        val reply = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }

        handler.post {
            if (callActive) return@post   // a call always stays on top
            showingNotif = true
            notifIntent = n.contentIntent
            notifKey = sbn.key
            replyAction = reply
            title.text = if (isMaps && debugMaps) "DEBUG: $t" else t
            applyNotifAccent(n.color)
            subtitle.text = body
            icon.setImageDrawable(appIcon(sbn.packageName))
            refreshMedia()
            val ms = when {
                isMaps && debugMaps -> 10000L
                reply != null -> 8000L
                else -> 4000L
            }
            peek(ms)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == CapsulePrefs.MAPS_PKG) mapsKeys.remove(sbn.key)
        handler.post {
            if (sbn.key == downloadKey) {
                downloadKey = null
                handler.removeCallbacks(collapse)
                collapse.run()
            }
            if (sbn.key == callKey) {
                callActive = false
                callKey = null
                callAnswer = null
                callHangup = null
                handler.removeCallbacks(collapse)
                collapse.run()
            }
            if (sbn.key == notifKey) {
                notifKey = null
                replyAction = null
                if (!callActive && showingNotif) {
                    handler.removeCallbacks(collapse)
                    collapse.run()
                }
            }
        }
    }

    private fun sendReply(text: String) {
        val action = replyAction ?: return
        val ri = action.remoteInputs?.firstOrNull() ?: return
        val intent = Intent()
        val bundle = Bundle().apply { putCharSequence(ri.resultKey, text) }
        RemoteInput.addResultsToIntent(arrayOf(ri), intent, bundle)
        runCatching { action.actionIntent.send(this, 0, intent) }
        handler.removeCallbacks(collapse)
        collapse.run()
    }

    // ---------- phone events (unlock, headphones, Bluetooth) ----------

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (!prefs.getBoolean("showEvents", true)) return
            // Headset state is sticky: ignore the copy Android sends when we first register
            if (Build.VERSION.SDK_INT >= 33 && isInitialStickyBroadcast) return
            val msg = when (intent.action) {
                Intent.ACTION_USER_PRESENT -> "Unlocked"
                Intent.ACTION_HEADSET_PLUG ->
                    if (intent.getIntExtra("state", 0) == 1) "Headphones connected" else "Headphones disconnected"
                BluetoothDevice.ACTION_ACL_CONNECTED -> "Bluetooth connected"
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> "Bluetooth disconnected"
                else -> return
            }
            handler.post { showEvent(msg) }
        }
    }

    private fun registerEvents() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, eventReceiver, f, ContextCompat.RECEIVER_EXPORTED)
    }

    // Shows a download's name and percentage in the pill until its notification is removed
    private fun showDownload(key: String, name: String, pct: Int, indet: Boolean) {
        if (pill == null || callActive) return
        downloadKey = key
        title.text = name
        subtitle.text = if (indet || pct < 0) "In progress" else "$pct%"
        icon.setImageDrawable(null)
        showingNotif = true
        notifIntent = null
        replyAction = null
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = indet || pct < 0
        if (!indet && pct >= 0) progress.progress = pct * 10
        handler.removeCallbacks(collapse)
        setExpanded(true)
        applyVisibility()
    }

    // Shows the time and date in the pill for a few seconds
    private fun showClock() {
        if (pill == null || callActive) return
        val now = java.util.Date()
        title.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
        subtitle.text = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(now)
        icon.setImageDrawable(null)
        showingNotif = true
        notifIntent = null
        replyAction = null
        setExpanded(true)
        handler.removeCallbacks(collapse)
        handler.postDelayed(collapse, 4000)
    }

    // Shows a short message in the pill, then returns to the normal state
    private fun showEvent(msg: String) {
        boostViz()
        if (pill == null || callActive) return
        title.text = msg
        subtitle.text = ""
        icon.setImageDrawable(null)
        showingNotif = true
        notifIntent = null
        replyAction = null
        setExpanded(true)
        handler.removeCallbacks(collapse)
        handler.postDelayed(collapse, 2500)
    }

    // ---------- charging ----------

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = handleBattery(intent)
    }

    private fun registerBattery() {
        val sticky = ContextCompat.registerReceiver(
            this, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        sticky?.let { handleBattery(it) }
    }

    // Lists every entry under /sys/class/power_supply with its type and raw current (for Diagnostics)
    private fun powerSupplyCurrents(): List<String> {
        val dir = File("/sys/class/power_supply")
        val names = dir.list()?.toList() ?: return emptyList()
        return names.mapNotNull { name ->
            val base = File(dir, name)
            val type = runCatching { File(base, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            val cur = runCatching { File(base, "current_now").readText().trim() }.getOrNull()
            "$name type=$type current=${cur ?: "unreadable"}"
        }
    }

    // Current in microamps, or null if no method gave a real (non-zero) value
    private fun readCurrentUa(): Int? {
        val bm = getSystemService(BatteryManager::class.java)
        val fromApi = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (fromApi != Int.MIN_VALUE && fromApi != 0) return fromApi

        // Try every battery-type supply, not just "battery"
        val dir = File("/sys/class/power_supply")
        dir.list()?.forEach { name ->
            val base = File(dir, name)
            val type = runCatching { File(base, "type").readText().trim() }.getOrNull()
            if (type != "Battery") return@forEach
            val v = runCatching { File(base, "current_now").readText().trim().toInt() }.getOrNull()
            if (v != null && v != 0) return v
        }
        return null
    }

    private fun handleBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        lastTempC = tempC

        charging = plugged != 0 &&
            (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        chargePct = level * 100 / scale
        if (!charging) {
            chargeAlerted = false
            fullAlerted = false
        } else if (prefs.getBoolean("chargeAlert", true)) {
            if (chargePct >= prefs.getInt("chargeAlertAt", 80) && !chargeAlerted) {
                chargeAlerted = true
                handler.post { showEvent("Reached $chargePct%") }
            }
            if (status == BatteryManager.BATTERY_STATUS_FULL && !fullAlerted) {
                fullAlerted = true
                handler.post { showEvent("Fully charged") }
            }
        }

        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "Charger"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Charging"
        }

        val parts = mutableListOf(source)
        val currentUa = readCurrentUa()
        if (currentUa != null) {
            val mA = Math.abs(currentUa) / 1000
            val watts = Math.abs(voltageMv.toDouble() * currentUa.toDouble()) / 1e9
            parts.add("$mA mA")
            parts.add("%.1f W".format(watts))
        }
        parts.add("%.2f V".format(voltageMv / 1000.0))
        parts.add("%.1f\u00B0C".format(tempC))
        val ttf = getSystemService(BatteryManager::class.java).computeChargeTimeRemaining()
        if (ttf > 0) parts.add("full in ${ttf / 60000} min")
        chargeDetail = parts.joinToString(" \u00B7 ")
        diag("battery: charging=$charging pct=$chargePct")

        refreshMedia()
    }
}
