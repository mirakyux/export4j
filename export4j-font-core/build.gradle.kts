// SPDX-License-Identifier: Apache-2.0
description = "Immutable TrueType resources, glyph mappings and metrics"
dependencies {
    implementation(libs.fontbox)
    implementation(libs.pdfbox.io)
}
sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}
