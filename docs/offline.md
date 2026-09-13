# Offline memos

Memos Pocket reads and writes memo text in SQLite. Saving does not wait for the network or a usable access token. A saved memo and its upload intent commit in the same transaction. Unsaved editor drafts still live in the ViewModel: rotation and background updates preserve them, but process death can lose text that has not been saved.

## Sync

WorkManager starts a network-constrained sync after local writes and at startup. It also requests periodic sync about every 15 minutes, subject to Android's battery and background limits. Force-stopping the app prevents background work until it is opened again. Refresh and **Sync now** schedule work; they do not lock the editor while requests run.

Sync scans My memos and every advertised Space in both normal and archived states, then uploads pending changes. This order prevents a stale list response from overwriting a mutation acknowledged later in the same run or resurrecting a confirmed deletion. A failed or interrupted scan never establishes that missing memos were deleted. Only a complete scan replaces/prunes clean cache entries. Local pending changes stay on top of downloaded data.

The cache budget is 100 MiB of UTF-8 content and snippets. When necessary, the app keeps newer clean entries and reports incomplete caching. Pending changes, deletion requests and conflicts are exempt from eviction. SQLite snapshots, metadata and the reminder ledger take additional disk space. Images and attachments are not cached.

## Conflicts and failures

Open **Settings → Offline and sync** to inspect pending changes, including hidden deletion requests.

When the server differs from the last confirmed snapshot, sync keeps the local desired version and the server version. You can explicitly:

- discard the local changes and use the server version (including its deletion), or
- keep the local version as a new memo, leaving the server version unchanged.

Both choices require confirmation. A conflict on one memo does not prevent unrelated uploads.

Transient network/server failures leave work pending and make WorkManager retry with backoff. Permanent request failures stop that memo and show **Sync failed**; its text remains selectable for recovery. After correcting the memo or server problem, choose **Try again** explicitly.

A timed-out create is first checked by its stable ID. If that ID is still absent, automatic retry stops because an older server may have ignored the chosen ID. **Try again** starts a new create attempt explicitly and may produce a duplicate if such a server accepted the earlier request under another ID. If a response returns a different ID, the app preserves that server memo and the local version as a normal conflict.

Authentication failure pauses syncing, not local reading or writing. Use **Sign in again** in Settings. Signing in to the same server and user preserves the queue. Switching to another account is blocked while local work remains. Confirming **Disconnect** explicitly discards all local work; it does not delete server memos.

## Safety limits

Memos supports a chosen create ID but does not provide an atomic revision precondition for edits. Snapshot comparison detects changes made before the preflight GET; another client can still write between that GET and PATCH. The app does not claim atomic conflict prevention against concurrent server writers.

A dispatched intent is frozen on disk. After a lost response or process death, sync checks the remote memo against that intent before retrying. An acknowledgement advances the confirmed baseline without replacing newer local edits. Deletes never use `force`.

## Validation

JVM tests cover local access without session refresh, queueing, interrupted scans, scope filtering, stable create IDs, lost-response recovery, concurrent local saves, conflicts, authentication retention and account replacement protection. Android tests use uniquely named test databases in the debug package, not the app's account database. They exercise v1/v2 migrations, transaction rollback, reopen persistence, tombstones, conflict resolution, acknowledgement revisions, cache limits and reminder delivery deduplication.

Live multi-device conflict races and a full 100 MiB dataset still require device acceptance testing. Browser SSO end-to-end testing remains dependent on registering the mobile callback with the identity provider.
