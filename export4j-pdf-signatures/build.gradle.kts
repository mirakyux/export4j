// SPDX-License-Identifier: Apache-2.0
description = "PDF signing, signature verification and PAdES trust evidence"

dependencies {
    implementation(project(":export4j-model"))
    implementation(libs.pdfbox)
    implementation(libs.bcpkix)
    testImplementation(project(":export4j-backend-pdf"))
    testImplementation(project(":export4j-graphics-core"))
}

sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}
