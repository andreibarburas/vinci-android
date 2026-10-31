# Changelog

All notable changes to Vinci are documented in this file.  
Format: [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)

---

## [2.5.0]
### New
Local-only mode
- You can now use Vinci without a Nextcloud server. On first launch, choose between connecting to Nextcloud or keeping everything on your device. The choice can be changed later from Settings.

Backup and restore (local mode)
- Export your entire history: interactions, contacts, companies, and all display settings; as a single ZIP file you can save anywhere. Restore it on a new device or after a fresh install. Contacts that can’t be matched to your address book on the new device are flagged for review rather than lost.

Native Nextcloud contact sync - no DAVx5 needed
- Vinci can now read and write your Nextcloud contacts directly, mirroring them into Android’s Contacts so they show up in the system Contacts app, dialer, and anywhere else on your phone. Edits made in the system Contacts app push back out to Nextcloud automatically. Contact photos round-trip in both directions. Enable it in Settings → Sync → “Sync with Android Contacts.”

Sync status, visible
- Settings now shows live progress (“Syncing… 420/1315”) and a “Last synced HH:mm · N contacts” line once done. A notification shows the same progress while a sync is running and no longer gets stuck. A “Sync now” action retries safely; unlike toggling sync off and back on, it never deletes already-synced contacts.

### Fixed & Improved
Sync reliability
- Fixed large address books (1000+ contacts) stalling partway through a sync
- Fixed a completed sync still showing a stale, lower contact count
- vCards are now fetched concurrently, substantially cutting total sync time
- A sync can no longer run indefinitely or leave things in a half-finished state

Bug fixes
- Fixed a race condition during first-run onboarding that could send new users to two screens simultaneously
- Permissions screen now shows accurate messaging for local vs. Nextcloud mode
- New contacts created in Vinci now correctly attach to Nextcloud sync even without DAVx5 installed (2.5.0)

Settings
- The Account and Data sections adapt to your storage mode. Local users see backup/restore options and a “Connect to Nextcloud” button; Nextcloud users see the existing server and sync controls.

---

## [2.2.0]
### New
- Company pages: Vinci automatically creates a company page for every organization found in your contacts. See all employees in one place, add an RSS/Atom feed, and sync company notes to Nextcloud.
- Export a contact to PDF: full timeline, SIM labels, inline images, remaining files listed by name
- Dual-SIM labels: name each line (Personal, Business) and every call carries that label through to Nextcloud
- Caller preview modes: pick between a richer notification (last 2 to 3 interactions) or a full-screen card over the ringing screen
- Relationship sparkline: a tiny 12-week activity chart next to the "Logged in Vinci" header showing interaction frequency at a glance
- Calendar nudges: after a meeting ends, a prompt to log it when attendees match your Vinci contacts
- Daily follow-up reminder: one summary notification at a time you choose, with Open and Mark done actions
- Conversation starter: a one-line prompt appears on each contact’s page, assembled from their standing notes and the most recent interaction note, so you always have something to open with
- A contact can now be the primary record of a Vinci company page.

### Fixed & Improved
- Tags now sync to Nextcloud: interaction tags are written to the markdown file and parsed back on restore, so the web app can read and edit them
- Contacts with only an email address (no phone number) no longer show the entire device call history on their profile page
- Restore from Nextcloud now walks each contact's 'calls/[timestamp]-attachments/' folder and imports every file it finds into the Android attachments table (deduped by logId + fileName), so web-side uploads surface locally.

---

## [2.0.1]
### Fixed & Improved
- Fixed the System theme showing incorrect colors when the device was actually in light mode.
- Fixed the OLED Black theme not applying true black backgrounds consistently across the app.

---

## [2.0.0]
### New
- Global interaction search: search notes, reasons, and outcomes across every contact at once; not just one contact's history at a time.
- Duplicate/merge assistant: a new screen (Settings → Duplicate contacts) spots probable duplicates; same name, different Nextcloud identity and merges one's interaction history onto the other in a tap.
- Interaction templates: quick-fill presets (Intake call, Reference check, Advisory session, Follow-up chat) that pre-populate type, reason, and follow-up when logging.
- Pre-call briefing: tapping Call now surfaces your last few interactions with that person first, so you remember what you talked about before you dial.
- Share-sheet capture: share a link, text, or image from any app straight into Vinci; pick a contact and it lands as a note or attachment.
- Sketch attachments: draw a quick diagram or note by hand and attach it to an interaction.
- Timeline view: a new vertical-timeline layout for a contact's history, with month dividers; toggle between it and the original stacked-card view.
- OLED Black theme: a fourth theme option alongside System, Light, and Dark, with true-black surfaces.
- Pull to refresh: swipe down on Home or your contact list to sync immediately.
- Editorial alphabet index: contact list section headers are now large serif letters for a more magazine-like feel.

