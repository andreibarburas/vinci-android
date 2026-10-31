package com.brbrs.vinci.network

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full (not just the social-links sliver [CardDavRepository] already handled) vCard 3.0
 * read/write for feature #ContactContract -- the Vinci half of "replace DAVx5 for contacts" the
 * same way Zaman's CalendarContract work replaced it for calendars. DAVx5 used to be the only
 * thing that actually turned a vCard into ContactsContract rows and back; this is Vinci doing
 * that itself.
 *
 * Deliberately matches the fields [ContactsRepository.syncFromDevice] already reads out of
 * ContactsContract and that [EditContactViewModel] already lets the user edit -- name, phones,
 * emails, organization/title, birthday, one flat address string, notes, photo, and social links
 * (via [CardDavRepository.parseSocialLinks]/[CardDavRepository.buildSocialLines], reused rather
 * than duplicated).
 */
@Singleton
class VCardCodec @Inject constructor(
    private val cardDavRepository: CardDavRepository,
) {
    data class PhoneEntry(val type: String, val number: String)
    data class EmailEntry(val type: String, val address: String)

    data class ParsedVCard(
        val uid: String,
        val displayName: String,
        val phones: List<PhoneEntry>,
        val emails: List<EmailEntry>,
        val organization: String,
        val jobTitle: String,
        /** yyyy-MM-dd, or blank. */
        val birthday: String,
        /** One flat formatted address, matching ContactEntity.address. */
        val address: String,
        val notes: String,
        val socialLinksJson: String,
        /** Raw base64 (no data: prefix, no folding whitespace), or null if the vCard has no
         *  PHOTO property. */
        val photoBase64: String?,
    )

    /** Parses a raw vCard into [ParsedVCard], or null if it has no UID/FN to anchor on. */
    fun parse(vcard: String): ParsedVCard? {
        // Unfold folded lines (CRLF/LF followed by a space or tab continues the previous line).
        val unfolded = vcard.replace(Regex("\r\n[ \t]"), "").replace(Regex("\n[ \t]"), "")
        val lines = unfolded.lines()

        var uid = ""
        var fn = ""
        var givenName = ""
        var familyName = ""
        var organization = ""
        var jobTitle = ""
        var birthday = ""
        var notes = ""
        var photoBase64: String? = null
        val addressParts = mutableListOf<String>()
        val phones = mutableListOf<PhoneEntry>()
        val emails = mutableListOf<EmailEntry>()

        lines.forEach { raw ->
            val line = raw.trimEnd('\r')
            val upper = line.uppercase()
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) return@forEach
            val name = line.substring(0, colonIdx)
            val nameUpper = name.uppercase()

            // PHOTO's value is raw base64 -- never run it through unescapeVCardText (base64's
            // alphabet has no backslashes, but there's no reason to risk it), and only PHOTO
            // needs the un-unfolded raw substring preserved verbatim.
            if (nameUpper == "PHOTO" || nameUpper.startsWith("PHOTO;")) {
                val raw64 = line.substring(colonIdx + 1).trim()
                if (raw64.isNotBlank()) photoBase64 = raw64
                return@forEach
            }

            val value = unescapeVCardText(line.substring(colonIdx + 1))

            when {
                nameUpper == "UID" || nameUpper.startsWith("UID;") -> uid = value.trim()
                nameUpper == "FN" || nameUpper.startsWith("FN;") -> fn = value.trim()
                nameUpper == "N" || nameUpper.startsWith("N;") -> {
                    // N:Family;Given;Middle;Prefix;Suffix
                    val parts = value.split(";")
                    familyName = parts.getOrElse(0) { "" }.trim()
                    givenName = parts.getOrElse(1) { "" }.trim()
                }
                nameUpper == "ORG" || nameUpper.startsWith("ORG;") -> organization = value.split(";").firstOrNull()?.trim() ?: ""
                nameUpper == "TITLE" || nameUpper.startsWith("TITLE;") -> jobTitle = value.trim()
                nameUpper == "BDAY" || nameUpper.startsWith("BDAY;") -> birthday = normalizeBirthday(value.trim())
                nameUpper == "NOTE" || nameUpper.startsWith("NOTE;") -> notes = value.trim()
                nameUpper.startsWith("ADR") -> {
                    // ADR;TYPE=...:PO Box;Extended;Street;City;Region;PostalCode;Country
                    val formatted = value.split(";").map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
                    if (formatted.isNotBlank()) addressParts += formatted
                }
                nameUpper.startsWith("TEL") -> {
                    val number = value.trim()
                    if (number.isNotBlank()) phones += PhoneEntry(phoneTypeFromParams(name), number)
                }
                nameUpper.startsWith("EMAIL") -> {
                    val address = value.trim()
                    if (address.isNotBlank()) emails += EmailEntry(emailTypeFromParams(name), address)
                }
                upper.startsWith("UID") -> Unit // already handled above via nameUpper
            }
        }

        val displayName = fn.ifBlank { "$givenName $familyName".trim() }
        if (uid.isBlank() || displayName.isBlank()) return null

        return ParsedVCard(
            uid = uid,
            displayName = displayName,
            phones = phones,
            emails = emails,
            organization = organization,
            jobTitle = jobTitle,
            birthday = birthday,
            address = addressParts.firstOrNull() ?: "",
            notes = notes,
            socialLinksJson = cardDavRepository.parseSocialLinks(vcard),
            photoBase64 = photoBase64,
        )
    }

    /** Builds a full vCard 3.0 text from Vinci's own field set. [uid] must already be assigned
     *  (a fresh one is generated by the caller for brand-new contacts, before this is called). */
    fun build(
        uid: String,
        displayName: String,
        phones: List<PhoneEntry>,
        emails: List<EmailEntry>,
        organization: String,
        jobTitle: String,
        birthday: String,
        address: String,
        notes: String,
        socialLinksJson: String,
        /** Raw base64 (no data: prefix), or null/blank to omit the PHOTO property entirely. */
        photoBase64: String? = null,
    ): String = buildString {
        appendLine("BEGIN:VCARD")
        appendLine("VERSION:3.0")
        appendLine("UID:${cardDavRepository.escapeVCardText(uid)}")
        appendLine("FN:${cardDavRepository.escapeVCardText(displayName)}")
        // N has no first/last split in Vinci's own model (ContactEntity.displayName is one
        // field) -- putting the whole name in the family-name slot keeps FN (what actually
        // displays everywhere) authoritative while still giving well-behaved CardDAV clients a
        // non-empty N to work with.
        appendLine("N:${cardDavRepository.escapeVCardText(displayName)};;;;")
        if (organization.isNotBlank()) appendLine("ORG:${cardDavRepository.escapeVCardText(organization)}")
        if (jobTitle.isNotBlank()) appendLine("TITLE:${cardDavRepository.escapeVCardText(jobTitle)}")
        if (birthday.isNotBlank()) appendLine("BDAY:$birthday")
        if (address.isNotBlank()) appendLine("ADR;TYPE=HOME:;;${cardDavRepository.escapeVCardText(address)};;;;")
        phones.forEach { phone ->
            if (phone.number.isNotBlank()) appendLine("TEL;TYPE=${vcardPhoneType(phone.type)}:${phone.number.trim()}")
        }
        emails.forEach { email ->
            if (email.address.isNotBlank()) appendLine("EMAIL;TYPE=${vcardEmailType(email.type)}:${email.address.trim()}")
        }
        if (notes.isNotBlank()) {
            // NOTE is a single text value -- escape embedded newlines per RFC 6350 rather than
            // letting them break the line (vCard lines are terminated by actual CRLFs).
            val escaped = cardDavRepository.escapeVCardText(notes).replace("\n", "\\n")
            appendLine("NOTE:$escaped")
        }
        append(cardDavRepository.buildSocialLines(socialLinksJson))
        if (!photoBase64.isNullOrBlank()) {
            append(foldLine("PHOTO;ENCODING=b;TYPE=JPEG:$photoBase64"))
        }
        appendLine("END:VCARD")
    }

    /** RFC 6350 line folding: lines over 75 octets are broken with a CRLF followed by a single
     *  leading space, which a reader strips back out when unfolding. Needed for PHOTO -- a
     *  base64-encoded contact photo is routinely several KB, far past the 75-octet limit, and
     *  some CardDAV servers reject or mangle an unfolded line that long. */
    private fun foldLine(line: String): String {
        val maxFirst = 75
        val maxCont = 74 // 75 minus the leading continuation space
        val sb = StringBuilder()
        var start = 0
        var limit = maxFirst
        while (start < line.length) {
            val end = minOf(start + limit, line.length)
            if (start > 0) sb.append(' ')
            sb.append(line, start, end)
            sb.append("\r\n")
            start = end
            limit = maxCont
        }
        return sb.toString()
    }

    private fun unescapeVCardText(value: String): String =
        value.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /** BDAY can arrive as yyyyMMdd or yyyy-MM-dd (some clients write ISO-8601 directly, others
     *  the compact vCard 3.0 form) -- normalized to yyyy-MM-dd, matching what Vinci's own
     *  EditContactScreen date field and ContactsContract.Event.START_DATE both expect. */
    private fun normalizeBirthday(raw: String): String {
        val digitsOnly = raw.replace("-", "")
        return if (digitsOnly.length == 8 && digitsOnly.all { it.isDigit() }) {
            "${digitsOnly.substring(0, 4)}-${digitsOnly.substring(4, 6)}-${digitsOnly.substring(6, 8)}"
        } else raw
    }

    private fun phoneTypeFromParams(fieldName: String): String = when {
        fieldName.contains("CELL", ignoreCase = true) -> "Mobile"
        fieldName.contains("HOME", ignoreCase = true) -> "Home"
        fieldName.contains("WORK", ignoreCase = true) -> "Work"
        else -> "Other"
    }

    private fun emailTypeFromParams(fieldName: String): String = when {
        fieldName.contains("HOME", ignoreCase = true) -> "Home"
        fieldName.contains("WORK", ignoreCase = true) -> "Work"
        else -> "Other"
    }

    private fun vcardPhoneType(label: String): String = when (label) {
        "Mobile" -> "CELL"
        "Home" -> "HOME"
        "Work" -> "WORK"
        else -> "OTHER"
    }

    private fun vcardEmailType(label: String): String = when (label) {
        "Home" -> "HOME"
        "Work" -> "WORK"
        else -> "OTHER"
    }
}
