// SPDX-License-Identifier: Apache-2.0
description = "Reusable page, drawing and document interaction components"
dependencies {
    api(project(":export4j-layout-core"))
    implementation(project(":export4j-graphics-core"))
}
sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}
