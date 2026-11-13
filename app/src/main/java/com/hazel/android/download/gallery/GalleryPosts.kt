package com.hazel.android.download.gallery

import android.content.Context
import android.util.Log
import com.hazel.android.download.MediaFormat
import com.hazel.android.download.MediaInfo
import com.hazel.android.download.NetworkProxy
import com.hazel.android.download.SiteAccess
import com.hazel.android.util.LinkKey
import com.hazel.android.util.PostItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Hazel (Saif): photo posts and carousels, read and downloaded with gallery-dl.
 *
 * A post on one of the sites below is put to gallery-dl first. When it holds at least one
 * picture it becomes a single card whose one "format" is the whole post ([isGallery]), and
 * the download takes every file in it. A post that turns out to be video only is left to
 * yt-dlp, which offers qualities and audio choices gallery-dl does not. Elsewhere on these
 * sites gallery-dl is the fallback for a link yt-dlp could not read.
 *
 * The format survives the queue and the history because everything about it is carried in
 * its id: `hazel-gallery:<number of files>`.
 */
object GalleryPosts {

    private const val TAG = "HazelGallery"
    const val FORMAT_PREFIX = "hazel-gallery"
    private const val PROBE_ID = "hazel_gallery_probe"

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "bmp")

    // ── Which links ──

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(PostItem.address(url)).host.orEmpty() }.getOrDefault("")
            .lowercase()
            .removePrefix("www.").removePrefix("m.").removePrefix("mobile.").removePrefix("old.")

    private fun pathOf(url: String): String =
        runCatching { java.net.URI(PostItem.address(url)).path.orEmpty() }.getOrDefault("")

    private fun isPinterest(host: String) = host.startsWith("pinterest.") || host.contains(".pinterest.")

    /** Sites gallery-dl is asked about at all. */
    fun supportsSite(url: String): Boolean {
        val host = hostOf(url)
        return host == "instagram.com" || host == "instagr.am" ||
            host == "twitter.com" || host == "x.com" ||
            isPinterest(host) || host == "pin.it" ||
            host == "reddit.com" || host == "redd.it" ||
            host == "tumblr.com" || host.endsWith(".tumblr.com")
    }

    private val INSTAGRAM_POST = Regex("^/(?:[^/]+/)?(?:p|tv)/[A-Za-z0-9_-]+/?$")
    private val TWEET = Regex("^/(?:[^/]+|i/web)/status/\\d+")
    private val PIN = Regex("^/pin/[^/]+")
    private val REDDIT_POST = Regex("^/(?:r/[^/]+/comments/|comments/|gallery/|user/[^/]+/comments/)")
    private val TUMBLR_SUBDOMAIN_POST = Regex("^/post/\\d+")
    private val TUMBLR_POST = Regex("^/[^/]+/\\d+")

    /** A single post, which gallery-dl reads before yt-dlp does. */
    fun isPostLink(url: String): Boolean {
        val host = hostOf(url)
        val path = pathOf(url)
        return when {
            host == "instagram.com" || host == "instagr.am" -> INSTAGRAM_POST.containsMatchIn(path)
            host == "twitter.com" || host == "x.com" -> TWEET.containsMatchIn(path)
            isPinterest(host) -> PIN.containsMatchIn(path)
            host == "pin.it" -> path.length > 1
            host == "reddit.com" -> REDDIT_POST.containsMatchIn(path)
            host == "redd.it" -> path.length > 1
            host.endsWith(".tumblr.com") -> TUMBLR_SUBDOMAIN_POST.containsMatchIn(path)
            host == "tumblr.com" -> TUMBLR_POST.containsMatchIn(path)
            else -> false
        }
    }

    // ── The format that stands for a whole post ──

    fun isGallery(format: MediaFormat): Boolean = format.formatId.startsWith(FORMAT_PREFIX)

    fun formatOf(info: MediaInfo): MediaFormat? = info.videoFormats.firstOrNull { isGallery(it) }

    fun isGallery(info: MediaInfo): Boolean = formatOf(info) != null

    /** How many files the post holds, as read when its card was made. */
    fun countOf(format: MediaFormat): Int = format.formatId.substringAfter(':', "").toIntOrNull() ?: 0

    private fun galleryFormat(images: Int, videos: Int, firstExt: String): MediaFormat {
        val label = when {
            videos == 0 && images == 1 -> "Photo"
            videos == 0 -> "Photos · $images"
            else -> "$images photo${if (images == 1) "" else "s"} + $videos video${if (videos == 1) "" else "s"}"
        }
        return MediaFormat(
            formatId = "$FORMAT_PREFIX:${images + videos}",
            selector = "gallery-dl",
            label = label,
            ext = if (videos == 0) firstExt else "",
            vcodec = null,
            acodec = null,
            height = 0,
            fps = 0,
            bitrateKbps = 0.0,
            fileSizeBytes = 0L,
            // Listed with the video formats so the sheet and every automatic pick find it;
            // marked as carrying sound so nothing goes looking for a track to merge.
            hasVideo = true,
            hasAudio = true,
            isGeneric = false
        )
    }

    // ── Reading ──

    private fun commonArgs(url: String, access: SiteAccess): List<String> = buildList {
        access.cookieFile?.let {
            add("--cookies"); add(it.absolutePath)
            // gallery-dl writes updated cookies back by default; Hazel owns this file.
            add("-o"); add("cookies-update=false")
        }
        // The identity the sign-in was collected under, as yt-dlp is given it.
        if (access.hasCookies && access.userAgent.isNotBlank()) {
            add("-o"); add("user-agent=${access.userAgent}")
        }
        NetworkProxy.current()?.url?.let { add("--proxy"); add(it) }
        add("-o"); add("http.timeout=30")
        // One item of a post, which the card names as a fragment on the address.
        PostItem.indexOf(url)?.let { add("--range"); add(it.toString()) }
    }

    /**
     * Reads [url] as a post. Returns a card for it when it holds at least one picture, null
     * when it holds none (video only, or nothing gallery-dl recognises), and throws with
     * gallery-dl's own message when the site refused.
     */
    suspend fun read(context: Context, url: String, access: SiteAccess): MediaInfo? = withContext(Dispatchers.IO) {
        if (!GalleryDlEngine.isAvailable(context)) return@withContext null

        val args = commonArgs(url, access) + listOf("-j", PostItem.address(url))
        val result = GalleryDlEngine.execute(context, args, "${PROBE_ID}_${LinkKey.digest(url)}")

        val payload = result.out.substringAfter('[', "").let { if (it.isEmpty()) "" else "[$it" }
        val messages = runCatching { JSONArray(payload) }.getOrNull()
        if (messages == null) {
            val reason = result.err.lineSequence().lastOrNull { it.isNotBlank() }
            if (reason != null) throw GalleryDlEngine.GalleryDlException("gallery-dl: $reason")
            return@withContext null
        }

        var postInfo: JSONObject? = null
        val files = mutableListOf<Pair<String, JSONObject>>()
        var error: String? = null
        for (i in 0 until messages.length()) {
            val message = messages.optJSONArray(i) ?: continue
            when (message.optInt(0)) {
                // Directory: the post's own metadata.
                2 -> if (postInfo == null) postInfo = message.optJSONObject(1)
                // Url: one file of the post.
                3 -> {
                    val fileUrl = message.optString(1)
                    val meta = message.optJSONObject(2) ?: JSONObject()
                    if (fileUrl.isNotBlank()) files += fileUrl to meta
                }
                -1 -> message.optJSONObject(1)?.let { e ->
                    error = e.optString("message").ifBlank { e.optString("error") }
                }
            }
        }

        if (files.isEmpty()) {
            error?.let { throw GalleryDlEngine.GalleryDlException(explain(url, it)) }
            return@withContext null
        }

        val images = files.filter { (fileUrl, meta) -> extensionOf(fileUrl, meta) in IMAGE_EXTS }
        if (images.isEmpty()) return@withContext null
        val videos = files.size - images.size

        val meta = postInfo ?: files.first().second
        val site = meta.optString("category").replaceFirstChar { it.uppercase() }.ifBlank { "Post" }
        val title = firstText(meta, "description", "content", "title", "grid_title", "summary", "caption")
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(120)
            ?: "$site post"
        val uploader = firstText(
            meta, "username", "fullname", "author.name", "author", "user.name", "owner.username",
            "pinner.username", "blog_name", "blog.name"
        ).orEmpty()

        MediaInfo(
            url = url,
            title = title,
            uploader = uploader,
            thumbnail = images.first().first,
            durationSeconds = 0,
            videoFormats = listOf(galleryFormat(images.size, videos, extensionOf(images.first().first, images.first().second))),
            audioFormats = emptyList(),
            requiresSignIn = access.hasCookies
        )
    }

    /**
     * gallery-dl's refusal in words Hazel's error screen understands. A site that sent it to
     * its login page wants cookies, and "sign in" is what brings up Hazel's sign-in offer.
     */
    private fun explain(url: String, reason: String): String {
        val lower = reason.lowercase()
        val wantsSignIn = "login" in lower || "log in" in lower || "logged in" in lower ||
            "authenticat" in lower || "401" in lower || "403" in lower
        if (!wantsSignIn) return "gallery-dl: $reason"
        val site = hostOf(url).substringBefore('.').replaceFirstChar { it.uppercase() }.ifBlank { "This site" }
        return "$site wants you to sign in before it shows this post's photos. " +
            "Import your $site cookies (Cookies → Import), then try again.

gallery-dl: $reason"
    }

    /** yt-dlp's answer to a post that is all pictures. */
    fun isNoVideoError(message: String?): Boolean {
        val lower = message?.lowercase() ?: return false
        return "no video formats" in lower || "no video could be found" in lower ||
            "there is no video" in lower || "no media found" in lower
    }

    private fun extensionOf(fileUrl: String, meta: JSONObject): String =
        meta.optString("extension").takeIf { it.isNotBlank() }?.lowercase()
            ?: fileUrl.substringBefore('?').substringAfterLast('.', "").lowercase()

    /** The first non-blank text among [keys]; a dotted key looks inside an object. */
    private fun firstText(meta: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            var node: Any? = meta
            for (part in key.split('.')) node = (node as? JSONObject)?.opt(part)
            val text = (node as? String)?.trim()
            if (!text.isNullOrBlank() && text != "null") return text
        }
        return null
    }

    // ── Downloading ──

    /**
     * Downloads every file of the post at [url] into [dir]. [onFile] is told how many files
     * have arrived so far. Files already in [dir] are skipped, which is what makes a resume
     * after a pause carry on rather than start again.
     */
    fun download(
        context: Context,
        url: String,
        dir: File,
        access: SiteAccess,
        processId: String,
        onFile: (Int) -> Unit
    ) {
        dir.mkdirs()
        var done = 0
        val args = commonArgs(url, access) + listOf(
            "-D", dir.absolutePath,
            "--no-mtime",
            PostItem.address(url)
        )
        val result = GalleryDlEngine.execute(context, args, processId) { line ->
            // Each file is printed as its path once written; "# " marks one already there.
            if (line.isNotBlank()) {
                done++
                onFile(done)
            }
        }
        val saved = dir.listFiles()?.count { it.isFile && !it.name.endsWith(".part") } ?: 0
        if (saved == 0) {
            val reason = result.err.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "gallery-dl saved nothing (exit ${result.exitCode})"
            throw GalleryDlEngine.GalleryDlException(reason)
        }
        if (result.exitCode != 0) {
            Log.w(TAG, "Post partly downloaded: $saved files, exit ${result.exitCode}")
        }
    }
}
