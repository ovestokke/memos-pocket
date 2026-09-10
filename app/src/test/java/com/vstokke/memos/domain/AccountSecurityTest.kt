package com.vstokke.memos.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSecurityTest {
    @Test
    fun `account string representation redacts the access token`() {
        val account = Account(
            baseUrl = "https://memos.example.com",
            token = "private-token-value",
            userName = "users/alice",
            displayName = "Alice",
            supportsMemoReminderTime = true,
        )

        assertFalse(account.toString().contains("private-token-value"))
        assertTrue(account.toString().contains("token=[redacted]"))
    }
}
