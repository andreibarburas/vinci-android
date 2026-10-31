package com.brbrs.vinci.calendar

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import com.brbrs.vinci.data.ContactEntity
import org.json.JSONArray

/**
 * Reads device calendar via `CalendarContract` (events from Samsung Calendar, DAVx5-synced
 * Nextcloud, Google Calendar, Outlook Mobile, etc. all land here). Proton Calendar is a known
 * exception: its events don't sync to the system calendar provider and so can't be matched
 * from Android.
 *
 * One query gets events whose end time falls in a given window; a second gets attendees per
 * event; emails are matched against Vinci contacts (via vCard email + allEmails JSON). Called
 * from CalendarNudgeWorker.
 */
object CalendarMatcher {

    data class Attendee(val name: String, val email: String)

    data class CalendarEvent(
        val id: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val attendees: List<Attendee>,
    )

    data class MatchedEvent(
        val event: CalendarEvent,
        val matchedContacts: List<ContactEntity>,
    )

    /**
     * Returns non-all-day events that ENDED between [sinceMs] and [untilMs] and have at least
     * one attendee resolvable to a Vinci contact by email. Caller provides [contacts] so this
     * stays non-suspend; `READ_CALENDAR` is handled by the OS -- queries wrap in try/catch so
     * a missing permission just yields an empty list.
     */
    fun findEndedEventsWithContacts(
        context: Context,
        contacts: List<ContactEntity>,
        sinceMs: Long,
        untilMs: Long,
    ): List<MatchedEvent> {
        val events = queryEvents(context, sinceMs, untilMs).filter { !it.allDay && it.title.isNotBlank() }
        if (events.isEmpty()) return emptyList()

        // Build an email -> contact map once, lower-cased for case-insensitive matching.
        val emailIndex: Map<String, ContactEntity> = contacts
            .flatMap { c -> allEmailsFor(c).map { it.lowercase() to c } }
            .groupBy { it.first }
            .mapValues { it.value.first().second }

        return events.mapNotNull { ev ->
            val matched = ev.attendees
                .mapNotNull { emailIndex[it.email.lowercase()] }
                .distinctBy { it.id }
            if (matched.isEmpty()) null else MatchedEvent(ev, matched)
        }
    }

    private fun queryEvents(context: Context, sinceMs: Long, untilMs: Long): List<CalendarEvent> {
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        // Query Instances (recurring events expand to their occurrences) between sinceMs and
        // untilMs + a day of lookahead -- we actually filter by end time below.
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, sinceMs - 24 * 60 * 60 * 1000L)
        ContentUris.appendId(uriBuilder, untilMs + 24 * 60 * 60 * 1000L)
        val uri: Uri = uriBuilder.build()

        val events = mutableListOf<CalendarEvent>()
        try {
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.END} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val end = c.getLong(3)
                    if (end !in sinceMs..untilMs) continue
                    val id = c.getLong(0)
                    events.add(
                        CalendarEvent(
                            id        = id,
                            title     = c.getString(1).orEmpty(),
                            begin     = c.getLong(2),
                            end       = end,
                            allDay    = c.getInt(4) == 1,
                            attendees = queryAttendees(context, id),
                        )
                    )
                }
            }
        } catch (e: SecurityException) { /* READ_CALENDAR not granted yet */ }
        return events
    }

    private fun queryAttendees(context: Context, eventId: Long): List<Attendee> {
        val list = mutableListOf<Attendee>()
        try {
            context.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL),
                "${CalendarContract.Attendees.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name  = c.getString(0).orEmpty()
                    val email = c.getString(1).orEmpty()
                    if (email.isNotBlank()) list += Attendee(name = name, email = email)
                }
            }
        } catch (e: SecurityException) { /* ignored, same reason */ }
        return list
    }

    /**
     * Pulls every email we know about a contact: the primary email field plus the entries in
     * the allEmails JSON ([{"type":"work","address":"..."}]).
     */
    private fun allEmailsFor(c: ContactEntity): List<String> {
        val out = mutableListOf<String>()
        if (c.email.isNotBlank()) out += c.email
        if (c.allEmails.isNotBlank()) {
            runCatching {
                val arr = JSONArray(c.allEmails)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val addr = obj.optString("address", "")
                    if (addr.isNotBlank()) out += addr
                }
            }
        }
        return out.distinct()
    }

}
