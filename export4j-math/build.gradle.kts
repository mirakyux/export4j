// SPDX-License-Identifier: Apache-2.0
description = "Optional bounded TeX mathematics and Markdown equations"

val jlatexmathSources by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    api(project(":export4j-model"))
    api(project(":export4j-adapter-docx"))
    api(project(":export4j-adapter-web"))
    implementation(project(":export4j-font-core"))
    implementation(project(":export4j-graphics-api"))
    implementation(libs.jlatexmath) { isTransitive = false }
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    testImplementation(project(":export4j-adapter-template"))
    add(jlatexmathSources.name, "org.scilab.forge:jlatexmath:${libs.versions.jlatexmath.get()}:sources")
}

sourceSets.test {
    resources.srcDir(rootProject.file("assets"))
}

// Ship corresponding upstream source beside every published binary, including Maven consumers.
// This is source material, not shaded/replaced classes or another runtime dependency.
tasks.named<Jar>("jar") {
    from(jlatexmathSources) { into("META-INF/third-party/source") }
    from(rootProject.file("third-party/jlatexmath-source-build")) {
        into("META-INF/third-party/source/build")
    }
}
