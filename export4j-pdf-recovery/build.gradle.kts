// SPDX-License-Identifier: Apache-2.0
description = "PDF embedded semantic model recovery"

dependencies {
    api(project(":export4j-adapter-template"))
    implementation(project(":export4j-adapter-pdf"))
    implementation(libs.pdfbox)
}
