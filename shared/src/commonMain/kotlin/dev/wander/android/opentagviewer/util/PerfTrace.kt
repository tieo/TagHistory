package io.github.tieo.taghistory.util

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Tiny tracing helper for ad-hoc latency profiling. Each call writes a
 * single line via println — on Android that lands in logcat tagged
 * `System.out`. Filter with: adb logcat | grep PERF
 *
 * Use `start("history-open")` to begin a flow and `mark("loaded-cache")`
 * for each interesting waypoint; the printer reports both the cumulative
 * time since `start` and the delta since the previous `mark` so a slow
 * phase shows up immediately.
 *
 * Not for production telemetry; intentionally global / single-flow at a
 * time. Remove the calls once a perf bug is fixed.
 */
@OptIn(ExperimentalTime::class)
object PerfTrace {
    private var startMs: Long = 0
    private var lastMs: Long = 0
    private var label: String = ""

    fun start(flow: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        startMs = now
        lastMs = now
        label = flow
        println("PERF [$flow] START")
    }

    fun mark(step: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val sinceStart = now - startMs
        val sinceLast = now - lastMs
        lastMs = now
        println("PERF [$label] $step +${sinceLast}ms (total ${sinceStart}ms)")
    }
}
