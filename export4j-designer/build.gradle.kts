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
tasks.named<ProcessResources>("processResources") {
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
