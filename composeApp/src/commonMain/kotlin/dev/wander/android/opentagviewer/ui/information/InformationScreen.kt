package io.github.tieo.taghistory.ui.information

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.tieo.taghistory.ui.nav.PushedScreenScaffold

@Composable
fun InformationScreen(
    versionName: String,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PushedScreenScaffold(
        title = "About",
        onBack = onBack,
        modifier = modifier,
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("TagHistory", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Version $versionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "A community-built viewer for Apple FindMy beacons. " +
                            "Uses an on-device anisette bridge and your Apple " +
                            "credentials to fetch location reports.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text(
                "Links",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    LinkRow("Developer website", tag = "btn_developer_website") { onOpenUrl(DEVELOPER_URL) }
                    LinkRow("Source code", tag = "btn_source_code") { onOpenUrl(SOURCE_URL) }
                    LinkRow("License", tag = "btn_license") { onOpenUrl(LICENSE_URL) }
                }
            }

            Text(
                "Map attributions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Light basemap: CartoDB Voyager, © CARTO, © OpenStreetMap contributors (ODbL).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Dark basemap: CartoDB Dark Matter, © CARTO, © OpenStreetMap contributors.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Satellite basemap: © Esri, Maxar, Earthstar Geographics, GIS User Community.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Rendered with MapLibre GL.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, tag: String? = null, onClick: () -> Unit) {
    val mod = if (tag != null) Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(tag)
              else Modifier.fillMaxWidth().padding(horizontal = 8.dp)
    TextButton(
        onClick = onClick,
        modifier = mod,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
        )
    }
}

private const val DEVELOPER_URL = "https://github.com/tieo"
private const val SOURCE_URL = "https://github.com/tieo/TagHistory"
private const val LICENSE_URL = "https://github.com/tieo/TagHistory/blob/main/LICENSE"
