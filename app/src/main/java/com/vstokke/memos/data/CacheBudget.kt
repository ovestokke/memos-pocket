package com.vstokke.memos.data

import com.vstokke.memos.domain.Memo

internal object CacheBudget {
    fun bytes(memo: Memo): Long = memo.content.toByteArray(Charsets.UTF_8).size.toLong() +
        memo.snippet.toByteArray(Charsets.UTF_8).size

    fun retain(clean: List<Memo>, limit: Long, protected: List<Memo> = emptyList()): List<Memo> {
        var remaining = (limit - protected.sumOf(::bytes)).coerceAtLeast(0)
        return clean.distinctBy { it.name }.sortedByDescending { it.createTime }.filter {
            val size = bytes(it)
            if (size <= remaining) { remaining -= size; true } else false
        }
    }
}
