# Vinci — Personal CRM for Nextcloud

> Stay in touch with the people who matter — synced to your own Nextcloud.

![GitHub Release](https://img.shields.io/github/v/release/andreibarburas/vinci-android?style=flat&link=https://github.com/andreibarburas/vinci-android/releases)
![GitHub last commit](https://img.shields.io/github/last-commit/andreibarburas/vinci-android)
![GitHub Issues](https://img.shields.io/github/issues/andreibarburas/vinci-android?link=https://github.com/andreibarburas/vinci-android/issues)

[![Get it on Google Play](https://img.shields.io/badge/Google%20Play-Download-brightgreen?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.brbrs.vinci)
[![F-Droid](https://img.shields.io/badge/F--Droid-coming%20soon-blue?logo=f-droid)](https://f-droid.org)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-purple.svg)](LICENSE)

![GitHub Sponsors](https://img.shields.io/github/sponsors/andreibarburas?link=https://github.com/sponsors/andreibarburas)

---

Vinci is a free, open-source, privacy-first personal CRM for Android. Your contacts, interaction history, follow-ups, and relationship notes live entirely on your own Nextcloud — no third-party servers, no tracking, no ads.

---

## Screenshots

| Home | Log Interaction | Contact Detail |
|------|----------------|---------------|
| ![Home](fastlane/metadata/android/en-US/images/phoneScreenshots/01.png) | ![Log](fastlane/metadata/android/en-US/images/phoneScreenshots/02.png) | ![Detail](fastlane/metadata/android/en-US/images/phoneScreenshots/03.png) |

| Contacts | Settings | Dark Mode |
|----------|----------|-----------|
| ![Contacts](fastlane/metadata/android/en-US/images/phoneScreenshots/04.png) | ![Settings](fastlane/metadata/android/en-US/images/phoneScreenshots/05.png) | ![Dark](fastlane/metadata/android/en-US/images/phoneScreenshots/06.png) |

---

## Features

- **Interaction logging** — Log calls, meetings, emails, messages, and social media interactions with type, reason, notes, outcome, and tags
- **Starred contacts** — Pin your most important people for quick access from the home screen
- **Follow-up reminders** — Set a follow-up reminder when logging an interaction; syncs to Tasks.org via CalDAV
- **Birthday reminders** — See upcoming birthdays on the home screen
- **Contact detail** — View all contact info (phone, email, address, birthday, notes, social links) alongside your full interaction history
- **Tag filtering** — Filter contacts by tag on the Contacts tab
- **Bulk actions** — Star or unstar multiple contacts via long-press selection
- **Markdown export** — Export a contact's full interaction history as a Markdown file
- **File attachments** — Attach files to contacts; stored on Nextcloud WebDAV
- **WhatsApp & Signal deep links** — Start a WhatsApp or Signal conversation directly from the contact detail screen
- **Text size** — Small / Default / Large / Extra Large, applied throughout the app
- **Biometric lock** — Require fingerprint or face unlock when opening Vinci
- **Dark mode** — Full dark theme support
- **Nextcloud sync** — All data syncs to your own Nextcloud via WebDAV; contacts read from DAVx5

---

## Requirements

- Android 9 (API 28) or higher
- A self-hosted [Nextcloud](https://nextcloud.com) instance
- [DAVx5](https://www.davx5.com/) (or a compatible CardDAV app) for contact sync
- [Tasks.org](https://tasks.org/) (optional) for follow-up reminders via CalDAV

---

## Privacy

Vinci has no backend. All data — interaction logs, follow-ups, attachments — is stored on **your own Nextcloud**. Vinci never connects to any server other than the one you configure. No analytics, no crash reporting, no ads, no tracking of any kind.

Privacy policy: [barburas.com/privacy-policy](https://barburas.com/privacy-policy)

---

## Building from Source

```bash
git clone https://github.com/andreibarburas/vinci-android.git
cd vinci-android
./gradlew assembleDebug
```

Requires Android Studio Ladybug or newer, JDK 17+.

---

## About Author

Hi, I'm [Andrei Barburas](https://barburas.com). By day, I work at the intersection of information management, intelligence gathering, and human cyber risk; designing systems that make sense of complexity and surface what usually stays unseen, drawing on a background in counter-terrorism research and the behavioral patterns behind radicalization and digital vulnerability. I also lecture in Ethics, Communication Management, and Cross-Cultural Management, and write and teach courses on psychology, culture, and critical thinking.

This corner of GitHub is the other side of that same instinct. I build a small collection of free, open-source tools for people who self-host: a suite of [Android Apps](https://github.com/stars/andreibarburas/lists/android-apps) designed around Nextcloud and selfhosters, along with [Home Assistant Apps](https://github.com/andreibarburas/hassio-apps) and a handful of [Wordpress Plugins](https://github.com/andreibarburas/lists/wordpress-plugins).

I started making these because I wanted something the existing options didn't offer; small, focused tools that fit my own self-hosted setup instead of forcing me to compromise. No ads, no tracking, GPLv3 licensed where applicable, and built to work with the tools people already run at home.

## AI Usage

I'm open about the fact that I use AI throughout development. It genuinely helps me move faster and build things I wouldn't otherwise have time for as one person. But AI doesn't replace the actual work; I still spend the evenings figuring out what something should actually do, testing it against real setups and edge cases, and tracking down the inevitable weird bugs. AI is a tool I use, not a shortcut around the thinking.

## Support

If my work saves you time, a small tip means a lot:

- ⭐ **Donate via Bunq (my bank):** [bunq.me/barburasdonations](https://bunq.me/barburasdonations?description=Donation%20from%20Github)
- 🐈‍⬛ **GitHub Sponsors:** [github.com/sponsors/andreibarburas](https://github.com/sponsors/andreibarburas)
- ☕ **Buy me a coffee:** [buymeacoffee.com/barburas](https://buymeacoffee.com/barburas)
- 🌐 **Website:** [barburas.com](https://barburas.com)
- 🐛 **Issues & feature requests:** [open an issue](/issues)

## Links

- [Play Store](https://play.google.com/store/apps/dev?id=6842866278906089090) [![Google Play Store](https://img.shields.io/badge/Google_Play-414141?logo=google-play&logoColor=white)](https://play.google.com/store/apps/dev?id=6842866278906089090)
- [r/BarburasLab](https://reddit.com/r/barburaslab) ![Subreddit subscribers](https://img.shields.io/reddit/subreddit-subscribers/BarburasLab?style=flat&logo=reddit&link=https://reddit.com/barburaslab)
- [Privacy Policy](https://barburas.com/privacy-policy/) [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-purple.svg)](LICENSE)

## Other work on Github

- [Android Apps](https://github.com/stars/andreibarburas/lists/android-apps)
- [Home Assistant Apps](https://github.com/andreibarburas/hassio-apps)
- [Wordpress Plugins](https://github.com/stars/andreibarburas/lists/wordpress-plugins)

## License

All my work is licensed under the [GNU General Public License v3.0](LICENSE).

You are free to use, study, modify, and redistribute this software, provided that any distributed modifications are also licensed under GPL v3 and the source code remains available.

---

*by [andrei BARBURAS](https://barburas.com)*