package io.github.tieo.taghistory

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import io.github.tieo.taghistory.host.DebugStateRegistry
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * Debug-only state-extraction surface. Does no content work — it exists
 * solely so `dump()` can be reached over adb:
 *
 *   adb shell dumpsys activity provider io.github.tieo.taghistory/.DebugDumpProvider
 *
 * This prints a point-in-time snapshot of [DebugStateRegistry] (the live
 * MapViewModel UI state). Unlike logcat, it is queried on demand and is
 * never truncated by the ring buffer. Merged only into the debug manifest,
 * so it ships in no release build.
 */
class DebugDumpProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println(DebugStateRegistry.dump())
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
