// SPDX-License-Identifier: Apache-2.0
description = "FontBox implementation of standalone TrueType and CFF parsing"
dependencies {
    implementation(project(":export4j-font-core"))
    implementation(libs.fontbox)
    implementation(libs.pdfbox.io)
    implementation("commons-logging:commons-logging:1.4.0")
}
