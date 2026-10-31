package com.brbrs.vinci.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Contact ───────────────────────────────────────────────────────────────────

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: Long,
    val cardavUid: String,
    val displayName: String,
    val phoneNumber: String,
    val allPhones: String = "",
    val email: String = "",
    val allEmails: String = "",
    val organization: String = "",
    val jobTitle: String = "",
    val photoUri: String = "",
    val notes: String = "",
    val birthday: String = "",
    val address: String = "",
    val lastCallTimestamp: Long = 0,
    val followUpDue: Long = 0,
    val isStarred: Boolean = false,
    val socialLinks: String = "",
)

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE birthday != ''")
    fun getContactsWithBirthdays(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE displayName LIKE '%' || :query || '%' OR phoneNumber LIKE '%' || :query || '%' ORDER BY displayName ASC")
    fun searchContacts(query: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE followUpDue > 0 AND followUpDue <= :now ORDER BY followUpDue ASC")
    fun getFollowUpsDue(now: Long): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE lastCallTimestamp > 0 ORDER BY lastCallTimestamp DESC LIMIT 10")
    fun getRecentContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isStarred = 1 ORDER BY displayName ASC")
    fun getStarredContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isStarred = 1")
    suspend fun getStarredContactsOnce(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getContactById(id: Long): ContactEntity?
    @Query("SELECT * FROM contacts WHERE phoneNumber = :phone LIMIT 1")
    suspend fun getContactByPhone(phone: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE cardavUid = :uid LIMIT 1")
    suspend fun getContactByUid(uid: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE cardavUid LIKE :prefix || '%' LIMIT 1")
    suspend fun getContactByUidPrefix(prefix: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE displayName = :name LIMIT 1")
    suspend fun getContactByExactName(name: String): ContactEntity?

    @Query("SELECT * FROM contacts")
    suspend fun getAllContactsSuspend(): List<ContactEntity>

    @Upsert
    suspend fun upsertContact(contact: ContactEntity)

    @Upsert
    suspend fun upsertAll(contacts: List<ContactEntity>)

    @Query("UPDATE contacts SET lastCallTimestamp = :ts WHERE id = :id")
    suspend fun updateLastCall(id: Long, ts: Long)

    @Query("UPDATE contacts SET followUpDue = :ts WHERE id = :id")
    suspend fun setFollowUp(id: Long, ts: Long)

    @Query("UPDATE contacts SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("DELETE FROM contacts WHERE id NOT IN (:ids)")
    suspend fun deleteNotIn(ids: List<Long>)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteContact(id: Long)
}

// ── Interaction log ───────────────────────────────────────────────────────────

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey val id: Long = System.currentTimeMillis(),
    val contactId: Long?,            // null = unknown-number interaction (no contact)
    val contactUid: String,
    val contactName: String,         // for unknown numbers: user-typed label, e.g. "Insurance company"
    val phoneNumber: String,
    val normalizedPhone: String = "", // digits-only, used to match unknown-number logs against future calls/contacts
    val callTimestamp: Long,
    val durationSeconds: Int,
    val isOutgoing: Boolean,
    val interactionType: String = "Call",
    val reason: String,
    val outcome: String,
    val notes: String,
    val tags: String = "",           // JSON array of user-defined tags, e.g. ["Recruiter","Catch-up"]
    val participants: String = "[]", // JSON array of extra people in this interaction, e.g. group calls: [{"uid":"...","name":"Jane Doe"}]
    val followUpDays: Int = 0,
    /** Label of the SIM/line this interaction came in on, e.g. "Personal" or "Business".
     *  Blank when the user hasn't labelled this SIM, when the interaction is non-Call, or when
     *  it was logged manually. Round-trips through the Nextcloud markdown via "**SIM:**". */
    val simLabel: String = "",
    /** JSON array of pointers to cards/tasks/notes (already linked to this interaction's
     *  contact) that this specific interaction is about, e.g. [{"source":"KOTO","itemId":"..."}].
     *  See [com.brbrs.vinci.util.InteractionLinkedItemRef]. */
    val linkedExternalItems: String = "[]",
    val isSynced: Boolean = false,
)

@Dao
interface CallLogDao {

    @Query("SELECT * FROM call_logs WHERE contactId = :contactId ORDER BY callTimestamp DESC")
    fun getLogsForContact(contactId: Long): Flow<List<CallLogEntity>>

    /** Interactions where this contact was added as an extra participant (e.g. group calls), not the primary contact. */
    @Query("SELECT * FROM call_logs WHERE participants LIKE '%\"uid\":\"' || :contactUid || '\"%' ORDER BY callTimestamp DESC")
    fun getLogsAsParticipant(contactUid: String): Flow<List<CallLogEntity>>

    @Query("SELECT MAX(callTimestamp) FROM call_logs WHERE contactId = :contactId")
    suspend fun getLatestTimestampForContact(contactId: Long): Long?

    @Query("SELECT * FROM call_logs WHERE contactId IS NULL AND normalizedPhone = :normalizedPhone ORDER BY callTimestamp DESC")
    fun getLogsForUnknownNumber(normalizedPhone: String): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE contactId IS NULL AND normalizedPhone = :normalizedPhone ORDER BY callTimestamp DESC LIMIT 1")
    suspend fun getLatestLogForNumber(normalizedPhone: String): CallLogEntity?

    /** Last N logs for a number across both known-contact and unknown-number interactions,
     *  used by the on-RINGING caller preview to show the last 2-3 instead of just one. */
    @Query("SELECT * FROM call_logs WHERE normalizedPhone = :normalizedPhone ORDER BY callTimestamp DESC LIMIT :limit")
    suspend fun getRecentLogsForNumber(normalizedPhone: String, limit: Int): List<CallLogEntity>

    /**
     * Caller preview — full picture of the incoming contact:
     *  1. All interactions where this person is the primary contact.
     *  2. All interactions where they appear as a participant (group calls/meetings logged
     *     from someone else's profile — these have a different contactId and may have no
     *     matching normalizedPhone, so getRecentLogsForNumber misses them entirely).
     * Returns the N most recent across both sets.
     */
    @Query("""
        SELECT * FROM call_logs
        WHERE contactId = :contactId
           OR participants LIKE '%"uid":"' || :contactUid || '"%'
        ORDER BY callTimestamp DESC
        LIMIT :limit
    """)
    suspend fun getRecentLogsForContact(contactId: Long, contactUid: String, limit: Int): List<CallLogEntity>

    @Query("UPDATE call_logs SET contactId = :contactId WHERE contactId IS NULL AND normalizedPhone = :normalizedPhone")
    suspend fun relinkLogsToContact(normalizedPhone: String, contactId: Long)

    /** Duplicate/merge assistant: re-points every interaction from one contact row onto another. */
    @Query("UPDATE call_logs SET contactId = :keepId WHERE contactId = :mergeId")
    suspend fun reassignLogs(mergeId: Long, keepId: Long)

    @Query("SELECT DISTINCT tags FROM call_logs WHERE tags != ''")
    suspend fun getAllTagSets(): List<String>

    @Query("SELECT DISTINCT contactId FROM call_logs WHERE contactId IS NOT NULL AND tags LIKE '%\"' || :tag || '\"%'")
    suspend fun getContactIdsWithTag(tag: String): List<Long>

    @Query("SELECT * FROM call_logs ORDER BY callTimestamp DESC LIMIT 20")
    fun getRecentLogs(): Flow<List<CallLogEntity>>

    /** Global search across every contact's interactions -- notes, reason, outcome, or contact name. */
    @Query("""
        SELECT * FROM call_logs
        WHERE notes LIKE '%' || :query || '%'
           OR reason LIKE '%' || :query || '%'
           OR outcome LIKE '%' || :query || '%'
           OR contactName LIKE '%' || :query || '%'
        ORDER BY callTimestamp DESC
    """)
    fun searchAllInteractions(query: String): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs ORDER BY callTimestamp DESC")
    fun getAllLogs(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs ORDER BY contactId, callTimestamp")
    suspend fun getAllLogsOnce(): List<CallLogEntity>

    @Query("SELECT * FROM call_logs WHERE id = :id")
    suspend fun getLogById(id: Long): CallLogEntity?

    @Query("SELECT * FROM call_logs WHERE isSynced = 0")
    suspend fun getUnsynced(): List<CallLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: CallLogEntity)

    @Update
    suspend fun updateLog(log: CallLogEntity)

    @Query("DELETE FROM call_logs WHERE id = :id")
    suspend fun deleteLog(id: Long)

    @Query("UPDATE call_logs SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)
}

// ── Attachment ───────────────────────────────────────────────────────────────

@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long,                  // FK -> call_logs.id
    val fileName: String,
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val nextcloudPath: String = "",   // WebDAV path relative to davBase, e.g. Contacts/.../calls/.../file.pdf
    val localPath: String = "",       // absolute path on device if cached, else ""
    val cachedLocally: Boolean = false,
    val isSynced: Boolean = false,    // true once uploaded to Nextcloud
)

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE logId = :logId ORDER BY id ASC")
    fun getAttachmentsForLog(logId: Long): Flow<List<AttachmentEntity>>

    /** Dedupe lookup used by restore to avoid re-inserting rows for web-uploaded attachments. */
    @Query("SELECT * FROM attachments WHERE logId = :logId AND fileName = :fileName LIMIT 1")
    suspend fun getByLogIdAndName(logId: Long, fileName: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE cachedLocally = 1")
    suspend fun getCachedAttachments(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getById(id: Long): AttachmentEntity?

    @Insert
    suspend fun insert(attachment: AttachmentEntity): Long

    @Update
    suspend fun update(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE attachments SET cachedLocally = 0, localPath = '' WHERE id = :id")
    suspend fun clearLocalCache(id: Long)
}

// ── ContactContract sync state ──────────────────────────────────────────────────
// Feature #ContactContract: tracks, per CardDAV UID, which ContactsContract raw contact it's
// mirrored into and what ETag it was last pulled/pushed at -- lets a pull skip refetching every
// vCard (only ones whose server ETag changed) instead of re-downloading the whole address book
// every sync, same purpose as Zaman's per-calendar feedEtag.

@Entity(tableName = "contact_sync_state")
data class ContactSyncStateEntity(
    @PrimaryKey val uid: String,
    val rawContactId: Long,
    val etag: String,
)

@Dao
interface ContactSyncStateDao {
    @Query("SELECT * FROM contact_sync_state")
    suspend fun getAll(): List<ContactSyncStateEntity>

    @Query("SELECT * FROM contact_sync_state WHERE rawContactId = :rawContactId LIMIT 1")
    suspend fun byRawContactId(rawContactId: Long): ContactSyncStateEntity?

    @Upsert
    suspend fun upsert(entity: ContactSyncStateEntity)

    @Query("DELETE FROM contact_sync_state WHERE uid = :uid")
    suspend fun deleteByUid(uid: String)
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [ContactEntity::class, CallLogEntity::class, AttachmentEntity::class, CompanyEntity::class, ContactSyncStateEntity::class],
    version = 13,
    exportSchema = false,
)
abstract class VinciDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun callLogDao(): CallLogDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun companyDao(): CompanyDao
    abstract fun contactSyncStateDao(): ContactSyncStateDao
}
