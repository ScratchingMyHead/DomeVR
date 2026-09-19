/* DomeVR Player — GPL-3.0-only.
 * See LICENSE in the repository root.
 */
package net.domevr.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/** Browsing files on the phone itself (the "This device" top-level entry). */
object LocalFiles {
    data class LocalEntry(val name: String, val file: File, val isDir: Boolean, val size: Long)

    fun needsPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermission(): String {
        return if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun externalRoot(): File = Environment.getExternalStorageDirectory()

    fun isVideoFile(f: File): Boolean {
        if (!f.isFile) return false
        val n = f.name.lowercase()
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") ||
            n.endsWith(".mov") || n.endsWith(".webm") || n.endsWith(".m4v") ||
            n.endsWith(".ts") || n.endsWith(".mpg") || n.endsWith(".mpeg")
    }

    /** List a directory: subdirs first, then playable videos. Hidden dirs skipped. */
    fun list(dir: File): List<LocalEntry> {
        val kids = dir.listFiles() ?: return emptyList()
        return kids
            .filter { !it.name.startsWith(".") && (it.isDirectory || isVideoFile(it)) }
            .map { LocalEntry(it.name, it, it.isDirectory, if (it.isFile) it.length() else 0L) }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun toSmbEntry(e: LocalEntry): SmbEntry =
        SmbEntry(e.name, e.file.absolutePath, e.isDir, e.size)
}
