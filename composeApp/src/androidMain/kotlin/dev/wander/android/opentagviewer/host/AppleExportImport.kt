package io.github.tieo.taghistory.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import io.github.tieo.taghistory.data.importer.AppleExportParser
import io.github.tieo.taghistory.data.repo.BeaconRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OTV/Import"
private const val TAG_EXPORT = "OTV/Export"

/** Upper bound to keep a pathological zip from exploding RAM. */
private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024 // 4 MiB per file — plists are tiny
private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024 // 32 MiB total cap

/**
 * Read every entry from a zip stream into a `name -> bytes` map, with
 * per-entry + total-archive size caps. Caller closes the stream.
 */
fun readZipEntries(stream: InputStream): Map<String, ByteArray> {
    val out = HashMap<String, ByteArray>()
    var total = 0
    ZipInputStream(stream).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            if (entry.isDirectory) {
                zis.closeEntry()
                continue
            }
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var entryBytes = 0
            while (true) {
                val read = zis.read(chunk)
                if (read <= 0) break
                entryBytes += read
                total += read
                if (entryBytes > MAX_ENTRY_BYTES) {
                    throw IllegalStateException(
                        "Entry ${entry.name} exceeds $MAX_ENTRY_BYTES bytes",
                    )
                }
                if (total > MAX_TOTAL_BYTES) {
                    throw IllegalStateException(
                        "Archive exceeds $MAX_TOTAL_BYTES bytes uncompressed",
                    )
                }
                buf.write(chunk, 0, read)
            }
            out[entry.name] = buf.toByteArray()
            zis.closeEntry()
        }
    }
    return out
}

/**
 * Full import pipeline — reads the zip from [uri], parses it, and
 * writes rows through [beaconRepo]. Returns a user-readable status
 * message suitable for a toast / snackbar.
 */
suspend fun runAppleExportImport(
    context: Context,
    uri: Uri,
    beaconRepo: BeaconRepository,
): String = withContext(Dispatchers.IO) {
    Log.i(TAG, "runAppleExportImport start uri=$uri")
    val started = System.currentTimeMillis()
    try {
        val stream = context.contentResolver.openInputStream(uri)
        if (stream == null) {
            Log.w(TAG, "openInputStream returned null for $uri")
            return@withContext "Could not open archive"
        }
        val entries = stream.use { readZipEntries(it) }
        Log.i(TAG, "zip parsed entries=${entries.size} keys=${entries.keys.take(8)}")
        when (val parsed = AppleExportParser.parse(entries)) {
            is AppleExportParser.ParseResult.Err -> {
                Log.w(TAG, "AppleExportParser err: ${parsed.message}")
                "Import failed: ${parsed.message}"
            }
            is AppleExportParser.ParseResult.Ok -> {
                Log.i(TAG, "AppleExportParser ok imported=${parsed.imported}")
                beaconRepo.addNewImport(parsed.data)
                Log.i(TAG, "DB write done in ${System.currentTimeMillis() - started} ms")
                "Imported ${parsed.imported} beacon${if (parsed.imported == 1) "" else "s"}"
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "runAppleExportImport threw", t)
        "Import failed: ${t.message ?: t::class.simpleName}"
    }
}

/**
 * Build a TagHistory-compatible zip of the given beacons and hand it
 * off via ACTION_SEND so the system chooser can deliver it wherever
 * the user wants (Files, Drive, Signal, …). Reuses the plist blobs
 * we already store in the DB — no re-derivation, no Apple servers,
 * fully offline.
 *
 * Returns a user-readable status line for the bottom-bar toast.
 */
suspend fun runExportSelected(
    context: Context,
    beaconIds: List<String>,
    beaconRepo: BeaconRepository,
): String = withContext(Dispatchers.IO) {
    Log.i(TAG_EXPORT, "runExportSelected ids=${beaconIds.size}")
    if (beaconIds.isEmpty()) return@withContext "Nothing selected"
    try {
        val owned = beaconIds.mapNotNull { beaconRepo.getById(it) }
        val withContent = owned.filter { !it.ownedBeaconInfo?.content.isNullOrBlank() }
        if (withContent.isEmpty()) {
            return@withContext "Export failed: no plist data on disk for selected tags"
        }

        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val outFile = File(outDir, "taghistory-export-$ts.zip")
        outFile.outputStream().use { fos ->
            ZipOutputStream(fos).use { zos ->
                // OPENTAGVIEWER.yml at root — matches the format
                // AppleExportParser expects so the round-trip works.
                zos.putNextEntry(ZipEntry("OPENTAGVIEWER.yml"))
                zos.write(
                    buildString {
                        appendLine("version: '1'")
                        appendLine("exportTimestamp: $ts")
                        appendLine("sourceUser: TagHistory")
                        appendLine("via: TagHistory in-app export")
                    }.encodeToByteArray(),
                )
                zos.closeEntry()

                for (b in withContent) {
                    val plistBytes = b.ownedBeaconInfo?.content?.encodeToByteArray()
                        ?: continue
                    zos.putNextEntry(ZipEntry("OwnedBeacons/${b.beaconId}.plist"))
                    zos.write(plistBytes)
                    zos.closeEntry()
                    val naming = b.beaconNamingRecord
                    if (naming != null && !naming.content.isNullOrBlank()) {
                        zos.putNextEntry(
                            ZipEntry(
                                "BeaconNamingRecord/${b.beaconId}/${naming.id}.plist",
                            ),
                        )
                        zos.write(naming.content!!.encodeToByteArray())
                        zos.closeEntry()
                    }
                }
            }
        }
        Log.i(TAG_EXPORT, "wrote ${outFile.length()} bytes -> $outFile")

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile,
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, outFile.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(share, "Share TagHistory export")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        "Shared ${withContent.size} tag${if (withContent.size == 1) "" else "s"} (${outFile.length() / 1024} KB) via system share sheet"
    } catch (t: Throwable) {
        Log.e(TAG_EXPORT, "export threw", t)
        "Export failed: ${t.message ?: t::class.simpleName}"
    }
}
