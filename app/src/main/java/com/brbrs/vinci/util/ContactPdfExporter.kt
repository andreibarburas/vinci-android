package com.brbrs.vinci.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.brbrs.vinci.data.AttachmentEntity
import com.brbrs.vinci.data.CallLogEntity
import com.brbrs.vinci.data.ContactEntity
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a polished PDF summary of one contact's interaction history.
 *
 * Design language mirrors the Vinci dark-glass aesthetic in print form:
 *  - Deep navy/off-white palette with the #AB47BC purple accent
 *  - DM Serif Display feel via bold italic for display text, clean sans for body
 *  - Rounded section cards, avatar initials circle, color-coded interaction chips
 *  - Full-bleed header band, ruled timeline, per-page footer with Vinci branding
 *
 * Uses only Android's native PdfDocument — no new dependencies.
 */
object ContactPdfExporter {

    // ── Page geometry ──────────────────────────────────────────────────────────

    private const val PW  = 595   // A4 at 72 dpi
    private const val PH  = 842
    private const val M   = 40f   // margin
    private val CW        = PW - 2 * M  // content width

    // ── Colour palette ─────────────────────────────────────────────────────────

    private val NAVY         = Color.rgb(14,  20,  40)
    private val NAVY_LIGHT   = Color.rgb(22,  30,  58)
    private val ACCENT       = Color.rgb(171, 71,  188)   // #AB47BC
    private val ACCENT_LIGHT = Color.rgb(206, 147, 216)   // softer purple for chips
    private val WHITE        = Color.rgb(255, 255, 255)
    private val OFF_WHITE    = Color.rgb(240, 235, 248)
    private val BODY_TEXT    = Color.rgb(30,  25,  45)
    private val MUTED_TEXT   = Color.rgb(120, 110, 145)
    private val RULE_LINE    = Color.rgb(210, 200, 230)
    private val CARD_BG      = Color.rgb(250, 247, 255)
    private val CHIP_BG      = Color.argb(40,  171, 71, 188)  // translucent purple

    // ── Paint objects ──────────────────────────────────────────────────────────

    private fun makePaint(color: Int, size: Float, bold: Boolean = false, italic: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color     = color
            this.textSize  = size
            this.typeface  = when {
                bold && italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                bold           -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                italic         -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else           -> Typeface.DEFAULT
            }
        }

