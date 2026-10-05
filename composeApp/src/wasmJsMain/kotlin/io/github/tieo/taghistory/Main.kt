package io.github.tieo.taghistory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import io.github.tieo.taghistory.data.storage.SettingsFactory
import io.github.tieo.taghistory.db.DatabaseDriverFactory
import io.github.tieo.taghistory.db.createDatabase
import io.github.tieo.taghistory.host.WasmAppHost
import io.github.tieo.taghistory.server.ServerClient
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import kotlinx.browser.document
import kotlinx.browser.window

/**
 * Browser entry. Opens the local database, asks the server it was served
 * from whether it holds an Apple session, and, when it does, pulls what the
 * server stored since the last visit before showing the map, so the first
 * frame already has every tag's latest position.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        var factories by remember { mutableStateOf<AppHostFactories?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var attempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(attempt) {
            error = null
            try {
                val db = createDatabase(DatabaseDriverFactory())
                val client = ServerClient(window.location.origin)
                val status = client.status()
                val host = WasmAppHost(db, SettingsFactory(), client, serverSignedIn = status.signedIn)
                if (status.signedIn) runCatching { host.replicator.pull() }
                factories = host.buildFactories(appVersion = "web")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e::class.simpleName
            }
        }
        val f = factories
        if (f != null) {
            App(factories = f)
        } else {
            TagHistoryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val message = error
                        if (message == null) {
                            CircularProgressIndicator()
                        } else {
                            Text("Can't reach the TagHistory server", style = MaterialTheme.typography.titleMedium)
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { attempt++ }) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}
