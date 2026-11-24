// SPDX-License-Identifier: Apache-2.0
description = "Explicit optional native OpenType shaping, variable and color fonts"

dependencies {
    api(project(":export4j-graphics-api"))
    implementation(project(":export4j-graphics-core"))
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.harfbuzz)
}
