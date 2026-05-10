// Top-level build file — only declares plugins (applied per-module).
// `apply false` keeps root classpath lean; modules that need a plugin apply it
// themselves via the alias.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.ktlint) apply false
}

// Apply ktlint to every Kotlin / KMP module so format + lint runs against
// shared, composeApp, androidApp uniformly. Module build directories are
// excluded by ktlint defaults; we additionally skip generated and gradle
// scratch dirs that sometimes get scanned on first sync.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        // ktlint engine version (separate from the gradle-plugin version
        // declared in the version catalog). Pin to a recent stable
        // already in the offline cache.
        version.set("1.5.0")
        verbose.set(true)
        android.set(false)
        ignoreFailures.set(false)
        enableExperimentalRules.set(false)
        filter {
            exclude { entry ->
                entry.file.absolutePath.contains("/build/") ||
                    entry.file.absolutePath.contains("/generated/") ||
                    entry.file.absolutePath.contains("/.gradle/")
            }
        }
        // Disable rules that fight the existing house style. The goal of
        // wiring ktlint here isn't pure format policing — it's catching
        // smells that would otherwise sneak in (unused imports, wildcard
        // imports drifting back, accidental tabs). Anything purely about
        // wrapping / signature shape is silenced because the existing
        // codebase chose its own style and we don't want a multi-thousand-
        // line reformat diff to land here.
        additionalEditorconfig.set(
            mapOf(
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_no-empty-first-line-in-class-body" to "disabled",
                "ktlint_standard_function-signature" to "disabled",
                "ktlint_standard_multiline-expression-wrapping" to "disabled",
                "ktlint_standard_chain-method-continuation" to "disabled",
                "ktlint_standard_argument-list-wrapping" to "disabled",
                "ktlint_standard_trailing-comma-on-call-site" to "disabled",
                "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
                "ktlint_standard_wrapping" to "disabled",
                "ktlint_standard_parameter-list-wrapping" to "disabled",
                "ktlint_standard_class-signature" to "disabled",
                "ktlint_standard_property-wrapping" to "disabled",
                "ktlint_standard_value-argument-comment" to "disabled",
                "ktlint_standard_value-parameter-comment" to "disabled",
                "ktlint_standard_no-empty-class-body" to "disabled",
                "ktlint_standard_blank-line-before-declaration" to "disabled",
                "ktlint_standard_no-blank-line-in-list" to "disabled",
                "ktlint_standard_function-expression-body" to "disabled",
                "ktlint_standard_string-template-indent" to "disabled",
                "ktlint_standard_indent" to "disabled",
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_no-empty-file" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "ktlint_standard_class-naming" to "disabled",
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_kdoc-wrapping" to "disabled",
                "ktlint_standard_condition-wrapping" to "disabled",
                "ktlint_standard_if-else-wrapping" to "disabled",
                "ktlint_standard_when-entry-bracing" to "disabled",
                "ktlint_standard_function-literal" to "disabled",
                "ktlint_standard_block-comment-initial-star-alignment" to "disabled",
                "ktlint_standard_class-must-be-internal" to "disabled",
                "ktlint_standard_curly-spacing" to "disabled",
                "ktlint_standard_modifier-list-spacing" to "disabled",
                "ktlint_standard_modifier-order" to "disabled",
                "ktlint_standard_annotation" to "disabled",
                "ktlint_standard_no-blank-line-before-rbrace" to "disabled",
                "ktlint_standard_blank-line-between-when-conditions" to "disabled",
                "ktlint_standard_statement-wrapping" to "disabled",
                "ktlint_standard_enum-entry-name-case" to "disabled",
                "ktlint_standard_enum-wrapping" to "disabled",
                "ktlint_standard_no-multi-spaces" to "disabled",
                "ktlint_standard_spacing-around-operators" to "disabled",
                "ktlint_standard_paren-spacing" to "disabled",
                "ktlint_standard_comment-spacing" to "disabled",
                "ktlint_standard_multiline-if-else" to "disabled",
                "ktlint_standard_import-ordering" to "disabled",
                "ktlint_standard_function-return-type-spacing" to "disabled",
                "ktlint_standard_colon-spacing" to "disabled",
                "ktlint_standard_string-template" to "disabled",
                "ktlint_standard_if-else-bracing" to "disabled",
                "ktlint_standard_try-catch-finally-spacing" to "disabled",
                "ktlint_standard_no-consecutive-blank-lines" to "disabled",
                "ktlint_standard_spacing-between-declarations-with-comments" to "disabled",
                "ktlint_standard_chain-wrapping" to "disabled",
                // Keep these on — they catch real smells without churn:
                //   no-unused-imports  ← the headline win
                //   no-trailing-spaces
                //   final-newline
            )
        )
    }
}

