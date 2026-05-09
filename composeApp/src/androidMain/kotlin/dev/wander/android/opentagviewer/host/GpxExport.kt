package io.github.tieo.taghistory.host

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.tieo.taghistory.ui.history.HistoryPoint
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val FILE_EXT = ".gpx"
private const val MIME = "application/gpx+xml"

/**
 * Builds a GPX 1.1 track file from the day's points and fires
 * Intent.ACTION_SEND so the user can pick any share target (mail,
 * Drive, OsmAnd, etc). Files live in `${cacheDir}/exports/` and are
 * exposed via the manifest's FileProvider; nothing is left in public
 * storage.
 */
fun shareDayAsGpx(
    context: Context,
    title: String,
    dayLabel: String,
    points: List<HistoryPoint>,
) {
    if (points.isEmpty()) return
    val gpx = buildGpx(title, dayLabel, points)
    val safeName = sanitize("${title}_${dayLabel}") + FILE_EXT
    val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(exportsDir, safeName).apply { writeText(gpx) }
    val authority = "${context.packageName}.fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "$title — $dayLabel")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Share GPX").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun buildGpx(title: String, dayLabel: String, points: List<HistoryPoint>): String {
    val sb = StringBuilder(64 + points.size * 96)
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    sb.append(
        """<gpx version="1.1" creator="TagHistory" xmlns="http://www.topografix.com/GPX/1/1">"""
    ).append('\n')
    sb.append("  <metadata>").append('\n')
    sb.append("    <name>").append(escape("$title — $dayLabel")).append("</name>").append('\n')
    sb.append("    <time>")
        .append(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
        .append("</time>").append('\n')
    sb.append("  </metadata>").append('\n')
    sb.append("  <trk>").append('\n')
    sb.append("    <name>").append(escape("$title — $dayLabel")).append("</name>").append('\n')
    sb.append("    <trkseg>").append('\n')
    for (p in points.sortedBy { it.timestampMs }) {
        val instant = Instant.ofEpochMilli(p.timestampMs).atOffset(ZoneOffset.UTC)
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant)
        sb.append("      <trkpt lat=\"").append(p.latitude)
            .append("\" lon=\"").append(p.longitude).append("\">").append('\n')
        sb.append("        <time>").append(iso).append("</time>").append('\n')
        // GPX <hdop> is unitless; we ship the raw accuracy as an
        // <extensions> child instead so consumers that ignore it still
        // parse the file correctly.
        sb.append("        <extensions><horizontal_accuracy_m>")
            .append(p.horizontalAccuracy)
            .append("</horizontal_accuracy_m></extensions>").append('\n')
        sb.append("      </trkpt>").append('\n')
    }
    sb.append("    </trkseg>").append('\n')
    sb.append("  </trk>").append('\n')
    sb.append("</gpx>").append('\n')
    return sb.toString()
}

private fun sanitize(s: String): String =
    s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(64).ifEmpty { "history" }

private fun escape(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
