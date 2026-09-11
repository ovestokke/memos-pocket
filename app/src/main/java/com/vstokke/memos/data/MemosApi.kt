package com.vstokke.memos.data

import com.vstokke.memos.domain.MemoPage
import com.vstokke.memos.domain.MemoEdit
import com.vstokke.memos.domain.Account
import com.vstokke.memos.domain.AppError
import com.vstokke.memos.domain.AppException
import com.vstokke.memos.domain.CreatedMemoResult
import com.vstokke.memos.domain.Memo
import com.vstokke.memos.domain.NewMemo
import com.vstokke.memos.domain.ReminderRecord
import com.vstokke.memos.domain.ReminderTime
import com.vstokke.memos.domain.Space
import com.vstokke.memos.domain.User
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class MemosApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    fun currentUser(baseUrl: String, token: String): User {
        val body = execute(baseUrl, token, "api/v1/auth/me")
        return parseJson(body) { root ->
            val user = root.optJSONObject("user") ?: throw JSONException("missing user")
            val name = user.getString("name")
            if (!USER_NAME.matches(name)) throw JSONException("invalid user resource")
            User(
                name = name,
                username = user.optString("username"),
                displayName = user.optString("displayName"),
            )
        }
    }

    fun supportsMemoReminderTime(baseUrl: String, token: String): Boolean {
        val body = execute(baseUrl, token, "api/v1/instance/profile")
        return parseJson(body) { root -> root.optBoolean("memoReminderTimeSupported", false) }
    }

    fun listSpaces(account: Account): List<Space> {
        val spaces = LinkedHashMap<String, Space>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val url = endpoint(account.baseUrl, "api/v1/spaces").newBuilder()
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val body = execute(account, url)
            pageToken = parseJson(body) { root ->
                val source = root.optJSONArray("spaces") ?: org.json.JSONArray()
                for (index in 0 until source.length()) {
                    val value = source.getJSONObject(index)
                    val name = value.getString("name")
                    if (!SPACE_NAME.matches(name)) throw JSONException("invalid space resource")
                    spaces[name] = Space(
                        name = name,
                        title = value.getString("title"),
                        description = value.optString("description"),
                    )
                }
                root.optString("nextPageToken").ifBlank { null }
            }
            if (pageToken != null && !seenTokens.add(pageToken!!)) {
                throw AppException(AppError.InvalidResponse)
            }
        } while (pageToken != null)
        return spaces.values.sortedBy { it.title.lowercase() }
    }

    fun listRecent(account: Account, limit: Int = 20): List<Memo> {
        val url = memoListUrl(account, limit, orderBy = "create_time desc")
        val body = execute(account, url)
        return parseJson(body) { root -> parseMemos(root) }
    }

    fun listAllReminders(account: Account): List<ReminderRecord> {
        val reminders = LinkedHashMap<String, ReminderRecord>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val url = memoListUrl(account, 1_000, pageToken = pageToken)
            val body = execute(account, url)
            pageToken = parseJson(body) { root ->
                parseMemos(root).forEach { memo ->
                    memo.reminderTime?.let { due ->
                        reminders[memo.name] = ReminderRecord(
                            memoName = memo.name,
                            dueAt = due,
                            content = memo.snippet.ifBlank { memo.content },
                        )
                    }
                }
                root.optString("nextPageToken").ifBlank { null }
            }
            if (pageToken != null && !seenTokens.add(pageToken!!)) {
                throw AppException(AppError.InvalidResponse)
            }
        } while (pageToken != null)
        return reminders.values.toList()
    }

    fun createMemo(account: Account, draft: NewMemo): CreatedMemoResult {
        if (draft.reminderTime != null && !account.supportsMemoReminderTime) throw AppException(AppError.UnsupportedReminder)
        require(
            draft.visibility in listOf("PRIVATE", "PROTECTED", "PUBLIC") ||
                (draft.visibility == "SPACE" && draft.space?.matches(SPACE_NAME) == true),
        )
        draft.space?.let { require(SPACE_NAME.matches(it)) }
        val payload = JSONObject()
            .put("content", draft.content)
            .put("state", "NORMAL")
            .put("visibility", draft.visibility)
        draft.space?.let { payload.put("space", it) }
        draft.reminderTime?.let { payload.put("reminderTime", ReminderTime.toServer(it)) }

        val body = execute(
            account = account,
            url = endpoint(account.baseUrl, "api/v1/memos"),
            method = "POST",
            jsonBody = payload.toString(),
        )
        val memo = parseJson(body, ::parseMemo)
        return CreatedMemoResult(
            memo = memo,
            reminderPreserved = draft.reminderTime == null || memo.reminderTime == draft.reminderTime,
        )
    }

    fun listPage(
        account: Account,
        state: String = "NORMAL",
        pageToken: String? = null,
        space: String? = null,
    ): MemoPage {
        val body = execute(account, memoListUrl(account, 30, "pinned desc, create_time desc", pageToken, state, space))
        return parseJson(body) { MemoPage(parseMemos(it), it.optString("nextPageToken").ifBlank { null }) }
    }

    fun getMemo(account: Account, name: String): Memo =
        parseJson(execute(account, memoEndpoint(account, name)), ::parseMemo)

    fun editMemo(account: Account, base: Memo, edit: MemoEdit): Memo {
        val fields = JSONObject()
        val mask = mutableListOf<String>()
        if (edit.content != base.content) { fields.put("content", edit.content); mask += "content"; mask += "update_time" }
        if (edit.visibility != base.visibility) {
            require(edit.visibility in listOf("PRIVATE", "PROTECTED", "PUBLIC") || (edit.visibility == "SPACE" && base.space != null))
            fields.put("visibility", edit.visibility); mask += "visibility"
        }
        if (edit.reminderTime != base.reminderTime) {
            if (!account.supportsMemoReminderTime) throw AppException(AppError.UnsupportedReminder)
            mask += "reminder_time"
            edit.reminderTime?.let { fields.put("reminderTime", ReminderTime.toServer(it)) }
        }
        return if (mask.isEmpty()) base else patch(account, base.name, fields, mask)
    }

    fun setPinned(account: Account, memo: Memo, pinned: Boolean): Memo =
        patch(account, memo.name, JSONObject().put("pinned", pinned), listOf("pinned"))

    fun setState(account: Account, memo: Memo, state: String): Memo {
        require(state in listOf("NORMAL", "ARCHIVED"))
        return patch(account, memo.name, JSONObject().put("state", state), listOf("state"))
    }

    fun deleteMemo(account: Account, name: String) {
        execute(account, memoEndpoint(account, name), "DELETE") // Never force deletion.
    }

    private fun patch(account: Account, name: String, fields: JSONObject, mask: List<String>): Memo {
        // grpc-gateway query parsing splits paths verbatim; this is NOT JSON FieldMask encoding.
        val url = memoEndpoint(account, name).newBuilder().addQueryParameter("updateMask", mask.joinToString(",")).build()
        return parseJson(execute(account, url, "PATCH", fields.toString()), ::parseMemo)
    }

    private fun memoEndpoint(account: Account, name: String): HttpUrl {
        if (!MEMO_NAME.matches(name) || name.substringAfter('/') in listOf(".", "..") ||
            name.any { it == '?' || it == '#' || it == '%' || it == '\\' }) throw AppException(AppError.InvalidResponse)
        return endpoint(account.baseUrl, "api/v1/$name")
    }

    private fun memoListUrl(
        account: Account,
        pageSize: Int,
        orderBy: String? = null,
        pageToken: String? = null,
        state: String = "NORMAL",
        space: String? = null,
    ): HttpUrl {
        val creator = account.userName.replace("\\", "\\\\").replace("\"", "\\\"")
        space?.let { require(SPACE_NAME.matches(it)) }
        return endpoint(account.baseUrl, "api/v1/memos").newBuilder()
            .addQueryParameter("pageSize", pageSize.toString())
            .addQueryParameter("state", state)
            .apply {
                orderBy?.let { addQueryParameter("orderBy", it) }
                if (space == null) addQueryParameter("filter", "creator == \"$creator\"")
                else addQueryParameter("space", space)
                pageToken?.let { addQueryParameter("pageToken", it) }
            }
            .build()
    }

    private fun endpoint(baseUrl: String, path: String): HttpUrl =
        baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()

    private fun execute(account: Account, url: HttpUrl, method: String = "GET", jsonBody: String? = null): String =
        execute(account.baseUrl, account.token, url, method, jsonBody)

    private fun execute(baseUrl: String, token: String, path: String): String =
        execute(baseUrl, token, endpoint(baseUrl, path))

    private fun execute(
        baseUrl: String,
        token: String,
        url: HttpUrl,
        method: String = "GET",
        jsonBody: String? = null,
    ): String {
        check(url.toString().startsWith(baseUrl))
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .apply {
                if (jsonBody != null) {
                    method(method, jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                } else {
                    method(method, null)
                }
            }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw AppException(
                        if (response.code == 401) {
                            AppError.Authentication
                        } else if (response.code == 403) {
                            AppError.Permission
                        } else {
                            AppError.Server(response.code)
                        },
                    )
                }
                return response.body?.string() ?: throw AppException(AppError.InvalidResponse)
            }
        } catch (error: AppException) {
            throw error
        } catch (_: IOException) {
            throw AppException(AppError.Network)
        }
    }

    private fun parseMemos(root: JSONObject): List<Memo> {
        val source = if (!root.has("memos")) org.json.JSONArray() else root.getJSONArray("memos")
        return buildList(source.length()) {
            for (index in 0 until source.length()) {
                add(parseMemo(source.getJSONObject(index)))
            }
        }
    }

    private fun parseMemo(value: JSONObject): Memo {
        val name = value.getString("name")
        if (!MEMO_NAME.matches(name)) throw JSONException("invalid memo resource")
        return Memo(
            name = name,
            content = value.optString("content"),
            snippet = value.optString("snippet"),
            visibility = value.optString("visibility", "PRIVATE"),
            createTime = ReminderTime.parseServer(value.optNullableString("createTime")) ?: Instant.EPOCH,
            updateTime = ReminderTime.parseServer(value.optNullableString("updateTime")),
            reminderTime = ReminderTime.parseServer(value.optNullableString("reminderTime")),
            creator = value.optString("creator"),
            state = value.optString("state", "NORMAL"),
            pinned = value.optBoolean("pinned"),
            parent = value.optNullableString("parent"),
            space = value.optNullableString("space"),
        )
    }

    private fun <T> parseJson(body: String, block: (JSONObject) -> T): T = try {
        block(JSONObject(body))
    } catch (_: JSONException) {
        throw AppException(AppError.InvalidResponse)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val USER_NAME = Regex("^users/[^/]+$")
        val MEMO_NAME = Regex("^memos/[^/]+$")
        val SPACE_NAME = Regex("^spaces/[^/]+$")
    }
}
