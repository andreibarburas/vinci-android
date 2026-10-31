package com.brbrs.vinci.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.displayDataStore by preferencesDataStore(name = "vinci_display")

data class DisplayPreferences(
    val isGridView: Boolean = false,
    val showRecentCalls: Boolean = true,
    val showRecentInteractions: Boolean = true,
    /** Country calling code (digits only, no '+') used to normalize phone
     *  numbers that lack one, e.g. "31" for the Netherlands, when opening
     *  WhatsApp/Signal chats. Empty means no normalization is applied. */
    val defaultCountryCode: String = "",
    /** If true, attachments are kept on-device as well as on Nextcloud by
     *  default. If false (default), attachments are Nextcloud-only and
     *  downloaded on demand. */
    val attachmentsKeepLocal: Boolean = false,
    /** Text size scale: "small", "default", "large", or "extra_large". */
    val textSize: String = "default",
    /** If true, use DM Serif Display + Inter Tight font pairing.
     *  Defaults to true as of 2.0.0-beta01 (Expressive pass) — the pairing
     *  was already built but off by default; it's now the intended look. */
    val useCustomFont: Boolean = true,
    /** Per-SIM labels keyed by subscription id, e.g. {"3": "Personal", "7": "Business"}. The
     *  label (not the id) is what gets stored on each Call interaction, so changing a label
     *  later only affects future calls -- past ones keep the name they were tagged with. */
    val simLabels: Map<String, String> = emptyMap(),
    /** Daily follow-up reminder: enabled, hour-of-day and minute (device-local time). */
    val followUpReminderEnabled: Boolean = true,
    val followUpReminderHour: Int = 9,
    val followUpReminderMinute: Int = 0,
    /** Calendar-aware nudges after a meeting ends. Requires READ_CALENDAR at runtime. */
    val calendarNudgesEnabled: Boolean = false,
    /** Caller preview mode: how the on-RINGING "who is this?" preview appears. */
    val callerPreviewMode: String = CALLER_PREVIEW_CONTEXT,
)

const val CALLER_PREVIEW_OFF        = "off"
const val CALLER_PREVIEW_CONTEXT    = "context"
const val CALLER_PREVIEW_PROMINENCE = "prominence"

/** Density multipliers applied to the base typography scale for each text size option. */
fun textSizeMultiplier(textSize: String): Float = when (textSize) {
    "small"      -> 0.9f
    "large"      -> 1.15f
    "extra_large" -> 1.3f
    else          -> 1.0f // "default"
}

