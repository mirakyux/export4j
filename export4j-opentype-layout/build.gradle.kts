// SPDX-License-Identifier: Apache-2.0
description = "Pageable OpenType text and vertical flow"

dependencies {
    api(project(":export4j-opentype"))
    api(project(":export4j-layout-core"))
    testRuntimeOnly(project(":export4j-font-fontbox"))
    @Suppress("UNCHECKED_CAST")
    val classifier = rootProject.extra["export4jNativeClassifier"] as Provider<String>
    val lwjglVersion = libs.versions.lwjgl.get()
    addProvider("testRuntimeOnly", classifier.map { "org.lwjgl:lwjgl:$lwjglVersion:$it" })
    addProvider("testRuntimeOnly", classifier.map { "org.lwjgl:lwjgl-harfbuzz:$lwjglVersion:$it" })
}

sourceSets.test { resources.srcDir(rootProject.file("assets")) }
