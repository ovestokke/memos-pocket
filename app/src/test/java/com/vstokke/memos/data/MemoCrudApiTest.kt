package com.vstokke.memos.data

import com.vstokke.memos.domain.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class MemoCrudApiTest {
    private lateinit var server: MockWebServer
    private val api = MemosApi()
    private val memoJson = """{"name":"memos/one","creator":"users/alice","state":"NORMAL","content":"Old","visibility":"PRIVATE","pinned":true,"space":"spaces/team","attachments":[{"name":"attachments/a"}],"futureField":{"keep":true},"reminderTime":"2026-05-01T12:00:00Z"}"""
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }
    private fun account(supported: Boolean = true) = Account(server.url("/").toString().trimEnd('/'), "private-token", "users/alice", "Alice", supported)
    private fun enqueue(json: String = memoJson) { server.enqueue(MockResponse().setBody(json)) }
    private fun base(): Memo { enqueue(); return api.getMemo(account(), "memos/one").also { server.takeRequest() } }

    @Test fun `content edit has precise mask and never echoes attachments space or unknown fields`() {
        val base = base(); enqueue()
        api.editMemo(account(), base, MemoEdit("New", base.visibility, base.reminderTime))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("content,update_time", request.requestUrl!!.queryParameter("updateMask"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(setOf("content"), body.keys().asSequence().toSet())
        assertEquals("New", body.getString("content"))
        assertEquals("spaces/team", base.space)
    }
    @Test fun `clear reminder masks field but omits timestamp`() {
        val base = base(); enqueue()
        api.editMemo(account(), base, MemoEdit(base.content, base.visibility, null))
        val request = server.takeRequest()
        assertEquals("reminder_time", request.requestUrl!!.queryParameter("updateMask"))
        assertFalse(JSONObject(request.body.readUtf8()).has("reminderTime"))
    }
    @Test fun `set reminder and visibility use lower camel fields`() {
        val base = base(); enqueue()
        val due = Instant.parse("2027-01-01T10:00:00Z")
        api.editMemo(account(), base, MemoEdit(base.content, "PUBLIC", due))
        val request = server.takeRequest()
        assertEquals("visibility,reminder_time", request.requestUrl!!.queryParameter("updateMask"))
        assertEquals(due.toString(), JSONObject(request.body.readUtf8()).getString("reminderTime"))
    }
    @Test fun `upstream rejects reminder mutation locally but allows unrelated edit`() {
        val base = base()
        val failure = runCatching { api.editMemo(account(false), base, MemoEdit(base.content, base.visibility, null)) }.exceptionOrNull()
        assertEquals(AppError.UnsupportedReminder, (failure as AppException).error)
        enqueue()
        api.editMemo(account(false), base, MemoEdit("New", base.visibility, base.reminderTime))
        assertEquals("content,update_time", server.takeRequest().requestUrl!!.queryParameter("updateMask"))
    }
    @Test fun `upstream creation omits reminder fields and permits explicit visibility`() {
        enqueue()
        api.createMemo(account(false), NewMemo("Text", null, "PROTECTED"))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("PROTECTED", body.getString("visibility"))
        assertFalse(body.has("reminderTime"))
        val failure = runCatching { api.createMemo(account(false), NewMemo("Text", Instant.now())) }.exceptionOrNull()
        assertEquals(AppError.UnsupportedReminder, (failure as AppException).error)
    }
    @Test fun `delete never forces associated data deletion`() {
        enqueue("{}")
        api.deleteMemo(account(), "memos/one")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/memos/one", request.path)
        assertNull(request.requestUrl!!.queryParameter("force"))
    }
    @Test fun `pin and archive use isolated masks`() {
        val base = base(); enqueue(); enqueue()
        api.setPinned(account(), base, false)
        api.setState(account(), base, "ARCHIVED")
        val pin = server.takeRequest(); val archive = server.takeRequest()
        assertEquals("pinned", pin.requestUrl!!.queryParameter("updateMask"))
        assertEquals(false, JSONObject(pin.body.readUtf8()).getBoolean("pinned"))
        assertEquals("state", archive.requestUrl!!.queryParameter("updateMask"))
        assertEquals("ARCHIVED", JSONObject(archive.body.readUtf8()).getString("state"))
    }
    @Test fun `feed pages request owner state ordering and opaque token`() {
        enqueue("""{"memos":[$memoJson],"nextPageToken":"opaque + token"}""")
        enqueue("{}")
        val first = api.listPage(account(), "ARCHIVED")
        val last = api.listPage(account(), "ARCHIVED", first.nextPageToken)
        assertTrue(last.memos.isEmpty()); assertNull(last.nextPageToken)
        val request = server.takeRequest().requestUrl!!
        assertEquals("ARCHIVED", request.queryParameter("state"))
        assertEquals("creator == \"users/alice\"", request.queryParameter("filter"))
        assertEquals("pinned desc, create_time desc", request.queryParameter("orderBy"))
        assertEquals("opaque + token", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
    }
    @Test fun `permission errors are not mislabeled as invalid token and body is redacted`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("secret private-token"))
        val error = runCatching { api.deleteMemo(account(), "memos/one") }.exceptionOrNull() as AppException
        assertEquals(AppError.Permission, error.error)
        assertFalse(error.toString().contains("private-token"))
    }
    @Test fun `unsafe resource names cannot alter endpoint`() {
        listOf("memos/..", "memos/%2e%2e", "memos/one?force=true", "users/alice", "memos/a/b").forEach {
            assertTrue(runCatching { api.getMemo(account(), it) }.exceptionOrNull() is AppException)
        }
        assertEquals(0, server.requestCount)
    }
    @Test fun `repeated reminder page tokens fail instead of looping`() {
        repeat(2) { enqueue("""{"memos":[],"nextPageToken":"repeat"}""") }
        val failure = runCatching { api.listAllReminders(account()) }.exceptionOrNull() as AppException
        assertEquals(AppError.InvalidResponse, failure.error)
        assertEquals(2, server.requestCount)
    }
}
