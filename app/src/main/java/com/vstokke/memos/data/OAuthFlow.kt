package com.vstokke.memos.data

import com.vstokke.memos.BuildConfig
import com.vstokke.memos.domain.AppError
import com.vstokke.memos.domain.AppException
import com.vstokke.memos.domain.AuthProvider
import com.vstokke.memos.domain.OAuthStart
import com.vstokke.memos.domain.PendingOAuth
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object OAuthFlow {
    val REDIRECT_URI = "${BuildConfig.APPLICATION_ID}:/oauth2redirect"
    private const val MAX_AGE_MILLIS = 10 * 60 * 1000L
    private val random = SecureRandom()

    fun start(baseUrl: String, provider: AuthProvider, nowEpochMillis: Long = System.currentTimeMillis()): OAuthStart {
        val authorizationEndpoint = provider.authorizationUrl.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
            ?: throw AppException(AppError.InvalidResponse)
        if (provider.clientId.isBlank() || provider.name.isBlank() || provider.scopes.isEmpty()) {
            throw AppException(AppError.InvalidResponse)
        }

        val state = randomValue()
        val verifier = randomValue()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()),
        )
        val url = authorizationEndpoint.newBuilder()
            .addQueryParameter("client_id", provider.clientId)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("state", state)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", provider.scopes.joinToString(" "))
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build()

        return OAuthStart(
            authorizationUrl = url.toString(),
            pending = PendingOAuth(baseUrl, provider.name, state, verifier, nowEpochMillis),
        )
    }

    fun authorizationCode(
        callbackUrl: String,
        pending: PendingOAuth,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): String {
        val callback = try {
            URI(callbackUrl)
        } catch (_: Exception) {
            throw AppException(AppError.SignInFailed)
        }
        if (
            callback.scheme != BuildConfig.APPLICATION_ID || callback.rawAuthority != null ||
            callback.path != "/oauth2redirect" || callback.fragment != null
        ) {
            throw AppException(AppError.SignInFailed)
        }
        val age = nowEpochMillis - pending.createdAtEpochMillis
        if (age !in 0..MAX_AGE_MILLIS) throw AppException(AppError.SignInFailed)
        val pairs = callback.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { entry ->
            val key = entry.substringBefore('=')
            decode(key) to decode(entry.substringAfter('=', ""))
        }
        if (pairs.map { it.first }.distinct().size != pairs.size) throw AppException(AppError.SignInFailed)
        val parameters = pairs.toMap()
        if (parameters["state"] != pending.state || parameters.containsKey("error")) {
            throw AppException(AppError.SignInFailed)
        }
        return parameters["code"]?.takeIf { it.isNotBlank() } ?: throw AppException(AppError.SignInFailed)
    }

    private fun randomValue(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        throw AppException(AppError.SignInFailed)
    }
}
