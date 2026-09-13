package com.episode6.meetingminder.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.SortedSet

internal fun readAllowlist(file: File): SortedSet<String> = file.readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .toSortedSet()

internal fun StringBuilder.appendDiff(actual: Set<String>, expected: Set<String>) {
    (actual - expected).forEach { appendLine("  unexpected: $it") }
    (expected - actual).forEach { appendLine("  missing:    $it") }
}

/**
 * Fails unless the release APK's transitive runtime dependency set (group:artifact)
 * exactly matches the checked-in allowlist, so a new library — direct or transitive —
 * can't ship without a deliberate review: every artifact needs a
 * THIRD_PARTY_LICENSES.md entry, and none may pull network code into an app that must
 * stay fully offline.
 */
abstract class VerifyAppDependenciesTask : DefaultTask() {
    @get:Input
    abstract val actualDependencies: SetProperty<String>

    @get:InputFile
    abstract val expectedFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val expected = readAllowlist(expectedFile.get().asFile)
        val actual = actualDependencies.get().toSortedSet()
        if (expected == actual) return
        throw GradleException(buildString {
            appendLine("Release dependencies don't match ${expectedFile.get().asFile.name}.")
            appendDiff(actual, expected)
            appendLine("If this change is intentional, run ./gradlew :app:writeExpectedDependencies")
            appendLine("and update THIRD_PARTY_LICENSES.md to match.")
        })
    }
}

/** Regenerates the dependency allowlist that [VerifyAppDependenciesTask] checks. */
abstract class WriteExpectedAppDependenciesTask : DefaultTask() {
    @get:Input
    abstract val actualDependencies: SetProperty<String>

    @get:OutputFile
    abstract val expectedFile: RegularFileProperty

    @TaskAction
    fun write() {
        expectedFile.get().asFile.writeText(
            "# Transitive runtime dependencies of the release APK (group:artifact).\n" +
                "# Verified by :app:verifyReleaseDependencies (runs with `check`); regenerate\n" +
                "# with :app:writeExpectedDependencies and keep THIRD_PARTY_LICENSES.md in sync.\n" +
                actualDependencies.get().toSortedSet().joinToString("\n", postfix = "\n")
        )
    }
}

/**
 * Guards the no-network product spec at the artifact level: the merged release
 * manifest must declare exactly the permissions in the checked-in allowlist, so
 * neither our own manifest nor a library's can (re)introduce INTERNET.
 */
abstract class VerifyAppPermissionsTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    abstract val expectedFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val actual = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"")
            .findAll(mergedManifest.get().asFile.readText())
            .map { it.groupValues[1] }
            // androidx-core auto-defines this app-private signature permission (named
            // after the applicationId) to sandbox unexported dynamic receivers; it
            // grants no capability, so it's exempt from the allowlist
            .filterNot { it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
            .toSortedSet()
        val expected = readAllowlist(expectedFile.get().asFile)
        if (expected == actual) return
        throw GradleException(buildString {
            appendLine("Merged release manifest permissions don't match ${expectedFile.get().asFile.name}.")
            appendDiff(actual, expected)
            appendLine("This app must stay fully offline (never add INTERNET — see AGENTS.md);")
            appendLine("deliberate permission changes update app/expected-permissions.txt.")
        })
    }
}
