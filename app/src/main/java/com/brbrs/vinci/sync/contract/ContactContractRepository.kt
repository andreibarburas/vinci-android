package com.brbrs.vinci.sync.contract

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.data.ContactSyncStateDao
import com.brbrs.vinci.data.ContactSyncStateEntity
import com.brbrs.vinci.network.CardDavRepository
import com.brbrs.vinci.network.ContactsRepository
import com.brbrs.vinci.network.VCardCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VinciContractSync"

/** Contacts (and their Data ops) per ContentResolver.applyBatch() call during a pull -- see
 *  Zaman's identical lesson (CalendarContractRepository.EVENT_BATCH_SIZE): a contact can produce
 *  up to ~10 ops (raw contact + name + org + a few phones/emails + postal + birthday + note +
 *  social links), so 40 contacts per chunk stays comfortably under the Binder transaction
 *  buffer's ~1MB ceiling even for a contact with several of everything. */
private const val CONTACT_BATCH_SIZE = 40

/**
 * Feature #ContactContract -- the Vinci half of "replace DAVx5 for contacts". Pulls the full
 * address book from Nextcloud via CardDAV directly (not through ContactsContract -- DAVx5 used
 * to be the thing that did that), mirrors it into Android's shared contacts provider under
 * Vinci's own stub account so the system Contacts app/dialer see them as real device contacts,
 * and pushes edits made there back out to Nextcloud.
 *
 * Reuses [ContactsRepository.syncFromDevice] for the "ContactsContract -> Vinci's own Room
 * `contacts` cache" direction on both sides of a sync, rather than re-deriving field values a
 * second way: after a pull writes fresh rows into ContactsContract, or after reading a dirty
 * row's current values to push, the normal device-read pipeline Vinci's UI already trusts is
 * what actually produces the data.
 *
 */
