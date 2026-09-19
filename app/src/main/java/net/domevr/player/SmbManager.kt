package net.domevr.player

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet

private const val FILE_ATTRIBUTE_DIRECTORY = 0x10

/**
 * SMB2/3 client over smbj. All blocking I/O on Dispatchers.IO.
 * Streaming is random-access via [SmbReadHandle] — the proxy seeks with
 * readAt() so playback never downloads the whole file.
 */
class SmbManager {
    private var client: SMBClient? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var boundConnId: String = ""

    @Volatile var connected: Boolean = false
        private set

    suspend fun connect(c: SmbConnection) = withContext(Dispatchers.IO) {
        disconnectLocked()
        val cl = SMBClient()
        val con = cl.connect(c.host)
        val auth = if (c.username.isBlank()) AuthenticationContext.anonymous()
            else AuthenticationContext(c.username, c.password.toCharArray(), c.domain)
        val sess = con.authenticate(auth)
        val disk = sess.connectShare(c.share) as DiskShare
        client = cl; session = sess; share = disk
        boundConnId = c.id; connected = true
    }

    suspend fun list(path: String): List<SmbEntry> = withContext(Dispatchers.IO) {
        val sh = share ?: error("not connected")
        val dir = path.ifBlank { "" }
        sh.list(dir).mapNotNull { info ->
            val nm = info.fileName
            if (nm == "." || nm == "..") return@mapNotNull null
            val isDir = (info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY.toLong()) != 0L
            val rel = if (dir.isBlank()) nm else "$dir\\$nm"
            SmbEntry(nm, rel, isDir, info.endOfFile)
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Open a random-access handle. Caller MUST close. */
    suspend fun openRead(path: String): SmbReadHandle = withContext(Dispatchers.IO) {
        val sh = share ?: error("not connected")
        val f = sh.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        val size = f.fileInformation.standardInformation.endOfFile
        SmbReadHandle(f, size)
    }

    fun disconnect() {
        synchronized(this) {
            try { share?.close() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            try { client?.close() } catch (_: Exception) {}
            share = null; session = null; client = null
            connected = false; boundConnId = ""
        }
    }

    private fun disconnectLocked() {
        try { share?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        share = null; session = null; client = null; connected = false
    }

    fun isBoundTo(id: String) = connected && boundConnId == id
}

/** Single open SMB file; thread-confined reads via synchronized. */
class SmbReadHandle internal constructor(
    private val file: com.hierynomus.smbj.share.File,
    val size: Long
) {
    private val lock = Any()
    fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        synchronized(lock) {
            return file.read(buf, offset, off, len)
        }
    }
    fun close() { try { file.close() } catch (_: Exception) {} }
}

object SmbHolder {
    val manager = SmbManager()
}
