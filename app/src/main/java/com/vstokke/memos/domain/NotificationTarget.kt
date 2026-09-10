package com.vstokke.memos.domain

/** Nonsecret account-bound destination; never replay a notification against another account. */
data class NotificationTarget(val baseUrl: String, val userName: String, val memoName: String) {
    fun matches(account: AccountSummary?): Boolean =
        account != null && baseUrl == account.baseUrl && userName == account.userName
}
