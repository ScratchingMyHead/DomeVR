/* DomeVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.domevr.player

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Encrypted store for SMB connections (passwords never touch plain prefs). */
class ConnectionStore(ctx: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "domevr_conns", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        ctx.getSharedPreferences("domevr_conns_fallback", Context.MODE_PRIVATE)
    }

    fun load(): MutableList<SmbConnection> {
        val raw = prefs.getString("connections", "[]") ?: "[]"
        val out = mutableListOf<SmbConnection>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += SmbConnection(
                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                name = o.optString("name", ""),
                host = o.optString("host", ""),
                share = o.optString("share", ""),
                domain = o.optString("domain", ""),
                username = o.optString("user", ""),
                password = o.optString("pass", "")
            )
        }
        return out
    }

    fun save(all: List<SmbConnection>) {
        val arr = JSONArray()
        for (c in all) {
            arr.put(JSONObject().apply {
                put("id", c.id); put("name", c.name); put("host", c.host)
                put("share", c.share); put("domain", c.domain)
                put("user", c.username); put("pass", c.password)
            })
        }
        prefs.edit().putString("connections", arr.toString()).apply()
    }
}

/** Non-secret UI prefs: display mode, last location, buffer. */
class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("domevr_settings", Context.MODE_PRIVATE)

    var projection: Projection
        get() = runCatching { Projection.valueOf(p.getString("projection", "DEG180")!!) }.getOrDefault(Projection.DEG180)
        set(v) { p.edit().putString("projection", v.name).apply() }
    var stereo: Stereo
        get() = runCatching { Stereo.valueOf(p.getString("stereo", "SBS")!!) }.getOrDefault(Stereo.SBS)
        set(v) { p.edit().putString("stereo", v.name).apply() }
    var startInVrBrowser: Boolean
        get() = p.getBoolean("vr_browser", true)
        set(v) { p.edit().putBoolean("vr_browser", v).apply() }
    var bufferKb: Int
        get() = p.getInt("buffer_kb", 256).coerceIn(32, 2048)
        set(v) { p.edit().putInt("buffer_kb", v).apply() }
    // --- optics / comfort ---
    /** Vertical FOV degrees per eye. Cardboard viewers are usually 60-75. */
    var fovDeg: Float
        get() = p.getFloat("fov", 68f).coerceIn(40f, 110f)
        set(v) { p.edit().putFloat("fov", v).apply() }
    /** Full interpupillary distance in mm. */
    var ipdMm: Float
        get() = p.getFloat("ipd", 64f).coerceIn(40f, 80f)
        set(v) { p.edit().putFloat("ipd", v).apply() }
    /** Swap left/right eye images (some viewers + phones need it). */
    var swapEyes: Boolean
        get() = p.getBoolean("swap_eyes", false)
        set(v) { p.edit().putBoolean("swap_eyes", v).apply() }
    /** Browser panel distance in meters. */
    var panelDistM: Float
        get() = p.getFloat("panel_d", 2.4f).coerceIn(1.2f, 5f)
        set(v) { p.edit().putFloat("panel_d", v).apply() }
    /** Video scale (flat screen size / sphere radius multiplier). */
    var videoZoom: Float
        get() = p.getFloat("zoom", 1f).coerceIn(0.3f, 2.5f)
        set(v) { p.edit().putFloat("zoom", v).apply() }
    /** Gaze dwell-to-select in ms. */
    var dwellMs: Long
        get() = p.getLong("dwell", 1500L).coerceIn(400L, 4000L)
        set(v) { p.edit().putLong("dwell", v).apply() }
    /** Pin video in front of the viewer (screen lock); off = look-around. */
    var pinVideo: Boolean
        get() = p.getBoolean("pin_video", false)
        set(v) { p.edit().putBoolean("pin_video", v).apply() }
    /** Diagnostics: drive head tracking with a scripted sweep instead of sensors. */
    var testSweep: Boolean
        get() = p.getBoolean("test_sweep", false)
        set(v) { p.edit().putBoolean("test_sweep", v).apply() }
    /** Play video without audio (isolates A/V clock/sync involvement). */
    var muteAudioTrack: Boolean
        get() = p.getBoolean("mute_audio_track", false)
        set(v) { p.edit().putBoolean("mute_audio_track", v).apply() }
    /** Prefer software video decoders (compat for streams that stall HW decoders). */
    var softwareDecode: Boolean
        get() = p.getBoolean("sw_decode", false)
        set(v) { p.edit().putBoolean("sw_decode", v).apply() }
    /** Last non-fisheye screen shape (the Lens toggle flips between this
     *  and FISHEYE without losing the dome setting). */
    var screenProj: Projection
        get() = runCatching { Projection.valueOf(p.getString("screen_proj", "DEG180")!!) }.getOrDefault(Projection.DEG180)
        set(v) { p.edit().putString("screen_proj", v.name).apply() }
    /** Cardboard lens distortion k1 (0..1, standard coefficients, 0.34 default). */
    var lensK1: Float
        get() = p.getFloat("lens_k1", 0.34f).coerceIn(0f, 1f)
        set(v) { p.edit().putFloat("lens_k1", v).apply() }
    /** Cardboard lens distortion k2 (0..1, standard coefficients, 0.55 default). */
    var lensK2: Float
        get() = p.getFloat("lens_k2", 0.55f).coerceIn(0f, 1f)
        set(v) { p.edit().putFloat("lens_k2", v).apply() }
    /** Look pitch (deg) that opens the VR play menu. Positive = look up,
     *  negative = look down. */
    var menuAngleDeg: Float
        get() = p.getFloat("menu_angle", 65f).coerceIn(-90f, 90f)
        set(v) { p.edit().putFloat("menu_angle", v).apply() }
    var lastConnectionId: String
        get() = p.getString("last_conn", "") ?: ""
        set(v) { p.edit().putString("last_conn", v).apply() }
    var lastPath: String
        get() = p.getString("last_path", "") ?: ""
        set(v) { p.edit().putString("last_path", v).apply() }
}
