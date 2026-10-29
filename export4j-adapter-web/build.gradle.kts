// SPDX-License-Identifier: Apache-2.0
description = "Bounded semantic HTML and Markdown adapters with explicit unsupported-content actions"
dependencies {
    api(project(":export4j-model"))
    implementation(project(":export4j-components"))
    implementation(project(":export4j-graphics-core"))
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.jsoup)
}
sourceSets.test { resources.srcDir(rootProject.file("assets")) }
