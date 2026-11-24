// SPDX-License-Identifier: Apache-2.0
description = "Immutable TrueType resources, glyph mappings and metrics"
dependencies {
    testRuntimeOnly(project(":export4j-font-fontbox"))
}
sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}
