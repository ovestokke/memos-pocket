# Memos Pocket

An unofficial Android client for [Memos](https://www.usememos.com/). The app is under active development.

## Implemented

- HTTPS server + personal access token login, with Keystore-encrypted credentials.
- Paginated normal and archived feeds, pinned-first ordering, memo detail fetched independently of the feed.
- Member-space discovery, space-scoped feeds, remembered space selection and space memo creation.
- Create, edit, pin/unpin, archive/restore, copy link/content and confirmed delete without `force`.
- Precise update masks, best-effort concurrent-edit detection, retained drafts on rotation/network errors, explicit server reload on conflict.
- Compact Compose screens adapted from actual web-Memos colors and layouts, drawer/sidebar navigation, persistent system/light/dark theme.
- Settings with account information, confirmed disconnect, Android notification/channel and exact-alarm controls, sync/alarm diagnostics and an explicit test notification.
- Capability-controlled server reminders, local delivery ledger, stale reminder cleanup and background reconciliation.

Still required: task toggles, moving existing memos between spaces, attachments, comments/relations/reactions, full sharing, search/filters/calendar/views, richer Markdown/editor support and remaining user preferences. Drafts survive rotation but are not persisted across process death.

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

The namespace and application ID are **`com.vstokke.memos`**. App name remains Memos Pocket.

The old `com.vstokke.memopocket` installation cannot be updated by this package. It has a separate sandbox, credentials, Keystore, permissions and delivery history. No credentials are exported or copied.

## Install and update with Obtainium

Stable APKs are published through [GitHub Releases](https://github.com/ovestokke/memos-pocket/releases). Add this repository URL to Obtainium:

`https://github.com/ovestokke/memos-pocket`

The first production-signed APK cannot update a debug-signed development install. Uninstall the debug app once, install the release through Obtainium, and sign in again. Later releases update in place.

See [`docs/releasing.md`](docs/releasing.md) for signing-key setup and the tag-based release procedure.

## Release signing

The build accepts either all or none of:

- `MEMOS_POCKET_STORE_FILE`
- `MEMOS_POCKET_STORE_PASSWORD`
- `MEMOS_POCKET_KEY_ALIAS`
- `MEMOS_POCKET_KEY_PASSWORD`

Without these, release artifacts are unsigned and must not be distributed. Keystores remain outside source control.

## Reminder behavior and diagnostics

The server's `reminderTime` is the source of truth. Controls are enabled only when `/api/v1/instance/profile` advertises `memoReminderTimeSupported: true`. No hostname inference or hidden device-only fallback is used. Network failures retain the last confirmed capability; confirmed removal clears local reminders before any feed request can fail. Workers do not cancel themselves during cleanup.

The parent session observed the public server still at commit `bbe3fc141132`, without that capability. The old tablet package had `POST_NOTIFICATIONS granted=false`, `SCHEDULE_EXACT_ALARM granted=true`, and no matching scheduled alarm. These observations identify separate blockers; they do not establish an end-to-end trace of the user's test memo.

Settings distinguishes a direct Android test notification from server storage, synchronization and scheduled alarm delivery. “Test submitted” means Android accepted the request, not that a visible notification was verified. **Live notification success has not been demonstrated on the tablet.**

The app maintains a local reminder inventory and delivery ledger and schedules the next due alarm. It catches up reminders from the preceding 24 hours. Only successfully submitted notifications are acknowledged individually in the ledger. Unpublished items remain retryable after process death, with full timestamp precision. A crash after publication but before acknowledgment can repeat submission: stable notification IDs and only-alert-once reduce duplication, but exactly-once delivery across SQLite and Android is not guaranteed.

WorkManager requests approximately 15-minute sync intervals, subject to Android/network/battery constraints; it does not guarantee that frequency. Inexact alarm fallback can be late. Force-stop prevents background work until reopening. Already synchronized reminders can be delivered offline, but external edits, clear, archive and delete are discovered only on a successful sync. These lifecycle cases still need device validation.

## API and safety

Implemented routes are `/api/v1/auth/me`, `/api/v1/instance/profile`, `/api/v1/spaces`, paginated `/api/v1/memos`, and GET/PATCH/DELETE `/api/v1/memos/{id}`. Space feeds use server scope when available and verify placement client-side for compatibility.

JSON fields use lower camel case. REST `updateMask` query values use proto paths such as `content,update_time,reminder_time`: grpc-gateway does not apply JSON FieldMask camel-case conversion to this query parameter. Clearing a reminder includes `reminder_time` in the mask and omits its timestamp. Content-only mutations never echo attachments, relations, placement or unknown JSON fields.

Writes first fetch the current memo and compare known fields with the editor's base. This detects many conflicts but is not atomic; the API has no revision precondition. Author-only menus follow the server handler, not a broader web superuser UI exception. The server remains authoritative for space membership. A failed delete leaves the local memo visible. Creation has no automatic retry, including OkHttp connection retry, because an ambiguous response could otherwise duplicate a memo.

Account changes, writes and sync share a mutex; UI requests also carry expected server/user identity. Disconnect removes credentials, cache, local reminders and active notifications, never server memos.

TLS verification, redirect blocking, HTTPS-only input, backup exclusion and private lock-screen notifications are retained. Tokens never enter URLs, diagnostics, shared UI state or error messages. Cached memo text is sandboxed but not separately encrypted. Copied links contain no token and do not change visibility.

## Validation

49 API/domain/repository/ViewModel tests currently pass. A real-SQLite instrumentation regression test for interrupted batches and submillisecond timestamps compiles but has not yet run on a device. Lint and debug/release APK/AAB builds pass; bounded logs are in `validation/`. Repository tests use mocks, and ViewModel tests do not exercise Compose layout. The plan records pending live CRUD, database/alarm instrumentation, actual notification delivery, installation transition and visual/device acceptance. Building is not proof of those behaviors.