@Singleton
class ContactContractRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cardDavRepository: CardDavRepository,
    private val vCardCodec: VCardCodec,
    private val contactSyncStateDao: ContactSyncStateDao,
    private val contactsRepository: ContactsRepository,
    private val contactDao: ContactDao,
    private val syncStatus: ContactContractSyncStatus,
    private val syncNotifier: ContactContractSyncNotifier,
    private val contactContractPreferences: ContactContractPreferences,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Guards the whole push-then-pull sequence -- see Zaman's CalendarContractRepository for
     *  why: two overlapping callers (the Settings toggle's immediate first sync landing at the
     *  same moment as a periodic worker's own sync) can otherwise each run their own
     *  "does this already exist" pass blind to the other's not-yet-committed write. */
    private val syncMutex = Mutex()

    // Set once per sync() call, read by processContactChunk()'s per-chunk notifier call too --
    // safe as a plain field because syncMutex already guarantees only one sync() runs at a time.
    // Gates the SYSTEM notification only; syncStatus (the silent in-app Settings row) always
    // updates regardless, since that one was never the annoyance.
    private var notifyThisSync = false

    private fun Uri.asSyncAdapter(account: Account): Uri =
        buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
            .build()

    /** Push local edits out first, then pull -- so a pull never clobbers an edit the user just
     *  made in the system Contacts app with a stale server copy (same ordering reasoning as
     *  Zaman's sync()). */
    suspend fun sync() = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            syncStatus.syncStarted()
            notifyThisSync = contactContractPreferences.notifyOnSync.first()
            if (notifyThisSync) syncNotifier.showProgress(processed = 0, total = 0)
            try {
                // Bounded so a sync can never leave the "Syncing contacts" notification stuck
                // indefinitely -- a plain one-shot WorkManager worker already gets killed
                // outright by Android around the 10-minute mark with no chance to run any
                // cleanup code at all, which is exactly what left an earlier run's notification
                // ongoing for hours even after the data itself had long since finished catching
                // up. Timing out here first means the finally block below always runs and
                // clears it, whether or not the OS also kills the worker shortly after.
                val completed = withTimeoutOrNull(9 * 60 * 1000L) {
                    runCatching {
                        if (VinciAccount.existsOrNull(context) == null) return@runCatching
                        push()
                        pull()
                    }.onFailure { e -> android.util.Log.w(TAG, "sync() failed", e) }
                }
                if (completed == null) {
                    android.util.Log.w(TAG, "sync() timed out after 9 minutes, will resume next run")
                }
            } finally {
                val count = contactSyncStateDao.getAll().size
                val now = System.currentTimeMillis()
                syncStatus.syncFinished(now, count)
                contactContractPreferences.recordSync(now, count)
                if (notifyThisSync) syncNotifier.showFinished(count)
            }
        }
    }

    // -- Pull: Nextcloud -> ContactsContract -----------------------------------

    suspend fun pull() = withContext(Dispatchers.IO) {
        val account = VinciAccount.get(context)
        val serverEntries = cardDavRepository.listAddressBookEntries() // uid -> etag
        if (serverEntries.isEmpty()) {
            android.util.Log.i(TAG, "pull: listAddressBookEntries returned 0 entries, nothing to do")
            return@withContext
        }
        val localState = contactSyncStateDao.getAll().associateBy { it.uid }

        val toDelete = localState.keys - serverEntries.keys
        toDelete.forEach { uid ->
            val state = localState[uid] ?: return@forEach
            resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, state.rawContactId).asSyncAdapter(account), null, null)
            contactSyncStateDao.deleteByUid(uid)
        }

        val toFetch = serverEntries.filter { (uid, etag) -> localState[uid]?.etag != etag }.keys
        // Temporary diagnostic for the "sync stops partway" investigation.
        android.util.Log.i(TAG, "pull: serverEntries=${serverEntries.size} localState=${localState.size} toDelete=${toDelete.size} toFetch=${toFetch.size}")

        // No early return when toFetch is empty: Vinci's own Room `contacts` cache (and so the
        // count it displays) is only ever refreshed from ContactsContract by the
        // syncFromDevice() call below. Short-circuiting past it whenever there's nothing new to
        // fetch -- the previous shape of this method -- left that cache stale even after
        // ContactsContract itself was already fully populated by an earlier run, which is
        // exactly what made a completed sync look stuck on an old count.
        toFetch.chunked(CONTACT_BATCH_SIZE).forEach { chunk ->
            processContactChunk(chunk, account, localState, serverEntries, toFetch.size)
        }

        android.util.Log.i(TAG, "pull: finished processing ${toFetch.size} contacts")
        contactsRepository.syncFromDevice()
    }

    /** Fetches, parses and applies one chunk of contacts as a single ContentResolver.applyBatch()
     *  transaction. That call is one Binder IPC transaction capped around ~1MB total, and a chunk
     *  of CONTACT_BATCH_SIZE contacts that each carry several phones/emails/social links -- or
     *  just land in the same chunk together -- can still add up past that even with photo bytes
     *  already kept out of this batch (see dataOps()). Rather than guess the exact ceiling and
     *  tune CONTACT_BATCH_SIZE around it, a failed transaction is bisected and each half retried
     *  on its own, recursing down to one contact at a time if it has to: a sync degrades to
     *  slower-but-complete instead of stalling partway through a run. */
    private suspend fun processContactChunk(
        chunk: List<String>,
        account: Account,
        localState: Map<String, ContactSyncStateEntity>,
        serverEntries: Map<String, String>,
        totalToFetch: Int,
    ) {
        if (chunk.isEmpty()) return

        // Fetch+parse this chunk's vCards concurrently -- each is its own HTTP GET, and doing
        // all of them one at a time (as the original pull did) means a ~1300-contact address
        // book can take long enough in wall-clock time to run past the ~10-minute execution
        // ceiling Android applies to a plain one-shot WorkManager worker, which kills it
        // silently (no exception, nothing for sync()'s own runCatching to log) mid-run. OkHttp's
        // own per-host connection limit (default 5) keeps this from hammering the server despite
        // the chunk-wide fan-out.
        val parsedByUid: Map<String, VCardCodec.ParsedVCard> = coroutineScope {
            chunk.map { uid ->
                async {
                    val vcard = runCatching { cardDavRepository.fetchVCard(uid) }.getOrNull() ?: return@async null
                    vCardCodec.parse(vcard)?.let { uid to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }

        val ops = ArrayList<ContentProviderOperation>()
        // rawContactOpIndex: uid -> index of its RawContacts insert op in THIS chunk, for
        // brand-new contacts only (updates reference an already-known rawContactId directly).
        val newContactOpIndex = mutableMapOf<String, Int>()
        val existingRawIdForUid = mutableMapOf<String, Long>()
        val photoForUid = mutableMapOf<String, ByteArray>()

        chunk.forEach { uid ->
            val parsed = parsedByUid[uid] ?: return@forEach
            decodePhotoBytes(parsed.photoBase64)?.let { photoForUid[uid] = it }
            val existingId = localState[uid]?.rawContactId ?: findRawContactIdBySourceId(account, uid)

            if (existingId != null) {
                existingRawIdForUid[uid] = existingId
                // Delete-and-reinsert the mirrored Data rows -- same pattern as Zaman's
                // reminders: simpler and safer than diffing individual fields, and the raw
                // contact row itself (and so its aggregate CONTACT_ID) stays stable, which
                // matters since Vinci's own `contacts` table is keyed by that aggregate id.
                MIRRORED_MIMETYPES.forEach { mimeType ->
                    ops += ContentProviderOperation.newDelete(Data.CONTENT_URI.asSyncAdapter(account))
                        .withSelection("${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(existingId.toString(), mimeType))
                        .build()
                }
                ops += dataOps(parsed) { it.withValue(Data.RAW_CONTACT_ID, existingId) }
            } else {
                val rawOpIndex = ops.size
                newContactOpIndex[uid] = rawOpIndex
                ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI.asSyncAdapter(account))
                    .withValue(RawContacts.SOURCE_ID, uid)
                    .build()
                ops += dataOps(parsed) { it.withValueBackReference(Data.RAW_CONTACT_ID, rawOpIndex) }
            }
        }

        if (ops.isEmpty()) return

        val results = try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            if (chunk.size == 1) {
                android.util.Log.w(TAG, "pull: applyBatch failed for single contact ${chunk.first()}, skipping it", e)
                syncStatus.chunkProcessed(1, totalToFetch)
                return
            }
            android.util.Log.w(TAG, "pull: applyBatch failed for ${ops.size} ops across ${chunk.size} contacts, splitting and retrying", e)
            val mid = chunk.size / 2
            processContactChunk(chunk.subList(0, mid), account, localState, serverEntries, totalToFetch)
            processContactChunk(chunk.subList(mid, chunk.size), account, localState, serverEntries, totalToFetch)
            return
        }

        android.util.Log.i(TAG, "pull: chunk of ${chunk.size} contacts (${ops.size} ops) applied OK")
        syncStatus.chunkProcessed(chunk.size, totalToFetch)
        if (notifyThisSync) syncNotifier.showProgress(processed = syncStatus.progress.value.processed, total = totalToFetch)

        newContactOpIndex.forEach { (uid, opIndex) ->
            val rawId = results.getOrNull(opIndex)?.uri?.let { ContentUris.parseId(it) } ?: return@forEach
            contactSyncStateDao.upsert(ContactSyncStateEntity(uid, rawId, serverEntries[uid] ?: ""))
        }
        existingRawIdForUid.forEach { (uid, rawId) ->
            contactSyncStateDao.upsert(ContactSyncStateEntity(uid, rawId, serverEntries[uid] ?: ""))
        }

        // Apply photos one contact at a time -- each is its own small transaction, so no single
        // applyBatch() call ever carries more than one photo's worth of bytes.
        photoForUid.forEach { (uid, bytes) ->
            val rawId = existingRawIdForUid[uid]
                ?: newContactOpIndex[uid]?.let { idx -> results.getOrNull(idx)?.uri?.let { ContentUris.parseId(it) } }
                ?: return@forEach
            runCatching {
                resolver.insert(
                    Data.CONTENT_URI.asSyncAdapter(account),
                    android.content.ContentValues().apply {
                        put(Data.RAW_CONTACT_ID, rawId)
                        put(Data.MIMETYPE, CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        put(CommonDataKinds.Photo.PHOTO, bytes)
                        put(CommonDataKinds.Photo.IS_SUPER_PRIMARY, 1)
                    },
                )
            }.onFailure { e -> android.util.Log.w(TAG, "pull: photo insert failed for raw contact $rawId", e) }
        }
    }

    private fun decodePhotoBytes(photoBase64: String?): ByteArray? {
        if (photoBase64.isNullOrBlank()) return null
        return runCatching { android.util.Base64.decode(photoBase64, android.util.Base64.DEFAULT) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun findRawContactIdBySourceId(account: Account, uid: String): Long? {
        resolver.query(
            RawContacts.CONTENT_URI.asSyncAdapter(account),
            arrayOf(RawContacts._ID),
            "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.SOURCE_ID} = ?",
            arrayOf(account.name, account.type, uid),
            null,
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    /** Builds the Data rows for one parsed vCard. [rawContactRef] sets RAW_CONTACT_ID on each
     *  builder -- either a direct value (update) or a withValueBackReference (brand-new insert,
     *  where the real id isn't known until the batch actually runs). */
    private fun dataOps(
        parsed: VCardCodec.ParsedVCard,
        rawContactRef: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder,
    ): List<ContentProviderOperation> {
        val ops = mutableListOf<ContentProviderOperation>()

        ops += rawContactRef(ContentProviderOperation.newInsert(Data.CONTENT_URI))
            .withValue(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, parsed.displayName)
            .build()

        if (parsed.organization.isNotBlank() || parsed.jobTitle.isNotBlank()) {
            ops += rawContactRef(ContentProviderOperation.newInsert(Data.CONTENT_URI))
                .withValue(Data.MIMETYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Organization.COMPANY, parsed.organization)
                .withValue(CommonDataKinds.Organization.TITLE, parsed.jobTitle)
                .build()
        }

        parsed.phones.forEachIndexed { i, phone ->
            ops += rawContactRef(ContentProviderOperation.newInsert(Data.CONTENT_URI))
                .withValue(Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Phone.NUMBER, phone.number)
                .withValue(CommonDataKinds.Phone.TYPE, phoneTypeInt(phone.type))
                .withValue(CommonDataKinds.Phone.IS_PRIMARY, if (i == 0) 1 else 0)
                .build()
        }

        parsed.emails.forEachIndexed { i, email ->
            ops += rawContactRef(ContentProviderOperation.newInsert(Data.CONTENT_URI))
                .withValue(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Email.ADDRESS, email.address)
                .withValue(CommonDataKinds.Email.TYPE, emailTypeInt(email.type))
                .withValue(CommonDataKinds.Email.IS_PRIMARY, if (i == 0) 1 else 0)
                .build()
        }

        if (parsed.address.isNotBlank()) {
            ops += rawContactRef(ContentProviderOperation.newInsert(Data.CONTENT_URI))
                .withValue(Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, parsed.address)
                .build()
        }

        if (parsed.birthday.isNotBlank()) {
            ops += rawContactRef(ContentProviderOperation.newInsert(Data.CONTENT_URI))
                .withValue(Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Event.START_DATE, parsed.birthday)
                .withValue(CommonDataKinds.Event.TYPE, CommonDataKinds.Event.TYPE_BIRTHDAY)
                .build()
        }

        if (parsed.notes.isNotBlank()) {
            ops += rawContactRef(ContentProviderOperation.newInsert(Data.CONTENT_URI))
                .withValue(Data.MIMETYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Note.NOTE, parsed.notes)
                .build()
        }

        // PHOTO is deliberately NOT added here -- see decodePhotoBytes()/pull()'s per-contact
        // photo pass below. A single applyBatch() call is one Binder IPC transaction capped
        // around ~1MB; CONTACT_BATCH_SIZE=40 was sized for small text ops, and 40 contacts'
        // worth of inline JPEG bytes in the same transaction can blow straight through that
        // limit (TransactionTooLargeException), aborting the whole chunk. Photo bytes are
        // applied as their own single-row operation per contact instead, after this batch
        // commits and real raw-contact ids are known, so no transaction ever carries more than
        // one photo.

        runCatching { JSONArray(parsed.socialLinksJson) }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "")
                if (url.isBlank()) continue
                val label = obj.optString("label", obj.optString("platform", "other"))
                ops += rawContactRef(ContentProviderOperation.newInsert(Data.CONTENT_URI))
                    .withValue(Data.MIMETYPE, CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Website.URL, url)
                    .withValue(CommonDataKinds.Website.LABEL, label)
                    .withValue(CommonDataKinds.Website.TYPE, CommonDataKinds.Website.TYPE_CUSTOM)
                    .build()
            }
        }

        return ops
    }

    // -- Push: ContactsContract -> Nextcloud -----------------------------------

    suspend fun push() = withContext(Dispatchers.IO) {
        val account = VinciAccount.get(context)

        val deletedRows = mutableListOf<Long>()
        val dirtyRowToContactId = mutableMapOf<Long, Long>() // rawContactId -> aggregate CONTACT_ID
        resolver.query(
            RawContacts.CONTENT_URI.asSyncAdapter(account),
            arrayOf(RawContacts._ID, RawContacts.CONTACT_ID, RawContacts.DIRTY, RawContacts.DELETED),
            "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ? AND (${RawContacts.DIRTY} = 1 OR ${RawContacts.DELETED} = 1)",
            arrayOf(account.name, account.type),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val rawId = cursor.getLong(0)
                val contactId = cursor.getLong(1)
                val deleted = cursor.getInt(3) == 1
                if (deleted) deletedRows += rawId else dirtyRowToContactId[rawId] = contactId
            }
        }
        if (deletedRows.isEmpty() && dirtyRowToContactId.isEmpty()) return@withContext

        deletedRows.forEach { rawId ->
            runCatching { pushOneDeletedRow(account, rawId) }
                .onFailure { e -> android.util.Log.w(TAG, "push delete failed for raw contact $rawId", e) }
        }

        if (dirtyRowToContactId.isNotEmpty()) {
            // Refresh Room from the device first, so the dirty rows' CURRENT values (not
            // whatever was cached from the last sync) are what actually gets built into a vCard.
            contactsRepository.syncFromDevice()
            dirtyRowToContactId.forEach { (rawId, contactId) ->
                runCatching { pushOneDirtyRow(account, rawId, contactId) }
                    .onFailure { e -> android.util.Log.w(TAG, "push failed for raw contact $rawId", e) }
            }
        }
    }

    private suspend fun pushOneDeletedRow(account: Account, rawId: Long) {
        val state = contactSyncStateDao.byRawContactId(rawId)
        if (state != null) {
            cardDavRepository.deleteVCard(state.uid, state.etag.ifBlank { null })
            contactSyncStateDao.deleteByUid(state.uid)
        }
        resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId).asSyncAdapter(account), null, null)
    }

    private suspend fun pushOneDirtyRow(account: Account, rawId: Long, aggregateContactId: Long) {
        val contact = contactDao.getContactById(aggregateContactId) ?: return
        val existingState = contactSyncStateDao.byRawContactId(rawId)
        val sourceId = existingState?.uid ?: readSourceId(account, rawId)
        val uid = sourceId?.ifBlank { null } ?: UUID.randomUUID().toString()

        val vcard = vCardCodec.build(
            uid = uid,
            displayName = contact.displayName,
            phones = parsePhoneEntries(contact.allPhones),
            emails = parseEmailEntries(contact.allEmails),
            organization = contact.organization,
            jobTitle = contact.jobTitle,
            birthday = contact.birthday,
            address = contact.address,
            notes = contact.notes,
            socialLinksJson = contact.socialLinks,
            photoBase64 = readContactPhotoBase64(aggregateContactId),
        )
        // isNew must track whether a server UID (SOURCE_ID) was already assigned, not whether
        // contact_sync_state happens to have an etag for it -- those can diverge after state
        // loss (reinstall, cleared app data), and getting this wrong sends If-None-Match: * for
        // a contact that already exists server-side, which the server correctly rejects with
        // 412 rather than silently overwriting.
        val isNew = sourceId.isNullOrBlank()
        val newEtag = cardDavRepository.putVCard(uid, vcard, ifMatchEtag = existingState?.etag, isNew = isNew) ?: return

        contactSyncStateDao.upsert(ContactSyncStateEntity(uid, rawId, newEtag))
        if (sourceId.isNullOrBlank()) {
            resolver.update(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId).asSyncAdapter(account),
                android.content.ContentValues().apply { put(RawContacts.SOURCE_ID, uid) },
                null, null,
            )
        }
        resolver.update(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId).asSyncAdapter(account),
            android.content.ContentValues().apply { put(RawContacts.DIRTY, 0) },
            null, null,
        )
    }

    /** Reads the aggregate contact's current photo (highest-res available) and base64-encodes
     *  it for embedding in an outgoing vCard. ContactEntity.photoUri only holds a content:// URI
     *  reference, not raw bytes, so this reads straight from ContactsContract instead -- the same
     *  way the system Contacts app itself would fetch a photo to display or share it. */
    private fun readContactPhotoBase64(aggregateContactId: Long): String? {
        val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, aggregateContactId)
        val bytes = runCatching {
            ContactsContract.Contacts.openContactPhotoInputStream(resolver, contactUri, /* preferHighres = */ true)
                ?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        // NO_WRAP: this app folds PHOTO lines itself per RFC 6350 in VCardCodec.build(), so
        // Base64's own line-wrapping would just add a second, conflicting layer of CRLFs.
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun readSourceId(account: Account, rawId: Long): String? {
        resolver.query(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId).asSyncAdapter(account),
            arrayOf(RawContacts.SOURCE_ID),
            null, null, null,
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun parsePhoneEntries(allPhonesJson: String): List<VCardCodec.PhoneEntry> = runCatching {
        val arr = JSONArray(allPhonesJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VCardCodec.PhoneEntry(o.optString("type", "Other"), o.optString("number", ""))
        }
    }.getOrDefault(emptyList())

    private fun parseEmailEntries(allEmailsJson: String): List<VCardCodec.EmailEntry> = runCatching {
        val arr = JSONArray(allEmailsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VCardCodec.EmailEntry(o.optString("type", "Other"), o.optString("address", ""))
        }
    }.getOrDefault(emptyList())

    private fun phoneTypeInt(label: String): Int = when (label) {
        "Mobile" -> CommonDataKinds.Phone.TYPE_MOBILE
        "Home" -> CommonDataKinds.Phone.TYPE_HOME
        "Work" -> CommonDataKinds.Phone.TYPE_WORK
        else -> CommonDataKinds.Phone.TYPE_OTHER
    }

    private fun emailTypeInt(label: String): Int = when (label) {
        "Home" -> CommonDataKinds.Email.TYPE_HOME
        "Work" -> CommonDataKinds.Email.TYPE_WORK
        else -> CommonDataKinds.Email.TYPE_OTHER
    }
}

private val MIRRORED_MIMETYPES = listOf(
    CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
    CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
    CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
    CommonDataKinds.Email.CONTENT_ITEM_TYPE,
    CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
    CommonDataKinds.Event.CONTENT_ITEM_TYPE,
    CommonDataKinds.Note.CONTENT_ITEM_TYPE,
    CommonDataKinds.Website.CONTENT_ITEM_TYPE,
    CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
)
