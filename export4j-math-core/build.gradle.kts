// SPDX-License-Identifier: Apache-2.0
description = "Bounded TeX vector outlines and explicit formula fonts"

val jlatexmathSources by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    api(project(":export4j-math-api"))
    api(project(":export4j-model"))
    implementation(project(":export4j-font-core"))
    runtimeOnly(project(":export4j-font-fontbox"))
    implementation(libs.jlatexmath) { isTransitive = false }
    add(jlatexmathSources.name, "org.scilab.forge:jlatexmath:${libs.versions.jlatexmath.get()}:sources")
}

sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}

// Include the corresponding upstream source with the implementation that uses it.
tasks.named<Jar>("jar") {
    from(jlatexmathSources) { into("META-INF/third-party/source") }
    from(rootProject.file("third-party/jlatexmath-source-build")) {
        into("META-INF/third-party/source/build")
    }
}
