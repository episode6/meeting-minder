plugins {
    `kotlin-dsl`
}

dependencies {
    // resolved together with the main build's plugin classpath (same version via the
    // shared catalog), so AGP loads once in the same classloader scope as the Kotlin
    // compiler plugins — unlike buildSrc, whose parent classloader broke Metro codegen
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
}
