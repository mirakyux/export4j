// SPDX-License-Identifier: Apache-2.0
plugins { application }
layout.buildDirectory.set(layout.projectDirectory.dir(providers.gradleProperty("consumerBuildDirectory").orElse("build").get()))
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
dependencies {
    implementation(platform("com.mirakyux.export4j:export4j-bom:0.1.0-SNAPSHOT"))
    implementation("com.mirakyux.export4j:export4j-components")
    implementation("com.mirakyux.export4j:export4j-backend-pdf")
    implementation("com.mirakyux.export4j:export4j-backend-svg")
    implementation("com.mirakyux.export4j:export4j-backend-raster")
    implementation("com.mirakyux.export4j:export4j-adapter-docx")
    implementation("com.mirakyux.export4j:export4j-adapter-template")
    implementation("com.mirakyux.export4j:export4j-adapter-pdf")
    implementation("com.mirakyux.export4j:export4j-adapter-web")
}
application { mainClass.set("consumer.Consumer") }

dependencyLocking { lockAllConfigurations() }
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
tasks.named<JavaExec>("run") {
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}
tasks.register<JavaExec>("pdfFontCompatibility") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.PdfFontCompatibilityConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("xmlRegionSaving") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.XmlRegionSavingConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("xmlModelSaving") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.XmlModelSavingConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("xmlTemplateBundle") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.XmlTemplateBundleConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}
tasks.register("resolvedLibraries") {
    val runtime = configurations.named("runtimeClasspath")
    inputs.files(runtime)
    outputs.file(layout.buildDirectory.file("resolved-libraries.txt"))
    doLast {
        val ids = runtime.get().resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.toString() }.distinct().sorted()
        layout.buildDirectory.file("resolved-libraries.txt").get().asFile.writeText(ids.joinToString("\n") + "\n")
    }
}
