package io.github.tieo.taghistory.data.importer

import io.github.tieo.taghistory.data.model.ImportData
import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.Import
import io.github.tieo.taghistory.db.OwnedBeacons
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Pure-Kotlin port of the legacy [AppleZipImporterUtil] semantics.
 *
 * Takes already-extracted zip entries (a file-name → bytes map) and
 * produces an [ImportData] suitable for
 * [io.github.tieo.taghistory.data.repo.BeaconRepository.addNewImport].
 *
 * Zip I/O is intentionally pushed to the platform layer: Android uses
 * `java.util.zip`, desktop likewise, iOS would need its own reader if we
 * ever wire import there. That keeps this parser unit-testable without
 * a real zip file.
 */
@OptIn(ExperimentalTime::class)
object AppleExportParser {

    /** Filename matchers — copied from the legacy Java regex verbatim. */
    private val UUID_PATTERN = "[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}"
    private val EXPORT_INFO_FILE = Regex("^OPENTAGVIEWER\\.yml$")
    private val OWNED_BEACON_FILE = Regex("^OwnedBeacons/($UUID_PATTERN)\\.plist$")
    private val BEACON_NAMING_FILE = Regex(
        "^BeaconNamingRecord/($UUID_PATTERN)/($UUID_PATTERN)\\.plist$",
    )

    sealed class ParseResult {
        data class Ok(val data: ImportData, val imported: Int) : ParseResult()
        data class Err(val message: String) : ParseResult()
    }

    /**
     * Parse a map of zipped-entry → content. Returns [ParseResult.Ok] on
     * success or [ParseResult.Err] with a user-readable reason.
     */
    fun parse(
        entries: Map<String, ByteArray>,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): ParseResult {
        var exportInfoYaml: String? = null
        val ownedBeacons = mutableMapOf<String, String>()
        // beaconId -> (recordId, content). On conflict we keep the first
        // entry (the legacy code preferred the newer timestamp, but that
        // needed NSKeyedArchiver parsing we don't have in common).
        val namingRecords = mutableMapOf<String, Pair<String, String>>()

        for ((name, bytes) in entries) {
            when {
                EXPORT_INFO_FILE.matches(name) ->
                    exportInfoYaml = bytes.decodeToString()

                OWNED_BEACON_FILE.matches(name) -> {
                    val match = OWNED_BEACON_FILE.matchEntire(name)!!
                    val beaconId = match.groupValues[1]
                    ownedBeacons[beaconId] = bytes.decodeToString()
                }

                BEACON_NAMING_FILE.matches(name) -> {
                    val match = BEACON_NAMING_FILE.matchEntire(name)!!
                    val beaconId = match.groupValues[1]
                    val recordId = match.groupValues[2]
                    if (beaconId !in namingRecords) {
                        namingRecords[beaconId] = recordId to bytes.decodeToString()
                    }
                }
            }
        }

        if (exportInfoYaml == null || exportInfoYaml.isBlank()) {
            return ParseResult.Err("OPENTAGVIEWER.yml is missing from the archive")
        }

        // Inner-join owned beacons with naming records — drop mismatches.
        val common = ownedBeacons.keys.intersect(namingRecords.keys)
        if (common.isEmpty()) {
            return ParseResult.Err(
                "No beacons found — archive contained no matching " +
                    "OwnedBeacons + BeaconNamingRecord pairs",
            )
        }

        val yaml = parseExportInfo(exportInfoYaml)
            ?: return ParseResult.Err("OPENTAGVIEWER.yml could not be parsed")

        val importRow = Import(
            id = 0L, // assigned by the DB
            version = yaml.version,
            imported_at = nowMs,
            exported_at = yaml.exportTimestamp ?: 0L,
            source_user = yaml.sourceUser,
            via = yaml.via,
        )

        val ownedRows = common.map { beaconId ->
            OwnedBeacons(
                id = beaconId,
                import_id = null,
                content = ownedBeacons[beaconId],
                version = yaml.version,
                is_removed = false,
            )
        }

        val namingRows = common.map { beaconId ->
            val (recordId, content) = namingRecords[beaconId]!!
            BeaconNamingRecord(
                id = recordId,
                import_id = null,
                version = yaml.version,
                content = content,
                is_removed = false,
            )
        }

        return ParseResult.Ok(
            ImportData(
                anImport = importRow,
                ownedBeacons = ownedRows,
                beaconNamingRecords = namingRows,
            ),
            imported = common.size,
        )
    }

    internal data class ExportInfo(
        val version: String?,
        val exportTimestamp: Long?,
        val sourceUser: String?,
        val via: String?,
    )

    /**
     * Minimal YAML subset parser. The export file is flat `key: value`
     * pairs; no nesting, no lists. Matching Jackson's behaviour is
     * overkill and would pull a 2MB dep into :shared. We trim quoting and
     * accept integers for `exportTimestamp`.
     */
    internal fun parseExportInfo(yaml: String): ExportInfo? {
        var version: String? = null
        var exportTimestamp: Long? = null
        var sourceUser: String? = null
        var via: String? = null

        yaml.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val sep = line.indexOf(':')
            if (sep <= 0) return@forEach
            val key = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            when (key) {
                "version" -> version = value
                "exportTimestamp" -> exportTimestamp = value.toLongOrNull()
                "sourceUser" -> sourceUser = value
                "via" -> via = value
            }
        }

        // A well-formed archive always has at least `version`. Reject
        // everything-null as a defensive check against byte garbage that
        // happened to parse as empty strings.
        if (version == null && exportTimestamp == null && sourceUser == null && via == null) {
            return null
        }
        return ExportInfo(version, exportTimestamp, sourceUser, via)
    }
}
