package com.hazel.android.download.gallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Hazel (Saif): the files a saved post was made of.
 *
 * A history entry keeps one file, as a video download has one. A post keeps many, so the
 * rest are listed here under the one the entry names, which is the post's first file.
 * Opening that entry then shows the whole post rather than one picture of it. Kept apart
 * from the history itself so the upstream history format stays as it is.
 */
object GallerySets {

    private const val MAX_SETS = 500

    private fun file(context: Context) = File(File(context.noBackupFilesDir, "gallery-dl").apply { mkdirs() }, "sets.json")

    @Synchronized
    private fun load(context: Context): JSONObject =
        runCatching { JSONObject(file(context).readText()) }.getOrDefault(JSONObject())

    /** Records [files] as the post whose entry names the first of them. */
    @Synchronized
    fun save(context: Context, files: List<String>) {
        if (files.size < 2) return
        val all = load(context)
        all.put(files.first(), JSONArray(files))
        // The oldest go first once there are more than anyone scrolls back to.
        while (all.length() > MAX_SETS) {
            val oldest = all.keys().asSequence().firstOrNull() ?: break
            all.remove(oldest)
        }
        runCatching {
            val target = file(context)
            val staging = File(target.parentFile, "sets.json.tmp")
            staging.writeText(all.toString())
            if (!staging.renameTo(target)) {
                target.writeText(all.toString())
                staging.delete()
            }
        }
    }

    /** The files of the post [fileUri] begins, or null when it is a single file. */
    @Synchronized
    fun setFor(context: Context, fileUri: String): List<String>? {
        if (fileUri.isBlank()) return null
        val array = load(context).optJSONArray(fileUri) ?: return null
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
            .takeIf { it.size > 1 }
    }
}
