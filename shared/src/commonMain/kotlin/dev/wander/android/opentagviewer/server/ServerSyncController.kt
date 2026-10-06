package io.github.tieo.taghistory.server

import com.russhwolf.settings.Settings
import io.github.tieo.taghistory.apple.http.createPlatformHttpClient
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.sync.SyncEvent
import io.github.tieo.taghistory.sync.SyncLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Settings screen shows about the optional sync server, and the
 * actions behind it. Background sync calls [exchangeIfConnected] after each
 * Apple fetch; a failure there is logged and never fails the fetch, since
 * the app works without the server.
 */
class ServerSyncController(
    private val connection: ServerConnection,
    private val exchange: ServerReportExchange,
    private val scope: CoroutineScope,
    /** Opens the provider's sign-in page outside the app. */
    private val openBrowser: (String) -> Unit,
    private val nowMs: () -> Long,
) {
    data class UiState(
        val connection: ServerConnection.State,
        val busy: Boolean = false,
        /** Outcome of the last exchange or failed action, for one line under the controls. */
        val message: String? = null,
        val lastExchangeAtMs: Long? = null,
    )

    private val _state = MutableStateFlow(UiState(connection.state.value))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        scope.launch { connection.state.collect { c -> _state.update { it.copy(connection = c) } } }
    }

    fun connect(input: String) = act {
        val browserUrl = connection.connect(input)
        if (browserUrl != null) openBrowser(browserUrl) else exchangeNow()
    }

    /** The browser handed back the provider's answer through the app redirect. */
    fun onRedirect(redirect: String) = act {
        connection.complete(redirect)
        exchangeNow()
    }

    fun cancelSignIn() = connection.cancelSignIn()

    fun disconnect() {
        connection.disconnect()
        _state.update { it.copy(message = null, lastExchangeAtMs = null) }
    }

    fun syncNow() = act { exchangeNow() }

    /** One exchange when a server is connected; returns a log line, or null without a server. */
    suspend fun exchangeIfConnected(): String? {
        val client = connection.client() ?: return null
        return try {
            val result = exchange.exchange(client)
            val line = "Sync server: sent ${result.uploaded} new, received ${result.downloaded} new reports"
            SyncLog.record(SyncEvent.Kind.INFO, line)
            _state.update { it.copy(message = describe(result), lastExchangeAtMs = nowMs()) }
            line
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val line = "Sync server failed: ${e.message ?: e::class.simpleName}"
            SyncLog.record(SyncEvent.Kind.RUNG_FAIL, line)
            _state.update { it.copy(message = e.message ?: "Sync failed") }
            line
        }
    }

    private suspend fun exchangeNow() {
        val client = connection.client() ?: return
        val result = exchange.exchange(client)
        _state.update { it.copy(message = describe(result), lastExchangeAtMs = nowMs()) }
    }

    private fun act(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(message = e.message ?: e::class.simpleName) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun describe(result: ServerReportExchange.Result) =
        "Sent ${result.uploaded} and received ${result.downloaded} new reports"

    companion object {
        /** Wires the controller over the platform HTTP engine. */
        fun create(
            db: TagHistoryDatabase,
            connectionSettings: Settings,
            cursorSettings: Settings,
            blobs: SecureBlobStore,
            scope: CoroutineScope,
            openBrowser: (String) -> Unit,
            nowMs: () -> Long,
        ): ServerSyncController {
            val http = createPlatformHttpClient()
            return ServerSyncController(
                connection = ServerConnection(connectionSettings, blobs, OidcClient(http, nowMs), http, nowMs),
                exchange = ServerReportExchange(db, cursorSettings),
                scope = scope,
                openBrowser = openBrowser,
                nowMs = nowMs,
            )
        }
    }
}
