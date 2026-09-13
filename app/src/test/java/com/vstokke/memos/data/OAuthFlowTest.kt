package com.vstokke.memos.data

import com.vstokke.memos.domain.AppError
import com.vstokke.memos.domain.AppException
import com.vstokke.memos.domain.AuthProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class OAuthFlowTest {
    private val provider = AuthProvider(
        name = "identity-providers/authelia",
        title = "Authelia",
        clientId = "memos",
        authorizationUrl = "https://auth.example.com/authorize",
        scopes = listOf("openid", "profile", "email"),
    )

    @Test
    fun `authorization request uses state exact redirect and S256 PKCE`() {
        val start = OAuthFlow.start("https://memos.example.com", provider, nowEpochMillis = 1_000)
        val url = start.authorizationUrl.toHttpUrl()
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(start.pending.codeVerifier.encodeToByteArray()),
        )

        assertEquals("memos", url.queryParameter("client_id"))
        assertEquals(OAuthFlow.REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals(start.pending.state, url.queryParameter("state"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("openid profile email", url.queryParameter("scope"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(expectedChallenge, url.queryParameter("code_challenge"))
        assertNotEquals(start.pending.codeVerifier, url.queryParameter("code_challenge"))
        assertFalse(start.authorizationUrl.contains(start.pending.codeVerifier))
    }

    @Test
    fun `matching callback returns decoded authorization code`() {
        val start = OAuthFlow.start("https://memos.example.com", provider, nowEpochMillis = 1_000)
        val state = URLEncoder.encode(start.pending.state, StandardCharsets.UTF_8)
        val callback = "${OAuthFlow.REDIRECT_URI}?code=code%2Fvalue&state=$state"

        val code = OAuthFlow.authorizationCode(callback, start.pending, nowEpochMillis = 2_000)

        assertEquals("code/value", code)
    }

    @Test
    fun `callback rejects mismatched state and expired requests`() {
        val start = OAuthFlow.start("https://memos.example.com", provider, nowEpochMillis = 1_000)
        val wrongState = runCatching {
            OAuthFlow.authorizationCode(
                "${OAuthFlow.REDIRECT_URI}?code=value&state=wrong",
                start.pending,
                nowEpochMillis = 2_000,
            )
        }.exceptionOrNull()
        val expired = runCatching {
            OAuthFlow.authorizationCode(
                "${OAuthFlow.REDIRECT_URI}?code=value&state=${start.pending.state}",
                start.pending,
                nowEpochMillis = 1_000 + 10 * 60 * 1_000L + 1,
            )
        }.exceptionOrNull()

        assertTrue(wrongState is AppException && wrongState.error == AppError.SignInFailed)
        assertTrue(expired is AppException && expired.error == AppError.SignInFailed)
    }

    @Test
    fun `authorization endpoint must use HTTPS`() {
        val insecure = provider.copy(authorizationUrl = "http://auth.example.com/authorize")

        val error = runCatching { OAuthFlow.start("https://memos.example.com", insecure) }.exceptionOrNull()

        assertTrue(error is AppException && error.error == AppError.InvalidResponse)
    }
}
