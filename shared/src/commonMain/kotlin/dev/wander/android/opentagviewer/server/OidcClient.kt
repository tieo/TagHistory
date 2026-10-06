package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.apple.crypto.secureRng
import io.github.tieo.taghistory.apple.crypto.sha256
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Endpoints of the OpenID provider guarding a sync server. */
@Serializable
data class OidcEndpoints(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorization: String,
    @SerialName("token_endpoint") val token: String,
    @SerialName("pushed_authorization_request_endpoint") val pushedAuthorization: String,
)

/** Tokens from one grant. [expiresAtMs] is computed on receipt. */
@Serializable
data class OidcTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
)

/** An authorization the browser is working on, kept until its redirect comes back. */
@Serializable
data class PendingAuthorization(
    val serverUrl: String,
    val endpoints: OidcEndpoints,
    val state: String,
    val codeVerifier: String,
    /** Where to send the browser. */
    val browserUrl: String,
)

class OidcException(message: String) : RuntimeException(message)

/**
 * Authorization-code client for a sync server behind a forward-auth proxy
 * (Authelia). The app is a public client: PKCE (S256) instead of a secret,
 * pushed authorization requests, and the code arrives through the server's
 * [ServerApi.OAUTH2_CALLBACK] page because the provider answers with a
 * form POST. Access tokens go to the proxy as `Authorization: Bearer`.
 */
class OidcClient(
    engineClient: HttpClient,
    private val nowMs: () -> Long,
) {
    // The login redirect is the answer discovery reads, so it must not be followed.
    private val http = engineClient.config {
        followRedirects = false
        expectSuccess = false
    }

    /**
     * Finds the provider guarding [serverUrl]: an unauthenticated request
     * is redirected to its login portal, whose origin is the issuer. Null
     * when the server answers without asking for a login.
     */
    suspend fun discover(serverUrl: String): OidcEndpoints? {
        val probe = http.get(serverUrl + ServerApi.STATUS)
        if (probe.status.isSuccess()) return null
        val location = probe.headers[HttpHeaders.Location]
            ?: throw OidcException("The server answered ${probe.status.value} without a login redirect")
        val portal = Url(location)
        val issuer = URLBuilder(protocol = portal.protocol, host = portal.host, port = portal.port).buildString()
            .trimEnd('/')
        val config = http.get("$issuer/.well-known/openid-configuration").json<OidcEndpoints>()
        if (config.issuer.trimEnd('/') != issuer) {
            throw OidcException("The login portal names a different issuer: ${config.issuer}")
        }
        return config
    }

    /** Pushes the authorization request and returns the browser URL to open. */
    suspend fun begin(serverUrl: String, endpoints: OidcEndpoints): PendingAuthorization {
        val verifier = randomToken(32)
        val state = randomToken(16)
        val pushed = http.submitForm(
            endpoints.pushedAuthorization,
            Parameters.build {
                append("client_id", CLIENT_ID)
                append("response_type", "code")
                append("response_mode", "form_post")
                append("redirect_uri", redirectUri(serverUrl))
                append("scope", SCOPES)
                append("state", state)
                append("code_challenge", base64Url(sha256(verifier.encodeToByteArray())))
                append("code_challenge_method", "S256")
            },
        ).json<PushedAuthorization>()
        val browserUrl = URLBuilder(endpoints.authorization).apply {
            parameters.append("client_id", CLIENT_ID)
            parameters.append("request_uri", pushed.requestUri)
        }.buildString()
        return PendingAuthorization(serverUrl, endpoints, state, verifier, browserUrl)
    }

    /** Redeems the code from the app redirect [redirect] for tokens. */
    suspend fun complete(pending: PendingAuthorization, redirect: String): OidcTokens {
        val params = Url(redirect).parameters
        params["error"]?.let { throw OidcException(params["error_description"] ?: it) }
        if (params["state"] != pending.state) throw OidcException("The sign-in answer belongs to another attempt")
        val code = params["code"] ?: throw OidcException("The sign-in answer carries no code")
        params["iss"]?.let {
            if (it.trimEnd('/') != pending.endpoints.issuer.trimEnd('/')) {
                throw OidcException("The sign-in answer comes from another issuer")
            }
        }
        return token(
            pending.endpoints,
            Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri(pending.serverUrl))
                append("client_id", CLIENT_ID)
                append("code_verifier", pending.codeVerifier)
            },
            previousRefresh = null,
        )
    }

    /** Trades [refreshToken] for new tokens; the provider rotates the refresh token. */
    suspend fun refresh(endpoints: OidcEndpoints, refreshToken: String): OidcTokens = token(
        endpoints,
        Parameters.build {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", CLIENT_ID)
        },
        previousRefresh = refreshToken,
    )

    private suspend fun token(endpoints: OidcEndpoints, form: Parameters, previousRefresh: String?): OidcTokens {
        val issuedAt = nowMs()
        val response = http.submitForm(endpoints.token, form)
        if (!response.status.isSuccess()) throw tokenError(response)
        val body = response.json<TokenResponse>()
        return OidcTokens(
            accessToken = body.accessToken,
            refreshToken = body.refreshToken ?: previousRefresh,
            expiresAtMs = issuedAt + body.expiresIn * 1000,
        )
    }

    private suspend fun tokenError(response: HttpResponse): RuntimeException {
        val text = response.bodyAsText()
        val error = runCatching { ServerClient.WireJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
        // invalid_grant: the refresh token was revoked, expired or already used.
        return if (error?.error == "invalid_grant") {
            SignInExpiredException(error.description ?: "The sign-in expired")
        } else {
            OidcException(error?.description ?: error?.error ?: "Token request failed: ${response.status.value}")
        }
    }

    private suspend inline fun <reified T> HttpResponse.json(): T {
        val text = bodyAsText()
        if (!status.isSuccess()) {
            val error = runCatching { ServerClient.WireJson.decodeFromString(ErrorResponse.serializer(), text) }
                .getOrNull()
            throw OidcException(error?.description ?: error?.error ?: "${call.request.url.encodedPath}: ${status.value}")
        }
        return ServerClient.WireJson.decodeFromString(text)
    }

    @Serializable
    private data class PushedAuthorization(@SerialName("request_uri") val requestUri: String)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private data class ErrorResponse(
        val error: String,
        @SerialName("error_description") val description: String? = null,
    )

    companion object {
        /** Registered with the provider as a public client. */
        const val CLIENT_ID = "taghistory-app"

        /**
         * `offline_access` brings the refresh token background sync lives on;
         * `authelia.bearer.authz` lets the proxy accept the access token.
         */
        const val SCOPES = "offline_access authelia.bearer.authz"

        fun redirectUri(serverUrl: String) = serverUrl + ServerApi.OAUTH2_CALLBACK

        private fun randomToken(bytes: Int): String =
            base64Url(ByteArray(bytes).also { secureRng().nextBytes(it) })

        @OptIn(ExperimentalEncodingApi::class)
        private fun base64Url(bytes: ByteArray): String =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
    }
}

/** The provider no longer honours the stored sign-in; the user has to sign in again. */
class SignInExpiredException(message: String) : RuntimeException(message)