    private val pHeroName   = makePaint(WHITE,      22f, bold = true)
    private val pHeroRole   = makePaint(OFF_WHITE,  11f, italic = true)
    private val pHeroMeta   = makePaint(OFF_WHITE,  10f)
    private val pSectionHdr = makePaint(ACCENT,     9f,  bold = true)
    private val pLabel      = makePaint(MUTED_TEXT,  9f)
    private val pValue      = makePaint(BODY_TEXT,  10f)
    private val pBodyBold   = makePaint(BODY_TEXT,  10f, bold = true)
    private val pBody       = makePaint(BODY_TEXT,  10f)
    private val pNotes      = makePaint(BODY_TEXT,  10f, italic = true)
    private val pChip       = makePaint(ACCENT,      8f, bold = true)
    private val pMuted      = makePaint(MUTED_TEXT,  9f)
    private val pTimeline   = makePaint(ACCENT,     10f, bold = true)
    private val pFooter     = makePaint(MUTED_TEXT,  8f)
    private val pDivide     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RULE_LINE; strokeWidth = 0.5f }
    private val pFill       = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pAvatar     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val pAvatarText = makePaint(WHITE, 22f, bold = true).also { it.textAlign = Paint.Align.CENTER }

    private const val LINE  = 15f   // standard line height
    private const val LINE_S= 13f   // compact line height

    // ── Public API ─────────────────────────────────────────────────────────────

    data class Input(
        val contact: ContactEntity,
        /** Interactions sorted DESC by callTimestamp. */
        val interactions: List<CallLogEntity>,
        val attachmentsByLogId: Map<Long, List<AttachmentEntity>>,
        /** Local image files, keyed by AttachmentEntity.id. */
        val imagesByAttachmentId: Map<Long, File>,
    )

    fun writePdf(context: Context, input: Input): File {
        val dir      = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val safeName = input.contact.displayName
            .replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().ifBlank { "contact" }
        val file = File(dir, "$safeName-history.pdf")

        val doc   = PdfDocument()
        val state = PageState(doc)
        state.newPage()
        try {
            drawHeroBand(state, input.contact)
            drawContactDetails(state, input.contact)
            drawInteractions(state, input)
            state.doc.finishPage(state.page)
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return file
    }

    // ── Page state ─────────────────────────────────────────────────────────────

    private class PageState(val doc: PdfDocument) {
        var pageNum   = 0
        lateinit var page:   PdfDocument.Page
        lateinit var canvas: Canvas
        var y         = M

        /** Start a new page; finish the current one first. */
        fun newPage() {
            if (pageNum > 0) doc.finishPage(page)
            pageNum++
            val info = PdfDocument.PageInfo.Builder(PW, PH, pageNum).create()
            page   = doc.startPage(info)
            canvas = page.canvas
            y      = M
            drawPageFooter(this)
        }

        /** Ensure [needed] vertical space exists, starting a new page if not. */
        fun space(needed: Float) {
            if (y + needed > PH - M - 20f) newPage()
        }

        /** Advance the cursor by [delta] points. */
        fun advance(delta: Float) { y += delta }
    }

    // ── Footer (drawn on every new page) ──────────────────────────────────────

    private fun drawPageFooter(s: PageState) {
        val footY  = PH - 18f
        val right  = Paint(pFooter).also { it.textAlign = Paint.Align.RIGHT }
        val center = Paint(pFooter).also { it.textAlign = Paint.Align.CENTER }
        s.canvas.drawLine(M, footY - 6f, PW - M, footY - 6f, pDivide)
        s.canvas.drawText("Vinci · Interaction History", M, footY, pFooter)
        s.canvas.drawText(
            SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date()),
            PW / 2f, footY, center,
        )
        s.canvas.drawText("${s.pageNum}", PW - M, footY, right)
    }

    // ── Hero band ──────────────────────────────────────────────────────────────

    private fun drawHeroBand(s: PageState, c: ContactEntity) {
        val bandH = 110f

        // Navy gradient band (simulated with two filled rects, darker on left)
        pFill.color = NAVY
        s.canvas.drawRect(0f, 0f, PW.toFloat(), bandH, pFill)
        pFill.color = NAVY_LIGHT
        s.canvas.drawRect(PW * 0.45f, 0f, PW.toFloat(), bandH, pFill)

        // Avatar circle — initials
        val avatarX = M + 30f
        val avatarY = bandH / 2f
        s.canvas.drawCircle(avatarX, avatarY, 28f, pAvatar)
        val initials = c.displayName.split(" ").take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
            .ifBlank { "?" }
        s.canvas.drawText(initials, avatarX, avatarY - (pAvatarText.ascent() + pAvatarText.descent()) / 2f, pAvatarText)

        // Name + role line to the right of the avatar
        val textX = avatarX + 38f
        s.canvas.drawText(c.displayName.ifBlank { "(no name)" }, textX, avatarY - 10f, pHeroName)
        val role = listOf(c.jobTitle, c.organization).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (role.isNotBlank()) {
            s.canvas.drawText(role, textX, avatarY + 6f, pHeroRole)
        }

        // Key meta (phone / email) in bottom of band
        val meta = buildList {
            if (c.phoneNumber.isNotBlank()) add(c.phoneNumber)
            if (c.email.isNotBlank())       add(c.email)
        }.joinToString("   |   ")
        if (meta.isNotBlank()) {
            s.canvas.drawText(meta, textX, avatarY + 20f, pHeroMeta)
        }

        // Purple accent stripe at bottom of band
        pFill.color = ACCENT
        s.canvas.drawRect(0f, bandH - 3f, PW.toFloat(), bandH, pFill)

        s.y = bandH + 16f
    }

    // ── Contact detail card ────────────────────────────────────────────────────

    private fun drawContactDetails(s: PageState, c: ContactEntity) {
        val fields = buildList {
            if (c.phoneNumber.isNotBlank()) add("Phone"    to c.phoneNumber)
            if (c.email.isNotBlank())       add("Email"    to c.email)
            if (c.address.isNotBlank())     add("Address"  to c.address)
            if (c.birthday.isNotBlank())    add("Birthday" to c.birthday)
        }
        val hasNotes = c.notes.isNotBlank()
        if (fields.isEmpty() && !hasNotes) return

        val cardH = fields.size * LINE + (if (hasNotes) 32f else 0f) + 24f
        s.space(cardH)
        drawCard(s, cardH)

        s.advance(14f)
        drawSectionHeader(s, "CONTACT DETAILS")
        s.advance(8f)

        for ((label, value) in fields) {
            val labelW = 56f
            s.canvas.drawText(label.uppercase(), M + 12f, s.y, pLabel)
            drawWrapped(s, value, M + 12f + labelW, pValue, indent = M + 12f + labelW, maxW = CW - labelW - 12f)
            s.advance(LINE)
        }

        if (hasNotes) {
            s.advance(6f)
            s.canvas.drawText("NOTES", M + 12f, s.y, pLabel)
            s.advance(LINE_S)
            drawWrapped(s, c.notes, M + 12f, pNotes, indent = M + 12f, maxW = CW - 12f)
            s.advance(4f)
        }

        s.advance(12f)
    }

    // ── Interactions timeline ──────────────────────────────────────────────────

    private fun drawInteractions(s: PageState, input: Input) {
        s.space(20f)
        drawSectionHeader(s, "INTERACTION TIMELINE  ·  ${input.interactions.size} TOTAL")
        s.advance(10f)

        if (input.interactions.isEmpty()) {
            s.canvas.drawText("No interactions recorded.", M, s.y, pMuted)
            s.advance(LINE)
            return
        }

        val tsFmt = SimpleDateFormat("d MMM yyyy  HH:mm", Locale.getDefault())

        input.interactions.forEachIndexed { idx, log ->
            val atts           = input.attachmentsByLogId[log.id].orEmpty()
            val (images, others) = atts.partition { it.mimeType.startsWith("image/") }
            val hasNote        = log.notes.isNotBlank()
            val hasParticipants= log.participants.isNotBlank() && log.participants != "[]"

            // Estimate height needed for this entry
            val noteLines  = if (hasNote) estimateLines(log.notes, CW - 28f, pNotes) else 0
            val entryH     = 14f + LINE + (if (hasParticipants) LINE_S else 0f) + (if (log.simLabel.isNotBlank()) LINE_S else 0f) +
                             noteLines * LINE_S + images.size * 160f + (if (others.isNotEmpty()) LINE_S else 0f) + 18f
            s.space(entryH.coerceAtMost(PH * 0.8f))

            // Timeline bullet
            val bulletX = M + 6f
            pFill.color = if (idx == 0) ACCENT else ACCENT_LIGHT
            s.canvas.drawCircle(bulletX, s.y - 3f, if (idx == 0) 5f else 3.5f, pFill)

            // Vertical rule connecting bullets (skip for last entry)
            if (idx < input.interactions.lastIndex) {
                pDivide.color = ACCENT_LIGHT
                s.canvas.drawLine(bulletX, s.y + 2f, bulletX, s.y + entryH - 2f, pDivide)
                pDivide.color = RULE_LINE
            }

            val rowX = M + 18f

            // Header: type + timestamp
            val header = buildString {
                append(log.interactionType)
                if (log.reason.isNotBlank()) append("  ·  ${log.reason}")
            }
            s.canvas.drawText(header, rowX, s.y, pTimeline)
            val tsText = tsFmt.format(Date(log.callTimestamp))
            val tsP    = Paint(pMuted).also { it.textAlign = Paint.Align.RIGHT }
            s.canvas.drawText(tsText, PW - M, s.y, tsP)
            s.advance(LINE)

            // Outcome chip + duration chip
            val chips = buildList {
                if (log.outcome.isNotBlank()) add(log.outcome)
                if (log.durationSeconds > 0)  add(formatDuration(log.durationSeconds))
                if (log.simLabel.isNotBlank()) add(log.simLabel)
            }
            if (chips.isNotEmpty()) {
                var cx = rowX
                for (chip in chips) {
                    cx = drawChip(s, chip, cx)
                    cx += 6f
                }
                s.advance(LINE)
            }

            // Participants
            if (hasParticipants) {
                val names = parseParticipantNames(log.participants)
                if (names.isNotEmpty()) {
                    s.canvas.drawText("With: ${names.joinToString(", ")}", rowX, s.y, pMuted)
                    s.advance(LINE_S)
                }
            }

            // Notes
            if (hasNote) {
                drawWrapped(s, log.notes, rowX, pNotes, indent = rowX, maxW = CW - 18f)
                s.advance(4f)
            }

            // Inline images
            images.forEach { att ->
                val file = input.imagesByAttachmentId[att.id] ?: return@forEach
                drawImage(s, file)
            }

            // Other attachments listed by name
            if (others.isNotEmpty()) {
                val names = others.joinToString(", ") { it.fileName }
                s.canvas.drawText("📎  $names", rowX, s.y, pMuted)
                s.advance(LINE_S)
            }

            s.advance(14f)

            // Light divider between entries (not after last)
            if (idx < input.interactions.lastIndex) {
                s.canvas.drawLine(rowX, s.y - 6f, PW - M, s.y - 6f, pDivide)
            }
        }
    }

    // ── Drawing helpers ────────────────────────────────────────────────────────

    /** Draws a rounded-rect card background starting at s.y, height [h]. */
    private fun drawCard(s: PageState, h: Float) {
        pFill.color = CARD_BG
        val rect = RectF(M, s.y - 4f, PW - M, s.y + h - 4f)
        s.canvas.drawRoundRect(rect, 10f, 10f, pFill)
        pFill.color = RULE_LINE
        val stroke = Paint(pFill).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f }
        s.canvas.drawRoundRect(rect, 10f, 10f, stroke)
    }

    /** Draws a coloured UPPERCASE section header with a purple rule below. */
    private fun drawSectionHeader(s: PageState, text: String) {
        s.canvas.drawText(text, M, s.y, pSectionHdr)
        s.advance(4f)
        pFill.color = ACCENT
        s.canvas.drawRect(M, s.y, M + 32f, s.y + 1.5f, pFill)
        s.advance(6f)
    }

    /** Draws a rounded chip with [label] at (x, s.y) and returns the right edge x. */
    private fun drawChip(s: PageState, label: String, x: Float): Float {
        val textW = pChip.measureText(label)
        val chipW = textW + 14f
        val chipH = 13f
        pFill.color = CHIP_BG
        val rect = RectF(x, s.y - chipH + 2f, x + chipW, s.y + 2f)
        s.canvas.drawRoundRect(rect, chipH / 2f, chipH / 2f, pFill)
        s.canvas.drawText(label, x + 7f, s.y, pChip)
        return x + chipW
    }

    /** Wraps [text] at [maxW], drawing each line at [indent], advancing s.y per line. */
    private fun drawWrapped(s: PageState, text: String, startX: Float, paint: Paint, indent: Float, maxW: Float) {
        for (rawLine in text.lines()) {
            if (rawLine.isBlank()) { s.advance(LINE_S / 2f); continue }
            var rest = rawLine
            var first = true
            while (rest.isNotEmpty()) {
                val count = paint.breakText(rest, true, maxW, null)
                val chunk = rest.substring(0, count.coerceAtLeast(1))
                val cut   = if (count < rest.length) chunk.lastIndexOf(' ') else -1
                val line  = if (cut > 4) chunk.substring(0, cut) else chunk
                s.space(LINE_S)
                s.canvas.drawText(line, if (first) startX else indent, s.y, paint)
                s.advance(LINE_S)
                rest  = rest.substring(line.length).trimStart()
                first = false
            }
        }
    }

    /** Estimates number of lines [text] will wrap to at [maxW]. */
    private fun estimateLines(text: String, maxW: Float, paint: Paint): Int =
        text.lines().sumOf { line ->
            if (line.isBlank()) 1
            else maxOf(1, kotlin.math.ceil(paint.measureText(line).toDouble() / maxW.toDouble()).toInt())
        }

    private fun drawImage(s: PageState, file: File) {
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return
        val maxW = CW - 18f
        val maxH = 150f
        val ratio = minOf(maxW / bmp.width, maxH / bmp.height, 1f)
        val tw = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val th = (bmp.height * ratio).toInt().coerceAtLeast(1)
        s.space(th + 8f)
        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
        // Rounded clip via canvas save/restore
        s.canvas.save()
        val clipRect = RectF(M + 18f, s.y, M + 18f + tw, s.y + th)
        val clipPath = Path().apply { addRoundRect(clipRect, 6f, 6f, Path.Direction.CW) }
        s.canvas.clipPath(clipPath)
        s.canvas.drawBitmap(scaled, M + 18f, s.y, null)
        s.canvas.restore()
        s.advance(th + 8f)
    }

    // ── Tiny helpers ───────────────────────────────────────────────────────────

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60; val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun parseParticipantNames(json: String): List<String> {
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).optString("name", "") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
