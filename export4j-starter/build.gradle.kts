// SPDX-License-Identifier: Apache-2.0
description = "Configured-once source and template export to PDF and DOCX"

dependencies {
    api(project(":export4j-components"))
    api(project(":export4j-math"))
    api(project(":export4j-adapter-template"))
    api(project(":export4j-backend-pdf"))
    api(project(":export4j-font-core"))
    api(project(":export4j-adapter-docx"))
    testImplementation(libs.pdfbox)
}

sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}
