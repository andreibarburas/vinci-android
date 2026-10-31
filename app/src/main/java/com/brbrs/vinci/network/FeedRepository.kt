package com.brbrs.vinci.network

import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class FeedItem(
    val title: String,
    val link: String,       // http(s) only, or "" if the feed didn't give a usable link
    val publishedAt: Long,  // epoch millis, 0 if unknown
    val summary: String,    // plain text, trimmed
    val imageUrl: String = "", // featured image, always absolute https, or "" if the post has none
)

/**
 * Fetches and parses a company's RSS/Atom feed straight from the device.
 *
 * Deliberately uses its OWN OkHttpClient rather than the shared one: feeds live on arbitrary
 * third-party websites, and nothing here should ever sit anywhere near the Nextcloud client
 * or its credentials. Posts are only cached in memory -- just the feed URL is synced.
 */
@Singleton
class FeedRepository @Inject constructor() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Vinci (Android feed reader)")
                    .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8, */*;q=0.5")
                    .build()
            )
        }
        .build()

    // Short overall timeout for guessing common feed paths, so "Find feed" can't hang for ages
    private val probeClient: OkHttpClient = client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()

    private class Cached(val items: List<FeedItem>, val fetchedAt: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun load(feedUrl: String, forceRefresh: Boolean = false): Result<List<FeedItem>> =
        withContext(Dispatchers.IO) {
            val url = normalizeUrl(feedUrl)
                ?: return@withContext Result.failure(IOException("That doesn't look like a valid web address"))
            val cached = cache[url]
            if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
                return@withContext Result.success(cached.items)
            }
            runCatching {
                val items = parseFeed(fetchBytes(client, url, MAX_FEED_BYTES), url)
                if (items.isEmpty()) throw IOException("No posts found at that address -- is it a feed URL?")
                cache[url] = Cached(items, System.currentTimeMillis())
                items
            }
        }

    /**
     * Finds a feed for a company website: first the <link rel="alternate"> tags the site
     * advertises in its own HTML, then a handful of common feed paths. Returns null if none.
     */
    suspend fun discover(websiteUrl: String): String? = withContext(Dispatchers.IO) {
        val base = normalizeUrl(websiteUrl) ?: return@withContext null
        val baseUrl = base.toHttpUrlOrNull() ?: return@withContext null

        val html = runCatching { String(fetchBytes(probeClient, base, 500_000), Charsets.UTF_8) }.getOrNull()
        if (html != null) {
            val typeRegex = Regex("type\\s*=\\s*[\"']application/(rss|atom)\\+xml", RegexOption.IGNORE_CASE)
            val hrefRegex = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            for (tag in Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }) {
                if (!typeRegex.containsMatchIn(tag)) continue
                val href = hrefRegex.find(tag)?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
                baseUrl.resolve(href)?.let { return@withContext it.toString() }
            }
        }

        for (path in listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml", "/blog/feed")) {
            val candidate = baseUrl.resolve(path)?.toString() ?: continue
            val looksLikeFeed = runCatching { parseFeed(fetchBytes(probeClient, candidate, 600_000), candidate).isNotEmpty() }
                .getOrDefault(false)
            if (looksLikeFeed) return@withContext candidate
        }
        null
    }

    // -- helpers --

    /** Adds https:// if missing; only http(s) addresses are accepted (toHttpUrlOrNull rejects the rest). */
    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull()?.toString()
    }

    private fun fetchBytes(c: OkHttpClient, url: String, maxBytes: Int): ByteArray {
        c.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("The site answered with HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("The site returned an empty response")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            body.byteStream().use { input ->
                while (out.size() < maxBytes) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        }
    }

    /**
     * Parses RSS 2.0 (<item>) and Atom (<entry>) feeds; newest first, max 25 posts.
     * [feedUrl] is only used to resolve relative image addresses.
     */
    internal fun parseFeed(bytes: ByteArray, feedUrl: String? = null): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(ByteArrayInputStream(bytes), null)

            var inItem = false
            var title = ""; var link = ""; var date = ""; var summary = ""
            // Featured-image candidates, best first: media:content, thumbnail/itunes:image,
            // image enclosure, and finally the first real <img> inside the post's HTML.
            var mediaImage = ""; var thumbImage = ""; var enclosureImage = ""; var htmlBlob = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val tag = parser.name
                    if (tag == "item" || tag == "entry") {
                        inItem = true; title = ""; link = ""; date = ""; summary = ""
                        mediaImage = ""; thumbImage = ""; enclosureImage = ""; htmlBlob = ""
                    } else if (inItem) {
                        when (tag) {
                            "title" -> title = readText(parser)
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                if (href != null) { // Atom: <link href="..." rel="alternate"/>
                                    val rel = parser.getAttributeValue(null, "rel")
                                    val type = parser.getAttributeValue(null, "type")
                                    if ((rel == null || rel == "alternate") && link.isBlank()) {
                                        link = href
                                    } else if (rel == "enclosure" && type?.startsWith("image/") == true && enclosureImage.isBlank()) {
                                        enclosureImage = href
                                    }
                                } else {            // RSS: <link>https://...</link>
                                    link = readText(parser)
                                }
                            }
                            "media:content" -> {
                                val url = parser.getAttributeValue(null, "url")
                                val medium = parser.getAttributeValue(null, "medium")
                                val type = parser.getAttributeValue(null, "type")
                                val isImage = medium == "image" || type?.startsWith("image/") == true ||
                                    (medium == null && type == null && url != null && looksLikeImage(url))
                                if (url != null && isImage && mediaImage.isBlank()) mediaImage = url
                            }
                            "media:thumbnail" -> {
                                val url = parser.getAttributeValue(null, "url")
                                if (url != null && thumbImage.isBlank()) thumbImage = url
                            }
                            "itunes:image" -> {
                                val href = parser.getAttributeValue(null, "href")
                                if (href != null && thumbImage.isBlank()) thumbImage = href
                            }
                            "enclosure" -> {
                                val url = parser.getAttributeValue(null, "url")
                                val type = parser.getAttributeValue(null, "type")
                                val isImage = type?.startsWith("image/") == true || (type == null && url != null && looksLikeImage(url))
                                if (url != null && isImage && enclosureImage.isBlank()) enclosureImage = url
                            }
                            "pubDate", "published", "dc:date" -> date = readText(parser)
                            "updated" -> { val t = readText(parser); if (date.isBlank()) date = t }
                            "description", "summary", "content:encoded", "content" -> {
                                val t = readText(parser)
                                if (summary.isBlank()) summary = t
                                if (htmlBlob.length < 40_000) htmlBlob += t
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && (parser.name == "item" || parser.name == "entry")) {
                    if (inItem) {
                        val cleanLink = link.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
                        val cleanTitle = plainText(title).ifBlank { cleanLink }
                        if (cleanTitle.isNotBlank()) {
                            val rawImage = listOf(mediaImage, thumbImage, enclosureImage).firstOrNull { it.isNotBlank() }
                                ?: firstHtmlImage(htmlBlob)
                            items += FeedItem(
                                title = cleanTitle,
                                link = cleanLink,
                                publishedAt = parseDate(date),
                                summary = plainText(summary).take(220),
                                imageUrl = resolveImage(rawImage, cleanLink.ifBlank { feedUrl }),
                            )
                        }
                        inItem = false
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // A truncated (size-capped) feed still yields the posts parsed before the cut;
            // an address that isn't a feed at all (e.g. an HTML page) yields nothing.
            if (items.isEmpty()) throw IOException("That address didn't return a valid RSS or Atom feed")
        }
        return items.sortedByDescending { it.publishedAt }.take(25)
    }

    /** Collects all text inside the current element, including text nested in child elements. */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (sb.length < 40_000) sb.append(parser.text)
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    private fun plainText(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace("\uFFFC", "") // inline image placeholders
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun looksLikeImage(url: String): Boolean =
        Regex("\\.(jpe?g|png|webp|gif|avif)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** First meaningful <img> inside a post's HTML, skipping tracking pixels and emoji. */
    private fun firstHtmlImage(html: String): String {
        if (html.isBlank()) return ""
        val tinyRegex = Regex("(?:width|height)\\s*=\\s*[\"']?1[\"']?(?![0-9])", RegexOption.IGNORE_CASE)
        val srcRegex = Regex("src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val junk = listOf("pixel", "tracker", "beacon", "feedburner", "doubleclick", "analytics", "emoji")
        for (tag in Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }) {
            if (tinyRegex.containsMatchIn(tag)) continue
            val src = srcRegex.find(tag)?.groupValues?.get(1) ?: continue
            if (junk.any { src.contains(it, ignoreCase = true) }) continue
            return src
        }
        return ""
    }

    /** Makes an image address absolute and https. Returns "" if it can't be used. */
    private fun resolveImage(raw: String, base: String?): String {
        val cleaned = raw.trim().replace("&amp;", "&")
        if (cleaned.isEmpty() || cleaned.startsWith("data:")) return ""
        val absolute = when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
            else -> base?.toHttpUrlOrNull()?.resolve(cleaned)?.toString() ?: return ""
        }
        // Android blocks cleartext http by default, so an http image would never load anyway
        return absolute.replaceFirst("http://", "https://")
    }

    private fun parseDate(raw: String): Long {
        val t = raw.trim()
        if (t.isEmpty()) return 0L
        return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(t)?.time }.getOrNull()
            ?: runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(t)?.time }.getOrNull()
            ?: runCatching { SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.US).parse(t)?.time }.getOrNull()
            ?: 0L
    }

    private companion object {
        const val CACHE_TTL_MS = 15 * 60 * 1000L
        const val MAX_FEED_BYTES = 2_000_000
    }
}
