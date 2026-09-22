/* DomeVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.domevr.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.util.Locale
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Stereo renderer: split-screen left/right with head-tracked view.
 *
 * VIDEO: ExoPlayer frame (OES texture) on plane / sphere segment.
 * BROWSER: Canvas-rendered file/settings list on a world-locked panel.
 *
 * Gaze is a real head-forward ray intersected with the panel plane, so the
 * head-locked reticle actually hovers rows. Staring [dwellMs] activates.
 * All optics (FOV, IPD, swap, zoom, panel distance) are live fields fed
 * from SettingsStore by the activity.
 */
class VrRenderer(
    private val onBrowserActivate: (Int, Float?) -> Unit,
    private val onMenuEvent: (MenuEvent) -> Unit = {}
) : android.opengl.GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    enum class Mode { BROWSER, VIDEO }

    /** Play-menu actions. Press(idx): 0 prev, 1 rewind, 2 play/pause, 3 fast-forward,
     *  4 next, 5 settings, 6 vol+, 7 vol-, 8 zoom-, 9 zoom+, 10 files.
     *  Seek(frac): jump to fraction. */
    sealed class MenuEvent {
        data class Press(val idx: Int) : MenuEvent()
        data class Seek(val frac: Float) : MenuEvent()
    }

    @Volatile var mode: Mode = Mode.BROWSER
    @Volatile var projection: Projection = Projection.DEG180
    /** Vertical stretch at poles for DEG180: 0 = linear, 0.45 ≈ 45% more top/bottom FOV. */
    @Volatile var domeStretchK: Float = 0.45f
    /** Stretch onset: half-height |p| where stretch starts (0.30 = 20% from top/bottom). */
    @Volatile var domeOnset: Float = 0.30f
    @Volatile var stereo: Stereo = Stereo.SBS
    @Volatile var fovDeg: Float = 68f
    @Volatile var eyeHalfM: Float = 0.032f
    @Volatile var swapEyes: Boolean = false
    @Volatile var zoom: Float = 1f
    @Volatile var panelDistM: Float = 2.4f
    @Volatile var dwellMs: Long = 1500L
    /** Pin video dead-ahead (screen lock); browser always look-around. */
    @Volatile var pinVideo: Boolean = false
    /**
     * Diagnostics: ignore the sensors and drive tracking with a scripted
     * sweep (yaw ±35°/10s + pitch ±12°/7s). If the image pans level in sweep
     * mode but rotates on your real head, the sensors (not the math) lie.
     */
    @Volatile var testSweep: Boolean = false
    private var lastTestSweep = false
    private var sweepT0 = 0L

    var videoTextureId: Int = -1
    /** Bumped every onSurfaceCreated. If ExoPlayer is still targeting an
     *  older surface (EGL context loss recreates it silently), its frames
     *  go nowhere: frozen picture, advancing position, zero errors. The
     *  activity watches this generation and re-attaches on change. */
    @Volatile var surfaceGen = 0
    /** Frames completed (GL thread). Watchdog reads it: advancing = GL
     *  alive; frozen + frozen video = GL stuck (GPU hang/surface stall). */
    @Volatile var frameCount = 0L
        private set
    /** Video frames actually consumed from the decoder (updateTexImage ran).
     *  The discriminator: glfps high + consumed frozen = decoder stopped
     *  delivering (input starvation/track end); both frozen = GL stalled. */
    @Volatile var consumedFrames = 0L
        private set
    /** onFrameAvailable firings (binder thread). arrivals frozen + renderer
     *  counters climbing = queue/listener stopped delivering despite output;
     *  arrivals flowing + consumed frozen = consumption broken. */
    @Volatile var arrivedFrames = 0L
        private set
    var surfaceTexture: SurfaceTexture? = null
        private set
    var surface: android.view.Surface? = null
        private set
    // Written by the BufferQueue binder thread (onFrameAvailable), read by the
    // GL thread every frame. MUST be volatile: without it the GL thread may
    // stop seeing new frames after JIT recompiles the read (seconds in) —
    // frozen video, healthy audio/position/buffers, zero errors. This exact
    // failure froze every video at varying 5-15s until found.
    @Volatile private var frameAvailable = false

    @Volatile var browserTitle: String = "/"
    @Volatile var browserRows: List<BrowserRow> = emptyList()
    /** A row can carry a gaze slider: dwelling at horizontal fraction u
     *  sets value = min + u·(max-min). One dwell reaches any value. */
    /** Display format for a gaze slider's live tooltip: display value =
     *  raw * scale + offset, snapped to the snap grid (0 = no snap),
     *  rendered with decimals places plus suffix. Mirrors handleSlide. */
    data class SlideFormat(
        val suffix: String = "",
        val decimals: Int = 0,
        val scale: Float = 1f,
        val offset: Float = 0f,
        val snap: Float = 0f
    )
    data class BrowserRow(
        val label: String, val meta: String, val kind: Int,
        val slideKey: String? = null,
        val slideMin: Float = 0f,
        val slideMax: Float = 1f,
        val slideVal: Float = 0f,
        val slideFmt: SlideFormat? = null,
        val segLabels: List<String> = emptyList(),
        val segActions: List<String> = emptyList(),
        val segSelected: Int = -1,
        val previewMags: FloatArray? = null, // shaping preview: per-point 0..1
        val previewHull: IntArray? = null, // shaping preview: convex-hull indices
        val previewN: Int = 9, // shaping preview grid size
        val previewPos: FloatArray? = null, // shaping preview: absolute [x,y] per point, [0,1], y down
        val dead: Boolean = false // rest zone: hover drains, never accumulates or fires
    ) {
        companion object {
            const val FOLDER = 0; const val VIDEO = 1; const val FILE = 2; const val ACTION = 3
        }
    }

    // highlight index into FULL rows list
    private var highlight = -1
    private var scroll = 0
    private var dwellStart = 0L
    private var dwellFiredFor = -2
    // X close button (title bar, top right): own dwell state, fires sentinel -10.
    // Hit zone in panel TEX coords: x > 920, y < ROWS_Y0 (above the rows).
    private var inXZone = false
    private var xProgF = 0f
    private var xDwellFired = false
    // Diagnostic: log once per latch episode when a completed dwell is
    // suppressed by the fired latch (tells stuck-latch from no-dwell).
    private var fireBlockedLogged = false
    fun tapSelect() { val h = highlight; if (h in browserRows.indices) onBrowserActivate(h, null) }
    fun moveHighlight(d: Int) {
        val n = browserRows.size
        if (n == 0) return
        highlight = ((if (highlight < 0) n / 2 else highlight) + d).coerceIn(0, n - 1)
        ensureVisible()
        dwellStart = now(); dwellFiredFor = -2
    }

    private val rawM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val effM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    /** Rendered camera-forward, refreshed every frame (for the trace recorder). */
    @Volatile var lastEffFwd = floatArrayOf(0f, 0f, -1f)
    /** Rendered head-up, refreshed every frame. Drives the menu trigger. */
    @Volatile var lastEffUp = floatArrayOf(0f, 1f, 0f)
    // Recenter basis: R0 = raw pose at snap, Sb = screen frame in device
    // coords at snap (frozen). Effective view = Stb · R0t · R · Sb.
    // Sb is deliberately FROZEN at snap, not re-derived live: re-deriving
    // from live gravity makes the output frame swim whenever the yaw axis
    // isn't world-vertical (reclined viewer), which reads as image roll
    // during head turns. A fixed frame keeps relative motion exact.
    private val r0M = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val r0tM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val sbM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val stbM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)
    // NOTE: no upright/roll lock here, deliberately. Full 3DOF look-around:
    // yaw, pitch AND roll all track the head (world-locked, like GVR),
    // so rolling the head counter-rotates the image and the world stays put.
    // (An earlier upright constraint glued roll and was removed per request.)
    // Volatile: sensor callbacks may run on a different thread than taps and
    // lifecycle calls. A stale read here re-snaps the basis every event and
    // glues the world to the face (tracking gain collapses to ~0).
    @Volatile private var hasBasis = false
    /** Cause tag for the next snap (entry/files/video/tap/auto). Audit trail:
     *  repeated unprompted auto-snaps mean the reference frame is chasing
     *  the head, which glues/drags the world. */
    @Volatile private var snapTag = "auto"
    // NOTE: yaw/pitch/roll all flow through the matrix path (full 3DOF
    // world-lock). An earlier gyro-direct yaw drive was removed: unbounded
    // gyro drift made the image wander, and it blinded the turn diagnostic.
    /** Successful basis snaps since creation (debug overlay). */
    @Volatile var snapCount = 0
        private set

    /** Locked copy of the current effective view matrix (debug overlay). */
    fun effCopy(): FloatArray = synchronized(rawM) { effM.clone() }

    /** Latest sensor reading. First upright reading becomes the forward basis,
     *  so the view starts centered wherever the phone is pointing. */
    fun setHeadMatrix(m: FloatArray) {
        if (testSweep) return // sweep mode owns rawM on the GL thread
        synchronized(rawM) { System.arraycopy(m, 0, rawM, 0, 16) }
        if (!hasBasis) recenter(snapTag)
    }

    /** Snap "forward" to the current head pose. Call on tap and on entering VR.
     *  Returns false when the phone is flat: the snap is deferred until the
     *  next upright reading (setHeadMatrix retries automatically). */
    fun recenter(why: String = "auto"): Boolean {
        if (testSweep) return true // fixed canonical basis, nothing to snap
        var ok = false
        synchronized(rawM) {
            // Only commit the snap when the screen frame is derivable;
            // a flat-phone snap would freeze a degenerate frame.
            if (deriveScreenFrame(rawM, sbM)) {
                System.arraycopy(rawM, 0, r0M, 0, 16)
                Matrix.transposeM(r0tM, 0, r0M, 0)
                Matrix.transposeM(stbM, 0, sbM, 0)
                if (!hasBasis) { android.util.Log.d("DomeVR-basis", "basis snapped ($why)"); FileLog.d("DomeVR-basis", "basis snapped ($why)") }
                hasBasis = true
                snapCount++
                snapTag = "auto"
                ok = true
            } else {
                hasBasis = false // try again on the next reading
            }
        }
        inputGraceUntil = now() + 800
        return ok
    }

    /**
     * Screen frame (in device coords) from gravity. R maps device→world, so
     * world-up in device coords is Rᵀ·ẑ, i.e. row 2 = elements (r[2], r[6]).
     * (Column 2, (r[8], r[9]), is the screen normal in world coords — using
     * that swaps yaw and pitch in landscape, which makes panning roll the
     * image.) Works in portrait, either landscape, or upside-down.
     * Returns false when the phone is flat (no usable in-plane up).
     */
    private fun deriveScreenFrame(r: FloatArray, s: FloatArray): Boolean {
        var ux = r[2]; var uy = r[6] // world-up projected into screen plane
        val n = kotlin.math.sqrt(ux * ux + uy * uy)
        if (n < 0.25f) return false
        ux /= n; uy /= n
        // columns: screen-right = up × out, screen-up, screen-out
        s[0] = uy; s[1] = -ux; s[2] = 0f; s[3] = 0f
        s[4] = ux; s[5] = uy; s[6] = 0f; s[7] = 0f
        s[8] = 0f; s[9] = 0f; s[10] = 1f; s[11] = 0f
        s[12] = 0f; s[13] = 0f; s[14] = 0f; s[15] = 1f
        return true
    }

    /** View = world→camera = (Stb · R0t · R · Sb)ᵀ, all frozen at snap.
     *  Caller must hold rawM's monitor. Full 3DOF: yaw, pitch and roll all
     *  flow through (gyro drive re-anchors yaw); no upright lock. */
    private fun computeEffLocked(raw: FloatArray, eff: FloatArray) {
        Matrix.multiplyMM(tmpA, 0, r0tM, 0, raw, 0) // device-frame relative
        Matrix.multiplyMM(tmpB, 0, tmpA, 0, sbM, 0)
        Matrix.multiplyMM(tmpA, 0, stbM, 0, tmpB, 0)
        // transpose: raw sensor R maps device→world, but a GL view matrix
        // must map world→camera.
        Matrix.transposeM(eff, 0, tmpA, 0)
    }

    /** Forget the basis so the next sensor reading re-centers (enter VR / play).
     *  The tag audits WHY the next snap happens (entry/files/video). */
    fun resetBasis(tag: String = "auto") {
        if (testSweep) return
        snapTag = tag
        hasBasis = false
        inputGraceUntil = now() + 2500
    }

    @Volatile private var inputGraceUntil = 0L

    private var progOes = 0; private var prog2d = 0
    private var aPosOes = 0; private var aTexOes = 0; private var uMvpOes = 0
    private var uTexOes = 0; private var uStereoOes = 0; private var uEyeOes = 0; private var uZoomOes = 0
    private var uWarpOnOes = 0; private var uWarpCxOes = 0; private var uWarpK1Oes = 0; private var uWarpK2Oes = 0; private var uWarpAspectOes = 0
    private var uWarpOn2d = 0; private var uWarpCx2d = 0; private var uWarpK12d = 0; private var uWarpK22d = 0; private var uWarpAspect2d = 0
    private var aPos2d = 0; private var aTex2d = 0; private var uMvp2d = 0; private var uTex2d = 0

    private var mesh: Mesh? = null
    private var meshKey: String = ""
    private var browserTexId = -1
    private var browserBitmap: Bitmap? = null
    private var lastPanelHash = 0
    private var reticleTexId = -1
    /** Blue twin of the dwell reticle, for the recenter aim pointer. */
    private var aimTexId = -1

    private val projM = FloatArray(16)
    private val projEyeM = FloatArray(16)
    // Video shares the plain projection now: zoom crops UVs in FRAG_OES,
    // so the frustum (and all perspective/motion feel) never changes.
    private val videoProjM = FloatArray(16)
    private val projEyeVM = FloatArray(16)
    private val viewM = FloatArray(16)
    /** Per-eye convergence shift, NDC units (half-screen spans 2.0).
     *  >0 pulls both image centers toward the middle (for narrow IPD).
     *  Computed in applyOptics as (1 - ipd/spacing). */
    @Volatile var convShiftNdc = 0f
    /** Play-menu trigger tilts: up opens the top menu, down the bottom menu. */
    @Volatile var menuAngleUp = 40f
    @Volatile var menuAngleDown = -40f
    /** Which side the play menu lives on; flipped by its ⇅ button (persisted). */
    @Volatile var menuSideUp = true
    @Volatile private var menuAnimFrom = 52f
    @Volatile private var menuAnimT0 = 0L
    private val menuAnimMs = 350L
    /** Flip top<->bottom with a quick visible sweep through the middle. */
    fun menuToggleSide() {
        menuAnimFrom = menuElevCurrent()
        menuSideUp = !menuSideUp
        menuAnimT0 = now()
    }
    /** Browser panel elevation (deg): 0 = centered at horizon (file
     *  browsing), halfway to the play menu when floating over video. */
    @Volatile var browserElevDeg: Float = 0f
    /** Elevation for browser panels floating over live video: halfway
     *  between center (horizon) and the play-menu panel. */
    fun overlayElevDeg(): Float = menuElevDeg() / 2f
    /** True while the play menu is shown (VIDEO mode only). */
    @Volatile var menuOpen = false
    /** Transient in-VR message on the menu panel (Toasts are invisible
     *  in the headset). Activity sets text; visible ~2s. */
    @Volatile var menuFlash = ""
    @Volatile var menuFlashUntil = 0L
    fun flashMenu(msg: String, ms: Long = 2000L) {
        menuFlash = msg
        menuFlashUntil = System.currentTimeMillis() + ms
        showToast(msg, ms)
    }
    /** Center-screen 3D toast (Android Toasts are unreadable in-headset):
     *  head-locked quad in the vertical middle, auto-expiring. */
    @Volatile var toastText = ""
    @Volatile var toastUntil = 0L
    fun showToast(msg: String, ms: Long = 2500L) {
        toastText = msg
        toastUntil = System.currentTimeMillis() + ms
    }
    private var toastTexId = 0
    private var lastToastText = ""
    private var lastToastActive = false
    private val toastVerts: FloatBuffer = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val toastTex: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private fun maybeUploadToast() {
        val active = toastText.isNotEmpty() && now() < toastUntil
        if (active == lastToastActive && toastText == lastToastText && toastBitmap != null) return
        lastToastActive = active
        lastToastText = toastText
        val W = 512; val H = 112
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        if (active) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.argb(220, 10, 14, 22)
            c.drawRoundRect(4f, 4f, (W - 4).toFloat(), (H - 4).toFloat(), 24f, 24f, p)
            p.color = Color.WHITE; p.textSize = 40f; p.textAlign = Paint.Align.CENTER
            c.drawText(toastText.take(34), W / 2f, 70f, p)
            p.textAlign = Paint.Align.LEFT
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, toastTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        toastBitmap?.recycle()
        toastBitmap = bmp
    }
    private var toastBitmap: Bitmap? = null
    /** Screen-space toast quad at the vertical middle, drawn after the
     *  panels/pointer like the reticle (prog2d + identM, warp off). */
    private fun drawToast() {
        maybeUploadToast()
        if (toastText.isEmpty() || now() >= toastUntil) return
        putQuad(toastVerts, toastTex, -0.62f, -0.14f, 0.62f, 0.14f)
        GLES20.glUseProgram(prog2d)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, toastTexId)
        GLES20.glUniform1i(uTex2d, 0)
        GLES20.glUniformMatrix4fv(uMvp2d, 1, false, identM, 0)
        GLES20.glUniform1f(uWarpOn2d, 0f)
        GLES20.glEnableVertexAttribArray(aPos2d)
        GLES20.glVertexAttribPointer(aPos2d, 3, GLES20.GL_FLOAT, false, 0, toastVerts)
        GLES20.glEnableVertexAttribArray(aTex2d)
        GLES20.glVertexAttribPointer(aTex2d, 2, GLES20.GL_FLOAT, false, 0, toastTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos2d)
        GLES20.glDisableVertexAttribArray(aTex2d)
    }
    /** Playback position/duration/state for the menu progress bar. */
    @Volatile var menuPosMs = 0L
    @Volatile var menuDurMs = 0L
    @Volatile var menuPlaying = true
    /** File name shown across the top of the play menu. */
    @Volatile var menuTitle = ""
    /** Rewind/fast-forward jump, seconds (2D setting). */
    @Volatile var skipSecs = 10
    // menu gaze state (GL thread)
    private var menuHighlight = -2 // -1 = seek bar, 0..10 buttons
    private var menuDwellFiredFor = -3
    private var menuHitValid = false
    // hovered seek fraction (panel-wide u) for the live time tooltip; -1 = none
    private var menuSeekHoverU = -1f
    private var menuHitW = FloatArray(3)
    /** Last panel hit, kept while the menu is open: the pointer tracks the
     *  gaze even mid-motion instead of flickering off. */
    private var menuStickyValid = false
    private var menuStickyW = FloatArray(3)
    private var menuTexId = 0
    private var menuBitmap: Bitmap? = null
    private var lastMenuHash = 0
    private var menuDwellPrevInit = false
    private val menuDwellPrevM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var menuDwellPrevT = 0L
    /** When the gaze first dropped below the open angle (0 = above it).
     *  Closing needs 400ms continuously below: sensor noise at the exact
     *  threshold must not strobe the menu (and reset every dwell). */
    private var menuBelowSince = 0L
    private var menuWasOpen = false
    private var menuProgFresh = true
    /** Cardboard lens distortion coefficients (0..1, standard Cardboard). */
    @Volatile var lensK1 = 0.34f
    @Volatile var lensK2 = 0.55f
    // Distortion runs as a FINAL pass on a flat quad (never on scene
    // geometry): warping scene vertices breaks GPU clipping where the
    // dome crosses behind the camera, fanning streaks across the screen.
    // Eye buffer supersampled 2x with mipmaps + anisotropy: plain bilinear
    // minifies by point-sampling (chunky "lego" aliasing on 4K/8K sources),
    // while a mipmapped supersample minifies smoothly. Per-eye cost is
    // one RGBA blit + mipmap gen; fine on modern GPUs.
    private val FBO_SCALE = 2
    private var fboId = 0
    private var fboTexId = 0
    private var fboW = 0
    private var fboH = 0
    private var fboOk = false
    private var progDist = 0
    private var aPosDist = 0
    private var aTexDist = 0
    private var uMvpDist = 0
    private var uTexDist = 0
    private var uK1Dist = 0
    private var uK2Dist = 0
    private var uAspectDist = 0
    private val identM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val flatM = FloatArray(16) // proj · eff: rotation-only UI matrix
    private val mvpM = FloatArray(16)
    private val tmpM = FloatArray(16)

    @Volatile var lastWidth = 1
    @Volatile var lastHeight = 1

    companion object {
        const val VISIBLE_ROWS = 13
        const val MENU_BUTTONS = 14
        const val TEX = 1024
        const val ROWS_Y0 = 150
        const val ROW_H = 64

        private const val VERT = """
attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; uniform mat4 uMvp;
uniform float uWarpOn; uniform float uWarpCx; uniform float uWarpK1; uniform float uWarpK2; uniform float uWarpAspect;
void main(){
  vTex = aTex;
  vec4 p = uMvp * aPos;
  float w = p.w;
  if (uWarpOn > 0.5) {
    if (w > 0.0) {
      float nx = p.x / w;
      float ny = p.y / w;
      float dx = (nx - uWarpCx) * uWarpAspect;
      float r2 = dx * dx + ny * ny;
      float s = 1.0 / (1.0 + uWarpK1 * r2 + uWarpK2 * r2 * r2);
      nx = uWarpCx + dx * s / uWarpAspect;
      ny = ny * s;
      p.x = nx * w;
      p.y = ny * w;
    }
  }
  gl_Position = p;
}
"""
        // highp UVs when available: mediump quantizes texture coordinates
        // to ~1024 steps, i.e. 8-texel blocks on 8K video ("lego").
        private const val FRAG_OES = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTex;
uniform samplerExternalOES uTex; uniform int uStereo; uniform int uEye; uniform float uZoom;
void main(){
  vec2 t = vTex;
  if (uStereo == 1) { t.x = (t.x + float(uEye)) * 0.5; }
  else if (uStereo == 2) { t.y = (t.y + float(uEye)) * 0.5; }
  // zoom crops the picture, not the frustum: magnify content about the
  // half-image center (each SBS/TB half is its own picture). Outside the
  // frame paints black (shrunken screen on flat, void on domes). Unlike
  // FOV zoom this never distorts perspective, at any value.
  vec2 c = vec2(0.5);
  if (uStereo == 1) { c = vec2(float(uEye) * 0.5 + 0.25, 0.5); }
  else if (uStereo == 2) { c = vec2(0.5, float(uEye) * 0.5 + 0.25); }
  t = c + (t - c) / uZoom;
  if (t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
  gl_FragColor = texture2D(uTex, t);
}
"""
        private const val FRAG_2D = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTex; uniform sampler2D uTex;
void main(){ gl_FragColor = texture2D(uTex, vTex); }
"""
        // Final-pass lens warp (per-pixel, on a flat quad at safe depth):
        // radial barrel offsets the sample outward (edges magnified, like
        // the real lens); sampling outside the eye image paints black,
        // carving the curved lens boundary. k1/k2 span 0..1.
        private const val FRAG_DIST = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTex;
uniform sampler2D uTex; uniform float uK1; uniform float uK2; uniform float uAspect;
void main(){
  vec2 c = vTex - vec2(0.5);
  vec2 d = vec2(c.x * uAspect, c.y);
  float r2 = dot(d, d);
  float s = 1.0 + uK1 * r2 + uK2 * r2 * r2;
  vec2 sc = vec2(0.5) + vec2(d.x * s / uAspect, d.y * s);
  if (sc.x < 0.0 || sc.x > 1.0 || sc.y < 0.0 || sc.y > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
  gl_FragColor = texture2D(uTex, sc);
}
"""
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Black: the warped meshes cover less than the viewport, and the
        // lens boundary must read as darkness, like real VR software.
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        // Alpha blending for the pointer ring (transparent bitmap
        // background). Opaque content (video, panels) has alpha 1, so this
        // is a no-op for everything except the pointer.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        progOes = buildProgram(VERT, FRAG_OES)
        aPosOes = GLES20.glGetAttribLocation(progOes, "aPos")
        aTexOes = GLES20.glGetAttribLocation(progOes, "aTex")
        uMvpOes = GLES20.glGetUniformLocation(progOes, "uMvp")
        uTexOes = GLES20.glGetUniformLocation(progOes, "uTex")
        uStereoOes = GLES20.glGetUniformLocation(progOes, "uStereo")
        uEyeOes = GLES20.glGetUniformLocation(progOes, "uEye")
        uZoomOes = GLES20.glGetUniformLocation(progOes, "uZoom")
        uWarpOnOes = GLES20.glGetUniformLocation(progOes, "uWarpOn")
        uWarpCxOes = GLES20.glGetUniformLocation(progOes, "uWarpCx")
        uWarpK1Oes = GLES20.glGetUniformLocation(progOes, "uWarpK1")
        uWarpK2Oes = GLES20.glGetUniformLocation(progOes, "uWarpK2")
        uWarpAspectOes = GLES20.glGetUniformLocation(progOes, "uWarpAspect")
        prog2d = buildProgram(VERT, FRAG_2D)
        aPos2d = GLES20.glGetAttribLocation(prog2d, "aPos")
        aTex2d = GLES20.glGetAttribLocation(prog2d, "aTex")
        uMvp2d = GLES20.glGetUniformLocation(prog2d, "uMvp")
        uTex2d = GLES20.glGetUniformLocation(prog2d, "uTex")
        uWarpOn2d = GLES20.glGetUniformLocation(prog2d, "uWarpOn")
        uWarpCx2d = GLES20.glGetUniformLocation(prog2d, "uWarpCx")
        uWarpK12d = GLES20.glGetUniformLocation(prog2d, "uWarpK1")
        uWarpK22d = GLES20.glGetUniformLocation(prog2d, "uWarpK2")
        uWarpAspect2d = GLES20.glGetUniformLocation(prog2d, "uWarpAspect")
        progDist = buildProgram(VERT, FRAG_DIST)
        aPosDist = GLES20.glGetAttribLocation(progDist, "aPos")
        aTexDist = GLES20.glGetAttribLocation(progDist, "aTex")
        uMvpDist = GLES20.glGetUniformLocation(progDist, "uMvp")
        uTexDist = GLES20.glGetUniformLocation(progDist, "uTex")
        uK1Dist = GLES20.glGetUniformLocation(progDist, "uK1")
        uK2Dist = GLES20.glGetUniformLocation(progDist, "uK2")
        uAspectDist = GLES20.glGetUniformLocation(progDist, "uAspect")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        videoTextureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(videoTextureId)
        surfaceTexture?.setOnFrameAvailableListener(this)
        surface = android.view.Surface(surfaceTexture)
        surfaceGen++

        GLES20.glGenTextures(1, tex, 0)
        browserTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, browserTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        reticleTexId = makeReticle(Color.RED)
        aimTexId = makeReticle(Color.rgb(96, 165, 250))

        GLES20.glGenTextures(1, tex, 0)
        menuTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, menuTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glGenTextures(1, tex, 0)
        toastTexId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, toastTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {}

    override fun onDrawFrame(gl: GL10?) {
        try {
            drawFrameInner(gl)
            frameCount++
        } catch (e: Throwable) {
            // An uncaught exception here kills the GL thread SILENTLY:
            // frozen picture, advancing position, zero errors. Never again.
            android.util.Log.e("DomeVR-GL", "onDrawFrame failed", e)
            FileLog.e("DomeVR-GL", "onDrawFrame failed", e)
        }
    }

    private var texFailCount = 0

    private fun drawFrameInner(gl: GL10?) {
        // Consume UNCONDITIONALLY once any frame has ever arrived (the queue
        // warms within ~1s of start; before that stay flag-guarded so an
        // empty queue never throws). Gating consumption on the flag
        // deadlocks permanently: if the producer ever gets a frame ahead
        // (ordinary jitter), its overflow replaces the queued frame SILENTLY
        // (no onFrameAvailable), the flag stays false forever, we never
        // consume, the queue never drains — frozen video, healthy audio,
        // zero errors, both decoders, varying 5-25s. Latching the same frame
        // an extra time is harmless; a stale slot is fatal. Never again.
        // NOTE: no mode check here — panels float over LIVE video in
        // BROWSER mode now, so the queue must keep draining there too.
        surfaceTexture?.let { st ->
            if (frameAvailable || arrivedFrames > 0) {
                try { st.updateTexImage(); if (frameAvailable) consumedFrames++ } catch (e: Throwable) { texFailCount++; if (texFailCount <= 3 || texFailCount % 300 == 0) { android.util.Log.e("DomeVR-GL", "updateTexImage failed #$texFailCount", e); FileLog.e("DomeVR-GL", "updateTexImage failed #$texFailCount", e) } }
                frameAvailable = false
            }
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (testSweep != lastTestSweep) {
            lastTestSweep = testSweep
            if (testSweep) {
                // canonical basis: R0 = Sb = identity; sweep drives rawM directly
                Matrix.setIdentityM(r0M, 0); Matrix.setIdentityM(r0tM, 0)
                Matrix.setIdentityM(sbM, 0); Matrix.setIdentityM(stbM, 0)
                hasBasis = true
                sweepT0 = android.os.SystemClock.elapsedRealtime()
            } else {
                hasBasis = false // re-snap from the real sensors
            }
        }
        if (testSweep) {
            val t = (android.os.SystemClock.elapsedRealtime() - sweepT0) / 1000f
            // yaw (about Y) ±12°/10s + pitch (about X) ±8°/7s + roll (about
            // view axis Z) ±15°/13s. Small on purpose: the panel stays in
            // frame so screenshots (or eyes) can judge each axis. Roll must
            // spin the panel IN PLACE (centered, tilted); yaw/pitch pan it.
            // NB: yaw is rotation about Y, NOT Z (Z is roll).
            val yaw = 12f * kotlin.math.sin(2 * Math.PI.toFloat() * t / 10f)
            val pitch = 8f * kotlin.math.sin(2 * Math.PI.toFloat() * t / 7f + 1.3f)
            val roll = 15f * kotlin.math.sin(2 * Math.PI.toFloat() * t / 13f + 2.1f)
            Matrix.setIdentityM(rawM, 0)
            Matrix.rotateM(rawM, 0, yaw, 0f, 1f, 0f)
            Matrix.rotateM(rawM, 0, pitch, 1f, 0f, 0f)
            Matrix.rotateM(rawM, 0, roll, 0f, 0f, 1f)
        }
        val w = lastWidth.coerceAtLeast(1); val h = lastHeight.coerceAtLeast(1)
        Matrix.perspectiveM(projM, 0, fovDeg.coerceIn(40f, 110f), (w / 2f) / h, 0.1f, 100f)
        Matrix.perspectiveM(videoProjM, 0, fovDeg.coerceIn(40f, 110f), (w / 2f) / h, 0.1f, 100f)
        val cur: Mode = mode
        val wantMeshKey = projection.name + "|k=" + domeStretchK.toString() + "|o=" + domeOnset.toString() +
            "|sh=" + shapingRevision.toString()
        if (meshKey != wantMeshKey) {
            mesh = buildMesh(projection); meshKey = wantMeshKey
        }
        // effective orientation in screen frame (identity at recenter)
        synchronized(rawM) { computeEffLocked(rawM, effM) }
        lastEffFwd = floatArrayOf(-effM[2], -effM[6], -effM[10])
        lastEffUp = floatArrayOf(effM[1], effM[5], effM[9])
        if (cur == Mode.BROWSER) updateGaze()
        if (cur == Mode.VIDEO) updateMenu() else menuOpen = false
        val swap = swapEyes
        // Rotation-only UI: identical geometry in both eyes (no vergence
        // conflict); per-eye convergence below centers the images.
        // Stereo depth (eye shift) applies to video only.
        for (vp in 0..1) {
            val eye = if (swap) 1 - vp else vp
            GLES20.glViewport(vp * w / 2, 0, w / 2, h)
            // Convergence: shift each eye's image toward its half-center so
            // the two centers land at the configured IPD apart. Baking the
            // NDC offset into projEye[8] moves the image uniformly, at any
            // depth (NDC shift o moves every pixel o*halfScreenPx).
            // eye 0 (left half) shifts right (+), eye 1 shifts left (-).
            System.arraycopy(projM, 0, projEyeM, 0, 16)
            projEyeM[8] = if (eye == 0) -convShiftNdc else convShiftNdc
            Matrix.multiplyMM(flatM, 0, projEyeM, 0, effM, 0)
            // video uses the zoomed projection (same convergence shift)
            System.arraycopy(videoProjM, 0, projEyeVM, 0, 16)
            projEyeVM[8] = projEyeM[8]
            if (cur == Mode.VIDEO && pinVideo) {
                // pinned: screen fixed dead-ahead, only eye shift
                Matrix.setIdentityM(viewM, 0)
            } else {
                System.arraycopy(effM, 0, viewM, 0, 16)
            }
            // eye-shifted view (parallel cameras)
            Matrix.translateM(mvpM, 0, viewM, 0, if (eye == 0) eyeHalfM else -eyeHalfM, 0f, 0f)
            Matrix.multiplyMM(tmpM, 0, projEyeVM, 0, mvpM, 0)
            System.arraycopy(tmpM, 0, mvpM, 0, 16)
            // Lens correction as a FINAL pass on a flat quad: the scene
            // renders undistorted into the eye FBO (same aspect as the
            // screen half, so all MVP math is unchanged), then the
            // distortion quad resamples it with barrel UVs. Flat quad =
            // no clipping boundary issues, unlike warping scene verts.
            // The pointer draws after, in screen space, staying round.
            val fw = w / 2 * FBO_SCALE; val fh = h * FBO_SCALE
            // Physical eye-halves are ~0.8-1.4 aspect. Clamp: a bogus
            // measurement must never reach the warp.
            val eyeAspect = ((w / 2).toFloat() / h.toFloat()).coerceIn(0.5f, 2.0f)
            val warpCx = if (vp == 0) -0.5f else 0.5f
            ensureEyeFbo(fw, fh)
            if (fboOk) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                GLES20.glViewport(0, 0, fw, fh)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                // Panels float over live video: in BROWSER mode the video
                // keeps rendering behind the panel (when frames have arrived).
                if (cur == Mode.VIDEO) drawVideo(eye, warpCx, eyeAspect)
                else { if (arrivedFrames > 0) drawVideo(eye, warpCx, eyeAspect); drawBrowser(warpCx, eyeAspect) }
                if (cur == Mode.VIDEO && menuOpen) drawMenuPanel(warpCx, eyeAspect)
                // mipmaps for the distortion minification (smooth, not chunky)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(vp * w / 2, 0, w / 2, h)
                drawDistortionQuad(eyeAspect)
            } else {
                GLES20.glViewport(vp * w / 2, 0, w / 2, h)
                if (cur == Mode.VIDEO) drawVideo(eye, warpCx, eyeAspect)
                else { if (arrivedFrames > 0) drawVideo(eye, warpCx, eyeAspect); drawBrowser(warpCx, eyeAspect) }
                if (cur == Mode.VIDEO && menuOpen) drawMenuPanel(warpCx, eyeAspect)
            }
            // Menu lives in the browser's pipeline (FBO + flatM with
            // convergence), so it fuses exactly like the browser panel.
            // The pointer is then re-warped to the displayed position
            // (see drawScreenPointer): seen and computed agree.
            if (cur == Mode.VIDEO && menuOpen && menuStickyValid)
                drawScreenPointer(menuStickyW[0], menuStickyW[1], menuStickyW[2], flatM, eyeAspect, menuDwellProg())
            // recenter aim pointer: blue twin, 2x size, 2x dwell to a point
            if (cur == Mode.VIDEO && aimArmed)
                drawScreenPointer(aimW[0], aimW[1], aimW[2], flatM, eyeAspect,
                    aimProg.coerceIn(0f, 1f), 2f, aimTexId)
            // screen-space pointer, re-warped to the displayed position:
            // project the world hit with this eye's rotation-only matrix
            if (cur == Mode.BROWSER && hitValid)
                drawScreenPointer(hitX, hitY, hitZ, flatM, eyeAspect,
                    if (inXZone) xProgF.coerceIn(0f, 1f) else browserDwellProg())
            drawToast()
        }
    }

    /** Scene-vertex warp stays OFF (it breaks clipping where the dome
     *  crosses behind the camera); distortion runs per-pixel on the flat
     *  final quad instead. */
    private fun setWarp(
        uOn: Int, uCx: Int, uK1: Int, uK2: Int, uAsp: Int, cx: Float, aspect: Float
    ) {
        GLES20.glUniform1f(uOn, 0f)
    }

    /** Dwell progress 0..1 for the browser pointer (full ring → point). */
    private fun browserDwellProg(): Float = browProgF.coerceIn(0f, 1f)

    /** Dwell progress 0..1 for the menu pointer. */
    private fun menuDwellProg(): Float =
        if (menuHighlight != -2) menuProg[menuSlot(menuHighlight)].coerceIn(0f, 1f) else 0f

    private val clipV = FloatArray(4)

    /** Pointer ring in screen space. The panels live behind the barrel
     *  distortion, so the projected point is re-warped with the forward
     *  lens model to land on the button as displayed. NOTE the center:
     *  the distortion quad is a fullscreen (-1..1) quad drawn under a
     *  HALF viewport, so its center is NDC (0,0) per eye — warping toward
     *  the half-centers (±0.5) lands a quarter-screen off and doubles. */
    private fun drawScreenPointer(
        wx: Float, wy: Float, wz: Float, mat: FloatArray, aspect: Float, prog: Float,
        sizeMul: Float = 1f, texId: Int = -1
    ) {
        v4[0] = wx; v4[1] = wy; v4[2] = wz; v4[3] = 1f
        Matrix.multiplyMV(clipV, 0, mat, 0, v4, 0)
        val cw = clipV[3]
        if (cw <= 0.01f) return
        // work in distortion-quad texel units, mirroring FRAG_DIST exactly
        val tu = clipV[0] / cw * 0.5f + 0.5f
        val tv = clipV[1] / cw * 0.5f + 0.5f
        val dx = (tu - 0.5f) * aspect
        val dy = tv - 0.5f
        val r2 = dx * dx + dy * dy
        val s = 1f / (1f + lensK1 * r2 + lensK2 * r2 * r2)
        var nx = (0.5f + dx * s / aspect) * 2f - 1f
        var ny = (0.5f + dy * s) * 2f - 1f
        if (nx < -1.2f || nx > 1.2f || ny < -1.2f || ny > 1.2f) return
        val sx = 0.022f * sizeMul * (1f - 0.85f * prog)
        val sy = sx * aspect
        putQuad(ptrVerts, ptrTex, nx - sx, ny - sy, nx + sx, ny + sy)
        GLES20.glUseProgram(prog2d)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (texId < 0) reticleTexId else texId)
        GLES20.glUniform1i(uTex2d, 0)
        GLES20.glUniformMatrix4fv(uMvp2d, 1, false, identM, 0)
        // screen-space pointer: warp stays off (ring must stay round)
        GLES20.glUniform1f(uWarpOn2d, 0f)
        GLES20.glEnableVertexAttribArray(aPos2d)
        GLES20.glVertexAttribPointer(aPos2d, 3, GLES20.GL_FLOAT, false, 0, ptrVerts)
        GLES20.glEnableVertexAttribArray(aTex2d)
        GLES20.glVertexAttribPointer(aTex2d, 2, GLES20.GL_FLOAT, false, 0, ptrTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos2d)
        GLES20.glDisableVertexAttribArray(aTex2d)
    }

    // scratch buffers: zero per-frame allocation on the GL thread (direct
    // ByteBuffer churn caused GC strobes that read as pointer flicker)
    private val v4 = FloatArray(4)
    private val ptrVerts: FloatBuffer = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val ptrTex: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private fun putQuad(vb: FloatBuffer, tb: FloatBuffer, x0: Float, y0: Float, x1: Float, y1: Float) {
        vb.clear()
        vb.put(x0); vb.put(y1); vb.put(0f)
        vb.put(x0); vb.put(y0); vb.put(0f)
        vb.put(x1); vb.put(y1); vb.put(0f)
        vb.put(x1); vb.put(y0); vb.put(0f)
        vb.position(0)
        tb.clear()
        tb.put(0f); tb.put(0f)
        tb.put(0f); tb.put(1f)
        tb.put(1f); tb.put(0f)
        tb.put(1f); tb.put(1f)
        tb.position(0)
    }

    /** Fullscreen quad resampling the eye FBO with barrel UVs. Flat quad
     *  at safe depth: no clipping-boundary issues by construction. */
    private fun drawDistortionQuad(aspect: Float) {
        GLES20.glUseProgram(progDist)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
        GLES20.glUniform1i(uTexDist, 0)
        GLES20.glUniform1f(uK1Dist, lensK1)
        GLES20.glUniform1f(uK2Dist, lensK2)
        GLES20.glUniform1f(uAspectDist, aspect)
        GLES20.glUniformMatrix4fv(uMvpDist, 1, false, identM, 0)
        val v = floatArrayOf(-1f, -1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f)
        val t = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)
        GLES20.glEnableVertexAttribArray(aPosDist)
        GLES20.glVertexAttribPointer(aPosDist, 3, GLES20.GL_FLOAT, false, 0, fb(v))
        GLES20.glEnableVertexAttribArray(aTexDist)
        GLES20.glVertexAttribPointer(aTexDist, 2, GLES20.GL_FLOAT, false, 0, fb(t))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosDist)
        GLES20.glDisableVertexAttribArray(aTexDist)
    }

    /** (Re)creates the eye FBO at half-screen size. Failure falls back to
     *  direct rendering (fboOk=false), never a black screen. */
    private fun ensureEyeFbo(fw: Int, fh: Int) {
        if (fboOk && fw == fboW && fh == fboH && fboId != 0) return
        if (fboId != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0); fboId = 0 }
        if (fboTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(fboTexId), 0); fboTexId = 0 }
        fboOk = false
        if (fw < 8 || fh < 8) return
        try {
            val ta = IntArray(1)
            GLES20.glGenTextures(1, ta, 0)
            fboTexId = ta[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            try {
                // anisotropic filtering on the minified eye buffer (if present)
                val exts = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
                if (exts.contains("GL_EXT_texture_filter_anisotropic")) {
                    val maxA = IntArray(1)
                    GLES20.glGetIntegerv(0x84FF, maxA, 0)
                    GLES20.glTexParameterf(
                        GLES20.GL_TEXTURE_2D, 0x84FE,
                        minOf(8f, maxA[0].toFloat()).coerceAtLeast(1f)
                    )
                }
            } catch (_: Throwable) {}
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw, fh, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            val fa = IntArray(1)
            GLES20.glGenFramebuffers(1, fa, 0)
            fboId = fa[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTexId, 0)
            fboOk = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
                GLES20.GL_FRAMEBUFFER_COMPLETE && GLES20.glGetError() == GLES20.GL_NO_ERROR
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (fboOk) { fboW = fw; fboH = fh }
        } catch (_: Throwable) {
            fboOk = false
            try { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) } catch (_: Throwable) {}
        }
    }

    // ---------- gaze ----------
    // Panel size grows sub-linearly with distance (sqrt): distance changes
    // stay clearly visible (nearer = bigger) while extremes stay comfortable.
    // A linear width (= constant on-screen size) hid the control entirely.
    // Matches stock 0.96 half-width at the 2.4 m default.
    private fun panelHalfW(): Float = 0.62f * kotlin.math.sqrt(panelDistM)
    private fun panelHalfH(): Float = panelHalfW() * 0.62f

    // last ray↔panel hit in panel-world coords (for the at-depth cursor)
    private var hitX = 0f
    private var hitY = 0f
    private var hitZ = -2.4f
    private var hitValid = false
    // hovered slider fraction (panel-wide u) for the live tooltip; -1 = none
    private var sliderHoverU = -1f
    // gaze u where the last slider dwell fired; sliding the gaze along the
    // bar re-arms shrink + fire without leaving the row (NaN = disarmed)
    private var lastFiredU = Float.NaN
    // head-forward at the last slider fire: re-arm requires the HEAD to have
    // moved, so a panel resize shifting the mapping under a steady gaze
    // can't trigger a spurious feedback-loop refire
    private var lastFiredFwd = floatArrayOf(0f, 0f, -1f)
    // stillness gate state: dwell must NEVER fire while the head is moving,
    // or slow test turns sweep the gaze across rows and trigger accidental
    // navigation + recentering mid-turn (reads as rotation/pan glitches).
    private val dwellPrevM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var dwellPrevT = 0L
    private var dwellPrevInit = false

    private fun updateGaze() {
        val rows = browserRows
        if (rows.isEmpty()) { highlight = -1; hitValid = false; inXZone = false; xDwellFired = false; sliderHoverU = -1f; lastFiredU = Float.NaN; return }
        if (now() < inputGraceUntil) { dwellStart = now(); sliderHoverU = -1f; return }
        // Windowed stillness + leaky dwell (same as the play menu): jitter
        // and row churn only dent progress instead of zeroing the timer.
        val nowMs = now()
        var still = true
        synchronized(rawM) { still = browserStill.update(rawM, nowMs) }
        val dtMs = (nowMs - browProgT).coerceIn(0L, 500L)
        browProgT = nowMs
        if (!still) browProgF = maxOf(0f, browProgF - dtMs / 600f)
        // head-forward ray in world (panel floats at browserElevDeg,
        // facing the viewer; elevation 0 = centered at z=-D, identical math).
        // Uses the recentered orientation, so the reticle and the hover agree.
        val hm = FloatArray(16)
        synchronized(rawM) { computeEffLocked(rawM, hm) }
        val fx = -hm[2]; val fy = -hm[6]; val fz = -hm[10]
        val d = panelDistM
        val el = Math.toRadians(browserElevDeg.toDouble()).toFloat()
        val cx = 0f; val cy = (kotlin.math.sin(el) * d); val cz = (-kotlin.math.cos(el) * d)
        var pnx = -cx; var pny = -cy; var pnz = -cz
        val pnl = kotlin.math.sqrt(pnx * pnx + pny * pny + pnz * pnz).coerceAtLeast(1e-6f)
        pnx /= pnl; pny /= pnl; pnz /= pnl
        val denom = fx * pnx + fy * pny + fz * pnz
        if (denom < -0.05f) {
            val t = (cx * pnx + cy * pny + cz * pnz) / denom
            val hx = fx * t; val hy = fy * t; val hz = fz * t
            // panel coords: right = (1,0,0); up = n × right = (0, nz, -ny)
            val upx = 0f; val upy = pnz; val upz = -pny
            val upl = kotlin.math.sqrt(upy * upy + upz * upz).coerceAtLeast(1e-6f)
            val hw = panelHalfW(); val hh = panelHalfH()
            val alongRight = hx - cx
            val alongUp = ((hx - cx) * upx + (hy - cy) * (upy / upl) + (hz - cz) * (upz / upl))
            if (alongRight >= -hw && alongRight <= hw && alongUp >= -hh && alongUp <= hh) {
                hitX = hx; hitY = hy; hitZ = hz; hitValid = true
                val u = (alongRight + hw) / (2 * hw)
                val v = (hh - alongUp) / (2 * hh)
                // X close button: top-right title bar, above the rows.
                if (u > 0.90f && v * TEX < ROWS_Y0) {
                    inXZone = true
                    sliderHoverU = -1f
                    lastFiredU = Float.NaN
                    fireBlockedLogged = false
                    highlight = -1; dwellFiredFor = -2
                    browProgF = maxOf(0f, browProgF - dtMs / 600f)
                    if (!xDwellFired) {
                        if (still) xProgF += dtMs / dwellMs.toFloat()
                        else xProgF = maxOf(0f, xProgF - dtMs / 600f)
                    }
                    if (still && !xDwellFired && xProgF >= 1f) {
                        xDwellFired = true
                        xProgF = 1f
                        FileLog.i("DomeVR-browser", "FIRE X close")
                        onBrowserActivate(-10, null)
                        return
                    }
                    return
                }
                inXZone = false
                xDwellFired = false
                xProgF = maxOf(0f, xProgF - dtMs / 600f)
                val vi = ((v * TEX - ROWS_Y0) / ROW_H).toInt()
                if (vi in 0 until VISIBLE_ROWS) {
                    val idx = (scroll + vi).coerceIn(0, rows.size - 1)
                    // dead rows are gaze rest zones: drain, never accumulate or fire
                    if (rows[idx].dead) {
                        if (idx != highlight) { highlight = idx; dwellFiredFor = -2 }
                        sliderHoverU = -1f
                        browProgF = maxOf(0f, browProgF - dtMs / 600f)
                        return
                    }
                    sliderHoverU = if (rows[idx].slideKey != null || rows[idx].segActions.isNotEmpty()) u else -1f
                    if (idx != highlight) {
                        highlight = idx; dwellFiredFor = -2
                        lastFiredU = Float.NaN
                        fireBlockedLogged = false
                        // small credit on row change (replaces the old
                        // 150ms-style stability delay): keeps flips cheap
                        browProgF = minOf(browProgF, 0.25f)
                    }
                    // Slider re-arm: sliding the gaze along the bar after a
                    // fire restarts shrink + fire without leaving the row.
                    // Requires real head motion: a panel resize shifting the
                    // mapping under a steady gaze must not refire by itself.
                    // (Gaze is head-driven — no eye tracking — so any genuine
                    // slide moves the head; jitter stays far below both gates.)
                    if (highlight == dwellFiredFor && rows[idx].slideKey != null &&
                        !lastFiredU.isNaN() && kotlin.math.abs(u - lastFiredU) > 0.05f &&
                        angleDeg(lastFiredFwd, lastEffFwd) > 2f) {
                        dwellFiredFor = -2
                        browProgF = 0f
                        lastFiredU = Float.NaN
                    }
                    // Accumulate only until fired for this hover; after firing
                    // hold the shrunken state (no second shrink animation).
                    // Re-entry (off-panel resets dwellFiredFor) restarts it.
                    if (highlight != dwellFiredFor) {
                        if (still) browProgF += dtMs / dwellMs.toFloat()
                        else browProgF = maxOf(0f, browProgF - dtMs / 600f)
                    }
                    if (still && highlight != dwellFiredFor && browProgF >= 1f) {
                        dwellFiredFor = highlight
                        browProgF = 1f
                        // slider rows pass the bar-mapped fraction so one dwell
                        // sets any value; segmented rows pass panel-wide u for
                        // segment picking; plain rows null
                        val frac = if (rows[idx].slideKey != null) barFrac(u)
                            else if (rows[idx].segActions.isNotEmpty()) u else null
                        if (rows[idx].slideKey != null) {
                            lastFiredU = u
                            lastFiredFwd = lastEffFwd.clone()
                        }
                        FileLog.i("DomeVR-browser", "FIRE row=$idx frac=$frac")
                        onBrowserActivate(highlight, frac)
                        return
                    } else if (still && highlight == dwellFiredFor && browProgF >= 1f && !fireBlockedLogged) {
                        fireBlockedLogged = true
                        FileLog.i("DomeVR-browser", "BLOCKED repeat dwell row=$highlight prog=$browProgF")
                    }
                    return
                }
            }
        }
        // not hovering the panel: hide cursor, drain progress (no zeroing).
        // Reset the row-fired latch so looking back re-arms shrink + fire.
        hitValid = false
        inXZone = false
        xDwellFired = false
        dwellFiredFor = -2
        lastFiredU = Float.NaN
        fireBlockedLogged = false
        sliderHoverU = -1f
        xProgF = maxOf(0f, xProgF - 16f / 600f)
        browProgF = maxOf(0f, browProgF - 16f / 600f)
    }

    private fun ensureVisible() {
        if (highlight < scroll) scroll = highlight
        else if (highlight >= scroll + VISIBLE_ROWS) scroll = highlight - VISIBLE_ROWS + 1
        scroll = scroll.coerceIn(0, maxOf(0, browserRows.size - VISIBLE_ROWS))
    }

    private fun now() = System.currentTimeMillis()

    /** Angle between two head-forward vectors, degrees. */
    private fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(dot).toDouble()).toFloat()
    }

    /** Panel-wide gaze fraction -> slider-bar fraction (bar spans x 44..1000 of TEX 1024). */
    private fun barFrac(u: Float) = ((u * TEX - 44f) / 956f).coerceIn(0f, 1f)

    /** Panel-wide gaze fraction -> seek-bar fraction (bar spans x 24..1000 of 1024). */
    private fun seekFrac(u: Float) = ((u * 1024f - 24f) / 976f).coerceIn(0f, 1f)

    // ---------- play menu ----------
    // World-locked panel floating up (or down) in the recentered frame.
    // Look past the side's trigger angle to open; the close band sits 25° lower.
    // The pointer stays hidden during video until the menu opens.
    /** Windowed stillness gate: displacement over the trailing ~250ms.
     *  Per-frame deltas are useless — game-RV jitter trips a per-frame
     *  gate ~30×/s (see the menu motion-reset bursts in the log), so
     *  dwell can only accumulate during lucky-still streaks. Over a
     *  window, zero-mean noise cancels while real motion accumulates. */
    private class MotionStillness(private val windowMs: Long, private val limitDeg: Float) {
        private val refM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private var refT = 0L
        private var init = false
        private val tmpA = FloatArray(16)
        private val tmpB = FloatArray(16)
        /** Call on the GL thread with the live rotation matrix. */
        fun update(raw: FloatArray, nowMs: Long): Boolean {
            if (!init) {
                System.arraycopy(raw, 0, refM, 0, 16)
                refT = nowMs
                init = true
                return true
            }
            Matrix.transposeM(tmpA, 0, refM, 0)
            Matrix.multiplyMM(tmpB, 0, tmpA, 0, raw, 0)
            val tr = tmpB[0] + tmpB[5] + tmpB[10]
            val ang = Math.toDegrees(
                kotlin.math.acos(((tr - 1f) / 2f).toDouble().coerceIn(-1.0, 1.0))
            ).toFloat()
            if (nowMs - refT >= windowMs) {
                System.arraycopy(raw, 0, refM, 0, 16)
                refT = nowMs
            }
            return ang < limitDeg
        }
    }
    private val menuStill = MotionStillness(250L, 4f)
    private val browserStill = MotionStillness(250L, 2.5f)
    // leaky dwell integrators: progress grows while still on target and
    // drains slowly otherwise. Resets can never win against tremor or
    // target churn — flicker only dents progress instead of zeroing it.
    private val menuProg = FloatArray(15)
    private var menuProgT = 0L
    private var browProgF = 0f
    private var browProgT = 0L
    private fun menuSlot(id: Int) = if (id == -1) 14 else id
    private fun menuUsable(id: Int) = id != -2
    // Play-menu column geometry shared by drawing + hit-testing: 11 columns
    // across the middle 5/6, with wider empty gaps (no divider lines) after
    // transport (7), zoom (8) and volume (9). menuColXs[c] = left edge of col c.
    private val menuColXs: FloatArray by lazy {
        val W = 1024f
        val gap = 20f
        val bw = (W * 5f / 6f - 3f * gap) / 11f
        val xs = FloatArray(12)
        var x = W / 12f
        for (c in 0..11) {
            xs[c] = x
            if (c < 11) {
                x += bw
                if (c == 7 || c == 8 || c == 9) x += gap
            }
        }
        xs
    }

    /** Effective open angle (always 10..60) and side from the ⇅ toggle. */
    private fun menuOpenAngle(): Float =
        if (menuSideUp) menuAngleUp.coerceIn(10f, 60f)
        else kotlin.math.abs(menuAngleDown).coerceIn(10f, 60f)
    private fun menuIsBelow(): Boolean = !menuSideUp
    /** Menu panel elevation: the whole panel floats above the open angle
     *  (center = angle + 12°, half-height ~10°), so looking at any button
     *  keeps you above the trigger. No hysteresis anywhere: open at/above
     *  the angle, closed below it. */
    private fun menuElevDeg(): Float =
        ((menuOpenAngle() + 12f).coerceAtMost(85f)) * (if (menuIsBelow()) -1f else 1f)
    /** Animated elevation for the ⇅ flip: smooth sweep through the middle,
     *  settling on the target side. */
    fun menuElevCurrent(): Float {
        val target = menuElevDeg()
        val dt = now() - menuAnimT0
        if (dt >= menuAnimMs) return target
        val t = (dt.toFloat() / menuAnimMs).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return menuAnimFrom + (target - menuAnimFrom) * s
    }
    private fun menuHalfW(): Float = panelDistM * 0.465f
    private fun menuHalfH(): Float = menuHalfW() * 0.20f

    // ---------- play menu ----------

    /** Circle-to-recenter gesture master switch (2D settings, default on). */
    @Volatile var circleGestureEnabled = true
    /** UI-thread request: close the menu and arm the aim pointer (GL consumes). */
    @Volatile private var aimRequest = false
    /** Aim pointer live. Volatile: armed/cleared on GL, read on UI (tap cancels). */
    @Volatile private var aimArmed = false
    // GL-thread aim dwell state (head-locked big blue pointer, 2x dwell)
    private var aimProg = 0f
    private var aimT = 0L
    private var aimArmedAt = 0L
    private val aimW = FloatArray(3)
    // circle detector: fixed ring buffer, zero per-frame allocation
    // (160 slots ≈ 5.3s at the 33ms sample step: the window must be
    // longer than the gesture, or the first loop ages out mid-draw)
    private val circT = LongArray(160)
    private val circX = FloatArray(160)
    private val circY = FloatArray(160)
    private var circHead = 0
    private var circN = 0
    private var circLastT = 0L
    private var circCooldownUntil = 0L
    private var circNearT = 0L

    /** Dwell the toolbar button / draw a circle to call this: the menu
     *  closes and a big blue pointer arms — stare at the new forward for
     *  2x the gaze delay and the snap fires there. */
    fun requestAim() { aimRequest = true }
    /** Tap while armed cancels the aim instead of snapping. True if consumed. */
    fun cancelAim(): Boolean {
        if (!aimArmed && !aimRequest) return false
        aimArmed = false; aimRequest = false; aimProg = 0f
        FileLog.i("DomeVR-menu", "aim cancelled")
        return true
    }

    /** Aim dwell: progress grows while still (2x the menu dwell), drains
     *  on motion; at full the basis snaps to the faced direction. */
    private fun updateAim() {
        val nowMs = now()
        val dtMs = (nowMs - aimT).coerceIn(0L, 500L)
        aimT = nowMs
        if (nowMs - aimArmedAt > 15000L) {
            aimArmed = false; aimProg = 0f
            FileLog.i("DomeVR-menu", "aim timeout")
            return
        }
        val still = synchronized(rawM) { menuStill.update(rawM, nowMs) }
        if (still) aimProg += dtMs / dwellMs.toFloat()
        else aimProg = maxOf(0f, aimProg - dtMs / 600f)
        val d = panelDistM
        val f = lastEffFwd
        aimW[0] = f[0] * d; aimW[1] = f[1] * d; aimW[2] = f[2] * d
        if (aimProg >= 1f) {
            aimProg = 0f; aimArmed = false
            recenter("aim")
            FileLog.i("DomeVR-menu", "aim FIRE -> recenter")
        }
    }

    /** Circle-to-recenter: signed turning angle of the forward-vector
     *  trail in the x/y plane. ONE full loop accumulates to ±~300°;
     *  nods, shakes and look-and-returns self-cancel to ~0, which is
     *  what makes circles robust where linear swipes false-positive.
     *  Fires aim mode (never a blind snap). */
    private fun updateCircle() {
        val nowMs = now()
        if (nowMs < circCooldownUntil) return
        if (nowMs - circLastT < 33L) return
        circLastT = nowMs
        val f = lastEffFwd
        circT[circHead] = nowMs; circX[circHead] = f[0]; circY[circHead] = f[1]
        circHead = (circHead + 1) % circT.size
        if (circN < circT.size) circN++
        // window: trailing 5000ms, oldest -> newest
        var m = 0
        var mx = 0f; var my = 0f
        var idx = (circHead - circN + circT.size * 2) % circT.size
        for (k in 0 until circN) {
            val t = circT[idx]
            if (nowMs - t <= 5000L) {
                winT[m] = t; winX[m] = circX[idx]; winY[m] = circY[idx]
                mx += winX[m]; my += winY[m]; m++
            }
            idx = (idx + 1) % circT.size
        }
        if (m < 18) return
        val span = winT[m - 1] - winT[0]
        if (span < 600L) return
        mx /= m; my /= m
        var maxR = 0f
        for (i in 0 until m) {
            val dx = winX[i] - mx; val dy = winY[i] - my
            maxR = maxOf(maxR, kotlin.math.sqrt(dx * dx + dy * dy))
        }
        val ex = winX[m - 1] - winX[0]; val ey = winY[m - 1] - winY[0]
        val closure = kotlin.math.sqrt(ex * ex + ey * ey)
        var accum = 0.0; var pos = 0; var neg = 0; var travel = 0f
        for (i in 1 until m - 1) {
            val ax = winX[i] - winX[i - 1]; val ay = winY[i] - winY[i - 1]
            val bx = winX[i + 1] - winX[i]; val by = winY[i + 1] - winY[i]
            val la = kotlin.math.sqrt(ax * ax + ay * ay)
            val lb = kotlin.math.sqrt(bx * bx + by * by)
            if (la < 0.004f || lb < 0.004f) continue
            travel += la
            val a = Math.atan2((ax * by - ay * bx).toDouble(), (ax * bx + ay * by).toDouble())
            accum += a
            if (a > 0) pos++ else neg++
        }
        val signFrac = if (pos + neg > 0) maxOf(pos, neg).toFloat() / (pos + neg) else 0f
        val accDeg = Math.toDegrees(accum)
        fun stats() = "span=${span}ms travel=${"%.2f".format(travel)} " +
            "acc=${accDeg.toInt()}° maxR=${"%.3f".format(maxR)} " +
            "close=${"%.3f".format(closure)} sign=${"%.2f".format(signFrac)}"
        // sign ≥0.60: measured real circles score 0.65-0.74 (heads
        // wobble), random motion ~0.52 — margin on both sides
        if (maxR in 0.09f..0.65f && closure <= 0.18f && travel >= 1.0f &&
            kotlin.math.abs(accum) >= 5.2 && signFrac >= 0.6f
        ) {
            circN = 0
            circCooldownUntil = nowMs + 3000L
            aimRequest = true
            FileLog.i("DomeVR-circle", "FIRE x2 ${stats()}")
            return
        }
        // tuning capture: real rotation that didn't qualify (throttled 2s).
        // The numbers say which guard failed: acc (need ±300° one loop),
        // maxR (need 0.09..0.65 ≈ 5..40° radius), close (need ≤0.18),
        // travel (need ≥1.0), sign (need ≥0.60), span.
        if (kotlin.math.abs(accum) > 2.6 && nowMs - circNearT > 2000L) {
            circNearT = nowMs
            FileLog.i("DomeVR-circle", "near-miss ${stats()}")
        }
    }
    // scratch window for the circle detector (fields, never allocated per frame)
    private val winT = LongArray(160)
    private val winX = FloatArray(160)
    private val winY = FloatArray(160)

    private fun updateMenu() {
        val fwd = lastEffFwd
        // recenter aim flow: consume the UI-thread request, close the menu,
        // arm the big blue pointer (suppresses the open logic below)
        if (aimRequest) {
            aimRequest = false
            if (menuWasOpen) FileLog.i("DomeVR-menu", "menu close (aim)")
            menuOpen = false; menuHitValid = false; menuStickyValid = false
            menuHighlight = -2; menuDwellFiredFor = -3
            menuSeekHoverU = -1f
            menuBelowSince = 0L
            menuWasOpen = false
            menuProgFresh = true
            aimArmed = true; aimProg = 0f; aimT = now(); aimArmedAt = aimT
            circN = 0
            FileLog.i("DomeVR-menu", "aim armed")
        }
        if (aimArmed) { updateAim(); return }
        // circle gesture: menu-closed VIDEO only; stale arcs die when the menu opens
        if (!menuOpen) { if (circleGestureEnabled) updateCircle() }
        else if (circN > 0) circN = 0
        // Trigger metric: head-TILT (angle of the head-up vector from
        // vertical), NOT gaze elevation. asin(fwd.y) conflates yaw with
        // pitch once the head is tilted back: yawing ±30° about the tilted
        // neck axis swings gaze on a cone whose elevation falls ~20°
        // (77°→57°), closing the menu exactly when reaching for the end
        // buttons. Head-up is preserved by yaw (local-Y rotation) exactly,
        // so tilt = atan2(up.z, up.y) is yaw-invariant: + = tipped back,
        // - = tipped forward, roll reads ~0 (never opens).
        val up = lastEffUp
        val tilt = Math.toDegrees(kotlin.math.atan2(up[2].toDouble(), up[1].toDouble())).toFloat()
        val ang = menuOpenAngle()
        val below = menuIsBelow()
        // Open exactly at the angle; close ~4.2° lower (a third of the
        // original 12.5°). The band is load-bearing, not hysteresis-
        // for-comfort: reaching the panel ends dips the tilt reading
        // ~15-20° (head-yaw cone geometry), so too tight a close
        // threshold strobes the menu (and every dwell) while operating
        // the end buttons. Opening is still exact at the angle.
        // Closing additionally needs 400ms continuously below, killing
        // sensor-noise flapping at the boundary.
        val openAt = ang
        val closeAt = (ang - 12.5f / 3f).coerceAtLeast(2.5f)
        val above = if (!below) tilt >= (if (!menuOpen) openAt else closeAt)
            else tilt <= -(if (!menuOpen) openAt else closeAt)
        val isUp: Boolean
        if (above) {
            menuBelowSince = 0L
            isUp = true
        } else if (!menuOpen) {
            menuBelowSince = 0L
            isUp = false
        } else {
            if (menuBelowSince == 0L) menuBelowSince = now()
            isUp = now() - menuBelowSince < 400
        }
        if (!isUp) {
            menuOpen = false; menuHitValid = false; menuStickyValid = false
            menuHighlight = -2; menuDwellFiredFor = -3
            menuSeekHoverU = -1f
            menuBelowSince = 0L
            if (menuWasOpen) FileLog.i("DomeVR-menu", "menu close")
            menuWasOpen = false
            menuProgFresh = true
            return
        }
        menuOpen = true
        menuWasOpen = true
        // fresh progress each open: stale banks must never insta-fire
        if (menuProgFresh) {
            menuProgFresh = false
            for (i in menuProg.indices) menuProg[i] = 0f
            menuProgT = now()
        }
        // Windowed stillness: displacement over 250ms, immune to the
        // per-frame sensor jitter that trips instant gates ~30×/s.
        val nowMs = now()
        var still = true
        synchronized(rawM) { still = menuStill.update(rawM, nowMs) }
        // fixed world-locked panel (no yaw following): center straight up
        // in the recentered frame, facing the viewer. The bar is 1.5x the
        // button row: buttons live in the middle 5/6, blank 1/12 margins
        // either side are dead zones (highlight -2, no fire).
        // Uses the animated elevation so the pointer tracks the ⇅ flip.
        val d = panelDistM
        val el = Math.toRadians(menuElevCurrent().toDouble()).toFloat()
        val cx = 0f; val cy = (kotlin.math.sin(el) * d); val cz = (-kotlin.math.cos(el) * d)
        // normal toward viewer
        var nx = -cx; var ny = -cy; var nz = -cz
        val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        nx /= nl; ny /= nl; nz /= nl
        val denom = fwd[0] * nx + fwd[1] * ny + fwd[2] * nz
        if (denom < -0.05f) {
            val t = (cx * nx + cy * ny + cz * nz) / denom
            val hx = fwd[0] * t; val hy = fwd[1] * t; val hz = fwd[2] * t
            // The pointer tracks the gaze freely on the panel plane — even
            // above/below/outside the panel — so it never parks at an edge.
            // Only highlight/fire are confined to the panel itself.
            menuStickyValid = true
            menuStickyW = floatArrayOf(hx, hy, hz)
            // panel up-vector in world: up = n × right, right = (1,0,0)
            val ux = 0f; val uy = nz; val uz = -ny
            val ul = kotlin.math.sqrt(uy * uy + uz * uz).coerceAtLeast(1e-6f)
            val alongUp = ((hx - cx) * ux + (hy - cy) * (uy / ul) + (hz - cz) * (uz / ul))
            val mhw = menuHalfW(); val mhh = menuHalfH()
            val u = ((hx - cx) + mhw) / (2 * mhw)
            val v = (mhh - alongUp) / (2 * mhh)
            if (u >= 0f && u <= 1f && v >= 0f && v <= 1f) {
                menuHitValid = true
                menuHitW = floatArrayOf(hx, hy, hz)
                val ub = (u - 1f / 12f) / (5f / 6f)
                val id = if (v > 0.68f) -1 else if (ub < 0f || ub > 1f) -2
                    else {
                        // column from the shared menuColXs geometry (gaps
                        // between groups are dead and report -2)
                        val bx = ub * (1024f * 5f / 6f)
                        var col = -1
                        for (cc in 0 until 11) {
                            if (bx >= menuColXs[cc] - 1024f / 12f &&
                                bx < menuColXs[cc + 1] - 1024f / 12f
                            ) { col = cc; break }
                        }
                        if (col < 0) -2
                        else when (col) {
                            in 0..7 -> col
                            8 -> if (v < 0.415f) 8 else 9
                            9 -> if (v < 0.415f) 10 else 11
                            // recenter (13) stacked above flip (12)
                            else -> if (v < 0.415f) 13 else 12
                        }
                    }
                menuSeekHoverU = if (id == -1) seekFrac(u) else -1f
                // Leaky dwell: adopt immediately; progress grows while still
                // on target and drains slowly otherwise. Churn and motion
                // only dent progress instead of zeroing the timer.
                if (id != menuHighlight) {
                    menuHighlight = id; menuDwellFiredFor = -3
                    // cap carried progress on adopt: churn can never bank
                    // a full dwell, and stale slots can't insta-fire
                    if (menuUsable(id)) menuProg[menuSlot(id)] = minOf(menuProg[menuSlot(id)], 0.3f)
                    FileLog.i("DomeVR-menu", "dwell start: id=$id tilt=${tilt.toInt()}°")
                }
                val slot = menuSlot(id)
                val dtMs = (nowMs - menuProgT).coerceIn(0L, 500L)
                menuProgT = nowMs
                for (i in menuProg.indices)
                    if (i != slot) menuProg[i] = maxOf(0f, menuProg[i] - dtMs / 600f)
                // -2 = blank margin: hover shows the pointer, never fires.
                // Hold shrunken state after firing; re-entry restarts it.
                if (still && menuUsable(id) && menuHighlight != menuDwellFiredFor)
                    menuProg[slot] += dtMs / dwellMs.toFloat()
                if (still && menuUsable(id) && menuProg[slot] >= 1f && menuHighlight != menuDwellFiredFor) {
                    menuDwellFiredFor = menuHighlight
                    menuProg[slot] = 1f
                    FileLog.i("DomeVR-menu", "FIRE id=$id")
                    if (id == -1) onMenuEvent(MenuEvent.Seek(seekFrac(u))) else onMenuEvent(MenuEvent.Press(id))
                    return
                }
                return
            }
        }
        // off-panel: log the transition once (menuHitValid still true from
        // the last hitting frame), then clear highlight (progress per slot
        // is kept and drains slowly, so brief leaves are forgiven)
        if (menuHitValid)
            FileLog.i("DomeVR-menu", "left panel (was id=$menuHighlight)")
        menuHitValid = false
        menuSeekHoverU = -1f
        if (menuHighlight != -2) {
            menuHighlight = -2; menuDwellFiredFor = -3
        }
    }

    // ---------- drawing ----------
    private fun drawVideo(eye: Int, warpCx: Float, aspect: Float) {
        val m = mesh ?: return
        GLES20.glUseProgram(progOes)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(uTexOes, 0)
        GLES20.glUniform1i(uStereoOes, when (stereo) { Stereo.MONO -> 0; Stereo.SBS -> 1; Stereo.TB -> 2 })
        GLES20.glUniform1i(uEyeOes, eye)
        GLES20.glUniform1f(uZoomOes, zoom.coerceIn(0.3f, 2.5f))
        GLES20.glUniformMatrix4fv(uMvpOes, 1, false, mvpM, 0)
        setWarp(uWarpOnOes, uWarpCxOes, uWarpK1Oes, uWarpK2Oes, uWarpAspectOes, warpCx, aspect)
        GLES20.glEnableVertexAttribArray(aPosOes)
        GLES20.glVertexAttribPointer(aPosOes, 3, GLES20.GL_FLOAT, false, 0, m.verts)
        GLES20.glEnableVertexAttribArray(aTexOes)
        GLES20.glVertexAttribPointer(aTexOes, 2, GLES20.GL_FLOAT, false, 0, m.tex)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.indexCount, GLES20.GL_UNSIGNED_SHORT, m.indices)
        GLES20.glDisableVertexAttribArray(aPosOes)
        GLES20.glDisableVertexAttribArray(aTexOes)
    }

    /** Bilinear grid over the quad (p00 top-left, p10 top-right, p01
     *  bottom-left, p11 bottom-right). Per-vertex lens warp needs real
     *  vertices across the surface — a 2-triangle quad warps wrong. */
    private fun gridQuadP(
        p00: FloatArray, p10: FloatArray, p01: FloatArray, p11: FloatArray,
        nx: Int, ny: Int
    ): Mesh {
        val verts = FloatArray((nx + 1) * (ny + 1) * 3)
        val texs = FloatArray((nx + 1) * (ny + 1) * 2)
        var vi = 0; var ti = 0
        for (iy in 0..ny) {
            val v = iy.toFloat() / ny
            for (ix in 0..nx) {
                val u = ix.toFloat() / nx
                for (k in 0..2) {
                    val top = p00[k] + (p10[k] - p00[k]) * u
                    val bot = p01[k] + (p11[k] - p01[k]) * u
                    verts[vi++] = top + (bot - top) * v
                }
                texs[ti++] = u; texs[ti++] = v
            }
        }
        val idx = mutableListOf<Short>()
        for (iy in 0 until ny) for (ix in 0 until nx) {
            val a = (iy * (nx + 1) + ix).toShort()
            val b = (a + 1).toShort(); val c = ((iy + 1) * (nx + 1) + ix).toShort(); val d = (c + 1).toShort()
            idx += listOf(a, c, b, b, c, d)
        }
        return Mesh(fb(verts), fb(texs), sb(idx.toShortArray()), idx.size)
    }

    private fun drawMesh2d(m: Mesh, texId: Int, mat: FloatArray, warpCx: Float, aspect: Float) {
        GLES20.glUseProgram(prog2d)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTex2d, 0)
        GLES20.glUniformMatrix4fv(uMvp2d, 1, false, mat, 0)
        setWarp(uWarpOn2d, uWarpCx2d, uWarpK12d, uWarpK22d, uWarpAspect2d, warpCx, aspect)
        GLES20.glEnableVertexAttribArray(aPos2d)
        GLES20.glVertexAttribPointer(aPos2d, 3, GLES20.GL_FLOAT, false, 0, m.verts)
        GLES20.glEnableVertexAttribArray(aTex2d)
        GLES20.glVertexAttribPointer(aTex2d, 2, GLES20.GL_FLOAT, false, 0, m.tex)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.indexCount, GLES20.GL_UNSIGNED_SHORT, m.indices)
        GLES20.glDisableVertexAttribArray(aPos2d)
        GLES20.glDisableVertexAttribArray(aTex2d)
    }

    private var browserGrid: Mesh? = null
    private var browserGridD = -1f
    private var browserGridEl = -999f
    private fun drawBrowser(warpCx: Float, aspect: Float) {
        maybeUploadBrowser()
        val d = panelDistM; val hw = panelHalfW(); val hh = panelHalfH()
        // rotation-only UI matrix: identical in both eyes, always fuses.
        // Grid cached: rebuilding it per frame churned direct buffers and
        // strobed the whole scene through GC. Panel floats at browserElevDeg
        // (0 = centered; elevated = below the play menu, facing viewer).
        val el = Math.toRadians(browserElevDeg.toDouble()).toFloat()
        if (browserGrid == null || browserGridD != d || browserGridEl != el) {
            val cx = 0f; val cy = (kotlin.math.sin(el) * d); val cz = (-kotlin.math.cos(el) * d)
            var nx = -cx; var ny = -cy; var nz = -cz
            val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            nx /= nl; ny /= nl; nz /= nl
            // up = n × (1,0,0) = (0, nz, -ny)
            val ux = 0f; val uy = nz; val uz = -ny
            fun corner(sx: Float, sy: Float) = floatArrayOf(
                cx + sx * hw + ux * sy * hh,
                cy + uy * sy * hh,
                cz + uz * sy * hh
            )
            browserGrid = gridQuadP(
                corner(-1f, 1f), corner(1f, 1f), corner(-1f, -1f), corner(1f, -1f), 12, 8
            )
            browserGridD = d; browserGridEl = el
            FileLog.i("DomeVR-browser", "grid rebuild d=$d el=$el")
        }
        drawMesh2d(browserGrid!!, browserTexId, flatM, warpCx, aspect)
    }

    private fun makeReticle(color: Int): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // transparent background (needs BLEND enabled); small ring that
        // the draw code shrinks toward a point as dwell progresses
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = 7f
        c.drawCircle(48f, 48f, 30f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(48f, 48f, 5f, p)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return tex[0]
    }

    /** Shaping preview cell: distorted 9x9 grid + moved dots (amber→red by
     *  magnitude) + displacement vectors + minimum convex polygon of moved
     *  points. Box is 56px at row left; grid coords [0,1], y down. */
    private fun drawShapePreview(c: Canvas, p: Paint, r: BrowserRow, y: Int) {
        val mags = r.previewMags ?: return
        val pos = r.previewPos ?: return
        val n = r.previewN.coerceAtLeast(2)
        if (mags.size < n * n || pos.size < n * n * 2) return
        val bx0 = 28f; val by0 = y.toFloat() + 4f; val bs = 56f
        fun px(j: Int) = bx0 + pos[j * 2].toFloat().coerceIn(-0.2f, 1.2f) * bs
        fun py(j: Int) = by0 + pos[j * 2 + 1].toFloat().coerceIn(-0.2f, 1.2f) * bs
        fun nx(j: Int) = bx0 + (j % n).toFloat() / (n - 1) * bs
        fun ny(j: Int) = by0 + (j / n).toFloat() / (n - 1) * bs
        // convex hull fill + stroke
        val hull = r.previewHull
        if (hull != null && hull.size >= 3) {
            val path = Path()
            path.moveTo(px(hull[0]), py(hull[0]))
            for (k in 1 until hull.size) path.lineTo(px(hull[k]), py(hull[k]))
            path.close()
            p.style = Paint.Style.FILL; p.color = Color.argb(40, 8, 145, 178)
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.rgb(8, 145, 178)
            c.drawPath(path, p)
            p.style = Paint.Style.FILL; p.strokeWidth = 1f
        }
        // distorted grid lines
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = Color.rgb(150, 150, 150)
        for (i in 0 until n) {
            val rowPath = Path()
            rowPath.moveTo(px(i * n), py(i * n))
            for (j in 1 until n) rowPath.lineTo(px(i * n + j), py(i * n + j))
            c.drawPath(rowPath, p)
            val colPath = Path()
            colPath.moveTo(px(i), py(i))
            for (k in 1 until n) colPath.lineTo(px(k * n + i), py(k * n + i))
            c.drawPath(colPath, p)
        }
        p.style = Paint.Style.FILL
        // displacement vectors (nominal -> offset), faint (style still STROKE)
        p.color = Color.argb(120, 125, 211, 252); p.strokeWidth = 1f
        for (j in mags.indices) {
            if (mags[j] <= 1e-6f) continue
            c.drawLine(nx(j), ny(j), px(j), py(j), p)
        }
        p.style = Paint.Style.FILL
        // dots: grey unmoved, amber->red moved
        for (j in mags.indices) {
            val m = mags[j].coerceIn(0f, 1f)
            val x = px(j); val yy = py(j)
            if (m <= 1e-6f) {
                p.color = Color.rgb(170, 170, 170)
                c.drawCircle(x, yy, 2f, p)
            } else {
                val t = m.coerceAtLeast(0.15f)
                p.color = Color.rgb(255, (200 - 170 * t).toInt(), (60 - 40 * t).toInt())
                c.drawCircle(x, yy, 2f + 3f * t, p)
            }
        }
        p.strokeWidth = 1f
    }

    private fun maybeUploadBrowser() {
        ensureVisible()
        val rows = browserRows
        val end = (scroll + VISIBLE_ROWS).coerceAtMost(rows.size)
        var h = browserTitle.hashCode() * 31 + scroll
        for (i in scroll until end) {
            h = h * 31 + rows[i].label.hashCode() * 7 + rows[i].meta.hashCode() + rows[i].segSelected
            // shaping previews change with weights/toggles: sample magnitudes into the hash
            val pm = rows[i].previewMags
            if (pm != null) {
                var j = 0
                while (j < pm.size) { h = h * 31 + (pm[j] * 1000).toInt(); j += 7 }
                h = h * 31 + (rows[i].previewHull?.size ?: 0)
            }
        }
        h = h * 31 + highlight + (if (inXZone) 1009 else 0) +
            (if (sliderHoverU >= 0f) (sliderHoverU * 128).toInt() else 0)
        if (h == lastPanelHash && browserBitmap != null) return
        lastPanelHash = h
        val bmp = Bitmap.createBitmap(TEX, TEX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(13, 20, 28))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; p.textSize = 44f
        c.drawText(browserTitle.take(30), 40f, 72f, p)
        // X close button, top right (hit zone u>0.90, above ROWS_Y0)
        if (inXZone) {
            p.color = Color.rgb(30, 58, 95)
            c.drawRect(920f, 16f, 1004f, 96f, p)
        }
        p.color = Color.WHITE; p.textSize = 44f; p.textAlign = Paint.Align.CENTER
        c.drawText("✕", 962f, 72f, p)
        p.textAlign = Paint.Align.LEFT
        var y = ROWS_Y0
        for (i in scroll until end) {
            val r = rows[i]
            if (i == highlight) {
                p.color = Color.rgb(30, 58, 95)
                c.drawRect(20f, y.toFloat(), 1004f, (y + ROW_H).toFloat(), p)
            }
            if (r.previewMags != null && r.previewPos != null) {
                // shaping preview row: mini 9x9 grid with moved dots, hull + vectors
                drawShapePreview(c, p, r, y)
                p.color = Color.WHITE; p.textSize = 30f; p.textAlign = Paint.Align.LEFT
                c.drawText(r.label.take(24), 100f, (y + 36).toFloat(), p)
                if (r.meta.isNotEmpty()) {
                    p.color = Color.rgb(148, 163, 184); p.textSize = 20f
                    c.drawText(r.meta.take(40), 100f, (y + 58).toFloat(), p)
                }
            } else if (r.dead) {
                // rest zone: thin divider, nothing to activate
                p.color = Color.rgb(51, 65, 85)
                c.drawRect(44f, (y + ROW_H / 2 - 1).toFloat(), 1000f, (y + ROW_H / 2 + 1).toFloat(), p)
            } else if (r.segLabels.isNotEmpty()) {
                // segmented button row: N equal buttons across the row width
                val n = r.segLabels.size
                val x0 = 20f; val x1 = 1004f
                val bw = (x1 - x0) / n
                p.textSize = 30f; p.textAlign = Paint.Align.CENTER
                for (s in 0 until n) {
                    val sx0 = x0 + s * bw + 3f
                    val sx1 = x0 + (s + 1) * bw - 3f
                    if (s == r.segSelected) {
                        p.color = Color.rgb(8, 145, 178)
                        c.drawRect(sx0, y.toFloat() + 6f, sx1, (y + ROW_H - 6).toFloat(), p)
                        p.color = Color.WHITE
                    } else {
                        p.color = Color.rgb(51, 65, 85)
                        c.drawRect(sx0, y.toFloat() + 6f, sx1, (y + ROW_H - 6).toFloat(), p)
                        p.color = Color.rgb(203, 213, 225)
                    }
                    c.drawText(r.segLabels[s].take(12), (sx0 + sx1) / 2f, (y + 41).toFloat(), p)
                }
                // live tooltip: name of the segment the gaze would select
                if (i == highlight && sliderHoverU >= 0f) {
                    val fx = ((sliderHoverU * 1024f - 20f) / 984f).coerceIn(0f, 0.999f)
                    val seg = (fx * n).toInt().coerceIn(0, n - 1)
                    val txt = r.segLabels[seg].take(12)
                    val cx = x0 + (seg + 0.5f) * bw
                    p.textSize = 20f
                    val tw = p.measureText(txt)
                    val bx0 = (cx - tw / 2f - 10f).coerceAtLeast(24f)
                    val bx1 = (cx + tw / 2f + 10f).coerceAtMost(1000f)
                    p.color = Color.rgb(10, 14, 22)
                    c.drawRect(bx0, (y + 18).toFloat(), bx1, (y + 46).toFloat(), p)
                    p.color = Color.WHITE
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3f
                    c.drawRect(bx0, (y + 18).toFloat(), bx1, (y + 46).toFloat(), p)
                    p.style = Paint.Style.FILL
                    c.drawText(txt, (bx0 + bx1) / 2f, (y + 39).toFloat(), p)
                }
                p.textAlign = Paint.Align.LEFT
            } else {
            if (r.slideKey == null) {
                val icon = when (r.kind) {
                    BrowserRow.FOLDER -> "📁"
                    BrowserRow.VIDEO -> "🎬"
                    BrowserRow.ACTION -> "⚙"
                    else -> "📄"
                }
                p.color = Color.WHITE; p.textSize = 36f
                c.drawText("$icon  ${r.label.take(30)}", 44f, (y + 34).toFloat(), p)
            }
            if (r.slideKey != null) {
                // gaze slider (compact): label + value on top line, bar
                // mid-row, live tooltip bubble below the bar at the gaze
                // position showing the value a dwell would select
                val frac = ((r.slideVal - r.slideMin) / (r.slideMax - r.slideMin)).coerceIn(0f, 1f)
                p.color = Color.WHITE; p.textSize = 28f
                c.drawText(r.label.take(30), 44f, (y + 26).toFloat(), p)
                p.color = Color.rgb(125, 211, 252); p.textSize = 20f; p.textAlign = Paint.Align.RIGHT
                c.drawText(r.meta.take(20), 1000f, (y + 26).toFloat(), p)
                p.textAlign = Paint.Align.LEFT
                p.color = Color.rgb(51, 65, 85)
                c.drawRect(44f, (y + 30).toFloat(), 1000f, (y + 42).toFloat(), p)
                p.color = Color.rgb(125, 211, 252)
                c.drawRect(44f, (y + 30).toFloat(), 44f + 956f * frac, (y + 42).toFloat(), p)
                if (i == highlight && sliderHoverU >= 0f) {
                    val hf = barFrac(sliderHoverU)
                    var rraw = r.slideMin + hf * (r.slideMax - r.slideMin)
                    val fm = r.slideFmt
                    if (fm != null && fm.snap > 0f) rraw = Math.round(rraw / fm.snap).toFloat() * fm.snap
                    val disp = rraw * (fm?.scale ?: 1f) + (fm?.offset ?: 0f)
                    val dec = fm?.decimals ?: 0
                    val num = if (dec == 0) Math.round(disp).toString()
                        else String.format(Locale.US, "%.${dec}f", disp)
                    val txt = num + (fm?.suffix ?: "")
                    val tx = (44f + hf * 956f).coerceIn(70f, 954f)
                    p.textSize = 16f; p.textAlign = Paint.Align.CENTER
                    val tw = p.measureText(txt)
                    val bx0 = (tx - tw / 2f - 10f).coerceAtLeast(24f)
                    val bx1 = (tx + tw / 2f + 10f).coerceAtMost(1000f)
                    p.color = Color.rgb(10, 14, 22)
                    c.drawRect(bx0, (y + 44).toFloat(), bx1, (y + 62).toFloat(), p)
                    p.color = Color.rgb(125, 211, 252)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                    c.drawRect(bx0, (y + 44).toFloat(), bx1, (y + 62).toFloat(), p)
                    p.style = Paint.Style.FILL
                    p.color = Color.WHITE
                    c.drawText(txt, (bx0 + bx1) / 2f, (y + 58).toFloat(), p)
                    p.textAlign = Paint.Align.LEFT
                }
            } else if (r.meta.isNotEmpty()) {
                p.color = Color.rgb(148, 163, 184); p.textSize = 24f
                c.drawText("    ${r.meta.take(56)}", 44f, (y + 58).toFloat(), p)
            }
            }
            y += ROW_H
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, browserTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        browserBitmap?.recycle()
        browserBitmap = bmp
    }

    override fun onFrameAvailable(st: SurfaceTexture?) { frameAvailable = true; arrivedFrames++ }

    // ---------- play menu drawing ----------
    private var menuGrid: Mesh? = null
    private var menuGridD = -1f
    private var menuGridEl = -999f
    private fun drawMenuPanel(warpCx: Float, aspect: Float) {
        maybeUploadMenu()
        // browser-pipeline panel (FBO + flatM with convergence): fuses
        // exactly like the browser panel. Animated elevation: the grid
        // rebuilds each frame mid-flip, then settles (cached).
        val d = panelDistM
        val el = Math.toRadians(menuElevCurrent().toDouble()).toFloat()
        if (menuGrid == null || menuGridD != d || menuGridEl != el) {
            val cx = 0f; val cy = (kotlin.math.sin(el) * d); val cz = (-kotlin.math.cos(el) * d)
            var nx = -cx; var ny = -cy; var nz = -cz
            val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            nx /= nl; ny /= nl; nz /= nl
            // up = n × (1,0,0) = (0, nz, -ny)
            val mhw = menuHalfW(); val mhh = menuHalfH()
            val ux = 0f; val uy = nz; val uz = -ny
            fun corner(sx: Float, sy: Float) = floatArrayOf(
                cx + sx * mhw + ux * sy * mhh,
                cy + uy * sy * mhh,
                cz + uz * sy * mhh
            )
            menuGrid = gridQuadP(
                corner(-1f, 1f), corner(1f, 1f), corner(-1f, -1f), corner(1f, -1f),
                16, 6
            )
            menuGridD = d; menuGridEl = el
        }
        drawMesh2d(menuGrid!!, menuTexId, flatM, warpCx, aspect)
    }

    private fun maybeUploadMenu() {
        val posSec = (menuPosMs / 1000).toInt()
        val durSec = (menuDurMs / 1000).toInt()
        val flashing = menuFlash.isNotEmpty() && now() < menuFlashUntil
        val h = menuHighlight * 31 + posSec * 131 + durSec * 17 +
            (if (menuPlaying) 1 else 0) + (if (flashing) 1009 else 0) + menuFlash.hashCode() +
            (if (menuSeekHoverU >= 0f) (menuSeekHoverU * 128).toInt() else 0) +
            menuTitle.hashCode() * 7 + skipSecs
        if (h == lastMenuHash && menuBitmap != null) return
        lastMenuHash = h
        val W = 1024; val H = 352
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(10, 14, 22))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // file name across the top
        p.color = Color.WHITE; p.textSize = 28f; p.textAlign = Paint.Align.CENTER
        c.drawText(menuTitle.take(48), W / 2f, 34f, p)
        p.textAlign = Paint.Align.LEFT
        val icons = arrayOf("⚙", "⧗", "📁", "⏮", "⏪", if (menuPlaying) "⏸" else "▶", "⏩", "⏭", "", "", "", "", "⇅", "")
        // 11 columns across the middle 5/6 with wider empty gaps between
        // the groups (transport | zoom | volume | recenter/flip) — see menuColXs.
        // 0-7 are full-height singles; zoom (8/9), volume (10/11) and
        // recenter (13) over flip (12) stack in one column each.
        // Icons only, no captions.
        fun drawBtn(id: Int, col: Int, y0: Float, y1: Float, isize: Float) {
            val x0 = menuColXs[col] + 4f; val x1 = menuColXs[col + 1] - 4f
            if (id == menuHighlight) {
                p.color = Color.rgb(30, 58, 95)
                c.drawRect(x0, y0, x1, y1, p)
            }
            val cx = (x0 + x1) / 2f
            val cy = (y0 + y1) / 2f
            if (id == 8 || id == 9) {
                // plain vector magnifier: stroked lens + handle, with the
                // +/- drawn inside the lens (emoji 🔍 turns to mush small)
                val r = isize * 0.30f
                val lx = cx - r * 0.35f; val ly = cy - r * 0.25f
                val sw = (isize * 0.09f).coerceAtLeast(3f)
                p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = sw
                c.drawCircle(lx, ly, r, p)
                val hx = lx + r * 0.72f; val hy = ly + r * 0.72f
                c.drawLine(hx, hy, hx + r * 0.85f, hy + r * 0.85f, p)
                p.style = Paint.Style.FILL
                val bw2 = r * 1.1f
                c.drawRect(lx - bw2 / 2f, ly - sw / 2f, lx + bw2 / 2f, ly + sw / 2f, p)
                if (id == 8) c.drawRect(lx - sw / 2f, ly - bw2 / 2f, lx + sw / 2f, ly + bw2 / 2f, p)
            } else if (id == 10 || id == 11) {
                // vector speaker: identical body geometry in both halves,
                // loud adds a second wave. Emoji 🔊/🔈 differ in width,
                // bearings AND ink height, so no text alignment can ever
                // line them up — drawing both from the same code does.
                val s = isize * 0.36f
                val sw = (isize * 0.09f).coerceAtLeast(3f)
                p.color = Color.WHITE
                p.style = Paint.Style.FILL
                val bx1 = cx - s * 0.35f
                c.drawRect(cx - s * 1.1f, cy - s * 0.55f, bx1, cy + s * 0.55f, p)
                val tipX = cx + s * 0.25f
                c.drawPath(android.graphics.Path().apply {
                    moveTo(bx1, cy - s * 0.55f); lineTo(tipX, cy - s)
                    lineTo(tipX, cy + s); lineTo(bx1, cy + s * 0.55f); close()
                }, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = sw
                fun wave(r: Float) = c.drawArc(
                    tipX - r, cy - r, tipX + r, cy + r, -55f, 110f, false, p)
                wave(s * 0.62f)
                if (id == 10) wave(s * 1.12f)
            } else if (id == 13) {
                // recenter crosshair: stroked ring + center dot
                val r = isize * 0.30f
                val sw = (isize * 0.09f).coerceAtLeast(3f)
                p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = sw
                c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, sw, p)
            } else {
                p.color = Color.WHITE; p.textSize = isize; p.textAlign = Paint.Align.CENTER
                c.drawText(icons[id], cx, cy + isize * 0.35f, p)
            }
        }
        for (i in 0..7) drawBtn(i, i, 56f, 236f, 60f)
        drawBtn(8, 8, 56f, 146f, 48f)
        drawBtn(9, 8, 146f, 236f, 48f)
        drawBtn(10, 9, 56f, 146f, 48f)
        drawBtn(11, 9, 146f, 236f, 48f)
        drawBtn(13, 10, 56f, 146f, 48f)
        drawBtn(12, 10, 146f, 236f, 48f)
        // hover tooltip: hovered button's name in the strip ABOVE the
        // buttons (the filename up there is briefly covered for center
        // columns — transient, only while hovering). Stacked halves share
        // their column's tooltip.
        if (menuHighlight >= 0) {
            val tipCol = when (menuHighlight) {
                in 0..7 -> menuHighlight
                8, 9 -> 8
                10, 11 -> 9
                else -> 10
            }
            val tips = arrayOf("settings", "shape", "files", "prev", "rew", "play",
                "ff", "next", "zoom+", "zoom−", "vol+", "vol−", "flip", "recenter")
            val txt = tips[menuHighlight]
            val ccx = (menuColXs[tipCol] + menuColXs[tipCol + 1]) / 2f
            p.textSize = 32f; p.textAlign = Paint.Align.CENTER
            val tw = p.measureText(txt)
            val bx0 = (ccx - tw / 2f - 14f).coerceAtLeast(8f)
            val bx1 = (ccx + tw / 2f + 14f).coerceAtMost(1016f)
            p.color = Color.rgb(10, 14, 22)
            c.drawRect(bx0, 6f, bx1, 54f, p)
            p.color = Color.rgb(125, 211, 252)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            c.drawRect(bx0, 6f, bx1, 54f, p)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            c.drawText(txt, (bx0 + bx1) / 2f, 40f, p)
        }
        p.textAlign = Paint.Align.LEFT
        // progress bar (seek zone)
        val frac = if (menuDurMs > 0) (menuPosMs.toFloat() / menuDurMs).coerceIn(0f, 1f) else 0f
        p.color = Color.rgb(51, 65, 85)
        c.drawRect(24f, 264f, (W - 24).toFloat(), 296f, p)
        p.color = Color.rgb(125, 211, 252)
        c.drawRect(24f, 264f, 24f + (W - 48) * frac, 296f, p)
        if (menuHighlight == -1) {
            p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 4f
            c.drawRect(24f, 264f, (W - 24).toFloat(), 296f, p)
            p.style = Paint.Style.FILL
        }
        // live tooltip: time the current gaze position would seek to,
        // just below the bar
        if (menuHighlight == -1 && menuSeekHoverU >= 0f && menuDurMs > 0) {
            val hf = menuSeekHoverU.coerceIn(0f, 1f)
            val txt = fmtTime((hf * menuDurMs).toLong())
            val tx = (24f + hf * (W - 48)).coerceIn(70f, (W - 70).toFloat())
            p.textSize = 15f; p.textAlign = Paint.Align.CENTER
            val tw = p.measureText(txt)
            val tx0 = (tx - tw / 2f - 10f).coerceAtLeast(8f)
            val tx1 = (tx + tw / 2f + 10f).coerceAtMost((W - 8).toFloat())
            p.color = Color.rgb(10, 14, 22)
            c.drawRect(tx0, 298f, tx1, 320f, p)
            p.color = Color.rgb(125, 211, 252)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            c.drawRect(tx0, 298f, tx1, 320f, p)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            c.drawText(txt, (tx0 + tx1) / 2f, 315f, p)
            p.textAlign = Paint.Align.LEFT
        }
        // time/length under the seek bar (32px). Skipped while the seek
        // tooltip bubble is up — it already shows the time at the gaze point
        // and the two would overlap.
        if (!(menuHighlight == -1 && menuSeekHoverU >= 0f && menuDurMs > 0)) {
            p.color = Color.WHITE; p.textSize = 32f; p.textAlign = Paint.Align.CENTER
            c.drawText(if (flashing) menuFlash else "${fmtTime(menuPosMs)} / ${fmtTime(menuDurMs)}", W / 2f, 342f, p)
            p.textAlign = Paint.Align.LEFT
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, menuTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        menuBitmap?.recycle()
        menuBitmap = bmp
    }

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---- meshes ----
    private data class Mesh(val verts: FloatBuffer, val tex: FloatBuffer, val indices: java.nio.ShortBuffer, val indexCount: Int)

    /** One enabled shaping grid: offsets in half-frame normalized units
     *  (ox +right, oy +down/grid-space), weight 0..1. Arrays never mutated
     *  after creation; the whole list is replaced atomically (volatile). */
    data class ActiveShape(val ox: FloatArray, val oy: FloatArray, val weight01: Float, val n: Int)
    @Volatile var shapingActive: List<ActiveShape> = emptyList()
    @Volatile var shapingRevision: Int = 0

    private fun buildMesh(proj: Projection): Mesh {
        val m = when (proj) {
            Projection.FLAT -> gridQuadP(
                floatArrayOf(-3.2f, 1.8f, -4f), floatArrayOf(3.2f, 1.8f, -4f),
                floatArrayOf(-3.2f, -1.8f, -4f), floatArrayOf(3.2f, -1.8f, -4f),
                24, 12
            )
            Projection.FISHEYE -> sphereSegment(180f, flipX = true)
            Projection.DEG180 -> sphereSegment(180f, stretchK = domeStretchK)
            Projection.DEG220 -> sphereSegment(220f)
            Projection.DEG270 -> sphereSegment(270f)
            Projection.DEG360 -> sphereSegment(360f)
        }
        return bakeShaping(m)
    }

    /** Bake the normalized weighted-average shaping grid into the video mesh
     *  UVs (texture space, so head tracking via MVP is unaffected). Mesh UVs
     *  are per-half-frame: u right, v up (GL origin). Grid space is y down,
     *  so grid_v = 1 - v and dy flips sign on write. No-op when nothing
     *  enabled (identity = same sampling as unwarped). */
    private fun bakeShaping(m: Mesh): Mesh {
        val active = shapingActive
        if (active.isEmpty()) return m
        val n = active[0].n
        var wsum = 0f
        for (a in active) if (a.n == n) wsum += a.weight01
        if (wsum <= 0f) return m
        // Combine on the fly per vertex (meshes are small: 25x49 sphere, 25x13 flat).
        val count = m.tex.capacity() / 2
        val out = FloatArray(m.tex.capacity())
        m.tex.rewind()
        for (k in 0 until count) {
            val u = m.tex.get()
            val v = m.tex.get()
            // bilinear sample of averaged offsets at grid coords (u, 1-v)
            var dx = 0f; var dy = 0f
            val gx = (u.coerceIn(0f, 1f) * (n - 1)).coerceIn(0f, (n - 1).toFloat())
            val gv = ((1f - v).coerceIn(0f, 1f) * (n - 1)).coerceIn(0f, (n - 1).toFloat())
            val x0 = gx.toInt().coerceAtMost(n - 2); val y0 = gv.toInt().coerceAtMost(n - 2)
            val fx = gx - x0; val fy = gv - y0
            for (a in active) {
                if (a.n != n) continue
                val w = a.weight01 / wsum
                fun at(ix: Int, iy: Int, arr: FloatArray) = arr[iy * n + ix]
                val ox = (at(x0, y0, a.ox) * (1 - fx) + at(x0 + 1, y0, a.ox) * fx) * (1 - fy) +
                         (at(x0, y0 + 1, a.ox) * (1 - fx) + at(x0 + 1, y0 + 1, a.ox) * fx) * fy
                val oy = (at(x0, y0, a.oy) * (1 - fx) + at(x0 + 1, y0, a.oy) * fx) * (1 - fy) +
                         (at(x0, y0 + 1, a.oy) * (1 - fx) + at(x0 + 1, y0 + 1, a.oy) * fx) * fy
                dx += w * ox; dy += w * oy
            }
            out[k * 2] = u + dx
            out[k * 2 + 1] = v - dy
        }
        m.tex.rewind()
        return Mesh(m.verts, fb(out), m.indices, m.indexCount)
    }

    private fun sphereSegment(deg: Float, flipX: Boolean = false, stretchK: Float = 0f): Mesh {
        val rows = 24; val cols = 48
        val r = 8f
        val yawMax = Math.toRadians((deg / 2).toDouble())
        val verts = mutableListOf<Float>(); val texs = mutableListOf<Float>()
        for (iy in 0..rows) {
            val v = iy.toFloat() / rows
            // Vertical stretch: dead-zone |p|<onset (onset 0.30 = starts 20% from top/bottom),
            // fast ramp to poles. Centre v=0.5 stays at pitch 0; poles stretched by (1+k).
            val p = v - 0.5f
            val absP = kotlin.math.abs(p)
            val onset = domeOnset.coerceIn(0f, 0.45f)
            val width = (0.5f - onset).coerceAtLeast(0.05f)
            val t = ((absP - onset) / width).coerceIn(0f, 1f)
            val s = 1f - (1f - t) * (1f - t)
            val stretch = 1f + stretchK * s
            val pitch = Math.PI * p * stretch
            for (ix in 0..cols) {
                val u = ix.toFloat() / cols
                val yaw = -yawMax + u * 2 * yawMax
                val x = (r * Math.cos(pitch) * Math.sin(yaw)).toFloat()
                val y = (r * Math.sin(pitch)).toFloat()
                val z = (-r * Math.cos(pitch) * Math.cos(yaw)).toFloat()
                verts += listOf(x, y, z)
                texs += listOf(if (flipX) 1f - u else u, 1f - v)
            }
        }
        val idx = mutableListOf<Short>()
        for (iy in 0 until rows) for (ix in 0 until cols) {
            val a = (iy * (cols + 1) + ix).toShort()
            val b = (a + 1).toShort(); val cc = ((iy + 1) * (cols + 1) + ix).toShort(); val d = (cc + 1).toShort()
            idx += listOf(a, cc, b, b, cc, d)
        }
        return Mesh(fb(verts.toFloatArray()), fb(texs.toFloatArray()), sb(idx.toShortArray()), idx.size)
    }

    private fun fb(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
    private fun sb(a: ShortArray): java.nio.ShortBuffer =
        ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); position(0) }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String, tag: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s) ?: "?"
                android.util.Log.e("DomeVR-GL", "$tag compile FAILED: $log")
                try { FileLog.e("DomeVR-GL", "$tag compile FAILED: $log") } catch (_: Throwable) {}
            }
            return s
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs, "VERT")
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs, "FRAG")
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v); GLES20.glAttachShader(it, f); GLES20.glLinkProgram(it)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(it) ?: "?"
                android.util.Log.e("DomeVR-GL", "link FAILED: $log")
                try { FileLog.e("DomeVR-GL", "link FAILED: $log") } catch (_: Throwable) {}
            }
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        }
    }
}
