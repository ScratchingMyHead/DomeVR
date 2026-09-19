package net.domevr.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * VR player + in-headset browser.
 * Root level lists every SMB connection plus "This device".
 * A ⚙ Settings page exposes projection/stereo/FOV/IPD/zoom/dwell
 * without leaving VR. SMB files stream via the localhost Range proxy
 * (no download); local files play direct.
 */
class VrPlayerActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        const val EXTRA_URL = "url"          // http proxy URL (SMB) or file:// URI (local)
        const val EXTRA_NAME = "name"
        const val EXTRA_PROJ = "proj"
        const val EXTRA_STEREO = "stereo"
        const val EXTRA_CONN_ID = "conn_id"  // smb:<id> | local:<abs path> | "" = root
        const val EXTRA_PATH = "path"
        const val EXTRA_QUEUE_PATHS = "queue_paths"  // sibling files for prev/next
        const val EXTRA_QUEUE_INDEX = "queue_index"
        private const val TAG = "DomeVR"
    }

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var txtStatus: TextView
    private var player: ExoPlayer? = null
    private lateinit var settings: SettingsStore
    private var connections: List<SmbConnection> = emptyList()

    // browser state
    private sealed interface Loc {
        data object Root : Loc
        data class Smb(val connId: String, val path: String) : Loc
        data class Local(val dir: File) : Loc
        data object SettingsPage : Loc
        data object Sensors : Loc
    }
    private var loc: Loc = Loc.Root
    // True when the settings page was opened from the in-video play menu:
    // backing out resumes the video instead of leaving to the servers.
    private var settingsFromVideo = false
    private var rows: List<Row> = emptyList()
    private data class Row(
        val label: String, val meta: String, val kind: Int,
        val smb: SmbEntry? = null, val local: File? = null,
        val action: String? = null, // for settings rows
        val slideKey: String? = null, // gaze slider id (settings rows)
        val slideMin: Float = 0f, val slideMax: Float = 1f, val slideVal: Float = 0f
    )

    private var connId: String = ""
    private var playUrl: String? = null
    private var playIsProxy = false

    private lateinit var sensors: SensorManager
    private var rotSensor: Sensor? = null
    private var useGameRv = false
    private var cmpSensor: Sensor? = null // A/B: second source for comparison
    private var oriLogCountdown = 0
    // Raw environment sensors for the debug overlay (snapshots, sensor thread).
    private var gyroSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var magSensor: Sensor? = null
    @Volatile private var lastGyro = FloatArray(3)
    @Volatile private var lastAccel = FloatArray(3)
    @Volatile private var lastMag = FloatArray(3)
    @Volatile private var lastTrackM: FloatArray? = null
    private val sensorTick = android.os.Handler(android.os.Looper.getMainLooper())
    private var sensorTicking = false
    // Yaw-direction baseline captured on first debug-page refresh.
    private var yawBaseG: Int? = null
    private var yawBaseR: Int? = null
    private var yawBaseC: Int? = null
    // TURNDET baselines (page-open): gyro-integrated yaw + rendered yaw.
    private var turnBaseG: Double? = null
    private var turnBaseR: Double? = null
    // Flight recorder: raw sensor matrix + rendered forward per event.
    // File: getFilesDir()/trace-<ts>.csv. Open VR, turn head left-right once,
    // nod once, exit — then pull the file for analysis.
    private var traceQueue: java.util.concurrent.LinkedBlockingQueue<String>? = null
    private var traceThread: Thread? = null
    private var traceCount = 0
    private var traceFullLogged = false
    private val TRACE_MAX_LINES = 12000

    private fun startTrace() {
        try {
            val f = java.io.File(filesDir, "trace-${System.currentTimeMillis()}.csv")
            val q: java.util.concurrent.LinkedBlockingQueue<String> =
                java.util.concurrent.LinkedBlockingQueue()
            traceQueue = q
            traceCount = 0
            traceFullLogged = false
            q.put("t_ns," + (0..15).joinToString(",") { "r$it" } + ",fwdx,fwdy,fwdz," + (0..15).joinToString(",") { "c$it" } + ",gx,gy,gz,ax,ay,az\n")
            traceThread = kotlin.concurrent.thread(name = "domevr-trace", isDaemon = true) {
                try {
                    f.bufferedWriter().use { w ->
                        while (true) {
                            val line = q.take()
                            if (line == "EOF") break
                            w.write(line)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "trace: ${e.message}")
                }
            }
            Log.i(TAG, "trace recording to ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "trace start failed: ${e.message}")
        }
    }

    private fun traceEvent(tNs: Long, raw: FloatArray) {
        val q = traceQueue ?: return
        if (traceCount >= TRACE_MAX_LINES) {
            if (!traceFullLogged) {
                traceFullLogged = true
                Log.w(TAG, "trace full ($TRACE_MAX_LINES lines), stopping")
            }
            return
        }
        traceCount++
        val fwd = renderer.lastEffFwd
        val cmp = cmpListener.lastRaw
        val g = lastGyro
        val ac = lastAccel
        val sb = StringBuilder(480)
        sb.append(tNs)
        for (v in raw) sb.append(',').append(v)
        sb.append(',').append(fwd[0]).append(',').append(fwd[1]).append(',').append(fwd[2])
        if (cmp != null) { for (v in cmp) sb.append(',').append(v) }
        else { for (i in 0..15) sb.append(",NaN") }
        sb.append(',').append(g[0]).append(',').append(g[1]).append(',').append(g[2])
        sb.append(',').append(ac[0]).append(',').append(ac[1]).append(',').append(ac[2])
        sb.append('\n')
        q.offer(sb.toString())
    }

    private fun stopTrace() {
        traceQueue?.offer("EOF")
        traceQueue = null
        traceThread = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLog.init(this)
        FileLog.i(TAG, "DomeVR ${BuildConfig.VERSION_NAME} VrPlayerActivity start")
        val volProof = runCatching {
            val f = VrRenderer::class.java.getDeclaredField("frameAvailable")
            java.lang.reflect.Modifier.isVolatile(f.modifiers)
        }.getOrDefault(false)
        FileLog.i(TAG, "volatileProof frameAvailable=$volProof")
        android.util.Log.i(TAG, "volatileProof frameAvailable=$volProof")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContentView(R.layout.activity_vr)
        settings = SettingsStore(this)
        connections = ConnectionStore(this).load()

        glView = findViewById(R.id.glView)
        txtStatus = findViewById(R.id.txtStatus)
        glView.setEGLContextClientVersion(2)
        renderer = VrRenderer(
            onBrowserActivate = { idx, frac -> runOnUiThread { activateRow(idx, frac) } },
            onMenuEvent = { e -> runOnUiThread { handleMenuEvent(e) } }
        )
        applyOptics()
        renderer.projection = runCatching { Projection.valueOf(intent.getStringExtra(EXTRA_PROJ) ?: settings.projection.name) }.getOrDefault(settings.projection)
        renderer.stereo = runCatching { Stereo.valueOf(intent.getStringExtra(EXTRA_STEREO) ?: settings.stereo.name) }.getOrDefault(settings.stereo)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            renderer.lastWidth = v.width.coerceAtLeast(1)
            renderer.lastHeight = v.height.coerceAtLeast(1)
        }
        glView.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                renderer.recenter("tap")
                Toast.makeText(this, "View centered", Toast.LENGTH_SHORT).show()
            }
            true
        }

        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Prefer GAME rotation vector: gyro+accel only, no compass, so viewer
        // magnets / indoor metal can't corrupt yaw (GVR-style fusion behaves
        // the same way). Yaw has no absolute reference and drifts slowly — tap to
        // recenter. Fall back to the full rotation vector if absent.
        rotSensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        useGameRv = rotSensor?.type == Sensor.TYPE_GAME_ROTATION_VECTOR
        // A/B comparison: always listen to the OTHER source too (diagnostics only)
        cmpSensor = if (useGameRv) sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            else sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        gyroSensor = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magSensor = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        findViewById<android.view.View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btnBrowser).setOnClickListener { goBrowser() }
        findViewById<android.view.View>(R.id.btnMode).setOnClickListener { cycleProjection() }

        connId = intent.getStringExtra(EXTRA_CONN_ID) ?: settings.lastConnectionId
        val url = intent.getStringExtra(EXTRA_URL)
        if (url != null) {
            playIsProxy = url.startsWith("http://127.0.0.1")
            // Direct launch (2D Watch play button): rebuild the prev/next
            // queue + remembered folder from the extras, exactly like the
            // in-VR play path does from its listing.
            val qPaths = intent.getStringArrayListExtra(EXTRA_QUEUE_PATHS) ?: arrayListOf()
            val qIndex = intent.getIntExtra(EXTRA_QUEUE_INDEX, -1)
            if (connId.startsWith("local:")) {
                val dir = connId.removePrefix("local:")
                settings.lastConnectionId = connId
                settings.lastPath = ""
                playQueue = qPaths.map { PlayItem.Local(File(it)) }
                playIndex = qIndex
                if (playIndex !in playQueue.indices) {
                    playIndex = playQueue.indexOfFirst {
                        (it as PlayItem.Local).f.absolutePath == File(dir, intent.getStringExtra(EXTRA_NAME) ?: "").absolutePath
                    }
                }
            } else if (connId.isNotEmpty()) {
                val cleanId = connId.removePrefix("smb:")
                val dirPath = intent.getStringExtra(EXTRA_PATH) ?: settings.lastPath
                settings.lastConnectionId = "smb:$cleanId"
                settings.lastPath = dirPath
                playQueue = qPaths.map {
                    PlayItem.Smb(SmbEntry(it.substringAfterLast('\\'), it, false, -1), cleanId)
                }
                playIndex = qIndex
            }
            startPlayback(url, intent.getStringExtra(EXTRA_NAME) ?: "video")
        } else {
            loc = when {
                connId.startsWith("local:") -> Loc.Local(File(connId.removePrefix("local:")))
                connId.startsWith("smb:") -> Loc.Smb(connId.removePrefix("smb:"), intent.getStringExtra(EXTRA_PATH) ?: settings.lastPath)
                connId.isNotEmpty() -> Loc.Smb(connId, intent.getStringExtra(EXTRA_PATH) ?: settings.lastPath)
                else -> Loc.Root
            }
            enterBrowser()
        }
    }

    private fun applyOptics() {
        renderer.fovDeg = settings.fovDeg
        renderer.eyeHalfM = settings.ipdMm / 2000f
        renderer.swapEyes = settings.swapEyes
        renderer.zoom = settings.videoZoom
        renderer.panelDistM = settings.panelDistM
        renderer.dwellMs = settings.dwellMs
        renderer.pinVideo = settings.pinVideo
        renderer.testSweep = settings.testSweep
        renderer.lensK1 = settings.lensK1
        renderer.lensK2 = settings.lensK2
        renderer.menuAngleDeg = settings.menuAngleDeg
        // Convergence: each screen half is its own NDC range, so move each
        // eye's image toward its half-center until the centers sit ipdMm
        // apart. o = 1 - ipd/spacing (NDC units), clamped to stay on-screen.
        val dm = resources.displayMetrics
        val pxPerMm = (dm.xdpi / 25.4f).coerceAtLeast(1f)
        val halfPx = (if (renderer.lastWidth > 100) renderer.lastWidth else dm.widthPixels) / 2f
        val spacingMm = (halfPx / pxPerMm).coerceAtLeast(1f)
        renderer.convShiftNdc = ((1f - settings.ipdMm / spacingMm).coerceIn(-0.5f, 0.5f))
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        applyOptics()
        renderer.resetBasis("entry") // next sensor reading centers the view
        connections = ConnectionStore(this).load()
        glView.onResume()
        rotSensor?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "tracking via ${if (useGameRv) "GAME_ROTATION_VECTOR (no compass)" else "ROTATION_VECTOR"}")
        }
        cmpSensor?.let {
            sensors.registerListener(cmpListener, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "A/B comparison via type=${it.type}")
        }
        gyroSensor?.let { sensors.registerListener(envListener, it, SensorManager.SENSOR_DELAY_GAME) }
        accelSensor?.let { sensors.registerListener(envListener, it, SensorManager.SENSOR_DELAY_GAME) }
        magSensor?.let { sensors.registerListener(envListener, it, SensorManager.SENSOR_DELAY_GAME) }
        startTrace()
        startSensorTick()
        startWatchTick()
    }

    /** 2Hz live refresh + 1Hz logcat mirror while the sensor page is open. */
    private val sensorTickTask = object : Runnable {
        var n = 0
        override fun run() {
            if (!sensorTicking) return
            if (loc == Loc.Sensors && renderer.mode == VrRenderer.Mode.BROWSER) {
                updatePeaks()
                showSensors()
                if (n++ % 2 == 0) logSensors()
            }
            sensorTick.postDelayed(this, 500)
        }
    }

    // Peak-hold telemetry: latch max gyro per axis + max gaze deflection from
    // page-open baseline, so motion can be read AFTER stopping (gyro shows
    // rate = zero when still; Euler rows are gimbal-unreliable in-viewer).
    private var peakGyro = FloatArray(3)
    private var peakBaseFwd: FloatArray? = null
    private var peakGazeDeg = 0.0
    private var peakGazeDir = ""

    private fun resetPeaks() {
        peakGyro = FloatArray(3)
        peakBaseFwd = null
        peakGazeDeg = 0.0
        peakGazeDir = ""
    }

    private fun updatePeaks() {
        val g = lastGyro
        for (i in 0..2) {
            val m = kotlin.math.abs(g[i])
            if (m > peakGyro[i]) peakGyro[i] = m
        }
        val e = renderer.effCopy()
        val f = floatArrayOf(-e[2], -e[6], -e[10])
        val base = peakBaseFwd
        if (base == null) {
            peakBaseFwd = f
            return
        }
        val dot = ((f[0]*base[0] + f[1]*base[1] + f[2]*base[2]) /
            (kotlin.math.sqrt((f[0]*f[0] + f[1]*f[1] + f[2]*f[2]).toDouble()) *
             kotlin.math.sqrt((base[0]*base[0] + base[1]*base[1] + base[2]*base[2]).toDouble()) + 1e-9))
            .coerceIn(-1.0, 1.0)
        val ang = Math.toDegrees(kotlin.math.acos(dot))
        if (ang > peakGazeDeg) {
            peakGazeDeg = ang
            // direction of deflection in screen terms (from baseline forward)
            val yawPart = Math.toDegrees(kotlin.math.atan2((f[0] - base[0]).toDouble(), (-(f[2] - base[2])).toDouble()))
            val pitPart = Math.toDegrees(kotlin.math.asin((f[1] - base[1]).toDouble().coerceIn(-1.0, 1.0)))
            peakGazeDir = (if (yawPart >= 0) "R" else "L") + (if (pitPart >= 0) "+up" else "+dn")
        }
    }

    private fun startSensorTick() {
        if (sensorTicking) return
        sensorTicking = true
        sensorTick.post(sensorTickTask)
    }

    private fun stopSensorTick() {
        sensorTicking = false
        sensorTick.removeCallbacks(sensorTickTask)
        sensorTick.removeCallbacks(watchTickTask)
    }

    // Playback stall watchdog: 2s tick while video plays. Distinguishes
    // BUFFERING-starved (state=BUFFERING, buffered~0 → link too slow) from
    // STUCK-ready (state=READY but position frozen with buffer → decoder/
    // render stall) from clean playback. The money log for freezes.
    // Also re-attaches the video surface if EGL recreated it under us
    // (orphaned surface = frozen picture, advancing position, no errors).
    private var attachedSurfaceGen = -1
    private var lastWatchFrames = 0L
    private var lastWatchConsumed = 0L
    private var lastWatchArrived = 0L
    @Volatile private var lastFrameBatchMs = 0L
    private val watchTickTask = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && renderer.mode == VrRenderer.Mode.VIDEO) {
                if (attachedSurfaceGen != renderer.surfaceGen) {
                    attachedSurfaceGen = renderer.surfaceGen
                    val s = renderer.surface
                    if (s != null) {
                        p.setVideoSurface(s)
                        FileLog.i(TAG, "video surface (re-)attached gen=${renderer.surfaceGen}")
                    }
                }
                val state = when (p.playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?${p.playbackState}"
                }
                val pos = p.currentPosition
                renderer.menuPosMs = pos
                renderer.menuDurMs = p.duration.coerceAtLeast(0)
                renderer.menuPlaying = p.playWhenReady && p.playbackState == Player.STATE_READY
                val buf = (p.bufferedPosition - pos).coerceAtLeast(0)
                val frames = renderer.frameCount
                val fps = frames - lastWatchFrames
                lastWatchFrames = frames
                val consumed = renderer.consumedFrames
                val vfps = consumed - lastWatchConsumed
                lastWatchConsumed = consumed
                val arrived = renderer.arrivedFrames
                val afps = arrived - lastWatchArrived
                lastWatchArrived = arrived
                // Splitter signals: is the video track still selected, what
                // format does the player think is current, is it playing, and
                // how long since the decoder last delivered a frame batch?
                // (offsetAge growing + vfps 0 = decoder stopped feeding;
                //  drops climbing instead = render-side stall.)
                var vidSel = false
                var vidFmt = "none"
                runCatching {
                    for (g in p.currentTracks.groups) {
                        if (!g.isSelected) continue
                        val f = g.getTrackFormat(0)
                        if (f.sampleMimeType?.startsWith("video/") == true) {
                            vidSel = true
                            vidFmt = "${f.sampleMimeType} ${f.width}x${f.height}"
                        }
                    }
                }
                val offsetAge = System.currentTimeMillis() - lastFrameBatchMs
                FileLog.i(TAG, "watch pos=${pos}ms buf=${buf}ms dur=${p.duration}ms " +
                    "state=$state playWhenReady=${p.playWhenReady} glfps~${fps / 2} vfps~${vfps / 2} afps~${afps / 2} " +
                    "vidSel=$vidSel vidFmt=$vidFmt playing=${p.isPlaying} offsetAge=${offsetAge}ms " +
                    "wh=${renderer.lastWidth}x${renderer.lastHeight} lens=${renderer.lensK1},${renderer.lensK2} " +
                    "proj=${renderer.projection} stereo=${renderer.stereo} zoom=${settings.videoZoom}")
            }
            sensorTick.postDelayed(this, 2000)
        }
    }

    private fun startWatchTick() {
        sensorTick.removeCallbacks(watchTickTask)
        sensorTick.post(watchTickTask)
    }

    override fun onPause() {
        glView.onPause()
        sensors.unregisterListener(this)
        cmpSensor?.let { sensors.unregisterListener(cmpListener) }
        sensors.unregisterListener(envListener)
        stopSensorTick()
        stopTrace()
        player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        player?.release(); player = null
        if (playIsProxy) playUrl?.let { StreamProxy.unregisterByUrl(it) }
        super.onDestroy()
    }

    override fun onKeyDown(code: Int, e: KeyEvent?): Boolean {
        // Volume keys are deliberately NOT intercepted: they adjust volume.
        // Gaze dwell is the selector; DPAD/enter covers devices that have it.
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            renderer.tapSelect()
            return true
        }
        return super.onKeyDown(code, e)
    }

    private fun hideSystemBars() {
        val ctrl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        ctrl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------------- browser ----------------
    // Back to the browser, staying in the directory that was open (NOT top
    // level): exiting a video returns to its folder.
    private fun goBrowser() { player?.pause(); enterBrowser() }

    private fun enterBrowser() {
        player?.pause()
        renderer.mode = VrRenderer.Mode.BROWSER
        renderer.resetBasis("files") // panel appears straight ahead
        refresh()
    }

    private fun refresh() {
        when (val l = loc) {
            is Loc.Root -> showRoot()
            is Loc.Smb -> showSmb(l.connId, l.path)
            is Loc.Local -> showLocal(l.dir)
            is Loc.SettingsPage -> showSettings()
            is Loc.Sensors -> showSensors()
        }
    }

    private fun pushRows(title: String, status: String, r: List<Row>) {
        rows = r
        renderer.browserTitle = title
        renderer.browserRows = r.map {
            VrRenderer.BrowserRow(it.label, it.meta, it.kind, it.slideKey, it.slideMin, it.slideMax, it.slideVal)
        }
        txtStatus.text = status
    }

    private fun showRoot() {
        val r = mutableListOf<Row>()
        for (c in connections) {
            r += Row(c.label, "${c.unc} • ${if (c.username.isBlank()) "guest" else c.username}",
                VrRenderer.BrowserRow.FOLDER, action = "smb:${c.id}")
        }
        r += Row("This device", "phone storage", VrRenderer.BrowserRow.FOLDER, action = "local:")
        r += Row("Settings", currentOpticsSummary(), VrRenderer.BrowserRow.ACTION, action = "settings:")
        r += Row("Sensor debug", "live raw values", VrRenderer.BrowserRow.ACTION, action = "sensors:")
        pushRows("DomeVR", if (connections.isEmpty()) "Add a server in the 2D app, or open This device" else "${connections.size} servers — stare to open", r)
    }

    private fun currentOpticsSummary(): String =
        "${renderer.projection.label} ${renderer.stereo.label} • ${settings.fovDeg.toInt()}° • IPD ${settings.ipdMm.toInt()}mm"

    private fun showSmb(connId: String, path: String) {
        val conn = connections.find { it.id == connId }
        if (conn == null) { loc = Loc.Root; refresh(); return }
        pushRows("${conn.host}/${conn.share} /${path.replace('\\', '/')}", "Listing…",
            listOf(Row("Loading…", "", VrRenderer.BrowserRow.FILE)))
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!SmbHolder.manager.isBoundTo(conn.id)) SmbHolder.manager.connect(conn)
                val list = SmbHolder.manager.list(path)
                val r = mutableListOf<Row>()
                r += Row(".. (up)", if (path.isEmpty()) "back to servers" else "parent folder", VrRenderer.BrowserRow.FOLDER, action = "up:")
                for (e in list) {
                    val kind = if (e.isDir) VrRenderer.BrowserRow.FOLDER
                        else if (e.isVideo()) VrRenderer.BrowserRow.VIDEO else VrRenderer.BrowserRow.FILE
                    val meta = if (e.isDir) "folder" else humanSize(e.size)
                    r += Row(e.name, meta, kind, smb = e)
                }
                withContext(Dispatchers.Main) {
                    if (loc == Loc.Smb(connId, path)) {
                        pushRows("${conn.host}/${conn.share} /${path.replace('\\', '/')}",
                            "${list.size} items • streaming, no download", r)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "smb list failed", t)
                withContext(Dispatchers.Main) {
                    pushRows("Error", "SMB: ${t.message}",
                        listOf(Row(".. (back)", "${t.message?.take(60)}", VrRenderer.BrowserRow.FOLDER, action = "up:")))
                    Toast.makeText(this@VrPlayerActivity, "SMB: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showLocal(dir: File) {
        pushRows("This device /${dir.name}", "Listing…",
            listOf(Row("Loading…", "", VrRenderer.BrowserRow.FILE)))
        lifecycleScope.launch(Dispatchers.IO) {
            val kids = try { LocalFiles.list(dir) } catch (t: Throwable) { emptyList() }
            val r = mutableListOf<Row>()
            val root = LocalFiles.externalRoot()
            r += Row(".. (up)", if (dir == root) "back to servers" else "parent folder", VrRenderer.BrowserRow.FOLDER, action = "up:")
            for (k in kids) {
                val kind = if (k.isDir) VrRenderer.BrowserRow.FOLDER
                    else VrRenderer.BrowserRow.VIDEO
                r += Row(k.name, if (k.isDir) "folder" else humanSize(k.size), kind, local = k.file)
            }
            withContext(Dispatchers.Main) {
                pushRows("This device ${dir.absolutePath.removePrefix(root.absolutePath).ifEmpty { "/" }}",
                    "${kids.size} items", r)
            }
        }
    }

    private fun showSettings() {
        fun on(cur: Boolean) = if (cur) "● on" else ""
        // gaze sliders: dwell anywhere on the bar to jump straight there
        fun slide(label: String, value: String, key: String, min: Float, max: Float, cur: Float) =
            Row(label, value, VrRenderer.BrowserRow.ACTION,
                action = "slide:$key", slideKey = key, slideMin = min, slideMax = max, slideVal = cur)
        val r = mutableListOf(
            Row(".. (back)", if (settingsFromVideo) "resume video" else "back to servers",
                VrRenderer.BrowserRow.FOLDER, action = "settingsback:"),
            Row("Video: 2D", on(renderer.stereo == Stereo.MONO), VrRenderer.BrowserRow.ACTION, action = "setstereo2:MONO"),
            Row("Video: SBS", on(renderer.stereo == Stereo.SBS), VrRenderer.BrowserRow.ACTION, action = "setstereo2:SBS"),
            Row("Video: OU (top/bottom)", on(renderer.stereo == Stereo.OU), VrRenderer.BrowserRow.ACTION, action = "setstereo2:OU"),
            Row("Screen: Flat", on(renderer.projection == Projection.FLAT), VrRenderer.BrowserRow.ACTION, action = "setscreen:FLAT"),
            Row("Screen: Pano 180", on(renderer.projection == Projection.DEG180), VrRenderer.BrowserRow.ACTION, action = "setscreen:DEG180"),
            Row("Screen: Pano 220", on(renderer.projection == Projection.DEG220), VrRenderer.BrowserRow.ACTION, action = "setscreen:DEG220"),
            Row("Screen: Pano 270", on(renderer.projection == Projection.DEG270), VrRenderer.BrowserRow.ACTION, action = "setscreen:DEG270"),
            Row("Screen: Pano 360", on(renderer.projection == Projection.DEG360), VrRenderer.BrowserRow.ACTION, action = "setscreen:DEG360"),
            Row("Lens: Normal", on(renderer.projection != Projection.FISHEYE), VrRenderer.BrowserRow.ACTION, action = "setlens:normal"),
            Row("Lens: Fisheye", on(renderer.projection == Projection.FISHEYE), VrRenderer.BrowserRow.ACTION, action = "setlens:fisheye"),
            slide("Field of view", "${settings.fovDeg.toInt()}°", "fov", 40f, 110f, settings.fovDeg),
            slide("Video size", "${String.format("%.2f", settings.videoZoom)}×", "zoom", 0.3f, 2.5f, settings.videoZoom),
            slide("Eye separation", "${settings.ipdMm.toInt()} mm", "ipd", 40f, 80f, settings.ipdMm),
            slide("Panel distance", "${String.format("%.1f", settings.panelDistM)} m", "panel", 1.2f, 5f, settings.panelDistM),
            slide("Gaze delay", "${settings.dwellMs} ms", "dwell", 400f, 4000f, settings.dwellMs.toFloat()),
            Row("Swap eyes", if (settings.swapEyes) "ON" else "off", VrRenderer.BrowserRow.ACTION, action = "set:swap"),
            Row("Pin video in front", if (settings.pinVideo) "ON (no look-around)" else "off (look-around)",
                VrRenderer.BrowserRow.ACTION, action = "set:pin"),
        )
        pushRows("Video settings", "stare at a bar position to jump there", r)
    }

    /** Live raw sensor readout. Fixed row count; navigation is frozen here
     *  (activateRow ignores everything but Back) so staring can't fire. */
    private fun showSensors() {
        fun f(v: Float) = if (v >= 0) "+${String.format("%.2f", v)}" else String.format("%.2f", v)
        fun ypr(m: FloatArray?): IntArray {
            if (m == null) return intArrayOf(999, 999, 999)
            val o = FloatArray(3)
            SensorManager.getOrientation(m, o)
            return intArrayOf(
                Math.toDegrees(o[0].toDouble()).toInt(),
                Math.toDegrees(o[1].toDouble()).toInt(),
                Math.toDegrees(o[2].toDouble()).toInt()
            )
        }
        fun yprOf(m: FloatArray?): String {
            val d = ypr(m)
            return if (d[0] > 900) "n/a" else "y${d[0]} p${d[1]} r${d[2]}"
        }
        val g = lastGyro
        val ac = lastAccel
        val mg = lastMag
        val gmag = kotlin.math.sqrt((g[0]*g[0] + g[1]*g[1] + g[2]*g[2]).toDouble())
        val amag = kotlin.math.sqrt((ac[0]*ac[0] + ac[1]*ac[1] + ac[2]*ac[2]).toDouble())
        val mmag = kotlin.math.sqrt((mg[0]*mg[0] + mg[1]*mg[1] + mg[2]*mg[2]).toDouble())
        // Turn detector (gimbal-free): gyro-integrated yaw about accel-up vs
        // rendered-forward yaw, both from page-open baselines. A real head
        // turn MUST move both, opposite signs (head-R ⇒ image-L), ~equal
        // magnitude. Head tilt moves NEITHER (correct: no pan on tilt).
        // (Euler yaw rows above are gimbal-unreliable in-viewer; this row
        // and the trace file are the ground truth.)
        val effM = renderer.effCopy()
        val rfx = -effM[2]; val rfz = -effM[10]
        // render yaw measured from recenter-forward; baseline at page open
        val renderYawNow = Math.toDegrees(kotlin.math.atan2(rfx.toDouble(), -rfz.toDouble()))
        if (turnBaseG == null || turnBaseR == null) {
            turnBaseG = yawGyroRelDeg; turnBaseR = renderYawNow
        }
        fun wrap(d: Double): Double {
            var x = d % 360.0
            if (x > 180) x -= 360
            if (x < -180) x += 360
            return x
        }
        val turnRow = run {
            val dg = wrap(yawGyroRelDeg - turnBaseG!!)
            val dr = wrap(renderYawNow - turnBaseR!!)
            val ok = (kotlin.math.abs(dg) < 3 && kotlin.math.abs(dr) < 3) ||
                (kotlin.math.abs(dg) > 8 && ((dg > 0) != (dr > 0)) &&
                    kotlin.math.abs(kotlin.math.abs(dg) - kotlin.math.abs(dr)) <
                        kotlin.math.max(12.0, kotlin.math.abs(dg) * 0.5))
            Row("TURN gyro=${if (dg >= 0) "+" else ""}${dg.toInt()} img=${if (dr >= 0) "+" else ""}${dr.toInt()} ${if (ok) "OK" else "WRONG"}",
                "turn: opposite, ~equal • tilt: both ~0", VrRenderer.BrowserRow.ACTION)
        }
        val r = listOf(
            Row("GYRO ${f(g[0])} ${f(g[1])} ${f(g[2])}", "rad/s — turn head L/R, one axis must swing", VrRenderer.BrowserRow.ACTION),
            Row("GYROmag ${String.format("%.2f", gmag)}", "still=~0, turning=spikes", VrRenderer.BrowserRow.ACTION),
            Row("ACC ${f(ac[0])} ${f(ac[1])} ${f(ac[2])}", "|g|=${String.format("%.1f", amag)} — still=~9.8", VrRenderer.BrowserRow.ACTION),
            Row("MAG ${f(mg[0])} ${f(mg[1])} ${f(mg[2])}", "|m|=${String.format("%.0f", mmag)} — still~=const", VrRenderer.BrowserRow.ACTION),
            Row("GAMErv ${yprOf(lastTrackM)}", if (useGameRv) "tracking source" else "compare source", VrRenderer.BrowserRow.ACTION),
            Row("FULLrv ${yprOf(cmpListener.lastRaw)}", if (useGameRv) "compare source" else "tracking source", VrRenderer.BrowserRow.ACTION),
            Row("RENDER ${yprOf(renderer.effCopy())}", "snaps=${renderer.snapCount} — must follow GAMErv", VrRenderer.BrowserRow.ACTION),
            turnRow,
            Row("PEAK gyro ${String.format("%.2f", peakGyro[0])}/${String.format("%.2f", peakGyro[1])}/${String.format("%.2f", peakGyro[2])}",
                "max rate per axis since page opened", VrRenderer.BrowserRow.ACTION),
            Row("PEAK gaze ${String.format("%.0f", peakGazeDeg)}° $peakGazeDir",
                "max image deflection since page opened", VrRenderer.BrowserRow.ACTION),
            Row(".. (back)", "leave debug page", VrRenderer.BrowserRow.FOLDER, action = "up:")
        )
        pushRows("Sensors — turn head L/R", "nav frozen here • Back exits", r)
    }

    private fun logSensors() {
        val g = lastGyro; val ac = lastAccel; val mg = lastMag
        val e = renderer.effCopy()
        val eo = FloatArray(3); SensorManager.getOrientation(e, eo)
        val ed = eo.map { Math.toDegrees(it.toDouble()).toInt() }
        Log.i("DomeVR-sensors",
            "gyro=${g[0].fmt()}|${g[1].fmt()}|${g[2].fmt()} " +
            "acc=${ac[0].fmt()}|${ac[1].fmt()}|${ac[2].fmt()} " +
            "mag=${mg[0].fmt()}|${mg[1].fmt()}|${mg[2].fmt()} " +
            "render=y${ed[0]}p${ed[1]}r${ed[2]} snaps=${renderer.snapCount}")
    }

    private fun Float.fmt(): String = String.format("%.2f", this)

    private fun activateRow(idx: Int, frac: Float? = null) {
        if (idx !in rows.indices || renderer.mode != VrRenderer.Mode.BROWSER) return
        // Debug page: frozen except Back (up:), so staring at numbers is safe.
        if (loc == Loc.Sensors) {
            if (rows[idx].action == "up:") handleAction("up:")
            return
        }
        val row = rows[idx]
        // gaze slider: one dwell at fraction u sets the value directly
        if (row.slideKey != null && frac != null) {
            handleSlide(row.slideKey, frac)
            return
        }
        val act = row.action
        if (act != null) { handleAction(act); return }
        when (val l = loc) {
            is Loc.Smb -> row.smb?.let { e ->
                if (e.isDir) { loc = Loc.Smb(l.connId, e.path); refresh() }
                else if (e.isVideo()) playSmbFile(e, l.connId)
                else Toast.makeText(this, "Not a playable video", Toast.LENGTH_SHORT).show()
            }
            is Loc.Local -> row.local?.let { f ->
                if (f.isDirectory) { loc = Loc.Local(f); refresh() }
                else playLocalFile(f)
            }
            else -> {}
        }
    }

    /** Gaze-slider set: fraction across the row maps to the key's range,
     *  snapped to its grid. One dwell reaches any value. */
    private fun handleSlide(key: String, frac: Float) {
        val f = frac.coerceIn(0f, 1f)
        when (key) {
            "fov" -> settings.fovDeg = (40f + f * 70f).roundToInt().toFloat().coerceIn(40f, 110f)
            "zoom" -> settings.videoZoom = ((0.3f + f * 2.2f) * 20f).roundToInt() / 20f
            "ipd" -> settings.ipdMm = (40f + f * 40f).roundToInt().toFloat().coerceIn(40f, 80f)
            "panel" -> settings.panelDistM = ((1.2f + f * 3.8f) * 10f).roundToInt() / 10f
            "dwell" -> settings.dwellMs = ((400f + f * 3600f) / 100f).roundToInt() * 100L
        }
        applyOptics(); refresh()
    }

    private fun handleAction(act: String) {
        when {
            act == "up:" -> {
                loc = when (val l = loc) {
                    is Loc.Smb -> if (l.path.isEmpty()) Loc.Root else Loc.Smb(l.connId, l.path.substringBeforeLast('\\', ""))
                    is Loc.Local -> {
                        val root = LocalFiles.externalRoot()
                        if (l.dir == root || l.dir.parentFile == null) Loc.Root else Loc.Local(l.dir.parentFile!!)
                    }
                    else -> Loc.Root
                }
                refresh()
            }
            act == "settings:" -> { settingsFromVideo = false; loc = Loc.SettingsPage; refresh() }
            act == "settingsback:" -> {
                // Back out of settings: resume the video if we came from it,
                // otherwise behave like going up to the server list.
                if (settingsFromVideo && player != null) {
                    settingsFromVideo = false
                    renderer.mode = VrRenderer.Mode.VIDEO
                    renderer.resetBasis("video")
                } else {
                    settingsFromVideo = false
                    loc = Loc.Root
                    refresh()
                }
            }
            act.startsWith("setstereo2:") -> {
                val s = runCatching { Stereo.valueOf(act.removePrefix("setstereo2:")) }.getOrDefault(Stereo.SBS)
                settings.stereo = s; renderer.stereo = s; refresh()
            }
            act.startsWith("setscreen:") -> {
                val p = runCatching { Projection.valueOf(act.removePrefix("setscreen:")) }.getOrDefault(Projection.DEG180)
                settings.screenProj = p; settings.projection = p; renderer.projection = p; refresh()
            }
            act.startsWith("setlens:") -> {
                if (act.removePrefix("setlens:") == "fisheye") {
                    settings.projection = Projection.FISHEYE; renderer.projection = Projection.FISHEYE
                } else {
                    settings.projection = settings.screenProj; renderer.projection = settings.screenProj
                }
                refresh()
            }
            act == "sensors:" -> { loc = Loc.Sensors; yawBaseG = null; yawBaseR = null; yawBaseC = null; turnBaseG = null; turnBaseR = null; resetPeaks(); refresh() }
            act.startsWith("smb:") -> { loc = Loc.Smb(act.removePrefix("smb:"), ""); refresh() }
            act == "local:" -> {
                if (LocalFiles.needsPermission(this)) {
                    Toast.makeText(this, "Allow videos permission in the 2D app first", Toast.LENGTH_LONG).show()
                } else loc = Loc.Local(LocalFiles.externalRoot())
                refresh()
            }
            act == "set:proj" -> { cycleProjection(); refresh() }
            act == "set:stereo" -> {
                renderer.stereo = when (renderer.stereo) { Stereo.MONO -> Stereo.SBS; Stereo.SBS -> Stereo.OU; Stereo.OU -> Stereo.MONO }
                settings.stereo = renderer.stereo; refresh()
            }
            act == "set:swap" -> { settings.swapEyes = !settings.swapEyes; applyOptics(); refresh() }
            act == "set:pin" -> { settings.pinVideo = !settings.pinVideo; applyOptics(); refresh() }
            act.startsWith("adj:") -> {
                val parts = act.split(":")
                val key = parts[1]; val dir = if (parts[2] == "+") 1 else -1
                when (key) {
                    "fov" -> settings.fovDeg = (settings.fovDeg + dir * 2f).coerceIn(40f, 110f)
                    "ipd" -> settings.ipdMm = (settings.ipdMm + dir * 1f).coerceIn(40f, 80f)
                    "zoom" -> settings.videoZoom = (settings.videoZoom + dir * 0.1f).coerceIn(0.3f, 2.5f)
                    "panel" -> settings.panelDistM = (settings.panelDistM + dir * 0.2f).coerceIn(1.2f, 5f)
                    "dwell" -> settings.dwellMs = (settings.dwellMs + dir * 250).coerceIn(400L, 4000L)
                }
                applyOptics(); refresh()
            }
        }
    }

    // ---------------- playback ----------------
    private fun playSmbFile(e: SmbEntry, connId: String) {
        txtStatus.text = "Opening ${e.name}… (streaming, no download)"
        Log.i(TAG, "open smb: ${e.path}")
        // Remember the folder: re-entering VR (or the 2D app) must land
        // back in the directory the video was started from, not top level.
        settings.lastConnectionId = "smb:$connId"
        settings.lastPath = e.path.substringBeforeLast('\\', "")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val probe = SmbHolder.manager.openRead(e.path)
                val size = probe.size
                probe.close()
                Log.i(TAG, "smb probed, size=$size")
                val url = StreamProxy.register(
                    opener = { kotlinx.coroutines.runBlocking { SmbHolder.manager.openRead(e.path) } },
                    size = size,
                    displayName = e.name
                )
                if (playIsProxy) playUrl?.let { StreamProxy.unregisterByUrl(it) }
                playIsProxy = true; playUrl = url
                withContext(Dispatchers.Main) { captureQueueSmb(e, connId); startPlayback(url, e.name) }
            } catch (t: Throwable) {
                Log.e(TAG, "smb open failed", t)
                withContext(Dispatchers.Main) {
                    txtStatus.text = "Open failed: ${t.message}"
                    Toast.makeText(this@VrPlayerActivity, "Open failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun playLocalFile(f: File) {
        if (playIsProxy) playUrl?.let { StreamProxy.unregisterByUrl(it) }
        playIsProxy = false
        playUrl = Uri.fromFile(f).toString()
        settings.lastConnectionId = "local:${f.parentFile?.absolutePath ?: ""}"
        settings.lastPath = ""
        captureQueue(f)
        startPlayback(playUrl!!, f.name)
    }

    // ---------------- play menu ----------------
    /** Sibling videos of the playing file, for prev/next. Captured at play
     *  time from the folder listing (queue = folder as it was opened). */
    private sealed class PlayItem {
        data class Smb(val e: SmbEntry, val connId: String) : PlayItem()
        data class Local(val f: File) : PlayItem()
    }
    private var playQueue: List<PlayItem> = emptyList()
    private var playIndex = -1

    private fun captureQueue(local: File) {
        val sibs = local.parentFile?.listFiles()
            ?.filter { it.isFile && LocalFiles.isVideoFile(it) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (sibs.isNotEmpty()) {
            playQueue = sibs.map { PlayItem.Local(it) }
            playIndex = sibs.indexOfFirst { it.absolutePath == local.absolutePath }
        } else {
            // No listable dir (stepped from a 2D-started queue): keep the
            // existing queue, relocate the new index inside it.
            playIndex = playQueue.indexOfFirst {
                (it as? PlayItem.Local)?.f?.absolutePath == local.absolutePath
            }
        }
    }

    private fun captureQueueSmb(current: SmbEntry, connId: String) {
        val vids = rows.mapNotNull { r ->
            val e = r.smb
            if (r.kind == VrRenderer.BrowserRow.VIDEO && e != null && !e.isDir) PlayItem.Smb(e, connId) else null
        }
        if (vids.isNotEmpty()) {
            playQueue = vids
            playIndex = vids.indexOfFirst { it.e.path == current.path }
        } else {
            // Browser holds no listing (2D-started playback, or stepped
            // mid-video): keep the queue, relocate the new index inside it.
            playIndex = playQueue.indexOfFirst {
                (it as? PlayItem.Smb)?.e?.path == current.path
            }
        }
    }

    private fun handleMenuEvent(e: VrRenderer.MenuEvent) {
        val p = player ?: return
        when (e) {
            is VrRenderer.MenuEvent.Press -> when (e.idx) {
                0 -> stepQueue(-1)
                1 -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                2 -> p.playWhenReady = !p.playWhenReady
                3 -> {
                    val d = p.duration.coerceAtLeast(0)
                    p.seekTo((p.currentPosition + 10_000).coerceAtMost(d))
                }
                4 -> stepQueue(1)
                5 -> {
                    // 3D settings page over the video (player keeps running,
                    // so zoom/FOV/type changes preview live on return).
                    // Backing out resumes the video in place.
                    settingsFromVideo = true
                    loc = Loc.SettingsPage
                    renderer.mode = VrRenderer.Mode.BROWSER
                    renderer.resetBasis("settings")
                    refresh()
                }
                6 -> audioManager().adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE,
                    android.media.AudioManager.FLAG_SHOW_UI)
                7 -> audioManager().adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.FLAG_SHOW_UI)
                8 -> { settings.videoZoom = (settings.videoZoom - 0.1f).coerceIn(0.3f, 2.5f); applyOptics() }
                9 -> { settings.videoZoom = (settings.videoZoom + 0.1f).coerceIn(0.3f, 2.5f); applyOptics() }
                10 -> enterBrowser() // 3D file browser, staying in the open folder
            }
            is VrRenderer.MenuEvent.Seek -> {
                val d = p.duration.coerceAtLeast(0)
                if (d > 0) p.seekTo((e.frac.coerceIn(0f, 1f) * d).toLong())
            }
        }
    }

    private fun audioManager(): android.media.AudioManager =
        getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    private fun stepQueue(dir: Int) {
        if (playQueue.isEmpty() || playIndex < 0) {
            renderer.flashMenu("No folder queue")
            return
        }
        val n = (playIndex + dir).coerceIn(0, playQueue.size - 1)
        if (n == playIndex) {
            renderer.flashMenu(if (dir < 0) "First file" else "Last file")
            return
        }
        playIndex = n
        when (val it = playQueue[n]) {
            is PlayItem.Smb -> playSmbFile(it.e, it.connId)
            is PlayItem.Local -> playLocalFile(it.f)
        }
    }

    private var playerSwDecode: Boolean? = null
    private fun startPlayback(url: String, name: String) {
        renderer.mode = VrRenderer.Mode.VIDEO
        renderer.resetBasis("video") // video centers on wherever you're looking
        // Decoder preference is baked at player build time; rebuild if the
        // user flipped it since (applies to videos opened after changing).
        if (player == null || playerSwDecode != settings.softwareDecode) {
            try { player?.release() } catch (_: Exception) {}
            player = null
            playerSwDecode = settings.softwareDecode
            attachedSurfaceGen = -1 // force surface attach for the new player
        }
        if (player == null) {
            // Buffering sized for the 256MB Java heap: the old 300MB/180s
            // target could retain more samples than the heap holds (8K
            // HEVC fills it in seconds on fast storage) and OOM-killed
            // playback — a consistent crash other players don't have.
            // 60s covers SMB jitter; the 90MB byte backstop keeps total
            // retention inside the heap with headroom. Time thresholds
            // still take priority so audio-front-loaded files can't
            // strand the video track.
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
                .setTargetBufferBytes(90 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val preferSw = settings.softwareDecode
            val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(this)
                .setMediaCodecSelector(
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, requiresSecure, requiresTunnel ->
                        val all = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                            .getDecoderInfos(mimeType, requiresSecure, requiresTunnel)
                        val sorted = if (preferSw) {
                            all.sortedBy { info ->
                                val n = info.name
                                if (n.startsWith("OMX.google.") || n.startsWith("c2.android.")) 0 else 1
                            }
                        } else all
                        FileLog.i(TAG, "decoder pick: ${sorted.firstOrNull()?.name} for $mimeType (sw=$preferSw)")
                        sorted
                    })
            player = ExoPlayer.Builder(this, renderers)
                .setLoadControl(loadControl)
                .build().also { exo ->
                exo.playWhenReady = true
                // Engine-verbose internals to logcat (pull with logcat -d):
                // decoder init/release, formats, rendered/dropped counters.
                exo.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        FileLog.e(TAG, "player error: ${error.message}", error)
                        txtStatus.text = "Playback error: ${error.errorCodeName} — ${error.message?.take(120)}"
                        Toast.makeText(this@VrPlayerActivity, "Playback error: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        // Codec/resolution/bitrate once known — rules out
                        // decoder-limit issues (e.g. exotic codec, 8K, AV1).
                        val sb = StringBuilder("tracks")
                        for (g in tracks.groups) {
                            if (!g.isSelected) continue
                            val f = g.getTrackFormat(0)
                            sb.append(" [${f.sampleMimeType} ${f.width}x${f.height}" +
                                " br=${f.bitrate} codecs=${f.codecs}]")
                            // Size the decoder output buffers to the video:
                            // without this the SurfaceTexture can run small
                            // buffers and the picture upscales from mush.
                            if ((f.sampleMimeType ?: "").startsWith("video/") &&
                                f.width > 0 && f.height > 0
                            ) {
                                try {
                                    renderer.surfaceTexture?.setDefaultBufferSize(f.width, f.height)
                                    FileLog.i(TAG, "video buffer size ${f.width}x${f.height}")
                                } catch (t: Throwable) {
                                    FileLog.w(TAG, "buffer size failed: ${t.message}")
                                }
                            }
                        }
                        Log.i(TAG, sb.toString())
                        FileLog.i(TAG, sb.toString())
                    }
                })
                exo.addAnalyticsListener(object : AnalyticsListener {
                    override fun onLoadError(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                        error: IOException,
                        wasCanceled: Boolean
                    ) {
                        // HTTP-level failures (404/416/timeouts/resets) surface here,
                        // NOT in onPlayerError — this is the money log for freezes.
                        FileLog.w(TAG, "loadError uri=${loadEventInfo.uri} " +
                            "bytes=${loadEventInfo.bytesLoaded} " +
                            "loadMs=${loadEventInfo.loadDurationMs} " +
                            "canceled=$wasCanceled err=${error.message}")
                    }
                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        // Climbing drops with a healthy buffer = the decoder is
                        // fine but frames never reach eyes (render-side stall).
                        FileLog.w(TAG, "droppedVideoFrames=$droppedFrames elapsedMs=$elapsedMs")
                    }
                    override fun onVideoFrameProcessingOffset(
                        eventTime: AnalyticsListener.EventTime,
                        totalProcessingOffsetUs: Long,
                        frameCount: Int
                    ) {
                        // Heartbeat: fires per batch of frames the decoder
                        // delivers to the video renderer. Timestamp it always;
                        // the watchdog reports offsetAge (growing = decoder
                        // stopped feeding, the freeze signature).
                        lastFrameBatchMs = System.currentTimeMillis()
                        // Growing offset = renderer falling behind wall clock.
                        if (frameCount > 0 && totalProcessingOffsetUs > 500_000L * frameCount) {
                            FileLog.w(TAG, "frameProcessingOffset avg=" +
                                "${totalProcessingOffsetUs / frameCount}us over $frameCount frames")
                        }
                    }
                    override fun onRenderedFirstFrame(
                        eventTime: AnalyticsListener.EventTime,
                        output: Any,
                        renderTimeMs: Long
                    ) {
                        FileLog.i(TAG, "renderedFirstFrame")
                        lastFrameBatchMs = System.currentTimeMillis()
                    }
                    override fun onVideoSizeChanged(
                        eventTime: AnalyticsListener.EventTime,
                        videoSize: androidx.media3.common.VideoSize
                    ) {
                        FileLog.i(TAG, "videoSize ${videoSize.width}x${videoSize.height}")
                    }
                    override fun onVideoEnabled(
                        eventTime: AnalyticsListener.EventTime,
                        decoderCounters: androidx.media3.exoplayer.DecoderCounters
                    ) {
                        FileLog.i(TAG, "videoEnabled")
                    }
                    override fun onVideoDisabled(
                        eventTime: AnalyticsListener.EventTime,
                        decoderCounters: androidx.media3.exoplayer.DecoderCounters
                    ) {
                        FileLog.w(TAG, "videoDisabled rendered=${decoderCounters.renderedOutputBufferCount} " +
                            "dropped=${decoderCounters.droppedBufferCount} " +
                            "skippedOut=${decoderCounters.skippedOutputBufferCount}")
                    }
                })
            }
            lifecycleScope.launch {
                for (i in 1..100) {
                    val s = renderer.surface
                    if (s != null) { withContext(Dispatchers.Main) { player?.setVideoSurface(s) }; break }
                    kotlinx.coroutines.delay(100)
                }
            }
        }
        Log.i(TAG, "play: $url")
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        txtStatus.text = "▶ $name  •  ${renderer.projection.label} ${renderer.stereo.label}"
    }

    private fun cycleProjection() {
        val order = listOf(Projection.FLAT, Projection.DEG180, Projection.DEG220, Projection.DEG270, Projection.DEG360, Projection.FISHEYE)
        renderer.projection = order[(order.indexOf(renderer.projection) + 1) % order.size]
        settings.projection = renderer.projection
        txtStatus.text = "${renderer.projection.label} • ${renderer.stereo.label}"
    }

    // Live rotation matrices on this stack arrive TRANSPOSED vs the AOSP
    // documented convention (proven: column-2, not row-2, equals accel-up at
    // +1.000 over 10k still samples). Transpose once on receipt so every
    // downstream consumer (basis derivation, view composition, Euler logs,
    // trace) sees docs-convention device→world matrices.
    private val trackFixed = FloatArray(16)
    private val cmpFixed = FloatArray(16)

    private val cmpListener = object : SensorEventListener {
        var lastRaw: FloatArray? = null
        override fun onSensorChanged(e: SensorEvent) {
            val raw = FloatArray(16)
            SensorManager.getRotationMatrixFromVector(raw, e.values)
            android.opengl.Matrix.transposeM(cmpFixed, 0, raw, 0)
            lastRaw = cmpFixed.clone()
        }
        override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit
    }

    /** Raw gyro/accel/mag snapshot tap for the sensor debug page. Also
     *  integrates gyro yaw about accel-up (gimbal-free turn ground truth). */
    @Volatile private var yawGyroRelDeg = 0.0
    private var lastGyroTNs = 0L
    private val envListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    lastGyro = floatArrayOf(e.values[0], e.values[1], e.values[2])
                    // integrate yaw rate about accel-up for the TURNDET row;
                    // reject free-fall/handling spikes
                    val a = lastAccel
                    val an = kotlin.math.sqrt((a[0]*a[0] + a[1]*a[1] + a[2]*a[2]).toDouble())
                    if (an in 7.0..13.0 && lastGyroTNs != 0L) {
                        val dt = (e.timestamp - lastGyroTNs) / 1e9
                        if (dt in 0.0..0.5) {
                            val rate = (e.values[0]*a[0] + e.values[1]*a[1] + e.values[2]*a[2]) / an
                            yawGyroRelDeg += Math.toDegrees(rate * dt)
                        }
                    }
                    lastGyroTNs = e.timestamp
                }
                Sensor.TYPE_ACCELEROMETER -> lastAccel = floatArrayOf(e.values[0], e.values[1], e.values[2])
                Sensor.TYPE_MAGNETIC_FIELD -> lastMag = floatArrayOf(e.values[0], e.values[1], e.values[2])
            }
        }
        override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit
    }

    // ---------------- head tracking ----------------
    // Pass the RAW rotation matrix. The renderer derives the screen frame
    // from gravity at recenter time, so any phone/viewer orientation works.
    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            e.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        val v = FloatArray(5)
        System.arraycopy(e.values, 0, v, 0, minOf(e.values.size, 5))
        val raw = FloatArray(16)
        SensorManager.getRotationMatrixFromVector(raw, v)
        // See trackFixed note above: transpose to docs convention first.
        android.opengl.Matrix.transposeM(trackFixed, 0, raw, 0)
        renderer.setHeadMatrix(trackFixed)
        lastTrackM = trackFixed.clone()
        traceEvent(e.timestamp, trackFixed)
        // 1Hz orientation diagnostic for tracking issues (logcat -s DomeVR-ori).
        // All matrices here are post-transpose (docs convention), so Euler
        // angles and up-rows are meaningful (no gimbal games beyond normal).
        if (oriLogCountdown-- <= 0) {
            oriLogCountdown = 50
            val o = FloatArray(3)
            SensorManager.getOrientation(trackFixed, o)
            val cmp = cmpListener.lastRaw
            var cmpStr = "none"
            if (cmp != null) {
                val c = FloatArray(3)
                SensorManager.getOrientation(cmp, c)
                // structural agreement: angle between the two "up" rows
                val dot = (trackFixed[2]*cmp[2] + trackFixed[6]*cmp[6] + trackFixed[10]*cmp[10])
                cmpStr = "yaw=${Math.toDegrees(c[0].toDouble()).toInt()} " +
                    "pitch=${Math.toDegrees(c[1].toDouble()).toInt()} " +
                    "roll=${Math.toDegrees(c[2].toDouble()).toInt()} " +
                    "upAgree=${String.format("%.3f", dot)}"
            }
            Log.i("DomeVR-ori", "src=${if (useGameRv) "game" else "full"} " +
                "yaw=${Math.toDegrees(o[0].toDouble()).toInt()} " +
                "pitch=${Math.toDegrees(o[1].toDouble()).toInt()} " +
                "roll=${Math.toDegrees(o[2].toDouble()).toInt()} " +
                "upxy=(${String.format("%.2f", trackFixed[2])},${String.format("%.2f", trackFixed[6])}) " +
                "vsOther=[$cmpStr]")
        }
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit

    private fun humanSize(n: Long): String {
        if (n < 1024) return "$n B"
        val kb = n / 1024.0
        if (kb < 1024) return String.format("%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
