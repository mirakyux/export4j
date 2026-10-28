// SPDX-License-Identifier: Apache-2.0
description = "Explicit optional native OpenType shaping, variable and color fonts"

dependencies {
    api(project(":export4j-layout-core"))
    implementation(project(":export4j-graphics-core"))
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.harfbuzz)
    val nativeClassifier = providers.gradleProperty("export4jNativeClassifier").orElse(
        when {
            System.getProperty("os.name").startsWith("Windows") -> when (System.getProperty("os.arch")) {
                "aarch64" -> "natives-windows-arm64"
                "x86" -> "natives-windows-x86"
                else -> "natives-windows"
            }
            System.getProperty("os.name").startsWith("Mac") -> if (System.getProperty("os.arch") == "aarch64") "natives-macos-arm64" else "natives-macos"
            else -> if (System.getProperty("os.arch") == "aarch64") "natives-linux-arm64" else "natives-linux"
        })
    // Native binaries are a test dependency only. Applications explicitly choose their target classifier.
    testRuntimeOnly("org.lwjgl:lwjgl:${libs.versions.lwjgl.get()}:${nativeClassifier.get()}")
    testRuntimeOnly("org.lwjgl:lwjgl-harfbuzz:${libs.versions.lwjgl.get()}:${nativeClassifier.get()}")
}

sourceSets.test { resources.srcDir(rootProject.file("assets")) }
