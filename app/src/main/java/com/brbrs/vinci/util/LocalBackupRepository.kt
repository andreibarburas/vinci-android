package com.brbrs.vinci.util

import android.content.Context
import com.brbrs.vinci.data.CallLogDao
import com.brbrs.vinci.data.CallLogEntity
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.data.ContactEntity
import com.brbrs.vinci.ui.theme.DisplayPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vinci-native ZIP export / import for phone-migration scenarios (local mode).
 *
 * ZIP layout:
 *   vinci_backup.json  — interactions (call_logs) as a JSON array
 *   settings.json      — display preferences key/value map
 *
 * Contacts are NOT exported because they come from the device address book
 * (Google Contacts / DAVx5), which the new phone will already have. We keep
 * the contact name + uid inside each interaction row so orphaned logs (contact
 * no longer present on the new device) can be identified at import time and
 * flagged rather than silently discarded.
 */
@Singleton
class LocalBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callLogDao: CallLogDao,
    private val contactDao: ContactDao,
    private val displayPrefs: DisplayPreferencesRepository,
) {

    data class ImportResult(
        val imported: Int,
        val orphaned: Int,   // logs whose contactUid no longer maps to a contact on this device
        val skipped: Int,    // rows that already existed (same id)
    )

    // ─── Export ─────────────────────────────────────────────────────────────

    suspend fun export(): Result<File> = runCatching {
        withContext(Dispatchers.IO) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(context.cacheDir, "vinci_backup_$stamp.zip")

            ZipOutputStream(BufferedOutputStream(outFile.outputStream())).use { zos ->

                // interactions
                zos.putNextEntry(ZipEntry("vinci_backup.json"))
                zos.write(buildInteractionsJson().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // settings
                zos.putNextEntry(ZipEntry("settings.json"))
                zos.write(buildSettingsJson().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            outFile
        }
    }

    private suspend fun buildInteractionsJson(): String {
        val logs = callLogDao.getAllLogsOnce()
        val arr = JSONArray()
        logs.forEach { log ->
            val obj = JSONObject()
            obj.put("id", log.id)
            obj.put("contactId", log.contactId ?: JSONObject.NULL)
            obj.put("contactUid", log.contactUid)
            obj.put("contactName", log.contactName)
            obj.put("phoneNumber", log.phoneNumber)
            obj.put("normalizedPhone", log.normalizedPhone)
            obj.put("callTimestamp", log.callTimestamp)
            obj.put("durationSeconds", log.durationSeconds)
            obj.put("isOutgoing", log.isOutgoing)
            obj.put("interactionType", log.interactionType)
            obj.put("reason", log.reason)
            obj.put("outcome", log.outcome)
            obj.put("notes", log.notes)
            obj.put("tags", log.tags)
            obj.put("participants", log.participants)
            obj.put("followUpDays", log.followUpDays)
            obj.put("simLabel", log.simLabel)
            // isSynced intentionally NOT exported — on a new device everything starts unsynced
            arr.put(obj)
        }
        return arr.toString(2)
    }

    private suspend fun buildSettingsJson(): String {
        val prefs = displayPrefs.preferences.first()
        val obj = JSONObject()
        obj.put("is_grid_view", prefs.isGridView)
        obj.put("show_recent_calls", prefs.showRecentCalls)
        obj.put("show_recent_interactions", prefs.showRecentInteractions)
        obj.put("default_country_code", prefs.defaultCountryCode)
        obj.put("attachments_keep_local", prefs.attachmentsKeepLocal)
        obj.put("text_size", prefs.textSize)
        obj.put("use_custom_font", prefs.useCustomFont)
        obj.put("follow_up_reminder_enabled", prefs.followUpReminderEnabled)
        obj.put("follow_up_reminder_hour", prefs.followUpReminderHour)
        obj.put("follow_up_reminder_minute", prefs.followUpReminderMinute)
        obj.put("calendar_nudges_enabled", prefs.calendarNudgesEnabled)
        obj.put("caller_preview_mode", prefs.callerPreviewMode)
        // SIM labels intentionally omitted — SIM subscription IDs differ per device
        return obj.toString(2)
    }

    // ─── Import ─────────────────────────────────────────────────────────────

    suspend fun import(zipFile: File): Result<ImportResult> = runCatching {
        withContext(Dispatchers.IO) {
            var interactionsJson: String? = null
            var settingsJson: String? = null

            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "vinci_backup.json" -> interactionsJson = zis.readBytes().toString(Charsets.UTF_8)
                        "settings.json" -> settingsJson = zis.readBytes().toString(Charsets.UTF_8)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Restore settings first (non-fatal if absent)
            settingsJson?.let { restoreSettings(it) }

            // Restore interactions
            val json = interactionsJson
                ?: throw IllegalArgumentException("vinci_backup.json not found in ZIP")

            importInteractions(JSONArray(json))
        }
    }

    private suspend fun restoreSettings(json: String) {
        runCatching {
            val obj = JSONObject(json)
            val map = buildMap<String, Any> {
                if (obj.has("is_grid_view")) put("is_grid_view", obj.getBoolean("is_grid_view"))
                if (obj.has("show_recent_calls")) put("show_recent_calls", obj.getBoolean("show_recent_calls"))
                if (obj.has("show_recent_interactions")) put("show_recent_interactions", obj.getBoolean("show_recent_interactions"))
                if (obj.has("default_country_code")) put("default_country_code", obj.getString("default_country_code"))
                if (obj.has("attachments_keep_local")) put("attachments_keep_local", obj.getBoolean("attachments_keep_local"))
                if (obj.has("text_size")) put("text_size", obj.getString("text_size"))
                if (obj.has("use_custom_font")) put("use_custom_font", obj.getBoolean("use_custom_font"))
                if (obj.has("follow_up_reminder_enabled")) put("follow_up_reminder_enabled", obj.getBoolean("follow_up_reminder_enabled"))
                if (obj.has("follow_up_reminder_hour")) put("follow_up_reminder_hour", obj.getInt("follow_up_reminder_hour"))
                if (obj.has("follow_up_reminder_minute")) put("follow_up_reminder_minute", obj.getInt("follow_up_reminder_minute"))
                if (obj.has("calendar_nudges_enabled")) put("calendar_nudges_enabled", obj.getBoolean("calendar_nudges_enabled"))
                if (obj.has("caller_preview_mode")) put("caller_preview_mode", obj.getString("caller_preview_mode"))
            }
            displayPrefs.fromMap(map)
        }
    }

    private suspend fun importInteractions(arr: JSONArray): ImportResult {
        var imported = 0
        var orphaned = 0
        var skipped = 0

        // Build a uid -> contactId lookup for contacts currently on this device
        val knownUids: Set<String> = contactDao.getAllContactsSuspend()
            .map { it.cardavUid }
            .filter { it.isNotBlank() }
            .toSet()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val logId = obj.getLong("id")

            // Skip if already in DB (e.g. partial re-import)
            if (callLogDao.getLogById(logId) != null) {
                skipped++
                continue
            }

            val contactUid = obj.optString("contactUid", "")
            val isOrphaned = contactUid.isNotBlank() && contactUid !in knownUids

            val log = CallLogEntity(
                id = logId,
                contactId = if (obj.isNull("contactId")) null else obj.getLong("contactId"),
                contactUid = contactUid,
                contactName = obj.optString("contactName", ""),
                phoneNumber = obj.optString("phoneNumber", ""),
                normalizedPhone = obj.optString("normalizedPhone", ""),
                callTimestamp = obj.optLong("callTimestamp", 0L),
                durationSeconds = obj.optInt("durationSeconds", 0),
                isOutgoing = obj.optBoolean("isOutgoing", false),
                interactionType = obj.optString("interactionType", "Call"),
                reason = obj.optString("reason", ""),
                outcome = obj.optString("outcome", ""),
                notes = obj.optString("notes", ""),
                tags = obj.optString("tags", "[]"),
                participants = obj.optString("participants", "[]"),
                followUpDays = obj.optInt("followUpDays", 0),
                simLabel = obj.optString("simLabel", ""),
                // Flag orphaned logs by appending a tag so the user can find them in search
                isSynced = false,
            ).let { base ->
                if (isOrphaned) {
                    // Append an #orphaned tag so the user can filter for these
                    val existing = runCatching {
                        val tagArr = JSONArray(base.tags.ifBlank { "[]" })
                        (0 until tagArr.length()).map { tagArr.getString(it) }
                    }.getOrDefault(emptyList())
                    val withFlag = if ("orphaned" !in existing) {
                        JSONArray(existing + "orphaned").toString()
                    } else base.tags
                    base.copy(tags = withFlag)
                } else base
            }

            callLogDao.insertLog(log)
            if (isOrphaned) orphaned++ else imported++
        }

        return ImportResult(imported, orphaned, skipped)
    }
}
