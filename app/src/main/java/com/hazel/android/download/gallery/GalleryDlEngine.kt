package com.hazel.android.download.gallery

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Hazel (Saif): runs gallery-dl with the Python that youtubedl-android already ships.
 *
 * The engine library starts yt-dlp as `libpython.so <zipapp> args`, with a handful of
 * environment variables pointing Python at the standard library it unpacked into
 * `noBackupFilesDir/youtubedl-android/packages/python`. gallery-dl is started the same way,
 * from a zipapp built in CI (tools/saif/build_gallery_dl.sh) that also carries `requests`
 * and the other pure-Python libraries the bundled Python lacks.
 */
object GalleryDlEngine {

    private const val TAG = "HazelGallery"
    private const val ASSET = "gallery-dl.pyz"

    /** Base folder of youtubedl-android, `YoutubeDL.baseName` in the library. */
    private const val ENGINE_BASE = "youtubedl-android"

    private val processes = ConcurrentHashMap<String, Process>()

    class GalleryDlException(message: String) : Exception(message)

    data class Result(val exitCode: Int, val out: String, val err: String)

    @Volatile private var available: Boolean? = null

    /** Whether this build carries the zipapp. A local build without the CI step does not. */
    fun isAvailable(context: Context): Boolean = available ?: runCatching {
        context.assets.open(ASSET).close()
        true
    }.getOrDefault(false).also { available = it }

    /**
     * The zipapp on disk, copied out of the APK once per install or update. Python reads a
     * zipapp by path, so it cannot be run from inside the APK's assets.
     */
    @Synchronized
    private fun zipapp(context: Context): File {
        val dir = File(context.noBackupFilesDir, "gallery-dl").apply { mkdirs() }
        val target = File(dir, ASSET)
        val marker = File(dir, "installed-from")
        @Suppress("DEPRECATION")
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!target.exists() || runCatching { marker.readText() }.getOrNull() != stamp) {
            val staging = File(dir, "$ASSET.tmp")
            context.assets.open(ASSET).use { input -> staging.outputStream().use { input.copyTo(it) } }
            if (!staging.renameTo(target)) {
                target.delete()
                if (!staging.renameTo(target)) throw GalleryDlException("Could not install gallery-dl")
            }
            marker.writeText(stamp)
        }
        return target
    }

    /**
     * Runs gallery-dl with [args] and waits for it. [onLine] sees each line of standard
     * output as it arrives. A run stopped through [destroy] returns with a non-zero code.
     */
    fun execute(
        context: Context,
        args: List<String>,
        processId: String,
        onLine: ((String) -> Unit)? = null
    ): Result {
        // Unpacks the Python runtime if the app has not done so yet; returns at once otherwise.
        YoutubeDL.getInstance().init(context)

        val pythonUsr = File(context.noBackupFilesDir, "$ENGINE_BASE/packages/python/usr")
        val binDir = File(context.applicationInfo.nativeLibraryDir)
        val python = File(binDir, "libpython.so")
        val certs = File(pythonUsr, "etc/tls/cert.pem").absolutePath

        val command = buildList {
            add(python.absolutePath)
            add(zipapp(context).absolutePath)
            // Only what Hazel passes counts; no config file on the device can change it.
            add("--config-ignore")
            addAll(args)
        }
        val builder = ProcessBuilder(command)
        builder.environment().apply {
            this["LD_LIBRARY_PATH"] = "${pythonUsr.absolutePath}/lib"
            this["SSL_CERT_FILE"] = certs
            this["REQUESTS_CA_BUNDLE"] = certs
            this["PATH"] = System.getenv("PATH") + ":" + binDir.absolutePath
            this["PYTHONHOME"] = pythonUsr.absolutePath
            this["HOME"] = pythonUsr.absolutePath
            this["TMPDIR"] = context.cacheDir.absolutePath
            this["PYTHONIOENCODING"] = "utf-8"
            this["PYTHONUTF8"] = "1"
        }

        val process = builder.start()
        processes.put(processId, process)?.destroy()
        try {
            val err = StringBuilder()
            val errReader = thread(name = "gallery-dl-stderr") {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        synchronized(err) { err.appendLine(line) }
                    }
                }
            }
            val out = StringBuilder()
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    out.appendLine(line)
                    onLine?.invoke(line)
                }
            }
            val code = process.waitFor()
            errReader.join(2_000)
            val errText = synchronized(err) { err.toString() }
            if (code != 0) Log.w(TAG, "gallery-dl exited $code: ${errText.takeLast(600)}")
            return Result(code, out.toString(), errText)
        } finally {
            processes.remove(processId, process)
        }
    }

    /** Stops the run started under [processId], if one is going. */
    fun destroy(processId: String) {
        processes.remove(processId)?.destroy()
    }
}