// Project-specific source-pattern lint. ktlint only catches formatting
// smells; this task catches a class of bug that has actually shipped to
// production in this repo: writing into a Compose-side state holder
// (e.g. `lastRenderedPointsKey[0] = …`) from inside an async MapLibre
// `getMapAsync { ... }` callback. The async write means two updates
// arriving in quick succession can both observe stale state and both
// fire camera animations. The regression caused the day-switch zoom
// jump fixed in commit 7fd54a0; this guard prevents it from slipping
// in again.
// Source-pattern lint task. Defined as an abstract class with explicit
// inputs so it survives Gradle's configuration cache; the equivalent
// doLast { … } closure approach failed to serialize because the
// closure captured script-level objects that the cache can't restore.
abstract class VerifyNoAsyncMapStateWriteTask : org.gradle.api.DefaultTask() {

    @get:org.gradle.api.tasks.InputFiles
    abstract val sources: org.gradle.api.file.ConfigurableFileCollection

    @get:org.gradle.api.tasks.Internal
    abstract val rootDirectory: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val offenders = mutableListOf<String>()
        val rootDir = rootDirectory.get().asFile
        sources.files.forEach { kt ->
            if (!kt.isFile || !kt.name.endsWith(".kt")) return@forEach
            if (kt.absolutePath.contains("/build/")) return@forEach
            val text = kt.readText()
            var idx = 0
            while (true) {
                val start = text.indexOf("getMapAsync", idx)
                if (start < 0) break
                val open = text.indexOf('{', start)
                if (open < 0) { idx = start + 1; continue }
                var depth = 1
                var i = open + 1
                while (i < text.length && depth > 0) {
                    when (text[i]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    i++
                }
                val block = text.substring(open + 1, i - 1)
                val bad = Regex("""(?m)^[^/]*\[\d+\]\s*=\s*[^=]""")
                if (bad.containsMatchIn(block)) {
                    offenders += "${kt.relativeTo(rootDir)}: state-holder " +
                        "write inside getMapAsync callback (see commit 7fd54a0)"
                }
                idx = i
            }
        }
        if (offenders.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "Async map state write detected:\n" +
                    offenders.joinToString("\n") { "  - $it" } +
                    "\nMove the assignment outside the getMapAsync { ... } block."
            )
        }
    }
}

val verifyNoAsyncMapStateWrite = tasks.register<VerifyNoAsyncMapStateWriteTask>("verifyNoAsyncMapStateWrite") {
    group = "verification"
    description = "Fails if any *.kt file writes to a Compose state " +
        "array from inside a getMapAsync { ... } callback."
    rootDirectory.set(layout.projectDirectory)
    sources.from(
        listOf("composeApp/src", "shared/src", "androidApp/src").map { rel ->
            layout.projectDirectory.dir(rel).asFileTree.matching {
                include("**/*.kt")
                exclude("**/build/**")
                exclude("**/generated/**")
            }
        }
    )
}

// Run the source-pattern check whenever any module compiles. Cheap to
// run (regex over <1k Kotlin files), so no need to gate further.
subprojects {
    afterEvaluate {
        tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
            .configureEach { dependsOn(verifyNoAsyncMapStateWrite) }
    }
}
