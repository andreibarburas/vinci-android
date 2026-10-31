package com.brbrs.vinci.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Company ───────────────────────────────────────────────────────────────────
// A company page is derived from the organization text on contacts (nothing changes in the
// Nextcloud address book) and only becomes a synced file once it actually has something
// of its own: notes, a website, or a feed URL.

@Entity(tableName = "companies")
data class CompanyEntity(
    @PrimaryKey val companyKey: String,   // companyKey(displayName) -- see util/CompanyNames.kt
    val displayName: String,
    val website: String = "",
    val feedUrl: String = "",
    val notes: String = "",
    val updatedAt: Long = 0,
    // A freshly created empty placeholder has nothing worth uploading, so it starts "synced";
    // every real edit flips this to false until the upload succeeds.
    val isSynced: Boolean = true,
    /** Optional CardDAV uid of the contact that *is* this company (e.g. a vCard called
     *  "Barburas" that represents the company itself). When set, the Company page uses that
     *  contact's photo, phone and email as its own, hides the contact from the "People" list
     *  on its own page, and the contact gets a company badge in the Contacts tab. */
    val primaryContactUid: String = "",
)

@Dao
interface CompanyDao {

    @Query("SELECT * FROM companies WHERE companyKey = :key")
    fun observe(key: String): Flow<CompanyEntity?>

    @Query("SELECT * FROM companies")
    fun observeAll(): Flow<List<CompanyEntity>>

    @Query("SELECT * FROM companies WHERE companyKey = :key")
    suspend fun get(key: String): CompanyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(company: CompanyEntity)

    /** Lookup used by Contact detail: tells us whether this contact is the primary record of
     *  any company (shown as a badge, and the "This contact is a company" toggle is on). */
    @Query("SELECT * FROM companies WHERE primaryContactUid = :uid LIMIT 1")
    suspend fun getByPrimaryContactUid(uid: String): CompanyEntity?

    @Query("SELECT * FROM companies WHERE isSynced = 0")
    suspend fun getUnsynced(): List<CompanyEntity>

    /** Only marks synced if the row hasn't been edited again since this version was uploaded. */
    @Query("UPDATE companies SET isSynced = 1 WHERE companyKey = :key AND updatedAt = :updatedAt")
    suspend fun markSynced(key: String, updatedAt: Long)
}
