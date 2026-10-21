// SPDX-License-Identifier: Apache-2.0
description = "Simple horizontal text measurement and paragraph line layout"
dependencies {
    api(project(":export4j-font-core"))
    api(project(":export4j-model"))
}
sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}
