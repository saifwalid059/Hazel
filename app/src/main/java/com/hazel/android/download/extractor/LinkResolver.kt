package com.hazel.android.download.extractor

import androidx.annotation.StringRes
import com.hazel.android.HazelApp
import com.hazel.android.R
import com.hazel.android.download.FetchMode
import com.hazel.android.download.InfoCache
import com.hazel.android.download.MediaProbe
import com.hazel.android.download.SiteAccess
import com.hazel.android.download.gallery.GalleryPosts
import com.hazel.android.download.extractor.newpipe.NewPipeEngine
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Which extractor is asked what a link holds.
 *
 * Only the listing is in question. Formats and the download itself are always yt-dlp's,
 * whichever of these is chosen, because its format ids are what a download is expressed in.
 */
enum class ListingSource(
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int
) {

    YT_DLP(
        labelRes = R.string.listing_source_ytdlp_label,
        descriptionRes = R.string.listing_source_ytdlp_description
    ),

    NEWPIPE(
        labelRes = R.string.listing_source_newpipe_label,
        descriptionRes = R.string.listing_source_newpipe_description
    );

    companion object {
        /**
         * yt-dlp binary engine by default. It guarantees full extraction of all available
         * stream qualities, audio language tracks, and subtitles across all supported sites.
         * NewPipe remains available as an optional fast reader.
         */
        val DEFAULT = YT_DLP

        fun fromName(name: String?): ListingSource =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Answers what a link holds, from whichever extractor can say soonest.
 *
 * The fallback is the point of this class. Anything the preferred reader cannot answer is
 * put to yt-dlp instead, silently, because a link it cannot read is still a link yt-dlp very
 * likely can. Nothing here reports a failure to the user: the only observable difference
 * between the two paths is how long the answer took.
 */
object LinkResolver {

    suspend fun resolve(
        url: String,
        cacheDir: File,
        access: SiteAccess,
        fetchMode: FetchMode,
        forceIpv4: Boolean,
        source: ListingSource,
        processId: String = MediaProbe.PROBE_PROCESS_ID
    ): LinkContents {

        // Hazel (Saif): a post on a photo site is put to gallery-dl first, so the pictures
        // of a carousel are not lost to a reader that only sees its videos. A post with no
        // pictures in it comes back null and goes on to yt-dlp as before.
        // Why gallery-dl turned the post down, shown in place of yt-dlp's "no video" when
        // the post is pictures (Instagram asks for a sign-in, for one).
        var galleryRefusal: Exception? = null
        if (GalleryPosts.isPostLink(url)) {
            try {
                readAsGallery(url, access)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                galleryRefusal = e
            }
        }

        if (source == ListingSource.NEWPIPE && !access.hasCookies) {
            // First check collections (playlists & channel tabs) for low-latency listing
            if (NewPipeEngine.handlesCollection(url)) {
                NewPipeEngine.list(url)?.let { many ->
                    InfoCache.putListing(url, many)
                    return many
                }
            } else if (NewPipeEngine.handlesStream(url)) {
                NewPipeEngine.single(url)?.let { info ->
                    InfoCache.put(url, info, rawJson = null)
                    return LinkContents.Single(info)
                }
            }
        }

        // Silent universal fallback to yt-dlp binary engine
        return try {
            MediaProbe.listContents(url, cacheDir, access, fetchMode, forceIpv4, processId)
        } catch (e: Exception) {
            // Hazel (Saif): any other link on a photo site that yt-dlp cannot read (it
            // reports "no video" for a picture) is offered to gallery-dl. yt-dlp's own
            // message stands when gallery-dl finds no pictures either.
            if (e is CancellationException) throw e
            galleryRefusal?.let { refusal ->
                if (GalleryPosts.isNoVideoError(e.message)) throw refusal
            }
            if (!GalleryPosts.supportsSite(url) || GalleryPosts.isPostLink(url)) throw e
            runCatching { readAsGallery(url, access) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: throw e
        }
    }

    /** Throws gallery-dl's refusal; null when the link holds no pictures. */
    private suspend fun readAsGallery(url: String, access: SiteAccess): LinkContents? =
        GalleryPosts.read(HazelApp.instance, url, access)?.let { info ->
            InfoCache.put(url, info, rawJson = null)
            LinkContents.Single(info)
        }
}
