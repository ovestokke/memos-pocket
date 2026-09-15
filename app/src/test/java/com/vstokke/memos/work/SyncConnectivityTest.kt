package com.vstokke.memos.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConnectivityTest {
    @Test
    fun `identical validated capability callbacks only wake once`() {
        val tracker = UsableNetworkTracker<NetworkKey>()
        val first = NetworkKey(7)
        val equalButDistinct = NetworkKey(7)

        assertTrue(tracker.update(first, usable = true))
        assertFalse(tracker.update(equalButDistinct, usable = true))
        assertFalse(tracker.update(NetworkKey(7), usable = false))
        assertTrue(tracker.update(NetworkKey(7), usable = true))
    }

    @Test
    fun `a different usable network is a new transition`() {
        val tracker = UsableNetworkTracker<NetworkKey>()
        val first = NetworkKey(1)
        val second = NetworkKey(2)

        assertTrue(tracker.update(first, usable = true))
        assertTrue(tracker.update(second, usable = true))
        assertFalse(tracker.update(second, usable = true))
    }

    private data class NetworkKey(val id: Int)
}
