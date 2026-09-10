package com.vstokke.memos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstanceUrlTest {
    @Test
    fun `normalizes an HTTPS instance address`() {
        assertEquals(
            "https://memos.example.com/subpath",
            InstanceUrl.normalize("  https://MEMOS.Example.com/subpath/  "),
        )
    }

    @Test
    fun `rejects cleartext addresses`() {
        assertNull(InstanceUrl.normalize("http://memos.example.com"))
    }

    @Test
    fun `rejects credentials query and fragment`() {
        assertNull(InstanceUrl.normalize("https://user:secret@memos.example.com"))
        assertNull(InstanceUrl.normalize("https://memos.example.com?token=secret"))
        assertNull(InstanceUrl.normalize("https://memos.example.com/#secret"))
    }

    @Test
    fun `rejects whitespace inside an address`() {
        assertNull(InstanceUrl.normalize("https://memos.example.com/a b"))
    }
}
