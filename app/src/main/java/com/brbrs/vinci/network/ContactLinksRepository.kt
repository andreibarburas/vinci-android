package com.brbrs.vinci.network

import com.brbrs.vinci.auth.AuthRepository
import com.brbrs.vinci.auth.VinciSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Which suite app a linked item came from. Drives the icon/label shown in the UI. */
enum class LinkedItemSource { TAVLA, KOTO, NOTA }

/**
 * One item from another suite app that references a Vinci contact via its cardavUid.
 *
 * Deliberately thin: just enough to render a row and (later) deep-link into the owning app.
 * Each source app's contact-links.json carries a human-readable title/snippet alongside the
 * id, so Vinci never has to fetch or understand that app's own data files.
 */
data class LinkedItem(
    val source: LinkedItemSource,
    val itemId: String,
    val title: String,
    val snippet: String = "",
    val updatedAt: Long = 0L,
)

/**
 * Reads the `contact-links.json` sidecar file each of Tavla, Koto and Nóta maintains in its
 * own Nextcloud folder (same pattern as Tavla's existing solidtime-links.json), and resolves
 * the entries that reference a given contact's cardavUid.
 *
 * Vinci only ever reads these files -- it never writes them. A missing file (app not
 * installed, or installed but nothing linked yet) is a normal, silent empty result, not
 * an error: every fetch is wrapped so one app's absence never blocks the other two.
 */
@Singleton
class ContactLinksRepository @Inject constructor(
    private val authRepository: AuthRepository,
    private val httpClient: OkHttpClient,
) {
    /** Returns the linked items across all three apps for [cardavUid], newest first. */
    suspend fun linkedItemsFor(cardavUid: String): List<LinkedItem> = withContext(Dispatchers.IO) {
        if (cardavUid.isBlank()) return@withContext emptyList()
        val session = authRepository.session.first() ?: return@withContext emptyList()

        coroutineScope {
            val tavla = async { fetchLinks(session, "Tavla", LinkedItemSource.TAVLA) }
            val koto  = async { fetchLinks(session, "Koto", LinkedItemSource.KOTO) }
            val nota  = async { fetchLinks(session, "Nota", LinkedItemSource.NOTA) }

            awaitAll(tavla, koto, nota)
                .flatten()
                .filter { it.contactUids.contains(cardavUid) }
                .map { it.item }
                .sortedByDescending { it.updatedAt }
        }
    }

    private data class RawLink(val contactUids: List<String>, val item: LinkedItem)

    private fun fetchLinks(
        session: VinciSession,
        remoteFolder: String,
        source: LinkedItemSource,
    ): List<RawLink> = runCatching {
        val client = httpClient.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", session.basicAuthHeader())
                        .build()
                )
            }
            .build()

        val folder = URLEncoder.encode(remoteFolder, "UTF-8")
        val url = "${session.serverUrl.trimEnd('/')}/remote.php/dav/files/${session.username}/" +
            "$folder/contact-links.json"

        val body = client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            // 404 -- app not installed, or installed but nothing linked yet. Not an error.
            if (!resp.isSuccessful) return@runCatching emptyList()
            resp.body?.string().orEmpty()
        }
        if (body.isBlank()) return@runCatching emptyList()

        parseLinks(JSONObject(body), source)
    }.getOrDefault(emptyList())

    /**
     * Expected shape (same per app, just a different key for the item id):
     * {
     *   "links": [
     *     { "itemId": "123", "title": "...", "snippet": "...", "updatedAt": 169..., "contactUids": ["..."] }
     *   ]
     * }
     */
    private fun parseLinks(json: JSONObject, source: LinkedItemSource): List<RawLink> {
        val arr = json.optJSONArray("links") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val uids = o.getJSONArray("contactUids").let { u ->
                    (0 until u.length()).map { j -> u.getString(j) }
                }
                RawLink(
                    contactUids = uids,
                    item = LinkedItem(
                        source = source,
                        itemId = o.getString("itemId"),
                        title = o.optString("title", ""),
                        snippet = o.optString("snippet", ""),
                        updatedAt = o.optLong("updatedAt", 0L),
                    ),
                )
            }.getOrNull()
        }
    }
}
