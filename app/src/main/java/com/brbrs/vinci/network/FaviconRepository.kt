package com.brbrs.vinci.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Company logos: the icon (favicon) of the website saved on the company -- not the feed's --
 * so nobody has to upload a logo by hand.
 *
 * The icon is looked up on the company's OWN site, never through a favicon service such as
 * Google's or DuckDuckGo's: those would learn which companies you keep track of. Like feeds,
 * this uses its own HTTP client, entirely separate from the Nextcloud one. Results are cached
 * on the device (per website host), so the network is only touched the first time.
 */
@Singleton
class FaviconRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("vinci_company_logos", Context.MODE_PRIVATE)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Vinci (Android)").build())
        }
        .build()

    private val memory = ConcurrentHashMap<String, String>() // host -> icon address, "" = none found

    /** Address of the best icon for [website], or null if it has none (or can't be reached right now). */
    suspend fun logoFor(website: String): String? = withContext(Dispatchers.IO) {
        val siteUrl = normalize(website) ?: return@withContext null
        val host = siteUrl.toHttpUrlOrNull()?.host ?: return@withContext null

        memory[host]?.let { return@withContext it.ifBlank { null } }

        // On-disk cache, stored as "<timestamp>|<icon address>". A positive result is trusted
        // for two weeks; a miss only for an hour, so a site that was offline when it was
        // added is tried again soon.
        val now = System.currentTimeMillis()
        val saved = prefs.getString(host, null)
        val savedTime = saved?.substringBefore('|')?.toLongOrNull() ?: 0L
        val savedUrl = saved?.substringAfter('|', "").orEmpty()
        val ttl = if (savedUrl.isBlank()) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
        if (saved != null && now - savedTime < ttl) {
            memory[host] = savedUrl
            return@withContext savedUrl.ifBlank { null }
        }

        // A failed refresh keeps the icon we already had rather than dropping it
        val result = runCatching { discover(siteUrl) }.getOrNull() ?: savedUrl.ifBlank { null }
        prefs.edit().putString(host, "$now|${result.orEmpty()}").apply()
        memory[host] = result.orEmpty()
        result
    }

    // -- discovery --

    private class Candidate(val url: String, val score: Int)

    private fun discover(siteUrl: String): String? {
        val start = siteUrl.toHttpUrlOrNull() ?: return null
        var base: HttpUrl = start
        val candidates = mutableListOf<Candidate>()

        runCatching {
            client.newCall(Request.Builder().url(start).get().build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    base = resp.request.url // after redirects, e.g. acme.com -> www.acme.com/en/
                    candidates += parseIconLinks(readCapped(resp, 500_000), base)
                }
            }
        }

        // Best declared icon first; the conventional /favicon.ico is the last resort
        val ordered = candidates.sortedByDescending { it.score }.map { it.url }.distinct() +
            listOfNotNull(base.resolve("/favicon.ico")?.toString())
        return ordered.take(4).firstOrNull { isLoadableImage(it) }
    }

    /** Reads the icons a page declares: <link rel="icon">, "shortcut icon" and "apple-touch-icon". */
    private fun parseIconLinks(html: String, base: HttpUrl): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (tag in Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }) {
            val rel = attr(tag, "rel")?.lowercase() ?: continue
            val relTokens = rel.split(Regex("\\s+"))
            val isApple = relTokens.any { it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" }
            if (!isApple && "icon" !in relTokens) continue

            val href = attr(tag, "href")?.replace("&amp;", "&")?.trim().orEmpty()
            if (href.isEmpty() || href.startsWith("data:")) continue
            val type = attr(tag, "type")?.lowercase().orEmpty()
            val path = href.substringBefore('?').lowercase()
            // Coil can't draw SVG without an extra library, so only raster icons are considered
            if (type.contains("svg") || path.endsWith(".svg")) continue

            val resolved = base.resolve(href)?.toString() ?: continue
            val isIco = path.endsWith(".ico")
            val declared = attr(tag, "sizes")?.lowercase()?.let { sizes ->
                Regex("(\\d+)x(\\d+)").findAll(sizes).maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 }
            }
            val size = declared ?: if (isApple) 180 else if (isIco) 32 else 64
            // Prefer big, then Apple's square touch icons, then PNG/JPG over ICO
            val score = size * 10 + (if (isApple) 5 else 0) + (if (isIco) 0 else 2)
            out += Candidate(resolved.replaceFirst("http://", "https://"), score)
        }
        return out
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    /** True if the address answers with an actual image (checked without downloading the body). */
    private fun isLoadableImage(url: String): Boolean = runCatching {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val type = resp.header("Content-Type")?.lowercase().orEmpty()
            resp.isSuccessful && !type.contains("svg") &&
                (type.startsWith("image/") || type.contains("icon") || type.contains("octet-stream"))
        }
    }.getOrDefault(false)

    private fun readCapped(resp: Response, maxBytes: Int): String {
        val body = resp.body ?: return ""
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        body.byteStream().use { input ->
            while (out.size() < maxBytes) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull()?.toString()
    }

    private companion object {
        const val POSITIVE_TTL_MS = 14L * 24 * 60 * 60 * 1000
        const val NEGATIVE_TTL_MS = 60L * 60 * 1000
    }
}
