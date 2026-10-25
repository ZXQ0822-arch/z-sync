package app.zsync.log

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import app.zsync.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the app's own log lines to a file the person can hand back from inside the app.
 *
 * A tester only has the phone: adb is not an option, and an app cannot read logcat (the `logcat`
 * binary refuses without `READ_LOGS`). So every line goes to logcat *and* to a file under the app's
 * external files directory, which the share sheet can send.
 *
 * The file lives at `Android/data/app.zsync/files/logs/`. That folder is hidden from most file
 * managers on Android 11+, so sharing is the reliable way out and the screen offers exactly that.
 */
object QuickLog {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val FILE_PREFIX = "z-sync-"

    /** Nothing older than this stays on the phone; six hours covers one debugging session. */
    internal const val MAX_AGE_MS = 6L * 60 * 60 * 1000

    /** Hard ceiling on top of the age rule, so a chatty session cannot fill the disk. */
    internal const val KEEP_FILES = 5

    /** How often an open session re-checks the folder, so old files expire without a restart. */
    private const val PRUNE_EVERY_MS = 5L * 60 * 1000

    private val lineStamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val nameStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val lock = Any()

    private var folder: File? = null
    private var current: File? = null
    private var lastPruneAt = 0L

    /** Opens (or rotates) the log file. Safe to call more than once. */
    fun start(context: Context) {
        synchronized(lock) {
            val base = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
            base.mkdirs()
            folder = base
            current = File(base, "$FILE_PREFIX${nameStamp.format(Date())}.txt")
            prune(base)
        }
        write(
            "I",
            TAG,
            "app started version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "android=${android.os.Build.VERSION.RELEASE}",
        )
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        write("W", tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        write("E", tag, message)
    }

    fun e(tag: String, message: String, thrown: Throwable) {
        Log.e(tag, message, thrown)
        write("E", tag, "$message ${thrown.javaClass.simpleName}: ${thrown.message}")
    }

    /** The newest log file, for sharing and for showing the person where it is. */
    fun file(): File? = synchronized(lock) { current }

    fun folderPath(context: Context): String =
        (folder ?: context.getExternalFilesDir("logs"))?.absolutePath.orEmpty()

    /** Opens the share sheet with the log attached. */
    fun share(context: Context) {
        val target = file()
        if (target == null || !target.exists() || target.length() == 0L) {
            i(TAG, "share skipped: no log file yet")
            return
        }
        write("I", TAG, "log shared size=${target.length()}")
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.logs", target)
        }.getOrNull()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (uri != null) putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, target.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "导出日志").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { Log.e(TAG, "share failed ${it.javaClass.simpleName}") }
    }

    private fun write(level: String, tag: String, message: String) {
        val line = "${lineStamp.format(Date())} $level $tag: $message\n"
        synchronized(lock) {
            var f = current ?: return
            if (System.currentTimeMillis() - lastPruneAt > PRUNE_EVERY_MS) folder?.let(::prune)
            if (f.length() > MAX_BYTES) {
                val base = folder
                if (base != null) {
                    f = File(base, "$FILE_PREFIX${nameStamp.format(Date())}.txt")
                    current = f
                    prune(base)
                }
            }
            runCatching { f.appendText(line) }
        }
    }

    private fun prune(base: File) {
        val logs = base.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }?.toList() ?: return
        val doomed = expiringLogs(logs, System.currentTimeMillis())
        doomed.forEach { runCatching { it.delete() } }
        lastPruneAt = System.currentTimeMillis()
        if (doomed.isNotEmpty()) Log.i(TAG, "log pruned removed=${doomed.size}")
    }

    /**
     * Which files to delete: first everything past [MAX_AGE_MS], then everything beyond the newest
     * [KEEP_FILES] of what is left.
     *
     * Pure and internal so the rule can be tested without a phone or a clock.
     */
    internal fun expiringLogs(
        logs: List<File>,
        now: Long,
        maxAgeMs: Long = MAX_AGE_MS,
        keep: Int = KEEP_FILES,
    ): List<File> {
        val (expired, fresh) = logs.partition { now - it.lastModified() > maxAgeMs }
        return expired + fresh.sortedByDescending { it.lastModified() }.drop(keep)
    }

    private const val TAG = "Z-Sync"
}
