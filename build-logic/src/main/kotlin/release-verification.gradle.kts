import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.episode6.meetingminder.build.VerifyAppDependenciesTask
import com.episode6.meetingminder.build.VerifyAppPermissionsTask
import com.episode6.meetingminder.build.WriteExpectedAppDependenciesTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

// Pins the release APK's exact transitive dependency set to expected-dependencies.txt
// and the merged release manifest's permissions to expected-permissions.txt (both in
// the applying module's directory), then wires the verifications into `check` so CI
// fails on any undeliberate change — a library or manifest merge can never introduce
// network access unnoticed. Apply after com.android.application.

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        if (variant.name != "release") return@onVariants

        // resolution happens lazily at execution time via the rootComponent provider
        val dependencies = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
            .map { root ->
                val seen = LinkedHashSet<ResolvedComponentResult>()
                val queue = ArrayDeque(listOf(root))
                while (queue.isNotEmpty()) {
                    val component = queue.removeFirst()
                    if (seen.add(component)) {
                        queue.addAll(
                            component.dependencies
                                .filterIsInstance<ResolvedDependencyResult>()
                                .map { it.selected }
                        )
                    }
                }
                seen.mapNotNull { component ->
                    (component.id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}" }
                }.toSortedSet()
            }
        val expectedDependenciesFile = layout.projectDirectory.file("expected-dependencies.txt")

        val writeExpectedDependencies =
            tasks.register<WriteExpectedAppDependenciesTask>("writeExpectedDependencies") {
                actualDependencies.set(dependencies)
                expectedFile.set(expectedDependenciesFile)
            }
        val verifyDependencies =
            tasks.register<VerifyAppDependenciesTask>("verifyReleaseDependencies") {
                actualDependencies.set(dependencies)
                expectedFile.set(expectedDependenciesFile)
                // the verify input is the write task's output; ordering keeps a combined
                // `writeExpectedDependencies check` invocation valid (and verifying the
                // freshly written file)
                mustRunAfter(writeExpectedDependencies)
            }
        val verifyPermissions =
            tasks.register<VerifyAppPermissionsTask>("verifyReleasePermissions") {
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                expectedFile.set(layout.projectDirectory.file("expected-permissions.txt"))
            }
        tasks.named("check") { dependsOn(verifyDependencies, verifyPermissions) }
    }
}
