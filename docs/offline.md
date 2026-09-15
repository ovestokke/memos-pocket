# Offline memos

Memos Pocket reads and writes memo text in SQLite. Saving does not wait for the network or a usable access token. A saved memo and its upload intent commit in the same transaction. Unsaved editor drafts still live in the ViewModel: rotation and background updates preserve them, but process death can lose text that has not been saved.

## Sync

A process-scoped coordinator starts a sync turn immediately after a durable local write, at startup, when the app resumes, and when Android reports a network becoming available. Connectivity callbacks are only wake hints; the server request remains the reachability test. WorkManager keeps a CONNECTED-constrained, exponential-backoff fallback (coalesced with KEEP) and periodic work about every 15 minutes, subject to Android's battery and background limits. Force-stopping the app prevents background work until it is opened again. Refresh and **Sync now** wake the coordinator directly and do not lock the editor while requests run.

Each sync turn uploads pending changes before discovering inventory, then scans My memos and every advertised Space in both normal and archived states. Upload work is bounded and repeated at every page boundary, so a save made during a scan is picked up at the next boundary without waiting for the original outbox snapshot. A failed, interrupted, quota-limited, or budget-limited scan never establishes that missing memos were deleted. A budget-limited scan resumes its owner-scoped page/scope cursor on the next turn rather than repeating the same prefix; process death safely starts a new scan. Only a complete inventory replaces/prunes clean cache entries. Local pending changes stay on top of downloaded data.

Acknowledged mutations leave a durable per-memo fence until a later complete scan corroborates the same server snapshot or authoritative deletion. A stale or contradictory list entry is checked with a bounded per-memo GET; unresolved fences remain protected for a later turn. This protects acknowledged edits and deletions without issuing a GET for every cached memo.

The cache budget is 100 MiB of UTF-8 content and snippets. When necessary, the app keeps newer clean entries and reports incomplete caching. Pending changes, deletion requests and conflicts are exempt from eviction. SQLite snapshots, metadata and the reminder ledger take additional disk space. Images and attachments are not cached.

## Conflicts and failures

Open **Settings → Offline and sync** to inspect pending changes, including hidden deletion requests.

When the server differs from the last confirmed snapshot, sync keeps the local desired version and the server version. You can explicitly:

- discard the local changes and use the server version (including its deletion), or
- keep the local version as a new memo, leaving the server version unchanged.

Both choices require confirmation. A conflict on one memo does not prevent unrelated uploads.

Transient network/server failures leave work pending and make the durable scheduler retry with backoff. Permanent request failures stop that memo and show **Sync failed**; its text remains selectable for recovery. After correcting the memo or server problem, choose **Try again** explicitly. A successful upload acknowledgement is tracked separately from pull/discovery failure, so a memo can be synced while refresh inventory is unavailable. The UI distinguishes sending changes, checking the server, upload failure, and refresh failure; an ACK is not inferred from being online or from enqueueing work.

A timed-out create is first checked by its stable ID. If that ID is still absent, automatic retry stops because an older server may have ignored the chosen ID. **Try again** starts a new create attempt explicitly and may produce a duplicate if such a server accepted the earlier request under another ID. If a response returns a different ID, the app preserves that server memo and the local version as a normal conflict.

Authentication failure pauses syncing, not local reading or writing. Use **Sign in again** in Settings. Signing in to the same server and user preserves the queue. Switching to another account is blocked while local work remains. Confirming **Disconnect** explicitly discards all local work; it does not delete server memos.

## Safety limits

Memos supports a chosen create ID but does not provide an atomic revision precondition for edits. Snapshot comparison detects changes made before the preflight GET; another client can still write between that GET and PATCH. The app does not claim atomic conflict prevention against concurrent server writers.

A dispatched intent is frozen on disk. After a lost response or process death, sync checks the remote memo against that intent before retrying. An acknowledgement advances the confirmed baseline without replacing newer local edits. Deletes never use `force`.

## Validation

JVM tests cover local access without session refresh, queueing, interrupted scans, scope filtering, stable create IDs, lost-response recovery, concurrent local saves, conflicts, authentication retention and account replacement protection. Android tests use uniquely named test databases in the debug package, not the app's account database. They exercise v1/v2 migrations, transaction rollback, reopen persistence, tombstones, conflict resolution, acknowledgement revisions, cache limits and reminder delivery deduplication.

Live multi-device conflict races and a full 100 MiB dataset still require device acceptance testing. Browser SSO end-to-end testing remains dependent on registering the mobile callback with the identity provider.
