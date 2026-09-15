package com.vstokke.memos.data

import com.vstokke.memos.domain.MemoPage
import com.vstokke.memos.domain.MemoEdit
import com.vstokke.memos.domain.Account
import com.vstokke.memos.domain.AuthProvider
import com.vstokke.memos.domain.AppError
import com.vstokke.memos.domain.AppException
import com.vstokke.memos.domain.CreatedMemoResult
import com.vstokke.memos.domain.Memo
import com.vstokke.memos.domain.NewMemo
import com.vstokke.memos.domain.ReminderRecord
import com.vstokke.memos.domain.ReminderTime
import com.vstokke.memos.domain.Space
import com.vstokke.memos.domain.SpacePage
import com.vstokke.memos.domain.SignInOptions
import com.vstokke.memos.domain.SignedInSession
import com.vstokke.memos.domain.SessionTokens
import com.vstokke.memos.domain.User
import okhttp3.Cookie
import okhttp3.Headers
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
    fun signInOptions(baseUrl: String): SignInOptions {
        val settings = executeUnauthenticated(baseUrl, "api/v1/instance/settings/GENERAL")
        val providersBody = executeUnauthenticated(baseUrl, "api/v1/identity-providers")
        val passwordAllowed = parseJson(settings) { root ->
            !root.optJSONObject("generalSetting")?.optBoolean("disallowPasswordAuth", false).orFalse()
        }
        val providers = parseJson(providersBody) { root ->
            val source = root.optJSONArray("identityProviders") ?: org.json.JSONArray()
            buildList(source.length()) {
                for (index in 0 until source.length()) {
                    val value = source.getJSONObject(index)
                    if (value.optString("type") != "OAUTH2") continue
                    val config = value.optJSONObject("config")?.optJSONObject("oauth2Config") ?: continue
                    val scopesJson = config.optJSONArray("scopes") ?: org.json.JSONArray()
                    val scopes = buildList(scopesJson.length()) {
                        for (scopeIndex in 0 until scopesJson.length()) add(scopesJson.getString(scopeIndex))
                    }
                    add(
                        AuthProvider(
                            name = value.getString("name"),
                            title = value.getString("title"),
                            clientId = config.getString("clientId"),
                            authorizationUrl = config.getString("authUrl"),
                            scopes = scopes,
                        ),
                    )
                }
            }
        }
        return SignInOptions(passwordAllowed, providers)
    }

    fun signInWithPassword(baseUrl: String, username: String, password: String): SignedInSession {
        val payload = JSONObject().put(
            "passwordCredentials",
            JSONObject().put("username", username).put("password", password),
        )
        return parseSignedInSession(
            baseUrl,
            executeRequest(
                baseUrl = baseUrl,
                url = endpoint(baseUrl, "api/v1/auth/signin"),
                method = "POST",
                jsonBody = payload.toString(),
                invalidCredentialsOnBadRequest = true,
            ),
        )
    }

    fun signInWithSso(
        baseUrl: String,
        providerName: String,
        code: String,
        codeVerifier: String,
    ): SignedInSession {
        val payload = JSONObject().put(
            "ssoCredentials",
            JSONObject()
                .put("idpName", providerName)
                .put("code", code)
                .put("redirectUri", OAuthFlow.REDIRECT_URI)
                .put("codeVerifier", codeVerifier),
        )
        return parseSignedInSession(
            baseUrl,
            executeRequest(
                baseUrl = baseUrl,
                url = endpoint(baseUrl, "api/v1/auth/signin"),
                method = "POST",
                jsonBody = payload.toString(),
                signInRequest = true,
            ),
        )
    }

    fun refreshSession(baseUrl: String, refreshToken: String): SessionTokens {
        val response = executeRequest(
            baseUrl = baseUrl,
            url = endpoint(baseUrl, "api/v1/auth/refresh"),
            method = "POST",
            jsonBody = "{}",
            refreshToken = refreshToken,
            refreshRequest = true,
        )
        return parseJson(response.body) { root ->
            SessionTokens(
                accessToken = root.getString("accessToken").takeIf { it.isNotBlank() }
                    ?: throw JSONException("missing access token"),
                accessTokenExpiresAt = Instant.parse(root.getString("expiresAt")),
                refreshToken = response.refreshToken(baseUrl),
            )
        }
    }

    fun signOut(account: Account) {
        executeRequest(
            baseUrl = account.baseUrl,
            url = endpoint(account.baseUrl, "api/v1/auth/signout"),
            method = "POST",
            token = account.token,
            refreshToken = account.refreshToken,
        )
    }

    fun currentUser(baseUrl: String, token: String): User {
        val body = execute(baseUrl, token, "api/v1/auth/me")
        return parseJson(body) { root -> parseUser(root.optJSONObject("user") ?: throw JSONException("missing user")) }
    }

    fun supportsMemoReminderTime(baseUrl: String, token: String): Boolean {
        val body = execute(baseUrl, token, "api/v1/instance/profile")
        return parseJson(body) { root -> root.optBoolean("memoReminderTimeSupported", false) }
    }

    /** Fetches exactly one space page so callers can bound a reconciliation turn. */
    fun listSpacesPage(account: Account, pageToken: String? = null, pageSize: Int = 1_000): SpacePage {
        require(pageSize in 1..1_000)
        val url = endpoint(account.baseUrl, "api/v1/spaces").newBuilder()
            .addQueryParameter("pageSize", pageSize.toString())
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
            .build()
        return parseJson(execute(account, url)) { root ->
            val source = root.optJSONArray("spaces") ?: org.json.JSONArray()
            val spaces = buildList(source.length()) {
                for (index in 0 until source.length()) {
                    val value = source.getJSONObject(index)
                    val name = value.getString("name")
                    if (!SPACE_NAME.matches(name)) throw JSONException("invalid space resource")
                    add(Space(
                        name = name,
                        title = value.getString("title"),
                        description = value.optString("description"),
                    ))
                }
            }
            SpacePage(spaces, root.optString("nextPageToken").ifBlank { null })
        }
    }

    /** Compatibility convenience; reconciliation uses listSpacesPage for bounded work. */
    fun listSpaces(account: Account): List<Space> {
        val spaces = LinkedHashMap<String, Space>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val page = listSpacesPage(account, pageToken)
            page.spaces.forEach { spaces[it.name] = it }
            pageToken = page.nextPageToken
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

    fun createMemo(account: Account, draft: NewMemo): CreatedMemoResult = createMemo(account, draft, null)

    fun createMemo(account: Account, draft: NewMemo, memoId: String?): CreatedMemoResult {
        memoId?.let { require(Regex("^[a-zA-Z0-9][a-zA-Z0-9-]{0,34}[a-zA-Z0-9]$").matches(it)) }
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
            url = endpoint(account.baseUrl, "api/v1/memos").newBuilder().apply {
                memoId?.let { addQueryParameter("memoId", it) }
            }.build(),
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
        pageSize: Int = 30,
    ): MemoPage {
        require(pageSize in 1..1_000)
        val body = execute(account, memoListUrl(account, pageSize, "pinned desc, create_time desc", pageToken, state, space))
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

    fun applyDesired(account: Account, base: Memo, desired: Memo): Memo {
        val fields = JSONObject()
        val mask = mutableListOf<String>()
        if (base.content != desired.content) { fields.put("content", desired.content); mask += "content"; mask += "update_time" }
        if (base.visibility != desired.visibility) { fields.put("visibility", desired.visibility); mask += "visibility" }
        if (base.pinned != desired.pinned) { fields.put("pinned", desired.pinned); mask += "pinned" }
        if (base.state != desired.state) { fields.put("state", desired.state); mask += "state" }
        if (base.reminderTime != desired.reminderTime) {
            if (!account.supportsMemoReminderTime) throw AppException(AppError.UnsupportedReminder)
            mask += "reminder_time"
            desired.reminderTime?.let { fields.put("reminderTime", ReminderTime.toServer(it)) }
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
    ): String = executeRequest(baseUrl, url, method, jsonBody, token = token).body

    private fun executeUnauthenticated(baseUrl: String, path: String): String =
        executeRequest(baseUrl, endpoint(baseUrl, path)).body

    private fun executeRequest(
        baseUrl: String,
        url: HttpUrl,
        method: String = "GET",
        jsonBody: String? = null,
        token: String? = null,
        refreshToken: String? = null,
        refreshRequest: Boolean = false,
        invalidCredentialsOnBadRequest: Boolean = false,
        signInRequest: Boolean = false,
    ): ApiResponse {
        check(url.toString().startsWith(baseUrl))
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                refreshToken?.takeIf { it.isNotBlank() }?.let { header("Cookie", "$REFRESH_COOKIE_NAME=$it") }
                if (jsonBody != null) method(method, jsonBody.toRequestBody(JSON_MEDIA_TYPE)) else method(method, null)
            }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = when {
                        invalidCredentialsOnBadRequest && response.code == 400 -> AppError.InvalidCredentials
                        signInRequest -> AppError.SignInFailed
                        refreshRequest && response.code == 401 -> AppError.SessionRefreshRejected
                        response.code == 401 -> AppError.Authentication
                        response.code == 403 -> AppError.Permission
                        else -> AppError.Server(response.code)
                    }
                    throw AppException(error)
                }
                return ApiResponse(response.body?.string().orEmpty(), response.headers)
            }
        } catch (error: AppException) {
            throw error
        } catch (_: IOException) {
            throw AppException(AppError.Network)
        }
    }

    private fun parseSignedInSession(baseUrl: String, response: ApiResponse): SignedInSession = parseJson(response.body) { root ->
        SignedInSession(
            user = parseUser(root.optJSONObject("user") ?: throw JSONException("missing user")),
            tokens = SessionTokens(
                accessToken = root.getString("accessToken"),
                accessTokenExpiresAt = Instant.parse(root.getString("accessTokenExpiresAt")),
                refreshToken = response.refreshToken(baseUrl),
            ),
        )
    }

    private fun parseUser(value: JSONObject): User {
        val name = value.getString("name")
        if (!USER_NAME.matches(name)) throw JSONException("invalid user resource")
        return User(name, value.optString("username"), value.optString("displayName"))
    }

    private data class ApiResponse(val body: String, val headers: Headers) {
        fun refreshToken(baseUrl: String): String {
            val url = baseUrl.toHttpUrl()
            // Memos' grpc-gateway may expose response metadata with this prefix instead of
            // forwarding it as a regular Set-Cookie header. Accept both wire formats.
            return listOf("Set-Cookie", "Grpc-Metadata-Set-Cookie")
                .asSequence()
                .flatMap { headers.values(it).asSequence() }
                .mapNotNull { Cookie.parse(url, it) }
                .firstOrNull { it.name == REFRESH_COOKIE_NAME }
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?: throw AppException(AppError.InvalidResponse)
        }
    }

    private fun Boolean?.orFalse(): Boolean = this ?: false

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
    } catch (error: AppException) {
        throw error
    } catch (_: Exception) {
        throw AppException(AppError.InvalidResponse)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    private companion object {
        const val REFRESH_COOKIE_NAME = "memos_refresh"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val USER_NAME = Regex("^users/[^/]+$")
        val MEMO_NAME = Regex("^memos/[^/]+$")
        val SPACE_NAME = Regex("^spaces/[^/]+$")
    }
}
