// SPDX-License-Identifier: Apache-2.0
description = "Bounded paragraph flow pagination and conversion to drawing commands"
dependencies {
    api(project(":export4j-layout-model"))
    implementation(project(":export4j-graphics-core"))
}
sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}
