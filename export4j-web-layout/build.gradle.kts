// SPDX-License-Identifier: Apache-2.0
description = "Optional runtime CSS box layout for semantic HTML import"
dependencies {
    api(project(":export4j-adapter-web"))
    implementation(project(":export4j-components"))
    implementation(project(":export4j-graphics-core"))
}
sourceSets.test { resources.srcDir(rootProject.file("assets")) }
