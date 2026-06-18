package io.github.tieo.taghistory.host

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-global registry of point-in-time state snapshots, read over adb
 * via the debug-only DebugDumpProvider:
 *
 *   adb shell dumpsys activity provider io.github.tieo.taghistory/.DebugDumpProvider
 *
 * Logs are an event stream; this is a state view. A registered provider is
 * a `() -> String` evaluated lazily at dump time, so it always reflects the
 * live object (e.g. a ViewModel's current StateFlow value), never a stale
 * copy. Holding only a lambda means the registry adds no exposure in
 * release builds where the provider isn't merged into the manifest.
 */
object DebugStateRegistry {
    private val providers = ConcurrentHashMap<String, () -> String>()

    fun register(name: String, snapshot: () -> String) {
        providers[name] = snapshot
    }

    fun unregister(name: String) {
        providers.remove(name)
    }

    fun dump(): String = buildString {
        appendLine("=== TagHistory debug state @ ${System.currentTimeMillis()} ===")
        if (providers.isEmpty()) {
            appendLine("(nothing registered — no logged-in MapViewModel live yet)")
            return@buildString
        }
        for ((name, snapshot) in providers) {
            appendLine("[$name]")
            appendLine(runCatching { snapshot() }.getOrElse { "  ERROR reading: ${it.message}" })
        }
    }
}
