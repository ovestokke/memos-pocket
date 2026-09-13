package com.vstokke.memos.data

import com.vstokke.memos.domain.Memo
import com.vstokke.memos.domain.MemoSyncStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OfflineModelTest {
    private val memo = Memo("memos/test", "æ🙂", "æ", "PRIVATE", Instant.EPOCH,
        Instant.parse("2026-01-01T00:00:00.123456789Z"), null, creator = "users/alice")

    @Test fun `snapshot round trip preserves nanoseconds nulls and markdown`() {
        assertEquals(memo, MemoJson.decode(MemoJson.encode(memo)))
        assertTrue(memo.copy(syncStatus = MemoSyncStatus.PENDING).sameSnapshot(memo))
    }
    @Test fun `cache budget is UTF8 not characters and protects pending bytes`() {
        assertEquals(8L, CacheBudget.bytes(memo))
        assertTrue(CacheBudget.retain(listOf(memo), 7).isEmpty())
        assertEquals(listOf(memo), CacheBudget.retain(listOf(memo), 8))
        assertTrue(CacheBudget.retain(listOf(memo), 8, listOf(memo.copy(name = "memos/protected"))).isEmpty())
    }
    @Test fun `desired convergence ignores server timestamps but respects every editable field`() {
        assertTrue(memo.sameDesired(memo.copy(updateTime = Instant.now(), snippet = "rendered")))
        assertFalse(memo.sameDesired(memo.copy(pinned = true)))
        assertFalse(memo.sameDesired(memo.copy(state = "ARCHIVED")))
        assertFalse(memo.sameDesired(memo.copy(content = "different")))
        assertFalse(memo.sameDesired(memo.copy(space = "spaces/another")))
        assertFalse(memo.sameDesired(memo.copy(reminderTime = Instant.now())))
    }
}
