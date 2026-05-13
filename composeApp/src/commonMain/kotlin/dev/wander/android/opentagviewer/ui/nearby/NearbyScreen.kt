package io.github.tieo.taghistory.ui.nearby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * "Nearby" screen — live RSSI per owned AirTag while the phone is in BLE
 * range. Pure-Compose; the VM owns the platform scan integration.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun NearbyScreen(
    viewModel: NearbyViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.onStart()
        onDispose { viewModel.onStop() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(state.scanState, state.uwbAvailable)

        if (state.tags.isEmpty()) {
            Text(
                "No tags imported yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val nowMs = Clock.System.now().toEpochMilliseconds()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.tags, key = { it.beaconId }) { tag ->
                val hit = state.hits[tag.beaconId]
                NearbyCard(tag = tag, hit = hit, nowMs = nowMs)
            }
        }
    }
}

@Composable
private fun Header(state: ScanState, uwbAvailable: Boolean) {
    val (label, color) = when (state) {
        ScanState.IDLE -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
        ScanState.STARTING -> "Starting scan…" to MaterialTheme.colorScheme.onSurfaceVariant
        ScanState.SCANNING -> "Scanning" to MaterialTheme.colorScheme.primary
        ScanState.PERMISSION_DENIED -> "Grant Nearby Devices permission" to MaterialTheme.colorScheme.error
        ScanState.BLUETOOTH_OFF -> "Turn Bluetooth on" to MaterialTheme.colorScheme.error
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Nearby",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
        if (uwbAvailable) {
            Spacer(Modifier.height(2.dp))
            Text(
                "UWB radio detected — precision finding will land in a future build.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun NearbyCard(tag: OwnedTagInfo, hit: NearbyHit?, nowMs: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hit != null && (nowMs - hit.seenAtMs) < 8_000L) {
                MaterialTheme.colorScheme.primaryContainer
            } else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val emoji = tag.emoji
                if (!emoji.isNullOrBlank()) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    tag.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    proximity(hit, nowMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            RssiBar(hit?.smoothedRssi)
            Spacer(Modifier.height(4.dp))
            Text(
                meta(hit, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RssiBar(rssi: Int?) {
    // Map [-100, -30] -> [0, 1]. Anything weaker than -100 is essentially
    // out of range; anything stronger than -30 is touching the phone.
    val fraction = rssi?.let {
        ((it + 100).coerceIn(0, 70).toFloat() / 70f)
    } ?: 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .background(
                    when {
                        fraction >= 0.7f -> Color(0xFF22C55E)
                        fraction >= 0.4f -> Color(0xFFEAB308)
                        fraction >= 0.15f -> Color(0xFFF97316)
                        else -> Color(0xFF94A3B8)
                    },
                ),
        )
    }
}

private fun proximity(hit: NearbyHit?, nowMs: Long): String {
    if (hit == null) return "searching…"
    val age = nowMs - hit.seenAtMs
    if (age > 12_000) return "out of range"
    val m = approxMeters(hit.smoothedRssi)
    return "~${m.roundToInt()} m"
}

private fun meta(hit: NearbyHit?, nowMs: Long): String {
    if (hit == null) return "Not seen this session"
    val age = ((nowMs - hit.seenAtMs) / 1000).coerceAtLeast(0)
    return "RSSI ${hit.smoothedRssi} dBm · seen ${age}s ago · ${hit.keyType.lowercase()}"
}

private fun approxMeters(rssi: Int): Double {
    // Path-loss with txPower ≈ -59 dBm at 1 m and n = 2.5 (indoor).
    val txPower = -59.0
    val n = 2.5
    val ratio = (txPower - rssi) / (10.0 * n)
    return 10.0.pow(ratio)
}

