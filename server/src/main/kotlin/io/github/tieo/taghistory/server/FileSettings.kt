package io.github.tieo.taghistory.server

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * A [Settings] store persisted to a properties file. Every write rewrites the
 * file through a temp file and an atomic rename, so a crash mid-write leaves
 * the previous complete file instead of a truncated one.
 */
fun fileSettings(file: File): Settings {
    val properties = Properties()
    if (file.isFile) file.inputStream().use { properties.load(it) }
    return PropertiesSettings(properties) { updated ->
        synchronized(file) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.outputStream().use { updated.store(it, null) }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
