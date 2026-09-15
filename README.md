# Memos Pocket

An unofficial Android client for [Memos](https://www.usememos.com/). The app is under active development.

## Implemented

- HTTPS server discovery, username/password and provider-neutral OAuth sign-in, with PKCE and Keystore-encrypted rotating sessions.
- Offline-first normal, archived and Space feeds with complete locally cached Markdown and pinned-first ordering.
- CommonMark/GFM Markdown rendering for headings, emphasis, strong, strikethrough, nested lists, interactive owner task checkboxes, code, block quotes, safe links, tables and thematic breaks; task insertion/continuation is available in both editor surfaces.
- Member-space discovery, space-scoped feeds, remembered space selection and space memo creation.
- Create, edit, pin/unpin, archive/restore, copy link/content and confirmed delete without `force`.
- Durable local creates, edits, pin/archive actions and deletion requests; immediate application-scoped sync with network-constrained WorkManager fallback and manual conflict resolution.
- Compact Compose screens adapted from actual web-Memos colors and layouts, drawer/sidebar navigation, persistent system/light/dark theme.
- Settings with account information, confirmed disconnect, Android notification/channel and exact-alarm controls, sync/alarm diagnostics and an explicit test notification.
- Capability-controlled server reminders, local delivery ledger, stale reminder cleanup and background reconciliation.

Remaining Markdown parity gaps are non-executing fallbacks for images and raw HTML plus literal source for math, diagrams, syntax highlighting, tags/mentions, previews, managed attachments and footnotes. Moving existing memos between spaces, attachments, comments/relations/reactions, full sharing, search/filters/calendar/views and remaining user preferences are still required. Drafts survive rotation but are not persisted across process death.

## Build

JDK 17 and Android SDK 36:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME="$HOME/Android/Sdk" \
  ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease
```

Artifacts:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk` when signing is configured; otherwise `app-release-unsigned.apk`
- Release bundle: `app/build/outputs/bundle/release/app-release.aab`

The release namespace and application ID are **`com.vstokke.memos`**. Debug builds use **`com.vstokke.memos.debug`** and the label **Memos Pocket Debug**, so they can be installed beside the Obtainium release without sharing credentials or data.

The old `com.vstokke.memopocket` installation cannot be updated by this package. It has a separate sandbox, credentials, Keystore, permissions and delivery history. No credentials are exported or copied.

## Install and update with Obtainium

Stable APKs are published through [GitHub Releases](https://github.com/ovestokke/memos-pocket/releases). Add this repository URL to Obtainium:

`https://github.com/ovestokke/memos-pocket`

The first production-signed APK cannot update an older debug-signed build that used the release application ID. That one-time transition requires uninstalling the old debug build before installing through Obtainium. Current debug builds are a separate app; stable releases update the production app in place.

See [`docs/releasing.md`](docs/releasing.md) for signing-key setup and the tag-based release procedure. Self-hosted login and OAuth callback setup are documented in [`docs/authentication.md`](docs/authentication.md).

## Release signing

The build accepts either all or none of:

- `MEMOS_POCKET_STORE_FILE`
- `MEMOS_POCKET_STORE_PASSWORD`
- `MEMOS_POCKET_KEY_ALIAS`
- `MEMOS_POCKET_KEY_PASSWORD`

Without these, release artifacts are unsigned and must not be distributed. Keystores remain outside source control.

## Reminder behavior and diagnostics

The server's `reminderTime` is the synchronized value; pending local reminder edits remain an overlay until acknowledged. Controls are enabled only when `/api/v1/instance/profile` advertises `memoReminderTimeSupported: true`. No hostname inference or hidden device-only fallback is used. Network failures retain the last confirmed capability; confirmed removal clears local reminders before any feed request can fail. Workers do not cancel themselves during cleanup.

The parent session observed the public server still at commit `bbe3fc141132`, without that capability. The old tablet package had `POST_NOTIFICATIONS granted=false`, `SCHEDULE_EXACT_ALARM granted=true`, and no matching scheduled alarm. These observations identify separate blockers; they do not establish an end-to-end trace of the user's test memo.

Settings distinguishes a direct Android test notification from server storage, synchronization and scheduled alarm delivery. “Test submitted” means Android accepted the request, not that a visible notification was verified. **Live notification success has not been demonstrated on the tablet.**

The app maintains a local reminder inventory and delivery ledger and schedules the next due alarm. It catches up reminders from the preceding 24 hours. Only successfully submitted notifications are acknowledged individually in the ledger. Unpublished items remain retryable after process death, with full timestamp precision. A crash after publication but before acknowledgment can repeat submission: stable notification IDs and only-alert-once reduce duplication, but exactly-once delivery across SQLite and Android is not guaranteed.

The application coordinator wakes immediately after durable writes, manual Sync now, resume and network restoration. WorkManager requests approximately 15-minute sync intervals and keeps a CONNECTED-constrained, coalesced retry fallback, subject to Android/network/battery constraints; neither background frequency nor immediate reachability is guaranteed. Inexact alarm fallback can be late. Force-stop prevents background work until reopening. Already synchronized reminders can be delivered offline, but external edits, clear, archive and delete are discovered only on a successful sync. These lifecycle cases still need device validation.

## API and safety

Authentication uses `/api/v1/auth/signin`, `/api/v1/auth/refresh`, `/api/v1/auth/signout`, `/api/v1/auth/me`, instance settings and `/api/v1/identity-providers`. Content routes include `/api/v1/instance/profile`, `/api/v1/spaces`, paginated `/api/v1/memos`, and GET/PATCH/DELETE `/api/v1/memos/{id}`. Space feeds use server scope when available and verify placement client-side for compatibility.

JSON fields use lower camel case. REST `updateMask` query values use proto paths such as `content,update_time,reminder_time`: grpc-gateway does not apply JSON FieldMask camel-case conversion to this query parameter. Clearing a reminder includes `reminder_time` in the mask and omits its timestamp. Content-only mutations never echo attachments, relations, placement or unknown JSON fields.

Writes commit the local memo and upload intent in one SQLite transaction. Sync compares the last confirmed server snapshot before sending one precise mutation. This detects many conflicts but is not atomic; the API has no revision precondition. Conflicts preserve both versions in Settings. Pending deletions are hidden from feeds but remain available in Settings. Stable client-selected `memoId` values and frozen dispatched intents allow GET-based recovery after ambiguous responses; a server that ignores the requested ID stops that upload rather than risking duplicate automatic retries.

Local writes have a short database lock separate from the network sync lock. UI requests carry the expected server/user identity. Authentication failure pauses sync without deleting data; signing in to the same account resumes pending work. Account replacement is blocked while local work remains. Explicitly confirmed disconnect removes credentials, cache, unsynced work, conflicts, local reminders and active notifications, never server memos.

The text cache targets all readable My memos, Archived and advertised Spaces. A completed scan retains the newest clean entries within a 100 MiB UTF-8 content/snippet budget. Pending changes, tombstones and conflicts are never evicted; Settings reports incomplete caching. This is a text budget, not a limit on the SQLite file, JSON snapshots, reminders or unsynced work. Attachments are not downloaded. See [`docs/offline.md`](docs/offline.md) for recovery and validation details.

TLS verification, redirect blocking, HTTPS-only input, backup exclusion and private lock-screen notifications are retained. Tokens, passwords and memo bodies never enter logs, diagnostics or error messages. Refresh credentials are encrypted with a non-exportable Android Keystore key; short-lived session access tokens remain in process memory. Cached memo text is sandboxed but not separately encrypted. Copied links contain no token and do not change visibility.

## Validation

JVM unit tests, lint and the debug APK build are run under JDK 17. Android SQLite tests cover migrations, transaction rollback, reopen persistence, fences, cache limits and reminder delivery, but are compile-checked only in this environment and have not been installed or run on a device. Repository tests otherwise use mocks, and ViewModel tests do not exercise Compose layout. Live offline CRUD/reconnect races, notification delivery and end-to-end browser SSO remain device acceptance work; passing builds are not proof of those behaviors.