@Singleton
class DisplayPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val IS_GRID_VIEW             = booleanPreferencesKey("is_grid_view")
    private val SHOW_RECENT_CALLS        = booleanPreferencesKey("show_recent_calls")
    private val SHOW_RECENT_INTERACTIONS = booleanPreferencesKey("show_recent_interactions")
    private val DEFAULT_COUNTRY_CODE     = stringPreferencesKey("default_country_code")
    private val ATTACHMENTS_KEEP_LOCAL   = booleanPreferencesKey("attachments_keep_local")
    private val TEXT_SIZE                = stringPreferencesKey("text_size")
    private val USE_CUSTOM_FONT          = booleanPreferencesKey("use_custom_font")
    private val SIM_LABELS               = stringPreferencesKey("sim_labels")
    private val FOLLOW_UP_ENABLED        = booleanPreferencesKey("follow_up_enabled")
    private val FOLLOW_UP_HOUR           = intPreferencesKey("follow_up_hour")
    private val FOLLOW_UP_MINUTE         = intPreferencesKey("follow_up_minute")
    private val CALENDAR_NUDGES          = booleanPreferencesKey("calendar_nudges_enabled")
    private val CALLER_PREVIEW_MODE      = stringPreferencesKey("caller_preview_mode")

    val preferences: Flow<DisplayPreferences> = context.displayDataStore.data.map { prefs ->
        DisplayPreferences(
            isGridView             = prefs[IS_GRID_VIEW]             ?: false,
            showRecentCalls        = prefs[SHOW_RECENT_CALLS]        ?: true,
            showRecentInteractions = prefs[SHOW_RECENT_INTERACTIONS] ?: true,
            defaultCountryCode     = prefs[DEFAULT_COUNTRY_CODE]     ?: "",
            attachmentsKeepLocal   = prefs[ATTACHMENTS_KEEP_LOCAL]   ?: false,
            textSize               = prefs[TEXT_SIZE]                ?: "default",
            useCustomFont          = prefs[USE_CUSTOM_FONT]          ?: true,
            simLabels              = parseSimLabels(prefs[SIM_LABELS]),
            followUpReminderEnabled= prefs[FOLLOW_UP_ENABLED]        ?: true,
            followUpReminderHour   = prefs[FOLLOW_UP_HOUR]           ?: 9,
            followUpReminderMinute = prefs[FOLLOW_UP_MINUTE]         ?: 0,
            calendarNudgesEnabled  = prefs[CALENDAR_NUDGES]          ?: false,
            callerPreviewMode      = prefs[CALLER_PREVIEW_MODE]      ?: CALLER_PREVIEW_CONTEXT,
        )
    }

    /** Stored as a compact JSON map, e.g. {"3":"Personal","7":"Business"}. */
    private fun parseSimLabels(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw)
            buildMap {
                obj.keys().forEach { k -> put(k, obj.optString(k, "")) }
            }
        } catch (e: Exception) { emptyMap() }
    }

    suspend fun setGridView(enabled: Boolean) {
        context.displayDataStore.edit { it[IS_GRID_VIEW] = enabled }
    }

    suspend fun setShowRecentCalls(enabled: Boolean) {
        context.displayDataStore.edit { it[SHOW_RECENT_CALLS] = enabled }
    }

    suspend fun setShowRecentInteractions(enabled: Boolean) {
        context.displayDataStore.edit { it[SHOW_RECENT_INTERACTIONS] = enabled }
    }

    /** Sets the default country calling code (digits only, e.g. "31"). */
    suspend fun setDefaultCountryCode(code: String) {
        val digitsOnly = code.replace(Regex("[^0-9]"), "")
        context.displayDataStore.edit { it[DEFAULT_COUNTRY_CODE] = digitsOnly }
    }

    suspend fun setAttachmentsKeepLocal(enabled: Boolean) {
        context.displayDataStore.edit { it[ATTACHMENTS_KEEP_LOCAL] = enabled }
    }

    suspend fun setTextSize(size: String) {
        context.displayDataStore.edit { it[TEXT_SIZE] = size }
    }

    suspend fun setUseCustomFont(enabled: Boolean) {
        context.displayDataStore.edit { it[USE_CUSTOM_FONT] = enabled }
    }

    /** Sets the user-facing label for a SIM subscription (blank removes it). */
    suspend fun setSimLabel(subscriptionId: String, label: String) {
        context.displayDataStore.edit { prefs ->
            val current = parseSimLabels(prefs[SIM_LABELS]).toMutableMap()
            if (label.isBlank()) current.remove(subscriptionId) else current[subscriptionId] = label.trim()
            prefs[SIM_LABELS] = org.json.JSONObject(current.toMap()).toString()
        }
    }

    /** Looks up the label currently assigned to a SIM subscription, or empty if unlabeled. */
    suspend fun simLabelFor(subscriptionId: String?): String {
        if (subscriptionId.isNullOrBlank()) return ""
        return preferences.first().simLabels[subscriptionId].orEmpty()
    }

    suspend fun setFollowUpReminderEnabled(enabled: Boolean) {
        context.displayDataStore.edit { it[FOLLOW_UP_ENABLED] = enabled }
    }

    suspend fun setFollowUpReminderTime(hour: Int, minute: Int) {
        context.displayDataStore.edit {
            it[FOLLOW_UP_HOUR]   = hour.coerceIn(0, 23)
            it[FOLLOW_UP_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setCalendarNudgesEnabled(enabled: Boolean) {
        context.displayDataStore.edit { it[CALENDAR_NUDGES] = enabled }
    }

    suspend fun setCallerPreviewMode(mode: String) {
        val safe = when (mode) {
            CALLER_PREVIEW_OFF, CALLER_PREVIEW_CONTEXT, CALLER_PREVIEW_PROMINENCE -> mode
            else -> CALLER_PREVIEW_CONTEXT
        }
        context.displayDataStore.edit { it[CALLER_PREVIEW_MODE] = safe }
    }

    /** Returns current preferences as a map suitable for Nextcloud upload. */
    suspend fun toMap(): Map<String, Any> {
        val prefs = preferences.first()
        return mapOf(
            "is_grid_view"              to prefs.isGridView,
            "show_recent_calls"         to prefs.showRecentCalls,
            "show_recent_interactions"  to prefs.showRecentInteractions,
            "default_country_code"      to prefs.defaultCountryCode,
            "attachments_keep_local"    to prefs.attachmentsKeepLocal,
            "text_size"                 to prefs.textSize,
        )
    }

    /** Restores preferences from a map (Nextcloud or local backup). */
    suspend fun fromMap(map: Map<String, Any>) {
        context.displayDataStore.edit { prefs ->
            (map["is_grid_view"] as? Boolean)?.let              { prefs[IS_GRID_VIEW] = it }
            (map["show_recent_calls"] as? Boolean)?.let         { prefs[SHOW_RECENT_CALLS] = it }
            (map["show_recent_interactions"] as? Boolean)?.let  { prefs[SHOW_RECENT_INTERACTIONS] = it }
            (map["default_country_code"] as? String)?.let       { prefs[DEFAULT_COUNTRY_CODE] = it }
            (map["attachments_keep_local"] as? Boolean)?.let    { prefs[ATTACHMENTS_KEEP_LOCAL] = it }
            (map["text_size"] as? String)?.let                  { prefs[TEXT_SIZE] = it }
            (map["use_custom_font"] as? Boolean)?.let           { prefs[USE_CUSTOM_FONT] = it }
            (map["follow_up_reminder_enabled"] as? Boolean)?.let { prefs[FOLLOW_UP_ENABLED] = it }
            (map["follow_up_reminder_hour"] as? Int)?.let       { prefs[FOLLOW_UP_HOUR] = it.coerceIn(0, 23) }
            (map["follow_up_reminder_minute"] as? Int)?.let     { prefs[FOLLOW_UP_MINUTE] = it.coerceIn(0, 59) }
            (map["calendar_nudges_enabled"] as? Boolean)?.let   { prefs[CALENDAR_NUDGES] = it }
            (map["caller_preview_mode"] as? String)?.let        { mode ->
                val safe = when (mode) {
                    CALLER_PREVIEW_OFF, CALLER_PREVIEW_CONTEXT, CALLER_PREVIEW_PROMINENCE -> mode
                    else -> CALLER_PREVIEW_CONTEXT
                }
                prefs[CALLER_PREVIEW_MODE] = safe
            }
        }
    }
}
