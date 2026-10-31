// SPDX-License-Identifier: Apache-2.0
plugins { application }

description = "Optional offline XML template designer and runnable business scenarios"

dependencies {
    implementation(project(":export4j-components"))
    implementation(project(":export4j-adapter-template"))
    implementation(project(":export4j-adapter-docx"))
    implementation(project(":export4j-adapter-web"))
    implementation(project(":export4j-backend-pdf"))
    implementation(project(":export4j-backend-svg"))
    implementation(project(":export4j-backend-raster"))
}
application { mainClass.set("com.mirakyux.export4j.designer.DesignerApp") }

val frontendDirectory = layout.projectDirectory.dir("frontend")
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd", "/c", "npm") else listOf("npm")
val frontendInstall by tasks.registering(Exec::class) {
    workingDir(frontendDirectory)
    commandLine(npmCommand + listOf("ci", "--ignore-scripts", "--no-audit", "--no-fund"))
    inputs.files(frontendDirectory.file("package.json"), frontendDirectory.file("package-lock.json"))
    outputs.dir(frontendDirectory.dir("node_modules"))
}
val frontendBuild by tasks.registering(Exec::class) {
    dependsOn(frontendInstall)
    workingDir(frontendDirectory)
    commandLine(npmCommand + listOf("run", "build"))
    inputs.dir(frontendDirectory.dir("src"))
    inputs.files(frontendDirectory.file("build.mjs"), frontendDirectory.file("tsconfig.json"), frontendDirectory.file("package-lock.json"))
    outputs.dir(layout.buildDirectory.dir("frontend"))
}
tasks.named<ProcessResources>("processResources") {
    dependsOn(frontendBuild)
    from(layout.buildDirectory.dir("frontend")) { into("designer/web") }
    from(rootProject.file("assets/fonts")) {
        include("LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf", "ZCOOLXiaoWei-Regular.ttf",
            "LiberationSans-OFL.txt", "ZCOOLXiaoWei-OFL.txt")
        into("designer/fonts")
    }
}
tasks.register<JavaExec>("scenarios") {
    group = "verification"
    description = "Generates editable business templates, six export formats and measured cost/failure evidence."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.designer.DesignerScenarios")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
    args(providers.gradleProperty("designerOutputDirectory").map { rootProject.file(it).absolutePath }
        .getOrElse(layout.buildDirectory.dir("scenarios").get().asFile.absolutePath))
}
