// SPDX-License-Identifier: Apache-2.0
description = "PDF output implementation using Apache PDFBox"
dependencies {
    api(project(":export4j-graphics-api"))
    implementation(libs.pdfbox)
    implementation(libs.bcpkix)
    testImplementation(project(":export4j-graphics-core"))
    testImplementation(project(":export4j-layout-core"))
    testImplementation(project(":export4j-components"))
}
sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}
