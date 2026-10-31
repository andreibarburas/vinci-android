package com.brbrs.vinci.network

import android.util.Base64
import com.brbrs.vinci.auth.AuthRepository
import com.brbrs.vinci.auth.VinciSession
import com.brbrs.vinci.ui.components.SOCIAL_PLATFORMS
import com.brbrs.vinci.ui.components.platformForUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes contact data directly from/to Nextcloud via CardDAV.
 * Used specifically for fields DAVx5 does not sync into Android ContactsContract,
 * such as X-SOCIALPROFILE.
 */
@Singleton
class CardDavRepository @Inject constructor(
    private val authRepository: AuthRepository,
    private val httpClient: OkHttpClient,
) {
    private fun authedClient(session: VinciSession) = httpClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", session.basicAuthHeader())
                    .build()
            )
        }
        .build()

    // -- Address book discovery -----------------------------------------------

    /**
     * Discovers the primary address book URL for the user via PROPFIND.
     * Returns something like:
     *   https://server/remote.php/dav/addressbooks/users/username/contacts-1/
     * Cached in AuthRepository DataStore after first discovery.
     */
    suspend fun discoverAddressBookUrl(): String? = withContext(Dispatchers.IO) {
        val session = authRepository.session.first() ?: run {
            return@withContext null
        }

        session.addressBookUrl.let {
            if (it.isNotBlank() && !it.endsWith(".vcf")) {
                return@withContext it
            }
            if (it.endsWith(".vcf")) {
                kotlinx.coroutines.runBlocking { authRepository.saveAddressBookUrl("") }
            }
        }

        val client = authedClient(session)
        val base   = "${session.serverUrl.trimEnd('/')}/remote.php/dav/addressbooks/users/${session.username}/"

        val propfind = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
  <prop><displayname/><resourcetype/></prop>
</propfind>"""

        return@withContext runCatching {
            val resp = client.newCall(
                Request.Builder()
                    .url(base)
                    .method("PROPFIND", propfind.toRequestBody("application/xml".toMediaType()))
                    .header("Depth", "1")
                    .build()
            ).execute()

            val body = resp.body?.string() ?: run {
                resp.close()
                return@runCatching null
            }
            resp.close()

            val hrefRegex = Regex(
                """<[^>]*:?href[^>]*>([^<]+/)</[^>]*:?href>""",
                RegexOption.IGNORE_CASE
            )
            val basePath = "/remote.php/dav/addressbooks/users/${session.username}/"
            val allHrefs = hrefRegex.findAll(body).map { it.groupValues[1] }.toList()

            val path = allHrefs.firstOrNull { it != basePath && it.startsWith(basePath) }
                ?: run {
                    // Fallback: try well-known Nextcloud address book paths directly
                    val fallbacks = listOf("contacts", "contacts-1", "default", "addressbook")
                    fallbacks.map { "$basePath$it/" }
                        .firstOrNull { fallbackPath ->
                            val testUrl = "${session.serverUrl.trimEnd('/')}$fallbackPath"
                            val testResp = runCatching {
                                client.newCall(Request.Builder().url(testUrl)
                                    .method("PROPFIND", propfind.toRequestBody("application/xml".toMediaType()))
                                    .header("Depth", "0").build()).execute()
                            }.getOrNull()
                            val ok = testResp?.isSuccessful == true
                            testResp?.close()
                            ok
                        }
                        ?: run {
                            return@runCatching null
                        }
                }

            val url = if (path.startsWith("http")) path
                      else "${session.serverUrl.trimEnd('/')}$path"
            authRepository.saveAddressBookUrl(url)
            url
        }.getOrNull()
    }

    // -- Address book UID listing ------------------------------------------

    /**
     * PROPFINDs the address book to get the list of all VCF filenames (UIDs).
     * Returns a Set of UIDs like "21f30e04-ba51-4302-a293-d439a21fd5dd".
     * One request instead of N requests -- used to filter before fetching.
     */
    suspend fun listAddressBookUids(): Set<String> = withContext(Dispatchers.IO) {
        val session     = authRepository.session.first() ?: return@withContext emptySet()
        val addressBook = discoverAddressBookUrl() ?: return@withContext emptySet()
        val client      = authedClient(session)

        val propfind = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><getetag/></prop></propfind>"""

        return@withContext runCatching {
            val resp = client.newCall(
                Request.Builder()
                    .url(addressBook)
                    .method("PROPFIND", propfind.toRequestBody("application/xml".toMediaType()))
                    .header("Depth", "1")
                    .build()
            ).execute()
            val body = resp.body?.string() ?: return@runCatching emptySet()
            resp.close()

            // Extract hrefs that end with .vcf and strip to UID
            val hrefRegex = Regex(
                """<[^>]*:href[^>]*>([^<]+\.vcf)</[^>]*:href>""",
                RegexOption.IGNORE_CASE
            )
            hrefRegex.findAll(body)
                .map { it.groupValues[1].trimEnd('/').substringAfterLast('/').removeSuffix(".vcf") }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrDefault(emptySet()).also {
        }
    }

    // -- Address book UID + ETag listing (feature #ContactContract) -----------

    /**
     * Like [listAddressBookUids] but keeps each contact's ETag too, so a pull can tell which
     * contacts actually changed since the last sync without refetching every vCard — same
     * purpose as Zaman's per-calendar feedEtag, at per-contact granularity since CardDAV
     * addressbooks (unlike Nextcloud's calendar subscriptions) don't expose one collection-wide
     * ETag that changes on every edit.
     */
    suspend fun listAddressBookEntries(): Map<String, String> = withContext(Dispatchers.IO) {
        val session     = authRepository.session.first() ?: return@withContext emptyMap()
        val addressBook = discoverAddressBookUrl() ?: return@withContext emptyMap()
        val client      = authedClient(session)

        val propfind = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><getetag/></prop></propfind>"""

        return@withContext runCatching {
            val resp = client.newCall(
                Request.Builder()
                    .url(addressBook)
                    .method("PROPFIND", propfind.toRequestBody("application/xml".toMediaType()))
                    .header("Depth", "1")
                    .build()
            ).execute()
            val body = resp.body?.string() ?: return@runCatching emptyMap()
            resp.close()

            // Each <response> block holds one href followed by its getetag, in that order --
            // same simple sequential-scan assumption listAddressBookUids already relies on
            // (Nextcloud emits one href+etag pair per block, never interleaved across blocks).
            val result = mutableMapOf<String, String>()
            val blocks = body.split(Regex("""(?i)<[^:>]*:?response[ >]"""))
            val hrefRegex = Regex("""<[^>]*:?href[^>]*>([^<]+\.vcf)</[^>]*:?href>""", RegexOption.IGNORE_CASE)
            val etagRegex = Regex("""<[^>]*:?getetag[^>]*>([^<]+)</[^>]*:?getetag>""", RegexOption.IGNORE_CASE)
            blocks.forEach { block ->
                val uid  = hrefRegex.find(block)?.groupValues?.get(1)
                    ?.trimEnd('/')?.substringAfterLast('/')?.removeSuffix(".vcf")?.removeSuffix(".VCF")
                    ?: return@forEach
                val etag = etagRegex.find(block)?.groupValues?.get(1)?.trim() ?: ""
                if (uid.isNotBlank()) result[uid] = etag
            }
            // Temporary diagnostic for the "sync stops partway" investigation -- tells us
            // whether a short-circuited pull() is because this parsed zero/few entries from a
            // response the server did send, or because the server itself only returned a
            // partial listing (addressBook, response byte length, and parsed count below).
            android.util.Log.i("VinciContractSync", "listAddressBookEntries: addressBook=$addressBook bodyLen=${body.length} blocks=${blocks.size} parsed=${result.size}")
            result
        }.onFailure { e -> android.util.Log.w("VinciContractSync", "listAddressBookEntries failed", e) }
            .getOrDefault(emptyMap())
    }

    // -- vCard create/update/delete (feature #ContactContract) -----------------

    /**
     * Creates or updates a contact's vCard on Nextcloud. [ifMatchEtag] null + [isNew] true means
     * "must not already exist" (If-None-Match: *); otherwise the stored ETag guards against
     * clobbering a concurrent server-side edit, same discipline as Zaman's event writes.
     * Returns the new ETag on success, or null on failure.
     */
    suspend fun putVCard(uid: String, vcard: String, ifMatchEtag: String?, isNew: Boolean): String? = withContext(Dispatchers.IO) {
        val session     = authRepository.session.first() ?: return@withContext null
        val addressBook = discoverAddressBookUrl() ?: return@withContext null
        val client      = authedClient(session)
        val url         = "$addressBook$uid.vcf"

        return@withContext runCatching {
            val req = Request.Builder()
                .url(url)
                .put(vcard.toRequestBody("text/vcard; charset=utf-8".toMediaType()))
                .apply {
                    if (isNew) header("If-None-Match", "*")
                    else if (!ifMatchEtag.isNullOrBlank()) header("If-Match", ifMatchEtag)
                }
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { resp.close(); return@runCatching null }
            val newEtag = resp.header("ETag")
            resp.close()
            // Nextcloud doesn't always echo the new ETag on PUT -- a bare "created/updated OK,
            // no ETag given" still counts as success, so the next pull's listAddressBookEntries
            // picks up whatever ETag the server actually assigned.
            newEtag ?: ""
        }.getOrNull()
    }

    /** Deletes a contact's vCard on Nextcloud. [etag], if known, guards against deleting a
     *  version the user doesn't know they've already edited elsewhere. */
    suspend fun deleteVCard(uid: String, etag: String?): Boolean = withContext(Dispatchers.IO) {
        val session     = authRepository.session.first() ?: return@withContext false
        val addressBook = discoverAddressBookUrl() ?: return@withContext false
        val client      = authedClient(session)
        val url         = "$addressBook$uid.vcf"

        return@withContext runCatching {
            val req = Request.Builder()
                .url(url)
                .delete()
                .apply { if (!etag.isNullOrBlank()) header("If-Match", etag) }
                .build()
            val resp = client.newCall(req).execute()
            // A 404 means it's already gone on the server -- as good as a successful delete.
            (resp.isSuccessful || resp.code == 404).also { resp.close() }
        }.getOrDefault(false)
    }

    // -- vCard fetch ----------------------------------------------------------

    /**
     * Fetches the raw vCard for a contact by UID.
     * Returns the vCard text or null if not found.
     */
    suspend fun fetchVCard(uid: String): String? = withContext(Dispatchers.IO) {
        val session      = authRepository.session.first() ?: return@withContext null
        val addressBook  = discoverAddressBookUrl()
        if (addressBook == null) {
            return@withContext null
        }
        val client = authedClient(session)
        val url    = "$addressBook$uid.vcf"

        return@withContext runCatching {
            val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
            if (!resp.isSuccessful) { resp.close(); return@runCatching null }
            val body = resp.body?.string()
            resp.close()
            body
        }.getOrNull()
    }

    suspend fun fetchSocialLinks(uid: String): String? = withContext(Dispatchers.IO) {
        val vcard = fetchVCard(uid) ?: return@withContext null
        val result = parseSocialLinks(vcard)
        result
    }

    // -- vCard write ----------------------------------------------------------

    /**
     * Writes updated social links -- and, since this is the one place Vinci already talks
     * CardDAV directly, the organization/title too -- back to a contact's vCard on Nextcloud.
     * Fetches the current vCard, replaces the relevant lines, and PUTs it back with the ETag
     * for conflict detection.
     *
     * Organization/title ride along here rather than relying solely on DAVx5's own upload of
     * the ContactsContract edit: that upload runs on its own schedule, after this call has
     * already moved the vCard's ETag by rewriting the social lines. DAVx5 then finds its cached
     * ETag stale, and resolving that conflict can mean re-pulling the server's (still old)
     * vCard and overwriting the just-made local edit -- which is how a freshly typed company
     * name was disappearing from Nextcloud *and* from Vinci itself. Setting ORG/TITLE here too
     * means the server always ends up with the right value in the same PUT, so even if DAVx5
     * later overwrites the local contact from the server, it overwrites it with the correct
     * data instead of stale data.
     */
    suspend fun writeSocialLinks(
        uid: String,
        socialLinksJson: String,
        organization: String = "",
        jobTitle: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        val session     = authRepository.session.first() ?: return@withContext false
        val addressBook = discoverAddressBookUrl() ?: return@withContext false
        val client      = authedClient(session)
        val url         = "$addressBook$uid.vcf"

        return@withContext runCatching {
            // 1. Fetch current vCard and ETag
            val getResp = client.newCall(
                Request.Builder().url(url).get()
                    .header("Accept", "text/vcard; version=3.0")
                    .build()
            ).execute()
            if (!getResp.isSuccessful) return@runCatching false
            val etag  = getResp.header("ETag") ?: ""
            val vcard = getResp.body?.string() ?: return@runCatching false
            getResp.close()

            // 2. Strip existing X-SOCIALPROFILE, social URL, ORG and TITLE lines
            val cleaned = vcard.lines().filter { line ->
                !line.startsWith("X-SOCIALPROFILE", ignoreCase = true) &&
                !line.startsWith("IMPP", ignoreCase = true) &&
                !line.startsWith("ORG", ignoreCase = true) &&
                !line.startsWith("TITLE", ignoreCase = true)
            }.joinToString("\n")

            // 3. Build replacement lines from the current state
            val newLines = buildString {
                append(buildSocialLines(socialLinksJson))
                if (organization.isNotBlank()) appendLine("ORG:${escapeVCardText(organization)}")
                if (jobTitle.isNotBlank()) appendLine("TITLE:${escapeVCardText(jobTitle)}")
            }

            // 4. Insert before END:VCARD
            val updated = cleaned.replace(
                "END:VCARD",
                "${newLines}END:VCARD"
            )

            // 5. PUT back with ETag
            val putReq = Request.Builder()
                .url(url)
                .put(updated.toRequestBody("text/vcard; charset=utf-8".toMediaType()))
                .apply { if (etag.isNotBlank()) header("If-Match", etag) }
                .build()
            val putResp = client.newCall(putReq).execute()
            putResp.isSuccessful.also { putResp.close() }
        }.getOrDefault(false)
    }

    /** Escapes commas, semicolons and backslashes per RFC 6350 so a free-typed value can't break the line. */
    internal fun escapeVCardText(value: String): String =
        value.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;")

    // -- vCard parsers --------------------------------------------------------

    /**
     * Parses X-SOCIALPROFILE;TYPE=LINKEDIN:https://...
     * and URL;TYPE=HOME;VALUE=URI:https://...
     * from a raw vCard string.
     */
    fun parseSocialLinks(vcard: String): String {
        val links = JSONArray()
        val seen  = mutableSetOf<String>()

        // Unfold vCard lines (lines ending with \r\n + whitespace are continuations)
        val unfolded = vcard.replace(Regex("\r\n[ \t]"), "").replace(Regex("\n[ \t]"), "")

        unfolded.lines().forEach { line ->
            val upper = line.uppercase().trimStart()
            when {
                // X-SOCIALPROFILE;TYPE=LINKEDIN:https://...
                upper.startsWith("X-SOCIALPROFILE") -> {
                    // Find the URL — it starts after the last colon that precedes "http"
                    // e.g. X-SOCIALPROFILE;TYPE=LINKEDIN:https://linkedin.com/in/user
                    val colonIdx = line.indexOf(":http", ignoreCase = true)
                        .takeIf { it >= 0 } ?: line.lastIndexOf(":")
                    val url = if (colonIdx >= 0) line.substring(colonIdx + 1).trim() else ""
                    if (url.isBlank() || url in seen) return@forEach
                    seen.add(url)
                    // Extract TYPE value
                    val typeParam = Regex("TYPE=([^;:]+)", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.trim() ?: ""
                    val platform = SOCIAL_PLATFORMS
                        .firstOrNull { it.key.equals(typeParam, ignoreCase = true) || it.label.equals(typeParam, ignoreCase = true) }
                        ?: platformForUrl(url)
                    links.put(JSONObject().apply {
                        put("platform", platform.key)
                        put("url", url)
                    })
                }

                // IMPP:xmpp:user@mastodon.social etc.
                upper.startsWith("IMPP") -> {
                    val colonIdx = line.indexOf(":http", ignoreCase = true)
                        .takeIf { it >= 0 } ?: line.lastIndexOf(":")
                    val value = if (colonIdx >= 0) line.substring(colonIdx + 1).trim() else ""
                    if (value.isBlank() || value in seen) return@forEach
                    // Only include http/https URIs
                    if (!value.startsWith("http", ignoreCase = true)) return@forEach
                    seen.add(value)
                    val platform = platformForUrl(value)
                    links.put(JSONObject().apply {
                        put("platform", platform.key)
                        put("url", value)
                    })
                }

                // URL;TYPE=HOME;VALUE=URI:https://barburas.com
                upper.startsWith("URL") -> {
                    // URL;TYPE=HOME;VALUE=URI:https://barburas.com
                    val colonIdx = line.indexOf(":http", ignoreCase = true)
                        .takeIf { it >= 0 } ?: line.lastIndexOf(":")
                    val url = if (colonIdx >= 0) line.substring(colonIdx + 1).trim() else ""
                    if (url.isBlank() || url in seen) return@forEach
                    if (!url.startsWith("http", ignoreCase = true)) return@forEach
                    seen.add(url)
                    val typeParam = Regex("TYPE=([^;:]+)", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.trim() ?: ""
                    // Try to match a known platform first
                    val knownPlatform = SOCIAL_PLATFORMS
                        .firstOrNull { it.key.equals(typeParam, ignoreCase = true) }
                        ?: platformForUrl(url)
                    // For plain website URLs (home, work, etc.), store a custom label
                    val platformKey   = knownPlatform.key
                    val displayLabel  = if (platformKey == "other" && typeParam.isNotBlank())
                        typeParam.replaceFirstChar { it.uppercase() }  // "home" -> "Home"
                    else null
                    links.put(JSONObject().apply {
                        put("platform", platformKey)
                        put("url", url)
                        if (displayLabel != null) put("label", displayLabel)
                    })
                }
            }
        }

        val result = links.toString()
        return result
    }

    internal fun buildSocialLines(socialLinksJson: String): String {
        if (socialLinksJson.isBlank() || socialLinksJson == "[]") return ""
        return try {
            val arr = JSONArray(socialLinksJson)
            buildString {
                for (i in 0 until arr.length()) {
                    val obj      = arr.getJSONObject(i)
                    val url      = obj.optString("url", "").trim()
                    val key      = obj.optString("platform", "other")
                    val platform = SOCIAL_PLATFORMS.firstOrNull { it.key == key }
                    if (url.isBlank()) continue

                    if (platform != null && platform.key != "other") {
                        appendLine("X-SOCIALPROFILE;TYPE=${platform.label.uppercase()}:$url")
                    } else {
                        appendLine("URL;TYPE=HOME;VALUE=URI:$url")
                    }
                }
            }
        } catch (e: Exception) { "" }
    }
}
