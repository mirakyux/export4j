// SPDX-License-Identifier: Apache-2.0
description = "Explicit optional native OpenType shaping, variable and color fonts"

dependencies {
    api(project(":export4j-layout-core"))
    implementation(project(":export4j-graphics-core"))
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.harfbuzz)
    val nativeClassifier = rootProject.extra["export4jNativeClassifierResolved"] as String
    // Native binaries are a test dependency only. Applications explicitly choose their target classifier.
    testRuntimeOnly("org.lwjgl:lwjgl:${libs.versions.lwjgl.get()}:$nativeClassifier")
    testRuntimeOnly("org.lwjgl:lwjgl-harfbuzz:${libs.versions.lwjgl.get()}:$nativeClassifier")
}

sourceSets.test { resources.srcDir(rootProject.file("assets")) }