### Fixed & Improved
- Per-type color hints: calls, meetings, emails, and messages each get a distinct hue within the purple palette, so timelines and lists are easier to scan at a glance.
- Stacked avatars: group interactions now show overlapping participant photos instead of a plain "+N" badge.
- Photo-tinted contact pages: each contact's page now pulls a soft, muted color from their photo as a subtle backdrop.
- Shared-element transitions: tapping a contact now smoothly morphs their avatar from the list into their detail page, with a satisfying spring animation and haptic feedback when starring a contact.
- Time-of-day background: the app's background gradient shifts almost imperceptibly across the day; a touch warmer at dusk, deeper at night.

---

## [1.7.0]
### New
- Contact photos can now be cropped before saving; pick an image and you'll get a square crop with a circular guide overlay, so you can see exactly how it'll look once displayed as a circle (matching how Nextcloud shows avatars).

### Fixed

- Fixed a "Permission Denial: requires android.permission.WRITE_CONTACTS" error when saving a contact. Existing users who set up Vinci before contact editing existed were never actually prompted for this permission; it's now requested the first time you try to save a contact edit, rather than only during initial onboarding.
- Errors when saving a contact now show a clear message instead of raw Android system error text.
- Fixed contact photos disappearing after syncing to Nextcloud; the photo wasn't marked as the primary photo when saved, so the sync didn't recognize it as the image to upload.

---

## [1.6.0]
### Fixed
- Vinci folder was not editable — the field in Settings → Nextcloud is now tappable and opens an edit dialog.
- Vinci folder can now also be set during onboarding, before connecting to Nextcloud.

---

## [1.5.3]
### Added
- Log interactions with multiple contacts at once — group calls and meetings now appear in everyone's history, not just the primary contact.
- Foundation for a Vinci companion app for Nextcloud: contacts and interactions will be viewable and editable from the web.

### Improved
- Sync now pulls in interactions logged on other devices too, not just pushing local changes up.

### Fixed
- An issue where syncing could occasionally create duplicate interactions.

---

## [1.4.0]
### Added
- Optional font pairing in Settings → Display: DM Serif Display for headings, Inter Tight for all UI text — consistent with the rest of the BARBURAS suite.

---

## [1.3.4]
### Added
- Multi-device restore: interactions and starred contacts now restore correctly on a second device (tablet, new phone).
- Restore screen shows progress while importing and a summary when complete.
- New "Sync starred to Nextcloud" button in Settings — pushes starred contacts and follow-ups to Nextcloud, with a spinner and confirmation message.
- Each interaction file now embeds the contact identifier, making restore reliable across devices regardless of how contacts were synced.
- Both plain and bold field formats are now supported when reading older interaction files.

### Fixed
- Deleted interactions are now removed from Nextcloud so they no longer reappear after a restore.
- Starring or unstarring a contact now syncs to Nextcloud immediately.
- Restore and sync actions now show a Snackbar notification when starting and when complete.

---

## [1.1.9]
### Fixed
- Deleting an interaction now correctly resets the contact's last-interaction timestamp in the People section on Home.

## [1.1.8]
### Fixed
- Social Media interactions now correctly save and display the selected platform (was storing a literal backslash due to a string escape bug).

## [1.1.7]
### Added
- "Social Media" interaction type with platform picker (LinkedIn, Instagram, GitHub, etc.) and correct brand icons in interaction lists.

## [1.1.6] / [1.1.5]
### Fixed
- Social Media platform picker now renders correctly — replaced `LazyRow` with `FlowRow` to avoid nested scroll conflict.
- Fixed duplicate `@OptIn` annotation that caused a build error.

## [1.1.4]
### Fixed
- Contacts starting with a digit now appear at the top of the list under a `#` section; `#` added to the alphabet sidebar.

## [1.1.3]
### Fixed
- Social links (LinkedIn, Instagram, GitHub, etc.) now display correctly on the contact detail screen.
- Fixed CardDAV UID resolution to use `SOURCE_ID` (filename) instead of `SYNC1` (internal vCard UID), which differ on some Nextcloud setups.
- Fixed WhatsApp raw contact UID overwriting the DAVx5 UID in multi-account setups.

## [1.1.0]
### Added
- Text size setting (Small / Default / Large / Extra Large) in Settings → Display.
- Tag filter chips on the Contacts tab.
- Bulk star/unstar via long-press selection.
- Per-contact interaction history export as Markdown.
- Privacy policy link in Settings.
- Onboarding screen explaining Vinci and DAVx5 setup.
- GPLv3 license.

### Fixed
- Unknown-number interactions now sync to Nextcloud.
- WhatsApp and Signal icons now display correctly (missing manifest query declarations added).
- Contact detail screen background gradient seam removed.
- Address book URL discovery rewritten to use folder paths instead of `.vcf` file paths.

## [1.0.2]
### Fixed
- Interactions with unsaved/unknown phone numbers were silently not uploading to Nextcloud.
- Fixed folder naming for unknown-number contacts.
- Sync button now retries previously unsynced logs.

## [1.0.1]
### Added
- Initial public release on Google Play.
