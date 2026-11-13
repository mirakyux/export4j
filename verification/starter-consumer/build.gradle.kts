// SPDX-License-Identifier: Apache-2.0
import java.security.MessageDigest
import java.util.jar.JarFile

plugins { application }

val publicationVersion = providers.gradleProperty("export4jVersion").orElse("0.1.0-SNAPSHOT").get()
layout.buildDirectory.set(file(providers.gradleProperty("consumerBuildDirectory").orElse("build").get()))
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
dependencyLocking { lockAllConfigurations() }

// This is intentionally the entire dependency declaration: no BOM, additional library or native runtime.
dependencies { implementation("com.mirakyux.export4j:export4j-starter:$publicationVersion") }

sourceSets.main {
    java.setSrcDirs(listOf("../consumer/src/main/java"))
    java.include("consumer/EasyExportConsumer.java")
}
application { mainClass.set("consumer.EasyExportConsumer") }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register<JavaExec>("easyExport") {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register("resolvedLibraries") {
    dependsOn(tasks.jar)
    val runtime = configurations.named("runtimeClasspath")
    inputs.files(runtime)
    inputs.file(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    outputs.files(listOf("resolved-libraries.txt", "resolved-library-sha256.txt", "resolved-library-files.txt", "runtime-audit.json")
        .map { layout.buildDirectory.file(it) })
    doLast {
        val configuration = runtime.get()
        val artifacts = configuration.resolvedConfiguration.resolvedArtifacts.sortedBy { it.moduleVersion.id.toString() }
        val expected = setOf("export4j-starter", "export4j-components", "export4j-math", "export4j-adapter-template",
            "export4j-adapter-docx", "export4j-adapter-web", "export4j-backend-pdf", "export4j-font-core",
            "export4j-graphics-api", "export4j-graphics-core", "export4j-layout-core", "export4j-layout-model",
            "export4j-model", "export4j-text-core")
        val own = artifacts.filter { it.moduleVersion.id.group == "com.mirakyux.export4j" }
        check(own.map { it.moduleVersion.id.name }.toSet() == expected) { "Starter transitive project module scope differs" }
        check(own.all { it.moduleVersion.id.version == publicationVersion }) { "Starter modules use mixed versions" }
        check(artifacts.none { it.moduleVersion.id.group == "org.lwjgl" || it.classifier?.startsWith("natives") == true
            || it.moduleVersion.id.name in setOf("export4j-opentype", "export4j-adapter-pdf") }) { "Unrelated native/PDF-edit runtime was included" }
        val direct = configurations.getByName("implementation").dependencies
        check(direct.size == 1 && direct.single().group == "com.mirakyux.export4j"
            && direct.single().name == "export4j-starter" && direct.single().version == publicationVersion) {
            "Single-coordinate consumer acquired an additional direct dependency"
        }
        check(configuration.incoming.resolutionResult.allComponents.none {
            it.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier && it.id != configuration.incoming.resolutionResult.rootComponent.get().id
        }) { "Consumer resolved project source instead of Maven artifacts" }

        fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val lines = artifacts.map { it.moduleVersion.id.toString() }
        layout.buildDirectory.file("resolved-libraries.txt").get().asFile.writeText(lines.joinToString("\n") + "\n")
        layout.buildDirectory.file("resolved-library-sha256.txt").get().asFile.writeText(artifacts.joinToString("\n") {
            "${it.moduleVersion.id}\t${it.file.name}\t${sha(it.file)}"
        } + "\n")
        layout.buildDirectory.file("resolved-library-files.txt").get().asFile.writeText(artifacts.joinToString("\n") {
            "${it.moduleVersion.id}\t${it.file.absolutePath}"
        } + "\n")

        // Resolve each multi-release archive to its effective Java 21 class names before checking providers.
        val providers = linkedMapOf<String, String>()
        val jars = artifacts.map { it.file } + tasks.named<Jar>("jar").get().archiveFile.get().asFile
        var classes = 0
        for (file in jars) {
            JarFile(file).use { jar ->
                val multiRelease = jar.manifest?.mainAttributes?.getValue("Multi-Release")?.equals("true", ignoreCase = true) == true
                val effective = hashMapOf<String, Int>()
                for (entry in jar.entries().asSequence()) {
                    if (entry.isDirectory || !entry.name.endsWith(".class")) { continue }
                    var name = entry.name
                    var version = 0
                    if (name.startsWith("META-INF/versions/")) {
                        if (!multiRelease) { continue }
                        val match = Regex("META-INF/versions/([0-9]+)/(.+)").matchEntire(name) ?: continue
                        version = match.groupValues[1].toInt()
                        if (version < 9 || version > 21) { continue }
                        name = match.groupValues[2]
                    }
                    if (name == "module-info.class" || name.startsWith("META-INF/")) { continue }
                    val previous = effective[name]
                    check(previous != version) { "Duplicate class entry inside ${file.name}: $name" }
                    if (previous == null || version > previous) { effective[name] = version }
                }
                for (name in effective.keys) {
                    val previous = providers.putIfAbsent(name, file.name)
                    check(previous == null) { "Duplicate Java 21 class provider: $name in $previous and ${file.name}" }
                }
                classes += effective.size
            }
        }
        val report = """{"directDependencies":1,"bomDeclared":false,"ownRuntimeModules":${own.size},"runtimeArtifacts":${artifacts.size},"applicationJars":1,"effectiveJava":21,"effectiveClasses":$classes,"duplicateClassProviders":0,"forbiddenNativeOrPdfEditArtifacts":0,"mavenArtifactsOnly":true}"""
        layout.buildDirectory.file("runtime-audit.json").get().asFile.writeText(report + "\n")
        println("Verified starter-only Maven consumer: ${own.size} project modules, ${artifacts.size} runtime artifacts, $classes Java 21 classes")
    }
}
