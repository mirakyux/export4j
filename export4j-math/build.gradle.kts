// SPDX-License-Identifier: Apache-2.0
description = "Rich source mathematics with coordinated visual and Word projections"

dependencies {
    api(project(":export4j-math-docx"))
    api(project(":export4j-math-web"))
    testImplementation(project(":export4j-adapter-template"))
}

sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}
