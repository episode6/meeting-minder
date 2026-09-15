// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.metro) apply false
    alias(libs.plugins.roborazzi) apply false
}

// snapshot unless CI is building from a release tag (GITHUB_REF=refs/tags/v*);
// local and branch/PR builds are always snapshots
val selfIsSnapshot: Boolean by extra(System.getenv("GITHUB_REF")?.startsWith("refs/tags/v") != true)

// snapshot builds carry their own app identity so they install side-by-side with
// release builds instead of overwriting them. selfAppName is the user-facing display
// name, selfAppId the android applicationId. (Debug builds additionally append a
// .debug applicationIdSuffix — see app/build.gradle.kts — so they coexist with
// installed snapshot APKs too.)
val selfAppName: String by extra(if (selfIsSnapshot) "Meeting Minder (SNAPSHOT)" else "Meeting Minder")
val selfAppId: String by extra(if (selfIsSnapshot) "com.episode6.meetingminder.snapshot" else "com.episode6.meetingminder")

// The version name in self.versions.toml is the single source of truth: MAJOR.MINOR.PATCH.
// Cutting a release branch bumps the patch by 10, so regular releases land on multiples
// of 10 and the 9 values in between are reserved for hotfixing that release (1.2.30 ->
// hotfixes 1.2.31-1.2.39, next release 1.2.40). The android versionCode is derived via
// mixed radix — (major * 256 + minor) * 10000 + patch — so newer versions always outrank
// older ones and hotfixes need no versionCode coordination with main. Major/minor cap at
// 255 and patch at 9999 (limits inherited from the episode6 app-repo schema, where they
// match Windows MSI's ProductVersion caps), making the highest possible code
// 255.255.9999 -> 655,359,999, well under Google Play's 2,100,000,000 versionCode cap.
// This is the single source of truth for the formula: release tooling
// (scripts/ship-release.py) queries it via the printReleaseVersionCode task instead of
// reimplementing it.
//
// Snapshot builds instead derive their versionCode from git: the commit count at HEAD's
// merge-base with main. Snapshots install under their own applicationId, so their codes
// never compete with release codes; builds from main carry a strictly growing code (a
// newer main snapshot always installs over an older one), while branch/PR builds are
// locked to their closest main ancestor's code so a later main build can install right
// over them. This needs full git history — CI checkouts set fetch-depth: 0, and a
// shallow clone is rejected rather than silently under-counting.
fun git(vararg args: String): String = providers.exec {
    workingDir(rootDir)
    commandLine("git", *args)
}.standardOutput.asText.get().trim()
val gitSnapshotVersionCode: Int by lazy {
    require(git("rev-parse", "--is-shallow-repository") == "false") {
        "snapshot versionCode is derived from git commit count, which a shallow clone " +
            "would under-count — fetch full history (CI: fetch-depth: 0)"
    }
    val mainRef = listOf("origin/main", "main").firstOrNull { ref ->
        providers.exec {
            workingDir(rootDir)
            commandLine("git", "rev-parse", "--verify", "--quiet", "$ref^{commit}")
            isIgnoreExitValue = true
        }.result.get().exitValue == 0
    }
    requireNotNull(mainRef) { "snapshot versionCode needs a main ref (origin/main or main) to merge-base against" }
    git("rev-list", "--count", git("merge-base", mainRef, "HEAD")).toInt()
}
val selfVersionName: String = self.versions.name.get()
val selfReleaseVersionCode: Int = run {
    val segments = selfVersionName.split(".")
    require(segments.size == 3) { "version name '$selfVersionName' must be MAJOR.MINOR.PATCH" }
    val nums = segments.map { segment ->
        requireNotNull(segment.toIntOrNull()?.takeIf { it >= 0 }) {
            "version name '$selfVersionName' has a non-numeric segment '$segment'"
        }
    }
    val (major, minor, patch) = nums
    require(major >= 1) { "major version must be >= 1 (inherited from the episode6 app-repo schema)" }
    require(major <= 255) { "major version maxes out at 255 (got '$selfVersionName')" }
    require(minor <= 255) { "minor version maxes out at 255 (got '$selfVersionName')" }
    require(patch <= 9999) { "patch version maxes out at 9999 (got '$selfVersionName')" }
    (major * 256 + minor) * 10000 + patch
}

// the name is validated on every build, but only release-tag builds carry its code
val selfVersionCode: Int by extra(if (selfIsSnapshot) gitSnapshotVersionCode else selfReleaseVersionCode)

// query task for the release tooling (use with -q and take the last output line);
// printReleaseVersionCode reports the formula-derived code regardless of snapshot-ness
tasks.register("printReleaseVersionCode") {
    val code = selfReleaseVersionCode
    doLast { println(code) }
}
