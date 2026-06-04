package io.github.tieo.taghistory.host

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.tieo.taghistory.data.importer.AppleExportParser
import io.github.tieo.taghistory.data.repo.BeaconRepository
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OTV/Import"

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
