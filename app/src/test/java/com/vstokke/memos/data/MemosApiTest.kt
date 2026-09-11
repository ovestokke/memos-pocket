package com.vstokke.memos.data

import com.vstokke.memos.domain.Account
import com.vstokke.memos.domain.AppError
import com.vstokke.memos.domain.AppException
import com.vstokke.memos.domain.NewMemo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class MemosApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MemosApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = MemosApi()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `current user reads wrapper and sends bearer token`() {
        server.enqueue(MockResponse().setBody("""{"user":{"name":"users/alice","username":"alice","displayName":"Alice"}}"""))

        val user = api.currentUser(server.url("/").toString().trimEnd('/'), "private-token")

        assertEquals("users/alice", user.name)
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/me", request.path)
        assertEquals("Bearer private-token", request.getHeader("Authorization"))
    }

    @Test
    fun `fork capability enables synchronized reminders`() {
        server.enqueue(MockResponse().setBody("""{"memoReminderTimeSupported":true}"""))

        val supported = api.supportsMemoReminderTime(server.url("/").toString().trimEnd('/'), "private-token")

        assertTrue(supported)
        val request = server.takeRequest()
        assertEquals("/api/v1/instance/profile", request.path)
        assertEquals("Bearer private-token", request.getHeader("Authorization"))
    }

    @Test
    fun `missing fork capability keeps upstream reminders disabled`() {
        server.enqueue(MockResponse().setBody("""{"version":"0.26.0"}"""))

        val supported = api.supportsMemoReminderTime(server.url("/").toString().trimEnd('/'), "private-token")

        assertFalse(supported)
    }

    @Test
    fun `reminder inventory follows every page and filters owner`() {
        server.enqueue(
            MockResponse().setBody(
                """{"memos":[{"name":"memos/one","content":"First","snippet":"First","visibility":"PRIVATE","createTime":"2026-01-01T10:00:00Z","reminderTime":"2026-01-02T10:00:00Z"}],"nextPageToken":"next token"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"memos":[{"name":"memos/two","content":"No reminder","visibility":"PRIVATE","createTime":"2026-01-01T09:00:00Z"}]}""",
            ),
        )
        val account = account()

        val reminders = api.listAllReminders(account)

        assertEquals(listOf("memos/one"), reminders.map { it.memoName })
        val first = server.takeRequest().requestUrl!!
        val second = server.takeRequest().requestUrl!!
        assertEquals("creator == \"users/alice\"", first.queryParameter("filter"))
        assertEquals("next token", second.queryParameter("pageToken"))
        assertEquals("NORMAL", second.queryParameter("state"))
    }

    @Test
    fun `spaces are listed across pages and sorted by title`() {
        server.enqueue(MockResponse().setBody("""{"spaces":[{"name":"spaces/z","title":"Zeta","description":"Z"}],"nextPageToken":"next"}"""))
        server.enqueue(MockResponse().setBody("""{"spaces":[{"name":"spaces/a","title":"Alpha"}]}"""))

        val spaces = api.listSpaces(account())

        assertEquals(listOf("Alpha", "Zeta"), spaces.map { it.title })
        assertEquals("1000", server.takeRequest().requestUrl!!.queryParameter("pageSize"))
        assertEquals("next", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
    }

    @Test
    fun `space feed uses scope and does not restrict results to current creator`() {
        server.enqueue(MockResponse().setBody("""{"memos":[]}"""))

        api.listPage(account(), space = "spaces/team")

        val request = server.takeRequest().requestUrl!!
        assertEquals("spaces/team", request.queryParameter("space"))
        assertEquals(null, request.queryParameter("filter"))
    }

    @Test
    fun `space memo creation sends placement and space visibility`() {
        server.enqueue(
            MockResponse().setBody(
                """{"name":"memos/new","content":"Together","state":"NORMAL","visibility":"SPACE","space":"spaces/team","createTime":"2026-04-01T10:00:00Z"}""",
            ),
        )

        api.createMemo(account(), NewMemo("Together", null, "SPACE", "spaces/team"))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"visibility\":\"SPACE\""))
        assertTrue(body.contains("\"space\":\"spaces/team\""))
    }

    @Test
    fun `create sends private normal memo and checks reminder readback`() {
        val due = Instant.parse("2026-04-05T12:00:00Z")
        server.enqueue(
            MockResponse().setBody(
                """{"name":"memos/new","content":"Remember this","state":"NORMAL","visibility":"PRIVATE","createTime":"2026-04-01T10:00:00Z","reminderTime":"2026-04-05T12:00:00Z"}""",
            ),
        )

        val result = api.createMemo(account(), NewMemo("Remember this", due))

        assertTrue(result.reminderPreserved)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"visibility\":\"PRIVATE\""))
        assertTrue(body.contains("\"state\":\"NORMAL\""))
        assertTrue(body.contains("\"reminderTime\":\"2026-04-05T12:00:00Z\""))
    }

    @Test
    fun `authentication error does not expose response body`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private-token should never escape"))

        val error = runCatching { api.currentUser(server.url("/").toString().trimEnd('/'), "private-token") }.exceptionOrNull()

        assertTrue(error is AppException)
        assertEquals(AppError.Authentication, (error as AppException).error)
        assertFalse(error.toString().contains("private-token"))
    }

    private fun account() = Account(
        baseUrl = server.url("/").toString().trimEnd('/'),
        token = "private-token",
        userName = "users/alice",
        displayName = "Alice",
        supportsMemoReminderTime = true,
    )
}
