package io.github.tieo.taghistory.server

import com.russhwolf.settings.Settings
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The standalone app's optional link to a sync server: which server, the
 * provider guarding it, and the sign-in that lets background sync reach it.
 * The refresh token is the long-lived secret and is kept encrypted in
 * [blobs]; access tokens live in memory only and are renewed from it.
 * An authorization in progress is persisted too, because the app process
 * can die while the user is in the browser.
 */
class ServerConnection(
    private val settings: Settings,
    private val blobs: SecureBlobStore,
    private val oidc: OidcClient,
    private val engineClient: HttpClient,
    private val nowMs: () -> Long,
) {
    sealed interface State {
        data object Disconnected : State
        data class AwaitingBrowser(val serverUrl: String) : State
        data class Connected(val serverUrl: String) : State
        /** The provider refused the stored sign-in; reconnecting signs in again. */
        data class SignInExpired(val serverUrl: String) : State
    }

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<State> = _state.asStateFlow()

    private val tokenLock = Mutex()
    private var access: OidcTokens? = null

    /**
     * Starts connecting to [input] (a host name or URL). Returns the URL the
     * browser has to open to sign in, or null when the server needs no sign-in
     * and is connected already.
     */
    suspend fun connect(input: String): String? {
        val serverUrl = normalizeServerUrl(input)
        val endpoints = oidc.discover(serverUrl)
        if (endpoints == null) {
            store(serverUrl, endpoints = null, refreshToken = null)
            return null
        }
        val pending = oidc.begin(serverUrl, endpoints)
        settings.putString(KEY_PENDING, ServerClient.WireJson.encodeToString(PendingAuthorization.serializer(), pending))
        _state.value = State.AwaitingBrowser(serverUrl)
        return pending.browserUrl
    }

    /** Finishes the sign-in with the app redirect the browser delivered. */
    suspend fun complete(redirect: String) {
        val pending = settings.getStringOrNull(KEY_PENDING)
            ?.let { ServerClient.WireJson.decodeFromString(PendingAuthorization.serializer(), it) }
            ?: throw OidcException("No sign-in is waiting for an answer")
        try {
            val tokens = oidc.complete(pending, redirect)
            tokenLock.withLock { access = tokens }
            store(pending.serverUrl, pending.endpoints, tokens.refreshToken)
        } finally {
            settings.remove(KEY_PENDING)
            if (_state.value is State.AwaitingBrowser) _state.value = loadState()
        }
    }

    /** Leaves the browser sign-in, keeping whatever connection there was. */
    fun cancelSignIn() {
        settings.remove(KEY_PENDING)
        _state.value = loadState()
    }

    fun disconnect() {
        for (key in listOf(KEY_SERVER, KEY_ENDPOINTS, KEY_REFRESH, KEY_PENDING)) settings.remove(key)
        access = null
        _state.value = State.Disconnected
    }

    /** A client for the connected server, or null while there is none to use. */
    fun client(): ServerClient? {
        val current = _state.value as? State.Connected ?: return null
        val bearer = if (settings.hasKey(KEY_ENDPOINTS)) BearerSource { renew -> token(renew) } else null
        return ServerClient(current.serverUrl, engineClient, bearer)
    }

    private suspend fun token(renew: Boolean): String = tokenLock.withLock {
        val cached = access
        if (!renew && cached != null && cached.expiresAtMs - nowMs() > RENEW_MARGIN_MS) {
            return@withLock cached.accessToken
        }
        val endpoints = settings.getStringOrNull(KEY_ENDPOINTS)
            ?.let { ServerClient.WireJson.decodeFromString(OidcEndpoints.serializer(), it) }
            ?: throw OidcException("Not signed in to the sync server")
        val refreshToken = readRefreshToken() ?: throw expired("Not signed in to the sync server")
        val tokens = try {
            oidc.refresh(endpoints, refreshToken)
        } catch (e: SignInExpiredException) {
            throw expired(e.message ?: "The sign-in expired")
        }
        // The provider rotates refresh tokens: the old one is spent now.
        tokens.refreshToken?.let { writeRefreshToken(it) }
        access = tokens
        tokens.accessToken
    }

    private fun expired(message: String): SignInExpiredException {
        access = null
        settings.remove(KEY_REFRESH)
        settings.getStringOrNull(KEY_SERVER)?.let { _state.value = State.SignInExpired(it) }
        return SignInExpiredException(message)
    }

    private fun store(serverUrl: String, endpoints: OidcEndpoints?, refreshToken: String?) {
        settings.putString(KEY_SERVER, serverUrl)
        if (endpoints != null) {
            settings.putString(KEY_ENDPOINTS, ServerClient.WireJson.encodeToString(OidcEndpoints.serializer(), endpoints))
        } else {
            settings.remove(KEY_ENDPOINTS)
        }
        if (refreshToken != null) writeRefreshToken(refreshToken) else settings.remove(KEY_REFRESH)
        _state.value = State.Connected(serverUrl)
    }

    private fun loadState(): State {
        val server = settings.getStringOrNull(KEY_SERVER)
        val pending = settings.getStringOrNull(KEY_PENDING)
            ?.let { runCatching { ServerClient.WireJson.decodeFromString(PendingAuthorization.serializer(), it) }.getOrNull() }
        return when {
            pending != null -> State.AwaitingBrowser(pending.serverUrl)
            server == null -> State.Disconnected
            settings.hasKey(KEY_ENDPOINTS) && !settings.hasKey(KEY_REFRESH) -> State.SignInExpired(server)
            else -> State.Connected(server)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun writeRefreshToken(token: String) {
        settings.putString(KEY_REFRESH, Base64.encode(blobs.encrypt(token.encodeToByteArray(), BLOB_ALIAS)))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun readRefreshToken(): String? = settings.getStringOrNull(KEY_REFRESH)
        ?.let { blobs.decrypt(Base64.decode(it), BLOB_ALIAS).decodeToString() }

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_ENDPOINTS = "server_oidc_endpoints"
        private const val KEY_REFRESH = "server_refresh_token"
        private const val KEY_PENDING = "server_pending_authorization"
        private const val BLOB_ALIAS = "sync_server_refresh_token"
        /** Renew this long before expiry, so a token does not lapse mid-sync. */
        private const val RENEW_MARGIN_MS = 60_000L

        /** `example.org` and `https://example.org/anything` both become `https://example.org`. */
        fun normalizeServerUrl(input: String): String {
            val trimmed = input.trim()
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = io.ktor.http.Url(withScheme)
            require(url.host.isNotBlank()) { "Enter the server address" }
            return io.ktor.http.URLBuilder(protocol = url.protocol, host = url.host, port = url.port)
                .buildString().trimEnd('/')
        }
    }
}
