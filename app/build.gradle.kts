plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.metro)
    // screenshot tests: recordRoborazziDebug writes the reference PNGs under
    // src/test/screenshots, verifyRoborazziDebug (run by CI) compares against them
    alias(libs.plugins.roborazzi)
    // build-logic convention plugin: pins release dependencies to expected-dependencies.txt
    // and merged-manifest permissions to expected-permissions.txt (both verified by check)
    id("release-verification")
}

// derived from self.versions.name in the root build script (see the formula there)
val selfVersionCode: Int by rootProject.extra
val selfIsSnapshot: Boolean by rootProject.extra
val selfAppName: String by rootProject.extra
val selfAppId: String by rootProject.extra

android {
    namespace = "com.episode6.meetingminder"
    // compileSdk is 37 while targetSdk stays at 36: every androidx library we pin
    // (compose 1.12 via the BOM, core 1.19, lifecycle 2.11, navigation 2.10) declares
    // a minCompileSdk of 37 in its aar-metadata, so 36 fails the build outright.
    // Compiling against 37 changes nothing at runtime — targetSdk is what opts the app
    // into new platform behaviour, and that stays where the spec puts it.
    compileSdk = 37

    buildFeatures {
        compose = true
        // for the snapshot-aware app_name resValue in defaultConfig
        resValues = true
    }

    defaultConfig {
        // snapshot builds (everything except CI release-tag builds) get their own
        // applicationId and launcher label so they can be installed side-by-side with
        // the released app instead of overwriting it (the namespace above stays fixed,
        // so R + manifest class refs are unaffected)
        applicationId = selfAppId
        resValue("string", "app_name", selfAppName)
        // "Check for updates" just opens a browser page — this app must never request
        // the INTERNET permission, so there is no in-app update check. Snapshots point
        // at the main-branch commit log, releases at the latest GitHub release.
        resValue(
            "string", "check_for_updates_url",
            if (selfIsSnapshot) "https://github.com/episode6/meeting-minder/commits/main/"
            else "https://github.com/episode6/meeting-minder/releases/latest",
        )
        // snapshot builds keep the launcher foreground but swap the episode6-orange
        // background for dark charcoal, so the two installs are distinguishable at a
        // glance; placeholders resolve at manifest merge, so lint + resource shrinking
        // still see the concrete @mipmap reference per build
        manifestPlaceholders["appIcon"] =
            if (selfIsSnapshot) "@mipmap/ic_launcher_snapshot" else "@mipmap/ic_launcher"
        manifestPlaceholders["appIconRound"] =
            if (selfIsSnapshot) "@mipmap/ic_launcher_round_snapshot" else "@mipmap/ic_launcher_round"
        // minSdk 31: exact-alarm permissions, full-screen-intent behaviour and
        // VibrationAttributes all start at 31, and staying there removes a pile of
        // version branches from the alarm code (see AGENTS.md)
        minSdk = 31
        targetSdk = 36
        versionCode = selfVersionCode
        versionName = self.versions.name.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // the debug keystore is committed to the repo (standard debug credentials,
            // not a secret) so CI-built and local debug APKs share a signature and can
            // overwrite each other on-device instead of failing with a signature mismatch
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // CI decodes the ANDROID_KEYSTORE secret to a file and exports these
            // env vars; without them (local builds, PR CI) release stays unsigned
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                // a half-configured keystore would otherwise fail deep inside the
                // signing task with an opaque error
                storePassword = requireNotNull(System.getenv("ANDROID_KEYSTORE_ROOT_PASSWORD")) {
                    "ANDROID_KEYSTORE_PATH is set but ANDROID_KEYSTORE_ROOT_PASSWORD is not"
                }
                keyAlias = "episode6"
                keyPassword = requireNotNull(System.getenv("ANDROID_KEYSTORE_KEY_PASSWORD")) {
                    "ANDROID_KEYSTORE_PATH is set but ANDROID_KEYSTORE_KEY_PASSWORD is not"
                }
            }
        }
    }
    buildTypes {
        debug {
            // debug installs get their own id so they don't clobber (or get blocked by
            // a signature mismatch with) an installed CI-built snapshot APK
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 runs in full mode by default on AGP 8+; keep rules live in proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric (screenshot tests) needs merged resources to render real screens
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            // JDK 17+ module encapsulation: Robolectric's FileDescriptor interceptor (hit
            // while setting up the API 36 application state) calls jdk.internal.access
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

// Room schemas are exported (unlike headache-tracker) so migrations stay reviewable in
// the diff; the generated JSON lands in app/schemas/ once the database exists.
room {
    schemaDirectory("$projectDir/schemas")
}

// LicenseNotices.kt embeds THIRD_PARTY_LICENSES.md so the in-app licenses screen always
// shows the same document the repo ships
abstract class GenerateLicenseNoticesTask : DefaultTask() {
    @get:InputFile
    abstract val noticesFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val escaped = noticesFile.get().asFile.readText()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\r", "")
            .replace("\n", "\\n")
        val outFile = outDir.get().file("com/episode6/meetingminder/LicenseNotices.kt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |package com.episode6.meetingminder
            |
            |/** Generated from THIRD_PARTY_LICENSES.md at build time; do not edit. */
            |object LicenseNotices {
            |    const val MARKDOWN: String = "$escaped"
            |}
            |""".trimMargin()
        )
    }
}

val generateLicenseNotices = tasks.register<GenerateLicenseNoticesTask>("generateLicenseNotices") {
    noticesFile.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_LICENSES.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generateLicenseNotices,
            GenerateLicenseNoticesTask::outDir,
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.metro.runtime)
    implementation(libs.metrox.viewmodel)
    implementation(libs.metrox.viewmodel.compose)
    implementation(libs.redux.compose)
    implementation(libs.redux.side.effects)
    implementation(libs.redux.store.flow)
    implementation(libs.redux.subscriber.aware)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.assertk)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.redux.test.support)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.turbine)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.assertk)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.test.rules)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "ksp"(libs.androidx.room.compiler)
}
