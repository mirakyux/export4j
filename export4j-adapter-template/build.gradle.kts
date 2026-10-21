// SPDX-License-Identifier: Apache-2.0
description = "Strict offline declarative XML templates compiled to the semantic document model"
dependencies {
    api(project(":export4j-model"))
    testImplementation(project(":export4j-components"))
    testImplementation(project(":export4j-adapter-docx"))
}
sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}
