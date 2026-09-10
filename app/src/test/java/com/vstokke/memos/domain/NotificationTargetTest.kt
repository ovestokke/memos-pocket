package com.vstokke.memos.domain

import org.junit.Assert.*
import org.junit.Test

class NotificationTargetTest {
    @Test
    fun queuedNotificationCannotOpenForAnotherServerOrUser() {
        val account = AccountSummary("https://memos.example.com", "users/alice", "Alice", true)
        val target = NotificationTarget(account.baseUrl, account.userName, "memos/one")
        assertTrue(target.matches(account))
        assertFalse(target.matches(null))
        assertFalse(target.matches(account.copy(baseUrl = "https://other.example.com")))
        assertFalse(target.matches(account.copy(userName = "users/bob")))
    }
}
