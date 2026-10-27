// SPDX-License-Identifier: Apache-2.0
description = "Bounded offline PDF inspection, text extraction and static page composition"

dependencies {
    api(project(":export4j-model"))
    api(project(":export4j-adapter-template"))
    implementation(libs.pdfbox)
    implementation(libs.fontbox)
}
